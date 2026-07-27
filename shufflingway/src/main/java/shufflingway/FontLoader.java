package shufflingway;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class FontLoader {

    /** Classpath directory holding the bundled UI fonts (.otf/.ttf). */
    private static final String FONT_DIR = "/resources/fonts";

    /** Point size at which cap heights are measured for normalization; larger = less rounding error. */
    private static final float MEASURE_SIZE = 100f;

    /**
     * Cap height of the default font ({@link AppSettings#DEFAULT_FONT_FILE}, Pixel NES) at
     * {@link #MEASURE_SIZE}. This is the size baseline: every {@code loadPixelFont} size constant was
     * tuned against it, so the default font scales by exactly 1.0 and other fonts are matched to it.
     */
    private static final double REFERENCE_CAP_HEIGHT =
            capHeight(loadFontResource(FONT_DIR + "/" + AppSettings.DEFAULT_FONT_FILE));

    /** The base UI font, derived per-call to the requested size. */
    private static Font baseFont;

    /** Per-font multiplier that matches {@link #baseFont}'s cap height to {@link #REFERENCE_CAP_HEIGHT}. */
    private static double fontScale = 1.0;

    static {
        baseFont = loadRegistered(AppSettings.getFontFile());
        if (baseFont == null && !AppSettings.DEFAULT_FONT_FILE.equals(AppSettings.getFontFile()))
            baseFont = loadRegistered(AppSettings.DEFAULT_FONT_FILE);
        if (baseFont == null)
            System.err.println("Failed to load UI font from " + FONT_DIR);
        fontScale = scaleFor(baseFont);
    }

    /** One selectable font: its family display name, backing resource file, and loaded {@link Font}. */
    public record FontChoice(String displayName, String fileName, Font font) {
        @Override public String toString() { return displayName; }
    }

    /**
     * Returns the base UI font derived to {@code size} (after UI scaling). All app text flows
     * through here, so switching the base font (see {@link #setBaseFontFile}) affects the whole UI.
     */
    public static Font loadPixelFont(float size) {
        float scaled = UiScale.scale(size);
        if (baseFont != null) return baseFont.deriveFont((float) (scaled * fontScale));
        return new Font("Arial", Font.PLAIN, (int) scaled);
    }

    /** Swaps the base font to the given bundled file; future {@code loadPixelFont} calls use it. */
    public static void setBaseFontFile(String fileName) {
        Font f = loadRegistered(fileName);
        if (f != null) { baseFont = f; fontScale = scaleFor(f); }
    }

    // -------------------------------------------------------------------------
    // Glyph fallback
    // -------------------------------------------------------------------------

    /**
     * Stand-in for characters the bundled fonts have no glyph for. Both bundled fonts cover ASCII
     * and Latin-1 only, so arrows (← →) and the CP-cost brackets (《 》) would otherwise render as
     * missing-glyph boxes. {@code Font.DIALOG} is a JVM logical font, so it is always present.
     */
    private static final Font FALLBACK_BASE = new Font(Font.DIALOG, Font.PLAIN, 1);

    /** Cap-height multiplier that sizes {@link #FALLBACK_BASE} to sit alongside the base font. */
    private static final double FALLBACK_SCALE = scaleFor(FALLBACK_BASE);

    /**
     * The fallback font at the same requested size as {@link #loadPixelFont}, cap-height matched so
     * substituted glyphs sit at the same visual size as the pixel text around them.
     */
    public static Font fallbackFont(float size) {
        float scaled = UiScale.scale(size);
        return FALLBACK_BASE.deriveFont((float) (scaled * FALLBACK_SCALE));
    }

    /** True when the base UI font has a glyph for every character in {@code text}. */
    public static boolean canDisplayAll(String text) {
        return baseFont == null || text == null || baseFont.canDisplayUpTo(text) < 0;
    }

    /**
     * Draws {@code text} with its baseline at ({@code x}, {@code y}), rendering each character in
     * {@code base} and falling back to {@code fallback} only for the ones {@code base} cannot
     * display. Leaves {@code g2}'s font set to {@code base}.
     *
     * @return the total advance width, so callers can lay out what follows
     */
    public static float drawWithFallback(Graphics2D g2, String text, float x, float y,
                                         Font base, Font fallback) {
        float cursor = x;
        for (int i = 0; i < text.length(); ) {
            Font run = fontFor(text.charAt(i), base, fallback);
            int end = i + 1;
            while (end < text.length() && fontFor(text.charAt(end), base, fallback) == run) end++;
            String chunk = text.substring(i, end);
            g2.setFont(run);
            g2.drawString(chunk, cursor, y);
            cursor += (float) run.getStringBounds(chunk, g2.getFontRenderContext()).getWidth();
            i = end;
        }
        g2.setFont(base);
        return cursor - x;
    }

    /**
     * Advance width {@link #drawWithFallback} would produce for {@code text} — use this instead of
     * {@code FontMetrics.stringWidth} when centring text that may contain substituted glyphs.
     */
    public static float widthWithFallback(Graphics2D g2, String text, Font base, Font fallback) {
        float w = 0;
        for (int i = 0; i < text.length(); ) {
            Font run = fontFor(text.charAt(i), base, fallback);
            int end = i + 1;
            while (end < text.length() && fontFor(text.charAt(end), base, fallback) == run) end++;
            w += (float) run.getStringBounds(text.substring(i, end), g2.getFontRenderContext()).getWidth();
            i = end;
        }
        return w;
    }

    private static Font fontFor(char c, Font base, Font fallback) {
        return base.canDisplay(c) ? base : fallback;
    }

    /**
     * Wraps {@code text} in HTML so the characters the base UI font lacks render in the fallback
     * family, for Swing components ({@code JLabel}, {@code AbstractButton}) that take a single
     * {@link Font} and so cannot mix runs the way {@link #drawWithFallback} does. Everything else
     * keeps the component's own font.
     *
     * <p>Returns {@code text} unchanged when nothing needs substituting, so the common case keeps
     * plain-text layout rather than paying for Swing's HTML view.
     */
    public static String htmlWithFallback(String text) {
        if (text == null || canDisplayAll(text)) return text;
        StringBuilder sb = new StringBuilder("<html>");
        boolean inFallback = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean needs = baseFont != null && !baseFont.canDisplay(c);
            if (needs != inFallback) {
                sb.append(needs ? "<span style='font-family:" + FALLBACK_BASE.getFamily() + "'>"
                                : "</span>");
                inFallback = needs;
            }
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default  -> {
                    if (c > 127) sb.append("&#").append((int) c).append(';');
                    else         sb.append(c);
                }
            }
        }
        if (inFallback) sb.append("</span>");
        return sb.append("</html>").toString();
    }

    /**
     * Enumerates the fonts bundled in {@link #FONT_DIR}, each loaded so callers can preview it.
     * Sorted by display name. Never empty when at least the default font is present.
     */
    public static List<FontChoice> availableFonts() {
        List<FontChoice> out = new ArrayList<>();
        for (String file : listFontFiles()) {
            Font f = loadFontResource(FONT_DIR + "/" + file);
            if (f != null) out.add(new FontChoice(f.getFamily(), file, f));
        }
        if (out.isEmpty()) {
            Font f = loadFontResource(FONT_DIR + "/" + AppSettings.DEFAULT_FONT_FILE);
            if (f != null) out.add(new FontChoice(f.getFamily(), AppSettings.DEFAULT_FONT_FILE, f));
        }
        out.sort(Comparator.comparing(FontChoice::displayName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Multiplier that makes {@code font}'s cap height match the default font's, so its glyphs render
     * at roughly the same visual size for a given {@code loadPixelFont} argument. Returns 1.0 when
     * either measurement is unavailable (e.g. headless load failure).
     */
    private static double scaleFor(Font font) {
        double h = capHeight(font);
        return (REFERENCE_CAP_HEIGHT > 0 && h > 0) ? REFERENCE_CAP_HEIGHT / h : 1.0;
    }

    /** Visual height of a capital "H" for {@code font} at {@link #MEASURE_SIZE}, or 0 if unmeasurable. */
    private static double capHeight(Font font) {
        if (font == null) return 0;
        FontRenderContext frc = new FontRenderContext(null, true, true);
        GlyphVector gv = font.deriveFont(MEASURE_SIZE).createGlyphVector(frc, "H");
        return gv.getVisualBounds().getHeight();
    }

    /** Loads a bundled font and registers it with the graphics environment. */
    private static Font loadRegistered(String fileName) {
        Font f = loadFontResource(FONT_DIR + "/" + fileName);
        if (f != null) {
            try { GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f); }
            catch (Exception ignored) {}
        }
        return f;
    }

    /** Creates a {@link Font} from a classpath resource path, or {@code null} if it can't be read. */
    private static Font loadFontResource(String path) {
        try (InputStream is = FontLoader.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return Font.createFont(Font.TRUETYPE_FONT, is);
        } catch (Exception e) {
            return null;
        }
    }

    /** Lists the font filenames (e.g. {@code "Pixel_NES.otf"}) in {@link #FONT_DIR}, on disk or in a jar. */
    private static List<String> listFontFiles() {
        List<String> files = new ArrayList<>();
        try {
            URL dir = FontLoader.class.getResource(FONT_DIR);
            if (dir == null) return files;
            if ("file".equals(dir.getProtocol())) {
                File[] fs = new File(dir.toURI()).listFiles((d, name) -> isFontFile(name));
                if (fs != null) for (File f : fs) files.add(f.getName());
            } else if ("jar".equals(dir.getProtocol())) {
                String path = dir.getPath();               // file:/…/app.jar!/resources/fonts
                int bang = path.indexOf('!');
                String jarPath = URLDecoder.decode(path.substring("file:".length(), bang), StandardCharsets.UTF_8);
                String prefix = FONT_DIR.substring(1) + "/"; // resources/fonts/
                try (JarFile jar = new JarFile(jarPath)) {
                    Enumeration<JarEntry> en = jar.entries();
                    while (en.hasMoreElements()) {
                        String name = en.nextElement().getName();
                        if (name.startsWith(prefix) && isFontFile(name))
                            files.add(name.substring(prefix.length()));
                    }
                }
            }
        } catch (Exception ignored) {}
        return files;
    }

    private static boolean isFontFile(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".otf") || n.endsWith(".ttf");
    }
}
