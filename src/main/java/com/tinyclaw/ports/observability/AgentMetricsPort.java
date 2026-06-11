package com.tinyclaw.ports.observability;

/**
 * Port for agent-level metrics collection.
 *
 * <p>Decouples the application layer from specific metrics backends
 * such as Micrometer.</p>
 */
public interface AgentMetricsPort {

    /**
     * Record a run state change.
     *
     * @param status running / completed / failed
     */
    void recordRun(String status);

    /**
     * Record a tool execution attempt.
     *
     * @param toolName the tool name
     * @param success  true if the tool executed without error
     */
    void recordToolExecution(String toolName, boolean success);

    /**
     * Update the context size gauge.
     *
     * @param size current message count
     */
    void updateContextSize(int size);
}
