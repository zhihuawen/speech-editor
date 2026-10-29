import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The system-wide shortcut: how a key spec is read, and how the app talks to the helper that registers it. */
class GlobalHotkeyTest {

    // Carbon modifier bits, as RegisterEventHotKey wants them.
    private static final int CMD = 0x100, SHIFT = 0x200, OPTION = 0x800, CONTROL = 0x1000;

    // ------------------------------------------------------------ parsing

    @Test
    void parsesShiftCommandDigit() {
        GlobalHotkey.Key key = GlobalHotkey.parse("shift+cmd+2");

        assertEquals(0x13, key.keyCode(), "kVK_ANSI_2");
        assertEquals(SHIFT | CMD, key.modifiers());
        assertEquals("⇧⌘2", key.label());
    }

    @Test
    void parsesLettersAndModifierSynonymsInAnyOrder() {
        GlobalHotkey.Key a = GlobalHotkey.parse("cmd+alt+r");
        GlobalHotkey.Key b = GlobalHotkey.parse("Option+Command+R");

        assertEquals(0x0F, a.keyCode(), "kVK_ANSI_R");
        assertEquals(OPTION | CMD, a.modifiers());
        assertEquals("⌥⌘R", a.label());
        assertEquals(a, b);
    }

    @Test
    void parsesFunctionKeysAndControl() {
        GlobalHotkey.Key key = GlobalHotkey.parse("ctrl+f5");

        assertEquals(0x60, key.keyCode(), "kVK_F5");
        assertEquals(CONTROL, key.modifiers());
        assertEquals("⌃F5", key.label());
    }

    @Test
    void acceptsTheMacModifierSymbolsAndLabelsThemInMacOrder() {
        GlobalHotkey.Key key = GlobalHotkey.parse("⌘⌥⌃r");

        assertEquals(CONTROL | OPTION | CMD, key.modifiers());
        assertEquals("⌃⌥⌘R", key.label());
    }

    @Test
    void allowsABareFunctionKey() {
        assertEquals(0, GlobalHotkey.parse("f6").modifiers());
    }

