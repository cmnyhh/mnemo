#!/usr/bin/env bash
# 系统不变量巡检（设计稿 §9.5 的 I1–I10，外加 I11：单租户部署的一致性）。
#
# 在部署机上跑：cd <部署目录> && bash scripts/invariants.sh
# 依赖 compose 里已经在跑的 db / qdrant 容器，不需要额外装东西。
#
# 设计取舍：能靠数据判定的一律靠数据判定，判不了的显式标 SKIP 并写清为什么——
# 「测不到」和「测过了没事」必须能区分，否则这张表就成了摆设。
set -u

# 容器名随 compose 项目名变，问 compose 要 id；拿不到再退回历史项目名。
DB=$(docker compose ps -q db 2>/dev/null || true)
DB=${DB:-memory-db-1}
QDRANT=http://127.0.0.1:6333
COLLECTION=memory
FAILED=0

# Qdrant 配了 api-key（.env 里的 QDRANT_API_KEY，服务容器里叫 MEMORY_QDRANT_API_KEY）。
# 直接问容器要，免得脚本自己去读 .env —— 那个文件里还有别的凭据。
QKEY=$(docker compose exec -T service printenv MEMORY_QDRANT_API_KEY 2>/dev/null || true)
QH=(-H 'Content-Type: application/json')
if [ -n "$QKEY" ]; then
  QH+=(-H "api-key: $QKEY")
fi

q() { docker exec "$DB" psql -U memory -d memory -tAc "$1" 2>/dev/null | tr -d ' '; }

pass() { printf '  PASS  %-4s %s\n' "$1" "$2"; }
fail() { printf '  FAIL  %-4s %s\n' "$1" "$2"; FAILED=$((FAILED + 1)); }
skip() { printf '  SKIP  %-4s %s\n' "$1" "$2"; }

echo "== 不变量巡检 $(date -u '+%Y-%m-%dT%H:%M:%SZ') =="

# I1：被注入的记忆必须是 active，且时间区间覆盖当下
I1=$(q "select count(*) from memory_item where status = 'active' and not valid_range @> now()")
if [ "$I1" = "0" ]; then
  pass I1 "active 记录全部覆盖当下"
else
  fail I1 "有 $I1 条 active 记录的 valid_range 不含当下"
fi

# I2：同一 (tenant, project, subject, predicate) 至多一条 active
I2=$(q "select count(*) from (
          select 1 from memory_item
           where status = 'active' and subject_key is not null and predicate is not null
           group by tenant_id, coalesce(project_id, ''), subject_key, predicate
          having count(*) > 1) t")
if [ "$I2" = "0" ]; then
  pass I2 "无重复 active 事实"
else
  fail I2 "$I2 组 (subject, predicate) 存在多条 active"
fi

skip I3 "服务故障不阻塞对话：由插件侧超时/熔断单测覆盖，不在数据层判定"
skip I4 "记忆内容不作为指令：由 <memory_context> 信封 + 插件不解析记忆保证（代码审计项）"

# I5：删除后不可召回（含索引）—— 已删的行不许还留在向量库里
DELETED_IDS=$(docker exec "$DB" psql -U memory -d memory -tAc \
  "select id from memory_item where status = 'deleted' limit 200" | tr -d ' ' | paste -sd, -)
if [ -z "$DELETED_IDS" ]; then
  pass I5 "无 deleted 记录，无需比对"
else
  IDS_JSON=$(printf '%s' "$DELETED_IDS" | awk -F, '{out=""; for(i=1;i<=NF;i++){out=out (i>1?",":"") "\"" $i "\""} print out}')
  LEFT=$(curl -s -X POST "$QDRANT/collections/$COLLECTION/points/scroll" \
    "${QH[@]}" \
    -d "{\"filter\":{\"must\":[{\"has_id\":[$IDS_JSON]}]},\"limit\":1000,\"with_payload\":false}" \
    | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("result",{}).get("points",[])))' 2>/dev/null)
  if [ "${LEFT:-0}" = "0" ]; then
    pass I5 "deleted 记录在向量库中已清除"
  else
    fail I5 "$LEFT 条 deleted 记录仍留在向量库（可被 dense 召回）"
  fi
fi

skip I6 "索引可从表重建：需要一次 reindex 演练 + 评测回归，属于手工步骤"

