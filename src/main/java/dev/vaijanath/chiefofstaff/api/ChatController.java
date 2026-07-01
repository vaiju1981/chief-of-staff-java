package dev.vaijanath.chiefofstaff.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.chiefofstaff.agent.ChatAgent;
import dev.vaijanath.chiefofstaff.agent.Conversations;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAI Chat Completions surface, ported from the Python {@code server.py}. Each agent is a "model"
 * named {@code agent-<x>}. The full message history the client sends is passed through as the
 * conversation (multi-turn memory), so the server stays stateless. When {@code stream:true}, tokens are
 * written as SSE chunks; tool agents fall back to a single final chunk.
 */
@RestController
class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final Map<String, ChatAgent> agents;
    private final ObjectMapper json = new ObjectMapper();

    ChatController(Map<String, ChatAgent> agents) {
        this.agents = agents;
    }

    @GetMapping("/v1/models")
    Map<String, Object> models() {
        long created = System.currentTimeMillis() / 1000;
        List<Map<String, Object>> data = agents.keySet().stream()
                .map(id -> Map.<String, Object>of(
                        "id", id, "object", "model", "created", created, "owned_by", "chief-of-staff"))
                .toList();
        return Map.of("object", "list", "data", data);
    }

    @PostMapping("/v1/chat/completions")
    ResponseEntity<?> chat(@RequestBody ChatRequest req, HttpServletResponse response) throws IOException {
        ChatAgent agent = agents.get(req.model());
        if (agent == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Unknown agent: " + req.model()));
        }
        List<Message> conversation = toConversation(req.messages());
        if (Conversations.latestUser(conversation).isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No user message found"));
        }

        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long created = System.currentTimeMillis() / 1000;

        if (Boolean.TRUE.equals(req.stream())) {
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            streamTo(response.getOutputStream(), agent, conversation, id, created, req.model());
            return null; // response already written
        }
        String answer = agent.respond(conversation).output();
        return ResponseEntity.ok(completion(id, created, req.model(), answer));
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("status", "ok", "agents", List.copyOf(agents.keySet()));
    }

    /** Maps the OpenAI message history to framework messages (user/assistant turns; our own system prompt). */
    static List<Message> toConversation(List<Msg> messages) {
        List<Message> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (Msg m : messages) {
            if (m == null || m.content() == null || m.content().isBlank()) {
                continue;
            }
            if ("user".equals(m.role())) {
                out.add(Message.user(m.content()));
            } else if ("assistant".equals(m.role())) {
                out.add(Message.assistant(m.content()));
            } // drop client system/tool messages — each agent supplies its own system prompt
        }
        return out;
    }

    /** Streams the agent's answer as OpenAI SSE chunks, then the terminal stop chunk and {@code [DONE]}. */
    private void streamTo(
            OutputStream out, ChatAgent agent, List<Message> conversation, String id, long created, String model) {
        Consumer<String> onToken = token -> writeEvent(out, chunkJson(id, created, model, token, null));
        try {
            agent.respondStreaming(conversation, onToken);
            writeEvent(out, chunkJson(id, created, model, null, "stop"));
            writeRaw(out, "data: [DONE]\n\n");
        } catch (UncheckedIOException e) {
            log.info("stream client disconnected: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("stream failed", e);
        }
    }

    private Map<String, Object> completion(String id, long created, String model, String answer) {
        Map<String, Object> choice =
                Map.of("index", 0, "message", Map.of("role", "assistant", "content", answer), "finish_reason", "stop");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("object", "chat.completion");
        out.put("created", created);
        out.put("model", model);
        out.put("choices", List.of(choice));
        out.put("usage", Map.of("prompt_tokens", 0, "completion_tokens", 0, "total_tokens", 0));
        return out;
    }

    /** One streaming chunk: a content delta (finishReason null), or the terminal empty delta (stop). */
    private String chunkJson(String id, long created, String model, String token, String finishReason) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (token != null) {
            delta.put("role", "assistant");
            delta.put("content", token);
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason); // may be null

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("id", id);
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", created);
        chunk.put("model", model);
        chunk.put("choices", List.of(choice));
        try {
            return json.writeValueAsString(chunk);
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("failed to serialize chunk", e));
        }
    }

    private void writeEvent(OutputStream out, String jsonChunk) {
        writeRaw(out, "data: " + jsonChunk + "\n\n");
    }

    private void writeRaw(OutputStream out, String s) {
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Msg(String role, String content) {}

    // Open WebUI sends extra fields (temperature, max_tokens, stream_options, tools, …) — ignore the
    // ones we don't model. `stream` is boxed because Jackson 3 (Spring Boot 4) refuses to map an
    // absent field onto a primitive.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatRequest(String model, List<Msg> messages, Boolean stream) {}
}
