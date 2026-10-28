import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Turns a list of text chunks into audio and plays them back to back. While one chunk plays, the
 * next one is already being synthesized, so playback starts after the first chunk instead of
 * after the whole text. Synthesis and playback are supplied by the caller so the pipeline itself
 * knows nothing about ollama or the sound system.
 */
final class SpeechPipeline {

    interface Synth {
        byte[] synthesize(String chunk) throws Exception;
    }

    /** Plays one WAV and returns when it has finished (or been cancelled). */
    interface Player {
        void play(byte[] wav) throws Exception;
    }

    /** Callbacks arrive on pipeline threads, never on the Swing event thread. */
    interface Listener {
        void status(String message);
        void done();
        void stopped();
        void failed(Throwable t);
    }

    private final Synth synth;
    private final Player player;
    private final Runnable canceller;
    private volatile boolean cancelled;
    private volatile Thread worker;
    private volatile Future<byte[]> pending;

    /**
     * @param canceller called from {@link #stop()} to abort whatever the synth or player is doing
     *                  right now (kill the ollama process, stop the audio clip).
     */
    SpeechPipeline(Synth synth, Player player, Runnable canceller) {
        this.synth = synth;
        this.player = player;
        this.canceller = canceller;
    }

    void start(List<String> chunks, Listener listener) {
        Thread t = new Thread(() -> run(chunks, listener), "speech-pipeline");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** Aborts the current chunk and skips the rest. The listener gets {@code stopped()} once. */
    void stop() {
        cancelled = true;
        Future<byte[]> f = pending;
        if (f != null) f.cancel(true);
        canceller.run();
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    private void run(List<String> chunks, Listener listener) {
        ExecutorService generator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "speech-synth");
            t.setDaemon(true);
            return t;
        });
        try {
            int total = chunks.size();
            listener.status("Generating 1/" + total + "...");
            pending = generator.submit(() -> synth.synthesize(chunks.get(0)));
            for (int i = 0; i < total; i++) {
                byte[] wav = pending.get();
                if (cancelled) break;
                if (i + 1 < total) {
                    String following = chunks.get(i + 1);
                    pending = generator.submit(() -> synth.synthesize(following));
                }
                listener.status("Playing " + (i + 1) + "/" + total + "...");
                player.play(wav);
                if (cancelled) break;
            }
            if (cancelled) listener.stopped(); else listener.done();
        } catch (Throwable e) {
            if (cancelled) {
                listener.stopped();
            } else {
                listener.failed(e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e);
            }
        } finally {
            generator.shutdownNow();
        }
    }
}
