/* === Core Dashboard: CSRF, fetch wrapper, tab switching, userinfo === */

function getCsrfToken() {
    const match = document.cookie.match(/XSRF-TOKEN=([^;]+)/);
    return match ? decodeURIComponent(match[1]) : null;
}

async function fetchApi(url, options = {}) {
    const defaults = {
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        }
    };

    const method = (options.method || 'GET').toUpperCase();
    if (method !== 'GET' && method !== 'HEAD') {
        const token = getCsrfToken();
        if (token) {
            defaults.headers['X-XSRF-TOKEN'] = token;
        }
    }

    const merged = {
        ...defaults,
        ...options,
        headers: { ...defaults.headers, ...(options.headers || {}) }
    };

    const response = await fetch(url, merged);

    if (response.status === 401 || response.status === 403) {
        window.location.href = '/oauth2/authorization/sso';
        throw new Error('Authentication required');
    }

    if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new Error(text || `HTTP ${response.status}`);
    }

    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
        return response.json();
    }
    return null;
}

/* --- Tab switching --- */

function initTabs() {
    const buttons = document.querySelectorAll('.tab-btn');
    buttons.forEach(btn => {
        btn.addEventListener('click', () => switchTab(btn.dataset.tab));
    });
}

function switchTab(tabName) {
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.tab === tabName);
    });
    document.querySelectorAll('.tab-panel').forEach(panel => {
        panel.classList.toggle('active', panel.id === 'tab-' + tabName);
    });

    if (tabName === 'chat') chatTab.init();
    if (tabName === 'skills') skillsTab.init();
    if (tabName === 'admin') adminTab.init();
}

/* --- User info --- */

async function loadUserInfo() {
    try {
        const info = await fetchApi('/admin/api/userinfo');
        const el = document.getElementById('username');
        if (el && info) {
            el.textContent = info.name || 'admin';
        }
    } catch (e) {
        // User info display is non-critical
    }
}

/* --- Utility --- */

function escapeHtml(str) {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function formatTimestamp(ts) {
    if (!ts) return '-';
    try {
        const d = new Date(ts);
        return d.toLocaleString();
    } catch (e) {
        return ts;
    }
}
