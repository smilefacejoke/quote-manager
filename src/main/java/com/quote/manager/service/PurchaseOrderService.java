package com.quote.manager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.BizException;
import com.quote.manager.dto.PurchaseOrderDetailVO;
import com.quote.manager.dto.PurchaseOrderListVO;
import com.quote.manager.dto.PurchaseOrderSaveRequest;
import com.quote.manager.entity.Product;
import com.quote.manager.entity.PurchaseOrder;
import com.quote.manager.entity.PurchaseOrderItem;
import com.quote.manager.entity.Quote;
import com.quote.manager.mapper.ProductMapper;
import com.quote.manager.mapper.PurchaseOrderItemMapper;
import com.quote.manager.mapper.PurchaseOrderMapper;
import com.quote.manager.mapper.QuoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 采购订单：主表 + 型号明细。
 * <p>
 * 金额口径（与业务表格一致，采购额按正数成本填写）：
 * <ul>
 *   <li>实际收款：整单一笔，在订单主表，不按型号拆</li>
 *   <li>销售价(USD) = 该型号最新报价的 quote_usd</li>
 *   <li>汇率 = 当日业务汇率 usd_cny_used</li>
 *   <li>销售价(CNY) = 销售价(USD) × 汇率</li>
 *   <li>销售额 = 销售价(CNY) × 数量</li>
 *   <li>利润 = 销售额 − 采购额</li>
 *   <li>实际总利润 = 实际收款 − Σ 明细采购额</li>
 * </ul>
 * 价格列保存时快照，后续报价/汇率变动不影响历史订单。
 * 型号录入：库内查找；找不到则自动建档（提示尚未报价）。
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private static final int MONEY_SCALE = 2;

    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderItemMapper itemMapper;
    private final ProductMapper productMapper;
    private final QuoteMapper quoteMapper;
    private final ExchangeRateService exchangeRateService;

    /** 订单分页列表，附带明细汇总金额 */
    public Page<PurchaseOrderListVO> page(int pageNo, int pageSize, String orderNo, String customerName,
                                          String month) {
        LambdaQueryWrapper<PurchaseOrder> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(orderNo)) {
            qw.like(PurchaseOrder::getOrderNo, orderNo.trim());
        }
        if (StringUtils.hasText(customerName)) {
            qw.like(PurchaseOrder::getCustomerName, customerName.trim());
        }
        // month 格式 yyyy-MM：按下订时间落在当月内
        if (StringUtils.hasText(month)) {
            String m = month.trim();
            if (!m.matches("\\d{4}-\\d{2}")) {
                throw new BizException("月份格式应为 yyyy-MM");
            }
            java.time.YearMonth ym = java.time.YearMonth.parse(m);
            java.time.LocalDateTime start = ym.atDay(1).atStartOfDay();
            java.time.LocalDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay();
            qw.ge(PurchaseOrder::getOrderTime, start);
            qw.lt(PurchaseOrder::getOrderTime, end);
        }
        qw.orderByDesc(PurchaseOrder::getOrderTime).orderByDesc(PurchaseOrder::getId);
        Page<PurchaseOrder> orderPage = orderMapper.selectPage(new Page<>(pageNo, pageSize), qw);

        Page<PurchaseOrderListVO> result = new Page<>(
                orderPage.getCurrent(), orderPage.getSize(), orderPage.getTotal());
        if (orderPage.getRecords().isEmpty()) {
            result.setRecords(Collections.emptyList());
            return result;
        }

        List<Long> orderIds = orderPage.getRecords().stream().map(PurchaseOrder::getId).toList();
        Map<Long, List<PurchaseOrderItem>> itemsByOrder = fetchItemsByOrder(orderIds);

        List<PurchaseOrderListVO> rows = orderPage.getRecords().stream().map(o -> {
            PurchaseOrderListVO vo = new PurchaseOrderListVO();
            vo.setId(o.getId());
            vo.setOrderNo(o.getOrderNo());
            vo.setCustomerName(o.getCustomerName());
            vo.setOrderTime(o.getOrderTime());
            List<PurchaseOrderItem> items = itemsByOrder.getOrDefault(o.getId(), List.of());
            fillTotals(vo, o, items);
            vo.setItems(items.stream().map(it -> {
                PurchaseOrderListVO.ItemSummary s = new PurchaseOrderListVO.ItemSummary();
                s.setId(it.getId());
                s.setModel(it.getModel());
                s.setQuantity(it.getQuantity());
                s.setPurchaseAmount(it.getPurchaseAmount());
                return s;
            }).toList());
            return vo;
        }).toList();
        result.setRecords(rows);
        return result;
    }

    /** 订单详情：主表 + 全部明细计算列 */
    public PurchaseOrderDetailVO detail(Long id) {
        PurchaseOrder order = getOrder(id);
        List<PurchaseOrderItem> items = listItems(order.getId());
        PurchaseOrderDetailVO vo = new PurchaseOrderDetailVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setCustomerName(order.getCustomerName());
        vo.setOrderTime(order.getOrderTime());

        BigDecimal totalPurchase = BigDecimal.ZERO;
        BigDecimal expectedReceipt = BigDecimal.ZERO;
        List<PurchaseOrderDetailVO.Item> itemVos = new ArrayList<>();
        for (PurchaseOrderItem it : items) {
            PurchaseOrderDetailVO.Item iv = new PurchaseOrderDetailVO.Item();
            iv.setId(it.getId());
            iv.setProductId(it.getProductId());
            iv.setModel(it.getModel());
            iv.setQuantity(it.getQuantity());
            iv.setPurchaseAmount(it.getPurchaseAmount());
            iv.setSalesUsd(it.getSalesUsd());
            iv.setExchangeRate(it.getExchangeRate());
            iv.setSalesCny(it.getSalesCny());
            iv.setSalesAmount(it.getSalesAmount());
            iv.setProfit(it.getProfit());
            iv.setQuoted(it.getSalesUsd() != null);
            itemVos.add(iv);
            totalPurchase = totalPurchase.add(nz(it.getPurchaseAmount()));
            expectedReceipt = expectedReceipt.add(nz(it.getSalesAmount()));
        }
        vo.setItems(itemVos);
        BigDecimal received = nz(order.getActualReceived());
        vo.setExpectedReceipt(expectedReceipt.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        vo.setTotalReceived(order.getActualReceived() != null
                ? order.getActualReceived().setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : null);
        vo.setTotalPurchase(totalPurchase.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        vo.setActualTotalProfit(received.subtract(totalPurchase).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        return vo;
    }

    /**
     * 当月（或指定月）利润 = 该月订单实际总利润之和（实际收款 − 采购额）。
     * month 为空则取当前月。
     */
    public java.util.Map<String, Object> monthProfit(String month) {
        java.time.YearMonth ym = StringUtils.hasText(month)
                ? java.time.YearMonth.parse(month.trim())
                : java.time.YearMonth.now();
        java.time.LocalDateTime start = ym.atDay(1).atStartOfDay();
        java.time.LocalDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay();
        List<PurchaseOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<PurchaseOrder>()
                .ge(PurchaseOrder::getOrderTime, start)
                .lt(PurchaseOrder::getOrderTime, end));
        BigDecimal profit = BigDecimal.ZERO;
        if (!orders.isEmpty()) {
            List<Long> ids = orders.stream().map(PurchaseOrder::getId).toList();
            Map<Long, List<PurchaseOrderItem>> byOrder = fetchItemsByOrder(ids);
            for (PurchaseOrder o : orders) {
                List<PurchaseOrderItem> items = byOrder.getOrDefault(o.getId(), List.of());
                BigDecimal purchase = BigDecimal.ZERO;
                for (PurchaseOrderItem it : items) {
                    purchase = purchase.add(nz(it.getPurchaseAmount()));
                }
                profit = profit.add(nz(o.getActualReceived())).subtract(purchase);
            }
        }
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("month", ym.toString());
        body.put("orderCount", orders.size());
        body.put("profit", profit.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        return body;
    }

    /**
     * 订单号：当日日期 yyyyMMdd + 当日已添加订单数+1（三位），如 20261024001。
     * 自动递增，冲突则顺延。
     */
    public String generateOrderNo() {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDateTime dayStart = today.atStartOfDay();
        java.time.LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();
        long todayCount = orderMapper.selectCount(new LambdaQueryWrapper<PurchaseOrder>()
                .ge(PurchaseOrder::getCreatedAt, dayStart)
                .lt(PurchaseOrder::getCreatedAt, dayEnd));
        String prefix = today.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        int seq = (int) todayCount + 1;
        String base;
        do {
            base = prefix + String.format("%03d", seq);
            seq++;
        } while (orderMapper.selectCount(new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getOrderNo, base)) > 0);
        return base;
    }

    @Transactional
    public PurchaseOrder create(PurchaseOrderSaveRequest req) {
        checkOrderNo(req.getOrderNo(), null);
        PurchaseOrder order = new PurchaseOrder();
        applyHeader(order, req);
        orderMapper.insert(order);
        insertItems(order.getId(), req.getItems());
        return order;
    }

    /** 更新：整单覆盖明细（先删后插），价格按当前报价/汇率重算 */
    @Transactional
    public PurchaseOrder update(Long id, PurchaseOrderSaveRequest req) {
        PurchaseOrder order = getOrder(id);
        checkOrderNo(req.getOrderNo(), id);
        applyHeader(order, req);
        orderMapper.updateById(order);
        itemMapper.delete(new LambdaQueryWrapper<PurchaseOrderItem>()
                .eq(PurchaseOrderItem::getOrderId, id));
        insertItems(id, req.getItems());
        return order;
    }

    @Transactional
    public void delete(Long id) {
        PurchaseOrder order = getOrder(id);
        itemMapper.delete(new LambdaQueryWrapper<PurchaseOrderItem>()
                .eq(PurchaseOrderItem::getOrderId, order.getId()));
        orderMapper.deleteById(order.getId());
    }

    private void applyHeader(PurchaseOrder order, PurchaseOrderSaveRequest req) {
        order.setOrderNo(req.getOrderNo().trim());
        order.setCustomerName(req.getCustomerName().trim());
        order.setOrderTime(req.getOrderTime() != null ? req.getOrderTime() : LocalDateTime.now());
        order.setActualReceived(req.getActualReceived());
        if (order.getCreatedAt() == null) {
            order.setCreatedAt(LocalDateTime.now());
        }
    }

    /** 运费/物流费用行：只进订单，不建档、不写报价库 */
    public static boolean isFreightLine(String modelOrName) {
        if (modelOrName == null) return false;
        String s = modelOrName.trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("运费") || s.equals("shipping cost") || s.equals("shipping")
                || s.equals("freight") || s.equals("shipping fee") || s.contains("运费");
    }

    private void insertItems(Long orderId, List<PurchaseOrderSaveRequest.Item> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException("至少填写一行型号明细");
        }
        BigDecimal rate = exchangeRateService.getTodayUsedRate();
        for (PurchaseOrderSaveRequest.Item src : items) {
            PurchaseOrderItem it = new PurchaseOrderItem();
            it.setOrderId(orderId);
            String modelText = src.getModel() != null ? src.getModel().trim() : null;

            // 运费：只保存在订单明细，不进产品/报价库
            if (isFreightLine(modelText) || isFreightLine(productModelIfAny(src))) {
                it.setProductId(null);
                it.setModel(modelText != null ? modelText : "运费");
                it.setQuantity(src.getQuantity() != null ? src.getQuantity() : 1);
                it.setPurchaseAmount(src.getPurchaseAmount());
                if (src.getSalesUsd() != null) {
                    applyPricingWithUsd(it, rate, src.getSalesUsd());
                } else {
                    applyPricing(it, rate);
                }
                itemMapper.insert(it);
                continue;
            }

            Product product = resolveProduct(src);
            it.setProductId(product.getId());
            it.setModel(product.getModel());
            it.setQuantity(src.getQuantity() != null ? src.getQuantity() : 1);
            it.setPurchaseAmount(src.getPurchaseAmount());
            // 带销售价USD（导入表格/发票）→ 写入报价库，并用于本单计价
            if (src.getSalesUsd() != null) {
                syncQuoteFromUsd(product, src.getSalesUsd(), it.getQuantity(),
                        src.getPurchaseAmount(), rate);
                applyPricingWithUsd(it, rate, src.getSalesUsd());
            } else {
                applyPricing(it, rate);
            }
            itemMapper.insert(it);
        }
    }

    /**
     * 型号解析：
     * 1. 带 productId 直接用；
     * 2. 否则按型号精确查找；
     * 3. 找不到则自动建档（无品牌），前端提示「该型号还未进行报价」。
     */
    private String productModelIfAny(PurchaseOrderSaveRequest.Item src) {
        if (src.getProductId() == null) return null;
        Product p = productMapper.selectById(src.getProductId());
        return p != null ? p.getModel() : null;
    }

    private Product resolveProduct(PurchaseOrderSaveRequest.Item src) {
        // 优先型号精确匹配（产品页里存在的就以库为准），productId 仅作线索
        String model = src.getModel() != null ? src.getModel().trim() : null;
        if (StringUtils.hasText(model)) {
            Product byModel = findProductByModel(model);
            if (byModel != null) {
                return byModel;
            }
        }
        if (src.getProductId() != null) {
            Product p = productMapper.selectById(src.getProductId());
            if (p != null) {
                return p;
            }
            // 缓存里的 id 已失效：不直接报错，按型号建档继续，避免「产品报价页有、订单编辑却说不存在」
        }
        if (!StringUtils.hasText(model)) {
            throw new BizException("型号不能为空");
        }
        Product created = new Product();
        created.setBrand(null);
        created.setModel(model);
        created.setCreatedAt(LocalDateTime.now());
        productMapper.insert(created);
        return created;
    }

    private Product findProductByModel(String model) {
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<>();
        qw.eq(Product::getModel, model);
        return productMapper.selectList(qw).stream().findFirst().orElse(null);
    }

    /**
     * 价格快照：
     * 销售价USD ← 最新报价 quote_usd；汇率 ← 当日业务汇率；
     * 销售价CNY = USD × 汇率；销售额 = CNY × 数量；利润 = 销售额 − 采购额。
     * 无报价时价格列留空，不参与利润计算。
     */
    private void applyPricing(PurchaseOrderItem it, BigDecimal rate) {
        it.setExchangeRate(rate);
        BigDecimal salesUsd = null;
        if (it.getProductId() != null) {
            Quote latest = findLatestQuote(it.getProductId());
            if (latest != null) {
                salesUsd = latest.getQuoteUsd();
            }
        }
        it.setSalesUsd(salesUsd);
        applyPricingWithUsd(it, rate, salesUsd);
    }

    /** 用给定销售价USD 计算 CNY/销售额/利润；usd 为空则相关列置空 */
    private void applyPricingWithUsd(PurchaseOrderItem it, BigDecimal rate, BigDecimal salesUsd) {
        it.setExchangeRate(rate);
        it.setSalesUsd(salesUsd);
        if (salesUsd == null) {
            it.setSalesCny(null);
            it.setSalesAmount(null);
            it.setProfit(null);
            return;
        }
        BigDecimal salesCny = salesUsd.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        it.setSalesCny(salesCny);
        BigDecimal qty = it.getQuantity() != null ? BigDecimal.valueOf(it.getQuantity()) : BigDecimal.ONE;
        BigDecimal salesAmount = salesCny.multiply(qty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        it.setSalesAmount(salesAmount);
        if (it.getPurchaseAmount() != null) {
            it.setProfit(salesAmount.subtract(it.getPurchaseAmount()).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        } else {
            it.setProfit(null);
        }
    }

    /**
     * 把销售价(USD) 同步进报价表；采购额非空时再写采购价 = 采购额 ÷ 数量。
     * 采购额为空 → 不改采购价（新建报价仍用 0 占位）。
     */
    private void syncQuoteFromUsd(Product product, BigDecimal salesUsd, Integer quantity,
                                  BigDecimal purchaseAmount, BigDecimal rate) {
        BigDecimal usd = salesUsd.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        int qty = quantity != null && quantity > 0 ? quantity : 1;
        BigDecimal unitPurchase = unitPurchasePrice(purchaseAmount, qty);

        Quote latest = findLatestQuote(product.getId());
        if (latest == null) {
            Quote q = new Quote();
            q.setProductId(product.getId());
            q.setPurchasePrice(unitPurchase != null ? unitPurchase : BigDecimal.ZERO);
            q.setQuotePrice(usd.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            q.setQuantity(qty);
            q.setQuoteUsd(usd);
            q.setQuoteTotalUsd(usd.multiply(BigDecimal.valueOf(qty)).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            q.setQuoteTime(LocalDateTime.now());
            q.setRemark(unitPurchase != null ? "订单导入" : "订单导入，采购价待补充");
            quoteMapper.insert(q);
            return;
        }
        boolean usdSame = latest.getQuoteUsd() != null
                && latest.getQuoteUsd().setScale(MONEY_SCALE, RoundingMode.HALF_UP).compareTo(usd) == 0;
        // USD 相同且采购额为空 → 不动
        if (usdSame && unitPurchase == null) {
            return;
        }
        if (!usdSame) {
            latest.setQuoteUsd(usd);
            latest.setQuotePrice(usd.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            latest.setQuantity(qty);
            latest.setQuoteTotalUsd(usd.multiply(BigDecimal.valueOf(qty)).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }
        if (unitPurchase != null) {
            latest.setPurchasePrice(unitPurchase);
        }
        latest.setQuoteTime(LocalDateTime.now());
        quoteMapper.updateById(latest);
    }

    /** 采购价 = 采购额 ÷ 数量；采购额为空返回 null（表示不改采购价） */
    private BigDecimal unitPurchasePrice(BigDecimal purchaseAmount, int qty) {
        if (purchaseAmount == null) {
            return null;
        }
        return purchaseAmount.divide(BigDecimal.valueOf(qty), MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private Quote findLatestQuote(Long productId) {
        LambdaQueryWrapper<Quote> qw = new LambdaQueryWrapper<>();
        qw.eq(Quote::getProductId, productId);
        qw.orderByDesc(Quote::getQuoteTime).orderByDesc(Quote::getId);
        List<Quote> quotes = quoteMapper.selectList(qw);
        return quotes.isEmpty() ? null : quotes.get(0);
    }

    private void fillTotals(PurchaseOrderListVO vo, PurchaseOrder order, List<PurchaseOrderItem> items) {
        BigDecimal totalPurchase = BigDecimal.ZERO;
        BigDecimal expectedReceipt = BigDecimal.ZERO;
        for (PurchaseOrderItem it : items) {
            totalPurchase = totalPurchase.add(nz(it.getPurchaseAmount()));
            expectedReceipt = expectedReceipt.add(nz(it.getSalesAmount()));
        }
        BigDecimal received = nz(order.getActualReceived());
        vo.setExpectedReceipt(expectedReceipt.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        vo.setTotalReceived(order.getActualReceived());
        vo.setTotalPurchase(totalPurchase.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        vo.setActualTotalProfit(received.subtract(totalPurchase).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    private Map<Long, List<PurchaseOrderItem>> fetchItemsByOrder(List<Long> orderIds) {
        List<PurchaseOrderItem> items = itemMapper.selectList(new LambdaQueryWrapper<PurchaseOrderItem>()
                .in(PurchaseOrderItem::getOrderId, orderIds)
                .orderByAsc(PurchaseOrderItem::getId));
        return items.stream().collect(Collectors.groupingBy(PurchaseOrderItem::getOrderId));
    }

    private List<PurchaseOrderItem> listItems(Long orderId) {
        return itemMapper.selectList(new LambdaQueryWrapper<PurchaseOrderItem>()
                .eq(PurchaseOrderItem::getOrderId, orderId)
                .orderByAsc(PurchaseOrderItem::getId));
    }

    private PurchaseOrder getOrder(Long id) {
        PurchaseOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BizException("订单不存在: " + id);
        }
        return order;
    }

    private void checkOrderNo(String orderNo, Long excludeId) {
        LambdaQueryWrapper<PurchaseOrder> qw = new LambdaQueryWrapper<>();
        qw.eq(PurchaseOrder::getOrderNo, orderNo.trim());
        if (excludeId != null) {
            qw.ne(PurchaseOrder::getId, excludeId);
        }
        if (orderMapper.selectCount(qw) > 0) {
            throw new BizException("订单号已存在: " + orderNo.trim());
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
