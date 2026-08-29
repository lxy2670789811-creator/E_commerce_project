package com.ecommerce.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusEnumTest {

    @Test
    void canCancelOnlyPendingAndPaid() {
        assertThat(OrderStatusEnum.canCancel(OrderStatusEnum.PENDING_PAYMENT.getCode())).isTrue();
        assertThat(OrderStatusEnum.canCancel(OrderStatusEnum.PAID.getCode())).isTrue();
        assertThat(OrderStatusEnum.canCancel(OrderStatusEnum.SHIPPED.getCode())).isFalse();
        assertThat(OrderStatusEnum.canCancel(OrderStatusEnum.COMPLETED.getCode())).isFalse();
        assertThat(OrderStatusEnum.canCancel(OrderStatusEnum.CANCELED.getCode())).isFalse();
        assertThat(OrderStatusEnum.canCancel(null)).isFalse();
    }

    @Test
    void textLookupWorks() {
        assertThat(OrderStatusEnum.getTextByCode(OrderStatusEnum.PENDING_PAYMENT.getCode())).isEqualTo("待支付");
        assertThat(OrderStatusEnum.getTextByCode(OrderStatusEnum.PAID.getCode())).isEqualTo("已支付");
        assertThat(OrderStatusEnum.getTextByCode(999)).isEqualTo("未知");
        assertThat(OrderStatusEnum.getTextByCode(null)).isEqualTo("未知");
    }

    @Test
    void canTransitFollowsStateMachine() {
        // 待支付 -> 已支付 / 已取消
        assertThat(OrderStatusEnum.canTransit(
                OrderStatusEnum.PENDING_PAYMENT.getCode(), OrderStatusEnum.PAID.getCode())).isTrue();
        assertThat(OrderStatusEnum.canTransit(
                OrderStatusEnum.PENDING_PAYMENT.getCode(), OrderStatusEnum.CANCELED.getCode())).isTrue();
        // 已支付 -> 已发货 / 已取消
        assertThat(OrderStatusEnum.canTransit(
                OrderStatusEnum.PAID.getCode(), OrderStatusEnum.SHIPPED.getCode())).isTrue();
        assertThat(OrderStatusEnum.canTransit(
                OrderStatusEnum.PAID.getCode(), OrderStatusEnum.CANCELED.getCode())).isTrue();
        // 已发货 -> 已完成
        assertThat(OrderStatusEnum.canTransit(
                OrderStatusEnum.SHIPPED.getCode(), OrderStatusEnum.COMPLETED.getCode())).isTrue();
        // 非法流转
        assertThat(OrderStatusEnum.canTransit(
                OrderStatusEnum.PENDING_PAYMENT.getCode(), OrderStatusEnum.SHIPPED.getCode())).isFalse();
        assertThat(OrderStatusEnum.canTransit(
                OrderStatusEnum.COMPLETED.getCode(), OrderStatusEnum.CANCELED.getCode())).isFalse();
        assertThat(OrderStatusEnum.canTransit(
                OrderStatusEnum.PAID.getCode(), OrderStatusEnum.PENDING_PAYMENT.getCode())).isFalse();
        assertThat(OrderStatusEnum.canTransit(null, OrderStatusEnum.PAID.getCode())).isFalse();
        assertThat(OrderStatusEnum.canTransit(OrderStatusEnum.PAID.getCode(), null)).isFalse();
    }
}