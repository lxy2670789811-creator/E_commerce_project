package com.ecommerce.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁死 {@link JwtProperties} 的前缀 {@code ecommerce.jwt} 必须与 application.yml 的层级一致。
 *
 * <h3>为什么值得单独写测试</h3>
 * {@code @ConfigurationProperties} 绑不到属性时<b>不会报错</b>，而是安静地保留字段的代码默认值。
 * 于是会出现最难排查的一类配置问题：<b>「配置写了、环境变量也注入了，实际却全在用硬编码默认值」</b>，
 * 从启动日志到运行现象完全看不出来。
 *
 * <h3>历史事故</h3>
 * {@code application.yml} 里的 {@code jwt:} 曾被缩进在 {@code ecommerce.business} 之下，
 * 即绑定路径成了 {@code ecommerce.business.jwt}。而 {@code BusinessDynamicConfig} 并没有 jwt 字段、
 * {@link JwtProperties} 又只认 {@code ecommerce.jwt} —— <b>这段配置没有任何读取方</b>，
 * 导致 {@code JWT_SECRET} 环境变量从未生效，一直使用仓库中的硬编码密钥。
 *
 * <p>本测试直接用 {@link Binder} 读 application.yml，不启动 Spring 上下文，
 * 因此不依赖 MySQL / Redis，可稳定运行。
 */
class JwtPropertiesBindingTest {

    /** {@link JwtProperties#getSecret()} 期望的属性前缀 */
    private static final String CORRECT_PREFIX = "ecommerce.jwt";

    /** 曾经的错误位置：business 下没有任何类读 jwt */
    private static final String WRONG_PREFIX = "ecommerce.business.jwt";

    private static Binder binderFromApplicationYml() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        // 用 StandardEnvironment 承载属性源：Binder.get(Environment) 会带上占位符解析器，
        // 因此 ${JWT_SECRET:某个默认值} 这类写法能按真实行为解析（而不是留下字面量）。
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(source -> environment.getPropertySources().addLast(source));
        return Binder.get(environment);
    }

    @Test
    @DisplayName("jwt 配置必须能从 ecommerce.jwt 取到，否则 JwtProperties 读的是硬编码默认值")
    void jwtConfig_mustBeReadableUnderEcommerceJwtPrefix() throws IOException {
        Binder binder = binderFromApplicationYml();

        assertThat(binder.bind(CORRECT_PREFIX + ".secret", String.class).isBound())
                .as("application.yml 必须能从 %s.secret 取到值（JwtProperties 的 @ConfigurationProperties 前缀）。"
                        + "取不到说明 jwt: 段缩进错位，JWT_SECRET 环境变量注入不会生效。",
                        CORRECT_PREFIX)
                .isTrue();
    }

    @Test
    @DisplayName("jwt 配置不得残留在 ecommerce.business 之下（那里没有任何读取方）")
    void jwtConfig_mustNotRemainUnderBusinessPrefix() throws IOException {
        Binder binder = binderFromApplicationYml();

        assertThat(binder.bind(WRONG_PREFIX + ".secret", String.class).isBound())
                .as("ecommerce.business.jwt 是死配置：BusinessDynamicConfig 无 jwt 字段，"
                        + "JwtProperties 又只认 %s。存在即说明缩进错位未修复。", CORRECT_PREFIX)
                .isFalse();
    }
}
