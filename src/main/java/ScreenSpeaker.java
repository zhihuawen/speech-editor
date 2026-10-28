import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Collects text off the screen and reads it on request. Copy Screen hides this window, lets the
 * user drag over any part of the screen with the macOS crosshair (Space switches to picking a whole
 * window), recognizes the text in the screenshot with Apple's Vision framework and adds it to the
 * end of the text pane as a new paragraph. Nothing is spoken until the user presses Speak, which
 * reads the pane (or the selection in it): English with {@link OllamaSpeech}, Chinese with the
 * macOS say command through {@link SaySpeech}, as chosen in the Language box. Several captures can
 * be collected and then read in one go. Pause holds playback where it is, even mid-sentence, and
 * the same button, now reading Resume, continues from there; Stop ends it. The speed box changes
 * the reading speed at once without changing the pitch. Save WAV runs the same synthesis but
 * writes the speech to a file instead of playing it.
 *
 * macOS only: needs {@code screencapture}, {@code say} and the bundled Vision helper. Run with
 * {@code ./gradlew run} or {@code java -jar build/libs/screen-speaker.jar}.
 */
public class ScreenSpeaker extends JFrame {

    private static final String HINT = "Press Copy Screen and drag over some text, then press Speak to hear it.";
    private static final String[] SPEEDS = {"0.75x", "1x", "1.25x", "1.5x", "1.75x", "2x", "2.5x", "3x"};
    static final String ENGLISH = "English";
    static final String CHINESE = "Chinese";

    private final JTextArea text = new JTextArea();
    private final JLabel status = new JLabel(HINT);
    private final JComboBox<String> language = new JComboBox<>(new String[]{ENGLISH, CHINESE});
    private final JComboBox<String> voice = new JComboBox<>(OllamaSpeech.DEFAULT_VOICES);
    private final JComboBox<String> speed = new JComboBox<>(SPEEDS);
    private final JButton copyScreenButton = new JButton("Copy Screen");
    private final JButton speakButton = new JButton("Speak");
    /** One action for the toolbar button and the menu item, so both flip between Pause and Resume. */
    private final Action pauseAction = new AbstractAction("Pause") {
        @Override
        public void actionPerformed(java.awt.event.ActionEvent e) {
            togglePause();
        }
    };
    private final JButton pauseButton = new JButton(pauseAction);
    private final JButton stopButton = new JButton("Stop");
    /** Shared by the toolbar button and the menu item, so both are enabled and disabled together. */
    private final Action saveAction = new AbstractAction("Save WAV…") {
        @Override
        public void actionPerformed(java.awt.event.ActionEvent e) {
            saveWav();
        }
    };
    private final OllamaSpeech speech = new OllamaSpeech(this, this::setStatus);
    private final SaySpeech say = new SaySpeech();
    /** {@code say -v ?} takes about a second, so the Chinese voices are listed in the background from the start. */
    private final CompletableFuture<List<String>> chineseVoices =
            CompletableFuture.supplyAsync(SaySpeech::installedChineseVoices);
    /** The voice last chosen for each language, restored when switching back to it. */
    private final Map<String, String> voiceByLanguage = new HashMap<>();

