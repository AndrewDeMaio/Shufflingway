package shufflingway.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.border.TitledBorder;

import shufflingway.CardData;
import shufflingway.FontLoader;
import shufflingway.ImageCache;
import shufflingway.UiScale;
import shufflingway.graphics.CardAnimation;

/**
 * The two halves of Kefka 15-071H: "Divide all the Forwards opponent controls into N groups …
 * Your opponent selects 1 group among them. Put all the Forwards of the other groups into the
 * Break Zone."
 *
 * <p>Two dialogs because two players decide, one after the other, and neither sees the other's
 * screen. {@link #showDivide} puts the Forwards in a holding row and lets the ability's controller
 * drag each one into a coloured group; {@link #showSelect} shows the finished groups to the player
 * who owns those Forwards and asks which one survives. The second is laid out from the first's
 * answer, so the player choosing sees the split exactly as it was made.
 *
 * <p>Neither can be dismissed. The ability has already been paid for by the time either opens, and
 * a closed dialog would leave the effect half-resolved — and, in a networked game, one client
 * waiting on an answer that is never sent.
 *
 * <p>Cards are drawn smaller than in the hand pickers: three groups side by side, each wide enough
 * for a board's worth of Forwards, does not fit at full size.
 */
public final class DivideIntoGroupsDialog {

    private DivideIntoGroupsDialog() {}

    /** A card in the holding row, waiting to be put in a group. */
    private static final int UNASSIGNED = -1;

    private static final int TILE_W = CardAnimation.CARD_W * 3 / 5;
    private static final int TILE_H = CardAnimation.CARD_H * 3 / 5;

    /**
     * Group colours, in order. Chosen to stay apart from the blue and yellow the board's own
     * selection glow uses, so a group tint never reads as "this card is selected".
     */
    private static final Color[] ZONE_COLORS = {
        new Color(198,  76,  76),   // red
        new Color( 88, 148,  96),   // green
        new Color(150, 108, 186),   // violet
        new Color(202, 152,  70),   // amber — only reached if a future card asks for four
    };

    /** The flavour a dragged card travels under: its position in the dialog's card list. */
    private static final DataFlavor INDEX_FLAVOR = DataFlavor.stringFlavor;

    // -------------------------------------------------------------------------
    // Dividing
    // -------------------------------------------------------------------------

