import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The rule Copy Screen uses to add recognized text to the end of the pane. */
class ScreenSpeakerTest {

    @Test
    void appendToAnEmptyPaneIsJustTheNewText() {
        assertEquals("Hello world", ScreenSpeaker.appendParagraph("", "Hello world"));
        assertEquals("Hello world", ScreenSpeaker.appendParagraph("  \n\n", "Hello world"));
    }

    @Test
    void appendToANonEmptyPaneStartsANewParagraph() {
        assertEquals("First capture.\n\nSecond capture.",
                ScreenSpeaker.appendParagraph("First capture.", "Second capture."));
    }

    @Test
    void appendCollapsesTrailingBlankLinesToExactlyOneParagraphBreak() {
        assertEquals("First capture.\n\nSecond capture.",
                ScreenSpeaker.appendParagraph("First capture.\n\n\n   ", "Second capture."));
    }
}
