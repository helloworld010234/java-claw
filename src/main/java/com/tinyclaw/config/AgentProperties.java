package com.tinyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent run lifecycle configuration.
 */
@ConfigurationProperties(prefix = "tiny-claw.agent")
public class AgentProperties {

    private int maxTurns = 20;
    private int maxToolCallsPerTurn = 8;
    private int maxRuntimeSeconds = 600;
    private boolean planMode = false;
    private boolean enableThinking = false;

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public int getMaxToolCallsPerTurn() {
        return maxToolCallsPerTurn;
    }

    public void setMaxToolCallsPerTurn(int maxToolCallsPerTurn) {
        this.maxToolCallsPerTurn = maxToolCallsPerTurn;
    }

    public int getMaxRuntimeSeconds() {
        return maxRuntimeSeconds;
    }

    public void setMaxRuntimeSeconds(int maxRuntimeSeconds) {
        this.maxRuntimeSeconds = maxRuntimeSeconds;
    }

    public boolean isPlanMode() {
        return planMode;
    }

    public void setPlanMode(boolean planMode) {
        this.planMode = planMode;
    }

    public boolean isEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(boolean enableThinking) {
        this.enableThinking = enableThinking;
    }
}