    private volatile SpeechPipeline pipeline;
    private volatile boolean capturing;
    private volatile boolean speaking;
    private volatile boolean saving;
    private volatile boolean paused;

    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "Screen Speaker");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            // The .app bundle carries the icon in its Info.plist; this covers `./gradlew run`.
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(AppIcon.render(512));
            }
            new ScreenSpeaker().setVisible(true);
        });
    }

    public ScreenSpeaker() {
        super("Screen Speaker");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                quit();
            }
        });
        setAlwaysOnTop(true);
        setIconImage(AppIcon.render(128)); // window icon on platforms that show one

        text.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setMargin(new Insets(8, 8, 8, 8));

        setJMenuBar(buildMenuBar());
        Box bars = Box.createVerticalBox(); // settings above, actions below: one row would be 850 px wide
        bars.add(buildSettingsBar());
        bars.add(buildActionBar());
        add(bars, BorderLayout.NORTH);
        add(new JScrollPane(text), BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(status, BorderLayout.SOUTH);

        // When the listing arrives after the user has already switched to Chinese, refresh the box.
        chineseVoices.thenAccept(v -> SwingUtilities.invokeLater(() -> {
            if (chinese()) {
                voiceByLanguage.put(CHINESE, currentVoice());
                showVoices(CHINESE);
            }
        }));

        updateButtons();
        setSize(560, 400);
        placeBottomRight();
    }

    /** Parks the window in the lower right corner so it covers as little of the screen as possible. */
    private void placeBottomRight() {
        GraphicsConfiguration gc = getGraphicsConfiguration();
        Rectangle screen = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        int margin = 24;
        setLocation(screen.x + screen.width - insets.right - getWidth() - margin,
                screen.y + screen.height - insets.bottom - getHeight() - margin);
    }

    // ---------------------------------------------------------------- UI

    private JMenuBar buildMenuBar() {
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        saveAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask | KeyEvent.SHIFT_DOWN_MASK));
        file.add(new JMenuItem(saveAction));
        file.addSeparator();
        file.add(item("Quit", KeyEvent.VK_Q, menuMask, e -> quit()));
        bar.add(file);

        JMenu edit = new JMenu("Edit");
        edit.add(action(new DefaultEditorKit.CutAction(), "Cut", KeyEvent.VK_X, menuMask));
        edit.add(action(new DefaultEditorKit.CopyAction(), "Copy", KeyEvent.VK_C, menuMask));
        edit.add(action(new DefaultEditorKit.PasteAction(), "Paste", KeyEvent.VK_V, menuMask));
        edit.addSeparator();
        edit.add(item("Select All", KeyEvent.VK_A, menuMask, e -> text.selectAll()));
        bar.add(edit);

        JMenu speechMenu = new JMenu("Speech");
        speechMenu.add(item("Copy Screen", KeyEvent.VK_R, menuMask, e -> copyScreen()));
        speechMenu.add(item("Speak Text", KeyEvent.VK_R, menuMask | KeyEvent.SHIFT_DOWN_MASK, e -> speak()));
        pauseAction.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(KeyEvent.VK_P, menuMask));
        speechMenu.add(new JMenuItem(pauseAction));
        speechMenu.add(item("Stop", KeyEvent.VK_PERIOD, menuMask, e -> stopSpeech()));
        bar.add(speechMenu);
        return bar;
    }

    /** Language, voice and speed: what Speak and Save WAV produce. */
    private JToolBar buildSettingsBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(new JLabel(" Language: "));
        language.setMaximumSize(new Dimension(110, 30));
        language.setToolTipText("English is read by ollama " + OllamaSpeech.MODEL + ", Chinese by the macOS say command");
        language.addItemListener(ev -> {
            String lang = String.valueOf(ev.getItem());
            if (ev.getStateChange() == ItemEvent.DESELECTED) voiceByLanguage.put(lang, currentVoice());
            else showVoices(lang);
        });
        tb.add(language);
        tb.add(new JLabel(" Voice: "));
        voice.setEditable(true);
        voice.setMaximumSize(new Dimension(200, 30));
        tb.add(voice);
        tb.add(new JLabel(" Speed: "));
        speed.setSelectedItem("1x");
        speed.setMaximumSize(new Dimension(90, 30));
        speed.setToolTipText("Reading speed; a change applies right away and keeps the pitch");
        speed.addActionListener(e -> speech.setRate(parseSpeed(String.valueOf(speed.getSelectedItem()))));
        tb.add(speed);
        return tb;
    }

    /** Copy Screen, Speak, Pause, Stop, and Save WAV. */
    private JToolBar buildActionBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        copyScreenButton.setToolTipText("Take a screenshot of a region or window and add its text to the end of the pane");
        copyScreenButton.addActionListener(e -> copyScreen());
        tb.add(copyScreenButton);
        speakButton.setToolTipText("Read the text below (or the selected part of it)");
        speakButton.addActionListener(e -> speak());
        tb.add(speakButton);
        pauseButton.setToolTipText("Hold playback where it is; press again to continue from that point");
        tb.add(pauseButton);
        stopButton.addActionListener(e -> stopSpeech());
        tb.add(stopButton);
        tb.addSeparator();
        JButton saveButton = new JButton(saveAction);
        saveButton.setToolTipText("Write the speech of the text below (or the selected part) to a WAV file instead of playing it");
        tb.add(saveButton);
        return tb;
    }

    private JMenuItem item(String name, int key, int mask, java.awt.event.ActionListener l) {
        JMenuItem mi = new JMenuItem(name);
        mi.setAccelerator(KeyStroke.getKeyStroke(key, mask));
        mi.addActionListener(l);
        return mi;
    }

    private JMenuItem action(Action a, String name, int key, int mask) {
        a.putValue(Action.NAME, name);
        a.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(key, mask));
        return new JMenuItem(a);
    }

    /** "1.5x" to 1.5; anything unreadable means normal speed. */
    static double parseSpeed(String label) {
        try {
            return Double.parseDouble(label.strip().replaceFirst("[xX×]$", ""));
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }

    private boolean chinese() {
        return CHINESE.equals(language.getSelectedItem());
    }

    private String currentVoice() {
        return String.valueOf(voice.getEditor().getItem()).strip();
    }

    /** Fills the voice box with the voices of {@code lang} and selects the one last used for it. */
    private void showVoices(String lang) {
        List<String> voices = CHINESE.equals(lang)
                ? chineseVoices.getNow(List.of(SaySpeech.DEFAULT_VOICE))
                : Arrays.asList(OllamaSpeech.DEFAULT_VOICES);
        voice.setModel(new DefaultComboBoxModel<>(voices.toArray(new String[0])));
        voice.setSelectedItem(voiceByLanguage.getOrDefault(lang, voices.get(0)));
    }

    private void setStatus(String s) {
        SwingUtilities.invokeLater(() -> status.setText(s));
    }

    private void error(String msg) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE));
    }

    /**
     * Copy Screen is available between captures; Speak and Save WAV only while nothing is being
     * synthesized; Pause only while something plays; Stop while something plays or is saved. The
     * pause button reads Resume while playback is held.
     */
    private void updateButtons() {
        SwingUtilities.invokeLater(() -> {
            boolean idle = !capturing && !speaking && !saving;
            copyScreenButton.setEnabled(!capturing);
            speakButton.setEnabled(idle);
            saveAction.setEnabled(idle);
            pauseAction.setEnabled(speaking);
            pauseAction.putValue(Action.NAME, paused ? "Resume" : "Pause");
            stopButton.setEnabled(speaking || saving);
        });
    }

    private void quit() {
        stopSpeech();
        dispose();
        System.exit(0);
    }

    // ---------------------------------------------------------------- copy screen

    /** Screenshots a region, recognizes its text and appends it to the pane. Playback keeps going. */
    private void copyScreen() {
        if (capturing) return;
        capturing = true;
        updateButtons();
        setStatus("Drag over the text to copy. Space picks a whole window, Esc cancels.");
        setVisible(false); // get out of the way of the screenshot

        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                Optional<Path> shot = ScreenCapture.captureInteractively();
                if (shot.isEmpty()) return null;
                try {
                    return OcrHelper.recognize(OcrHelper.locate(), shot.get());
                } finally {
                    Files.deleteIfExists(shot.get());
                }
            }

            @Override
            protected void done() {
                capturing = false;
                setVisible(true);
                toFront();
                updateButtons();
                try {
                    String recognized = get();
                    if (recognized == null) {
                        setStatus("Capture cancelled");
                        return;
                    }
                    String cleaned = recognized.strip();
                    if (cleaned.isEmpty()) {
                        setStatus("No text found. If the screenshot came out blank, allow Screen Recording for "
                                + "this app in System Settings > Privacy & Security.");
                        return;
                    }
                    String merged = appendParagraph(text.getText(), cleaned);
                    text.setText(merged);
                    text.setCaretPosition(merged.length());
                    setStatus("Copied " + cleaned.length() + " characters from the screen. Press Speak to hear the text.");
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    setStatus("Copy Screen failed: " + c.getMessage());
                    error("Copy Screen failed:\n" + c.getMessage());
                }
            }
        }.execute();
    }

    /**
     * The pane's text with {@code addition} added as a new paragraph at the end: a blank line
     * separates it from what is already there, and an empty pane becomes just the addition.
     */
    static String appendParagraph(String existing, String addition) {
        String kept = existing.stripTrailing();
        return kept.isEmpty() ? addition : kept + "\n\n" + addition;
    }

    // ---------------------------------------------------------------- speech

    /** The selected part of the pane, or all of it when nothing is selected. */
    private String textToRead() {
        String selected = text.getSelectedText();
        return (selected == null || selected.isBlank()) ? text.getText() : selected;
    }

    /** Chunks sized for the engine: Chinese characters carry more speech each than English ones. */
    private List<String> chunksOf(String toRead) {
        return TextChunker.chunks(toRead, chinese() ? SaySpeech.CHUNK_CHARS : OllamaSpeech.CHUNK_CHARS);
    }

    private void speak() {
        start(textToRead(), null);
    }

    /** Asks where to put the file, then synthesizes the text into it instead of playing it. */
    private void saveWav() {
        String toRead = textToRead();
        if (chunksOf(toRead).isEmpty()) {
            setStatus("Nothing to save. Press Copy Screen to grab some text first.");
            return;
        }
        FileDialog dialog = new FileDialog(this, "Save Speech as WAV", FileDialog.SAVE);
        dialog.setFile(WavFile.suggestedName(toRead) + ".wav");
        dialog.setVisible(true);
        if (dialog.getFile() == null) return; // cancelled
        Path file = WavFile.withWavExtension(Path.of(dialog.getDirectory(), dialog.getFile()));
        boolean renamed = !file.getFileName().toString().equals(dialog.getFile());
        if (renamed && Files.exists(file) && JOptionPane.showConfirmDialog(this,
                file.getFileName() + " already exists. Replace it?", "Save WAV",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        start(toRead, file);
    }

    /** Reads {@code toRead} aloud, or, when {@code saveTo} is given, writes the speech there instead. */
    private void start(String toRead, Path saveTo) {
        List<String> chunks = chunksOf(toRead);
        if (chunks.isEmpty()) {
            setStatus("Nothing to read. Press Copy Screen to grab some text first.");
            return;
        }
        stopSpeech();
        boolean chinese = chinese();
        String voiceName = currentVoice();
        WavFile file = saveTo == null ? null : new WavFile();
        speaking = file == null;
        saving = file != null;
        paused = false;
        updateButtons();
        String engine = chinese ? "say " + (voiceName.isEmpty() ? SaySpeech.DEFAULT_VOICE : voiceName)
                : "ollama " + OllamaSpeech.MODEL;
        setStatus((saving ? "Saving speech from " : "Generating speech with ") + engine + " ("
                + toRead.strip().length() + " chars in " + chunks.size() + " parts)...");

        SpeechPipeline.Synth synth = chinese
                ? chunk -> say.synthesize(chunk, voiceName)
                : chunk -> speech.synthesize(chunk, voiceName);
        Runnable cancel = () -> { say.cancel(); speech.cancel(); }; // whichever is running
        SpeechPipeline p = new SpeechPipeline(synth, saving ? file : speech, cancel, saving ? "Writing" : "Playing");
        pipeline = p;
        p.start(chunks, new SpeechPipeline.Listener() {
            public void status(String message) { whileCurrent(p, () -> setStatus(message)); }
            public void done() {
                if (file == null) {
                    whileCurrent(p, () -> { setStatus("Done"); resetButtons(); });
                    return;
                }
                try {
                    file.write(saveTo, speech.rate()); // here, off the EDT: stretching a long text takes a moment
                    String length = String.format("%.1f", file.seconds() / speech.rate());
                    whileCurrent(p, () -> { setStatus("Saved " + saveTo + " (" + length + " s)"); resetButtons(); });
                } catch (Exception e) {
                    failed(e);
                }
            }
            public void stopped() { whileCurrent(p, () -> { setStatus("Stopped"); resetButtons(); }); }
            public void failed(Throwable t) {
                String what = file == null ? "Speech failed: " : "Save failed: ";
                whileCurrent(p, () -> {
                    setStatus(what + t.getMessage());
                    error(what + "\n" + t.getMessage());
                    resetButtons();
                });
            }
        });
    }

    /** Runs {@code update} on the EDT unless a newer pipeline has replaced {@code p} meanwhile. */
    private void whileCurrent(SpeechPipeline p, Runnable update) {
        SwingUtilities.invokeLater(() -> {
            if (pipeline == p) update.run();
        });
    }

    /** Pause holds playback where it is; pressed again (now Resume) it continues from that point. */
    private void togglePause() {
        SpeechPipeline p = pipeline;
        if (p == null || !speaking) return;
        if (p.isPaused()) p.resume(); else p.pause();
        paused = p.isPaused();
        updateButtons();
    }

    private void stopSpeech() {
        SpeechPipeline p = pipeline;
        pipeline = null;
        if (p != null) p.stop(); else speech.cancel();
        resetButtons();
    }

    private void resetButtons() {
        speaking = false;
        saving = false;
        paused = false;
        updateButtons();
    }
}
