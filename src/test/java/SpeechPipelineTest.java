import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechPipelineTest {

    /** Listener that records what happened and lets the test wait for the end. */
    static final class Recording implements SpeechPipeline.Listener {
        final List<String> statuses = new CopyOnWriteArrayList<>();
        final List<String> ends = new CopyOnWriteArrayList<>();
        final CountDownLatch ended = new CountDownLatch(1);
        volatile Throwable failure;

        public void status(String message) { statuses.add(message); }
        public void done() { ends.add("done"); ended.countDown(); }
        public void stopped() { ends.add("stopped"); ended.countDown(); }
        public void failed(Throwable t) { failure = t; ends.add("failed"); ended.countDown(); }

        void awaitEnd() throws InterruptedException {
            assertTrue(ended.await(5, TimeUnit.SECONDS), "pipeline did not finish in time");
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String string(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void playsEveryChunkInOrderThenReportsDone() throws Exception {
        List<String> played = new CopyOnWriteArrayList<>();
        Recording listener = new Recording();
        SpeechPipeline pipeline = new SpeechPipeline(
                chunk -> bytes("wav:" + chunk),
                wav -> played.add(string(wav)),
                () -> { });

        pipeline.start(List.of("one", "two", "three"), listener);
        listener.awaitEnd();

        assertEquals(List.of("wav:one", "wav:two", "wav:three"), played);
        assertEquals(List.of("done"), listener.ends);
    }

    @Test
    void synthesizesTheNextChunkWhileTheCurrentOnePlays() throws Exception {
        CountDownLatch secondSynthStarted = new CountDownLatch(1);
        Recording listener = new Recording();
        SpeechPipeline pipeline = new SpeechPipeline(
                chunk -> {
                    if (chunk.equals("two")) secondSynthStarted.countDown();
                    return bytes(chunk);
                },
                wav -> {
                    if (string(wav).equals("one")) {
                        // Playback of chunk one must not finish before chunk two is being generated.
                        assertTrue(secondSynthStarted.await(2, TimeUnit.SECONDS),
                                "chunk two was not synthesized while chunk one played");
                    }
                },
                () -> { });

        pipeline.start(List.of("one", "two"), listener);
        listener.awaitEnd();

        assertEquals(List.of("done"), listener.ends, () -> "failure: " + listener.failure);
    }

    @Test
    void stopDuringPlaybackSkipsRemainingChunksAndReportsStopped() throws Exception {
        List<String> played = new CopyOnWriteArrayList<>();
        List<String> cancels = new CopyOnWriteArrayList<>();
        Recording listener = new Recording();
        SpeechPipeline[] holder = new SpeechPipeline[1];
        holder[0] = new SpeechPipeline(
                chunk -> bytes(chunk),
                wav -> {
                    played.add(string(wav));
                    holder[0].stop();
                },
                () -> cancels.add("cancel"));

        holder[0].start(List.of("one", "two", "three"), listener);
        listener.awaitEnd();

        assertEquals(List.of("one"), played);
        assertEquals(List.of("stopped"), listener.ends);
        assertEquals(List.of("cancel"), cancels);
    }

    @Test
    void stopWhileWaitingForSynthesisReportsStoppedPromptly() throws Exception {
        CountDownLatch synthStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Recording listener = new Recording();
        SpeechPipeline pipeline = new SpeechPipeline(
                chunk -> {
                    synthStarted.countDown();
                    release.await(10, TimeUnit.SECONDS); // simulates a slow ollama run
                    return bytes(chunk);
                },
                wav -> { },
                release::countDown);

        pipeline.start(List.of("one"), listener);
        assertTrue(synthStarted.await(2, TimeUnit.SECONDS));
        pipeline.stop();
        listener.awaitEnd();

        assertEquals(List.of("stopped"), listener.ends);
    }

    @Test
    void synthesisFailureReportsFailedAndPlaysNothingFurther() throws Exception {
        List<String> played = new CopyOnWriteArrayList<>();
        Recording listener = new Recording();
        SpeechPipeline pipeline = new SpeechPipeline(
                chunk -> {
                    if (chunk.equals("two")) throw new java.io.IOException("ollama exploded");
                    return bytes(chunk);
                },
                wav -> played.add(string(wav)),
                () -> { });

        pipeline.start(List.of("one", "two", "three"), listener);
        listener.awaitEnd();

        assertEquals(List.of("one"), played);
        assertEquals(List.of("failed"), listener.ends);
        assertEquals("ollama exploded", listener.failure.getMessage());
    }

    /** Player that can be paused and resumed and records every call it gets, in order. */
    static final class PausablePlayer implements SpeechPipeline.Player {
        final List<String> events = new CopyOnWriteArrayList<>();
        private final java.util.function.Consumer<String> onPlay;

        PausablePlayer(java.util.function.Consumer<String> onPlay) { this.onPlay = onPlay; }

        public void play(byte[] wav) { events.add("play:" + string(wav)); onPlay.accept(string(wav)); }
        public void pause() { events.add("pause"); }
        public void resume() { events.add("resume"); }
    }

    private static void waitUntilPaused(Recording listener, String status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (!listener.statuses.contains(status) && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue(listener.statuses.contains(status), () -> "never saw '" + status + "' in " + listener.statuses);
    }

    @Test
    void pauseHoldsTheNextChunkUntilResume() throws Exception {
        CountDownLatch secondChunkStarted = new CountDownLatch(1);
        SpeechPipeline[] holder = new SpeechPipeline[1];
        PausablePlayer player = new PausablePlayer(chunk -> {
            if (chunk.equals("one")) holder[0].pause(); // the pause lands while chunk one is playing
            if (chunk.equals("two")) secondChunkStarted.countDown();
        });
        Recording listener = new Recording();
        holder[0] = new SpeechPipeline(chunk -> bytes(chunk), player, () -> { });

        holder[0].start(List.of("one", "two"), listener);
        waitUntilPaused(listener, "Paused at 1/2");

        assertFalse(secondChunkStarted.await(300, TimeUnit.MILLISECONDS), "chunk two must wait while paused");
        holder[0].resume();
        assertTrue(secondChunkStarted.await(2, TimeUnit.SECONDS), "chunk two should play after resume");
        listener.awaitEnd();

        assertEquals(List.of("play:one", "pause", "resume", "play:two"), player.events);
        assertEquals(List.of("done"), listener.ends);
    }

    @Test
    void stopWhilePausedReportsStoppedWithoutPlayingMore() throws Exception {
        SpeechPipeline[] holder = new SpeechPipeline[1];
        PausablePlayer player = new PausablePlayer(chunk -> holder[0].pause());
        Recording listener = new Recording();
        holder[0] = new SpeechPipeline(chunk -> bytes(chunk), player, () -> { });

        holder[0].start(List.of("one", "two"), listener);
        waitUntilPaused(listener, "Paused at 1/2");
        holder[0].stop();
        listener.awaitEnd();

        assertEquals(List.of("stopped"), listener.ends);
        assertEquals(List.of("play:one", "pause"), player.events);
    }
}