    @Test
    void rejectsALetterWithoutAModifier() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> GlobalHotkey.parse("r"));
        assertTrue(e.getMessage().contains("modifier"), e.getMessage());
    }

    @Test
    void rejectsAnUnknownKeyName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> GlobalHotkey.parse("cmd+hyper"));
        assertTrue(e.getMessage().contains("hyper"), e.getMessage());
    }

    @Test
    void theDefaultSpecParses() {
        assertEquals("⇧⌘2", GlobalHotkey.parse(GlobalHotkey.DEFAULT_SPEC).label());
    }

    // ------------------------------------------------------------ the user's choice

    @Test
    void everyPresetParsesAndTheDefaultIsOneOfThem() {
        for (String preset : GlobalHotkey.PRESETS) GlobalHotkey.parse(preset);
        assertTrue(GlobalHotkey.PRESETS.contains(GlobalHotkey.DEFAULT_SPEC));
    }

    @Test
    void noneAndOffMeanNoShortcut() {
        assertTrue(GlobalHotkey.isOff("none"));
        assertTrue(GlobalHotkey.isOff(" OFF "));
        assertTrue(GlobalHotkey.isOff(""));
        assertTrue(!GlobalHotkey.isOff("shift+cmd+2"));
    }

    /** A scratch preferences node, removed afterwards, and the system property put back as it was. */
    private static void withChoiceStore(ThrowingConsumer<java.util.prefs.Preferences> body) throws Exception {
        java.util.prefs.Preferences node = java.util.prefs.Preferences.userRoot().node("dev/zhihua/screen-speaker-test");
        String property = System.getProperty(GlobalHotkey.PROPERTY);
        System.clearProperty(GlobalHotkey.PROPERTY);
        try {
            body.accept(node);
        } finally {
            node.removeNode();
            if (property != null) System.setProperty(GlobalHotkey.PROPERTY, property);
        }
    }

    interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }

    @Test
    void theChoiceIsTheDefaultUntilSomethingIsRemembered() throws Exception {
        withChoiceStore(node -> assertEquals(GlobalHotkey.DEFAULT_SPEC, GlobalHotkey.currentSpec(node)));
    }

    @Test
    void aRememberedChoiceComesBackNextTime() throws Exception {
        withChoiceStore(node -> {
            GlobalHotkey.remember(node, "alt+cmd+r");
            assertEquals("alt+cmd+r", GlobalHotkey.currentSpec(node));
            GlobalHotkey.remember(node, "none");
            assertEquals("none", GlobalHotkey.currentSpec(node));
        });
    }

    @Test
    void theSystemPropertyOverridesTheRememberedChoice() throws Exception {
        withChoiceStore(node -> {
            GlobalHotkey.remember(node, "alt+cmd+r");
            System.setProperty(GlobalHotkey.PROPERTY, "ctrl+f5");
            assertEquals("ctrl+f5", GlobalHotkey.currentSpec(node));
        });
    }

    // ------------------------------------------------------------ talking to the helper (fake helpers)

    private static Path script(Path dir, String body) throws IOException {
        Path file = dir.resolve("fake-hotkey");
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        file.toFile().setExecutable(true);
        return file;
    }

    @Test
    void startReturnsOnceTheHelperIsReadyAndCloseEndsItQuietly(@TempDir Path dir) throws Exception {
        Path helper = script(dir, "echo ready\ncat >/dev/null"); // lives until stdin closes, like the real one
        List<String> failures = new ArrayList<>();

        GlobalHotkey hotkey = GlobalHotkey.start(helper, GlobalHotkey.parse("shift+cmd+2"), () -> {}, failures::add);
        hotkey.close();
        Thread.sleep(200);

        assertEquals(List.of(), failures, "a deliberate close is not a failure");
    }

    @Test
    void everyPressLineBecomesACallback(@TempDir Path dir) throws Exception {
        Path helper = script(dir, "echo ready\necho pressed\necho pressed\ncat >/dev/null");
        CountDownLatch presses = new CountDownLatch(2);

        try (GlobalHotkey ignored = GlobalHotkey.start(helper, GlobalHotkey.parse("shift+cmd+2"), presses::countDown, f -> {})) {
            assertTrue(presses.await(5, TimeUnit.SECONDS), "two presses expected");
        }
    }

    @Test
    void startFailsWithTheHelpersMessageWhenItCannotRegisterTheKey(@TempDir Path dir) throws Exception {
        Path helper = script(dir, "echo 'hotkey: cannot register (error -9878)' >&2\nexit 1");

        IOException e = assertThrows(IOException.class,
                () -> GlobalHotkey.start(helper, GlobalHotkey.parse("shift+cmd+2"), () -> {}, f -> {}));

        assertTrue(e.getMessage().contains("cannot register"), e.getMessage());
    }

    @Test
    void aHelperThatDiesLaterIsReportedOnce(@TempDir Path dir) throws Exception {
        Path helper = script(dir, "echo ready\nsleep 0.2\necho 'hotkey: lost the event loop' >&2\nexit 3");
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);

        try (GlobalHotkey ignored = GlobalHotkey.start(helper, GlobalHotkey.parse("shift+cmd+2"), () -> {},
                f -> { failure.set(f); reported.countDown(); })) {
            assertTrue(reported.await(5, TimeUnit.SECONDS), "the death should be reported");
        }

        assertTrue(failure.get().contains("lost the event loop"), failure.get());
    }

    // ------------------------------------------------------------ the real helper (macOS builds)

    private static Path bundledHelper() {
        String path = System.getProperty("hotkey.helper");
        assumeTrue(path != null && Files.isExecutable(Path.of(path)), "the Swift helper is built on macOS only");
        return Path.of(path);
    }

    @Test
    void theBundledHelperRegistersAKeyAndExitsWhenStdinCloses() throws Exception {
        GlobalHotkey.Key key = GlobalHotkey.parse("ctrl+alt+cmd+f12"); // not the default: that may be live in the app
        Process p = new ProcessBuilder(bundledHelper().toString(),
                String.valueOf(key.keyCode()), String.valueOf(key.modifiers())).start();

        String first = new String(p.getInputStream().readNBytes(6), java.nio.charset.StandardCharsets.UTF_8);
        p.getOutputStream().close();

        assertEquals("ready\n", first);
        assertTrue(p.waitFor(5, TimeUnit.SECONDS), "helper should exit when its stdin closes");
        assertEquals(0, p.exitValue());
    }

    @Test
    void theBundledHelperRefusesBadArguments() throws Exception {
        Process p = new ProcessBuilder(bundledHelper().toString(), "not-a-number").start();
        String err = new String(p.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(p.waitFor(5, TimeUnit.SECONDS));
        assertEquals(1, p.exitValue());
        assertTrue(err.contains("usage"), err);
    }

    @Test
    void theBundledHelperWorksThroughStart() throws Exception {
        List<String> failures = new ArrayList<>();

        GlobalHotkey hotkey = GlobalHotkey.start(bundledHelper(), GlobalHotkey.parse("ctrl+alt+cmd+f12"), () -> {}, failures::add);
        hotkey.close();
        Thread.sleep(200);

        assertEquals(List.of(), failures);
    }

    /**
     * The one test that presses the key: src/test/swift/press.swift posts the modifier keys and the
     * key at the HID level, which needs Accessibility trust for the terminal running the tests, and
     * the helper must report the press. Three modifiers keep the test key out of everyone's way.
     */
    @Test
    void aPressOfTheRegisteredKeyReachesTheListener() throws Exception {
        Path helper = bundledHelper();
        Path press = Path.of("src/test/swift/press.swift").toAbsolutePath();
        assumeTrue(Files.isRegularFile(press), "run from the project directory");
        GlobalHotkey.Key key = GlobalHotkey.parse("ctrl+alt+cmd+h");
        CountDownLatch pressed = new CountDownLatch(1);
        List<String> failures = new ArrayList<>();

        try (GlobalHotkey ignored = GlobalHotkey.start(helper, key, pressed::countDown, failures::add)) {
            Process p = new ProcessBuilder("swift", press.toString(),
                    String.valueOf(key.keyCode()), String.valueOf(key.modifiers())).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            assertTrue(p.waitFor(2, TimeUnit.MINUTES), "swift should finish");
            assumeTrue(p.exitValue() != 2, "posting key events needs Accessibility trust for the terminal running the tests");
            assertEquals(0, p.exitValue(), out);

            assertTrue(pressed.await(5, TimeUnit.SECONDS), "the helper should report the press; failures so far: " + failures);
        }
    }

    @Test
    void locateUnpacksTheBundledHelperIntoTheCache(@TempDir Path cache) throws Exception {
        assumeTrue(GlobalHotkey.class.getResource(GlobalHotkey.RESOURCE) != null, "bundled helper only exists on macOS builds");

        Path helper = GlobalHotkey.locate(cache);

        assertTrue(helper.startsWith(cache));
        assertTrue(helper.getFileName().toString().startsWith("hotkey-"), helper.toString());
        assertTrue(Files.isExecutable(helper));
    }
}
