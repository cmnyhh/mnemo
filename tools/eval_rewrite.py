#!/usr/bin/env python3
"""在**真实记忆库**上验证查询改写：同一批 query，原句 vs 扩展句，各跑一次召回评测。

和 bench/locomo 的区别：那边证明的是「闲聊语料上有效」，这里回答的是
「**你自己的库**上有没有用」。后者才是决定要不要开启的唯一依据。

用法（在服务器上跑，能直连 127.0.0.1:8090）：

    # 1. 先只看扩展词，人工确认它没有在「猜事实」（对应方案里的 shadow 模式）
    MEMORY_TOKEN=mn_... python3 tools/eval_rewrite.py --set queries.json --dry-run

    # 2. 再跑 A/B 对比
    MEMORY_TOKEN=mn_... python3 tools/eval_rewrite.py --set queries.json \
        --llm-key sk-... --out rewrite-ab.json

判据：`hit@1` 或 `mrr` 提升、且 `--dry-run` 里没有猜事实，才值得开。
扩展调用失败/超时一律退回原查询，所以最坏情况和现在完全一致。
"""
import argparse
import importlib.util
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

# 中文技术场景的提示词。LoCoMo 那版英文提示（加 hobbies/exercise 这类闲聊类别词）
# 在中文工程记忆上实测不可用：会把「服务部署在哪台机器上」扩成英文，
# 把「以后给领导的文档怎么写」扩成「团队合作/时间管理」这类 40 多个无关词。
REWRITE_SYSTEM = (
    "把这个检索查询改写成关键词列表。规则："
    "① 保留查询里所有专有名词（产品名、服务名、主机名、人名、配置项）；"
    "② 补充 3~5 个同义说法或上下位词，覆盖用户可能用的不同叫法；"
    "③ 不要回答问题，不要编造任何具体数值、名称、日期；"
    "④ 输出一行，中文，逗号分隔，不要引号。"
)

# 时间/计数类问句不扩展：扩展会抹掉「4 年前」这种唯一定位信息。
TEMPORAL = re.compile(
    r"\b(when|what year|how many|how long|how often|years? ago|months? ago|"
    r"weeks? ago|days? ago|19\d\d|20\d\d)\b", re.I)


