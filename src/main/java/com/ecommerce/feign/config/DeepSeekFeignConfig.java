package com.ecommerce.feign.config;

import feign.Client;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.codec.ErrorDecoder;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek Feign 客户端专用配置
 *
 * 工程化改造要点：
 * 1. HTTP 客户端切换为 OkHttpClient（比默认的 HttpURLConnection 性能更好，支持连接池）
 * 2. 统一连接池配置（连接超时 / 读取超时 / 空闲连接保活）
 * 3. 自定义 ErrorDecoder：识别 HTTP 错误码并记录日志
 * 4. 自动鉴权 Header（Bearer Token）
 * 5. 追踪 ID 注入：便于日志排查
 *
 * 重试策略：由上层 DeepSeekClient + Spring Retry 实现（非 Feign 层重试），
 * 这样可以控制重试条件和间隔，避免 HTTP 非幂等接口被重复调用。
 */
@Slf4j
@Configuration
public class DeepSeekFeignConfig {

    @Value("${deepseek.api.key}")
    private String apiKey;

    @Value("${feign.client.config.deepseekClient.connectTimeout:5000}")
    private int connectTimeoutMs;

    @Value("${feign.client.config.deepseekClient.readTimeout:30000}")
    private int readTimeoutMs;

    /**
     * OkHttp 客户端 Bean（Feign 使用 OkHttp 作为底层 HTTP 客户端）
     *
     * 优势：
     * - 连接池复用（避免频繁 TCP 握手）
     * - 更成熟的超时管理
     * - 更好的性能和稳定性
     */
    @Bean
    public OkHttpClient okHttpClient() {
        ConnectionPool connectionPool = new ConnectionPool(5, 5, TimeUnit.MINUTES);
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .connectionPool(connectionPool)
                .retryOnConnectionFailure(false) // 禁止 OkHttp 层自动重试（由上层控制）
                .build();
    }

    /**
     * 让 Feign 使用 OkHttp 作为底层 HTTP 客户端
     * 使用 feign-okhttp 适配器将 okhttp3.OkHttpClient 包装为 feign.Client
     */
    @Bean
    public Client feignClient(OkHttpClient okHttpClient) {
        return new feign.okhttp.OkHttpClient(okHttpClient);
    }

    /**
     * Feign 请求拦截器：统一注入鉴权 Header + 追踪 ID
     */
    @Bean
    public RequestInterceptor deepSeekAuthInterceptor() {
        return new RequestInterceptor() {
            @Override
            public void apply(RequestTemplate template) {
                template.header("Authorization", "Bearer " + apiKey);
                template.header("Content-Type", "application/json");
                String traceId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                template.header("X-Trace-Id", traceId);
            }
        };
    }

    /**
     * 自定义 ErrorDecoder：将 HTTP 错误码转为 Feign 异常
     * 记录详细的错误日志，便于排查 AI API 调用问题
     */
    @Bean
    public ErrorDecoder deepSeekErrorDecoder() {
        return new ErrorDecoder.Default() {
            @Override
            public Exception decode(String methodKey, feign.Response response) {
                int status = response.status();
                String body = "";
                try {
                    if (response.body() != null) {
                        try (java.io.InputStream is = response.body().asInputStream()) {
                            body = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                } catch (IOException e) {
                    // ignore
                }

                log.warn("【Feign错误】DeepSeek API 响应异常：method={}, status={}, body={}",
                        methodKey, status, truncate(body, 200));

                return super.decode(methodKey, response);
            }
        };
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
