# K8s 部署清单（L1 + L2）

容器编排层的清单与部署手册。应用侧的前置改造（探针配置、优雅停机、定时任务选主、镜像加固）见项目根目录的 `Dockerfile` 与 `src/main/resources/application.yml`。

## 文件说明

| 文件 | 作用 |
|---|---|
| `namespace.yaml` | 命名空间 `ecommerce` |
| `middleware-external.yaml` | **L2**：把云托管中间件接进集群内 DNS（5 个 ExternalName Service） |
| `configmap.yaml` | 后端非敏感配置（DB/Redis/MQ 地址等），键名与配置里的 `${...}` 占位符一一对应 |
| `secret.example.yaml` | **模板**，敏感配置。复制成 `secret.yaml` 填真实值后手动 apply，`secret.yaml` 已被 `.gitignore` 排除 |
| `deployment.yaml` | 后端 Deployment（探针、资源、宽限期、preStop 全在这里） |
| `service.yaml` | 后端 + 前端的 ClusterIP Service |
| `hpa.yaml` | 后端 HPA（2~6 副本，CPU 70%） |
| `pdb.yaml` | PodDisruptionBudget，给节点排水兜底 |
| `frontend-deployment.yaml` | 前端 Deployment（nginx-unprivileged） |
| `frontend-nginx.conf` | 前端 nginx 配置**唯一来源**，由 kustomization 生成 ConfigMap |
| `ingress.yaml` | 入口路由 + 管理端点屏蔽 + TLS |
| `kustomization.yaml` | 部署入口，`kubectl apply -k deploy/k8s` |
| `../overlays/kind-verify/` | **L2 验证用**：本地 kind 覆盖层（把 ExternalName 指向宿主机、换本地镜像、降副本）。**不是生产部署路径** |
| `../overlays/kind-verify/coredns-hosts.yaml` | **L2 验证用**：给 CoreDNS 补 `host.docker.internal` 静态记录，单独 `kubectl apply -f`（集群级改动，不能混进 kustomization，否则 CoreDNS 会被改到 `ecommerce` 命名空间） |

无集群时也能校验渲染结果：

```bash
kubectl kustomize deploy/k8s        # 只渲染，不连集群
python tools/verify_k8s_references.py   # 校验配置引用的 DNS 名与 Service 是否对得上
```

## 前置条件

1. 集群已安装 **ingress-nginx**（清单里 `ingressClassName: nginx`）。从 v1.9 起 snippet 注解默认被禁用，若 `server-snippet` 不生效，见下方"管理端点屏蔽"。
2. 集群已安装 **metrics-server**，否则 HPA 的 TARGETS 会一直显示 `<unknown>`。
3. 云托管中间件已开通，且 `middleware-external.yaml` 里的 5 个 `externalName` 已替换成真实地址（见下方「L2：中间件落地」）。

## 部署

```bash
# 1. 改镜像地址（两处）：deployment.yaml、frontend-deployment.yaml
#    改域名（一处）：ingress.yaml 里的 shop.example.com

# 2. 先建 Secret（必须先于 Deployment，否则 Pod 卡在 CreateContainerConfigError）
cp deploy/k8s/secret.example.yaml deploy/k8s/secret.yaml
# 编辑 secret.yaml 填入真实值；JWT_SECRET 用 `openssl rand -base64 48` 生成
kubectl apply -f deploy/k8s/secret.yaml

# 3. 部署其余清单
kubectl apply -k deploy/k8s

# 4. 看状态
kubectl -n ecommerce get pods -w
kubectl -n ecommerce get hpa
```

## L2：中间件落地（云托管接入）

### 为什么选云托管而不是自建 StatefulSet

MySQL / Redis / RocketMQ / Nacos 都是**有状态**组件。自建要处理 PVC 与存储类、备份恢复、故障转移、版本升级、监控告警——这些是运维工作量，对 Java 后端岗的边际收益低。真正的难点不在"能不能跑起来"，而在"长期跑不出事"。

本项目采取**混合模式**：本地开发继续用 `docker-compose.yml`（环境一致、零成本），生产用云托管（高可用与备份交给厂商）。

代价要说清楚：云托管意味着**网络多了一跳**（跨 VPC 或走公网），延迟高于同机房自建；也更贵。对求职项目这个体量，这个代价是划算的。

