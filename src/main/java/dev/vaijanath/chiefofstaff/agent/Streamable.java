package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.agent.AgentRequest;
import dev.vaijanath.aiagent.agent.AgentResponse;
import java.util.function.Consumer;

/**
 * An agent that can stream its answer token-by-token. The OpenAI SSE endpoint uses this: agents that
 * implement it stream live; the rest fall back to a single final chunk.
 */
public interface Streamable {

    /** Runs the turn, forwarding each text chunk to {@code onToken}, and returns the full response. */
    AgentResponse runStreaming(AgentRequest request, Consumer<String> onToken);
}
