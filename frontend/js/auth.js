/** Chỉ sử dụng phiên đăng nhập JWT từ backend. */
const Auth = {
  getCurrentUser() {
    try { return JSON.parse(localStorage.getItem(API_CONFIG.USER_KEY)); }
    catch (_) { return null; }
  },
  clearSession() {
    localStorage.removeItem(API_CONFIG.TOKEN_KEY);
    localStorage.removeItem(API_CONFIG.USER_KEY);
  },
  hasSession() {
    const token = localStorage.getItem(API_CONFIG.TOKEN_KEY);
    if (token && token.split('.').length !== 3) this.clearSession();
    return !!localStorage.getItem(API_CONFIG.TOKEN_KEY);
  },
  requireAuth() {
    if (this.hasSession()) return true;
    window.location.href = 'index.html';
    return false;
  },
  redirectIfAuthenticated() {
    if (this.hasSession()) window.location.href = 'dashboard.html';
  },
  async login(username, password, rememberMe = false) {
    try {
      const response = await apiRequest('/api/auth/login', {
        method: 'POST', body: JSON.stringify({ username, password }), silent: true
      });
      if (!response?.token || !response?.username) throw new Error('Phản hồi đăng nhập không hợp lệ');
      authRedirectScheduled = false;
      localStorage.setItem(API_CONFIG.TOKEN_KEY, response.token);
      localStorage.setItem(API_CONFIG.USER_KEY, JSON.stringify({ username: response.username }));
      if (rememberMe) localStorage.setItem('iot_remember_username', response.username);
      else localStorage.removeItem('iot_remember_username');
      showToast('Đăng nhập thành công!', 'success');
      setTimeout(() => { window.location.href = 'dashboard.html'; }, 600);
      return true;
    } catch (error) {
      showToast(error.message, 'error');
      return false;
    }
  },
  logout() {
    this.clearSession();
    window.location.href = 'index.html';
  }
};

document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('btnLogout')?.addEventListener('click', e => { e.preventDefault(); Auth.logout(); });
  document.getElementById('mobileMenuBtn')?.addEventListener('click', () => document.querySelector('.sidebar')?.classList.toggle('show'));
  document.querySelectorAll('.user-name').forEach(el => {
    el.textContent = Auth.getCurrentUser()?.username || '--';
  });
});

