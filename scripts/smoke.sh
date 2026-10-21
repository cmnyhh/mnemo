#!/usr/bin/env bash
# 端到端冒烟：写入 → 召回 → 覆盖 → 无匹配弃权 → 重启后仍在
set -u
BASE=http://127.0.0.1:8090
# 令牌在控制台「令牌」页签发后传进来：MEMORY_TOKEN=mn_... ./scripts/smoke.sh
TOKEN="${MEMORY_TOKEN:-}"
if [ -z "$TOKEN" ]; then
  echo "缺少 MEMORY_TOKEN：先在控制台「令牌」页签发一把" >&2
  exit 2
fi
AUTH="authorization: Bearer $TOKEN"
JSON="content-type: application/json"

fmt() {
  if command -v python3 >/dev/null 2>&1; then
    python3 -c '
import sys, json
d = json.load(sys.stdin)
print("  route=%s abstained=%s count=%s ms=%s" % (d.get("route"), d.get("abstained"), d.get("count"), d.get("elapsedMs")))
if d.get("degradeReason"): print("  degrade:", d["degradeReason"][:120])
for h in d.get("hits", []):
    print("  %.3f %-10s bm25=%-3s dense=%-3s %s" % (h["score"], h["kind"], h.get("bm25Rank"), h.get("denseRank"), h["narrative"][:52]))
if d.get("injection"): print("  --- injection ---\n" + "\n".join("  " + l for l in d["injection"].strip().split("\n")))
'
  else
    head -c 600
  fi
}

echo "== health =="; curl -s $BASE/v1/memory/health; echo
echo "== readyz =="; curl -s $BASE/v1/memory/readyz; echo
echo "== auth: 无 token 应 401 =="
curl -s -o /dev/null -w "  status=%{http_code}\n" -X POST $BASE/v1/memory/search -H "$JSON" -d '{"query":"x"}'

echo "== ingest: 3 条结构化记忆 =="
curl -s -X POST $BASE/v1/memory/ingest -H "$AUTH" -H "$JSON" -d '{
  "projectId": "kb-service",
  "items": [
    {"kind":"fact","scope":"project","narrative":"知识库服务的 API 监听 10.0.0.12:8080，前端走 80 端口","subjectKey":"kb-service-api","predicate":"port","objectValue":"8080","facts":{"host":"10.0.0.12","port":8080},"importance":0.8,"trustLevel":"high"},
    {"kind":"preference","scope":"user","narrative":"对外文档不写本地与云端之分，统一只说大模型服务","importance":0.9,"trustLevel":"high","predicate":"doc_style"},
    {"kind":"episode","scope":"project","narrative":"10 月 16 日清盘时缓存回收 25G，swap 占用 18G，根分区回到 37%","importance":0.5}
  ]
}'; echo

echo "== ingest: 一轮对话（兜底抽取）=="
curl -s -X POST $BASE/v1/memory/ingest -H "$AUTH" -H "$JSON" -d '{
  "projectId": "kb-service",
  "idempotencyKey": "turn-demo-1",
  "turn": {"sessionId":"s-demo","index":1,"messages":[
    {"role":"user","content":"记忆服务部署在哪台机器上？"},
    {"role":"assistant","content":"部署在 example.com 这台 2 核机器上，用 docker compose 跑 PG、Qdrant 和 Java 服务。"}]}
}'; echo

echo "== ingest: 幂等重放（同一 key）=="
curl -s -X POST $BASE/v1/memory/ingest -H "$AUTH" -H "$JSON" -d '{
  "projectId": "kb-service",
  "idempotencyKey": "turn-demo-1",
  "turn": {"sessionId":"s-demo","index":1,"messages":[{"role":"user","content":"记忆服务部署在哪台机器上？"}]}
}'; echo

echo "== search: 知识库 API 端口 =="
curl -s -X POST $BASE/v1/memory/search -H "$AUTH" -H "$JSON" -d '{"query":"知识库 API 端口是多少","topK":5}' | fmt

echo "== search: 仅 BM25 =="
curl -s -X POST $BASE/v1/memory/search -H "$AUTH" -H "$JSON" -d '{"query":"swap 清盘 缓存","mode":"bm25","topK":5}' | fmt

echo "== search: 无匹配（应弃权）=="
curl -s -X POST $BASE/v1/memory/search -H "$AUTH" -H "$JSON" -d '{"query":"量子纠缠 拓扑绝缘体","topK":5}' | fmt

echo "== search: 无效 mode 应 400 =="
curl -s -o /dev/null -w "  status=%{http_code}\n" -X POST $BASE/v1/memory/search -H "$AUTH" -H "$JSON" -d '{"query":"x","mode":"magic"}'

echo "== stats =="
curl -s $BASE/v1/memory/stats -H "$AUTH"; echo
