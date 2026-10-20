import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { harness, response } from './helpers.mjs';

test('dashboard, data sensor và history không còn các dòng ghi chú nguồn dữ liệu/ACK', () => {
  for (const page of ['dashboard', 'data-sensor', 'history']) {
    const html = readFileSync(new URL(`../${page}.html`, import.meta.url), 'utf8');
    assert.doesNotMatch(html, /sensorDataNotice|dataSourceNotice|data-source-notice|Dữ liệu từ backend|ON\/OFF là trạng thái xác nhận cuối/);
    const script = readFileSync(new URL(`../js/${page}.js`, import.meta.url), 'utf8');
    assert.doesNotMatch(script, /sensorDataNotice|dataSourceNotice|setSensorNotice/);
  }
});

test('thời gian YYYY-MM-DD HH:mm:ss, đủ số 0, đúng +07:00 và không có phần lẻ', () => {
  const h = harness();
  assert.equal(h.run("formatTime('2026-10-09T10:55:04.123456+07:00')"), '2026-10-09 10:55:04');
  assert.equal(h.run("formatTime('2026-01-02T03:04:05+07:00')"), '2026-01-02 03:04:05');
  assert.equal(h.run("formatTime('2026-10-08T17:00:00Z')"), '2026-10-09 00:00:00');
  assert.equal(h.run('formatTime(null)'), '--');
});

test('wrapper giữ data:null và giá trị 0', async () => {
  const h = harness(async () => response(null));
  assert.equal(await h.run("apiRequest('/api/sensors/latest')"), null);
  h.context.fetch = async () => response(0);
  assert.equal(await h.run("apiRequest('/api/example')"), 0);
});

test('Bearer chỉ gửi vào API được bảo vệ; control không có user giả', async () => {
  let options;
  const h = harness(async (_, value) => { options = value; return response({}); });
  await h.run("apiRequest('/api/devices/status')");
  assert.equal(options.headers.Authorization, 'Bearer test.jwt.token');
  await h.run("apiRequest('/api/auth/login', { method: 'POST' })");
  assert.equal(options.headers.Authorization, undefined);
});

test('HTTP error giữ status/data; silent không có toast', async () => {
  const h = harness(async () => response({ field: 'bad' }, 503, 'unavailable'));
  await assert.rejects(h.run("apiRequest('/api/devices/control', { silent: true })"), error => error.status === 503 && error.data.field === 'bad');
  assert.equal(h.toasts.length, 0);
});

test('timeout bao gồm đọc body và tôn trọng silent', async () => {
  const h = harness(async (_, options) => ({ status: 200, ok: true,
    json: () => new Promise((_, reject) => options.signal.addEventListener('abort', () => reject(Object.assign(new Error('abort'), { name: 'AbortError' })))) }));
  const pending = h.run("apiRequest('/api/sensors/chart', { silent: true })");
  await new Promise(resolve => setImmediate(resolve));
  for (const callback of h.timers.values()) callback();
  await assert.rejects(pending, error => error.kind === 'timeout' && error.status === 0);
  assert.equal(h.toasts.length, 0);
  assert.equal(h.timers.size, 0);
});

test('401 đồng thời chỉ một thông báo/redirect; login sai không bật demo', async () => {
  const h = harness(async () => response(null, 401, 'Sai thông tin đăng nhập'));
  await Promise.allSettled([h.run("apiRequest('/api/a', {silent:true})"), h.run("apiRequest('/api/b', {silent:true})")]);
  assert.equal(h.toasts.length, 1);
  assert.equal(h.storage.has('iot_auth_token'), false);
  assert.equal(h.timers.size, 1);
  const login = harness(async () => response(null, 401, 'Sai thông tin đăng nhập'));
  login.storage.clear();
  assert.equal(await login.run("Auth.login('wrong', 'wrong')"), false);
  assert.equal(login.run("'DEMO_MODE' in API_CONFIG"), false);
  assert.equal(login.storage.has('iot_auth_token'), false);
  assert.match(login.toasts[0].message, /Sai thông tin/);
});

test('phản hồi sai hợp đồng không bị coi là dữ liệu thật', async () => {
  const h = harness(async () => ({ status: 200, ok: true, json: async () => ({ status: 200 }) }));
  await assert.rejects(h.run("apiRequest('/api/a', {silent:true})"), error => error.kind === 'protocol');
});

