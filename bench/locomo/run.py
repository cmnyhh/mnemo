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
import threading
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

_TOKENS: dict[str, str] = {}


def load_tokens(path: str) -> None:
    """LoCoMo 每段对话是一个独立记忆库——通行做法，也是唯一能避免跨对话串味的方式。
    令牌按 sample 映射，一个 sample 一个租户。"""
    global _TOKENS
    if path and Path(path).exists():
        _TOKENS = json.loads(Path(path).read_text())


def token_for(sample: str, fallback: str) -> str:
    return _TOKENS.get(sample, fallback)


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


# ---------------------------------------------------------------- 查询扩展

# 这段提示是全套实验里最容易做砸的地方：裸提示会让 7B 直接「猜答案」
# （问 destress 吐 reads books drinks tea），猜错等于把检索带向错误的语义区域。
# 只允许泛化类别词，禁止具体活动 / 物件 / 日期 / 数字。
REWRITE_SYSTEM = (
    "Output 8 general topic words or short phrases that a chat about this question would "
    "contain: keep every name and place from the question, then add CATEGORIES (e.g. hobbies, "
    "exercise, art, socializing) for whatever is being asked about. Rules: never answer the "
    "question; never name a specific activity, object, place, date or number that is not in "
    "the question; never guess a fact. One line, comma separated, no quotes."
)

# 显式时间锚点不扩展：加泛化词会把 "4 years ago" 这类唯一线索稀释掉（实测 #92→#129）。
TEMPORAL = re.compile(r"\b(when|how many|how long|years? ago|months? ago|19\d\d|20\d\d)\b", re.I)

_REWRITE_CACHE: dict[str, str] = {}
_REWRITE_FAILS = [0]


