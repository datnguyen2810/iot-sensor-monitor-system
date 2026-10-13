import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

// DOM/Chart doubles for logic tests; this is not a visual browser test.
export function harness(fetchImpl = async () => { throw new TypeError('offline'); }) {
  const elements = new Map();
  const events = new Map();
  const storage = new Map([['iot_auth_token', 'test.jwt.token']]);
  const timers = new Map();
  const toasts = [];
  let timerId = 0;
  function element(id) {
    if (!elements.has(id)) {
      const classes = new Set();
      const listeners = new Map();
      const el = { id, textContent: '', innerHTML: '', value: '', checked: false, disabled: false,
        style: {}, appendChild() {}, remove() {},
        classList: { add: key => classes.add(key), remove: key => classes.delete(key),
          toggle: (key, force) => force ? classes.add(key) : classes.delete(key), contains: key => classes.has(key) },
        getAttribute: key => key === 'data-device' ? id.replace('switch-', '') : null,
        addEventListener: (name, callback) => listeners.set(name, callback), listeners,
        closest: () => element('control-' + id.replace('switch-', '')),
        querySelector: selector => element(id + selector) };
      elements.set(id, el);
    }
    return elements.get(id);
  }
  const context = vm.createContext({ console, URLSearchParams, AbortController, Date, fetch: fetchImpl,
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)), removeItem: key => storage.delete(key) },
    setTimeout: callback => { const id = ++timerId; timers.set(id, callback); return id; },
    clearTimeout: id => timers.delete(id),
    document: { body: element('body'), getElementById: element, createElement: tag => element(tag),
      querySelector: () => null,
      querySelectorAll: selector => selector.includes('checkbox') ? ['led', 'fan', 'ac'].map(code => element('switch-' + code)) : [],
      addEventListener: (name, callback) => { if (!events.has(name)) events.set(name, []); events.get(name).push(callback); } },
    window: { location: { pathname: '/dashboard.html', href: '' }, addEventListener() {} },
    Chart: class { constructor(_, config) { this.data = config.data; } update() {} }
  });
  const run = code => vm.runInContext(code, context);
  const load = name => run(readFileSync(fileURLToPath(new URL('../js/' + name, import.meta.url)), 'utf8'));
  load('api.js');
  run('showToast = (message, type) => testToasts.push({ message, type })');
  context.testToasts = toasts;
  load('auth.js');
  return { context, run, load, element, storage, timers, toasts,
    async ready() { for (const callback of events.get('DOMContentLoaded') || []) await callback(); } };
}

export function response(data, status = 200, message = 'OK') {
  return { status, ok: status >= 200 && status < 300, json: async () => ({ status, message, data }) };
}
