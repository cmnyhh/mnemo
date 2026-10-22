#!/usr/bin/env bash
# 验证「对话摘要」这条路通不通。
#
# 每轮对话都会调一次摘要模型。它挂了不会报错——只会静默回落成 summary-fallback
# （把原文拼进去），从表面的写入结果上看不出来，只是库里越来越像聊天记录。
# 这个脚本用同一份提示词、同一个端点打一次，直接报「耗时 / 是否合法 JSON / 摘要正文 / keywords」。
#
# 用法（在服务器仓库根目录）：
#   bash scripts/probe-summary.sh                # 用 app_config 里现有的配置
#   bash scripts/probe-summary.sh kimi-k2.6      # 临时试别的模型，不改库
#   TIMEOUT=180 bash scripts/probe-summary.sh    # 慢模型放宽等待
#
# ★ 摘要是写入的同步路径：插件 outbox 串行 flush，所以「每轮 5s」意味着连聊 20 轮会积压 100s。
#   超过 8s 就该换个轻一点的模型，别只看它能不能跑通。
set -euo pipefail

PROJECT=${COMPOSE_PROJECT:-memory}
MODEL_OVERRIDE=${1:-}
TIMEOUT=${TIMEOUT:-120}
DB=$(docker ps --filter "name=${PROJECT}-db" --format '{{.Names}}' | head -1)
SRC_DIR=$(cd "$(dirname "$0")/.." && pwd)

if [ -z "$DB" ]; then
  echo "找不到 ${PROJECT}-db 容器" >&2
  exit 1
fi

read_cfg() {
  docker exec "$DB" psql -U memory -d memory -A -t -c \
    "select coalesce(values->>'$1','') from app_config" | tr -d '[:space:]'
}

PROVIDER=$(read_cfg extract.provider)
BASE_URL=$(read_cfg extract.base-url)
MODEL=$(read_cfg extract.model)
API_KEY=$(read_cfg extract.api-key)
TIMEOUT_MS=$(read_cfg extract.timeout-ms)

MODEL=${MODEL_OVERRIDE:-$MODEL}
TIMEOUT_MS=${TIMEOUT_MS:-30000}

echo "配置：provider=${PROVIDER:-<none>} model=${MODEL:-<none>} base=${BASE_URL:-<none>}"
echo "      timeout-ms=${TIMEOUT_MS}"

if [ "${PROVIDER:-none}" != "http" ]; then
  echo
  echo "✗ provider=${PROVIDER:-none}：摘要未启用，每轮只会把原文拼进去（summary-fallback）。"
  echo "  摘要与抽取共用同一份模型配置，在控制台「配置 → 抽取模型」里配好即可。"
  exit 2
fi

ENDPOINT="${BASE_URL%/}/chat/completions"
echo "      endpoint=${ENDPOINT}"
echo

export KEY="$API_KEY" ENDPOINT MODEL TIMEOUT MAX_ITEMS="${MAX_ITEMS:-8}" TIMEOUT_MS SRC_DIR
python3 - <<'PY'
import json, os, re, time, urllib.error, urllib.request

endpoint = os.environ["ENDPOINT"]
model = os.environ["MODEL"]
timeout = float(os.environ["TIMEOUT"])
service_timeout = int(os.environ["TIMEOUT_MS"])

# 提示词从源码取，保证测的就是线上那一段
src = os.path.join(os.environ["SRC_DIR"],
                   "service/src/main/java/com/hs/memory/extract/SummarizePrompt.java")
system = re.findall(r'"""(.*?)"""', open(src).read(), re.S)[0]

text = ("这一轮对话：\n"
        "用户：我们那台记忆服务的控制台端口到底是哪个？我记得是 8090 还是 8080\n"
        "助手：宿主上只绑了 127.0.0.1:8090，容器内是 8080。对外走 "
        "https://memory.huhufox.love:18443，由 nginx-proxy-manager 转发。\n")

body = {"model": model, "temperature": 0.2, "stream": False,
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": text}]}
req = urllib.request.Request(endpoint, data=json.dumps(body).encode(),
                             headers={"content-type": "application/json",
                                      "authorization": "Bearer " + os.environ["KEY"]})
t0 = time.time()
try:
    raw = urllib.request.urlopen(req, timeout=timeout).read().decode()
except urllib.error.HTTPError as e:
    print("✗ HTTP %d —— 端点不通或鉴权/模型名有问题" % e.code)
    print("  " + e.read().decode()[:400].replace("\n", " "))
    raise SystemExit(3)
except Exception as e:
    print("✗ 请求失败（%.1fs）：%r" % (time.time() - t0, e))
    raise SystemExit(3)

elapsed = time.time() - t0
try:
    data = json.loads(raw)
    content = (data["choices"][0]["message"].get("content") or "").strip()
except Exception:
    print("✗ 网关返回的不是 chat.completion：%s" % raw[:300])
    raise SystemExit(3)

usage = data.get("usage") or {}
think = (usage.get("completion_thinking_tokens")
         or usage.get("reasoning_tokens") or 0)

print("耗时 %.1fs（服务端上限 %ds）%s" % (
    elapsed, service_timeout // 1000,
    " ← 会超时，整轮回落成原文" if elapsed * 1000 > service_timeout else ""))
print("tokens: prompt=%s completion=%s thinking=%s" % (
    usage.get("prompt_tokens"), usage.get("completion_tokens"), think))
if think and think > 500:
    print("  ← 推理型模型，思考 token 占大头；摘要该用 flash 档")

# 服务端的解析是宽松的：认不出 JSON 就把整段当摘要。这里照抄那份宽容度。
stripped = content
if stripped.startswith("```"):
    stripped = re.sub(r"^```[a-z]*\s*", "", stripped)
    stripped = re.sub(r"\s*```$", "", stripped)
s, e = stripped.find("{"), stripped.rfind("}")
summary, keywords = None, []
if s >= 0 and e > s:
    try:
        parsed = json.loads(stripped[s:e + 1])
        summary = (parsed.get("summary") or "").strip()
        keywords = parsed.get("keywords") or []
    except Exception:
        summary = None

if summary:
    print("✓ JSON 合法，摘要 %d 字，keywords=%d 个" % (len(summary), len(keywords)))
    print("  " + summary)
    print("  keywords: " + ", ".join(str(k) for k in keywords))
elif stripped:
    print("△ 没吐 JSON，服务端会把整段当摘要（%d 字）：" % len(stripped))
    print("  " + stripped[:300])
else:
    print("✗ 空摘要 —— 服务端会判失败并回落原文")
    raise SystemExit(4)

# 摘要的价值全在硬信息上：这些值丢了，日后就检索不到了
missing = [k for k in ("8090", "8080", "127.0.0.1", "18443") if k not in (summary or "")]
if missing:
    print("⚠ 丢了硬信息：%s —— 摘要过度概括，该在提示词里再强调" % ", ".join(missing))
PY
