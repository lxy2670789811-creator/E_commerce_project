package com.ecommerce.common;

import com.ecommerce.config.BusinessDynamicConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;

/**
 * 下单一次性凭证（Idempotency Token）服务
 *
 * <p>解决的问题：createOrder 接口本身不是幂等的——用户双击提交、前端超时自动重试、
 * 网络重发，都会生成两张订单并扣两次库存。防超卖（分布式锁 + 原子 SQL）只能挡住"并发"，
 * 挡不住"时间上分散的重复提交"。</p>
 *
 * <p>设计思路：把"意图"的判断权交给客户端，因为它才看得见用户的手指。
 *   1. 进入下单页 → 调用 GET /order/token 领一张凭证（绑定 userId + productId）；
 *   2. 提交订单时携带凭证 → 服务端用 Lua 原子地"取出并删除"（GETDEL）；
 *   3. 第二次用同一张凭证提交 → Redis 里已经没有了 → 判定为重复提交，拒绝。
 * 用户想再买一单？重新进入下单页领新凭证即可——这就是"有意"的自然表达。</p>
 *
 * <p>为什么必须用 Lua 而不是 GET + DEL 两步？
 *   并发下两个请求可能都 GET 到值、都通过校验，然后各下一单。
 *   Lua 脚本在 Redis 内原子执行，"取出并删除"要么全做要么全不做，杜绝这个窗口。
 *   （Redis 6.2+ 有原生 GETDEL 命令，这里用 Lua 是为了兼容低版本。）</p>
 *
 * <p>降级策略：Redis 故障时放行（fail-open），由数据库唯一索引 uk_pending_unique 兜底。
 *   两层防护互相独立，任一层失效另一层仍然生效。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTokenService {

    private static final String TOKEN_KEY_PREFIX = "ecommerce:order:token:";

    /**
     * Lua：原子地"取出并删除"（GETDEL）
     * 返回被删除的 value；key 不存在或已被消耗时返回 nil（Java 侧为 null）
     */
    private static final String CONSUME_LUA =
            "local v = redis.call('GET', KEYS[1])\n" +
            "if v then\n" +
            "    redis.call('DEL', KEYS[1])\n" +
            "    return v\n" +
            "end\n" +
            "return nil";

    private final StringRedisTemplate stringRedisTemplate;
    private final BusinessDynamicConfig businessDynamicConfig;

    private final DefaultRedisScript<String> consumeScript = new DefaultRedisScript<>(CONSUME_LUA, String.class);

    /**
     * 生成下单凭证（进入下单页时调用）
     *
     * @param userId    用户ID
     * @param productId 商品ID
     * @return 一次性凭证字符串
     */
    public String generate(Long userId, Long productId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = buildKey(token);
        long ttlSeconds = businessDynamicConfig.getOrderTokenExpireSeconds();
        // value 记录归属，防止 A 用户的凭证被 B 用户拿去下单
        stringRedisTemplate.opsForValue().set(key, buildValue(userId, productId), ttlSeconds, java.util.concurrent.TimeUnit.SECONDS);
        log.debug("生成下单凭证：userId={}, productId={}, ttl={}s", userId, productId, ttlSeconds);
        return token;
    }

    /**
     * 校验并消耗凭证（提交订单时调用，原子操作）
     *
     * @param token     客户端携带的凭证
     * @param userId    当前用户ID
     * @param productId 当前商品ID
     * @return true = 凭证有效且已消耗，允许下单；false = 重复提交或凭证无效
     */
    public boolean consume(String token, Long userId, Long productId) {
        long ttl = businessDynamicConfig.getOrderTokenExpireSeconds();
        if (!businessDynamicConfig.isOrderTokenEnabled() || ttl <= 0) {
            log.debug("下单凭证校验已关闭，跳过：userId={}", userId);
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            // 原子取出并删除：并发下只有一个请求能拿到非 null
            String value = stringRedisTemplate.execute(consumeScript, Collections.singletonList(buildKey(token)));
            if (value == null) {
                log.warn("下单凭证无效或已被使用（判定为重复提交）：userId={}, productId={}", userId, productId);
                return false;
            }
            // 校验归属：凭证必须和本次下单的用户、商品一致
            // 注意：不匹配时凭证已被删除（fail-safe，防止暴力尝试复用）
            boolean matched = buildValue(userId, productId).equals(value);
            if (!matched) {
                log.warn("下单凭证归属不匹配，已销毁：userId={}, productId={}", userId, productId);
            }
            return matched;
        } catch (Exception e) {
            // Redis 故障：放行，交给数据库唯一索引兜底（与项目整体降级哲学一致）
            log.warn("下单凭证校验异常（Redis故障），降级放行由DB唯一索引兜底：userId={}", userId, e);
            return true;
        }
    }

    private String buildKey(String token) {
        return TOKEN_KEY_PREFIX + token;
    }

    private String buildValue(Long userId, Long productId) {
        return userId + ":" + productId;
    }
}
