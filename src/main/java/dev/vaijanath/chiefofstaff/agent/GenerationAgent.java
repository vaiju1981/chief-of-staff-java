package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.AgentRequest;
import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.ModelResponse;
import dev.vaijanath.aiagent.model.StreamingModelPort;
import java.util.List;
import java.util.function.Consumer;

/**
 * A tool-less generation agent: system prompt + user message → model. Used for comms and code, which
 * never call tools — so they can stream token-by-token, unlike a tool-using {@code DefaultAgent}.
 */
public final class GenerationAgent implements Agent, Streamable {

    private final String systemPrompt;
    private final ModelPort model;
    private final StreamingModelPort streamingModel;

    public GenerationAgent(String systemPrompt, ModelPort model, StreamingModelPort streamingModel) {
        this.systemPrompt = systemPrompt;
        this.model = model;
        this.streamingModel = streamingModel;
    }

    @Override
    public AgentResponse run(AgentRequest request) {
        return AgentResponse.completed(model.chat(request(request.input())).text());
    }

    @Override
    public AgentResponse runStreaming(AgentRequest request, Consumer<String> onToken) {
        ModelResponse response = streamingModel.chatStream(request(request.input()), onToken);
        return AgentResponse.completed(response.text());
    }

    private ModelRequest request(String userMessage) {
        return ModelRequest.of(
                List.of(Message.system(systemPrompt), Message.user(userMessage == null ? "" : userMessage)));
    }
}
