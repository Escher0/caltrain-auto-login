//! Captive portal protocol, independent of the tray and network monitor.
pub mod controller;
pub mod diagnostics;
pub mod http_session;
#[cfg(target_os = "linux")]
pub mod linux_monitor;
pub mod network;
pub mod portal;
pub mod runtime;
pub mod settings;
