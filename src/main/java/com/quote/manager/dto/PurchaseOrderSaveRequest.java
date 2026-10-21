package com.quote.manager.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 新建/更新采购订单。明细只提交用户输入字段，价格由服务端按规则计算落库。 */
@Data
public class PurchaseOrderSaveRequest {

    /** 订单号，手填 */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    @NotBlank(message = "客户名称不能为空")
    private String customerName;

    /** 下订时间；为空取当前 */
    private LocalDateTime orderTime;

    @Valid
    @NotEmpty(message = "至少填写一行型号明细")
    private List<Item> items;

    @Data
    public static class Item {

        /** 系统产品 id；用于取最新报价的报价 USD */
        private Long productId;

        /** 型号；productId 为空时必填 */
        private String model;

        /** 数量 */
        private Integer quantity;

        /** 实际收款(CNY)，用户填写 */
        private BigDecimal actualReceived;

        /** 采购额(CNY)，用户填写 */
        private BigDecimal purchaseAmount;
    }
}
