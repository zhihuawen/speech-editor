import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * A small text editor with a Speak button. Speak reads the selected text (or the whole document
 * when nothing is selected) with {@link OllamaSpeech}, sentence-sized chunk by chunk, so audio
 * starts after the first chunk instead of after the whole text.
 *
 * No dependencies beyond the JDK. Build with `./gradlew build`; run with
 * `java -jar build/libs/speech-editor.jar`. See {@link ScreenSpeaker} for the companion app that
 * reads text off the screen.
 */
public class SpeechEditor extends JFrame {

    private final JTextArea text = new JTextArea();
    private final JLabel status = new JLabel("Ready");
    private final JComboBox<String> voice = new JComboBox<>(OllamaSpeech.DEFAULT_VOICES);
    private final JButton speakButton = new JButton("Speak");
    private final JButton stopButton = new JButton("Stop");
    private final OllamaSpeech speech = new OllamaSpeech(this, this::setStatus);

    private File currentFile;
    private boolean dirty;
    private volatile SpeechPipeline pipeline;

    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "Speech Editor");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            SpeechEditor editor = new SpeechEditor();
            if (args.length > 0) {
                editor.open(new File(args[0]));
            }
            editor.setVisible(true);
        });
    }

    public SpeechEditor() {
        super("Speech Editor");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (confirmDiscard()) {
                    stopSpeech();
                    dispose();
                    System.exit(0);
                }
            }
        });

        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { markDirty(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { markDirty(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { markDirty(); }
        });

        setJMenuBar(buildMenuBar());
        add(buildToolBar(), BorderLayout.NORTH);
        add(new JScrollPane(text), BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(status, BorderLayout.SOUTH);

        stopButton.setEnabled(false);
        setSize(900, 650);
        setLocationRelativeTo(null);
        updateTitle();
    }

    // ---------------------------------------------------------------- UI

    private JMenuBar buildMenuBar() {
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(item("New", KeyEvent.VK_N, menuMask, e -> newFile()));
        file.add(item("Open...", KeyEvent.VK_O, menuMask, e -> openDialog()));
        file.addSeparator();
        file.add(item("Save", KeyEvent.VK_S, menuMask, e -> save()));
        file.add(item("Save As...", KeyEvent.VK_S, menuMask | KeyEvent.SHIFT_DOWN_MASK, e -> saveAs()));
        file.addSeparator();
        file.add(item("Quit", KeyEvent.VK_Q, menuMask, e -> {
            if (confirmDiscard()) { stopSpeech(); System.exit(0); }
        }));
        bar.add(file);

        JMenu edit = new JMenu("Edit");
        edit.add(action(new DefaultEditorKit.CutAction(), "Cut", KeyEvent.VK_X, menuMask));
        edit.add(action(new DefaultEditorKit.CopyAction(), "Copy", KeyEvent.VK_C, menuMask));
        edit.add(action(new DefaultEditorKit.PasteAction(), "Paste", KeyEvent.VK_V, menuMask));
        edit.addSeparator();
        edit.add(item("Select All", KeyEvent.VK_A, menuMask, e -> text.selectAll()));
        bar.add(edit);

        JMenu speech = new JMenu("Speech");
        speech.add(item("Speak Selection", KeyEvent.VK_R, menuMask, e -> speak()));
        speech.add(item("Stop", KeyEvent.VK_PERIOD, menuMask, e -> stopSpeech()));
        bar.add(speech);
        return bar;
    }

    private JToolBar buildToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(new JLabel(" Voice: "));
        voice.setEditable(true);
        voice.setMaximumSize(new Dimension(220, 30));
        tb.add(voice);
        tb.addSeparator();
        speakButton.setToolTipText("Read the selected text (or the whole document) with ollama " + OllamaSpeech.MODEL);
        speakButton.addActionListener(e -> speak());
        tb.add(speakButton);
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

    private void setStatus(String s) {
        SwingUtilities.invokeLater(() -> status.setText(s));
    }

    private void markDirty() {
        if (!dirty) {
            dirty = true;
            updateTitle();
        }
    }

    private void updateTitle() {
        String name = currentFile == null ? "Untitled" : currentFile.getName();
        setTitle((dirty ? "* " : "") + name + " - Speech Editor");
        getRootPane().putClientProperty("Window.documentModified", dirty);
        getRootPane().putClientProperty("Window.documentFile", currentFile);
    }

    // ---------------------------------------------------------------- files

    private boolean confirmDiscard() {
        if (!dirty) return true;
        int r = JOptionPane.showConfirmDialog(this, "Save changes to "
                + (currentFile == null ? "Untitled" : currentFile.getName()) + "?",
                "Unsaved changes", JOptionPane.YES_NO_CANCEL_OPTION);
        if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return false;
        if (r == JOptionPane.YES_OPTION) return save();
        return true;
    }

    private void newFile() {
        if (!confirmDiscard()) return;
        text.setText("");
        currentFile = null;
        dirty = false;
        updateTitle();
        setStatus("New file");
    }

    private void openDialog() {
        if (!confirmDiscard()) return;
        JFileChooser fc = chooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            open(fc.getSelectedFile());
        }
    }

    private void open(File f) {
        try {
            text.setText(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            text.setCaretPosition(0);
            currentFile = f;
            dirty = false;
            updateTitle();
            setStatus("Opened " + f.getAbsolutePath());
        } catch (IOException ex) {
            error("Could not open " + f + ":\n" + ex.getMessage());
        }
    }

    private boolean save() {
        if (currentFile == null) return saveAs();
        return writeTo(currentFile);
    }

    private boolean saveAs() {
        JFileChooser fc = chooser();
        if (currentFile != null) fc.setSelectedFile(currentFile);
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        File f = fc.getSelectedFile();
        if (f.exists() && JOptionPane.showConfirmDialog(this, f.getName() + " exists. Replace it?",
                "Replace file", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return false;
        }
        return writeTo(f);
    }

    private boolean writeTo(File f) {
        try {
            Files.writeString(f.toPath(), text.getText(), StandardCharsets.UTF_8);
            currentFile = f;
            dirty = false;
            updateTitle();
            setStatus("Saved " + f.getAbsolutePath());
            return true;
        } catch (IOException ex) {
            error("Could not save " + f + ":\n" + ex.getMessage());
            return false;
        }
    }

    private JFileChooser chooser() {
        JFileChooser fc = new JFileChooser(currentFile != null ? currentFile.getParentFile()
                : new File(System.getProperty("user.home")));
        fc.addChoosableFileFilter(new FileNameExtensionFilter("Text files", "txt", "md", "log", "csv", "json"));
        fc.setAcceptAllFileFilterUsed(true);
        return fc;
    }

    private void error(String msg) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE));
    }

    // ---------------------------------------------------------------- speech

    private void speak() {
        String selected = text.getSelectedText();
        String toRead = (selected == null || selected.isBlank()) ? text.getText() : selected;
        List<String> chunks = TextChunker.chunks(toRead, OllamaSpeech.CHUNK_CHARS);
        if (chunks.isEmpty()) {
            setStatus("Nothing to read");
            return;
        }
        stopSpeech();
        String voiceName = String.valueOf(voice.getEditor().getItem()).strip();
        speakButton.setEnabled(false);
        stopButton.setEnabled(true);
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

    private void stopSpeech() {
        SpeechPipeline p = pipeline;
        pipeline = null;
        if (p != null) p.stop(); else speech.cancel();
        resetButtons();
    }

    private void resetButtons() {
        SwingUtilities.invokeLater(() -> {
            speakButton.setEnabled(true);
            stopButton.setEnabled(false);
        });
    }
}
