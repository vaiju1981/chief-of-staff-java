package dev.vaijanath.chiefofstaff.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.AgentRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAI Chat Completions surface, ported from the Python {@code server.py}. Each agent is exposed as
 * a "model" named {@code agent-<x>}, so it appears in the Open WebUI model picker and any
 * OpenAI-compatible client (including LiteLLM) can call it.
 */
@RestController
class ChatController {

    private final Map<String, Agent> agents;
    // Instantiated directly (not injected): Spring Boot 4 doesn't expose a com.fasterxml ObjectMapper
    // bean, and this is only used to serialize the small SSE chunks below.
    private final ObjectMapper json = new ObjectMapper();

    ChatController(Map<String, Agent> agents) {
        this.agents = agents;
    }

    /** Open WebUI / LiteLLM call this to discover the available agents. */
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
    ResponseEntity<?> chat(@RequestBody ChatRequest req) {
        Agent agent = agents.get(req.model());
        if (agent == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Unknown agent: " + req.model()));
        }
        String userMessage = lastUserMessage(req.messages());
        if (userMessage == null || userMessage.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No user message found"));
        }

        String answer = agent.run(new AgentRequest(userMessage)).output();
        String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long created = System.currentTimeMillis() / 1000;

        if (Boolean.TRUE.equals(req.stream())) {
            // Single-chunk SSE — matches the Python server (no true token streaming yet).
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(sse(id, created, req.model(), answer));
        }
        return ResponseEntity.ok(completion(id, created, req.model(), answer));
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("status", "ok", "agents", List.copyOf(agents.keySet()));
    }

    /** The last user-role message, mirroring the Python {@code for m in reversed(messages)} loop. */
    static String lastUserMessage(List<Message> messages) {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m != null && "user".equals(m.role())) {
                return m.content();
            }
        }
        return null;
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

    private String sse(String id, long created, String model, String answer) {
        try {
            // finish_reason is null on the content chunk, so build with a null-tolerant map (not Map.of).
            Map<String, Object> firstChoice = new LinkedHashMap<>();
            firstChoice.put("index", 0);
            firstChoice.put("delta", Map.of("role", "assistant", "content", answer));
            firstChoice.put("finish_reason", null);

            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("id", id);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", created);
            chunk.put("model", model);
            chunk.put("choices", List.of(firstChoice));

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("id", id);
            done.put("object", "chat.completion.chunk");
            done.put("created", created);
            done.put("model", model);
            done.put("choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", "stop")));

            return "data: " + json.writeValueAsString(chunk) + "\n\n"
                    + "data: " + json.writeValueAsString(done) + "\n\n"
                    + "data: [DONE]\n\n";
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize SSE chunk", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String role, String content) {}

    // Open WebUI sends extra fields (temperature, max_tokens, stream_options, tools, …) — ignore the
    // ones we don't model. `stream` is boxed because Jackson 3 (Spring Boot 4) refuses to map an
    // absent field onto a primitive.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatRequest(String model, List<Message> messages, Boolean stream) {}
}
