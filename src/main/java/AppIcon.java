import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The app icon: a white screen with lines of text and an amber speaker with sound waves, on a
 * blue rounded square. Drawn with Java2D at whatever size is asked for, so every size is crisp.
 * {@link #main} writes the .iconset folder that Gradle packs into Screen Speaker.icns with
 * iconutil; Screen Speaker also uses the drawing for the Dock when run from sources.
 */
final class AppIcon {

    /** Point sizes of a macOS iconset; each is also written at @2x. */
    private static final int[] SIZES = {16, 32, 128, 256, 512};

    private static final Color PLATE_TOP = new Color(0x5A8CEF);
    private static final Color PLATE_BOTTOM = new Color(0x2A4DBF);
    private static final Color TEXT_LINE = new Color(0xB8C3E0);
    private static final Color AMBER = new Color(0xFFB13B);

    private AppIcon() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: java AppIcon <output folder ending in .iconset>");
            System.exit(2);
        }
        writeIconSet(Path.of(args[0]));
    }

    /** Writes icon_16x16.png ... icon_512x512@2x.png into {@code dir}, creating it if needed. */
    static void writeIconSet(Path dir) throws IOException {
        Files.createDirectories(dir);
        for (int size : SIZES) {
            ImageIO.write(render(size), "png", dir.resolve("icon_" + size + "x" + size + ".png").toFile());
            ImageIO.write(render(size * 2), "png", dir.resolve("icon_" + size + "x" + size + "@2x.png").toFile());
        }
    }

    /** The icon as a {@code size} by {@code size} transparent image. */
    static BufferedImage render(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(size, size); // everything below is drawn in a 1 by 1 unit square
            draw(g);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void draw(Graphics2D g) {
        // Rounded square per Apple's template: 824/1024 of the canvas, corner radius 22.5% of its side.
        double side = 0.805, inset = (1 - side) / 2, corner = side * 0.225 * 2;
        g.setPaint(new GradientPaint(0, (float) inset, PLATE_TOP, 0, (float) (inset + side), PLATE_BOTTOM));
        g.fill(new RoundRectangle2D.Double(inset, inset, side, side, corner, corner));

        // A screen with three lines of text.
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Double(0.20, 0.24, 0.56, 0.38, 0.05, 0.05));
        g.setColor(TEXT_LINE);
        double[] widths = {0.42, 0.35, 0.28};
        for (int i = 0; i < widths.length; i++) {
            g.fill(new RoundRectangle2D.Double(0.26, 0.31 + i * 0.075, widths[i], 0.045, 0.045, 0.045));
        }

        // A speaker with two sound waves over the screen's lower right corner. A halo in the plate
        // colour keeps it readable where it crosses the white.
        Path2D cone = new Path2D.Double();
        cone.moveTo(0.54, 0.60);
        cone.lineTo(0.60, 0.60);
        cone.lineTo(0.69, 0.515);
        cone.lineTo(0.69, 0.805);
        cone.lineTo(0.60, 0.72);
        cone.lineTo(0.54, 0.72);
        cone.closePath();
        Arc2D wave1 = new Arc2D.Double(0.69 - 0.075, 0.66 - 0.075, 0.15, 0.15, -42, 84, Arc2D.OPEN);
        Arc2D wave2 = new Arc2D.Double(0.69 - 0.135, 0.66 - 0.135, 0.27, 0.27, -42, 84, Arc2D.OPEN);
        Stroke halo = new BasicStroke(0.09f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        Stroke wave = new BasicStroke(0.038f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        g.setColor(PLATE_BOTTOM);
        g.setStroke(halo);
        g.draw(cone);
        g.draw(wave1);
        g.draw(wave2);
        g.setColor(AMBER);
        g.fill(cone);
        g.setStroke(wave);
        g.draw(wave1);
        g.draw(wave2);
    }
}
