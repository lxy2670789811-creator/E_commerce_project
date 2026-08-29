package com.ecommerce.vo.order;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单视图对象（列表用）
 */
@Data
@Schema(description = "订单视图对象")
public class OrderVO {

    @Schema(description = "订单ID", example = "1")
    private Long id;

    @Schema(description = "订单号", example = "ORD20240101000001")
    private String orderNo;

    @Schema(description = "用户ID", example = "1")
    private Long userId;

    @Schema(description = "商品ID", example = "1")
    private Long productId;

    @Schema(description = "商品名称", example = "无线蓝牙耳机Pro")
    private String productName;

    @Schema(description = "商品单价", example = "399.00")
    private BigDecimal productPrice;

    @Schema(description = "购买数量", example = "1")
    private Integer quantity;

    @Schema(description = "订单总金额", example = "399.00")
    private BigDecimal totalAmount;

    @Schema(description = "订单状态码", example = "0")
    private Integer status;

    @Schema(description = "订单状态文本", example = "待支付")
    private String statusText;

    @Schema(description = "创建时间", example = "2024-01-01 10:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "支付时间", example = "2024-01-01 10:05:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime payTime;
}
