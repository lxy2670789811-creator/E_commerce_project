package com.ecommerce.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 模拟支付回调请求 DTO
 */
@Data
@Schema(description = "支付回调请求")
public class OrderPayCallbackDTO {

    @Schema(description = "订单号", requiredMode = Schema.RequiredMode.REQUIRED, example = "ORD202608291200001234567890123456789")
    @NotBlank(message = "订单号不能为空")
    private String orderNo;
}