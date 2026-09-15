package com.ecommerce.feign;

import com.ecommerce.feign.config.DeepSeekFeignConfig;
import com.ecommerce.feign.dto.DeepSeekChatRequest;
import com.ecommerce.feign.dto.DeepSeekChatResponse;
import com.ecommerce.feign.fallback.DeepSeekFeignFallback;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * DeepSeek 大模型 OpenFeign 声明式客户端
 *
 * 说明：
 * 1. name = "deepseekClient"：与 application.yml 中 feign.client.config.deepseekClient 配置项对应（单独超时）
 * 2. url / path 取自 DeepSeekApiConstants：与 Sentinel 熔断规则的资源名共用同一来源，避免静默漂移
 * 3. configuration = DeepSeekFeignConfig.class：加载鉴权拦截器（自动加 Bearer Token）
 * 4. fallback = DeepSeekFeignFallback.class：Sentinel 熔断/异常时的降级实现类
 *
 * ⚠️ 【重要修复】严禁在 Feign 接口方法上标注 @SentinelResource：
 * Feign 接口本身就是 JDK 动态代理，再叠加 Sentinel 注解切面会抛
 * IllegalStateException: Wrong state for SentinelResource annotation，
 * 导致"每次调用都直接失败/降级"。熔断请依赖：
 *   (1) feign.sentinel.enabled=true + fallback 类（由 Spring Cloud Alibaba Sentinel Starter 自动织入）
 *   (2) SentinelConfig 中注册的熔断规则，资源名必须取 DeepSeekApiConstants.FEIGN_RESOURCE_NAME
 *
 * ⚠️ 资源名的真实形态是 "POST:https://api.deepseek.com/v1/chat/completions"，
 * 由 SCA 的 SentinelInvocationHandler 按「HTTP方法:url+path」拼装，
 * 不是 "DeepSeekFeign#analyzeChat"（那个写法永远不命中，详见常量类注释）。
 */
@FeignClient(
        name = "deepseekClient",
        url = DeepSeekApiConstants.BASE_URL,
        configuration = DeepSeekFeignConfig.class,
        fallback = DeepSeekFeignFallback.class
)
public interface DeepSeekFeign {

    /**
     * 调用 DeepSeek Chat Completions 接口
     * POST https://api.deepseek.com/v1/chat/completions
     *
     * @param request 请求体（JSON）
     * @return 响应体
     */
    @PostMapping(value = DeepSeekApiConstants.CHAT_COMPLETIONS_PATH,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    DeepSeekChatResponse analyzeChat(@RequestBody DeepSeekChatRequest request);
}
