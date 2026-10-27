#!/usr/bin/env python3
"""离线对照：off / rules / llm 三种弃权重试策略，用真实记忆生成的问题做判别。

要点：问题不是手写的，是从库里随机抽一条记忆、让模型反推「用户会怎么问」。
两种问法分开测：
  literal    —— 正常提问，允许复用原文词汇（现实中的多数情况）
  paraphrase —— 强制换说法，不许出现原文名词（词汇不对齐时的压力测试）

★ 两个前提不能省：
  1) 样本必须是 status=active。deleted 的条目检索路径本来就不返回，拿它当「正确答案」
     会让命中率结构性归零，评测看着像「召回不行」，其实是样本错了。
  2) 只在服务端跑（要读 /srv/memory-service/.env 取口令）。它会临时改
     search.squeeze-retry，跑完恢复成 rules，别在有人用的时候跑。
"""
import http.cookiejar
import json
import random
import ssl
import statistics
import subprocess
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:8090"
MODEL_URL = "https://model.huhufox.love:18443/chat/completions"
MODEL = "glm-5.3-flash"
N_RECORDS = 24
STRATEGIES = ["off", "rules", "llm"]

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
csrf = None


def req(method, path, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("content-type", "application/json")
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    with opener.open(r, timeout=120) as resp:
        return json.loads(resp.read().decode())


def login():
    global csrf
    pw = subprocess.run(
        ["bash", "-lc", "cd /srv/memory-service && set -a && . ./.env && set +a && "
         "printf %s \"$MEMORY_ADMIN_PASSWORD\""],
        capture_output=True, text=True).stdout.strip()
    d = req("POST", "/admin/api/login", {"username": "admin", "password": pw})
    csrf = d["csrfToken"]
    return pw


def put_config(key, value):
    cfg = req("GET", "/admin/api/config")
    rev = cfg["revision"]
    return req("PUT", "/admin/api/config",
               {"values": {key: value}, "revision": rev}, {"X-CSRF-Token": csrf})


def model_key():
    sql = "select values->>'extract.api-key' from app_config limit 1"
    return subprocess.run(
        ["docker", "compose", "-p", "memory", "exec", "-T", "db", "psql", "-U", "memory",
         "-d", "memory", "-tAc", sql], capture_output=True, text=True).stdout.strip()


def ask_model(prompt, key, max_tokens=200):
    body = json.dumps({
        "model": MODEL, "temperature": 0.3, "max_tokens": max_tokens,
        "messages": [{"role": "user", "content": prompt}],
    }).encode()
    r = urllib.request.Request(MODEL_URL, data=body, method="POST")
    r.add_header("content-type", "application/json")
    r.add_header("authorization", "Bearer " + key)
    with urllib.request.urlopen(r, timeout=120, context=ctx) as resp:
        d = json.loads(resp.read().decode())
    return d["choices"][0]["message"]["content"].strip()


def records():
    out, page = [], 1
    while True:
        d = req("GET", f"/admin/api/records?size=100&page={page}")
        out.extend(d["items"])
        if page >= d["pages"] or page >= 5:
            break
        page += 1
    # 只能是 active：deleted/retired 的条目检索路径本来就取不到，拿它们当答案是自欺。
    return [r for r in out if r["kind"] in ("fact", "episode") and r.get("status") == "active"
            and r.get("narrative") and 40 <= len(r["narrative"]) <= 400]


def gen_questions(recs, key):
    pairs = []
    for r in recs:
        text = r["narrative"][:400]
        lit = ask_model(
            "回想下面这条记录，写出用户当时可能提的那个问题（就是能让助手答出这条内容的问题）。\n"
            "要求：像聊天里随口一问，一句话，不超过 25 字，只输出问题本身，不要解释。\n\n"
            f"记录：{text}", key, 80)
        par = ask_model(
            "下面是一条记录。请写出一个用户会问的问题，其答案正是这条记录。\n"
            "硬要求：问题里不许出现记录中的专有名词/术语原名，必须换成同义或更口语的说法；"
            "一句话，不超过 25 字，只输出问题本身。\n\n"
            f"记录：{text}", key, 80)
        for kind, q in (("literal", lit), ("paraphrase", par)):
            q = " ".join(q.split())
            q = q.strip('"“”\' ')
            if q:
                pairs.append({"id": r["id"], "kind": kind, "query": q,
                              "narrative": text[:60]})
    return pairs


def run_recall(q):
    t = time.time()
    d = req("POST", "/admin/api/recall", {"query": q}, {"X-CSRF-Token": csrf})
    return d, (time.time() - t) * 1000


def evaluate(pairs, strategy):
    put_config("search.squeeze-retry", strategy)
    time.sleep(0.6)
    rows = []
    for p in pairs:
        d, ms = run_recall(p["query"])
        ids = [h["id"] for h in (d.get("hits") or [])]
        rank = ids.index(p["id"]) + 1 if p["id"] in ids else None
        rows.append({"kind": p["kind"], "abstained": d.get("abstained"),
                     "count": d.get("count"), "rank": rank, "ms": ms,
                     "note": d.get("degradeReason") or ""})
    return rows


def summarize(rows):
    out = {}
    for kind in ("literal", "paraphrase"):
        sub = [r for r in rows if r["kind"] == kind]
        n = len(sub)
        out[kind] = {
            "n": n,
            "abstain": sum(1 for r in sub if r["abstained"]) / max(1, n),
            "hit1": sum(1 for r in sub if r["rank"] == 1) / max(1, n),
            "hit3": sum(1 for r in sub if r["rank"] and r["rank"] <= 3) / max(1, n),
            "hit5": sum(1 for r in sub if r["rank"] and r["rank"] <= 5) / max(1, n),
            "ms": statistics.mean([r["ms"] for r in sub]) if sub else 0,
        }
    return out


def main():
    login()
    key = model_key()
    recs = records()
    random.Random(424242).shuffle(recs)
    recs = recs[:N_RECORDS]
    print(f"抽样 {len(recs)} 条记忆，生成问题中…", flush=True)
    pairs = gen_questions(recs, key)
    print(f"问题 {len(pairs)} 条（literal {sum(1 for p in pairs if p['kind']=='literal')}"
          f" / paraphrase {sum(1 for p in pairs if p['kind']=='paraphrase')}）", flush=True)

    result = {}
    for s in STRATEGIES:
        print(f"跑策略 {s} …", flush=True)
        result[s] = summarize(evaluate(pairs, s))

    put_config("search.squeeze-retry", "rules")

    print("\n策略  问法        弃权率  Hit@1  Hit@3  Hit@5  延迟ms")
    for s in STRATEGIES:
        for kind in ("literal", "paraphrase"):
            m = result[s][kind]
            print(f"{s:<6}{kind:<12}{m['abstain']*100:5.1f}% {m['hit1']*100:6.1f}%"
                  f"{m['hit3']*100:7.1f}%{m['hit5']*100:7.1f}%{m['ms']:8.0f}")

    with open("/tmp/eval_result.json", "w") as f:
        json.dump({"pairs": pairs, "summary": result}, f, ensure_ascii=False, indent=2)
    print("\n明细已写 /tmp/eval_result.json")


if __name__ == "__main__":
    main()
