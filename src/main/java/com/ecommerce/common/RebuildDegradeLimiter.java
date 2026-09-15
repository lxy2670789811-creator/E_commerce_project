package com.ecommerce.common;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 降级回源的并发闸门（缓存击穿防护的最后一道防线）
 *
 * <p><b>解决什么问题：</b>等待 leader 的预算耗尽后，同一批 follower 会同时去查 DB——
 * 这正是 singleflight 想避免的事情，却在"保护失效"的瞬间集中发生。
 * 若 leader 是因为 DB 慢而卡住，那么这些 follower 打下去的查询只会让 DB 更慢，
 * 形成正反馈，最终连不相关的业务也被拖垮。</p>
 *
 * <p><b>为什么不返回兜底数据：</b>商品详情里带价格和库存，返回兜底值等于让用户
 * 看到空商品或错误价格，是把"DB 压力"升级成"业务可用性事故"，代价只是被转移到了更贵的一侧。
 * 正确做法是<b>封顶 + 快速失败</b>：守住数据正确性，把 DB 并发限制在可承受范围内，
 * 超出部分快速失败（上层返回"系统繁忙"），让线程尽快释放而不是堆积。</p>
 *
 * <p><b>为什么用 CAS 计数而不是 {@link java.util.concurrent.Semaphore}：</b>
 * 并发上限来自 Nacos 动态配置，需要热更新。Semaphore 的许可数在构造时就固定，
 * 运行期调整得靠 {@code release}/{@code reducePermits} 补偿，容易算错；
 * 而 CAS 方案每次都拿最新配置与实时在途数比较，<b>调大调小都立即生效</b>，也不需要额外同步。</p>
 *
 * <p><b>语义约定：</b>{@link #tryAcquire(int)} 返回 true 的调用方必须在自己的 finally 里
 * 调用一次 {@link #release()}，否则计数只增不减、闸门会被永久占满。</p>
 *
 * <p>线程安全：无锁 CAS，落在降级路径上不会成为新瓶颈。</p>
 */
@Slf4j
public class RebuildDegradeLimiter {

    /** 当前在途（已获准降级、正在查 DB）的请求数 */
    private final AtomicInteger inFlight = new AtomicInteger();

    /**
     * 尝试获取一个降级许可
     *
     * @param limit 并发上限；{@code <= 0} 表示不封顶（退化为旧行为，直接放行）
     * @return true = 已获准（调用方必须配对 {@link #release()}）；false = 已达上限，应快速失败
     */
    public boolean tryAcquire(int limit) {
        if (limit <= 0) {
            return true; // 不封顶：紧急情况下可配置为 0，恢复成"降级不做任何限制"的旧行为
        }
        while (true) {
            int current = inFlight.get();
            if (current >= limit) {
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
            // CAS 失败说明有其他线程同时进出，重读上限后重试
        }
    }

    /** 释放一个降级许可（必须与 {@link #tryAcquire(int)} 返回 true 配对） */
    public void release() {
        int remaining = inFlight.decrementAndGet();
        if (remaining < 0) {
            // 防御性兜底：正常情况下不会发生，出现即说明 tryAcquire/release 未配对（代码缺陷）
            log.warn("降级闸门计数出现负值，tryAcquire/release 可能未配对：inFlight={}", remaining);
        }
    }

    /** 当前在途的降级请求数（供指标 / 测试观测） */
    public int inFlight() {
        return inFlight.get();
    }
}
