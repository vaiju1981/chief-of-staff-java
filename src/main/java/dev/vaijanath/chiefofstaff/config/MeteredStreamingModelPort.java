package dev.vaijanath.chiefofstaff.config;

import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.ModelResponse;
import dev.vaijanath.aiagent.model.StreamingModelPort;
import java.util.function.Consumer;

/**
 * Decorates a {@link StreamingModelPort} to record token usage into the thread-bound
 * {@link UsageTracker} (when one is bound). The final non-streaming {@link ModelResponse} returned by
 * {@code chatStream} carries the usage (when the provider reports it), which we bank; for providers that
 * don't, {@link UsageTracker} falls back to a char estimate from the streamed text.
 */
public final class MeteredStreamingModelPort implements StreamingModelPort {

    private final StreamingModelPort delegate;

    public MeteredStreamingModelPort(StreamingModelPort delegate) {
        this.delegate = delegate;
    }

    @Override
    public ModelResponse chatStream(ModelRequest request, Consumer<String> onToken) {
        ModelResponse response = delegate.chatStream(request, onToken);
        UsageTracker tracker = UsageTracker.current();
        if (tracker != null) {
            tracker.record(request, response);
        }
        return response;
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        return delegate.chat(request);
    }

    @Override
    public String name() {
        return delegate.name();
    }
}
