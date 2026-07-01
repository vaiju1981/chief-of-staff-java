package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.AgentRequest;
import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.chiefofstaff.prompt.CosPrompts;
import java.util.List;

/**
 * Handoff agent, ported from {@code handoff.py}: turns the user's request into a rich, ready-to-paste
 * prompt for Claude.ai / ChatGPT, for tasks beyond the local setup.
 *
 * <p>ponytail: v1 is tool-less — no local RAG context retrieval and a language-neutral wrapper. The
 * Qdrant→pgvector context fetch and the fully localized wrapper land in the RAG step.
 */
public final class Handoff implements Agent {

    private final ModelPort model;

    public Handoff(ModelPort model) {
        this.model = model;
    }

    @Override
    public AgentResponse run(AgentRequest request) {
        String userMessage = request.input() == null ? "" : request.input();

        ModelRequest build = ModelRequest.of(List.of(
                Message.system(CosPrompts.handoffBuilder()),
                Message.user("User request:\n" + userMessage
                        + "\n\nReformulate this into a structured, rich prompt ready to paste into "
                        + "Claude.ai or ChatGPT. Output the prompt in the user's language. Reply with "
                        + "ONLY the prompt.")));
        String prompt = model.chat(build).text().strip();

        String out = """
                ## 📋 Prompt prepared for Claude.ai / ChatGPT

                Copy the block below into a new conversation:

                ---

                %s

                ---

                ## 🚀 Where to send it
                - **Claude.ai** (long-form writing, reasoning, analysis) → https://claude.ai/new
                - **ChatGPT** (complex code, data analysis) → https://chatgpt.com/
                """
                .formatted(prompt);
        return AgentResponse.completed(out);
    }
}
