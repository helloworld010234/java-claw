package com.tinyclaw.config;

import com.tinyclaw.application.engine.AgentEngine;
import com.tinyclaw.application.engine.PromptComposer;
import com.tinyclaw.ports.reporter.Reporter;
import com.tinyclaw.ports.session.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class EngineConfigurationTest {

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private PromptComposer promptComposer;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private Reporter reporter;

    @Test
    void agentEngineIsWired() {
        assertThat(agentEngine).isNotNull();
    }

    @Test
    void promptComposerIsWired() {
        assertThat(promptComposer).isNotNull();
    }

    @Test
    void sessionServiceIsWired() {
        assertThat(sessionService).isNotNull();
    }

    @Test
    void reporterIsWired() {
        assertThat(reporter).isNotNull();
    }
}
