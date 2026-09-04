package com.ecommerce.security;

import cn.hutool.crypto.digest.BCrypt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 签发/校验与 BCrypt 密码加密单元测试（不依赖 Spring/DB）
 */
class JwtTokenServiceTest {

    private JwtTokenService jwtTokenService;
    private JwtProperties props;

    @BeforeEach
    void setUp() {
        props = new JwtProperties();
        props.setSecret("unit-test-secret-key-0123456789abcdef-0123456789");
        props.setExpireSeconds(3600L);
        jwtTokenService = new JwtTokenService(props);
        jwtTokenService.init();
    }

    @Test
    void generateThenParse_returnsSameUserId() {
        String token = jwtTokenService.generate(42L);
        assertNotNull(token);
        // JWT 三段时间点分隔
        assertEquals(3, token.split("\\.").length, "JWT 应为 header.payload.signature 三段");
        Long parsed = jwtTokenService.parseAndGetUserId(token);
        assertEquals(42L, parsed, "合法 token 应能解析回原 userId");
    }

    @Test
    void tamperedToken_isRejected() {
        String token = jwtTokenService.generate(42L);
        // 篡改 payload 段最后一个字符，签名随之失效
        String[] parts = token.split("\\.");
        String payload = parts[1];
        char flipped = payload.charAt(payload.length() - 1) == 'A' ? 'B' : 'A';
        String tamperedPayload = payload.substring(0, payload.length() - 1) + flipped;
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];
        assertNull(jwtTokenService.parseAndGetUserId(tampered), "篡改过的 token 应校验失败返回 null");
    }

    @Test
    void garbageOrEmptyToken_isRejected() {
        assertNull(jwtTokenService.parseAndGetUserId(null));
        assertNull(jwtTokenService.parseAndGetUserId("  "));
        assertNull(jwtTokenService.parseAndGetUserId("not.a.jwt"));
        assertNull(jwtTokenService.parseAndGetUserId("a.b.c"));
    }

    @Test
    void bcryptHashAndCheckpw_roundTrip() {
        String raw = "123456";
        String hash = BCrypt.hashpw(raw, BCrypt.gensalt());
        assertTrue(BCrypt.checkpw(raw, hash), "正确密码应校验通过");
        assertFalse(BCrypt.checkpw("wrong", hash), "错误密码应校验失败");
        assertTrue(hash.startsWith("$2"), "BCrypt 哈希应以 $2 开头");
    }

    @Test
    void knownHashOf123456_validates() {
        // 该哈希即 schema.sql 中测试用户的默认值（明文 123456）
        String known = "$2a$10$43zntPUfCGS3fihhvF9VAew0XnfnXlUU0Xz8d3k0xCdm9IutfyPjC";
        assertTrue(BCrypt.checkpw("123456", known), "预置测试用户哈希应对应明文 123456");
    }
}
