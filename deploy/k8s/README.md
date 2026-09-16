# K8s 部署清单（L1）

容器编排层的清单与部署手册。应用侧的前置改造（探针配置、优雅停机、定时任务选主、镜像加固）见项目根目录的 `Dockerfile` 与 `src/main/resources/application.yml`。

## 文件说明

| 文件 | 作用 |
|---|---|
| `namespace.yaml` | 命名空间 `ecommerce` |
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

无集群时也能校验渲染结果：

```bash
kubectl kustomize deploy/k8s        # 只渲染，不连集群
```

## 前置条件

1. 集群已安装 **ingress-nginx**（清单里 `ingressClassName: nginx`）。从 v1.9 起 snippet 注解默认被禁用，若 `server-snippet` 不生效，见下方"管理端点屏蔽"。
2. 集群已安装 **metrics-server**，否则 HPA 的 TARGETS 会一直显示 `<unknown>`。
3. MySQL / Redis / RocketMQ / Nacos 已就绪（L2，可用云托管），地址写进 `configmap.yaml`。

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

## 验证清单

```bash
# 探针端点（注意 context-path=/api，少写 /api 会 404）
kubectl -n ecommerce exec deploy/ecommerce-backend -- \
  curl -s localhost:8080/api/actuator/health/liveness
kubectl -n ecommerce exec deploy/ecommerce-backend -- \
  curl -s localhost:8080/api/actuator/health/readiness

# 确认未暴露的端点确实是 404（注意：本项目全局异常处理器会把 404 包成
# HTTP 200 + {"code":5000,"message":"系统异常"}，看状态码会误判，要看响应体）

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

## 已知未在真实集群验证的项

诚实标注，避免把"写在清单里"当成"验证过"：

- **清单的 schema 校验**：本机未配置 K8s 集群（`kubectl config current-context` 为空），
  而 `kubectl apply --dry-run=client` 要向 apiserver 拉 openapi 才能校验字段，所以只完成了
  `kubectl kustomize deploy/k8s` 的渲染验证（10 个对象、ConfigMap hash 注入与引用改写均正确）。
  拿到集群后建议先跑 `kubectl apply -k deploy/k8s --dry-run=server` 过一遍字段合法性。
- **`readOnlyRootFilesystem: true`（后端）**：本地无 K8s 环境，未实测。若 Pod 反复 CrashLoopBackOff 且日志提到 `Read-only file system`，先注掉这一行定位是哪个客户端在写盘。
- **HPA 实际扩缩容行为**：需要 metrics-server 和持续压测才能验证，清单只保证配置正确。
- **TLS 证书**：占位 `secretName: ecommerce-tls`，需要 cert-manager 或手动创建。
- **前端 graceful shutdown**：nginx 的优雅关闭依赖 PID 1 收到 SIGTERM，未在集群验证（镜像的 `daemon off` 是前提）。
- **镜像构建**：本机 Docker daemon 未运行，两个 Dockerfile 都未实际构建过镜像。
- **优雅停机（应用侧）**：Windows 本地无法向 JVM 发 SIGTERM（Git Bash 的 kill 不支持 Windows 原生进程），改用 `/actuator/shutdown` 端点触发了同一条 Tomcat `GracefulShutdown` 链路，日志确认输出 `Commencing graceful shutdown` → `Graceful shutdown complete`。**SIGTERM 路径本身仍需在集群里复验**。
