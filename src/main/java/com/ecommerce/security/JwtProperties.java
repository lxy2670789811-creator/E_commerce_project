package com.ecommerce.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 认证相关配置（前缀 ecommerce.jwt，见 application.yml）
 *
 * <p>secret：签名密钥，必须足够长（HMAC-SHA256 建议 ≥ 32 字节）。生产环境务必通过环境变量覆盖，
 * 禁止把生产密钥硬编码进仓库。</p>
 *
 * <p>expireSeconds：token 有效期（秒），默认 86400（1 天）。</p>
 *
 * <p>required：鉴权硬开关。
 * true  = 受保护路径必须携带有效 token，缺失/失效直接 401（用于生产全量接入）；
 * false = 兼容模式（当前默认）：请求带 token 则严格校验，不带则放行、沿用请求参数里的 userId
 *        （过渡期保证现有前端/测试不破坏）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ecommerce.jwt")
public class JwtProperties {

    /** JWT 签名密钥（HMAC-SHA256）；生产必须用环境变量覆盖 */
    private String secret = "ecommerce-order-demo-secret-key-please-change-in-prod-0123456789";

    /** token 有效期（秒），默认 86400 = 1 天 */
    private long expireSeconds = 86400L;

    /** 鉴权硬开关：true 强制受保护路径校验；false 兼容模式（缺 token 放行，用 userId 参数） */
    private boolean required = false;

    /** 是否允许明文密码自动升级为 BCrypt（兼容存量数据，生产建议开启后在稳定后移除） */
    private boolean allowPlainTextLogin = true;
}
