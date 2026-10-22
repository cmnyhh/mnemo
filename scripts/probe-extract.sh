#!/usr/bin/env bash
# 验证抽取模型到底通不通。
#
# 服务的抽取路径只有一个失败表现：崩回 episode-fallback（按段硬切）。原因可能是
# 网络不通 / key 错 / 模型名不存在 / 输出不是合法 JSON / 太慢超过 extract.timeout-ms。
# 这五种情况在日志里只差一行 WARN，看不清是哪一种。本脚本把同一份请求原样复刻一遍，
# 直接报出「耗时 + usage + admit + items 数 + JSON 合法性」，一次定位。
#
# 用法（在服务器仓库根目录）：
#   bash scripts/probe-extract.sh                 # 用 app_config 里现有的配置
#   bash scripts/probe-extract.sh hy3             # 临时试别的模型名，不改库
#   TIMEOUT=180 bash scripts/probe-extract.sh     # 慢模型放宽等待
#
# ★ 耗时必须 < extract.timeout-ms（默认 30000）。推理型模型（reasoning_tokens 上千）
#   在抽取这种结构化小任务上要 40-60s，一定超时，别拿它当抽取器。
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
MAX_ITEMS=$(read_cfg extract.max-items)
MIN_CONF=$(read_cfg extract.min-confidence)

MODEL=${MODEL_OVERRIDE:-$MODEL}
TIMEOUT_MS=${TIMEOUT_MS:-30000}
MAX_ITEMS=${MAX_ITEMS:-8}

echo "配置：provider=${PROVIDER:-<none>} model=${MODEL:-<none>} base=${BASE_URL:-<none>}"
echo "      timeout-ms=${TIMEOUT_MS} max-items=${MAX_ITEMS} min-confidence=${MIN_CONF:-0.35}"

if [ "${PROVIDER:-none}" != "http" ]; then
  echo
  echo "✗ provider=${PROVIDER:-none}：抽取模型未启用，写入只会按段硬切。"
  echo "  在控制台「配置 → 抽取模型」里把来源改成 http 并填 base-url / key / model。"
  exit 2
fi

# ★ 服务的 endpoint 是 baseUrl + "/chat/completions"，此处必须一致，否则测的不是同一条路。
ENDPOINT="${BASE_URL%/}/chat/completions"
echo "      endpoint=${ENDPOINT}"
echo

export KEY="$API_KEY" ENDPOINT MODEL TIMEOUT MAX_ITEMS TIMEOUT_MS SRC_DIR
python3 - "$@" <<'PY'
import json, os, re, sys, time, urllib.error, urllib.request

endpoint = os.environ["ENDPOINT"]
model = os.environ["MODEL"]
timeout = float(os.environ["TIMEOUT"])
service_timeout = int(os.environ["TIMEOUT_MS"])
max_items = os.environ["MAX_ITEMS"]

# 提示词直接从源码里取，保证测的就是线上那一段，不会跟代码漂移。
src = os.path.join(os.environ["SRC_DIR"],
                   "service/src/main/java/com/hs/memory/extract/ExtractPrompt.java")
system = re.findall(r'"""(.*?)"""', open(src).read(), re.S)[0].replace("%d", max_items)

text = ("对话内容：\n"
        "user: 我们这台记忆服务的精排模型是 Qwen3-Reranker-0.6B，跑在 SiliconFlow 上，超时设的 8 秒。\n"
        "user: 另外部署时注意 compose 项目名必须保持 memory，改了会挂空卷。\n")

body = {"model": model, "temperature": 0.1, "stream": False,
        "messages": [{"role": "system", "content": system},
                     {"role": "user", "content": text}]}
req = urllib.request.Request(endpoint, data=json.dumps(body).encode(),
                             headers={"content-type": "application/json",
                                      "authorization": "Bearer " + os.environ["KEY"]})
t0 = time.time()
try:
    resp = urllib.request.urlopen(req, timeout=timeout)
    raw = resp.read().decode()
except urllib.error.HTTPError as e:
    print("✗ HTTP %d —— 端点不通或鉴权/模型名有问题" % e.code)
    print("  " + e.read().decode()[:400].replace("\n", " "))
    sys.exit(3)
except Exception as e:
    print("✗ 请求失败（%.1fs）：%r" % (time.time() - t0, e))
    sys.exit(3)

elapsed = time.time() - t0
try:
    data = json.loads(raw)
    msg = data["choices"][0]["message"]
except Exception as e:
    print("✗ 网关返回的不是 chat.completion（%r）：%s" % (e, raw[:300]))
    sys.exit(3)

usage = data.get("usage") or {}
think = (usage.get("completion_thinking_tokens")
         or usage.get("reasoning_tokens") or 0)
content = (msg.get("content") or "").strip()

# 服务端自己会剥 ``` 围栏并做括号配对，这里照抄那份宽容度，只判最终能不能解出对象。
stripped = content
if stripped.startswith("```"):
    stripped = re.sub(r"^```[a-z]*\s*", "", stripped)
    stripped = re.sub(r"\s*```$", "", stripped)
s, e = stripped.find("{"), stripped.rfind("}")
if s >= 0 and e > s:
    stripped = stripped[s:e + 1]
try:
    parsed = json.loads(stripped)
    shaped = "admit" in parsed and isinstance(parsed.get("items"), list)
except Exception as err:
    parsed, shaped = None, False
    print("✗ 输出不是合法 JSON（%r）：%s" % (err, content[:200]))

print("耗时 %.1fs（服务端上限 %ds）%s" % (
    elapsed, service_timeout // 1000,
    " ← 会超时回落硬切" if elapsed * 1000 > service_timeout else ""))
print("tokens: prompt=%s completion=%s thinking=%s" % (
    usage.get("prompt_tokens"), usage.get("completion_tokens"), think))
if think and think > 500:
    print("  ← 这是推理型模型，思考 token 占大头；抽取该用 flash 档")

if not shaped:
    print("✗ 输出形状不对：缺 admit 或 items 不是数组")
    print("  " + content[:300].replace("\n", " "))
    sys.exit(4)

items = parsed.get("items") or []
print("✓ JSON 合法，admit=%s，items=%d 条" % (parsed.get("admit"), len(items)))
print("  reason: %s" % str(parsed.get("reason"))[:160])
for it in items[:3]:
    print("  - [%s] %s" % (it.get("kind"), str(it.get("narrative"))[:90]))
PY