### 接入方式：ExternalName Service

`middleware-external.yaml` 用 5 个 `ExternalName` Service 把云托管实例接进集群内 DNS，`configmap.yaml` 里的连接串直接引用这些名字：

```
configmap.yaml 里的          由谁提供
mysql.ecommerce.svc.cluster.local:3306          ->  middleware-external.yaml 的 mysql
redis.ecommerce.svc.cluster.local:6379          ->  middleware-external.yaml 的 redis
rocketmq-namesrv.ecommerce.svc.cluster.local    ->  middleware-external.yaml 的 rocketmq-namesrv
nacos.ecommerce.svc.cluster.local:8848          ->  middleware-external.yaml 的 nacos
sentinel-dashboard.ecommerce.svc.cluster.local  ->  middleware-external.yaml 的 sentinel-dashboard
```

**这一层的价值**：切换云厂商或区域时，只改 `middleware-external.yaml` 里的 `externalName`，`configmap.yaml` 一个字段都不用动。

> **注意**：在 L2 之前，`configmap.yaml` 里这 5 个名字是**引用不到的** —— 配置写了、Service 不存在。这种悬空引用 `kustomize` 和 `kubectl apply` 都**不会报错**（ConfigMap 的值只是字符串，K8s 不做跨对象校验），只在 Pod 启动后以 `UnknownHostException` 的形式暴露。所以加了 `tools/verify_k8s_references.py` 在 apply 前静态查出来。

### ExternalName 的三个限制（面试高频追问）

机制：ExternalName **没有 ClusterIP、不经过 kube-proxy**，只在 CoreDNS 里生成一条 CNAME 记录，客户端拿到 CNAME 后自己解析、自己直连，数据平面完全不参与。

| 限制 | 后果 | 何时必须换方案 |
|---|---|---|
| **端口不能重映射** | 客户端连的端口号会原样带到远端主机，不支持 `targetPort` 转换 | 云侧端口与集群内约定不一致时 |
| **NetworkPolicy 管不住** | 没有 ClusterIP，流量不经过 Service；一旦给 Pod 加上 egress 规则，这些流量会被**整体拒绝** | 需要按 selector 精确管控出流量时 |
| **externalName 必须是 DNS 名** | CNAME 指向 IP 非法，apiserver 校验会拒绝 | 云厂商只给了 IP 时 |

三个限制的共同解法是 `middleware-external.yaml` 末尾注释里的**无 selector ClusterIP + 手写 Endpoints** 方案（代价是 IP 要写死、且没有健康检查）。文件里有可直接放开的代码。

### 云侧必须配合做的三件事

1. **网络放行**：Pod 出集群访问云实例，需要把**节点出口 IP 或 NAT 网关 IP** 加进云侧白名单；跨 VPC 的走对等连接或 PrivateLink。
2. **端口要放全**：Nacos 2.x 需要 **8848 和 9848** 两个端口（9848 = 8848+1000，由客户端自动推导，**没有独立配置项**）。只放行 8848 的现象是：拉配置能通、配置推送静默失效——"改了配置不生效，重启服务又能读到最新的"。
3. **连接数上限要重算**：云 RDS 的 `max_connections` 通常远低于自建 MySQL 的 151。`hpa.yaml` 的 `maxReplicas: 6` 是按 `Hikari 15 × 6 = 90 < 151` 反推的，**换成云实例后要用实际规格重算**（还要扣掉厂商预留与运维连接）。

### 一个必须知道的隐蔽坑：CoreDNS 解析不了云内网域名

CoreDNS 对集群外的名字会**转发给 Node 的 `/etc/resolv.conf`** 解析。如果云实例的域名是**厂商的 VPC 内网域名**（只能由 VPC 内 DNS 解析），Pod 里会拿到 `NXDOMAIN`：

- 现象：`kubectl exec` 进去 `nslookup` 失败、应用报 `UnknownHostException`，但 `kubectl get svc` 一切正常
- 排查方向极易跑偏：会去怀疑白名单、SSL、密码，而问题在 DNS
- 解法二选一：给 CoreDNS 配 `forward` 指向 VPC DNS，或改 NodeLocal DNSCache 方案

### 本地 compose ↔ 云端托管的切换对照

