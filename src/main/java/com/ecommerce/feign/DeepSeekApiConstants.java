package com.ecommerce.feign;

/**
 * DeepSeek API 常量定义
 *
 * <p>存在的唯一理由：让「Feign 客户端声明的 URL」与「Sentinel 熔断规则用的资源名」
 * 共用同一个来源。二者一旦各写各的，就会静默漂移 —— 规则照常加载，
 * 但永远不会命中，熔断形同虚设（本项目曾真实踩过，见 FEIGN_RESOURCE_NAME 注释）。
 *
 * <p>⚠️ {@code @FeignClient} / {@code @PostMapping} 的注解属性要求编译期常量，
 * 无法用 {@code @Value} 从配置中心注入，所以这里必须写死。
 * application.yml 中的 {@code deepseek.api.url} 仅作运维参考，代码不读取它。
 */
public final class DeepSeekApiConstants {

    private DeepSeekApiConstants() {
    }

    /** DeepSeek API 基础地址（不含路径） */
    public static final String BASE_URL = "https://api.deepseek.com";

    /** Chat Completions 接口路径 */
    public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /** 接口使用的 HTTP 方法（大写） */
    private static final String HTTP_METHOD = "POST";

    /**
     * Sentinel 对 Feign 调用埋点所使用的资源名。
     *
     * <p>由 SCA 的 {@code SentinelInvocationHandler} 拼装，规则固定为：
     * <pre>
     *   HTTP方法.toUpperCase() + ":" + @FeignClient.url + @RequestMapping.path
     * </pre>
     * 即 {@code POST:https://api.deepseek.com/v1/chat/completions}。
     *
     * <p>⚠️ 它<b>不是</b> {@code DeepSeekFeign#analyzeChat}。
     * 后者是很多资料里写的形式（类名#方法名），SCA 并不这么生成 —— 按它配规则会永远不命中。
     */
    public static final String FEIGN_RESOURCE_NAME =
            HTTP_METHOD + ":" + BASE_URL + CHAT_COMPLETIONS_PATH;
}
