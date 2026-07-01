package dev.vaijanath.chiefofstaff.rag;

import dev.vaijanath.aiagent.rag.Embedder;
import dev.vaijanath.aiagent.rag.RetrievedChunk;
import dev.vaijanath.aiagent.store.jdbc.ConnectionSource;
import dev.vaijanath.aiagent.store.pgvector.PgVectorRetriever;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RAG store over pgvector, ported from the Python ingest.py + search.py. Everything lives under one
 * tenant with the category in chunk metadata; category and meeting filters are applied in-app (pgvector
 * ranks by cosine distance, no metadata filter in SQL).
 *
 * <p>Degrades gracefully: if Postgres is unreachable at startup, the store is <b>disabled</b> — the app
 * still boots and researcher / notes keep their filesystem tools; RAG search just reports unavailable.
 *
 * <p>ponytail: plain char-window chunking and text/markdown ingestion only. PDF/DOCX via Apache Tika is
 * a follow-up (the {@code extractText} seam is where it plugs in).
 */
public final class RagStore {

    private static final Logger log = LoggerFactory.getLogger(RagStore.class);
    private static final String TENANT = "default";
    private static final int CHUNK_CHARS = 900;
    private static final int CHUNK_OVERLAP = 120;

    private final PgVectorRetriever store; // null when unavailable
    private final boolean enabled;

    public RagStore(String dbUrl, String dbUser, String dbPassword, Embedder embedder, int dimensions) {
        PgVectorRetriever s = null;
        try {
            ConnectionSource connections = () -> DriverManager.getConnection(dbUrl, dbUser, dbPassword);
            s = new PgVectorRetriever(connections, embedder, dimensions);
            s.ensureSchema();
            log.info("[rag] pgvector store ready ({} dims)", dimensions);
        } catch (Exception e) {
            log.warn("[rag] pgvector unavailable ({}). RAG search disabled; researcher/notes still run "
                    + "with filesystem tools.", e.toString());
        }
        this.store = s;
        this.enabled = s != null;
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
        return enabled ? store.retrieve(TENANT, query, topK) : List.of();
    }

    public List<RetrievedChunk> searchByCategory(String query, String category, int topK) {
        if (!enabled) {
            return List.of();
        }
        return store.retrieve(TENANT, query, topK * 4).stream()
                .filter(c -> category.equals(c.metadata().get("category")))
                .limit(topK)
                .toList();
    }

    public List<RetrievedChunk> searchMeetings(String query, int topK) {
        if (!enabled) {
            return List.of();
        }
        return store.retrieve(TENANT, query, topK * 4).stream()
                .filter(c -> "meeting".equals(c.metadata().get("type"))
                        || c.id().toLowerCase().contains("meeting"))
                .limit(topK)
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
