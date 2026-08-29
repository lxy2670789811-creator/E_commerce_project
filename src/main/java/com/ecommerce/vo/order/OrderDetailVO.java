package com.ecommerce.vo.order;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 订单详情视图对象
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "订单详情视图对象")
public class OrderDetailVO extends OrderVO {

    @Schema(description = "收货地址ID", example = "1")
    private Long addressId;

    @Schema(description = "收货地址快照", example = "广东省深圳市南山区科技园南区1栋1001号 张三 13800138001")
    private String addressSnapshot;

    @Schema(description = "发货时间", example = "2024-01-02 09:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime shipTime;

    @Schema(description = "完成时间", example = "2024-01-04 18:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishTime;

    @Schema(description = "取消时间", example = "2024-01-01 12:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime cancelTime;

    @Schema(description = "取消原因", example = "不想要了")
    private String cancelReason;

    @Schema(description = "更新时间", example = "2024-01-02 09:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
