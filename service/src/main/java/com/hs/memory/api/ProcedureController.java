package com.hs.memory.api;

import com.hs.memory.proc.ProcedureService;
import com.hs.memory.proc.TrajectoryRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 程序性记忆的写入面。叙述性记忆走 /ingest，这里走「一次执行」的完整生命周期：
 * 开局 → 追加步骤 → 关闭并回传信用。
 *
 * <p>为什么不是一步写入整条轨迹：信用要等终局奖励才回传，而 agent 是在执行过程中
 * 边走边记的。分三步调用能让轨迹在中途崩溃时也留下证据——那恰恰是最该学的样本。
 */
@RestController
@RequestMapping("/v1/memory")
public class ProcedureController {

    private final ProcedureService proc;

    public ProcedureController(ProcedureService proc) {
        this.proc = proc;
    }

    @PostMapping("/trajectories")
    public ProcedureService.OpenResult open(@RequestAttribute("tenant") String tenant,
                                            @RequestBody OpenRequest request) {
        return proc.open(tenant, request.userId(), request.projectId(), request.sessionId(), request.goal());
    }

    @PostMapping("/trajectories/{id}/steps")
    public ProcedureService.AppendResult append(@RequestAttribute("tenant") String tenant,
                                                @PathVariable String id,
                                                @RequestBody StepRequest request) {
        return proc.append(tenant, id, request.steps());
    }

    @PostMapping("/trajectories/{id}/close")
    public ProcedureService.CloseResult close(@RequestAttribute("tenant") String tenant,
                                              @PathVariable String id,
                                              @RequestBody CloseRequest request) {
        return proc.close(tenant, id, request.status(), request.reward(), request.reflection());
    }

    /**
     * 一次写完：任务已经结束、手上有一整条轨迹时用这个。
     * 分三步的版本仍然保留——中途崩溃的半条轨迹同样是学习样本。
     */
    @PostMapping("/trajectories/record")
    public ProcedureService.CloseResult record(@RequestAttribute("tenant") String tenant,
                                               @RequestBody RecordRequest request) {
        return proc.record(tenant, request.userId(), request.projectId(), request.sessionId(),
                request.goal(), request.steps(), request.status(), request.reward(), request.reflection());
    }

    @GetMapping("/trajectories")
    public Map<String, Object> list(@RequestAttribute("tenant") String tenant,
                                    @RequestParam(required = false) Integer limit) {
        int n = limit == null ? 50 : Math.min(Math.max(1, limit), 200);
        return Map.of("trajectories", proc.recent(tenant, n));
    }

    @GetMapping("/trajectories/{id}")
    public ProcedureService.TrajectoryDetail detail(@RequestAttribute("tenant") String tenant,
                                                    @PathVariable String id) {
        return proc.detail(tenant, id);
    }

    @GetMapping("/strategies")
    public Map<String, Object> strategies(@RequestAttribute("tenant") String tenant,
                                          @RequestParam(required = false) Integer limit) {
        return Map.of("strategies", proc.strategies(tenant, limit == null ? 100 : limit));
    }

    @GetMapping("/world")
    public Map<String, Object> world(@RequestAttribute("tenant") String tenant,
                                     @RequestParam(required = false) String subject,
                                     @RequestParam(required = false) Integer limit) {
        return Map.of("rules", proc.world(tenant, subject, limit == null ? 100 : limit));
    }

    @GetMapping("/skills")
    public Map<String, Object> skills(@RequestAttribute("tenant") String tenant,
                                      @RequestParam(required = false) Integer limit) {
        return Map.of("skills", proc.skills(tenant, limit == null ? 100 : limit));
    }

    /**
     * 技能正文按 Markdown 返回，不是 JSON 包一层字符串。
     * 这样 `curl .../skills/proc-1a2b3c4d &gt; SKILL.md` 拿到的就是可以直接用的文件。
     */
    @GetMapping(value = "/skills/{name}", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> skill(@RequestAttribute("tenant") String tenant,
                                        @PathVariable String name) {
        if (name == null || !name.matches("[a-z0-9-]{3,64}")) {
            throw new IllegalArgumentException("技能名不合法");
        }
        return proc.skill(tenant, name)
                .map(s -> ResponseEntity.ok(s.body()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 手工蒸馏。定时蒸馏还没做——它需要「成功样本」，而样本来自真实使用，急不来。 */
    @PostMapping("/learn")
    public ProcedureService.LearnResult learn(@RequestAttribute("tenant") String tenant) {
        return proc.learnAll(tenant);
    }

    public record OpenRequest(String goal, String userId, String projectId, String sessionId) {
    }

    public record StepRequest(List<ProcedureService.StepCommand> steps) {
    }

    public record CloseRequest(String status, Double reward, String reflection) {
    }

    /** 一次写完整条轨迹。steps 与服务端同形，不需要先拿 id。 */
    public record RecordRequest(String goal, String userId, String projectId, String sessionId,
                                List<ProcedureService.StepCommand> steps,
                                String status, Double reward, String reflection) {
    }
}
