package com.ecommerce.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.config.BusinessDynamicConfig;
import com.ecommerce.entity.OrderDO;
import com.ecommerce.enums.OrderStatusEnum;
import com.ecommerce.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 订单超时未支付自动关单 - 定时扫描兜底（补偿机制）
 *
 * <p>业务定位：RocketMQ 延迟消息是超时关单的主通道，但存在失效风险：
 * <ol>
 *   <li>MQ 未启用 / 宕机 / 网络异常 → {@code sendDelayCancel} 仅打日志降级，延迟消息根本没发出去；</li>
 *   <li>MQ 投递乱序、消息丢失、消费积压 → 延迟消息迟迟不被消费；</li>
 *   <li>RocketMQ 延迟消息只有固定 18 档，精度有限。</li>
 * </ol>
 * 本定时任务作为补偿通道：周期扫描"仍为待支付且创建时间超过超时阈值"的订单，调用幂等的
 * {@link OrderService#autoCancelOrder} 关单并回滚库存。
 *
 * <p>为什么可以放心和 RocketMQ 双跑：{@code autoCancelOrder} 只处理待支付状态订单，
 * 已被延迟消息关掉的单（状态已非待支付）会被幂等跳过，不会重复回滚库存。延迟消息与
 * 定时扫描形成"双保险"，互为兜底。
 *
 * <p><b>MQ 健康门控（常态零查询）</b>：扫描任务仅在 RocketMQ 通道不可用时才真正查询数据库。
 * 常态下（MQ 装配正常且最近发送成功）任务直接返回，不做任何 DB 查询，因此不引入周期轮询开销，
 * 不违背"用定时消息规避周期扫描"的原设计意图；仅当 MQ 感知到发送失败/未装配时才接管补偿。
 *
 * <p>开关与参数均来自 {@link BusinessDynamicConfig}（Nacos 可热更新）：
 * <ul>
 *   <li>{@code order-timeout-scan-enabled}：总开关，false 时本任务不执行；</li>
 *   <li>{@code order-timeout-scan-cron}：扫描频率；</li>
 *   <li>{@code order-timeout-seconds}：超时阈值（秒），建议 ≥ RocketMQ 延迟级别对应时长；</li>
 *   <li>{@code order-timeout-scan-batch-size}：单批处理上限。</li>
 * </ul>
 *
 * <p><b>多副本选主（云原生 L0）</b>：本任务是 {@code @Scheduled}，多副本部署时每个 Pod 都会触发。
 * 后果要说清楚：<b>不会造成业务错误</b> —— {@code autoCancelOrder} 只处理待支付订单，
 * 已关掉的单会被幂等跳过，不会重复回滚库存。真正的问题是①同一批订单被 N 个 Pod 重复扫描、
 * DB 查询放大 N 倍；②MQ 健康门控 {@code isRocketMqUsable()} 是<b>实例本地标志</b>，
 * "A 的 MQ 挂了所以 A 在扫、B 正常所以 B 不扫"，扫描日志分散在不同 Pod，排查要逐个翻。
 * 因此执行前先用 Redisson 抢一把全局锁，抢到才扫，一轮只由一个副本执行。
 *
 * <p>为什么用 Redisson 而不是 ShedLock / K8s CronJob：项目已引入 Redisson（库存锁、缓存重建锁都用它），
 * 零新增依赖；ShedLock 要加一张表；CronJob 要拆代码另做镜像，对这个体量偏重。
 */
@Slf4j
@Component
@RequiredArgsConstructor
// Bean 常驻注册；是否真正执行由 order-timeout-cancel-enabled（总开关）、
// order-timeout-scan-enabled（兜底任务专属开关）与 MQ 健康门控在任务内运行时判断。
public class OrderTimeoutScanScheduler {

    /** 扫描选主锁：多副本同时只有一个能拿到，拿到才执行本轮扫描 */
    private static final String SCAN_LOCK_KEY = "ecommerce:lock:scheduler:order-timeout-scan";

    /**
     * 选主锁的租约（秒）。
     *
     * <p>取值要大于"单轮扫描耗时"，否则锁提前过期、另一副本会重复扫（虽然幂等无害，但失去选主意义）。
     * 单轮耗时 = 查一批（默认 100 条）+ 逐条关单（每笔自带事务与库存锁等待），常态秒级完成；
     * 60s 对默认 batch-size 留了充足余量。
     *
     * <p>这里刻意<b>不做成 Nacos 热配置</b>：与库存锁那些业务参数不同，调度锁的 lease 只在任务触发时读一次，
     * 运行期调整没有实际价值，反而多一个要维护和口头解释的参数。
     * ⚠️ 若把 {@code order-timeout-scan-batch-size} 调到很大（比如上万），需同步调大此值。
     */
    private static final long SCAN_LOCK_LEASE_SECONDS = 60L;

    private final OrderService orderService;
    private final BusinessDynamicConfig businessDynamicConfig;
    private final OrderTimeoutCancelSender orderTimeoutCancelSender;
    private final RedissonClient redissonClient;

    /**
     * 周期扫描超时未支付订单并自动关单
     *
     * <p>cron 通过 SpEL 每次触发时从 {@link BusinessDynamicConfig#getOrderTimeoutScanCron()} 动态读取，
     * Nacos 修改后无需重启即生效；任务内部捕获单条异常，避免某条失败中断整批。
     */
    @Scheduled(cron = "#{@businessDynamicConfig.orderTimeoutScanCron}")
    public void scanExpiredPendingOrders() {
        // 总开关（与 RocketMQ 消费端共用的关单总开关）关闭时跳过
        if (!businessDynamicConfig.isOrderTimeoutCancelEnabled()) {
            log.debug("超时关单总开关已关闭，定时扫描跳过");
            return;
        }
        if (!businessDynamicConfig.isOrderTimeoutScanEnabled()) {
            log.debug("定时扫描兜底开关已关闭，跳过");
            return;
        }
        // MQ 健康门控：RocketMQ 通道可用（模板已装配且最近发送成功）时，超时关单由延迟消息主通道负责，
        // 扫描直接返回、零 DB 查询，常态下不引入周期轮询开销。仅当 MQ 不可用时才接管补偿。
        if (orderTimeoutCancelSender.isRocketMqUsable()) {
            log.debug("RocketMQ 超时关单通道可用，定时扫描跳过（MQ 健康门控，避免常态周期查询）");
            return;
        }
        runWithLeaderLock("RocketMQ 超时关单通道不可用，定时扫描接管超时订单补偿");
    }

    /**
     * 粗粒度对账兜底（独立于 MQ 健康状态，固定周期强制扫描）。
     *
     * <p>目的：堵住"异步发送在回调到达前 JVM 崩溃 / 消息静默丢失但健康标志未翻转"等极端窗口，
     * 保证任何超时未支付订单最终一定被关单，不依赖发送端的健康标志 {@code lastSendSucceeded}。
     * 频率较低（默认每 10 分钟），对 DB 压力可忽略，却足以在延迟消息主通道失效时兜底。
     */
    @Scheduled(cron = "#{@businessDynamicConfig.orderTimeoutScanCoarseCron}")
    public void reconcileExpiredPendingOrders() {
        if (!businessDynamicConfig.isOrderTimeoutCancelEnabled()) {
            log.debug("超时关单总开关已关闭，粗粒度对账跳过");
            return;
        }
        if (!businessDynamicConfig.isOrderTimeoutScanCoarseEnabled()) {
            log.debug("粗粒度对账开关已关闭，跳过");
            return;
        }
        runWithLeaderLock("粗粒度对账兜底：固定周期强制扫描超时未支付订单（独立于 MQ 健康状态）");
    }

    /**
     * 多副本选主：抢到全局锁的副本才执行本轮扫描，抢不到就直接跳过。
     *
     * <p><b>为什么 waitTime 传 0（不等待）—— 与缓存重建锁的"有限等待"方向相反</b>：
     * 缓存重建是"必须拿到结果才能返回请求"，所以要有限等待、随时接替 leader；
     * 定时扫描是"这一轮谁做都行，做不了等下一轮"。若在这里排队等待，
     * 会让 N 个副本<b>串行</b>执行同一批任务 —— 既无收益，还可能把执行拖到下一个 cron 周期之外。
     *
     * <p>{@code tryLock} 声明了 {@link InterruptedException}，被中断时放弃本轮（等下个周期），
     * 不向上抛 —— 抛出去会被 Spring 调度线程池吞掉并打一屏堆栈，没有意义。
     */
    private void runWithLeaderLock(String reason) {
        RLock lock = redissonClient.getLock(SCAN_LOCK_KEY);
        boolean acquired;
        try {
            acquired = lock.tryLock(0, SCAN_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("定时扫描选主被中断，放弃本轮执行（lockKey={}）", SCAN_LOCK_KEY);
            return;
        }
        if (!acquired) {
            log.debug("本轮扫描由其它副本执行，当前副本跳过（lockKey={}）", SCAN_LOCK_KEY);
            return;
        }
        try {
            doScan(reason);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            } else {
                // 租约已过期：锁被 Redis 自动释放，此时 unlock 会抛 IllegalMonitorStateException，必须跳过。
                // 这个分支要记 warn 而不是静默跳过 —— 频繁出现说明单轮扫描耗时超过了 lease，
                // 选主已经失效（会有第二个副本进来重复扫），需要调大 SCAN_LOCK_LEASE_SECONDS 或调小 batch-size。
                log.warn("扫描锁租约已过期，跳过释放（lockKey={}, lease={}s）；" +
                                "若频繁出现说明单轮扫描耗时超过 lease，选主已失效",
                        SCAN_LOCK_KEY, SCAN_LOCK_LEASE_SECONDS);
            }
        }
    }

    /**
     * 扫描"仍为待支付且创建时间超过超时阈值"的订单并自动关单（补偿核心逻辑）。
     * 由细粒度扫描（门控于 MQ 健康）与粗粒度独立对账（强制）共用。
     */
    private void doScan(String reason) {
        log.info("{}", reason);

        LocalDateTime deadline = LocalDateTime.now().minusSeconds(businessDynamicConfig.getOrderTimeoutSeconds());
        List<OrderDO> expiredOrders = orderService.list(new LambdaQueryWrapper<OrderDO>()
                .select(OrderDO::getId, OrderDO::getOrderNo)
                .eq(OrderDO::getStatus, OrderStatusEnum.PENDING_PAYMENT.getCode())
                .lt(OrderDO::getCreateTime, deadline)
                .orderByAsc(OrderDO::getCreateTime)
                .last("LIMIT " + businessDynamicConfig.getOrderTimeoutScanBatchSize()));

        if (expiredOrders == null || expiredOrders.isEmpty()) {
            return;
        }
        log.info("扫描命中 {} 笔超时未支付订单，开始补偿关单", expiredOrders.size());

        int success = 0;
        for (OrderDO order : expiredOrders) {
            try {
                // autoCancelOrder 幂等 + 自带事务；单条失败不影响其余
                orderService.autoCancelOrder(order.getId());
                success++;
            } catch (Exception e) {
                log.error("补偿关单失败：orderId={}, orderNo={}",
                        order.getId(), order.getOrderNo(), e);
            }
        }
        log.info("补偿关单完成：成功 {} / {} 笔", success, expiredOrders.size());
    }
}
