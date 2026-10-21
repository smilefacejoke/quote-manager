package com.quote.manager.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单详情：主表 + 汇总 + 带计算列的明细 */
@Data
public class PurchaseOrderDetailVO {

    private Long id;
    private String orderNo;
    private String customerName;
    private LocalDateTime orderTime;

    private BigDecimal totalReceived;
    private BigDecimal totalPurchase;
    /** 实际总利润 = 实际收款额 − 采购额 */
    private BigDecimal actualTotalProfit;

    private java.util.List<Item> items;

    /** 明细行：用户输入 + 快照/计算列，口径与业务表格一致 */
    @Data
    public static class Item {
        private Long id;
        private Long productId;
        private String model;
        private Integer quantity;

        private BigDecimal actualReceived;
        private BigDecimal purchaseAmount;

        private BigDecimal salesUsd;
        private BigDecimal exchangeRate;
        private BigDecimal salesCny;
        private BigDecimal salesAmount;
        /** 利润 = 销售额 − 采购额 */
        private BigDecimal profit;
        /** 行实际利润 = 实际收款 − 采购额（表格中的「实际总利润」列） */
        private BigDecimal actualProfit;
    }
}
