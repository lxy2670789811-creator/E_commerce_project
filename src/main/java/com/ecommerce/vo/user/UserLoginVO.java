package com.ecommerce.vo.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户登录 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户登录结果")
public class UserLoginVO {

    @Schema(description = "用户ID", example = "1")
    private Long userId;

    @Schema(description = "用户名", example = "test001")
    private String username;

    @Schema(description = "昵称", example = "测试用户1")
    private String nickname;

    @Schema(description = "模拟Token（极简实现，生产请用JWT）", example = "mock-token-xxxxxxx")
    private String token;
}
