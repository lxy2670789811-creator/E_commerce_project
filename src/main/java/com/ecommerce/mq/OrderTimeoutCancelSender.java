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
            log.info("已发送超时关单延迟消息：orderId={}, orderNo={}, delayLevel={}",
                    message.getOrderId(), message.getOrderNo(), delayLevel);
        } catch (Exception e) {
            // MQ 故障不影响下单主流程
            log.error("发送超时关单延迟消息失败（不影响下单主流程）：orderId={}, orderNo={}",
                    message.getOrderId(), message.getOrderNo(), e);
        }
    }
}