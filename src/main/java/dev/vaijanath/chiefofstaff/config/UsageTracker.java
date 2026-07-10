package dev.vaijanath.chiefofstaff.config;

import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.ModelResponse;
import dev.vaijanath.aiagent.model.Usage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-request token-usage accumulator, surfaced in the OpenAI {@code usage} field so Open WebUI shows
 * real counts instead of the hard-coded zeros.
 *
 * <p>Bound to the calling thread via a {@link ThreadLocal} (not a Spring request scope) so it works when
 * generation runs on a virtual thread — ChatController binds it on both the request thread and the stream
 * worker thread. We prefer the model's reported {@link Usage} when present, and fall back to a
 * characters/4 estimate (the common ~4-char-per-token heuristic) only when the model reports nothing.
 */
public final class UsageTracker {

    private static final ThreadLocal<UsageTracker> CURRENT = new ThreadLocal<>();

    private final AtomicLong realIn = new AtomicLong();
    private final AtomicLong realOut = new AtomicLong();
    private volatile boolean hasReal;
    private long promptChars;
    private long completionChars;

    /** Attach this tracker to the current thread so metered model ports record into it. */
    public static void bind(UsageTracker tracker) {
        CURRENT.set(tracker);
    }

    public static void unbind() {
        CURRENT.remove();
    }

    /** The tracker bound to the current thread, or {@code null} if none is bound. */
    public static UsageTracker current() {
        return CURRENT.get();
    }

    /** Record one model call: estimates char counts always, and banks real token counts when reported. */
    public synchronized void record(ModelRequest request, ModelResponse response) {
        if (request != null) {
            for (Message m : request.messages()) {
                promptChars += chars(m.content());
            }
        }
        String text = response == null ? null : response.text();
        completionChars += chars(text);
        Usage u = response == null ? null : response.usage();
        if (u != null && u != Usage.UNKNOWN && (u.inputTokens() > 0 || u.outputTokens() > 0)) {
            realIn.addAndGet(u.inputTokens());
            realOut.addAndGet(u.outputTokens());
            hasReal = true;
        }
    }

    /** The accumulated usage — real counts if any were reported, else a char-based estimate. */
    public Usage get() {
        if (hasReal) {
            return new Usage(realIn.get(), realOut.get());
        }
        return new Usage(est(promptChars), est(completionChars));
    }

    private static long chars(String s) {
        return s == null ? 0 : s.length();
    }

    private static long est(long chars) {
        return (chars + 3) / 4;
    }
}
