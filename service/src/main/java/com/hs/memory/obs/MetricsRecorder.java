package com.hs.memory.obs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 召回与写入的计时器。名字统一挂在 {@code mnemo.*} 下，控制台与 /internal/metrics 读同一份。
 *
 * <p>为什么要有它：一次召回慢到底是慢在嵌入（公网 100~500ms）、BM25、向量库还是精排，
 * 以前只能翻 {@code docker logs} 里的零散 warn，而且只看得到失败的那几次——慢但成功的那几次
 * 一条日志都没有。分阶段计时把「慢」变成一个能排序的数字。
 *
 * <p>三个约定：
 * <ul>
 *   <li>计时器按标签组合缓存。每次请求都 {@code registry.timer(...)} 会重新走一遍注册表查找，
 *       在几十 QPS 下这是白白多出来的锁竞争；</li>
 *   <li>成功率用 outcome 计数器而不是从日志里数：{@code hit / abstain / empty / error} 四个值
 *       合起来才是召回质量的全貌，只看错误率会漏掉「一直在弃权」这种更常见的病；</li>
 *   <li>记账失败一律吞掉。观测不该成为新的故障点。</li>
 * </ul>
 */
@Component
public class MetricsRecorder {

    public static final String SEARCH = "mnemo.search";
    public static final String INGEST = "mnemo.ingest";
    public static final String SEARCH_STAGE = "mnemo.search.stage";
    public static final String INGEST_STAGE = "mnemo.ingest.stage";
    public static final String OUTCOME = "mnemo.search.outcome";
    public static final String RERANK = "mnemo.rerank";
    public static final String DEGRADE = "mnemo.degrade";
    public static final String REVIEW = "mnemo.ingest.review";
    public static final String ADMISSION = "mnemo.ingest.admission";

    private final MeterRegistry registry;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public MetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 一次召回的端到端耗时 + 结果。{@code outcome} 取 hit / abstain / empty / error。 */
    public void search(String mode, String outcome, long nanos) {
        record(SEARCH, nanos, "mode", mode, "outcome", outcome);
        counter(OUTCOME, "outcome", outcome).increment();
    }

    /** 召回链路里某一段的耗时：embed / bm25 / dense / rerank。 */
    public void searchStage(String stage, long nanos) {
        record(SEARCH_STAGE, nanos, "stage", stage);
    }

    /**
     * 一次写入的端到端耗时。
     *
     * <p>{@code extraction} 是这一轮走了哪条路：{@code llm-extract}（抽取+摘要）、
     * {@code log-summary}（只摘了话，没抽事实）、{@code summary-fallback}（摘要模型不可用，
     * 记了原文）、{@code episode-fallback}（摘要也不可用，按段硬切）、{@code structured}（调用方自抽）。
     * 它同时回答了「记的是什么」和「花了谁的钱」。
     */
    public void ingest(String extraction, String outcome, long nanos) {
        record(INGEST, nanos, "extraction", extraction, "outcome", outcome);
    }

    /** 写入链路里某一段：extract / summarize / embed。 */
    public void ingestStage(String stage, long nanos) {
        record(INGEST_STAGE, nanos, "stage", stage);
    }

    public void rerank(String state) {
        counter(RERANK, "state", state).increment();
    }

    /** 降级：值取 vector / rerank / extract / vision，用来回答「这阵子是不是一直在降级跑」。 */
    public void degrade(String kind) {
        counter(DEGRADE, "kind", kind).increment();
    }

    /** 写入审查的处置：pass / downgrade。 */
    public void review(String verdict) {
        counter(REVIEW, "verdict", verdict).increment();
    }

    /**
     * 准入门：keep / reject / rules / error。
     *
     * <p>单独计数是因为它和 review 回答的是不同问题：review 是「这条内容有没有投毒嫌疑」，
     * 准入是「这段内容该不该进记忆库」。reject 忽然变多，通常是上游把提示词或工具回显
     * 当成对话发过来了，而不是模型变严格了。
     */
    public void admission(String verdict) {
        counter(ADMISSION, "verdict", verdict).increment();
    }

