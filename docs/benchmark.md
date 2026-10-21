# LoCoMo 评测

[LoCoMo](https://github.com/snap-research/locomo) 是长对话记忆的标准数据集：10 段跨月对话、
272 个会话、5882 轮、1986 道题，分五类——multi-hop、temporal、open-domain、single-hop、
adversarial。MemOS、Zep、Mem0 都在这份数据上报告过数字，所以拿它做横向对比是同一把尺子。

## 协议

```
每段对话 → 每个会话（含日期、逐轮 dia_id）作为一条 episode 入库
每道题   → /v1/memory/search（topK=12，hybrid）
         → 用返回的 injection 直接作答（不额外拼提示词，测的就是产品真实注入的内容）
         → 与标准答案比对
```

三个指标分开报，因为它们回答的是不同问题：

| 指标 | 含义 | 需不需要 LLM |
| --- | --- | --- |
| 证据召回（任意） | 至少一条标注证据被召回 | 不需要，客观 |
| 证据召回（全部） | 标注的所有证据都被召回 | 不需要，客观 |
| 准确率 | 作答与标准答案一致 | 需要判分模型 |

分开报的意义：召回率是检索能力的硬指标，不受判分模型强弱影响；准确率会掺进生成模型的水平。
换了判分模型只需重跑 `judge`，不必重灌记忆。

adversarial 类（446 题）的「标准答案」是一个看起来很像的错答案，这类题考的是**会不会被诱导**，
所以拒答（`NOT MENTIONED`）记对，顺着答错记错。这一类和 mnemo 的弃权机制直接对应。

## 跑法

```bash
python3 bench/locomo/run.py ingest --base http://127.0.0.1:8090 --token $TOKEN
python3 bench/locomo/run.py ask    --base http://127.0.0.1:8090 --token $TOKEN
python3 bench/locomo/run.py judge  --llm-base $LLM_BASE --llm-key $LLM_KEY --model $MODEL
python3 bench/locomo/run.py report
```

`ingest` 按 `idempotencyKey` 去重，`ask` / `judge` 按题号跳过已完成的，中断了直接重跑。

## 隔离

评测跑在独立租户里（令牌行的 `tenant_id`），与线上记忆互不可见——RLS 策略按
`app.tenant_id` 过滤，不是「查完再筛」。跑完删该租户的行即可，不影响其他数据。
