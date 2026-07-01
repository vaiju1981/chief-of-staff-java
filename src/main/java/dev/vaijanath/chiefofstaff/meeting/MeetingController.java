package dev.vaijanath.chiefofstaff.meeting;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops endpoints for the meeting pipeline: list the audio input devices (to confirm BlackHole is
 * visible), drive the recorder directly, and run the post-processing pipeline on an existing WAV
 * (which verifies transcribe → summary → index without needing live capture).
 */
@RestController
@RequestMapping("/meeting")
class MeetingController {

    private final MeetingProcess process;
    private final MeetingRecorder recorder;

    MeetingController(MeetingProcess process, MeetingRecorder recorder) {
        this.process = process;
        this.recorder = recorder;
    }

    @GetMapping("/devices")
    Map<String, Object> devices() {
        List<String> inputs = new ArrayList<>();
        DataLine.Info capture = new DataLine.Info(TargetDataLine.class, null);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (AudioSystem.getMixer(info).isLineSupported(capture)) {
                inputs.add(info.getName());
            }
        }
        return Map.of("audioInputs", inputs);
    }

    @PostMapping("/process")
    Map<String, Object> process(
            @RequestParam String file,
            @RequestParam(defaultValue = "default") String project,
            @RequestParam(required = false) String topic)
            throws Exception {
        Path note = process.process(Path.of(file), project, topic);
        return Map.of("note", note.toString());
    }

    @PostMapping("/start")
    Map<String, String> start(
            @RequestParam(defaultValue = "default") String project,
            @RequestParam(required = false) String topic) {
        return Map.of("result", recorder.start(project, topic == null ? "" : topic));
    }

    @PostMapping("/stop")
    Map<String, String> stop() {
        return Map.of("result", recorder.stop());
    }

    @GetMapping("/status")
    Map<String, String> status() {
        return Map.of("result", recorder.status());
    }
}
