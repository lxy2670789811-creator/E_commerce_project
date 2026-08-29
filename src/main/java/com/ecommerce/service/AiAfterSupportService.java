package com.ecommerce.service;

import com.ecommerce.dto.ai.AiAfterSupportDTO;
import com.ecommerce.vo.ai.AiAnalysisResultVO;

/**
 * AI售后智能分析 Service 接口
 */
public interface AiAfterSupportService {

    /**
     * 执行AI售后智能分析
     * - 查询订单信息
     * - 组装 Prompt（订单业务数据+用户反馈）
     * - 调用 DeepSeek API（含超时控制、降级）
     * - 限流保护
     * - 记录写入 ai_after_support 表
     * - AI失败时降级为"待人工审核"
     */
    AiAnalysisResultVO analyze(AiAfterSupportDTO dto);
}
