package dev.vaijanath.chiefofstaff.rag;

import dev.vaijanath.aiagent.rag.Embedder;
import dev.vaijanath.aiagent.rag.RetrievedChunk;
import dev.vaijanath.aiagent.store.jdbc.ConnectionSource;
import dev.vaijanath.aiagent.store.pgvector.PgVectorRetriever;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAG store over pgvector, ported from the Python ingest.py + search.py. Everything lives under one
 * tenant with the category in chunk metadata; category and meeting filters are applied in-app.
 *
 * <p><b>Grounding gate:</b> pgvector always returns its top-k, even when every match is weak — which lets
 * an agent dress up an irrelevant chunk as "grounding". So results below {@code minScore} cosine
 * similarity are dropped; when nothing clears the bar the search returns empty, and the agent is told
 * (imperatively, in {@link RagTools}) to say it isn't in the user's documents rather than invent an answer.
 *
 * <p>Degrades gracefully: if Postgres is unreachable at startup the store is disabled and RAG search
 * reports unavailable, but the app still boots.
 */
public final class RagStore {

    private static final Logger log = LoggerFactory.getLogger(RagStore.class);
    private static final String TENANT = "default";
    private static final int CHUNK_CHARS = 900;
    private static final int CHUNK_OVERLAP = 120;

    private final PgVectorRetriever store; // null when unavailable
    private final ConnectionSource connections; // reused for direct deletes (orphan cleanup)
    private final boolean enabled;
    private final double minScore;

    public RagStore(
            String dbUrl, String dbUser, String dbPassword, Embedder embedder, int dimensions, double minScore) {
        this.minScore = minScore;
        ConnectionSource source = () -> DriverManager.getConnection(dbUrl, dbUser, dbPassword);
        PgVectorRetriever s = null;
        try {
            s = new PgVectorRetriever(source, embedder, dimensions);
            s.ensureSchema();
            log.info("[rag] pgvector store ready ({} dims, min-score {})", dimensions, minScore);
        } catch (Exception e) {
            log.warn("[rag] pgvector unavailable ({}). RAG search disabled; researcher/notes still run "
                    + "with filesystem tools.", e.toString());
            source = null;
        }
        this.store = s;
        this.connections = source;
        this.enabled = s != null;
    }

    /** Drop every chunk for a source (by filename) — used to prune embeddings of deleted/renamed files. */
    public int deleteBySource(String source) {
        if (!enabled || connections == null) {
            return 0;
        }
        try (Connection c = connections.get();
                Statement st = c.createStatement()) {
            int n = st.executeUpdate("DELETE FROM rag_vectors WHERE tenant = '" + TENANT
                    + "' AND metadata->>'source' = '" + source.replace("'", "''") + "'");
            if (n > 0) {
                log.info("[rag] deleted {} chunk(s) for source {}", n, source);
            }
            return n;
        } catch (Exception e) {
            log.warn("[rag] deleteBySource failed for {}: {}", source, e.toString());
            return 0;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** Chunk and upsert a document under a category. Returns the number of chunks written. */
    public int ingest(String category, String source, String type, String text) {
        if (!enabled) {
            return 0;
        }
        List<String> chunks = chunk(text);
        Map<String, String> metadata = Map.of("source", source, "category", category, "type", type);
        for (int i = 0; i < chunks.size(); i++) {
            store.add(TENANT, source + "#" + i, chunks.get(i), metadata);
        }
        return chunks.size();
    }

    public List<RetrievedChunk> search(String query, int topK) {
        return relevant(query, topK * 2).stream().limit(topK).toList();
    }

    public List<RetrievedChunk> searchByCategory(String query, String category, int topK) {
        return relevant(query, topK * 4).stream()
                .filter(c -> category.equals(c.metadata().get("category")))
                .limit(topK)
                .toList();
    }

    public List<RetrievedChunk> searchMeetings(String query, int topK) {
        return relevant(query, topK * 4).stream()
                .filter(c -> "meeting".equals(c.metadata().get("type"))
                        || c.id().toLowerCase().contains("meeting"))
                .limit(topK)
                .toList();
    }

    /** Retrieve, then keep only chunks at or above the similarity floor (the grounding gate). */
    private List<RetrievedChunk> relevant(String query, int limit) {
        if (!enabled) {
            return List.of();
        }
        return store.retrieve(TENANT, query, limit).stream()
                .filter(c -> c.score() >= minScore)
                .toList();
    }

    /** Fixed char-window chunking with overlap (Python used 512-token / 64-overlap sentence chunks). */
    private static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return chunks;
        }
        int i = 0;
        while (i < t.length()) {
            int end = Math.min(t.length(), i + CHUNK_CHARS);
            chunks.add(t.substring(i, end));
            if (end == t.length()) {
                break;
            }
            i = end - CHUNK_OVERLAP;
        }
        return chunks;
    }
}
