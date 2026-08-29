package com.ecommerce.feign.fallback;

import com.ecommerce.feign.DeepSeekFeign;
import com.ecommerce.feign.dto.DeepSeekChatRequest;
import com.ecommerce.feign.dto.DeepSeekChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DeepSeek Feign 客户端 Sentinel 熔断 / 异常降级实现类
 *
 * 触发场景：
 * 1. Sentinel 熔断（慢调用比例 / 异常比例 / 异常数）
 * 2. Feign 调用抛出异常（超时、连接失败、鉴权失败等）
 *
 * 降级策略：返回 null，由上层业务（DeepSeekClient）统一做"待人工审核"降级。
 * 这样底层 Feign 层和业务层分工明确：
 *   - Feign Fallback：返回 null 表示调用失败，不编造数据
 *   - DeepSeekClient：识别 null -> 返回空给 Service
 *   - AiAfterSupportServiceImpl：识别 null -> 构造"待人工审核"降级结果
 */
@Slf4j
@Component
public class DeepSeekFeignFallback implements DeepSeekFeign {

    @Override
    public DeepSeekChatResponse analyzeChat(DeepSeekChatRequest request) {
        // 仅记录前 100 字符的用户输入，避免日志过大
        String userInput = (request != null && request.getMessages() != null
                && !request.getMessages().isEmpty())
                ? request.getMessages().get(request.getMessages().size() - 1).getContent()
                : "";
        if (userInput.length() > 100) {
            userInput = userInput.substring(0, 100) + "...";
        }
        log.warn("【Feign Sentinel 熔断降级】DeepSeek API 调用被熔断/异常，返回 null。请求片段={}", userInput);
        return null;
    }
}
