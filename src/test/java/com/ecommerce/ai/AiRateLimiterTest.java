package com.ecommerce.ai;

import com.ecommerce.config.BusinessDynamicConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * AI 限流器单元测试：窗口内未超限放行、超限拒绝、Redis 异常降级放行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiRateLimiterTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private BusinessDynamicConfig businessDynamicConfig;

    private AiRateLimiter aiRateLimiter;

    @BeforeEach
    void setUp() {
        aiRateLimiter = new AiRateLimiter(redisTemplate, businessDynamicConfig);
        when(businessDynamicConfig.getAiRateLimitMaxRequests()).thenReturn(10);
        when(businessDynamicConfig.getAiRateLimitTimeWindowSeconds()).thenReturn(60);
    }

    @Test
    void allowsWhenCountWithinLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(5L);
        assertThat(aiRateLimiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void blocksWhenCountExceedsLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(11L);
        assertThat(aiRateLimiter.tryAcquire(1L)).isFalse();
    }

    @Test
    void allowsWhenScriptReturnsNull() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(null);
        assertThat(aiRateLimiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void allowsWhenRedisUnavailable_degradeOpen() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("redis down"));
        assertThat(aiRateLimiter.tryAcquire(1L)).isTrue();
    }
}