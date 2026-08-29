package com.ecommerce.vo.ai;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI售后分析结果 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI售后智能分析结果")
public class AiAnalysisResultVO {

    @Schema(description = "记录ID", example = "1")
    private Long id;

    @Schema(description = "订单ID", example = "1")
    private Long orderId;

    @Schema(description = "订单号", example = "ORD20240101000001")
    private String orderNo;

    @Schema(description = "用户输入", example = "收到商品破损，要退款")
    private String userInput;

    @Schema(description = "问题分类", example = "质量问题")
    private String problemType;

    @Schema(description = "情绪判断", example = "负面")
    private String emotion;

    @Schema(description = "建议处理方案", example = "直接退款")
    private String suggestion;

    @Schema(description = "AI处理状态：1-成功 0-待人工审核", example = "1")
    private Integer aiStatus;

    @Schema(description = "AI状态文本", example = "分析成功")
    private String aiStatusText;

    @Schema(description = "AI失败原因（失败时返回）")
    private String failReason;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /**
     * 大模型内部返回的结构化 JSON 对象
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiStructuredResult {
        @JsonProperty("problem_type")
        private String problemType;

        @JsonProperty("emotion")
        private String emotion;

        @JsonProperty("suggestion")
        private String suggestion;
    }
}
