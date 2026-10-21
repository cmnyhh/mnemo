#!/usr/bin/env python3
"""LoCoMo benchmark for mnemo.

三段式，每段都能单独重跑（结果落 JSONL，跳过已完成的题）：

    python3 bench/locomo/run.py ingest  --base http://host:8080 --token mn_xxx
    python3 bench/locomo/run.py ask     --base http://host:8080 --token mn_xxx
    python3 bench/locomo/run.py judge   --llm-base https://api.siliconflow.cn/v1 ...
    python3 bench/locomo/run.py report

为什么要分三段：入库和检索是「被测系统」的行为，作答和评判是「评测框架」的行为。
混在一起的话，换个判分模型就得把 5882 条记忆重灌一遍。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

CATEGORY_NAMES = {
    1: "multi-hop",
    2: "temporal",
    3: "open-domain",
    4: "single-hop",
    5: "adversarial",
}

# 证据标记：[D3:7] 这种。入库时逐轮保留原始 dia_id，召回后可以精确对账。
DIA = re.compile(r"\[(D\d+:\d+)\]")


_LOCAL = {"127.0.0.1", "localhost", "::1", "0.0.0.0"}


def _opener(url: str):
    """本机地址绕开环境代理。urllib 会读 HTTP_PROXY，而经 SSH 隧道暴露的服务
    在 127.0.0.1 上，走代理只会拿到 502。"""
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    if host in _LOCAL or host.startswith("172.18.") or host.startswith("192.168."):
        return urllib.request.build_opener(urllib.request.ProxyHandler({}))
    return urllib.request.build_opener()


def http_json(url: str, body: dict | None, token: str | None, timeout: int = 120) -> dict:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method="POST" if body is not None else "GET")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    with _opener(url).open(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def sessions_of(conv: dict) -> list[tuple[int, str, list[dict]]]:
    """返回 [(会话序号, 日期, 轮次列表)]，按序号排序。"""
    out = []
    for key, value in conv.items():
        if not key.startswith("session_") or key.endswith("date_time"):
            continue
        num = int(key.split("_")[1])
        out.append((num, conv.get(key + "_date_time", ""), value))
    out.sort(key=lambda x: x[0])
    return out


def session_text(num: int, date: str, turns: list[dict]) -> str:
    head = f"Conversation on {date} (session {num})." if date else f"Session {num}."
    lines = [head]
    for t in turns:
        caption = t.get("blip_caption")
        text = t.get("text", "")
        if caption:
            text = f"{text} [shares a photo: {caption}]"
        lines.append(f"[{t.get('dia_id', '')}] {t.get('speaker', '?')}: {text}")
    return "\n".join(lines)


def load_questions(path: Path) -> list[dict]:
    data = json.loads(path.read_text())
    rows = []
    for conv in data:
        sample = conv["sample_id"]
        for i, qa in enumerate(conv["qa"]):
            rows.append({
                "id": f"{sample}#{i}",
                "sample": sample,
                "category": qa["category"],
                "question": qa["question"],
                "answer": str(qa.get("answer", "")),
                # 证据里可能带 "D8:6; D9:17" 这种一条多个
                "evidence": [e.strip(" ;") for e in qa.get("evidence", []) if e.strip(" ;")],
            })
    return rows


# ---------------------------------------------------------------- ingest

def do_ingest(args) -> None:
    data = json.loads(Path(args.data).read_text())
    total = sum(len(sessions_of(c["conversation"])) for c in data)
    done = 0
    for conv in data:
        sample = conv["sample_id"]
        for num, date, turns in sessions_of(conv["conversation"]):
            body = {
                "sessionId": f"locomo-{sample}-s{num}",
                "projectId": f"locomo-{sample}",
                "items": [{
                    "kind": "episode",
                    "narrative": session_text(num, date, turns),
                    "importance": 0.5,
                    "trustLevel": "manual",
                }],
                "idempotencyKey": f"locomo:{sample}:{num}",
            }
            try:
                resp = http_json(args.base + "/v1/memory/ingest", body, args.token)
                done += 1
                ids = resp.get("ids") or []
                if not ids:
                    print(f"  !! {sample} s{num}: 没有返回 id {resp}", file=sys.stderr)
            except Exception as e:  # 单条失败不该毁掉整轮
                print(f"  !! {sample} s{num}: {e}", file=sys.stderr)
            if done % 25 == 0:
                print(f"  ingested {done}/{total}", flush=True)
    print(f"ingested {done}/{total}")


# ---------------------------------------------------------------- ask

def do_ask(args) -> None:
    questions = load_questions(Path(args.data))
    if args.limit:
        questions = questions[: args.limit]
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    seen = set()
    if out.exists():
        for line in out.read_text().splitlines():
            if line.strip():
                seen.add(json.loads(line)["id"])
    todo = [q for q in questions if q["id"] not in seen]
    print(f"ask: {len(todo)} 待跑（已完成 {len(seen)}）", flush=True)

    lock_rows = []
    t0 = time.time()

    def one(q: dict) -> dict:
        started = time.time()
        try:
            resp = http_json(args.base + "/v1/memory/search",
                             {"query": q["question"], "topK": args.topk},
                             args.token)
        except Exception as e:
            return {**q, "error": str(e), "hits": 0}
        hits = resp.get("hits") or []
        gathered = set()
        for h in hits:
            gathered.update(DIA.findall(h.get("narrative") or ""))
        injection = resp.get("injection") or ""
        return {
            **q,
            "elapsedMs": resp.get("elapsedMs"),
            "abstained": resp.get("abstained"),
            "route": resp.get("route"),
            "rerank": resp.get("rerank"),
            "hits": len(hits),
            "context": injection[: args.max_context],
            # 逐 dia_id 对账：证据是否真的出现在召回文本里
            "evidenceFound": sorted(set(q["evidence"]) & gathered),
            "evidenceTotal": len(q["evidence"]),
            "retrieved": sorted(gathered)[:80],
            "wallMs": int((time.time() - started) * 1000),
        }

    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = {pool.submit(one, q): q for q in todo}
        for i, fut in enumerate(as_completed(futures), 1):
            lock_rows.append(fut.result())
            if i % 50 == 0 or i == len(todo):
                rate = i / max(1e-6, time.time() - t0)
                print(f"  {i}/{len(todo)}  {rate:.1f} q/s", flush=True)

    with out.open("a") as fh:
        for row in lock_rows:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"wrote {out}")


# ---------------------------------------------------------------- judge

ANSWER_SYSTEM = (
    "You answer questions using only the memory excerpts provided. "
    "Reply with a short phrase — a name, a date, a number, or a brief clause. "
    "No explanation, no full sentence. "
    "If the excerpts do not contain the answer, reply exactly: NOT MENTIONED"
)

JUDGE_SYSTEM = (
    "You grade an answer against a reference answer. "
    "Reply with one word: CORRECT or WRONG. "
    "A short paraphrase that carries the same fact counts as CORRECT. "
    "A date is CORRECT if it names the same day, even in another format. "
    "If the reference says the answer is absent from the conversation, then "
    "an answer that declines counts as CORRECT."
)


def llm(args, system: str, user: str) -> str:
    body = {
        "model": args.model,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": 0,
        "max_tokens": 120,
    }
    for attempt in range(3):
        try:
            resp = http_json(args.llm_base.rstrip("/") + "/chat/completions", body, args.llm_key, timeout=180)
            return (resp["choices"][0]["message"]["content"] or "").strip()
        except (urllib.error.URLError, KeyError, TimeoutError, json.JSONDecodeError) as e:
            if attempt == 2:
                return f"__ERR__ {e}"
            time.sleep(1.5 * (attempt + 1))
    return "__ERR__"


def do_judge(args) -> None:
    rows = [json.loads(l) for l in Path(args.out).read_text().splitlines() if l.strip()]
    out = Path(args.out).with_suffix(".judged.jsonl")
    seen = set()
    if out.exists():
        for line in out.read_text().splitlines():
            if line.strip():
                seen.add(json.loads(line)["id"])
    todo = [r for r in rows if r["id"] not in seen and "error" not in r]
    print(f"judge: {len(todo)} 待判（已完成 {len(seen)}）", flush=True)
    t0 = time.time()

    def one(r: dict) -> dict:
        ctx = r.get("context") or ""
        prompt = (f"MEMORY EXCERPTS:\n{ctx or '(nothing retrieved)'}\n\n"
                  f"QUESTION: {r['question']}\nAnswer:")
        answer = llm(args, ANSWER_SYSTEM, prompt)

        gold = r["answer"]
        # 类别 5 是「这段对话里根本没这回事」，标准答案是一个看起来很像的错答案。
        # 这类题判的是「有没有被诱导」，所以拒答才算对。
        adversarial = r["category"] == 5
        if adversarial:
            correct = answer.upper().startswith("NOT MENTIONED")
            verdict = "CORRECT" if correct else "WRONG"
        else:
            verdict_raw = llm(args, JUDGE_SYSTEM,
                              f"REFERENCE: {gold}\nCANDIDATE: {answer}\nVerdict:")
            verdict = "CORRECT" if "CORRECT" in verdict_raw.upper().replace("INCORRECT", "") else "WRONG"
            correct = verdict == "CORRECT"
        return {**r, "answer": answer, "verdict": verdict, "correct": correct,
                "adversarial": adversarial}

    results = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(one, r) for r in todo]
        for i, fut in enumerate(as_completed(futures), 1):
            results.append(fut.result())
            if i % 25 == 0 or i == len(todo):
                print(f"  {i}/{len(todo)}  {i / max(1e-6, time.time() - t0):.1f} q/s", flush=True)

    with out.open("a") as fh:
        for row in results:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"wrote {out}")


# ---------------------------------------------------------------- report

def do_report(args) -> None:
    asked = Path(args.out)
    judged = asked.with_suffix(".judged.jsonl")
    ask_rows = {r["id"]: r for r in (json.loads(l) for l in asked.read_text().splitlines() if l.strip())}
    judge_rows = {}
    if judged.exists():
        judge_rows = {r["id"]: r for r in (json.loads(l) for l in judged.read_text().splitlines() if l.strip())}

    cats: dict[int, dict[str, list[float]]] = {}
    for qid, r in ask_rows.items():
        c = r["category"]
        bucket = cats.setdefault(c, {"recall_any": [], "recall_all": [], "acc": [], "lat": []})
        found, total = len(r.get("evidenceFound") or []), r.get("evidenceTotal") or 0
        bucket["recall_any"].append(1.0 if found > 0 else 0.0)
        bucket["recall_all"].append(1.0 if total and found >= total else 0.0)
        if r.get("elapsedMs"):
            bucket["lat"].append(float(r["elapsedMs"]))
        j = judge_rows.get(qid)
        if j is not None:
            bucket["acc"].append(1.0 if j.get("correct") else 0.0)

    def mean(xs):
        return sum(xs) / len(xs) if xs else float("nan")

    def pct(x):
        return "—" if x != x else f"{x * 100:.1f}%"

    lines = ["| 类别 | 题数 | 证据召回(任意) | 证据召回(全部) | 准确率 | 检索 p50 |",
             "| --- | ---: | ---: | ---: | ---: | ---: |"]
    all_bucket = {"recall_any": [], "recall_all": [], "acc": [], "lat": []}
    for c in sorted(cats):
        b = cats[c]
        for k in all_bucket:
            all_bucket[k].extend(b[k])
        lat = sorted(b["lat"])
        p50 = f"{lat[len(lat) // 2]:.0f} ms" if lat else "—"
        lines.append(f"| {c} {CATEGORY_NAMES.get(c, '')} | {len(b['recall_any'])} | "
                     f"{pct(mean(b['recall_any']))} | {pct(mean(b['recall_all']))} | "
                     f"{pct(mean(b['acc']))} | {p50} |")
    lat = sorted(all_bucket["lat"])
    p50 = f"{lat[len(lat) // 2]:.0f} ms" if lat else "—"
    lines.append(f"| **合计** | {len(all_bucket['recall_any'])} | "
                 f"**{pct(mean(all_bucket['recall_any']))}** | "
                 f"**{pct(mean(all_bucket['recall_all']))}** | "
                 f"**{pct(mean(all_bucket['acc']))}** | {p50} |")

    table = "\n".join(lines)
    print(table)
    if args.table_out:
        Path(args.table_out).write_text(table + "\n")
        print(f"wrote {args.table_out}")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("stage", choices=["ingest", "ask", "judge", "report"])
    p.add_argument("--data", default="/tmp/locomo10.json")
    p.add_argument("--base", default=os.environ.get("MNEMO_BASE", "http://127.0.0.1:8090"))
    p.add_argument("--token", default=os.environ.get("MNEMO_TOKEN", ""))
    p.add_argument("--out", default="bench/locomo/out/ask.jsonl")
    p.add_argument("--table-out", default="")
    p.add_argument("--topk", type=int, default=12)
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--concurrency", type=int, default=6)
    p.add_argument("--max-context", type=int, default=6000)
    p.add_argument("--llm-base", default=os.environ.get("BENCH_LLM_BASE", ""))
    p.add_argument("--llm-key", default=os.environ.get("BENCH_LLM_KEY", ""))
    p.add_argument("--model", default=os.environ.get("BENCH_LLM_MODEL", ""))
    args = p.parse_args()

    {"ingest": do_ingest, "ask": do_ask, "judge": do_judge, "report": do_report}[args.stage](args)


if __name__ == "__main__":
    main()
