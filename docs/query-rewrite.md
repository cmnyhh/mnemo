# 查询改写（query expansion）实现方案

> **⚠️ 2026-10-22 更新：本方案已被端到端证伪，不要实施。**
> 全量 1986 题跑完，召回 **65.3% → 57.6%（−7.7pt）**，single-hop −10.4、adversarial −10.8；
> temporal 因为时间门控没扩展而 +0.3 不动，反证退化来自改写本身。
> 下文「零命中组 ≤64 命中率 33%→63%」是**偏采样的假信号**：样本是挑出来的零命中题，
> 且只统计单条证据的名次，看不到其他证据被挤掉。
> 保留本文档是为了记录三个可复用的教训：规则去壳无效、推理模型不能进检索路径、
> 英文提示词在中文工程库上会变质。**结论与数据见 `.workbuddy/memory/2026-10-22.md`。**

目标：把 LoCoMo 上「证据在该租户里，但一条都没召回到」的那批题捞回来。
当时基线 60.5%（radius=2 / max=14，1.3.4 + 配置覆盖）。

## 1. 结论先行

| 结论 | 依据 |
| --- | --- |
| **不要做「规则去壳」**（删疑问词/助动词/虚词） | 实测中位名次 113 → 120，命中组 ≤12 从 80% 掉到 75% |
| **要做「泛化词汇扩展」** | 零命中组 ≤64 命中率 33% → 63%（7B 模型）/ 73%（强模型） |
| **必须用非推理小模型** | 推理模型 p50 2620 ms，小模型 450 ms；检索路径 p50 只有 293 ms |
| **扩展只作用于向量路** | BM25 保留原查询，否则人名/日期的精确匹配被稀释 |
| **候选池要同步抬到 100** | 扩展把目标从 #170 带到 #40~#60，`dense-top-k=40` 仍然截断 |

## 2. 为什么是「扩展」而不是「改写」

问题不是句子罗嗦，是**词汇层错配**：问句用抽象属性（`destress`、`activities`），
记忆里写的是具体行为（`pottery class`、`pride parade`）。删词无法补上缺失的词，只有加词能。

实测（`loc2-conv-26`，419 条向量，30 道零命中题 + 20 道已命中题做对照）：

| 变体 | 零命中组中位名次 | ≤12 | ≤30 | ≤64 | 命中组 ≤12 |
| --- | ---: | ---: | ---: | ---: | ---: |
| v0 原问句 | 113 | 17% | 23% | 33% | 80% |
| v1 规则去壳 | 120 | 17% | 23% | 33% | 75% |
| v2 扩展（deepseek-v4.1-flash） | **25** | **30%** | **53%** | **73%** | **90%** |
| v2 扩展（Qwen2.5-7B + 安全提示） | **44** | **30%** | **40%** | **63%** | **85%** |

两个反直觉的细节：

1. **相似度分数在下降，名次在上升**（0.531 → 0.484）。扩展加的是泛化词，目标分数略降，
   但竞争者分数降得更多——这正是缺的「区分度」，也再次说明分数不能当判据。
2. **命中组也变好了**（80% → 85~90%）。扩展不是零和的：原本答对的题，证据名次同样上移。

延迟（公网 SiliconFlow / 自建端点，单次调用）：

| 模型 | p50 | 备注 |
| --- | ---: | --- |
| `deepseek-v4.1-flash` | 2620 ms | 推理 token 占了 completion 的 80%+，不可用 |
| `THUDM/GLM-4-9B-0414` | 714~957 ms | 可用但偏慢 |
| `Qwen/Qwen2.5-7B-Instruct` | **436~452 ms** | 当前首选 |

## 3. 架构：只改向量路的查询

```
query ──┬─ BM25          ← 原查询（人名、日期、专名要精确）
        ├─ embed(原)      ┐
        ├─ rewrite ─ embed(扩展) ┴→ dense union（按 max(score) 合并）→ RRF → rerank(原查询) → 注入
        └─ smartTrim 窗口 ← 原查询
```

**为什么 dense 用 union 而不是替换**：union 让候选集是原查询的**严格超集**，
最坏情况与今天完全一致，不存在「扩展把原本能召回的条目挤掉」这种回退。

> ⚠️ **这一条已被实测推翻（2026-10-22 追加）**，不要照做。见 §3.1：按原始分数取 max
> 的 union 会把扩展收益吃掉一半（≤64 命中 63% → 43%），而归一化后的 union 又换成了
> 另一批条目变差。真正的方案是**替换 + 时间门控**，理由见 §3.1。
两条向量查询各 ~30 ms，代价可以忽略。

**为什么精排仍用原查询**：扩展词由模型猜出来，用它去精排等于让猜测自我实现——
「像不像扩展出来的那串词」会取代「是不是用户问的那件事」。召回可以借猜测，判定不能。

