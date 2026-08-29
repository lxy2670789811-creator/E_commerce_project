package com.ecommerce.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.ecommerce.ai.AiRateLimiter;
import com.ecommerce.ai.DeepSeekClient;
import com.ecommerce.common.BusinessException;
import com.ecommerce.common.ErrorCode;
import com.ecommerce.config.BusinessDynamicConfig;
import com.ecommerce.dto.ai.AiAfterSupportDTO;
import com.ecommerce.entity.AiAfterSupportDO;
import com.ecommerce.entity.OrderDO;
import com.ecommerce.enums.OrderStatusEnum;
import com.ecommerce.mapper.AiAfterSupportMapper;
import com.ecommerce.service.AiAfterSupportService;
import com.ecommerce.service.OrderService;
import com.ecommerce.vo.ai.AiAnalysisResultVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * AI售后智能分析 Service 实现类
 *
 * 保护机制层级（由外到内，共5层）：
 * ┌────────────────────────────────────────────────────────────┐
 * │ 1. Nacos 动态开关 after-support-enabled：                  │
 * │    一键关闭售后功能，紧急情况下可秒级停服                   │
 * │ 2. Sentinel 慢调用比例熔断（aiAfterSupportAnalyze）：      │
 * │    AI 接口大量超时时自动熔断，快速返回降级                  │
 * │ 3. Redis 滑动窗口限流（AiRateLimiter）：                   │
 * │    防止单用户频繁调用大模型                                 │
 * │ 4. Feign Sentinel 熔断（DeepSeekFeign#analyzeChat）：      │
 * │    AI API 异常比例过高时底层熔断                           │
 * │ 5. 业务降级兜底：                                          │
 * │    AI 调用失败 → 返回"待人工审核"，不影响接口整体可用       │
 * └────────────────────────────────────────────────────────────┘
 *
 * 即使 AI 完全不可用，售后接口仍能稳定返回"待人工审核"结果，
 * 体现了"降级不降级服务"的稳定性设计思想。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAfterSupportServiceImpl implements AiAfterSupportService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 降级默认值 —— 当 AI 完全不可用时使用
     */
    private static final String FALLBACK_PROBLEM_TYPE = "其他";
    private static final String FALLBACK_EMOTION = "中性";
    private static final String FALLBACK_SUGGESTION = "待人工审核";
    private static final int AI_STATUS_SUCCESS = 1;
    private static final int AI_STATUS_FAILED = 0;

    private final OrderService orderService;
    private final AiRateLimiter aiRateLimiter;
    private final DeepSeekClient deepSeekClient;
    private final AiAfterSupportMapper aiAfterSupportMapper;
    private final ObjectMapper objectMapper;
    private final BusinessDynamicConfig businessDynamicConfig;

    @Override
    @SentinelResource(
            value = "aiAfterSupportAnalyze",
            blockHandler = "analyzeBlockHandler",
            fallback = "analyzeFallback"
    )
    public AiAnalysisResultVO analyze(AiAfterSupportDTO dto) {
        long startTime = System.currentTimeMillis();
        log.info("【AI售后分析】开始：userId={}, orderId={}, userInput={}",
                dto.getUserId(), dto.getOrderId(), truncate(dto.getUserInput(), 100));

        // ====== 第1层保护：Nacos 动态开关 ======
        if (!businessDynamicConfig.isAfterSupportEnabled()) {
            log.warn("【Nacos功能开关】售后AI分析功能已关闭，直接返回降级：userId={}, orderId={}",
                    dto.getUserId(), dto.getOrderId());
            throw new BusinessException(ErrorCode.AFTER_SUPPORT_DISABLED,
                    "售后AI分析功能暂不可用，请稍后再试或联系人工客服");
        }

        // 1. 查询订单 + 校验归属
        OrderDO order = orderService.getOrderByIdForAi(dto.getOrderId());
        if (!order.getUserId().equals(dto.getUserId())) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在或不属于当前用户");
        }

        // 2. 限流：超出直接抛业务异常，不进入AI调用
        boolean allowed = aiRateLimiter.tryAcquire(dto.getUserId());
        if (!allowed) {
            log.warn("【AI限流】用户触发限流：userId={}", dto.getUserId());
            throw new BusinessException(ErrorCode.AI_RATE_LIMIT_EXCEEDED);
        }

        // 3. 组装订单业务数据（Prompt上下文）
        String orderInfo = buildOrderInfoText(order);

        // 4. 调用大模型（DeepSeekClient 内部已实现重试/超时/日志/Feign熔断）
        AiAnalysisResultVO.AiStructuredResult aiResult =
                deepSeekClient.analyzeAfterSupport(orderInfo, dto.getUserInput());

        // 5. 判定是否降级
        boolean aiSuccess = (aiResult != null && aiResult.getProblemType() != null);
        String problemType = aiSuccess ? aiResult.getProblemType() : FALLBACK_PROBLEM_TYPE;
        String emotion = aiSuccess ? aiResult.getEmotion() : FALLBACK_EMOTION;
        String suggestion = aiSuccess ? aiResult.getSuggestion() : FALLBACK_SUGGESTION;
        String failReason = aiSuccess ? null : (aiResult == null ? "AI服务调用失败或响应异常" : "AI返回字段缺失");

        // 6. 持久化到 ai_after_support 表
        AiAfterSupportDO record = new AiAfterSupportDO();
        record.setOrderId(order.getId());
        record.setOrderNo(order.getOrderNo());
        record.setUserId(dto.getUserId());
        record.setUserInput(dto.getUserInput());
        record.setProblemType(problemType);
        record.setEmotion(emotion);
        record.setSuggestion(suggestion);
        record.setAiStatus(aiSuccess ? AI_STATUS_SUCCESS : AI_STATUS_FAILED);
        record.setFailReason(failReason);
        record.setCreateTime(LocalDateTime.now());
        try {
            // 完整AI原始 JSON 也存一份，便于审计和问题排查
            record.setAiResult(aiResult != null ? objectMapper.writeValueAsString(aiResult) : null);
        } catch (JsonProcessingException e) {
            log.warn("序列化AI结果失败，跳过存ai_result字段", e);
        }
        aiAfterSupportMapper.insert(record);

        long elapsedMs = System.currentTimeMillis() - startTime;
        if (!aiSuccess) {
            log.warn("【AI降级】售后分析降级为【待人工审核】：recordId={}, orderNo={}, elapsed={}ms, reason={}",
                    record.getId(), order.getOrderNo(), elapsedMs, failReason);
        } else {
            log.info("【AI成功】售后分析完成：recordId={}, orderNo={}, elapsed={}ms, problemType={}",
                    record.getId(), order.getOrderNo(), elapsedMs, problemType);
        }

        // 7. 返回 VO
        return AiAnalysisResultVO.builder()
                .id(record.getId())
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .userInput(dto.getUserInput())
                .problemType(problemType)
                .emotion(emotion)
                .suggestion(suggestion)
                .aiStatus(record.getAiStatus())
                .aiStatusText(aiSuccess ? "分析成功" : "待人工审核")
                .failReason(failReason)
                .createTime(record.getCreateTime())
                .build();
    }

    /**
     * Sentinel 限流/熔断触发时的降级方法（blockHandler）
     * 当触发慢调用熔断时，直接返回"待人工审核"降级结果，保证接口可用性
     */
    public AiAnalysisResultVO analyzeBlockHandler(AiAfterSupportDTO dto, BlockException ex) {
        log.warn("【Sentinel熔断/限流】AI售后分析触发保护，直接降级：rule={}, userId={}, orderId={}",
                ex.getRule(), dto.getUserId(), dto.getOrderId());
        // 不查库、不调AI，直接构造降级结果返回，保证接口快速响应
        return AiAnalysisResultVO.builder()
                .orderId(dto.getOrderId())
                .orderNo("")
                .userInput(dto.getUserInput())
                .problemType(FALLBACK_PROBLEM_TYPE)
                .emotion(FALLBACK_EMOTION)
                .suggestion(FALLBACK_SUGGESTION)
                .aiStatus(AI_STATUS_FAILED)
                .aiStatusText("待人工审核")
                .failReason("系统繁忙，已触发熔断降级保护")
                .createTime(LocalDateTime.now())
                .build();
    }

    /**
     * 业务异常降级方法（fallback）
     * 非限流/熔断类的异常也做一次兜底，避免500
     */
    public AiAnalysisResultVO analyzeFallback(AiAfterSupportDTO dto, Throwable t) throws Throwable {
        // 业务异常原样抛出（例如订单不存在、限流等）
        if (t instanceof BusinessException) {
            throw t;
        }
        log.error("【Sentinel降级兜底】AI售后分析发生未捕获异常，降级返回：userId={}, orderId={}",
                dto.getUserId(), dto.getOrderId(), t);
        return AiAnalysisResultVO.builder()
                .orderId(dto.getOrderId())
                .orderNo("")
                .userInput(dto.getUserInput())
                .problemType(FALLBACK_PROBLEM_TYPE)
                .emotion(FALLBACK_EMOTION)
                .suggestion(FALLBACK_SUGGESTION)
                .aiStatus(AI_STATUS_FAILED)
                .aiStatusText("待人工审核")
                .failReason("服务异常，已自动降级")
                .createTime(LocalDateTime.now())
                .build();
    }

    /**
     * 组装订单业务信息（给大模型看的上下文）
     */
    private String buildOrderInfoText(OrderDO order) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 订单号：").append(order.getOrderNo()).append("\n");
        sb.append("- 商品名称：").append(order.getProductName()).append("\n");
        sb.append("- 下单时间：").append(order.getCreateTime() != null ? order.getCreateTime().format(DTF) : "-").append("\n");
        sb.append("- 商品单价：¥").append(order.getProductPrice() != null ? order.getProductPrice() : BigDecimal.ZERO).append("\n");
        sb.append("- 购买数量：").append(order.getQuantity()).append("\n");
        sb.append("- 订单总金额：¥").append(order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO).append("\n");
        sb.append("- 订单状态：").append(OrderStatusEnum.getTextByCode(order.getStatus())).append("\n");
        if (order.getPayTime() != null) {
            sb.append("- 支付时间：").append(order.getPayTime().format(DTF)).append("\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
