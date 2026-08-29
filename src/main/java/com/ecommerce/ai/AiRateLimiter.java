package com.ecommerce.ai;

import com.ecommerce.config.BusinessDynamicConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * AI服务简单限流（基于 Redis 滑动窗口计数器）
 * 防止频繁调用大模型 API
 *
 * 限流参数 maxRequests / timeWindowSeconds 由 BusinessDynamicConfig 提供，
 * 可在 Nacos 配置中心动态修改，无需重启服务即可生效。
 */
@Slf4j
@Component
public class AiRateLimiter {

    private static final String RATE_LIMIT_KEY_PREFIX = "ecommerce:ai:ratelimit:";

    private final RedisTemplate<String, Object> redisTemplate;

    /** 动态配置注入（@RefreshScope 代理对象，每次 get 都会读取最新值） */
    private final BusinessDynamicConfig businessDynamicConfig;

    /**
     * Lua 脚本：保证计数+过期是原子操作
     */
    private static final String LUA_SCRIPT =
            "local current = redis.call('INCR', KEYS[1])\n" +
            "if current == 1 then\n" +
            "    redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +
            "end\n" +
            "return current";

    private final DefaultRedisScript<Long> redisScript;

    public AiRateLimiter(RedisTemplate<String, Object> redisTemplate,
                         BusinessDynamicConfig businessDynamicConfig) {
        this.redisTemplate = redisTemplate;
        this.businessDynamicConfig = businessDynamicConfig;
        this.redisScript = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
    }

    /**
     * 尝试获取调用许可
     *
     * @param userId 用户ID（按用户维度限流；可改为全局就传固定值）
     * @return true 允许调用；false 超出限流
     */
    public boolean tryAcquire(Long userId) {
        // 从动态配置读取最新的限流参数（Nacos 修改后立即生效）
        int maxRequests = businessDynamicConfig.getAiRateLimitMaxRequests();
        int timeWindowSeconds = businessDynamicConfig.getAiRateLimitTimeWindowSeconds();

        String key = RATE_LIMIT_KEY_PREFIX + userId;
        try {
            List<String> keys = Collections.singletonList(key);
            Long count = redisTemplate.execute(redisScript, keys, String.valueOf(timeWindowSeconds));
            if (count == null) {
                log.warn("AI限流脚本返回null，放行：userId={}", userId);
                return true;
            }
            boolean allowed = count <= maxRequests;
            if (!allowed) {
                log.warn("AI服务触发限流：userId={}, 当前计数={}, 上限={}, 时间窗口={}s",
                        userId, count, maxRequests, timeWindowSeconds);
            }
            return allowed;
        } catch (Exception e) {
            // Redis 异常时降级：放行（避免限流组件故障影响业务）
            log.warn("AI限流器异常，降级放行：userId={}", userId, e);
            return true;
        }
    }
}
