import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Generates the FirstPick app icon as a 1024x1024 PNG (no SVG rasterizer needed — pure
 * Java2D). The motif is the app itself: a ranked pick list with the top pick highlighted
 * in the brand teal. Build the .icns from the output with packaging/icon/build-icon.sh.
 *
 *   javac IconGen.java && java IconGen icon-1024.png
 */
public class IconGen {
    public static void main(String[] args) throws Exception {
        int S = 1024;
        BufferedImage img = new BufferedImage(S, S, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // --- Squircle background (Apple icon grid: 824x824 inset, ~185 corner radius) ---
        double inset = 100, size = S - 2 * inset, rad = 185.4;
        RoundRectangle2D sq = new RoundRectangle2D.Double(inset, inset, size, size, rad * 2, rad * 2);
        // Flat, solid charcoal background (no gradient, no glow).
        g.setColor(new Color(0x14, 0x18, 0x1A));
        g.fill(sq);
        // subtle teal hairline to define the edge on dark backgrounds
        g.setColor(new Color(0x7F, 0xD1, 0xC4, 45));
        g.setStroke(new BasicStroke(3f));
        g.draw(new RoundRectangle2D.Double(inset + 2, inset + 2, size - 4, size - 4, rad * 2, rad * 2));

        // --- Ranked rows: top one highlighted (the "first pick") ---
        double rw = 524, rx = (S - rw) / 2;
        double rh = 140, gap = 52, rr = 36;
        double total = 3 * rh + 2 * gap;
        double y0 = (S - total) / 2;
        Color teal = new Color(0x7F, 0xD1, 0xC4);
        Color ink = new Color(0x12, 0x16, 0x18); // near-bg dark, for marks on the teal row
        Color[] dim = { new Color(0x2B, 0x32, 0x36), new Color(0x23, 0x2A, 0x2E) };

        for (int i = 0; i < 3; i++) {
            double y = y0 + i * (rh + gap);
            RoundRectangle2D row = new RoundRectangle2D.Double(rx, y, rw, rh, rr, rr);
            if (i == 0) {
                // flat, solid teal "first pick" row
                g.setColor(teal);
                g.fill(row);
                // rank marker (left dot)
                g.setColor(new Color(ink.getRed(), ink.getGreen(), ink.getBlue(), 150));
                g.fill(new Ellipse2D.Double(rx + 40, y + rh / 2 - 22, 44, 44));
                // checkmark (right)
                g.setColor(ink);
                g.setStroke(new BasicStroke(26f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double cx = rx + rw - 118, cy = y + rh / 2;
                Path2D chk = new Path2D.Double();
                chk.moveTo(cx - 48, cy + 4);
                chk.lineTo(cx - 12, cy + 40);
                chk.lineTo(cx + 52, cy - 40);
                g.draw(chk);
            } else {
                g.setColor(dim[i - 1]);
                g.fill(row);
                // muted content bar
                g.setColor(new Color(0x49, 0x55, 0x5A));
                g.fill(new RoundRectangle2D.Double(rx + 40, y + rh / 2 - 14, rw * 0.46, 28, 14, 14));
            }
        }

        g.dispose();
        String out = args.length > 0 ? args[0] : "icon-1024.png";
        ImageIO.write(img, "png", new File(out));
        System.out.println("wrote " + out);
    }
}
