package com.tinyclaw.ports.llm;

/**
 * Port for generating assistant messages from an LLM.
 *
 * <p>Core engine must interact with LLMs exclusively through this interface.
 * No Spring AI types leak through.</p>
 */
public interface LlmGateway {

    /**
     * Generate an assistant response given a conversation history and available tools.
     *
     * @param request the request containing messages, tool definitions, and options
     * @return the LLM response
     * @throws LlmException if the gateway itself fails (network, timeout, etc.)
     */
    LlmResponse generate(LlmRequest request);
}
