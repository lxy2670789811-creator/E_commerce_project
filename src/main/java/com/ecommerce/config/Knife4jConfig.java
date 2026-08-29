package com.ecommerce.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j + SpringDoc OpenAPI 配置
 */
@Configuration
public class Knife4jConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("轻量化电商订单后端系统 API")
                        .version("1.0.0")
                        .description("基于 JDK 17 + Spring Boot 3.x + MyBatis-Plus 的电商后端系统，支持商品管理、订单创建、AI售后分析等功能。")
                        .contact(new Contact()
                                .name("E-commerce Team")
                                .email("support@ecommerce.com")));
    }
}
