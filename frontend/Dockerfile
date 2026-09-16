# ==================== 前端镜像 ====================
# 多阶段：Node 构建 Vite 产物 → nginx-unprivileged 托管静态文件。
#
# 构建（构建上下文就是 frontend/ 目录）：
#   docker build -t <registry>/ecommerce/frontend:0.1.0 frontend/
#
# 刻意**不**把 nginx 配置 COPY 进镜像：配置由 K8s ConfigMap 挂载
# （源文件 deploy/k8s/frontend-nginx.conf）。这样改缓存策略或超时不需要重新构建镜像，
# 同一份镜像能从测试环境一路推到生产 —— 这是"配置分离"的实际价值。
# 直接 docker run 这个镜像会看到 nginx 默认欢迎页，这是预期行为，不是缺陷。

FROM node:22-alpine AS build
WORKDIR /app

# 先只拷依赖清单再装依赖，利用镜像层缓存：
# 只要 package*.json 没动，改业务代码重新构建时会复用这一层，省掉一次完整依赖安装。
COPY package.json package-lock.json ./

# 走 npmmirror：国内直连 registry.npmjs.org 经常超时，
# 而构建阶段的网络抖动是 CI 里最折磨人的一类偶发失败。
RUN npm ci --registry=https://registry.npmmirror.com --no-audit --no-fund

COPY . .
RUN npm run build

# ==================== 运行阶段 ====================
# 为什么用 nginx-unprivileged 而不是 nginx:alpine：
#   官方 nginx 镜像默认以 root 启动 master 进程 —— 它要绑定 80（特权端口）、
#   把 pid 写到 /var/run、把临时文件写到 /var/cache/nginx。
#   在 runAsNonRoot 的 Pod 里这些全部会失败（Permission denied）。
#   nginx-unprivileged 是官方出的非 root 变体：监听 8080、pid 与临时目录改到 /tmp，
#   开箱就能跑在"非 root + 只读根文件系统"约束下，不需要任何 hack。
FROM nginxinc/nginx-unprivileged:1.27-alpine

# 只拷构建产物。注意 dist 路径由 vite 默认输出决定（frontend/vite.config.js 未改 build.outDir）。
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080

# 基础镜像自带 CMD ["nginx", "-g", "daemon off;"]，这里不覆盖：
#   daemon off 让 nginx 保持前台运行、成为 PID 1，SIGTERM 才能被 nginx master 收到
#   并触发 quit（优雅关闭：处理完在途请求再退出）。
#   如果写成 daemon on，容器会立刻退出（前台没有进程），这是新手最常见的困惑。
