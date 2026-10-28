import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Plays one second of silence through the real sound system to check pause, resume and cancel. */
class OllamaSpeechPlaybackTest {

    private static final AudioFormat FORMAT = new AudioFormat(24000f, 16, 1, true, false);

    private static byte[] silence(double seconds) throws Exception {
        int frames = (int) (FORMAT.getFrameRate() * seconds);
        byte[] pcm = new byte[frames * FORMAT.getFrameSize()];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(pcm), FORMAT, frames),
                AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }

    private static void assumeSoundOutput() {
        try {
            AudioSystem.getClip().close();
        } catch (Exception | Error e) {
            assumeTrue(false, "no sound output on this machine: " + e);
        }
    }

    /** Runs play() on its own thread, the way the pipeline does. */
    private static Thread playInBackground(OllamaSpeech speech, byte[] wav, Throwable[] failure) {
        Thread t = new Thread(() -> {
            try {
                speech.play(wav);
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "test-player");
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    void pauseHoldsTheClipAndResumeLetsItFinish() throws Exception {
        assumeSoundOutput();
        OllamaSpeech speech = new OllamaSpeech(null, s -> { });
        Throwable[] failure = new Throwable[1];

        Thread player = playInBackground(speech, silence(1.0), failure);
        Thread.sleep(200);
        speech.pause();
        player.join(1500); // well past the clip's one second
        assertTrue(player.isAlive(), "play() returned while paused" + (failure[0] != null ? ": " + failure[0] : ""));

        speech.resume();
        player.join(3000);
        assertFalse(player.isAlive(), "play() did not finish after resume");
        assertNull(failure[0]);
    }

    @Test
    void cancelWhilePausedEndsPlaybackPromptly() throws Exception {
        assumeSoundOutput();
        OllamaSpeech speech = new OllamaSpeech(null, s -> { });
        Throwable[] failure = new Throwable[1];

        Thread player = playInBackground(speech, silence(1.0), failure);
        Thread.sleep(200);
        speech.pause();
        Thread.sleep(100);
        speech.cancel();
        player.join(2000);

        assertFalse(player.isAlive(), "play() kept waiting after cancel");
        assertNull(failure[0]);
    }

    @Test
    void fasterRatePlaysTheClipInLessTime() throws Exception {
        assumeSoundOutput();
        OllamaSpeech speech = new OllamaSpeech(null, s -> { });
        speech.setRate(2.0);
        Throwable[] failure = new Throwable[1];

        Thread player = playInBackground(speech, silence(2.0), failure);
        player.join(1500); // the clip is two seconds long at normal speed

        assertFalse(player.isAlive(), "two seconds of audio at 2x should be done in about one");
        assertNull(failure[0]);
    }

    @Test
    void changingTheRateTakesEffectInTheMiddleOfAClip() throws Exception {
        assumeSoundOutput();
        OllamaSpeech speech = new OllamaSpeech(null, s -> { });
        Throwable[] failure = new Throwable[1];

        Thread player = playInBackground(speech, silence(3.0), failure);
        Thread.sleep(300);
        speech.setRate(3.0); // about 2.7 s left, which is 0.9 s at 3x
        player.join(2000);

        assertFalse(player.isAlive(), "the rest of the clip should have played at 3x");
        assertNull(failure[0]);
    }
}
