import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chinese text to speech through the macOS {@code say} command for Screen Speaker.
 *
 * {@code say -v Tingting} speaks Mandarin with a voice macOS ships. Instead of letting it play,
 * {@link #synthesize} has it write a WAV in the shape ollama produces (16-bit mono 24 kHz) and
 * returns the bytes, so a {@link SpeechPipeline} with the {@link OllamaSpeech} player treats
 * Chinese exactly like English: the next chunk is synthesized while this one plays, Pause and
 * Resume work mid-sentence, the speed control keeps the pitch, and Save WAV collects the same
 * bytes into a file.
 */
final class SaySpeech {

    static final String SAY = "/usr/bin/say";
    /** The Mandarin voice every Mac has; older systems spell it Ting-Ting. */
    static final String DEFAULT_VOICE = "Tingting";
    /**
     * Characters per synthesis request. A Chinese character is about a syllable, so 100 of them
     * are roughly the twenty seconds of speech that 280 English characters are.
     */
    static final int CHUNK_CHARS = 100;
    /** One line of {@code say -v ?}: a name (maybe followed by a language in parentheses), a locale, a sample. */
    private static final Pattern VOICE_LINE = Pattern.compile("^(.*?)\\s+(\\S+)\\s+#");

    /** Output of {@code say -v ?}, read once per run; empty when it could not be read. */
    private static String listing;
    private volatile Process process;

    /**
     * Runs {@code say} on {@code text} with {@code voiceName} and returns the WAV it wrote. An
     * uninstalled voice is an error here: say itself would silently fall back to the system voice,
     * usually an English one, which cannot read Chinese.
     */
    byte[] synthesize(String text, String voiceName) throws IOException, InterruptedException {
        Path out = Files.createTempFile("screen-speaker-", ".wav");
        try {
            List<String> cmd = command(voiceName, out);
            String v = cmd.get(2);
            if (!isInstalled(v)) {
                throw new IOException("The voice " + v + " is not installed on this Mac. say -v '?' lists the voices.");
            }
            Process p = new ProcessBuilder(cmd).start();
            process = p;
            try (OutputStream in = p.getOutputStream()) {
                in.write(text.getBytes(StandardCharsets.UTF_8));
            }
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("say timed out");
            }
            if (process == p) process = null;
            if (p.exitValue() != 0) {
                throw new IOException("say -v " + v + " exited with " + p.exitValue() + (err.isEmpty() ? "" : ": " + err));
            }
            byte[] wav = Files.readAllBytes(out);
            if (wav.length < 44 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') {
                throw new IOException("say did not write WAV audio (" + wav.length + " bytes)");
            }
            return wav;
        } finally {
            Files.deleteIfExists(out);
        }
    }

    /**
     * The command line: {@code say -v <voice> -o <out> --file-format=WAVE --data-format=LEI16@24000 -f -},
     * reading the text from stdin so no text can be mistaken for an option. A blank voice means
     * {@link #DEFAULT_VOICE}; without {@code -v}, say would use the system voice, usually an English one.
     */
    static List<String> command(String voiceName, Path out) {
        String v = voiceName.isBlank() ? DEFAULT_VOICE : voiceName.strip();
        return new ArrayList<>(List.of(SAY, "-v", v, "-o", out.toString(),
                "--file-format=WAVE", "--data-format=LEI16@24000", "-f", "-"));
    }

    /** Kills a running say process; the pipeline's canceller. */
    void cancel() {
        Process p = process;
        if (p != null) {
            p.destroyForcibly();
            process = null;
        }
    }

    // ---------------------------------------------------------------- voices

    /**
     * The Chinese voices installed on this Mac, from {@code say -v ?} (about a second the first
     * time, so call it off the event thread). Tingting first; just Tingting when the listing fails.
     */
    static List<String> installedChineseVoices() {
        return chineseVoices(listing());
    }

    /** Whether {@code say -v ?} lists {@code voiceName} (in any locale); true when the listing is unavailable. */
    static boolean isInstalled(String voiceName) {
        String l = listing();
        if (l.isEmpty()) return true;
        return voiceNames(l, null).stream().anyMatch(n -> n.equalsIgnoreCase(voiceName));
    }

    private static synchronized String listing() {
        if (listing == null) {
            try {
                Process p = new ProcessBuilder(SAY, "-v", "?").redirectErrorStream(true).start();
                p.getOutputStream().close();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                listing = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : "";
            } catch (IOException | InterruptedException e) {
                listing = "";
            }
        }
        return listing;
    }

    /**
     * The voices with a {@code zh} locale in a {@code say -v ?} listing, once each, in listing
     * order except that Tingting (or Ting-Ting) comes first. Just {@link #DEFAULT_VOICE} when
     * there are none, so the dropdown always offers something.
     */
    static List<String> chineseVoices(String listing) {
        List<String> out = new ArrayList<>();
        for (String n : voiceNames(listing, "zh")) {
            if (n.replace("-", "").equalsIgnoreCase(DEFAULT_VOICE)) out.add(0, n); else out.add(n);
        }
        return out.isEmpty() ? List.of(DEFAULT_VOICE) : out;
    }

    /** Voice names in a listing, once each, in order; only those whose locale starts with {@code localePrefix} if given. */
    private static List<String> voiceNames(String listing, String localePrefix) {
        Set<String> names = new LinkedHashSet<>();
        for (String line : listing.split("\n")) {
            Matcher m = VOICE_LINE.matcher(line);
            if (!m.find()) continue;
            if (localePrefix != null && !m.group(2).startsWith(localePrefix)) continue;
            String name = m.group(1).replaceFirst("\\s*\\(.*\\)$", "").strip();
            if (!name.isEmpty()) names.add(name);
        }
        return new ArrayList<>(names);
    }
}
