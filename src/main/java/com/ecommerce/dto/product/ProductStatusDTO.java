package com.ecommerce.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 商品上下架 DTO
 */
@Data
@Schema(description = "商品上下架请求")
public class ProductStatusDTO {

    @Schema(description = "商品ID", example = "1")
    @NotNull(message = "商品ID不能为空")
    private Long id;

    @Schema(description = "状态：1-上架 0-下架", example = "1")
    @NotNull(message = "商品状态不能为空")
    private Integer status;
}
