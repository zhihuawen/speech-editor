import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.DefaultEditorKit;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A small text editor with a Speak button. Speak runs `ollama run phonon --voice <v> <text>` on the
 * selected text (or the whole document when nothing is selected), captures the WAV that ollama
 * writes to stdout, and plays it.
 *
 * If ollama is not installed, Speak offers to install it with
 * `curl -fsSL https://ollama.com/install.sh | sh` and then continues.
 *
 * No dependencies beyond the JDK. Build with `./gradlew build`; run with
 * `java -jar build/libs/speech-editor.jar`.
 */
public class SpeechEditor extends JFrame {

    private static final String MODEL = System.getProperty("speech.model", "phonon");
    private static final String INSTALL_CMD = "curl -fsSL https://ollama.com/install.sh | sh";
    private static final String[] DEFAULT_VOICES = {
            "default", "Alfie", "Bryony", "Delia", "Easton", "Edwin", "Elodie-Rose", "Everly",
            "Freya", "Hadley", "Harper", "Hugh", "Imogen"
    };

    private final JTextArea text = new JTextArea();
    private final JLabel status = new JLabel("Ready");
    private final JComboBox<String> voice = new JComboBox<>(DEFAULT_VOICES);
    private final JButton speakButton = new JButton("Speak");
    private final JButton stopButton = new JButton("Stop");

    private File currentFile;
    private boolean dirty;
    private volatile Process speechProcess;
    private volatile Clip clip;

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
        speakButton.setToolTipText("Read the selected text (or the whole document) with ollama " + MODEL);
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
        toRead = toRead.strip();
        if (toRead.isEmpty()) {
            setStatus("Nothing to read");
            return;
        }
        stopSpeech();
        String voiceName = String.valueOf(voice.getEditor().getItem()).strip();
        final String finalText = toRead;
        speakButton.setEnabled(false);
        stopButton.setEnabled(true);
        setStatus("Generating speech with ollama " + MODEL + " (" + finalText.length() + " chars)...");

        new SwingWorker<byte[], Void>() {
            @Override
            protected byte[] doInBackground() throws Exception {
                return runOllama(ensureOllama(), finalText, voiceName);
            }

            @Override
            protected void done() {
                try {
                    byte[] wav = get();
                    play(wav);
                } catch (java.util.concurrent.CancellationException ce) {
                    setStatus("Stopped");
                    resetButtons();
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    setStatus("Speech failed: " + c.getMessage());
                    error("Speech failed:\n" + c.getMessage());
                    resetButtons();
                }
            }
        }.execute();
    }

    /** Runs ollama and returns the WAV bytes it writes to stdout. */
    private byte[] runOllama(String ollama, String content, String voiceName)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(ollama, "run", MODEL));
        if (!voiceName.isEmpty() && !voiceName.equals("default")) {
            cmd.add("--voice");
            cmd.add(voiceName);
        }
        cmd.add(content);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process p = pb.start();
        speechProcess = p;
        byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!p.waitFor(5, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("ollama timed out");
        }
        speechProcess = null;
        if (p.exitValue() != 0) {
            throw new IOException("ollama exited with " + p.exitValue() + (err.isEmpty() ? "" : ": " + err));
        }
        if (out.length < 44 || out[0] != 'R' || out[1] != 'I' || out[2] != 'F' || out[3] != 'F') {
            String head = new String(out, 0, Math.min(out.length, 200), StandardCharsets.UTF_8);
            throw new IOException("ollama did not return WAV audio. Output starts with: " + head
                    + (err.isEmpty() ? "" : "\nstderr: " + err));
        }
        return out;
    }

    private void play(byte[] wav) {
        try {
            AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
            Clip c = AudioSystem.getClip();
            c.open(ais);
            clip = c;
            long seconds = c.getMicrosecondLength() / 1_000_000;
            setStatus("Playing (" + seconds + " s)...");
            c.addLineListener(ev -> {
                if (ev.getType() == LineEvent.Type.STOP) {
                    c.close();
                    if (clip == c) clip = null;
                    setStatus("Done");
                    resetButtons();
                }
            });
            c.start();
        } catch (Exception ex) {
            setStatus("Playback failed: " + ex.getMessage());
            error("Playback failed:\n" + ex.getMessage());
            resetButtons();
        }
    }

    private void stopSpeech() {
        Process p = speechProcess;
        if (p != null) {
            p.destroyForcibly();
            speechProcess = null;
        }
        Clip c = clip;
        if (c != null) {
            c.stop();
            c.close();
            clip = null;
        }
        resetButtons();
    }

    private void resetButtons() {
        SwingUtilities.invokeLater(() -> {
            speakButton.setEnabled(true);
            stopButton.setEnabled(false);
        });
    }

    /**
     * Returns the ollama binary, installing it first when it is missing. Runs off the EDT; the
     * confirmation dialog is shown on the EDT and this thread waits for the answer.
     */
    private String ensureOllama() throws IOException, InterruptedException {
        try {
            return findOllama();
        } catch (IOException notFound) {
            // fall through to install
        }
        final int[] answer = new int[1];
        try {
            SwingUtilities.invokeAndWait(() -> answer[0] = JOptionPane.showConfirmDialog(this,
                    "ollama was not found on this machine.\nInstall it now with\n\n    " + INSTALL_CMD
                            + "\n\nThis downloads Ollama from ollama.com and may ask for your password.",
                    "Install Ollama", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IOException(e.getCause());
        }
        if (answer[0] != JOptionPane.OK_OPTION) {
            throw new IOException("ollama not found. Install Ollama or set OLLAMA_BIN to the binary.");
        }
        installOllama();
        return findOllama();
    }

    /** Runs the official install script and surfaces its output on failure. */
    private void installOllama() throws IOException, InterruptedException {
        setStatus("Installing ollama (" + INSTALL_CMD + ")...");
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", INSTALL_CMD);
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process p = pb.start();
        speechProcess = p;
        StringBuilder log = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.append(line).append('\n');
                if (!line.isBlank()) setStatus("Installing ollama: " + line.strip());
            }
        }
        if (!p.waitFor(15, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("ollama install timed out");
        }
        speechProcess = null;
        if (p.exitValue() != 0) {
            String tail = log.length() > 2000 ? log.substring(log.length() - 2000) : log.toString();
            throw new IOException("ollama install failed (exit " + p.exitValue() + "):\n" + tail.strip()
                    + "\n\nInstall it manually from https://ollama.com/download");
        }
        setStatus("ollama installed");
    }

    /** ollama from $OLLAMA_BIN, $PATH, else the usual macOS install locations. */
    private static String findOllama() throws IOException {
        String env = System.getenv("OLLAMA_BIN");
        if (env != null && new File(env).canExecute()) return env;
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                Path p = Path.of(dir, "ollama");
                if (Files.isExecutable(p)) return p.toString();
            }
        }
        for (String candidate : new String[]{"/usr/local/bin/ollama", "/opt/homebrew/bin/ollama",
                "/Applications/Ollama.app/Contents/Resources/ollama"}) {
            if (new File(candidate).canExecute()) return candidate;
        }
        throw new IOException("ollama not found. Install Ollama or set OLLAMA_BIN to the binary.");
    }
}
