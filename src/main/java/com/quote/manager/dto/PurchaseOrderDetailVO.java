package com.quote.manager.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单详情：主表 + 汇总 + 带计算列的明细 */
@Data
public class PurchaseOrderDetailVO {

    private Long id;
    private String orderNo;
    private String customerName;
    private LocalDateTime orderTime;

    /** 预计收款 = Σ 明细销售额 */
    private BigDecimal expectedReceipt;
    /** 实际收款额 = 订单主表字段（整单一笔） */
    private BigDecimal totalReceived;
    private BigDecimal totalPurchase;
    /** 实际总利润 = 实际收款额 − 采购额 */
    private BigDecimal actualTotalProfit;

    private List<Item> items;

    /** 明细行：用户输入 + 快照/计算列 */
    @Data
    public static class Item {
        private Long id;
        private Long productId;
        private String model;
        private Integer quantity;

        private BigDecimal purchaseAmount;

        private BigDecimal salesUsd;
        private BigDecimal exchangeRate;
        private BigDecimal salesCny;
        private BigDecimal salesAmount;
        /** 利润 = 销售额 − 采购额 */
        private BigDecimal profit;
        /** 是否已有报价（false 时销售价/利润为空） */
        private Boolean quoted;
    }
}
