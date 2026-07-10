package dev.vaijanath.chiefofstaff.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vaijanath.aiagent.rag.Embedder;
import dev.vaijanath.chiefofstaff.config.CosProperties;
import dev.vaijanath.chiefofstaff.rag.RagStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CreatorTools persistence: save_note writes markdown to the vault (RAG index is a no-op when disabled). */
class CreatorToolsTest {

    @Test
    void saveNoteWritesMarkdownToVault(@TempDir Path tmp) throws Exception {
        CosProperties props = new CosProperties(
                null, null, null, null, null, null, null, 0, 0, 0,
                tmp.toString(), null, null, null, null, null, null);
        // RAG store pointed at an unreachable DB is disabled; save_note must still persist the file.
        RagStore rag = new RagStore("jdbc:postgresql://127.0.0.1:1/none", "cos", "cos", (Embedder) null, 384, 0.3);
        CreatorTools tools = new CreatorTools(props, rag);

        String result = tools.saveNote("My Test Note", "# My Test Note\n\nSome content.");
        assertTrue(result.contains("Note saved"), result);

        Path note = Files.list(tmp.resolve("vault/creator"))
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("note not written"));
        String text = Files.readString(note);
        assertTrue(text.contains("# My Test Note"), text);
        assertTrue(text.contains("type: creator"), text);
    }
}
