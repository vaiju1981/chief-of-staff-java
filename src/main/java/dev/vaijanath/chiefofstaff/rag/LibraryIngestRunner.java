package dev.vaijanath.chiefofstaff.rag;

import dev.vaijanath.chiefofstaff.config.CosProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * On startup, ingest text / markdown files under {@code data/library/<category>/} into the RAG store.
 * Upsert-by-chunk-id makes it idempotent across restarts. Mirrors the Python watcher's intent; a
 * continuous poll-based watcher (and PDF/DOCX via Tika) are follow-ups.
 */
@Component
class LibraryIngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LibraryIngestRunner.class);
    private static final List<String> CATEGORIES = List.of("idn", "research", "personal", "admin", "inbox");

    private final RagStore rag;
    private final CosProperties props;

    LibraryIngestRunner(RagStore rag, CosProperties props) {
        this.rag = rag;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!rag.enabled()) {
            return;
        }
        Path library = Path.of(props.dataDir(), "library");
        int total = 0;
        for (String category : CATEGORIES) {
            Path dir = library.resolve(category);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                total += files.filter(Files::isRegularFile)
                        .filter(LibraryIngestRunner::isText)
                        .mapToInt(file -> ingest(file, category))
                        .sum();
            } catch (IOException e) {
                log.warn("[ingest] could not scan {}: {}", dir, e.toString());
            }
        }
        if (total > 0) {
            log.info("[ingest] indexed {} chunks from data/library on startup", total);
        }
    }

    private int ingest(Path file, String category) {
        try {
            int chunks = rag.ingest(category, file.getFileName().toString(), "text", Files.readString(file));
            log.info("[ingest] {} ({}) -> {} chunks", file.getFileName(), category, chunks);
            return chunks;
        } catch (Exception e) {
            log.warn("[ingest] failed {}: {}", file, e.toString());
            return 0;
        }
    }

    private static boolean isText(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".markdown");
    }
}
