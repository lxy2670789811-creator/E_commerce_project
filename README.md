# 电商订单后端系统（e-commerce-order-backend）

一个面向简历的 Java 后端电商订单系统，覆盖**商品、订单、用户、AI 售后**四大模块，
沉淀了分布式锁防超卖、缓存一致性、多级降级、RocketMQ 延迟消息、分页、多环境配置与测试等工程实践。

## 功能特性

| 模块 | 亮点 |
| ---- | ---- |
| 商品 | Cache-Aside 缓存（Redis 主动删缓存保证一致性）、逻辑删除、分页查询 |
| 订单 | **三层防超卖**：Sentinel 限流 → Redisson 按商品维度分布式锁 → DB 原子扣减（`stock >= quantity`） |
| 订单 | 完整状态机：待支付 → 已支付 → 已发货 → 已完成 / 已取消（支付回调幂等） |
| 订单 | **RocketMQ 延迟消息超时自动关单**（事务提交后发送，消费端幂等，库存回滚） |
| 用户 | 极简登录 + 收货地址管理（默认地址互斥） |
| AI 售后 | DeepSeek 大模型智能分析 + **五层保护**：动态开关 → Sentinel 熔断 → Redis 滑动窗口限流 → Feign 熔断 → 业务降级"待人工审核" |
| 工程化 | 统一响应/全局异常、MapStruct、Knife4j 接口文档、Nacos 动态配置、多环境 profile、Docker Compose、35 个测试（含并发防超卖集成测试） |

## 技术栈

- **JDK 17 + Spring Boot 3.2.x + Maven**
- Spring Cloud Alibaba：Nacos 配置中心、Sentinel 限流熔断、OpenFeign
- MyBatis-Plus + MySQL 8 + Redis(Redisson)
- RocketMQ（延迟消息）
- DeepSeek 大模型 API（OpenAI 兼容协议）
- Knife4j(SpringDoc OpenAPI)、MapStruct、Lombok、Hutool
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

### 4. 启动后端

```bash
# 默认使用 dev 环境（application.yml -> application-dev.yml）
mvn spring-boot:run
# 或
java -jar target/e-commerce-order-backend-1.0.0.jar
```

初始化数据库（首次）：执行 `src/main/resources/sql/schema.sql`（建库建表 + 演示数据）。

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
mvn test
```

共 **35 个测试**，重点：

- `OrderConcurrencyIntegrationTest`：真实 MySQL + Redis 并发防超卖（40 线程抢 20 库存 → 恰好 20 单、库存归 0、无超卖）
- `OrderServiceImplTest`：下单/取消/支付回调/发货/完成/超时关单等 21 个核心路径
- `OrderNoGeneratorTest`：订单号格式 + 5 万连续/并发唯一性
- `DeepSeekClientTest`：AI 解析、重试、降级
- `AiRateLimiterTest`：限流放行/拒绝/Redis 故障降级

> 集成测试使用独立测试库 `ecommerce_test`（自动创建）与 Redis DB15，不污染开发数据；
> 测试 profile 已禁用 Nacos/Sentinel/RocketMQ，无需额外中间件。

## 多环境配置

| Profile | 文件 | 说明 |
| ------- | ---- | ---- |
| `dev`（默认） | `application-dev.yml` | 本地开发：localhost 中间件、SQL 日志、debug 日志 |
| `prod` | `application-prod.yml` | 生产：敏感配置必须环境变量注入、关闭 SQL 日志 |
| `test` | `src/test/resources/application-test.yml` | 测试专用（独立库 + 禁外部中间件） |

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
2. **缓存一致性**：Cache-Aside + 写后删缓存（而非更新缓存），避免并发覆盖旧值；Redis 异常降级查库不影响主流程。
3. **超时自动关单**：下单事务提交后发送 RocketMQ 延迟消息（延迟级别可动态配置），消费端幂等关单、回滚库存。
4. **AI 售后降级**：动态开关 → Sentinel 慢调用/异常比例熔断 → Redis 滑动窗口限流（Lua 原子）→ Feign 熔断 → "待人工审核"兜底，AI 完全不可用时接口仍可用。
5. **订单号唯一性**：`ORD + 秒级时间戳 + 完整雪花ID`，并发的订单号测试验证无重复。

## 项目结构

```
src/main/java/com/ecommerce
├── ai          # DeepSeek 客户端、Redis 限流器
├── common      # 统一响应、全局异常、错误码、分页、订单号生成器
├── config      # MyBatis-Plus / Redis / Sentinel / Knife4j / 动态配置
├── controller  # Product / Order / User / AI 售后
├── convert     # MapStruct 转换器
├── dto         # 请求对象
├── entity      # DO 实体
├── enums       # 订单状态机
├── feign       # DeepSeek OpenFeign 客户端 + 熔断降级
├── mapper      # MyBatis-Plus Mapper
├── mq          # RocketMQ 超时关单生产者/消费者
├── service     # 业务层
└── vo          # 响应对象
```