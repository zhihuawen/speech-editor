import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextChunkerTest {

    @Test
    void splitsAtSentenceBoundariesWhenTextExceedsLimit() {
        List<String> chunks = TextChunker.chunks("First sentence. Second one! Third? Fourth.", 20);
        assertEquals(List.of("First sentence.", "Second one! Third?", "Fourth."), chunks);
    }

    @Test
    void joinsLineBreaksInsideAParagraphWithSpaces() {
        List<String> chunks = TextChunker.chunks("The quick brown\nfox jumps over\nthe lazy dog.", 200);
        assertEquals(List.of("The quick brown fox jumps over the lazy dog."), chunks);
    }

    @Test
    void blankLinesEndAChunkEvenWithoutPunctuation() {
        List<String> chunks = TextChunker.chunks("Chapter One\n\nIt was a dark night.", 200);
        assertEquals(List.of("Chapter One", "It was a dark night."), chunks);
    }

    @Test
    void splitsAnOverlongSentenceAtWordBoundaries() {
        List<String> chunks = TextChunker.chunks("alpha beta gamma delta", 11);
        assertEquals(List.of("alpha beta", "gamma delta"), chunks);
    }

    @Test
    void returnsNoChunksForBlankText() {
        assertEquals(List.of(), TextChunker.chunks("  \n\n \t", 100));
    }
}
