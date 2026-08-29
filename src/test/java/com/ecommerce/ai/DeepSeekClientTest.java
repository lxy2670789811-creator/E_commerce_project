package com.ecommerce.ai;

import com.ecommerce.config.BusinessDynamicConfig;
import com.ecommerce.feign.DeepSeekFeign;
import com.ecommerce.feign.dto.DeepSeekChatRequest;
import com.ecommerce.feign.dto.DeepSeekChatResponse;
import com.ecommerce.vo.ai.AiAnalysisResultVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * DeepSeek 客户端单元测试：解析成功、空响应重试后成功、全部失败返回 null（降级）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeepSeekClientTest {

    private static final String VALID_JSON =
            "{\"problem_type\":\"质量问题\",\"emotion\":\"负面\",\"suggestion\":\"直接退款\"}";

    @Mock
    private DeepSeekFeign deepSeekFeign;
    @Mock
    private BusinessDynamicConfig businessDynamicConfig;

    private DeepSeekClient deepSeekClient;

    @BeforeEach
    void setUp() {
        deepSeekClient = new DeepSeekClient(deepSeekFeign, new ObjectMapper(), businessDynamicConfig);
        ReflectionTestUtils.setField(deepSeekClient, "model", "deepseek-chat");
        when(businessDynamicConfig.getAiApiRetryTimes()).thenReturn(1);
    }

    private DeepSeekChatResponse responseWithContent(String content) {
        return DeepSeekChatResponse.builder()
                .choices(List.of(DeepSeekChatResponse.Choice.builder()
                        .message(DeepSeekChatResponse.Message.builder()
                                .role("assistant")
                                .content(content)
                                .build())
                        .build()))
                .build();
    }

    @Test
    void analyzeSuccess_parsesStructuredResult() {
        when(deepSeekFeign.analyzeChat(any(DeepSeekChatRequest.class))).thenReturn(responseWithContent(VALID_JSON));

        AiAnalysisResultVO.AiStructuredResult result =
                deepSeekClient.analyzeAfterSupport("订单信息", "收到商品破损，要退款");

        assertThat(result).isNotNull();
        assertThat(result.getProblemType()).isEqualTo("质量问题");
        assertThat(result.getEmotion()).isEqualTo("负面");
        assertThat(result.getSuggestion()).isEqualTo("直接退款");
    }

    @Test
    void analyze_retriesAfterEmptyResponse_thenSucceeds() {
        when(deepSeekFeign.analyzeChat(any(DeepSeekChatRequest.class)))
                .thenReturn(null).thenReturn(responseWithContent(VALID_JSON));

        AiAnalysisResultVO.AiStructuredResult result =
                deepSeekClient.analyzeAfterSupport("订单信息", "物流太慢了");

        assertThat(result).isNotNull();
        assertThat(result.getProblemType()).isEqualTo("质量问题");
    }

    @Test
    void analyze_allFailuresReturnNull_degrade() {
        when(deepSeekFeign.analyzeChat(any(DeepSeekChatRequest.class))).thenReturn(null);

        AiAnalysisResultVO.AiStructuredResult result =
                deepSeekClient.analyzeAfterSupport("订单信息", "咨询发货时间");

        assertThat(result).isNull();
    }
}