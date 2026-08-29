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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, ProductDO> implements ProductService {

    private static final String CACHE_KEY_PREFIX = "ecommerce:product:detail:";

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
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                log.debug("命中商品详情缓存：productId={}", id);
                return (ProductVO) cached;
            }
        } catch (Exception e) {
            log.warn("读取 Redis 缓存异常，降级查DB：productId={}", id, e);
        }

        // 2. 缓存未命中，查 DB
        ProductDO productDO = this.getById(id);
        if (productDO == null) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        ProductVO vo = ProductConvert.INSTANCE.doToVO(productDO);

        // 3. 回写缓存（设置过期时间，从 Nacos 动态配置读取）
        long expireSeconds = businessDynamicConfig.getProductDetailExpireSeconds();
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
}
