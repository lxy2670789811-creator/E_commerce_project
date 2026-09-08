package com.ecommerce.service.impl;

import com.ecommerce.config.BusinessDynamicConfig;
import com.ecommerce.mapper.ProductMapper;
import com.ecommerce.vo.product.ProductVO;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品详情缓存"防穿透 / 防雪崩"两项防护的单元测试
 *
 * <p>为什么需要这组测试：
 * 空值缓存（防穿透）能否生效，完全依赖一个容易被忽略的假设——
 * 写入 Redis 的字符串标记 {@code __NULL__}，经过 {@code GenericJackson2JsonRedisSerializer}
 * 序列化再反序列化回来之后，<b>仍然是 String 类型</b>。
 * 如果读回来变成了别的类型，{@code isNullMarker()} 会永远返回 false，
 * 空值缓存形同虚设（代码跑得欢，防护却是假的）。
 * 这里直接用与生产一致的配置验证这个假设，而不是靠推理。</p>
 */
class ProductCacheNullMarkerTest {

    private static final String NULL_MARKER = "__NULL__";
    private static final long BASE_TTL = 3600L;
    private static final long JITTER = 300L;

    /**
     * 构造与 RedisConfig 一致的序列化器（JavaTimeModule + 全字段可见 + GenericJackson 类型信息）
     */
    private GenericJackson2JsonRedisSerializer newSerializerLikeProduction() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 与修复后的 RedisConfig 保持一致：显式开启 @class 类型信息
        objectMapper.activateDefaultTyping(
                objectMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }

    // ==================== 防穿透：空值缓存的前提假设 ====================

    @Test
    void nullMarkerSurvivesSerializationRoundTrip() {
        GenericJackson2JsonRedisSerializer serializer = newSerializerLikeProduction();

        byte[] bytes = serializer.serialize(NULL_MARKER);
        Object back = serializer.deserialize(bytes);

        assertTrue(back instanceof String,
                "空值标记反序列化后必须是 String，否则 isNullMarker 识别不到、防穿透失效，实际类型："
                        + (back == null ? "null" : back.getClass().getName()));
        assertEquals(NULL_MARKER, back, "空值标记的值不应在序列化往返中丢失");
    }

    @Test
    void realProductVoIsNeverMistakenForNullMarker() {
        GenericJackson2JsonRedisSerializer serializer = newSerializerLikeProduction();

        byte[] bytes = serializer.serialize(sampleVo());
        Object back = serializer.deserialize(bytes);

        assertTrue(back instanceof ProductVO,
                "正常商品缓存反序列化后应是 ProductVO，实际类型："
                        + (back == null ? "null" : back.getClass().getName()));
        assertFalse(back instanceof String,
                "正常商品缓存绝不能被判成空值标记，否则会被误杀成“商品不存在”");
    }

    private ProductVO sampleVo() {
        ProductVO vo = new ProductVO();
        vo.setId(1L);
        vo.setName("无线蓝牙耳机Pro");
        vo.setDescription("主动降噪、蓝牙5.3");
        vo.setPrice(new BigDecimal("399.00"));
        vo.setStock(100);
        vo.setStatus(1);
        vo.setStatusText("已上架");
        vo.setCategory("数码配件");
        vo.setImageUrl("https://example.com/img.jpg");
        vo.setCreateTime(LocalDateTime.now());
        vo.setUpdateTime(LocalDateTime.now());
        return vo;
    }

    // ==================== 防雪崩：TTL 随机抖动 ====================

    @SuppressWarnings("unchecked")
    private ProductServiceImpl newService(BusinessDynamicConfig config) {
        return new ProductServiceImpl(
                Mockito.mock(ProductMapper.class),
                Mockito.mock(RedisTemplate.class),
                config);
    }

    @Test
    void expireWithJitterStaysInRangeAndSpreads() {
        BusinessDynamicConfig config = new BusinessDynamicConfig();
        config.setProductDetailExpireSeconds(BASE_TTL);
        config.setProductDetailExpireJitterSeconds(JITTER);
        ProductServiceImpl service = newService(config);

        Set<Long> distinct = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Long ttl = ReflectionTestUtils.invokeMethod(service, "resolveExpireWithJitter");
            assertTrue(ttl >= BASE_TTL && ttl <= BASE_TTL + JITTER,
                    "TTL 必须落在 [base, base+jitter] 区间内，实际：" + ttl);
            distinct.add(ttl);
        }
        assertTrue(distinct.size() > 50,
                "抖动应让 TTL 明显分散（实现错峰过期），实际 200 次仅产生 " + distinct.size() + " 种取值");
    }

    @Test
    void jitterDisabledFallsBackToBaseTtl() {
        BusinessDynamicConfig config = new BusinessDynamicConfig();
        config.setProductDetailExpireSeconds(BASE_TTL);
        config.setProductDetailExpireJitterSeconds(0L);
        ProductServiceImpl service = newService(config);

        Long ttl = ReflectionTestUtils.invokeMethod(service, "resolveExpireWithJitter");
        assertEquals(BASE_TTL, ttl, "抖动上限为 0 时应关闭抖动，严格返回基础 TTL");
    }
}
