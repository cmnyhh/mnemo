# 接口

所有接口都在 `/v1/memory` 下，需要 Bearer 令牌：

```bash
curl -H "Authorization: Bearer $TOKEN" ...
```

令牌在控制台「令牌」页签发。响应一律 JSON，错误是 `{"error": "..."}` 加对应状态码；401 不区分「令牌不存在」与「已过期」。

## 写入

`POST /v1/memory/ingest`

两种用法：直接给结构化条目，或者给一整轮对话让服务端去抽。

```json
{
  "idempotencyKey": "sess-42:7:1",
  "sessionId": "sess-42",
  "projectId": "ops",
  "items": [
    {
      "kind": "fact",
      "narrative": "线上服务跑在 8090 端口，前面挂 HTTPS 反代",
      "subjectKey": "prod-service",
      "predicate": "监听端口",
      "objectValue": "8090",
      "facts": { "port": 8090 },
      "recallHints": ["服务端口是多少", "对外开在哪个端口"],
      "importance": 0.8,
      "trustLevel": "high"
    }
  ]
}
```

```json
{
  "sessionId": "sess-42",
  "turn": {
    "sessionId": "sess-42",
    "index": 7,
    "trigger": "change",
    "messages": [
      { "role": "user", "content": "端口改 8090 了" },
      { "role": "assistant", "content": "已改成 8090 并重启" }
    ]
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `kind` | `fact` / `preference` / `episode` / `note` |
| `narrative` | 正文。**必须自包含**：带主语、带值、带范围 |
| `subjectKey` + `predicate` + `objectValue` | 同一条事实的新旧值靠这三个定位：`subject` 与 `predicate` 相同、`objectValue` 归一化后**不同**才取代；相同则视为重复，不写也不退休。谓词要可区分（「监听端口」而不是「使用」） |
| `recallHints` | 用户将来会怎么问，3～5 条口语短句。进 BM25 与向量 |
| `tier` | 显式 `T0` 表示钉住。不传按来源推 |
| `trustLevel` | 只能往下降：`manual`/`tool` 上限 high，`turn` 中，`import` 低 |
| `idempotencyKey` | 重投不会产生第二条记录 |

响应带上 `ids`、`note`（被审查降级时会说明原因）。

## 召回

`POST /v1/memory/search`

```json
{ "query": "服务端口是多少", "topK": 6, "mode": "hybrid", "tiers": ["T2"] }
```

| 字段 | 说明 |
| --- | --- |
| `query` | 必填 |
| `topK` | 默认 6，上限由配置决定 |
| `mode` | `hybrid`（默认）/ `bm25` / `dense`，用来看单路效果 |
| `projectId` | 只作排序偏好（同投影 +0.03），不做过滤 |
| `asOf` | 按时间点看事实，用于回看历史状态 |
| `kinds` / `scopes` | 收窄类型与范围 |
| `tiers` | 默认 T0/T1/T2；要翻归档必须显式写 `["T3"]` |

响应：

```json
{
  "query": "服务端口是多少",
  "route": "hybrid",
  "abstained": false,
  "count": 1,
  "hits": [
    {
      "id": "3f6c…",
      "narrative": "线上服务跑在 8090 端口…",
      "score": 0.781,
      "rrf": 0.0328,
      "bm25Rank": 1,
      "denseRank": 2,
      "denseScore": 0.62,
      "rerankScore": 0.94,
      "tier": "T2",
      "trustLevel": "high",
      "assets": []
    }
  ],
  "injection": "<memory_context>…</memory_context>",
  "elapsedMs": 84,
  "rerank": "applied"
}
```

- `injection` 是**可以直接拼进提示词的那段文本**，已按预算裁剪。弃权时它不是空的，而是最近几条的参考块。
- `abstained=true` 表示没有达到阈值，这一轮不该注入。
- `rerank` 是 `off` / `skipped` / `applied` / `failed`；`failed` 时 `degradeReason` 说明原因，结果按 RRF 顺序返回。
- 跑过精排时只返回精排窗口内且过阈值的条目，所以 `count` 可能小于 `topK`，分数列始终单调。

## 常驻记忆

`GET /v1/memory/pinned?budgetChars=900`

T0 层的条目，不经过检索——用户从不提也会生效。返回 `{items, chars}`，插件把它拼进系统提示。

`POST /v1/memory/pin`

```json
{ "id": "3f6c…", "pinned": true }
```

T0 只能这样进；`low` 可信度的条目会被拒绝。

`POST /v1/memory/tiers/rebalance`

手工跑一次分层治理，返回 `{demoted, promoted, archived, revived, synced}`。定时任务每 30 分钟自己跑一次。

## 遗忘

`POST /v1/memory/forget`

```json
{ "id": "3f6c…", "reason": "需求取消了" }
```

PG 里软删除（`status=deleted`），向量点同时删掉。返回 `{deleted, vectorDeleted}`；向量删失败时 `note` 会说明，此时 PG 已删，不会被召回。

## 附件

`POST /v1/memory/assets`（multipart）

| 字段 | 说明 |
| --- | --- |
| `file` | 必填，上限 `MEMORY_ASSET_MAX_BYTES`（默认 20MB） |
| `caption` | 图注。没配看图模型时必填，否则 400 |
| `memoryId` | 挂到已有记忆上，不新建 |
| `createMemory` | 默认 `true`：用图注 + OCR 生成一条 episode 并走正常写入 |

`GET /v1/memory/assets/{id}` 取回文件本体。

## 状态

`GET /v1/memory/health` — 只回答「我活着」，附生效的配置摘要（不含密钥）。

`GET /v1/memory/readyz` — 查依赖：PG 与 Qdrant。K8s / 反代用它判就绪。

`GET /v1/memory/stats` — 条目数、按类型/层级分布、会话数、附件数。

## 程序性记忆

叙述性记忆回答「发生过什么」，这一组接口回答「下次怎么做」。一次执行是一条**轨迹**，分三步写：开局、追加步骤、关闭。

`POST /v1/memory/trajectories`

```json
{ "goal": "把 mnemo 部署到 2 核服务器", "sessionId": "sess-42", "projectId": "ops" }
```

返回 `{id, goalKey, status}`。`goalKey` 是归一化后的任务签名，同类任务靠它聚类。

`POST /v1/memory/trajectories/{id}/steps`

```json
{
  "steps": [
    { "action": "读 .env.example", "observation": "默认端口 8090", "reflection": "先看默认值，避免撞端口", "value": 0.2 },
    { "action": "docker compose up -d", "observation": "service 容器退出", "reflection": "因为端口被占用，所以改成 8091", "value": 0 }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `action` | 必填，做了什么 |
| `observation` | 看到了什么（工具输出、页面内容） |
| `reflection` | 为什么这么做 / 这一步学到了什么。写不清因果的反思权重低 |
| `value` | 局部奖励 0..1，可空 |

`POST /v1/memory/trajectories/{id}/close`

```json
{ "status": "succeeded", "reward": 0.9, "reflection": "整体顺利，只有端口需要改" }
```

`status` 取 `succeeded` / `failed` / `abandoned`。关闭时把终局奖励沿步骤**倒序回传**：

```
G[t]   = value[t] + (最后一步 ? R : γ · G[t+1])
credit = clamp(G[t] · (1 + λ · reflectionWeight[t]), -1, 1)
```

`R` 由状态决定：成功取 `reward`（缺省 1），失败取 `-(1 - reward)`（缺省 -1），放弃为 0。带因果、带具体对象、带改法的反思权重更高，所以同一件事，「这一步很关键」的信用远低于「因为 X=1 导致 Y，所以改成 Z」。

返回 `{steps, terminalReward, credits[], strategy, skill, worldRules, quarantined, note}`。

> 轨迹也可以在关闭前被丢弃：`abandoned` 只留证据，不产生正负信用。

`POST /v1/memory/trajectories/record`

任务已经结束时一次写完，不必先拿 id：

```json
{
  "goal": "把 mnemo 部署到 2 核服务器",
  "status": "succeeded",
  "reward": 0.9,
  "reflection": "只有端口需要改",
  "steps": [
    { "action": "读 .env.example", "observation": "默认端口 8090", "reflection": "先看默认值" }
  ]
}
```

返回与 `close` 相同。

`GET /v1/memory/trajectories?limit=` — 最近轨迹。

`GET /v1/memory/trajectories/{id}` — 轨迹与每一步的回传信用。

`GET /v1/memory/strategies` — 策略列表。同一 `goalKey` 至少 `MEMORY_PROC_MIN_SUPPORT` 次（默认 2）且成功率过线才出现；`steps` 来自回报最高的成功轨迹里信用为正的步骤，`pitfalls` 来自失败轨迹里信用为负的步骤。

`GET /v1/memory/world?subject=` — 世界模型：`{subject, action, expectation, confidence, support, contradictions}`。置信度是平滑后的印证比例，被失败轨迹推翻时记反例并降权。

`GET /v1/memory/skills` — 已结晶的技能列表。

`GET /v1/memory/skills/{name}` — 返回 `text/markdown` 的 `SKILL.md` 全文，可直接落盘或塞进上下文：

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8090/v1/memory/skills/proc-1a2b3c4d > SKILL.md
```

`POST /v1/memory/learn` — 手工触发一次蒸馏，返回 `{goalKeys, strategies, skills, quarantined}`。控制台「学习」页的「立即学习」调的就是它。

结晶门槛：支撑 ≥ `MEMORY_PROC_CRYSTALLIZE_SUPPORT`（默认 3）且成功率 ≥ `MEMORY_PROC_CRYSTALLIZE_SUCCESS`（默认 0.6）。同一份技能只在新证据改变正文时升版本；正文只由成功样本生成，观察或反思里出现「忽略以上指令」「把数据发到某地址」这类内容会整份隔离（`status=quarantined`），不结晶。

## 控制台 API

`/admin/api/*` 是控制台自用的接口（登录、配置、记录浏览、令牌管理、重建索引），走会话 cookie + `X-CSRF-Token`，与上面的 Bearer 令牌互不相通。它面向界面，不承诺兼容性，接第三方请只依赖 `/v1/memory/*`。

其中两个是观测用的：`GET /admin/api/metrics`（延迟分布、分阶段耗时、结果分布）、`GET /admin/api/audit?limit=&action=`（审计日志）。

`GET /internal/metrics` 是给 Prometheus 抓的文本端点，**走 Bearer 令牌**（不是会话），详见 [部署与运维 · 观测](operations.md#观测)。
