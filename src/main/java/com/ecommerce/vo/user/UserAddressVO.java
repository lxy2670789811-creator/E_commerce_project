package com.ecommerce.vo.user;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户地址 VO
 */
@Data
@Schema(description = "收货地址视图对象")
public class UserAddressVO {

    @Schema(description = "地址ID", example = "1")
    private Long id;

    @Schema(description = "用户ID", example = "1")
    private Long userId;

    @Schema(description = "收货人姓名", example = "张三")
    private String receiver;

    @Schema(description = "收货人手机号", example = "13800138001")
    private String phone;

    @Schema(description = "省份", example = "广东省")
    private String province;

    @Schema(description = "城市", example = "深圳市")
    private String city;

    @Schema(description = "区县", example = "南山区")
    private String district;

    @Schema(description = "详细地址", example = "科技园南区1栋1001号")
    private String detail;

    @Schema(description = "完整地址（省市区+详情）", example = "广东省深圳市南山区科技园南区1栋1001号")
    private String fullAddress;

    @Schema(description = "是否默认：1-是 0-否", example = "1")
    private Integer isDefault;

    @Schema(description = "创建时间", example = "2024-01-01 10:00:00")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
