# 电商订单后端系统（e-commerce-order-backend）

一个面向简历的 Java 后端电商订单系统，覆盖**商品、订单、用户、AI 售后**四大模块，
沉淀了分布式锁防超卖、缓存一致性（详情三防 + 列表缓存）、多级降级、RocketMQ 延迟消息、DB 连接池护栏调优、分页、多环境配置与测试等工程实践。

## 功能特性

| 模块 | 亮点 |
| ---- | ---- |
| 商品 | Cache-Aside 缓存（写后删缓存保一致性）、**空值缓存防穿透 + TTL 随机抖动防雪崩 + Redisson 单飞(singleflight)防击穿**、逻辑删除、分页查询 |
| 商品 | **默认首页商品流列表缓存**：对无筛选分页做 Redis 整页缓存（短 TTL 兜底一致性、空结果防穿透），写操作对版本号 key 自增一次即整体失效（**O(1)，不用 `KEYS` 扫描键空间**）；带筛选查询走联合索引直查，避免 key 组合爆炸 |
| 订单 | **三层防超卖**：Sentinel 限流 → Redisson 按商品维度分布式锁 → DB 原子扣减（`stock >= quantity`） |
| 订单 | 完整状态机：待支付 → 已支付 → 已发货 → 已完成 / 已取消（支付回调幂等） |
| 订单 | **RocketMQ 延迟消息超时自动关单**（事务提交后**异步发送**（asyncSend + SendCallback），消费端幂等，库存回滚；细粒度健康门控扫描 + 粗粒度强制对账双兜底） |
| 用户 | **JWT 鉴权登录**：登录下发 Token，拦截器校验，`AuthContext`（ThreadLocal）传递身份，业务层取身份而非信任参数；收货地址管理（默认地址互斥） |
| 订单 | **下单一次性凭证（幂等 Token）**：进入下单页 `GET /order/token` 领凭证、提交时 Lua 原子消耗（GETDEL），防双击/超时重试生成的重复订单与重复扣库存 |
| AI 售后 | DeepSeek 大模型智能分析 + **五层保护**：动态开关 → Sentinel 熔断 → Redis 滑动窗口限流 → Feign 熔断 → 业务降级"待人工审核" |
| 工程化 | 统一响应/全局异常（**HTTP 状态码语义分层**：业务失败 200 + code，传输层失败真实 4xx/5xx）、MapStruct、Knife4j 接口文档、Nacos 动态配置、多环境 profile、Docker Compose、**HikariCP 连接池护栏调优**、67 个测试（含并发防超卖集成测试） |
| 云原生 | **容器化就绪（L0）+ K8s 编排（L1）**：Actuator 存活/就绪探针（分组显式收敛）、**优雅停机**、**定时扫描多副本 Redisson 选主**、非 root 运行的多阶段镜像；**K8s 清单**（Deployment / Service / Ingress / HPA / PDB / ConfigMap / Secret 模板 + 前端 nginx 镜像与配置分离）；应用本身无状态（JWT + Redis 锁/限流 + 无本地缓存与文件），可直接水平扩缩 |

## 技术栈

- **JDK 17 + Spring Boot 3.2.x + Maven**
- Spring Cloud Alibaba：Nacos 配置中心、Sentinel 限流熔断、OpenFeign
- MyBatis-Plus + MySQL 8 + Redis(Redisson)
- RocketMQ（延迟消息）
- DeepSeek 大模型 API（OpenAI 兼容协议）
- Knife4j(SpringDoc OpenAPI)、MapStruct、Lombok、Hutool
- **JWT 鉴权**：零第三方依赖，基于 JDK `javax.crypto` 手写 HMAC-SHA256 实现（非 Spring Security / jjwt），便于把"三段式结构 + 签名防篡改"讲透
- 前端：Vue 3 + Vite + Element Plus（管理台演示）

## 快速开始

### 1. 环境要求

- JDK 17、Maven 3.8+、Node 18+
- MySQL 8、Redis、RocketMQ（可选用 docker-compose 一键启动，见下）
- Nacos 可选（不启动则使用本地默认配置，仅失去配置中心动态刷新能力）
- DeepSeek API Key（可选，不设置时 AI 售后自动降级为"待人工审核"）

### 2. 启动中间件（可选）

