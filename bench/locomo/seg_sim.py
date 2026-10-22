#!/usr/bin/env python3
"""话题簇假设的定量验证：把一轮扩成「前后各 2 轮」的段，向量相似度会不会明显上去。

为什么值得单独测：621 条零命中证据是「整个会话一条都没召回」，回填无锚点。
直觉上"把相邻轮次合并成段，语义更完整、更容易匹配 query"——但这只是直觉。
如果段级相似度只涨 0.02，那话题簇重入库几小时的投入就不值；
如果涨 0.15 以上、并且把相当一部分推过 dense-min-score，那才值得做。

只调 embedding，不碰检索链路，所以不会污染正在跑的评测。

用法：
    python3 bench/locomo/seg_sim.py --ask out/ask-turn133.jsonl --data /tmp/locomo10.json \
        --base https://api.siliconflow.cn/v1 --key sk-xxx --model Qwen/Qwen3-Embedding-4B
"""

from __future__ import annotations

import argparse
import json
import math
import random
import urllib.request
from pathlib import Path


def embed(base: str, key: str, model: str, texts: list[str], batch: int = 10) -> list[list[float]]:
    out: list[list[float]] = []
    for i in range(0, len(texts), batch):
        chunk = texts[i:i + batch]
        body = json.dumps({"model": model, "input": chunk}).encode()
        req = urllib.request.Request(base.rstrip("/") + "/embeddings", data=body, method="POST",
                                     headers={"Content-Type": "application/json",
                                              "Authorization": "Bearer " + key})
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read().decode())
        rows = sorted(data["data"], key=lambda d: d["index"])
        out.extend(r["embedding"] for r in rows)
    return out


def cos(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ask", required=True, help="基线 ask.jsonl")
    ap.add_argument("--data", default="/tmp/locomo10.json")
    ap.add_argument("--base", required=True)
    ap.add_argument("--key", required=True)
    ap.add_argument("--model", default="Qwen/Qwen3-Embedding-4B")
    ap.add_argument("--radius", type=int, default=2)
    ap.add_argument("--sample", type=int, default=150, help="抽多少条零命中证据")
    ap.add_argument("--threshold", type=float, default=0.30, help="dense-min-score")
    args = ap.parse_args()

    # dia_id -> (sample, 会话号, 轮号, 文本)，按会话分组好取邻居
    turns: dict[tuple[str, str], list[tuple[int, str]]] = {}
    for conv in json.loads(Path(args.data).read_text()):
        sample = conv["sample_id"]
        for k, v in conv["conversation"].items():
            if not k.startswith("session_") or k.endswith("date_time"):
                continue
            sess = "D" + k.split("_")[1]
            items = []
            for t in v:
                dia = t.get("dia_id") or ""
                if ":" not in dia:
                    continue
                idx = int(dia.split(":")[1]) if dia.split(":")[1].isdigit() else 0
                items.append((idx, t.get("speaker", "?") + ": " + t.get("text", "")))
            items.sort()
            turns[(sample, sess)] = items

    rows = [json.loads(l) for l in Path(args.ask).read_text().splitlines() if l.strip()]
    cases = []
    for r in rows:
        ev = [p.strip() for e in (r.get("evidence") or []) for p in e.split(";") if p.strip()]
        got = set(r.get("retrieved") or [])
        got_sess = {g.split(":")[0] for g in got}
        for e in ev:
            if e in got or e.split(":")[0] in got_sess:
                continue
            # 证据字段里有 "D8:6; D9:17" 这种一条塞多个的，拆完后仍可能残留畸形串
            parts = e.split(":")
            if len(parts) != 2 or not parts[1].isdigit():
                continue
            sess, idx = parts[0], int(parts[1])
            seq = turns.get((r["sample"], sess))
            if not seq:
                continue
            pos = next((i for i, (n, _) in enumerate(seq) if n == idx), None)
            if pos is None:
                continue
            span = seq[max(0, pos - args.radius): pos + args.radius + 1]
            cases.append({"q": r["question"], "single": seq[pos][1],
                          "seg": " ".join(t for _, t in span)[:1200]})
    random.seed(20261022)
    random.shuffle(cases)
    cases = cases[: args.sample]
    print("抽样 %d 条零命中证据（radius=%d）" % (len(cases), args.radius), flush=True)

    qs = embed(args.base, args.key, args.model, [c["q"] for c in cases])
    ss = embed(args.base, args.key, args.model, [c["single"] for c in cases])
    gs = embed(args.base, args.key, args.model, [c["seg"] for c in cases])

    single = [cos(qs[i], ss[i]) for i in range(len(cases))]
    seg = [cos(qs[i], gs[i]) for i in range(len(cases))]

    def stat(xs):
        xs = sorted(xs)
        return xs[len(xs) // 2], sum(xs) / len(xs)

    m1, a1 = stat(single)
    m2, a2 = stat(seg)
    p1 = sum(1 for x in single if x >= args.threshold) / len(single)
    p2 = sum(1 for x in seg if x >= args.threshold) / len(seg)
    up = sum(1 for i in range(len(cases)) if seg[i] > single[i]) / len(cases)

    print("\n%-12s %8s %8s %10s" % ("", "中位", "均值", "过门槛"))
    print("%-12s %8.3f %8.3f %9.1f%%" % ("单轮", m1, a1, p1 * 100))
    print("%-12s %8.3f %8.3f %9.1f%%" % ("段(±%d轮)" % args.radius, m2, a2, p2 * 100))
    print("\n均值变化 %+.3f，中位变化 %+.3f，变好的比例 %.1f%%" % (a2 - a1, m2 - m1, up * 100))
    print("判定：均值增幅 >=0.10 且过门槛比例翻倍 ⇒ 话题簇值得做；否则不值得。")


if __name__ == "__main__":
    main()
