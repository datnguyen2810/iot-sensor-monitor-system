import assert from 'node:assert/strict';
import { setTimeout as pause } from 'node:timers/promises';
import { harness } from './helpers.mjs';

// Called only by the isolated Spring/Mosquitto test. Never point this at the main DB/broker.
const settings = JSON.parse(process.env.IOT_FRONTEND_SMOKE);
const h = harness(fetch);
h.storage.clear();
h.context.baseUrl = settings.baseUrl;
h.run('API_CONFIG.BASE_URL = baseUrl');
h.context.username = settings.username;
h.context.password = settings.password;
assert.equal(await h.run("Auth.login(username, 'wrong-test-password')"), false);
assert.equal(h.storage.has('iot_auth_token'), false);
assert.equal(await h.run('Auth.login(username, password)'), true);
assert.equal(h.run('Auth.getCurrentUser().username'), settings.username);

const preflight = await fetch(settings.baseUrl + '/api/devices/control', {
  method: 'OPTIONS', headers: { Origin: 'http://localhost:5500',
    'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type' }
});
assert.equal(preflight.status, 200);
assert.equal(preflight.headers.get('access-control-allow-origin'), 'http://localhost:5500');

h.load('dashboard.js'); await h.ready();
assert.equal(h.element('tempValue').textContent, '28.5');
assert.equal(h.element('lightValue').textContent, '0');
assert.equal(h.run('sensorChart.data.labels.length'), 1);
const input = h.element('switch-led'); input.checked = true; h.context.input = input;
await h.run('sendDeviceControl(input)');
assert.equal(input.disabled, true);
for (let attempt = 0; attempt < 20 && h.run("pendingCommands.has('led')"); attempt++) {
  await pause(150); await h.run('checkPendingCommands()');
}
assert.equal(h.run("pendingCommands.has('led')"), false);
assert.equal(input.checked, true);
assert.equal(h.toasts.at(-1).type, 'success');

function page(script) {
  const view = harness(fetch);
  for (const [key, value] of h.storage) view.storage.set(key, value);
  view.context.baseUrl = settings.baseUrl;
  view.run('API_CONFIG.BASE_URL = baseUrl');
  view.load(script);
  return view;
}
const sensors = page('data-sensor.js');
sensors.element('sensorTypeFilter').value = 'temperature';
sensors.element('timeSearchInput').value = '28.5';
await sensors.run('submitSensorSearch()');
assert.equal(sensors.run('totalRecords'), 1);
assert.match(sensors.element('sensorTableBody').innerHTML, /28.5/);
const samples = await sensors.run("apiRequest('/api/sensors/history')");
sensors.context.sampleTime = samples.data[0].created_at;
const copiedSensorTime = sensors.run('formatTime(sampleTime)');
assert.match(copiedSensorTime, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
sensors.element('sensorTypeFilter').value = 'time';
sensors.element('timeSearchInput').value = copiedSensorTime;
await sensors.run('submitSensorSearch()');
assert.equal(sensors.run('totalRecords'), 3);
const history = page('history.js');
history.element('deviceFilter').value = 'Đèn LED';
history.element('statusFilter').value = 'success';
history.element('timeSearchInput').value = settings.username;
await history.run('loadDeviceHistory()');
assert.equal(history.run('totalRecords'), 1);
assert.match(history.element('historyTableBody').innerHTML, /success/);
assert.match(history.element('historyTableBody').innerHTML, /ON/);
const commands = await history.run("apiRequest('/api/devices/history')");
history.context.commandTime = commands.data[0].executed_at;
history.element('timeSearchInput').value = history.run('formatTime(commandTime)');
await history.run('loadDeviceHistory()');
assert.equal(history.run('totalRecords'), 1);
h.run('Auth.logout()');
assert.equal(h.storage.has('iot_auth_token'), false);
await assert.rejects(h.run("apiRequest('/api/devices/status', {silent:true})"), error => error.status === 401);
console.log('PASS: frontend JS → HTTP/JWT/CORS → MQTT sensor/control/ACK → history → logout (DOM/Chart doubles)');
if (process.env.IOT_FRONTEND_BROWSER) {
  const { browserSmoke } = await import('./browser-smoke.mjs');
  await browserSmoke(settings, process.env.IOT_FRONTEND_BROWSER);
}
