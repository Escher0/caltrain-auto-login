// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

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
