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
     *     product-detail-rebuild-lock-lease-seconds: 5   # 重建锁自动释放超时（秒，≈回源上限，非越大越好）
     *     product-detail-rebuild-lock-budget-millis: 1000 # 重建等待总预算（毫秒，硬上限）
     *     product-detail-rebuild-lock-backoff-millis: 20 # 重建等待起始片长（毫秒，每轮翻倍并带抖动）
     *     product-detail-rebuild-lock-degrade-max-concurrency: 8 # 降级查DB的并发上限（0=不封顶）
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
     *
     * <p><b>取值口径：约等于"回源超时上限"，而不是"越大越安全"。</b>
     * 直觉上会想把租约调得很大以免中途丢锁，但方向是反的：
     * leader 一旦卡死（如 DB 无响应），这个商品在本租约周期内<b>所有</b>请求
     * 都拿不到锁、只能走降级查 DB——租约越长，"保护完全失效"的窗口越长。
     * 所以要按"回源正常需要多久"来取，而不是按"最坏能有多久"。</p>
     *
     * <p>本项目口径：Hikari {@code connection-timeout=3000}（拿不到连接最多等 3 秒）
     * + 查询与回写余量 → 回源上限约 3s，故租约取 <b>5 秒</b>，留 2 秒冗余。
     * 两个联动约束：</p>
     * <ul>
     *   <li><b>租约 &gt; 等待预算</b>：否则等待者可能等到"锁已易主"，白等一轮；</li>
     *   <li><b>租约 &gt; 回源上限</b>：否则锁在回源途中自动释放，会出现第二个 leader
     *       （数据仍正确，但同一次 miss 被查两遍 DB）。是否发生由
     *       {@code RebuildLockMetrics} 的 {@code leaseLost} 指标直接暴露。</li>
     * </ul>
     * <p>当前默认组合：等待预算 1s ＋ 回源上限 3s ＝ 4s &lt; 租约 5s，自洽。</p>
     */
    private long productDetailRebuildLockLeaseSeconds = 5L;

    /**
     * 重建等待总预算（毫秒）——缓存击穿防护，等待 leader 重建完成的时间硬上限
     *
     * <p>用"总时长预算"而不是"重试次数"：次数约束不了时间。
     * 早期实现是 {@code maxRetries=50 × backoff=20ms}，看着是 1 秒，
     * 但每轮还要发一次 Redis 读，真实耗时 = N×backoff + N×RTT，抖动时能到 2 秒。
     * 现在由本预算算出绝对截止时间，每轮等待片长取"剩余预算"与"本轮退避"的较小值，
     * <b>总耗时恒不超过本值</b>，与退避怎么调都无关。</p>
     *
     * <p>默认：1000 毫秒。设为 0 = 关闭等待（未抢到锁立即降级直查 DB），
     * 可作紧急降级开关使用。</p>
     *
     * <p>取值需与 {@code ...-lease-seconds} 一起看：等待预算应显著小于租约时长，
     * 否则等待者可能等到"锁已易主"；反过来租约也不宜过大，
     * leader 卡死期间所有请求都会走降级查 DB，等于保护失效整整一个租约周期。</p>
     */
    private long productDetailRebuildLockBudgetMillis = 1000L;

    /**
     * 重建等待起始片长（毫秒，缓存击穿防护）——每次等待锁的时长
     *
     * <p>等待者用 {@code lock.tryLock(片长, lease)} 做有限等待，片长从本值起步<b>每轮翻倍</b>，
     * 并叠加 ±50% 抖动。为什么不是固定值：</p>
     * <ul>
     *   <li><b>抖动</b>：让同一批到达的等待者错峰重试，避免它们在退避结束时被同步唤醒、
     *       一起降级查 DB 形成瞬时洪峰。</li>
     *   <li><b>翻倍</b>：leader 卡死时，片长恒定会让 follower 反复"订阅锁 + 退订"几十次，
     *       把 Redis 命令量放大数十倍；翻倍后同一段预算内只需几次等待。</li>
     * </ul>
     *
     * <p><b>不宜调大</b>：Redisson 的语义是"被唤醒但抢锁失败的一方仍要等满本次片长"，
     * 片长太长会直接抬高正常请求的延迟（默认 20ms 与一次典型回源同量级）。
     * 下限 1ms（配 0 也按 1ms 处理），避免退化成空转。</p>
     */
    private long productDetailRebuildLockBackoffMillis = 20L;

    /**
     * 降级回源的并发上限（缓存击穿防护）—— 同一瞬间允许有多少个请求绕过 singleflight 直接查 DB
     *
     * <p>等待预算耗尽后，同一批 follower 会在相近时刻集体降级查 DB。
     * 这恰恰是 singleflight 想避免的场景，却在"保护失效"的瞬间集中发生；
     * 若 leader 卡住的原因就是 DB 慢，这些查询会让 DB 更慢、形成正反馈。
     * 本值把"同时查 DB"的请求数框在连接池可承受范围内。</p>
     *
     * <p><b>为什么超出后是"快速失败"而不是"返回兜底数据"：</b>商品详情带价格和库存，
     * 兜底值 = 空商品 / 错误价格，等于把 DB 压力换成业务事故。快速失败牺牲这批请求，
     * 但守住数据正确性、也守住系统存活。</p>
     *
     * <p><b>取值：</b>应显著小于数据库连接池上限（dev 10 / prod 15），给正常业务留连接。
     * 默认 8 —— 留出余量的同时，也确保降级本身不至于瞬间占满池子。
     * 设为 0 = 不封顶，退化成旧行为（降级不做限制），仅作紧急开关使用。</p>
     */
    private int productDetailRebuildLockDegradeMaxConcurrency = 8;

    // ====== 商品列表缓存（默认首页商品流） ======
    // 说明：仅对"无筛选"的默认首页商品流（keyword/category/status 均为空）做整页缓存。
    // 带 keyword 自由搜索/多条件任意组合的列表 key 会爆炸、命中率低、失效困难，故不做整页缓存，
    // 那部分靠联合索引 idx_list_query + Sentinel 限流兜底。
    // 写操作通过对版本号 key（ecommerce:product:list:default:version）执行一次 INCR 来失效
    // 全部分页缓存：O(1)、单条命令、不扫描键空间（历史上曾用 KEYS 前缀扫描，会阻塞 Redis 主线程）。
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
