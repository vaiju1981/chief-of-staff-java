package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.agent.AgentResponse;
import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.StreamingModelPort;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tool-less generation agent: our system prompt + the full conversation → model. Used for comms and
 * code, which never call tools — so they get real multi-turn context and stream token-by-token.
 *
 * <p>Auto-continuation: when the user asks for a large word count (e.g. "a 4000-word article") and the
 * model stops well short, it is re-prompted to continue — up to {@link #MAX_CONTINUATIONS} times — since
 * models under-deliver on length in a single shot. Only kicks in for long-form targets (≥ 300 words).
 */
public final class GenerationAgent implements ChatAgent {

    private static final int MAX_CONTINUATIONS = 3;
    private static final double ENOUGH = 0.9; // stop continuing once within 90% of the target
    private static final Pattern WORD_TARGET = Pattern.compile("(\\d[\\d,]{2,})\\s*[- ]?word");
    private static final String CONTINUE = "Continue the piece from exactly where you stopped. Do not repeat "
            + "anything already written and do not restart — just keep going until it is complete.";

    private final String systemPrompt;
    private final ModelPort model;
    private final StreamingModelPort streamingModel;
    private final boolean autoContinue;

    public GenerationAgent(String systemPrompt, ModelPort model, StreamingModelPort streamingModel) {
        this(systemPrompt, model, streamingModel, true);
    }

    /**
     * @param autoContinue whether to re-prompt for more text to hit a requested word count. True for free
     *     composition (comms / code). Pass <b>false</b> for a grounded writer (the report writer): its
     *     length must follow the evidence, so forcing it to a quota only makes a small model pad and restate.
     */
    public GenerationAgent(String systemPrompt, ModelPort model, StreamingModelPort streamingModel,
            boolean autoContinue) {
        this.systemPrompt = systemPrompt;
        this.model = model;
        this.streamingModel = streamingModel;
        this.autoContinue = autoContinue;
    }

    @Override
    public AgentResponse respond(List<Message> conversation) {
        int target = autoContinue ? targetWords(Conversations.latestUser(conversation)) : 0;
        StringBuilder full = new StringBuilder(text(model.chat(request(conversation, null))));
        for (int i = 0; i < MAX_CONTINUATIONS && shortOf(full, target); i++) {
            String more = text(model.chat(request(conversation, full.toString()))).strip();
            if (more.isEmpty()) {
                break;
            }
            full.append("\n\n").append(more);
        }
        return AgentResponse.completed(full.toString());
    }

    @Override
    public AgentResponse respondStreaming(List<Message> conversation, Consumer<String> onToken) {
        int target = autoContinue ? targetWords(Conversations.latestUser(conversation)) : 0;
        StringBuilder full = new StringBuilder();
        Consumer<String> collect = token -> {
            full.append(token);
            onToken.accept(token);
        };
        streamingModel.chatStream(request(conversation, null), collect);
        for (int i = 0; i < MAX_CONTINUATIONS && shortOf(full, target); i++) {
            int before = full.length();
            collect.accept("\n\n");
            streamingModel.chatStream(request(conversation, full.toString()), collect);
            if (full.length() <= before + 2) {
                break; // nothing new was produced
            }
        }
        return AgentResponse.completed(full.toString());
    }

    /** system prompt + conversation; on a continuation, also the text so far + a "keep going" turn. */
    private ModelRequest request(List<Message> conversation, String priorOutput) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.addAll(conversation);
        if (priorOutput != null && !priorOutput.isBlank()) {
            messages.add(Message.assistant(priorOutput));
            messages.add(Message.user(CONTINUE));
        }
        return ModelRequest.of(messages);
    }

    private static boolean shortOf(CharSequence text, int target) {
        return target > 0 && wordCount(text) < target * ENOUGH;
    }

    /** The largest long-form word target requested (≥300), or 0 if none — so short asks don't continue. */
    private static int targetWords(String message) {
        if (message == null) {
            return 0;
        }
        Matcher m = WORD_TARGET.matcher(message.toLowerCase());
        int max = 0;
        while (m.find()) {
            try {
                max = Math.max(max, Integer.parseInt(m.group(1).replace(",", "")));
            } catch (NumberFormatException ignored) {
                // not a usable number
            }
        }
        return max >= 300 ? max : 0;
    }

    private static int wordCount(CharSequence text) {
        String t = text.toString().strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    private static String text(dev.vaijanath.aiagent.model.ModelResponse response) {
        String t = response.text();
        return t == null ? "" : t;
    }
}
