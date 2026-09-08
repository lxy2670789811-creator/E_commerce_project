package com.ecommerce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.common.BusinessException;
import com.ecommerce.common.PageResult;
import com.ecommerce.common.ErrorCode;
import com.ecommerce.config.BusinessDynamicConfig;
import com.ecommerce.convert.ProductConvert;
import com.ecommerce.dto.product.ProductAddDTO;
import com.ecommerce.dto.product.ProductStatusDTO;
import com.ecommerce.dto.product.ProductUpdateDTO;
import com.ecommerce.entity.ProductDO;
import com.ecommerce.mapper.ProductMapper;
import com.ecommerce.service.ProductService;
import com.ecommerce.vo.product.ProductStockVO;
import com.ecommerce.vo.product.ProductVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 商品 Service 实现类
 *
 * 缓存策略说明（Cache-Aside 模式）：
 *   - 读：先查 Redis，miss 时查 DB 并回写缓存
 *   - 写：先更新 DB，再删除缓存（而非更新缓存）
 *   - 为什么"更新后删缓存"？
 *     1. 库存/商品信息的写入频率远低于读取频率，更新缓存增加复杂度但收益低
 *     2. 并发场景下，"更新DB→更新缓存"可能导致缓存被旧值覆盖
 *        （线程A更新DB→线程B读取DB旧值→线程A更新缓存→线程B更新缓存为旧值）
 *     3. "更新DB→删缓存"模式更安全：下次读取时自动从DB加载最新值
 *   - 缓存过期时间可通过 Nacos 动态配置（product-detail-expire-seconds）调整
 *   - 缓存穿透防护：DB 查不到时写入短 TTL 的空值标记（__NULL__），
 *     避免不存在的 / 已逻辑删除的商品被反复请求时，每个请求都穿透到数据库
 *   - 缓存雪崩防护：过期时间加随机抖动（base + random[0, jitter]），
 *     避免批量 key 在同一时刻失效、请求集体回源打爆数据库
 *   - 两个防护的 TTL 均可通过 Nacos 动态调整；置 0 即关闭对应防护（紧急降级开关）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, ProductDO> implements ProductService {

    private static final String CACHE_KEY_PREFIX = "ecommerce:product:detail:";

    /**
     * 空值缓存标记（缓存穿透防护）
     * 缓存中存这个字符串表示"该商品在 DB 中不存在（含已被逻辑删除）"，
     * 读到它直接快速失败、不再回源。
     * 用字符串而非 null，是因为 Redis 区分不了"key 不存在"和"value 是 null"。
     */
    private static final String NULL_MARKER = "__NULL__";

    private final ProductMapper productMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    /** Nacos 动态配置：商品详情缓存过期时间可动态调整 */
    private final BusinessDynamicConfig businessDynamicConfig;

    @Override
    public Long addProduct(ProductAddDTO dto) {
        ProductDO productDO = ProductConvert.INSTANCE.addDTOToDO(dto);
        this.save(productDO);
        log.info("新增商品成功：productId={}, name={}", productDO.getId(), productDO.getName());
        return productDO.getId();
    }

    @Override
    public void updateProduct(ProductUpdateDTO dto) {
        ProductDO exist = this.getById(dto.getId());
        if (exist == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        ProductDO productDO = ProductConvert.INSTANCE.updateDTOToDO(dto);
        this.updateById(productDO);
        // 更新商品后主动删除缓存（Cache-Aside：更新DB后删缓存，下次读自动回源）
        deleteProductCache(dto.getId());
        log.info("修改商品成功：productId={}, 已清除缓存", dto.getId());
    }

    @Override
    public void deleteProduct(Long id) {
        ProductDO exist = this.getById(id);
        if (exist == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        this.removeById(id);
        // 删除商品后主动删除缓存
        deleteProductCache(id);
        log.info("删除商品成功：productId={}, 已清除缓存", id);
    }

    @Override
    public void updateStatus(ProductStatusDTO dto) {
        ProductDO exist = this.getById(dto.getId());
        if (exist == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        exist.setStatus(dto.getStatus());
        this.updateById(exist);
        // 状态变更后删除缓存
        deleteProductCache(dto.getId());
        log.info("商品上下架成功：productId={}, status={}, 已清除缓存", dto.getId(), dto.getStatus());
    }

    @Override
    public ProductVO getProductDetail(Long id) {
        String cacheKey = buildCacheKey(id);
        // 1. 先查 Redis 缓存
        Object cached = null;
        try {
            cached = redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.warn("读取 Redis 缓存异常，降级查DB：productId={}", id, e);
        }
        if (cached != null) {
            // 1.1 命中"空值缓存"：说明该商品不存在或已被逻辑删除，快速失败，不再打 DB（防穿透）
            if (isNullMarker(cached)) {
                log.debug("命中空值缓存，快速失败（防穿透）：productId={}", id);
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            log.debug("命中商品详情缓存：productId={}", id);
            return (ProductVO) cached;
        }

        // 2. 缓存未命中，查 DB
        ProductDO productDO = this.getById(id);
        if (productDO == null) {
            // 缓存穿透防护：查不到也要写一份短 TTL 的空值缓存。
            // 否则不存在的 / 已逻辑删除的 productId 被高频请求时，每次都会打到数据库。
            cacheNullResult(cacheKey, id);
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        ProductVO vo = ProductConvert.INSTANCE.doToVO(productDO);

        // 3. 回写缓存（过期时间 = 基础 TTL + 随机抖动，防缓存雪崩）
        long expireSeconds = resolveExpireWithJitter();
        try {
            redisTemplate.opsForValue().set(cacheKey, vo, expireSeconds, TimeUnit.SECONDS);
            log.debug("回写商品详情缓存：productId={}, expire={}s", id, expireSeconds);
        } catch (Exception e) {
            log.warn("写入 Redis 缓存异常，不影响主流程：productId={}", id, e);
        }

        return vo;
    }

    @Override
    public PageResult<ProductVO> listProducts(String keyword, String category, Integer status, long page, long pageSize) {
        LambdaQueryWrapper<ProductDO> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            wrapper.like(ProductDO::getName, keyword);
        }
        if (StringUtils.hasText(category)) {
            wrapper.eq(ProductDO::getCategory, category);
        }
        if (status != null) {
            wrapper.eq(ProductDO::getStatus, status);
        }
        wrapper.orderByDesc(ProductDO::getCreateTime);
        Page<ProductDO> p = new Page<>(Math.max(page, 1), Math.min(Math.max(pageSize, 1), 100));
        Page<ProductDO> result = this.page(p, wrapper);
        return PageResult.of(result.convert(ProductConvert.INSTANCE::doToVO));
    }

    @Override
    public ProductStockVO getProductStock(Long id) {
        ProductDO productDO = this.getById(id);
        if (productDO == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return ProductConvert.INSTANCE.doToStockVO(productDO);
    }

    @Override
    public boolean decreaseStock(Long productId, Integer quantity) {
        int affected = productMapper.decreaseStock(productId, quantity);
        if (affected == 1) {
            // 库存变更后删除缓存（Cache-Aside 策略：保证下次读取拿到最新库存）
            deleteProductCache(productId);
            return true;
        }
        return false;
    }

    @Override
    public boolean increaseStock(Long productId, Integer quantity) {
        int affected = productMapper.increaseStock(productId, quantity);
        if (affected == 1) {
            deleteProductCache(productId);
            return true;
        }
        return false;
    }

    /**
     * 构建缓存 Key
     */
    private String buildCacheKey(Long productId) {
        return CACHE_KEY_PREFIX + productId;
    }

    /**
     * 主动删除商品缓存（用于数据更新时）
     */
    private void deleteProductCache(Long productId) {
        try {
            String cacheKey = buildCacheKey(productId);
            Boolean deleted = redisTemplate.delete(cacheKey);
            log.debug("删除商品缓存：key={}, result={}", cacheKey, deleted);
        } catch (Exception e) {
            log.warn("删除 Redis 缓存异常：productId={}", productId, e);
        }
    }

    // ==================== 缓存穿透 / 雪崩 防护 ====================

    /**
     * 判断缓存值是否为"空值标记"
     *
     * <p>正常缓存的是 ProductVO（带 @class 类型信息，反序列化回 ProductVO），
     * 空值缓存存的是纯字符串 {@link #NULL_MARKER}，二者类型不同，用 instanceof 即可区分。</p>
     */
    private boolean isNullMarker(Object cached) {
        return cached instanceof String s && NULL_MARKER.equals(s);
    }

    /**
     * 写入空值缓存（缓存穿透防护）
     *
     * <p>DB 中查不到的商品（根本不存在 / 已被逻辑删除）也缓存一份短 TTL 的空标记，
     * 避免同一个 productId 被高频请求时每次都穿透到数据库。</p>
     *
     * <p>TTL 必须远小于正常缓存：太长会导致"商品后来被新增或恢复上架后长时间查不到"。
     * 本项目的 productId 是数据库自增主键，新增商品的 id 不会与历史空值缓存冲突，
     * 因此只需靠短 TTL 自动过期即可，无需在新增时额外清理。</p>
     */
    private void cacheNullResult(String cacheKey, Long productId) {
        long nullExpire = businessDynamicConfig.getProductDetailNullCacheExpireSeconds();
        if (nullExpire <= 0) {
            // 0 或负值 = 关闭空值缓存（紧急降级开关）
            return;
        }
        try {
            redisTemplate.opsForValue().set(cacheKey, NULL_MARKER, nullExpire, TimeUnit.SECONDS);
            log.debug("写入空值缓存（防穿透）：productId={}, expire={}s", productId, nullExpire);
        } catch (Exception e) {
            log.warn("写入空值缓存异常，不影响主流程：productId={}", productId, e);
        }
    }

    /**
     * 计算带随机抖动的过期时间（缓存雪崩防护）
     *
     * <p>实际 TTL = 基础过期时间 + random[0, jitter]。
     * 作用：让批量写入的 key 错峰过期，避免它们在某一时刻集体失效、
     * 导致大量请求同时回源打爆数据库（例如部署冷启动、缓存被批量清空后重新预热）。</p>
     *
     * <p>代价是最坏情况下数据陈旧时间多了 jitter 秒，对商品详情这类读多写少的场景可接受。</p>
     */
    private long resolveExpireWithJitter() {
        long base = businessDynamicConfig.getProductDetailExpireSeconds();
        long jitter = businessDynamicConfig.getProductDetailExpireJitterSeconds();
        if (jitter <= 0) {
            // 关闭抖动（所有 key 严格同 TTL，便于压测对照）
            return base;
        }
        // nextLong(bound) 要求 bound > 0，故 +1，使取值范围为 [0, jitter]
        return base + ThreadLocalRandom.current().nextLong(jitter + 1);
    }
}
