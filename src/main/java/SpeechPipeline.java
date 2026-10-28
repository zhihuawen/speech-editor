import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Turns a list of text chunks into audio and hands them to a player back to back. While one chunk
 * plays, the next one is already being synthesized, so playback starts after the first chunk
 * instead of after the whole text. Synthesis and playback are supplied by the caller so the
 * pipeline itself knows nothing about ollama, say, the sound system or WAV files.
 *
 * Playback can be paused and resumed. A pause reaches the player right away (so it can hold the
 * current chunk where it is) and also keeps the pipeline from starting the next chunk until
 * {@link #resume()}.
 */
final class SpeechPipeline {

    interface Synth {
        byte[] synthesize(String chunk) throws Exception;
    }

    /** Plays one WAV and returns when it has finished (or been cancelled). */
    interface Player {
        void play(byte[] wav) throws Exception;

        /** Holds the chunk that is playing where it is; {@link #resume()} continues from there. */
        default void pause() {
        }

        default void resume() {
        }
    }

    /**
     * Callbacks arrive on pipeline threads, never on the Swing event thread, except that
     * {@link #status} is also called on whichever thread calls {@link SpeechPipeline#pause()}
     * or {@link SpeechPipeline#resume()}.
     */
    interface Listener {
        void status(String message);
        void done();
        void stopped();
        void failed(Throwable t);
    }

    private final Synth synth;
    private final Player player;
    private final Runnable canceller;
    private final String verb;
    private final Object pauseLock = new Object();
    private volatile boolean cancelled;
    private volatile boolean paused;
    private volatile Thread worker;
    private volatile Future<byte[]> pending;
    private volatile Listener listener;
    private volatile int total;
    /** 1-based index of the chunk being played (or about to be), for status messages. */
    private volatile int current;

    /**
     * @param canceller called from {@link #stop()} to abort whatever the synth or player is doing
     *                  right now (kill the ollama or say process, stop the audio clip).
     */
    SpeechPipeline(Synth synth, Player player, Runnable canceller) {
        this(synth, player, canceller, "Playing");
    }

    /**
     * @param verb what the player does with a chunk, for the status messages: "Playing" for
     *             speakers, "Writing" for a {@link WavFile}.
     */
    SpeechPipeline(Synth synth, Player player, Runnable canceller, String verb) {
        this.synth = synth;
        this.player = player;
        this.canceller = canceller;
        this.verb = verb;
    }

    void start(List<String> chunks, Listener listener) {
        this.listener = listener;
        this.total = chunks.size();
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

    /** Holds playback where it is. Synthesis of upcoming chunks carries on in the background. */
    void pause() {
        synchronized (pauseLock) {
            if (paused || cancelled) return;
            paused = true;
        }
        player.pause();
        listener.status("Paused at " + current + "/" + total);
    }

    /** Continues after {@link #pause()}; does nothing when not paused. */
    void resume() {
        synchronized (pauseLock) {
            if (!paused) return;
            paused = false;
            pauseLock.notifyAll();
        }
        player.resume();
        listener.status(verb + " " + current + "/" + total + "...");
    }

    boolean isPaused() {
        return paused;
    }

    private void awaitResume() throws InterruptedException {
        synchronized (pauseLock) {
            while (paused) pauseLock.wait();
        }
    }

    private void run(List<String> chunks, Listener listener) {
        ExecutorService generator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "speech-synth");
            t.setDaemon(true);
            return t;
        });
        try {
            listener.status("Generating 1/" + total + "...");
            pending = generator.submit(() -> synth.synthesize(chunks.get(0)));
            for (int i = 0; i < total; i++) {
                byte[] wav = pending.get();
                if (cancelled) break;
                if (i + 1 < total) {
                    String following = chunks.get(i + 1);
                    pending = generator.submit(() -> synth.synthesize(following));
                }
                current = i + 1;
                awaitResume(); // a pause pressed between chunks holds the next one here
                listener.status(verb + " " + current + "/" + total + "...");
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
