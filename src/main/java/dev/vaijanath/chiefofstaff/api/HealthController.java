package dev.vaijanath.chiefofstaff.api;

import dev.vaijanath.chiefofstaff.config.CosProperties;
import dev.vaijanath.chiefofstaff.rag.RagStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Detailed health, replacing the bare {@code /health} with one that reflects the real state of the
 * dependencies: Ollama reachability (a cheap {@code GET /api/tags}) and the pgvector RAG store. The app
 * degrades gracefully when either is down, so this surfaces that instead of a flat {@code ok}.
 */
@RestController
public final class HealthController {

    private final RagStore rag;
    private final String ollamaBaseUrl;
    private final List<String> agents;
    private final HttpClient client = HttpClient.newHttpClient();

    HealthController(RagStore rag, CosProperties props, Map<String, ?> agents) {
        this.rag = rag;
        this.ollamaBaseUrl = props.ollamaBaseUrl();
        this.agents = List.copyOf(agents.keySet());
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        Map<String, Object> ollama = ollamaStatus();
        Map<String, Object> ragStatus = new LinkedHashMap<>();
        if (rag.enabled()) {
            ragStatus.put("status", "up");
            ragStatus.put("store", "pgvector");
        } else {
            ragStatus.put("status", "unknown");
            ragStatus.put("store", "pgvector");
            ragStatus.put("reason", "unavailable at startup — RAG search disabled, filesystem tools still work");
        }
        String overall = "up".equals(ollama.get("status")) ? "ok" : "degraded";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", overall);
        out.put("ollama", ollama);
        out.put("rag", ragStatus);
        out.put("agents", agents);
        return out;
    }

    private Map<String, Object> ollamaStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBaseUrl.replaceAll("/+$", "") + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                status.put("status", "up");
            } else {
                status.put("status", "down");
                status.put("code", response.statusCode());
            }
        } catch (Exception e) {
            status.put("status", "down");
            status.put("error", e.toString());
        }
        status.put("baseUrl", ollamaBaseUrl);
        return status;
    }
}
