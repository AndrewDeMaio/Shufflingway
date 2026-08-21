package shufflingway.dialog;

import shufflingway.*;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

import shufflingway.graphics.CardAnimation;
import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;
import static shufflingway.CpPaymentUtils.contributingElement;
import static shufflingway.CpPaymentUtils.matchesAnyElement;

/** CP payment dialog for a card's alternate cost (non-Warp). */
public class AltCostPaymentDialog {

    private final JFrame         owner;
    private final CardData       card;
    private final int            handIdx;
    private final int            altCp;
    private final long           genericNeeded;
    private final String[]       elems;
    private final LinkedHashMap<String, Integer> costByElem;
    private final boolean        backupOnly;
    private final List<CardData> hand;
    private final CardData[]     backupCards;
    private final CardState[]    backupStates;
    private final String[]       backupUrls;
    private final Consumer<String> onZoom;
    private final Runnable         onZoomHide;
    /** Called on Confirm with (discardIndices, backupSlots). */
    /**
     * Called on Confirm with the discards, the Backups to dull, and the Backups to put into the
     * Break Zone for CP mapped to the Element each produces (Sherlotta 8-053H).
     */
    @FunctionalInterface
    public interface ConfirmCallback {
        void accept(List<Integer> discards, List<Integer> backups, Map<Integer, String> breakElements);
    }
    private final ConfirmCallback onConfirm;
    /**
     * Backup slots that may be put into the Break Zone for CP — Sherlotta 8-053H. Each appears in
     * the Backups row a second time, captioned "Break".
     */
    private final Map<Integer, Integer> breakForCpSlots;
    private final java.util.Set<String> ldDiscardGrants;

    /**
     * @param ldDiscardGrants Light/Dark elements the player may discard from hand for CP via a
     *     field grant (see {@code MainWindow.lightDarkDiscardGrants}); empty when none apply.
     */
    public AltCostPaymentDialog(JFrame owner, CardData card, int handIdx,
            int altCp, long genericNeeded, String[] elems, LinkedHashMap<String, Integer> costByElem,
            boolean backupOnly, List<CardData> hand, CardData[] backupCards, CardState[] backupStates,
            String[] backupUrls, Consumer<String> onZoom, Runnable onZoomHide,
            java.util.Set<String> ldDiscardGrants,
            ConfirmCallback onConfirm) {
        this(owner, card, handIdx, altCp, genericNeeded, elems, costByElem, backupOnly, hand,
                backupCards, backupStates, backupUrls, onZoom, onZoomHide, ldDiscardGrants,
                onConfirm, Map.of());
    }

    /**
     * @param breakForCpSlots backup slots that may be broken for CP, mapped to how much each
     *     produces (Sherlotta 8-053H); empty when none apply
     */
    public AltCostPaymentDialog(JFrame owner, CardData card, int handIdx,
            int altCp, long genericNeeded, String[] elems, LinkedHashMap<String, Integer> costByElem,
            boolean backupOnly, List<CardData> hand, CardData[] backupCards, CardState[] backupStates,
            String[] backupUrls, Consumer<String> onZoom, Runnable onZoomHide,
            java.util.Set<String> ldDiscardGrants,
            ConfirmCallback onConfirm, Map<Integer, Integer> breakForCpSlots) {
        this.breakForCpSlots = breakForCpSlots == null ? Map.of() : breakForCpSlots;
        this.owner         = owner;
        this.card          = card;
        this.handIdx       = handIdx;
        this.altCp         = altCp;
        this.genericNeeded = genericNeeded;
        this.elems         = elems;
        this.costByElem    = costByElem;
        this.backupOnly    = backupOnly;
        this.hand          = hand;
        this.backupCards   = backupCards;
        this.backupStates  = backupStates;
        this.backupUrls    = backupUrls;
        this.onZoom        = onZoom;
        this.onZoomHide    = onZoomHide;
        this.ldDiscardGrants = ldDiscardGrants;
        this.onConfirm     = onConfirm;
    }

