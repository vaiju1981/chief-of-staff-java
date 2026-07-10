package dev.vaijanath.chiefofstaff.config;

import dev.vaijanath.aiagent.guardrail.GuardrailDecision;
import dev.vaijanath.aiagent.guardrail.GuardrailStage;
import dev.vaijanath.aiagent.guardrail.Guardrails;
import dev.vaijanath.aiagent.guardrail.LlamaGuardGuardrail;
import dev.vaijanath.aiagent.langchain4j.OllamaModelPorts;
import dev.vaijanath.aiagent.model.ModelPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Input/output safety, ported from the Python {@code Trust.govern} step. When {@code COS_GUARD_MODEL} is
 * set (a Llama Guard model pulled into Ollama), every user message is checked before it reaches an agent
 * and every assistant reply is checked before it is returned.
 *
 * <p>Fail-open: if the guard model is unreachable or throws, the request proceeds — matching the rest of
 * the app's graceful-degradation posture (RAG, MCP). The refusal text is the guard's own replacement.
 */
@Component
public final class Guard {

    private static final Logger log = LoggerFactory.getLogger(Guard.class);
    private static final String REFUSAL = "I'm sorry, but I can't help with that request.";

    private final LlamaGuardGuardrail llama;

    Guard(CosProperties props) {
        LlamaGuardGuardrail built = null;
        if (props.hasGuardModel()) {
            try {
                ModelPort classifier =
                        OllamaModelPorts.ollama(props.ollamaBaseUrl(), props.guardModel());
                built = new LlamaGuardGuardrail(classifier, REFUSAL, true);
                log.info("[guard] Llama Guard active ({})", props.guardModel());
            } catch (Exception e) {
                log.warn("[guard] could not build guard model ({}); safety disabled: {}",
                        props.guardModel(), e.toString());
            }
        } else {
            log.info("[guard] no guard model configured; safety checks are off");
        }
        this.llama = built;
    }

    public boolean isEnabled() {
        return llama != null;
    }

    /** Pre-flight check on a user message; {@link GuardrailDecision#blocked()} means refuse. */
    public GuardrailDecision checkInput(String text) {
        return check(GuardrailStage.INPUT, text);
    }

    /** Post-flight check on an assistant reply; if blocked, return the refusal instead. */
    public GuardrailDecision checkOutput(String text) {
        return check(GuardrailStage.OUTPUT, text);
    }

    private GuardrailDecision check(GuardrailStage stage, String text) {
        if (llama == null || text == null || text.isBlank()) {
            return GuardrailDecision.allow(text == null ? "" : text);
        }
        try {
            return Guardrails.apply(java.util.List.of(llama), stage, text);
        } catch (Exception e) {
            log.warn("[guard] {} check failed, failing open: {}", stage, e.toString());
            return GuardrailDecision.allow(text);
        }
    }
}
