package dev.vaijanath.chiefofstaff.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.vaijanath.aiagent.mcp.McpTools;
import dev.vaijanath.aiagent.tool.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Starts the MCP servers (via {@code npx}) and exposes their tools as java-ai-agent {@link Tool}s.
 *
 * <p>Currently wires the <b>filesystem</b> server (no token needed), rooted at the data directory so
 * researcher / notes can list and read files. Each server is started in its own subprocess; a server
 * that fails to start is logged and skipped, so the app still boots. Clients are closed on shutdown.
 *
 * <p>ponytail: github / tavily are token-gated and can't be exercised without keys, so they're deferred
 * (see the commented block) until a token is present and the transport's env API is verified live.
 */
@Component
class McpToolSource implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpToolSource.class);

    private final List<McpClient> clients = new ArrayList<>();
    private final Map<String, Tool> byName = new LinkedHashMap<>();

    McpToolSource(CosProperties props) {
        Path dataDir = Path.of(props.dataDir()).toAbsolutePath();
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            log.warn("[mcp] could not create data dir {}: {}", dataDir, e.toString());
        }
        addServer("filesystem",
                List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", dataDir.toString()), Map.of());
        if (props.hasGithubToken()) {
            addServer("github", List.of("npx", "-y", "@modelcontextprotocol/server-github"),
                    Map.of("GITHUB_PERSONAL_ACCESS_TOKEN", props.githubToken()));
        }
        if (props.hasTavily()) {
            addServer("tavily", List.of("npx", "-y", "tavily-mcp@latest"),
                    Map.of("TAVILY_API_KEY", props.tavilyApiKey()));
        }
    }

    private void addServer(String name, List<String> command, Map<String, String> env) {
        try {
            StdioMcpTransport.Builder builder = new StdioMcpTransport.Builder().command(command).logEvents(false);
            if (!env.isEmpty()) {
                builder.environment(env);
            }
            McpTransport transport = builder.build();
            McpClient client = new DefaultMcpClient.Builder()
                    .transport(transport)
                    .key(name)
                    .initializationTimeout(Duration.ofSeconds(90))
                    .toolExecutionTimeout(Duration.ofSeconds(60))
                    .build();
            List<Tool> tools = McpTools.from(client);
            clients.add(client);
            for (Tool tool : tools) {
                byName.put(tool.name(), tool);
            }
            log.info("[mcp] {} server: {} tools {}", name, tools.size(), tools.stream().map(Tool::name).toList());
        } catch (Exception e) {
            log.warn("[mcp] {} server failed to start ({}); its tools are unavailable", name, e.toString());
        }
    }

    /** The advertised tools whose names are requested (silently skips any not advertised). */
    List<Tool> select(String... names) {
        List<Tool> selected = new ArrayList<>();
        for (String name : names) {
            Tool tool = byName.get(name);
            if (tool != null) {
                selected.add(tool);
            }
        }
        return selected;
    }

    @Override
    public void close() {
        for (McpClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("[mcp] error closing client: {}", e.toString());
            }
        }
    }
}
