package dev.vaijanath.chiefofstaff.meeting;

import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.chiefofstaff.config.CosProperties;
import dev.vaijanath.chiefofstaff.rag.RagStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Meeting post-processing, ported from {@code process.py}: transcribe (whisper.cpp) → silence guard →
 * structured summary (LLM) → save to the vault → index in pgvector.
 *
 * <p>The silence guard ({@link #isMeaningful}) is an anti-hallucination measure: whisper emits noise
 * tags like {@code [BLANK_AUDIO]} on silent input, which is long enough to fool a naive length check —
 * so on empty/noise audio we save an honest "no speech" note instead of letting the model fabricate a
 * meeting from nothing.
 */
@Component
public class MeetingProcess {

    private static final Logger log = LoggerFactory.getLogger(MeetingProcess.class);
    private static final Pattern TAG = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern REAL_WORD = Pattern.compile("\\b\\p{L}{3,}\\b");

    private static final String SYNTHESIS_PROMPT =
            """
            You are a meeting-notes assistant. Summarize ONLY the actual content of the transcript below.

            RULES:
            1. Base everything STRICTLY on the transcript. Never invent decisions, actions, names, dates, or topics.
            2. Do not use any outside knowledge.
            3. Write in the SAME LANGUAGE as the transcript.
            4. If the transcript has no substantive content, write "No usable content in this meeting." in each section.

            Produce this structure (translate the headings into the transcript's language):

            # Summary
            [3-4 factual sentences, only what was actually said]

            # Decisions
            - [decision, only if explicitly stated; otherwise "None"]

            # Actions
            - [ ] who: what (deadline if mentioned)   [only explicit actions; otherwise "None"]

            # Open questions
            - [raised but unanswered; otherwise "None"]

            # Topics
            - [topics actually discussed; otherwise "None"]

            TRANSCRIPT:
            %s
            """;

    private final ModelPort model;
    private final RagStore rag;
    private final CosProperties props;

    public MeetingProcess(ModelPort model, RagStore rag, CosProperties props) {
        this.model = model;
        this.rag = rag;
        this.props = props;
    }

    /** Transcribe → guard → summarize → save → index. Returns the saved note path. */
    public Path process(Path wav, String project, String topic) throws IOException, InterruptedException {
        String transcript = transcribe(wav);
        if (!isMeaningful(transcript)) {
            log.info("[meeting] no meaningful speech in {} — saving a 'no speech' note", wav.getFileName());
            return saveNote(wav, project, topic, "meeting_no_speech",
                    "# ⚠️ No meaningful speech detected\n\nWhisper found no real speech in this recording, so no "
                            + "summary was generated (to avoid hallucination).\n\n## Raw transcript\n```\n"
                            + transcript + "\n```");
        }
        String summary = summarize(transcript);
        String body = summary + "\n\n---\n\n## Raw transcript\n\n> Source: `" + wav.getFileName() + "`\n\n" + transcript;
        Path note = saveNote(wav, project, topic, "meeting", body);
        index(note, project);
        return note;
    }

    private String transcribe(Path wav) throws IOException, InterruptedException {
        Path prefix = wav.resolveSibling(stripExt(wav.getFileName().toString()));
        List<String> cmd = List.of(props.whisperCli(), "-m", props.whisperModel(), "-f", wav.toString(),
                "-l", props.whisperLanguage(), "-otxt", "-of", prefix.toString(), "-np");
        log.info("[meeting] transcribing {} via whisper.cpp...", wav.getFileName());
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();
        Path txt = Path.of(prefix + ".txt");
        if (Files.exists(txt)) {
            String transcript = Files.readString(txt).strip();
            Files.deleteIfExists(txt);
            return transcript;
        }
        throw new IOException("whisper-cli produced no transcript (exit " + code + "): " + out.strip());
    }

    /** Silence/noise guard (ported from is_transcript_meaningful). */
    static boolean isMeaningful(String transcript) {
        String text = transcript == null ? "" : transcript.strip();
        if (text.length() < 50) {
            return false;
        }
        String cleaned = TAG.matcher(text).replaceAll("").strip();
        if (cleaned.length() < 50) {
            return false;
        }
        return REAL_WORD.matcher(cleaned).results().count() >= 10;
    }

    private String summarize(String transcript) {
        log.info("[meeting] summarizing transcript ({} chars)...", transcript.length());
        return model.chat(ModelRequest.of(List.of(Message.user(SYNTHESIS_PROMPT.formatted(transcript)))))
                .text()
                .strip();
    }

    private Path saveNote(Path wav, String project, String topic, String type, String body) throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String date = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path dir = Path.of(props.dataDir(), "vault", "meetings", now.format(DateTimeFormatter.ofPattern("yyyy/MM")));
        Files.createDirectories(dir);
        String base = topic != null && !topic.isBlank() ? topic : stripExt(wav.getFileName().toString());
        String suffix = "meeting".equals(type) ? "" : "_NO_SPEECH";
        Path note = dir.resolve(date + "_" + slugify(base) + suffix + ".md");
        String front = "---\ndate: " + date + "\nproject: " + project + "\ntopic: " + (topic == null ? "" : topic)
                + "\ntype: " + type + "\naudio: " + wav.getFileName() + "\ntags: [meeting, " + project + "]\n---\n\n";
        Files.writeString(note, front + body);
        log.info("[meeting] saved {}", note);
        return note;
    }

    private void index(Path note, String project) {
        try {
            rag.ingest(project, note.getFileName().toString(), "meeting", Files.readString(note));
            log.info("[meeting] indexed {} in pgvector", note.getFileName());
        } catch (Exception e) {
            log.warn("[meeting] indexing failed: {}", e.toString());
        }
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String slugify(String text) {
        String s = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s-]", "")
                .replaceAll("[\\s-]+", "-")
                .replaceAll("^-|-$", "");
        return s.isBlank() ? "meeting" : s.substring(0, Math.min(s.length(), 50));
    }
}