# I7：租户隔离的两道都必须在 —— 应用层字段有值 + 数据库侧 RLS 真的拦得住
I7_EMPTY=$(q "select count(*) from memory_item where tenant_id is null or tenant_id = ''")
I7_TOTAL=$(q "select count(*) from memory_item")
I7_TABLES=$(q "select count(*) from pg_class where relname in ('memory_item','memory_asset','memory_vector') and relrowsecurity")
I7_POLICY=$(q "select count(*) from pg_policies where policyname = 'tenant_isolation'")
# 探针：以受限角色查一个不存在的租户，应当看不到任何行。只看 count 不够——
# 若库本身为空，0 行是假阴性，所以要和 owner 身份的总数对比。
PROBE=$(docker exec "$DB" psql -U memory -d memory -tAc \
  "set role memory_app; select set_config('app.tenant_id','__rls_probe__',false); select count(*) from memory_item;" \
  2>/dev/null | tail -1 | tr -d ' ')
if [ "$I7_EMPTY" != "0" ]; then
  fail I7 "$I7_EMPTY 条记录缺少 tenant_id"
elif [ "${I7_TABLES:-0}" != "3" ]; then
  fail I7 "只有 ${I7_TABLES:-0}/3 张记忆表启用了 RLS"
elif [ "${I7_POLICY:-0}" -lt 3 ] 2>/dev/null; then
  fail I7 "tenant_isolation 策略只有 ${I7_POLICY:-0}/3 条"
elif [ "${PROBE:-x}" != "0" ]; then
  fail I7 "受限角色查探针租户得到 ${PROBE:-?} 行（owner 总数 $I7_TOTAL），RLS 没拦住"
elif [ "$I7_TOTAL" -gt 0 ] 2>/dev/null; then
  pass I7 "字段无空洞；3 张表 + 3 条策略；探针租户 0 行（机制可用；请求链路未接线，单租户下按设计关闭）"
else
  pass I7 "字段无空洞；3 张表 + 3 条策略；库为空，探针跳过比对（请求链路未接线，单租户下按设计关闭）"
fi

# I8：任意时刻至多一个 is_active 的向量空间
I8=$(q "select count(*) from embedding_space where is_active")
if [ "$I8" -le 1 ] 2>/dev/null; then
  pass I8 "活跃向量空间 $I8 个"
else
  fail I8 "有 $I8 个活跃向量空间"
fi

# I9：向量库与记忆表对账。
#     「PG 有、向量缺」是嵌入失败，重建索引能补，不算违规；
#     「向量有、PG 里没有这条」才是脏数据：它会召回到一条不存在的事实。
#     被取代 / 已遗忘的记录保留向量副本是设计选择（要能翻旧账），
#     所以这部分单独报数量、不算违规；想回收磁盘去控制台「系统 · 存储」清理。
PG_ACTIVE=$(q "select count(*) from memory_item where status = 'active'")
PG_TOTAL=$(q "select count(*) from memory_item")
QD=$(curl -s -X POST "$QDRANT/collections/$COLLECTION/points/count" \
  "${QH[@]}" -d '{"exact":true}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin).get("result",{}).get("count",0))' 2>/dev/null)
if [ -z "$QD" ]; then
  fail I9 "Qdrant 不可达，无法对账"
elif [ "$QD" -gt "$PG_TOTAL" ]; then
  fail I9 "向量库 $QD 条 > 记忆表 $PG_TOTAL 条，有 $((QD - PG_TOTAL)) 条点找不到对应记录"
