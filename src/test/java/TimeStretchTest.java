import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeStretchTest {

    private static final AudioFormat MONO_16 = new AudioFormat(24000f, 16, 1, true, false);

    /** Little-endian 16-bit mono sine, the shape phonon's WAV takes. */
    private static byte[] sine(double hz, double seconds) {
        int n = (int) (MONO_16.getSampleRate() * seconds);
        ByteBuffer b = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            b.putShort((short) (12000 * Math.sin(2 * Math.PI * hz * i / MONO_16.getSampleRate())));
        }
        return b.array();
    }

    private static short[] middleHalf(byte[] pcm) {
        ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        int n = pcm.length / 2;
        short[] out = new short[n / 2];
        for (int i = 0; i < out.length; i++) out[i] = b.getShort((n / 4 + i) * 2);
        return out;
    }

    /** Frequency estimate from zero crossings over the middle half of the signal. */
    private static double frequency(byte[] pcm) {
        short[] s = middleHalf(pcm);
        int crossings = 0;
        for (int i = 1; i < s.length; i++) {
            if ((s[i - 1] < 0) != (s[i] < 0)) crossings++;
        }
        return crossings / 2.0 / (s.length / MONO_16.getSampleRate());
    }

    private static double rms(byte[] pcm) {
        short[] s = middleHalf(pcm);
        double sum = 0;
        for (short v : s) sum += (double) v * v;
        return Math.sqrt(sum / s.length);
    }

    @Test
    void rateOneReturnsTheRangeUnchanged() {
        byte[] pcm = sine(440, 0.5);

        byte[] out = TimeStretch.stretch(pcm, 100, pcm.length - 100, MONO_16, 1.0);

        assertArrayEquals(Arrays.copyOfRange(pcm, 100, pcm.length), out);
    }

    @Test
    void fasterRateShortensTheAudioByThatFactor() {
        byte[] pcm = sine(440, 1.0);

        byte[] out = TimeStretch.stretch(pcm, 0, pcm.length, MONO_16, 2.0);

        assertEquals(pcm.length / 2, out.length, 2 * 48); // within 2 ms
    }

    @Test
    void slowerRateLengthensTheAudioByThatFactor() {
        byte[] pcm = sine(440, 1.0);

        byte[] out = TimeStretch.stretch(pcm, 0, pcm.length, MONO_16, 0.5);

        assertEquals(pcm.length * 2, out.length, 2 * 48);
    }

    @Test
    void fasterPlaybackKeepsThePitch() {
        byte[] pcm = sine(440, 1.0);

        byte[] out = TimeStretch.stretch(pcm, 0, pcm.length, MONO_16, 1.5);

        assertEquals(440, frequency(out), 440 * 0.03); // plain resampling would give 660
    }

    @Test
    void slowerPlaybackKeepsThePitch() {
        byte[] pcm = sine(440, 1.0);

        byte[] out = TimeStretch.stretch(pcm, 0, pcm.length, MONO_16, 0.75);

        assertEquals(440, frequency(out), 440 * 0.03); // plain resampling would give 330
    }

    @Test
    void keepsTheLoudness() {
        byte[] pcm = sine(440, 1.0);

        byte[] out = TimeStretch.stretch(pcm, 0, pcm.length, MONO_16, 1.5);

        assertEquals(rms(pcm), rms(out), rms(pcm) * 0.15);
    }

    @Test
    void formatsOtherThanSixteenBitMonoPassThroughUnchanged() {
        AudioFormat stereo = new AudioFormat(24000f, 16, 2, true, false);
        byte[] pcm = sine(440, 0.1);

        assertArrayEquals(pcm, TimeStretch.stretch(pcm, 0, pcm.length, stereo, 2.0));
    }
}
