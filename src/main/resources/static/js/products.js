  let productPage = 1;
  let productPageSize = 50;
  let editingProductId = null;
  let currentProductId = null;
  let currentProductLabel = '';
  let editingQuoteId = null;
  const productCache = {};
  /** 勾选中的产品 id（跨分页保留，翻页/查询后清空） */
  const selectedProductIds = new Set();

  async function loadBrands() {
    const select = document.getElementById('q-brand');
    const current = select.value;
    const r = await api.get('/api/products/brands');
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    const brands = r.data || [];
    select.innerHTML = '<option value="">全部品牌</option>'
      + brands.map(b => `<option value="${esc(b)}">${esc(b)}</option>`).join('');
    if (current && brands.includes(current)) {
      select.value = current;
    }
  }

  function pageProducts(delta) {
    const next = productPage + delta;
    if (next < 1) return;
    productPage = next;
    loadProducts();
  }

  /** 切换每页行数：回到第 1 页后重载，避免落在空白页 */
  function changePageSize() {
    productPageSize = Number(document.getElementById('page-size').value);
    productPage = 1;
    loadProducts();
  }

  /** 批量导入 Excel：一行=型号+报价，时间取导入时刻 */
  async function importExcel(input) {
    const file = input.files[0];
    input.value = '';
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    try {
      const res = await fetch('/api/products/import', { method: 'POST', body: form });
      const r = await res.json();
      if (r.code !== 0) {
        toast(r.message || '导入失败');
        return;
      }
      const d = r.data || {};
      let msg = `导入完成：新建产品 ${d.productsCreated ?? 0}，匹配已有 ${d.productsMatched ?? 0}，新增报价 ${d.quotesCreated ?? 0}`;
      if (d.errors && d.errors.length) {
        msg += `；${d.errors.length} 行有问题`;
        console.warn('导入行错误', d.errors);
      }
      toast(msg);
      loadBrands();
      loadProducts();
    } catch (e) {
      toast('导入失败：' + (e && e.message ? e.message : '未知错误'));
    }
  }

  /** 打开批量导出预览：展示勾选产品的最新报价与总金额 */
  function openExportPreview() {
    const ids = [...selectedProductIds];
    if (!ids.length) { toast('请先勾选要导出的产品'); return; }
    // 与导出顺序一致：id 倒序
    ids.sort((a, b) => b - a);
    const body = document.getElementById('export-body');
    let total = 0;
    const rowsHtml = ids.map(id => {
      const p = productCache[id];
      if (!p) return '';
      const hasQuote = p.purchasePrice != null;
      const t = p.quoteTotalUsd != null ? Number(p.quoteTotalUsd) : null;
      if (t != null) total += t;
      return `
        <tr>
          <td>${esc(p.brand || '')}</td>
          <td>${esc(p.model)}</td>
          <td class="num ${hasQuote ? '' : 'no-quote'}">${hasQuote ? Number(p.purchasePrice).toFixed(2) : '—'}</td>
          <td class="num ${hasQuote ? '' : 'no-quote'}">${hasQuote ? Number(p.quotePrice).toFixed(2) : '—'}</td>
          <td class="num ${p.quoteUsd != null ? '' : 'no-quote'}">${p.quoteUsd != null ? Number(p.quoteUsd).toFixed(2) : '—'}</td>
          <td class="num ${t != null ? '' : 'no-quote'}">${t != null ? t.toFixed(2) : '—'}</td>
        </tr>`;
    }).join('');
    body.innerHTML = rowsHtml + `
      <tr class="export-total-row">
        <td colspan="5">总金额（USD）</td>
        <td class="num">${total.toFixed(2)}</td>
      </tr>`;
    document.getElementById('export-modal').classList.add('open');
  }

  /** 确认后请求后端生成 xlsx 并触发浏览器下载 */
  async function confirmExportExcel() {
    const ids = [...selectedProductIds];
    if (!ids.length) { toast('请先勾选要导出的产品'); return; }
    const resp = await fetch('/api/products/export-excel', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(ids),
    });
    const ctype = resp.headers.get('content-type') || '';
    if (!resp.ok || ctype.includes('application/json')) {
      let msg = '导出失败';
      try {
        const j = await resp.json();
        msg = j.message || msg;
      } catch (_) { /* 非 JSON 错误体 */ }
      toast(msg);
      return;
    }
    const blob = await resp.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `产品报价_${new Date().toISOString().slice(0, 19).replace(/[-:T]/g, '')}.xlsx`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    closeModal('export-modal');
    toast('已导出 Excel');
  }

  async function loadProducts() {
    const brand = document.getElementById('q-brand').value.trim();
    const model = document.getElementById('q-model').value.trim();
    let url = `/api/products?pageNo=${productPage}&pageSize=${productPageSize}`;
    if (brand) url += `&brand=${encodeURIComponent(brand)}`;
    if (model) url += `&model=${encodeURIComponent(model)}`;
    const r = await api.get(url);
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    const page = r.data;
    const rows = page.records || [];
    // 换页/查询后清空勾选，避免删到看不见的行
    selectedProductIds.clear();
    document.getElementById('check-all').checked = false;
    updateBatchDeleteBtn();
    document.getElementById('product-page-info').textContent = `第 ${page.current} 页 · 共 ${page.total} 条`;
    const body = document.getElementById('product-body');
    if (!rows.length) {
      body.innerHTML = '<tr><td colspan="11" class="empty">暂无数据</td></tr>';
      return;
    }
    rows.forEach(p => { productCache[p.id] = p; });
    body.innerHTML = rows.map(p => {
      const hasQuote = p.purchasePrice != null;
      const hasQty = p.quantity != null;
      return `
      <tr class="clickable" onclick="openHistory(${p.id})">
        <td class="check" onclick="event.stopPropagation()">
          <input type="checkbox" class="row-check" data-id="${p.id}" onchange="onRowCheckChange()" />
        </td>
        <td>${esc(p.brand)}</td>
        <td><span class="model-link">${esc(p.model)}</span></td>
        <td class="num ${hasQty ? '' : 'no-quote'}">${hasQty ? p.quantity : '—'}</td>
        <td class="num ${hasQuote ? '' : 'no-quote'}">${hasQuote ? Number(p.purchasePrice).toFixed(2) : '—'}</td>
        <td class="num ${hasQuote ? '' : 'no-quote'}">${hasQuote ? Number(p.quotePrice).toFixed(2) : '—'}</td>
        <td class="num ${p.quoteUsd != null ? '' : 'no-quote'}">${p.quoteUsd != null ? Number(p.quoteUsd).toFixed(2) : '—'}</td>
        <td class="num ${p.quoteTotalUsd != null ? '' : 'no-quote'}">${p.quoteTotalUsd != null ? Number(p.quoteTotalUsd).toFixed(2) : '—'}</td>
        <td class="muted nowrap">${hasQuote ? fmtTime(p.quoteTime) : '暂无报价'}</td>
        <td class="actions">
          <div class="btn-row">
            <button class="ghost" onclick="event.stopPropagation(); openHistory(${p.id})">报价</button>
            <button class="ghost" onclick="event.stopPropagation(); editProduct(${p.id})">编辑</button>
            <button class="ghost danger" onclick="event.stopPropagation(); deleteProduct(${p.id})">删除</button>
          </div>
        </td>
        <td class="muted">${esc(p.remark || '')}</td>
      </tr>`;
    }).join('');
  }

  function openProductModal() {
    editingProductId = null;
    document.getElementById('product-modal-title').textContent = '新增产品';
    document.getElementById('product-modal-sub').textContent =
      '价格为可选：填写则顺带记录第一条报价；后续可在「报价」里追加';
    document.getElementById('p-brand').value = '';
    document.getElementById('p-model').value = '';
    document.getElementById('p-remark').value = '';
    document.getElementById('p-purchase-price').value = '';
    document.getElementById('p-quote-price').value = '';
    document.getElementById('p-quantity').value = '';
    document.getElementById('p-quote-time').value = nowForDateTimeLocal();
    document.getElementById('product-modal').classList.add('open');
  }

  function editProduct(id) {
    const p = productCache[id];
    if (!p) { toast('数据未加载'); return; }
    editingProductId = id;
    document.getElementById('product-modal-title').textContent = `编辑产品 #${id}`;
    document.getElementById('product-modal-sub').textContent =
      '已带入最新报价；保存后直接更新该条报价（不会新增历史），时间记为当前';
    document.getElementById('p-brand').value = p.brand || '';
    document.getElementById('p-model').value = p.model || '';
    document.getElementById('p-remark').value = p.remark || '';
    // 预填最新报价，避免只改数量时价格被清空
    document.getElementById('p-purchase-price').value =
      p.purchasePrice != null ? p.purchasePrice : '';
    document.getElementById('p-quote-price').value =
      p.quotePrice != null ? p.quotePrice : '';
    document.getElementById('p-quantity').value =
      p.quantity != null ? p.quantity : '';
    // 编辑时刻即本次报价时间
    document.getElementById('p-quote-time').value = nowForDateTimeLocal();
    document.getElementById('product-modal').classList.add('open');
  }

  async function saveProduct() {
    const body = {
      brand: document.getElementById('p-brand').value,
      model: document.getElementById('p-model').value,
      remark: document.getElementById('p-remark').value,
    };
    const purchaseVal = document.getElementById('p-purchase-price').value;
    const quoteVal = document.getElementById('p-quote-price').value;
    const qtyVal = document.getElementById('p-quantity').value;
    const timeVal = document.getElementById('p-quote-time').value;
    if (purchaseVal !== '') body.purchasePrice = Number(purchaseVal);
    if (quoteVal !== '') body.quotePrice = Number(quoteVal);
    if (qtyVal !== '') body.quantity = Number(qtyVal);
    if (timeVal) body.quoteTime = timeVal + ':00';
    const r = editingProductId
      ? await api.send(`/api/products/${editingProductId}`, 'PUT', body)
      : await api.send('/api/products', 'POST', body);
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    closeModal('product-modal');
    toast(editingProductId ? '产品与最新报价已更新' : '产品已创建');
    loadBrands();
    loadProducts();
  }

  async function deleteProduct(id) {
    const p = productCache[id];
    const label = p ? `${p.brand} ${p.model}` : `#${id}`;
    if (!confirm(`确认删除「${label}」？\n它的全部历史报价会一并删除，且不可恢复。`)) return;
    const r = await api.send(`/api/products/${id}`, 'DELETE');
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    selectedProductIds.delete(id);
    updateBatchDeleteBtn();
    toast('已删除');
    loadProducts();
  }

  /** 表头全选/取消全选本页 */
  function toggleCheckAll(el) {
    document.querySelectorAll('.row-check').forEach(c => {
      c.checked = el.checked;
      const id = Number(c.dataset.id);
      if (el.checked) selectedProductIds.add(id);
      else selectedProductIds.delete(id);
    });
    updateBatchDeleteBtn();
  }

  /** 单行勾选变化：同步 selectedProductIds 与表头状态 */
  function onRowCheckChange() {
    selectedProductIds.clear();
    document.querySelectorAll('.row-check:checked').forEach(c => {
      selectedProductIds.add(Number(c.dataset.id));
    });
    const all = document.querySelectorAll('.row-check');
    const checked = document.querySelectorAll('.row-check:checked');
    const checkAll = document.getElementById('check-all');
    checkAll.checked = all.length > 0 && all.length === checked.length;
    checkAll.indeterminate = checked.length > 0 && checked.length < all.length;
    updateBatchDeleteBtn();
  }

  function updateBatchDeleteBtn() {
    const btn = document.getElementById('batch-delete-btn');
    const n = selectedProductIds.size;
    btn.textContent = n > 0 ? `批量删除 (${n})` : '批量删除';
    btn.disabled = n === 0;
  }

  /** 批量删除勾选的产品：各自历史报价一并删除 */
  async function deleteSelectedProducts() {
    const ids = [...selectedProductIds];
    if (!ids.length) { toast('请先勾选要删除的产品'); return; }
    const preview = ids.slice(0, 5).map(id => {
      const p = productCache[id];
      return p ? `· ${p.brand || '(无品牌)'} / ${p.model}` : `· #${id}`;
    }).join('\n');
    const more = ids.length > 5 ? `\n… 等共 ${ids.length} 个产品` : '';
    if (!confirm(`确认删除以下 ${ids.length} 个产品？\n${preview}${more}\n它们的全部历史报价会一并删除，且不可恢复。`)) return;
    const r = await api.send('/api/products/batch-delete', 'POST', ids);
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    const deleted = r.data && r.data.deleted != null ? r.data.deleted : ids.length;
    selectedProductIds.clear();
    toast(`已删除 ${deleted} 个产品`);
    loadBrands();
    loadProducts();
  }

  async function openHistory(productId) {
    const p = productCache[productId];
    currentProductId = productId;
    currentProductLabel = p ? `${p.brand} / ${p.model}` : `产品 #${productId}`;
    document.getElementById('history-title').textContent = '历史报价';
    document.getElementById('history-sub').textContent = currentProductLabel;
    document.getElementById('history-body').innerHTML = '<tr><td colspan="8" class="empty">加载中…</td></tr>';
    document.getElementById('history-modal').classList.add('open');

    const r = await api.get(`/api/quotes?pageNo=1&pageSize=100&productId=${productId}`);
    if (r.code !== 0) {
      document.getElementById('history-body').innerHTML = `<tr><td colspan="8" class="empty">${esc(r.message)}</td></tr>`;
      return;
    }
    const rows = r.data.records || [];
    if (!rows.length) {
      document.getElementById('history-body').innerHTML = '<tr><td colspan="8" class="empty">暂无报价，点击下方「录入新报价」</td></tr>';
      return;
    }
    // 已按报价时间倒序（服务端），第一条为最新
    document.getElementById('history-body').innerHTML = rows.map((q, idx) => `
      <tr>
        <td class="num">${Number(q.purchasePrice).toFixed(2)}</td>
        <td class="num">${Number(q.quotePrice).toFixed(2)}</td>
        <td class="num">${q.quantity != null ? q.quantity : '—'}</td>
        <td class="num">${q.quoteUsd != null ? Number(q.quoteUsd).toFixed(2) : '—'}</td>
        <td class="num">${q.quoteTotalUsd != null ? Number(q.quoteTotalUsd).toFixed(2) : '—'}</td>
        <td class="muted nowrap">${fmtTime(q.quoteTime)}${idx === 0 ? '<span class="latest-tag">最新</span>' : ''}</td>
        <td>${esc(q.remark || '')}</td>
        <td>
          <button class="ghost danger" onclick="deleteQuote(${q.id})">删除</button>
        </td>
      </tr>`).join('');
  }

  function openQuoteForm() {
    editingQuoteId = null;
    document.getElementById('quote-modal-title').textContent = '录入报价';
    document.getElementById('quote-sub').textContent = currentProductLabel;
    document.getElementById('f-purchase-price').value = '';
    document.getElementById('f-quote-price').value = '';
    document.getElementById('f-quantity').value = '';
    // 默认报价时间为打开弹窗的当前时刻，格式适配 datetime-local
    document.getElementById('f-quote-time').value = nowForDateTimeLocal();
    document.getElementById('f-remark').value = '';
    document.getElementById('quote-modal').classList.add('open');
  }

  async function saveQuote() {
    const timeVal = document.getElementById('f-quote-time').value;
    const qtyVal = document.getElementById('f-quantity').value;
    const body = {
      productId: currentProductId,
      purchasePrice: Number(document.getElementById('f-purchase-price').value),
      quotePrice: Number(document.getElementById('f-quote-price').value),
      quantity: qtyVal === '' ? null : Number(qtyVal),
      quoteTime: timeVal ? timeVal + ':00' : null,
      remark: document.getElementById('f-remark').value,
    };
    const r = await api.send('/api/quotes', 'POST', body);
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    closeModal('quote-modal');
    toast('报价已保存');
    openHistory(currentProductId);
    loadProducts();
  }

  async function deleteQuote(id) {
    if (!confirm(`确认删除报价记录 #${id}？`)) return;
    const r = await api.send(`/api/quotes/${id}`, 'DELETE');
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    toast('已删除');
    openHistory(currentProductId);
    loadProducts();
  }
