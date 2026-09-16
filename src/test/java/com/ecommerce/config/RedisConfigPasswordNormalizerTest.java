package com.ecommerce.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 空密码归一化测试
 *
 * 背景（在 kind 集群里真实踩到的坑，2026-09-16）：
 * prod profile 下应用**启动直接失败**，报错链路有 7 层，根因藏在最里面——
 * {@code ERR Client sent AUTH, but no password is set}。
 *
 * 由 starter 源码（redisson-spring-boot-starter 3.27.2 的 RedissonAutoConfiguration#redisson()）
 * 可确认它把 {@code spring.data.redis.password} 原样交给 Redisson，**没有空值守卫**：
 * <pre>
 * String password = redisProperties.getPassword();   // 第 147 行
 * ...
 * .setPassword(password)                             // 第 315 行
 * </pre>
 * 而 Redisson 判"要不要发 AUTH"看的是 password **是否为 null**，不是"是否为空串"，
 * 于是空串会发一条 {@code AUTH ""}，被没配 requirepass 的 Redis 顶回来。
 *
 * 为什么 dev 好使、prod 不好使：dev 里根本没写 password 这一行（绑定结果为 null），
 * prod 里写的是 {@code ${REDIS_PASSWORD:}}（环境变量缺失时解析成**空串**）。
 *
 * ⚠️ 第 4 个用例（真密码不被清掉）是防"修过头"的护栏：
 *    归一化逻辑一旦写成"无条件清空密码"，本地能跑通、线上的带密码 Redis 会**全部连不上**，
 *    而且同样会以 RedisConnectionException 的形式出现，表现和本 bug 一模一样，很容易误判。
 */
class RedisConfigPasswordNormalizerTest {

    private final RedisConfig redisConfig = new RedisConfig();

    private RedissonAutoConfigurationCustomizer normalizer(String password) {
        return redisConfig.redisBlankPasswordNormalizer(password);
    }

    @Test
    void blankSingleServerPassword_isClearedToNull() {
        // 复现线上故障的最小现场：单机模式 + 空串密码
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setPassword("");

        normalizer("").customize(config);

        assertThat(config.useSingleServer().getPassword()).isNull();
    }

    @Test
    void whitespaceOnlyPassword_isTreatedAsBlank() {
        // 用 hasText 而不是 isBlank/equals("")：误填一个空格也不该把启动打挂
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("   ");

        normalizer("   ").customize(config);

        assertThat(config.useSingleServer().getPassword()).isNull();
    }

    @Test
    void nullPassword_staysNull() {
        // 幂等：dev profile 就是这条路（无 password 配置项），customizer 必须无害
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");

        normalizer(null).customize(config);

        assertThat(config.useSingleServer().getPassword()).isNull();
    }

    @Test
    void realPassword_isLeftUntouched() {
        // 防"修过头"：配了真密码就必须原样保留，否则线上带密码的 Redis 全连不上
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("s3cret");

        normalizer("s3cret").customize(config);

        assertThat(config.useSingleServer().getPassword()).isEqualTo("s3cret");
    }

    @Test
    void clusterMode_clearsPasswordWithoutSwitchingMode() {
        // 集群模式下若误调 useSingleServer()，Redisson 会抛 IllegalStateException
        //（"cluster servers config already used!"），这个用例就是拦这个的
        Config config = new Config();
        config.useClusterServers()
                .addNodeAddress("redis://127.0.0.1:7000")
                .setPassword("");

        normalizer("").customize(config);

        assertThat(config.isClusterConfig()).isTrue();
        assertThat(config.isSingleConfig()).isFalse();
        assertThat(config.useClusterServers().getPassword()).isNull();
        // 节点地址不能被改坏
        assertThat(config.useClusterServers().getNodeAddresses()).contains("redis://127.0.0.1:7000");
    }

    @Test
    void sentinelMode_clearsPasswordWithoutSwitchingMode() {
        Config config = new Config();
        config.useSentinelServers()
                .setMasterName("mymaster")
                .addSentinelAddress("redis://127.0.0.1:26379")
                .setPassword("");

        normalizer("").customize(config);

        assertThat(config.isSentinelConfig()).isTrue();
        assertThat(config.isSingleConfig()).isFalse();
        assertThat(config.useSentinelServers().getPassword()).isNull();
    }
}
