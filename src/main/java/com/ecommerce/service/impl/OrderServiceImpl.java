package com.ecommerce.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.common.BusinessException;
import com.ecommerce.common.ErrorCode;
import com.ecommerce.common.OrderNoGenerator;
import com.ecommerce.common.OrderTokenService;
import com.ecommerce.common.PageResult;
import com.ecommerce.config.BusinessDynamicConfig;
import com.ecommerce.convert.OrderConvert;
import com.ecommerce.dto.order.OrderCancelDTO;
import com.ecommerce.dto.order.OrderCreateDTO;
import com.ecommerce.entity.OrderDO;
import com.ecommerce.entity.ProductDO;
import com.ecommerce.entity.UserAddressDO;
import com.ecommerce.entity.UserDO;
import com.ecommerce.enums.OrderStatusEnum;
import com.ecommerce.mapper.OrderMapper;
import com.ecommerce.mq.OrderTimeoutCancelSender;
import com.ecommerce.mq.OrderTimeoutMessage;
import com.ecommerce.service.OrderService;
import com.ecommerce.service.ProductService;
import com.ecommerce.service.UserAddressService;
import com.ecommerce.service.UserService;
import com.ecommerce.vo.order.OrderDetailVO;
import com.ecommerce.vo.order.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Objects;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 订单 Service 实现类（核心：Redisson 分布式锁防超卖 + Sentinel QPS限流）
 *
 * 并发保护策略（三层递进）：
 *   1. Sentinel 网关层 QPS 限流（createOrder / cancelOrder）→ 入口快速失败
 *   2. Redisson 分布式锁（按商品ID加锁）→ 同一商品串行化
 *   3. SQL 原子扣减（stock >= quantity 乐观检查）→ DB 层最终保险
 *
 * 缓存一致性策略：
 *   - 库存变更（扣减/回滚）→ 主动删除商品详情缓存（Cache-Aside 模式）
 *   - 为什么选"删缓存"而非"更新缓存"？
 *     因为库存写入频率远低于读取频率，更新缓存可能引入不一致窗口；
 *     删除后下次读取时自动回源数据库重建缓存，保证最终一致性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, OrderDO> implements OrderService {

    private static final String STOCK_LOCK_KEY_PREFIX = "ecommerce:lock:stock:product:";

    private final OrderMapper orderMapper;
    private final ProductService productService;
    private final UserService userService;
    private final UserAddressService userAddressService;
    private final RedissonClient redissonClient;
    /** 注入动态配置：库存锁超时时间可从 Nacos 动态刷新 */
    private final BusinessDynamicConfig businessDynamicConfig;
    /** 订单号生成器（ORD + 秒级时间戳 + 完整雪花ID，保证全局唯一） */
    private final OrderNoGenerator orderNoGenerator;
    /** 下单一次性凭证服务（幂等：防重复提交，Redis + Lua 原子消耗） */
    private final OrderTokenService orderTokenService;
    /** 超时关单延迟消息发送器（MQ 不可用时降级，不影响下单） */
    private final OrderTimeoutCancelSender orderTimeoutCancelSender;

    @Override
    @Transactional(rollbackFor = Exception.class)
    @SentinelResource(
            value = "createOrder",
            blockHandler = "createOrderBlockHandler",
            fallback = "createOrderFallback"
    )
    public String createOrder(OrderCreateDTO dto) {
        // 1. 基础校验：用户、地址存在性（在锁外，减少锁内逻辑）
        UserDO user = userService.getUserById(dto.getUserId());
        UserAddressDO address = userAddressService.getByIdAndUserId(dto.getAddressId(), dto.getUserId());
        ProductDO product = productService.getById(dto.getProductId());
        if (product == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        if (product.getStatus() == null || product.getStatus() != 1) {
            throw new BusinessException(ErrorCode.PRODUCT_OFF_SHELF);
        }

        // 1.1 幂等第一层：校验并消耗一次性凭证（Redis + Lua 原子 GETDEL）
        //     放在锁外是安全的——Lua 的原子性保证并发下只有一个请求能消耗成功，
        //     同时避免在持锁期间额外增加网络往返，缩短锁持有时间。
        //     凭证无效/已被使用 → 判定为重复提交，直接拒绝。
        if (!orderTokenService.consume(dto.getToken(), dto.getUserId(), dto.getProductId())) {
            throw new BusinessException(ErrorCode.ORDER_TOKEN_INVALID);
        }

        // 2. 按【单个商品ID】获取分布式锁，保证同一商品并发下单时库存扣减的串行化
        //    锁超时时间从 Nacos 动态读取（businessDynamicConfig）
        //    当 leaseSeconds > 0 时：超时自动释放（死锁保护）
        //    当 leaseSeconds = -1 时：启用 Redisson 看门狗自动续期（每 10s 续期一次）
        String lockKey = STOCK_LOCK_KEY_PREFIX + dto.getProductId();
        RLock lock = redissonClient.getLock(lockKey);
        long lockWaitSeconds = businessDynamicConfig.getInventoryLockWaitSeconds();
        long lockLeaseSeconds = businessDynamicConfig.getInventoryLockLeaseSeconds();
        boolean useWatchdog = (lockLeaseSeconds <= 0);
        try {
            boolean locked;
            if (useWatchdog) {
                // 看门狗模式：leaseTime=-1，Redisson 会在后台每 10s 自动续期30s，直到业务完成
                locked = lock.tryLock(lockWaitSeconds, -1, TimeUnit.SECONDS);
                log.info("以看门狗模式获取库存锁：lockKey={}, userId={}, wait={}s", lockKey, dto.getUserId(), lockWaitSeconds);
            } else {
                locked = lock.tryLock(lockWaitSeconds, lockLeaseSeconds, TimeUnit.SECONDS);
                log.info("以固定超时获取库存锁：lockKey={}, userId={}, wait={}s, lease={}s",
                        lockKey, dto.getUserId(), lockWaitSeconds, lockLeaseSeconds);
            }
            if (!locked) {
                log.warn("获取库存分布式锁失败：productId={}, 等待={}s, 持有={}s, 请稍后重试",
                        dto.getProductId(), lockWaitSeconds, lockLeaseSeconds);
                throw new BusinessException(ErrorCode.LOCK_ACQUIRE_FAILED);
            }

            // 锁获取成功后，将释放动作挂到事务提交/回滚之后执行，
            // 保证锁持有时间覆盖整个事务（含提交阶段），避免"锁先释放、事务未提交"的并发窗口
            releaseLockAfterTransaction(lock, lockKey);

            // 3. 锁内再次检查库存 + 数据库乐观扣减（双重保险，防超卖）
            ProductDO latestProduct = productService.getById(dto.getProductId());
            if (latestProduct.getStock() < dto.getQuantity()) {
                throw new BusinessException(ErrorCode.PRODUCT_STOCK_INSUFFICIENT);
            }

            // 4. 数据库原子扣减（SQL层面的 stock >= quantity 判断，再一层保险）
            boolean decreaseOk = productService.decreaseStock(dto.getProductId(), dto.getQuantity());
            if (!decreaseOk) {
                log.error("库存扣减失败：productId={}, quantity={}", dto.getProductId(), dto.getQuantity());
                throw new BusinessException(ErrorCode.PRODUCT_STOCK_INSUFFICIENT);
            }
            log.info("库存扣减成功：productId={}, 扣减数量={}, 扣减后库存={}",
                    dto.getProductId(), dto.getQuantity(), latestProduct.getStock() - dto.getQuantity());

            // 5. 构建订单并保存
            OrderDO order = buildOrder(dto, product, address);
            this.save(order);
            log.info("订单创建成功：orderId={}, orderNo={}, userId={}, productId={}, 数量={}, 总金额={}",
                    order.getId(), order.getOrderNo(), dto.getUserId(), dto.getProductId(),
                    dto.getQuantity(), order.getTotalAmount());

            // 6. 事务提交后再发送"超时未支付自动关单"延迟消息（为了防止“订单没生成，关单消息却发出去了”）
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        orderTimeoutCancelSender.sendDelayCancel(
                                new OrderTimeoutMessage(order.getId(), order.getOrderNo()));
                    }
                });
            } else {
                orderTimeoutCancelSender.sendDelayCancel(
                        new OrderTimeoutMessage(order.getId(), order.getOrderNo()));
            }

            return order.getOrderNo();

        } catch (BusinessException e) {
            throw e;
        } catch (DuplicateKeyException e) {
            // 幂等第二层兜底：uk_idempotency_token 唯一索引冲突。
            // 走到这里说明凭证层失效（Redis 故障降级放行）或请求绕过凭证直接调接口，
            // 由数据库唯一索引做最终拦截，防止同一凭证重复下单、重复扣库存。
            log.warn("订单创建被幂等凭证唯一索引拦截（重复提交）：userId={}, productId={}",
                    dto.getUserId(), dto.getProductId());
            throw new BusinessException(ErrorCode.ORDER_DUPLICATE_SUBMIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.ORDER_CREATE_FAILED, "获取锁被中断");
        } catch (Exception e) {
            log.error("订单创建异常：", e);
            throw new BusinessException(ErrorCode.ORDER_CREATE_FAILED);
        }
    }

    /**
     * Sentinel 限流触发时的降级方法（blockHandler）
     * 签名要求：参数列表 = 原方法参数 + 最后加 BlockException；返回值与原方法一致
     */
    public String createOrderBlockHandler(OrderCreateDTO dto, BlockException ex) {
        log.warn("【Sentinel限流】创建订单接口触发QPS限流：userId={}, productId={}, rule={}",
                dto.getUserId(), dto.getProductId(), ex.getRule());
        throw new BusinessException(ErrorCode.SYSTEM_BUSY, "下单人数过多，请稍后再试");
    }

    /**
     * Sentinel 业务异常降级方法（fallback）
     */
    public String createOrderFallback(OrderCreateDTO dto, Throwable t) throws Throwable {
        if (t instanceof BusinessException) {
            throw t;
        }
        log.error("【Sentinel降级兜底】创建订单接口发生非限流异常：userId={}, productId={}",
                dto.getUserId(), dto.getProductId(), t);
        throw t;
    }

    /**
     * 生成下单一次性凭证（幂等用）
     *
     * <p>客户端流程：进入下单页 → 调本接口领凭证 → 提交订单时携带 → 服务端用后即焚。
     * 用户想再下一单同样的商品，需要重新进入下单页领取新凭证——
     * 这一步天然区分了"用户有意的第二次购买"和"误触/重试产生的重复提交"。</p>
     */
    @Override
    public String generateOrderToken(Long userId, Long productId) {
        // 顺带做一次存在性校验，避免给不存在的用户/商品发凭证
        userService.getUserById(userId);
        ProductDO product = productService.getById(productId);
        if (product == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return orderTokenService.generate(userId, productId);
    }

    @Override
    public PageResult<OrderVO> listOrders(Long userId, Integer status, long page, long pageSize) {
        LambdaQueryWrapper<OrderDO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderDO::getUserId, userId);
        if (status != null) {
            wrapper.eq(OrderDO::getStatus, status);
        }
        wrapper.orderByDesc(OrderDO::getCreateTime);
        Page<OrderDO> p = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));
        Page<OrderDO> result = this.page(p, wrapper);
        return PageResult.of(result.convert(OrderConvert.INSTANCE::doToVO));
    }

    @Override
    public OrderDetailVO getOrderDetail(Long orderId, Long userId) {
        OrderDO order = getOrderAndCheckUser(orderId, userId);
        return OrderConvert.INSTANCE.doToDetailVO(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @SentinelResource(
            value = "cancelOrder",
            blockHandler = "cancelOrderBlockHandler",
            fallback = "cancelOrderFallback"
    )
    public void cancelOrder(OrderCancelDTO dto) {
        OrderDO order = getOrderAndCheckUser(dto.getOrderId(), dto.getUserId());

        // 1. 状态校验：只有待支付/已支付可取消
        if (!OrderStatusEnum.canCancel(order.getStatus())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_ERROR,
                    "当前订单状态[" + OrderStatusEnum.getTextByCode(order.getStatus()) + "]不允许取消");
        }

        // 2. 加锁回滚库存 + 更新状态（与超时自动关单共用核心逻辑）
        doCancelOrder(order, dto.getCancelReason() != null ? dto.getCancelReason() : "用户主动取消");
    }

    /**
     * 超时未支付自动关单（RocketMQ 延迟消息消费端调用）
     * 幂等：仅"待支付"订单会被取消，已支付/已取消等状态直接忽略，避免重复回滚库存。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void autoCancelOrder(Long orderId) {
        OrderDO order = this.getById(orderId);
        if (order == null) {
            log.warn("超时关单：订单不存在，忽略：orderId={}", orderId);
            return;
        }
        if (!Objects.equals(order.getStatus(), OrderStatusEnum.PENDING_PAYMENT.getCode())) {
            log.info("超时关单：订单非待支付状态（status={}），忽略：orderId={}", order.getStatus(), orderId);
            return;
        }
        doCancelOrder(order, "超时未支付自动取消");
    }

    /**
     * 模拟支付回调：待支付 -> 已支付（幂等，重复回调不报错）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void payCallback(String orderNo) {
        boolean updated = this.update(new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getOrderNo, orderNo)
                .eq(OrderDO::getStatus, OrderStatusEnum.PENDING_PAYMENT.getCode())
                .set(OrderDO::getStatus, OrderStatusEnum.PAID.getCode())
                .set(OrderDO::getPayTime, LocalDateTime.now()));
        if (updated) {
            log.info("订单支付成功：orderNo={}", orderNo);
            return;
        }
        // 未更新成功：可能订单不存在，或重复回调（已支付）
        OrderDO order = orderMapper.selectOne(new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (Objects.equals(order.getStatus(), OrderStatusEnum.PAID.getCode())) {
            log.info("支付回调重复：订单已支付，幂等返回：orderNo={}", orderNo);
            return;
        }
        throw new BusinessException(ErrorCode.ORDER_STATUS_ERROR,
                "当前订单状态[" + OrderStatusEnum.getTextByCode(order.getStatus()) + "]不允许支付");
    }

    /**
     * 发货（管理端）：已支付 -> 已发货
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void shipOrder(Long orderId) {
        boolean updated = this.update(new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getId, orderId)
                .eq(OrderDO::getStatus, OrderStatusEnum.PAID.getCode())
                .set(OrderDO::getStatus, OrderStatusEnum.SHIPPED.getCode())
                .set(OrderDO::getShipTime, LocalDateTime.now()));
        if (!updated) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_ERROR, "订单不存在或当前状态不允许发货");
        }
        log.info("订单已发货：orderId={}", orderId);
    }

    /**
     * 完成订单（管理端）：已发货 -> 已完成
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finishOrder(Long orderId) {
        boolean updated = this.update(new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getId, orderId)
                .eq(OrderDO::getStatus, OrderStatusEnum.SHIPPED.getCode())
                .set(OrderDO::getStatus, OrderStatusEnum.COMPLETED.getCode())
                .set(OrderDO::getFinishTime, LocalDateTime.now()));
        if (!updated) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_ERROR, "订单不存在或当前状态不允许完成");
        }
        log.info("订单已完成：orderId={}", orderId);
    }

    /**
     * 取消订单核心逻辑（加锁回滚库存 + 更新状态），供用户取消与超时自动关单共用
     */
    private void doCancelOrder(OrderDO order, String reason) {
        // 1. 回滚库存（已扣减的数量加回去）—— 同样加分布式锁，避免与并发下单冲突
        String lockKey = STOCK_LOCK_KEY_PREFIX + order.getProductId();
        RLock lock = redissonClient.getLock(lockKey);
        long lockWaitSeconds = businessDynamicConfig.getInventoryLockWaitSeconds();
        long lockLeaseSeconds = businessDynamicConfig.getInventoryLockLeaseSeconds();
        boolean useWatchdog = (lockLeaseSeconds <= 0);
        try {
            boolean locked;
            if (useWatchdog) {
                locked = lock.tryLock(lockWaitSeconds, -1, TimeUnit.SECONDS);
            } else {
                locked = lock.tryLock(lockWaitSeconds, lockLeaseSeconds, TimeUnit.SECONDS);
            }
            if (!locked) {
                throw new BusinessException(ErrorCode.LOCK_ACQUIRE_FAILED);
            }

            // 锁获取成功后，将释放动作挂到事务提交/回滚之后执行
            releaseLockAfterTransaction(lock, lockKey);
            // 校验回滚结果：increaseStock 的 SQL 带 deleted=0 条件，商品被逻辑删除时返回 false。
            // 早期版本未校验返回值就打印"回滚成功"，会导致库存实际未回滚却把订单置为已取消
            // （库存凭空丢失且无日志可查）。这里补齐校验，失败即中止取消流程。
            boolean rollbackOk = productService.increaseStock(order.getProductId(), order.getQuantity());
            if (!rollbackOk) {
                log.error("订单取消中止：库存回滚未生效，productId={}, 回滚数量={}",
                        order.getProductId(), order.getQuantity());
                throw new BusinessException(ErrorCode.ORDER_CANCEL_FAILED, "库存回滚失败，请稍后重试");
            }
            log.info("订单取消：回滚库存成功，productId={}, 回滚数量={}", order.getProductId(), order.getQuantity());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.ORDER_CANCEL_FAILED);
        }

        // 2. 更新订单状态为已取消
        //    这里用条件更新而非 updateById：一是避免并发下覆盖其他状态流转，二是能拿到"是否更新成功"的结果
        boolean canceled = this.update(new LambdaUpdateWrapper<OrderDO>()
                .eq(OrderDO::getId, order.getId())
                .set(OrderDO::getStatus, OrderStatusEnum.CANCELED.getCode())
                .set(OrderDO::getCancelTime, LocalDateTime.now())
                .set(OrderDO::getCancelReason, reason));
        if (!canceled) {
            throw new BusinessException(ErrorCode.ORDER_CANCEL_FAILED, "订单状态更新失败，请稍后重试");
        }
        log.info("订单取消成功：orderId={}, orderNo={}, reason={}", order.getId(), order.getOrderNo(), reason);
    }

    /**
     * Sentinel 取消订单限流 blockHandler
     */
    public void cancelOrderBlockHandler(OrderCancelDTO dto, BlockException ex) {
        log.warn("【Sentinel限流】取消订单接口触发QPS限流：orderId={}, rule={}", dto.getOrderId(), ex.getRule());
        throw new BusinessException(ErrorCode.SYSTEM_BUSY, "操作过于频繁，请稍后再试");
    }

    /**
     * Sentinel 取消订单 fallback
     */
    public void cancelOrderFallback(OrderCancelDTO dto, Throwable t) throws Throwable {
        if (t instanceof BusinessException) {
            throw t;
        }
        log.error("【Sentinel降级兜底】取消订单接口异常：orderId={}", dto.getOrderId(), t);
        throw t;
    }

    @Override
    public OrderDO getOrderByIdForAi(Long orderId) {
        OrderDO order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    /**
     * 构建订单对象
     */
    private OrderDO buildOrder(OrderCreateDTO dto, ProductDO product, UserAddressDO address) {
        OrderDO order = new OrderDO();
        String orderNo = orderNoGenerator.generate();
        order.setOrderNo(orderNo);
        order.setUserId(dto.getUserId());
        order.setProductId(dto.getProductId());
        order.setProductName(product.getName());
        order.setProductPrice(product.getPrice());
        order.setQuantity(dto.getQuantity());
        order.setTotalAmount(product.getPrice().multiply(BigDecimal.valueOf(dto.getQuantity())));
        order.setAddressId(dto.getAddressId());
        order.setAddressSnapshot(buildAddressSnapshot(address));
        order.setStatus(OrderStatusEnum.PENDING_PAYMENT.getCode());
        // 记录本次下单使用的幂等凭证，配合 uk_idempotency_token 唯一索引做数据库层兜底
        order.setIdempotencyToken(dto.getToken());
        return order;
    }

    private String buildAddressSnapshot(UserAddressDO address) {
        return address.getProvince() + address.getCity() + address.getDistrict() + address.getDetail()
                + " " + address.getReceiver() + " " + address.getPhone();
    }

    /**
     * 释放库存分布式锁：
     * - 事务开启时：把释放动作注册到事务同步回调（afterCompletion），在事务提交/回滚之后释放，
     *   保证锁的持有时间覆盖整个事务（含提交阶段），避免"锁已释放、事务尚未提交"的并发窗口；
     * - 无事务时（如单元测试、非事务调用）：立即释放。
     */
    private void releaseLockAfterTransaction(RLock lock, String lockKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                        log.info("事务提交/回滚后已释放库存分布式锁：lockKey={}", lockKey);
                    }
                }
            });
        } else if (lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.info("已释放库存分布式锁（无事务）：lockKey={}", lockKey);
        }
    }
    private OrderDO getOrderAndCheckUser(Long orderId, Long userId) {
        OrderDO order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_FOUND, "订单不存在或不属于当前用户");
        }
        return order;
    }
}
