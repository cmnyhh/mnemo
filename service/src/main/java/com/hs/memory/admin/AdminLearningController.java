package com.hs.memory.admin;

import com.hs.memory.Settings;
import com.hs.memory.obs.AuditService;
import com.hs.memory.proc.ProcedureService;
import com.hs.memory.proc.TrajectoryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台的「学习」页接口。
 *
 * <p>它读的是单租户部署里的默认租户——控制台本身就是运维视角，没有「当前租户」
 * 这个概念。要按租户看，用 /v1/memory/* 与对应令牌。
 */
@RestController
@RequestMapping("/admin/api/learning")
public class AdminLearningController {

    private final ProcedureService proc;
    private final Settings settings;
    private final AuditService audit;

    public AdminLearningController(ProcedureService proc, Settings settings, AuditService audit) {
        this.proc = proc;
        this.settings = settings;
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> overview() {
        String tenant = settings.tenant();
        List<TrajectoryRepository.TrajectoryRow> runs = proc.recent(tenant, 50);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", settings.procEnabled());
        out.put("trajectories", runs);
        out.put("strategies", proc.strategies(tenant, 100));
        out.put("skills", proc.skills(tenant, 100));
        out.put("world", proc.world(tenant, null, 100));
        out.put("thresholds", Map.of(
                "minSupport", settings.procMinSupport(),
                "minSuccess", settings.procMinSuccess(),
                "crystallizeSupport", settings.procCrystallizeSupport(),
                "crystallizeSuccess", settings.procCrystallizeSuccess(),
                "gamma", settings.procGamma(),
                "reflectionBonus", settings.procReflectionBonus()));
        return out;
    }

    @PostMapping("/distill")
    public ProcedureService.LearnResult distill() {
        ProcedureService.LearnResult result = proc.learnAll(settings.tenant());
        audit.record("proc.distill", null, Map.of("strategies", result.strategies(),
                "skills", result.skills(), "quarantined", result.quarantined()));
        return result;
    }

    /** 轨迹详情：控制台点开一行时看每一步的动作、观察、反思与回传信用。 */
    @GetMapping("/trajectories/{id}")
    public ProcedureService.TrajectoryDetail trajectory(@PathVariable String id) {
        return proc.detail(settings.tenant(), id);
    }

    /** 控制台点开技能时按 Markdown 取正文；前端用 text 读，不做 JSON 解析。 */
    @GetMapping(value = "/skills/{name}", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> skill(@PathVariable String name) {
        if (name == null || !name.matches("[a-z0-9-]{3,64}")) {
            throw new IllegalArgumentException("技能名不合法");
        }
        return proc.skill(settings.tenant(), name)
                .map(s -> ResponseEntity.ok(s.body()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
