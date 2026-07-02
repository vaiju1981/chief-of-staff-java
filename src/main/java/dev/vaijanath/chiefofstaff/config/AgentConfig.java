package dev.vaijanath.chiefofstaff.config;

import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.vaijanath.aiagent.agent.Agent;
import dev.vaijanath.aiagent.langchain4j.LangChain4jModelPort;
import dev.vaijanath.aiagent.langchain4j.LangChain4jStreamingModelPort;
import dev.vaijanath.aiagent.langchain4j.OllamaModelPorts;
import dev.vaijanath.aiagent.agent.DefaultAgent;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.StreamingModelPort;
import dev.vaijanath.aiagent.model.StructuredOutput;
import dev.vaijanath.aiagent.rag.Embedder;
import dev.vaijanath.aiagent.tool.Tool;
import dev.vaijanath.aiagent.tool.ToolApprovers;
import dev.vaijanath.aiagent.tools.annotations.ReflectiveTools;
import dev.vaijanath.chiefofstaff.agent.ChatAgent;
import dev.vaijanath.chiefofstaff.agent.GenerationAgent;
import dev.vaijanath.chiefofstaff.agent.Handoff;
import dev.vaijanath.chiefofstaff.agent.Supervisor;
import dev.vaijanath.chiefofstaff.agent.ToolChatAgent;
import dev.vaijanath.chiefofstaff.meeting.MeetingTools;
import dev.vaijanath.chiefofstaff.prompt.CosPrompts;
import dev.vaijanath.chiefofstaff.rag.RagStore;
import dev.vaijanath.chiefofstaff.rag.RagTools;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the models, the RAG store, and the registry of agents exposed as OpenAI-style "models".
 *
 * <p>Models are built directly (not via the {@code OllamaModelPorts.ollama} helper) so we can set
 * {@code num_ctx} (256K) and never set {@code num_predict} — long outputs are bounded only by context.
 * A streaming model backs token-by-token SSE for the tool-less agents.
 */
@Configuration
class AgentConfig {

    @Bean
    @Primary // StreamingModelPort is also a ModelPort; prefer this one for plain ModelPort injection.
    ModelPort modelPort(CosProperties props) {
        OllamaChatModel model = OllamaChatModel.builder()
                .baseUrl(props.ollamaBaseUrl())
                .modelName(props.model())
                .numCtx(props.numCtx())
                .timeout(Duration.ofMinutes(10))
                .build();
        return new LangChain4jModelPort(model, "ollama:" + props.model());
    }

    @Bean
    StreamingModelPort streamingModelPort(CosProperties props) {
        OllamaStreamingChatModel model = OllamaStreamingChatModel.builder()
                .baseUrl(props.ollamaBaseUrl())
                .modelName(props.model())
                .numCtx(props.numCtx())
                .timeout(Duration.ofMinutes(10)) // long generations (esp. auto-continuation) mustn't time out
                .build();
        return new LangChain4jStreamingModelPort(model, "ollama-stream:" + props.model());
    }

    @Bean
    RagStore ragStore(CosProperties props) {
        Embedder embedder = OllamaModelPorts.ollamaEmbedder(props.ollamaBaseUrl(), props.embeddingModel());
        return new RagStore(props.dbUrl(), props.dbUser(), props.dbPassword(), embedder,
                props.embeddingDimensions(), props.minScore());
    }

    @Bean
    Map<String, ChatAgent> agents(
            ModelPort model, StreamingModelPort streamingModel, CosProperties props, RagStore rag,
            McpToolSource mcp, MeetingTools meetingTools) {
        List<Tool> ragTools = ReflectiveTools.from(new RagTools(rag));
        String dataDir = Path.of(props.dataDir()).toAbsolutePath().toString();
        String vaultDir = Path.of(props.dataDir(), "vault").toAbsolutePath().toString();

        Map<String, ChatAgent> specialists = new LinkedHashMap<>();
        specialists.put("comms", new GenerationAgent(CosPrompts.comms(), model, streamingModel));

        // Code: general Q&A (streaming) unless GitHub issue tools are available (GITHUB_TOKEN set), in
        // which case it becomes a tool agent that can also manage issues.
        List<Tool> githubTools = mcp.select("create_issue", "add_issue_comment", "get_issue", "list_issues");
        specialists.put("code", githubTools.isEmpty()
                ? new GenerationAgent(CosPrompts.code(), model, streamingModel)
                : new ToolChatAgent(toolAgent(model, CosPrompts.code(), githubTools)));

        specialists.put("handoff", new ToolChatAgent(new Handoff(model)));

        // Researcher: library RAG + filesystem read + web (Tavily, when TAVILY_API_KEY is set).
        // Notes: meeting RAG + vault exploration. Tool agents flatten the conversation into their input.
        specialists.put("researcher", new ToolChatAgent(toolAgent(model, CosPrompts.researcher(dataDir),
                concat(only(ragTools, "search_local_documents", "search_by_category"),
                        mcp.select("list_directory", "read_text_file", "tavily_search", "tavily_extract")))));
        specialists.put("notes", new ToolChatAgent(toolAgent(model, CosPrompts.notes(vaultDir),
                concat(only(ragTools, "search_meetings"),
                        mcp.select("list_directory", "read_text_file", "search_files", "directory_tree")))));

        // Meeting: pilot the recorder from chat (start / stop / status).
        specialists.put("meeting", new ToolChatAgent(
                toolAgent(model, CosPrompts.meeting(), ReflectiveTools.from(meetingTools))));

        StructuredOutput router = OllamaModelPorts.ollamaStructured(props.ollamaBaseUrl(), props.model());
        Supervisor supervisor = new Supervisor(model, streamingModel, router, specialists);

        Map<String, ChatAgent> registry = new LinkedHashMap<>();
        registry.put("agent-chief-of-staff", supervisor);
        specialists.forEach((id, agent) -> registry.put("agent-" + id, agent));
        return registry;
    }

    /**
     * A tool-using DefaultAgent whose approver is a per-tool allow-list of exactly the tools it was
     * given — so the model can only invoke those (a hallucinated or injected tool name is denied),
     * while the intended tools (including effectful ones like create_issue) run.
     */
    private static Agent toolAgent(ModelPort model, String systemPrompt, List<Tool> tools) {
        DefaultAgent.Builder builder = DefaultAgent.builder()
                .model(model)
                .systemPrompt(systemPrompt)
                .toolApprover(ToolApprovers.allowList(tools.stream().map(Tool::name).toArray(String[]::new)));
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
