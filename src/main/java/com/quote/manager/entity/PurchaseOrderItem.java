package com.quote.manager.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 采购订单明细（按型号一行）。
 * 价格字段在保存时快照落库，避免后续报价变动影响历史订单。
 */
@Data
@TableName("purchase_order_item")
public class PurchaseOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /** 关联系统产品；为空则仅保留型号文本 */
    private Long productId;

    private String model;

    private Integer quantity;

    /** 实际收款(CNY)，用户填写 */
    private BigDecimal actualReceived;

    /** 采购额(CNY)，用户填写 */
    private BigDecimal purchaseAmount;

    /** 销售价USD = 该型号最新报价的 quote_usd */
    private BigDecimal salesUsd;

    /** 业务汇率 = 当日 usd_cny_used */
    private BigDecimal exchangeRate;

    /** 销售价CNY = 销售价USD × 汇率 */
    private BigDecimal salesCny;

    /** 销售额 = 销售价CNY × 数量 */
    private BigDecimal salesAmount;

    /** 利润 = 销售额 − 采购额 */
    private BigDecimal profit;
}
