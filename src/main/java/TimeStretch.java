import javax.sound.sampled.AudioFormat;
import java.util.Arrays;

/**
 * Changes the speed of speech without changing its pitch. WSOLA (waveform similarity overlap-add):
 * the output is assembled from short Hann-windowed pieces of the input, each taken from near its
 * nominal position (output time times rate) at the offset that best continues the previous piece,
 * so the waveform stays coherent where pieces overlap. Pure Java; 16-bit mono PCM only.
 */
final class TimeStretch {

    /** Length of one piece: a few pitch periods, short enough that speech keeps its rhythm. */
    private static final double WINDOW_SECONDS = 0.024;
    /** How far from its nominal position a piece may be taken to line up with the previous one. */
    private static final double TOLERANCE_SECONDS = 0.006;

    private TimeStretch() {
    }

    /** True for the formats {@link #stretch} can change; others pass through at normal speed. */
    static boolean supports(AudioFormat format) {
        return format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                && format.getSampleSizeInBits() == 16 && format.getChannels() == 1;
    }

    /**
     * {@code length} bytes of {@code pcm} from {@code offset}, played {@code rate} times as fast:
     * rate 2 halves the duration, rate 0.5 doubles it. Returns a copy of the range for rate 1 and
     * for formats other than 16-bit signed mono PCM.
     */
    static byte[] stretch(byte[] pcm, int offset, int length, AudioFormat format, double rate) {
        if (rate == 1.0 || !supports(format)) return Arrays.copyOfRange(pcm, offset, offset + length);
        boolean bigEndian = format.isBigEndian();
        float[] in = new float[length / 2];
        for (int i = 0; i < in.length; i++) {
            int lo = pcm[offset + 2 * i] & 0xff, hi = pcm[offset + 2 * i + 1] & 0xff;
            in[i] = (short) (bigEndian ? (lo << 8) | hi : (hi << 8) | lo);
        }
        float[] out = wsola(in, Math.round(format.getSampleRate()), rate);
        byte[] bytes = new byte[out.length * 2];
        for (int i = 0; i < out.length; i++) {
            int v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(out[i])));
            bytes[2 * i] = (byte) (bigEndian ? v >> 8 : v);
            bytes[2 * i + 1] = (byte) (bigEndian ? v : v >> 8);
        }
        return bytes;
    }

    /** Samples of {@code x} played {@code rate} times as fast, same pitch. */
    static float[] wsola(float[] x, int sampleRate, double rate) {
        int n = x.length;
        int window = Math.max(4, (int) Math.round(sampleRate * WINDOW_SECONDS) & ~1);
        int hop = window / 2; // Hann windows at 50% overlap add up to one
        int tolerance = (int) Math.round(sampleRate * TOLERANCE_SECONDS);
        int outLength = (int) Math.round(n / rate);
        float[] w = new float[window];
        for (int i = 0; i < window; i++) w[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / window));

        float[] sum = new float[outLength + window];
        float[] weight = new float[outLength + window];
        int previous = -1;
        for (int outPos = 0; outPos < outLength; outPos += hop) {
            int nominal = (int) Math.round(outPos * rate);
            int pos = previous < 0 ? Math.max(0, Math.min(nominal, n - 1))
                    : bestContinuation(x, previous + hop, nominal, tolerance, hop);
            for (int i = 0; i < window && pos + i < n; i++) {
                sum[outPos + i] += w[i] * x[pos + i];
                weight[outPos + i] += w[i];
            }
            previous = pos;
        }
        float[] out = new float[outLength];
        for (int i = 0; i < outLength; i++) out[i] = weight[i] > 1e-3f ? sum[i] / weight[i] : sum[i];
        return out;
    }

    /**
     * Start of the piece within {@code tolerance} of {@code nominal} whose first {@code length}
     * samples look most like {@code reference}, the input that naturally follows the previous
     * piece. Normalized cross-correlation, so loud and quiet candidates compete fairly.
     */
    private static int bestContinuation(float[] x, int reference, int nominal, int tolerance, int length) {
        int n = x.length;
        int low = Math.max(0, nominal - tolerance);
        int high = Math.min(n - length, nominal + tolerance);
        if (high < low) return Math.max(0, Math.min(nominal, n - 1)); // near the end: nothing to compare
        int best = Math.max(low, Math.min(nominal, high));
        if (reference + length > n) return best;
        double refEnergy = 0;
        for (int i = 0; i < length; i++) refEnergy += (double) x[reference + i] * x[reference + i];
        if (refEnergy < 1e-6) return best; // silence: any position continues it
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int cand = low; cand <= high; cand++) {
            double dot = 0, energy = 0;
            for (int i = 0; i < length; i++) {
                float a = x[cand + i];
                dot += (double) a * x[reference + i];
                energy += (double) a * a;
            }
            double score = energy > 0 ? dot / Math.sqrt(energy * refEnergy) : 0;
            if (score > bestScore) {
                bestScore = score;
                best = cand;
            }
        }
        return best;
    }
}
