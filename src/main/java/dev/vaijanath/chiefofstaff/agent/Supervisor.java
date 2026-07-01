package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.AgentRequest;
import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.StructuredOutput;
import dev.vaijanath.chiefofstaff.prompt.SupervisorPrompts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrator, ported from {@code supervisor.py}: routes each request to exactly one specialist, or
 * answers meta / meta_recording questions itself by reformulating fixed facts into the user's language.
 *
 * <p>Routing prefers typed {@link StructuredOutput} (no fragile parsing), then falls back to a
 * single-token chat classification, then to {@code meta} (a safe presentation, never a tool-calling
 * agent) — the same defensive ladder as the Python version.
 */
public final class Supervisor implements Agent {

    private static final Logger log = LoggerFactory.getLogger(Supervisor.class);

    private final ModelPort model;
    private final StructuredOutput router;
    private final Map<String, Agent> specialists;

    public Supervisor(ModelPort model, StructuredOutput router, Map<String, Agent> specialists) {
        this.model = model;
        this.router = router;
        this.specialists = specialists;
    }

    /** Typed routing result — the model returns {@code {"agent": "..."}}. */
    public record Route(String agent) {}

    @Override
    public AgentResponse run(AgentRequest request) {
        String message = request.input() == null ? "" : request.input().strip();
        if (isAutoRequest(message)) {
            // Open WebUI auto-generated title/tag/follow-up request — not a real user turn.
            return AgentResponse.completed("[]");
        }

        String route = route(message);
        log.info("[supervisor] route -> {}", route);

        return switch (route) {
            case "meta" -> AgentResponse.completed(reformulate(
                    SupervisorPrompts.SYSTEM_FACTS,
                    "End with an open question like \"What can I do for you?\" in the user's language.",
                    message));
            case "meta_recording" -> AgentResponse.completed(reformulate(
                    SupervisorPrompts.MEETING_FACTS,
                    "Highlight the local automated pipeline first. Keep any terminal commands EXACTLY as "
                            + "written — do not translate commands.",
                    message));
            default -> {
                Agent specialist = specialists.get(route);
                // route() only returns validated ids, so specialist is present; guard defensively anyway.
                yield specialist != null
                        ? specialist.run(request)
                        : AgentResponse.completed(reformulate(SupervisorPrompts.SYSTEM_FACTS, "", message));
            }
        };
    }

    private String route(String userMessage) {
        Set<String> valid = SupervisorPrompts.validRoutes(specialists.keySet());
        String pick = structuredRoute(userMessage, valid);
        if (pick != null) {
            return pick;
        }
        pick = plainRoute(userMessage, valid);
        return pick != null ? pick : "meta";
    }

    private String structuredRoute(String userMessage, Set<String> valid) {
        try {
            ModelRequest request = ModelRequest.of(List.of(
                    Message.system(SupervisorPrompts.routerPrompt(specialists.keySet())),
                    Message.user(userMessage)));
            Route route = router.generate(request, Route.class);
            if (route != null && route.agent() != null) {
                String pick = normalize(route.agent());
                if (valid.contains(pick)) {
                    return pick;
                }
            }
        } catch (Exception e) {
            log.warn("[supervisor] structured routing failed: {}", e.toString());
        }
        return null;
    }

    private String plainRoute(String userMessage, Set<String> valid) {
        try {
            ModelRequest request = ModelRequest.of(List.of(
                    Message.system(SupervisorPrompts.routerPrompt(specialists.keySet())
                            + "\nReply with ONLY the agent name, one word, no punctuation."),
                    Message.user(userMessage)));
            String raw = model.chat(request).text();
            String first = raw == null || raw.isBlank() ? "" : raw.strip().split("\\s+")[0];
            String pick = normalize(first);
            if (valid.contains(pick)) {
                return pick;
            }
            log.warn("[supervisor] unparseable router response '{}', falling back to meta", raw);
        } catch (Exception e) {
            log.warn("[supervisor] plain routing failed: {}", e.toString());
        }
        return null;
    }

    private String reformulate(String facts, String guidance, String userMessage) {
        ModelRequest request = ModelRequest.of(List.of(
                Message.system(SupervisorPrompts.reformulation(facts, guidance)),
                Message.user(userMessage)));
        return model.chat(request).text();
    }

    /** Lowercase and strip everything but a–z and underscore (so {@code meta_recording} survives). */
    private static String normalize(String s) {
        return s == null ? "" : s.strip().toLowerCase().replaceAll("[^a-z_]", "");
    }

    /** Open WebUI fires auto-generated title/tag/follow-up requests that look like JSON; skip them. */
    private static boolean isAutoRequest(String message) {
        if (!message.startsWith("{")) {
            return false;
        }
        String head = message.substring(0, Math.min(message.length(), 200));
        return message.contains("\"follow_ups\"")
                || message.contains("\"title\"")
                || message.contains("\"tags\"")
                || head.contains("Generate");
    }
}
