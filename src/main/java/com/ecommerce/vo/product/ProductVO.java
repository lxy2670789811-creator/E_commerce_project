package com.ecommerce.vo.product;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品视图对象 VO
 */
@Data
@Schema(description = "商品视图对象")
public class ProductVO {

    @Schema(description = "商品ID", example = "1")
    private Long id;

    @Schema(description = "商品名称", example = "无线蓝牙耳机Pro")
    private String name;

    @Schema(description = "商品描述", example = "主动降噪、蓝牙5.3、续航30小时")
    private String description;

    @Schema(description = "商品价格", example = "399.00")
    private BigDecimal price;

    @Schema(description = "库存数量", example = "100")
    private Integer stock;

    @Schema(description = "状态：1-上架 0-下架", example = "1")
    private Integer status;

    @Schema(description = "状态描述", example = "已上架")
    private String statusText;

    @Schema(description = "分类", example = "数码配件")
    private String category;

    @Schema(description = "商品图片URL", example = "https://example.com/img.jpg")
    private String imageUrl;

    @Schema(description = "创建时间", example = "2024-01-01 10:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间", example = "2024-01-02 12:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
