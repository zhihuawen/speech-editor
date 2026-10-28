import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppIconTest {

    @Test
    void rendersASquareWithTransparentCornersAndAnOpaqueMiddle() {
        BufferedImage icon = AppIcon.render(64);

        assertEquals(64, icon.getWidth());
        assertEquals(64, icon.getHeight());
        assertEquals(0, icon.getRGB(0, 0) >>> 24, "the corner outside the rounded square must be transparent");
        assertEquals(255, icon.getRGB(32, 32) >>> 24, "the middle must be opaque");
    }

    @Test
    void writesTheTenIconsetFilesMacOsExpects(@TempDir Path dir) throws Exception {
        Path set = dir.resolve("Screen Speaker.iconset");

        AppIcon.main(new String[]{set.toString()});

        for (String name : List.of("icon_16x16.png", "icon_16x16@2x.png", "icon_32x32.png", "icon_32x32@2x.png",
                "icon_128x128.png", "icon_128x128@2x.png", "icon_256x256.png", "icon_256x256@2x.png",
                "icon_512x512.png", "icon_512x512@2x.png")) {
            assertTrue(Files.isRegularFile(set.resolve(name)), "missing " + name);
        }
        assertEquals(1024, ImageIO.read(set.resolve("icon_512x512@2x.png").toFile()).getWidth());
        assertEquals(32, ImageIO.read(set.resolve("icon_16x16@2x.png").toFile()).getWidth());
    }
}