| 配置项 | 本地 compose 值 | 云端托管值 | 改在哪 |
|---|---|---|---|
| `MYSQL_URL` | `jdbc:mysql://mysql:3306/...` | `jdbc:mysql://mysql.ecommerce.svc.cluster.local:3306/...` | `configmap.yaml` |
| `REDIS_HOST` | `redis` | `redis.ecommerce.svc.cluster.local` | `configmap.yaml` |
| `ROCKETMQ_NAME_SERVER` | `rocketmq-namesrv:9876` | `rocketmq-namesrv.ecommerce.svc.cluster.local:9876` | `configmap.yaml` |
| `NACOS_SERVER_ADDR` | `nacos:8848` | `nacos.ecommerce.svc.cluster.local:8848` | `configmap.yaml` |
| 云实例真实地址 | 无（容器内） | 厂商给的域名 | **`middleware-external.yaml`** |
| 账号密码 | `123456` | 云实例的账号 | `secret.yaml`（不入库） |

注意 `useSSL`：本地 compose 是 `useSSL=false`，云端 RDS 一般要求 SSL 并需要提供 CA 证书。

## 验证清单

```bash
# 探针端点（注意 context-path=/api，少写 /api 会 404）
kubectl -n ecommerce exec deploy/ecommerce-backend -- \
  curl -s localhost:8080/api/actuator/health/liveness
kubectl -n ecommerce exec deploy/ecommerce-backend -- \
  curl -s localhost:8080/api/actuator/health/readiness

# 确认未暴露的端点确实是 404（2026-09-16 修复后语义已正确：
# GlobalExceptionHandler 曾把框架异常统一包成 HTTP 200 + {"code":5000,"message":"系统异常"}，
# 已改为按语义分流 —— 404 就是 404、405 带 Allow 头。若这里看到 200，说明代码回退了）

# 滚动更新不丢请求：另开一个窗口持续压测，然后在第三个窗口执行
kubectl -n ecommerce rollout restart deploy/ecommerce-backend
kubectl -n ecommerce rollout status deploy/ecommerce-backend

# 优雅停机：观察日志中的 GracefulShutdown 输出
kubectl -n ecommerce delete pod <pod-name>
kubectl -n ecommerce logs deploy/ecommerce-backend --tail=50 | grep -i graceful
# 期望看到：
#   Commencing graceful shutdown. Waiting for active requests to complete
#   Graceful shutdown complete
```

## 关键决策与坑

### 1. 探针分组的 include 必须显式写（本项目最容易被忽略的一处）

打开 `probes.enabled` 之后，**liveness 与 readiness 两个分组默认都包含全部健康指示器**（db / redis / nacosConfig / sentinel / diskSpace / ping），实测两组列出的组件完全相同。

这意味着：不显式写 `management.endpoint.health.group.liveness.include`，liveness 探针实际就在探测数据库——MySQL 抖动会让 K8s 逐个重启全部 Pod，把"暂时不可用"放大成"全站崩溃"。

本项目已在 `application.yml` 里收敛为：

```yaml
group:
  liveness:
    include: livenessState      # 只认进程自身状态，任何外部依赖都不许进
  readiness:
    include: readinessState     # 只认应用是否就绪（ACCEPTING_TRAFFIC）
```

readiness 刻意**不含** db/redis：本项目的依赖抖动都有降级路径（AI 有 fallback、缓存有降级直查、MQ 不可用有定时扫描兜底），把 db 放进来只会让"MySQL 抖动 → 所有 Pod 被摘 → 入口 503"，把局部故障放大成全站故障。依赖不可用应由告警和业务错误率发现。若要改成保守策略，写成 `readinessState,db,redis` 即可，但要知道这个代价。

### 2. requests.cpu 有值、limits.cpu 留空

CPU 是可压缩资源，设了 limit 会触发 cgroup throttling。而 JVM 的 GC 线程和 JIT 编译线程需要突发多核，被限流的表现是 **p99 延迟周期性尖刺，而 CPU 使用率看起来并不高**——非常难查。内存 limit 必须设，因为镜像里的 `-XX:MaxRAMPercentage=70.0` 靠它算堆大小。

### 3. preStop 是 sleep，不是"优雅退出"

K8s 删 Pod 时，发 SIGTERM 与从 Endpoint 摘除是**并行**的。存在一个竞态窗口：进程已经开始停机，但 kube-proxy 还没更新转发规则，新请求仍会打过来。`sleep 8` 用来吸收这个窗口，之后才走应用的优雅停机流程。配套要求：`terminationGracePeriodSeconds(45s) > spring.lifecycle.timeout-per-shutdown-phase(30s)`。

