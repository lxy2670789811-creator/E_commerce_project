package com.ecommerce.dto.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建订单 DTO
 */
@Data
@Schema(description = "创建订单请求")
public class OrderCreateDTO {

    @Schema(description = "用户ID", example = "1")
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @Schema(description = "商品ID", example = "1")
    @NotNull(message = "商品ID不能为空")
    private Long productId;

    @Schema(description = "购买数量", example = "1")
    @NotNull(message = "购买数量不能为空")
    @Min(value = 1, message = "购买数量最少为1")
    private Integer quantity;

    @Schema(description = "收货地址ID", example = "1")
    @NotNull(message = "收货地址ID不能为空")
    private Long addressId;

    /**
     * 下单凭证（幂等用）
     *
     * <p>进入下单页时调用 GET /order/token 领取，提交后即失效；
     * 同一凭证第二次提交会被拒绝，从而防止双击、前端超时重试、网络重发导致的重复下单。</p>
     *
     * <p>注意：这里不加 @NotBlank，是为了让 Nacos 开关 order-token-enabled
     * 能在紧急情况下整体关闭凭证校验（关闭后由数据库唯一索引兜底）；
     * 必填校验统一在 OrderServiceImpl 中按开关状态执行。</p>
     */
    @Schema(description = "下单凭证（幂等用）：调用 GET /order/token 获取，提交后即失效；"
            + "重复提交会被拒绝。可通过 Nacos 开关 order-token-enabled 关闭校验",
            example = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6")
    private String token;
}
