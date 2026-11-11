// SPDX-FileCopyrightText: 2026 Brian Pilnick
// SPDX-License-Identifier: GPL-3.0-or-later
// Additional WebView2 permission: see LICENSING.md at the repository root.

const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const ui = path.join(__dirname, '../ui');
const settle = () => new Promise(resolve => setImmediate(resolve));
function fixture({ failSave = false, startup = false, startupError = null } = {}) {
  const elements = Object.fromEntries([...readFileSync(path.join(ui, 'index.html'), 'utf8').matchAll(/id="([^"]+)"/g)].map(([, id]) => [id, { checked: false, disabled: true, hidden: false, textContent: '', handlers: {}, addEventListener(event, handler) { this.handlers[event] = handler; } }]));
  const state = { login: { settings: { consent: false, automatic: false, check_updates: true }, busy: false, can_connect: false, status: 'Waiting for Caltrain Wi-Fi' }, startup, startup_error: startupError, updates: { configured: false, enabled: false, label: 'Updates unavailable in this build', candidate: null }, version: '0.2.0' };
  const calls = [];
  let poll;
  const invoke = async (command, args) => {
    calls.push({ command, args });
    if (command === 'settings_state') return structuredClone(state);
    if (command === 'set_preference') {
      if (failSave) throw 'Settings could not be saved';
      state.login.settings[args.preference] = args.enabled;
      if (!state.login.settings.consent) state.login.settings.automatic = false;
    }
  };
  vm.runInNewContext(readFileSync(path.join(ui, 'settings.js'), 'utf8'), {
    document: { getElementById: id => { assert.ok(elements[id], `Missing element ${id}`); return elements[id]; } },
    window: { __TAURI__: { core: { invoke } } },
    setInterval: fn => { poll = fn; },
  });
  return { elements, state, calls, poll: () => poll(), change: async (id, checked) => { elements[id].checked = checked; await elements[id].handlers.change(); await settle(); } };
}

test('consent gates automatic sign-in and sends explicit preferences', async () => {
  const f = fixture(); await settle();
  assert.equal(f.elements.automatic.disabled, true);
  assert.equal(f.elements.consent.checked, false);
  assert.equal(f.elements.update.disabled, true);
  assert.match(f.elements['update-help'].textContent, /unavailable/i);
  await f.change('consent', true);
  assert.equal(f.elements.automatic.disabled, false);
  assert.equal(f.calls.filter(c => c.command === 'set_preference')[0].args.enabled, true);
  assert.equal(f.calls.filter(c => c.command === 'set_preference')[0].args.preference, 'consent');
  await f.change('automatic', true);
  await f.change('consent', false);
  assert.equal(f.elements.automatic.checked, false);
  assert.equal(f.elements.automatic.disabled, true);
});

test('failed preference writes restore actual state and retain an actionable error', async () => {
  const f = fixture({ failSave: true }); await settle();
  await f.change('consent', true);
  assert.equal(f.elements.consent.checked, false);
  assert.equal(f.elements.error.hidden, false);
  assert.match(f.elements.error.textContent, /could not be saved/);
  await f.poll();
  assert.equal(f.elements.error.hidden, false);
});

test('unknown OS startup state is not represented as a known off setting', async () => {
  const f = fixture({ startup: null, startupError: 'Could not read start-at-login status' }); await settle();
  assert.equal(f.elements.startup.disabled, true);
  assert.match(f.elements['startup-help'].textContent, /Could not read/);
});

test('update action sends the candidate displayed to the user', async () => {
  const f = fixture(); await settle();
  Object.assign(f.state.updates, { configured: true, enabled: true, candidate: 7, label: 'Install update 0.3.0' });
  await f.poll();
  assert.equal(f.elements.update.textContent, 'Install update 0.3.0');
  assert.equal(f.elements.update.disabled, false);
  await f.elements.update.handlers.click();
  const request = f.calls.find(c => c.command === 'request_update');
  assert.equal(request.args.candidate, 7);
});

test('a stale polling reply cannot undo an acknowledged settings change', async () => {
  const elements = Object.fromEntries([...readFileSync(path.join(ui, 'index.html'), 'utf8').matchAll(/id="([^"]+)"/g)].map(([, id]) => [id, { checked: false, disabled: true, handlers: {}, addEventListener(event, handler) { this.handlers[event] = handler; } }]));
  const view = consent => ({ login: { settings: { consent, automatic: false, check_updates: true }, busy: false, can_connect: false, status: 'Waiting' }, startup: false, startup_error: null, updates: { configured: false, enabled: false, label: 'Unavailable', candidate: null }, version: '0.2.0' });
  let reply;
  let reads = 0;
  let poll;
  vm.runInNewContext(readFileSync(path.join(ui, 'settings.js'), 'utf8'), {
    document: { getElementById: id => elements[id] },
    window: { __TAURI__: { core: { invoke: async command => command !== 'settings_state' ? undefined : ++reads === 1 ? view(false) : reads === 2 ? new Promise(resolve => { reply = resolve; }) : view(true) } } },
    setInterval(fn) { poll = fn; },
  });
  await settle();
  poll();
  elements.consent.checked = true;
  await elements.consent.handlers.change();
  reply(view(false));
  await settle();
  assert.equal(elements.consent.checked, true);
});