### 4. maxReplicas = 6 是被数据库连接数卡出来的

Hikari `maximum-pool-size=15` 是**每个实例**的池。6 副本 = 90 连接，加上滚动更新瞬间多出的 1 个 Pod ≈ 105，而 MySQL 默认 `max_connections=151`，还要留给运维工具和其它客户端。扩容前先算这笔账，否则第二次扩容就会开始报 `Too many connections`。

顺带一个预期边界：HPA 扩的是应用副本，不是数据库吞吐。本项目下单链路的瓶颈是 MySQL 行锁 + Redisson 分布式锁（`createOrder` 天然串行化），加 Pod 不会让下单变快。**HPA 的实际收益在商品查询、AI 售后这类无锁读链路**。

### 5. 前端用 nginx-unprivileged，监听 8080

官方 `nginx:alpine` 默认以 root 启动（要绑 80 特权端口、写 `/var/run` 和 `/var/cache/nginx`），在 `runAsNonRoot` 下直接起不来。`nginxinc/nginx-unprivileged` 是官方非 root 变体：监听 8080、pid 与临时目录改到 `/tmp`，开箱可用。Service 对外仍是 80。

### 6. nginx 配置用 configMapGenerator 而不是手写 ConfigMap

generator 会在 ConfigMap 名字里加内容 hash。改配置 → 名字变 → Deployment 引用变 → **自动触发一次滚动更新**。手写 ConfigMap 的话必须自己记得 `kubectl rollout restart`，而人一定会忘。

### 7. 管理端点屏蔽

L0 决策让 actuator 与业务共用 8080（理由见 `application.yml` 末尾注释），代价是 Ingress 的 `/api` 前缀会把它一起带出去，因此必须在入口挡掉：`ingress.yaml` 用 `server-snippet` 返回 403，前端 nginx 里再配一条 `location ^~ /api/actuator { return 404; }` 兜底（万一 snippet 被禁用，或有人直连前端 Pod 端口）。

若集群不允许 snippet 注解，改用"最长前缀引流到前端"的方案（`ingress.yaml` 注释里给了写法）。

### 8. 部署后一定要核对的一处

`/api/actuator/info` 在加 `info.*` 属性之前返回的是**空对象 `{}`**——端点不会自动带出版本信息。本项目已在 `application.yml` 里补了 `info.app.*` / `info.build.version`，部署后 curl 一次即可核对跑的是哪个版本、哪个 profile。

## L2 实机验证结果（kind 集群，2026-09-16）

用 `deploy/overlays/kind-verify/` 在本地 kind 集群里真实 **apply + 跑起来**过一遍，专门验证那些"只靠清单渲染证明不了"的东西。
环境：kind v0.33.0 + K8s v1.37.0 单节点，中间件走 ExternalName 打回宿主机。

