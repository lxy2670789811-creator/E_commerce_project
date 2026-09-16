package com.ecommerce.config;

import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.redisson.config.Config;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Redis 配置类
 */
@Configuration
public class RedisConfig {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        // 使用 GenericJackson2JsonRedisSerializer
        GenericJackson2JsonRedisSerializer jsonSerializer = genericJackson2JsonRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    private GenericJackson2JsonRedisSerializer genericJackson2JsonRedisSerializer() {
        ObjectMapper objectMapper = createObjectMapper();
        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }

    /**
     * 把"空密码"归一化成 {@code null}，修掉 redisson-spring-boot-starter 的一个坑。
     *
     * <h3>问题现象</h3>
     * prod profile 下应用**启动即失败**，报错链路有 7 层那么深，真正的根因藏在最里面：
     * <pre>
     * productController
     *  → productServiceImpl
     *   → redisTemplate
     *    → redissonConnectionFactory
     *     → redisson  (Failed to instantiate RedissonClient)
     *      → RedisConnectionException: Unable to connect to Redis server: redis...:6379
     *       → RedisException: ERR Client sent AUTH, but no password is set
     * </pre>
     *
     * <h3>根因</h3>
     * starter 把 {@code spring.data.redis.password} 原样塞给 Redisson，**没有任何空值守卫**：
     * 见 redisson-spring-boot-starter 3.27.2 的 {@code RedissonAutoConfiguration#redisson()}
     * <pre>
     * String password = redisProperties.getPassword();        // 第 147 行
     * ...
     * SingleServerConfig c = config.useSingleServer()
     *         .setAddress(singleAddr)
     *         .setDatabase(database)
     *         .setUsername(username)
     *         .setPassword(password)                          // 第 315 行：无守卫
     * </pre>
     * 而 Redisson 判断"要不要发 AUTH"的依据是 <b>password 是否为 null</b>，不是"是否为空串"。
     * 于是空串会被当成"密码是空字符串"，乖乖发一条 {@code AUTH ""}，对**没配 requirepass 的 Redis**
     * 被服务端顶回来：{@code ERR Client sent AUTH, but no password is set}。
     *
     * <h3>为什么 dev 能跑、prod 跑不起来（这个差异才是关键）</h3>
     * {@code application-dev.yml} 里**根本没有 password 这一行** → 绑定结果是 {@code null} → 不发 AUTH，正常。
     * {@code application-prod.yml} 里写的是 {@code password: ${REDIS_PASSWORD:}}，环境变量缺失时
     * 占位符解析成**空串**（注意：是空串，不是 null）→ 触发上面那条路径。
     * 即"同一个 Redis，dev 连得上、prod 连不上"，跟网络、白名单、防火墙全无关系。
     *
     * ⚠️ 顺带说明为什么不在 YAML 里把它改成"没配就没有"（例如删掉 password 一行）：
     *    那样 prod 会**静默**退化成"无密码连接"。而 prod profile 的既定原则是
     *    "敏感信息必须显式注入"（见该文件开头注释），删掉占位符等于把这条原则破掉了。
     *    在代码里做"空串→null"的归一化，既保住了 {@code REDIS_PASSWORD} 这个注入入口，
     *    又让"确实没密码"这种情况有一个明确的、可被单测覆盖的表达。
     *
     * @param password 绑定自 {@code spring.data.redis.password}；未配置或为空串时为 {@code ""}
     */
    @Bean
    public RedissonAutoConfigurationCustomizer redisBlankPasswordNormalizer(
            @Value("${spring.data.redis.password:}") String password) {
        return config -> {
            // 配了真密码就什么都不做，交回 starter 原本的逻辑。
            if (StringUtils.hasText(password)) {
                return;
            }
            clearPassword(config);
        };
    }

    /**
     * 按 Redisson 当前所处的部署模式清掉密码。
     *
     * ⚠️ 必须先判断模式、再取对应配置，**不能**直接 {@code config.useSingleServer().setPassword(null)}：
     *    Redisson 的 {@code Config} 三种部署模式是互斥的，混用会**抛异常**而不是静默切换 ——
     *    见 3.27.2 的 {@code Config#useSingleServer()} 会依次调 checkClusterServersConfig() /
     *    checkMasterSlaveServersConfig() / checkSentinelServersConfig() / checkReplicatedServersConfig()，
     *    而 {@code checkClusterServersConfig()} 的实现是
     *    {@code if (clusterServersConfig != null) throw new IllegalStateException("cluster servers config already used!");}
     *    也就是说在集群配置上调 useSingleServer() 会直接炸掉启动。
     *
     * ⚠️ 刻意**不写 else 兜底**：模式判断用的是三个精确的 null 判定
     *    （{@code isSingleConfig()} = {@code singleServerConfig != null}，另两个同理，已读源码确认），
     *    若出现本项目未使用的模式（masterSlave / replicated），这里就不动它 ——
     *    宁可漏改也不能因为"猜它是单机"而把模式改坏。
     *    本项目的 starter 只走 单机 / 集群 / 哨兵 三选一，单机分支必然命中。
     */
    private void clearPassword(Config config) {
        if (config.isSingleConfig()) {
            config.useSingleServer().setPassword(null);
        } else if (config.isClusterConfig()) {
            config.useClusterServers().setPassword(null);
        } else if (config.isSentinelConfig()) {
            config.useSentinelServers().setPassword(null);
        }
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        // Java 8 时间模块
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(formatter));
        javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(formatter));
        objectMapper.registerModule(javaTimeModule);

        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        // 【关键】必须显式开启多态类型处理，否则缓存读不回来。
        // GenericJackson2JsonRedisSerializer 只有在使用"无参构造器"时才会自行注册 @class 类型信息；
        // 一旦传入自定义 ObjectMapper（本类就是这种做法），它直接沿用该 mapper，不再自动开启。
        // 结果：序列化出的 JSON 不带 @class，反序列化时只能得到 LinkedHashMap，
        // 业务侧 (ProductVO) cached 抛 ClassCastException —— 又被"缓存异常降级查DB"的 catch 悄悄吞掉，
        // 表现为接口一切正常、但缓存 100% 未命中（每次都打数据库）。
        // 这里用 NON_FINAL 策略：String 等 final 类型不写 @class（空值缓存标记仍按纯字符串往返），
        // 普通 POJO 带上 @class，读回真实类型。
        objectMapper.activateDefaultTyping(
                objectMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        return objectMapper;
    }
}