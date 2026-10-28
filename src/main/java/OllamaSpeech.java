import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Text to speech through {@code ollama run phonon} for Screen Speaker.
 *
 * {@code ollama run phonon --voice <v> "<text>"} writes a 16-bit mono 24 kHz WAV to stdout when
 * stdout is not a terminal; {@link #synthesize} captures those bytes and {@link #play} plays them
 * with javax.sound. If ollama is not installed, the first synthesis offers to install it with the
 * official script. Plug an instance into a {@link SpeechPipeline} via {@link #newPipeline}.
 */
final class OllamaSpeech {

    static final String MODEL = System.getProperty("speech.model", "phonon");
    static final String INSTALL_CMD = "curl -fsSL https://ollama.com/install.sh | sh";
    static final String[] DEFAULT_VOICES = {
            "default", "Alfie", "Bryony", "Delia", "Easton", "Edwin", "Elodie-Rose", "Everly",
            "Freya", "Hadley", "Harper", "Hugh", "Imogen"
    };
    /** Characters per synthesis request: a sentence or three, so the first audio arrives quickly. */
    static final int CHUNK_CHARS = 280;

    private final Component parent;
    private final Consumer<String> status;
    private volatile Process process;
    private volatile Clip clip;

    /**
     * @param parent window that owns the "install ollama?" dialog
     * @param status receives progress messages; may be called from any thread
     */
    OllamaSpeech(Component parent, Consumer<String> status) {
        this.parent = parent;
        this.status = status;
    }

    /** A pipeline that reads chunks with {@code voiceName}; the caller starts it. */
    SpeechPipeline newPipeline(String voiceName) {
        return new SpeechPipeline(chunk -> synthesize(chunk, voiceName), this::play, this::cancel);
    }

    /** Runs ollama on {@code text} and returns the WAV bytes it writes to stdout. */
    byte[] synthesize(String text, String voiceName) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(ensureOllama(), "run", MODEL));
        if (!voiceName.isEmpty() && !voiceName.equals("default")) {
            cmd.add("--voice");
            cmd.add(voiceName);
        }
        cmd.add(text);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process p = pb.start();
        process = p;
        byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!p.waitFor(5, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("ollama timed out");
        }
        if (process == p) process = null;
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

    /** Plays a WAV and returns when it has finished, or when {@link #cancel} or an interrupt ends it. */
    void play(byte[] wav) throws Exception {
        AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
        Clip c = AudioSystem.getClip();
        CountDownLatch finished = new CountDownLatch(1);
        c.addLineListener(ev -> {
            if (ev.getType() == LineEvent.Type.STOP) finished.countDown();
        });
        c.open(ais);
        clip = c;
        try {
            c.start();
            finished.await();
        } finally {
            c.close();
            if (clip == c) clip = null;
        }
    }

    /** Kills a running ollama (or installer) process and stops any playing clip. */
    void cancel() {
        Process p = process;
        if (p != null) {
            p.destroyForcibly();
            process = null;
        }
        Clip c = clip;
        if (c != null) {
            c.stop();
            c.close();
            clip = null;
        }
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
            SwingUtilities.invokeAndWait(() -> answer[0] = JOptionPane.showConfirmDialog(parent,
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
        status.accept("Installing ollama (" + INSTALL_CMD + ")...");
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", INSTALL_CMD);
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process p = pb.start();
        process = p;
        StringBuilder log = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.append(line).append('\n');
                if (!line.isBlank()) status.accept("Installing ollama: " + line.strip());
            }
        }
        if (!p.waitFor(15, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("ollama install timed out");
        }
        if (process == p) process = null;
        if (p.exitValue() != 0) {
            String tail = log.length() > 2000 ? log.substring(log.length() - 2000) : log.toString();
            throw new IOException("ollama install failed (exit " + p.exitValue() + "):\n" + tail.strip()
                    + "\n\nInstall it manually from https://ollama.com/download");
        }
        status.accept("ollama installed");
    }

    /** ollama from $OLLAMA_BIN, $PATH, else the usual macOS install locations. */
    static String findOllama() throws IOException {
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
