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
    public Page<PurchaseOrderListVO> page(int pageNo, int pageSize, String orderNo, String customerName) {
        LambdaQueryWrapper<PurchaseOrder> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(orderNo)) {
            qw.like(PurchaseOrder::getOrderNo, orderNo.trim());
        }
        if (StringUtils.hasText(customerName)) {
            qw.like(PurchaseOrder::getCustomerName, customerName.trim());
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

    private void insertItems(Long orderId, List<PurchaseOrderSaveRequest.Item> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException("至少填写一行型号明细");
        }
        BigDecimal rate = exchangeRateService.getTodayUsedRate();
        for (PurchaseOrderSaveRequest.Item src : items) {
            PurchaseOrderItem it = new PurchaseOrderItem();
            it.setOrderId(orderId);
            Product product = resolveProduct(src);
            it.setProductId(product.getId());
            it.setModel(product.getModel());
            it.setQuantity(src.getQuantity() != null ? src.getQuantity() : 1);
            it.setPurchaseAmount(src.getPurchaseAmount());
            // 发票导入可直接指定销售价USD；否则取最新报价
            if (src.getSalesUsd() != null) {
                it.setSalesUsd(src.getSalesUsd());
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
    private Product resolveProduct(PurchaseOrderSaveRequest.Item src) {
        if (src.getProductId() != null) {
            Product p = productMapper.selectById(src.getProductId());
            if (p == null) {
                throw new BizException("产品不存在: " + src.getProductId());
            }
            return p;
        }
        String model = src.getModel() != null ? src.getModel().trim() : null;
        if (!StringUtils.hasText(model)) {
            throw new BizException("型号不能为空");
        }
        Product existing = findProductByModel(model);
        if (existing != null) {
            return existing;
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
