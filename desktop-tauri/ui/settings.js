// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

'use strict';
(() => {
  const ids = ['version', 'status', 'error', 'connect', 'refresh', 'consent', 'automatic', 'startup', 'check_updates', 'update', 'help', 'activity', 'automatic-help', 'startup-help', 'update-help'];
  const elements = Object.fromEntries(ids.map(id => [id, document.getElementById(id)]));
  const controls = ['connect', 'refresh', 'consent', 'automatic', 'startup', 'check_updates', 'update', 'help', 'activity'];
  const invoke = window.__TAURI__?.core?.invoke;
  let current = null;
  let changing = false;
  let reading = false;
  let revision = 0;
  function showError(error) {
    elements.error.textContent = String(error);
    elements.error.hidden = false;
  }
  function render(view) {
    current = view;
    elements.version.textContent = `v${view.version}`;
    elements.status.textContent = view.login.status;
    for (const key of ['consent', 'automatic', 'check_updates']) elements[key].checked = view.login.settings[key];
    elements.startup.checked = view.startup === true;
    elements['automatic-help'].textContent = view.login.settings.consent
      ? 'Sign in when an eligible Caltrain Wi-Fi connection is detected.'
      : 'First allow terms acceptance above.';
    elements['startup-help'].textContent = view.startup_error || 'Start quietly in the system tray when you sign in.';
    elements['update-help'].textContent = view.updates.configured
      ? 'Checks every six hours. You choose when to install.'
      : 'Updates are unavailable in this build.';
    elements.update.textContent = view.updates.label;
    elements.connect.textContent = view.login.busy ? 'Connecting...' : 'Connect now';
    for (const id of controls) elements[id].disabled = changing;
    elements.connect.disabled ||= !view.login.can_connect;
    elements.automatic.disabled ||= !view.login.settings.consent;
    elements.startup.disabled ||= view.startup === null;
    elements.check_updates.disabled ||= !view.updates.configured;
    elements.update.disabled ||= !view.updates.enabled;
  }
  async function refresh() {
    if (changing || reading) return;
    reading = true;
    const startedAt = revision;
    try {
      const view = await invoke('settings_state');
      // A read already in flight must not reset a pending user change.
      if (!changing && startedAt === revision) render(view);
    } catch (error) { showError(error); }
    finally { reading = false; }
  }
  async function mutate(command, args) {
    if (changing) return;
    changing = true;
    revision += 1;
    elements.error.hidden = true;
    for (const id of controls) elements[id].disabled = true;
    try { await invoke(command, args); }
    catch (error) { showError(error); }
    finally {
      // Fetch only after the acknowledged write. This also restores the saved
      // checkbox value after an error without clearing its explanation.
      try { render(await invoke('settings_state')); }
      catch (error) { showError(error); }
      changing = false;
      if (current) render(current);
    }
  }
  for (const preference of ['consent', 'automatic', 'check_updates']) {
    elements[preference].addEventListener('change', () => mutate('set_preference', { preference, enabled: elements[preference].checked }));
  }
  elements.startup.addEventListener('change', () => mutate('set_startup', { enabled: elements.startup.checked }));
  for (const action of ['connect', 'refresh', 'help', 'activity']) {
    elements[action].addEventListener('click', () => mutate('settings_action', { action }));
  }
  elements.update.addEventListener('click', () => mutate('request_update', { candidate: current?.updates.candidate ?? null }));
  if (!invoke) {
    showError('Open settings from the Caltrain Auto Login app to change preferences.');
    return;
  }
  refresh();
  // This timer exists only while the settings WebView is open.
  setInterval(refresh, 1000);
})();
