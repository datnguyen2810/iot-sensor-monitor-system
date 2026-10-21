/**
 * Logic trang Lịch sử đo cảm biến
 */

if (typeof Auth !== 'undefined') {
  Auth.requireAuth();
}

let currentPage = 1;
let limit = 10;
let totalPages = 1;
let totalRecords = 0;
let requestSequence = 0;
let appliedSearch = { searchType: 'all', keyword: '' };
const SENSOR_LABELS = { temperature: 'nhiệt độ', humidity: 'độ ẩm', light: 'ánh sáng' };

function submitSensorSearch() {
  appliedSearch = {
    searchType: document.getElementById('sensorTypeFilter')?.value || 'all',
    keyword: document.getElementById('timeSearchInput')?.value.trim() || ''
  };
  currentPage = 1;
  return loadSensorHistory();
}

/**
 * Tải dữ liệu lịch sử cảm biến từ API
 */
async function loadSensorHistory() {
  if (!Auth.hasSession()) return;
  const sequence = ++requestSequence;
  const pageSizeSelect = document.getElementById('pageSizeSelect');
  if (pageSizeSelect) {
    limit = parseInt(pageSizeSelect.value, 10) || limit;
  }
  // Chuyển trang/sort chỉ dùng bộ lọc đã bấm tìm kiếm, không dùng lựa chọn đang nhập dở.
  const searchType = appliedSearch.searchType;
  const search = appliedSearch.keyword;
  const sort = document.getElementById('sortOrder')?.value || 'desc';
  const tbody = document.getElementById('sensorTableBody');

  tbody.innerHTML = `
    <tr>
      <td colspan="4" class="table-empty">Đang tải dữ liệu...</td>
    </tr>
  `;

  try {
    const queryParams = new URLSearchParams({
      page: currentPage,
      limit: limit,
      sort: sort,
      search: search,
      search_type: searchType
    });

    const result = await apiRequest(`/api/sensors/history?${queryParams.toString()}`, { silent: true });
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
          <td colspan="4" class="table-empty">Không tìm thấy dữ liệu cảm biến phù hợp.</td>
        </tr>
      `;
      renderPagination(1, 1);
    }
  } catch (error) {
    if (sequence !== requestSequence) return;
    totalRecords = 0; totalPages = 1; currentPage = 1;
    tbody.innerHTML = '<tr><td colspan="4" class="table-empty">' + escapeHtml(error.message) + '</td></tr>';
    renderPagination(1, 1);
  }
}

/**
 * Hiển thị dữ liệu vào bảng
 * @param {Array<object>} items 
 */
function renderTableData(items) {
  const tbody = document.getElementById('sensorTableBody');

  if (items.length === 0) {
    tbody.innerHTML = `
      <tr>
        <td colspan="4" class="table-empty">Không có dữ liệu hiển thị.</td>
      </tr>
    `;
    return;
  }

  tbody.innerHTML = items.map(item => {
    // Xác định đơn vị nếu API không trả về
    let unit = item.unit || '';
    if (!unit) {
      if (item.sensor_name && item.sensor_name.toLowerCase().includes('nhiệt')) unit = '°C';
      else if (item.sensor_name && item.sensor_name.toLowerCase().includes('độ ẩm')) unit = '%';
      else if (item.sensor_name && item.sensor_name.toLowerCase().includes('sáng')) unit = 'Lux';
    }

    const valStr = `${item.value} ${unit}`.trim();

    return `
      <tr>
        <td>${escapeHtml(item.id)}</td>
        <td>${escapeHtml(item.sensor_name || '--')}</td>
        <td>${escapeHtml(valStr)}</td>
        <td>${escapeHtml(formatTime(item.created_at))}</td>
      </tr>
    `;
  }).join('');
}

/**
 * Vẽ thanh phân trang
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

  // Tạo danh sách các số trang cần hiển thị
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
 * Chuyển trang
 * @param {number} page 
 */
function goToPage(page) {
  if (page < 1 || page > totalPages || page === currentPage) return;
  currentPage = page;
  loadSensorHistory();
}

/**
 * Đăng ký các sự kiện tương tác
 */
document.addEventListener('DOMContentLoaded', () => {
  const btnSearch = document.getElementById('btnSearch');
  const searchInput = document.getElementById('timeSearchInput');
  const sensorTypeFilter = document.getElementById('sensorTypeFilter');
  const sortOrder = document.getElementById('sortOrder');

  // Nút tìm kiếm
  btnSearch.addEventListener('click', submitSensorSearch);

  // Nhấn Enter trong ô tìm kiếm
  searchInput.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') {
      return submitSensorSearch();
    }
  });

  // Chọn tiêu chí chỉ đổi placeholder; bấm Tìm Kiếm/Enter mới áp dụng.
  sensorTypeFilter.addEventListener('change', () => {
    if (sensorTypeFilter.value === 'time') {
      searchInput.placeholder = 'Nhập thời gian';
    } else if (SENSOR_LABELS[sensorTypeFilter.value]) {
      searchInput.placeholder = `Nhập giá trị ${SENSOR_LABELS[sensorTypeFilter.value]}`;
    } else {
      searchInput.placeholder = 'Nhập giá trị';
    }
  });

  // Khi thay đổi thứ tự sắp xếp
  sortOrder.addEventListener('change', () => {
    currentPage = 1;
    loadSensorHistory();
  });

  const pageSizeSelect = document.getElementById('pageSizeSelect');
  if (pageSizeSelect) {
    pageSizeSelect.addEventListener('change', (e) => {
      limit = parseInt(e.target.value, 10) || 10;
      currentPage = 1;
      loadSensorHistory();
    });
  }

  // Tải dữ liệu ban đầu
  loadSensorHistory();
});

