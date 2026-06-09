package com.tinyclaw.application.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.tinyclaw.domain.common.DomainGuards;

/**
 * A single step in a scripted run plan.
 */
public record ScriptedRunStep(String id, String tool, JsonNode args) {

    public ScriptedRunStep {
        DomainGuards.requireNonBlank(id, "step id");
        DomainGuards.requireNonBlank(tool, "step tool");
        DomainGuards.requireNonNull(args, "step args");
    }
}