| 验证项 | 手段 | 结果 |
|---|---|---|
| 清单能被 apiserver 接受 | `kubectl apply -k` | ✅ 全部对象 created/configured，无字段非法 |
| ExternalName 必须是域名 | 由 apiserver 强制 | ✅ 5 个 Service 的 EXTERNAL-IP 均为 `host.docker.internal` |
| ExternalName → CNAME → 外部地址 全链路 | Pod 内真实连接 | ✅ 应用日志出现 `R:redis.ecommerce.svc.cluster.local/192.168.65.254:6379` |
| ConfigMap hash 触发滚动更新 | configMapGenerator | ✅ 生成名 `ecommerce-frontend-nginx-k9b4f5tffh`，Deployment 引用已被改写 |
| `runAsNonRoot` / `readOnlyRootFilesystem` / seccomp | 真实调度运行 | ✅ 后端（非 root uid 10001）与前端（nginx-unprivileged）都正常起来，未见 `Read-only file system` |
| 探针真的被 kubelet 调用 | startupProbe 失败事件 | ✅ 启动期连续报 `Startup probe failed: Get "http://.../api/actuator/health/readiness": connection refused`，证明 kubelet 确在打这个路径 |
| **探针分组语义（L1 核心决策）** | 临时置 `MANAGEMENT_ENDPOINT_HEALTH_SHOW_COMPONENTS=always` 读组件清单 | ✅ `/health/liveness` → `{"components":{"livenessState"}}`；`/health/readiness` → `{"components":{"readinessState"}}`。聚合 `/health` 里的 db/redis/nacosConfig/sentinel/diskSpace/ping **一个都没进探针分组** |
| HTTP 状态码语义 | 集群内 curl | ✅ 未暴露端点 `env`/`beans` → **404**（不是 200 + `code5000`） |
| profile 生效 | `/api/actuator/info` | ✅ 返回 `{"app":{"name":"e-commerce-order-backend","profile":"prod"},"build":{"version":"1.0.0"}}` |
| **优雅停机（真实 SIGTERM）** | `kubectl delete pod` | ✅ 日志依次输出 `Commencing graceful shutdown. Waiting for active requests to complete` → `Graceful shutdown complete` → HikariCP `Shutdown initiated/completed` |
| 业务链路端到端 | 集群内 curl `/api/product/detail?id=1` | ✅ 返回 MySQL 真实数据；Redis 里随后出现 `ecommerce:product:detail:1`，**Cache-Aside 写入真的生效** |
| 依赖缺失时的降级声明 | 观察 | ⚠️ RocketMQ Broker 全程不在线（容器 Exited 11 天），应用照常启动、商品链路正常 —— 「MQ 不可用不影响下单主流程」得到部分印证（**未做**关单时序验证） |

### 这次验证暴露/澄清的三个坑

**1. `host.docker.internal` 在集群里默认解析不了 —— 而且它不是靠 DNS 提供的**

Docker Desktop 是把 `host.docker.internal` 写进**容器自己的 `/etc/hosts`**，而不是靠 DNS 应答。kind 节点与 Pod 的 `/etc/hosts` 里都没有它，Docker 内嵌 DNS（127.0.0.11）对它直接回 **NXDOMAIN**（已用普通容器 `nslookup` 复现）。于是 ExternalName 的 CNAME 链会断在最后一跳，现象是 `UnknownHostException` / `RedisConnectionException`，看起来像应用 bug。

排查时**一定要做对照实验**，否则容易误判：`/etc/nsswitch.conf` 是 `hosts: files dns`（说明 getent 确实会查 DNS），`getent hosts www.baidu.com` 正常（说明 DNS 链路本身通），而 `getent hosts host.docker.internal` 为空 —— 三者合起来才能定住"是这个名字没人答"，而不是"DNS 坏了"。

解法见 `overlays/kind-verify/coredns-hosts.yaml`：用 CoreDNS 的 `hosts` 插件补一条静态记录，ExternalName 链路就能原样走通，不必为了绕开 DNS 而用 `hostAliases` 把 ExternalName 整个短路掉（那样就验证不到 CNAME 生成得对不对了）。

**2. 地址可达性 ≠ DNS 可解析，而且"网关 IP"和"宿主机"不是一回事**

从 kind 节点测宿主机端口，两组地址结果完全不同：

```
172.20.0.1（kind 网桥网关） : 6379/8848/9876/8858 OPEN   ← 这些是**容器**且发布了端口
                             : 3306               CLOSED ← MySQL 是 **Windows 原生服务**，没发布端口
192.168.65.254（Docker 认定的宿主机） : 3306/6379/8848/9876 全 OPEN
```

`172.20.0.1:3306` 不通这件事**不会**给出任何额外线索，很容易被误判成"MySQL 没启动"，实际是地址选错了。`192.168.65.254` 可以用 `docker run --add-host=host.docker.internal:host-gateway ...` 打出来。

**3. 本机同时存在两个 Redis，`localhost:6379` 和"宿主机 6379"不是同一个实例**

```
redis-server.exe      (原生 Windows)  绑 127.0.0.1:6379   version 5.0.14.1
com.docker.backend.exe (容器 ecommerce-redis 发布) 绑 0.0.0.0:6379  version 7.4.11
```

Windows 上 `127.0.0.1:6379` 比 `0.0.0.0:6379` 更具体，所以 Pod 经 `host.docker.internal:6379` 打到的其实是**原生那个**。这一点是应用自己的健康端点帮我确认的 —— 聚合 `/health` 报 `redis: {"version":"5.0.14.1"}`，正是原生实例的版本。

