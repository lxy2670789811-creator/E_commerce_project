package com.ecommerce.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 极简 JWT 签发/校验工具（零第三方依赖，用 JDK javax.crypto 实现 HMAC-SHA256 签名）
 *
 * <p>为什么不用 Spring Security / jjwt：本项目为演示/面试导向，不引 Spring Security 可完全绕开其
 * 庞大的配置体系，也不影响现有 @SentinelResource、@Transactional、Redisson 等注解的生效；
 * 且手写能让你把 JWT 的"三段式"结构（header.payload.signature）讲得清清楚楚。</p>
 *
 * <p>JWT 结构（三段，点分隔，Base64URL 编码）：
 *   1. Header    {"alg":"HS256","typ":"JWT"}
 *   2. Payload   {"sub":"<userId>","iat":<签发时间>,"exp":<过期时间>}
 *   3. Signature HMACSHA256(base64(header)+"."+base64(payload), secret) 再 Base64URL
 * 服务端持有 secret，可校验第三段是否被篡改（防伪）；payload 未加密，仅签名。
 * 因此严禁把密码等敏感信息放进 payload——只放 userId 这类公开标识。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenService {

    private static final String HMAC_ALGO = "HmacSHA256";

    private final JwtProperties jwtProperties;

    private ObjectMapper objectMapper;

    @PostConstruct
    void init() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 签发 token
     *
     * @param userId 用户ID（写入 sub）
     * @return JWT 字符串
     */
    public String generate(Long userId) {
        long now = System.currentTimeMillis() / 1000;
        try {
            // header
            Map<String, Object> header = new HashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");
            String headerB64 = base64Url(objectMapper.writeValueAsBytes(header));

            // payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("sub", String.valueOf(userId));
            payload.put("iat", now);
            payload.put("exp", now + jwtProperties.getExpireSeconds());
            String payloadB64 = base64Url(objectMapper.writeValueAsBytes(payload));

            String signingInput = headerB64 + "." + payloadB64;
            String signature = sign(signingInput);

            return signingInput + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签发失败", e);
        }
    }

    /**
     * 解析并校验 token。
     *
     * @param token JWT 字符串
     * @return 用户ID；token 非法/过期/签名不符时返回 null
     */
    public Long parseAndGetUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            String headerB64 = parts[0];
            String payloadB64 = parts[1];
            String signature = parts[2];

            // 1. 重算签名比对，防篡改
            String expected = sign(headerB64 + "." + payloadB64);
            if (!constantTimeEquals(expected, signature)) {
                return null;
            }

            // 2. 解析 payload，校验过期
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(payloadB64), Map.class);
            Object sub = payload.get("sub");
            if (sub == null) {
                return null;
            }
            // 过期校验：exp 必须 > 当前时间
            Object expObj = payload.get("exp");
            long now = System.currentTimeMillis() / 1000;
            if (expObj == null || ((Number) expObj).longValue() < now) {
                return null;
            }
            return Long.valueOf(String.valueOf(sub));
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(
                    jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            return base64Url(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
