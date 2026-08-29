package com.ecommerce.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * AI售后分析请求 DTO
 */
@Data
@Schema(description = "AI售后智能分析请求")
public class AiAfterSupportDTO {

    @Schema(description = "订单ID", example = "1")
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    @Schema(description = "用户ID", example = "1")
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @Schema(description = "用户反馈问题描述", example = "收到商品破损，要退款")
    @NotBlank(message = "问题描述不能为空")
    private String userInput;
}
