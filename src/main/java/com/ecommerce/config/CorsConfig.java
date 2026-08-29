package com.ecommerce.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域配置
 * 前端开发服务器（Vite，默认 http://localhost:5173）与后端（http://localhost:8080/api）端口不同，存在跨域问题。
 * 这里通过 WebMvcConfigurer 全局放开 CORS，所有 Controller 均生效。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // 允许的前端来源（Vite 默认端口 5173）
                .allowedOriginPatterns("http://localhost:5173", "http://127.0.0.1:5173")
                // 允许的请求方式
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // 允许携带的请求头
                .allowedHeaders("*")
                // 允许携带凭证（Cookie 等，本项目暂未用到，保留扩展性）
                .allowCredentials(true)
                // 预检请求（OPTIONS）缓存时间，单位秒，避免频繁预检
                .maxAge(3600);
    }
}
