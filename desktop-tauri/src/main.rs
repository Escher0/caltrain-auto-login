// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod settings_window;
mod updates;
#[cfg(windows)]
mod windows_monitor;

use caltrain_wifi::{
    runtime::{self, Command, Handle, PortalRunner},
    settings::Store,
};
use serde_json::json;
use std::{
    env,
    fs::{self, OpenOptions},
    io::Write,
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tauri::{
    image::Image,
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::TrayIconBuilder,
    Manager,
};
use tokio_util::sync::CancellationToken;

#[derive(Clone)]
pub(crate) struct Log {
    directory: PathBuf,
    lock: Arc<Mutex<()>>,
}
impl Log {
    fn write(&self, event: &str, detail: serde_json::Value) {
        let Ok(_lock) = self.lock.lock() else {
            return;
        };
        let path = self.directory.join("events.jsonl");
        if path.metadata().is_ok_and(|m| m.len() > 256_000) {
            let old = self.directory.join("events.previous.jsonl");
            let _ = fs::remove_file(&old);
            let _ = fs::rename(&path, old);
        }
        let record = json!({"time_ms":SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis(),"event":event,"detail":detail});
        if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
            let _ = writeln!(file, "{record}");
        }
    }
}

struct Lifecycle {
    login: Handle,
    stop: CancellationToken,
    updater: updates::Handle,
    settings_opener: settings_window::Opener,
    log: Log,
    #[cfg(target_os = "linux")]
    refresh_sender: tokio::sync::mpsc::UnboundedSender<()>,
    #[cfg(windows)]
    monitor: Mutex<Option<windows_monitor::Monitor>>,
}
impl Lifecycle {
    fn refresh(&self) {
        #[cfg(windows)]
        if let Some(monitor) = self.monitor.lock().unwrap().as_ref() {
            monitor.refresh();
        }
        #[cfg(target_os = "linux")]
        let _ = self.refresh_sender.send(());
    }
}
fn argument(name: &str) -> Option<String> {
    env::args()
        .collect::<Vec<_>>()
        .windows(2)
        .find(|p| p[0] == name)
        .map(|p| p[1].clone())
}
fn open_text(path: &std::path::Path) -> std::io::Result<()> {
    #[cfg(windows)]
    let command = "notepad.exe";
    #[cfg(target_os = "macos")]
    let command = "open";
    #[cfg(not(any(windows, target_os = "macos")))]
    let command = "xdg-open";
    std::process::Command::new(command)
        .arg(path)
        .spawn()
        .map(|_| ())
}
fn main() {
    let app=tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app,args,_|{
            if !args.iter().any(|arg| arg == "--autostart") {
                if let Some(state) = app.try_state::<Lifecycle>() { state.settings_opener.request(); }
            }
        }))
        .invoke_handler(tauri::generate_handler![settings_window::settings_state,settings_window::set_preference,settings_window::set_startup,settings_window::settings_action,settings_window::request_update])
        // Keep the existing OS startup registration name across the display-name change.
        .plugin(tauri_plugin_autostart::Builder::new().app_name("Caltrain Wi-Fi").arg("--autostart").build())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .setup(|app| {
            let profile=env::var_os("CALTRAIN_PROFILE").map(PathBuf::from).unwrap_or(app.path().app_local_data_dir()?);
            fs::create_dir_all(&profile)?;
            let log=Log{directory:profile.clone(),lock:Arc::new(Mutex::new(()))};
            log.write("started",json!({"version":app.package_info().version.to_string(),"pid":std::process::id()}));
            let title=MenuItem::with_id(app,"title",format!("Caltrain Auto Login {}",app.package_info().version),false,None::<&str>)?;
            let status=MenuItem::with_id(app,"status","Checking Wi-Fi...",false,None::<&str>)?;
            let connect=MenuItem::with_id(app,"connect","Connect now",false,None::<&str>)?;
            let refresh=MenuItem::with_id(app,"refresh","Refresh network status",true,None::<&str>)?;
            let settings=MenuItem::with_id(app,"settings","Settings...",true,None::<&str>)?;
            let quit=MenuItem::with_id(app,"quit","Quit",true,None::<&str>)?;
            let separator=PredefinedMenuItem::separator(app)?;
            let menu=Menu::with_items(app,&[&title,&status,&separator,&connect,&refresh,&settings,&quit])?;

            let ui_app=app.handle().clone();let ui_log=log.clone();
            let diagnostic_log=log.clone();
            let runner=PortalRunner::new(Arc::new(move |record| diagnostic_log.write("network-failure",json!(record))));
            let (login,task)=runtime::create(Store::new(&profile),runner,move |view| {
                let _=status.set_text(&view.status);
                let _=connect.set_enabled(view.can_connect);
                let _=connect.set_text(if view.busy {"Connecting..."} else {"Connect now"});
                if let Some(tray)=ui_app.tray_by_id("caltrain") {let _=tray.set_tooltip(Some(format!("Caltrain Auto Login: {}",view.status)));}
                ui_log.write("status",json!({"message":view.status,"busy":view.busy,"consent":view.settings.consent,"automatic":view.settings.automatic}));
            });
            tauri::async_runtime::spawn(task);
            let stop=CancellationToken::new();
            let report_login=login.clone();let report_log=log.clone();
            let report=move |result:Result<caltrain_wifi::network::Snapshot,String>| {
                match &result {
                    Ok(snapshot)=>report_log.write("network",json!(snapshot)),
                    Err(error)=>report_log.write("network-error",json!(error)),
                }
                report_login.send(Command::Network(result));
            };
            #[cfg(windows)]
            let monitor=windows_monitor::start(move |_,result|report(result));
            #[cfg(target_os="linux")]
            let refresh_sender={
                let (sender,receiver)=tokio::sync::mpsc::unbounded_channel();
                tauri::async_runtime::spawn(caltrain_wifi::linux_monitor::run(receiver,stop.clone(),report));
                sender
            };
            #[cfg(not(any(windows,target_os="linux")))]
            report(Err("Network detection is not yet supported on this platform".into()));

            let updater=updates::start(app.handle().clone(),login.clone(),log.clone(),stop.clone());
            let menu_login=login.clone();
            let settings_opener=settings_window::Opener::start(app.handle().clone())?;
            app.manage(Lifecycle{login,stop,updater,settings_opener,log:log.clone(),#[cfg(target_os="linux")]refresh_sender,#[cfg(windows)]monitor:Mutex::new(Some(monitor))});
            TrayIconBuilder::with_id("caltrain")
                .icon(Image::from_bytes(include_bytes!("../icons/tray.png"))?)
                .tooltip("Caltrain Auto Login")
                .menu(&menu)
                .on_menu_event(move |app,event|match event.id.as_ref() {
                    "connect"=>menu_login.send(Command::Connect),
                    "refresh"=>app.state::<Lifecycle>().refresh(),
                    "settings"=>app.state::<Lifecycle>().settings_opener.request(),
                    "quit"=>{menu_login.stop();app.exit(0);},
                    _=>{},
                }).build(app)?;
            if !env::args().any(|arg| arg == "--autostart") {
                app.state::<Lifecycle>().settings_opener.request();
            }
            fs::write(profile.join("ready.txt"),std::process::id().to_string())?;
            if let Some(seconds)=argument("--diagnostic-seconds").and_then(|s|s.parse::<u64>().ok()) {
                let app=app.handle().clone();
                tauri::async_runtime::spawn(async move {tokio::time::sleep(Duration::from_secs(seconds)).await;app.exit(0);});
            }
            Ok(())
        }).build(tauri::generate_context!()).expect("Could not start Caltrain Auto Login");
    app.run(|app, event| {
        if let tauri::RunEvent::ExitRequested {
            code: None, api, ..
        } = &event
        {
            // Closing the last settings window destroys its WebView but leaves
            // the native tray/runtime alive. Explicit quit/restart still exits.
            api.prevent_exit();
        }
        if matches!(event, tauri::RunEvent::Exit) {
            let state = app.state::<Lifecycle>();
            state.settings_opener.stop();
            state.login.stop();
            state.stop.cancel();
            #[cfg(windows)]
            state.monitor.lock().unwrap().take();
        }
    });
}
