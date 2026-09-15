package com.ecommerce.service.impl;

import com.ecommerce.common.BusinessException;
import com.ecommerce.common.ErrorCode;
import com.ecommerce.common.metrics.RebuildLockMetrics;
import com.ecommerce.config.BusinessDynamicConfig;
import com.ecommerce.entity.ProductDO;
import com.ecommerce.mapper.ProductMapper;
import com.ecommerce.vo.product.ProductVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存击穿防护（singleflight 互斥重建）的单元测试
 *
 * <p>用 Mockito spy 包裹真实 ProductServiceImpl，并 stub 继承自 ServiceImpl 的 getById，
 * 从而绕开真实 MySQL，只验证 singleflight 的核心分支逻辑（无需 Spring 上下文 / 真实 Redis）。</p>
 *
 * <p>注意：本测试验证的是「逻辑分支正确性」（leader 回源一次并释放锁、非 leader 降级不 NPE 解锁、
 * 拿锁后二次检查命中缓存不再查 DB、总等待被预算硬约束、降级并发被闸门封顶、
 * 租约丢失被计数），并非「并发去重」本身——
 * 并发去重依赖真实 Redisson 分布式锁，需在 docker-compose 起 Redis 后做集成验证。</p>
 *
 * <p><b>stub 锁等待时必须真的睡掉 waitMillis</b>：等待循环靠 {@code System.nanoTime()} 判断预算是否耗尽，
 * 如果 mock 让 tryLock 立刻返回 false，真实时间几乎不流逝、截止时间永远到不了 → 用例死循环。
 * 因此统一用 {@link #stubLockAlwaysUnavailable()} 让 mock 按片长真睡，模拟 Redisson 的真实行为。</p>
 *
 * <p>另注：{@code RLock.tryLock} 声明了 {@code InterruptedException}，所以凡是用
 * {@code when(lock.tryLock(...))} 打桩或用例方法，都要保留 {@code throws InterruptedException}。</p>
 */
@ExtendWith(MockitoExtension.class)
class ProductCacheSingleflightTest {

    @Mock
    private ProductMapper productMapper;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    /**
     * 列表缓存版本号用的 StringRedisTemplate（ProductServiceImpl 的第 3 个依赖）。
     * 本用例只覆盖详情缓存分支，不会走到版本号逻辑，因此仅需一个 mock 占位——
     * 但绝不能少：少了就是 4 参构造，运行期直接 NoSuchMethodError。
     */
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    /**
     * singleflight 重建的运行指标。用 mock 而非真实对象，
     * 是为了直接 {@code verify(...)} 断言埋点是否发生在正确的分支上
     * （否则只能去解析 snapshot() 字符串，断言既脆弱又难读）。
     */
    @Mock
    private RebuildLockMetrics rebuildLockMetrics;

    private ProductServiceImpl service;

    @BeforeEach
    void init() {
        BusinessDynamicConfig config = new BusinessDynamicConfig();
        config.setProductDetailRebuildLockEnabled(true);
        config.setProductDetailRebuildLockLeaseSeconds(5);
        config.setProductDetailRebuildLockBudgetMillis(60);
        config.setProductDetailRebuildLockBackoffMillis(10);
        service = spy(new ProductServiceImpl(productMapper, redisTemplate, stringRedisTemplate,
                config, redissonClient, rebuildLockMetrics));

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    /**
     * 用给定配置重建 service（少数用例需要不同预算 / 并发上限）。
     *
     * <p>这里不再重复 stub {@code redisTemplate.opsForValue()}：那是 mock 上的行为、
     * 与 service 实例无关，{@code init()} 里已 stub 过。重复 stub 在 Mockito 严格模式下
     * 会被判定为"未被使用"而报 UnnecessaryStubbingException。</p>
     */
    private void rebuildService(BusinessDynamicConfig config) {
        service = spy(new ProductServiceImpl(productMapper, redisTemplate, stringRedisTemplate,
                config, redissonClient, rebuildLockMetrics));
    }

    private ProductDO sampleDb(Long id) {
        ProductDO db = new ProductDO();
        db.setId(id);
        db.setName("商品" + id);
        return db;
    }

    /**
     * leader 路径：缓存 miss -> 拿到重建锁 -> 查 DB 一次 -> 回写缓存 -> 释放锁
     */
    @Test
    void leaderRebuildsOnceAndReleasesLock() throws InterruptedException {
        when(valueOps.get(anyString())).thenReturn(null); // 始终 miss
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true); // 模拟当前线程持有锁，触发 safeUnlock
        doReturnSample(1L);

        ProductVO vo = service.getProductDetail(1L);

        assertNotNull(vo);
        verify(service, times(1)).getById(1L); // 只回源一次
        verify(valueOps).set(eq("ecommerce:product:detail:1"), any(), anyLong(), eq(TimeUnit.SECONDS));
        verify(lock).unlock(); // 拿锁后必须在 finally 释放
        verify(rebuildLockMetrics).recordLeaderAcquired();
        verify(rebuildLockMetrics).recordDbLoad(anyLong()); // 回源耗时必须被记录
        verify(rebuildLockMetrics, never()).recordBudgetExhausted();
    }

    /**
     * 非 leader 路径：始终拿不到锁，等待预算耗尽后降级直接查 DB 返回，
     * 且从未持有锁，因此 unlock 不应被调用（避免 IllegalMonitorStateException / NPE）
     */
    @Test
    void nonLeaderDegradesWithoutUnlock() throws InterruptedException {
        when(valueOps.get(anyString())).thenReturn(null);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        stubLockAlwaysUnavailable();
        doReturnSample(2L);

        ProductVO vo = service.getProductDetail(2L);

        assertNotNull(vo);
        verify(service, atLeastOnce()).getById(2L); // 降级仍会查 DB
        verify(lock, never()).unlock();            // 没拿锁就不能 unlock
        verify(rebuildLockMetrics).recordBudgetExhausted();
        // 等待轮数取决于预算与片长（预算 60ms、起始片长 10ms），不写成固定值
        verify(rebuildLockMetrics, atLeastOnce()).recordWaitRound();
    }

    /**
     * 总等待时长硬约束：这是"用 deadline 替代重试次数"的核心契约。
     *
     * <p>非 leader 且锁一直拿不到时，整个等待必须</p>
     * <ul>
     *   <li><b>等满预算</b>——不能提前放弃，否则 leader 还没写完就降级，白丢合并效果；</li>
     *   <li><b>不超过预算</b>——不能像旧实现那样"次数 × 退避 + 次数 × RTT"地超出。</li>
     * </ul>
     * <p>下界正是旧实现漏掉的那半句：次数约束不了时间，预算才能。</p>
     */
    @Test
    void waitIsHardBoundedByBudget() throws InterruptedException {
        long budgetMillis = 200L;
        BusinessDynamicConfig config = new BusinessDynamicConfig();
        config.setProductDetailRebuildLockEnabled(true);
        config.setProductDetailRebuildLockLeaseSeconds(5);
        config.setProductDetailRebuildLockBudgetMillis(budgetMillis);
        config.setProductDetailRebuildLockBackoffMillis(10);
        rebuildService(config);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        stubLockAlwaysUnavailable();
        doReturnSample(9L);

        long start = System.nanoTime();
        ProductVO vo = service.getProductDetail(9L);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertNotNull(vo);
        assertTrue(elapsedMillis >= budgetMillis - 20,
                "等待应至少等满预算（否则会丢合并效果），实测 " + elapsedMillis + "ms");
        assertTrue(elapsedMillis < budgetMillis * 3,
                "等待必须被预算硬约束，实测 " + elapsedMillis + "ms，预算 " + budgetMillis + "ms");
    }

    /**
     * 拿锁后二次检查缓存：leader 拿到锁的瞬间，缓存已被别的节点写好，
     * 应直接读缓存返回、不再查 DB，并释放锁
     */
    @Test
    void leaderDoubleChecksCacheAfterAcquiringLock() throws InterruptedException {
        // 入口 miss -> 循环内 miss -> 拿锁后二次检查命中
        ProductVO cached = new ProductVO();
        cached.setId(3L);
        when(valueOps.get("ecommerce:product:detail:3"))
                .thenReturn(null).thenReturn(null).thenReturn(cached);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true); // 模拟当前线程持有锁，触发 safeUnlock

        ProductVO vo = service.getProductDetail(3L);

        assertNotNull(vo);
        verify(service, never()).getById(anyLong()); // 命中缓存，绝不再查 DB
        verify(lock).unlock();
    }

    /**
     * 降级并发闸门：上限已满时，超出的降级请求必须<b>快速失败</b>，而不是继续查 DB。
     *
     * <p>这是 P1 补上的最后一道防线。没有它，"预算耗尽"的瞬间所有 follower 会一起打 DB，
     * 恰恰是 singleflight 想避免的场景。</p>
     *
     * <p>构造方式：让第一个请求卡在 DB 查询里（持有一个降级许可），
     * 此时第二个请求预算耗尽、申请许可失败，必须以 {@code SYSTEM_BUSY} 快速失败。</p>
     */
    @Test
    void degradeIsRejectedWhenConcurrencyLimitReached() throws Exception {
        BusinessDynamicConfig config = new BusinessDynamicConfig();
        config.setProductDetailRebuildLockEnabled(true);
        config.setProductDetailRebuildLockLeaseSeconds(5);
        config.setProductDetailRebuildLockBudgetMillis(30);
        config.setProductDetailRebuildLockBackoffMillis(10);
        config.setProductDetailRebuildLockDegradeMaxConcurrency(1); // 只允许 1 个降级在途
        rebuildService(config);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        stubLockAlwaysUnavailable();

        CountDownLatch firstDegradeInDb = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstDegradeInDb.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            return sampleDb(101L);
        }).when(service).getById(101L);
        // 注意：102L 故意不打桩 getById——它必须被闸门拒绝、绝不触达 DB。
        // 若打桩而未被调用，Mockito 严格模式会直接判 UnnecessaryStubbingException。

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<ProductVO> holder = pool.submit(() -> service.getProductDetail(101L));
            assertTrue(firstDegradeInDb.await(3, TimeUnit.SECONDS), "第一个请求应已降级并进入 DB 查询");

            // 第二个请求：预算耗尽后想降级，但闸门已被占满
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.getProductDetail(102L));
            assertEquals(ErrorCode.SYSTEM_BUSY.getCode(), ex.getCode());
            verify(rebuildLockMetrics, times(1)).recordDegradeRejected();
            verify(service, never()).getById(102L); // 被拒绝就不该再打 DB

            releaseFirst.countDown();
            assertNotNull(holder.get(5, TimeUnit.SECONDS), "持锁降级的请求应能正常返回");
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 闸门上限配 0 = 不封顶：两个并发降级都应被放行（退化成旧行为，紧急开关）
     */
    @Test
    void degradeLimiterDoesNotBlockWhenLimitIsZero() throws Exception {
        BusinessDynamicConfig config = new BusinessDynamicConfig();
        config.setProductDetailRebuildLockEnabled(true);
        config.setProductDetailRebuildLockLeaseSeconds(5);
        config.setProductDetailRebuildLockBudgetMillis(30);
        config.setProductDetailRebuildLockBackoffMillis(10);
        config.setProductDetailRebuildLockDegradeMaxConcurrency(0); // 不封顶
        rebuildService(config);
        when(valueOps.get(anyString())).thenReturn(null);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        stubLockAlwaysUnavailable();

        CountDownLatch firstDegradeInDb = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstDegradeInDb.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            return sampleDb(201L);
        }).when(service).getById(201L);
        doReturn(sampleDb(202L)).when(service).getById(202L);

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<ProductVO> holder = pool.submit(() -> service.getProductDetail(201L));
            assertTrue(firstDegradeInDb.await(3, TimeUnit.SECONDS), "第一个请求应已降级并进入 DB 查询");

            // 不封顶时任凭第一个请求占着，第二个也应被放行
            ProductVO second = service.getProductDetail(202L);
            assertNotNull(second);
            verify(rebuildLockMetrics, never()).recordDegradeRejected();

            releaseFirst.countDown();
            assertNotNull(holder.get(5, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * 租约丢失（lease 偏短）：回源结束时锁已自动过期，
     * 必须计入指标且不再尝试 unlock（避免 IllegalMonitorStateException）。
     */
    @Test
    void leaseLostIsRecordedAndUnlockSkipped() throws InterruptedException {
        when(valueOps.get(anyString())).thenReturn(null);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        // 回源还没结束，锁已被自动释放：isHeldByCurrentThread 返回 false
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        doReturnSample(5L);

        ProductVO vo = service.getProductDetail(5L);

        assertNotNull(vo);
        verify(lock, never()).unlock();
        verify(rebuildLockMetrics).recordLeaseLost();
    }

    /**
     * 开关关闭：退化为普通"查DB + 回写"，行为与改造前一致，且不走任何锁逻辑
     */
    @Test
    void disabledSwitchDegradesToPlainReload() {
        BusinessDynamicConfig config = new BusinessDynamicConfig();
        config.setProductDetailRebuildLockEnabled(false);
        rebuildService(config);
        when(valueOps.get(anyString())).thenReturn(null);
        doReturnSample(4L);

        ProductVO vo = service.getProductDetail(4L);

        assertNotNull(vo);
        verify(service, times(1)).getById(4L);
        verify(redissonClient, never()).getLock(anyString()); // 开关关闭不应申请锁
    }

    private void doReturnSample(Long id) {
        doReturn(sampleDb(id)).when(service).getById(id);
    }

    /**
     * 模拟"锁被 leader 一直持有"：按传入的等待片长真睡，然后返回 false。
     * 必须真睡，否则等待循环的截止时间永远到不了（见类注释）。
     */
    private void stubLockAlwaysUnavailable() throws InterruptedException {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            long waitMillis = ((Number) invocation.getArgument(0)).longValue();
            if (waitMillis > 0) {
                Thread.sleep(waitMillis);
            }
            return false;
        });
    }
}
