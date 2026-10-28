import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits text into sentence-aligned chunks of at most {@code maxChars} characters. Understands
 * English and Chinese punctuation; between two Chinese characters a line break is a wrap, not a
 * word gap, so nothing is put there.
 */
final class TextChunker {

    /**
     * A sentence ends at . ! ? or the Chinese 。！？ (optionally followed by closing quotes or
     * brackets) before whitespace or the next character.
     */
    private static final Pattern SENTENCE =
            Pattern.compile("[^.!?。！？]*(?:[.!?。！？]+[\"'”’)\\]）】」』》]*|$)\\s*");
    /** A blank line (possibly with spaces) separates paragraphs. */
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t]*\\n");
    /**
     * CJK symbols and punctuation, kana, the unified ideographs and the fullwidth forms: text
     * written without word gaps.
     */
    private static final String CJK = "[\\u3000-\\u9fff\\uff00-\\uffef]";
    /** Whitespace with CJK characters on both sides. */
    private static final Pattern CJK_GAP = Pattern.compile("(?<=" + CJK + ")\\s+(?=" + CJK + ")");
    /** Where a long Chinese sentence may be cut when it has no spaces: after a clause mark. */
    private static final String CJK_CLAUSE_MARKS = "，、；：";

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
            String sentence = CJK_GAP.matcher(m.group().strip().replaceAll("\\s+", " ")).replaceAll("");
            if (sentence.isEmpty()) continue;
            for (String piece : splitLongSentence(sentence, maxChars)) {
                String gap = current.length() == 0 ? "" : gap(current.charAt(current.length() - 1), piece.charAt(0));
                if (current.length() > 0 && current.length() + gap.length() + piece.length() > maxChars) {
                    out.add(current.toString());
                    current.setLength(0);
                    gap = "";
                }
                current.append(gap).append(piece);
            }
        }
        if (current.length() > 0) out.add(current.toString());
    }

    /** What goes between two adjacent pieces: nothing when both sides are CJK, else a space. */
    private static String gap(char before, char after) {
        return isCjk(before) && isCjk(after) ? "" : " ";
    }

    private static boolean isCjk(char c) {
        return (c >= 0x3000 && c <= 0x9fff) || (c >= 0xff00 && c <= 0xffef);
    }

    /**
     * Breaks a sentence longer than maxChars at the last space before the limit, or, for text
     * without spaces, after the last Chinese clause mark before it.
     */
    private static List<String> splitLongSentence(String sentence, int maxChars) {
        List<String> pieces = new ArrayList<>();
        String rest = sentence;
        while (rest.length() > maxChars) {
            int cut = Math.max(rest.lastIndexOf(' ', maxChars), lastClauseEnd(rest, maxChars));
            if (cut <= 0) cut = Math.min(maxChars, rest.length());
            pieces.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) pieces.add(rest);
        return pieces;
    }

    /** Index just after the last Chinese clause mark within the first {@code limit} characters, or -1. */
    private static int lastClauseEnd(String s, int limit) {
        for (int i = Math.min(limit, s.length()) - 1; i > 0; i--) {
            if (CJK_CLAUSE_MARKS.indexOf(s.charAt(i)) >= 0) return i + 1;
        }
        return -1;
    }
}