**为什么 BM25 不扩展**：扩展加的全是高频泛化词（hobbies / activities / support），
BM25 没有 IDF 加权时它们会命中半个库，把真正靠专名命中的条目挤出前 40。

配套参数：`search.dense-top-k` 40 → **100**。扩展把目标带到 #40~#60，
池子还是 40 的话收益全部丢失（精排窗口 64 不变，不需要动）。

### 3.1 合并方式：union 被证伪，改用「替换 + 时间门控」

同一批样本（30 道零命中 + 20 道已命中），把三种合并方式都算了一遍：

| 变体 | 零命中中位名次 | ≤12 | ≤30 | ≤64 | 相对 v0 变差 |
| --- | ---: | ---: | ---: | ---: | ---: |
| v0 原查询 | 113 | 17% | 23% | 33% | — |
| **v2 替换** | **38** | **30%** | **43%** | **60%** | — |
| union（原始分数取 max） | 109 | 17% | 27% | 43% | **11 条** |
| union（每路 z-score 归一化后取 max） | 41 | 30% | 50% | 57% | **11 条** |
| **替换 + 时间门控** | 46 | 30% | 40% | **53%** | **6 条** |

结论：

- **原始分数 union 是错的**。两个查询的余弦分不在同一尺度（v0 中位 0.531、v2 0.484），
  取 max 等于让原查询主导排序，扩展收益被吃掉一半（63% → 43%）。这不是实现 bug，
  是「不同查询的相似度不可直接比较」这条基本事实。
- **归一化能修名次，但修不了稳定性**：中位名次回到 41，却仍是 11 条变差，且变差幅度更大
  （`#8→#26`、`#126→#186`）——每路各自的均值/方差本身随查询波动，归一化把这种波动放大了。
- **替换 + 门控是最稳的一档**：变差 6 条、变好 17 条；而且那 6 条里 4 条只是 `#8→#26/#38`
  这种量级——仍在精排窗口内。门控规则：查询含 `when / how many / how long / years ago /
  19xx / 20xx` 时不扩展（temporal 题扩展后实测 `#92→#135`，是唯一真正的大幅退化）。

**但名次提升 ≠ 答对**。向量名次 ≤64 只说明「能进精排窗口」，最终还要过精排（用原查询判相关性）
和 topK 截断——扩展召回的候选完全可能被精排否掉。所以是否真的更好，只能看端到端判分，
不能看名次表。这也是为什么下一步是跑一次「只改 query、不动服务代码」的全量端到端实验。

配套参数：`search.dense-top-k` 40 → **100**。扩展把目标带到 #40~#60，
池子还是 40 的话收益全部丢失（精排窗口 64 不变，不需要动）。

## 4. 代码改动清单

新增 `service/src/main/java/com/hs/memory/rewrite/`：

| 文件 | 内容 |
| --- | --- |
| `QueryRewriter.java` | 接口：`boolean available()` / `String expand(String query)` / `String describe()` |
| `NoopRewriter.java` | 返回空串，`available()=false` |
| `HttpQueryRewriter.java` | OpenAI 兼容 `/chat/completions`；复用 `net.Retry`（只重试 429/408/5xx），超时即放弃 |
| `RewritePrompt.java` | 系统提示 + 输出清洗（去引号、截断、剔除数字与问句回显） |

改动既有文件：

| 文件 | 改动 |
| --- | --- |
| `Settings.java` | record 加 `rewriteMode` / `rewriteBaseUrl` / `rewriteApiKey` / `rewriteModel` / `rewriteTimeoutMs` / `rewriteMaxTerms` |
| `SettingsConfig.java` | 绑定 `memory.search.rewrite.*` |
| `config/ConfigSpec.java` | 6 个键 + 范围校验（`mode` 用 ENUM `off|shadow|on`） |
| `config/RuntimeConfig.java` | `Snapshot` 加 6 个字段与 `rewriter()` 工厂；**record 加字段要同步改 `of()`** |
| `search/SearchService.java` | `doSearch` 并行发起改写 → 未命中门控时用扩展向量**替换** dense 查询向量（BM25 仍用原查询） |
| `api/SearchResponse.java` | 加 `expandedQuery`（可空）；构造函数调用点同步 |
| `application.yml` | `memory.search.rewrite.*` 基线，`mode` 默认 `off` |

关键片段（`SearchService`）：

