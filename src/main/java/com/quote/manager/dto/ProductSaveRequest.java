package com.quote.manager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 新增/修改产品的请求体 */
@Data
public class ProductSaveRequest {

    @NotBlank(message = "品牌不能为空")
    private String brand;

    @NotBlank(message = "型号不能为空")
    private String model;

    private String spec;
}
