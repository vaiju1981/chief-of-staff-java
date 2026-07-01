package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.AgentRequest;
import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.model.Message;
import java.util.List;

/**
 * Adapts a tool-using {@link Agent} (researcher / notes / handoff) to the {@link ChatAgent} seam by
 * flattening the conversation into a single transcript input. Not streamable (the framework's streaming
 * port is text-only and these run tools), so it uses the default single-chunk streaming.
 */
public final class ToolChatAgent implements ChatAgent {

    private final Agent agent;

    public ToolChatAgent(Agent agent) {
        this.agent = agent;
    }

    @Override
    public AgentResponse respond(List<Message> conversation) {
        return agent.run(new AgentRequest(Conversations.flatten(conversation)));
    }
}
