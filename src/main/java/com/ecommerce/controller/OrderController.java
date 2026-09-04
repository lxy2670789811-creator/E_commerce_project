package com.ecommerce.controller;

import com.ecommerce.common.PageResult;
import com.ecommerce.common.Result;
import com.ecommerce.dto.order.OrderCancelDTO;
import com.ecommerce.dto.order.OrderCreateDTO;
import com.ecommerce.dto.order.OrderPayCallbackDTO;
import com.ecommerce.dto.order.OrderStatusOpDTO;
import com.ecommerce.service.OrderService;
import com.ecommerce.vo.order.OrderDetailVO;
import com.ecommerce.vo.order.OrderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 订单模块 Controller
 */
@Tag(name = "订单模块", description = "创建订单、订单列表、订单详情、取消订单、支付回调、发货/完成、超时自动关单")
@Validated
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "创建订单", description = "下单流程：一次性凭证防重复提交 + Redisson按商品ID加锁 + DB原子扣减防超卖；"
            + "下单成功后发送 RocketMQ 延迟消息用于超时自动关单")
    @PostMapping("/create")
    public Result<String> createOrder(@RequestBody @Valid OrderCreateDTO dto) {
        String orderNo = orderService.createOrder(dto);
        return Result.success(orderNo);
    }

    @Operation(summary = "获取下单凭证", description = "进入下单页时调用，返回一次性凭证（幂等用）。"
            + "提交订单时在 body 中携带该凭证，服务端校验后即销毁；同一凭证第二次提交会被拒绝，"
            + "从而防止双击、前端超时重试、网络重发导致的重复下单与重复扣库存")
    @GetMapping("/token")
    public Result<String> generateOrderToken(
            @Parameter(description = "用户ID", required = true, example = "1")
            @RequestParam @NotNull(message = "用户ID不能为空") Long userId,
            @Parameter(description = "商品ID", required = true, example = "1")
            @RequestParam @NotNull(message = "商品ID不能为空") Long productId) {
        return Result.success(orderService.generateOrderToken(userId, productId));
    }

    @Operation(summary = "订单列表", description = "分页查询指定用户的订单列表，可按订单状态筛选")
    @GetMapping("/list")
    public Result<PageResult<OrderVO>> listOrders(
            @Parameter(description = "用户ID", required = true, example = "1")
            @RequestParam @NotNull(message = "用户ID不能为空") Long userId,
            @Parameter(description = "订单状态：0-待支付 1-已支付 2-已发货 3-已完成 4-已取消")
            @RequestParam(required = false) Integer status,
            @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") long page,
            @Parameter(description = "每页条数，最大100") @RequestParam(defaultValue = "10") long pageSize) {
        PageResult<OrderVO> result = orderService.listOrders(userId, status, page, pageSize);
        return Result.success(result);
    }

    @Operation(summary = "订单详情", description = "根据订单ID + 用户ID查询订单完整详情")
    @GetMapping("/detail")
    public Result<OrderDetailVO> getOrderDetail(
            @Parameter(description = "订单ID", required = true, example = "1")
            @RequestParam @NotNull(message = "订单ID不能为空") Long orderId,
            @Parameter(description = "用户ID", required = true, example = "1")
            @RequestParam @NotNull(message = "用户ID不能为空") Long userId) {
        OrderDetailVO vo = orderService.getOrderDetail(orderId, userId);
        return Result.success(vo);
    }

    @Operation(summary = "取消订单", description = "取消待支付/已支付订单，自动回滚库存（同样加分布式锁）")
    @PostMapping("/cancel")
    public Result<Void> cancelOrder(@RequestBody @Valid OrderCancelDTO dto) {
        orderService.cancelOrder(dto);
        return Result.success();
    }

    @Operation(summary = "模拟支付回调", description = "模拟第三方支付回调：待支付 -> 已支付（幂等，重复回调不报错）")
    @PostMapping("/pay-callback")
    public Result<Void> payCallback(@RequestBody @Valid OrderPayCallbackDTO dto) {
        orderService.payCallback(dto.getOrderNo());
        return Result.success();
    }

    @Operation(summary = "发货（管理端）", description = "已支付 -> 已发货")
    @PostMapping("/ship")
    public Result<Void> shipOrder(@RequestBody @Valid OrderStatusOpDTO dto) {
        orderService.shipOrder(dto.getOrderId());
        return Result.success();
    }

    @Operation(summary = "完成订单（管理端）", description = "已发货 -> 已完成")
    @PostMapping("/finish")
    public Result<Void> finishOrder(@RequestBody @Valid OrderStatusOpDTO dto) {
        orderService.finishOrder(dto.getOrderId());
        return Result.success();
    }
}