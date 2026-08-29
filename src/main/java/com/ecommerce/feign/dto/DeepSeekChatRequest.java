package com.ecommerce.feign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek Chat Completions API 请求 DTO
 * 兼容 OpenAI 官方规范
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeepSeekChatRequest {

    /** 模型名称，如 deepseek-chat */
    @JsonProperty("model")
    private String model;

    /** 温度：0~2，越低越确定 */
    @JsonProperty("temperature")
    private Double temperature;

    /** 消息列表：[{"role":"system","content":"..."},{"role":"user","content":"..."}] */
    @JsonProperty("messages")
    private List<Message> messages;

    /** 强制输出 JSON：{"type":"json_object"} */
    @JsonProperty("response_format")
    private Map<String, String> responseFormat;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        @JsonProperty("role")
        private String role;      // system / user / assistant
        @JsonProperty("content")
        private String content;
    }
}
