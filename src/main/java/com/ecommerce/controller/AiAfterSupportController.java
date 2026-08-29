package com.ecommerce.controller;

import com.ecommerce.common.Result;
import com.ecommerce.dto.ai.AiAfterSupportDTO;
import com.ecommerce.service.AiAfterSupportService;
import com.ecommerce.vo.ai.AiAnalysisResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * AI售后智能分析 Controller
 */
@Tag(name = "AI售后分析模块", description = "调用DeepSeek大模型自动分析售后反馈：问题分类、情绪判断、建议处理方案，含降级+限流+超时保护")
@Validated
@RestController
@RequestMapping("/ai/after-support")
@RequiredArgsConstructor
public class AiAfterSupportController {

    private final AiAfterSupportService aiAfterSupportService;

    @Operation(
            summary = "智能售后分析",
            description = """
                    流程：
                    1. 根据订单ID查询数据库，获取商品名称/下单时间/支付金额/订单状态；
                    2. 组装Prompt（订单业务数据 + 用户反馈）调用DeepSeek API；
                    3. 大模型返回结构化JSON（问题分类、情绪、建议操作）；
                    4. 解析结果并存入ai_after_support表，返回给前端。
                    
                    保护机制：
                    - 超时控制（30秒）；
                    - 调用失败降级：返回"待人工审核"，不影响接口整体可用性；
                    - 简单限流（默认每用户每分钟10次），防止频繁调用大模型。
                    """
    )
    @PostMapping("/analyze")
    public Result<AiAnalysisResultVO> analyze(@RequestBody @Valid AiAfterSupportDTO dto) {
        AiAnalysisResultVO vo = aiAfterSupportService.analyze(dto);
        return Result.success(vo);
    }
}
