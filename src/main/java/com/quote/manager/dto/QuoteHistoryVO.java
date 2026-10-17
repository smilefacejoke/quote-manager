package com.quote.manager.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 历史报价条目 */
@Data
public class QuoteHistoryVO {

    private Long id;
    private Long productId;
    private BigDecimal purchasePrice;
    private BigDecimal quotePrice;
    private LocalDateTime quoteTime;
    private String remark;
}
