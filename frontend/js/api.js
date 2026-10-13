/**
 * Quản lý cấu hình BASE_URL, wrapper fetch tự động gắn Bearer Token và Toast Notification
 */

const API_CONFIG = {
  BASE_URL: 'http://localhost:8080',
  TOKEN_KEY: 'iot_auth_token',
  USER_KEY: 'iot_user_info',
  DEFAULT_TIMEOUT: 10000 // 10s
};

/**
 * Hiển thị Toast thông báo nổi góc trên bên phải
 * @param {string} message - Nội dung thông báo
 * @param {'success' | 'error' | 'warning' | 'info'} type - Loại thông báo
 * @param {number} duration - Thời gian hiển thị (ms)
 */
function showToast(message, type = 'info', duration = 3500) {
  let container = document.querySelector('.toast-container');
  if (!container) {
    container = document.createElement('div');
    container.className = 'toast-container';
    document.body.appendChild(container);
  }

  const toast = document.createElement('div');
  toast.className = `toast-item ${type}`;

  let iconSvg = '';
  if (type === 'success') {
    iconSvg = `<svg class="toast-icon" viewBox="0 0 24 24" fill="#05CD99"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>`;
  } else if (type === 'error') {
    iconSvg = `<svg class="toast-icon" viewBox="0 0 24 24" fill="#EE5D50"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/></svg>`;
  } else if (type === 'warning') {
    iconSvg = `<svg class="toast-icon" viewBox="0 0 24 24" fill="#FFB547"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z"/></svg>`;
  } else {
    iconSvg = `<svg class="toast-icon" viewBox="0 0 24 24" fill="#4318FF"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"/></svg>`;
  }

  toast.innerHTML = `
    ${iconSvg}
    <div class="toast-message"></div>
    <button class="toast-close" title="Đóng">&times;</button>
  `;

  toast.querySelector('.toast-message').textContent = message;
  const closeBtn = toast.querySelector('.toast-close');
  closeBtn.addEventListener('click', () => {
    toast.remove();
  });

  container.appendChild(toast);

  setTimeout(() => {
    toast.style.animation = 'fadeOut 0.3s forwards';
    setTimeout(() => toast.remove(), 300);
  }, duration);
}

/**
 * Hàm fetch bao bọc tự động gắn Authorization Header và xử lý lỗi tập trung
 * @param {string} endpoint - Đường dẫn API (ví dụ: /api/devices/status)
 * @param {object} options - Tùy chọn fetch (method, headers, body, ...)
 * @returns {Promise<any>}
 */
let authRedirectScheduled = false;

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, char =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);
}

function formatTime(value) {
  if (!value) return '--';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  // Khớp DATE_FORMAT của API để copy nguyên thời gian từ bảng vào ô tìm kiếm.
  const parts = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Ho_Chi_Minh', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
  }).formatToParts(date);
  const fields = Object.fromEntries(parts.map(part => [part.type, part.value]));
  return `${fields.year}-${fields.month}-${fields.day} ${fields.hour}:${fields.minute}:${fields.second}`;
}

async function apiRequest(endpoint, options = {}) {
  const { silent = false, timeout = API_CONFIG.DEFAULT_TIMEOUT, ...fetchOptions } = options;
  const url = API_CONFIG.BASE_URL + endpoint;
  const headers = { 'Content-Type': 'application/json', ...(fetchOptions.headers || {}) };
  const token = localStorage.getItem(API_CONFIG.TOKEN_KEY);
  const isLogin = endpoint === '/api/auth/login';
  if (token && !isLogin) headers.Authorization = 'Bearer ' + token;
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(url, { ...fetchOptions, headers, signal: controller.signal });
    let json;
    try {
      json = await response.json();
    }
    catch (error) {
      if (error.name === 'AbortError') throw error;
      throw Object.assign(new Error('Phản hồi backend không phải JSON hợp lệ'), { status: response.status, kind: 'protocol' });
    }
    if (!response.ok) {
      if (response.status === 401 && !isLogin && !authRedirectScheduled) {
        authRedirectScheduled = true;
        localStorage.removeItem(API_CONFIG.TOKEN_KEY);
        localStorage.removeItem(API_CONFIG.USER_KEY);
        showToast('Phiên đăng nhập không hợp lệ. Vui lòng đăng nhập lại!', 'warning');
        setTimeout(() => { window.location.href = 'index.html'; }, 800);
      }
      throw Object.assign(new Error(json.message || 'Lỗi máy chủ (' + response.status + ')'),
        { status: response.status, data: json.data, kind: 'http' });
    }
    if (!json || typeof json !== 'object' || !('data' in json) || json.status !== response.status) {
      throw Object.assign(new Error('Phản hồi không đúng hợp đồng ApiResponse'), { status: response.status, kind: 'protocol' });
    }
    return json.data; // Giữ nguyên null, không trả lại envelope.
  } catch (cause) {
    const error = cause.name === 'AbortError'
      ? Object.assign(new Error('Yêu cầu hết thời gian chờ'), { status: 0, kind: 'timeout' })
      : cause.status !== undefined ? cause
        : Object.assign(new Error('Không kết nối được backend'), { status: 0, kind: 'network' });
    if (!silent && !(error.status === 401 && !isLogin)) showToast(error.message, 'error');
    throw error;
  } finally { clearTimeout(timeoutId); }
}
