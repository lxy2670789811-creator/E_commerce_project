package com.ecommerce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.common.BusinessException;
import com.ecommerce.common.ErrorCode;
import com.ecommerce.dto.order.OrderCreateDTO;
import com.ecommerce.entity.OrderDO;
import com.ecommerce.entity.ProductDO;
import com.ecommerce.entity.UserAddressDO;
import com.ecommerce.entity.UserDO;
import com.ecommerce.mapper.OrderMapper;
import com.ecommerce.mapper.ProductMapper;
import com.ecommerce.mapper.UserAddressMapper;
import com.ecommerce.mapper.UserMapper;
import com.ecommerce.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发防超卖集成测试（真实 MySQL + Redis，不依赖 Docker）
 *
 * 运行前提：本机 MySQL(127.0.0.1:3306，root/123456 或 MYSQL_USERNAME/MYSQL_PASSWORD 环境变量)
 * 与 Redis(127.0.0.1:6379) 可用。
 * 测试使用独立测试库 ecommerce_test 与 Redis DB15，不会污染开发数据。
 *
 * 验证点：40 个线程并发下单抢 20 件库存，最终必须恰好成功 20 单、库存扣到 0，
 * 不允许任何超卖（库存为负 / 订单数超过库存）。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderConcurrencyIntegrationTest {

    private static final int STOCK = 20;
    private static final int THREADS = 40;

    @Autowired
    private OrderService orderService;
    @Autowired
    private ProductMapper productMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private UserAddressMapper userAddressMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long addressId;
    private Long productId;

    @BeforeEach
    void setUp() {
        // 清空测试表（独立测试库，不影响开发数据）
        jdbcTemplate.execute("TRUNCATE TABLE orders");
        jdbcTemplate.execute("TRUNCATE TABLE user_address");
        jdbcTemplate.execute("TRUNCATE TABLE product");
        jdbcTemplate.execute("TRUNCATE TABLE sys_user");
        jdbcTemplate.execute("TRUNCATE TABLE ai_after_support");

        UserDO user = new UserDO();
        user.setUsername("concurrency_user");
        user.setPassword("123456");
        user.setStatus(1);
        userMapper.insert(user);
        userId = user.getId();

        UserAddressDO address = new UserAddressDO();
        address.setUserId(userId);
        address.setReceiver("张三");
        address.setPhone("13800138000");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetail("测试路1号");
        address.setIsDefault(1);
        userAddressMapper.insert(address);
        addressId = address.getId();

        ProductDO product = new ProductDO();
        product.setName("并发测试商品");
        product.setPrice(new BigDecimal("88.00"));
        product.setStock(STOCK);
        product.setStatus(1);
        productMapper.insert(product);
        productId = product.getId();
    }

    @Test
    void concurrentCreateOrders_neverOversell() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                OrderCreateDTO dto = new OrderCreateDTO();
                dto.setUserId(userId);
                dto.setProductId(productId);
                dto.setQuantity(1);
                dto.setAddressId(addressId);
                try {
                    orderService.createOrder(dto);
                    return null; // 成功
                } catch (BusinessException e) {
                    return e.getCode(); // 业务失败（库存不足/获取锁失败等）
                } catch (Exception e) {
                    return ErrorCode.SYSTEM_ERROR.getCode();
                }
            }));
        }

        start.countDown();
        int successCount = 0;
        List<Integer> failureCodes = new ArrayList<>();
        for (Future<Integer> future : futures) {
            Integer code = future.get(60, TimeUnit.SECONDS);
            if (code == null) {
                successCount++;
            } else {
                failureCodes.add(code);
            }
        }
        pool.shutdown();

        // 核心断言：不允许超卖
        assertThat(successCount)
                .as("成功下单数必须恰好等于库存数（不允许超卖，也不允许无故丢单）")
                .isEqualTo(STOCK);

        ProductDO after = productMapper.selectById(productId);
        assertThat(after.getStock()).as("库存必须恰好扣到0，不能为负").isZero();

        Long orderCount = orderMapper.selectCount(
                new LambdaQueryWrapper<OrderDO>().eq(OrderDO::getProductId, productId));
        assertThat(orderCount).as("订单数必须等于库存数").isEqualTo((long) STOCK);

        // 失败原因只能是"库存不足"或"获取锁失败"，不允许出现其他系统错误
        assertThat(failureCodes).hasSize(THREADS - STOCK);
        assertThat(failureCodes).allMatch(code ->
                code == ErrorCode.PRODUCT_STOCK_INSUFFICIENT.getCode()
                        || code == ErrorCode.LOCK_ACQUIRE_FAILED.getCode());
    }
}