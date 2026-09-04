package com.ecommerce.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单数据库实体（DO）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("orders")
public class OrderDO extends BaseDO {

    private static final long serialVersionUID = 1L;

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 商品ID
     */
    private Long productId;

    /**
     * 商品名称（下单时快照）
     */
    private String productName;

    /**
     * 商品单价（下单时快照）
     */
    private BigDecimal productPrice;

    /**
     * 购买数量
     */
    private Integer quantity;

    /**
     * 订单总金额
     */
    private BigDecimal totalAmount;

    /**
     * 收货地址ID
     */
    private Long addressId;

    /**
     * 收货地址快照
     */
    private String addressSnapshot;

    /**
     * 订单状态：0-待支付 1-已支付 2-已发货 3-已完成 4-已取消
     */
    private Integer status;

    /**
     * 支付时间
     */
    private LocalDateTime payTime;

    /**
     * 发货时间
     */
    private LocalDateTime shipTime;

    /**
     * 完成时间
     */
    private LocalDateTime finishTime;

    /**
     * 取消时间
     */
    private LocalDateTime cancelTime;

    /**
     * 取消原因
     */
    private String cancelReason;

    /**
     * 下单幂等凭证（一次性 token）
     *
     * <p>数据库兜底层：与 Redis 凭证校验互为独立的两层防护。
     * 凭证层（Redis + Lua）防"重复提交"；本字段的唯一索引防"凭证层失效时仍被重复下单"
     * （如 Redis 故障降级放行、或请求绕过凭证直接调接口）。</p>
     *
     * <p>为什么唯一索引建在凭证上，而不是 (user_id, product_id)？
     * 后者等价于"同一用户同一商品同时只能有一笔待支付订单"，会误伤合法场景
     * （用户确实想分两单买同一件商品）。凭证唯一则精确对应"一次提交意图"，
     * 不引入任何业务假设——这也是 Stripe 等支付网关的标准做法。</p>
     *
     * <p>MySQL 唯一索引允许多行 NULL，因此关闭凭证功能（token 为 null）时不会互相冲突。</p>
     */
    private String idempotencyToken;
}
