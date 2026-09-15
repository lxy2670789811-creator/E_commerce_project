package com.ecommerce.feign;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.ecommerce.config.BusinessDynamicConfig;
import com.ecommerce.config.SentinelConfig;
import com.ecommerce.feign.dto.DeepSeekChatRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Feign 熔断资源名一致性测试
 *
 * 背景（真实踩过的坑）：
 * SCA 的 SentinelInvocationHandler 拼资源名的方式是「HTTP方法:url+path」，
 * 即 POST:https://api.deepseek.com/v1/chat/completions，
 * 而非常见资料里写的「类名#方法名」（DeepSeekFeign#analyzeChat）。
 *
 * 按后者配规则不会报错、规则照常加载，但**永远不命中** —— 熔断静默失效，
 * 而且 fallback 降级仍然正常，让人误以为熔断已生效。这类缺陷从日志和现象都看不出来。
 *
 * 本测试把「注解声明的 url/path」与「Sentinel 规则用的资源名」锁成同一来源，
 * 任何一方被改歪都会立刻红灯。改动 Feign 相关配置后请务必跑它。
 */
class DeepSeekFeignResourceNameTest {

    /** SCA 在构造 SentinelInvocationHandler 时需要的日志目录，避免污染用户目录 */
    @BeforeAll
    static void redirectSentinelLog() {
        if (System.getProperty("csp.sentinel.log.dir") == null) {
            System.setProperty("csp.sentinel.log.dir",
                    Paths.get(System.getProperty("java.io.tmpdir"), "sentinel-logs-test").toString());
        }
    }

    @Test
    void feignResourceName_isHttpMethodColonUrlPlusPath() {
        // 锁定字面量：这是 SCA 实际埋点用的资源名，改了它熔断就失效
        assertThat(DeepSeekApiConstants.FEIGN_RESOURCE_NAME)
                .isEqualTo("POST:https://api.deepseek.com/v1/chat/completions");
    }

    @Test
    void feignClientAnnotation_takesUrlAndPathFromTheSameConstants() throws Exception {
        FeignClient feignClient = DeepSeekFeign.class.getAnnotation(FeignClient.class);
        assertThat(feignClient).isNotNull();
        assertThat(feignClient.url()).isEqualTo(DeepSeekApiConstants.BASE_URL);

        Method method = DeepSeekFeign.class.getMethod("analyzeChat", DeepSeekChatRequest.class);
        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        assertThat(postMapping).isNotNull();
        assertThat(postMapping.value()).containsExactly(DeepSeekApiConstants.CHAT_COMPLETIONS_PATH);
    }

    @Test
    void sentinelConfig_registersFeignRuleUnderThatExactResourceName() {
        // BusinessDynamicConfig 只提供阈值，对资源名无影响，mock 返回默认 0 即可
        SentinelConfig sentinelConfig = new SentinelConfig(mock(BusinessDynamicConfig.class));
        sentinelConfig.initSentinelRules();

        Set<String> resources = DegradeRuleManager.getRules().stream()
                .map(DegradeRule::getResource)
                .collect(Collectors.toSet());

        assertThat(resources)
                .contains(DeepSeekApiConstants.FEIGN_RESOURCE_NAME)
                .doesNotContain("DeepSeekFeign#analyzeChat");
    }

    @Test
    void flowRules_areRegisteredUnderResourceNamesMatchingSentinelResourceAnnotation() {
        SentinelConfig sentinelConfig = new SentinelConfig(mock(BusinessDynamicConfig.class));
        sentinelConfig.initSentinelRules();

        List<String> flowResources = com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager
                .getRules().stream()
                .map(com.alibaba.csp.sentinel.slots.block.flow.FlowRule::getResource)
                .toList();

        // 这两个名字必须与 OrderServiceImpl 里 @SentinelResource 的 value 一致
        assertThat(flowResources).contains("createOrder", "cancelOrder");
    }
}
