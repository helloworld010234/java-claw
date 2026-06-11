package com.tinyclaw.adapters.web;

import com.tinyclaw.adapters.web.dto.RunStatusResponse;
import com.tinyclaw.ports.persistence.AgentRunSummary;
import com.tinyclaw.ports.persistence.RunRepositoryPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Agent Run 状态查询控制器。
 *
 * <p>GET /api/v1/runs/{runId} 查询指定 Run 的当前状态。</p>
 */
@RestController
@RequestMapping("/api/v1/runs")
public class AgentRunStatusController {

    private final RunRepositoryPort runRepository;

    public AgentRunStatusController(RunRepositoryPort runRepository) {
        this.runRepository = runRepository;
    }

    /**
     * 查询 Run 状态。
     *
     * @param runId Run ID
     * @return 200 OK + RunStatusResponse；404 如果 Run 不存在
     */
    @GetMapping("/{runId}")
    public ResponseEntity<RunStatusResponse> getRunStatus(@PathVariable String runId) {
        Optional<AgentRunSummary> maybeRun = runRepository.findById(runId);
        if (maybeRun.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        AgentRunSummary run = maybeRun.get();
        RunStatusResponse response = new RunStatusResponse(
            run.id(),
            run.status().name(),
            run.turnCount(),
            run.errorReason(),
            run.startedAt(),
            run.completedAt()
        );
        return ResponseEntity.ok(response);
    }
}
