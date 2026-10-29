import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A system-wide keyboard shortcut that triggers Copy Screen from any app. Java cannot register one
 * itself, so a small Swift helper (src/main/swift/hotkey.swift, bundled into the jar like the OCR
 * helper) registers it with Carbon's RegisterEventHotKey, which needs no Accessibility permission,
 * and prints a line for every press; this class runs the helper and turns the lines into callbacks.
 * The helper exits when its stdin closes, so it never outlives the app.
 */
final class GlobalHotkey implements AutoCloseable {

    /** Classpath location of the helper binary that Gradle bundles into the jar. */
    static final String RESOURCE = "/bin/hotkey";
    /** {@code -Dscreen.hotkey=<spec>} picks another key, for example {@code alt+cmd+r}; {@code none} turns it off. */
    static final String PROPERTY = "screen.hotkey";
    /** Next to the macOS screenshot keys ⇧⌘3, ⇧⌘4 and ⇧⌘5, and unused by macOS itself. */
    static final String DEFAULT_SPEC = "shift+cmd+2";

    /** A key as Carbon wants it: virtual key code and modifier bits, plus a label such as ⇧⌘2. */
    record Key(int keyCode, int modifiers, String label) {
    }

    // Carbon modifier bits (cmdKey, shiftKey, optionKey, controlKey).
    private static final int CMD = 0x100, SHIFT = 0x200, OPTION = 0x800, CONTROL = 0x1000;
    private static final Map<String, Integer> MODIFIERS = Map.ofEntries(
            Map.entry("cmd", CMD), Map.entry("command", CMD), Map.entry("meta", CMD), Map.entry("⌘", CMD),
            Map.entry("shift", SHIFT), Map.entry("⇧", SHIFT),
            Map.entry("alt", OPTION), Map.entry("option", OPTION), Map.entry("opt", OPTION), Map.entry("⌥", OPTION),
            Map.entry("ctrl", CONTROL), Map.entry("control", CONTROL), Map.entry("⌃", CONTROL));
    /** Carbon virtual key codes (kVK_ANSI_* and kVK_F*), hex, by key name. They follow the keyboard, not the alphabet. */
    private static final String KEY_CODES =
            "a=00 s=01 d=02 f=03 h=04 g=05 z=06 x=07 c=08 v=09 b=0b q=0c w=0d e=0e r=0f y=10 t=11 "
            + "1=12 2=13 3=14 4=15 6=16 5=17 9=19 7=1a 8=1c 0=1d o=1f u=20 i=22 p=23 l=25 j=26 k=28 n=2d m=2e "
            + "space=31 f1=7a f2=78 f3=63 f4=76 f5=60 f6=61 f7=62 f8=64 f9=65 f10=6d f11=67 f12=6f";
    private static final Map<String, Integer> KEYS = keyCodes();

    private final Process process;
    private volatile boolean closed;

    private GlobalHotkey(Process process) {
        this.process = process;
    }

    // ---------------------------------------------------------------- key specs

    /**
     * Reads a spec such as {@code shift+cmd+2}, {@code ctrl+alt+r} or {@code ⌃⌥⌘R}: modifiers in any
     * order and one key (a letter, a digit, {@code space} or {@code f1}..{@code f12}). Anything but a
     * function key needs at least one modifier, or it would swallow ordinary typing everywhere.
     */
    static Key parse(String spec) {
        int modifiers = 0;
        String keyName = null;
        for (String token : spec.replaceAll("([⌘⇧⌥⌃])", "$1+").split("\\+")) {
            String t = token.strip().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            Integer bit = MODIFIERS.get(t);
            if (bit != null) {
                modifiers |= bit;
            } else if (keyName != null) {
                throw new IllegalArgumentException("'" + spec + "' names two keys, " + keyName + " and " + t);
            } else {
                keyName = t;
            }
        }
        if (keyName == null) throw new IllegalArgumentException("'" + spec + "' names no key");
        Integer code = KEYS.get(keyName);
        if (code == null) throw new IllegalArgumentException("unknown key '" + keyName + "' in '" + spec + "'");
        if (modifiers == 0 && !keyName.matches("f\\d+")) {
            throw new IllegalArgumentException("'" + spec + "' needs a modifier (cmd, shift, alt or ctrl) to be a system-wide shortcut");
        }
        return new Key(code, modifiers, label(modifiers, keyName));
    }

    /** Modifiers in the order macOS menus show them, then the key: ⌃⌥⇧⌘R. */
    private static String label(int modifiers, String keyName) {
        StringBuilder sb = new StringBuilder();
        if ((modifiers & CONTROL) != 0) sb.append('⌃');
        if ((modifiers & OPTION) != 0) sb.append('⌥');
        if ((modifiers & SHIFT) != 0) sb.append('⇧');
        if ((modifiers & CMD) != 0) sb.append('⌘');
        return sb.append(keyName.equals("space") ? "Space" : keyName.toUpperCase(Locale.ROOT)).toString();
    }

    private static Map<String, Integer> keyCodes() {
        Map<String, Integer> codes = new HashMap<>();
        for (String entry : KEY_CODES.split(" ")) {
            String[] kv = entry.split("=");
            codes.put(kv[0], Integer.parseInt(kv[1], 16));
        }
        return codes;
    }

    // ---------------------------------------------------------------- the helper

    /** The bundled helper, unpacked into the user's cache next to the OCR helper. */
    static Path locate() throws IOException {
        return locate(OcrHelper.cacheDir());
    }

    static Path locate(Path cacheDir) throws IOException {
        return OcrHelper.unpack(RESOURCE, cacheDir);
    }

    /**
     * Starts {@code helper} for {@code key} and returns once the key is registered. From then on
     * every press calls {@code onPress} (on a background thread). If the helper dies before
     * {@link #close()}, {@code onFailure} gets its last words once.
     *
     * @throws IOException when the helper cannot be run or refuses the key, with its message
     */
    static GlobalHotkey start(Path helper, Key key, Runnable onPress, Consumer<String> onFailure) throws IOException {
        Process p = new ProcessBuilder(helper.toString(), Integer.toString(key.keyCode()), Integer.toString(key.modifiers()))
                .start();
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        if (!"ready".equals(out.readLine())) {
            String err = "";
            try {
                if (p.waitFor(5, TimeUnit.SECONDS)) err = stderrOf(p); // before destroy(), which closes the pipes
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            p.destroy();
            throw new IOException(err.isEmpty() ? "the hotkey helper ended without registering " + key.label() : err);
        }
        GlobalHotkey hotkey = new GlobalHotkey(p);
        Thread pump = new Thread(() -> hotkey.pump(out, onPress, onFailure), "global hotkey " + key.label());
        pump.setDaemon(true);
        pump.start();
        return hotkey;
    }

    private void pump(BufferedReader out, Runnable onPress, Consumer<String> onFailure) {
        try {
            String line;
            while ((line = out.readLine()) != null) {
                if (line.equals("pressed")) onPress.run();
            }
        } catch (IOException ignored) {
            // the pipe is gone: reported below unless we closed it ourselves
        }
        if (closed) return;
        String err;
        int code;
        try {
            err = stderrOf(process);
            code = process.waitFor();
        } catch (IOException e) {
            err = "";
            code = -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        onFailure.accept(err.isEmpty() ? "the hotkey helper exited (" + code + ")" : err);
    }

    private static String stderrOf(Process p) throws IOException {
        return new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    }

    /** Unregisters the key: closing the helper's stdin makes it exit; a helper that lingers is killed. */
    @Override
    public void close() {
        closed = true;
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // already gone
        }
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroy();
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
        }
    }
}