def load_sibling(name):
    """复用 eval_recall.py 里的评测逻辑，避免两份实现漂移。"""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), name + ".py")
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def expand(query, args, cache):
    """返回扩展词；任何异常都返回空串（调用方退回原查询）。"""
    if query in cache:
        return cache[query]
    if args.gate and TEMPORAL.search(query):
        cache[query] = ""
        return ""
    body = json.dumps({
        "model": args.llm_model,
        "temperature": 0,
        "max_tokens": 120,
        "messages": [
            {"role": "system", "content": REWRITE_SYSTEM},
            {"role": "user", "content": query},
        ],
    }).encode()
    req = urllib.request.Request(
        args.llm_base.rstrip("/") + "/chat/completions", data=body,
        headers={"content-type": "application/json",
                 "authorization": "Bearer " + args.llm_key})
    try:
        with urllib.request.urlopen(req, timeout=args.llm_timeout) as resp:
            data = json.loads(resp.read())
        text = ((data.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
        line = text.strip().splitlines()[0] if text.strip() else ""
        parts = [p.strip() for p in re.split(r"[,;]", line) if p.strip()]
        # 丢掉带数字的片段：那是模型在编具体事实（"3 times a week"）。
        parts = [p for p in parts if not re.search(r"\d", p)]
        out = ", ".join(parts)[:200]
    except Exception as exc:  # 超时、429、空响应
        print("  !! 扩展失败 %-30s %s" % (query[:30], type(exc).__name__), file=sys.stderr)
        out = ""
    cache[query] = out
    return out


def print_side(name, summary):
    print("  %-10s hit@1=%-7s hit@3=%-7s recall@k=%-7s mrr=%-7s p50=%sms" % (
        name, summary["hit@1"], summary["hit@3"], summary["recall@k"],
        summary["mrr"], summary.get("latency_p50")))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", required=True, help="评测集 JSON（同 eval_recall.py）")
    ap.add_argument("--base", default="http://127.0.0.1:8090")
    ap.add_argument("--mode", default="hybrid", choices=["hybrid", "bm25", "dense"])
    ap.add_argument("--topk", type=int, default=6)
    ap.add_argument("--out", help="把逐条对比写到该文件")
    ap.add_argument("--dry-run", action="store_true",
                    help="只打印扩展词，不打服务（人类检查有没有猜事实）")
    ap.add_argument("--llm-base", default=os.environ.get("REWRITE_LLM_BASE",
                                                         "https://api.siliconflow.cn/v1"))
    ap.add_argument("--llm-key", default=os.environ.get("REWRITE_LLM_KEY", ""))
    ap.add_argument("--llm-model", default=os.environ.get("REWRITE_LLM_MODEL",
                                                         "Qwen/Qwen2.5-7B-Instruct"))
    ap.add_argument("--llm-timeout", type=float, default=3.0)
    ap.add_argument("--no-gate", dest="gate", action="store_false",
                    help="对时间类问句也做扩展（默认不扩展）")
    ap.set_defaults(gate=True)
    args = ap.parse_args()

    if not args.llm_key:
        sys.exit("缺少 --llm-key（或 REWRITE_LLM_KEY）")

    with open(args.set, encoding="utf-8") as fh:
        spec = json.load(fh)
    cases = spec.get("cases", spec if isinstance(spec, list) else [])
    if not cases:
        sys.exit("评测集里没有 cases")

    cache = {}
    rewrites = []
    started = time.time()
    for i, case in enumerate(cases, 1):
        q = case["query"]
        exp = expand(q, args, cache)
        rewrites.append({"query": q, "expanded": exp,
                         "gated": args.gate and bool(TEMPORAL.search(q)),
                         "expect_ids": case.get("expect_ids") or []})
        if i % 20 == 0 or i == len(cases):
            print("  扩展 %d/%d" % (i, len(cases)), file=sys.stderr)

    print("\n=== 扩展结果（%d 条，耗时 %.1fs）===" % (len(cases), time.time() - started))
    unchanged = sum(1 for r in rewrites if not r["expanded"])
    for r in rewrites:
        tag = "跳过" if r["gated"] else ("空" if not r["expanded"] else "扩展")
        print("  [%s] %s" % (tag, r["query"]))
        if r["expanded"]:
            print("        -> %s" % r["expanded"])
    print("  未扩展 %d / %d（含时间门控跳过）" % (unchanged, len(cases)))

    if args.dry_run:
        print("\n人工检查三件事：① 有没有出现问句里没有的具体事实（主机名/日期/数值）；"
              "\n            ② 有没有变成英文（库是中文原文，变了就等于断掉词面匹配）；"
              "\n            ③ 扩展词是不是全库都有的高频泛化词（那样会压低区分度）。")
        return

    token = os.environ.get("MEMORY_TOKEN", "").strip()
    if not token:
        sys.exit("缺少 MEMORY_TOKEN（控制台「令牌」页签发）")

    ev = load_sibling("eval_recall")
    print("\n=== A/B 召回对比（mode=%s topk=%d）===" % (args.mode, args.topk))
    _, base_sum = ev.evaluate(args.base, token, cases, args.mode, args.topk)
    print_side("原问句", base_sum)

    expanded_cases = [{"query": r["expanded"] or r["query"],
                       "expect_ids": r["expect_ids"]} for r in rewrites]
    _, exp_sum = ev.evaluate(args.base, token, expanded_cases, args.mode, args.topk)
    print_side("扩展句", exp_sum)

    def delta(key):
        a, b = base_sum.get(key), exp_sum.get(key)
        return None if a is None or b is None else round(b - a, 4)

    print("\n  Δ hit@1=%s  Δ hit@3=%s  Δ recall@k=%s  Δ mrr=%s  Δ p50=%sms" % (
        delta("hit@1"), delta("hit@3"), delta("recall@k"), delta("mrr"),
        (exp_sum.get("latency_p50") or 0) - (base_sum.get("latency_p50") or 0)))

    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump({"baseline": base_sum, "expanded": exp_sum,
                       "rewrites": rewrites}, fh, ensure_ascii=False, indent=2)
        print("  saved -> %s" % args.out)


if __name__ == "__main__":
    main()
