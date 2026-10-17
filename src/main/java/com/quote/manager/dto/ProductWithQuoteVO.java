package com.quote.manager.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 产品 + 最新一条报价 */
@Data
public class ProductWithQuoteVO {

    private Long id;
    private String brand;
    private String model;
    private String spec;
    private LocalDateTime createdAt;

    /** 最新报价，无报价时为 null */
    private Long latestQuoteId;
    private BigDecimal purchasePrice;
    private BigDecimal quotePrice;
    private LocalDateTime quoteTime;
}
