#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Qdrant 服务端自检：在动真数据之前，先确认"这台机器 + 这条网络"扛得住。

它做的事和之前的 Chroma 自检脚本一一对应，但针对 Qdrant 的真实风险点重新设计了
用例（Chroma 的坑是删除崩溃、跨版本段错误；Qdrant 的坑主要是上游限流和维度契约）：

  1. 连通性          —— /healthz，以及版本号
  2. 建集合 + 索引    —— 1024 维 Cosine，验证维度契约能落地
  3. 分批写入 600 条  —— 真实体量（1436 块的一半），测写入吞吐
  4. 计数校验        —— exact count 必须等于写入数
  5. 删不存在的 id    —— 验证幂等（这是 Chroma 会崩、Qdrant 不会的那一步）
  6. 按 payload 删除  —— 验证 docId 过滤删除（本项目删文档走的就是这条路）
  7. 清理            —— 删掉临时集合，不留垃圾

用法：
    python tools/qdrant_selfcheck.py                       # 默认 localhost:6333
    python tools/qdrant_selfcheck.py --url http://localhost:6333
    python tools/qdrant_selfcheck.py --points 2000         # 压更多点

只用标准库；显式禁用 http_proxy，否则内网地址会被本机代理劫持。
"""

import argparse
import json
import random
import sys
import time
import urllib.error
import urllib.request
import uuid

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http(method, url, body=None, timeout=120):
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with _OPENER.open(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:400]
        raise RuntimeError("HTTP %s %s %s\n%s" % (e.code, method, url, detail)) from None


def plain(url, timeout=60):
    """取纯文本响应。Qdrant 的 /healthz 返回的是 `healthz check passed`，不是 JSON。"""
    req = urllib.request.Request(url, method="GET")
    with _OPENER.open(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8").strip()


def check(title, fn):
    """跑一个用例，打印耗时；异常直接抛出，让人看到是哪一步坏的。"""
    started = time.perf_counter()
    result = fn()
    cost = (time.perf_counter() - started) * 1000
    suffix = "" if result is None else " -> %s" % result
    print("  [OK] %-22s %7.1f ms%s" % (title, cost, suffix))
    return cost


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://localhost:6333")
    p.add_argument("--points", type=int, default=600)
    p.add_argument("--dim", type=int, default=1024)
    p.add_argument("--batch", type=int, default=100)
    p.add_argument("--keep", action="store_true", help="保留临时集合（排查用）")
    args = p.parse_args()

    base = args.url.rstrip("/")
    coll = "selfcheck-%d" % int(time.time())
    print("Qdrant 自检：%s（%d 条 × %d 维，集合 %s）" % (base, args.points, args.dim, coll))

    print("[1] 连通性")
    check("healthz", lambda: plain(base + "/healthz"))
    check("version", lambda: http("GET", base + "/").get("version"))

    print("[2] 建集合与索引")
    check("createCollection", lambda: http("PUT", "%s/collections/%s" % (base, coll), {
        "vectors": {"size": args.dim, "distance": "Cosine"}}).get("status"))
    for field, schema in (("docId", "keyword"), ("chunkIndex", "integer")):
        check("index:%s" % field, lambda f=field, s=schema: http(
            "PUT", "%s/collections/%s/index?wait=true" % (base, coll),
            {"field_name": f, "field_schema": s}).get("status"))

    print("[3] 写入 %d 条" % args.points)
    rnd = random.Random(20261116)
    ids = [str(uuid.uuid4()) for _ in range(args.points)]
    write_ms = 0.0
    for i in range(0, len(ids), args.batch):
        chunk = ids[i:i + args.batch]
        points = [{
            "id": pid,
            "vector": [rnd.uniform(-1, 1) for _ in range(args.dim)],
            "payload": {"docId": "selfcheck", "chunkIndex": i + j, "doc_content": "自检占位文本"},
        } for j, pid in enumerate(chunk)]
        write_ms += check("upsert %d-%d" % (i, i + len(chunk)),
                          lambda ps=points: http("PUT", "%s/collections/%s/points?wait=true"
                                                 % (base, coll), {"points": ps}).get("status"))

    print("[4] 计数校验")
    count = http("POST", "%s/collections/%s/points/count" % (base, coll), {"exact": True})
    actual = count["result"]["count"]
    if actual != args.points:
        raise SystemExit("计数不符：期望 %d，实际 %d" % (args.points, actual))
    check("count", lambda: actual)
    if args.points:
        print("  写入吞吐约 %.0f 条/秒" % (args.points / (write_ms / 1000.0)) if write_ms else "")

    print("[5] 删除不存在的 id（幂等性）")
    ghost = [str(uuid.uuid4()) for _ in range(5)]
    check("delete ghosts", lambda: http("POST", "%s/collections/%s/points/delete?wait=true"
                                       % (base, coll), {"points": ghost}).get("status"))
    still = http("POST", "%s/collections/%s/points/count" % (base, coll), {"exact": True})
    if still["result"]["count"] != actual:
        raise SystemExit("删不存在的 id 竟然改变了点数：%s -> %s"
                         % (actual, still["result"]["count"]))
    print("  点数未变（%d），符合预期" % actual)

    print("[6] 按 payload 过滤删除")
    check("delete by filter", lambda: http("POST", "%s/collections/%s/points/delete?wait=true"
                                          % (base, coll),
                                          {"filter": {"must": [{"key": "docId",
                                                                "match": {"value": "selfcheck"}}]}}
                                          ).get("status"))
    left = http("POST", "%s/collections/%s/points/count" % (base, coll), {"exact": True})
    if left["result"]["count"] != 0:
        raise SystemExit("按 docId 过滤删除后仍有 %d 个点" % left["result"]["count"])
    print("  已清空")

    print("[7] 清理")
    if args.keep:
        print("  保留集合 %s（--keep）" % coll)
    else:
        check("drop collection", lambda: http("DELETE", "%s/collections/%s" % (base, coll)).get("result"))

    print("\n全部通过。可以把这个地址配给应用：QDRANT_HOST=%s"
          % base.replace("http://", "").replace("https://", "").split(":")[0])


if __name__ == "__main__":
    sys.exit(main())
