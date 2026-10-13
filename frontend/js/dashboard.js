/** Chỉ hiển thị dữ liệu backend; trạng thái thiết bị cập nhật từ backend/ACK. */
const dashboardAuthenticated = Auth.requireAuth();
let sensorChart = null;
let lastChartTimestamp = null;
let pollingTimer = null;
let pollingBusy = false;
let dashboardStopped = false;
let deviceApiReady = false;
const confirmedStates = { led: 'UNKNOWN', fan: 'UNKNOWN', ac: 'UNKNOWN' };
const pendingCommands = new Map();
const DEVICE_NAMES = {
  led: 'Đèn LED',
  fan: 'Quạt',
  ac: 'Điều hoà'
};


function chartLabel(item) {
  if (!item.timestamp) return item.time || '';
  return new Date(item.timestamp).toLocaleTimeString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', hour12: false });
}

function updateSensorCards(temp, light, humidity) {
  for (const [id, value, digits] of [['tempValue', temp, 1], ['lightValue', light, 0], ['humidityValue', humidity, 0]]) {
    const el = document.getElementById(id);
    if (el) el.textContent = value == null ? '--' : Number(value).toFixed(digits);
  }
}

function updateDeviceVisual(device, state = confirmedStates[device]) {
  const input = document.getElementById('switch-' + device);
  const card = document.getElementById('card-' + device);
  const badge = document.getElementById('status-' + device);
  const pending = pendingCommands.has(device);
  if (input) {
    input.checked = state === 'ON';
    input.disabled = !deviceApiReady || pending;
    input.closest('.switch-control')?.classList.toggle('loading', pending);
  }
  card?.classList.toggle('active-' + device, state === 'ON');
  if (badge) {
    const label = state === 'ON' ? 'Đang bật' : state === 'OFF' ? 'Đã tắt' : 'Chưa xác nhận';
    badge.textContent = pending ? (pendingCommands.get(device).unverified ? 'Chưa xác minh ACK — ' : 'Đang chờ ACK — ') + label : deviceApiReady ? label : 'Không kết nối — ' + label;
    badge.classList.toggle('on', state === 'ON');
  }
}

