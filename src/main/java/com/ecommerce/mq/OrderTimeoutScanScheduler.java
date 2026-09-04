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

import java.time.LocalDateTime;
import java.util.List;

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
 */
@Slf4j
@Component
@RequiredArgsConstructor
// Bean 常驻注册；是否真正执行由 order-timeout-cancel-enabled（总开关）、
// order-timeout-scan-enabled（兜底任务专属开关）与 MQ 健康门控在任务内运行时判断。
public class OrderTimeoutScanScheduler {

    private final OrderService orderService;
    private final BusinessDynamicConfig businessDynamicConfig;
    private final OrderTimeoutCancelSender orderTimeoutCancelSender;

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
        log.info("RocketMQ 超时关单通道不可用，定时扫描接管超时订单补偿");

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
        log.info("定时扫描命中 {} 笔超时未支付订单，开始补偿关单", expiredOrders.size());

        int success = 0;
        for (OrderDO order : expiredOrders) {
            try {
                // autoCancelOrder 幂等 + 自带事务；单条失败不影响其余
                orderService.autoCancelOrder(order.getId());
                success++;
            } catch (Exception e) {
                log.error("定时扫描补偿关单失败：orderId={}, orderNo={}",
                        order.getId(), order.getOrderNo(), e);
            }
        }
        log.info("定时扫描补偿关单完成：成功 {} / {} 笔", success, expiredOrders.size());
    }
}
