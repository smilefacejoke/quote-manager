package com.quote.manager.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单列表行：主表字段 + 由明细汇总的金额 */
@Data
public class PurchaseOrderListVO {

    private Long id;
    private String orderNo;
    private String customerName;
    private LocalDateTime orderTime;

    /** 实际收款额 = Σ 明细实际收款 */
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
        private BigDecimal actualReceived;
        private BigDecimal purchaseAmount;
    }
}
