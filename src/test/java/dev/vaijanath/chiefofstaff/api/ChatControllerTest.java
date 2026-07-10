package dev.vaijanath.chiefofstaff.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vaijanath.aiagent.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The conversation-history mapping behind the OpenAI endpoint. */
class ChatControllerTest {

    @Test
    void keepsUserAndAssistantDropsSystemAndTool() {
        List<Message> conv = ChatController.toConversation(List.of(
                new ChatController.Msg("system", "ignore me"),
                new ChatController.Msg("user", "hi"),
                new ChatController.Msg("assistant", "hello"),
                new ChatController.Msg("tool", "result")));
        assertEquals(2, conv.size());
        assertEquals(Message.user("hi"), conv.get(0));
        assertEquals(Message.assistant("hello"), conv.get(1));
    }

    @Test
    void skipsBlankMessages() {
        List<Message> conv = ChatController.toConversation(List.of(
                new ChatController.Msg("user", "  "),
                new ChatController.Msg("user", "real")));
        assertEquals(1, conv.size());
        assertTrue(conv.get(0).content().contains("real"));
    }

    @Test
    void nullMessagesYieldsEmpty() {
        assertTrue(ChatController.toConversation(null).isEmpty());
    }
}