def expand_query(q: str, args) -> str:
    """返回扩展词；失败返回空串（调用方退回原查询）。"""
    if q in _REWRITE_CACHE:
        return _REWRITE_CACHE[q]
    if getattr(args, "rewrite_gate", False) and TEMPORAL.search(q):
        _REWRITE_CACHE[q] = ""
        return ""
    try:
        resp = http_json(args.rewrite_base.rstrip("/") + "/chat/completions", {
            "model": args.rewrite_model,
            "temperature": 0,
            "max_tokens": 120,
            "messages": [
                {"role": "system", "content": REWRITE_SYSTEM},
                {"role": "user", "content": q},
            ],
        }, args.rewrite_key, timeout=args.rewrite_timeout)
        text = ((resp.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
        line = text.strip().splitlines()[0] if text.strip() else ""
        parts = [p.strip() for p in re.split(r"[,;]", line) if p.strip()]
        parts = [p for p in parts if not re.search(r"\d", p)]
        out = ", ".join(parts)[:200]
    except Exception:
        _REWRITE_FAILS[0] += 1
        out = ""
    _REWRITE_CACHE[q] = out
    return out


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

def turn_text(num: int, date: str, turn: dict) -> str:
    """一轮一条记忆。产品里写入的就是这种原子粒度，注入块每条最多渲染 220 字，
    整段会话当一条写进去，答案多半落在截断之后——那是评测方法的问题，不是系统的。"""
    caption = turn.get("blip_caption")
    text = turn.get("text", "")
    if caption:
        text = f"{text} [shares a photo: {caption}]"
    when = f"On {date}" if date else f"In session {num}"
    return f"{when}, [{turn.get('dia_id', '')}] {turn.get('speaker', '?')}: {text}"


def do_ingest(args) -> None:
    data = json.loads(Path(args.data).read_text())
    units = []
    for conv in data:
        sample = conv["sample_id"]
        for num, date, turns in sessions_of(conv["conversation"]):
            if args.granularity == "session":
                units.append((sample, f"{sample}:s{num}", session_text(num, date, turns), None))
            else:
                for turn in turns:
                    dia = turn.get("dia_id") or f"s{num}-{len(units)}"
                    # turn 是「同会话里的第几轮」，服务端靠它做邻域回填（带上前后各一句）。
                    # 不给这个字段，检索只能给孤立的一条，模型看不出指代关系。
                    idx = int(dia.split(":")[1]) if ":" in dia and dia.split(":")[1].isdigit() else None
                    units.append((sample, f"{sample}:{dia}", turn_text(num, date, turn), idx))
    total = len(units)
    print(f"ingest: {total} 条（粒度 {args.granularity}）", flush=True)
    done = [0]
    lock = threading.Lock()

    def one(unit):
        sample, key, narrative, turn_index = unit
        body = {
            "sessionId": key.split(":")[1] if ":" in key else key,
            "projectId": f"locomo-{sample}",
            "items": [{"kind": "episode", "narrative": narrative,
                       "importance": 0.5, "trustLevel": "high", "turn": turn_index}],
            "idempotencyKey": f"locomo:{key}",
        }
        try:
            resp = http_json(args.base + "/v1/memory/ingest", body, token_for(sample, args.token))
            ok = bool(resp.get("ids") or resp.get("items"))
            note = "" if ok else str(resp)[:120]
        except Exception as e:
            ok, note = False, str(e)[:120]
        with lock:
            done[0] += 1
            if not ok:
                print(f"  !! {key}: {note}", file=sys.stderr)
            if done[0] % 250 == 0:
                print(f"  ingested {done[0]}/{total}", flush=True)

    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        list(pool.map(one, units))
    print(f"ingested {done[0]}/{total}")


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
        expanded = expand_query(q["question"], args) if args.rewrite_base else ""
        query = expanded or q["question"]
        try:
            resp = http_json(args.base + "/v1/memory/search",
                             {"query": query, "topK": args.topk},
                             token_for(q["sample"], args.token))
        except Exception as e:
            return {**q, "error": str(e), "hits": 0}
        hits = resp.get("hits") or []
        gathered = set()
        for h in hits:
            gathered.update(DIA.findall(h.get("narrative") or ""))
        injection = resp.get("injection") or ""
        return {
            **q,
            # 审计用：改写实际发出去的查询。judge 仍按原问句判分，不受影响。
            "expandedQuery": expanded,
            "queryUsed": query,
            "elapsedMs": resp.get("elapsedMs"),
            "abstained": resp.get("abstained"),
            "route": resp.get("route"),
            "rerank": resp.get("rerank"),
            "hits": len(hits),
            "context": injection[: args.max_context],
            # 逐 dia_id 对账：证据是否真的出现在召回文本里
            "evidenceFound": sorted(set(q["evidence"]) & gathered),
            "evidenceTotal": len(q["evidence"]),
            "retrieved": sorted(gathered, key=lambda d: (int(d.split(":")[0][1:]), int(d.split(":")[1]))),
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


# ---------------------------------------------------------------- probe

def miss_sessions(row: dict) -> set[str]:
    """这一题里「整段会话一条都没召回」的那些会话。

    会话靠 dia_id 的前缀区分（D1:3 -> D1）。判据是：某条证据所在的会话，
    在 retrieved 里一个 dia_id 都没有——这正是邻域回填救不了的那批，
    因为回填要从已命中的条目出发，没有锚点就无从下手。
    """
    ev = [p.strip() for e in (row.get("evidence") or []) for p in e.split(";") if p.strip()]
    got = set(row.get("retrieved") or [])
    missing = {e.split(":")[0] for e in ev if e not in got}
    covered = {g.split(":")[0] for g in got}
    return missing - covered


def do_probe(args) -> None:
    """深度探针：只检索、不作答，用来区分「表示不行」和「排得靠后」。

    做法是把 topK 放宽再查同一个 query，看零命中会话里的证据轮能不能进榜、排第几、
    向量分多少。这一步不调 LLM，所以比 ask 快两个量级，但能一次性说明：
    加深度到底能不能救回那批，还是必须换检索表示（话题簇）才行。

    只探基线里零命中的题（--baseline 给 ask.jsonl），全量跑既慢又没必要。
    """
    questions = load_questions(Path(args.data))
    ids = None
    if args.baseline:
        rows = [json.loads(l) for l in Path(args.baseline).read_text().splitlines() if l.strip()]
        ids = {r["id"] for r in rows if miss_sessions(r)}
        print(f"probe: 基线里零命中会话的题 {len(ids)} 道", flush=True)
    picked = [q for q in questions if ids is None or q["id"] in ids]
    if args.limit:
        picked = picked[: args.limit]

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    print(f"probe: {len(picked)} 道，topK={args.topk}，并发 {args.concurrency}", flush=True)

    def one(q: dict) -> dict:
        try:
            resp = http_json(args.base + "/v1/memory/search",
                             {"query": q["question"], "topK": args.topk},
                             token_for(q["sample"], args.token))
        except Exception as e:
            return {"id": q["id"], "error": str(e)[:200]}
        hits = resp.get("hits") or []
        inj = resp.get("injection") or ""
        return {
            "id": q["id"], "sample": q["sample"], "category": q["category"],
            "question": q["question"], "evidence": q["evidence"],
            "abstained": resp.get("abstained"), "rerank": resp.get("rerank"),
            "route": resp.get("route"), "elapsedMs": resp.get("elapsedMs"),
            "injectionLen": len(inj),
            # 邻居行数：注入块里带〔相邻轮次〕标记的条数，用来量回填有没有被预算丢掉
            "neighbors": inj.count("〔相邻轮次〕"),
            "hits": [{
                "dia": DIA.findall(h.get("narrative") or ""),
                "score": h.get("score"), "rrf": h.get("rrf"),
                "dense": h.get("denseScore"), "rel": h.get("rerankScore"),
                "bm25Rank": h.get("bm25Rank"), "denseRank": h.get("denseRank"),
                "tier": h.get("tier"),
            } for h in hits],
        }

    t0 = time.time()
    done = 0
    with out.open("w") as fh, ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(one, q) for q in picked]
        for fut in as_completed(futures):
            fh.write(json.dumps(fut.result(), ensure_ascii=False) + "\n")
            fh.flush()
            done += 1
            if done % 50 == 0 or done == len(picked):
                print(f"  {done}/{len(picked)}  {done / max(1e-6, time.time() - t0):.1f} q/s", flush=True)
    print(f"wrote {out}")


# ---------------------------------------------------------------- judge

ANSWER_SYSTEM = (
    "You answer questions using only the memory excerpts provided. "
    "Reply with a short phrase — a name, a date, a number, or a brief clause. "
    "No explanation, no full sentence. "
    "If the excerpts do not contain the answer, reply exactly: NOT MENTIONED"
)

JUDGE_SYSTEM = (
    "You are grading whether a candidate answer conveys the same fact as the reference answer.\n"
    "Say CORRECT if a reader would take away the same fact (paraphrase, abbreviation, or "
    "equivalent date format all count).\n"
    "Say WRONG if the candidate states a different fact, is vague, or declines while the "
    "reference has an answer.\n"
    "Reply with one word: CORRECT or WRONG."
)

# 第 5 类（adversarial）的题面预设了一件从未发生的事，标准答案字段只有陷阱答案。
# 判的是「有没有被诱导」，所以拒答才算对。措辞比 exact match 宽一点，
# 否则「The excerpts don't mention it」这种完全正确的回答会被判错。
DECLINE = re.compile(
    r"not mentioned|no mention|no information|not specified|not stated|no record|"
    r"(?:don'?t|doesn'?t|didn'?t) (?:mention|have|know|contain|include|say|provid)|"
    r"not (?:in|present in) the|never (?:mentioned|happened|occurred)|"
    r"unknown|cannot (?:be )?(?:determined|answer)|not enough (?:information|context)|"
    r"does not (?:appear|say|mention)|did not (?:happen|say|mention)",
    re.I,
)


def is_decline(answer: str) -> bool:
    return bool(DECLINE.search(answer or "")) or (answer or "").strip().upper() == "NOT MENTIONED"


def verdict_of(raw: str) -> str:
    """判分模型的回复可能带解释，只取第一个判定词。"""
    hit = re.search(r"\b(CORRECT|INCORRECT|WRONG)\b", (raw or "").upper())
    if not hit:
        return "WRONG"
    return "CORRECT" if hit.group(1) == "CORRECT" else "WRONG"


def llm(args, system: str, user: str, model: str | None = None, max_tokens: int = 600) -> str:
    # 推理型模型会先花 token 思考再吐正文；给 120 这种小额度，正文直接是空的。
    # 这不是「答不出」，是预算给错了——所以默认留足。
    body = {
        "model": model or args.model,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": 0,
        "max_tokens": max_tokens,
    }
    # 429 是配额问题，重试要退避得足够久才有意义；退完了还不行就显式报错，
    # 绝不能默认当「答错」——那会把限流计成模型的错，指标就假了。
    for attempt in range(5):
        try:
            resp = http_json(args.llm_base.rstrip("/") + "/chat/completions", body, args.llm_key, timeout=180)
            return (resp["choices"][0]["message"]["content"] or "").strip()
        except urllib.error.HTTPError as e:
            if attempt == 4:
                return f"__ERR__ HTTP {e.code}"
            time.sleep(min(30, 3 * (2 ** attempt)))
        except (urllib.error.URLError, KeyError, TimeoutError, json.JSONDecodeError) as e:
            if attempt == 4:
                return f"__ERR__ {e}"
            time.sleep(2 * (attempt + 1))
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
        answer = llm(args, ANSWER_SYSTEM, prompt, max_tokens=700)
        if answer.startswith("__ERR__"):
            return {**r, "gold": r.get("answer"), "answer": answer, "verdict": "ERROR",
                    "correct": None, "adversarial": r["category"] == 5}

        gold = r["answer"]
        adversarial = r["category"] == 5
        if adversarial:
            correct = is_decline(answer)
            verdict = "CORRECT" if correct else "WRONG"
        else:
            raw = llm(args, JUDGE_SYSTEM,
                      f"QUESTION: {r['question']}\nREFERENCE: {gold}\nCANDIDATE: {answer}\nVerdict:",
                      model=args.judge_model or args.model, max_tokens=400)
            if raw.startswith("__ERR__"):
                return {**r, "gold": gold, "answer": answer, "verdict": "ERROR",
                        "correct": None, "adversarial": False}
            verdict = verdict_of(raw)
            correct = verdict == "CORRECT"
        return {**r, "gold": gold, "answer": answer, "verdict": verdict,
                "correct": correct, "adversarial": adversarial}

    results = []
    # 边跑边落盘：判分这一步要二十多分钟，中途断了不该从零开始
    with out.open("a") as fh, ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(one, r) for r in todo]
        for i, fut in enumerate(as_completed(futures), 1):
            row = fut.result()
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
            fh.flush()
            if i % 25 == 0 or i == len(todo):
                print(f"  {i}/{len(todo)}  {i / max(1e-6, time.time() - t0):.1f} q/s", flush=True)
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
        bucket = cats.setdefault(c, {"recall_any": [], "recall_all": [], "acc": [], "lat": [], "err": []})
        # 证据字段里存在 "D8:6; D9:17" 这种一条塞多个的，得先拆开；
        # 少数 open-domain 题本身没标证据，不能算作漏召，直接排除出分母。
        ev = [p.strip() for e in (r.get("evidence") or []) for p in e.split(";") if p.strip()]
        if ev:
            got = set(r.get("retrieved") or [])
            found = len([x for x in ev if x in got])
            bucket["recall_any"].append(1.0 if found > 0 else 0.0)
            bucket["recall_all"].append(1.0 if found == len(ev) else 0.0)
        if r.get("elapsedMs"):
            bucket["lat"].append(float(r["elapsedMs"]))
        j = judge_rows.get(qid)
        if j is not None and j.get("correct") is not None:
            bucket["acc"].append(1.0 if j.get("correct") else 0.0)
        if j is not None and j.get("verdict") == "ERROR":
            bucket["err"].append(1.0)

    def mean(xs):
        return sum(xs) / len(xs) if xs else float("nan")

    def pct(x):
        return "—" if x != x else f"{x * 100:.1f}%"

    lines = ["| 类别 | 题数 | 证据召回(任意) | 证据召回(全部) | 准确率 | 检索 p50 | 判分失败 |",
             "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    all_bucket = {"recall_any": [], "recall_all": [], "acc": [], "lat": [], "err": []}
    for c in sorted(cats):
        b = cats[c]
        for k in all_bucket:
            all_bucket[k].extend(b[k])
        lat = sorted(b["lat"])
        p50 = f"{lat[len(lat) // 2]:.0f} ms" if lat else "—"
        lines.append(f"| {c} {CATEGORY_NAMES.get(c, '')} | {len(b['recall_any'])} | "
                     f"{pct(mean(b['recall_any']))} | {pct(mean(b['recall_all']))} | "
                     f"{pct(mean(b['acc']))} | {p50} | {len(b['err'])} |")
    lat = sorted(all_bucket["lat"])
    p50 = f"{lat[len(lat) // 2]:.0f} ms" if lat else "—"
    lines.append(f"| **合计** | {len(all_bucket['recall_any'])} | "
                 f"**{pct(mean(all_bucket['recall_any']))}** | "
                 f"**{pct(mean(all_bucket['recall_all']))}** | "
                 f"**{pct(mean(all_bucket['acc']))}** | {p50} | {len(all_bucket['err'])} |")

    table = "\n".join(lines)
    print(table)
    if args.table_out:
        Path(args.table_out).write_text(table + "\n")
        print(f"wrote {args.table_out}")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("stage", choices=["ingest", "ask", "probe", "judge", "report"])
    p.add_argument("--data", default="/tmp/locomo10.json")
    p.add_argument("--base", default=os.environ.get("MNEMO_BASE", "http://127.0.0.1:8090"))
    p.add_argument("--token", default=os.environ.get("MNEMO_TOKEN", ""))
    p.add_argument("--tokens", default=os.environ.get("MNEMO_TOKENS_FILE", ""))
    p.add_argument("--out", default="bench/locomo/out/ask.jsonl")
    p.add_argument("--table-out", default="")
    p.add_argument("--topk", type=int, default=12)
    p.add_argument("--limit", type=int, default=0)
    # ★ 默认 2，不是 6。ask 这一步的每一次请求都会打到 embedding + rerank 端点，
    # 而硅基流动的并发配额很死：并发 6 那轮 78% 的精排被 429 打回，服务端静默退回 RRF，
    # 分数偏低且看不出来。judge 走的是自建模型端点，可以自己传大一点。
    p.add_argument("--concurrency", type=int, default=2)
    p.add_argument("--max-context", type=int, default=6000)
    p.add_argument("--granularity", choices=["turn", "session"], default="turn")
    # probe 专用：拿基线结果筛出「零命中会话」的题，只探那批。
    p.add_argument("--baseline", default="", help="probe 用：基线 ask.jsonl 路径")
    # ask 专用：查询扩展。留空 = 不改写，行为与历史完全一致。
    p.add_argument("--rewrite-base", default=os.environ.get("REWRITE_BASE", ""),
                   help="改写的 OpenAI 兼容端点；留空则关闭")
    p.add_argument("--rewrite-key", default=os.environ.get("REWRITE_KEY", ""))
    p.add_argument("--rewrite-model", default=os.environ.get("REWRITE_MODEL", "Qwen/Qwen2.5-7B-Instruct"))
    p.add_argument("--rewrite-timeout", type=int, default=20, help="改写超时（秒）")
    p.add_argument("--rewrite-gate", action="store_true",
                   help="时间类问句（when / years ago / 年份）不扩展")
    p.add_argument("--llm-base", default=os.environ.get("BENCH_LLM_BASE", ""))
    p.add_argument("--llm-key", default=os.environ.get("BENCH_LLM_KEY", ""))
    p.add_argument("--model", default=os.environ.get("BENCH_LLM_MODEL", ""))
    p.add_argument("--judge-model", default=os.environ.get("BENCH_JUDGE_MODEL", ""))
    args = p.parse_args()
    # probe 的产物不能落到 ask.jsonl 上——那会把基线覆盖掉，后面没法对照。
    if args.stage == "probe" and args.out == "bench/locomo/out/ask.jsonl":
        args.out = "bench/locomo/out/probe.jsonl"
    load_tokens(args.tokens)

    {"ingest": do_ingest, "ask": do_ask, "probe": do_probe,
     "judge": do_judge, "report": do_report}[args.stage](args)


if __name__ == "__main__":
    main()
