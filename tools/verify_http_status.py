"""HTTP 状态码语义真机验证脚本

背景：GlobalExceptionHandler 原先把 404/405/未预期异常一律包成 HTTP 200 + code 5000。
本脚本验证修复后的真实行为，需要应用已在 127.0.0.1:8080 运行（profile=dev）。

用法：python verify_http_status.py
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8080'
API = BASE + '/api'

FAIL = []


def wait_ready(timeout_sec=180):
    """等待应用真正就绪。

    ⚠️ 不能用"首次响应成功"作为就绪判据：Tomcat 监听端口发生在
    ApplicationStartedEvent 之**前**，这段时间里 /actuator/health 会返回
    HTTP 503（liveness=DOWN, readiness=OUT_OF_SERVICE）—— 那是 Spring Boot 的正常语义。
    如果把这个 503 当成"起来了"，后面所有断言都会拿到错的基线。
    """
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        try:
            r = urllib.request.urlopen(API + '/actuator/health', timeout=3)
            if r.status == 200:
                return True
        except urllib.error.HTTPError:
            pass  # 503：Tomcat 已监听但应用尚未 ready，继续等
        except Exception:
            pass
        time.sleep(3)
    return False


def probe(expect_status, method, path, label, expect_allow=None):
    req = urllib.request.Request(API + path, method=method)
    try:
        r = urllib.request.urlopen(req, timeout=15)
        status, body, headers = r.status, r.read().decode('utf-8', 'replace'), r.headers
    except urllib.error.HTTPError as e:
        status, body, headers = e.code, e.read().decode('utf-8', 'replace'), e.headers
    except Exception as e:
        print('  ERR  %-30s %s' % (label, e))
        FAIL.append(label)
        return

    try:
        code = json.loads(body).get('code')
    except Exception:
        code = None

    allow = headers.get('Allow')
    ok = (status == expect_status) and (expect_allow is None or allow == expect_allow)
    print('  %s  HTTP %-4s code=%-5s %-28s %s %s' %
          ('PASS' if ok else 'FAIL', status, code, label, method, path))
    if not ok:
        FAIL.append(label)
    print('        body=%s' % body[:130].replace('\n', ' '))
    if allow:
        print('        Allow=%s' % allow)


if not wait_ready():
    print('应用未在 180s 内就绪，请确认 8080 端口已监听')
    sys.exit(1)

print('应用已就绪，开始验证 HTTP 状态码语义\n')

print('--- 期望 200：正常端点不受影响 ---')
probe(200, 'GET', '/actuator/health', 'actuator 健康聚合')
probe(200, 'GET', '/actuator/health/liveness', 'K8s liveness 探针')
probe(200, 'GET', '/actuator/health/readiness', 'K8s readiness 探针')
probe(200, 'GET', '/actuator/info', 'actuator info')
probe(200, 'GET', '/order/list?userId=1', '业务接口（业务失败也应 200）')

print('\n--- 期望 404：本次修复的核心场景 ---')
probe(404, 'GET', '/actuator/metrics', 'actuator 未暴露端点')
probe(404, 'GET', '/actuator/env', 'actuator 未暴露端点')
probe(404, 'GET', '/no-such-path-abc123', '完全不存在的路径')
probe(404, 'GET', '/actuator/beans', 'actuator 未暴露端点')

print('\n--- 期望 405：请求方法不支持（须带 Allow 头）---')
probe(405, 'DELETE', '/order/list', '已存在路径 + 不支持的方法')

print()
if FAIL:
    print('存在 %d 处不符合预期：%s' % (len(FAIL), FAIL))
    sys.exit(1)
print('全部符合预期')
