package com.ecommerce.mq;

import com.ecommerce.config.BusinessDynamicConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 订单超时未支付自动关单 - 延迟消息生产者
 *
 * 设计要点：
 * 1. 通过 ObjectProvider 注入 RocketMQTemplate：RocketMQ 未启用（如测试环境排除自动装配）时优雅跳过；
 * 2. 采用异步发送（asyncSend + SendCallback）：下单请求线程与同商品库存锁仅等待"入队"极短时间即释放，
 *    broker 往返与应答在客户端内部线程完成，提升吞吐、降低同款商品并发争用；
 * 3. 发送失败仅记录日志并标记通道不可用，不影响下单主流程（业务降级）；
 * 4. 延迟级别从 Nacos 动态配置读取（BusinessDynamicConfig）；
 * 5. 可靠性由"延迟消息 + 细粒度扫描（门控于 MQ 健康）+ 粗粒度独立对账扫描"三重保障，
 *    即使异步发送在回调到达前崩溃导致健康标志未翻转，粗粒度对账仍会兜底关单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutCancelSender {

    /** 超时关单延迟消息 Topic */
    public static final String TOPIC = "ecommerce-order-timeout-topic";

    private static final long SEND_TIMEOUT_MS = 3000L;

    private final ObjectProvider<RocketMQTemplate> rocketMQTemplateProvider;
    private final BusinessDynamicConfig businessDynamicConfig;

    /**
     * 记录"最近一次延迟消息发送是否成功"。
     * 乐观初始为成功：RocketMQ 正常（常态）时保持成功，细粒度定时扫描据此跳过查询，实现"MQ 健康时零轮询"；
     * 异步发送下，成功/失败由 SendCallback（客户端内部线程）异步回填：onSuccess 置 true、onException 置 false，
     * 仅当 asyncSend 同步抛异常（producer 未启动/线程池耗尽）时由当前线程直接置 false。
     * volatile 保证调度线程与发送/回调线程间的可见性。
     * 注意：此标志仅用于细粒度扫描的"MQ 健康门控"；极端崩溃窗口下可能未及时翻转，
     * 故另设不受此标志约束的粗粒度独立对账扫描作为最终兜底。
     */
    private volatile boolean lastSendSucceeded = true;

    /**
     * 发送"超时未支付自动关单"延迟消息（异步发送 + SendCallback 回调）
     *
     * 为何改为异步：发送在事务 afterCommit 中执行，同步发送会在 broker 往返期间占住
     * 请求线程与（同商品的）库存分布式锁；改为 asyncSend 后请求线程与锁仅多等待"入队"的
     * 极短时间即释放，broker 往返与应答放到客户端内部线程，提升吞吐、降低同款商品并发争用。
     *
     * 可靠性说明：异步发送的结果（成功/失败）在 SendCallback 中异步回填 lastSendSucceeded，
     * 供细粒度扫描做 MQ 健康门控；但"异步发送在回调到达前 JVM 崩溃"等极端窗口下健康标志可能
     * 未及时翻转，因此另设独立粗粒度对账扫描（不受 MQ 健康标志约束）兜底，保证超时订单终被关单。
     */
    public void sendDelayCancel(OrderTimeoutMessage message) {
        RocketMQTemplate rocketMQTemplate = rocketMQTemplateProvider.getIfAvailable();
        if (rocketMQTemplate == null) {
            log.debug("RocketMQ 未启用，跳过超时关单延迟消息：orderId={}", message.getOrderId());
            return;
        }
        if (!businessDynamicConfig.isOrderTimeoutCancelEnabled()) {
            log.debug("超时关单功能已关闭，跳过延迟消息：orderId={}", message.getOrderId());
            return;
        }
        int delayLevel = businessDynamicConfig.getOrderTimeoutCancelDelayLevel();
        Message<OrderTimeoutMessage> mqMessage = MessageBuilder.withPayload(message).build();
        try {
            // 异步发送：入队即返回，broker 往返与应答由客户端内部线程处理，不阻塞请求线程与库存锁
            rocketMQTemplate.asyncSend(TOPIC, mqMessage, new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    // 发送成功 → 标记 MQ 通道健康，供细粒度扫描据此跳过（常态零轮询）
                    lastSendSucceeded = true;
                    log.info("已异步发送超时关单延迟消息：orderId={}, orderNo={}, delayLevel={}",
                            message.getOrderId(), message.getOrderNo(), delayLevel);
                }

                @Override
                public void onException(Throwable e) {
                    // MQ 故障不影响下单主流程；标记通道不可用，细粒度扫描将接管补偿
                    lastSendSucceeded = false;
                    log.error("异步发送超时关单延迟消息失败（定时扫描兜底将接管补偿）：orderId={}, orderNo={}",
                            message.getOrderId(), message.getOrderNo(), e);
                }
            }, SEND_TIMEOUT_MS, delayLevel);
        } catch (Exception e) {
            // asyncSend 同步抛异常（如 producer 未启动 / 异步发送线程池耗尽）：
            // 同样视为通道不可用，交由扫描兜底
            lastSendSucceeded = false;
            log.error("异步发送超时关单延迟消息失败（定时扫描兜底将接管补偿）：orderId={}, orderNo={}",
                    message.getOrderId(), message.getOrderNo(), e);
        }
    }

    /**
     * 判断 RocketMQ 超时关单通道当前是否可用（用于细粒度扫描的"MQ 健康门控"）。
     * <ul>
     *   <li>模板未装配（如测试环境排除自动装配）→ 视为不可用，由定时扫描兜底补偿；</li>
     *   <li>模板已装配但最近一次发送失败（broker 宕机/网络异常）→ 视为不可用；</li>
     *   <li>模板已装配且最近发送成功（常态）→ 视为可用，细粒度扫描应跳过，避免无谓的周期查询。</li>
     * </ul>
     * 仅约束<b>细粒度</b>扫描（高频、常态零轮询）；<b>粗粒度独立对账扫描不受此标志约束</b>，
     * 会固定周期强制扫描，以兜底"异步发送在回调到达前崩溃、健康标志未翻转"等极端窗口。
     */
    public boolean isRocketMqUsable() {
        RocketMQTemplate rocketMQTemplate = rocketMQTemplateProvider.getIfAvailable();
        return rocketMQTemplate != null && lastSendSucceeded;
    }
}