    public void show() {
        String crystalStr = "《C》".repeat(card.altCrystalCost());
        String costDesc   = crystalStr + (altCp > 0 ? " + " + altCp + " CP" : "");

        JDialog dlg = new JDialog(owner, "Alt Cost: " + card.name() + "  (" + costDesc + ")", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<Integer> selectedBackups  = new ArrayList<>();
        List<Integer> selectedDiscards = new ArrayList<>();
        Map<String, Integer> bankCp    = new LinkedHashMap<>(costByElem);
        bankCp.replaceAll((k, v) -> 0);

        JLabel  cpLabel    = new JLabel();
        cpLabel.setFont(FontLoader.loadPixelFont(11));
        cpLabel.setHorizontalAlignment(SwingConstants.CENTER);
        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelFont(11));
        confirmBtn.setEnabled(false);

        List<JLabel>  backupLbls  = new ArrayList<>();
        List<Integer> backupSlots = new ArrayList<>();
        List<JLabel>  discardLbls = new ArrayList<>();
        List<Integer> discardIdxs = new ArrayList<>();
        boolean[] canAddBackup  = {true};
        boolean[] canAddDiscard = {true};

        BreakForCpEntries breaks = new BreakForCpEntries(breakForCpSlots, backupCards, backupUrls,
                onZoom, onZoomHide);

        Runnable updateAll = () -> {
            Map<String, Integer> cp = new LinkedHashMap<>(bankCp);
            int extra = 0;
            for (int s : selectedBackups) {
                if (matchesAnyElement(backupCards[s], elems))
                    cp.merge(contributingElement(backupCards[s], elems, cp, costByElem), 1, Integer::sum);
                else extra++;
            }
            for (int idx : selectedDiscards) {
                if (matchesAnyElement(hand.get(idx), elems))
                    cp.merge(contributingElement(hand.get(idx), elems, cp, costByElem), 2, Integer::sum);
                else extra += 2;
            }
            extra += breaks.contribute(cp, null);
            breaks.refresh();
            int total      = cp.values().stream().mapToInt(Integer::intValue).sum() + extra;
            // Any amount of CP may be produced when paying a cost; excess beyond the cost is wasted.
            canAddBackup[0]  = true;
            canAddDiscard[0] = !backupOnly;
            boolean satisfied = cp.entrySet().stream()
                    .allMatch(en -> en.getValue() >= costByElem.getOrDefault(en.getKey(), 0));
            confirmBtn.setEnabled(total >= altCp && satisfied);

            StringBuilder sb = new StringBuilder("CP: " + total + " / " + altCp + "  (");
            boolean first = true;
            for (String en : elems) {
                if (!first) sb.append(", ");
                sb.append(en).append(": ").append(cp.getOrDefault(en, 0)).append("/").append(costByElem.get(en));
                first = false;
            }
            if (genericNeeded > 0) { if (!first) sb.append(", "); sb.append("any: ").append(Math.min(extra, (int) genericNeeded)).append("/").append((int) genericNeeded); }
            if (first) sb.append("free");
            cpLabel.setText(sb.append(")").toString());

            for (int i = 0; i < backupLbls.size(); i++) {
                JLabel lbl = backupLbls.get(i); boolean sel = selectedBackups.contains(backupSlots.get(i));
                lbl.setBorder(sel ? CardAnimation.createCardGlowBorder(Color.YELLOW)
                        : BorderFactory.createLineBorder(canAddBackup[0] ? Color.GRAY : new Color(80, 80, 80), 1));
                lbl.setBackground(sel || canAddBackup[0] ? Color.DARK_GRAY : new Color(50, 50, 50));
                lbl.setCursor(sel || canAddBackup[0] ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
            for (int i = 0; i < discardLbls.size(); i++) {
                JLabel lbl = discardLbls.get(i); boolean sel = selectedDiscards.contains(discardIdxs.get(i));
                lbl.setBorder(sel ? CardAnimation.createCardGlowBorder(Color.YELLOW)
                        : BorderFactory.createLineBorder(canAddDiscard[0] ? Color.GRAY : new Color(80, 80, 80), 1));
                lbl.setBackground(sel || canAddDiscard[0] ? Color.DARK_GRAY : new Color(50, 50, 50));
                lbl.setCursor(sel || canAddDiscard[0] ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            }
        };

        JPanel center = new JPanel(); center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        List<Integer> eligibleSlots = new ArrayList<>();
        for (int i = 0; i < backupCards.length; i++)
            if (backupCards[i] != null && backupCards[i] != card && backupStates[i] == CardState.ACTIVE)
                eligibleSlots.add(i);

        if (!eligibleSlots.isEmpty() || !breaks.isEmpty()) {
            JLabel hdr = new JLabel("Backups — dull for 1 CP each"
                    + (breaks.isEmpty() ? "" : BreakForCpEntries.headerSuffix()) + ":");
            hdr.setFont(FontLoader.loadPixelFont(9)); hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
            JPanel bp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); bp.setAlignmentX(Component.LEFT_ALIGNMENT);
            for (int slot : eligibleSlots) {
                JLabel lbl = makeCardLabel();
                final String url = backupUrls[slot];
                lbl.addMouseListener(new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent ev) {
                        if (!selectedBackups.remove(Integer.valueOf(slot)) && canAddBackup[0]) selectedBackups.add(slot);
                        updateAll.run();
                    }
                    @Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) onZoom.accept(url); }
                    @Override public void mouseExited(MouseEvent ev)  { onZoomHide.run(); }
                });
                loadCardImage(lbl, url);
                backupLbls.add(lbl); backupSlots.add(slot); bp.add(lbl);
            }
            breaks.addTo(bp, null, updateAll);
            center.add(hdr); center.add(bp);
        }

        if (!backupOnly) {
            JLabel discHdr = new JLabel("Hand — discard for 2 CP each:");
            discHdr.setFont(FontLoader.loadPixelFont(9)); discHdr.setAlignmentX(Component.LEFT_ALIGNMENT);
            JPanel dp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); dp.setAlignmentX(Component.LEFT_ALIGNMENT);
            for (int i = 0; i < hand.size(); i++) {
                final int hi = i; CardData hc = hand.get(i);
                boolean payable = (i != handIdx) && CpPaymentUtils.canDiscardForCp(hc, ldDiscardGrants);
                JLabel lbl = makeCardLabel();
                lbl.setBackground(payable ? Color.DARK_GRAY : new Color(50, 50, 50));
                lbl.setBorder(BorderFactory.createLineBorder(payable ? Color.GRAY : new Color(80, 80, 80), 1));
                lbl.setCursor(payable ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                final String imgUrl = hc.imageUrl();
                if (payable) {
                    lbl.addMouseListener(new MouseAdapter() {
                        @Override public void mousePressed(MouseEvent ev) {
                            if (!selectedDiscards.remove(Integer.valueOf(hi)) && canAddDiscard[0]) selectedDiscards.add(hi);
                            updateAll.run();
                        }
                        @Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) onZoom.accept(imgUrl); }
                        @Override public void mouseExited(MouseEvent ev)  { onZoomHide.run(); }
                    });
                    discardLbls.add(lbl); discardIdxs.add(hi);
                } else {
                    lbl.addMouseListener(new MouseAdapter() {
                        @Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) onZoom.accept(imgUrl); }
                        @Override public void mouseExited(MouseEvent ev)  { onZoomHide.run(); }
                    });
                }
                loadCardImage(lbl, imgUrl);
                dp.add(lbl);
            }
            center.add(discHdr); center.add(dp);
        }

        updateAll.run();

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.setFont(FontLoader.loadPixelFont(11));
        cancelBtn.addActionListener(ev -> dlg.dispose());
        confirmBtn.addActionListener(ev -> {
            dlg.dispose();
            onConfirm.accept(new ArrayList<>(selectedDiscards), new ArrayList<>(selectedBackups),
                    breaks.selection());
        });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        south.add(confirmBtn); south.add(cancelBtn);

        JLabel titleLbl = new JLabel(
                "<html><center>" + card.name() + " — Alt Cost: " + costDesc + "</center></html>",
                SwingConstants.CENTER);
        titleLbl.setFont(FontLoader.loadPixelFont(11));
        JPanel topPanel = new JPanel(new java.awt.BorderLayout(0, 4));
        topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        topPanel.add(titleLbl, java.awt.BorderLayout.NORTH);
        topPanel.add(cpLabel,  java.awt.BorderLayout.CENTER);
        JPanel mainPanel = new JPanel(new java.awt.BorderLayout(0, 4));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        mainPanel.add(new JScrollPane(center), java.awt.BorderLayout.CENTER);
        mainPanel.add(south,                   java.awt.BorderLayout.SOUTH);
        dlg.getContentPane().setLayout(new java.awt.BorderLayout());
        dlg.getContentPane().add(topPanel,  java.awt.BorderLayout.NORTH);
        dlg.getContentPane().add(mainPanel, java.awt.BorderLayout.CENTER);
        dlg.pack(); dlg.setLocationRelativeTo(owner); dlg.setVisible(true);
    }

    private static JLabel makeCardLabel() {
        JLabel lbl = new JLabel("...", SwingConstants.CENTER);
        lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
        lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
        lbl.setOpaque(true);
        lbl.setBackground(Color.DARK_GRAY);
        lbl.setForeground(Color.WHITE);
        lbl.setFont(FontLoader.loadPixelFont(10));
        lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
        lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return lbl;
    }

    private static void loadCardImage(JLabel lbl, String url) {
        new SwingWorker<ImageIcon, Void>() {
            @Override protected ImageIcon doInBackground() throws Exception {
                Image img = ImageCache.load(url);
                return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
            }
            @Override protected void done() {
                try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                catch (InterruptedException | ExecutionException ignored) {}
            }
        }.execute();
    }
}
