package dev.vaijanath.chiefofstaff.meeting;

import dev.vaijanath.chiefofstaff.config.CosProperties;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Captures a meeting from the microphone (your voice) + BlackHole (the other participants' audio),
 * mixes them into one mono WAV, and on stop triggers {@link MeetingProcess}. Ported from recorder.py.
 *
 * <p>Captures at 48 kHz mono (widely supported), then downsamples to whisper's 16 kHz via {@code
 * afconvert} (built into macOS) — avoiding in-JVM resampling. Live capture needs BlackHole installed;
 * if a device is missing the recorder uses whichever is available.
 */
@Component
public class MeetingRecorder {

    private static final Logger log = LoggerFactory.getLogger(MeetingRecorder.class);
    private static final AudioFormat FORMAT = new AudioFormat(48_000f, 16, 1, true, false); // 48k mono 16-bit LE
    private static final int CHUNK = 8192;

    private final MeetingProcess process;
    private final CosProperties props;

    private volatile boolean recording;
    private Thread captureThread;
    private Path wavPath;
    private String project = "";
    private String topic = "";
    private String startedAt = "";

    public MeetingRecorder(MeetingProcess process, CosProperties props) {
        this.process = process;
        this.props = props;
    }

    public synchronized String start(String project, String topic) {
        if (recording) {
            return "⚠️  A recording is already in progress (project " + this.project + "). Stop it first.";
        }
        TargetDataLine mic = openLine("mic", null);
        TargetDataLine blackhole = openLine("blackhole", "BlackHole");
        if (mic == null && blackhole == null) {
            return "❌ No audio input available. Install BlackHole (brew install blackhole-2ch) and set the "
                    + "Multi-Output Device, then try again.";
        }
        try {
            Path dir = Path.of(props.dataDir(), "recordings");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            this.wavPath = dir.resolve(stamp + "_" + safe(project) + "_48k.wav");
        } catch (IOException e) {
            return "❌ Could not create the recordings folder: " + e.getMessage();
        }
        this.project = project;
        this.topic = topic == null ? "" : topic;
        this.startedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        this.recording = true;
        this.captureThread = new Thread(() -> capture(mic, blackhole, wavPath), "meeting-capture");
        this.captureThread.start();
        String src = (mic != null ? "mic" : "") + (mic != null && blackhole != null ? " + " : "")
                + (blackhole != null ? "BlackHole" : "");
        return "✅ Recording started (project " + project + (this.topic.isBlank() ? "" : ", topic " + this.topic)
                + ", capturing " + src + "). Say 'stop' when done.";
    }

