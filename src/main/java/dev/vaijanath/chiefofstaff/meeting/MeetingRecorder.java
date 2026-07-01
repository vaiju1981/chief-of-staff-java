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
 * downmixes each to mono, mixes them, and writes one 48 kHz mono WAV; on stop it downsamples to whisper's
 * 16 kHz via {@code afconvert} and triggers {@link MeetingProcess}. Ported from recorder.py.
 *
 * <p>Each device is opened mono if it supports it, else stereo (BlackHole 2ch is stereo) and downmixed.
 * Live capture needs BlackHole installed; a missing device is skipped and the other is used alone.
 */
@Component
public class MeetingRecorder {

    private static final Logger log = LoggerFactory.getLogger(MeetingRecorder.class);
    private static final float RATE = 48_000f;
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

    /** A capture line plus its channel count (1 = mono, 2 = stereo → downmixed on read). */
    private record Source(TargetDataLine line, int channels) {}

    public synchronized String start(String project, String topic) {
        if (recording) {
            return "⚠️  A recording is already in progress (project " + this.project + "). Stop it first.";
        }
        Source mic = openLine("mic", null);
        Source blackhole = openLine("blackhole", "BlackHole");
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

    /** Opens a 48k capture line (mono if supported, else stereo); by device-name substring, or default. */
    private Source openLine(String label, String nameContains) {
        Mixer.Info target = null;
        if (nameContains != null) {
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                // "Port <name>" mixers are control ports, not capture devices — skip them.
                if (mixerInfo.getName().startsWith("Port ")) {
                    continue;
                }
                if (mixerInfo.getName().toLowerCase().contains(nameContains.toLowerCase())) {
                    target = mixerInfo;
                    break;
                }
            }
            if (target == null) {
                log.info("[meeting] {} device not found (skipping)", label);
                return null;
            }
        }
        // Prefer stereo (BlackHole's concrete format; downmixed on read), fall back to mono (the mic).
        for (int channels : new int[] {2, 1}) {
            AudioFormat format = new AudioFormat(RATE, 16, channels, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            try {
                TargetDataLine line = (TargetDataLine)
                        (target != null ? AudioSystem.getMixer(target).getLine(info) : AudioSystem.getLine(info));
                line.open(format);
                line.start();
                log.info("[meeting] capturing {} from '{}' ({}ch)", label,
                        target != null ? target.getName() : "default input", channels);
                return new Source(line, channels);
            } catch (Exception e) {
                // format unsupported at this channel count — try the next
            }
        }
        log.warn("[meeting] no supported capture format for {}", label);
        return null;
    }

    private void capture(Source mic, Source blackhole, Path out) {
        try (WavWriter writer = new WavWriter(out)) {
            byte[] micBuf = new byte[CHUNK];
            byte[] bhBuf = new byte[CHUNK];
            while (recording) {
                short[] m = readMono(mic, micBuf);
                short[] b = readMono(blackhole, bhBuf);
                writer.write(toBytes(mix(m, b)));
            }
        } catch (Exception e) {
            log.warn("[meeting] capture error: {}", e.toString());
        } finally {
            close(mic);
            close(blackhole);
        }
    }

    /** Reads one chunk and returns mono 16-bit samples (downmixing L+R when the source is stereo). */
    private static short[] readMono(Source source, byte[] buf) {
        if (source == null) {
            return new short[0];
        }
        int n = source.line().read(buf, 0, buf.length);
        if (n <= 0) {
            return new short[0];
        }
        if (source.channels() == 1) {
            short[] out = new short[n / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (short) ((buf[2 * i] & 0xff) | (buf[2 * i + 1] << 8));
            }
            return out;
        }
        int frames = n / 4;
        short[] out = new short[frames];
        for (int i = 0; i < frames; i++) {
            short left = (short) ((buf[4 * i] & 0xff) | (buf[4 * i + 1] << 8));
            short right = (short) ((buf[4 * i + 2] & 0xff) | (buf[4 * i + 3] << 8));
            out[i] = (short) ((left + right) / 2);
        }
        return out;
    }

    private static short[] mix(short[] a, short[] b) {
        if (b.length == 0) {
            return a;
        }
        if (a.length == 0) {
            return b;
        }
        int n = Math.min(a.length, b.length);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            out[i] = (short) ((a[i] + b[i]) / 2);
        }
        return out;
    }

    private static byte[] toBytes(short[] samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            out[2 * i] = (byte) (samples[i] & 0xff);
            out[2 * i + 1] = (byte) ((samples[i] >> 8) & 0xff);
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

    private static void close(Source source) {
        if (source != null) {
            source.line().stop();
            source.line().close();
        }
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "meeting" : s.replaceAll("[^a-zA-Z0-9_-]", "-");
    }

    /** Minimal streaming WAV writer (48 kHz mono 16-bit PCM); patches the RIFF sizes on close. */
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
            file.seek(0);
            file.writeBytes("RIFF");
            writeIntLE(36 + dataBytes);
            file.writeBytes("WAVE");
            file.writeBytes("fmt ");
            writeIntLE(16);
            writeShortLE(1); // PCM
            writeShortLE(channels);
            writeIntLE(sampleRate);
            writeIntLE(sampleRate * channels * bits / 8);
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
