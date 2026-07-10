package dev.vaijanath.chiefofstaff.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.ModelResponse;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.StreamingModelPort;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The tool-less generator: streaming + the long-form auto-continue toward a requested word count. */
class GenerationAgentTest {

    private static final ModelPort ECHO = req -> ModelResponse.text("word ".repeat(50).strip());
    private static final StreamingModelPort ECHO_STREAM = (req, onToken) -> {
        String t = "word ".repeat(50).strip();
        onToken.accept(t);
        return ModelResponse.text(t);
    };

    @Test
    void continuesUntilTargetWordCount() {
        // "1000-word" target → up to 3 continuations; each turn is 50 words → ~200 words total.
        GenerationAgent agent = new GenerationAgent("sys", ECHO, ECHO_STREAM);
        String out = agent.respond(List.of(Message.user("write a 1000-word essay"))).output();
        int words = out.split("\\s+").length;
        assertTrue(words > 50, "expected continuations to append beyond the first 50 words, got " + words);
        assertTrue(out.contains("word"), out);
    }

    @Test
    void shortRequestsDoNotContinue() {
        GenerationAgent agent = new GenerationAgent("sys", ECHO, ECHO_STREAM);
        String out = agent.respond(List.of(Message.user("write a short note"))).output();
        // Exactly one 50-word turn, no continuation padding (no word target requested).
        assertEquals(50, out.split("\\s+").length, out);
    }
}
