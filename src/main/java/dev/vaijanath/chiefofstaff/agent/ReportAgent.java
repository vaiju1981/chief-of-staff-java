package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.model.Message;
import java.util.List;
import java.util.function.Consumer;

/**
 * A research→write pipeline: the {@code researcher} gathers cited findings (web + the user's documents),
 * then a {@code writer} composes a long-form, grounded report from them. This is the one path that does
 * <b>web search AND a long report</b> — and it streams, because the write phase uses the streaming
 * {@link GenerationAgent} (the research phase is non-streaming but covered by the SSE keep-alive).
 */
public final class ReportAgent implements ChatAgent {

    private final ChatAgent researcher;
    private final ChatAgent writer;

    public ReportAgent(ChatAgent researcher, ChatAgent writer) {
        this.researcher = researcher;
        this.writer = writer;
    }

    @Override
    public AgentResponse respond(List<Message> conversation) {
        return writer.respond(writePrompt(conversation, gather(conversation)));
    }

    @Override
    public AgentResponse respondStreaming(List<Message> conversation, Consumer<String> onToken) {
        String findings = gather(conversation); // non-streaming; the SSE heartbeat covers this phase
        return writer.respondStreaming(writePrompt(conversation, findings), onToken);
    }

    private String gather(List<Message> conversation) {
        String request = Conversations.latestUser(conversation);
        String brief = "Research this thoroughly using web search and the indexed documents. Produce "
                + "detailed, organized notes, and cite a source (URL or filename) for every claim. Do NOT "
                + "write the final prose piece yet — just gather the researched material.\n\nRequest: " + request;
        return researcher.respond(List.of(Message.user(brief))).output();
    }

    /** The write turn keeps the original request (so any word-count target still triggers continuation). */
    private List<Message> writePrompt(List<Message> conversation, String findings) {
        String request = Conversations.latestUser(conversation);
        return List.of(Message.user("Write the user's requested piece in full, grounded ONLY in the research "
                + "notes below; preserve their citations and match any requested length.\n\nUSER REQUEST:\n"
                + request + "\n\nRESEARCH NOTES:\n" + findings));
    }
}
