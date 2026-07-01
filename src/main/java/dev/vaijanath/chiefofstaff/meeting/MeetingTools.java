package dev.vaijanath.chiefofstaff.meeting;

import dev.vaijanath.aiagent.tool.ToolEffect;
import dev.vaijanath.aiagent.tools.annotations.AgentTool;
import dev.vaijanath.aiagent.tools.annotations.ToolParam;
import org.springframework.stereotype.Component;

/** Meeting recording tools for the meeting agent (start / stop / status), ported from meeting.py. */
@Component
public class MeetingTools {

    private final MeetingRecorder recorder;

    public MeetingTools(MeetingRecorder recorder) {
        this.recorder = recorder;
    }

    @AgentTool(
            name = "start_recording",
            description = "Start recording the current meeting (microphone + system audio). Transcription "
                    + "and a structured summary are generated automatically when you stop.",
            effect = ToolEffect.EFFECTFUL)
    public String startRecording(
            @ToolParam(description = "Free-form project tag, e.g. q2-roadmap", required = false) String project,
            @ToolParam(description = "Optional meeting topic", required = false) String topic) {
        return recorder.start(project == null || project.isBlank() ? "default" : project, topic == null ? "" : topic);
    }

    @AgentTool(
            name = "stop_recording",
            description = "Stop the active meeting recording; transcription and the summary run afterwards.",
            effect = ToolEffect.EFFECTFUL)
    public String stopRecording() {
        return recorder.stop();
    }

    @AgentTool(
            name = "recording_status",
            description = "Report whether a meeting recording is currently in progress.",
            effect = ToolEffect.READ_ONLY)
    public String recordingStatus() {
        return recorder.status();
    }
}
