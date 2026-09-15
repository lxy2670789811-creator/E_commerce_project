package com.ecommerce.common.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 缓存击穿防护（singleflight 互斥重建）的运行指标
 *
 * <p><b>为什么需要它：</b>singleflight 的参数（等待预算、租约时长、等待片长、降级并发上限）
 * 都是"凭经验拍的"，而它们的正确性只能由运行数据来验证。没有指标，
 * 调参就只能靠猜，故障时也说不清是"保护生效"还是"保护失效"。
 * 本类把判断参数是否合理所需的几个关键事实变成可读数字。</p>
 *
 * <p><b>五个关键指标及其解读：</b></p>
 * <ul>
 *   <li>{@code budgetExhausted}（等待预算耗尽次数）—— 与 {@code dbLoads} 的比值就是
 *       <b>singleflight 的实际合并失败率</b>。持续 > 0 说明 leader 回源比预算还慢，
 *       要么调大预算，要么先查 DB 为什么慢。</li>
 *   <li>{@code degradeRejected}（降级被并发闸门拒绝次数）—— 过载保护真正起作用的次数，
 *       也是"SLA 被牺牲了多少请求"的直接度量。持续 > 0 = 容量不足的信号。</li>
 *   <li>{@code leaseLost}（leader 完成时锁已不在手里）—— <b>判断租约时长是否合理的直接证据</b>。
 *       非 0 说明回源耗时超过了租约，锁在中途自动过期、期间可能有第二个 leader 并发回源。
 *       这是最容易被忽略的一个：lease 配短了不会报错，只会静默地多查几次 DB。</li>
 *   <li>{@code slowDbLoads} / {@code maxDbLoadMillis}（慢回源次数 / 最大回源耗时）——
 *       定位"到底是不是 DB 慢"，把 upper 层的等待问题归因到底层。</li>
 *   <li>{@code waitRounds}（等待轮次）—— 每个 follower 平均轮询几轮，直接反映 Redis 命令放大倍数
 *       （旧实现里 51 轮 = 单次 miss 被放大成 51 次 GET）。</li>
 * </ul>
 *
 * <p><b>实现取舍：</b>用 {@link LongAdder} 自建，而不是直接引 Micrometer。
 * 本项目当前没有任何 metrics 基础设施，为几个计数器引入 actuator + registry 会白白
 * 扩大依赖面和端点暴露面。这些方法都是"记录 + 累加"，将来接入监控时
 * （在 pom 加 {@code spring-boot-starter-actuator}，本类注入 {@code MeterRegistry}），
 * 只需在 {@code record*} 方法里补一行 {@code Counter.increment()}，调用方一行都不用改。</p>
 *
 * <p><b>出口：</b>{@link #snapshot()} 返回一行摘要，同时每 {@value #SUMMARY_LOG_INTERVAL_MILLIS}ms
 * 最多自动打一条 info 日志——只在指标真的发生变化时才打，不刷屏。
 * 数字只看不报等于没有，故保留这个低成本出口。</p>
 *
 * <p>线程安全：全部为无锁累加（LongAdder / AtomicLong），落在缓存 miss 路径上也不会成为瓶颈。</p>
 */
@Slf4j
@Component
public class RebuildLockMetrics {

    /** 慢回源阈值（毫秒）：超过该耗时的回源单独计数，用于区分"偶发抖动"和"DB 持续慢" */
    private static final long SLOW_DB_LOAD_MILLIS = 500L;

    /** 指标摘要日志的最小间隔（毫秒） */
    private static final long SUMMARY_LOG_INTERVAL_MILLIS = 30_000L;

    /** 等待轮次：follower 每做一次"读缓存 + tryLock"记 1，反映 Redis 命令放大倍数 */
    private final LongAdder waitRounds = new LongAdder();

    /** 成功成为 leader（拿到重建锁）的次数 */
    private final LongAdder leaderAcquired = new LongAdder();

    /** 等待预算耗尽、走降级的次数（= singleflight 合并失败） */
    private final LongAdder budgetExhausted = new LongAdder();

    /** 降级被并发闸门拒绝、快速失败的次数（过载保护真正起作用的次数） */
    private final LongAdder degradeRejected = new LongAdder();

    /** 租约丢失次数：leader 完成时锁已自动过期（或解锁时报错），说明 lease 可能配短了 */
    private final LongAdder leaseLost = new LongAdder();

    /** 回源（查 DB + 回写缓存）总次数 */
    private final LongAdder dbLoads = new LongAdder();

    /** 慢回源次数（单次耗时 > {@value #SLOW_DB_LOAD_MILLIS}ms） */
    private final LongAdder slowDbLoads = new LongAdder();

    /** 回源累计耗时（毫秒），用于算平均耗时 */
    private final LongAdder dbLoadTotalMillis = new LongAdder();

    /** 回源最大耗时（毫秒） */
    private final AtomicLong dbLoadMaxMillis = new AtomicLong();

    /** 上次打印摘要的时间（nanoTime），用于节流 */
    private final AtomicLong lastSummaryLogNanos = new AtomicLong();

    /** 记录一次等待轮次（每个 follower 每轮一次） */
    public void recordWaitRound() {
        waitRounds.increment();
    }

    /** 记录一次"成功成为 leader" */
    public void recordLeaderAcquired() {
        leaderAcquired.increment();
    }

    /** 记录一次"等待预算耗尽、即将降级查 DB" */
    public void recordBudgetExhausted() {
        budgetExhausted.increment();
        logSummaryIfDue();
    }

    /** 记录一次"降级被并发闸门拒绝、快速失败" */
    public void recordDegradeRejected() {
        degradeRejected.increment();
        logSummaryIfDue();
    }

    /**
     * 记录一次"租约丢失"——leader 完成回源时锁已不在自己手上。
     *
     * <p>这是租约时长是否合理的直接证据：非 0 说明 {@code lease-seconds}
     * 小于真实回源耗时，锁在中途被自动释放，期间可能有第二个 leader 并发回源
     * （数据仍正确，但 DB 白查一次、缓存白写一次）。</p>
     *
     * <p>本方法只负责计数与节流打摘要，具体日志（含 lockKey / 异常栈）由调用方打印，
     * 避免同一个事件在日志里出现两条。</p>
     */
    public void recordLeaseLost() {
        leaseLost.increment();
        logSummaryIfDue();
    }

    /**
     * 记录一次回源耗时（查 DB + 回写缓存的总耗时）
     *
     * @param costMillis 本次回源耗时（毫秒）
     */
    public void recordDbLoad(long costMillis) {
        dbLoads.increment();
        dbLoadTotalMillis.add(costMillis);
        dbLoadMaxMillis.accumulateAndGet(costMillis, Math::max);
        if (costMillis > SLOW_DB_LOAD_MILLIS) {
            slowDbLoads.increment();
            log.warn("商品详情回源耗时过长：costMillis={}（阈值 {}ms）", costMillis, SLOW_DB_LOAD_MILLIS);
        }
    }

    /** 一行摘要，便于日志输出 / 测试断言 / 将来转发到监控系统 */
    public String snapshot() {
        long loads = dbLoads.sum();
        long avgMillis = loads == 0 ? 0L : dbLoadTotalMillis.sum() / loads;
        return "waitRounds=" + waitRounds.sum()
                + ", leaderAcquired=" + leaderAcquired.sum()
                + ", budgetExhausted=" + budgetExhausted.sum()
                + ", degradeRejected=" + degradeRejected.sum()
                + ", leaseLost=" + leaseLost.sum()
                + ", dbLoads=" + loads
                + ", slowDbLoads=" + slowDbLoads.sum()
                + ", avgDbLoadMillis=" + avgMillis
                + ", maxDbLoadMillis=" + dbLoadMaxMillis.get();
    }

    /**
     * 节流打印摘要：最多每 {@value #SUMMARY_LOG_INTERVAL_MILLIS}ms 一条，
     * 且只在本窗口内发生过值得关注的事件（降级 / 拒绝 / 租约丢失）时才打印。
     *
     * <p>用 CAS 占位而非直接比较：多个线程同时发现"该打了"时，只有一个真正打印。</p>
     */
    private void logSummaryIfDue() {
        long now = System.nanoTime();
        long last = lastSummaryLogNanos.get();
        if (now - last < TimeUnit.MILLISECONDS.toNanos(SUMMARY_LOG_INTERVAL_MILLIS)) {
            return;
        }
        if (!lastSummaryLogNanos.compareAndSet(last, now)) {
            return; // 已有其他线程抢到本次打印机会
        }
        log.info("singleflight 重建指标摘要：{}", snapshot());
    }
}
