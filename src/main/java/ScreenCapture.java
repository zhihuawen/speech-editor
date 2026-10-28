import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Interactive screenshot through macOS {@code screencapture -i}: the user drags a rectangle, or
 * presses Space and clicks a window. Other apps' windows only show up once the responsible app
 * (Terminal, the IDE, or the .app bundle) has Screen Recording permission.
 */
final class ScreenCapture {

    private ScreenCapture() {
    }

    /** Blocks until the user has selected something; empty when they pressed Esc. */
    static Optional<Path> captureInteractively() throws IOException, InterruptedException {
        Path png = Files.createTempFile("screen-speaker-", ".png");
        Files.delete(png); // screencapture recreates it; a missing file afterwards means "cancelled"
        Process p = new ProcessBuilder("/usr/sbin/screencapture", "-i", "-x", "-t", "png", png.toString())
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("screencapture did not finish");
        }
        if (Files.isRegularFile(png) && Files.size(png) > 0) {
            return Optional.of(png);
        }
        if (p.exitValue() != 0 && !output.isEmpty()) {
            throw new IOException("screencapture failed: " + output);
        }
        return Optional.empty();
    }
}
