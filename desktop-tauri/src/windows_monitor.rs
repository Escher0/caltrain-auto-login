// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

//! Native Windows network monitoring. No shell child process.
use caltrain_wifi::network::{evaluate, Profile, Snapshot};
use std::{
    net::Ipv4Addr,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc,
    },
    thread,
    time::Duration,
};
use windows::{
    core::Result,
    Networking::Connectivity::{NetworkInformation, NetworkStatusChangedEventHandler},
    Win32::{
        NetworkManagement::{
            IpHelper::{
                FreeMibTable, GetIfTable2, GetUnicastIpAddressTable, MIB_IF_TABLE2,
                MIB_UNICASTIPADDRESS_TABLE,
            },
            Ndis::{IfOperStatusUp, NdisPhysicalMediumNative802_11, NdisPhysicalMediumWirelessLan},
        },
        Networking::{
            NetworkListManager::{
                INetworkListManager, NetworkListManager, NLM_CONNECTIVITY_IPV4_INTERNET,
                NLM_CONNECTIVITY_IPV6_INTERNET, NLM_ENUM_NETWORK_CONNECTED,
            },
            WinSock::{IpDadStatePreferred, AF_INET},
        },
        System::{
            Com::{CoCreateInstance, CLSCTX_ALL},
            WinRT::{RoInitialize, RoUninitialize, RO_INIT_MULTITHREADED},
        },
    },
};

struct Apartment;
impl Drop for Apartment {
    fn drop(&mut self) {
        unsafe { RoUninitialize() }
    }
}

struct InterfaceTable(*mut MIB_IF_TABLE2);
impl Drop for InterfaceTable {
    fn drop(&mut self) {
        unsafe { FreeMibTable(self.0.cast()) }
    }
}

struct Subscription(i64);
impl Drop for Subscription {
    fn drop(&mut self) {
        let _ = NetworkInformation::RemoveNetworkStatusChanged(self.0);
    }
}

struct AddressTable(*mut MIB_UNICASTIPADDRESS_TABLE);
impl Drop for AddressTable {
    fn drop(&mut self) {
        unsafe { FreeMibTable(self.0.cast()) }
    }
}

