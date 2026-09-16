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

                        // actuator 健康端点：K8s 的 liveness/readiness 探针是**集群内部发起的、
                        // 不带 JWT 的主动 HTTP GET**，所以这里必须放行。
                        // ⚠️ 一旦漏掉，ecommerce.jwt.required 切成 true 后探针会拿到 401 →
                        //    readiness 永远失败 → K8s 把**所有** Pod 摘出 Service → 服务整体不接流量。
                        //    这个故障形态是"Pod 全在 Running、日志毫无异常、入口 503"，极难定位。
                        // 暴露面可控：只开 health/info 两个端点，health 的 show-details=never 不吐
                        // 依赖明细；外部访问另由 Ingress 的 /api/actuator 屏蔽规则挡掉
                        // （Pod 探针直接打容器端口，不经过 Ingress，两者不冲突）。
                        "/actuator/**",

                        "/error");
    }
}