test('token phiên xem thử cũ bị loại, chỉ chấp nhận phiên JWT', () => {
  const h = harness();
  h.storage.set('iot_auth_token', 'demo_token_old');
  assert.equal(h.run('Auth.hasSession()'), false);
  assert.equal(h.run('typeof Auth.startDemo'), 'undefined');
});

test('dashboard offline không có số mẫu và khóa điều khiển', async () => {
  const h = harness(); h.load('dashboard.js'); await h.ready();
  assert.equal(h.element('tempValue').textContent, '--');
  assert.equal(h.element('switch-led').disabled, true);
  await h.run('pollDashboard()');
  assert.equal(h.element('tempValue').textContent, '--');
});

test('backend rỗng không render số mẫu; UNKNOWN khác OFF', async () => {
  const h = harness(async url => response(url.endsWith('/latest') ? null : url.endsWith('/status') ? [{ device: 'led', status: 'UNKNOWN' }] : []));
  h.load('dashboard.js'); await h.ready();
  assert.equal(h.element('tempValue').textContent, '--');
  assert.equal(h.run('sensorChart.data.labels.length'), 0);
  assert.equal(h.element('status-led').textContent, 'Chưa xác nhận');
});

test('chart thay mẫu bằng dữ liệu thật, giữ số 0 và khóa timestamp đầy đủ', async () => {
  const row = { timestamp: '2026-10-08T10:30:00+07:00', temperature: 0, humidity: 0, light: 0 };
  const h = harness(async url => response(url.endsWith('/latest') ? row : url.endsWith('/chart') ? [row, row] : []));
  h.load('dashboard.js'); await h.ready();
  assert.equal(h.element('tempValue').textContent, '0.0');
  assert.equal(h.run('sensorChart.data.labels.length'), 1);
  await h.run('pollDashboard()');
  assert.equal(h.run('sensorChart.data.labels.length'), 1);
  h.context.points = [row, { ...row, timestamp: '2026-10-09T10:30:00+07:00' }];
  h.run('replaceChart(points)');
  assert.equal(h.run('sensorChart.data.labels.length'), 2);
});

test('chart chỉ tải lần đầu; latest thêm cuối, bỏ mẫu trùng và giữ tối đa 15 điểm', async () => {
  const rows = Array.from({ length: 15 }, (_, i) => ({
    timestamp: `2026-10-14T10:30:${String(i).padStart(2, '0')}+07:00`,
    temperature: i, humidity: i + 30, light: i + 100
  }));
  let latest = rows.at(-1);
  let chartCalls = 0; let latestCalls = 0;
  const h = harness(async url => {
    if (url.endsWith('/chart')) { chartCalls++; return response(rows); }
    if (url.endsWith('/latest')) { latestCalls++; return response(latest); }
    return response([]);
  });
  h.load('dashboard.js');
  await h.run('pollSensors()');
  await h.run('pollSensors()');
  assert.equal(chartCalls, 1);
  assert.equal(latestCalls, 2);
  assert.equal(h.run('sensorChart.data.labels.length'), 15);
  latest = { timestamp: '2026-10-14T10:30:15+07:00', temperature: 0, humidity: 0, light: 0 };
  await h.run('pollSensors()');
  assert.equal(chartCalls, 1);
  assert.deepEqual(Array.from(h.run('sensorChart.data.datasets[0].data')), [...rows.slice(1).map(r => r.temperature), 0]);
  assert.equal(h.run('sensorChart.data.datasets[1].data.at(-1)'), 0);
  assert.equal(h.run('sensorChart.data.datasets[2].data.at(-1)'), 0);
  assert.equal(h.run('sensorChart.data.labels.at(-1)'), '10:30:15');
  assert.equal(h.element('tempValue').textContent, '0.0');
  latest = rows[0];
  await h.run('pollSensors()');
  assert.equal(h.run('lastChartTimestamp'), '2026-10-14T10:30:15+07:00');
  latest = { ...latest, timestamp: '2026-10-15T10:30:15+07:00' };
  await h.run('pollSensors()');
  assert.equal(h.run('lastChartTimestamp'), latest.timestamp);
  assert.equal(chartCalls, 1);
});

