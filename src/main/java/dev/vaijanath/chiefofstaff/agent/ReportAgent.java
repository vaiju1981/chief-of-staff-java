package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.model.Message;
import java.util.List;
import java.util.function.Consumer;

/**
 * A research→verify→write pipeline: the {@code researcher} gathers cited findings (web + the user's
 * documents), a {@code verifier} adversarially re-checks each claim against its source (dropping
 * conflations, upgrading secondary citations to primary), then a {@code writer} composes a long-form,
 * grounded report from the verified notes. This is the one path that does <b>web search AND a long
 * report</b> — and it streams, because the write phase uses the streaming {@link GenerationAgent} (the
 * research and verify phases are non-streaming but covered by the SSE keep-alive).
 */
public final class ReportAgent implements ChatAgent {

    private final ChatAgent researcher;
    private final ChatAgent verifier; // nullable: skip the verify pass when web tools aren't configured
    private final ChatAgent writer;

    public ReportAgent(ChatAgent researcher, ChatAgent verifier, ChatAgent writer) {
        this.researcher = researcher;
        this.verifier = verifier;
        this.writer = writer;
    }

    @Override
    public AgentResponse respond(List<Message> conversation) {
        return writer.respond(writePrompt(conversation, verify(gather(conversation))));
    }

    @Override
    public AgentResponse respondStreaming(List<Message> conversation, Consumer<String> onToken) {
        String notes = verify(gather(conversation)); // research + verify non-streaming; SSE heartbeat covers them
        return writer.respondStreaming(writePrompt(conversation, notes), onToken);
    }

    private String gather(List<Message> conversation) {
        String request = Conversations.latestUser(conversation);
        String brief = "Research this thoroughly using web search and the indexed documents. Produce "
                + "detailed, organized notes, and cite a source (URL or filename) for every claim. Do NOT "
                + "write the final prose piece yet — just gather the researched material.\n\nRequest: " + request;
        return researcher.respond(List.of(Message.user(brief))).output();
    }

    /**
     * Adversarial verify pass: re-check each cited claim against its source and prefer primary sources,
     * so the writer only sees grounded notes. No-op when no verifier is wired (e.g. no Tavily key).
     */
    private String verify(String findings) {
        if (verifier == null || findings.isBlank()) {
            return findings;
        }
        String brief = "Verify these research notes against their cited sources: drop unsupported or "
                + "conflated claims, and upgrade secondary citations to primary sources where you can. "
                + "Return the cleaned, verified notes.\n\nRESEARCH NOTES:\n" + findings;
        return verifier.respond(List.of(Message.user(brief))).output();
    }

    /** The write turn keeps the original request (so any word-count target still triggers continuation). */
    private List<Message> writePrompt(List<Message> conversation, String findings) {
        String request = Conversations.latestUser(conversation);
        return List.of(Message.user("Write the user's requested piece in full, grounded ONLY in the research "
                + "notes below; preserve their citations and match any requested length.\n\nUSER REQUEST:\n"
                + request + "\n\nRESEARCH NOTES:\n" + findings));
    }
}
