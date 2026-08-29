package com.ecommerce.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单状态枚举
 */
@Getter
@AllArgsConstructor
public enum OrderStatusEnum {

    PENDING_PAYMENT(0, "待支付"),
    PAID(1, "已支付"),
    SHIPPED(2, "已发货"),
    COMPLETED(3, "已完成"),
    CANCELED(4, "已取消");

    private final int code;
    private final String text;

    public static String getTextByCode(Integer code) {
        if (code == null) {
            return "未知";
        }
        for (OrderStatusEnum e : values()) {
            if (e.code == code) {
                return e.text;
            }
        }
        return "未知";
    }

    /**
     * 是否允许取消：只有待支付和已支付可取消
     */
    public static boolean canCancel(Integer code) {
        return code != null && (code == PENDING_PAYMENT.code || code == PAID.code);
    }

    /**
     * 校验状态机是否允许从 from 流转到 to
     * 合法流转：
     *   待支付 -> 已支付 / 已取消
     *   已支付 -> 已发货 / 已取消
     *   已发货 -> 已完成
     */
    public static boolean canTransit(Integer from, Integer to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == PENDING_PAYMENT.code) {
            return to == PAID.code || to == CANCELED.code;
        }
        if (from == PAID.code) {
            return to == SHIPPED.code || to == CANCELED.code;
        }
        if (from == SHIPPED.code) {
            return to == COMPLETED.code;
        }
        return false;
    }
}
