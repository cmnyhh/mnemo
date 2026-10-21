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
| `subjectKey` + `predicate` + `objectValue` | 同一条事实的新旧值靠这三个定位；谓词要可区分（「监听端口」而不是「使用」） |
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

## 控制台 API

`/admin/api/*` 是控制台自用的接口（登录、配置、记录浏览、令牌管理、重建索引），走会话 cookie + `X-CSRF-Token`，与上面的 Bearer 令牌互不相通。它面向界面，不承诺兼容性，接第三方请只依赖 `/v1/memory/*`。

其中两个是观测用的：`GET /admin/api/metrics`（延迟分布、分阶段耗时、结果分布）、`GET /admin/api/audit?limit=&action=`（审计日志）。

`GET /internal/metrics` 是给 Prometheus 抓的文本端点，**走 Bearer 令牌**（不是会话），详见 [部署与运维 · 观测](operations.md#观测)。
