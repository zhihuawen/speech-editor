import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
