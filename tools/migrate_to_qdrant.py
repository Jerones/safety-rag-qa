#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把 Chroma 里的向量整库搬到 Qdrant。

为什么需要它：向量是花钱算出来的（1436 块 ≈ 300 次 embedding 请求）。
换向量库时如果直接"重建索引"，等于把同样的文本重新算一遍，既慢又费钱。
chunkId 在两边都是同一个 UUID，所以完全可以原样搬运。

用法：
    python tools/migrate_to_qdrant.py \
        --chroma http://localhost:8000 \
        --qdrant http://localhost:6333 \
        --collection safety-regulations

    # 只看能导出多少条、维度对不对，不写任何东西
    python tools/migrate_to_qdrant.py --dry-run

注意：
  * 只用标准库，不需要 pip install 任何东西（虚拟机里的 python 往往没装 requests）；
  * 显式禁用 http_proxy，否则访问内网地址会被本机代理劫持；
  * Qdrant 的 point id 必须是 UUID 或 uint64，本项目 chunkId 正好是 UUID，可以直传。
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

# 本机有全局代理时，访问内网 192.168.x.x 会被劫持成 502
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class HttpError(Exception):
    """带上状态码的 HTTP 错误，交给调用方决定是否致命（比如 404 可以继续建集合）。"""

    def __init__(self, code, detail):
        super().__init__("HTTP %s: %s" % (code, detail))
        self.code = code
        self.detail = detail


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
        detail = e.read().decode("utf-8", "replace")[:500]
        raise HttpError(e.code, "%s %s -> %s" % (method, url, detail)) from None


def chroma_collection_id(base, name):
    """Chroma 0.5.x 的 /get 只认 collection UUID，名字要先换成 id。"""
    collections = http("GET", base + "/api/v1/collections")
    for c in collections or []:
        if c.get("name") == name:
            return c
    # 有的版本默认 tenant 判断不同，带参数再问一次
    for tenant, database in (("SpringAiTenant", "SpringAiDatabase"),):
        q = "?tenant=%s&database=%s" % (tenant, database)
        for c in http("GET", base + "/api/v1/collections" + q) or []:
            if c.get("name") == name:
                return c
    raise SystemExit("在 Chroma 上找不到集合：%s" % name)


def export_chroma(base, collection_id, page):
    """分页导出 id / embedding / metadata / document。"""
    ids, vectors, metas, docs = [], [], [], []
    offset = 0
    while True:
        body = {
            "limit": page,
            "offset": offset,
            "include": ["embeddings", "metadatas", "documents"],
        }
        res = http("POST", "%s/api/v1/collections/%s/get" % (base, collection_id), body)
        got = res.get("ids") or []
        if not got:
            break
        ids.extend(got)
        vectors.extend(res.get("embeddings") or [])
        metas.extend(res.get("metadatas") or [])
        docs.extend(res.get("documents") or [])
        print("  已导出 %d 条" % len(ids))
        if len(got) < page:
            break
        offset += page
    return ids, vectors, metas, docs


def ensure_collection(qdrant, name, dimension):
    """集合不存在就建；存在则核对维度（Qdrant 的维度是建库时定死的）。"""
    try:
        info = http("GET", "%s/collections/%s" % (qdrant, name))
        size = info["result"]["config"]["params"]["vectors"]["size"]
        if size != dimension:
            raise SystemExit(
                "维度不一致：Qdrant 集合是 %d 维，Chroma 导出的是 %d 维。\n"
                "处理：删掉集合重来 —— curl -X DELETE %s/collections/%s"
                % (size, dimension, qdrant, name)
            )
        print("集合已存在且维度一致（%d），将覆盖写入" % size)
        return
    except HttpError as e:
        if e.code != 404:
            raise
        print("集合不存在，准备创建")
    http("PUT", "%s/collections/%s" % (qdrant, name),
         {"vectors": {"size": dimension, "distance": "Cosine"}})
    print("已创建集合 %s（%d 维，Cosine）" % (name, dimension))
    for field, schema in (("docId", "keyword"), ("chunkIndex", "integer"), ("clause", "keyword")):
        http("PUT", "%s/collections/%s/index?wait=true" % (qdrant, name),
             {"field_name": field, "field_schema": schema})
        print("  已建 payload 索引：%s（%s）" % (field, schema))


def upsert(qdrant, name, ids, vectors, metas, docs, batch):
    total = 0
    for i in range(0, len(ids), batch):
        points = []
        for j in range(i, min(i + batch, len(ids))):
            payload = dict(metas[j] or {})
            # Spring AI 的 QdrantVectorStore 约定：正文放在 doc_content，
            # 其余 metadata 平铺在 payload 顶层（检索结果的 metadata 就是从这里读的）
            payload["doc_content"] = docs[j] if j < len(docs) else ""
            points.append({"id": ids[j], "vector": vectors[j], "payload": payload})
        http("PUT", "%s/collections/%s/points?wait=true" % (qdrant, name), {"points": points})
        total += len(points)
        print("  已写入 %d / %d" % (total, len(ids)))
    return total


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--chroma", default="http://localhost:8000")
    p.add_argument("--qdrant", default="http://localhost:6333")
    p.add_argument("--collection", default="safety-regulations")
    p.add_argument("--page", type=int, default=200)
    p.add_argument("--batch", type=int, default=100)
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    print("[1/4] 读取 Chroma 集合信息")
    coll = chroma_collection_id(args.chroma, args.collection)
    dimension = coll.get("dimension")
    print("  id=%s 维度=%s" % (coll["id"], dimension))

    print("[2/4] 导出向量与原文")
    ids, vectors, metas, docs = export_chroma(args.chroma, coll["id"], args.page)
    if not ids:
        raise SystemExit("Chroma 里没有任何数据，没什么可迁移的")
    print("  共 %d 条，向量 %d 条，原文 %d 条" % (len(ids), len(vectors), len(docs)))
    if len(vectors) != len(ids):
        raise SystemExit("向量的条数与 id 不一致，导出中断，请重试")

    dims = {len(v) for v in vectors[:20]}
    if dims != {dimension}:
        raise SystemExit("向量维度异常：%s（集合声明 %s）" % (dims, dimension))
    if len(set(ids)) != len(ids):
        raise SystemExit("id 有重复，中止")

    sample = metas[0] or {}
    print("  样例 metadata：%s" % json.dumps(sample, ensure_ascii=False)[:160])

    if args.dry_run:
        print("[dry-run] 不写入任何数据，退出")
        return

    print("[3/4] 准备 Qdrant 集合")
    ensure_collection(args.qdrant, args.collection, dimension)

    print("[4/4] 写入 Qdrant")
    written = upsert(args.qdrant, args.collection, ids, vectors, metas, docs, args.batch)

    count = http("POST", "%s/collections/%s/points/count" % (args.qdrant, args.collection),
                 {"exact": True})
    print("完成：写入 %d 条，Qdrant 现有 %s 个点" % (written, count["result"]["count"]))


if __name__ == "__main__":
    sys.exit(main())
