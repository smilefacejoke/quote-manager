package com.quote.manager.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单列表行：主表字段 + 明细汇总的采购额；实际收款来自订单主表 */
@Data
public class PurchaseOrderListVO {

    private Long id;
    private String orderNo;
    private String customerName;
    private LocalDateTime orderTime;

    /** 实际收款额 = 订单主表字段（整单一笔） */
    private BigDecimal totalReceived;
    /** 采购额 = Σ 明细采购额 */
    private BigDecimal totalPurchase;
    /** 实际总利润 = 实际收款额 − 采购额 */
    private BigDecimal actualTotalProfit;

    private List<ItemSummary> items;

    /** 列表可折叠/详情预览用的精简明细 */
    @Data
    public static class ItemSummary {
        private Long id;
        private String model;
        private Integer quantity;
        private BigDecimal purchaseAmount;
    }
}
