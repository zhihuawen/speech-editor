import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Save WAV: the chunks a pipeline produces, written as one file instead of played. */
class WavFileTest {

    private static final AudioFormat FORMAT = new AudioFormat(24000f, 16, 1, true, false);

    private static byte[] silence(AudioFormat format, double seconds) throws Exception {
        int frames = (int) (format.getFrameRate() * seconds);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(new byte[frames * format.getFrameSize()]),
                format, frames), AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }

    private static long framesIn(Path file) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(file.toFile())) {
            assertTrue(FORMAT.matches(ais.getFormat()), ais.getFormat().toString());
            return ais.getFrameLength();
        }
    }

    @Test
    void concatenatesTheChunksIntoOneWav(@TempDir Path dir) throws Exception {
        WavFile wav = new WavFile();
        wav.play(silence(FORMAT, 0.5));
        wav.play(silence(FORMAT, 0.5));
        Path file = dir.resolve("speech.wav");

        wav.write(file, 1.0);

        assertEquals(24000, framesIn(file));
        assertEquals(1.0, wav.seconds(), 1e-6);
    }

    @Test
    void appliesTheSpeedToTheSavedFile(@TempDir Path dir) throws Exception {
        WavFile wav = new WavFile();
        wav.play(silence(FORMAT, 1.0));
        Path file = dir.resolve("fast.wav");

        wav.write(file, 2.0);

        assertEquals(12000, framesIn(file), 120);
    }

    @Test
    void rejectsAChunkInADifferentFormat() throws Exception {
        WavFile wav = new WavFile();
        wav.play(silence(FORMAT, 0.1));
        byte[] other = silence(new AudioFormat(22050f, 16, 1, true, false), 0.1);
        assertThrows(IOException.class, () -> wav.play(other));
    }

    @Test
    void writingNothingFails(@TempDir Path dir) {
        assertThrows(IOException.class, () -> new WavFile().write(dir.resolve("empty.wav"), 1.0));
    }

    @Test
    void addsTheWavExtensionWhenMissing() {
        assertEquals(Path.of("/tmp/speech.wav"), WavFile.withWavExtension(Path.of("/tmp/speech")));
        assertEquals(Path.of("/tmp/speech.wav"), WavFile.withWavExtension(Path.of("/tmp/speech.wav")));
        assertEquals(Path.of("/tmp/speech.WAV"), WavFile.withWavExtension(Path.of("/tmp/speech.WAV")));
    }

    @Test
    void suggestsAFileNameFromTheFirstSentence() {
        assertEquals("Chapter One", WavFile.suggestedName("Chapter One\n\nIt was a dark night."));
        assertEquals("It was a dark night", WavFile.suggestedName("It was a dark night. Then it rained."));
        assertEquals("今天天气很好", WavFile.suggestedName("今天天气很好。明天呢？"));
    }

    @Test
    void suggestedNameIsSafeShortAndNeverEmpty() {
        assertEquals("a b c d", WavFile.suggestedName("a/b:c*d?"));
        assertEquals("speech", WavFile.suggestedName("   \n "));
        String name = WavFile.suggestedName("word ".repeat(30));
        assertTrue(name.length() <= 40 && name.endsWith("word"), name);
    }
}
