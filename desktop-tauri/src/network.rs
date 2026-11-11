use serde::Serialize;
use std::net::Ipv4Addr;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Target {
    pub identity: String,
    pub address: Ipv4Addr,
}

#[derive(Clone, Debug, Serialize)]
pub struct Profile {
    pub name: String,
    pub interface_id: String,
    pub physical_wifi_up: bool,
    pub internet: bool,
    pub ipv4: Option<Ipv4Addr>,
}

#[derive(Clone, Debug, Serialize)]
pub struct Snapshot {
    pub profiles: Vec<Profile>,
    pub ready: bool,
    pub status: String,
}
impl Snapshot {
    pub fn target(&self) -> Option<Target> {
        if !self.ready {
            return None;
        }
        let p = self
            .profiles
            .iter()
            .find(|p| p.physical_wifi_up && is_caltrain(&p.name))?;
        Some(Target {
            identity: format!("{}:{}", p.interface_id, p.name),
            address: p.ipv4?,
        })
    }
    pub fn internet(&self) -> bool {
        self.profiles
            .iter()
            .any(|p| p.physical_wifi_up && is_caltrain(&p.name) && p.internet)
    }
}

fn is_caltrain(name: &str) -> bool {
    name == "Caltrain_WiFi"
        || name
            .strip_prefix("Caltrain_WiFi ")
            .is_some_and(|suffix| !suffix.is_empty() && suffix.bytes().all(|c| c.is_ascii_digit()))
}

pub fn evaluate(profiles: Vec<Profile>) -> Snapshot {
    let targets: Vec<_> = profiles
        .iter()
        .filter(|p| p.physical_wifi_up && is_caltrain(&p.name))
        .collect();
    let other_internet = profiles.iter().any(|p| {
        p.internet
            && !targets
                .iter()
                .any(|target| p.interface_id == target.interface_id)
    });
    let ready = targets.len() == 1 && targets[0].ipv4.is_some() && !other_internet;
    let status = if targets.is_empty() {
        "Waiting for Caltrain Wi-Fi"
    } else if targets.len() != 1 {
        "Paused: more than one Caltrain connection"
    } else if other_internet {
        "Paused: another connection has Internet access"
    } else if targets[0].ipv4.is_none() {
        "Waiting for a Caltrain Wi-Fi address"
    } else {
        "Caltrain Wi-Fi detected"
    };
    Snapshot {
        profiles,
        ready,
        status: status.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn profile(name: &str, id: &str, physical_wifi_up: bool, internet: bool) -> Profile {
        Profile {
            name: name.into(),
            interface_id: id.into(),
            physical_wifi_up,
            internet,
            ipv4: Some(Ipv4Addr::new(10, 0, 0, 2)),
        }
    }

    #[test]
    fn matches_only_the_observed_caltrain_profile_family() {
        for name in ["Caltrain_WiFi", "Caltrain_WiFi 4", "Caltrain_WiFi 15"] {
            assert!(is_caltrain(name), "{name}");
        }
        for name in [
            "caltrain_wifi",
            "Caltrain_WiFi ",
            "Caltrain_WiFi 1x",
            "Caltrain_WiFi Guest",
        ] {
            assert!(!is_caltrain(name), "{name}");
        }
    }

    #[test]
    fn virtual_or_disconnected_wifi_is_not_a_target() {
        assert!(!evaluate(vec![profile("Caltrain_WiFi", "wifi", false, false)]).ready);
        assert!(!evaluate(vec![]).ready);
    }

    #[test]
    fn local_only_tailscale_does_not_block_but_other_internet_does() {
        let wifi = profile("Caltrain_WiFi 15", "wifi", true, false);
        assert!(
            evaluate(vec![
                wifi.clone(),
                profile("Tailscale", "vpn", false, false)
            ])
            .ready
        );
        assert!(!evaluate(vec![wifi.clone(), profile("Tailscale", "vpn", false, true)]).ready);
        assert!(!evaluate(vec![wifi, profile("Ethernet", "ethernet", false, true)]).ready);
    }

    #[test]
    fn online_caltrain_does_not_block_itself() {
        assert!(evaluate(vec![profile("Caltrain_WiFi 15", "wifi", true, true)]).ready);
    }

    #[test]
    fn missing_address_or_multiple_caltrain_connections_cannot_be_targeted() {
        let mut wifi = profile("Caltrain_WiFi", "wifi", true, false);
        wifi.ipv4 = None;
        let missing = evaluate(vec![wifi.clone()]);
        assert!(!missing.ready);
        assert!(missing.target().is_none());
        wifi.ipv4 = Some(Ipv4Addr::new(10, 0, 0, 3));
        let multiple = evaluate(vec![wifi, profile("Caltrain_WiFi 4", "wifi2", true, false)]);
        assert!(!multiple.ready);
        assert!(multiple.target().is_none());
    }
}
