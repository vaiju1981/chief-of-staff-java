package dev.vaijanath.chiefofstaff.agent;

import dev.vaijanath.aiagent.model.Message;
import dev.vaijanath.aiagent.model.Role;
import java.util.List;

/** Helpers over a conversation (the client-sent message history). */
public final class Conversations {

    private Conversations() {}

    /** The latest user turn — what routing and validation key on. */
    public static String latestUser(List<Message> conversation) {
        for (int i = conversation.size() - 1; i >= 0; i--) {
            Message m = conversation.get(i);
            if (m.role() == Role.USER) {
                return m.content();
            }
        }
        return "";
    }

    /**
     * Flattens a multi-turn conversation into one transcript string, for agents that accept only a
     * single input (the tool-using DefaultAgents). A single-turn conversation is passed through as-is.
     */
    public static String flatten(List<Message> conversation) {
        if (conversation.isEmpty()) {
            return "";
        }
        if (conversation.size() == 1) {
            return conversation.get(0).content();
        }
        StringBuilder sb = new StringBuilder("Conversation so far:\n");
        for (int i = 0; i < conversation.size() - 1; i++) {
            Message m = conversation.get(i);
            sb.append(m.role() == Role.USER ? "User" : "Assistant").append(": ").append(m.content()).append('\n');
        }
        return sb.append("\nCurrent request:\n").append(latestUser(conversation)).toString();
    }
}
