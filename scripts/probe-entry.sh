#!/usr/bin/env bash
# 验证「不同入口的对话形状」都能入记忆——不是只有某个桥接能用。
#
# 为什么要有这条：入口有很多种，但它们塞给模型的东西归一化之后只有三种形状
# （整段对话报文 / XML 上下文块 / 【小节】说明书+快照），另外两种常见变体是
# 「纯文本直连」和「半角昵称多行」。谁家的宿主换了，形状还是这几个，
# 所以这里按形状验证，不按产品验证。
#
# 用法：bash scripts/probe-entry.sh [服务地址] [令牌]
set -uo pipefail

BASE="${1:-http://127.0.0.1:8090}"
TOKEN="${2:-${MNEMO_SERVICE_TOKEN:-}}"
if [[ -z "$TOKEN" && -r /etc/dsh-web.env ]]; then
  TOKEN=$(grep -m1 '^MNEMO_SERVICE_TOKEN=' /etc/dsh-web.env | cut -d= -f2-)
fi
if [[ -z "$TOKEN" ]]; then
  echo "缺少令牌：传第二个参数，或设 MNEMO_SERVICE_TOKEN" >&2
  exit 2
fi

python3 - "$BASE" "$TOKEN" <<'PY'
import json
import sys
import urllib.request

base, token = sys.argv[1], sys.argv[2]


def post(path, payload):
    req = urllib.request.Request(
        base + path,
        data=json.dumps(payload, ensure_ascii=False).encode(),
        headers={"authorization": "Bearer " + token, "content-type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=90) as resp:
        return json.loads(resp.read().decode())


LONG_LOG = "\n".join("2026-10-22 03:30:0%d INFO backup chunk %d ok" % (i % 10, i) for i in range(200))

CASES = [
    (
        "纯文本直连（脚本 / API）",
        [{"role": "user", "content": "记忆服务的备份脚本每天 03:30 由 cron 触发，输出到 /srv/backup，保留 14 天。"},
         {"role": "assistant", "content": "已确认，和 crontab 里的一致。"}],
        "备份脚本",
    ),
    (
        "整段对话报文（webhook 透传）",
        [{"role": "user", "content": json.dumps([
            {"role": "user", "content": "向量库换成 Qdrant 1.12 了，旧的 1.9 删掉"},
            {"role": "assistant", "content": "已改：镜像 qdrant/qdrant:v1.12.0，数据卷保留"},
        ], ensure_ascii=False)},
         {"role": "assistant", "content": "记下了。"}],
        "Qdrant 1.12",
    ),
    (
        "XML 上下文块（IDE / CLI）",
        [{"role": "user", "content": (
            "<system-reminder>\nNever fabricate paths. Available tools: Read, Bash, Edit.\n</system-reminder>\n\n"
            "<context name=\"repo\">\n/srv/memory-service (java, maven)\n"
            "---- src/main/java/com/hs/memory/ingest/IngestService.java ----\n"
            "package com.hs.memory.ingest;\nimport java.util.List;\n</context>\n\n"
            "<user_query>群聊摘要为什么一直写成空的？</user_query>")},
         {"role": "assistant", "content": "因为信封没剥，真实内容被截断切掉了。"}],
        "群聊摘要",
    ),
    (
        "半角昵称多行（其他 IM / 转发）",
        [{"role": "user", "content": "Alice: 控制台端口我改成 9090 了\nBob: 收到，反代那边我来改"},
         {"role": "assistant", "content": "好，记一下：控制台 9090，反代同步。"}],
        "控制台端口",
    ),
    (
        "长消息（粘贴日志，问句在头、追问在尾）",
        [{"role": "user", "content": "开头：这堆备份日志里有报错吗？\n" + LONG_LOG + "\n末尾追加：顺便看下磁盘还剩多少。"},
         {"role": "assistant", "content": "没看到 ERROR，全是 INFO；磁盘还剩 41G。"}],
        "备份日志",
    ),
    (
        "英文 XML 上下文（同一宿主换语言）",
        [{"role": "user", "content": (
            "<system-reminder>\nYou are a coding assistant working in an IDE. Never fabricate paths.\n</system-reminder>\n\n"
            "<context name=\"repo\">\n/srv/memory-service\n"
            "---- IngestService.java ----\npackage com.hs.memory.ingest;\n</context>\n\n"
            "<user_query>Why do group chat summaries come out empty?</user_query>")},
         {"role": "assistant", "content": "Because the envelope was not stripped before truncation."}],
        "group chat summaries",
    ),
]

print("== 六种入口形状逐个写入 ==")
for i, (name, messages, must_keep) in enumerate(CASES, 1):
    session = "probe-entry-%d" % i
    try:
        resp = post("/v1/memory/ingest", {
            "userId": "probe", "projectId": "probe", "sessionId": session,
            "turn": {"sessionId": session, "index": 1, "trigger": "none", "messages": messages},
        })
    except Exception as e:  # noqa: BLE001
        print("[%d/6] %-28s 请求失败: %s" % (i, name, e))
        continue

    extraction = resp.get("extraction") or "?"
    accepted = resp.get("accepted")
    note = (resp.get("note") or "").replace("\n", " ")[:60]
    flag = "ok " if accepted else "!! "
    print("[%d/6] %s%-28s extraction=%-18s accepted=%s %s"
          % (i, flag, name, extraction, accepted, note))
    if not accepted:
        print("        ↑ 这条入口没写进记忆，问题在形状判定，不在入口本身")

print()
print("== 回查：每条入口记下的东西还能不能被检索到 ==")
for i, (name, _, must_keep) in enumerate(CASES, 1):
    try:
        hits = post("/v1/memory/search", {"query": must_keep, "projectId": "probe"})
    except Exception as e:  # noqa: BLE001
        print("[%d] %-28s 检索失败: %s" % (i, name, e))
        continue
    top = hits.get("hits", [])[:3]
    hit = next((h for h in top if must_keep.lower() in (h.get("narrative") or "").lower()), None)
    if hit is None:
        print("[%d] !! %-28s 检索不到「%s」，top1=%s"
              % (i, name, must_keep, (top[0].get("narrative", "")[:60] if top else "空")))
        continue
    print("[%d] ok %-28s %.3f  %s"
          % (i, name, hit.get("score", 0), hit.get("narrative", "")[:110].replace("\n", " ")))

print()
print("（清理：update memory_item set status='deleted' where project_id='probe';）")
PY
