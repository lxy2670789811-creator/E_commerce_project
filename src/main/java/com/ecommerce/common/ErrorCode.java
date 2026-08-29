package com.ecommerce.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局错误码枚举
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "操作成功"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),

    // 业务错误 1000-1999
    PRODUCT_NOT_FOUND(1001, "商品不存在"),
    PRODUCT_OFF_SHELF(1002, "商品已下架"),
    PRODUCT_STOCK_INSUFFICIENT(1003, "商品库存不足"),
    PRODUCT_NOT_EXIST(1004, "商品ID不存在"),

    ORDER_NOT_FOUND(2001, "订单不存在"),
    ORDER_STATUS_ERROR(2002, "订单状态不允许此操作"),
    ORDER_CANCEL_FAILED(2003, "订单取消失败"),
    ORDER_CREATE_FAILED(2004, "订单创建失败"),

    USER_NOT_FOUND(3001, "用户不存在"),
    USER_LOGIN_FAILED(3002, "用户名或密码错误"),
    ADDRESS_NOT_FOUND(3003, "收货地址不存在"),

    // 系统错误 5000-5999
    SYSTEM_ERROR(5000, "系统异常"),
    SYSTEM_BUSY(5001, "系统繁忙，请稍后再试"),
    CACHE_ERROR(5002, "缓存操作异常"),
    LOCK_ACQUIRE_FAILED(5003, "获取分布式锁失败，请稍后重试"),
    AI_RATE_LIMIT_EXCEEDED(5101, "AI服务调用过于频繁，请稍后再试"),
    AI_CALL_FAILED(5102, "AI服务调用失败，已转人工审核"),
    AFTER_SUPPORT_DISABLED(5103, "售后AI分析功能暂不可用");

    private final int code;
    private final String message;
}
