import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits text into sentence-aligned chunks of at most {@code maxChars} characters. */
final class TextChunker {

    /** A sentence ends at . ! or ? (optionally followed by closing quotes/brackets) before whitespace. */
    private static final Pattern SENTENCE = Pattern.compile("[^.!?]*(?:[.!?]+[\"'”’)\\]]*|$)\\s*");
    /** A blank line (possibly with spaces) separates paragraphs. */
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t]*\\n");

    private TextChunker() {
    }

    static List<String> chunks(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        for (String paragraph : PARAGRAPH_BREAK.split(text)) {
            chunkParagraph(paragraph, maxChars, out);
        }
        return out;
    }

    private static void chunkParagraph(String paragraph, int maxChars, List<String> out) {
        StringBuilder current = new StringBuilder();
        Matcher m = SENTENCE.matcher(paragraph);
        while (m.find()) {
            String sentence = m.group().strip().replaceAll("\\s+", " ");
            if (sentence.isEmpty()) continue;
            for (String piece : splitLongSentence(sentence, maxChars)) {
                if (current.length() > 0 && current.length() + 1 + piece.length() > maxChars) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) current.append(' ');
                current.append(piece);
            }
        }
        if (current.length() > 0) out.add(current.toString());
    }

    /** Breaks a sentence longer than maxChars at the last space before the limit. */
    private static List<String> splitLongSentence(String sentence, int maxChars) {
        List<String> pieces = new ArrayList<>();
        String rest = sentence;
        while (rest.length() > maxChars) {
            int cut = rest.lastIndexOf(' ', maxChars);
            if (cut <= 0) cut = Math.min(maxChars, rest.length());
            pieces.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) pieces.add(rest);
        return pieces;
    }
}