    // ------------------------------------------------------------------ 读

    /**
     * 控制台用的快照。异常一律降级成空表：观测页自己挂掉比没有观测页更糟。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("search", stats(SEARCH));
        out.put("ingest", stats(INGEST));
        out.put("stages", stageStats());
        out.put("outcomes", counts(OUTCOME, "outcome"));
        out.put("rerank", counts(RERANK, "state"));
        out.put("degrade", counts(DEGRADE, "kind"));
        out.put("review", counts(REVIEW, "verdict"));
        out.put("admission", counts(ADMISSION, "verdict"));
        out.put("scrapePath", "/internal/metrics");
        return out;
    }

    private Map<String, Object> stats(String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Timer> all = new ArrayList<>(registry.find(name).timers());
        long count = 0;
        double totalMs = 0;
        double maxMs = 0;
        for (Timer t : all) {
            count += t.count();
            totalMs += t.totalTime(TimeUnit.MILLISECONDS);
            maxMs = Math.max(maxMs, t.max(TimeUnit.MILLISECONDS));
        }
        out.put("count", count);
        out.put("maxMs", round(maxMs));
        out.put("meanMs", count == 0 ? 0 : round(totalMs / count));
        out.put("p50Ms", percentile(all, 0.5));
        out.put("p95Ms", percentile(all, 0.95));
        out.put("p99Ms", percentile(all, 0.99));
        return out;
    }

    /**
     * 分阶段耗时，按 p95 从高到低排。
     *
     * <p>排序是有意的：让人第一眼看到最贵的那一段，而不是自己去四行里比。
     */
    private List<Map<String, Object>> stageStats() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Timer t : registry.find(SEARCH_STAGE).timers()) {
            rows.add(stageRow("search", t));
        }
        for (Timer t : registry.find(INGEST_STAGE).timers()) {
            rows.add(stageRow("ingest", t));
        }
        rows.sort(Comparator.comparingDouble(
                r -> -((Number) r.getOrDefault("p95Ms", 0)).doubleValue()));
        return rows;
    }

    private Map<String, Object> stageRow(String kind, Timer t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("stage", t.getId().getTag("stage"));
        row.put("count", t.count());
        row.put("p50Ms", percentile(List.of(t), 0.5));
        row.put("p95Ms", percentile(List.of(t), 0.95));
        row.put("maxMs", round(t.max(TimeUnit.MILLISECONDS)));
        return row;
    }

    /** 多个计时器（不同标签）合成一个分位数：取各自最大，比加权平均更接近「最坏的那一类」。 */
    private static double percentile(List<Timer> timers, double p) {
        double v = 0;
        for (Timer t : timers) {
            for (ValueAtPercentile item : t.takeSnapshot().percentileValues()) {
                if (Math.abs(item.percentile() - p) < 0.001) {
                    v = Math.max(v, item.value());
                }
            }
        }
        return round(v);
    }

    private Map<String, Long> counts(String name, String tag) {
        Map<String, Long> out = new LinkedHashMap<>();
        registry.find(name).counters().forEach(c -> out.merge(
                String.valueOf(c.getId().getTag(tag)), (long) c.count(), Long::sum));
        return out;
    }

    // ------------------------------------------------------------------ 写

    private void record(String name, long nanos, String... tags) {
        try {
            Timer timer = timers.computeIfAbsent(name + "|" + String.join(",", tags),
                    key -> Timer.builder(name)
                            .tags(tags)
                            // 分位数在创建时就要声明：注册之后再想加，这个计时器已经定型了
                            .publishPercentiles(0.5, 0.95, 0.99)
                            .register(registry));
            timer.record(nanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // 观测失败不影响主流程
        }
    }

    private Counter counter(String name, String... tags) {
        return counters.computeIfAbsent(name + "|" + String.join(",", tags),
                key -> registry.counter(name, tags));
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0;
        }
        return Math.round(v * 10.0) / 10.0;
    }
}
