#!/usr/bin/env python3
"""深度归因：那批「整段会话零命中」的证据，到底是排不上还是匹配不上。

背景：基线 topK=12 时，漏检的 1359 条证据里有约 44.5% 落在「整个会话一条都没召回」
的会话里。这批没有回填锚点，±N 半径救不了。但「救不了」有两种截然不同的原因：

  1. 排得靠后——向量/BM25 其实匹配上了，只是被前面 12 条挤出去。解法是加检索深度
     （rerank.top-n / topK），改配置就行。
  2. 匹配不上——向量分本身就低，条目和 query 在语义上不相似。解法是换表示
     （话题簇 / 查询改写），要重新入库。

两者的投入差一个量级，所以先用 probe（放宽 topK 再查一次）把它们分开，再决定做哪个。

用法：
    python3 bench/locomo/run.py probe --baseline out/ask.jsonl --topk 30 --base ... --token ...
    python3 bench/locomo/analyze_depth.py --baseline out/ask.jsonl --probe out/probe.jsonl
"""

from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path

# 判定阈值：
# - 向量相似度低于 LOW_DENSE 视为「语义上就不像」，靠加深排名救不回来；
# - 名次落在 (baseline_topk, probe_topk] 区间才算「纯深度问题」。
LOW_DENSE = 0.35


def ev_parts(row: dict) -> list[str]:
    return [p.strip() for e in (row.get("evidence") or []) for p in e.split(";") if p.strip()]


def load(path: str) -> dict[str, dict]:
    out = {}
    for line in Path(path).read_text().splitlines():
        if line.strip():
            row = json.loads(line)
            out[row["id"]] = row
    return out


def rank_of(hits: list[dict], dia: str) -> tuple[int, dict] | tuple[None, None]:
    """证据在第几名被召回。一条 hit 的 narrative 可能含多个 dia_id，取包含即算。"""
    for i, h in enumerate(hits, 1):
        if dia in (h.get("dia") or []):
            return i, h
    return None, None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", required=True, help="ask.jsonl（基线，topK=12）")
    ap.add_argument("--probe", required=True, help="probe.jsonl（放宽 topK 后的结果）")
    ap.add_argument("--baseline-topk", type=int, default=12)
    args = ap.parse_args()

    base = load(args.baseline)
    probe = load(args.probe)

    # 三类计数：每条「漏检证据」一条记录
    total_missing = 0
    no_anchor = 0          # 会话内零命中（回填救不了的那批）
    anchor_far = 0         # 会话内有别的轮召回了（回填能救）
    buckets = Counter()
    dens_ok, dens_low = [], []
    rank_hist = Counter()
    missing_no_probe = 0

    for qid, row in base.items():
        ev = ev_parts(row)
        if not ev:
            continue
        got = set(row.get("retrieved") or [])
        got_sessions = {g.split(":")[0] for g in got}
        p = probe.get(qid)
        for e in ev:
            if e in got:
                continue
            total_missing += 1
            sess = e.split(":")[0]
            if sess in got_sessions:
                anchor_far += 1
                continue
            no_anchor += 1
            if p is None:
                missing_no_probe += 1
                continue
            r, h = rank_of(p.get("hits") or [], e)
            if r is None:
                buckets["topk_probe 仍未召回"] += 1
                continue
            rank_hist[r] += 1
            dense = h.get("dense")
            if r <= args.baseline_topk:
                # 基线没召回、放宽后进了基线窗口：多半是精排抖动或并发下的降级
                buckets["基线窗口内（抖动）"] += 1
            elif dense is not None and dense < LOW_DENSE:
                buckets["进了排名但向量分低"] += 1
                dens_low.append(dense)
            else:
                buckets["纯粹排得靠后"] += 1
            if dense is not None:
                dens_ok.append(dense)

    judged = no_anchor - missing_no_probe
    rescue = buckets["纯粹排得靠后"] + buckets["基线窗口内（抖动）"]
    rate = rescue / judged if judged else float("nan")

    print(f"\n漏检证据合计            {total_missing}")
    print(f"  ├ 会话内有锚点（±N 可救） {anchor_far}")
    print(f"  └ 会话内零命中（无锚点）   {no_anchor}")
    print(f"      ├ 已探针            {judged}")
    print(f"      └ 未覆盖            {missing_no_probe}")

    print("\n零命中证据在放宽 topK 后的去向：")
    for k, v in buckets.most_common():
        print(f"  {k:<24} {v:>5}  {v / judged * 100:5.1f}%")

    if rank_hist:
        print("\n召回名次分布（top10）：")
        for r, v in sorted(rank_hist.items())[:10]:
            print(f"  #{r:<4} {v:>5}")

    if dens_ok:
        avg = sum(dens_ok) / len(dens_ok)
        print(f"\n向量相似度：均值 {avg:.3f}，最低 {min(dens_ok):.3f}，最高 {max(dens_ok):.3f}")

    print("\n判定：")
    if rate != rate:
        print("  数据不足，无法判定。")
    elif rate >= 0.40:
        print(f"  加检索深度可救 {rate * 100:.0f}% —— 先调 rerank.top-n / topK，"
              f"改配置即可，不必重入库。")
    elif rate >= 0.20:
        print(f"  加深度可救 {rate * 100:.0f}%，只能吃掉一部分 —— 深度和表示都要动，"
              f"先做深度（便宜），再评估话题簇。")
    else:
        print(f"  加深度只救 {rate * 100:.0f}% —— 是表示问题，话题簇（第二路召回）才对症。")


if __name__ == "__main__":
    main()
