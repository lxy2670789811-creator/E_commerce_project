"""验证 actuator 白名单在硬鉴权模式下确实放行 K8s 探针。

背景：JwtAuthInterceptor 拦截 /**，而 K8s 的 liveness/readiness 探针是集群内部发起、
**不带 JWT** 的 GET。修复前 /actuator/** 不在 WebConfig 白名单里 —— 一旦
ecommerce.jwt.required 切成 true，探针会被 401 掉，readiness 永远失败，
所有 Pod 被摘出 Service（现象：Pod 全 Running 却不接流量）。

本脚本需应用以 --ecommerce.jwt.required=true 启动。

用法：python verify_jwt_whitelist.py
"""
import sys
import time
import urllib.error
import urllib.request

API = 'http://127.0.0.1:8080/api'
FAIL = []


def wait_ready(timeout_sec=180):
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        try:
            r = urllib.request.urlopen(API + '/actuator/health', timeout=3)
            if r.status == 200:
                return True
        except Exception:
            pass
        time.sleep(3)
    return False


def probe(expect, method, path, label):
    try:
        r = urllib.request.urlopen(urllib.request.Request(API + path, method=method), timeout=10)
        status, body = r.status, r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        status, body = e.code, e.read().decode('utf-8', 'replace')
    except Exception as e:
        print('  ERR  %-40s %s' % (label, e))
        FAIL.append(label)
        return
    ok = status == expect
    if not ok:
        FAIL.append(label)
    print('  %s  HTTP %-4s %-40s %s %s' % ('PASS' if ok else 'FAIL', status, label, method, path))
    print('        body=%s' % body[:120].replace('\n', ' '))


if not wait_ready():
    print('应用未就绪')
    sys.exit(1)

print('硬鉴权模式（ecommerce.jwt.required=true）已启动\n')
print('--- 白名单必须放行（否则 K8s 探针全挂）---')
probe(200, 'GET', '/actuator/health', 'actuator 健康聚合')
probe(200, 'GET', '/actuator/health/liveness', 'K8s liveness 探针')
probe(200, 'GET', '/actuator/health/readiness', 'K8s readiness 探针')
probe(200, 'GET', '/actuator/info', 'actuator info')
probe(404, 'GET', '/actuator/metrics', '未暴露端点（白名单内 → 404）')

print('\n--- 对照组：非白名单路径必须被 401 拦住（证明硬鉴权确实生效）---')
probe(401, 'GET', '/order/list?userId=1', '业务接口')
probe(401, 'GET', '/product/list', '业务接口')

print('\n--- 边界：非白名单的未映射路径会被 401 拦住（实测，与直觉相反）---')
print('    ⚠️ 原以为"没有 handler ⇒ 拦截器不执行 ⇒ 直接 404"，实测是 401。因为')
print('    spring.web.resources.add-mappings 默认开启，/** 被 SimpleUrlHandlerMapping')
print('    映射到 ResourceHttpRequestHandler —— 于是**任何路径都算有 handler**，')
print('    拦截器照常执行。404 只在拦截器放行之后（兼容模式、或命中白名单）才会出现。')
probe(401, 'GET', '/no-such-path-xyz', '未映射路径（非白名单）')

print()
if FAIL:
    print('存在 %d 处不符合预期：%s' % (len(FAIL), FAIL))
    sys.exit(1)
print('全部符合预期')