⚠️ 排查缓存问题时**先确认自己在查哪个 Redis**。我第一次查的是容器实例，`DBSIZE=1`、缓存键不存在，差点误判成"缓存写入被静默降级吞掉"；换到原生实例后，Pod 写入的 `ecommerce:product:detail:1/2/3` 与 `ecommerce:product:list:default:version` 都在。

### 本次验证顺带修掉的一个真实缺陷

集群里第一次拉起时**后端启动直接失败**，报错链路有 7 层，根因藏在最里面：`ERR Client sent AUTH, but no password is set`。

- **根因**：`redisson-spring-boot-starter 3.27.2` 把 `spring.data.redis.password` 原样交给 Redisson（`RedissonAutoConfiguration` 第 147 行取值、第 315 行 `setPassword`），**没有空值守卫**；而 Redisson 判"要不要发 AUTH"看的是 password **是否为 null**，不是"是否为空串"。`application-prod.yml` 里的 `${REDIS_PASSWORD:}` 在环境变量缺失时解析成**空串**，于是发出一条 `AUTH ""`，被无密码的 Redis 顶回来。
- **为什么 dev 一直没暴露**：`application-dev.yml` 里**根本没有 password 这一行**，绑定结果是 `null`，所以不发 AUTH。同一个 Redis，dev 连得上、prod 连不上，和网络、白名单、密码全无关。
- **修法**：在 `RedisConfig` 里加一个 `RedissonAutoConfigurationCustomizer`，把空白密码归一化成 `null`（有 `RedisConfigPasswordNormalizerTest` 6 个用例锁住，含防"修过头"的对照组，已做变异验证）。
- **顺带一个 API 坑**：`Config` 有 `isSingleConfig()` / `isClusterConfig()` / `isSentinelConfig()`，但**没有** `isSingleServer()`；且三种模式的配置是互斥的，在集群配置上调 `useSingleServer()` 会抛 `IllegalStateException("cluster servers config already used!")` —— 所以清密码前必须先判模式。

### 另一处值得记录的观察：Nacos 配置其实一直是空的

集群内启动日志反复出现：

```
WARN c.a.c.n.c.NacosPropertySourceBuilder : Ignore the empty nacos configuration and get it based on dataId[ecommerce-common.yaml] & group[ECOMMERCE_GROUP]
```

直连 Nacos 核对后确认：本地 Nacos **只有 `public` 命名空间，且 `configCount: 0`**，清单里配置的三个 dataId（`e-commerce-order-backend.yaml` / `ecommerce-common.yaml` / `ecommerce-business.yaml`）全部 404。

这正是 `bootstrap.yml` 注释里警告过的那种"最难发现的配置问题"——**应用照常启动，但所有声称"走 Nacos 热更新"的参数实际都在用代码默认值**，从日志到现象都没有显式失败。本项目的 `BusinessDynamicConfig` 参数（缓存 TTL、击穿预算、闸门上限等）目前全部来自代码默认值。若要真正启用热更新，需要先把配置发布进 Nacos。

## 仍未在真实集群验证的项

诚实标注，避免把"写在清单里"当成"验证过"：

- **HPA 实际扩缩容行为**：kind 未装 metrics-server，HPA 拿不到指标。清单只保证配置正确，扩容阈值与冷却行为需要带 metrics-server 的集群 + 持续压测才能验证。
- **Ingress 实际路由**：kind 未装 ingress-nginx 控制器，`ingress.yaml`（含 `server-snippet` 的 actuator 403 规则）只过了 apiserver 校验，**未验证真实转发与 snippet 是否被控制器接受**。
- **滚动更新不丢请求**：需要"持续压测 + 同时滚动"才能看出，本次未做（`terminationGracePeriodSeconds(45s) > preStop(8s) + 优雅停机(30s)` 的时序关系已由配置保证，优雅停机本身已单独验证）。
- **TLS 证书**：占位 `secretName: ecommerce-tls`，需要 cert-manager 或手动创建。
- **前端 nginx 优雅关闭**：未单独验证（依赖 PID 1 收到 SIGTERM，不依赖 `daemon off` 之外的东西）。
- **订单超时关单的完整时序**：本次 RocketMQ Broker 不在线，验证了"MQ 不可用不影响启动"，但**没有**验证"延迟消息发出的关单"与"定时扫描兜底关单"两条路径的实际时序与去重。

