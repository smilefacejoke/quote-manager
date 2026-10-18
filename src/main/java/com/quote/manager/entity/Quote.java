package com.quote.manager.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报价记录表 quote。
 * 一个产品可有多条，形成价格历史。
 */
@Data
@TableName("quote")
public class Quote {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属产品 id */
    private Long productId;

    /** 采购价（成本） */
    private BigDecimal purchasePrice;

    /** 对外报价 */
    private BigDecimal quotePrice;

    /** 报价时间 */
    private LocalDateTime quoteTime;

    /** 备注，如：首报 / 客户还价 */
    private String remark;
}
