package com.ecommerce.mq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ecommerce.config.BusinessDynamicConfig;
import com.ecommerce.entity.OrderDO;
import com.ecommerce.service.OrderService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时扫描任务的多副本选主测试（云原生 L0）
 *
 * <p>验证目标：{@code @Scheduled} 任务在多副本部署时，一轮只由一个副本真正执行。
 * 这是把应用放进 K8s 前必须锁死的行为 —— 否则扩到 N 副本，同一批超时订单会被扫 N 遍。
 *
 * <p>为什么测 {@code reconcileExpiredPendingOrders}（粗粒度对账）而不是细粒度扫描：
 * 粗粒度对账不受 MQ 健康门控影响，只要开关打开就一定会走到抢锁逻辑，断言更直接；
 * 细粒度那条要额外 stub {@code isRocketMqUsable()}，测的是同一段选主代码，没必要重复覆盖。
 */
@ExtendWith(MockitoExtension.class)
class OrderTimeoutScanSchedulerLeaderElectionTest {

    /** 必须与 OrderTimeoutScanScheduler 中的常量保持一致 */
    private static final String SCAN_LOCK_KEY = "ecommerce:lock:scheduler:order-timeout-scan";
    private static final long SCAN_LOCK_LEASE_SECONDS = 60L;

    @Mock
    private OrderService orderService;
    @Mock
    private BusinessDynamicConfig businessDynamicConfig;
    @Mock
    private OrderTimeoutCancelSender orderTimeoutCancelSender;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private OrderTimeoutScanScheduler scheduler;

    /**
     * 纯 Mockito 环境（无 Spring 上下文）下必须手动初始化 MyBatis-Plus 的实体元数据。
     *
     * <p>{@code doScan} 里构造 {@code LambdaQueryWrapper<OrderDO>} 时会用到 {@code OrderDO::getId}
     * 这类方法引用，MyBatis-Plus 需要先用 {@code TableInfoHelper} 把实体解析进 lambda 缓存；
     * 正常由 Spring 启动时扫描完成，单测里没有这一步，会直接抛
     * {@code MybatisPlusException: can not find lambda cache for this entity}。
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), OrderDO.class);
    }

    @BeforeEach
    void setUp() {
        scheduler = new OrderTimeoutScanScheduler(
                orderService, businessDynamicConfig, orderTimeoutCancelSender, redissonClient);
        // 两个开关在抢锁**之前**判断，因此这三个桩在下面每个用例里都会被用到
        // （Mockito 严格模式下，打了桩却没人调用会抛 UnnecessaryStubbingException）
        when(businessDynamicConfig.isOrderTimeoutCancelEnabled()).thenReturn(true);
        when(businessDynamicConfig.isOrderTimeoutScanCoarseEnabled()).thenReturn(true);
        when(redissonClient.getLock(SCAN_LOCK_KEY)).thenReturn(lock);
    }

    @Test
    void acquiresLock_scansAndReleases() throws InterruptedException {
        when(lock.tryLock(0, SCAN_LOCK_LEASE_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(businessDynamicConfig.getOrderTimeoutSeconds()).thenReturn(300L);
        when(businessDynamicConfig.getOrderTimeoutScanBatchSize()).thenReturn(100);
        when(orderService.list(ArgumentMatchers.<LambdaQueryWrapper<OrderDO>>any()))
                .thenReturn(List.of(expiredOrder(1001L, "NO1001")));

        scheduler.reconcileExpiredPendingOrders();

        verify(orderService).autoCancelOrder(1001L);
        verify(lock).unlock();
    }

    @Test
    void losesElection_skipsScanWithoutQueryingDb() throws InterruptedException {
        when(lock.tryLock(0, SCAN_LOCK_LEASE_SECONDS, TimeUnit.SECONDS)).thenReturn(false);

        scheduler.reconcileExpiredPendingOrders();

        // 关键断言：非 leader 连 DB 查询都不该发生 —— 这正是"多副本不再重复扫描"的直接证据。
        // 若只断言 autoCancelOrder 没调用，那么"每个副本都查了一遍库、只是恰好没有超时订单"也会通过。
        verify(orderService, never()).list(ArgumentMatchers.<LambdaQueryWrapper<OrderDO>>any());
        verify(orderService, never()).autoCancelOrder(ArgumentMatchers.anyLong());
        verify(lock, never()).unlock();
    }

    @Test
    void leaseExpired_skipsUnlockWithoutThrowing() throws InterruptedException {
        when(lock.tryLock(0, SCAN_LOCK_LEASE_SECONDS, TimeUnit.SECONDS)).thenReturn(true);
        // 模拟单轮扫描耗时超过 lease：锁已被 Redis 自动释放，当前线程不再持有
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        when(businessDynamicConfig.getOrderTimeoutSeconds()).thenReturn(300L);
        when(businessDynamicConfig.getOrderTimeoutScanBatchSize()).thenReturn(100);
        when(orderService.list(ArgumentMatchers.<LambdaQueryWrapper<OrderDO>>any()))
                .thenReturn(List.of(expiredOrder(1002L, "NO1002")));

        scheduler.reconcileExpiredPendingOrders();

        // 此时 unlock() 会抛 IllegalMonitorStateException，必须跳过；业务本身已完成
        verify(lock, never()).unlock();
        verify(orderService).autoCancelOrder(1002L);
    }

    @Test
    void interrupted_abandonsCurrentRound() throws InterruptedException {
        when(lock.tryLock(0, SCAN_LOCK_LEASE_SECONDS, TimeUnit.SECONDS))
                .thenThrow(new InterruptedException());

        scheduler.reconcileExpiredPendingOrders();

        verify(orderService, never()).autoCancelOrder(ArgumentMatchers.anyLong());
        // 代码里调用了 Thread.currentThread().interrupt() 复位中断标志，
        // 这里清掉，避免影响同 JVM 内后续用例（JUnit 默认多线程时尤其重要）
        Thread.interrupted();
    }

    private static OrderDO expiredOrder(Long id, String orderNo) {
        OrderDO order = new OrderDO();
        order.setId(id);
        order.setOrderNo(orderNo);
        return order;
    }
}
