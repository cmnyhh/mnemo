#!/usr/bin/env bash
# 验证「桥接信封」能不能被正确拆开并写入记忆。
#
# 为什么单独有这条探针：QQ/IM 桥接类宿主不把用户原话直接投给 agent，而是拼一大段运行态说明
# （当前时间、角色卡、表情包策略…）再把真实消息塞在末尾的 JSON 快照里。这类轮次以前有两个下场：
#   ① 整段像提示词模板/伪 XML，被形状过滤整轮丢掉；
#   ② 真实内容在几千字之后，按 400 字截断时早被切掉。
# 1.4.1 起先剥信封再判定、再截断。跑这个脚本能确认那两句群聊到底有没有进库。
#
# 用法：bash scripts/probe-bridge.sh [服务地址] [令牌]
#   令牌缺省从 /etc/dsh-web.env（本机）或环境变量 MNEMO_SERVICE_TOKEN 取。
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

SESSION="probe-bridge-$(date +%H%M%S)"
read -r -d '' BODY <<'JSON'
{
  "userId": "probe",
  "projectId": "probe",
  "sessionId": "__SESSION__",
  "turn": {
    "sessionId": "__SESSION__",
    "index": 1,
    "trigger": "none",
    "messages": [
      {
        "role": "user",
        "content": "【当前时间】2026/10/22 15:23:17（Asia/Shanghai）\n\n【当前角色】白绫（完整角色卡请调用 qq_get_prompt 查看）\n\n【会话令牌】152244a8702e4a85b38497856c9494c4（调用二代状态工具时请在参数中带上此令牌）\n\n【反AI味】你不是客服：不用有求必应，可以装傻、已读乱回、拒绝推荐。\n\n【表情包策略：像真人一样用，不刷屏】\n- 合适时机：被戳中笑点/槽点、接梗、怼人、赞同、自嘲。\n- 频率：普通闲聊不用每条都配；大约每 3~5 轮来一张就够。\n\n【此刻状态】未读 2 条；最近一条来自 美丽猫娘：[图片]\n\n【唤醒】group:705590372\n原因：anyMessage\n\n【本次消息快照】\n{\"messages\":[{\"seq\":474,\"sender\":\"大肥鱼，，，\",\"text\":\"没用的鱼才活得久，会用的都被炖了\"},{\"seq\":475,\"sender\":\"美丽猫娘\",\"text\":\"[图片]\"}],\"recent\":[{\"seq\":472,\"sender\":\"🧄\",\"text\":\"你这没用的鱼\"}],\"unreadCount\":2}"
      },
      {
        "role": "assistant",
        "content": "我这儿也收不了。（顿了顿）省了回礼的人情。"
      }
    ]
  }
}
JSON
BODY="${BODY//__SESSION__/$SESSION}"

echo "== 写入（信封 + 群聊 JSON 快照）=="
RESP=$(curl -s -X POST "$BASE/v1/memory/ingest" \
  -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  --data-binary "$BODY")
echo "$RESP"
echo

EXTRACTION=$(printf '%s' "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("extraction",""))' 2>/dev/null || true)
case "$EXTRACTION" in
  log-summary|llm-extract|summary-fallback|log-skipped|admission-rejected)
    echo "extraction=$EXTRACTION" ;;
  "")
    echo "服务端没有回 extraction 字段，看上面的原始响应" ;;
  *)
    echo "extraction=$EXTRACTION" ;;
esac

echo
echo "== 检索「群里刚才在说鱼的事」=="
curl -s -X POST "$BASE/v1/memory/search" \
  -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d "{\"query\":\"群里刚才在说鱼的事\",\"projectId\":\"probe\"}" \
  | python3 -c '
import sys, json
d = json.load(sys.stdin)
print("route=%s count=%s ms=%s" % (d.get("route"), d.get("count"), d.get("elapsedMs")))
for h in d.get("hits", [])[:3]:
    print("%.3f %-9s %s" % (h.get("score", 0), h.get("kind"), h.get("narrative", "")[:120]))
'

echo
echo "（清理：update memory_item set status='"'"'deleted'"'"' where project_id='"'"'probe'"'"';）"

# 私聊：同一套模板，只是【唤醒】是 private。关键差异是来信人叫「私聊」，
# 而且很多轮只有几个字（试键盘），这类不该进库。
PRIV_SESSION="${SESSION}-priv"
read -r -d '' PRIV <<'JSON'
{
  "userId": "probe",
  "projectId": "probe",
  "sessionId": "__SESSION__",
  "turn": {
    "sessionId": "__SESSION__",
    "index": 1,
    "trigger": "none",
    "messages": [
      {
        "role": "user",
        "content": "【当前时间】2026/10/22 15:27:25（Asia/Shanghai）\n\n【当前角色】白绫（完整角色卡请调用 qq_get_prompt 查看）\n\n【会话令牌】be15bf868e768e4fc7b246209476673d\n\n【唤醒】private:1704040087\n原因：private\n【行动前】先判断：对方说完了吗？\n\n【本次消息快照】\n{\"messages\":[{\"seq\":2,\"sender\":\"私聊\",\"userId\":\"1704040087\",\"text\":\"fsasf\",\"isOwner\":true}],\"recent\":[{\"sender\":\"我\",\"text\":\"阁下这是在试键盘，还是在试我。\",\"isSelf\":true}],\"unreadCount\":1}"
      },
      {
        "role": "assistant",
        "content": "阁下这是在试键盘，还是在试我。"
      }
    ]
  }
}
JSON
PRIV="${PRIV//__SESSION__/$PRIV_SESSION}"

echo
echo "== 私聊里只有「fsasf」：应当被判定为没有实质内容 =="
curl -s -X POST "$BASE/v1/memory/ingest" \
  -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  --data-binary "$PRIV" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("extraction=%s accepted=%s note=%s"%(d.get("extraction"),d.get("accepted"),d.get("note")))'

