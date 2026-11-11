#!/usr/bin/python3
# SPDX-FileCopyrightText: 2026 Brian Pilnick
# SPDX-License-Identifier: GPL-3.0-or-later
# Additional WebView2 permission: see LICENSING.md at the repository root.

"""Exercise the native tray in an isolated settings directory/session bus.

Run: dbus-run-session -- /usr/bin/python3 tools/smoke-linux.py APP_EXECUTABLE
Requires python3-dbus, python3-gi and an X display. Add --settings-ui to exercise
real local WebView IPC and window lifecycle (also needs python3-xlib and Pillow).
Use a private Xvfb display for --settings-ui: it sends pointer events there.
An optional duration in seconds measures the tray-only idle process.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

import dbus
import dbus.service
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("executable")
parser.add_argument("duration", nargs="?", type=int, default=0)
parser.add_argument("--settings-ui", action="store_true")
args = parser.parse_args()
if args.duration and args.duration < 12:
    parser.error("Idle measurement needs at least 12 seconds")
assert not (args.settings_ui and args.duration), "Choose UI smoke or idle measurement"
DBusGMainLoop(set_as_default=True)
bus = dbus.SessionBus()
loop = GLib.MainLoop()
registered = None


class Watcher(dbus.service.Object):
    @dbus.service.method("org.kde.StatusNotifierWatcher", in_signature="s", sender_keyword="sender")
    def RegisterStatusNotifierItem(self, service, sender=None):
        global registered
        registered = (sender, str(service)) if str(service).startswith("/") else (str(service), "/StatusNotifierItem")

    @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="ss", out_signature="v")
    def Get(self, interface, prop):
        return self.GetAll(interface)[prop]

    @dbus.service.method("org.freedesktop.DBus.Properties", in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        return {"IsStatusNotifierHostRegistered": True, "ProtocolVersion": dbus.Int32(0),
                "RegisteredStatusNotifierItems": dbus.Array([], signature="s")}


name = dbus.service.BusName("org.kde.StatusNotifierWatcher", bus)
watcher = Watcher(name, "/StatusNotifierWatcher")
exe = Path(args.executable).resolve()
profile = Path(tempfile.mkdtemp(prefix="caltrain-smoke-"))
test_bin = profile / "bin"
test_bin.mkdir()
shutil.copy2("/usr/bin/true", test_bin / "xdg-open")
env = dict(os.environ, CALTRAIN_PROFILE=str(profile), PATH=f"{test_bin}:{os.environ['PATH']}")
log = (profile / "console.log").open("w")
start = time.monotonic()
app = subprocess.Popen([str(exe), "--autostart", "--diagnostic-seconds", str(args.duration or 60)], env=env, stdout=log, stderr=log)
ready_seconds = None
samples = []
step = 0
failure = None
menu_labels = []

if args.settings_ui:
    from Xlib import X, display, protocol
    from Xlib.ext import xtest
    from PIL import Image
    x = display.Display()

    def windows():
        def walk(window):
            found = [window] if window.get_wm_name() == "Caltrain Auto Login settings" else []
            for child in window.query_tree().children:
                found.extend(walk(child))
            return found
        return walk(x.screen().root)

    def close_settings():
        window = windows()[0]
        event = protocol.event.ClientMessage(window=window.id, client_type=x.intern_atom("WM_PROTOCOLS"),
                                            data=(32, [x.intern_atom("WM_DELETE_WINDOW"), X.CurrentTime, 0, 0, 0]))
        window.send_event(event, event_mask=0)
        x.sync()

    def click_setting(y):
        window = windows()[0]
        point = x.screen().root.translate_coords(window, 594, y)
        xtest.fake_input(x, X.MotionNotify, x=point.x, y=point.y)
        xtest.fake_input(x, X.ButtonPress, 1)
        xtest.fake_input(x, X.ButtonRelease, 1)
        x.sync()

    def screenshot():
        window = windows()[0]
        geometry = window.get_geometry()
        raw = window.get_image(0, 0, geometry.width, geometry.height, X.ZPixmap, 0xffffffff)
        Image.frombytes("RGB", (geometry.width, geometry.height), raw.data, "raw", "BGRX").save(profile / "settings.png")


def flatten(node):
    result = [(int(node[0]), dict(node[1]))]
    for child in node[2]:
        result.extend(flatten(child))
    return result


def saved():
    return json.loads((profile / "settings.json").read_text())


def tick():
    global step, failure, ready_seconds, menu_labels
    try:
        elapsed = time.monotonic() - start
        if ready_seconds is None and (profile / "ready.txt").exists():
            ready_seconds = elapsed
        if app.poll() is not None:
            assert app.returncode == 0, (profile / "console.log").read_text()
            assert step >= (10 if args.settings_ui else 2) or (args.duration and menu_labels), "Exited before completing checks"
            loop.quit()
            return False
        stat = Path(f"/proc/{app.pid}/stat").read_text().split(") ", 1)[1].split()
        samples.append({"seconds": elapsed, "rss": int(stat[21]) * os.sysconf("SC_PAGE_SIZE"),
                        "cpu": sum(int(stat[i]) for i in [11, 12, 13, 14]) / os.sysconf("SC_CLK_TCK")})
        assert elapsed < (args.duration or 60) + 15, "Timed out"
        if not registered:
            return True
        service, path = registered
        props = dbus.Interface(bus.get_object(service, path), "org.freedesktop.DBus.Properties")
        item = props.GetAll("org.kde.StatusNotifierItem")
        menu = dbus.Interface(bus.get_object(service, str(item["Menu"])), "com.canonical.dbusmenu")
        rows = flatten(menu.GetLayout(0, -1, dbus.Array([], signature="s"))[1])
        labels = {str(prop.get("label", "")): (ident, prop) for ident, prop in rows if "label" in prop}
        menu_labels = list(labels)
        if "Waiting for Caltrain Wi-Fi" not in labels and step == 0:
            return True
        if args.duration:
            return True

        def click(label):
            menu.Event(labels[label][0], "clicked", dbus.Int32(0, variant_level=1), dbus.UInt32(0))

        if step == 0:
            assert not labels["Connect now"][1].get("enabled", True)
            assert "Settings..." in labels, menu_labels
            assert "Automatic sign-in" not in labels, menu_labels
            if args.settings_ui:
                assert not windows(), "Autostart created a settings window"
                click("Settings...")
            else:
                click("Refresh network status")
        elif not args.settings_ui:
            assert not (profile / "settings.json").exists(), "Quick actions unexpectedly saved consent"
            click("Quit")
        elif step == 1:
            if not windows():
                return True
            assert len(windows()) == 1
            screenshot()
            if elapsed < 8:
                return True
            click_setting(352)  # First switch in the 660px-wide settings page.
        elif step == 2:
            assert saved()["consent"], "Settings IPC failed to persist consent"
            click_setting(434)
        elif step == 3:
            assert saved()["automatic"], "Settings IPC failed to persist automatic sign-in"
            click_setting(352)
        elif step == 4:
            assert not saved()["consent"] and not saved()["automatic"], "Revocation did not clear automatic sign-in"
            close_settings()
        elif step == 5:
            assert not windows(), "Closing settings left a hidden window"
            assert app.poll() is None, "Closing settings quit the tray helper"
            # A second explicit launch must focus/open the existing helper.
            subprocess.run([str(exe)], env=env, stdout=log, stderr=log, timeout=10, check=True)
        elif step == 6:
            assert len(windows()) == 1, "Explicit second launch did not reopen settings"
            click("Settings...")
        elif step == 7:
            assert len(windows()) == 1, "Repeated settings action created multiple windows"
            close_settings()
        elif step == 8:
            assert not windows()
            subprocess.run([str(exe), "--autostart"], env=env, stdout=log, stderr=log, timeout=10, check=True)
        elif step == 9:
            assert not windows(), "Second autostart launch opened settings"
            click("Quit")
        step += 1
        return True
    except Exception as error:
        failure = repr(error)
        loop.quit()
        return False


GLib.timeout_add(1000, tick)
try:
    loop.run()
finally:
    if app.poll() is None:
        app.terminate()
        try:
            app.wait(timeout=5)
        except subprocess.TimeoutExpired:
            app.kill()
            app.wait()
    log.close()
if failure:
    raise RuntimeError(f"{failure}; profile={profile}\n{(profile / 'console.log').read_text()}")
result = {"result": "passed", "profile": str(profile), "settingsUi": args.settings_ui, "menuLabels": menu_labels,
          "startupObservedSeconds": round(ready_seconds, 3)}
if args.duration:
    steady = [s for s in samples if s["seconds"] >= 10]
    first, last = steady[0], steady[-1]
    result.update(steadyRssMiB=round(sum(s["rss"] for s in steady) / len(steady) / 1048576, 2),
                  peakRssMiB=round(max(s["rss"] for s in samples) / 1048576, 2),
                  cpuOneCorePercent=round(100 * (last["cpu"] - first["cpu"]) / (last["seconds"] - first["seconds"]), 3),
                  sampleSeconds=round(last["seconds"] - first["seconds"], 2))
(profile / "result.json").write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result, indent=2))

# Restarting with the isolated profile must preserve revoked/default consent.
with (profile / "console.log").open("a") as output:
    restarted = subprocess.run([str(exe), "--autostart", "--diagnostic-seconds", "3"], env=env,
                               stdout=output, stderr=output, timeout=20)
assert restarted.returncode == 0
records = [json.loads(line) for line in (profile / "events.jsonl").read_text().splitlines()]
last_start = max(i for i, record in enumerate(records) if record["event"] == "started")
statuses = [r["detail"] for r in records[last_start:] if r["event"] == "status"]
assert statuses and not statuses[0]["consent"] and not statuses[0]["automatic"], statuses
assert not any(s["busy"] for s in statuses), statuses
print("Restart preserved revoked/default consent; non-Caltrain network remained ineligible.")

if args.settings_ui:
    fresh_profile = profile / "fresh-explicit-launch"
    with (profile / "console.log").open("a") as output:
        explicit = subprocess.Popen([str(exe), "--diagnostic-seconds", "6"],
                                    env=dict(env, CALTRAIN_PROFILE=str(fresh_profile)), stdout=output, stderr=output)
        try:
            deadline = time.monotonic() + 5
            while not windows() and time.monotonic() < deadline:
                time.sleep(0.1)
            assert len(windows()) == 1, "First explicit launch did not open setup"
            assert not (fresh_profile / "settings.json").exists(), "First launch granted consent"
            explicit.wait(timeout=10)
            assert explicit.returncode == 0
        finally:
            if explicit.poll() is None:
                explicit.terminate()
                explicit.wait(timeout=5)
    print("First explicit launch opened setup without granting consent.")
