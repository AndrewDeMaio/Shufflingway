package shufflingway;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * PhaseTracker — a Swing component for displaying FFTCG turn phase progression.
 *
 * Six diamond markers (Active, Draw, Main 1, Attack, Main 2, End) sit on a
 * horizontal track. The current phase glows blue when {@code isMyTurn} is true,
 * red otherwise. On phase change the old diamond's halo fades out as the new
 * one's fades in over ~240ms.
 *
 * Usage (controlled — parent owns state):
 * <pre>
 *   PhaseTracker tracker = new PhaseTracker();
 *   sidePanel.add(tracker);
 *   tracker.setState("Main 1", 3, true);   // phase, turn number, your turn?
 * </pre>
 */
public class PhaseTracker extends JPanel {

    public static final String[] PHASES = {
        "Active", "Draw", "Main 1", "Attack", "Main 2", "End"
    };
    private static final String[] PHASE_LABELS = {
        "ACT", "DRAW", "M1", "ATK", "M2", "END"
    };

    private static final int    DIAMOND       = 20;
    private static final int    PAD_X         = 12;
    private static final int    PAD_TOP       = 8;
    private static final int    PAD_BOTTOM    = 8;
    private static final int    TOP_STRIP_H   = 22;
    private static final int    LABEL_GAP     = 8;
    private static final int    LABEL_H       = 10;
    private static final int    GLOW_RADIUS   = 20;

    private static final Color  BG            = new Color(0xd4d0c8);
    private static final Color  STROKE        = new Color(0x222222);
    private static final Color  PAST_FILL     = new Color(0x8a8a8a);
    private static final Color  CONNECTOR_MID = new Color(0x555555);
    private static final Color  CONNECTOR_HI  = new Color(0xaaaaaa);
    private static final Color  CONNECTOR_LO  = new Color(0x333333);

    private static final Color  BLUE          = new Color(0x4ab4ff);
    private static final Color  BLUE_FILL     = new Color(0xe8f4ff);
    private static final Color  BLUE_PILL_BG  = new Color(0x1d4f7a);

    private static final Color  RED           = new Color(0xff5252);
    private static final Color  RED_FILL      = new Color(0xffe8e8);
    private static final Color  RED_PILL_BG   = new Color(0x7a1d1d);

    private int     phaseIdx     = 0;
    private int     prevPhaseIdx = 0;
    private int     turn         = 1;
    private boolean isMyTurn     = true;

    private static final int ANIM_MS = 240;
    private long  animStart  = 0L;
    private float progress   = 1f;
    private final Timer animTimer;

    private Font pixelFont;
    private Font pixelFontSmall;

