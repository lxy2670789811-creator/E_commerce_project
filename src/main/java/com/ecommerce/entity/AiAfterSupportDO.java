package com.ecommerce.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI售后分析记录数据库实体（DO）
 * 注意：该表不使用逻辑删除、不继承 BaseDO，保留独立的创建时间字段
 */
@Data
@TableName("ai_after_support")
public class AiAfterSupportDO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 订单ID
     */
    private Long orderId;

    /**
     * 订单号
     */
    private String orderNo;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户反馈文本
     */
    private String userInput;

    /**
     * 大模型返回结果（JSON字符串存储）
     */
    private String aiResult;

    /**
     * 问题分类：质量问题/物流问题/咨询/其他
     */
    private String problemType;

    /**
     * 情绪：负面/中性/正面
     */
    private String emotion;

    /**
     * 建议处理方案
     */
    private String suggestion;

    /**
     * AI处理状态：1-成功 0-失败待人工
     */
    private Integer aiStatus;

    /**
     * AI失败原因
     */
    private String failReason;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
