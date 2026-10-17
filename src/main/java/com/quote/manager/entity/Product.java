package com.quote.manager.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 品牌 */
    private String brand;

    /** 型号 */
    private String model;

    /** 规格/描述 */
    private String spec;

    private LocalDateTime createdAt;
}
