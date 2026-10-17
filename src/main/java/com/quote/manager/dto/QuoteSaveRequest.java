package com.quote.manager.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class QuoteSaveRequest {

    @NotNull(message = "产品ID不能为空")
    private Long productId;

    @NotNull(message = "采购价不能为空")
    @DecimalMin(value = "0", message = "采购价不能为负数")
    private BigDecimal purchasePrice;

    @NotNull(message = "报价不能为空")
    @DecimalMin(value = "0", message = "报价不能为负数")
    private BigDecimal quotePrice;

    /** 不传则默认当前时间 */
    private LocalDateTime quoteTime;

    private String remark;
}