```bash
# 一键启动 MySQL / Redis / Nacos / RocketMQ / Sentinel Dashboard
docker compose up -d

# 同时启动后端应用（Docker 构建）
docker compose --profile app up -d --build
```

> 注意：如果你本机已运行 MySQL(3306)/Redis(6379)，请先停掉或修改 compose 端口映射，避免端口冲突。

### 3. 配置环境变量

复制 `.env.example` 并设置（Windows 可在 IDEA Run Configuration 中配置环境变量）：

| 变量 | 说明 | 默认值 |
| ---- | ---- | ---- |
| `DEEPSEEK_API_KEY` | DeepSeek API Key（**必须从环境变量注入，禁止提交真实 Key**） | 空（AI 走降级） |
| `MYSQL_PASSWORD` | MySQL 密码 | `123456` |
| `NACOS_USERNAME` / `NACOS_PASSWORD` | Nacos 账号 | `nacos` / `nacos` |
| `ROCKETMQ_NAME_SERVER` | RocketMQ NameServer | `127.0.0.1:9876` |
| `JWT_SECRET` | JWT HMAC 签名密钥（**生产必须设置且 ≥32 字节**；不设则用仓库默认演示密钥，存在被伪造风险） | 空（用默认演示密钥，**不建议上生产**） |

### 4. 启动后端

```bash
# 默认使用 dev 环境（application.yml -> application-dev.yml）
mvn spring-boot:run
# 或
java -jar target/e-commerce-order-backend-1.0.0.jar
```

初始化数据库（首次）：执行 `src/main/resources/sql/schema.sql`（建库建表 + 演示数据）。

> ⚠️ **`schema.sql` 只对"全新的空库"生效，不会影响已经存在的数据库。**
> 它只在两种情况被执行：① `docker-compose.yml` 把它挂到 `/docker-entrypoint-initdb.d/`，由 MySQL 镜像在数据目录为空时跑一次；
> ② 有人手工执行。而且脚本里是 `DROP TABLE IF EXISTS` 破坏性重建，已存在数据的库不能直接跑。
> **因此凡新增索引/字段，除了改 `schema.sql`，还必须另写一条迁移脚本并手工执行一次**，见下一节。

增量变更：`src/main/resources/sql/migration/` 下的脚本按文件名日期顺序执行，可重复执行（幂等）：

