package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.ModelResponse;
import dev.vaijanath.aiagent.model.StreamingModelPort;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A tool-less generation agent: our system prompt + the full conversation → model. Used for comms and
 * code, which never call tools — so they get real multi-turn context and stream token-by-token.
 */
public final class GenerationAgent implements ChatAgent {

    private final String systemPrompt;
    private final ModelPort model;
    private final StreamingModelPort streamingModel;

    public GenerationAgent(String systemPrompt, ModelPort model, StreamingModelPort streamingModel) {
        this.systemPrompt = systemPrompt;
        this.model = model;
        this.streamingModel = streamingModel;
    }

    @Override
    public AgentResponse respond(List<Message> conversation) {
        return AgentResponse.completed(model.chat(request(conversation)).text());
    }

    @Override
    public AgentResponse respondStreaming(List<Message> conversation, Consumer<String> onToken) {
        ModelResponse response = streamingModel.chatStream(request(conversation), onToken);
        return AgentResponse.completed(response.text());
    }

    private ModelRequest request(List<Message> conversation) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.addAll(conversation);
        return ModelRequest.of(messages);
    }
}
