package com.ecommerce.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关
 *
 * <p>启用 Spring {@code @Scheduled} 定时调度能力。目前由
 * {@code com.ecommerce.mq.OrderTimeoutScanScheduler} 使用（订单超时关单的定时扫描兜底），
 * 后续如有其它定时任务可复用本开关。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