function initChart(chartData = []) {
  const ctx = document.getElementById('sensorChart');
  if (!ctx || typeof Chart === 'undefined') return;

  const labels = chartData.map(item => chartLabel(item));
  const tempData = chartData.map(item => item.temperature ?? null);
  const humidityData = chartData.map(item => item.humidity ?? null);
  const lightData = chartData.map(item => item.light ?? null);

  // Lưu lại mốc thời gian điểm cuối cùng
  if (chartData.length > 0) {
    lastChartTimestamp = chartData[chartData.length - 1].timestamp || null;
  }

  sensorChart = new Chart(ctx, {
    type: 'line',
    data: {
      labels: labels,
      datasets: [
        {
          label: 'Nhiệt độ (°C)',
          data: tempData,
          borderColor: '#4318FF',
          backgroundColor: 'rgba(67, 24, 255, 0.05)',
          borderWidth: 2.5,
          tension: 0.4,
          pointRadius: 2.5,
          pointHoverRadius: 6,
          pointBackgroundColor: '#4318FF',
          fill: false
        },
        {
          label: 'Độ ẩm (%)',
          data: humidityData,
          borderColor: '#EE5D50',
          backgroundColor: 'rgba(238, 93, 80, 0.05)',
          borderWidth: 2.5,
          tension: 0.4,
          pointRadius: 2.5,
          pointHoverRadius: 6,
          pointBackgroundColor: '#EE5D50',
          fill: false
        },
        {
          label: 'Ánh sáng (Lux)',
          data: lightData,
          borderColor: '#FFB547',
          backgroundColor: 'rgba(255, 181, 71, 0.05)',
          borderWidth: 2.5,
          tension: 0.4,
          pointRadius: 2.5,
          pointHoverRadius: 6,
          pointBackgroundColor: '#FFB547',
          fill: false
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      interaction: {
        mode: 'index',
        intersect: false
      },
      plugins: {
        legend: {
          display: false // Sử dụng legend tùy chỉnh bên ngoài HTML
        },
        tooltip: {
          backgroundColor: '#1B254B',
          titleFont: { family: 'Plus Jakarta Sans', size: 12, weight: '700' },
          bodyFont: { family: 'Plus Jakarta Sans', size: 12, weight: '600' },
          padding: 10,
          cornerRadius: 10,
          displayColors: true
        }
      },
      scales: {
        x: {
          grid: {
            display: false,
            drawBorder: false
          },
          ticks: {
            color: '#A3AED0',
            font: { family: 'Plus Jakarta Sans', size: 11, weight: '600' },
            maxRotation: 0,
            autoSkip: true,
            maxTicksLimit: 8
          }
        },
        y: {
          beginAtZero: true,
          grid: {
            color: '#E0E5F2',
            borderDash: [5, 5],
            drawBorder: false
          },
          ticks: {
            color: '#A3AED0',
            font: { family: 'Plus Jakarta Sans', size: 11, weight: '600' },
            stepSize: 100
          }
        }
      }
    }
  });
}


function replaceChart(points) {
  // Thay toàn bộ dataset, không trùng timestamp.
  const rows = [...new Map(points.map(item => [item.timestamp || item.time, item])).values()].slice(-15);
  lastChartTimestamp = rows.at(-1)?.timestamp || null;
  if (!sensorChart) {
    initChart(rows); return; 
  }
  sensorChart.data.labels = rows.map(chartLabel);
  ['temperature', 'humidity', 'light'].forEach((key, index) => {
    sensorChart.data.datasets[index].data = rows.map(item => item[key] ?? null);
  });
  sensorChart.update('none');
}

async function pollSensors() {
  try {
    const [chart, latest] = await Promise.all([
      apiRequest('/api/sensors/chart', { silent: true }),
      apiRequest('/api/sensors/latest', { silent: true })
    ]);
    if (!Array.isArray(chart)) throw new Error('Dữ liệu chart không hợp lệ');
    replaceChart(chart);
    updateSensorCards(latest?.temperature, latest?.light, latest?.humidity);
  } catch (error) {
    replaceChart([]);
    updateSensorCards(null, null, null);
  }
}

async function loadDeviceStatuses() {
  try {
    const rows = await apiRequest('/api/devices/status', { silent: true });
    if (!Array.isArray(rows)) throw new Error('Trạng thái thiết bị không hợp lệ');
    deviceApiReady = true;
    for (const code of Object.keys(DEVICE_NAMES)) {
      const state = rows.find(item => item.device === code)?.status;
      confirmedStates[code] = ['ON', 'OFF'].includes(state) ? state : 'UNKNOWN';
    }
  } catch (_) {
    deviceApiReady = false;
    Object.keys(DEVICE_NAMES).forEach(code => { confirmedStates[code] = 'UNKNOWN'; });
  }
  Object.keys(DEVICE_NAMES).forEach(code => updateDeviceVisual(code));
}

async function checkPendingCommands() {
  for (const [device, receipt] of pendingCommands) {
    if (!receipt.command_id) continue;
    try {
      const params = new URLSearchParams({ device: DEVICE_NAMES[device], limit: '100', sort: 'desc' });
      const history = await apiRequest('/api/devices/history?' + params, { silent: true });
      const command = history?.data?.find(row => row.command_id === receipt.command_id);
      receipt.unverified = !command;
      if (!command || command.command_status === 'pending') { updateDeviceVisual(device); continue; }
      if (!['success', 'failed'].includes(command.command_status)) continue;
      pendingCommands.delete(device);
      // Chỉ GET status xác định trạng thái cuối; không suy ra từ yêu cầu gửi.
      await loadDeviceStatuses();
      showToast(command.command_status === 'success' ? DEVICE_NAMES[device] + ': đã nhận ACK thành công' :
        DEVICE_NAMES[device] + ': lệnh thất bại/timeout, xem lịch sử', command.command_status === 'success' ? 'success' : 'error');
    } catch (_) {
      receipt.unverified = true;
      // Không giả failed khi mất mạng: giữ khóa/pending và thử lại trong chu kỳ tiếp.
    }
    updateDeviceVisual(device);
  }
}

async function restorePendingCommands() {
  // Khôi phục lệnh đang chờ sau reload, kể cả lệnh được gửi từ một phiên khác.
  try {
    const page = await apiRequest('/api/devices/history?command_status=pending&limit=100', { silent: true });
    if (!Array.isArray(page?.data)) return;
    for (const row of page.data) {
      const device = Object.keys(DEVICE_NAMES).find(code => DEVICE_NAMES[code] === row.device_name);
      if (device && !pendingCommands.has(device)) pendingCommands.set(device, { command_id: row.command_id });
    }
    Object.keys(DEVICE_NAMES).forEach(device => updateDeviceVisual(device));
  } catch (_) { /* Không suy đoán không có pending khi API lỗi. */ }
}

async function pollDashboard() {
  if (pollingBusy || dashboardStopped || !dashboardAuthenticated) return;
  if (!localStorage.getItem(API_CONFIG.TOKEN_KEY)) return;
  pollingBusy = true;
  try {
    await Promise.all([pollSensors(), loadDeviceStatuses()]);
    await restorePendingCommands();
    await checkPendingCommands();
  } finally {
    pollingBusy = false;
    if (!dashboardStopped && localStorage.getItem(API_CONFIG.TOKEN_KEY)) pollingTimer = setTimeout(pollDashboard, 2000);
  }
}

async function sendDeviceControl(input) {
  const device = input.getAttribute('data-device');
  const action = input.checked ? 'ON' : 'OFF';
  if (!deviceApiReady || pendingCommands.has(device)) {
    updateDeviceVisual(device);
    return;
  }
  pendingCommands.set(device, {});
  updateDeviceVisual(device); // Trả switch về trạng thái xác nhận trước đó, khóa khi đang chờ.
  try {
    const receipt = await apiRequest('/api/devices/control', {
      method: 'POST', body: JSON.stringify({ device, action }), silent: true
    });
    if (!receipt?.command_id || receipt.command_status !== 'pending') throw new Error('Phản hồi tiếp nhận lệnh không hợp lệ');
    pendingCommands.set(device, receipt);
    showToast(DEVICE_NAMES[device] + ': đã gửi lệnh, đang chờ ACK', 'info');
    // Chu kỳ poll tuần tự sẽ xác minh đúng command_id, không chỉ so sánh ON/OFF.
  } catch (error) {
    pendingCommands.delete(device);
    showToast(error.status === 409 ? 'Thiết bị đang có lệnh chờ. Xem lịch sử và thử lại sau.' :
      error.status === 503 ? 'MQTT chưa sẵn sàng hoặc gửi lệnh thất bại. Kiểm tra broker/lịch sử.' :
        error.status === 0 ? 'Mất kết nối: chưa xác minh được kết quả gửi, kiểm tra lịch sử trước khi thử lại.' : error.message, 'error');
    await loadDeviceStatuses();
  } finally { updateDeviceVisual(device); }
}

document.addEventListener('DOMContentLoaded', async () => {
  if (!dashboardAuthenticated) return;
  replaceChart([]);
  updateSensorCards(null, null, null);
  Object.keys(DEVICE_NAMES).forEach(code => updateDeviceVisual(code));
  document.querySelectorAll('.switch-control input[type="checkbox"]').forEach(input => {
    input.addEventListener('change', () => sendDeviceControl(input));
  });
  await pollDashboard();
});

window.addEventListener('beforeunload', () => {
  dashboardStopped = true;
  clearTimeout(pollingTimer);
});
