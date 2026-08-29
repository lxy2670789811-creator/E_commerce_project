package com.ecommerce.config;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 配置类
 *
 * 作用：
 * 1. 注册 SentinelResourceAspect（使 @SentinelResource 注解生效，用于熔断降级切面）
 * 2. 本地初始化一套兜底的流控/熔断规则（控制台未配置时生效，控制台配置优先级更高）
 *
 * 资源命名约定：
 *  - "createOrder"：创建订单接口（QPS限流）
 *  - "cancelOrder"：取消订单接口（QPS限流，避免并发取消冲击）
 *  - "aiAfterSupportAnalyze"：AI售后分析接口（慢调用比例熔断降级）
 *  - "DeepSeekFeign#analyzeChat"：Feign调用大模型（异常比例熔断，底层保护）
 *
 * 所有阈值均从 BusinessDynamicConfig（Nacos 动态配置）读取，修改后可动态刷新。
 * 但注意：Sentinel 规则是"启动时加载"，运行期调整需走 Sentinel Dashboard。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
// 可通过 ecommerce.sentinel.enabled=false 关闭（如测试环境、无 Dashboard 的本地调试），默认开启
@ConditionalOnProperty(name = "ecommerce.sentinel.enabled", havingValue = "true", matchIfMissing = true)
public class SentinelConfig {

    private final BusinessDynamicConfig businessDynamicConfig;

    /**
     * 注册 Sentinel 注解切面（必须，否则 @SentinelResource 的 blockHandler/fallback 不生效）
     */
    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    /**
     * 初始化本地兜底规则
     * 说明：
     *  - 控制台（Dashboard）配置的规则是持久化/动态的，优先级更高
     *  - 本方法加载的是"本地内存规则"，仅在控制台尚未配置时生效，避免完全裸奔
     *  - 所有阈值参数从 BusinessDynamicConfig 读取，启动时确定
     */
    @PostConstruct
    public void initSentinelRules() {
        initFlowRules();
        initDegradeRules();
        log.info("Sentinel 本地兜底规则初始化完成");
    }

    /**
     * 初始化流控规则（FlowRule）
     *
     * 流控层级说明：
     *   网关层（createOrder QPS限流）→ 分布式锁（按商品ID串行化）→ DB原子扣减
     * 三层保护确保即使锁机制出问题，也不会让DB被打爆。
     */
    private void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();

        // ============ 1. 创建订单接口：QPS 限制 ============
        // 防止大量并发下单请求打垮数据库（即使有分布式锁，也需要网关层限流）
        FlowRule orderCreateRule = new FlowRule();
        orderCreateRule.setResource("createOrder"); // 与 @SentinelResource 的 value 对应
        orderCreateRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        orderCreateRule.setCount(businessDynamicConfig.getOrderCreateQpsThreshold());
        orderCreateRule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        orderCreateRule.setLimitApp("default");
        rules.add(orderCreateRule);

        // ============ 2. 取消订单接口：QPS 限制 ============
        // 取消也涉及库存回滚 + 状态更新，同样需要保护
        FlowRule cancelOrderRule = new FlowRule();
        cancelOrderRule.setResource("cancelOrder");
        cancelOrderRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        cancelOrderRule.setCount(200.0); // 取消操作相对轻，阈值设高一些
        cancelOrderRule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        cancelOrderRule.setLimitApp("default");
        rules.add(cancelOrderRule);

        log.info("Sentinel 流控规则：createOrder QPS={}, cancelOrder QPS={}",
                orderCreateRule.getCount(), cancelOrderRule.getCount());
        FlowRuleManager.loadRules(rules);
    }

    /**
     * 初始化熔断降级规则（DegradeRule）
     *
     * 熔断层级说明（由外到内）：
     *   1. aiAfterSupportAnalyze 慢调用比例熔断 → 保护业务接口
     *   2. DeepSeekFeign#analyzeChat 异常比例熔断 → 保护底层 AI 调用
     *
     * 两者形成"双层熔断"：上层接口慢调用多了就断，下层AI接口失败多了也断。
     */
    private void initDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();

        // ============ 1. AI售后分析接口：慢调用比例熔断 ============
        // 当 AI 接口大量超时/慢调用时，直接熔断，快速返回降级结果（"待人工审核"）
        DegradeRule aiAnalyzeRule = new DegradeRule();
        aiAnalyzeRule.setResource("aiAfterSupportAnalyze");
        aiAnalyzeRule.setGrade(CircuitBreakerStrategy.SLOW_REQUEST_RATIO.getType());
        // 从动态配置读取熔断阈值
        aiAnalyzeRule.setSlowRatioThreshold(businessDynamicConfig.getAiAnalyzeSlowRatioThreshold());
        aiAnalyzeRule.setMinRequestAmount(businessDynamicConfig.getAiAnalyzeMinRequestAmount());
        aiAnalyzeRule.setStatIntervalMs(businessDynamicConfig.getAiAnalyzeStatIntervalMs());
        aiAnalyzeRule.setTimeWindow(businessDynamicConfig.getAiAnalyzeTimeWindow());
        rules.add(aiAnalyzeRule);

        // ============ 2. Feign调用DeepSeek大模型：异常比例熔断 ============
        // 这是更底层的熔断：当 AI 第三方接口大量失败/超时，直接熔断 Feign 调用
        DegradeRule deepSeekFeignRule = new DegradeRule();
        deepSeekFeignRule.setResource("DeepSeekFeign#analyzeChat");
        deepSeekFeignRule.setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType());
        deepSeekFeignRule.setCount(businessDynamicConfig.getDeepseekErrorRatioThreshold());
        deepSeekFeignRule.setMinRequestAmount(5);
        deepSeekFeignRule.setStatIntervalMs(60000);
        deepSeekFeignRule.setTimeWindow(60);
        rules.add(deepSeekFeignRule);

        log.info("Sentinel 熔断规则：aiAfterSupportAnalyze(慢调用比例={}), DeepSeekFeign(异常比例={})",
                aiAnalyzeRule.getSlowRatioThreshold(), deepSeekFeignRule.getCount());
        DegradeRuleManager.loadRules(rules);
    }
}
