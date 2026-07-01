package dev.vaijanath.chiefofstaff.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.tika.Tika;

/**
 * Extracts plain text from a document: a plain read for text/markdown, Apache Tika (PDFBox / POI) for
 * binary formats (PDF / DOCX / PPTX / HTML / XLSX). Replaces the Python Docling step.
 */
public final class TextExtraction {

    private static final Set<String> PLAIN = Set.of(".md", ".markdown", ".txt");
    private static final Set<String> BINARY =
            Set.of(".pdf", ".docx", ".doc", ".pptx", ".ppt", ".html", ".htm", ".xlsx");
    private static final Tika TIKA = new Tika();

    static {
        TIKA.setMaxStringLength(-1); // no cap — extract the whole document
    }

    private TextExtraction() {}

    public static boolean supported(Path file) {
        String ext = ext(file);
        return PLAIN.contains(ext) || BINARY.contains(ext);
    }

    public static String extract(Path file) throws IOException {
        if (PLAIN.contains(ext(file))) {
            return Files.readString(file);
        }
        try {
            return TIKA.parseToString(file);
        } catch (Exception e) {
            throw new IOException("could not extract text from " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** The file's lowercase extension including the dot, or "" if none. */
    static String ext(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }
}
