package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.ModelResponse;
import dev.vaijanath.aiagent.model.StreamingModelPort;
import dev.vaijanath.aiagent.model.StructuredOutput;

/** Minimal model/structured-output stubs for unit tests that don't hit Ollama. */
final class TestDoubles {

    private TestDoubles() {}

    /** A {@link ModelPort} that always returns one canned reply. */
    static final class StubModel implements ModelPort {
        private final String reply;

        StubModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            return ModelResponse.text(reply);
        }
    }

    /** A {@link StructuredOutput} that always returns the same object (cast to the requested type). */
    static final class StubStructured implements StructuredOutput {
        private final Object route;

        StubStructured(Object route) {
            this.route = route;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T generate(ModelRequest request, Class<T> type) {
            return (T) route;
        }
    }

    /** A {@link ChatAgent} that returns one canned reply (useful for routing/delegation tests). */
    static final class StubChatAgent implements ChatAgent {
        private final String reply;

        StubChatAgent(String reply) {
            this.reply = reply;
        }

        @Override
        public AgentResponse respond(java.util.List<Message> conversation) {
            return AgentResponse.completed(reply);
        }
    }
}
