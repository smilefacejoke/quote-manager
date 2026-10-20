package com.quote.manager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 新增/修改产品的请求体。
 * 采购价/报价/数量/报价时间仅在「新增」时用于顺带写入第一条报价，均为可选；
 * 只要填了价格就必须成对填写，且采购价 ≤ 报价。
 */
@Data
public class ProductSaveRequest {

    @NotBlank(message = "品牌不能为空")
    private String brand;

    @NotBlank(message = "型号不能为空")
    private String model;

    /** 备注（可写原规格内容） */
    private String remark;

    /** 可选：初始采购价 */
    private BigDecimal purchasePrice;

    /** 可选：初始报价 */
    private BigDecimal quotePrice;

    /** 可选：初始需求数量 */
    private Integer quantity;

    /** 可选：初始报价时间，不传默认当前 */
    private LocalDateTime quoteTime;
}
