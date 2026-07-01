package dev.vaijanath.chiefofstaff.prompt;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Router + meta-response prompts for the {@code Supervisor}, ported from the Python {@code supervisor.py}.
 *
 * <p>The router prompt is built from a catalog of all known specialists but only lists {@code meta} +
 * {@code meta_recording} plus the specialists actually wired so far — so as later port steps register
 * researcher / notes / code / meeting, they appear in routing automatically, with curated descriptions
 * and multilingual few-shot examples already in place.
 */
public final class SupervisorPrompts {

    private SupervisorPrompts() {}

    /** id → one-line routing description. */
    private static final Map<String, String> CATALOG = new LinkedHashMap<>();
    /** id → few-shot "utterance -> id" examples (kept multilingual, like the Python router). */
    private static final Map<String, List<String>> EXAMPLES = new LinkedHashMap<>();

    static {
        CATALOG.put("meta", "meta questions about the system itself (who are you, what can you do, "
                + "introduce yourself, help, how it works, capabilities)");
        EXAMPLES.put("meta", List.of(
                "Hi, what can you do? -> meta", "Who are you? -> meta", "Présente-toi -> meta",
                "How does it work? -> meta"));

        CATALOG.put("meta_recording", "ABSTRACT questions about HOW to record / transcribe / summarize a "
                + "meeting (Teams, Zoom, Meet, audio capture, the transcription pipeline)");
        EXAMPLES.put("meta_recording", List.of(
                "How can I record my Teams meeting? -> meta_recording",
                "Comment enregistrer l'audio d'une réunion ? -> meta_recording"));

        CATALOG.put("researcher", "information lookup (indexed papers, web, filesystem); technical or "
                + "factual questions, documentation");
        EXAMPLES.put("researcher", List.of(
                "What's the difference between RNN and Transformer? -> researcher",
                "Search news about Granite 4 -> researcher"));

        CATALOG.put("comms", "writing content directly — emails, messages, announcements, notes, AND "
                + "long-form articles / reports / essays / deep-dives of ANY length. No retrieval. This is "
                + "where writing actually gets DONE (the model writes the full text here).");
        EXAMPLES.put("comms", List.of(
                "Draft an email to cancel the meeting -> comms",
                "Write a message to my team announcing the project -> comms",
                "Write a 4000-word deep-dive on transformer positional encodings -> comms",
                "Write a detailed 5000-word article on X -> comms"));

        CATALOG.put("notes", "exploration and search inside the notes vault (meetings, personal projects, "
                + "daily notes)");
        EXAMPLES.put("notes", List.of(
                "What meeting notes do I have on project X? -> notes", "List my vault notes -> notes"));

        CATALOG.put("code", "programming questions, algorithms, debugging, GitHub issue management");
        EXAMPLES.put("code", List.of(
                "How do I implement an LRU cache in Python? -> code",
                "Create an issue on repo Y for this bug -> code"));

        CATALOG.put("handoff", "ONLY when the user EXPLICITLY asks to prepare / build / give them a PROMPT "
                + "to paste into Claude.ai or ChatGPT. NOT for writing content here — writing (even long "
                + "articles) is comms.");
        EXAMPLES.put("handoff", List.of(
                "Prepare a prompt for Claude.ai about transformer attention -> handoff",
                "Give me a ChatGPT prompt to write my article -> handoff",
                "Build me a prompt I can paste into Claude -> handoff"));

        CATALOG.put("meeting", "to PILOT a meeting recording in real time (start / stop / status of the "
                + "recorder). Different from meta_recording, which answers ABSTRACT questions");
        EXAMPLES.put("meeting", List.of(
                "Start the recording for the Q2 meeting -> meeting", "Stop the recording -> meeting",
                "Recording status? -> meeting"));
    }

    /** Ordered route ids: meta + meta_recording, then the wired specialists in catalog order. */
    public static Set<String> validRoutes(Collection<String> availableSpecialistIds) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add("meta");
        ids.add("meta_recording");
        for (String id : CATALOG.keySet()) {
            if (availableSpecialistIds.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** The router system prompt, listing only the currently-available routes. */
    public static String routerPrompt(Collection<String> availableSpecialistIds) {
        Set<String> ids = validRoutes(availableSpecialistIds);
        StringBuilder sb = new StringBuilder();
        sb.append("You are a router. Pick ONE agent to handle the user's request.\n\nAvailable agents:\n");
        for (String id : ids) {
            sb.append("- ").append(id).append(": ").append(CATALOG.get(id)).append('\n');
        }
        sb.append("\nReturn the single best agent name from that list — exactly one of: ")
                .append(String.join(", ", ids))
                .append(".\n\nExamples:\n");
        for (String id : ids) {
            for (String ex : EXAMPLES.getOrDefault(id, List.of())) {
                sb.append("- ").append(ex).append('\n');
            }
        }
        return sb.toString();
    }

    public static final String SYSTEM_FACTS =
            """
            You are the user's personal Chief of Staff — a mostly-local multi-agent orchestrator.

            You delegate to these specialists:
            - 📚 Researcher: searches indexed documents (papers, notes) and the web
            - 📝 Comms: drafts emails, messages, and short reports
            - 🗒️ Notes: explores the notes vault (meetings, projects, daily notes)
            - 💻 Code: programming, algorithms, debugging, GitHub issue management
            - 🔀 Handoff: prepares rich prompts for Claude.ai or ChatGPT (for the heaviest tasks)
            - 🎙️ Meeting: pilots meeting recordings (start / stop / status) directly from chat

            Characteristics:
            - Runs mostly locally via Ollama; the chat model runs on Ollama's cloud, everything else
              (embeddings, notes, documents) stays on your machine
            - Multilingual: adapts to the user's language
            - For very large or complex tasks, suggests Claude.ai or ChatGPT via the Handoff agent
            """;

    public static final String MEETING_FACTS =
            """
            There are two ways to record a meeting (Teams, Zoom, Meet, etc.):

            OPTION 1 — Local automated pipeline (RECOMMENDED):
            Captures system audio + auto-transcribes (whisper.cpp) + generates a structured meeting note
            (summary, decisions, actions, open questions) + saves it to the notes vault + indexes it for
            future semantic search.

            One-time setup: install BlackHole 2ch and route system output through a Multi-Output Device
            that combines your speakers + BlackHole, so the assistant can hear the meeting audio.

            During the meeting, pilot it from chat: say "start the recording, project X, topic Y", then
            "stop" when done. Transcription and the structured summary run automatically afterwards.

            OPTION 2 — Native Teams/Zoom recording:
            Use the app's own Record button. Simpler, but no automatic summary, and participants are
            notified.

            Local pipeline advantages: automatic summary, locally indexed, data stays on your machine,
            no notification to participants, Markdown output in the vault.
            """;

    /** The reformulation system prompt used for meta / meta_recording answers. */
    public static String reformulation(String facts, String guidance) {
        return """
               The user is asking a question that the FACTS below answer.

               🌐 Detect the user's language from their message and respond ENTIRELY in that language.
               The FACTS are in English; translate and adapt them into the user's language — do NOT mix
               languages, and do NOT translate literally, sound warm and natural.

               FACTS:
               ---
               %s
               ---

               Present these facts to the user in well-structured markdown (headings, bullets, tables if
               useful). %s
               """
                .formatted(facts, guidance);
    }
}
