  let currentView = 'products';
  let orderPage = 1;
  let orderPageSize = 20;
  let currentOrderId = null;
  let editingOrderId = null;
  let editingItemId = null;
  /** 下拉/搜索用产品缓存 */
  let productOptions = [];
  /** 新增/编辑订单弹窗里的明细草稿 */
  let orderFormItems = [];

  async function loadProductOptions() {
    const r = await api.get('/api/products?pageNo=1&pageSize=1000');
    if (r.code !== 0) { toast(r.message || '产品列表加载失败'); return; }
    productOptions = r.data.records || [];
    refreshModelDatalist();
  }

  function refreshModelDatalist() {
    const dl = document.getElementById('model-options');
    if (!dl) return;
    dl.innerHTML = productOptions.map(p =>
      `<option value="${esc(p.model)}">${esc(p.brand ? p.brand + ' / ' + p.model : p.model)}</option>`
    ).join('');
  }

  function findProductByModelText(modelText) {
    const m = String(modelText || '').trim();
    if (!m) return null;
    return productOptions.find(p => String(p.model || '').trim() === m) || null;
  }

  function pageOrders(delta) {
    const next = orderPage + delta;
    if (next < 1) return;
    orderPage = next;
    loadOrders();
  }

  async function loadOrders() {
    const orderNo = document.getElementById('q-order-no').value.trim();
    const customer = document.getElementById('q-customer').value.trim();
    const month = document.getElementById('q-month').value.trim();
    let url = `/api/orders?pageNo=${orderPage}&pageSize=${orderPageSize}`;
    if (orderNo) url += `&orderNo=${encodeURIComponent(orderNo)}`;
    if (customer) url += `&customerName=${encodeURIComponent(customer)}`;
    if (month) url += `&month=${encodeURIComponent(month)}`;
    const r = await api.get(url);
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    const page = r.data;
    document.getElementById('order-page-info').textContent = `第 ${page.current} 页 · 共 ${page.total} 条`;
    const body = document.getElementById('order-body');
    const rows = page.records || [];
    if (!rows.length) {
      body.innerHTML = '<tr><td colspan="8" class="empty">暂无订单</td></tr>';
      return;
    }
    body.innerHTML = rows.map(o => `
      <tr class="clickable" onclick="openOrderDetail(${o.id})">
        <td><span class="model-link">${esc(o.orderNo)}</span></td>
        <td class="cell-strong">${esc(o.customerName)}</td>
        <td class="cell-sub nowrap">${fmtTime(o.orderTime)}</td>
        <td class="num cell-sub">${fmtMoney(o.expectedReceipt)}</td>
        <td class="num">${fmtMoney(o.totalReceived)}</td>
        <td class="num cell-sub">${fmtMoney(o.totalPurchase)}</td>
        <td class="num money-hl">${fmtMoney(o.actualTotalProfit)}</td>
        <td class="actions">
          <div class="btn-row">
            <button class="ghost" onclick="event.stopPropagation(); openOrderDetail(${o.id})">详情</button>
            <button class="ghost danger" onclick="event.stopPropagation(); deleteOrder(${o.id})">删除</button>
          </div>
        </td>
      </tr>`).join('');
  }

  async function openOrderDetail(id) {
    const r = await api.get(`/api/orders/${id}`);
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    const d = r.data;
    currentOrderId = d.id;
    document.getElementById('order-detail-title').textContent = `订单 ${d.orderNo}`;
    // 信息条只放单据属性；金额进摘要卡
    document.getElementById('order-detail-meta').innerHTML = `
      <div><div class="label">订单号</div><div class="val">${esc(d.orderNo)}</div></div>
      <div><div class="label">客户名称</div><div class="val">${esc(d.customerName)}</div></div>
      <div><div class="label">下订时间</div><div class="val">${fmtTime(d.orderTime)}</div></div>
    `;
    const profitNum = d.actualTotalProfit == null ? 0 : Number(d.actualTotalProfit);
    const profitClass = profitNum > 0 ? 'positive' : (profitNum < 0 ? 'negative' : 'neutral');
    document.getElementById('order-summary-cards').innerHTML = `
      <div class="summary-card neutral">
        <div class="label">预计收款</div>
        <div class="val">${fmtMoney(d.expectedReceipt)}</div>
      </div>
      <div class="summary-card neutral">
        <div class="label">实际收款（整单）</div>
        <div class="val">${fmtMoney(d.totalReceived)}</div>
      </div>
      <div class="summary-card neutral">
        <div class="label">采购额（合计）</div>
        <div class="val">${fmtMoney(d.totalPurchase)}</div>
      </div>
      <div class="summary-card ${profitClass}">
        <div class="label">实际总利润</div>
        <div class="val">${fmtMoney(d.actualTotalProfit)}</div>
        <div class="hint">实际收款 − 采购额</div>
      </div>
    `;
    const items = d.items || [];
    const body = document.getElementById('order-item-body');
    if (!items.length) {
      body.innerHTML = '<tr><td colspan="9" class="empty">暂无型号明细，点右上「添加型号」</td></tr>';
    } else {
      body.innerHTML = items.map(it => `
        <tr>
          <td class="cell-strong">
            ${it.productId
              ? `<span class="model-link" data-pid="${Number(it.productId)}" data-model="${esc(it.model)}" onclick="event.stopPropagation(); openHistory(Number(this.dataset.pid), this.dataset.model, true)">${esc(it.model)}</span>`
              : esc(it.model)}
            ${it.quoted === false ? '<span class="muted">（未报价）</span>' : ''}
          </td>
          <td class="num">${it.quantity ?? '—'}</td>
          <td class="num">${fmtMoney(it.purchaseAmount)}</td>
          <td class="num">${fmtMoney(it.salesAmount)}</td>
          <td class="num money-hl">${fmtMoney(it.profit)}</td>
          <td class="num cell-sub">${fmtMoney(it.salesUsd)}</td>
          <td class="num cell-sub">${it.exchangeRate != null ? Number(it.exchangeRate).toFixed(1) : '—'}</td>
          <td class="num cell-sub">${fmtMoney(it.salesCny)}</td>
          <td class="actions">
            <div class="btn-row">
              <button class="ghost" onclick="openItemForm(${it.id})">编辑</button>
              <button class="ghost danger" onclick="deleteOrderItem(${it.id})">删除</button>
            </div>
          </td>
        </tr>`).join('');
    }
    document.getElementById('order-detail-totals').textContent =
      `预计收款 ${fmtMoney(d.expectedReceipt)} · 实际收款（整单）${fmtMoney(d.totalReceived)} · 采购额合计 ${fmtMoney(d.totalPurchase)} · 实际总利润 ${fmtMoney(d.actualTotalProfit)}`;
    showView('order-detail');
    loadProductOptions();
  }


  /** 导入采购发票：To/PO#/send Date + Part Number/Price/Qty */
  async function importInvoice(input) {
    const file = input.files[0];
    input.value = '';
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    const resp = await fetch('/api/orders/import-invoice', {
      method: 'POST',
      body: form,
    });
    let r;
    try {
      r = await resp.json();
    } catch (_) {
      toast('导入失败，文件无法解析');
      return;
    }
    if (r.code !== 0) {
      toast(r.message || '导入失败');
      return;
    }
    const d = r.data || {};
    const parts = [
      `订单 ${d.orderNo || ''} 已生成（${d.itemsImported || 0} 行明细）`,
    ];
    if (d.productsCreated) {
      parts.push(`新增 ${d.productsCreated} 个型号，请去补充报价`);
    }
    if (d.quotesNeedSupplement) {
      parts.push(`${d.quotesNeedSupplement} 个型号待补充报价`);
    }
    if (d.quotesUpdated) {
      parts.push(`更新报价 ${d.quotesUpdated} 条`);
    }
    if (d.quotesUnchanged) {
      parts.push(`报价一致 ${d.quotesUnchanged} 条`);
    }
    toast(parts.join('；'));
    if (d.productsCreated || d.quotesNeedSupplement) {
      setTimeout(() => {
        const n = (d.productsCreated || 0) + (d.quotesNeedSupplement || 0);
        toast(`提醒：共 ${n} 个型号需要补充报价/采购价`);
      }, 2800);
    }
    orderPage = 1;
    loadOrders();
    loadProductOptions();
    if (d.orderNo) {
      // 打开新订单详情便于核对
      const list = await api.get(`/api/orders?pageNo=1&pageSize=5&orderNo=${encodeURIComponent(d.orderNo)}`);
      const rec = list.data && list.data.records && list.data.records[0];
      if (rec) openOrderDetail(rec.id);
    }
  }


  /** 导入表格预览：解析但不落库 */
  let tableImportItems = [];

  async function importTable(input) {
    const file = input.files[0];
    input.value = '';
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    const resp = await fetch('/api/orders/import-table/preview', {
      method: 'POST',
      body: form,
    });
    let r;
    try {
      r = await resp.json();
    } catch (_) {
      toast('解析失败，文件无法识别');
      return;
    }
    if (r.code !== 0) {
      toast(r.message || '解析失败');
      return;
    }
    const d = r.data || {};
    tableImportItems = d.items || [];
    document.getElementById('ti-customer').value = '';
    document.getElementById('ti-summary').textContent =
      `共 ${tableImportItems.length} 行 · 预计收款 ${fmtMoney(d.expectedReceipt)} · 采购额 ${fmtMoney(d.totalPurchase)} · 订单号将自动生成（${d.suggestedOrderNo || ''}）`;
    const body = document.getElementById('ti-body');
    if (!tableImportItems.length) {
      body.innerHTML = '<tr><td colspan="4" class="empty">无明细</td></tr>';
    } else {
      body.innerHTML = tableImportItems.map(it => `
        <tr>
          <td class="cell-strong">${esc(it.model)}</td>
          <td class="num">${it.quantity ?? '—'}</td>
          <td class="num">${fmtMoney(it.purchaseAmount)}</td>
          <td class="num">${fmtMoney(it.salesUsd)}</td>
        </tr>`).join('');
    }
    document.getElementById('table-import-modal').classList.add('open');
  }

  /** 确认入库：客户名必填 */
  async function commitTableImport() {
    const customerName = document.getElementById('ti-customer').value.trim();
    if (!customerName) {
      toast('请填写客户名称后再导入');
      document.getElementById('ti-customer').focus();
      return;
    }
    if (!tableImportItems.length) {
      toast('没有可导入的明细');
      return;
    }
    const body = {
      customerName,
      items: tableImportItems.map(it => ({
        model: it.model,
        quantity: it.quantity != null ? it.quantity : 1,
        purchaseAmount: it.purchaseAmount != null ? it.purchaseAmount : null,
        salesUsd: it.salesUsd != null ? it.salesUsd : null,
      })),
    };
    const r = await api.send('/api/orders/import-table/commit', 'POST', body);
    if (r.code !== 0) {
      toast(r.message || '导入失败');
      return;
    }
    closeModal('table-import-modal');
    toast(`已导入订单 ${r.data && r.data.orderNo ? r.data.orderNo : ''}`);
    tableImportItems = [];
    orderPage = 1;
    loadOrders();
    if (r.data && r.data.id) openOrderDetail(r.data.id);
    else loadOrders();
  }

  /** 明细 → 保存请求：必须带上 salesUsd 快照，否则整单覆盖会清掉运费等无报价行 */
  function toSaveItem(it) {
    const src = {
      model: it.model,
      quantity: it.quantity != null ? it.quantity : 1,
    };
    if (it.productId != null) src.productId = it.productId;
    if (it.purchaseAmount != null) src.purchaseAmount = it.purchaseAmount;
    if (it.salesUsd != null) src.salesUsd = it.salesUsd;
    return src;
  }

  function openOrderModal(id) {
    editingOrderId = id || null;
    document.getElementById('order-modal-title').textContent = id ? `编辑订单 #${id}` : '新增采购订单';
    const fill = (d) => {
      document.getElementById('o-order-no').value = d.orderNo || '';
      document.getElementById('o-customer').value = d.customerName || '';
      document.getElementById('o-order-time').value = d.orderTime ? toDateTimeLocal(d.orderTime) : nowForDateTimeLocal();
      document.getElementById('o-received').value = d.totalReceived != null ? d.totalReceived : '';
      orderFormItems = (d.items || []).map(it => ({
        model: it.model || '',
        quantity: it.quantity != null ? it.quantity : 1,
        purchaseAmount: it.purchaseAmount != null ? it.purchaseAmount : '',
        salesUsd: it.salesUsd != null ? it.salesUsd : '',
      }));
      if (!orderFormItems.length) {
        orderFormItems = [{ model: '', quantity: 1, purchaseAmount: '' }];
      }
      renderOrderItemEditor();
      document.getElementById('order-modal').classList.add('open');
    };
    if (id) {
      api.get(`/api/orders/${id}`).then(r => {
        if (r.code !== 0) { toast(r.message || '操作失败'); return; }
        fill(r.data);
      });
    } else {
      fill({ orderNo: '', customerName: '', totalReceived: '', items: [] });
    }
  }

  function addOrderItemRow() {
    orderFormItems.push({ model: '', quantity: 1, purchaseAmount: '' });
    renderOrderItemEditor();
  }

  function removeOrderItemRow(idx) {
    orderFormItems.splice(idx, 1);
    renderOrderItemEditor();
  }

  function onOrderItemInput(idx, field, value) {
    const it = orderFormItems[idx];
    if (!it) return;
    it[field] = value;
    updateOrderFormPreview();
  }

  function renderOrderItemEditor() {
    const host = document.getElementById('order-item-editor');
    host.innerHTML = orderFormItems.map((it, idx) => `
      <div class="item-form-grid" style="grid-template-columns: 1.6fr 0.6fr 1fr auto; align-items:end">
        <div class="field"><label>型号 *</label>
          <input class="order-model-input" list="model-options" value="${esc(it.model)}"
                 placeholder="输入型号搜索"
                 onchange="onOrderItemInput(${idx}, 'model', this.value)" />
        </div>
        <div class="field"><label>数量</label>
          <input type="number" min="1" step="1" value="${it.quantity ?? 1}"
                 onchange="onOrderItemInput(${idx}, 'quantity', this.value === '' ? '' : Number(this.value))" />
        </div>
        <div class="field"><label>采购额</label>
          <input type="number" step="0.01" value="${it.purchaseAmount}"
                 onchange="onOrderItemInput(${idx}, 'purchaseAmount', this.value === '' ? '' : Number(this.value))" />
        </div>
        <div class="field">
          ${orderFormItems.length > 1 ? `<button class="ghost danger" type="button" onclick="removeOrderItemRow(${idx})">删除</button>` : ''}
        </div>
      </div>
    `).join('');
    updateOrderFormPreview();
  }

  /** 弹窗内预览：按型号匹配到的最新报价 + 今日汇率估算 */
  function updateOrderFormPreview() {
    const rate = currentRate();
    let totalSales = 0, totalPurchase = 0;
    const missing = [];
    orderFormItems.forEach(it => {
      const p = findProductByModelText(it.model);
      const usd = p && p.quoteUsd != null ? Number(p.quoteUsd) : null;
      const qty = Number(it.quantity) || 0;
      const purchase = it.purchaseAmount === '' || it.purchaseAmount == null ? 0 : Number(it.purchaseAmount);
      if (usd != null && rate != null) {
        totalSales += usd * rate * qty;
      } else if (it.model) {
        missing.push(it.model.trim());
      }
      totalPurchase += purchase;
    });
    const received = document.getElementById('o-received').value === ''
      ? 0 : Number(document.getElementById('o-received').value);
    const profit = totalSales - totalPurchase;
    const actualProfit = received - totalPurchase;
    let html = `
      <span>销售额约 <b>${totalSales.toFixed(2)}</b></span>
      <span>采购额 <b>${totalPurchase.toFixed(2)}</b></span>
      <span>利润约 <b>${profit.toFixed(2)}</b></span>
      <span>实际总利润约 <b>${actualProfit.toFixed(2)}</b></span>
    `;
    if (missing.length) {
      html += `<span>${esc(missing.join('、'))}：还未进行报价</span>`;
    }
    document.getElementById('order-form-preview').innerHTML = html;
  }

  async function saveOrder() {
    const orderNo = document.getElementById('o-order-no').value.trim();
    const customer = document.getElementById('o-customer').value.trim();
    const timeVal = document.getElementById('o-order-time').value;
    const receivedVal = document.getElementById('o-received').value;
    if (!orderNo) { toast('请填写订单号'); return; }
    if (!customer) { toast('请填写客户名称'); return; }
    const items = orderFormItems.filter(it => it.model && String(it.model).trim());
    if (!items.length) { toast('请至少填写一行型号'); return; }
    const body = {
      orderNo,
      customerName: customer,
      items: items.map(it => {
        const row = {
          model: String(it.model).trim(),
          quantity: it.quantity === '' || it.quantity == null ? 1 : Number(it.quantity),
        };
        if (it.purchaseAmount !== '' && it.purchaseAmount != null) row.purchaseAmount = Number(it.purchaseAmount);
        if (it.salesUsd !== '' && it.salesUsd != null) row.salesUsd = Number(it.salesUsd);
        return row;
      }),
    };
    if (receivedVal !== '') body.actualReceived = Number(receivedVal);
    if (timeVal) body.orderTime = timeVal + ':00';

    // 预先识别「库内无此型号 / 未报价」，保存后提示
    const willCreate = [];
    const unquoted = [];
    items.forEach(it => {
      const m = String(it.model).trim();
      const p = findProductByModelText(m);
      if (!p) willCreate.push(m);
      else if (p.quoteUsd == null) unquoted.push(m);
    });

    const r = editingOrderId
      ? await api.send(`/api/orders/${editingOrderId}`, 'PUT', body)
      : await api.send('/api/orders', 'POST', body);
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    closeModal('order-modal');
    toast(editingOrderId ? '订单已更新' : '订单已创建');
    if (willCreate.length || unquoted.length) {
      const parts = [];
      if (willCreate.length) parts.push(`已自动建档：${willCreate.join('、')}`);
      if (unquoted.length || willCreate.length) parts.push('该型号还未进行报价');
      setTimeout(() => toast(parts.join('；')), 600);
      loadProductOptions();
    }
    const newId = editingOrderId || (r.data && r.data.id);
    if (newId) openOrderDetail(newId);
    else loadOrders();
  }

  async function deleteOrder(id) {
    if (!id) return;
    if (!confirm('确认删除该订单？\n它的全部型号明细会一并删除，且不可恢复。')) return;
    const r = await api.send(`/api/orders/${id}`, 'DELETE');
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    toast('订单已删除');
    currentOrderId = null;
    showView('orders');
    loadOrders();
  }

  function openItemForm(itemId) {
    editingItemId = itemId || null;
    document.getElementById('item-modal-title').textContent = itemId ? '编辑型号' : '添加型号';
    loadProductOptions().then(() => fillItemForm(itemId));
    document.getElementById('item-modal').classList.add('open');
  }

  async function fillItemForm(itemId) {
    refreshModelDatalist();
    const modelInput = document.getElementById('it-model');
    if (itemId) {
      const r = await api.get(`/api/orders/${currentOrderId}`);
      if (r.code !== 0) { toast(r.message || '操作失败'); return; }
      const it = (r.data.items || []).find(x => x.id === itemId);
      if (!it) { toast('明细不存在'); return; }
      modelInput.value = it.model || '';
      document.getElementById('it-quantity').value = it.quantity != null ? it.quantity : 1;
      document.getElementById('it-purchase').value = it.purchaseAmount != null ? it.purchaseAmount : '';
    } else {
      modelInput.value = '';
      document.getElementById('it-quantity').value = 1;
      document.getElementById('it-purchase').value = '';
    }
    updateItemCalcPreview();
    modelInput.oninput = updateItemCalcPreview;
    modelInput.onchange = updateItemCalcPreview;
    document.getElementById('it-quantity').oninput = updateItemCalcPreview;
    document.getElementById('it-purchase').oninput = updateItemCalcPreview;
  }

  /** 预览：按型号文本匹配库内产品最新报价 */
  function updateItemCalcPreview() {
    const modelText = document.getElementById('it-model').value.trim();
    const p = findProductByModelText(modelText);
    const usd = p && p.quoteUsd != null ? Number(p.quoteUsd) : null;
    const rate = currentRate();
    const qty = Number(document.getElementById('it-quantity').value) || 0;
    const purchase = document.getElementById('it-purchase').value === ''
      ? null : Number(document.getElementById('it-purchase').value);

    if (!modelText) {
      document.getElementById('item-calc-preview').innerHTML = '<span>请输入型号</span>';
      return;
    }
    if (!p) {
      document.getElementById('item-calc-preview').innerHTML =
        `<span>库内无此型号，保存后将自动建档，并提醒「该型号还未进行报价」</span>`;
      return;
    }
    if (usd == null) {
      document.getElementById('item-calc-preview').innerHTML =
        `<span>已在库内，但该型号还未进行报价，销售价/利润将为空</span>`;
      return;
    }
    // 销售价 USD 与 人民币 始终成对展示
    const salesCny = rate != null ? usd * rate : null;
    const salesAmount = salesCny != null ? salesCny * qty : null;
    const profit = (purchase == null || salesAmount == null) ? null : salesAmount - purchase;
    document.getElementById('item-calc-preview').innerHTML = `
      <span>销售价(USD) <b>${usd.toFixed(2)}</b></span>
      <span>销售价(CNY) <b>${salesCny == null ? '—' : salesCny.toFixed(2)}</b></span>
      <span>汇率 <b>${rate == null ? '—' : rate.toFixed(1)}</b></span>
      <span>销售额 <b>${salesAmount == null ? '—' : salesAmount.toFixed(2)}</b></span>
      <span>利润 <b>${profit == null ? '—' : profit.toFixed(2)}</b></span>
    `;
  }

  /** 单行保存：整单覆盖提交，型号走查找/建档，价格服务端快照 */
  async function saveOrderItem() {
    if (!currentOrderId) { toast('请先打开订单'); return; }
    const modelText = document.getElementById('it-model').value.trim();
    if (!modelText) { toast('请填写型号'); return; }
    const r = await api.get(`/api/orders/${currentOrderId}`);
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    const d = r.data;
    const qty = Number(document.getElementById('it-quantity').value) || 1;
    const purchaseVal = document.getElementById('it-purchase').value;

    const matched = findProductByModelText(modelText);
    const row = {
      model: modelText,
      quantity: qty,
    };
    if (matched && matched.id != null) row.productId = matched.id;
    if (purchaseVal !== '') row.purchaseAmount = Number(purchaseVal);
    // 编辑原行时保留销售价快照（运费行尤其重要）
    const origRow = editingItemId ? (d.items || []).find(x => x.id === editingItemId) : null;
    if (origRow && origRow.salesUsd != null) row.salesUsd = origRow.salesUsd;
    else if (matched && matched.quoteUsd != null) row.salesUsd = matched.quoteUsd;

    let items = (d.items || []).map(toSaveItem);
    if (editingItemId) {
      const idx = (d.items || []).findIndex(x => x.id === editingItemId);
      if (idx >= 0) items[idx] = row;
      else items.push(row);
    } else {
      items.push(row);
    }

    const body = {
      orderNo: d.orderNo,
      customerName: d.customerName,
      orderTime: d.orderTime ? String(d.orderTime).replace(' ', 'T').slice(0, 19) : undefined,
      items,
    };
    if (d.totalReceived != null) body.actualReceived = d.totalReceived;

    const saved = await api.send(`/api/orders/${currentOrderId}`, 'PUT', body);
    if (saved.code !== 0) { toast(saved.message || '操作失败'); return; }
    closeModal('item-modal');
    if (!matched) {
      toast('已自动建档，该型号还未进行报价');
      loadProductOptions();
    } else if (matched.quoteUsd == null) {
      toast('该型号还未进行报价');
    } else {
      toast('明细已保存');
    }
    openOrderDetail(currentOrderId);
  }

  async function deleteOrderItem(itemId) {
    if (!currentOrderId || !itemId) return;
    if (!confirm('确认删除该行型号明细？')) return;
    const r = await api.get(`/api/orders/${currentOrderId}`);
    if (r.code !== 0) { toast(r.message || '操作失败'); return; }
    const d = r.data;
    const items = (d.items || []).filter(x => x.id !== itemId).map(toSaveItem);
    if (!items.length) { toast('至少保留一行明细；如需清空请删除订单'); return; }
    const body = {
      orderNo: d.orderNo,
      customerName: d.customerName,
      orderTime: d.orderTime ? String(d.orderTime).replace(' ', 'T').slice(0, 19) : undefined,
      items,
    };
    if (d.totalReceived != null) body.actualReceived = d.totalReceived;
    const saved = await api.send(`/api/orders/${currentOrderId}`, 'PUT', body);
    if (saved.code !== 0) { toast(saved.message || '操作失败'); return; }
    toast('已删除该行');
    openOrderDetail(currentOrderId);
  }
