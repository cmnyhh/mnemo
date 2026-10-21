#!/usr/bin/env python3
"""召回评测：用一组带期望结果的 query 量化「该记的有没有记住」。

没有这个脚本，「漏召回」只能靠感觉；有了它，调阈值、换模型、加 recall_hints
才有 before/after 可比。

用法（在服务器上跑，能直连 127.0.0.1:8090）：

    python3 tools/eval_recall.py --set tools/queries.example.json
    python3 tools/eval_recall.py --set tools/queries.example.json --mode dense --topk 6
    python3 tools/eval_recall.py --set tools/queries.example.json --save baseline.json

指标：
    Hit@1 / Hit@3   第一名/前三名里有没有期望记忆
    Recall@K        期望记忆被召回了多少比例
    MRR             第一个正确结果的倒数名次均值
    AbstainRate     标注为「应弃权」的 query 里，真的返回空的比例
    FalseInject     标注为「应弃权」的 query 里，返回了结果的比例（越低越好）

query 集里 expect_ids 为空即表示「这条应该弃权」；填了 id 表示必须命中。
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request


def read_token():
    """令牌从控制台「令牌」页签发，用环境变量传进来（0.9.2 起没有 .env 兜底了）。"""
    tok = os.environ.get("MEMORY_TOKEN", "").strip()
    if tok:
        return tok
    sys.exit("缺少 MEMORY_TOKEN：先在控制台「令牌」页签一把，再 \n"
             "  MEMORY_TOKEN=mn_... python3 tools/eval_recall.py --set ...")


def search(base, token, query, mode, topk, timeout=30):
    body = json.dumps({"query": query, "mode": mode, "topK": topk}).encode()
    req = urllib.request.Request(
        base + "/v1/memory/search",
        data=body,
        headers={"content-type": "application/json", "authorization": "Bearer " + token},
    )
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return None, int((time.time() - started) * 1000), "HTTP %s: %s" % (
            e.code, e.read()[:200].decode("utf-8", "ignore"))
    except Exception as e:  # 超时、连接重置
        return None, int((time.time() - started) * 1000), type(e).__name__
    return data, int((time.time() - started) * 1000), None


def evaluate(base, token, cases, mode, topk):
    rows = []
    hit1 = hit3 = 0
    recall_sum = 0.0
    mrr_sum = 0.0
    abstain_total = abstain_ok = 0
    scored = 0

    for case in cases:
        query = case["query"]
        expect = set(case.get("expect_ids") or [])
        data, latency, err = search(base, token, query, mode, topk)
        if data is None:
            rows.append({"query": query, "error": err, "latency": latency})
            continue
        hits = data.get("hits") or []
        got = [h["id"] for h in hits]

        if not expect:
            abstain_total += 1
            ok = len(hits) == 0
            abstain_ok += 1 if ok else 0
            rows.append({
                "query": query, "kind": "abstain", "ok": ok,
                "hits": len(hits), "latency": latency,
                "route": data.get("route"), "rerank": data.get("rerank"),
                "top1": hits[0]["score"] if hits else None,
            })
            continue

        scored += 1
        rank = next((i + 1 for i, hid in enumerate(got) if hid in expect), None)
        found = len(expect & set(got))
        recall = found / len(expect)
        recall_sum += recall
        hit1 += 1 if rank == 1 else 0
        hit3 += 1 if rank and rank <= 3 else 0
        mrr_sum += (1.0 / rank) if rank else 0.0
        rows.append({
            "query": query, "kind": "expect", "rank": rank, "recall": recall,
            "hits": len(hits), "latency": latency,
            "route": data.get("route"), "rerank": data.get("rerank"),
            "top1": hits[0]["score"] if hits else None,
        })

    summary = {
        "mode": mode, "topk": topk, "cases": len(cases),
        "scored": scored,
        "hit@1": round(hit1 / scored, 4) if scored else None,
        "hit@3": round(hit3 / scored, 4) if scored else None,
        "recall@k": round(recall_sum / scored, 4) if scored else None,
        "mrr": round(mrr_sum / scored, 4) if scored else None,
        "abstain_rate": round(abstain_ok / abstain_total, 4) if abstain_total else None,
        "false_inject": round(1 - abstain_ok / abstain_total, 4) if abstain_total else None,
        "latency_p50": None,
        "errors": sum(1 for r in rows if r.get("error")),
    }
    lat = sorted(r["latency"] for r in rows if r.get("latency") is not None)
    if lat:
        summary["latency_p50"] = lat[len(lat) // 2]
        summary["latency_max"] = lat[-1]
    return rows, summary


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", required=True, help="评测集 JSON 路径")
    ap.add_argument("--base", default="http://127.0.0.1:8090")
    ap.add_argument("--mode", default="hybrid", choices=["hybrid", "bm25", "dense"])
    ap.add_argument("--topk", type=int, default=6)
    ap.add_argument("--save", help="把逐条结果与汇总写到该文件")
    ap.add_argument("--quiet", action="store_true", help="只打汇总")
    args = ap.parse_args()

    with open(args.set, encoding="utf-8") as fh:
        spec = json.load(fh)
    cases = spec.get("cases", spec if isinstance(spec, list) else [])
    if not cases:
        sys.exit("评测集里没有 cases")

    token = read_token()
    rows, summary = evaluate(args.base, token, cases, args.mode, args.topk)

    if not args.quiet:
        for r in rows:
            if r.get("error"):
                print("  ERR  %-28s %s" % (r["query"][:28], r["error"]))
            elif r["kind"] == "abstain":
                print("  %s  %-28s hits=%d top1=%s" % (
                    "OK  " if r["ok"] else "LEAK", r["query"][:28], r["hits"],
                    "%.3f" % r["top1"] if r["top1"] is not None else "-"))
            else:
                print("  %s  %-28s rank=%s recall=%.2f route=%s rerank=%s" % (
                    "HIT " if r["rank"] == 1 else ("PART" if r["rank"] else "MISS"),
                    r["query"][:28], r["rank"], r["recall"],
                    r.get("route"), r.get("rerank")))

    print()
    print("mode=%s topk=%d cases=%d errors=%d" % (
        summary["mode"], summary["topk"], summary["cases"], summary["errors"]))
    print("hit@1=%s  hit@3=%s  recall@k=%s  mrr=%s" % (
        summary["hit@1"], summary["hit@3"], summary["recall@k"], summary["mrr"]))
    print("abstain_rate=%s  false_inject=%s  latency p50=%sms max=%sms" % (
        summary["abstain_rate"], summary["false_inject"],
        summary.get("latency_p50"), summary.get("latency_max")))

    if args.save:
        with open(args.save, "w", encoding="utf-8") as fh:
            json.dump({"summary": summary, "rows": rows}, fh, ensure_ascii=False, indent=2)
        print("saved -> %s" % args.save)


if __name__ == "__main__":
    main()
