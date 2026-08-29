package com.ecommerce.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 订单状态操作（发货/完成）请求 DTO
 */
@Data
@Schema(description = "订单状态操作请求（发货/完成）")
public class OrderStatusOpDTO {

    @Schema(description = "订单ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "订单ID不能为空")
    private Long orderId;
}