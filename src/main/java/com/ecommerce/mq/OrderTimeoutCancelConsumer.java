package com.ecommerce.mq;

import com.ecommerce.config.BusinessDynamicConfig;
import com.ecommerce.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 订单超时未支付自动关单 - 延迟消息消费者
 *
 * 收到延迟消息后：
 * 1. 校验功能开关（可动态关闭）；
 * 2. 调用 OrderService.autoCancelOrder 自动关单（幂等：仅待支付订单会真正取消，库存回滚）。
 *
 * 说明：RocketMQ 默认 at-least-once 投递，autoCancelOrder 做了幂等处理，重复消息不会重复回滚库存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
// 可通过 ecommerce.order.timeout-cancel.consumer-enabled=false 关闭（如本地不想启动 RocketMQ）
@ConditionalOnProperty(name = "ecommerce.order.timeout-cancel.consumer-enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = OrderTimeoutCancelSender.TOPIC,
        consumerGroup = "ecommerce-order-timeout-group",
        // starter 2.3.1 不会从 RocketMQProperties 自动读取 name-server，需显式指定（支持占位符）
        nameServer = "${rocketmq.name-server:127.0.0.1:9876}"
)
public class OrderTimeoutCancelConsumer implements RocketMQListener<OrderTimeoutMessage> {

    private final OrderService orderService;
    private final BusinessDynamicConfig businessDynamicConfig;

    @Override
    public void onMessage(OrderTimeoutMessage message) {
        if (message == null || message.getOrderId() == null) {
            log.warn("超时关单消息为空或缺少订单ID，忽略");
            return;
        }
        if (!businessDynamicConfig.isOrderTimeoutCancelEnabled()) {
            log.info("超时关单功能已关闭，忽略延迟消息：orderId={}", message.getOrderId());
            return;
        }
        try {
            orderService.autoCancelOrder(message.getOrderId());
        } catch (Exception e) {
            log.error("超时关单处理失败：orderId={}, orderNo={}", message.getOrderId(), message.getOrderNo(), e);
        }
    }
}