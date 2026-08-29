package com.ecommerce.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 商品新增 DTO
 */
@Data
@Schema(description = "商品新增请求")
public class ProductAddDTO {

    @Schema(description = "商品名称", example = "无线蓝牙耳机")
    @NotBlank(message = "商品名称不能为空")
    private String name;

    @Schema(description = "商品描述", example = "主动降噪、蓝牙5.3、续航30小时")
    private String description;

    @Schema(description = "商品价格", example = "399.00")
    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.01", message = "商品价格必须大于0")
    private BigDecimal price;

    @Schema(description = "库存数量", example = "100")
    @NotNull(message = "库存数量不能为空")
    @Min(value = 0, message = "库存数量不能为负数")
    private Integer stock;

    @Schema(description = "状态：1-上架 0-下架", example = "1")
    @NotNull(message = "商品状态不能为空")
    private Integer status;

    @Schema(description = "分类", example = "数码配件")
    private String category;

    @Schema(description = "商品图片URL", example = "https://example.com/img.jpg")
    private String imageUrl;
}
