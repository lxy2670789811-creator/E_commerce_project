package com.ecommerce.service.impl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import com.ecommerce.common.BusinessException;
import com.ecommerce.common.ErrorCode;
import com.ecommerce.common.OrderNoGenerator;
import com.ecommerce.config.BusinessDynamicConfig;
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
import com.ecommerce.service.ProductService;
import com.ecommerce.service.UserAddressService;
import com.ecommerce.service.UserService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单服务核心业务单元测试（Mockito，无 Spring 上下文）：
 * 覆盖下单成功、商品不存在/下架、库存不足、库存扣减失败、锁获取失败、取消订单等核心路径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderServiceImplTest {

    private static final String FIXED_ORDER_NO = "ORD202608291200001234567890123456789";

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private ProductService productService;
    @Mock
    private UserService userService;
    @Mock
    private UserAddressService userAddressService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private BusinessDynamicConfig businessDynamicConfig;
    @Mock
    private OrderNoGenerator orderNoGenerator;
    @Mock
    private RLock lock;
    @Mock
    private OrderTimeoutCancelSender orderTimeoutCancelSender;

    @InjectMocks
    private OrderServiceImpl orderService;

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        // 纯 Mockito 环境不会自动构建 MyBatis-Plus TableInfo，
        // LambdaUpdateWrapper 需要 lambda cache，这里手动初始化 OrderDO
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderDO.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        // ServiceImpl 继承而来的 baseMapper 由 Spring 注入，单元测试需手动注入，否则 save/getById 会 NPE
        ReflectionTestUtils.setField(orderService, "baseMapper", orderMapper);

        when(businessDynamicConfig.getInventoryLockWaitSeconds()).thenReturn(5L);
        when(businessDynamicConfig.getInventoryLockLeaseSeconds()).thenReturn(30L);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(orderNoGenerator.generate()).thenReturn(FIXED_ORDER_NO);
    }

    private OrderCreateDTO buildCreateDTO() {
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setUserId(1L);
        dto.setProductId(100L);
        dto.setQuantity(2);
        dto.setAddressId(10L);
        return dto;
    }

    private ProductDO buildProduct(int stock, int status) {
        ProductDO product = new ProductDO();
        product.setId(100L);
        product.setName("测试商品");
        product.setPrice(new BigDecimal("99.90"));
        product.setStock(stock);
        product.setStatus(status);
        return product;
    }

    private UserAddressDO buildAddress() {
        UserAddressDO address = new UserAddressDO();
        address.setId(10L);
        address.setUserId(1L);
        address.setReceiver("张三");
        address.setPhone("13800138000");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetail("科技园路1号");
        address.setIsDefault(1);
        return address;
    }

    private void stubCommonSuccess() {
        when(userService.getUserById(1L)).thenReturn(new UserDO());
        when(userAddressService.getByIdAndUserId(10L, 1L)).thenReturn(buildAddress());
        when(productService.getById(100L)).thenReturn(buildProduct(10, 1));
        when(productService.decreaseStock(100L, 2)).thenReturn(true);
        when(orderMapper.insert(any(OrderDO.class))).thenReturn(1);
    }

    // ==================== 创建订单 ====================

    @Test
    void createOrder_success_savesOrderWithSnapshotAndReturnsOrderNo() {
        stubCommonSuccess();

        String orderNo = orderService.createOrder(buildCreateDTO());

        assertThat(orderNo).isEqualTo(FIXED_ORDER_NO);
        verify(productService).decreaseStock(100L, 2);

        ArgumentCaptor<OrderDO> captor = ArgumentCaptor.forClass(OrderDO.class);
        verify(orderMapper).insert(captor.capture());
        OrderDO saved = captor.getValue();
        assertThat(saved.getOrderNo()).isEqualTo(FIXED_ORDER_NO);
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getProductId()).isEqualTo(100L);
        assertThat(saved.getProductName()).isEqualTo("测试商品");
        assertThat(saved.getProductPrice()).isEqualByComparingTo("99.90");
        assertThat(saved.getQuantity()).isEqualTo(2);
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("199.80");
        assertThat(saved.getStatus()).isEqualTo(OrderStatusEnum.PENDING_PAYMENT.getCode());
        assertThat(saved.getAddressSnapshot()).contains("张三").contains("13800138000");

        // 单元测试无事务，锁应在方法内直接释放
        verify(lock).unlock();
        // 无事务时直接发送延迟关单消息（MQ 不可用时内部降级）
        verify(orderTimeoutCancelSender).sendDelayCancel(any(OrderTimeoutMessage.class));
    }

    @Test
    void createOrder_productNotFound_throws() {
        when(userService.getUserById(1L)).thenReturn(new UserDO());
        when(userAddressService.getByIdAndUserId(10L, 1L)).thenReturn(buildAddress());
        when(productService.getById(100L)).thenReturn(null);

        assertThatThrownBy(() -> orderService.createOrder(buildCreateDTO()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND.getCode()));
    }

    @Test
    void createOrder_productOffShelf_throws() {
        when(userService.getUserById(1L)).thenReturn(new UserDO());
        when(userAddressService.getByIdAndUserId(10L, 1L)).thenReturn(buildAddress());
        when(productService.getById(100L)).thenReturn(buildProduct(10, 0));

        assertThatThrownBy(() -> orderService.createOrder(buildCreateDTO()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_OFF_SHELF.getCode()));
    }

    @Test
    void createOrder_stockInsufficient_throws() {
        when(userService.getUserById(1L)).thenReturn(new UserDO());
        when(userAddressService.getByIdAndUserId(10L, 1L)).thenReturn(buildAddress());
        // 第一次查询（锁外）库存充足，第二次查询（锁内）库存不足
        when(productService.getById(100L)).thenReturn(buildProduct(10, 1), buildProduct(1, 1));

        assertThatThrownBy(() -> orderService.createOrder(buildCreateDTO()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_STOCK_INSUFFICIENT.getCode()));
        verify(orderMapper, never()).insert(any(OrderDO.class));
    }

    @Test
    void createOrder_decreaseStockFails_throwsAndRollsBack() {
        when(userService.getUserById(1L)).thenReturn(new UserDO());
        when(userAddressService.getByIdAndUserId(10L, 1L)).thenReturn(buildAddress());
        when(productService.getById(100L)).thenReturn(buildProduct(10, 1));
        when(productService.decreaseStock(100L, 2)).thenReturn(false);

        assertThatThrownBy(() -> orderService.createOrder(buildCreateDTO()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.PRODUCT_STOCK_INSUFFICIENT.getCode()));
        verify(orderMapper, never()).insert(any(OrderDO.class));
    }

    @Test
    void createOrder_lockNotAcquired_throwsAndNeverUnlocks() throws Exception {
        when(userService.getUserById(1L)).thenReturn(new UserDO());
        when(userAddressService.getByIdAndUserId(10L, 1L)).thenReturn(buildAddress());
        when(productService.getById(100L)).thenReturn(buildProduct(10, 1));
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        assertThatThrownBy(() -> orderService.createOrder(buildCreateDTO()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.LOCK_ACQUIRE_FAILED.getCode()));
        verify(lock, never()).unlock();
    }

    // ==================== 取消订单 ====================

    private OrderDO buildOrder(int status, Long ownerUserId) {
        OrderDO order = new OrderDO();
        order.setId(99L);
        order.setOrderNo(FIXED_ORDER_NO);
        order.setUserId(ownerUserId);
        order.setProductId(100L);
        order.setQuantity(2);
        order.setStatus(status);
        return order;
    }

    private OrderCancelDTO buildCancelDTO() {
        OrderCancelDTO dto = new OrderCancelDTO();
        dto.setOrderId(99L);
        dto.setUserId(1L);
        dto.setCancelReason("不想要了");
        return dto;
    }

    @Test
    void cancelOrder_success_restoresStockAndMarksCanceled() {
        when(orderMapper.selectById(99L)).thenReturn(buildOrder(OrderStatusEnum.PAID.getCode(), 1L));
        when(productService.increaseStock(100L, 2)).thenReturn(true);
        when(orderMapper.updateById(any(OrderDO.class))).thenReturn(1);

        orderService.cancelOrder(buildCancelDTO());

        verify(productService).increaseStock(100L, 2);
        ArgumentCaptor<OrderDO> captor = ArgumentCaptor.forClass(OrderDO.class);
        verify(orderMapper).updateById(captor.capture());
        OrderDO updated = captor.getValue();
        assertThat(updated.getStatus()).isEqualTo(OrderStatusEnum.CANCELED.getCode());
        assertThat(updated.getCancelTime()).isNotNull();
        assertThat(updated.getCancelReason()).isEqualTo("不想要了");
        verify(lock).unlock();
    }

    @Test
    void cancelOrder_orderNotFound_throws() {
        when(orderMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> orderService.cancelOrder(buildCancelDTO()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND.getCode()));
    }

    @Test
    void cancelOrder_notBelongToUser_throws() {
        when(orderMapper.selectById(99L)).thenReturn(buildOrder(OrderStatusEnum.PAID.getCode(), 2L));

        assertThatThrownBy(() -> orderService.cancelOrder(buildCancelDTO()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND.getCode()));
    }

    @Test
    void cancelOrder_statusNotCancelable_throws() {
        when(orderMapper.selectById(99L)).thenReturn(buildOrder(OrderStatusEnum.COMPLETED.getCode(), 1L));

        assertThatThrownBy(() -> orderService.cancelOrder(buildCancelDTO()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode()));
        verify(productService, never()).increaseStock(anyLong(), any());
    }

    // ==================== 支付回调 / 发货 / 完成 ====================

    @Test
    void payCallback_success_transitionsToPaid() {
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        orderService.payCallback("ORD123");

        verify(orderMapper).update(isNull(), any());
    }

    @Test
    void payCallback_duplicate_isIdempotent() {
        when(orderMapper.update(isNull(), any())).thenReturn(0);
        when(orderMapper.selectOne(any())).thenReturn(buildOrder(OrderStatusEnum.PAID.getCode(), 1L));

        orderService.payCallback("ORD123"); // 重复回调不抛异常
    }

    @Test
    void payCallback_orderNotFound_throws() {
        when(orderMapper.update(isNull(), any())).thenReturn(0);
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> orderService.payCallback("ORD123"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND.getCode()));
    }

    @Test
    void payCallback_statusNotPayable_throws() {
        when(orderMapper.update(isNull(), any())).thenReturn(0);
        when(orderMapper.selectOne(any())).thenReturn(buildOrder(OrderStatusEnum.SHIPPED.getCode(), 1L));

        assertThatThrownBy(() -> orderService.payCallback("ORD123"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode()));
    }

    @Test
    void shipOrder_success() {
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        orderService.shipOrder(99L);

        verify(orderMapper).update(isNull(), any());
    }

    @Test
    void shipOrder_wrongStatus_throws() {
        when(orderMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> orderService.shipOrder(99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode()));
    }

    @Test
    void finishOrder_success() {
        when(orderMapper.update(isNull(), any())).thenReturn(1);

        orderService.finishOrder(99L);

        verify(orderMapper).update(isNull(), any());
    }

    @Test
    void finishOrder_wrongStatus_throws() {
        when(orderMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> orderService.finishOrder(99L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode()));
    }

    // ==================== 超时自动关单（RocketMQ 消费端） ====================

    @Test
    void autoCancelOrder_pending_cancelsAndRestoresStock() {
        when(orderMapper.selectById(99L)).thenReturn(buildOrder(OrderStatusEnum.PENDING_PAYMENT.getCode(), 1L));
        when(productService.increaseStock(100L, 2)).thenReturn(true);
        when(orderMapper.updateById(any(OrderDO.class))).thenReturn(1);

        orderService.autoCancelOrder(99L);

        verify(productService).increaseStock(100L, 2);
        ArgumentCaptor<OrderDO> captor = ArgumentCaptor.forClass(OrderDO.class);
        verify(orderMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OrderStatusEnum.CANCELED.getCode());
        assertThat(captor.getValue().getCancelReason()).isEqualTo("超时未支付自动取消");
    }

    @Test
    void autoCancelOrder_notPending_ignored() {
        when(orderMapper.selectById(99L)).thenReturn(buildOrder(OrderStatusEnum.PAID.getCode(), 1L));

        orderService.autoCancelOrder(99L); // 已支付订单不应被自动关单

        verify(productService, never()).increaseStock(anyLong(), any());
        verify(orderMapper, never()).updateById(any(OrderDO.class));
    }

    @Test
    void autoCancelOrder_notFound_ignored() {
        when(orderMapper.selectById(99L)).thenReturn(null);

        orderService.autoCancelOrder(99L);

        verify(productService, never()).increaseStock(anyLong(), any());
        verify(orderMapper, never()).updateById(any(OrderDO.class));
    }
}