test('chart rỗng lần đầu vẫn thêm latest mới, null xóa dữ liệu, không tải chart lại', async () => {
  let latest = null; let chartCalls = 0;
  const h = harness(async url => {
    if (url.endsWith('/chart')) { chartCalls++; return response([]); }
    return response(latest);
  });
  h.load('dashboard.js');
  await h.run('pollSensors()');
  latest = { timestamp: '2026-10-14T10:30:00+07:00', temperature: 28, humidity: 60, light: 100 };
  await h.run('pollSensors()');
  assert.equal(h.run('sensorChart.data.labels.length'), 1);
  latest = null;
  await h.run('pollSensors()');
  assert.equal(h.run('sensorChart.data.labels.length'), 0);
  assert.equal(h.element('tempValue').textContent, '--');
  assert.equal(chartCalls, 1);
});

test('lỗi latest hoặc chart: xóa dữ liệu, tải lại chart khi kết nối phục hồi', async () => {
  let chartCalls = 0; let offline = false; let chartBroken = false;
  const row = { timestamp: '2026-10-14T10:30:00+07:00', temperature: 28, humidity: 60, light: 100 };
  const h = harness(async url => {
    if (url.endsWith('/chart')) { chartCalls++; return response(chartBroken ? null : [row]); }
    if (offline) throw new TypeError('offline');
    return response(row);
  });
  h.load('dashboard.js');
  await h.run('pollSensors()');
  offline = true;
  await h.run('pollSensors()');
  assert.equal(h.run('chartLoaded'), false);
  assert.equal(h.run('sensorChart.data.labels.length'), 0);
  assert.equal(h.element('tempValue').textContent, '--');
  offline = false; chartBroken = true;
  await h.run('pollSensors()');
  assert.equal(h.run('chartLoaded'), false);
  chartBroken = false;
  await h.run('pollSensors()');
  assert.equal(chartCalls, 3);
  assert.equal(h.run('chartLoaded'), true);
  assert.equal(h.run('sensorChart.data.labels.length'), 1);
  await h.run('pollSensors()');
  assert.equal(chartCalls, 3);
});

test('polling không chồng chu kỳ', async () => {
  let resolve; let calls = 0;
  const wait = new Promise(done => { resolve = done; });
  const h = harness(async url => { calls++; await wait; return response(url.endsWith('/latest') ? null : []); });
  h.load('dashboard.js');
  const first = h.run('pollDashboard()');
  await h.run('pollDashboard()');
  assert.equal(calls, 3);
  resolve(); await first;
});

for (const result of ['success', 'failed']) {
  test('control pending chỉ kết thúc khi đúng command_id: ' + result, async () => {
    let body;
    const h = harness(async (url, options) => {
      if (url.endsWith('/control')) { body = JSON.parse(options.body); return response({ command_id: 42, command_status: 'pending' }, 202); }
      if (url.includes('/history')) return response({ data: [{ command_id: 41, command_status: 'success' }, { command_id: 42, command_status: result }] });
      return response([{ device: 'led', status: result === 'success' ? 'ON' : 'OFF' }]);
    });
    h.load('dashboard.js'); h.run("deviceApiReady=true; confirmedStates.led='OFF'");
    const input = h.element('switch-led'); input.checked = true; h.context.input = input;
    await h.run('sendDeviceControl(input)');
    assert.deepEqual(body, { device: 'led', action: 'ON' });
    assert.equal(input.checked, false);
    assert.equal(input.disabled, true);
    assert.match(h.element('status-led').textContent, /chờ ACK/);
    await h.run('checkPendingCommands()');
    assert.equal(input.checked, result === 'success');
    assert.equal(input.disabled, false);
    assert.equal(h.toasts.at(-1).type, result === 'success' ? 'success' : 'error');
  });
}

test('API control lỗi không mô phỏng bật thiết bị', async () => {
  const h = harness(async url => url.endsWith('/control') ? response(null, 503, 'offline') : response([{ device: 'led', status: 'OFF' }]));
  h.load('dashboard.js'); h.run("deviceApiReady=true; confirmedStates.led='OFF'");
  const input = h.element('switch-led'); input.checked = true; h.context.input = input;
  await h.run('sendDeviceControl(input)');
  assert.equal(input.checked, false);
  assert.equal(h.toasts.some(item => item.type === 'success'), false);
});