    public synchronized String stop() {
        if (!recording) {
            return "ℹ️  No active recording.";
        }
        recording = false;
        try {
            captureThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Path wav48 = wavPath;
        String proj = project;
        String top = topic;
        this.project = "";
        this.topic = "";
        this.startedAt = "";
        this.wavPath = null;

        Path wav16 = downsampleTo16k(wav48);
        Path toProcess = wav16 != null ? wav16 : wav48;
        new Thread(() -> runProcess(toProcess, proj, top), "meeting-process").start();
        return "⏹️  Recording stopped. Transcription and the structured summary are running in the background; "
                + "the note will appear in data/vault/meetings/ shortly.";
    }

    public synchronized String status() {
        return recording
                ? "🎙️  Recording in progress (project " + project + (topic.isBlank() ? "" : ", topic " + topic)
                        + ", since " + startedAt + ")."
                : "ℹ️  No active recording.";
    }

    /** Opens a 48k-mono capture line; by device-name substring, or the default input when name is null. */
    private TargetDataLine openLine(String label, String nameContains) {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            Mixer.Info chosen = null;
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (!AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
                    continue;
                }
                if (nameContains == null) {
                    chosen = mixerInfo;
                    break;
                }
                if (mixerInfo.getName().toLowerCase().contains(nameContains.toLowerCase())) {
                    chosen = mixerInfo;
                    break;
                }
            }
            if (nameContains != null && chosen == null) {
                log.info("[meeting] {} device not found (skipping)", label);
                return null;
            }
            TargetDataLine line = (TargetDataLine)
                    (chosen != null ? AudioSystem.getMixer(chosen).getLine(info) : AudioSystem.getLine(info));
            line.open(FORMAT);
            line.start();
            log.info("[meeting] capturing {} from '{}'", label, chosen != null ? chosen.getName() : "default");
            return line;
        } catch (Exception e) {
            log.warn("[meeting] could not open {} line: {}", label, e.toString());
            return null;
        }
    }

    private void capture(TargetDataLine mic, TargetDataLine blackhole, Path out) {
        try (WavWriter writer = new WavWriter(out)) {
            byte[] micBuf = new byte[CHUNK];
            byte[] bhBuf = new byte[CHUNK];
            while (recording) {
                int nm = mic != null ? mic.read(micBuf, 0, CHUNK) : 0;
                int nb = blackhole != null ? blackhole.read(bhBuf, 0, CHUNK) : 0;
                writer.write(mix(mic != null ? micBuf : null, nm, blackhole != null ? bhBuf : null, nb));
            }
        } catch (Exception e) {
            log.warn("[meeting] capture error: {}", e.toString());
        } finally {
            close(mic);
            close(blackhole);
        }
    }

    /** Averages two 16-bit LE mono buffers; when only one is present, returns it as-is. */
    private static byte[] mix(byte[] a, int na, byte[] b, int nb) {
        if (b == null || nb == 0) {
            return a == null ? new byte[0] : java.util.Arrays.copyOf(a, na);
        }
        if (a == null || na == 0) {
            return java.util.Arrays.copyOf(b, nb);
        }
        int n = Math.min(na, nb);
        byte[] out = new byte[n];
        for (int i = 0; i + 1 < n; i += 2) {
            short sa = (short) ((a[i] & 0xff) | (a[i + 1] << 8));
            short sb = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            int mixed = (sa + sb) / 2;
            out[i] = (byte) (mixed & 0xff);
            out[i + 1] = (byte) ((mixed >> 8) & 0xff);
        }
        return out;
    }

    private Path downsampleTo16k(Path wav48) {
        if (wav48 == null || !Files.exists(wav48)) {
            return null;
        }
        Path wav16 = Path.of(wav48.toString().replace("_48k.wav", ".wav"));
        try {
            int code = new ProcessBuilder("afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1",
                    wav48.toString(), wav16.toString()).redirectErrorStream(true).start().waitFor();
            if (code == 0 && Files.exists(wav16)) {
                Files.deleteIfExists(wav48);
                return wav16;
            }
            log.warn("[meeting] afconvert exited {} — using the 48k file directly", code);
        } catch (Exception e) {
            log.warn("[meeting] afconvert failed ({}) — using the 48k file directly", e.toString());
        }
        return null;
    }

    private void runProcess(Path wav, String project, String topic) {
        try {
            process.process(wav, project, topic);
        } catch (Exception e) {
            log.warn("[meeting] post-processing failed: {}", e.toString());
        }
    }

    private static void close(TargetDataLine line) {
        if (line != null) {
            line.stop();
            line.close();
        }
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "meeting" : s.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    /** Minimal streaming WAV writer (16-bit PCM); patches the RIFF sizes on close. */
    private static final class WavWriter implements AutoCloseable {
        private final RandomAccessFile file;
        private int dataBytes;

        WavWriter(Path path) throws IOException {
            this.file = new RandomAccessFile(path.toFile(), "rw");
            file.write(new byte[44]); // placeholder header, filled in close()
        }

        void write(byte[] pcm) throws IOException {
            if (pcm.length > 0) {
                file.write(pcm);
                dataBytes += pcm.length;
            }
        }

        @Override
        public void close() throws IOException {
            int sampleRate = 48_000;
            int channels = 1;
            int bits = 16;
            int byteRate = sampleRate * channels * bits / 8;
            file.seek(0);
            file.writeBytes("RIFF");
            writeIntLE(36 + dataBytes);
            file.writeBytes("WAVE");
            file.writeBytes("fmt ");
            writeIntLE(16);
            writeShortLE(1); // PCM
            writeShortLE(channels);
            writeIntLE(sampleRate);
            writeIntLE(byteRate);
            writeShortLE(channels * bits / 8);
            writeShortLE(bits);
            file.writeBytes("data");
            writeIntLE(dataBytes);
            file.close();
        }

        private void writeIntLE(int v) throws IOException {
            file.write(new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)});
        }

        private void writeShortLE(int v) throws IOException {
            file.write(new byte[] {(byte) v, (byte) (v >> 8)});
        }
    }
}
