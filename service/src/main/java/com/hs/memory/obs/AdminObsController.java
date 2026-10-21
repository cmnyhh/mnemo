package com.hs.memory.obs;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台「观测」页的数据端点。
 *
 * <p>与 {@code /internal/metrics} 的区别：那边是给 Prometheus 抓的文本，这里是给页面画的 JSON。
 * 两条路读同一份 MeterRegistry，不会出现「页面显示 120ms、监控显示 400ms」这种对不上的情况。
 *
 * <p>都在 {@link com.hs.memory.admin.AdminFilter} 后面：延迟分布能暴露调用节奏，
 * 不该匿名可见。抓取端点则走 Bearer（见 {@code ApiTokenFilter}）。
 */
@RestController
@RequestMapping("/admin/api")
public class AdminObsController {

    private final MetricsRecorder metrics;
    private final AuditService audit;

    public AdminObsController(MetricsRecorder metrics, AuditService audit) {
        this.metrics = metrics;
        this.audit = audit;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> out = new LinkedHashMap<>(metrics.snapshot());
        out.put("scrapeNote", "Prometheus 抓取需带 Authorization: Bearer <token>");
        return out;
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(@RequestParam(name = "limit", required = false) Integer limit,
                                     @RequestParam(name = "action", required = false) String action) {
        return audit.page(limit == null ? 50 : limit, action);
    }
}
