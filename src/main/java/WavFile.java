import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * The Save WAV button's destination: a {@link SpeechPipeline.Player} that keeps the chunks it is
 * handed instead of playing them, and {@link #write writes} them as one WAV file when the
 * pipeline is done. Speak and Save WAV share the synthesis (ollama for English, say for Chinese);
 * the saved audio is stretched to the selected speed with {@link TimeStretch}, so the file sounds
 * the way Speak would.
 */
final class WavFile implements SpeechPipeline.Player {

    private static final int MAX_NAME_CHARS = 40;

    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private AudioFormat format;

    /** Appends one chunk's audio; every chunk must have the format of the first. */
    @Override
    public synchronized void play(byte[] wav) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            if (format == null) {
                format = ais.getFormat();
            } else if (!format.matches(ais.getFormat())) {
                throw new IOException("audio format changed between chunks: " + format + ", then " + ais.getFormat());
            }
            ais.transferTo(pcm);
        }
    }

    /** Seconds of audio collected so far, at normal speed. */
    synchronized double seconds() {
        return format == null ? 0 : (double) pcm.size() / format.getFrameSize() / format.getFrameRate();
    }

    /** Writes everything collected to {@code file}, played {@code rate} times as fast. */
    synchronized void write(Path file, double rate) throws IOException {
        if (format == null) throw new IOException("nothing to save");
        byte[] data = pcm.toByteArray();
        int frameSize = format.getFrameSize();
        byte[] out = TimeStretch.stretch(data, 0, data.length / frameSize * frameSize, format, rate);
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(out), format, out.length / frameSize)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file.toFile());
        }
    }

    /** {@code file} with a .wav extension added unless it already has one (in any case). */
    static Path withWavExtension(Path file) {
        String name = file.getFileName().toString();
        return name.toLowerCase().endsWith(".wav") ? file : file.resolveSibling(name + ".wav");
    }

    /**
     * A file name (without extension) for the speech of {@code text}: its first sentence or line,
     * with characters file systems dislike turned into spaces, at most {@value #MAX_NAME_CHARS}
     * characters; "speech" when nothing is left.
     */
    static String suggestedName(String text) {
        String first = text.strip().split("[\\n.!?。！？]", 2)[0];
        String clean = first.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").strip();
        if (clean.length() > MAX_NAME_CHARS) {
            clean = clean.substring(0, MAX_NAME_CHARS);
            int space = clean.lastIndexOf(' ');
            clean = (space > 0 ? clean.substring(0, space) : clean).strip();
        }
        return clean.isEmpty() ? "speech" : clean;
    }
}