```bash
mysql -uroot -p ecommerce < src/main/resources/sql/migration/V20260914_01__add_product_list_index.sql
```

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev   # http://localhost:5173
```

演示账号：`test001 / 123456`

### 6. 访问

- 后端接口文档（Knife4j）：http://localhost:8080/api/doc.html
- 前端管理台：http://localhost:5173

## 测试

```bash
mvn clean test
```

共 **67 个测试**，重点：

- `OrderConcurrencyIntegrationTest`：真实 MySQL + Redis 并发防超卖（40 线程抢 20 库存 → 恰好 20 单、库存归 0、无超卖）
- `OrderServiceImplTest`：下单/取消/支付回调/发货/完成/超时关单等 22 个核心路径
- `OrderNoGeneratorTest`：订单号格式 + 5 万连续/并发唯一性
- `DeepSeekClientTest`：AI 解析、重试、降级
- `DeepSeekFeignResourceNameTest`：Feign 熔断资源名一致性——SCA 的 `SentinelInvocationHandler` 按 `HTTP方法:url+path` 生成资源名，**不是**「类名#方法名」；写成后者规则照常加载却永远不命中（熔断静默失效，而 fallback 降级照常工作，现象上看不出来）。本用例锁定 `@FeignClient` 与 Sentinel 规则共用同一常量，并断言实际注册的资源名
- `AiRateLimiterTest`：限流放行/拒绝/Redis 故障降级
- `ProductCacheNullMarkerTest`：缓存防护专项——空值标记序列化往返（防穿透能否生效的前提）、商品缓存类型还原不被误判、TTL 抖动区间与错峰效果、抖动开关
- `ProductCacheSingleflightTest`：缓存击穿防护专项——singleflight 互斥重建的 leader 只回源一次、并发请求复用 leader 结果、二次查缓存命中不查 DB、开关关闭退化直查、**总等待时长被预算硬约束（既等满、又不超）**、**降级并发闸门满载时快速失败且不触达 DB**（含"上限配 0 = 不封顶"的对照）、**租约丢失被计数且跳过 unlock**
- `OrderTimeoutScanSchedulerLeaderElectionTest`：**多副本选主**——抢到锁才扫描并释放、抢不到锁**连 DB 查询都不发生**（这是"不再重复扫描"的直接证据）、租约过期时跳过 `unlock()` 且不抛 `IllegalMonitorStateException`、抢锁被中断则放弃本轮。已做变异验证：去掉选主的 `return` 后准确报红
- `GlobalExceptionHandlerTest`：**HTTP 状态码语义**——未映射路径与未暴露的 actuator 端点返回真实 404（而不是被兜底包成 HTTP 200 + `code 5000`）、方法不支持返回 405 且按 RFC 9110 带 `Allow` 头、未预期异常返回 500、框架级 `ErrorResponse` 沿用其自带状态码；另有**对照组锁定「业务异常仍是 HTTP 200」这条既有约定不被误改**

> 集成测试使用独立测试库 `ecommerce_test`（自动创建）与 Redis DB15，不污染开发数据；
> 测试 profile 已禁用 Nacos/Sentinel/RocketMQ，无需额外中间件。
>
> **务必带 `clean`**：本项目踩过两次「增量编译/Schema 漂移骗过测试」的坑——
> ① `ProductServiceImpl` 的构造器加了 `StringRedisTemplate` 后，两个缓存测试类连编译都过不了，
> 但 Maven 增量编译见 `.class` 比 `.java` 新便直接跳过，老字节码照旧执行，直到运行期才抛 `NoSuchMethodError`；
> ② 测试库由 `schema-test.sql` 建表，原用 `CREATE TABLE IF NOT EXISTS`，
> 对已存在的表完全无效 → 脚本加了 `idempotency_token` 列而库里永远没有，
> 并发用例全部报 `Unknown column`。现该脚本已改为 `DROP TABLE IF EXISTS` + `CREATE TABLE`，
> 保证每次上下文启动库结构与脚本严格一致。

## 多环境配置

| Profile | 文件 | 说明 |
| ------- | ---- | ---- |
| `dev`（默认） | `application-dev.yml` | 本地开发：localhost 中间件、SQL 日志、debug 日志 |
| `prod` | `application-prod.yml` | 生产：敏感配置必须环境变量注入、关闭 SQL 日志 |
| `test` | `src/test/resources/application-test.yml` | 测试专用（独立库 + 禁外部中间件） |

> 三个环境的 HikariCP 口径一致：`maximum-pool-size` dev/test 为 **10**、prod 为 **15**
> （原值分别为 20 / 30 / 50，均按「连接池不是越大越好」收敛），并统一 `connection-timeout: 3000` 快速失败。
> test 环境刻意不开 `leak-detection-threshold`：下单事务包住 Redisson 锁等待，
> 并发用例里「拿着连接等锁」是预期行为，开了会刷假泄漏告警。

```bash
# 生产环境启动
java -jar app.jar --spring.profiles.active=prod
```

## 订单状态机

```
            支付回调(pay-callback)          发货(ship)         完成(finish)
待支付(0) ───────────────────────► 已支付(1) ──────► 已发货(2) ──────► 已完成(3)
   │                                  │
   └── 取消(用户/超时自动关单) ────────┘
                        已取消(4)
