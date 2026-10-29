import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrHelperTest {

    @Test
    void extractWritesAnExecutableCopyOfTheBundledBinary(@TempDir Path cache) throws Exception {
        byte[] binary = {1, 2, 3, 4};

        Path helper = OcrHelper.extract(binary, cache);

        assertTrue(helper.startsWith(cache), "helper should live inside the cache dir");
        assertArrayEquals(binary, Files.readAllBytes(helper));
        assertTrue(Files.isExecutable(helper), "helper should be executable");
    }

    @Test
    void extractReusesAnExistingCopyInsteadOfRewritingIt(@TempDir Path cache) throws Exception {
        byte[] binary = {9, 8, 7};
        Path first = OcrHelper.extract(binary, cache);
        FileTime old = FileTime.fromMillis(1_000_000_000L);
        Files.setLastModifiedTime(first, old);

        Path second = OcrHelper.extract(binary, cache);

        assertEquals(first, second);
        assertEquals(old, Files.getLastModifiedTime(second), "existing helper must not be rewritten");
    }

    @Test
    void extractGivesDifferentBinariesDifferentPaths(@TempDir Path cache) throws Exception {
        Path a = OcrHelper.extract(new byte[]{1}, cache);
        Path b = OcrHelper.extract(new byte[]{2}, cache);

        assertNotEquals(a, b);
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(a));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(b));
    }

    @Test
    void locateHonoursTheScreenOcrSystemProperty(@TempDir Path dir) throws Exception {
        Path custom = dir.resolve("my-ocr");
        Files.write(custom, new byte[]{1});
        custom.toFile().setExecutable(true);
        Path cache = dir.resolve("cache");
        String before = System.getProperty("screen.ocr");
        System.setProperty("screen.ocr", custom.toString());
        try {
            assertEquals(custom, OcrHelper.locate(cache));
            assertTrue(Files.notExists(cache), "nothing should be unpacked when a helper is configured");
        } finally {
            if (before == null) System.clearProperty("screen.ocr"); else System.setProperty("screen.ocr", before);
        }
    }

    @Test
    void locateUnpacksTheBundledHelperWhenNothingIsConfigured(@TempDir Path cache) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(OcrHelper.class.getResource("/bin/ocr") != null,
                "bundled helper only exists on macOS builds");
        String before = System.getProperty("screen.ocr");
        System.clearProperty("screen.ocr");
        try {
            Path helper = OcrHelper.locate(cache);
            assertTrue(helper.startsWith(cache));
            assertTrue(Files.isExecutable(helper));
        } finally {
            if (before != null) System.setProperty("screen.ocr", before);
        }
    }

    @Test
    void extractNamesTheCopyAfterTheHelperItCameFrom(@TempDir Path cache) throws Exception {
        Path helper = OcrHelper.extract("hotkey", new byte[]{1, 2}, cache);

        assertTrue(helper.getFileName().toString().startsWith("hotkey-"), helper.toString());
        assertTrue(Files.isExecutable(helper));
    }
}
