package dev.vaijanath.chiefofstaff.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vaijanath.aiagent.rag.Embedder;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RAG store behaviour when Postgres/pgvector is unavailable: it must degrade gracefully (disabled) and
 * every read/delete path must be a safe no-op, so the rest of the app still boots and serves the other
 * agents. This also exercises the grounding gate returning empty rather than weak matches.
 */
class RagStoreTest {

    /** A store pointed at an unreachable database is disabled, not throwing. */
    private RagStore disabled() {
        return new RagStore(
                "jdbc:postgresql://127.0.0.1:1/does_not_exist",
                "cos", "cos_local_dev",
                (Embedder) null, 384, 0.3);
    }

    @Test
    void disabledWhenDatabaseUnreachable() {
        assertFalse(disabled().enabled());
    }

    @Test
    void readsAreEmptyWhenDisabled() {
        RagStore rag = disabled();
        assertTrue(rag.search("anything", 5).isEmpty());
        assertTrue(rag.searchByCategory("anything", "research", 5).isEmpty());
        assertTrue(rag.searchMeetings("anything", 5).isEmpty());
    }

    @Test
    void deletesAreNoOpsWhenDisabled() {
        assertEquals(0, disabled().deleteBySource("note.md"));
    }
}