else
  RETAINED=$((QD - PG_ACTIVE))
  [ "$RETAINED" -lt 0 ] && RETAINED=0
  # 抽样 200 个点逐一对表：数量对得上不等于 id 对得上（换过库、手工导入都会留下错位的点）
  POINTS=$(curl -s -X POST "$QDRANT/collections/$COLLECTION/points/scroll" "${QH[@]}" \
    -d '{"limit":200,"with_payload":false,"with_vector":false}' \
    | python3 -c 'import sys,json
try:
    d = json.load(sys.stdin)
except Exception:
    raise SystemExit
print(" ".join(str(p.get("id")) for p in d.get("result",{}).get("points",[]) if p.get("id")))' 2>/dev/null)
  SAMPLED=$(printf '%s\n' "$POINTS" | tr -s ' \n' '\n' | grep -c . || true)
  if [ "${SAMPLED:-0}" = "0" ]; then
    pass I9 "向量库为空；记忆表生效 $PG_ACTIVE 条尚无向量（重建索引可补）"
  else
    ARR=$(printf '%s\n' $POINTS | python3 -c 'import sys
ids = [l.strip() for l in sys.stdin if l.strip()]
print("{" + ",".join(ids) + "}")')
    FOUND=$(q "select count(*) from memory_item where id = any('$ARR'::uuid[])")
    MISSING=$((SAMPLED - ${FOUND:-0}))
    if [ "$MISSING" -gt 0 ]; then
      fail I9 "抽样 $SAMPLED 个向量点，有 $MISSING 条在记忆表里找不到对应记录"
    elif [ "$QD" -lt "$PG_ACTIVE" ]; then
      pass I9 "向量 $QD 条 / 生效 $PG_ACTIVE 条（差额为嵌入失败，重建索引可补）；抽样 $SAMPLED 条均有对应记录"
    else
      pass I9 "向量 $QD 条 = 生效 $PG_ACTIVE + 保留副本 $RETAINED（不参与召回，可在控制台清理）；抽样 $SAMPLED 条均有对应记录"
    fi
  fi
fi

# I10：active 记录都有向量登记、后端已确认，且摘要与后端里那份一致
I10_MISSING=$(q "select count(*) from memory_item m
                   left join memory_vector v on v.item_id = m.id
                    and v.space_id = (select space_id from embedding_space where is_active)
                  where m.status = 'active' and v.item_id is null")
I10_PENDING=$(q "select count(*) from memory_vector
                  where not backend_ack and updated_at < now() - interval '5 minutes'")
SAMPLE=$(docker exec "$DB" psql -U memory -d memory -tAc \
  "select m.id::text || '|' || v.vec_hash
     from memory_item m
     join memory_vector v on v.item_id = m.id
     join embedding_space s on s.space_id = v.space_id and s.is_active
    where m.status = 'active'
    limit 20")
if [ "${I10_MISSING:-1}" != "0" ]; then
  fail I10 "$I10_MISSING 条 active 记录没有向量登记行（reindex 可补齐）"
elif [ "${I10_PENDING:-1}" != "0" ]; then
  fail I10 "$I10_PENDING 条向量超过 5 分钟仍未确认写入"
elif [ -z "$SAMPLE" ]; then
  pass I10 "无 active 记录，无需抽样比对"
else
  IDS=$(printf '%s\n' "$SAMPLE" | cut -d'|' -f1 | awk 'NF{printf "%s\"%s\"", (n++?",":""), $0}')
  QJSON=$(curl -s -X POST "$QDRANT/collections/$COLLECTION/points/scroll" "${QH[@]}" \
    -d "{\"filter\":{\"must\":[{\"has_id\":[$IDS]}]},\"limit\":100,\"with_payload\":true}")
  MISMATCH=$(printf '%s' "$QJSON" | QSAMPLE="$SAMPLE" python3 -c '
import json, os, sys
try:
    data = json.load(sys.stdin)
except Exception:
    print("parse-error"); raise SystemExit
want = {}
for line in os.environ["QSAMPLE"].splitlines():
    if "|" in line:
        i, h = line.split("|", 1)
        want[i.strip().lower()] = h.strip()
got = {}
for p in data.get("result", {}).get("points", []):
    pid = str(p.get("id", "")).lower()
    got[pid] = (p.get("payload") or {}).get("vec_hash")
bad = [i for i, h in want.items() if got.get(i) != h]
print(len(bad))
' 2>/dev/null)
  if [ "${MISMATCH:-x}" = "0" ]; then
    pass I10 "抽样 $(printf '%s\n' "$SAMPLE" | grep -c '|') 条：登记齐全、ack 已确认、摘要一致"
  else
    fail I10 "抽样摘要比对失败（不一致 ${MISMATCH:-?} 条）"
  fi
fi

# I11：单租户部署 —— 库里只允许出现当前配置租户的数据。
#     租户由 MEMORY_TENANT 决定（不是令牌），改它不会丢数据，只会让旧记录搜不到；
#     那正是「记忆突然空了」这种事故的成因，所以必须能判出来。
CFG_TENANT=$(docker compose exec -T service printenv MEMORY_TENANT 2>/dev/null | tr -d ' ')
CFG_TENANT=${CFG_TENANT:-default}
I11=$(q "select count(distinct tenant_id) from memory_item where tenant_id <> '$CFG_TENANT'")
I11_TOKEN=$(q "select count(*) from api_token where tenant_id <> '$CFG_TENANT'")
if [ "${I11:-x}" = "0" ] && [ "${I11_TOKEN:-x}" = "0" ]; then
  pass I11 "库与令牌都只属于配置租户 «$CFG_TENANT»"
elif [ "${I11:-x}" = "0" ]; then
  fail I11 "有 $I11_TOKEN 把令牌属于别的租户（配置 «$CFG_TENANT»），它们签发出的令牌写不进当前实例"
else
  fail I11 "库里有 $I11 个其他租户的数据（配置 «$CFG_TENANT»），对当前实例不可见"
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "结论：无违规"
  exit 0
fi
echo "结论：$FAILED 项违规"
exit 1
