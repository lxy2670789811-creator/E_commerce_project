#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把 deploy/nacos/ 下的配置推送到 Nacos 配置中心。

为什么需要这个脚本
------------------
本项目原先存在一个「配置分离只有壳、没有内容」的问题：bootstrap.yml 声明了三个 dataId
（主配置 + shared + extension），但 Nacos 里**一个都没有创建**。Nacos 客户端连不上配置
或配置为空时**不会**让应用启动失败，只会在日志里打一行
`Ignore the empty nacos configuration ...` 然后安静地回落到代码默认值。
结果是"参数走 Nacos 热更新"这个能力一直只停留在文档上，从未真正生效。

这个脚本让配置源在仓库里**版本化**，可以被 review、被回滚、被重复执行（幂等覆盖）。

用法
----
    # 推送到本地默认 Nacos（127.0.0.1:8848, namespace=public）
    python tools/nacos_push_config.py

    # 指定地址 / 命名空间（例如推 k8s 验证环境）
    python tools/nacos_push_config.py --server 192.168.65.254:8848 --namespace public

    # 只看会做什么，不真正写入
    python tools/nacos_push_config.py --dry-run

    # 推送后立刻读回校验（推荐）
    python tools/nacos_push_config.py --verify

退出码：0 = 全部成功；1 = 有任一失败。
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

# 与 bootstrap.yml 保持一致，改这里之前先改那边
GROUP = "ECOMMERCE_GROUP"
DEFAULT_SERVER = "127.0.0.1:8848"
DEFAULT_NAMESPACE = "public"

# 读回校验的重试参数（原因见 verify_one 的注释：Nacos 发布后读回有短暂不可见窗口）
VERIFY_RETRIES = 5
VERIFY_RETRY_INTERVAL_SECONDS = 0.6

# 配置目录：仓库根的 deploy/nacos/
NACOS_DIR = Path(__file__).resolve().parent.parent / "deploy" / "nacos"

# 需要推送的 dataId（顺序无关，Nacos 端互不依赖）
DATA_IDS = [
    "e-commerce-order-backend.yaml",  # 主配置
    "ecommerce-common.yaml",          # shared-configs
    "ecommerce-business.yaml",        # extension-configs
]


def _post(server: str, path: str, params: dict, timeout: int = 10) -> tuple:
    """向 Nacos 发一个 POST（表单），返回 (status_code, body_text)。"""
    url = "http://%s%s" % (server, path)
    data = urllib.parse.urlencode(params).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def _get(server: str, path: str, params: dict, timeout: int = 10) -> tuple:
    """向 Nacos 发一个 GET（查询串），返回 (status_code, body_text)。"""
    url = "http://%s%s?%s" % (server, path, urllib.parse.urlencode(params))
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def _tenant_param(namespace: str) -> dict:
    """
    把「命名空间」翻译成 Nacos API 的 tenant 参数。

    ⚠️ 关键坑：public 命名空间的真实 ID 是**空字符串**，
       控制台里显示的 "public" 只是展示名（namespaces 接口返回
       `{"namespace":"", "namespaceShowName":"public"}`）。
       若照抄控制台传 `tenant=public`，Nacos 会把它当成一个不存在的命名空间 ID，
       **发布接口仍然返回 `true`**，但配置不落库 —— 读回 404、控制台 configCount 恒为 0。
       排查时很容易误判成"网络问题"或"写入延迟"。

       正确做法：public（或空）时**不传** tenant 参数。
    """
    ns = (namespace or "").strip()
    if ns.lower() in ("", "public"):
        return {}
    return {"tenant": ns}


def ensure_namespace(server: str, namespace: str) -> bool:
    """
    确保目标命名空间存在（public 视为始终存在）。不存在则创建。

    为什么需要这一步：应用侧的 `NACOS_NAMESPACE` 是按环境隔离的
    （K8s 里是 `ecommerce-prod`，bootstrap.yml 默认 `public`）。
    往一个**不存在**的命名空间写配置，行为与 `tenant=public` 那个坑一样危险 ——
    Nacos 会静默接受或直接丢弃，应用那边继续走代码默认值，
    而日志里只有一行容易被忽略的 `Ignore the empty nacos configuration`。
    """
    ns = (namespace or "").strip()
    if ns.lower() in ("", "public"):
        return True

    status, body = _get(server, "/nacos/v1/console/namespaces", {})
    if status == 200:
        try:
            existing = json.loads(body).get("data") or []
        except Exception:  # noqa: BLE001 - 解析失败则走创建分支
            existing = []
        for item in existing:
            if item.get("namespace") == ns or item.get("namespaceShowName") == ns:
                print("命名空间已存在：%s" % ns)
                return True

    # customNamespaceId 才是真正的 namespace ID；namespaceName 仅用于控制台展示
    status, body = _post(server, "/nacos/v1/console/namespaces", {
        "customNamespaceId": ns,
        "namespaceName": ns,
        "namespaceDesc": "created by tools/nacos_push_config.py",
    })
    ok = status == 200 and body.strip().lower() == "true"
    print("%s命名空间：%s" % ("已创建" if ok else "创建失败", ns))
    return ok


