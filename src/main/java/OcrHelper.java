import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Finds the native text-recognition helper (a small Swift program built by Gradle from
 * src/main/swift/ocr.swift and bundled into the jar) and runs it on screenshots.
 */
final class OcrHelper {

    /** Classpath location of the helper binary that Gradle bundles into the jar. */
    static final String RESOURCE = "/bin/ocr";

    private OcrHelper() {
    }

    /** The helper named by -Dscreen.ocr, else the bundled copy unpacked into the user's cache. */
    static Path locate() throws IOException {
        return locate(Path.of(System.getProperty("user.home"), "Library", "Caches", "dev.zhihua.speech-editor"));
    }

    static Path locate(Path cacheDir) throws IOException {
        String configured = System.getProperty("screen.ocr");
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            if (Files.isExecutable(p)) return p;
            throw new IOException("screen.ocr points to " + configured + ", which is not an executable file");
        }
        try (InputStream in = OcrHelper.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("The text recognition helper is not bundled in this build. Build on macOS "
                        + "with the Xcode Command Line Tools installed, or set -Dscreen.ocr=<path to helper>.");
            }
            return extract(in.readAllBytes(), cacheDir);
        }
    }

    /**
     * Unpacks {@code binary} into {@code cacheDir} as an executable file named after its hash and
     * returns the path. An existing copy with the same hash is reused untouched.
     */
    static Path extract(byte[] binary, Path cacheDir) throws IOException {
        Path target = cacheDir.resolve("ocr-" + sha256Prefix(binary));
        if (Files.isRegularFile(target) && Files.size(target) == binary.length && Files.isExecutable(target)) {
            return target;
        }
        Files.createDirectories(cacheDir);
        Path tmp = Files.createTempFile(cacheDir, "ocr-", ".tmp");
        try {
            Files.write(tmp, binary);
            Files.setPosixFilePermissions(tmp, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    /** Runs the helper on an image and returns the recognized text (may be empty). */
    static String recognize(Path helper, Path image) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(helper.toString(), image.toString())
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .start();
        byte[] out = p.getInputStream().readAllBytes();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!p.waitFor(2, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("text recognition timed out");
        }
        if (p.exitValue() != 0) {
            throw new IOException("text recognition failed (exit " + p.exitValue() + ")"
                    + (err.isEmpty() ? "" : ": " + err));
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    private static String sha256Prefix(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
