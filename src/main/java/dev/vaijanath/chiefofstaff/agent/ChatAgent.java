package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.model.Message;
import java.util.List;
import java.util.function.Consumer;

/**
 * The chat seam behind the OpenAI endpoint: an agent that responds to a full <b>conversation</b> — the
 * message history the client sends every turn — not just one message. Multi-turn memory comes from that
 * history, so the server stays stateless, like the OpenAI API itself.
 *
 * <p>{@link #respondStreaming} defaults to emitting the whole answer as one chunk; the tool-less
 * generators and the supervisor override it for real token streaming.
 */
public interface ChatAgent {

    AgentResponse respond(List<Message> conversation);

    default AgentResponse respondStreaming(List<Message> conversation, Consumer<String> onToken) {
        AgentResponse response = respond(conversation);
        onToken.accept(response.output());
        return response;
    }
}
