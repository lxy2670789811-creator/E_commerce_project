package com.ecommerce.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户登录 DTO（极简实现）
 */
@Data
@Schema(description = "用户登录请求")
public class UserLoginDTO {

    @Schema(description = "用户名", example = "test001")
    @NotBlank(message = "用户名不能为空")
    private String username;

    @Schema(description = "密码", example = "123456")
    @NotBlank(message = "密码不能为空")
    private String password;
}
