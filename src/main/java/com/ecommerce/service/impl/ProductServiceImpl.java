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
import org.springframework.data.redis.core.StringRedisTemplate;
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
 *   - 默认首页商品流列表缓存的失效方式：**版本号失效**（而非按前缀批量删除）。
 *     读：把当前版本号拼进 key（...:default:v{ver}:{page}:{pageSize}）；
 *     写：对版本号 key 执行一次 INCR，全部页的缓存即刻逻辑失效（O(1)，无键空间扫描）。
 *     旧版本 key 不再被读取，靠自身 TTL 自然过期，无需主动清理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl extends ServiceImpl<ProductMapper, ProductDO> implements ProductService {

    private static final String CACHE_KEY_PREFIX = "ecommerce:product:detail:";
    /** 重建锁前缀（缓存击穿防护的 singleflight 互斥锁，按 productId 维度加锁） */
    private static final String REBUILD_LOCK_KEY_PREFIX = "ecommerce:lock:rebuild:product:";
    /** 默认首页商品流（无筛选）分页缓存前缀 */
    private static final String LIST_CACHE_KEY_PREFIX = "ecommerce:product:list:";
    /** 默认首页商品流缓存 key 的前缀（含冒号），用于拼接"版本号 + 分页"缓存 key */
    private static final String DEFAULT_LIST_CACHE_PREFIX = LIST_CACHE_KEY_PREFIX + "default:";
    /**
     * 默认首页商品流缓存的**版本号 key**（版本号失效方案的核心）。
     *
     * <p>写操作只对它执行一次 {@code INCR}，即可让所有分页缓存同时失效：
     * 读路径把版本号拼进 key，版本号一变、所有旧 key 立即不可达，新 key 首次读时回源重建。
     * 复杂度 O(1)，不扫描键空间，因此不会像 {@code KEYS} 那样阻塞 Redis 单线程。</p>
     *
     * <p><b>该 key 不设过期时间</b>：它一旦缺失会被"当作全新版本重新播种"（见
     * {@link #resolveDefaultListVersion()}），历史 key 因此永久不可达，不会读到过期数据。</p>
     */
    private static final String DEFAULT_LIST_VERSION_KEY = DEFAULT_LIST_CACHE_PREFIX + "version";
    /** 版本号不可用（Redis 异常）时的哨兵：本次请求降级直查 DB，不读也不写缓存 */
    private static final long VERSION_UNAVAILABLE = -1L;
    /** 版本号重新播种时的随机基数上界（取随机值而非固定 0，避免与历史版本号重合） */
    private static final long VERSION_SEED_BOUND = 1_000_000_000L;
    /** 版本号 key 的初始值（仅首次播种时写入，之后只增不减） */
    private static final long VERSION_SEED_MIN = 1L;
    /**
     * 仅缓存默认首页商品流的前 N 页，防止深翻页产生无限增长的 key（缓存命中率随翻页骤降，
     * 前几页承载绝大多数流量，深翻页走 DB 直查 + 索引兜底更合理）。
     */
    private static final int DEFAULT_LIST_CACHE_MAX_PAGE = 50;

    /**
     * 空值缓存标记（缓存穿透防护）
     * 缓存中存这个字符串表示"该商品在 DB 中不存在（含已被逻辑删除）"，
     * 读到它直接快速失败、不再回源。
     * 用字符串而非 null，是因为 Redis 区分不了"key 不存在"和"value 是 null"。
     */
    private static final String NULL_MARKER = "__NULL__";

    private final ProductMapper productMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    /**
     * 字符串序列化的 RedisTemplate，专供列表缓存的版本号使用。
     * 必须与上面那个 RedisTemplate 分开：后者 value 用 GenericJackson2JsonRedisSerializer，
     * 写入的数字会被包成带 {@code @class} 的 JSON，原生 {@code INCR} 无法解析。
     * 版本号需要的是能直接被 Redis 当作整数自增的裸字符串，故走 StringRedisTemplate。
     */
    private final StringRedisTemplate stringRedisTemplate;
    /** Nacos 动态配置：商品详情缓存过期时间 / 防护开关可动态调整 */
    private final BusinessDynamicConfig businessDynamicConfig;
    /** Redisson 分布式锁客户端：用于缓存击穿防护的 singleflight 互斥重建 */
    private final RedissonClient redissonClient;

    @Override
    public Long addProduct(ProductAddDTO dto) {
        ProductDO productDO = ProductConvert.INSTANCE.addDTOToDO(dto);
        this.save(productDO);
        // 新增商品可能影响默认首页流，失效列表缓存（版本号自增）
        invalidateDefaultListCache();
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
        // 商品信息变更可能影响默认首页流，失效列表缓存（版本号自增）
        invalidateDefaultListCache();
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
        // 删除商品会改变默认首页流的记录，失效列表缓存（版本号自增）
        invalidateDefaultListCache();
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
        // 上下架会改变默认首页流的可见商品，失效列表缓存（版本号自增）
        invalidateDefaultListCache();
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
        long safePage = Math.max(page, 1);
        long safePageSize = Math.min(Math.max(pageSize, 1), 100);

        // 仅对"无筛选的默认首页商品流"走缓存：keyword/category/status 全为空 且 落在前 N 页。
        // 为什么只在无筛选时缓存？keyword 是自由搜索词，带筛选的 key 组合会爆炸、命中率极低、
        // 失效困难；而默认首页流组合固定(page+size)、承载最高流量，缓存命中率高、收益最大。
        // 深翻页(超过 DEFAULT_LIST_CACHE_MAX_PAGE)或带筛选的查询走 DB 直查 + idx_list_query 索引兜底。
        boolean cacheableDefault = businessDynamicConfig.isProductListCacheEnabled()
                && !StringUtils.hasText(keyword)
                && !StringUtils.hasText(category)
                && status == null
                && safePage <= DEFAULT_LIST_CACHE_MAX_PAGE;

        // 读缓存前先解析当前版本号（版本号会拼进缓存 key）。
        // 版本号取不到（Redis 异常）则本次直接降级查 DB，既不读缓存也不回写缓存：
        // 避免在"当前版本未知"的情况下读到一个可能已过期的旧版本缓存。
        long version = VERSION_UNAVAILABLE;
        if (cacheableDefault) {
            version = resolveDefaultListVersion();
            if (version == VERSION_UNAVAILABLE) {
                log.warn("列表缓存版本号不可用，本次降级直查DB：page={}, pageSize={}", safePage, safePageSize);
                cacheableDefault = false;
            }
        }

        if (cacheableDefault) {
            PageResult<ProductVO> cached = getDefaultListFromCache(version, safePage, safePageSize);
            if (cached != null) {
                return cached;
            }
        }

        // 缓存未命中(或本就不走缓存)：直查 DB
        PageResult<ProductVO> result = queryProductPage(keyword, category, status, safePage, safePageSize);

        // 回写默认流缓存(空结果走短 TTL 防穿透)
        if (cacheableDefault) {
            writeDefaultListToCache(version, safePage, safePageSize, result);
        }
        return result;
    }

    /**
     * 构造商品分页查询并返回(不含缓存，供列表主流程复用)
     */
    private PageResult<ProductVO> queryProductPage(String keyword, String category, Integer status, long page, long pageSize) {
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
        Page<ProductDO> p = new Page<>(page, pageSize);
        Page<ProductDO> result = this.page(p, wrapper);
        return PageResult.of(result.convert(ProductConvert.INSTANCE::doToVO));
    }

    // ==================== 默认首页商品流列表缓存(Cache-Aside) ====================

    /**
     * 从缓存读取默认首页商品流某一页；未命中/异常返回 null(交由上层直查 DB)
     */
    @SuppressWarnings("unchecked")
    private PageResult<ProductVO> getDefaultListFromCache(long version, long page, long pageSize) {
        String cacheKey = buildDefaultListKey(version, page, pageSize);
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof PageResult) {
                return (PageResult<ProductVO>) cached;
            }
        } catch (Exception e) {
            log.warn("读取商品列表缓存异常，降级查DB：key={}", cacheKey, e);
        }
        return null;
    }

    /**
     * 回写默认首页商品流某一页缓存
     * 空结果(total==0，如数据库还没有商品/翻过头)走更短的 TTL(防穿透)，有数据走正常 TTL。
     * total==0 且空结果缓存被关闭(null-cache-expire<=0)时则不缓存空页，避免写入无价值的空缓存。
     */
    private void writeDefaultListToCache(long version, long page, long pageSize, PageResult<ProductVO> result) {
        String cacheKey = buildDefaultListKey(version, page, pageSize);
        boolean isEmpty = result.getTotal() == 0 || result.getList() == null || result.getList().isEmpty();
        try {
            if (isEmpty) {
                long nullExpire = businessDynamicConfig.getProductListNullCacheExpireSeconds();
                if (nullExpire <= 0) {
                    return; // 空结果缓存关闭
                }
                redisTemplate.opsForValue().set(cacheKey, result, nullExpire, TimeUnit.SECONDS);
                log.debug("写入空商品列表缓存(防穿透)：key={}, expire={}s", cacheKey, nullExpire);
            } else {
                long expire = businessDynamicConfig.getProductListExpireSeconds();
                redisTemplate.opsForValue().set(cacheKey, result, Math.max(expire, 1), TimeUnit.SECONDS);
                log.debug("写入商品列表缓存：key={}, expire={}s", cacheKey, Math.max(expire, 1));
            }
        } catch (Exception e) {
            log.warn("写入商品列表缓存异常，不影响主流程：key={}", cacheKey, e);
        }
    }

    /**
     * 构建默认首页商品流缓存 key：{@code ecommerce:product:list:default:v{版本号}:{page}:{pageSize}}
     *
     * <p>把版本号编进 key 是"版本号失效"方案的读侧。同一页在不同版本下是不同的 key，
     * 因此版本号一变，全部旧 key 立即不可达 —— 等价于一次性失效整批缓存，却不需要扫描任何 key。</p>
     */
    private String buildDefaultListKey(long version, long page, long pageSize) {
        return DEFAULT_LIST_CACHE_PREFIX + "v" + version + ":" + page + ":" + pageSize;
    }

    /**
     * 写操作后失效全部默认首页商品流缓存（Cache-Aside：更新 DB 后失效缓存）。
     *
     * <p><b>实现方式：版本号自增，而不是按前缀批量删除。</b>
     * 对版本号 key 执行一次 {@code INCR}（O(1)、单条命令、不扫描键空间）即可让所有分页缓存失效，
     * 下次读取时自动回源重建。</p>
     *
     * <p><b>为什么不用 {@code KEYS prefix*} + {@code DEL}？</b>
     * {@code KEYS} 的复杂度是 O(N)，这里的 N 是<b>整个实例的键空间</b>（不是匹配到的 key 数量），
     * 且 Redis 单线程模型下会阻塞其它所有命令。本方法被库存扣减路径调用（每次成功下单都会触发），
     * 属于高频热路径：实测在 2 万键空间下，单笔下单延迟由 24ms 升至 30ms（+25%），
     * 且劣化幅度随键空间线性增长 —— 生产环境（键空间远大于 2 万）代价不可接受。</p>
     *
     * <p><b>旧版本 key 的清理：不主动删。</b>它们不再被任何读请求访问，
     * 各自靠 TTL（{@code product-list-expire-seconds}，默认 120 秒）自然过期即可。
     * 切勿为了清理旧 key 再引入一次 {@code KEYS}，那等于把问题绕回来。</p>
     *
     * <p><b>失败语义：</b>Redis 异常时只记录日志并放行，不影响主流程。
     * 代价是最多 {@code product-list-expire-seconds} 秒的列表脏读窗口，
     * 与改造前"靠短 TTL 兜底一致性"的语义完全一致，没有引入更差的一致性模型。</p>
     */
    private void invalidateDefaultListCache() {
        try {
            Long newVersion = stringRedisTemplate.opsForValue().increment(DEFAULT_LIST_VERSION_KEY);
            log.debug("已失效默认首页商品流缓存：版本号自增为 {}", newVersion);
        } catch (Exception e) {
            log.warn("失效商品列表缓存异常（版本号自增失败）", e);
        }
    }

    /**
     * 解析当前列表缓存版本号。
     *
     * <p>返回 {@link #VERSION_UNAVAILABLE} 表示版本号不可用，调用方应降级直查 DB，
     * 不在"当前版本未知"的前提下读写缓存。</p>
     *
     * <p><b>版本号 key 缺失时的自愈：</b>写入一个<b>随机基数</b>作为新版本号的起点。
     * 用随机值而不是固定 0，是为了处理"版本号 key 被意外清除或内存淘汰，而旧版本缓存尚未到期"
     * 这一场景：若固定回落到 0，可能恰好与历史版本号重合，从而读到过期数据；
     * 随机基数几乎不可能与历史值重合，读请求会全部 miss 并回源拿最新数据。
     * 这里的取舍是明确的：<b>宁可多查一次 DB，不可读到过期数据。</b></p>
     *
     * <p>用 {@code setIfAbsent} 保证并发安全：多实例同时发现 key 缺失时只允许一个写入，
     * 其余实例读回胜者的值，避免版本号来回跳变导致缓存被反复失效。</p>
     */
    private long resolveDefaultListVersion() {
        try {
            String cached = stringRedisTemplate.opsForValue().get(DEFAULT_LIST_VERSION_KEY);
            if (cached != null) {
                return Long.parseLong(cached);
            }

            long seed = ThreadLocalRandom.current().nextLong(VERSION_SEED_MIN, VERSION_SEED_BOUND);
            Boolean created = stringRedisTemplate.opsForValue()
                    .setIfAbsent(DEFAULT_LIST_VERSION_KEY, Long.toString(seed));
            if (Boolean.TRUE.equals(created)) {
                log.warn("列表缓存版本号 key 缺失，已重新播种：key={}, seed={}", DEFAULT_LIST_VERSION_KEY, seed);
                return seed;
            }

            // 并发下被其它实例抢先写入：读回它写的版本号，保持全局一致
            String afterRace = stringRedisTemplate.opsForValue().get(DEFAULT_LIST_VERSION_KEY);
            return afterRace == null ? VERSION_UNAVAILABLE : Long.parseLong(afterRace);
        } catch (Exception e) {
            log.warn("读取列表缓存版本号异常，本次降级直查DB：key={}", DEFAULT_LIST_VERSION_KEY, e);
            return VERSION_UNAVAILABLE;
        }
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
            // 列表展示含库存，扣减后失效默认首页流缓存
            invalidateDefaultListCache();
            return true;
        }
        return false;
    }

    @Override
    public boolean increaseStock(Long productId, Integer quantity) {
        int affected = productMapper.increaseStock(productId, quantity);
        if (affected == 1) {
            deleteProductCache(productId);
            invalidateDefaultListCache();
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
