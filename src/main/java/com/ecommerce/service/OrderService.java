package com.ecommerce.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ecommerce.common.PageResult;
import com.ecommerce.dto.order.OrderCancelDTO;
import com.ecommerce.dto.order.OrderCreateDTO;
import com.ecommerce.entity.OrderDO;
import com.ecommerce.vo.order.OrderDetailVO;
import com.ecommerce.vo.order.OrderVO;

import java.util.List;

/**
 * 订单 Service 接口
 */
public interface OrderService extends IService<OrderDO> {

    /**
     * 创建订单（核心：Redisson分布式锁 + DB原子扣减 防超卖 + 一次性凭证 防重复提交）
     *
     * @return 订单号
     */
    String createOrder(OrderCreateDTO dto);

    /**
     * 生成下单一次性凭证（幂等用）
     *
     * <p>进入下单页时调用，提交订单时携带；凭证一次性，用后即焚。</p>
     *
     * @param userId    用户ID
     * @param productId 商品ID
     * @return 凭证字符串
     */
    String generateOrderToken(Long userId, Long productId);

    /**
     * 分页查询用户订单列表
     */
    PageResult<OrderVO> listOrders(Long userId, Integer status, long page, long pageSize);

    /**
     * 查询订单详情
     */
    OrderDetailVO getOrderDetail(Long orderId, Long userId);

    /**
     * 取消订单
     */
    void cancelOrder(OrderCancelDTO dto);

    /**
     * 超时未支付自动关单（RocketMQ 延迟消息消费端调用，幂等）
     */
    void autoCancelOrder(Long orderId);

    /**
     * 模拟支付回调：待支付 -> 已支付（幂等）
     */
    void payCallback(String orderNo);

    /**
     * 发货（管理端）：已支付 -> 已发货
     */
    void shipOrder(Long orderId);

    /**
     * 完成订单（管理端）：已发货 -> 已完成
     */
    void finishOrder(Long orderId);

    /**
     * 根据订单ID查询订单（供AI售后模块使用）
     */
    OrderDO getOrderByIdForAi(Long orderId);
}