package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.ModelResponse;
import dev.vaijanath.aiagent.model.StreamingModelPort;
import dev.vaijanath.aiagent.model.StructuredOutput;
import dev.vaijanath.chiefofstaff.prompt.SupervisorPrompts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrator, ported from {@code supervisor.py}: routes each turn to exactly one specialist (on the
 * latest user message), or answers meta / meta_recording itself by reformulating fixed facts into the
 * user's language. Delegated specialists receive the full conversation, so multi-turn context flows
 * through. Routing prefers typed {@link StructuredOutput}, then a single-token chat, then {@code meta}.
 */
public final class Supervisor implements ChatAgent {

    private static final Logger log = LoggerFactory.getLogger(Supervisor.class);

    private static final String META_GUIDANCE =
            "End with an open question like \"What can I do for you?\" in the user's language.";
    private static final String MEETING_GUIDANCE =
            "Highlight the local automated pipeline first. Keep any terminal commands EXACTLY as written "
                    + "— do not translate commands.";

    private final ModelPort model;
    private final StreamingModelPort streamingModel;
    private final StructuredOutput router;
    private final Map<String, ChatAgent> specialists;

    public Supervisor(
            ModelPort model,
            StreamingModelPort streamingModel,
            StructuredOutput router,
            Map<String, ChatAgent> specialists) {
        this.model = model;
        this.streamingModel = streamingModel;
        this.router = router;
        this.specialists = specialists;
    }

    /** Typed routing result — the model returns {@code {"agent": "..."}}. */
    public record Route(String agent) {}

    @Override
    public AgentResponse respond(List<Message> conversation) {
        String latest = Conversations.latestUser(conversation);
        if (isAutoRequest(latest)) {
            return AgentResponse.completed("[]");
        }
        String route = route(latest);
        log.info("[supervisor] route -> {}", route);
        return switch (route) {
            case "meta" -> AgentResponse.completed(reformulate(SupervisorPrompts.SYSTEM_FACTS, META_GUIDANCE, latest));
            case "meta_recording" -> AgentResponse.completed(
                    reformulate(SupervisorPrompts.MEETING_FACTS, MEETING_GUIDANCE, latest));
            default -> {
                ChatAgent specialist = specialists.get(route);
                yield specialist != null
                        ? specialist.respond(conversation)
                        : AgentResponse.completed(reformulate(SupervisorPrompts.SYSTEM_FACTS, META_GUIDANCE, latest));
            }
        };
    }

    @Override
    public AgentResponse respondStreaming(List<Message> conversation, Consumer<String> onToken) {
        String latest = Conversations.latestUser(conversation);
        if (isAutoRequest(latest)) {
            onToken.accept("[]");
            return AgentResponse.completed("[]");
        }
        String route = route(latest);
        log.info("[supervisor] route -> {} (streaming)", route);
        return switch (route) {
            case "meta" -> streamReformulation(SupervisorPrompts.SYSTEM_FACTS, META_GUIDANCE, latest, onToken);
            case "meta_recording" -> streamReformulation(
                    SupervisorPrompts.MEETING_FACTS, MEETING_GUIDANCE, latest, onToken);
            default -> {
                ChatAgent specialist = specialists.get(route);
                yield specialist != null
                        ? specialist.respondStreaming(conversation, onToken)
                        : streamReformulation(SupervisorPrompts.SYSTEM_FACTS, META_GUIDANCE, latest, onToken);
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
        return model.chat(reformulationRequest(facts, guidance, userMessage)).text();
    }

    private AgentResponse streamReformulation(
            String facts, String guidance, String userMessage, Consumer<String> onToken) {
        ModelResponse response = streamingModel.chatStream(reformulationRequest(facts, guidance, userMessage), onToken);
        return AgentResponse.completed(response.text());
    }

    private ModelRequest reformulationRequest(String facts, String guidance, String userMessage) {
        return ModelRequest.of(List.of(
                Message.system(SupervisorPrompts.reformulation(facts, guidance)),
                Message.user(userMessage)));
    }

    /** Lowercase and strip everything but a–z and underscore (so {@code meta_recording} survives). */
    private static String normalize(String s) {
        return s == null ? "" : s.strip().toLowerCase().replaceAll("[^a-z_]", "");
    }

    /** Open WebUI fires auto-generated title/tag/follow-up requests that look like JSON; skip them. */
    private static boolean isAutoRequest(String message) {
        if (message == null || !message.startsWith("{")) {
            return false;
        }
        String head = message.substring(0, Math.min(message.length(), 200));
        return message.contains("\"follow_ups\"")
                || message.contains("\"title\"")
                || message.contains("\"tags\"")
                || head.contains("Generate");
    }
}
