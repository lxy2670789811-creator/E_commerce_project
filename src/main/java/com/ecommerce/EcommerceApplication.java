package com.ecommerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

import java.nio.file.Paths;

/**
 * 电商订单后端系统启动类
 *
 * 组件说明：
 * 1. @EnableFeignClients：开启 OpenFeign 声明式客户端扫描（默认扫描同包及子包下的 @FeignClient）
 * 2. Spring Cloud Alibaba Nacos Config：通过 bootstrap.yml 自动装载，无需额外注解
 * 3. Sentinel：通过 spring-cloud-starter-alibaba-sentinel 自动装配，无需注解
 */
@EnableFeignClients(basePackages = "com.ecommerce.feign")
@SpringBootApplication
public class EcommerceApplication {

    public static void main(String[] args) {
        // Sentinel 默认将日志写到 ~/logs/csp/；Windows 上若存在上次异常退出遗留的 .lck 锁文件，
        // JUL FileHandler 创建会失败并抛 NPE 导致启动中断。这里在未显式指定时重定向到系统临时目录规避。
        if (System.getProperty("csp.sentinel.log.dir") == null) {
            System.setProperty("csp.sentinel.log.dir",
                    Paths.get(System.getProperty("java.io.tmpdir"), "sentinel-logs").toString());
        }
        SpringApplication.run(EcommerceApplication.class, args);
        System.out.println("""
                
                ============================================================
                  轻量化电商订单后端系统启动成功！
                  接口文档地址（Knife4j）：http://localhost:8080/api/doc.html
                  Swagger UI：http://localhost:8080/api/swagger-ui.html
                  ======= SpringCloud Alibaba 组件已启用 =======
                  - Nacos 配置中心：动态刷新业务配置
                  - Sentinel 限流/熔断：保护核心接口
                  - OpenFeign：声明式调用 DeepSeek 大模型 API
                ============================================================
                """);
    }
}
