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

    // ---------------------------------------------------------------- Chinese

    @Test
    void splitsChineseTextAtItsSentenceMarks() {
        List<String> chunks = TextChunker.chunks("第一句。第二句！第三句？第四句。", 5);
        assertEquals(List.of("第一句。", "第二句！", "第三句？", "第四句。"), chunks);
    }

    @Test
    void joinsChineseSentencesWithoutASpace() {
        assertEquals(List.of("你好。再见。"), TextChunker.chunks("你好。再见。", 20));
    }

    @Test
    void dropsLineBreaksBetweenChineseCharacters() {
        // OCR of a wrapped paragraph puts a line break in the middle of a sentence; Chinese has
        // no word gaps, so nothing should be inserted there.
        assertEquals(List.of("今天天气很好。"), TextChunker.chunks("今天天气\n很好。", 20));
    }

    @Test
    void keepsTheSpaceBetweenLatinAndChinese() {
        assertEquals(List.of("使用 Java 语言。"), TextChunker.chunks("使用 Java\n语言。", 20));
    }

    @Test
    void splitsAnOverlongChineseSentenceAtACommaBeforeTheLimit() {
        List<String> chunks = TextChunker.chunks("一二三，四五六，七八九", 7);
        assertEquals(List.of("一二三，", "四五六，七八九"), chunks);
    }
}
