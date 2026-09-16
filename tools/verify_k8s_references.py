"""
校验 deploy/k8s 清单里"配置引用的集群内 DNS 名"与"实际存在的 Service"是否一致。

为什么需要这个脚本：
    configmap.yaml 里写的是 mysql.ecommerce.svc.cluster.local 这样的字面量。
    如果对应的 Service 名字拼错或漏建，**kustomize 渲染不会报错、kubectl apply 也不会报错**
    （ConfigMap 的值就是字符串，K8s 不做跨对象校验）。
    真正暴露的时候是 Pod 启动后抛 UnknownHostException，
    而那时你可能正在怀疑云托管白名单、SSL、密码 —— 排查方向完全跑偏。
    所以在 apply 之前把它静态查出来。

用法：
    python tools/verify_k8s_references.py
"""
import re
import subprocess
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parent.parent
K8S_DIR = REPO / "deploy" / "k8s"

# 集群内 DNS 名形如 <svc>.<ns>.svc.cluster.local
SVC_FQDN = re.compile(r"([a-z0-9][a-z0-9\-]*)\.([a-z0-9\-]+)\.svc\.cluster\.local")


def render():
    """用 kubectl kustomize 渲染，拿到的才是 apply 时真正生效的内容。"""
    out = subprocess.run(
        ["kubectl", "kustomize", str(K8S_DIR)],
        capture_output=True, text=True, check=True,
    )
    return [d for d in yaml.safe_load_all(out.stdout) if d]


def render_or_fail():
    try:
        return render()
    except FileNotFoundError:
        print("SKIP: 找不到 kubectl，无法渲染")
        sys.exit(0)
    except subprocess.CalledProcessError as e:
        print("FAIL: kubectl kustomize 渲染失败")
        print(e.stderr[:2000])
        sys.exit(1)


def main():
    docs = render_or_fail()
    ok = True

    # --- 1. 收集集群内实际存在的 Service 名（按 namespace 分组）---
    services = {}
    for d in docs:
        if d.get("kind") == "Service":
            ns = d["metadata"].get("namespace", "default")
            services.setdefault(ns, set()).add(d["metadata"]["name"])

    print("=== 1. 集群内 Service ===")
    for ns, names in sorted(services.items()):
        print(f"  {ns}: {sorted(names)}")

    # --- 2. 扫描所有对象里的 svc.cluster.local 引用 ---
    missing = []
    referenced = []
    for d in docs:
        kind = d.get("kind")
        name = d["metadata"]["name"]
        text = yaml.safe_dump(d, allow_unicode=True)
        for svc, ns in SVC_FQDN.findall(text):
            referenced.append((kind, name, svc, ns))
            if svc not in services.get(ns, set()):
                missing.append((kind, name, f"{svc}.{ns}.svc.cluster.local"))

    print("\n=== 2. 配置里引用的集群内 DNS 名 ===")
    if not referenced:
        print("  (无)")
    for kind, name, svc, ns in sorted(set(referenced)):
        status = "OK " if svc in services.get(ns, set()) else "缺失"
        print(f"  [{status}] {svc}.{ns}.svc.cluster.local   <- {kind}/{name}")

    if missing:
        ok = False
        print("\n=== 3. 结论：存在悬空引用（配置引用了不存在的 Service）===")
        for kind, name, fqdn in sorted(set(missing)):
            print(f"  {fqdn}  被 {kind}/{name} 引用，但集群内无同名 Service")

        # --- 4. 给出可疑项：名字相近但不同的 Service，通常是拼写错误 ---
        print("\n=== 4. 可能的原因（名字相近的 Service）===")
        all_svc = {n for names in services.values() for n in names}
        for _, _, fqdn in sorted(set(missing)):
            target = fqdn.split(".")[0]
            close = [s for s in all_svc if s != target and (target in s or s in target)]
            if close:
                print(f"  {target} -> 是否想写 {close}？")
    else:
        print("\n=== 3. 结论：全部引用都能对上 ===")

    # --- 5. ExternalName Service 的专项检查 ---
    print("\n=== 5. ExternalName Service 检查 ===")
    ext = [d for d in docs if d.get("kind") == "Service"
           and d.get("spec", {}).get("type") == "ExternalName"]
    if not ext:
        print("  (无 ExternalName Service)")
    for d in ext:
        nm = d["metadata"]["name"]
        target = d["spec"]["externalName"]
        ports = [p["port"] for p in d["spec"].get("ports", [])]
        # 三个已知限制的静态检查
        issues = []
        if target.replace(".", "").isdigit():
            issues.append("externalName 是纯 IP —— CNAME 不能指向 IP，apiserver 会拒绝")
        if "REPLACE-ME" in target:
            issues.append("仍是占位地址，未替换为真实云托管地址（在 kind 里属预期）")
        for p in d["spec"].get("ports", []):
            if "targetPort" in p:
                issues.append(f"port {p['port']} 声明了 targetPort —— ExternalName 不支持端口重映射，该字段无效")
        flag = "；".join(issues) if issues else "OK"
        print(f"  {nm:22} -> {target:42} ports={ports}")
        print(f"  {'':22}    {flag}")

    print("\n" + ("PASS" if ok else "FAIL"))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