```

## 设计要点（面试可讲）

1. **防超卖**：Sentinel 入口限流 → Redisson 按商品 ID 加锁（同商品串行）→ SQL `stock >= quantity` 原子扣减（DB 最终兜底）。
   锁在**事务提交/回滚后**释放（`TransactionSynchronization.afterCompletion`），避免"锁先释放、事务未提交"的并发窗口。
2. **缓存一致性 + 三防**：Cache-Aside + 写后删缓存（而非更新缓存），避免并发覆盖旧值；Redis 异常降级查库不影响主流程。
   - **防穿透**：DB 查不到（不存在 / 已逻辑删除）时写入短 TTL 空值标记（默认 120s），挡住同一个 productId 被反复打到数据库；
   - **防雪崩**：TTL = 基础值 + `random[0, 300s]` 随机抖动，让批量 key 错峰过期，避免同一时刻集体失效、请求同时回源；
   - **防击穿（singleflight 互斥重建）**：热点 key 过期瞬间大量并发同时 miss 时，用 Redisson 分布式锁做 singleflight——同一 productId 同一时刻只允许一个线程回源（leader），其余线程等锁并在 leader 写完缓存后直接读缓存复用结果，不让并发同时打到数据库；等待由**总时长预算**（默认 1000ms，绝对截止时间）硬约束，每次等待片长从 20ms 起步**每轮翻倍并叠加 ±50% 抖动**（抖动避免等待者被同步唤醒后一起降级查库，翻倍避免 leader 卡死时反复订阅锁放大 Redis 命令量）；预算耗尽则降级查 DB，**降级并发有闸门封顶**（默认 8，超出快速失败返回"系统繁忙"）——防止保护失效那一瞬把并发成倍放给 DB，详情含价格与库存故不返回兜底值；开关、锁租约（默认 5s，≈回源超时上限而非越大越好）、等待总预算、起始片长、降级并发上限均支持 Nacos 热更新；**运行指标**（预算耗尽数、降级拒绝数、租约丢失数、回源 avg/max 耗时）由 `RebuildLockMetrics` 累计，其中 `leaseLost` 是判断租约是否偏短的直接证据；
   - 开关均支持 Nacos 热更新，**置 false / 0 即关闭对应防护**（紧急降级开关）。
3. **超时自动关单**：下单事务提交后**异步发送** RocketMQ 延迟消息（`asyncSend` + `SendCallback`，延迟级别可动态配置），请求线程入队即返回、不阻塞等待 broker 应答，因此同商品库存锁（释放挂在 `afterCompletion`，晚于 `afterCommit` 里的发送）的持有窗口被压缩，降低同款并发争用、提升吞吐、抗 broker 抖动；消费端幂等关单、回滚库存。可靠性由**三层兜底**保证：
   - **细粒度健康门控扫描**：`RocketMQ` 通道可用（模板已装配且最近发送成功）时直接跳过、零 DB 轮询；仅当 MQ 不可用时接管补偿。
   - **粗粒度强制对账扫描**（`order-timeout-scan-coarse-cron`，默认每 10 分钟）：**不受健康标志约束、固定周期强制执行**，专门堵住"异步发送已入队、但 JVM 在回调到达前崩溃 / 静默丢单且健康标志未翻转"这一极端窗口，确保超时订单一定会被扫到关单。
   - 延迟消息 + 细粒度门控 + 粗粒度对账构成三重保障，消息丢失/中间件抖动时仍能兜底关单。
4. **AI 售后降级**：动态开关 → Sentinel 慢调用/异常比例熔断 → Redis 滑动窗口限流（Lua 原子）→ Feign 熔断 → "待人工审核"兜底，AI 完全不可用时接口仍可用。
5. **订单号唯一性**：`ORD + 秒级时间戳 + 完整雪花ID`，并发的订单号测试验证无重复。
6. **JWT 鉴权（零依赖手写实现）**：登录签发 HMAC-SHA256 三段式 Token，`JwtAuthInterceptor` 解析后写入 `AuthContext`（ThreadLocal）传递身份，业务层取身份而非信任请求参数；请求结束 `clear()` 防线程池复用串号；`required` 开关支持兼容模式（缺 Token 放行），`allow-plain-text-login` 支持存量明文密码自动升级 BCrypt。
7. **下单幂等凭证（防重复提交）**：`createOrder` 本身非幂等——防超卖只挡并发，挡不住时间分散的重复提交（双击/超时重发）。进入下单页 `GET /order/token` 领一次性凭证（绑定 userId+productId），提交时以 Lua 脚本原子"取出并删除"（GETDEL），二次提交因凭证已消耗被拒；Redis 故障 fail-open 放行，由数据库唯一索引 `uk_idempotency_token` 兜底，两层防护相互独立。
8. **Redis 序列化陷阱（踩坑实录）**：`GenericJackson2JsonRedisSerializer` **只有在使用无参构造器时**才会自动注册 `@class` 类型信息；一旦传入自定义 `ObjectMapper`（本项目为了定制 `LocalDateTime` 格式），它就沿用该 mapper、不再开启多态类型处理 → 序列化出的 JSON 不带 `@class` → 反序列化回来是 `LinkedHashMap` → `(ProductVO) cached` 抛 `ClassCastException` → 又被"缓存异常降级查库"的 `catch` 悄悄吞掉。**表现为接口一切正常、但缓存 100% 未命中**。已在 `RedisConfig` 显式 `activateDefaultTyping(NON_FINAL, As.PROPERTY)` 修复，并用单元测试锁死该行为（String 等 final 类型不写 `@class`，空值缓存标记仍按纯字符串往返）。
9. **列表缓存：只缓存"能缓存"的列表**。商品列表筛选维度多（keyword/category/status + 翻页），若对任意组合都做整页缓存，key 空间组合爆炸、命中率趋近于零、且写操作失效困难（无法精确到某个商品改一次就要删海量 key）。因此本项目**只对无筛选的默认首页商品流**（组合固定为 page+size，承载最高流量）做 Cache-Aside 缓存，短 TTL（默认 120s）兜底一致性、空结果短缓存防穿透、6 个写操作后只需对版本号 key 自增一次（O(1)）即让全部分页整体失效。带 keyword 自由搜索的列表不做整页缓存，改由**联合索引 `idx_list_query (deleted, status, category, create_time)` 兜底分页** + 限流。核心判断：**先分辨列表是否天然适合整页缓存，再决定缓存策略，比无脑加缓存更关键**。
10. **DB 连接池护栏调优（HikariCP）**：压测暴露"连接池争用"时，先问"连接被谁占着不还"而非"再加多少条"。本项目的护栏策略——`maximum-pool-size` 收敛到合理值（prod 从 50 收到 15；每多一条连接 = MySQL 多一个线程，池过大反而放大上下文切换与 InnoDB 锁争用）、`connection-timeout` 调低到 3s（拿不到连接快速失败而非让线程在池上无限堆积，避免拖垮 Tomcat 线程池）、开启 `leak-detection-threshold=30s`（连接超时未归还在日志中打泄漏告警，用于定位长期占连接的慢 SQL/事务）。**连接池参数是护栏不是提速器**：真正减少 DB 连接占用靠"查询走索引 + 默认流走缓存"（见第 9 点），护栏负责在压力下快速失败、暴露问题。
11. **`KEYS` 前缀扫描 → 版本号失效（性能踩坑实录）**：列表缓存的写后失效最初用 `redisTemplate.keys("...default:*")` + 批量 `DEL`。问题在于 `KEYS` 的复杂度 O(N) 里的 N 是**整个 Redis 实例的键空间**（不只是匹配到的那些 key），且 Redis 单线程模型下会阻塞其它所有命令。而该失效动作被**库存扣减路径**调用 —— 每成功下一单就触发一次，是高频热路径。实测（灌入 2 万键空间后重跑单线程顺序下单）：**单笔下单延迟 24ms → 30ms（+25%），劣化幅度随键空间线性增长**。改法：**版本号失效** —— 读路径把版本号拼进缓存 key（`...:default:v{ver}:{page}:{size}`），写路径对版本号 key 执行一次 `INCR`。版本号一变全部旧 key 即刻不可达，**O(1)、单条命令、零扫描**；旧 key 不再被读取，靠自身 TTL 自然过期，无需主动清理。两个关键细节：①版本号 key **不设过期时间**，若被意外清除则用**随机基数**重新播种（而非固定回落 0，否则可能与历史版本号重合而读到过期数据 —— 宁可多查一次 DB，不可读到过期数据）；②必须用 `StringRedisTemplate` 而非项目里那个 JSON 序列化的 `RedisTemplate`，后者写入的数字会被包成带 `@class` 的 JSON，原生 `INCR` 无法解析。

12. **容器化就绪 / 云原生 L0（K8s 适配）**：本项目能直接上 K8s，前提是应用**天然无状态**——JWT 无 Session、锁与限流都在 Redis（Redisson / Lua）、无本地缓存、无本地文件存储，因此 Pod 可任意扩缩与漂移，不需要 sticky session 或共享存储。在此之上补了四处 K8s 必需的集成：
    - **探针语义必须分开，而且分组的 include 必须显式写**（本项最容易漏，也最容易配错）：`/api/actuator/health/liveness` 只判"进程是否卡死"，失败 → K8s 重启容器；`/api/actuator/health/readiness` 只判"应用是否就绪"，失败 → K8s **只把 Pod 摘出 Service，不重启**。⚠️ **实测结论**：打开 `probes.enabled` 之后，两个分组的默认行为是**都包含全部健康指示器**（db / redis / nacosConfig / sentinel / diskSpace，两组列出的组件完全相同）。也就是说，不写 `management.endpoint.health.group.*.include`，liveness 探针实际探测的就是数据库 —— MySQL 抖一下，K8s 会重启**所有** Pod，把"暂时不可用"放大成"全站崩溃"。本项目已显式收敛为 `liveness: livenessState` / `readiness: readinessState`；readiness 刻意**不含** DB/Redis，理由是依赖抖动都有降级路径（AI 有 fallback、缓存降级直查、MQ 有定时扫描兜底），把 DB 放进去只会造成"MySQL 抖动 → 全部 Pod 被摘 → 入口 503"，反而把局部故障放大成全站故障。
    - **优雅停机**：`server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s`。Spring Boot 默认 immediate 会立刻关闭 Tomcat，正在执行的下单请求（扣库存 + 写订单 + 发延迟消息）被硬切就可能出现中间态。⚠️ 必须与 K8s 的 `terminationGracePeriodSeconds`（建议 45s）配合，否则在途请求没跑完就被 SIGKILL。已知边界：AI 链路 Feign `readTimeout` 就是 30s、恰好卡在边界，但接口有 fallback 降级且非交易主链路，接受；正确解法是收敛 AI 超时而不是调大停机等待（等待越久滚动更新越慢）。
    - **定时扫描多副本选主**：`OrderTimeoutScanScheduler` 的两个 `@Scheduled` 在多副本下会各跑一遍。抢不到 Redisson 全局锁的副本直接跳过本轮。注意这里 `tryLock` 的 **waitTime 传 0（不等待）**，与缓存重建锁的"有限等待"**方向相反**——缓存重建是"必须拿到结果才能返回请求"，定时扫描是"这一轮谁做都行"，若排队等待会让 N 个副本串行执行同一批任务。

> **已完成到哪一步**：**L0（容器化就绪）与 L1（K8s 编排）均已落地**。L1 的清单在 `deploy/k8s/`
> —— Deployment / Service / Ingress / HPA / PDB / ConfigMap / Secret 模板，以及前端镜像（`frontend/Dockerfile`）
> 与 nginx 配置（走 ConfigMap，不烘进镜像）；部署命令、验证步骤与关键取舍见 `deploy/k8s/README.md`。
> **L2（中间件上云）未开始** —— MySQL / Redis / RocketMQ / Nacos 目前仍由 `docker-compose.yml` 编排。
> 完整的可行性与工程量评估见 `work/云原生改造可行性与工程量评估.html`。

## 项目结构

```
src/main/java/com/ecommerce
├── ai          # DeepSeek 客户端、Redis 限流器
├── common      # 统一响应、全局异常、错误码、分页、订单号生成器、下单幂等凭证(OrderTokenService)
├── config      # MyBatis-Plus / Redis / Sentinel / Knife4j / 动态配置 / Web MVC(注册JWT拦截器)
├── security    # JWT 拦截器/签发校验服务/登录上下文(AuthContext)/配置属性
├── controller  # Product / Order / User / AI 售后
├── convert     # MapStruct 转换器
├── dto         # 请求对象
├── entity      # DO 实体
├── enums       # 订单状态机
├── feign       # DeepSeek OpenFeign 客户端 + 熔断降级
├── mapper      # MyBatis-Plus Mapper
├── mq          # RocketMQ 超时关单生产者/消费者 + 定时扫描兜底（@Scheduled 补偿）
├── service     # 业务层
└── vo          # 响应对象
```

```
deploy/
├── k8s/        # K8s 编排清单（L1）：Deployment / Service / Ingress / HPA / PDB / ConfigMap / Secret 模板
│               # 前端 nginx 配置也在其中（走 ConfigMap 挂载，不烘进镜像，改配置无需重建镜像）
└── rocketmq/   # 本地 RocketMQ broker 配置（broker.conf）

frontend/
├── Dockerfile  # 前端多阶段构建：Node 构建 Vite 产物 → nginx-unprivileged 托管
└── .dockerignore
```
