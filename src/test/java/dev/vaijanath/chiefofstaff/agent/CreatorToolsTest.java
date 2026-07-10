package dev.vaijanath.chiefofstaff.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.vaijanath.aiagent.rag.Embedder;
import dev.vaijanath.chiefofstaff.config.CosProperties;
import dev.vaijanath.chiefofstaff.rag.RagStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CreatorTools — hermetic tests using an in-process mock HTTP server (no network/Tavily/yt-dlp). */
class CreatorToolsTest {

    private CosProperties props(Path dataDir) {
        return new CosProperties(null, null, null, null, null, null, null, 0, 0, 0,
                dataDir.toString(), null, null, null, null, null, null);
    }

    private RagStore disabledRag() {
        return new RagStore("jdbc:postgresql://127.0.0.1:1/none", "cos", "cos", (Embedder) null, 384, 0.3);
    }

    private HttpServer server(String path, String contentType, byte[] body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        s.createContext(path, ex -> {
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        s.start();
        return s;
    }

    private String base(HttpServer s, String path) {
        return "http://localhost:" + s.getAddress().getPort() + path;
    }

    @Test
    void saveNoteWritesMarkdownToVault(@TempDir Path tmp) throws Exception {
        CreatorTools tools = new CreatorTools(props(tmp), disabledRag());
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

    @Test
    void readPageExtractsTextAndDiscoversImages(@TempDir Path tmp) throws IOException {
        String html = "<html><body><h1>Hello World</h1><p>Some article text here.</p>"
                + "<img src=\"http://localhost/x.png\" alt=\"Fig A\">"
                + "<img src=\"http://localhost/y.png\" alt=\"Fig B\"></body></html>";
        HttpServer s = server("/page", "text/html", html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            CreatorTools tools = new CreatorTools(props(tmp), disabledRag());
            String out = tools.readPage(base(s, "/page"));
            assertTrue(out.contains("Hello World"), out);
            assertTrue(out.contains("IMAGES DISCOVERED"), out);
            assertTrue(out.contains("http://localhost/x.png"), out);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void fetchImageDownloadsToAssets(@TempDir Path tmp) throws IOException {
        byte[] png = new byte[] {1, 2, 3, 4};
        HttpServer s = server("/i.png", "image/png", png);
        try {
            CreatorTools tools = new CreatorTools(props(tmp), disabledRag());
            String out = tools.fetchImage(base(s, "/i.png"), "fig1");
            assertTrue(out.contains("Saved image"), out);
            Path asset = Files.list(tmp.resolve("vault/creator/assets"))
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("image not saved"));
            assertTrue(Files.readAllBytes(asset).length == 4);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void parseArxivFormatsEntries() {
        String xml = "<feed><entry><title>Title One</title><summary>Abstract one.</summary>"
                + "<id>http://arxiv.org/abs/1234.5678</id></entry>"
                + "<entry><title>Title Two</title><summary>Abstract two.</summary>"
                + "<id>http://arxiv.org/abs/9876.5432</id></entry></feed>";
        String out = CreatorTools.parseArxiv(xml);
        assertTrue(out.contains("Title One"), out);
        assertTrue(out.contains("arxiv: http://arxiv.org/abs/1234.5678"), out);
        assertTrue(out.contains("pdf: http://arxiv.org/pdf/1234.5678"), out);
        assertTrue(out.contains("Title Two"), out);
    }

    @Test
    void parseArxivEmptyWhenNoEntries() {
        assertFalse(CreatorTools.parseArxiv("<feed></feed>").isEmpty());
    }
}
