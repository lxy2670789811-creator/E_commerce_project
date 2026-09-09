package com.ecommerce.service.impl;

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
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
 * 拿锁后二次检查命中缓存不再查 DB），并非「并发去重」本身——并发去重依赖真实 Redisson 分布式锁，
 * 需在 docker-compose 起 Redis 后做集成验证。</p>
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

    private ProductServiceImpl service;

    @BeforeEach
    void init() {
        BusinessDynamicConfig config = new BusinessDynamicConfig();
        config.setProductDetailRebuildLockEnabled(true);
        config.setProductDetailRebuildLockLeaseSeconds(10);
        config.setProductDetailRebuildLockMaxRetries(2);
        config.setProductDetailRebuildLockBackoffMillis(1);
        service = spy(new ProductServiceImpl(productMapper, redisTemplate, config, redissonClient));

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
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
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true); // 模拟当前线程持有锁，触发 safeUnlock
        doReturnSample(1L);

        ProductVO vo = service.getProductDetail(1L);

        assertNotNull(vo);
        verify(service, times(1)).getById(1L); // 只回源一次
        verify(valueOps).set(eq("ecommerce:product:detail:1"), any(), anyLong(), eq(TimeUnit.SECONDS));
        verify(lock).unlock(); // 拿锁后必须在 finally 释放
    }

    /**
     * 非 leader 路径：始终拿不到锁，重试耗尽后降级直接查 DB 返回，
     * 且从未持有锁，因此 unlock 不应被调用（避免 IllegalMonitorStateException / NPE）
     */
    @Test
    void nonLeaderDegradesWithoutUnlock() throws InterruptedException {
        when(valueOps.get(anyString())).thenReturn(null);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);
        doReturnSample(2L);

        ProductVO vo = service.getProductDetail(2L);

        assertNotNull(vo);
        verify(service, atLeastOnce()).getById(2L); // 降级仍会查 DB
        verify(lock, never()).unlock();            // 没拿锁就不能 unlock
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
        when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true); // 模拟当前线程持有锁，触发 safeUnlock

        ProductVO vo = service.getProductDetail(3L);

        assertNotNull(vo);
        verify(service, never()).getById(anyLong()); // 命中缓存，绝不再查 DB
        verify(lock).unlock();
    }

    /**
     * 开关关闭：退化为普通"查DB + 回写"，行为与改造前一致，且不走任何锁逻辑
     */
    @Test
    void disabledSwitchDegradesToPlainReload() throws InterruptedException {
        BusinessDynamicConfig config = new BusinessDynamicConfig();
        config.setProductDetailRebuildLockEnabled(false);
        service = spy(new ProductServiceImpl(productMapper, redisTemplate, config, redissonClient));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
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
}
