use crate::network::{evaluate, Profile, Snapshot};
use std::{net::Ipv4Addr, process::Stdio, time::Duration};
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    process::Command,
    sync::mpsc,
};
use tokio_util::sync::CancellationToken;

pub fn connected_wifi(output: &str) -> Vec<&str> {
    output
        .lines()
        .filter_map(|line| {
            let mut parts = line.splitn(3, ':');
            let device = parts.next()?;
            (parts.next()? == "wifi" && parts.next()? == "connected").then_some(device)
        })
        .collect()
}
pub fn has_caltrain(ap_list: &str) -> bool {
    ap_list.lines().any(|line| line == "*:Caltrain_WiFi")
}
pub fn route_matches(json: &str, device: &str) -> bool {
    serde_json::from_str::<serde_json::Value>(json)
        .ok()
        .and_then(|v| v.as_array().cloned())
        .is_some_and(|routes| routes.len() == 1 && routes[0]["dev"] == device)
}
async fn command(program: &str, args: &[&str]) -> Result<String, String> {
    let output = tokio::time::timeout(
        Duration::from_secs(5),
        Command::new(program)
            .args(args)
            .env("LC_ALL", "C")
            .kill_on_drop(true)
            .output(),
    )
    .await
    .map_err(|_| "Network query timed out")?
    .map_err(|_| "NetworkManager and iproute2 are required")?;
    if !output.status.success() {
        return Err("Network query failed".into());
    }
    String::from_utf8(output.stdout).map_err(|_| "Network query returned invalid text".into())
}
pub async fn snapshot() -> Result<Snapshot, String> {
    let devices = command(
        "nmcli",
        &[
            "-t",
            "-e",
            "no",
            "-f",
            "DEVICE,TYPE,STATE",
            "device",
            "status",
        ],
    )
    .await?;
    let mut profiles = vec![];
    for device in connected_wifi(&devices) {
        let aps = command(
            "nmcli",
            &[
                "-t",
                "-e",
                "no",
                "-f",
                "IN-USE,SSID",
                "device",
                "wifi",
                "list",
                "--rescan",
                "no",
                "ifname",
                device,
            ],
        )
        .await?;
        if !has_caltrain(&aps) {
            continue;
        }
        let addresses = command("nmcli", &["-g", "IP4.ADDRESS", "device", "show", device]).await?;
        let ipv4 = addresses
            .lines()
            .filter_map(|line| line.split('/').next()?.parse::<Ipv4Addr>().ok())
            .find(|ip| !ip.is_loopback() && !ip.is_unspecified() && !ip.is_link_local());
        profiles.push(Profile {
            name: "Caltrain_WiFi".into(),
            interface_id: device.into(),
            physical_wifi_up: true,
            internet: false,
            ipv4,
        });
    }
    let mut result = evaluate(profiles);
    if result.ready {
        let route = command("ip", &["-j", "route", "get", "1.1.1.1"]).await?;
        if !route_matches(&route, &result.profiles[0].interface_id) {
            result.ready = false;
            result.status = "Paused: Internet traffic uses another interface".into();
        }
    }
    Ok(result)
}

pub async fn run(
    mut refresh: mpsc::UnboundedReceiver<()>,
    stop: CancellationToken,
    report: impl Fn(Result<Snapshot, String>) + Send,
) {
    while !stop.is_cancelled() {
        let mut child = match Command::new("nmcli")
            .arg("monitor")
            .env("LC_ALL", "C")
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true)
            .spawn()
        {
            Ok(child) => child,
            Err(_) => {
                report(Err("NetworkManager is unavailable".into()));
                tokio::select! {_=stop.cancelled()=>return,_=tokio::time::sleep(Duration::from_secs(30))=>continue};
            }
        };
        let mut lines = BufReader::new(child.stdout.take().unwrap()).lines();
        // A quiet fallback covers missed notifications and resume, without
        // repeatedly scanning for access points or opening HTTP connections.
        let mut periodic = tokio::time::interval(Duration::from_secs(60));
        periodic.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            tokio::select! {
                _=stop.cancelled()=>return,
                line=lines.next_line()=>if !matches!(line,Ok(Some(_))){break;},
                _=periodic.tick()=>{},
                request=refresh.recv()=>if request.is_none(){return;},
            }
            tokio::select! {_=stop.cancelled()=>return,_=tokio::time::sleep(Duration::from_millis(250))=>{}}
            tokio::select! {_=stop.cancelled()=>return,result=snapshot()=>report(result)}
        }
        report(Err("Network monitor stopped; reconnecting".into()));
        tokio::select! {_=stop.cancelled()=>return,_=tokio::time::sleep(Duration::from_secs(5))=>{}}
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_connected_wifi_and_exact_active_ssid_are_targets() {
        assert_eq!(
            connected_wifi(
                "wlan0:wifi:connected\neth0:ethernet:connected\nwlan1:wifi:disconnected\n"
            ),
            vec!["wlan0"]
        );
        assert!(has_caltrain(" :Office\n*:Caltrain_WiFi\n"));
        assert!(!has_caltrain(" :Caltrain_WiFi\n*:Caltrain_WiFi Guest\n"));
    }
    #[test]
    fn ambiguous_invalid_or_other_interface_routes_are_blocked() {
        assert!(route_matches(r#"[{"dev":"wlan0"}]"#, "wlan0"));
        for route in [
            "broken",
            "[]",
            r#"[{"dev":"eth0"}]"#,
            r#"[{"dev":"wlan0"},{"dev":"tun0"}]"#,
        ] {
            assert!(!route_matches(route, "wlan0"));
        }
    }
}
