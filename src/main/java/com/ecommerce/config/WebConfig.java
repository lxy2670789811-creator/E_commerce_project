package com.ecommerce.config;

import com.ecommerce.security.JwtAuthInterceptor;
import com.ecommerce.security.JwtProperties;
import com.ecommerce.security.JwtTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 JWT 鉴权拦截器，并声明白名单
 *
 * <p>注意：服务 context-path = /api，Spring MVC 拦截器匹配的是去除 context-path 后的路径，
 * 因此这里写 /user/login，实际生效为 /api/user/login。</p>
 *
 * <p>白名单（无需鉴权）：
 *   - 登录接口：/user/login、/user/register（若未来加注册）
 *   - 接口文档：Knife4j /doc.html、SpringDoc /v3/api-docs、swagger-ui 及静态资源
 *   - CORS 预检：OPTIONS 需放行（浏览器跨域请求先发 OPTIONS）
 * 商品列表/详情等公开浏览接口也放行；订单/AI 等涉及用户身份的默认进入拦截。</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtAuthInterceptor(jwtTokenService, jwtProperties))
                // 白名单：登录 + 接口文档静态资源
                // （CORS 预检 OPTIONS 由框架 CorsProcessor 在进入拦截器前短路处理，无需在此放行）
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/login",
                        // Knife4j / SpringDoc 文档
                        "/doc.html",
                        "/webjars/**",
                        "/v3/api-docs/**",
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/favicon.ico",
                        "/error");
    }
}
