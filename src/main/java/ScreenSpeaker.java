import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Collects text off the screen and reads it on request. Copy Screen hides this window, lets the
 * user drag over any part of the screen with the macOS crosshair (Space switches to picking a whole
 * window), recognizes the text in the screenshot with Apple's Vision framework and adds it to the
 * end of the text pane as a new paragraph. Nothing is spoken until the user presses Speak, which
 * reads the pane (or the selection in it) with {@link OllamaSpeech}. Several captures can be
 * collected and then read in one go. Pause holds playback where it is, even mid-sentence, and the
 * same button, now reading Resume, continues from there; Stop ends it. The speed box changes the
 * reading speed at once without changing the pitch.
 *
 * macOS only: needs {@code screencapture} and the bundled Vision helper. Run with
 * {@code ./gradlew run} or {@code java -jar build/libs/screen-speaker.jar}.
 */
public class ScreenSpeaker extends JFrame {

    private static final String HINT = "Press Copy Screen and drag over some text, then press Speak to hear it.";
    private static final String[] SPEEDS = {"0.75x", "1x", "1.25x", "1.5x", "1.75x", "2x", "2.5x", "3x"};

    private final JTextArea text = new JTextArea();
    private final JLabel status = new JLabel(HINT);
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
    private final OllamaSpeech speech = new OllamaSpeech(this, this::setStatus);

    private volatile SpeechPipeline pipeline;
    private volatile boolean capturing;
    private volatile boolean speaking;
    private volatile boolean paused;

    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "Screen Speaker");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
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

        text.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setMargin(new Insets(8, 8, 8, 8));

        setJMenuBar(buildMenuBar());
        add(buildToolBar(), BorderLayout.NORTH);
        add(new JScrollPane(text), BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(status, BorderLayout.SOUTH);

        updateButtons();
        setSize(520, 380);
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

    private JToolBar buildToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(new JLabel(" Voice: "));
        voice.setEditable(true);
        voice.setMaximumSize(new Dimension(240, 30));
        tb.add(voice);
        tb.add(new JLabel(" Speed: "));
        speed.setSelectedItem("1x");
        speed.setMaximumSize(new Dimension(90, 30));
        speed.setToolTipText("Reading speed; a change applies right away and keeps the pitch");
        speed.addActionListener(e -> speech.setRate(parseSpeed(String.valueOf(speed.getSelectedItem()))));
        tb.add(speed);
        tb.addSeparator();
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

    private void setStatus(String s) {
        SwingUtilities.invokeLater(() -> status.setText(s));
    }

    private void error(String msg) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE));
    }

    /**
     * Copy Screen is available between captures; Speak only while nothing is playing; Pause and
     * Stop only while something is. The pause button reads Resume while playback is held.
     */
    private void updateButtons() {
        SwingUtilities.invokeLater(() -> {
            copyScreenButton.setEnabled(!capturing);
            speakButton.setEnabled(!capturing && !speaking);
            pauseAction.setEnabled(speaking);
            pauseAction.putValue(Action.NAME, paused ? "Resume" : "Pause");
            stopButton.setEnabled(speaking);
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

    private void speak() {
        String selected = text.getSelectedText();
        speakText((selected == null || selected.isBlank()) ? text.getText() : selected);
    }

    private void speakText(String toRead) {
        List<String> chunks = TextChunker.chunks(toRead, OllamaSpeech.CHUNK_CHARS);
        if (chunks.isEmpty()) {
            setStatus("Nothing to read. Press Copy Screen to grab some text first.");
            return;
        }
        stopSpeech();
        String voiceName = String.valueOf(voice.getEditor().getItem()).strip();
        speaking = true;
        paused = false;
        updateButtons();
        setStatus("Generating speech with ollama " + OllamaSpeech.MODEL + " (" + toRead.strip().length()
                + " chars in " + chunks.size() + " parts)...");

        SpeechPipeline p = speech.newPipeline(voiceName);
        pipeline = p;
        p.start(chunks, new SpeechPipeline.Listener() {
            public void status(String message) { whileCurrent(p, () -> setStatus(message)); }
            public void done() { whileCurrent(p, () -> { setStatus("Done"); resetButtons(); }); }
            public void stopped() { whileCurrent(p, () -> { setStatus("Stopped"); resetButtons(); }); }
            public void failed(Throwable t) {
                whileCurrent(p, () -> {
                    setStatus("Speech failed: " + t.getMessage());
                    error("Speech failed:\n" + t.getMessage());
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
        paused = false;
        updateButtons();
    }
}
