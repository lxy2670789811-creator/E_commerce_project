# Nacos 配置中心（L2 · 配置落地）

本目录是**配置源**：三个 dataId 的内容在此版本化，可 review、可回滚、可重复灌入。
运行时代码仍是同一个 jar，配置从 Nacos 下发。

```
deploy/nacos/
├── e-commerce-order-backend.yaml   # 主配置（dataId 默认 = ${spring.application.name}.yaml）
├── ecommerce-common.yaml           # shared-configs   —— 多服务共用约定
├── ecommerce-business.yaml         # extension-configs —— 业务动态参数（核心）
└── README.md
```

对应 `src/main/resources/bootstrap.yml` 里的声明：

| dataId | 加载方式 | 内容 | 优先级 |
|---|---|---|---|
| `e-commerce-order-backend.yaml` | 主配置 | 日志级别 | 最高 |
| `ecommerce-common.yaml` | `shared-configs` | Jackson 序列化约定 | 中 |
| `ecommerce-business.yaml` | `extension-configs` | 全部业务动态参数（34 项） | 中 |

---

## 为什么需要这个目录

改造前这里存在一个**"配置分离只有壳、没有内容"**的问题：

`bootstrap.yml` 声明了三个 dataId，但 Nacos 里**一个都没创建**。而 Nacos 客户端在配置为空或连不上时
**不会让应用启动失败**，只在日志里打一行：

```
Ignore the empty nacos configuration and get it based on dataId[ecommerce-common.yaml] & group[ECOMMERCE_GROUP]
```

然后安静地回落到代码默认值。结果是"参数走 Nacos 热更新"这个能力一直只写在文档里，
**从未真正生效** —— 属于最难发现的一类问题：服务一切正常，只有你以为的那个开关是假的。

同样的静默失败还有两处，见下方「已踩过的坑」。

---

## 灌入

```bash
# 1) 确认 Nacos 在跑（docker-compose 里的 nacos 服务）
curl -s http://127.0.0.1:8848/nacos/v1/console/namespaces

# 2) 先看会做什么，不写入
python tools/nacos_push_config.py --dry-run

# 3) 推送 + 读回校验
python tools/nacos_push_config.py --verify

# 指定地址/命名空间（例如从宿主机推给 kind 里的应用）
python tools/nacos_push_config.py --server 192.168.65.254:8848 --verify
```

脚本是**幂等覆盖**语义，可反复执行。退出码 0 = 全部成功。

> Windows 上如果控制台报 `UnicodeEncodeError`，前面加 `PYTHONIOENCODING=utf-8`。

---

## 已踩过的坑（都表现为"看起来成功了"）

### 1. `tenant=public` 会静默丢配置

Nacos 的 **public 命名空间的真实 ID 是空字符串**，控制台里的 "public" 只是展示名：

```json
{"namespace":"", "namespaceShowName":"public", "configCount":0}
```

若照抄控制台传 `tenant=public`，Nacos 会把它当成一个不存在的命名空间 ID：

- 发布接口**照样返回 `true`**
- 但配置**不落库** —— 读回 404，控制台 `configCount` 恒为 0

排查时极易误判成"网络问题"或"写入延迟"。**正确做法：public（或空）时不传 `tenant` 参数**，
`tools/nacos_push_config.py` 里的 `_tenant_param()` 已处理。

### 2. 发布后立刻读回，可能 404

Nacos 2.x 在「发布 → 立刻读回」时，偶尔读路径还不可见（发布已回 `true`、`configCount` 也正常）。
不重试会把这种时序抖动误判成发布失败。脚本的 `verify_one()` 已内置重试（5 次 × 0.6s）。

### 3. 空配置不会导致启动失败

这是最该记住的一条：**Nacos 客户端连不上配置中心、或配置为空，应用依然能正常启动**，
只是所有参数悄悄用代码默认值。所以"服务起来了"**不能**作为"配置生效了"的证据。

验证配置真的生效，只有两个办法：

```bash
# 办法一：看启动日志里还有没有 "Ignore the empty nacos configuration"
kubectl logs -n ecommerce deploy/ecommerce-backend | grep -a "Ignore the empty"

# 办法二（推荐）：改一个参数，观察行为是否变化 —— 见下方「热更新验证」
```

---

## 热更新验证（面试可现场演示）

选 `product-detail-expire-seconds` 最直观：改完不重启，直接看 Redis 里缓存键的 TTL 变化。

```bash
# 0) 前提：改之前先删掉旧缓存键，否则会命中旧值、不写新 TTL
docker exec ecommerce-redis redis-cli DEL ecommerce:product:detail:1

# 1) 在 Nacos 控制台把 ecommerce-business.yaml 的
#    product-detail-expire-seconds 从 3600 改成 120，点「发布」

# 2) 触发一次读接口（走 Pod，确保读的是应用写进去的缓存）
kubectl -n ecommerce exec deploy/ecommerce-backend -- \
  curl -s -o /dev/null "localhost:8080/api/product/detail?id=1"

# 3) 看 TTL：应在 120 ~ 420 之间（120 + jitter[0,300]），说明新 TTL 已生效
docker exec ecommerce-redis redis-cli TTL ecommerce:product:detail:1
```

⚠️ 若想看到**确定的**数值，把 `product-detail-expire-jitter-seconds` 同时改成 `0`，
此时 TTL 就精确等于 `product-detail-expire-seconds`。

> 注意 `spring.data.redis` 指向的是**宿主机原生 Redis**（`127.0.0.1:6379` 比容器的 `0.0.0.0:6379` 更具体），
> 不是 `ecommerce-redis` 容器。本机两个 Redis 并存，查错实例会得到"缓存没写进去"的假结论。

---

## 安全约定

**敏感信息绝不进配置中心。** DB/Redis 密码、`JWT_SECRET`、DeepSeek API Key 一律走
K8s Secret → 环境变量注入。配置中心只放可公开的业务开关与阈值。

理由：Nacos 的配置是明文存储、控制台可读、还能被有权限的人随时改。把密钥放进去，
等于把"密钥泄露面"从 K8s Secret 扩大到了配置中心 + 控制台账号 + 审计日志。

因此本目录下的 YAML **可以**入库（不含敏感值），`deploy/k8s/secret.yaml` **绝不**入库。