fn snapshot() -> Result<Snapshot> {
    let mut table = std::ptr::null_mut();
    // GetIfTable2 owns the variable-sized table until FreeMibTable. Copy all
    // properties while the RAII guard is alive; never retain a row reference.
    unsafe {
        GetIfTable2(&mut table).ok()?;
    }
    let table = InterfaceTable(table);
    let rows = unsafe {
        std::slice::from_raw_parts((*table.0).Table.as_ptr(), (*table.0).NumEntries as usize)
    };
    let mut addresses = std::ptr::null_mut();
    unsafe {
        GetUnicastIpAddressTable(AF_INET, &mut addresses).ok()?;
    }
    let addresses = AddressTable(addresses);
    let address_rows = unsafe {
        std::slice::from_raw_parts(
            (*addresses.0).Table.as_ptr(),
            (*addresses.0).NumEntries as usize,
        )
    };
    let mut profiles = Vec::new();
    // NLM enumerates connected networks, including captive/local-only ones.
    // WinRT GetConnectionProfiles includes saved networks, and in Windows testing its
    // adapter GetConnectedProfileAsync attributed the default Wi-Fi to the VPN.
    // Use WinRT for notifications only; NLM supplies the actual current state.
    let manager: INetworkListManager =
        unsafe { CoCreateInstance(&NetworkListManager, None, CLSCTX_ALL)? };
    let networks = unsafe { manager.GetNetworks(NLM_ENUM_NETWORK_CONNECTED)? };
    loop {
        let mut next = [None];
        unsafe {
            networks.Next(&mut next, None)?;
        }
        let Some(network) = next[0].take() else {
            break;
        };
        let name = unsafe { network.GetName()?.to_string() };
        let connections = unsafe { network.GetNetworkConnections()? };
        loop {
            let mut next = [None];
            unsafe {
                connections.Next(&mut next, None)?;
            }
            let Some(connection) = next[0].take() else {
                break;
            };
            if unsafe { connection.IsConnected()? }.0 == 0 {
                continue;
            }
            let adapter_id = unsafe { connection.GetAdapterId()? };
            let connectivity = unsafe { connection.GetConnectivity()? };
            let row = rows.iter().find(|row| row.InterfaceGuid == adapter_id);
            let physical_wifi_up = row.is_some_and(|row| {
                // HardwareInterface is bit 0 of InterfaceAndOperStatusFlags in
                // Microsoft's MIB_IF_ROW2 declaration (netioapi.h).
                row.InterfaceAndOperStatusFlags._bitfield & 1 != 0
                    && row.OperStatus == IfOperStatusUp
                    && (row.PhysicalMediumType == NdisPhysicalMediumWirelessLan
                        || row.PhysicalMediumType == NdisPhysicalMediumNative802_11)
            });
            profiles.push(Profile {
                name: name.clone(),
                interface_id: format!("{adapter_id:?}"),
                physical_wifi_up,
                ipv4: row
                    .and_then(|interface| {
                        address_rows.iter().find(|a| {
                            a.InterfaceIndex == interface.InterfaceIndex
                                && a.DadState == IpDadStatePreferred
                                && !a.SkipAsSource
                        })
                    })
                    .map(|a| {
                        let bytes = unsafe { a.Address.Ipv4.sin_addr.S_un.S_un_b };
                        Ipv4Addr::new(bytes.s_b1, bytes.s_b2, bytes.s_b3, bytes.s_b4)
                    })
                    .filter(|ip| !ip.is_loopback() && !ip.is_unspecified() && !ip.is_link_local()),
                internet: connectivity.0
                    & (NLM_CONNECTIVITY_IPV4_INTERNET.0 | NLM_CONNECTIVITY_IPV6_INTERNET.0)
                    != 0,
            });
        }
    }
    profiles.sort_by(|a, b| {
        a.interface_id
            .cmp(&b.interface_id)
            .then(a.name.cmp(&b.name))
    });
    Ok(evaluate(profiles))
}

pub struct Monitor {
    sender: mpsc::Sender<&'static str>,
    stopped: Arc<AtomicBool>,
}
impl Monitor {
    pub fn refresh(&self) {
        let _ = self.sender.send("manual-refresh");
    }
}
impl Drop for Monitor {
    fn drop(&mut self) {
        self.stopped.store(true, Ordering::SeqCst);
        let _ = self.sender.send("shutdown");
    }
}

pub fn start(
    report: impl Fn(&str, std::result::Result<Snapshot, String>) + Send + 'static,
) -> Monitor {
    let (sender, receiver) = mpsc::channel();
    let event_sender = sender.clone();
    let stopped = Arc::new(AtomicBool::new(false));
    let thread_stopped = stopped.clone();
    thread::spawn(move || {
        let result = (|| -> Result<()> {
            unsafe {
                RoInitialize(RO_INIT_MULTITHREADED)?;
            }
            let _apartment = Apartment;
            let callback = NetworkStatusChangedEventHandler::new(move |_| {
                let _ = event_sender.send("network-status-changed");
                Ok(())
            });
            let _subscription = Subscription(NetworkInformation::NetworkStatusChanged(&callback)?);
            report("initial", snapshot().map_err(|e| e.to_string()));
            while !thread_stopped.load(Ordering::SeqCst) {
                let reason = match receiver.recv_timeout(Duration::from_secs(60)) {
                    Ok(reason) => reason,
                    Err(mpsc::RecvTimeoutError::Timeout) => "periodic-refresh",
                    Err(mpsc::RecvTimeoutError::Disconnected) => break,
                };
                if thread_stopped.load(Ordering::SeqCst) {
                    break;
                }
                // Coalesce bursts without polling the network when idle.
                thread::sleep(Duration::from_millis(250));
                while receiver.try_recv().is_ok() {}
                report(reason, snapshot().map_err(|e| e.to_string()));
            }
            Ok(())
        })();
        if let Err(error) = result {
            report("monitor-error", Err(error.to_string()));
        }
    });
    Monitor { sender, stopped }
}
