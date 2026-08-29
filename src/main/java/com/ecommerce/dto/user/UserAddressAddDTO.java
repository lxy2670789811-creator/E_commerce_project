package com.ecommerce.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 新增用户地址 DTO
 */
@Data
@Schema(description = "新增收货地址请求")
public class UserAddressAddDTO {

    @Schema(description = "用户ID", example = "1")
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    @Schema(description = "收货人姓名", example = "张三")
    @NotBlank(message = "收货人姓名不能为空")
    private String receiver;

    @Schema(description = "收货人手机号", example = "13800138001")
    @NotBlank(message = "收货人手机号不能为空")
    private String phone;

    @Schema(description = "省份", example = "广东省")
    @NotBlank(message = "省份不能为空")
    private String province;

    @Schema(description = "城市", example = "深圳市")
    @NotBlank(message = "城市不能为空")
    private String city;

    @Schema(description = "区县", example = "南山区")
    @NotBlank(message = "区县不能为空")
    private String district;

    @Schema(description = "详细地址", example = "科技园南区1栋1001号")
    @NotBlank(message = "详细地址不能为空")
    private String detail;

    @Schema(description = "是否默认：1-是 0-否", example = "1")
    private Integer isDefault;
}
