package com.quote.manager.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 当日美元汇率表 exchange_rate：1 USD = usdCnyUsed CNY */
@Data
@TableName("exchange_rate")
public class ExchangeRate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 汇率日期 */
    private LocalDate rateDate;

    /** 接口原始汇率 */
    private BigDecimal usdCnyRaw;

    /** 业务用汇率：原始值向下取 1 位小数 */
    private BigDecimal usdCnyUsed;

    private LocalDateTime fetchedAt;
}
