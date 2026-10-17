package com.quote.manager.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("quote")
public class Quote {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    /** 采购价 */
    private BigDecimal purchasePrice;

    /** 报价 */
    private BigDecimal quotePrice;

    /** 报价时间 */
    private LocalDateTime quoteTime;

    private String remark;
}