test('không nhận ACK lệnh khác và không giả failed khi mạng mất', async () => {
  const h = harness(async () => response({ data: [{ command_id: 41, command_status: 'success' }] }));
  h.load('dashboard.js'); h.run("deviceApiReady=true; pendingCommands.set('led', {command_id:42}); updateDeviceVisual('led')");
  await h.run('checkPendingCommands()');
  assert.equal(h.run("pendingCommands.has('led')"), true);
  h.context.fetch = async () => { throw new TypeError('offline'); };
  await h.run('checkPendingCommands()');
  assert.equal(h.element('switch-led').disabled, true);
  assert.equal(h.toasts.length, 0);
});

test('reload khôi phục pending theo history và khóa switch', async () => {
  const h = harness(async () => response({ data: [{ command_id: 42, device_name: 'Đèn LED', command_status: 'pending' }] }));
  h.load('dashboard.js'); h.run('deviceApiReady=true');
  await h.run('restorePendingCommands()');
  assert.equal(h.run("pendingCommands.get('led').command_id"), 42);
  assert.equal(h.element('switch-led').disabled, true);
});

for (const [script, fn, tbody] of [['data-sensor.js', 'loadSensorHistory', 'sensorTableBody'], ['history.js', 'loadDeviceHistory', 'historyTableBody']]) {
  test(script + ': lỗi thật không render mock', async () => {
    const h = harness(); h.load(script); await h.run(fn + '()');
    assert.match(h.element(tbody).innerHTML, /Không kết nối/);
    assert.doesNotMatch(h.element(tbody).innerHTML, /2026-08-16/);
  });
  test(script + ': trang rỗng không nhảy về đầu', async () => {
    const h = harness(async () => response({ data: [], current_page: 9, total_pages: 2, total_records: 11 }));
    h.load(script); await h.run(fn + '()');
    assert.match(h.element(tbody).innerHTML, /Không có dữ liệu/);
  });
  test(script + ': escape HTML từ API', () => {
    const h = harness(); h.load(script);
    h.run(`renderTableData([{id:1, sensor_name:'<img onerror=x>', device_name:'<img onerror=x>', value:0, operator:'<script>x</script>', command_status:'pending'}])`);
    assert.doesNotMatch(h.element(tbody).innerHTML, /<img onerror|<script>/);
    assert.match(h.element(tbody).innerHTML, /&lt;img/);
    if (script === 'history.js') assert.match(h.element(tbody).innerHTML, /pending/);
  });
  test(script + ': copy thời gian hiển thị được gửi nguyên vẹn lên search', async () => {
    let query;
    const timestamp = '2026-10-09T10:55:04.123456+07:00';
    const h = harness(async url => {
      query = new URL(url).searchParams;
      return response({data:[{id:1, created_at:timestamp, executed_at:timestamp}], total_records:1, total_pages:1, current_page:1});
    });
    h.load(script); await h.run(fn + '()');
    assert.match(h.element(tbody).innerHTML, /2026-10-09 10:55:04/);
    h.element('timeSearchInput').value = '2026-10-09 10:55:04';
    if (script === 'data-sensor.js') h.element('sensorTypeFilter').value = 'time';
    await h.run(script === 'data-sensor.js' ? 'submitSensorSearch()' : fn + '()');
    assert.equal(query.get('search'), '2026-10-09 10:55:04');
    if (script === 'data-sensor.js') assert.equal(query.get('search_type'), 'time');
  });
  test(script + ': phản hồi cũ không ghi đè tìm kiếm mới', async () => {
    let resolve; let call = 0;
    const h = harness(async () => ++call === 1 ? new Promise(done => { resolve = done; }) : response({ data: [], total_records: 0, total_pages: 1, current_page: 1 }));
    h.load(script); const first = h.run(fn + '()'); await h.run(fn + '()');
    resolve(response({ data: [{ id: 999, sensor_name: 'old', device_name: 'old' }], total_records: 1, total_pages: 1, current_page: 1 }));
    await first;
    assert.doesNotMatch(h.element(tbody).innerHTML, /999/);
  });
}

test('mất kết nối sau dữ liệu thật xóa card/chart và không hiển thị trạng thái cũ', async () => {
  const row = { timestamp: '2026-10-09T10:30:00+07:00', temperature: 28.5, humidity: 65, light: 100 };
  const h = harness(async url => response(url.endsWith('/latest') ? row : url.endsWith('/chart') ? [row] :
    url.endsWith('/status') ? [{device:'led',status:'ON'}] : {data:[]}));
  h.load('dashboard.js'); await h.ready();
  assert.equal(h.element('tempValue').textContent, '28.5');
  h.context.fetch = async () => { throw new TypeError('offline'); };
  await h.run('pollDashboard()');
  assert.equal(h.element('tempValue').textContent, '--');
  assert.equal(h.run('sensorChart.data.labels.length'), 0);
  assert.equal(h.element('switch-led').checked, false);
  assert.equal(h.element('switch-led').disabled, true);
});

