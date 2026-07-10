package dev.vaijanath.chiefofstaff.config;

import dev.vaijanath.aiagent.model.ModelPort;
import dev.vaijanath.aiagent.model.ModelRequest;
import dev.vaijanath.aiagent.model.ModelResponse;

/**
 * Decorates a {@link ModelPort} to record token usage into the thread-bound {@link UsageTracker} (when
 * one is bound). Pure pass-through otherwise, so it is safe to wrap the primary model bean.
 */
public final class MeteredModelPort implements ModelPort {

    private final ModelPort delegate;

    public MeteredModelPort(ModelPort delegate) {
        this.delegate = delegate;
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        ModelResponse response = delegate.chat(request);
        UsageTracker tracker = UsageTracker.current();
        if (tracker != null) {
            tracker.record(request, response);
        }
        return response;
    }

    @Override
    public String name() {
        return delegate.name();
    }
}