    public PhaseTracker() {
        setOpaque(true);
        setBackground(BG);
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x888888)));

        Font base;
        try {
            base = new Font("Press Start 2P", Font.PLAIN, 7);
            if (!base.getFamily().toLowerCase().contains("press")) {
                base = new Font(Font.MONOSPACED, Font.BOLD, 9);
            }
        } catch (Exception e) {
            base = new Font(Font.MONOSPACED, Font.BOLD, 9);
        }
        pixelFont      = base.deriveFont(13f);
        pixelFontSmall = base.deriveFont(12f);

        int h = PAD_TOP + TOP_STRIP_H + 8 + DIAMOND + LABEL_GAP + LABEL_H + PAD_BOTTOM;
        setPreferredSize(new Dimension(Short.MAX_VALUE, h));
        setMinimumSize(new Dimension(100, h));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, h));

        animTimer = new Timer(15, e -> {
            long now = System.currentTimeMillis();
            float p = Math.min(1f, (now - animStart) / (float) ANIM_MS);
            progress = easeOut(p);
            if (p >= 1f) {
                progress = 1f;
                ((Timer) e.getSource()).stop();
            }
            repaint();
        });
    }

    public void setState(String phase, int turn, boolean isMyTurn) {
        setPhase(phase);
        setTurn(turn);
        setMyTurn(isMyTurn);
    }

    public void setPhase(String phase) {
        int next = indexOfPhase(phase);
        if (next < 0 || next == phaseIdx) return;
        prevPhaseIdx = phaseIdx;
        phaseIdx     = next;
        animStart    = System.currentTimeMillis();
        progress     = 0f;
        animTimer.restart();
        repaint();
    }

    public void setTurn(int turn) {
        if (this.turn == turn) return;
        this.turn = turn;
        repaint();
    }

    public void setMyTurn(boolean isMyTurn) {
        if (this.isMyTurn == isMyTurn) return;
        this.isMyTurn = isMyTurn;
        repaint();
    }

    public String  getPhase()    { return PHASES[phaseIdx]; }
    public int     getTurn()     { return turn; }
    public boolean isMyTurn()    { return isMyTurn; }

    private static int indexOfPhase(String phase) {
        for (int i = 0; i < PHASES.length; i++) {
            if (PHASES[i].equalsIgnoreCase(phase)) return i;
        }
        return -1;
    }

    private static float easeOut(float t) { return 1f - (1f - t) * (1f - t); }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        int w = getWidth();

        Color glow     = isMyTurn ? BLUE        : RED;
        Color glowFill = isMyTurn ? BLUE_FILL   : RED_FILL;
        Color pillBg   = isMyTurn ? BLUE_PILL_BG: RED_PILL_BG;

        g.setFont(pixelFont);
        g.setColor(new Color(0x333333));
        FontMetrics fm = g.getFontMetrics();
        int stripY = PAD_TOP + fm.getAscent();
        g.drawString("TURN " + turn, PAD_X, stripY);

        String pillText  = isMyTurn ? "YOUR TURN" : "OPP TURN";
        int    pillTextW = fm.stringWidth(pillText);
        int    pillPadX  = 5, pillPadY = 2;
        int    pillW     = pillTextW + pillPadX * 2;
        int    pillH     = fm.getAscent() + fm.getDescent() + pillPadY * 2;
        int    pillX     = w - PAD_X - pillW;
        int    pillY     = PAD_TOP + (TOP_STRIP_H - pillH) / 2;
        g.setColor(pillBg);
        g.fillRect(pillX, pillY, pillW, pillH);
        g.setColor(Color.WHITE);
        g.drawString(pillText, pillX + pillPadX, pillY + pillPadY + fm.getAscent());

        int trackY  = PAD_TOP + TOP_STRIP_H + 6;
        int centerY = trackY + DIAMOND / 2;
        int innerLeft  = PAD_X + DIAMOND / 2;
        int innerRight = w - PAD_X - DIAMOND / 2;
        int span       = innerRight - innerLeft;
        int n          = PHASES.length;

        int[] cx = new int[n];
        for (int i = 0; i < n; i++) {
            cx[i] = innerLeft + Math.round(span * (i / (float) (n - 1)));
        }

        for (int i = 0; i < n - 1; i++) {
            int x1 = cx[i]   + DIAMOND / 2 + 1;
            int x2 = cx[i+1] - DIAMOND / 2 - 1;
            if (x2 <= x1) continue;
            g.setColor(CONNECTOR_LO);
            g.fillRect(x1, centerY - 1, x2 - x1, 1);
            g.setColor(CONNECTOR_MID);
            g.fillRect(x1, centerY,     x2 - x1, 1);
            g.setColor(CONNECTOR_HI);
            g.fillRect(x1, centerY + 1, x2 - x1, 1);
        }

        for (int i = 0; i < n; i++) {
            boolean isPast = i < phaseIdx;

            float haloAlpha = 0f;
            if (i == phaseIdx)      haloAlpha = progress;
            else if (i == prevPhaseIdx && progress < 1f) haloAlpha = 1f - progress;

            if (haloAlpha > 0.01f) {
                drawHalo(g, cx[i], centerY, glow, haloAlpha);
            }

            Color fill;
            if (haloAlpha > 0.01f) {
                Color base = isPast ? PAST_FILL : new Color(0, 0, 0, 0);
                fill = lerpColor(base, glowFill, haloAlpha);
            } else if (isPast) {
                fill = PAST_FILL;
            } else {
                fill = null;
            }

            Color border;
            if (haloAlpha > 0.01f) border = lerpColor(STROKE, glow, haloAlpha);
            else                   border = STROKE;

            drawDiamond(g, cx[i], centerY, DIAMOND, fill, border);

            String label = PHASE_LABELS[i];
            g.setFont(pixelFontSmall);
            FontMetrics lfm = g.getFontMetrics();
            int labelW = lfm.stringWidth(label);
            int labelX = cx[i] - labelW / 2;
            int labelY = trackY + DIAMOND + LABEL_GAP + lfm.getAscent();

            Color labelColor;
            if (haloAlpha > 0.01f) labelColor = lerpColor(isPast ? new Color(0x666666) : new Color(0xaaaaaa), glow, haloAlpha);
            else if (isPast)       labelColor = new Color(0x666666);
            else                   labelColor = new Color(0xaaaaaa);
            g.setColor(labelColor);
            g.drawString(label, labelX, labelY);
        }

        g.dispose();
    }

    private void drawHalo(Graphics2D g, int cx, int cy, Color color, float alpha) {
        int r = GLOW_RADIUS;
        Point2D center = new Point2D.Float(cx, cy);
        float[] dist = { 0.0f, 0.35f, 1.0f };
        Color core = new Color(color.getRed(), color.getGreen(), color.getBlue(),
                               Math.round(220 * alpha));
        Color mid  = new Color(color.getRed(), color.getGreen(), color.getBlue(),
                               Math.round(110 * alpha));
        Color edge = new Color(color.getRed(), color.getGreen(), color.getBlue(), 0);
        Color[] colors = { core, mid, edge };
        RadialGradientPaint paint = new RadialGradientPaint(center, r, dist, colors);
        Paint old = g.getPaint();
        g.setPaint(paint);
        g.fillOval(cx - r, cy - r, r * 2, r * 2);
        g.setPaint(old);
    }

    private void drawDiamond(Graphics2D g, int cx, int cy, int size,
                             Color fill, Color border) {
        int half = size / 2;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(cx,        cy - half);
        p.lineTo(cx + half, cy);
        p.lineTo(cx,        cy + half);
        p.lineTo(cx - half, cy);
        p.closePath();

        if (fill != null) {
            g.setColor(fill);
            g.fill(p);
        }
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(border);
        g.draw(p);
    }

    private static Color lerpColor(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = a.getRed(),   ag = a.getGreen(), ab = a.getBlue(),  aa = a.getAlpha();
        int br = b.getRed(),   bg = b.getGreen(), bb = b.getBlue(),  ba = b.getAlpha();
        return new Color(
            Math.round(ar + (br - ar) * t),
            Math.round(ag + (bg - ag) * t),
            Math.round(ab + (bb - ab) * t),
            Math.round(aa + (ba - aa) * t)
        );
    }
}
