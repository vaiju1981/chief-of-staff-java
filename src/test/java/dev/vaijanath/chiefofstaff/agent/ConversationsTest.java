package dev.vaijanath.chiefofstaff.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vaijanath.aiagent.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The conversation-history logic behind multi-turn memory. */
class ConversationsTest {

    @Test
    void latestUserIsTheLastUserTurn() {
        List<Message> conversation =
                List.of(Message.user("first"), Message.assistant("reply"), Message.user("second"));
        assertEquals("second", Conversations.latestUser(conversation));
    }

    @Test
    void singleTurnFlattensToItself() {
        assertEquals("hello", Conversations.flatten(List.of(Message.user("hello"))));
    }

    @Test
    void multiTurnFlattenKeepsHistoryAndCurrentRequest() {
        String transcript = Conversations.flatten(List.of(
                Message.user("My name is Alex"),
                Message.assistant("Nice to meet you, Alex."),
                Message.user("What is my name?")));
        assertTrue(transcript.contains("My name is Alex"), transcript); // history retained
        assertTrue(transcript.contains("What is my name?"), transcript); // current request retained
    }
}