    /**
     * Asks the local player to put every card in {@code cards} into one of {@code groupCount}
     * groups, and returns the group each one ended up in, positionally.
     *
     * <p>Submit stays disabled until the holding row is empty: the card says to divide <em>all</em>
     * the Forwards, so leaving one out is not a division. Groups may be empty — the card says so
     * outright, and an empty group is the whole point of the effect when the player wants to make
     * every option a bad one.
     */
    public static List<Integer> showDivide(JFrame owner, List<CardData> cards, int groupCount,
                                           Consumer<String> onZoom, Runnable onZoomHide) {
        final int[] group = new int[cards.size()];
        java.util.Arrays.fill(group, UNASSIGNED);

        JDialog dlg = modalDialog(owner, "Divide your opponent's Forwards");

        JPanel holding = zonePanel("Forwards to divide — drag each one into a group", Color.GRAY);
        JPanel[] zones = new JPanel[groupCount];
        JPanel zoneRow = new JPanel(new GridLayout(1, groupCount, UiScale.scale(8), 0));
        for (int g = 0; g < groupCount; g++) {
            zones[g] = zonePanel("Group " + (g + 1), zoneColor(g));
            zoneRow.add(zones[g]);
        }

        JLabel status = new JLabel("", SwingConstants.CENTER);
        status.setFont(FontLoader.loadPixelFont(10));
        JButton submit = new JButton("Submit");
        submit.setFont(FontLoader.loadPixelFont(11));

        List<JComponent> tiles = new ArrayList<>(cards.size());

        Runnable relayout = () -> {
            holding.removeAll();
            for (JPanel z : zones) z.removeAll();
            for (int i = 0; i < tiles.size(); i++)
                (group[i] == UNASSIGNED ? holding : zones[group[i]]).add(tiles.get(i));
            int left = 0;
            for (int g : group) if (g == UNASSIGNED) left++;
            status.setText(left > 0
                    ? left + " Forward(s) still to place — every one has to go in a group."
                    : "All placed. Your opponent will keep one group; the rest are broken.");
            submit.setEnabled(left == 0);
            holding.revalidate(); holding.repaint();
            for (JPanel z : zones) { z.revalidate(); z.repaint(); }
        };

        // The holding row and each group accept drops; so does every card, standing in for whatever
        // it is currently sitting in. Swing does not bubble a drop to a parent, so a card dropped
        // onto an area a previous card already covers would otherwise land nowhere.
        holding.setTransferHandler(new GroupDropHandler(() -> UNASSIGNED, group, relayout));
        for (int g = 0; g < groupCount; g++) {
            final int zone = g;
            zones[g].setTransferHandler(new GroupDropHandler(() -> zone, group, relayout));
        }
        for (int i = 0; i < cards.size(); i++) {
            final int idx = i;
            JComponent tile = buildTile(cards.get(i), onZoom, onZoomHide);
            tile.putClientProperty(TILE_INDEX, idx);
            tile.setTransferHandler(new GroupDropHandler(() -> group[idx], group, relayout));
            tile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            tile.addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    onZoomHide.run();
                    JComponent c = (JComponent) e.getSource();
                    c.getTransferHandler().exportAsDrag(c, e, TransferHandler.MOVE);
                }
            });
            tiles.add(tile);
        }

        submit.addActionListener(ae -> { onZoomHide.run(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(UiScale.scale(8), 0));
        south.add(status, BorderLayout.CENTER);
        south.add(submit, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout(0, UiScale.scale(8)));
        body.add(holding, BorderLayout.NORTH);
        body.add(zoneRow, BorderLayout.CENTER);
        body.add(south,   BorderLayout.SOUTH);

        relayout.run();
        show(dlg, owner, body, cards.size(), groupCount);

        List<Integer> out = new ArrayList<>(group.length);
        for (int g : group) out.add(g);
        return out;
    }

    // -------------------------------------------------------------------------
    // Selecting
    // -------------------------------------------------------------------------

    /**
     * Shows {@code cards} laid out by {@code assignment} and asks the local player which group to
     * keep, returning its number. Every group is offered, empty ones included: keeping nothing is a
     * legal answer, and hiding it would misrepresent the choice the divider actually left.
     */
    public static int showSelect(JFrame owner, List<CardData> cards, List<Integer> assignment,
                                 int groupCount, Consumer<String> onZoom, Runnable onZoomHide) {
        JDialog dlg = modalDialog(owner, "Choose the group to keep");
        final int[] kept = { 0 };

        JPanel zoneRow = new JPanel(new GridLayout(1, groupCount, UiScale.scale(8), 0));
        for (int g = 0; g < groupCount; g++) {
            final int zone = g;
            JPanel cardsPanel = zonePanel("Group " + (g + 1), zoneColor(g));
            int members = 0;
            for (int i = 0; i < cards.size() && i < assignment.size(); i++) {
                if (assignment.get(i) != zone) continue;
                cardsPanel.add(buildTile(cards.get(i), onZoom, onZoomHide));
                members++;
            }
            if (members == 0) {
                JLabel empty = new JLabel("(no Forwards)", SwingConstants.CENTER);
                empty.setFont(FontLoader.loadPixelFont(10));
                empty.setPreferredSize(new Dimension(TILE_W, TILE_H));
                cardsPanel.add(empty);
            }
            JButton keep = new JButton("Keep group " + (g + 1));
            keep.setFont(FontLoader.loadPixelFont(11));
            keep.addActionListener(ae -> { kept[0] = zone; onZoomHide.run(); dlg.dispose(); });

            JPanel column = new JPanel(new BorderLayout(0, UiScale.scale(6)));
            column.add(cardsPanel, BorderLayout.CENTER);
            column.add(keep,       BorderLayout.SOUTH);
            zoneRow.add(column);
        }

        JLabel note = new JLabel(
                "Your opponent divided your Forwards. Keep one group — the rest are put into the Break Zone.",
                SwingConstants.CENTER);
        note.setFont(FontLoader.loadPixelFont(10));

        JPanel body = new JPanel(new BorderLayout(0, UiScale.scale(8)));
        body.add(note,    BorderLayout.NORTH);
        body.add(zoneRow, BorderLayout.CENTER);

        show(dlg, owner, body, cards.size(), groupCount);
        return kept[0];
    }

    // -------------------------------------------------------------------------
    // Pieces
    // -------------------------------------------------------------------------

    /**
     * Moves the dragged card into whatever group this handler speaks for. One class serves the
     * holding row, the groups and the cards themselves; {@code target} is what tells them apart,
     * and it is read at drop time so a card standing in for its group follows that group when it
     * moves.
     */
    private static final class GroupDropHandler extends TransferHandler {
        private final IntSupplier target;
        private final int[]       group;
        private final Runnable    relayout;

        GroupDropHandler(IntSupplier target, int[] group, Runnable relayout) {
            this.target = target; this.group = group; this.relayout = relayout;
        }

        @Override public int getSourceActions(JComponent c) { return MOVE; }

        @Override protected Transferable createTransferable(JComponent c) {
            JComponent tile = c;
            Object idx = tile.getClientProperty(TILE_INDEX);
            return idx == null ? null : new StringSelection(idx.toString());
        }

        @Override public boolean canImport(TransferSupport support) {
            return support.isDrop() && support.isDataFlavorSupported(INDEX_FLAVOR);
        }

        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            int idx;
            try {
                idx = Integer.parseInt((String) support.getTransferable().getTransferData(INDEX_FLAVOR));
            } catch (Exception e) {
                return false;
            }
            if (idx < 0 || idx >= group.length) return false;
            group[idx] = target.getAsInt();
            relayout.run();
            return true;
        }
    }

    /** Client-property key holding a card tile's position in the dialog's card list. */
    private static final String TILE_INDEX = "shufflingway.divideTileIndex";

    /** A card's picture with its name under it, at group-dialog size. */
    private static JComponent buildTile(CardData card, Consumer<String> onZoom, Runnable onZoomHide) {
        JLabel art = new JLabel("...", SwingConstants.CENTER);
        art.setPreferredSize(new Dimension(TILE_W, TILE_H));
        art.setOpaque(true);
        art.setBackground(Color.DARK_GRAY);
        art.setForeground(Color.WHITE);
        art.setFont(FontLoader.loadPixelFont(9));
        loadTileImage(art, card.imageUrl());

        JLabel name = new JLabel(card.name(), SwingConstants.CENTER);
        name.setFont(FontLoader.loadPixelFont(9));
        name.setPreferredSize(new Dimension(TILE_W, UiScale.scale(16)));

        JPanel tile = new JPanel(new BorderLayout(0, UiScale.scale(2)));
        tile.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
        tile.add(art,  BorderLayout.CENTER);
        tile.add(name, BorderLayout.SOUTH);
        // Hover-zoom is registered on the tile rather than the picture so the name strip and the
        // border do not count as leaving the card and flicker the zoom off mid-drag.
        tile.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { onZoom.accept(card.imageUrl()); }
            @Override public void mouseExited(MouseEvent e)  { onZoomHide.run(); }
        });
        return tile;
    }

    /** A drop area with a coloured, titled border. */
    private static JPanel zonePanel(String title, Color color) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, UiScale.scale(6), UiScale.scale(6)));
        TitledBorder border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(color, UiScale.scale(3)), title);
        border.setTitleFont(FontLoader.loadPixelFont(11));
        border.setTitleColor(color.darker());
        panel.setBorder(border);
        // Tinted rather than filled: the cards have to stay readable on top of it.
        panel.setBackground(new Color(color.getRed(), color.getGreen(), color.getBlue(), 28));
        return panel;
    }

    private static Color zoneColor(int group) {
        return ZONE_COLORS[group % ZONE_COLORS.length];
    }

    /** A dialog that has to be answered: no close box, no escape, no decoration to fight with. */
    private static JDialog modalDialog(JFrame owner, String title) {
        JDialog dlg = new JDialog(owner, title, true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        return dlg;
    }

    /**
     * Sizes {@code body} so a group can hold the whole row of Forwards without the layout
     * reflowing as cards move between groups, then shows the dialog.
     */
    private static void show(JDialog dlg, JFrame owner, JPanel body, int cardCount, int groupCount) {
        body.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createRaisedBevelBorder(),
                BorderFactory.createEmptyBorder(UiScale.scale(10), UiScale.scale(10),
                        UiScale.scale(10), UiScale.scale(10))));
        int perZone  = Math.max(1, (cardCount + groupCount - 1) / groupCount + 1);
        int zoneW    = perZone * (TILE_W + UiScale.scale(12)) + UiScale.scale(16);
        int minWidth = Math.max(zoneW * groupCount, cardCount * (TILE_W + UiScale.scale(12)));
        body.setPreferredSize(new Dimension(
                Math.max(minWidth, UiScale.scale(640)), body.getPreferredSize().height));
        dlg.getContentPane().add(body);
        dlg.pack();
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
    }

    private static void loadTileImage(JLabel lbl, String url) {
        new SwingWorker<ImageIcon, Void>() {
            @Override protected ImageIcon doInBackground() throws Exception {
                Image img = ImageCache.load(url);
                if (img == null) return null;
                BufferedImage buf = new BufferedImage(TILE_W, TILE_H, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = buf.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(img, 0, 0, TILE_W, TILE_H, null);
                g2.dispose();
                return new ImageIcon(buf);
            }
            @Override protected void done() {
                try {
                    ImageIcon icon = get();
                    if (icon != null) { lbl.setIcon(icon); lbl.setText(null); }
                } catch (InterruptedException | ExecutionException ignored) { }
            }
        }.execute();
    }
}
