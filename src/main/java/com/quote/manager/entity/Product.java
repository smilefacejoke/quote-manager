package com.quote.manager.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 产品档案表 product */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 品牌，如：华为 */
    private String brand;

    /** 型号，如：Mate 60 Pro；与品牌联合唯一 */
    private String model;

    /** 规格/描述，如：12+512G */
    private String spec;

    /** 建档时间 */
    private LocalDateTime createdAt;
}
