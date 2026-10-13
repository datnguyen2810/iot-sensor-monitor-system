import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, basename, join, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { setTimeout as pause } from 'node:timers/promises';

// Native headless browser/DOM + real HTTP + fake firmware. No real ESP or main database.
export async function browserSmoke(settings, executable) {
  const root = fileURLToPath(new URL('../', import.meta.url));
  const server = createServer(async (req, res) => {
    try {
      if (req.url === '/test-chart.js') {
        res.setHeader('Content-Type', 'text/javascript');
        res.end('window.Chart=class {constructor(_,c){this.data=c.data;} update(){}}'); return;
      }
      const relative = req.url === '/' ? 'index.html' : decodeURIComponent(req.url.split('?')[0]).slice(1);
      const path = resolve(root, relative);
      if (!path.startsWith(resolve(root) + '/') && !path.startsWith(resolve(root) + '\\')) throw new Error('invalid path');
      const ext = extname(path);
      if (!['.html', '.js', '.css'].includes(ext)) { res.writeHead(404).end(); return; }
      let content = await readFile(path, 'utf8');
      // Offline reproducibility: external Chart CDN is replaced only in the test HTTP response.
      if (ext === '.html') content = content.replace(/<script[^>]*src="https:[^"]*chart[^\"]*"[^>]*><\/script>/gi, '<script src="/test-chart.js"></script>');
      if (ext === '.css') content = content.replace(/@import[^;]*;/g, '');
      if (relative === 'js/api.js') content = content.replace('http://localhost:8080', settings.baseUrl);
      res.setHeader('Content-Type', { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8' }[ext]);
      res.end(content);
    } catch (_) { res.writeHead(404).end(); }
  });
  await new Promise(done => server.listen(0, '127.0.0.1', done));
  const profile = await mkdtemp(join(tmpdir(), 'iot-browser-test-'));
  let browser; let socket;
  const calls = new Map(); let id = 0;
  try {
    browser = spawn(executable, ['--headless=new', '--disable-gpu', '--no-first-run', '--disable-extensions',
      '--disable-background-networking', '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0',
      '--user-data-dir=' + profile, 'about:blank'], { windowsHide: true, stdio: 'ignore' });
    let launchError;
    browser.on('error', error => { launchError = error; });
    let port;
    for (let attempt = 0; attempt < 80; attempt++) {
      if (launchError) throw launchError;
      try { port = (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]; break; }
      catch (_) { await pause(100); }
    }
    assert.ok(port, 'headless browser started');
    const pages = await (await fetch('http://127.0.0.1:' + port + '/json/list')).json();
    const page = pages.find(item => item.type === 'page');
    socket = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((done, fail) => { socket.addEventListener('open', done, { once: true }); socket.addEventListener('error', fail, { once: true }); });
    socket.addEventListener('message', event => {
      const message = JSON.parse(event.data);
      if (message.id && calls.has(message.id)) {
        const callback = calls.get(message.id); calls.delete(message.id);
        if (message.error) callback.reject(new Error(message.error.message)); else callback.resolve(message.result);
      }
    });
    const send = (method, params = {}) => new Promise((resolveCall, reject) => {
      const current = ++id; calls.set(current, { resolve: resolveCall, reject });
      socket.send(JSON.stringify({ id: current, method, params }));
    });
    async function evaluate(expression) {
      const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      if (result.exceptionDetails) throw new Error('Browser evaluation failed: ' + result.exceptionDetails.text);
      return result.result.value;
    }
    async function until(expression) {
      for (let attempt = 0; attempt < 80; attempt++) {
        if (await evaluate(expression)) return;
        await pause(100);
      }
      throw new Error('Browser condition timed out: ' + expression);
    }
    const url = 'http://127.0.0.1:' + server.address().port;
    await send('Page.navigate', { url: url + '/index.html' });
    await until("typeof Auth !== 'undefined' && !!document.getElementById('loginForm')");
    // Use the actual login form handler, not a manually injected JWT.
    await evaluate(`document.getElementById('username').value=${JSON.stringify(settings.username)}; document.getElementById('password').value=${JSON.stringify(settings.password)}; document.getElementById('loginForm').requestSubmit();`);
    await until("location.pathname.endsWith('dashboard.html') && document.getElementById('tempValue')?.textContent === '28.5'");
    assert.equal(await evaluate("document.getElementById('lightValue').textContent"), '0');
    await until("!document.getElementById('switch-led').disabled");
    await evaluate("document.getElementById('switch-led').checked=false; document.getElementById('switch-led').dispatchEvent(new Event('change',{bubbles:true}));");
    await until("document.getElementById('status-led')?.textContent === 'Đã tắt' && !document.getElementById('switch-led').disabled");
    await send('Page.navigate', { url: url + '/data-sensor.html' });
    await until("typeof loadSensorHistory === 'function'");
    await until("totalRecords === 3 && document.querySelectorAll('#sensorTableBody tr').length === 3");
    await evaluate("window.sensorQueryCount=0; const originalSensorFetch=window.fetch; window.fetch=(...args)=>{if(String(args[0]).includes('/api/sensors/history')) window.sensorQueryCount++; return originalSensorFetch(...args);}; document.getElementById('sensorTypeFilter').value='temperature'; document.getElementById('sensorTypeFilter').dispatchEvent(new Event('change',{bubbles:true}));");
    assert.equal(await evaluate('window.sensorQueryCount'), 0);
    assert.equal(await evaluate('totalRecords'), 3);
    await evaluate("document.getElementById('timeSearchInput').value='28.5'; document.getElementById('btnSearch').click();");
    await until("document.getElementById('sensorTableBody')?.textContent.includes('28.5') && totalRecords === 1");
    const copiedSensorTime = await evaluate("document.querySelector('#sensorTableBody tr').lastElementChild.textContent.trim()");
    assert.match(copiedSensorTime, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
    await evaluate(`document.getElementById('sensorTypeFilter').value='time'; document.getElementById('timeSearchInput').value=${JSON.stringify(copiedSensorTime)}; document.getElementById('btnSearch').click();`);
    await until("totalRecords === 3 && document.querySelectorAll('#sensorTableBody tr').length === 3");
    await send('Page.navigate', { url: url + '/history.html' });
    await until("typeof loadDeviceHistory === 'function'");
    await evaluate("document.getElementById('deviceFilter').value='Đèn LED'; document.getElementById('statusFilter').value='success'; document.getElementById('btnSearch').click();");
    await until("document.getElementById('historyTableBody')?.textContent.includes('success') && totalRecords === 2");
    assert.equal(await evaluate("document.querySelector('#historyTableBody tr').children.length"), 6);
    assert.equal(await evaluate("document.querySelectorAll('#statusFilter option').length"), 4);
    assert.equal(await evaluate("document.getElementById('commandStatusFilter')"), null);
    const copiedCommandTime = await evaluate("document.querySelector('#historyTableBody tr').lastElementChild.textContent.trim()");
    await evaluate(`document.getElementById('timeSearchInput').value=${JSON.stringify(copiedCommandTime)}; document.getElementById('btnSearch').click();`);
    await until(`totalRecords >= 1 && [...document.querySelectorAll('#historyTableBody tr')].every(row => row.lastElementChild.textContent.trim() === ${JSON.stringify(copiedCommandTime)})`);
    await evaluate("document.getElementById('btnLogout').click()");
    await until("location.pathname.endsWith('index.html') && !localStorage.getItem('iot_auth_token')");
    console.log('PASS: native headless browser login → dashboard → control/ACK → sensor filter → history → logout (Chart CDN doubled)');
    await send('Browser.close');
  } finally {
    socket?.close();
    if (browser && browser.exitCode === null) browser.kill();
    await new Promise(done => server.close(done));
    // Only our validated temporary browser profile; never an existing user's profile.
    if (resolve(profile).startsWith(resolve(tmpdir()) + '\\') || resolve(profile).startsWith(resolve(tmpdir()) + '/')) {
      if (basename(profile).startsWith('iot-browser-test-')) {
        await pause(300);
        await rm(profile, { recursive: true, force: true, maxRetries: 8, retryDelay: 200 });
      }
    }
  }
}
