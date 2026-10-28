import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs the real Swift helper (built by Gradle, path in -Docr.helper) on images drawn with Java2D. */
class OcrHelperRecognizeTest {

    private static Path helper() {
        String p = System.getProperty("ocr.helper");
        assumeTrue(p != null && !p.isBlank(), "ocr.helper not set; the Vision helper only builds on macOS");
        Path helper = Path.of(p);
        assertTrue(Files.isExecutable(helper), "OCR helper was not built at " + helper);
        return helper;
    }

    /** Draws black text lines on white at the given baselines (y pixels), 40 px tall. */
    private static Path draw(Path dir, int height, Object... linesAndBaselines) throws Exception {
        BufferedImage img = new BufferedImage(900, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 40));
        for (int i = 0; i < linesAndBaselines.length; i += 2) {
            g.drawString((String) linesAndBaselines[i], 40, (Integer) linesAndBaselines[i + 1]);
        }
        g.dispose();
        Path png = dir.resolve("text.png");
        ImageIO.write(img, "png", png.toFile());
        return png;
    }

    @Test
    void recognizesLinesTopToBottom(@TempDir Path dir) throws Exception {
        Path png = draw(dir, 200, "Hello world", 70, "Second line here", 140);

        String text = OcrHelper.recognize(helper(), png);

        assertEquals(List.of("Hello world", "Second line here"), text.strip().lines().toList());
    }

    @Test
    void marksALargeVerticalGapAsAParagraphBreak(@TempDir Path dir) throws Exception {
        // Two single-spaced lines (48 px apart at 40 px) and a third line a full blank line lower.
        Path png = draw(dir, 300, "Line one here", 70, "Line two here", 118, "New paragraph", 230);

        String text = OcrHelper.recognize(helper(), png);

        assertEquals("Line one here\nLine two here\n\nNew paragraph", text.strip());
    }

    @Test
    void findsParagraphBreaksWhenShortParagraphsMakeBigGapsCommon(@TempDir Path dir) throws Exception {
        // Heading, two 2-line paragraphs, heading, 2-line paragraph: more than half of the gaps are
        // paragraph gaps, so a "typical gap" taken from the median would itself be a paragraph gap.
        // Lines 56 px apart inside a paragraph (line-height 1.4), 96 px (one extra line) between blocks.
        Path png = draw(dir, 700,
                "Why the sky is blue", 70,
                "Sunlight is scattered by the air.", 166, "Blue light scatters the most.", 222,
                "So the sky looks blue to us.", 318, "Near the horizon it turns pale.", 374,
                "Sunsets", 470,
                "At sunset light crosses more air.", 566, "Only the reds and oranges remain.", 622);

        String text = OcrHelper.recognize(helper(), png);

        assertEquals(String.join("\n",
                "Why the sky is blue", "",
                "Sunlight is scattered by the air.", "Blue light scatters the most.", "",
                "So the sky looks blue to us.", "Near the horizon it turns pale.", "",
                "Sunsets", "",
                "At sunset light crosses more air.", "Only the reds and oranges remain."), text.strip());
    }
}
