package dev.vaijanath.chiefofstaff.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vaijanath.aiagent.tool.ToolEffect;
import dev.vaijanath.aiagent.tools.annotations.AgentTool;
import dev.vaijanath.aiagent.tools.annotations.ToolParam;
import dev.vaijanath.chiefofstaff.config.CosProperties;
import dev.vaijanath.chiefofstaff.rag.RagStore;
import dev.vaijanath.chiefofstaff.rag.TextExtraction;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * content. Source-specific search tools ({@link #searchArxiv}, {@link #mediumSearch},
 * {@link #youtubeTranscript}) feed URLs into it. {@link #fetchImage} grounds images by downloading them
 * and returning only the local path the note may embed; {@link #saveNote} persists the finished note to
 * the vault, grounds its image references, and indexes it.
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
    private static final Pattern MD_IMG = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern ENTRY = Pattern.compile("<entry>([\\s\\S]*?)</entry>");
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final String TRACKING = "pixel|tracking|analytics|beacon|1x1";

    private static final String NOTHING = "NO CONTENT FOUND at that URL (unreachable, blocked, or not text). "
            + "Tell the user you couldn't read it and try another source — do NOT invent its content.";

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper json = new ObjectMapper();
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
            String text = TIKA.parseToString(new ByteArrayInputStream(bytes)).strip();
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
            name = "search_arxiv",
            description = "Search Arxiv papers by keyword; returns titles, arxiv links, and PDF URLs. Use "
                    + "read_pdf on a PDF URL (or read_page on the abstract) to read the full paper.",
            effect = ToolEffect.READ_ONLY)
    public String searchArxiv(
            @ToolParam(description = "Search query") String query) {
        try {
            String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://export.arxiv.org/api/query?search_query=all:" + q
                    + "&start=0&max_results=5&sortBy=submittedDate&sortOrder=descending";
            String xml = new String(get(url, "application/atom+xml, application/xml, text/xml"), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            Matcher e = ENTRY.matcher(xml);
            int i = 1;
            while (e.find() && i <= 5) {
                String block = e.group(1);
                String title = tag(block, "title");
                String summary = tag(block, "summary");
                String id = tag(block, "id");
                String pdf = id.replace("/abs/", "/pdf/");
                sb.append(i++).append(". ").append(title).append("\n   arxiv: ").append(id)
                        .append("\n   pdf: ").append(pdf).append("\n   ")
                        .append(summary.length() > 500 ? summary.substring(0, 500) : summary).append("\n\n");
            }
            return sb.length() == 0 ? "No Arxiv results for that query." : sb.toString();
        } catch (Exception ex) {
            return "Arxiv search failed (" + ex.getMessage() + ").";
        }
    }

    @AgentTool(
            name = "medium_search",
            description = "Search Medium articles (scoped web search via Tavily). Returns titles, URLs, and "
                    + "snippets. Use read_page on a URL to read the article. Needs TAVILY_API_KEY.",
            effect = ToolEffect.READ_ONLY)
    public String mediumSearch(
            @ToolParam(description = "Search query") String query) {
        if (!props.hasTavily()) {
            return "Medium search needs TAVILY_API_KEY (not set). Use web_search instead.";
        }
        return tavilySearch(query, List.of("medium.com"));
    }

    @AgentTool(
            name = "youtube_transcript",
            description = "Fetch the transcript of a YouTube video as text (via yt-dlp). Use for video sources. "
                    + "Requires yt-dlp installed on the PATH.",
            effect = ToolEffect.READ_ONLY)
    public String youtubeTranscript(
            @ToolParam(description = "YouTube video URL") String url) {
        if (!isHttp(url)) {
            return "Refused: only http(s) URLs are allowed.";
        }
        try {
            Path tmp = Files.createTempDirectory("creator-yt");
            int code = run(new ProcessBuilder("yt-dlp", "--write-auto-subs", "--sub-langs", "en*",
                    "--skip-download", "-o", tmp.resolve("v").toString(), url));
            Path vtt = Files.list(tmp).filter(f -> f.toString().endsWith(".vtt")).findFirst().orElse(null);
            if (vtt != null) {
                String text = vttToText(vtt);
                if (!text.isBlank()) {
                    return text.length() > 8000 ? text.substring(0, 8000) : text;
                }
            }
            // Fallback: at least recover the title.
            String title = runJsonTitle(new ProcessBuilder("yt-dlp", "--dump-json", url));
            return "No transcript available for this video (exit " + code + ")."
                    + (title.isEmpty() ? "" : " Title: " + title);
        } catch (Exception e) {
            return "Could not get YouTube transcript (" + e.getMessage() + "). Is yt-dlp installed?";
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
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .header("Accept", "image/*")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "Could not fetch image (HTTP " + response.statusCode() + "). Skip it.";
            }
            byte[] bytes = response.body();
            if (bytes.length > MAX_IMAGE_BYTES) {
                return "Image too large (" + (bytes.length / 1024 / 1024) + " MB); skipped to avoid bloat.";
            }
            String ctype = response.headers().firstValue("content-type").orElse("");
            String ext = extFrom(url, ctype, bytes);
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
                    + "with the complete note. Image references are grounded: any ![](assets/...) path that was "
                    + "not actually fetched is dropped so the saved note never links to a missing image.",
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
            String body = front + markdown;
            body = groundImages(body, dir.resolve("assets"));
            Files.writeString(file, body);
            int chunks = rag.ingest("creator", file.getFileName().toString(), "creator", body);
            return "✅ Note saved to " + file + " and indexed (" + chunks + " chunks).";
        } catch (Exception e) {
            return "❌ Could not save note: " + e.getMessage();
        }
    }

    // --- internals ---

    private byte[] get(String url, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", accept)
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String tavilySearch(String query, List<String> includeDomains) {
        try {
            ObjectMapper om = this.json;
            com.fasterxml.jackson.databind.node.ObjectNode req = om.createObjectNode();
            req.put("api_key", props.tavilyApiKey());
            req.put("query", query);
            req.put("max_results", 5);
            req.put("search_depth", "advanced");
            com.fasterxml.jackson.databind.node.ArrayNode dom = req.putArray("include_domains");
            includeDomains.forEach(dom::add);
            HttpRequest r = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.tavily.com/search"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(req)))
                    .build();
            HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
            JsonNode root = om.readTree(resp.body());
            StringBuilder sb = new StringBuilder();
            JsonNode results = root.get("results");
            if (results != null) {
                int i = 1;
                for (JsonNode it : results) {
                    String content = it.path("content").asText();
                    sb.append(i++).append(". ").append(it.path("title").asText()).append("\n   ")
                            .append(it.path("url").asText()).append("\n   ")
                            .append(content.length() > 400 ? content.substring(0, 400) : content).append("\n\n");
                }
            }
            return sb.length() == 0 ? "No results." : sb.toString();
        } catch (Exception e) {
            return "Medium search failed (" + e.getMessage() + ").";
        }
    }

    private int run(ProcessBuilder pb) throws IOException, InterruptedException {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    private String runJsonTitle(ProcessBuilder pb) {
        try {
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            int i = out.indexOf("\"title\":");
            if (i < 0) {
                return "";
            }
            int q1 = out.indexOf('"', i + 8);
            int q2 = out.indexOf('"', q1 + 1);
            return q1 >= 0 && q2 > q1 ? out.substring(q1 + 1, q2) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String vttToText(Path vtt) throws IOException {
        List<String> lines = Files.readAllLines(vtt);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("WEBVTT") || t.startsWith("NOTE") || t.contains("-->")
                    || t.matches("\\d+")) {
                continue;
            }
            sb.append(t).append(" ");
        }
        return sb.toString().strip();
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
            if (src.toLowerCase(Locale.ROOT).matches(".*(" + TRACKING + ").*")) {
                continue; // skip tracking pixels / beacons
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

    private static String tag(String block, String name) {
        Matcher m = Pattern.compile("<" + name + ">([\\s\\S]*?)</" + name + ">", Pattern.CASE_INSENSITIVE)
                .matcher(block);
        return m.find() ? m.group(1).replaceAll("\\s+", " ").strip() : "";
    }

    /** Replace any ![](assets/...) reference whose file isn't actually present with a comment. */
    private static String groundImages(String markdown, Path assetsDir) {
        Matcher m = MD_IMG.matcher(markdown);
        StringBuffer sb = new StringBuffer();
        int dropped = 0;
        while (m.find()) {
            String path = m.group(2).strip();
            if (path.startsWith("assets/") && !Files.exists(assetsDir.resolve(path.substring("assets/".length())))) {
                m.appendReplacement(sb, "<!-- image not fetched, removed: " + path + " -->");
                dropped++;
            } else {
                m.appendReplacement(sb, m.group(0));
            }
        }
        m.appendTail(sb);
        return dropped == 0 ? markdown : sb.toString();
    }

    private static String extFrom(String url, String contentType, byte[] bytes) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("png")) {
            return ".png";
        }
        if (ct.contains("jpeg") || ct.contains("jpg")) {
            return ".jpg";
        }
        if (ct.contains("gif")) {
            return ".gif";
        }
        if (ct.contains("webp")) {
            return ".webp";
        }
        if (ct.contains("svg")) {
            return ".svg";
        }
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
