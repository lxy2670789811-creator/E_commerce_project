package com.ecommerce.common;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单号生成器单元测试：
 * 重点验证"同一秒并发生成也不会重复"——回归修复前 substring 截断雪花ID导致撞唯一索引的问题。
 */
class OrderNoGeneratorTest {

    private final OrderNoGenerator generator = new OrderNoGenerator();

    @Test
    void generatedOrderNoHasExpectedFormat() {
        String orderNo = generator.generate();
        assertTrue(orderNo.matches("ORD\\d{33}"),
                "订单号格式应为 ORD + 33位数字（14位秒级时间戳 + 19位完整雪花ID），实际：" + orderNo);
        assertEquals(36, orderNo.length(), "订单号长度应为 3 + 33 = 36");
    }

    @Test
    void generatedOrderNosAreUniqueInSequence() {
        int count = 50_000;
        Set<String> seen = ConcurrentHashMap.newKeySet();
        IntStream.range(0, count).forEach(i -> seen.add(generator.generate()));
        assertEquals(count, seen.size(), "连续生成 " + count + " 个订单号不应重复");
    }

    @Test
    void generatedOrderNosAreUniqueUnderConcurrency() throws Exception {
        int threads = 8;
        int perThread = 5_000;
        Set<String> seen = ConcurrentHashMap.newKeySet();
        List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() ->
                    IntStream.range(0, perThread).forEach(i -> seen.add(generator.generate())));
            pool.add(th);
            th.start();
        }
        for (Thread th : pool) {
            th.join();
        }
        assertEquals(threads * perThread, seen.size(), "并发生成的订单号不应重复");
    }
}