package dev.vaijanath.chiefofstaff.config;

import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.agent.DefaultAgent;
import dev.vaijanath.aiagent.langchain4j.OllamaModelPorts;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.StructuredOutput;
import dev.vaijanath.aiagent.rag.Embedder;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolApprovers;
import dev.vaijanath.aiagent.tools.annotations.ReflectiveTools;
import dev.vaijanath.chiefofstaff.agent.Handoff;
import dev.vaijanath.chiefofstaff.agent.Supervisor;
import dev.vaijanath.chiefofstaff.prompt.CosPrompts;
import dev.vaijanath.chiefofstaff.rag.RagStore;
import dev.vaijanath.chiefofstaff.rag.RagTools;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the model, the RAG store, and the registry of agents exposed as OpenAI-style "models".
 *
 * <p>The supervisor ({@code agent-chief-of-staff}) routes to bare-id specialists; each is also exposed
 * directly as {@code agent-<id>}. Tool-using specialists (researcher, notes) get RAG + MCP filesystem
 * tools; comms / code / handoff are tool-less. Add to the {@code specialists} map to grow the system.
 */
@Configuration
class AgentConfig {

    @Bean
    ModelPort modelPort(CosProperties props) {
        return OllamaModelPorts.ollama(props.ollamaBaseUrl(), props.model());
    }

    @Bean
    RagStore ragStore(CosProperties props) {
        Embedder embedder = OllamaModelPorts.ollamaEmbedder(props.ollamaBaseUrl(), props.embeddingModel());
        return new RagStore(
                props.dbUrl(), props.dbUser(), props.dbPassword(), embedder, props.embeddingDimensions());
    }

    @Bean
    Map<String, Agent> agents(ModelPort model, CosProperties props, RagStore rag, McpToolSource mcp) {
        List<Tool> ragTools = ReflectiveTools.from(new RagTools(rag));
        String dataDir = Path.of(props.dataDir()).toAbsolutePath().toString();
        String vaultDir = Path.of(props.dataDir(), "vault").toAbsolutePath().toString();

        Map<String, Agent> specialists = new LinkedHashMap<>();
        specialists.put(
                "comms", DefaultAgent.builder().model(model).systemPrompt(CosPrompts.comms()).build());
        specialists.put(
                "code", DefaultAgent.builder().model(model).systemPrompt(CosPrompts.code()).build());
        specialists.put("handoff", new Handoff(model));

        // Researcher: library RAG + filesystem read.
        specialists.put("researcher", toolAgent(model, CosPrompts.researcher(dataDir),
                concat(only(ragTools, "search_local_documents", "search_by_category"),
                        mcp.select("list_directory", "read_text_file"))));

        // Notes: meeting RAG + filesystem exploration of the vault.
        specialists.put("notes", toolAgent(model, CosPrompts.notes(vaultDir),
                concat(only(ragTools, "search_meetings"),
                        mcp.select("list_directory", "read_text_file", "search_files", "directory_tree"))));

        StructuredOutput router = OllamaModelPorts.ollamaStructured(props.ollamaBaseUrl(), props.model());
        Supervisor supervisor = new Supervisor(model, router, specialists);

        Map<String, Agent> registry = new LinkedHashMap<>();
        registry.put("agent-chief-of-staff", supervisor);
        specialists.forEach((id, agent) -> registry.put("agent-" + id, agent));
        return registry;
    }

    /**
     * A tool-using DefaultAgent with {@code allowAll} approval — matching the Python's no-auth tools.
     * The RAG tools are READ_ONLY (would run under the default policy anyway), but MCP tools arrive
     * EFFECTFUL, so without this they'd be denied.
     *
     * <p>ponytail: allowAll is dev-grade. Replace with a per-tool allow-list once an effectful tool
     * (e.g. GitHub create_issue) is added, so writes require an explicit opt-in.
     */
    private static Agent toolAgent(ModelPort model, String systemPrompt, List<Tool> tools) {
        DefaultAgent.Builder builder = DefaultAgent.builder()
                .model(model)
                .systemPrompt(systemPrompt)
                .toolApprover(ToolApprovers.allowAll());
        tools.forEach(builder::tool);
        return builder.build();
    }

    private static List<Tool> only(List<Tool> tools, String... names) {
        Set<String> wanted = Set.of(names);
        return tools.stream().filter(t -> wanted.contains(t.name())).toList();
    }

    private static List<Tool> concat(List<Tool> a, List<Tool> b) {
        List<Tool> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
