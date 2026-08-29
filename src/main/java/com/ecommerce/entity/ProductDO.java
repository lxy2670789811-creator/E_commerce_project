package com.ecommerce.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 商品数据库实体（DO）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("product")
public class ProductDO extends BaseDO {

    private static final long serialVersionUID = 1L;

    /**
     * 商品名称
     */
    private String name;

    /**
     * 商品描述
     */
    private String description;

    /**
     * 商品价格
     */
    private BigDecimal price;

    /**
     * 库存数量
     */
    private Integer stock;

    /**
     * 状态：1-上架 0-下架
     */
    private Integer status;

    /**
     * 分类
     */
    private String category;

    /**
     * 商品图片URL
     */
    private String imageUrl;
}
