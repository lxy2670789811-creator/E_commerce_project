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
     *     product-detail-expire-seconds: 3600            # 商品详情缓存过期（秒）
     *     product-detail-expire-jitter-seconds: 300      # 过期时间随机抖动上限（秒，防雪崩）
     *     product-detail-null-cache-expire-seconds: 120  # 空值缓存过期（秒，防穿透）
     *     product-detail-rebuild-lock-enabled: true      # 缓存击穿防护开关（singleflight 互斥重建）
     *     product-detail-rebuild-lock-lease-seconds: 10  # 重建锁自动释放超时（秒，防 leader 崩溃死锁）
     *     product-detail-rebuild-lock-max-retries: 50     # 重建等待重试次数
     *     product-detail-rebuild-lock-backoff-millis: 20 # 重建等待退避（毫秒）
     *     # --- 商品列表缓存（默认首页无筛选商品流） ---
     *     product-list-cache-enabled: true      # 列表缓存开关
     *     product-list-expire-seconds: 120       # 列表缓存过期（秒，短 TTL 兜底一致性）
     *     product-list-null-cache-expire-seconds: 30 # 空结果缓存过期（秒，防穿透；0=关闭）
     *     # --- 订单超时关单（延迟消息 + 定时扫描兜底） ---
     *     order-timeout-cancel-enabled: true      # 超时关单总开关
     *     order-timeout-cancel-delay-level: 9     # 延迟级别（9=5分钟）
     *     order-timeout-scan-enabled: true        # 定时扫描兜底开关
     *     order-timeout-scan-cron: "0 0/1 * * * ?" # 扫描 cron（等价每分钟）
     *     order-timeout-scan-coarse-enabled: true # 粗粒度对账兜底开关（独立于 MQ 健康）
     *     order-timeout-scan-coarse-cron: "0 0/10 * * * ?" # 粗粒度对账 cron（每10分钟，最终兜底）
     *     order-timeout-seconds: 300              # 超时阈值（秒）
     *     order-timeout-scan-batch-size: 100      # 单批处理上限
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

    /**
     * 商品详情缓存过期时间的随机抖动上限（秒）—— 缓存雪崩防护
     * 实际过期时间 = productDetailExpireSeconds + random[0, 本值]
     * 默认：300秒（让 1 小时的缓存错峰落在 60~65 分钟内过期，避免批量 key 同时失效集体回源）
     * 设为 0 = 关闭抖动（所有 key 严格同 TTL，仅用于压测对照）
     */
    private long productDetailExpireJitterSeconds = 300L;

    /**
     * 商品详情"空值缓存"过期时间（秒）—— 缓存穿透防护
     * 商品不存在（含已被逻辑删除）时，仍写入一个短 TTL 的空标记，
     * 防止同一个不存在的 productId 被反复打到数据库
     * 默认：120秒（足够短，商品新增/恢复后最多 120s 即可见；足够长，能挡住扫描型流量）
     * 设为 0 = 关闭空值缓存（紧急降级开关）
     */
    private long productDetailNullCacheExpireSeconds = 120L;

    // ====== 缓存击穿防护（singleflight 互斥重建） ======
    /**
     * 商品详情"缓存击穿"防护开关（singleflight 互斥重建）
     * true = 热点 key 过期瞬间，同一 productId 同一时刻只允许一个线程回源，
     *        其余并发请求等待其完成后读缓存，避免数据库被打爆
     * false = 关闭，退化为"查DB + 回写"（与改造前行为一致，紧急降级开关）
     */
    private boolean productDetailRebuildLockEnabled = true;

    /**
     * 重建锁持有超时（秒）—— 防止 leader 崩溃导致锁永不释放、该商品永远无法重建
     * Redisson 会在超过该时长后自动释放锁（死锁保护）
     * 默认：10秒（远大于正常回源耗时，仅在 DB 严重抖动时兜底）
     */
    private long productDetailRebuildLockLeaseSeconds = 10L;

    /**
     * 重建锁等待重试次数（缓存击穿防护）
     * 未拿到锁的并发请求会轮询缓存、退避重试，直到 leader 重建完成或重试耗尽
     * 默认：50次（配合退避时间约 1 秒预算，覆盖绝大多数正常回源耗时）
     */
    private int productDetailRebuildLockMaxRetries = 50;

    /**
     * 重建锁等待退避时间（毫秒，缓存击穿防护）
     * 未拿到锁的并发请求每次重试前的休眠时长（错峰，避免所有等待者同时重试）
     * 默认：20毫秒
     */
    private long productDetailRebuildLockBackoffMillis = 20L;

    // ====== 商品列表缓存（默认首页商品流） ======
    // 说明：仅对"无筛选"的默认首页商品流（keyword/category/status 均为空）做整页缓存。
    // 带 keyword 自由搜索/多条件任意组合的列表 key 会爆炸、命中率低、失效困难，故不做整页缓存，
    // 那部分靠联合索引 idx_list_query + Sentinel 限流兜底。写操作会批量失效默认流缓存。
    /**
     * 商品列表缓存开关（默认首页无筛选商品流）
     * true = 命中缓存直接返回分页结果；false = 直查 DB（紧急降级开关）
     */
    private boolean productListCacheEnabled = true;

    /**
     * 商品列表缓存过期时间（秒）
     * 列表对时效性要求高（新增/改价/上下架后应尽快可见），默认 120 秒，
     * 靠短 TTL 兜底一致性"脏读窗口"。
     */
    private long productListExpireSeconds = 120L;

    /**
     * 商品列表"空结果"缓存过期时间（秒）—— 防穿透
     * 某页在 DB 中查不到数据(空列表/翻过头)时写短 TTL 空标记，挡住反复回源扫描。
     * 默认 30 秒。设 0 = 关闭空结果缓存。
     */
    private long productListNullCacheExpireSeconds = 30L;

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

    // ====== 订单超时关单 - 定时扫描兜底（RocketMQ 延迟消息失效时的补偿） ======
    /**
     * 定时扫描兜底开关（RocketMQ 不可用/消息丢失时的补偿）
     * true = 由 @Scheduled 定时扫描超时未支付订单并自动关单
     */
    private boolean orderTimeoutScanEnabled = true;

    /**
     * 定时扫描 cron 表达式（Spring 6 位格式，含秒）
     * 默认：每分钟执行一次
     */
    private String orderTimeoutScanCron = "0 */1 * * * ?";

    /**
     * 订单超时阈值（秒）：创建时间早于 now-超时阈值 且仍为待支付的订单视为超时
     * 默认：300（5分钟，与 RocketMQ 默认延迟级别 9 对齐，避免扫描比延迟消息更早误关）
     */
    private long orderTimeoutSeconds = 300L;

    /**
     * 定时扫描单批处理的最大订单数（防止单次任务积压过多拖垮线程）
     */
    private int orderTimeoutScanBatchSize = 100;

    /**
     * 粗粒度对账兜底开关（独立于 MQ 健康状态的固定周期强制扫描）
     * true = 即使 RocketMQ 通道健康，也按 order-timeout-scan-coarse-cron 周期强制扫描超时订单，
     *        用于兜底"异步发送在回调到达前 JVM 崩溃 / 消息静默丢失但健康标志未翻转"等极端窗口，
     *        保证任何超时未支付订单最终一定被关单。
     * 默认：true
     */
    private boolean orderTimeoutScanCoarseEnabled = true;

    /**
     * 粗粒度对账 cron 表达式（Spring 6 位格式，含秒）
     * 默认：每 10 分钟执行一次（频率远低于主扫描，对 DB 压力可忽略，却足以在极端窗口下兜底）
     */
    private String orderTimeoutScanCoarseCron = "0 */10 * * * ?";

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