for (const outcome of ['success', 'failed', 'pending']) {
  test('History chỉ một trạng thái và filter kết quả: ' + outcome, async () => {
    let query;
    const h = harness(async url => {
      query = new URL(url).searchParams;
      return response({data:[{id:1, action_sent:'ON', status_received:'OFF', command_status:outcome}], total_records:1, total_pages:1, current_page:1});
    });
    h.load('history.js'); h.element('statusFilter').value = outcome;
    await h.run('loadDeviceHistory()');
    assert.equal(query.get('command_status'), outcome);
    assert.equal(query.has('status'), false);
    const html = h.element('historyTableBody').innerHTML;
    assert.equal((html.match(/<td(?:>|\s)/g) || []).length, 6);
    assert.match(html, new RegExp('>' + outcome + '<'));
    assert.doesNotMatch(html, />OFF<|Chưa ACK/);
    assert.match(html, />ON<\/td>/);
  });
}

test('Data Sensor chọn tiêu chí không request/đổi bảng trước khi bấm tìm kiếm', async () => {
  let calls = 0;
  const h = harness(async () => { calls++; return response({data:[{id:1, sensor_name:'Ánh Sáng',value:100}],total_records:1,total_pages:1,current_page:1}); });
  h.load('data-sensor.js'); await h.ready();
  const oldTable = h.element('sensorTableBody').innerHTML;
  h.element('sensorTypeFilter').value = 'temperature';
  h.element('sensorTypeFilter').listeners.get('change')();
  assert.equal(calls, 1);
  assert.equal(h.element('sensorTableBody').innerHTML, oldTable);
  assert.match(h.element('timeSearchInput').placeholder, /nhiệt độ/);
});

for (const sensorType of ['all','temperature','humidity','light','time']) {
  test('Data Sensor tìm kiếm giới hạn theo tiêu chí: ' + sensorType, async () => {
    let query;
    const h = harness(async url => { query = new URL(url).searchParams; return response({data:[],total_records:0,total_pages:1,current_page:1}); });
    h.load('data-sensor.js'); await h.ready();
    h.element('sensorTypeFilter').value = sensorType;
    h.element('timeSearchInput').value = sensorType === 'time' ? '2026-10-12 16:31:07' : '29.2';
    await h.element('btnSearch').listeners.get('click')();
    assert.equal(query.has('sensor_type'), false);
    assert.equal(query.get('search_type'), sensorType);
    assert.equal(query.get('search'), h.element('timeSearchInput').value);
  });
}

test('Data Sensor đổi lựa chọn chưa áp dụng không ảnh hưởng chuyển trang/sort/page size; Enter áp dụng mới', async () => {
  const queries = [];
  const h = harness(async url => { queries.push(new URL(url).searchParams); return response({data:[],total_records:30,total_pages:3,current_page:Number(new URL(url).searchParams.get('page'))}); });
  h.load('data-sensor.js'); await h.ready();
  h.element('sensorTypeFilter').value = 'temperature'; h.element('timeSearchInput').value = '29.2';
  await h.element('btnSearch').listeners.get('click')();
  h.element('sensorTypeFilter').value = 'humidity'; h.element('timeSearchInput').value = '72.3';
  h.element('sensorTypeFilter').listeners.get('change')();
  h.run('goToPage(2)'); await new Promise(done => setImmediate(done));
  h.element('sortOrder').value = 'asc'; h.element('sortOrder').listeners.get('change')();
  await new Promise(done => setImmediate(done));
  h.element('pageSizeSelect').value = '5'; h.element('pageSizeSelect').listeners.get('change')({target:{value:'5'}});
  await new Promise(done => setImmediate(done));
  for (const query of queries.slice(1)) {
    assert.equal(query.get('search_type'), 'temperature'); assert.equal(query.get('search'), '29.2');
  }
  await h.element('timeSearchInput').listeners.get('keypress')({key:'Enter'});
  assert.equal(queries.at(-1).get('search_type'), 'humidity');
  assert.equal(queries.at(-1).get('search'), '72.3');
  assert.equal(queries.at(-1).get('page'), '1');
});
