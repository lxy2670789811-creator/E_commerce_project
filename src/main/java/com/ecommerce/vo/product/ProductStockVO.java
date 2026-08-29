package com.ecommerce.vo.product;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商品库存查询 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "商品库存查询结果")
public class ProductStockVO {

    @Schema(description = "商品ID", example = "1")
    private Long productId;

    @Schema(description = "商品名称", example = "无线蓝牙耳机Pro")
    private String productName;

    @Schema(description = "当前库存", example = "100")
    private Integer stock;

    @Schema(description = "是否有库存", example = "true")
    private Boolean hasStock;

    @Schema(description = "状态：1-上架 0-下架", example = "1")
    private Integer status;

    @Schema(description = "状态描述", example = "已上架")
    private String statusText;
}
