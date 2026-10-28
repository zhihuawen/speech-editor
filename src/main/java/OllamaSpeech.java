import javax.sound.sampled.AudioFormat;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Text to speech through {@code ollama run phonon} for Screen Speaker.
 *
 * {@code ollama run phonon --voice <v> "<text>"} writes a 16-bit mono 24 kHz WAV to stdout when
 * stdout is not a terminal; {@link #synthesize} captures those bytes and {@link #play} plays them
 * with javax.sound. If ollama is not installed, the first synthesis offers to install it with the
 * official script. Plug an instance into a {@link SpeechPipeline} via {@link #newPipeline}.
 *
 * Playback can be paused in the middle of a clip: {@link #pause()} stops the clip where it is and
 * {@link #resume()} continues from the same point. {@link #setRate} changes the speed, also
 * mid-clip, by time-stretching the remaining audio with {@link TimeStretch} (pitch unchanged).
 */
final class OllamaSpeech implements SpeechPipeline.Player {

    static final String MODEL = System.getProperty("speech.model", "phonon");
    static final String INSTALL_CMD = "curl -fsSL https://ollama.com/install.sh | sh";
    /** Every voice {@code ollama show phonon} lists (as of October 2026), "default" first. */
    static final String[] DEFAULT_VOICES = {
            "default", "Alfie", "Azgorath", "Bryony", "Captain Silas Blackwater", "Delia", "Easton", "Edwin",
            "Elodie-Rose", "Everly", "Fairy Godmother", "Fennick the Young Elf", "Freya", "Grub the Ogre",
            "Hadley", "Harper", "Hugh", "Imogen", "Juniper", "Lottie", "Maeve", "Master Aldric", "Micah",
            "Miles", "Morwenna of the Deep", "Old Mortlake", "Pippa", "Preston", "Russell", "Sir Bramwell",
            "Spencer", "Teddy", "The Snow Queen", "The Thing Under the Stairs", "Toby", "Trevor",
            "Vyrmathrax the Ancient", "Zoey"
    };
    /** Characters per synthesis request: a sentence or three, so the first audio arrives quickly. */
    static final int CHUNK_CHARS = 280;

    private final Component parent;
    private final Consumer<String> status;
    private final Object pauseLock = new Object();
    private volatile Process process;
    private volatile Clip clip;
    private volatile boolean paused;
    private volatile boolean cancelled;
    private volatile double rate = 1.0;
    /** Set by {@link #setRate} until {@link #play} has reopened the clip at the new rate. */
    private volatile boolean rateChanged;

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
        return new SpeechPipeline(chunk -> synthesize(chunk, voiceName), this, this::cancel);
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

    /**
     * Plays a WAV and returns when it has finished, or when {@link #cancel} or an interrupt ends it.
     * A {@link #pause()} holds the clip where it is until {@link #resume()}; a {@link #setRate}
     * applies to the rest of the clip right away.
     */
    @Override
    public void play(byte[] wav) throws Exception {
        AudioFormat format;
        byte[] pcm;
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            format = ais.getFormat();
            pcm = ais.readAllBytes();
        }
        int frameSize = format.getFrameSize();
        int end = pcm.length / frameSize * frameSize;
        int offset = 0; // bytes of the original audio played so far
        cancelled = false;
        // Every (re)start gets a fresh Clip opened at the point reached, stretched at the current
        // rate. A DirectClip that has been stopped and started again keeps playing but sends no more
        // line events (seen on JDK 21), so a restarted clip would never report that it finished.
        while (offset < end && !cancelled) {
            double r;
            synchronized (pauseLock) {
                r = TimeStretch.supports(format) ? rate : 1.0;
                rateChanged = false;
            }
            byte[] data = TimeStretch.stretch(pcm, offset, end - offset, format, r);
            Clip c = AudioSystem.getClip();
            BlockingQueue<Boolean> stops = new LinkedBlockingQueue<>();
            c.addLineListener(ev -> {
                if (ev.getType() == LineEvent.Type.STOP) stops.add(Boolean.TRUE);
            });
            c.open(format, data, 0, data.length);
            clip = c;
            try {
                Start started = startWhenNotPaused(c);
                if (started == Start.CANCELLED) return;
                if (started == Start.RESTART) continue; // the rate changed during a pause; nothing played yet
                stops.take(); // the clip stopped: finished, paused, cancelled, or the rate changed
                if (cancelled || (!paused && !rateChanged)) return;
                offset += (int) Math.round(c.getFramePosition() * r) * frameSize; // the point reached, in the original
            } finally {
                c.close();
                if (clip == c) clip = null;
            }
        }
    }

    private enum Start { STARTED, RESTART, CANCELLED }

    /** Waits out a pause, then starts the clip, unless it was cancelled or needs reopening meanwhile. */
    private Start startWhenNotPaused(Clip c) throws InterruptedException {
        synchronized (pauseLock) {
            while (paused) pauseLock.wait();
            if (cancelled || !c.isOpen()) return Start.CANCELLED;
            if (rateChanged) return Start.RESTART;
            c.start();
            return Start.STARTED;
        }
    }

    /** Playback speed, 1 being normal. Takes effect at once, even in the middle of a clip. */
    void setRate(double newRate) {
        synchronized (pauseLock) {
            if (newRate == rate) return;
            rate = newRate;
            rateChanged = true;
            Clip c = clip;
            if (c != null && !paused) c.stop(); // play() reopens from the point reached, at the new rate
        }
    }

    double rate() {
        return rate;
    }

    /** Stops the playing clip where it is; {@link #play} keeps waiting instead of returning. */
    @Override
    public void pause() {
        synchronized (pauseLock) {
            if (paused) return;
            paused = true;
            Clip c = clip;
            if (c != null) c.stop();
        }
    }

    /** Lets a paused clip continue from the frame it stopped at. */
    @Override
    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
    }

    /** Kills a running ollama (or installer) process and stops any playing or paused clip. */
    void cancel() {
        Process p = process;
        if (p != null) {
            p.destroyForcibly();
            process = null;
        }
        synchronized (pauseLock) {
            cancelled = true;
            paused = false;
            Clip c = clip;
            if (c != null) {
                c.stop();
                c.close();
                clip = null;
            }
            pauseLock.notifyAll();
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
