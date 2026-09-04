package com.ecommerce.security;

import com.ecommerce.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 鉴权拦截器
 *
 * <p>从 Authorization 头（格式 "Bearer <token>"）解析 JWT，校验合法性，并把 userId 写入 AuthContext。</p>
 *
 * <p>双模式（由 ecommerce.jwt.required 控制）：
 * <ul>
 *   <li>required = false（当前默认，兼容模式）：请求带 token → 严格校验，非法/过期返回 401；
 *       请求不带 token → 放行，业务仍沿用参数里的 userId。保证现有前端/测试不破坏。</li>
 *   <li>required = true（硬鉴权）：受保护路径必须带有效 token，缺失也返回 401。</li>
 * </ul>
 *
 * <p>白名单路径（无需鉴权）：登录接口、Knife4j/Swagger 文档资源。白名单由 WebConfig 在注册时排除，
 * 拦截器本身不再重复判断。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 清理上次残留（防御性，正常流程在 afterCompletion 已清理）
        AuthContext.clear();

        String header = request.getHeader(AUTHORIZATION_HEADER);
        String token = null;
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            token = header.substring(BEARER_PREFIX.length()).trim();
        }

        // 未带 token
        if (token == null || token.isEmpty()) {
            if (jwtProperties.isRequired()) {
                log.warn("鉴权拦截：缺失 token，URI={}", request.getRequestURI());
                reject(response);
                return false;
            }
            // 兼容模式：放行，交由业务用 userId 参数
            return true;
        }

        // 带了 token：严格校验
        Long userId = jwtTokenService.parseAndGetUserId(token);
        if (userId == null) {
            log.warn("鉴权拦截：token 非法或已过期，URI={}", request.getRequestURI());
            reject(response);
            return false;
        }
        AuthContext.setUserId(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    private void reject(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        try {
            response.getWriter().write("{\"code\":" + ErrorCode.TOKEN_INVALID.getCode()
                    + ",\"message\":\"" + ErrorCode.TOKEN_INVALID.getMessage()
                    + "\",\"data\":null}");
        } catch (Exception e) {
            log.error("写入鉴权失败响应异常", e);
        }
    }
}
