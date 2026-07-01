package dev.vaijanath.chiefofstaff.meeting;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The silence/noise guard that stops the model fabricating a summary from empty audio. */
class MeetingProcessTest {

    @Test
    void realSpeechIsMeaningful() {
        assertTrue(MeetingProcess.isMeaningful(
                "Good morning everyone. Today we discussed the roadmap and decided to ship the dashboard by June."));
    }

    @Test
    void silenceAndNoiseTagsAreNot() {
        assertFalse(MeetingProcess.isMeaningful(""));
        assertFalse(MeetingProcess.isMeaningful("   "));
        assertFalse(MeetingProcess.isMeaningful("[BLANK_AUDIO]\n[BLANK_AUDIO]\n[BLANK_AUDIO]\n[BLANK_AUDIO]"));
        assertFalse(MeetingProcess.isMeaningful("[silence] [music] uh ah ok")); // tags + too few real words
    }
}
