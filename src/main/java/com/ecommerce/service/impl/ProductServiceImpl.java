package com.ecommerce.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ecommerce.common.BusinessException;
import com.ecommerce.common.ErrorCode;
import com.ecommerce.common.PageResult;
import com.ecommerce.common.RebuildDegradeLimiter;
import com.ecommerce.common.metrics.RebuildLockMetrics;
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
 *     避免大量并发同时打到数据库。等待以"总时长预算"硬约束（不是重试次数），
 *     预算耗尽则降级查 DB，且降级并发有封顶、超出快速失败（防止保护失效时把并发成倍放给 DB）。
 *     开关 / 租约 / 等待总预算 / 等待片长 / 降级并发上限均可通过 Nacos 动态调整
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
    /** singleflight 重建的运行指标（预算耗尽率、降级拒绝数、租约丢失数、回源耗时） */
    private final RebuildLockMetrics rebuildLockMetrics;

    /**
     * 降级回源的并发闸门：预算耗尽后允许同时查 DB 的请求数上限。
     *
     * <p>纯内存计数、无外部依赖，因此直接字段初始化而不走 Spring 注入——
     * 多一个构造参数就要同步改所有 new ProductServiceImpl(...) 的测试，
     * 而这个对象没有任何需要容器装配的东西。</p>
     */
    private final RebuildDegradeLimiter degradeLimiter = new RebuildDegradeLimiter();

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
     * 其余并发线程不回源，而是等待 leader 写入缓存后直接读缓存、复用其结果。</p>
     *
     * <p>等价于 Go 标准库 singleflight 的语义：多个调用方对同一 key 的请求，只放行一个去执行，
     * 其余调用方复用其结果。用分布式锁而非仅进程内锁，是因为本项目为多实例部署，
     * 不同实例上的并发同样需要互斥（进程内锁只能防住单实例内的并发）。</p>
     *
     * <p><b>等待策略：总时长预算（deadline），而不是"重试次数"。</b>
     * 早期版本用 {@code maxRetries × backoffMillis} 控制等待，但"次数"约束不了时间——
     * 每轮除退避之外还要发一次 Redis 读，真实耗时 = N×backoff + N×RTT，
     * Redis 一抖动就会显著超出配置意图（注释写"约 1 秒预算"，实测可到 2 秒）。
     * 现在改为用单调时钟 {@code System.nanoTime()} 算出绝对截止时间，
     * 等待时长由 {@code product-detail-rebuild-lock-budget-millis} 硬约束：
     * 每轮开始先算"距截止还剩多少"，等待片长取"剩余预算"与"本轮退避"的较小值，
     * 因此总耗时恒 ≤ 预算，与退避策略怎么调都无关（预算配 0 即关闭等待、立即降级）。</p>
     *
     * <p><b>为什么用锁的有限等待，而不是 {@code Thread.sleep} 轮询？</b>
     * 锁一旦释放（leader 正常完成 / 抛异常 / 租约到期），等待者会被立即唤醒，
     * 可以第一时间接替成为新 leader 继续重建，而不必干等满一个退避周期；
     * 同时 {@code Thread.sleep} 从热路径上消失。
     * 注意 Redisson 的语义：被唤醒但抢锁失败的一方仍要等到本次片长结束才返回，
     * 所以<b>片长必须保持在一个典型回源的量级（默认 20ms 起步），不能拿整个预算当等待时长</b>——
     * 那样等待者会白等一整个预算，延迟反而比轮询更差。这里用"指数增长 + 抖动"兼顾两者：
     * 前几轮片长很短（反应快），后几轮翻倍（避免在 leader 卡死时反复订阅造成 Redis 命令放大）。</p>
     *
     * <p><b>退化路径：预算耗尽（leader 迟迟未完成，如 DB 严重抖动）则降级查 DB，
     * 但降级本身受并发闸门封顶</b>（{@code ...-degrade-max-concurrency}）。
     * 单纯"降级直查 DB"会把 singleflight 刚挡住的并发在保护失效的瞬间成倍放出去，
     * 若 DB 本就慢，正反馈会拖垮整个服务；因此降级路径必须是"有界放行 + 超出快速失败"，
     * 而不是无限制放行或返回兜底假数据。详见 {@link #degradeToDb}。</p>
     *
     * <p>运行指标（预算耗尽率、降级拒绝数、租约丢失数、回源耗时）由
     * {@link RebuildLockMetrics} 累计，可通过其 {@code snapshot()} 读取。</p>
     */
    private ProductVO rebuildWithSingleFlight(Long id, String cacheKey) {
        if (!businessDynamicConfig.isProductDetailRebuildLockEnabled()) {
            // 开关关闭：退化为普通"查DB + 回写"，行为与改造前一致
            return loadFromDbAndWriteCache(id, cacheKey);
        }

        String lockKey = REBUILD_LOCK_KEY_PREFIX + id;
        RLock lock = redissonClient.getLock(lockKey);
        long leaseMillis = TimeUnit.SECONDS.toMillis(
                Math.max(businessDynamicConfig.getProductDetailRebuildLockLeaseSeconds(), 1L));
        long budgetMillis = Math.max(businessDynamicConfig.getProductDetailRebuildLockBudgetMillis(), 0L);
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);

        for (int attempt = 0; ; attempt++) {
            // 1. 每轮先回读缓存：leader 可能已重建完成（也就是正常情况下的快路径）
            Object cached = tryReadCache(cacheKey);
            if (cached != null) {
                return unwrapCached(cached);
            }

            // 2. 预算已耗尽（或预算配 0 = 关闭等待）：不再等待，直接走降级
            long remainingMillis = remainingMillis(deadlineNanos);
            if (remainingMillis <= 0) {
                break;
            }

            // 3. 有限等待拿锁：片长 = min(剩余预算, 本轮退避)。
            //    Redisson 会先做一次立即尝试，拿不到才订阅等待，
            //    因此这一句同时覆盖了"抢锁"和"等待"两件事，不需要额外的 tryLock(0) 快路径。
            long waitMillis = Math.min(remainingMillis, jitteredWaitMillis(attempt));
            rebuildLockMetrics.recordWaitRound();
            Boolean locked = tryLockQuietly(lock, waitMillis, leaseMillis);
            if (locked == null) {
                break; // 线程被中断：退出等待
            }
            if (locked) {
                rebuildLockMetrics.recordLeaderAcquired();
                return rebuildAsLeader(id, cacheKey, lock, lockKey);
            }
            // 4. 本轮没抢到锁：回到循环顶部回读缓存，复用 leader 的结果
        }

        // 走到这里 = 等待没换来结果（预算耗尽 / 线程被中断）→ 降级查 DB。
        // 注意降级本身要过并发闸门，不能放任所有 follower 在同一瞬间一起打 DB
        rebuildLockMetrics.recordBudgetExhausted();
        log.warn("singleflight 重建等待预算耗尽，降级查DB：productId={}, budgetMillis={}", id, budgetMillis);
        return degradeToDb(id, cacheKey);
    }

    /**
     * 降级回源：绕过 singleflight 直接查 DB，但受并发闸门封顶。
     *
     * <p><b>为什么需要闸门：</b>预算耗尽说明 leader 已经卡了整整一个预算，
     * 同一批 follower 会在相近时刻集体降级——这恰恰是 singleflight 想避免的场景，
     * 却在"保护失效"的瞬间集中发生。若 leader 卡住的原因正是 DB 慢，
     * 这些降级查询会让 DB 更慢，形成正反馈，最终连不相关的业务也被拖垮。
     * 闸门把"同时查 DB"的请求数限制在连接池可承受的范围内。</p>
     *
     * <p><b>为什么超出就快速失败，而不是返回兜底数据：</b>商品详情里带价格和库存，
     * 兜底值 = 空商品 / 错误价格，等于把"DB 压力"升级成"业务可用性事故"，
     * 代价只是被转移到了更贵的一侧。快速失败虽然牺牲了这批请求，
     * 但守住了数据正确性，也让线程尽快释放而不是堆积在连接池上。</p>
     *
     * <p>闸门上限为 0 时不封顶，退化成"降级不做任何限制"的旧行为（紧急开关）。</p>
     */
    private ProductVO degradeToDb(Long id, String cacheKey) {
        int limit = businessDynamicConfig.getProductDetailRebuildLockDegradeMaxConcurrency();
        if (!degradeLimiter.tryAcquire(limit)) {
            rebuildLockMetrics.recordDegradeRejected();
            log.warn("降级回源并发已达上限，快速失败：productId={}, limit={}, inFlight={}",
                    id, limit, degradeLimiter.inFlight());
            throw new BusinessException(ErrorCode.SYSTEM_BUSY);
        }
        try {
            return loadFromDbAndWriteCache(id, cacheKey);
        } finally {
            degradeLimiter.release();
        }
    }

    /**
     * 以 leader 身份回源重建：拿锁后必须再查一次缓存，并在 finally 释放锁。
     *
     * <p>二次检查的必要性：从"开始尝试拿锁"到"真正拿到锁"之间可能已经过了一段时间
     * （有限等待期间别的节点刚把缓存写好），此时应直接复用缓存，而不是重复回源。</p>
     *
     * <p>锁必须在写完缓存之后才释放，因此 {@code return} 表达式会先求值（查库 + 回写），
     * 再执行 {@code finally} 的解锁——顺序不能颠倒，否则会放进第二个 leader。</p>
     */
    private ProductVO rebuildAsLeader(Long id, String cacheKey, RLock lock, String lockKey) {
        try {
            Object cachedAgain = tryReadCache(cacheKey);
            if (cachedAgain != null) {
                return unwrapCached(cachedAgain);
            }
            return loadFromDbAndWriteCache(id, cacheKey);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                safeUnlock(lock, lockKey);
            } else {
                // 回源还没结束锁就自动过期了（lease 偏短的直接证据）。
                // 不是异常，但必须可见：此时可能有第二个 leader 并发回源，
                // 表现为"DB 被同一个商品多查了几次"，不记录就永远查不出来。
                log.warn("重建锁在回源期间已过期自动释放（lease 可能偏短）：lockKey={}", lockKey);
                rebuildLockMetrics.recordLeaseLost();
            }
        }
    }

    /**
     * 缓存值转 VO；命中"空值缓存"标记时抛商品不存在（防穿透语义）。
     * 统一收口，避免同一段"判空标记 + 强转"逻辑在重建路径里重复三遍。
     */
    private ProductVO unwrapCached(Object cached) {
        if (isNullMarker(cached)) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return (ProductVO) cached;
    }

    /**
     * 尝试获取重建锁；返回 {@code null} 表示线程被中断，调用方应退出等待。
     *
     * <p>{@code waitMillis} 与 {@code leaseMillis} 必须同单位，故统一用毫秒：
     * 预算和片长都是毫秒级，若用秒做单位，800ms 会被截断成 0、退化成"完全没有等待"。</p>
     */
    private Boolean tryLockQuietly(RLock lock, long waitMillis, long leaseMillis) {
        try {
            return lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 距截止时间还剩多少毫秒（已到期返回 0） */
    private long remainingMillis(long deadlineNanos) {
        long remainNanos = deadlineNanos - System.nanoTime();
        return remainNanos <= 0 ? 0L : TimeUnit.NANOSECONDS.toMillis(remainNanos);
    }

    /**
     * 单次等待片长：从 {@code backoffMillis} 起步、每轮翻倍，并叠加 ±50% 抖动。
     *
     * <p>抖动的作用：让同一批到达的等待者错峰重试，避免它们在退避结束时被同步唤醒、
     * 一起降级查 DB 形成瞬时洪峰。</p>
     *
     * <p>指数增长的作用：leader 卡死（如 DB 严重抖动）时，若片长恒定不变，
     * 一个 follower 会反复"订阅锁 + 退订"几十次，把 Redis 命令量放大数十倍；
     * 翻倍后同一段预算内只需几次等待即可走完。前缀几轮仍然很短，保证正常情况反应快。</p>
     *
     * <p>位移次数上限 10（即最多放大 1024 倍）：防止配置被改成极大值时位移溢出。
     * 下限 1ms：防止 {@code backoffMillis} 被配成 0 时片长为 0、退化成空转。</p>
     */
    private long jitteredWaitMillis(int attempt) {
        long base = Math.max(businessDynamicConfig.getProductDetailRebuildLockBackoffMillis(), 1L);
        long raw = base << Math.min(attempt, 10);
        long half = raw / 2;
        long jitter = ThreadLocalRandom.current().nextLong(raw - half + 1);
        return Math.max(half + jitter, 1L);
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
     *
     * <p>这里抛异常同样计入"租约丢失"指标：另一种租约已过期的表现形式
     * （{@code isHeldByCurrentThread()} 与 {@code unlock()} 之间存在窗口，
     * 判定时还在手里、解锁时已过期）。</p>
     */
    private void safeUnlock(RLock lock, String lockKey) {
        try {
            lock.unlock();
        } catch (IllegalMonitorStateException e) {
            log.warn("释放重建锁异常（可能已过期自动释放）：lockKey={}", lockKey, e);
            rebuildLockMetrics.recordLeaseLost();
        }
    }

    /**
     * 查 DB 并回写缓存（缓存未命中时的统一回源逻辑）
     *
     * <p>被 singleflight 的 leader（拿锁者）和极端降级路径共用：
     * DB 查不到（不存在 / 已逻辑删除）→ 写短 TTL 空值缓存（防穿透）后抛异常；
     * 查到 → 转 VO 并回写（带随机抖动的过期时间，防雪崩）后返回。</p>
     *
     * <p>整个方法的耗时（含"商品不存在"和抛异常这两条路径）都计入回源指标，
     * 因为它们是判断"等待预算够不够、租约要不要调"的原始依据。</p>
     */
    private ProductVO loadFromDbAndWriteCache(Long id, String cacheKey) {
        long startNanos = System.nanoTime();
        try {
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
        } finally {
            rebuildLockMetrics.recordDbLoad(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
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
