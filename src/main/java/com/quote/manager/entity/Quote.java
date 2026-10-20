package com.quote.manager.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报价记录表 quote。
 * 一个产品可有多条，形成价格历史；quantity 为当次需求数量。
 * USD 字段在保存时按「当日业务汇率」落库，保证历史不随汇率漂移。
 */
@Data
@TableName("quote")
public class Quote {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属产品 id */
    private Long productId;

    /** 采购价（CNY） */
    private BigDecimal purchasePrice;

    /** 报价（CNY） */
    private BigDecimal quotePrice;

    /** 当次需求数量 */
    private Integer quantity;

    /** 报价(USD) = 报价CNY / 当日业务汇率 */
    private BigDecimal quoteUsd;

    /** 报价总额(USD) = 报价USD * 数量 */
    private BigDecimal quoteTotalUsd;

    /** 报价时间 */
    private LocalDateTime quoteTime;

    /** 备注，如：首报 / 客户还价 */
    private String remark;
}
