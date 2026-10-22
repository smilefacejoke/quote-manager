package com.quote.manager.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 采购订单主表：一单可含多行型号明细；实际收款是整单一笔 */
@Data
@TableName("purchase_order")
public class PurchaseOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号，用户手填 */
    private String orderNo;

    /** 客户名称 */
    private String customerName;

    /** 下订时间 */
    private LocalDateTime orderTime;

    /** 实际收款(CNY)：整单一笔，不按型号拆 */
    private BigDecimal actualReceived;

    private LocalDateTime createdAt;
}
