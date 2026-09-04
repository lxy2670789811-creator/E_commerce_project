package com.ecommerce.mq;

import com.ecommerce.config.BusinessDynamicConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 2. 发送失败仅记录日志，不影响下单主流程（业务降级）；
 * 3. 延迟级别从 Nacos 动态配置读取（BusinessDynamicConfig）。
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
     * 乐观初始为成功：RocketMQ 正常（常态）时保持成功，定时扫描兜底据此跳过查询，实现"MQ 健康时零轮询"；
     * 一旦某次发送抛异常（broker 宕机/网络异常），置为失败，定时扫描兜底感知到后接管补偿。
     * volatile 保证调度线程与下单线程间的可见性。
     */
    private volatile boolean lastSendSucceeded = true;

    /**
     * 发送"超时未支付自动关单"延迟消息
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
        try {
            Message<OrderTimeoutMessage> mqMessage = MessageBuilder.withPayload(message).build();
            rocketMQTemplate.syncSend(TOPIC, mqMessage, SEND_TIMEOUT_MS, delayLevel);
            // 发送成功 → 标记 MQ 通道健康，供定时扫描兜底据此跳过（常态零轮询）
            lastSendSucceeded = true;
            log.info("已发送超时关单延迟消息：orderId={}, orderNo={}, delayLevel={}",
                    message.getOrderId(), message.getOrderNo(), delayLevel);
        } catch (Exception e) {
            // MQ 故障不影响下单主流程，但要让定时扫描兜底感知到 MQ 不可用，由它接管补偿
            lastSendSucceeded = false;
            log.error("发送超时关单延迟消息失败（定时扫描兜底将接管补偿）：orderId={}, orderNo={}",
                    message.getOrderId(), message.getOrderNo(), e);
        }
    }

    /**
     * 判断 RocketMQ 超时关单通道当前是否可用。
     * <ul>
     *   <li>模板未装配（如测试环境排除自动装配）→ 视为不可用，由定时扫描兜底补偿；</li>
     *   <li>模板已装配但最近一次发送失败（broker 宕机/网络异常）→ 视为不可用；</li>
     *   <li>模板已装配且最近发送成功（常态）→ 视为可用，定时扫描应跳过，避免无谓的周期查询。</li>
     * </ul>
     * 供 {@code OrderTimeoutScanScheduler} 做"MQ 健康门控"：仅当本方法返回 false 时才真正执行扫描。
     */
    public boolean isRocketMqUsable() {
        RocketMQTemplate rocketMQTemplate = rocketMQTemplateProvider.getIfAvailable();
        return rocketMQTemplate != null && lastSendSucceeded;
    }
}