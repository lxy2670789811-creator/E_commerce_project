package com.ecommerce.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 取消订单 DTO
 */
@Data
@Schema(description = "取消订单请求")
public class OrderCancelDTO {

    @Schema(description = "订单ID", example = "1")
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    @Schema(description = "用户ID（用于校验是否本人操作）", example = "1")
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @Schema(description = "取消原因", example = "不想要了")
    private String cancelReason;
}
