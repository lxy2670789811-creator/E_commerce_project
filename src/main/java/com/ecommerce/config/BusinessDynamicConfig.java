package com.ecommerce.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 业务动态配置类
 * 配置项存放于 Nacos 配置中心，修改后无需重启服务即可动态刷新
 *
 * 所有字段均可在 Nacos 控制台（DataID: ecommerce-business.yaml）中修改并实时生效，
 * 适用于生产环境灰度调整、紧急故障开关、限流阈值热更新等场景。
 *
 * Nacos 中 ecommerce-business.yaml 完整配置示例：
 * ecommerce:
 *   business:
 *     # --- 分布式锁 ---
 *     inventory-lock-lease-seconds: 30      # 库存锁持有超时（秒）
 *     inventory-lock-wait-seconds: 5        # 库存锁最大等待（秒）
 *     # --- AI 大模型 ---
 *     ai-api-timeout-seconds: 30            # AI API 超时
 *     ai-api-retry-times: 1                 # AI API 重试次数
 *     after-support-enabled: true           # 售后功能开关
 *     ai-rate-limit-max-requests: 10        # AI 限流上限
 *     ai-rate-limit-time-window-seconds: 60 # AI 限流窗口
 *     # --- Sentinel 熔断兜底阈值（控制台配置优先） ---
 *     order-create-qps-threshold: 100       # 下单 QPS 流控阈值
 *     ai-analyze-slow-ratio-threshold: 0.6  # AI 慢调用比例熔断阈值
 *     ai-analyze-min-request-amount: 5      # AI 熔断最小请求数
 *     ai-analyze-stat-interval-ms: 60000    # AI 熔断统计窗口
 *     ai-analyze-time-window: 30            # AI 熔断时长（秒）
 *     deepseek-error-ratio-threshold: 0.5   # Feign 异常比例熔断阈值
 *     # --- 缓存 ---
 *     product-detail-expire-seconds: 3600   # 商品详情缓存过期
     *     # --- 订单超时关单 ---
     *     order-timeout-cancel-enabled: true      # 超时关单总开关
     *     order-timeout-cancel-delay-level: 9     # 延迟级别（9=5分钟）
     *     # --- 下单幂等（一次性凭证） ---
     *     order-token-enabled: true               # 凭证校验开关
     *     order-token-expire-seconds: 300         # 凭证有效期（秒）
     */
@Data
@Component
@RefreshScope // 关键：开启 Nacos 配置动态刷新
@ConfigurationProperties(prefix = "ecommerce.business")
public class BusinessDynamicConfig {

    // ====== 分布式锁 ======
    /**
     * 库存扣减分布式锁持有超时时间（秒）
     * 默认：30秒（防止死锁；若业务耗时较长可调大，或设置为-1启用看门狗自动续期）
     */
    private long inventoryLockLeaseSeconds = 30L;

    /**
     * 库存分布式锁 - 最多等待时间（秒）
     * 默认：5秒（超过则快速失败，避免大量请求阻塞）
     */
    private long inventoryLockWaitSeconds = 5L;

    // ====== AI 大模型调用 ======
    /**
     * AI 大模型 API 调用超时时间（秒）
     * 默认：30秒
     */
    private int aiApiTimeoutSeconds = 30;

    /**
     * AI 大模型 API 调用失败重试次数
     * 默认：1次（首次失败后再试1次，用于应对瞬时网络抖动；注意 AI 接口非严格幂等，不宜过大）
     */
    private int aiApiRetryTimes = 1;

    /**
     * 售后功能全局开关
     * true = 开启售后AI分析功能
     * false = 关闭，所有售后分析请求直接返回"功能暂不可用"降级
     */
    private boolean afterSupportEnabled = true;

    /**
     * AI接口限流 - 时间窗口内最大请求数
     */
    private int aiRateLimitMaxRequests = 10;

    /**
     * AI接口限流 - 时间窗口大小（秒）
     */
    private int aiRateLimitTimeWindowSeconds = 60;

    // ====== Sentinel 兜底阈值（仅控制台未配置时生效） ======
    /**
     * 订单创建 QPS 流控阈值（Sentinel 本地兜底规则也可读取，控制台规则优先）
     */
    private double orderCreateQpsThreshold = 100.0;

    /**
     * AI 售后分析慢调用比例熔断阈值（本地兜底，控制台规则优先）
     * 慢调用比例 >= 此值触发熔断
     */
    private double aiAnalyzeSlowRatioThreshold = 0.6;

    /**
     * AI 售后分析熔断 - 最小请求数
     */
    private int aiAnalyzeMinRequestAmount = 5;

    /**
     * AI 售后分析熔断 - 统计窗口（毫秒）
     */
    private int aiAnalyzeStatIntervalMs = 60000;

    /**
     * AI 售后分析熔断 - 熔断时长（秒）
     */
    private int aiAnalyzeTimeWindow = 30;

    /**
     * DeepSeek Feign 异常比例熔断阈值
     */
    private double deepseekErrorRatioThreshold = 0.5;

    // ====== 缓存 ======
    /**
     * 商品详情缓存过期时间（秒）
     * 默认：3600秒（1小时）
     */
    private long productDetailExpireSeconds = 3600L;

    // ====== 订单超时未支付自动关单（RocketMQ 延迟消息） ======
    /**
     * 超时关单总开关（消费端收到延迟消息后校验）
     */
    private boolean orderTimeoutCancelEnabled = true;

    /**
     * RocketMQ 延迟消息级别（只支持固定的18个级别）：
     * 1=1s,2=5s,3=10s,4=30s,5=1m,6=2m,7=3m,8=4m,9=5m,10=6m,11=7m,12=8m,
     * 13=9m,14=10m,15=20m,16=30m,17=1h,18=2h
     * 默认：9（5分钟）
     */
    private int orderTimeoutCancelDelayLevel = 9;

    // ====== 下单幂等（一次性凭证） ======
    /**
     * 下单一次性凭证开关
     * true = 校验凭证，重复提交（同一凭证第二次使用）直接拒绝
     * false = 跳过凭证校验（紧急降级开关），由数据库唯一索引 uk_idempotency_token 兜底
     */
    private boolean orderTokenEnabled = true;

    /**
     * 下单凭证有效期（秒）
     * 默认：300秒（5分钟，够用户填完下单页；过期需重新进入下单页领取）
     */
    private long orderTokenExpireSeconds = 300L;
}
