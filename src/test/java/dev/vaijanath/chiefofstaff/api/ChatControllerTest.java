package dev.vaijanath.chiefofstaff.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vaijanath.chiefofstaff.api.ChatController.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The routing input logic: the controller must dispatch on the LAST user message. */
class ChatControllerTest {

    @Test
    void picksLastUserMessage() {
        List<Message> messages = List.of(
                new Message("system", "you are helpful"),
                new Message("user", "first"),
                new Message("assistant", "reply"),
                new Message("user", "second"));
        assertEquals("second", ChatController.lastUserMessage(messages));
    }

    @Test
    void nullWhenNoUserMessage() {
        assertNull(ChatController.lastUserMessage(List.of(new Message("system", "x"))));
        assertNull(ChatController.lastUserMessage(List.of()));
        assertNull(ChatController.lastUserMessage(null));
    }
}
