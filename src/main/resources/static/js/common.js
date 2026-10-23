  const api = {
    async get(url) {
      return (await fetch(url)).json();
    },
    async send(url, method, body) {
      return (await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: body ? JSON.stringify(body) : undefined,
      })).json();
    }
  };


  function toast(msg) {
    const el = document.getElementById('toast');
    el.textContent = (msg == null || msg === '') ? '操作失败，请重试' : String(msg);
    el.classList.add('show');
    setTimeout(() => el.classList.remove('show'), 2200);
  }


  function nowForDateTimeLocal() {
    const d = new Date();
    const pad = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  function closeModal(id) {
    document.getElementById(id).classList.remove('open');
  }

  function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, c => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
  }

  function fmtTime(t) {
    return t ? String(t).replace('T', ' ').slice(0, 19) : '';
  }


  let todayRateUsed = null;

  async function loadTodayRate() {
    const r = await api.get('/api/rates/today');
    if (r.code !== 0) {
      document.getElementById('today-rate').textContent = '获取失败';
      return;
    }
    // 加粗：业务汇率 1 位小数；灰色小字：精确汇率 3 位小数
    todayRateUsed = Number(r.data.rateUsed);
    const used = todayRateUsed.toFixed(1);
    const precise = Number(r.data.rateDisplay).toFixed(3);
    document.getElementById('today-rate').textContent = used + ' CNY';
    document.getElementById('today-rate-detail').textContent = precise;
  }

  function currentRate() {
    return Number.isFinite(todayRateUsed) && todayRateUsed > 0 ? todayRateUsed : null;
  }

  loadTodayRate();
  loadBrands().then(loadProducts);
  loadProductOptions();


  function showView(view) {
    currentView = view;
    document.getElementById('view-products').style.display = view === 'products' ? '' : 'none';
    document.getElementById('view-orders').style.display = view === 'orders' ? '' : 'none';
    document.getElementById('view-order-detail').style.display = view === 'order-detail' ? '' : 'none';
    document.getElementById('nav-products').classList.toggle('active', view === 'products' || view === 'order-detail');
    document.getElementById('nav-orders').classList.toggle('active', view === 'orders' || view === 'order-detail');
    if (view === 'orders') loadOrders();
    if (view === 'products') loadProducts();
  }

  function fmtMoney(v) {
    return v == null ? '—' : Number(v).toFixed(2);
  }

  function toDateTimeLocal(t) {
    if (!t) return nowForDateTimeLocal();
    return String(t).replace(' ', 'T').slice(0, 16);
  }
