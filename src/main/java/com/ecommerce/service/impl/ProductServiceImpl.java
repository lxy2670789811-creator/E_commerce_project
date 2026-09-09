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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
 *   - 缓存击穿防护：热点 key 过期的瞬间，用 Redisson 分布式锁做 singleflight（互斥重建），
 *     同一 productId 同一时刻只允许一个线程回源，其余并发请求等待其完成后直接读缓存，
 *     避免大量并发同时打到数据库。开关 / 超时 / 重试 / 退避均可通过 Nacos 动态调整
 *   - 穿透与雪崩两个防护的 TTL 均可通过 Nacos 动态调整；置 0 即关闭对应防护（紧急降级开关）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, ProductDO> implements ProductService {

    private static final String CACHE_KEY_PREFIX = "ecommerce:product:detail:";
    /** 重建锁前缀（缓存击穿防护的 singleflight 互斥锁，按 productId 维度加锁） */
    private static final String REBUILD_LOCK_KEY_PREFIX = "ecommerce:lock:rebuild:product:";

    /**
     * 空值缓存标记（缓存穿透防护）
     * 缓存中存这个字符串表示"该商品在 DB 中不存在（含已被逻辑删除）"，
     * 读到它直接快速失败、不再回源。
     * 用字符串而非 null，是因为 Redis 区分不了"key 不存在"和"value 是 null"。
     */
    private static final String NULL_MARKER = "__NULL__";

    private final ProductMapper productMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    /** Nacos 动态配置：商品详情缓存过期时间 / 防护开关可动态调整 */
    private final BusinessDynamicConfig businessDynamicConfig;
    /** Redisson 分布式锁客户端：用于缓存击穿防护的 singleflight 互斥重建 */
    private final RedissonClient redissonClient;

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

        // 2. 缓存未命中：进入 singleflight 重建流程，防缓存击穿（热点 key 过期瞬间的并发回源）
        return rebuildWithSingleFlight(id, cacheKey);
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

    // ==================== 缓存击穿防护（singleflight 互斥重建） ====================

    /**
     * 缓存未命中时的 singleflight 重建（防缓存击穿）
     *
     * <p>热点商品缓存过期的瞬间，可能有大量并发请求同时 miss、同时回源，
     * 导致数据库瞬时被打爆（缓存击穿）。这里用 Redisson 分布式锁实现 singleflight：
     * 同一 productId 同一时刻只有一个线程真正回源重建（leader），
     * 其余并发线程不回源，而是轮询等待 leader 写入缓存后直接读缓存、复用其结果。</p>
     *
     * <p>等价于 Go 标准库 singleflight 的语义：多个调用方对同一 key 的请求，只放行一个去执行，
     * 其余调用方复用其结果。用分布式锁而非仅进程内锁，是因为本项目为多实例部署，
     * 不同实例上的并发同样需要互斥（进程内锁只能防住单实例内的并发）。</p>
     *
     * <p>退化路径：若 leader 迟迟未完成（如 DB 严重抖动）导致重试耗尽，
     * 则降级为直接查 DB 返回，不让请求无限阻塞（宁可短暂多查几次 DB，也不让接口挂起）。</p>
     */
    private ProductVO rebuildWithSingleFlight(Long id, String cacheKey) {
        if (!businessDynamicConfig.isProductDetailRebuildLockEnabled()) {
            // 开关关闭：退化为普通"查DB + 回写"，行为与改造前一致
            return loadFromDbAndWriteCache(id, cacheKey);
        }

        String lockKey = REBUILD_LOCK_KEY_PREFIX + id;
        RLock lock = redissonClient.getLock(lockKey);
        long leaseSeconds = businessDynamicConfig.getProductDetailRebuildLockLeaseSeconds();
        int maxRetries = businessDynamicConfig.getProductDetailRebuildLockMaxRetries();
        long backoffMillis = businessDynamicConfig.getProductDetailRebuildLockBackoffMillis();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // 1. 每轮先重新读缓存：leader 可能已重建完成
            Object cached = tryReadCache(cacheKey);
            if (cached != null) {
                if (isNullMarker(cached)) {
                    throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
                }
                return (ProductVO) cached;
            }

            // 2. 缓存仍 miss：tryLock(0) 立即尝试成为 leader（不阻塞等待，拿不到就退避重试）
            boolean locked = false;
            try {
                locked = lock.tryLock(0, leaseSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break; // 线程被中断，退出循环走最终降级
            }

            if (locked) {
                try {
                    // 3. 成为 leader：二次检查缓存（拿锁瞬间可能别的节点刚写完）
                    Object cachedAgain = tryReadCache(cacheKey);
                    if (cachedAgain != null) {
                        if (isNullMarker(cachedAgain)) {
                            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
                        }
                        return (ProductVO) cachedAgain;
                    }
                    // 4. 真正回源重建
                    return loadFromDbAndWriteCache(id, cacheKey);
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        safeUnlock(lock, lockKey);
                    }
                }
            }

            // 5. 没拿到锁（leader 正在重建）：退避后重试读缓存，复用 leader 的结果
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 6. 重试耗尽（leader 迟迟未完成 / 锁异常）：降级直接查 DB 返回，避免请求无限阻塞
        log.warn("singleflight 重建重试耗尽，降级直接查DB：productId={}", id);
        return loadFromDbAndWriteCache(id, cacheKey);
    }

    /**
     * 读取缓存，异常时返回 null（交由上层降级查 DB）
     */
    private Object tryReadCache(String cacheKey) {
        try {
            return redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.warn("singleflight 重建中读取缓存异常：key={}", cacheKey, e);
            return null;
        }
    }

    /**
     * 安全释放 Redisson 锁（防止锁已过期自动释放后再次 unlock 抛 IllegalMonitorStateException）
     */
    private void safeUnlock(RLock lock, String lockKey) {
        try {
            lock.unlock();
        } catch (IllegalMonitorStateException e) {
            log.warn("释放重建锁异常（可能已过期自动释放）：lockKey={}", lockKey, e);
        }
    }

    /**
     * 查 DB 并回写缓存（缓存未命中时的统一回源逻辑）
     *
     * <p>被 singleflight 的 leader（拿锁者）和极端降级路径共用：
     * DB 查不到（不存在 / 已逻辑删除）→ 写短 TTL 空值缓存（防穿透）后抛异常；
     * 查到 → 转 VO 并回写（带随机抖动的过期时间，防雪崩）后返回。</p>
     */
    private ProductVO loadFromDbAndWriteCache(Long id, String cacheKey) {
        ProductDO productDO = this.getById(id);
        if (productDO == null) {
            // 缓存穿透防护：查不到也要写一份短 TTL 的空值缓存，挡住重复穿透
            cacheNullResult(cacheKey, id);
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        ProductVO vo = ProductConvert.INSTANCE.doToVO(productDO);

        // 回写缓存（过期时间 = 基础 TTL + 随机抖动，防缓存雪崩）
        long expireSeconds = resolveExpireWithJitter();
        try {
            redisTemplate.opsForValue().set(cacheKey, vo, expireSeconds, TimeUnit.SECONDS);
            log.debug("回写商品详情缓存：productId={}, expire={}s", id, expireSeconds);
        } catch (Exception e) {
            log.warn("写入 Redis 缓存异常，不影响主流程：productId={}", id, e);
        }
        return vo;
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
