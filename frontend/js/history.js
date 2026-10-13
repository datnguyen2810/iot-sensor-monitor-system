/**
 * Logic trang Lịch sử điều khiển thiết bị
 */

if (typeof Auth !== 'undefined') {
  Auth.requireAuth();
}

let currentPage = 1;
let limit = 10;
let totalPages = 1;
let totalRecords = 0;
let requestSequence = 0;

/**
 * Tải danh sách lịch sử điều khiển thiết bị từ API
 */
async function loadDeviceHistory() {
  if (!Auth.hasSession()) return;
  const sequence = ++requestSequence;
  const pageSizeSelect = document.getElementById('pageSizeSelect');
  if (pageSizeSelect) {
    limit = parseInt(pageSizeSelect.value, 10) || limit;
  }
  const device = document.getElementById('deviceFilter')?.value || '';
  const action = document.getElementById('actionFilter')?.value || '';
  const status = document.getElementById('statusFilter')?.value || '';
  const search = document.getElementById('timeSearchInput')?.value.trim() || '';
  const sort = document.getElementById('sortOrder')?.value || 'desc';
  const tbody = document.getElementById('historyTableBody');

  tbody.innerHTML = `
    <tr>
      <td colspan="6" class="table-empty">Đang tải dữ liệu...</td>
    </tr>
  `;

  try {
    const queryParams = new URLSearchParams({
      page: currentPage,
      limit: limit,
      search: search,
      device: device,
      action: action,
      command_status: status, // UI Trạng thái = success/failed/pending; không lọc ACK ON/OFF.
      sort: sort
    });

    const result = await apiRequest(`/api/devices/history?${queryParams.toString()}`, { silent: true });
    if (sequence !== requestSequence) return;

    if (result && Array.isArray(result.data)) {
      totalRecords = result.total_records || 0;
      totalPages = result.total_pages || Math.ceil(totalRecords / limit) || 1;
      currentPage = result.current_page || currentPage;

      renderTableData(result.data);
      renderPagination(totalPages, currentPage);
    } else {
      tbody.innerHTML = `
        <tr>
          <td colspan="6" class="table-empty">Không tìm thấy bản ghi lịch sử điều khiển nào.</td>
        </tr>
      `;
      renderPagination(1, 1);
    }
  } catch (error) {
    if (sequence !== requestSequence) return;
    totalRecords = 0; totalPages = 1; currentPage = 1;
    tbody.innerHTML = '<tr><td colspan="6" class="table-empty">' + escapeHtml(error.message) + '</td></tr>';
    renderPagination(1, 1);
  }
}

/**
 * Render dữ liệu vào các hàng của bảng
 * @param {Array<object>} items 
 */
function renderTableData(items) {
  const tbody = document.getElementById('historyTableBody');

  if (items.length === 0) {
    tbody.innerHTML = `
      <tr>
        <td colspan="6" class="table-empty">Không có dữ liệu hiển thị.</td>
      </tr>
    `;
    return;
  }

  tbody.innerHTML = items.map(item => {
    const action = item.action_sent ?? '--';
    const status = ['success', 'failed', 'pending'].includes(item.command_status) ? item.command_status : '--';
    const statusClass = status === '--' ? '' : status;
    const time = item.executed_at || item.created_at || item.timestamp || '--';
    const operator = item.operator ?? 'Hệ thống (Auto)';

    return `
      <tr>
        <td>${escapeHtml(item.id)}</td>
        <td>${escapeHtml(item.device_name || '--')}</td>
        <td style="font-weight: 700;">${escapeHtml(action)}</td>
        <td>
          <span class="badge-status ${statusClass}">${escapeHtml(status)}</span>
        </td>
        <td>
          <span class="user-cell">
            <svg class="user-cell-icon" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
            </svg>
            <span>${escapeHtml(operator)}</span>
          </span>
        </td>
        <td>${escapeHtml(formatTime(time))}</td>
      </tr>
    `;
  }).join('');
}

/**
 * Render các nút phân trang
 * @param {number} total 
 * @param {number} current 
 */
function renderPagination(total, current) {
  const currentEl = document.getElementById('currentPageDisplay');
  const totalEl = document.getElementById('totalPagesDisplay');
  if (currentEl) currentEl.textContent = current;
  if (totalEl) totalEl.textContent = total;

  const container = document.getElementById('pagination');
  if (!container) return;

  if (total <= 0) {
    container.innerHTML = '';
    return;
  }

  if (total === 1) {
    container.innerHTML = `
      <button class="page-btn" disabled title="Trang trước">&lt;</button>
      <button class="page-btn active">1</button>
      <button class="page-btn" disabled title="Trang sau">&gt;</button>
    `;
    return;
  }

  let html = '';

  // Nút Prev (<)
  html += `
    <button class="page-btn" ${current === 1 ? 'disabled' : ''} onclick="goToPage(${current - 1})" title="Trang trước">
      &lt;
    </button>
  `;

  // Danh sách trang
  const pages = [];
  if (total <= 7) {
    for (let i = 1; i <= total; i++) pages.push(i);
  } else {
    pages.push(1);
    if (current > 3) pages.push('...');
    
    const start = Math.max(2, current - 1);
    const end = Math.min(total - 1, current + 1);
    for (let i = start; i <= end; i++) {
      pages.push(i);
    }

    if (current < total - 2) pages.push('...');
    pages.push(total);
  }

  pages.forEach(p => {
    if (p === '...') {
      html += `<span class="page-dots">...</span>`;
    } else {
      html += `
        <button class="page-btn ${p === current ? 'active' : ''}" onclick="goToPage(${p})">
          ${p}
        </button>
      `;
    }
  });

  // Nút Next (>)
  html += `
    <button class="page-btn" ${current === total ? 'disabled' : ''} onclick="goToPage(${current + 1})" title="Trang sau">
      &gt;
    </button>
  `;

  container.innerHTML = html;
}

/**
 * Chuyển tới trang chỉ định
 * @param {number} page 
 */
function goToPage(page) {
  if (page < 1 || page > totalPages || page === currentPage) return;
  currentPage = page;
  loadDeviceHistory();
}

/**
 * Khởi tạo sự kiện
 */
document.addEventListener('DOMContentLoaded', () => {
  const btnSearch = document.getElementById('btnSearch');
  const searchInput = document.getElementById('timeSearchInput');
  const deviceFilter = document.getElementById('deviceFilter');
  const actionFilter = document.getElementById('actionFilter');
  const statusFilter = document.getElementById('statusFilter');
  const sortOrder = document.getElementById('sortOrder');

  btnSearch.addEventListener('click', () => {
    currentPage = 1;
    loadDeviceHistory();
  });

  searchInput.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') {
      currentPage = 1;
      loadDeviceHistory();
    }
  });

  deviceFilter.addEventListener('change', () => {
    currentPage = 1;
    loadDeviceHistory();
  });

  if (actionFilter) {
    actionFilter.addEventListener('change', () => {
      currentPage = 1;
      loadDeviceHistory();
    });
  }

  statusFilter.addEventListener('change', () => {
    currentPage = 1;
    loadDeviceHistory();
  });

  sortOrder.addEventListener('change', () => {
    currentPage = 1;
    loadDeviceHistory();
  });

  const pageSizeSelect = document.getElementById('pageSizeSelect');
  if (pageSizeSelect) {
    pageSizeSelect.addEventListener('change', (e) => {
      limit = parseInt(e.target.value, 10) || 10;
      currentPage = 1;
      loadDeviceHistory();
    });
  }

  // Tải dữ liệu ban đầu
  loadDeviceHistory();
});

