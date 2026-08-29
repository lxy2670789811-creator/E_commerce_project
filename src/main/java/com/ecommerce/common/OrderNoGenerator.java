package com.ecommerce.common;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.IdUtil;
import org.springframework.stereotype.Component;

/**
 * 订单号生成器
 *
 * 规则：ORD + yyyyMMddHHmmss（秒级时间戳，便于阅读与排查）+ 完整雪花ID（保证全局唯一）
 *
 * 注意：不能对雪花ID做 substring 截断。雪花ID的十进制高位由时间戳主导，
 * 同一秒内生成的所有ID前6位完全相同，截断会与秒级时间戳前缀叠加，导致
 * 同一秒内多个订单生成相同订单号，撞 uk_order_no 唯一索引。
 */
@Component
public class OrderNoGenerator {

    /**
     * 生成全局唯一订单号
     */
    public String generate() {
        return "ORD" + DateUtil.format(DateUtil.date(), "yyyyMMddHHmmss") + IdUtil.getSnowflakeNextIdStr();
    }
}