package com.ecommerce.ai;

import com.ecommerce.config.BusinessDynamicConfig;
import com.ecommerce.feign.DeepSeekFeign;
import com.ecommerce.feign.dto.DeepSeekChatRequest;
import com.ecommerce.feign.dto.DeepSeekChatResponse;
import com.ecommerce.vo.ai.AiAnalysisResultVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DeepSeek 大模型 API 调用客户端
 *
 * 工程化特性（超时、重试、日志、降级）：
 * ┌──────────────────────────────────────────────────────────┐
 * │ 1. 超时控制：                                            │
 * │    - connectTimeout: 5s（TCP连接超时）                    │
 * │    - readTimeout: 30s（响应读取超时）                     │
 * │    均在 application.yml feign.client.config.deepseekClient │
 * │    中配置，修改后需重启生效                               │
 * ├──────────────────────────────────────────────────────────┤
 * │ 2. 重试机制（自定义实现，支持精细控制）：               │
 * │    - 识别哪些异常可重试（5xx、连接超时、429限流）          │
 * │    - 其他异常（如4xx业务错误、JSON解析失败）不重试        │
 * │    - 重试次数可通过 Nacos 动态配置（ai-api-retry-times）  │
 * │    - 递增退避间隔（500ms * attempt）                     │
 * │    - AI 接口非幂等，重试次数限 0-3 次                   │
 * ├──────────────────────────────────────────────────────────┤
 * │ 3. 输入输出日志：                                        │
 * │    - 记录调用耗时、模型名称、用户输入（截断）            │
 * │    - 记录 AI 返回的结构化结果                            │
 * │    - 异常时记录完整堆栈，便于排查                        │
 * ├──────────────────────────────────────────────────────────┤
 * │ 4. 降级策略（三层防护）：                                │
 * │    - Feign Fallback 层：返回 null（Sentinel 熔断/异常）    │
 * │    - 本层：捕获所有异常返回 null                          │
 * │    - 上层 AiAfterSupportServiceImpl：null → "待人工审核"  │
 * └──────────────────────────────────────────────────────────┘
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekClient {

    private static final long RETRY_INTERVAL_MS = 500L; // 基础重试间隔

    private final DeepSeekFeign deepSeekFeign;
    private final ObjectMapper objectMapper;
    private final BusinessDynamicConfig businessDynamicConfig;

    @Value("${deepseek.api.model:deepseek-chat}")
    private String model;

    /**
     * 调用 DeepSeek 做售后分析（带重试机制）
     *
     * @param orderInfo    订单业务信息（已格式化的字符串）
     * @param userFeedback 用户反馈文本
     * @return 结构化结果；失败返回 null（上层做降级）
     */
    public AiAnalysisResultVO.AiStructuredResult analyzeAfterSupport(String orderInfo, String userFeedback) {
        int maxRetries = Math.min(Math.max(businessDynamicConfig.getAiApiRetryTimes(), 0), 3);
        String truncatedUserInput = truncate(userFeedback, 100);

        log.info("【AI调用】开始：model={}, maxRetries={}, userInput={}",
                model, maxRetries, truncatedUserInput);
        long startTime = System.currentTimeMillis();

        // 构造请求体（构造一次，避免重试时重复拼装）
        DeepSeekChatRequest request = buildRequest(orderInfo, userFeedback);

        int lastAttempt = 0;
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            lastAttempt = attempt;
            try {
                if (attempt > 0) {
                    long sleepMs = RETRY_INTERVAL_MS * attempt;
                    log.info("【AI调用】第{}次重试（等待{}ms）：userInput={}", attempt, sleepMs, truncatedUserInput);
                    Thread.sleep(sleepMs);
                }

                DeepSeekChatResponse response = deepSeekFeign.analyzeChat(request);
                long elapsedMs = System.currentTimeMillis() - startTime;

                // Feign Fallback 返回 null 或 body 为空 → 视为调用失败
                if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
                    log.error("【AI调用】返回为空：attempt={}, elapsed={}ms", attempt, elapsedMs);
                    if (attempt < maxRetries && isRetryableNullResponse()) {
                        continue;
                    }
                    return null;
                }

                // 提取 assistant message content
                DeepSeekChatResponse.Message msg = response.getChoices().get(0).getMessage();
                if (msg == null || msg.getContent() == null || msg.getContent().isEmpty()) {
                    log.error("【AI调用】返回内容为空：attempt={}, elapsed={}ms", attempt, elapsedMs);
                    if (attempt < maxRetries) continue;
                    return null;
                }

                // 清理 Markdown 代码块包裹并解析
                String rawContent = msg.getContent();
                String jsonContent = cleanMarkdownJson(rawContent);
                log.info("【AI调用】成功：attempt={}, elapsed={}ms, result={}",
                        attempt, elapsedMs, truncate(jsonContent, 300));

                AiAnalysisResultVO.AiStructuredResult result =
                        objectMapper.readValue(jsonContent, AiAnalysisResultVO.AiStructuredResult.class);
                log.info("【AI解析】完成：问题类型={}, 情绪={}, 建议={}",
                        result.getProblemType(), result.getEmotion(), truncate(result.getSuggestion(), 50));
                return result;

            } catch (feign.FeignException e) {
                lastException = e;
                long elapsedMs = System.currentTimeMillis() - startTime;
                boolean retryable = isRetryableStatus(e.status());
                log.warn("【AI调用】第{}次失败：status={}, elapsed={}ms, retryable={}, error={}",
                        attempt, e.status(), elapsedMs, retryable, truncate(e.getMessage(), 200));
                if (retryable && attempt < maxRetries) continue;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("【AI调用】被中断：", e);
                break;

            } catch (Exception e) {
                lastException = e;
                long elapsedMs = System.currentTimeMillis() - startTime;
                log.error("【AI调用】第{}次异常：elapsed={}ms, type={}, message={}",
                        attempt, elapsedMs, e.getClass().getSimpleName(), truncate(e.getMessage(), 200));
                // 非网络类异常（如解析失败）不重试
                break;
            }
        }

        // 所有重试均失败
        long totalElapsedMs = System.currentTimeMillis() - startTime;
        log.error("【AI调用】全部失败：attempts={}, totalElapsed={}ms, lastError={}",
                lastAttempt + 1, totalElapsedMs,
                lastException != null ? truncate(lastException.getMessage(), 200) : "unknown");
        return null;
    }

    /**
     * 判断 HTTP 状态码是否可重试
     * 5xx 服务端错误、429 限流、-1（连接超时）→ 可重试
     */
    private boolean isRetryableStatus(int status) {
        return (status >= 500 && status < 600) || status == 429 || status == -1;
    }

    /**
     * Feign 返回 null 时（可能是 Fallback 或连接失败），判断是否可重试
     * 目前一律可重试（上层 Fallback 返回 null 通常意味着可恢复的故障）
     */
    private boolean isRetryableNullResponse() {
        return true;
    }

    /**
     * 构建 Feign 请求体
     */
    private DeepSeekChatRequest buildRequest(String orderInfo, String userFeedback) {
        List<DeepSeekChatRequest.Message> messages = new ArrayList<>();
        messages.add(DeepSeekChatRequest.Message.builder()
                .role("system").content(buildSystemPrompt()).build());
        messages.add(DeepSeekChatRequest.Message.builder()
                .role("user").content(buildUserPrompt(orderInfo, userFeedback)).build());

        return DeepSeekChatRequest.builder()
                .model(model)
                .temperature(0.1)
                .responseFormat(Collections.singletonMap("type", "json_object"))
                .messages(messages)
                .build();
    }

    /**
     * 构建 System Prompt：强制 AI 按 JSON 输出
     */
    private String buildSystemPrompt() {
        return """
                你是电商售后客服智能分析助手。你需要根据用户反馈和订单信息，输出严格的JSON格式分析结果。

                分析维度要求：
                1. problem_type（问题分类）：只允许从以下枚举中选一个最贴切的：
                   - "质量问题"（商品破损、瑕疵、功能故障、与描述不符等）
                   - "物流问题"（发货慢、物流慢、包裹丢失、签收异常、配送问题等）
                   - "咨询"（仅询问订单状态、使用方法、退换货政策、无明确负面情绪的疑问）
                   - "其他"（以上都不符合的情况）

                2. emotion（情绪判断）：只允许三选一：
                   - "负面"（不满、抱怨、愤怒、要求退款/赔偿、语气激烈）
                   - "中性"（客观描述、礼貌询问、无明显情绪）
                   - "正面"（表扬、感谢、满意）

                3. suggestion（建议处理方案）：用不超过30字的中文简述推荐操作：
                   - 质量问题+负面情绪 → 一般建议"直接退款"或"安排补发"；
                   - 物流问题 → 一般建议"联系物流查询"或"安抚回复并跟进物流"；
                   - 咨询 → "礼貌回复用户问题"或"提供对应政策说明"；
                   - 其他情况 → 请给出一句话处理建议。

                【重要】你必须严格只输出一个合法的JSON对象，不包含任何markdown标记、解释文本、前后缀。
                JSON结构如下：
                {
                  "problem_type": "质量问题",
                  "emotion": "负面",
                  "suggestion": "直接退款"
                }
                """;
    }

    /**
     * 构建 User Prompt
     */
    private String buildUserPrompt(String orderInfo, String userFeedback) {
        return "【订单业务信息】\n" + orderInfo + "\n\n"
                + "【用户反馈内容】\n" + userFeedback + "\n\n"
                + "请严格按JSON格式输出售后分析结果。";
    }

    /**
     * 清理大模型返回内容中的 markdown 代码块包裹，提取纯 JSON。
     */
    private String cleanMarkdownJson(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("\uFEFF")) s = s.substring(1).trim();
        s = s.replace("\r", "");

        String mdFence = "```";
        if (s.startsWith(mdFence)) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            } else {
                s = s.substring(mdFence.length());
            }
            int lastIdx = s.lastIndexOf(mdFence);
            if (lastIdx >= 0) s = s.substring(0, lastIdx);
            s = s.trim();
        }

        if (!s.startsWith("{")) {
            int start = s.indexOf('{');
            int end = s.lastIndexOf('}');
            if (start >= 0 && end > start) {
                s = s.substring(start, end + 1);
            }
        }
        return s.trim();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