```java
// 改写与「原查询 embed + BM25」并行：改写慢的时候这段时间本来也要花，不额外等一次
CompletableFuture<String> expansion = rewriter.available() && !"off".equals(settings.rewriteMode())
        ? CompletableFuture.supplyAsync(() -> rewriter.expand(query), rewritePool)
                .orTimeout(settings.rewriteTimeoutMs(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> "")      // 超时/429/空输出一律降级为空
        : CompletableFuture.completedFuture("");

float[] vector = embedOrNull(query);                  // 原查询向量：兜底路径要用
String expanded = expansion.getNow("");               // 取值点尽量靠后，但必须早于 dense 查询
if (!expanded.isBlank() && !temporalGate(query)) {
    float[] ev = embedOrNull(expanded);
    if (ev != null) {
        vector = ev;                                  // 替换，不是合并（§3.1）
        metrics.rewrite("applied");
    }
}
Pass pass = retrieve(tenantId, ..., bm25Query /* 原查询 */, vector);
```

**不要写 union**。§3.1 已经证明按分数取 max 会被原查询主导、把收益吃掉一半。
代价是「扩展后原来能召回的条目可能被挤掉」——用时间门控把这类查询挡在外面，
剩下的风险交给端到端判分来验收，而不是靠合并规则来兜。
不建议在这里用 RRF：RRF 只吃名次，而我们刚刚证明语料上名次是噪声，分数才是连续信号。

## 5. 提示词（可直接用）

系统提示（这是全方案里最需要写死的一段）：

```
Output 8 general topic words or short phrases that a chat about this question would contain:
keep every name and place from the question, then add CATEGORIES (e.g. hobbies, exercise,
art, socializing) for whatever is being asked about. Rules: never answer the question;
never name a specific activity, object, place, date or number that is not in the question;
never guess a fact. One line, comma separated, no quotes.
```

`temperature=0`，`max_tokens=120`。输出清洗：只取第一行、去掉引号、按逗号切分后
**丢弃含数字或年份的片段**（防止模型把「4 years ago」变成具体日期）。

这一步不能省。裸提示（「改写成检索词」）会让 7B 直接猜答案：
问 `destress` 时它输出 `reads books drinks tea walks outside`——猜错就等于把检索带向错误的语义区域。
加了上面这段约束后，输出变成 `destressing methods, hobbies, relaxation techniques, self-care, ...`。

## 6. 降级与门控

| 情形 | 行为 |
| --- | --- |
| 改写超时 / 429 / 空输出 | 用原查询，响应 `degradeReason` 追加 `改写超时（按原查询检索）` |
| 连续 5 次失败 | 熔断 60 s，期间不再调用（否则每次请求都要白等 700 ms） |
| 查询 ≤ 2 个实词 | 跳过改写（`who is she` 这类没有可扩展的语义） |
| 查询含显式日期/数字 | 跳过改写（`2023`、`4 years ago` 这类时间锚点会被扩展词稀释） |
| `mode=shadow` | 照常改写、回填 `expandedQuery`，但**不参与检索**——上线前用它观察真实查询的扩展质量 |

缓存：`LinkedHashMap` LRU（1024 条，TTL 10 min，key = tenant + 小写 query）。
同一轮对话里连续追问同一个主题很常见，命中率不会低。不引入 Caffeine，为一个 Map 加依赖不值。

## 7. 风险

1. **扩展即猜测**。模型给出的词是「可能相关」，不是「确实存在」。约束提示 + 只在向量路使用 +
   精排用原查询，三层都在压这个风险，但无法归零。`shadow` 模式先观察一轮再放开。
2. **题型分化**。temporal 类（`Where did Caroline move from 4 years ago?`）扩展后名次反而从
   #92 退到 #129。这是 union 方案要存在的主要理由；同时验收时要按题型看，不能只看总分。
3. **数据出网**。查询原文会发给改写端点。当前 embedding 已经在做同样的事，但如果将来
   embedding 迁到内网而改写留在公网，这条就成了唯一的出网点，需要一并迁。
4. **成本**。每题一次 LLM 调用。全量 1986 题判分不受影响，但 ask 阶段会多 ~2000 次短调用。

## 8. 验收

1. 单测：超时降级、空输出降级、数字片段被剔除、熔断生效、`shadow` 不改排名。
2. 全量：`ask → judge → McNemar` 对照 60.5%。判据不是「涨了」，而是 **p<0.05 且 single-hop 不退化 > 2pt**。
3. 分题型表：multi-hop / open-domain 应明显提升，temporal 允许持平，single-hop 是护栏。
4. 延迟：p50 目标 < 800 ms（当前 293 ms），p99 < 1.5 s。

## 9. 实施顺序

| 阶段 | 内容 | 产出 |
| --- | --- | --- |
| P1 | 接口 + 配置 + `mode=shadow` | 可在响应里看到扩展词，排序不变；先人工抽查 50 条扩展质量 |
| P2 | 打开 `mode=on`，`dense-top-k=100` | 全量 ask + judge + 配对检验 |
| P3 | 视 P2 结果决定是否试「BM25 也扩展」或「精排用扩展查询」 | 单变量对照，一次只改一个 |

P1 之前不做任何排序改动——`shadow` 的价值就在于把「扩展词质量」和「排序效果」两件事分开验。
