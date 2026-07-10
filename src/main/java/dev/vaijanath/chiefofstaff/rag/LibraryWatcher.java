package dev.vaijanath.chiefofstaff.rag;

import dev.vaijanath.chiefofstaff.config.CosProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls {@code data/library/<category>/} and ingests new or changed documents into the RAG store (Tika
 * for PDF/DOCX, plain read for text). Ported from watcher.py.
 *
 * <p>Idempotent and non-destructive: a file is (re)ingested only when its modified-time changes since the
 * last scan; upsert-by-chunk-id overwrites its old chunks. The first scan (~3s after startup) picks up
 * everything already there, so this replaces the old startup-only ingester.
 */
@Component
class LibraryWatcher {

    private static final Logger log = LoggerFactory.getLogger(LibraryWatcher.class);
    private static final List<String> CATEGORIES = List.of("idn", "research", "personal", "admin", "inbox");

    private final RagStore rag;
    private final CosProperties props;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    LibraryWatcher(RagStore rag, CosProperties props) {
        this.rag = rag;
        this.props = props;
    }

    @Scheduled(initialDelayString = "3000", fixedDelayString = "${cos.library-poll-ms:10000}")
    void scan() {
        if (!rag.enabled()) {
            return;
        }
        Path library = Path.of(props.dataDir(), "library");
        Set<String> present = new HashSet<>();
        for (String category : CATEGORIES) {
            Path dir = library.resolve(category);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile)
                        .filter(TextExtraction::supported)
                        .forEach(file -> {
                            present.add(file.toAbsolutePath().toString());
                            maybeIngest(file, category);
                        });
            } catch (IOException e) {
                log.warn("[watcher] could not scan {}: {}", dir, e.toString());
            }
        }
        pruneRemoved(present);
    }

    /** Remove embeddings for library files that disappeared since the last scan (renamed/deleted). */
    private void pruneRemoved(Set<String> present) {
        for (String key : new HashSet<>(seen.keySet())) {
            if (!present.contains(key)) {
                String source = Path.of(key).getFileName().toString();
                rag.deleteBySource(source);
                seen.remove(key);
            }
        }
    }

    private void maybeIngest(Path file, String category) {
        try {
            long mtime = Files.getLastModifiedTime(file).toMillis();
            String key = file.toAbsolutePath().toString();
            if (Long.valueOf(mtime).equals(seen.get(key))) {
                return; // unchanged since last ingest
            }
            String type = TextExtraction.ext(file).replaceFirst("^\\.", "");
            int chunks = rag.ingest(category, file.getFileName().toString(), type, TextExtraction.extract(file));
            seen.put(key, mtime);
            log.info("[watcher] ingested {} ({}) -> {} chunks", file.getFileName(), category, chunks);
        } catch (Exception e) {
            log.warn("[watcher] failed {}: {}", file, e.toString());
        }
    }
}
