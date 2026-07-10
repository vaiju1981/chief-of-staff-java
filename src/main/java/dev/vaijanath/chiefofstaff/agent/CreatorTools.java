package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.rag.RetrievedChunk;
import dev.vaijanath.aiagent.tool.ToolEffect;
import dev.vaijanath.aiagent.tools.annotations.AgentTool;
import dev.vaijanath.aiagent.tools.annotations.ToolParam;
import dev.vaijanath.chiefofstaff.config.CosProperties;
import dev.vaijanath.chiefofstaff.rag.RagStore;
import dev.vaijanath.chiefofstaff.rag.TextExtraction;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/**
 * Retrieval + persistence tools for the Creator (content) agent. Plain {@code @AgentTool} Java methods
 * (no npx/MCP) so they are pinnable and safe. The workhorse is {@link #readPage}: it deep-reads any URL
 * into clean text and discovers its images, so the agent can go from a search result link to real
 * content. {@link #fetchImage} grounds images by downloading them and returning only the local path the
 * note may embed; {@link #saveNote} persists the finished note to the vault and indexes it.
 */
@Component
public class CreatorTools {

    private static final Tika TIKA = new Tika();

    static {
        TIKA.setMaxStringLength(-1);
    }

    private static final Pattern TAG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC = Pattern.compile("\\bsrc\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_SRC =
            Pattern.compile("\\bdata-src\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT = Pattern.compile("\\balt\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final String NOTHING = "NO CONTENT FOUND at that URL (unreachable, blocked, or not text). "
            + "Tell the user you couldn't read it and try another source — do NOT invent its content.";

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final CosProperties props;
    private final RagStore rag;

    public CreatorTools(CosProperties props, RagStore rag) {
        this.props = props;
        this.rag = rag;
    }

    @AgentTool(
            name = "read_page",
            description = "Fetch a web page URL and return its cleaned text plus any images discovered on it "
                    + "(with URLs + alt text). Use this to deep-read the links returned by web_search and the "
                    + "source-specific searches.",
            effect = ToolEffect.READ_ONLY)
    public String readPage(
            @ToolParam(description = "Absolute http(s) URL of the page to read") String url) {
        if (!isHttp(url)) {
            return "Refused: only http(s) URLs are readable.";
        }
        try {
            byte[] bytes = get(url, "text/html,application/xhtml+xml");
            String text = TIKA.parseToString(new java.io.ByteArrayInputStream(bytes)).strip();
            List<ImageRef> images = imagesOn(new String(bytes), url);
            StringBuilder sb = new StringBuilder();
            sb.append(text.isBlank() ? "(no readable text extracted)" : text);
            if (!images.isEmpty()) {
                sb.append("\n\nIMAGES DISCOVERED (use fetch_image with these URLs to embed them):\n");
                int i = 1;
                for (ImageRef img : images) {
                    sb.append(i++).append(". [").append(img.alt()).append("] ").append(img.url()).append('\n');
                }
            }
            return sb.toString().length() > 8000 ? sb.substring(0, 8000) : sb.toString();
        } catch (Exception e) {
            return NOTHING + " (" + e.toString() + ")";
        }
    }

    @AgentTool(
            name = "read_pdf",
            description = "Read a PDF: pass an http(s) URL (downloaded then parsed) or an absolute local path. "
                    + "Returns the extracted text — for papers and local documents.",
            effect = ToolEffect.READ_ONLY)
    public String readPdf(
            @ToolParam(description = "PDF URL or absolute local path") String location) {
        try {
            Path file;
            if (isHttp(location)) {
                Path tmp = Files.createTempFile("creator-pdf-", ".pdf");
                Files.write(tmp, get(location, "application/pdf"));
                file = tmp;
            } else {
                file = Path.of(location);
            }
            String text = TextExtraction.extract(file).strip();
            if (text.isBlank()) {
                return "(no text extracted from PDF)";
            }
            return text.length() > 8000 ? text.substring(0, 8000) : text;
        } catch (Exception e) {
            return "NO CONTENT FOUND in that PDF (" + e.toString() + "). Do NOT invent its content.";
        }
    }

    @AgentTool(
            name = "fetch_image",
            description = "Download an image URL into the note's assets folder and return the RELATIVE path "
                    + "(assets/<file>) to embed in markdown. Only call this with URLs discovered by read_page.",
            effect = ToolEffect.EFFECTFUL)
    public String fetchImage(
            @ToolParam(description = "Absolute http(s) image URL") String url,
            @ToolParam(description = "File name without extension, e.g. architecture", required = false)
                    String name) {
        if (!isHttp(url)) {
            return "Refused: only http(s) image URLs are allowed.";
        }
        try {
            byte[] bytes = get(url, "image/*");
            String ext = extFrom(url, bytes);
            String base = (name == null || name.isBlank()) ? "img" : slugify(name);
            Path assets = Path.of(props.dataDir(), "vault", "creator", "assets");
            Files.createDirectories(assets);
            Path out = unique(assets, base + ext);
            Files.write(out, bytes);
            String rel = "assets/" + out.getFileName();
            return "Saved image → " + rel + " (embed in markdown as ![](assets/" + out.getFileName() + "))";
        } catch (Exception e) {
            return "Could not fetch image (" + e.toString() + "). Skip it; do not embed a broken URL.";
        }
    }

    @AgentTool(
            name = "save_note",
            description = "Save the finished note (markdown) to the vault and index it for search. Call this once "
                    + "with the complete note.",
            effect = ToolEffect.EFFECTFUL)
    public String saveNote(
            @ToolParam(description = "Note title") String title,
            @ToolParam(description = "Full markdown note following the required schema") String markdown) {
        try {
            Path dir = Path.of(props.dataDir(), "vault", "creator");
            Files.createDirectories(dir);
            String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            String slug = slugify(title);
            Path file = dir.resolve(date + "_" + slug + ".md");
            String front = "---\ndate: " + date + "\ntype: creator\ntags: [creator, note]\n---\n\n";
            Files.writeString(file, front + markdown);
            int chunks = rag.ingest("creator", file.getFileName().toString(), "creator", front + markdown);
            return "✅ Note saved to " + file + " and indexed (" + chunks + " chunks).";
        } catch (Exception e) {
            return "❌ Could not save note: " + e.getMessage();
        }
    }

    // --- internals ---

    private byte[] get(String url, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Accept", accept)
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static boolean isHttp(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private List<ImageRef> imagesOn(String html, String base) {
        List<ImageRef> out = new ArrayList<>();
        if (html == null) {
            return out;
        }
        Matcher m = TAG.matcher(html);
        while (m.find()) {
            String tag = m.group();
            String src = first(SRC, tag);
            if (src == null) {
                src = first(DATA_SRC, tag);
            }
            if (src == null || src.isBlank() || src.startsWith("data:")) {
                continue;
            }
            out.add(new ImageRef(resolve(base, src), first(ALT, tag)));
        }
        return out;
    }

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String resolve(String base, String src) {
        try {
            return URI.create(base).resolve(src).toString();
        } catch (Exception e) {
            return src;
        }
    }

    private static String extFrom(String url, byte[] bytes) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains(".png")) {
            return ".png";
        }
        if (u.contains(".jpg") || u.contains(".jpeg")) {
            return ".jpg";
        }
        if (u.contains(".gif")) {
            return ".gif";
        }
        if (u.contains(".webp")) {
            return ".webp";
        }
        if (u.contains(".svg")) {
            return ".svg";
        }
        return ".img";
    }

    private static Path unique(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) {
            return p;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 2;
        while (Files.exists(p)) {
            p = dir.resolve(base + "-" + i++ + ext);
        }
        return p;
    }

    private static String slugify(String text) {
        String s = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s-]", "")
                .replaceAll("[\\s-]+", "-")
                .replaceAll("^-|-$", "");
        return s.isBlank() ? "note" : s.substring(0, Math.min(s.length(), 60));
    }

    private record ImageRef(String url, String alt) {}
}
