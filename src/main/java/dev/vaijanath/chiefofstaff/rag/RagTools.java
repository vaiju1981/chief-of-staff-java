package dev.vaijanath.chiefofstaff.rag;

import dev.vaijanath.aiagent.rag.RetrievedChunk;
import dev.vaijanath.aiagent.tool.ToolEffect;
import dev.vaijanath.aiagent.tools.annotations.AgentTool;
import dev.vaijanath.aiagent.tools.annotations.ToolParam;
import java.util.List;
import java.util.Set;

/**
 * RAG search tools for the researcher / notes agents, ported from the Python {@code @tool} functions.
 *
 * <p>Marked {@link ToolEffect#READ_ONLY} so the framework's default {@code denyEffectful} policy still
 * lets them run without an allow-list.
 */
public final class RagTools {

    private static final Set<String> CATEGORIES = Set.of("idn", "research", "personal", "admin", "inbox");

    /** Grounding gate: what a tool returns when nothing clears the similarity floor. */
    private static final String NOTHING = "NO MATCHING DOCUMENTS. This topic is not in the user's indexed "
            + "documents. Tell the user you could not find it in their documents, and do NOT answer it from "
            + "general knowledge.";

    private final RagStore rag;

    public RagTools(RagStore rag) {
        this.rag = rag;
    }

    @AgentTool(
            name = "search_local_documents",
            description = "Global semantic search across the entire indexed document library. Use for "
                    + "questions grounded in the user's papers, notes, and documents.",
            effect = ToolEffect.READ_ONLY)
    public String searchLocalDocuments(
            @ToolParam(description = "Natural-language query") String query,
            @ToolParam(description = "Number of chunks (default 5)", required = false) Integer topK) {
        return format(rag.search(query, topKOr(topK)));
    }

    @AgentTool(
            name = "search_by_category",
            description = "Semantic search within one library category: idn, research, personal, admin, inbox.",
            effect = ToolEffect.READ_ONLY)
    public String searchByCategory(
            @ToolParam(description = "Natural-language query") String query,
            @ToolParam(description = "One of: idn, research, personal, admin, inbox") String category,
            @ToolParam(description = "Number of chunks (default 5)", required = false) Integer topK) {
        if (category == null || !CATEGORIES.contains(category)) {
            return "Invalid category '" + category + "'. Must be one of: " + CATEGORIES;
        }
        return format(rag.searchByCategory(query, category, topKOr(topK)));
    }

    @AgentTool(
            name = "search_meetings",
            description = "Semantic search over indexed meeting notes. Use for questions about meetings.",
            effect = ToolEffect.READ_ONLY)
    public String searchMeetings(
            @ToolParam(description = "Keywords or a natural-language question") String query,
            @ToolParam(description = "Number of results (default 5)", required = false) Integer topK) {
        return format(rag.searchMeetings(query, topKOr(topK)));
    }

    private static int topKOr(Integer topK) {
        return topK == null || topK <= 0 ? 5 : Math.min(topK, 20);
    }

    private static String format(List<RetrievedChunk> hits) {
        if (hits.isEmpty()) {
            return NOTHING;
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (RetrievedChunk h : hits) {
            String source = h.metadata().getOrDefault("source", h.id());
            String category = h.metadata().getOrDefault("category", "?");
            sb.append('[').append(i++).append("] ").append(source)
                    .append(" (category: ").append(category).append(')')
                    .append(String.format(" (score %.2f)%n", h.score()));
            String text = h.text();
            sb.append(text.length() > 500 ? text.substring(0, 500) : text).append("\n\n---\n\n");
        }
        return sb.toString().strip();
    }
}