def push_one(server: str, namespace: str, data_id: str, content: str, dry_run: bool) -> bool:
    """发布单条配置。Nacos 的 /nacos/v1/cs/configs POST 是幂等覆盖语义。"""
    if dry_run:
        print("  [dry-run] 将发布 %-32s (%d 字符)" % (data_id, len(content)))
        return True

    params = {
        "dataId": data_id,
        "group": GROUP,
        "type": "yaml",
        "content": content,
    }
    params.update(_tenant_param(namespace))
    status, body = _post(server, "/nacos/v1/cs/configs", params)
    ok = status == 200 and body.strip().lower() == "true"
    print("  %-32s HTTP %s  %s" % (data_id, status, body.strip()[:60]))
    if not ok:
        print("    ⚠️ 发布失败。常见原因：Nacos 未启动 / 地址不对 / 需要鉴权（username/password）。")
    return ok


def verify_one(server: str, namespace: str, data_id: str, expected: str) -> bool:
    """
    读回配置并比对内容（Nacos 会保留原有换行，故做 strip 后全量比对）。

    ⚠️ 必须带重试，且**两种失败都要重试**。实测发现 Nacos 2.x 在「发布 → 立刻读回」时，
       读路径存在短暂不可见窗口，表现为两种之一：
         (a) 直接 404 —— 配置还没读到；
         (b) 返回 HTTP 200 但内容是**上一次的旧值** —— 更隐蔽，只看状态码会误判成成功。
       不重试会把这两种时序抖动误判成「发布失败」或「发布成功」，两个方向都会骗人。
    """
    params = {"dataId": data_id, "group": GROUP}
    params.update(_tenant_param(namespace))
    expected_stripped = expected.strip()
    last_status = None
    last_len = None

    for attempt in range(1, VERIFY_RETRIES + 1):
        status, body = _get(server, "/nacos/v1/cs/configs", params)
        last_status = status
        if status == 200:
            last_len = len(body)
            if body.strip() == expected_stripped:
                print("  %-32s 读回 %d 字符，内容一致" % (data_id, last_len))
                return True
        if attempt < VERIFY_RETRIES:
            time.sleep(VERIFY_RETRY_INTERVAL_SECONDS)

    if last_status == 200:
        print("  %-32s 读回 %d 字符，内容**不**一致（已重试 %d 次；期望 %d 字符）"
              % (data_id, last_len, VERIFY_RETRIES, len(expected_stripped)))
    else:
        print("  %-32s 读回失败 HTTP %s（已重试 %d 次）" % (data_id, last_status, VERIFY_RETRIES))
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description="推送 deploy/nacos/ 下的配置到 Nacos")
    parser.add_argument("--server", default=DEFAULT_SERVER, help="Nacos 地址，默认 %s" % DEFAULT_SERVER)
    parser.add_argument("--namespace", default=DEFAULT_NAMESPACE, help="命名空间，默认 %s" % DEFAULT_NAMESPACE)
    parser.add_argument("--dry-run", action="store_true", help="只打印将执行的动作，不真正写入")
    parser.add_argument("--verify", action="store_true", help="发布后读回校验内容一致")
    args = parser.parse_args()

    if not NACOS_DIR.is_dir():
        print("找不到配置目录：%s" % NACOS_DIR, file=sys.stderr)
        return 1

    # 1) 先确认 Nacos 活着，避免在断网时逐条报一堆相同的错
    print("Nacos: http://%s   namespace=%s   group=%s" % (args.server, args.namespace, GROUP))
    if not args.dry_run:
        try:
            status, _ = _get(args.server, "/nacos/v1/console/namespaces", {})
            if status != 200:
                print("✗ Nacos 控制台接口返回 HTTP %s，请确认服务已启动、地址正确。" % status, file=sys.stderr)
                return 1
        except Exception as e:  # noqa: BLE001 - 网络异常统一提示
            print("✗ 连不上 Nacos（%s）。请确认 Nacos 已启动。" % e, file=sys.stderr)
            return 1
    print("✓ Nacos 可达\n")

    # 2) 确保命名空间存在（public 之外的环境命名空间需预先创建，否则配置会静默丢失）
    if not args.dry_run and not ensure_namespace(args.server, args.namespace):
        print("✗ 命名空间不可用，中止。", file=sys.stderr)
        return 1

    # 3) 逐条发布
    print("发布配置：")
    results = {}
    contents = {}
    for data_id in DATA_IDS:
        path = NACOS_DIR / data_id
        if not path.is_file():
            print("  %-32s 本地文件不存在，跳过" % data_id)
            results[data_id] = False
            continue
        content = path.read_text(encoding="utf-8")
        contents[data_id] = content
        results[data_id] = push_one(args.server, args.namespace, data_id, content, args.dry_run)

    # 3) 可选：读回校验
    verify_ok = True
    if args.verify and not args.dry_run:
        print("\n读回校验：")
        for data_id, content in contents.items():
            if not verify_one(args.server, args.namespace, data_id, content):
                verify_ok = False

    failed = [d for d, ok in results.items() if not ok]
    print()
    if failed or not verify_ok:
        if failed:
            print("✗ 失败：%s" % ", ".join(failed), file=sys.stderr)
        if not verify_ok:
            print("✗ 读回校验未通过", file=sys.stderr)
        return 1

    if args.dry_run:
        print("✓ dry-run 完成（未写入任何内容）")
    else:
        print("✓ 全部成功。应用侧生效方式：重启进程，或等待 Nacos 推送（已开启 refresh-enabled）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
