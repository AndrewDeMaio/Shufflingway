package shufflingway;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;

import shufflingway.net.ChoiceKind;

import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;

/**
 * Modal dialogs that implement the "Look at the top N cards of your deck" family of effects.
 *
 * <p>Constructed by {@code MainWindow} and invoked through the
 * {@link GameContext#lookAtTopDeck(LookConfig)} bridge.  All callbacks into the
 * main window (logging, zoom, UI refreshes) are supplied via the {@link Callbacks} record.
 */
class LookAtDeckDialogs {

    /**
     * Callbacks into MainWindow for UI side-effects.
     *
     * @param log           append a line to the game log
     * @param showZoom      show the card-zoom overlay for the given image URL
     * @param hideZoom      dismiss the card-zoom overlay
     * @param refreshP1Deck refresh P1's deck-count label
     * @param refreshP2Deck refresh P2's deck-count label
     * @param refreshP1Hand refresh P1's hand label / count
     * @param refreshP2Hand refresh P2's hand count label
     * @param refreshP1Break refresh P1's break-zone label
     * @param refreshP2Break refresh P2's break-zone label
     * @param cardbackImage supplier for the current cardback image
     * @param animateDraw   triggers a deck→hand slide animation for the given player (isP1)
     * @param animateMill   triggers a deck→break-zone slide animation for the given player (isP1)
     * @param decide        puts a question to a seat and returns the answer — {@code MainWindow.decide}.
     *                      These dialogs ask the local player; who else might be sitting in the
     *                      seat, and how their answer gets here, is not their business
     */
    record Callbacks(
            Consumer<String>  log,
            Consumer<String>  showZoom,
            Runnable          hideZoom,
            Runnable          refreshP1Deck,
            Runnable          refreshP2Deck,
            Runnable          refreshP1Hand,
            Runnable          refreshP2Hand,
            Runnable          refreshP1Break,
            Runnable          refreshP2Break,
            Supplier<Image>   cardbackImage,
            Consumer<Boolean> animateDraw,
            Consumer<Boolean> animateMill,
            Function<PlayerChoice, List<Integer>> decide
    ) {}

    private final JFrame     frame;
    private final GameState  gameState;
    private final Callbacks  cb;

    LookAtDeckDialogs(JFrame frame, GameState gameState, Callbacks cb) {
        this.frame     = frame;
        this.gameState = gameState;
        this.cb        = cb;
    }

    // ── Convenience forwarders ──────────────────────────────────────────────────

    private void log(String msg)      { cb.log().accept(msg); }

    /**
     * Component text with any glyph the pixel font lacks — the "→" on the destination toggles —
     * drawn from a fallback font. Plain text is returned untouched, so only the arrow-bearing
     * labels pay for Swing's HTML rendering.
     */
    private static String txt(String s) { return FontLoader.htmlWithFallback(s); }

    /**
     * A centred label that draws its text over a 1px black shadow, so light colours stay legible
     * against the dialog's default background.
     */
    private static JLabel shadowedLabel(String text, float fontSize, Color fg) {
        JLabel label = new JLabel(text, SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                String s = getText();
                int x = (getWidth() - fm.stringWidth(s)) / 2;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(new Color(0, 0, 0, 190));
                g2.drawString(s, x + 1, y + 1);
                g2.setColor(getForeground());
                g2.drawString(s, x, y);
                g2.dispose();
            }
        };
        label.setFont(FontLoader.loadPixelFont(fontSize));
        label.setForeground(fg);
        return label;
    }
    private void showZoom(String url) { cb.showZoom().accept(url); }
    private void hideZoom()           { cb.hideZoom().run(); }

    // ── Public entry point ──────────────────────────────────────────────────────

    /**
     * Resolves the effect described by {@code config} for the player at seat {@code isP1}, and
     * returns the card it put into their hand — {@code null} when it adds none, and when the deck
     * was empty. Riders that act on what was taken — Lunafreya 23-129H's "if the card added to
     * your hand has an EX Burst" — need to know which card that was.
     *
     * @param p2IsCpu whether the opposing seat is the built-in AI. Only the top/bottom ordering
     *                dialog cares: it runs a countdown so a human opponent is not left waiting,
     *                and the AI is not waiting for anything
     */
    CardData show(LookConfig config, boolean isP1, boolean p2IsCpu) {
        Deque<CardData> deck = isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
        int n = Math.min(config.count(), deck.size());
        if (n == 0) { log("Look at top: deck is empty."); return null; }

        List<CardData> peeked = new ArrayList<>();
        for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }

        // "Reveal" is public — both players are entitled to see the cards, so they go to the
        // shared log whoever is looking. "Look at" is private to the controller, so P2's cards
        // never reach the log the human at P1's seat reads. The same distinction decides whether
        // the moves themselves are logged card by card or only counted.
        boolean namesArePublic = isP1 || config.reveal();
        if (namesArePublic)
            log((config.reveal() ? "Reveal" : "Look at") + " top " + n + " card(s): " +
                    peeked.stream().map(CardData::name)
                          .collect(java.util.stream.Collectors.joining(", ")));

        // The choice belongs to whoever controls the effect, and all three kinds of player answer
        // it the same way from here: as a DeckLookDecision over the peeked cards. Which is why the
        // dialogs below only build one and never move a card themselves — a decision made at this
        // seat has to be transmittable, and one made elsewhere has to be applicable.
        List<Integer> answer = cb.decide().apply(
                PlayerChoice.by(isP1, ChoiceKind.DECK_LOOK)
                        .prompting("Waiting for your opponent to look at the top " + n
                                + " card(s) of their deck...")
                        .locally(() -> askDeckLook(config, peeked, p2IsCpu).toAnswer())
                        .byCpu(()   -> cpuDeckLook(config, peeked).toAnswer())
                        .legalWhen(a -> DeckLookDecision.fromAnswer(a, n) != null,
                                "that is not an arrangement of the " + n
                                + " card(s) on top of their deck here"));

        DeckLookDecision decision = DeckLookDecision.fromAnswer(answer, n);
        // Nothing usable came back — a remote answer already reported as a desync, or a seat that
        // declined. Leaving the cards on top is the one outcome that changes nothing.
        if (decision == null) decision = DeckLookDecision.keepOnTop(n);
        return applyDeckLook(decision, peeked, deck, isP1, namesArePublic, config.action());
    }

    /**
     * Asks the local player what to do with the cards they just looked at, <em>without moving any
     * of them</em>. Every branch returns an arrangement for {@link #applyDeckLook} to carry out,
     * because the same answer may have to be sent to the other client and applied there too.
     */
    private DeckLookDecision askDeckLook(LookConfig config, List<CardData> peeked, boolean p2IsCpu) {
        return switch (config.action()) {
            case PEEK               -> showPeek(peeked);
            case BREAK_OR_KEEP      -> showBreakOrKeep(peeked.get(0));
            case BOTTOM_OR_KEEP     -> showBottomOrKeep(peeked.get(0));
            case RETURN_TOP_ORDERED -> showReturnTopOrdered(peeked);
            case ADD_TO_HAND_REST_BOTTOM              -> showAddToHandRestBottom(peeked);
            case ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM -> showAddToHandOneToBreakRestBottom(peeked);
            case ADD_TO_HAND_REST_BREAK               -> showAddToHandRestBreak(peeked, config);
            case TOP_OR_BOTTOM_ORDERED                -> showTopOrBottom(peeked, !p2IsCpu);
            case PICK_ONE_TOP_REST_BOTTOM             -> showPickOneTopRestBottom(peeked);
        };
    }

    /**
     * The AI's answer: simple, safe defaults — keep private looks on top in the order they came,
     * and for effects that pull a card to hand, take the topmost one the ability allows.
     *
     * <p>Pure, and deliberately so. It used to be written as deck mutations inside a P2-only
     * branch, which is how the multiplayer case ended up with nothing to fall back to but a
     * placeholder that left the cards where they were.
     */
    private static DeckLookDecision cpuDeckLook(LookConfig config, List<CardData> peeked) {
        int n = peeked.size();
        return switch (config.action()) {
            // Keep them on top, in the order they were peeked — no deck change at all.
            case PEEK, RETURN_TOP_ORDERED, TOP_OR_BOTTOM_ORDERED, BREAK_OR_KEEP, BOTTOM_OR_KEEP ->
                    DeckLookDecision.keepOnTop(n);

            case PICK_ONE_TOP_REST_BOTTOM ->
                    new DeckLookDecision(List.of(), List.of(), List.of(0), range(1, n));

            case ADD_TO_HAND_REST_BOTTOM ->
                    new DeckLookDecision(List.of(0), List.of(), List.of(), range(1, n));

            case ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM ->
                    new DeckLookDecision(List.of(0), n > 1 ? List.of(1) : List.of(),
                            List.of(), range(2, n));

            case ADD_TO_HAND_REST_BREAK -> {
                // The topmost card the ability qualifies, not simply the topmost — a filtered
                // effect ("Add 1 Category VII card among them") may not reach the first one, and
                // may not reach any of them.
                int keep = -1;
                for (int i = 0; i < n && keep < 0; i++)
                    if (config.eligibleForHand(peeked.get(i))) keep = i;
                List<Integer> broken = new ArrayList<>();
                for (int i = 0; i < n; i++) if (i != keep) broken.add(i);
                yield new DeckLookDecision(keep < 0 ? List.of() : List.of(keep), broken,
                        List.of(), List.of());
            }
        };
    }

    /** {@code [from, to)} as a list, for the fixed AI arrangements above. */
    private static List<Integer> range(int from, int to) {
        List<Integer> out = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) out.add(i);
        return out;
    }

    /**
     * Moves the peeked cards where {@code decision} says, and returns the one that reached hand.
     *
     * <p>The single place a look-at-deck effect touches the game state, whoever decided it. The
     * cards come off the top first and go back in destination order, so an arrangement that
     * returns them all to the top in their original order is genuinely a no-op.
     *
     * @param namesArePublic false for a private look the opponent controls — the moves are then
     *                       logged by count, because naming the cards would show the human at this
     *                       seat cards they are not entitled to see
     */
    private CardData applyDeckLook(DeckLookDecision decision, List<CardData> peeked,
            Deque<CardData> deck, boolean isP1, boolean namesArePublic,
            LookConfig.LookAction action) {
        int n = peeked.size();
        // An arrangement that puts every card back on top in the order it was found moved nothing,
        // and saying so would only repeat the "Look at top N" line already in the log.
        boolean moved = !decision.equals(DeckLookDecision.keepOnTop(n));
        boolean name  = namesArePublic && moved;

        for (int i = 0; i < n; i++) deck.pollFirst();

        CardData handCard = null;
        for (int i : decision.toHand()) {
            CardData c = peeked.get(i);
            if (handCard == null) handCard = c;
            if (isP1) gameState.getP1Hand().add(c); else gameState.getP2Hand().add(c);
            if (name) log(c.name() + " → hand");
        }
        for (int i : decision.toBreak()) {
            CardData c = peeked.get(i);
            if (isP1) gameState.getP1BreakZone().add(c); else gameState.getP2BreakZone().add(c);
            if (name) log(c.name() + " → Break Zone");
        }
        // toTop is topmost first, so it is pushed back in reverse.
        List<Integer> top = decision.toTop();
        for (int k = top.size() - 1; k >= 0; k--) deck.addFirst(peeked.get(top.get(k)));
        if (name && !top.isEmpty()) log("→ Top of deck (topmost first): " + names(peeked, top));
        for (int i : decision.toBottom()) {
            deck.addLast(peeked.get(i));
            if (name) log(peeked.get(i).name() + " → bottom of deck");
        }

        if (!namesArePublic) log(privateSummary(decision, n));

        if (!decision.toHand().isEmpty()) {
            if (isP1) cb.refreshP1Hand().run(); else cb.refreshP2Hand().run();
        }
        if (!decision.toBreak().isEmpty()) {
            if (isP1) cb.refreshP1Break().run(); else cb.refreshP2Break().run();
        }
        // Only this one action has ever animated the cards it moves. Left as it was rather than
        // made uniform: an animation is not free here — it runs against the turn-flow gate — and
        // spreading it to eight more effects is a change to make deliberately, not in passing.
        if (action == LookConfig.LookAction.ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM) {
            if (!decision.toHand().isEmpty())  cb.animateDraw().accept(isP1);
            if (!decision.toBreak().isEmpty()) cb.animateMill().accept(isP1);
        }
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();
        return handCard;
    }

    /**
     * What the opponent did with a private look, said without naming any of the cards — the human
     * at this seat is not entitled to see them, and the log is shared.
     */
    private static String privateSummary(DeckLookDecision decision, int n) {
        List<String> parts = new ArrayList<>();
        if (!decision.toHand().isEmpty())
            parts.add("takes " + decision.toHand().size() + " to hand");
        if (!decision.toBreak().isEmpty())
            parts.add("sends " + decision.toBreak().size() + " to the Break Zone");
        if (!decision.toBottom().isEmpty())
            parts.add("returns " + decision.toBottom().size() + " to the bottom");
        String looks = "[P2] looks at the top " + n + " card(s) of their deck";
        return parts.isEmpty() ? looks + "." : looks + " and " + String.join(", ", parts) + ".";
    }

    private static String names(List<CardData> peeked, List<Integer> indices) {
        return indices.stream().map(i -> peeked.get(i).name())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** Position of {@code card} in {@code peeked} by identity — two copies are distinct here. */
    private static int peekIndexOf(List<CardData> peeked, CardData card) {
        for (int i = 0; i < peeked.size(); i++) if (peeked.get(i) == card) return i;
        return -1;
    }

    /** The peek indices of {@code cards}, in the order given, skipping anything not peeked. */
    private static List<Integer> peekIndices(List<CardData> peeked, List<CardData> cards) {
        List<Integer> out = new ArrayList<>(cards.size());
        for (CardData c : cards) {
            int i = peekIndexOf(peeked, c);
            if (i >= 0) out.add(i);
        }
        return out;
    }

    // ── Dialog implementations ──────────────────────────────────────────────────

    private DeckLookDecision showBreakOrKeep(CardData top) {
        JDialog dlg = new JDialog(frame, "Top of Deck — " + top.name(), true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel cardLbl = makeCardLabel(top.imageUrl());
        cardLbl.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { showZoom(top.imageUrl()); }
            @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
        });

        boolean[] sendToBreak = { false };
        JButton breakBtn = new JButton("Break Zone");
        breakBtn.setFont(FontLoader.loadPixelFont(11));
        breakBtn.addActionListener(ae -> { sendToBreak[0] = true; hideZoom(); dlg.dispose(); });
        JButton keepBtn = new JButton("Keep on Top");
        keepBtn.setFont(FontLoader.loadPixelFont(11));
        keepBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        south.add(breakBtn); south.add(keepBtn);
        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardLbl, BorderLayout.CENTER);
        dlg.getContentPane().add(south,   BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        return sendToBreak[0]
                ? new DeckLookDecision(List.of(), List.of(0), List.of(), List.of())
                : DeckLookDecision.keepOnTop(1);
    }

    private DeckLookDecision showBottomOrKeep(CardData top) {
        JDialog dlg = new JDialog(frame, "Top of Deck — " + top.name(), true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel cardLbl = makeCardLabel(top.imageUrl());
        cardLbl.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { showZoom(top.imageUrl()); }
            @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
        });

        boolean[] moveToBottom = { false };
        JButton bottomBtn = new JButton("Move to Bottom");
        bottomBtn.setFont(FontLoader.loadPixelFont(11));
        bottomBtn.addActionListener(ae -> { moveToBottom[0] = true; hideZoom(); dlg.dispose(); });
        JButton keepBtn = new JButton("Keep on Top");
        keepBtn.setFont(FontLoader.loadPixelFont(11));
        keepBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        south.add(bottomBtn); south.add(keepBtn);
        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardLbl, BorderLayout.CENTER);
        dlg.getContentPane().add(south,   BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        return moveToBottom[0]
                ? new DeckLookDecision(List.of(), List.of(), List.of(), List.of(0))
                : DeckLookDecision.keepOnTop(1);
    }

    private DeckLookDecision showPeek(List<CardData> cards) {
        String title = cards.size() == 1
                ? "Top of Deck — " + cards.get(0).name()
                : "Top " + cards.size() + " Cards of Deck";
        JDialog dlg = new JDialog(frame, title, true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (CardData c : cards) {
            JLabel lbl = makeCardLabel(c.imageUrl());
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(c.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
            });
            cardsPanel.add(lbl);
        }

        JButton okBtn = new JButton("OK");
        okBtn.setFont(FontLoader.loadPixelFont(11));
        okBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        south.add(okBtn);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);
        // A peek decides nothing — the cards go back exactly as they were found.
        return DeckLookDecision.keepOnTop(cards.size());
    }

    private DeckLookDecision showReturnTopOrdered(List<CardData> cards) {
        int n = cards.size();
        JDialog dlg = new JDialog(frame, "Order Cards — Return to Top of Deck", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<CardData> order = new ArrayList<>(cards);
        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[] cardLabels = new JLabel[n];
        int[] selectedIdx = { -1 };

        Runnable updateLabels = () -> {
            for (int j = 0; j < n; j++) {
                ImageIcon ic = imgCache.get(order.get(j));
                if (ic != null) { cardLabels[j].setIcon(ic); cardLabels[j].setText(null); }
            }
        };

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(order.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (selectedIdx[0] == -1) {
                        selectedIdx[0] = idx;
                        cardLabels[idx].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
                    } else if (selectedIdx[0] == idx) {
                        selectedIdx[0] = -1;
                        cardLabels[idx].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
                    } else {
                        int other = selectedIdx[0];
                        CardData tmp = order.get(idx); order.set(idx, order.get(other)); order.set(other, tmp);
                        updateLabels.run();
                        cardLabels[idx].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
                        cardLabels[other].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
                        selectedIdx[0] = -1;
                    }
                }
            });
            cardLabels[i] = lbl;
            cardsPanel.add(lbl);
        }

        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { imgCache.put(c, ic); updateLabels.run(); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        JLabel instructions = new JLabel(
                "Click to select, click another to swap. Left = top of deck.", SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelFont(9));
        JButton confirmBtn = new JButton("Confirm Order");
        confirmBtn.setFont(FontLoader.loadPixelFont(11));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        return new DeckLookDecision(List.of(), List.of(), peekIndices(cards, order), List.of());
    }

    /** @return the arrangement chosen; its {@code toHand} is the card the player took */
    private DeckLookDecision showAddToHandRestBottom(List<CardData> cards) {
        int n = cards.size();
        JDialog dlg = new JDialog(frame, "Look — Add to Hand, Return Rest to Bottom", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<CardData> order = new ArrayList<>(cards);
        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[] cardLabels = new JLabel[n];
        int[] handLblIdx    = { -1 };
        int[] selectedForSwap = { -1 };

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelFont(11));
        confirmBtn.setEnabled(false);

        Runnable updateLabels = () -> {
            for (int j = 0; j < n; j++) {
                ImageIcon ic = imgCache.get(order.get(j));
                if (ic != null) { cardLabels[j].setIcon(ic); cardLabels[j].setText(null); }
            }
        };
        Runnable refreshBorders = () -> {
            for (int j = 0; j < n; j++) {
                if (j == handLblIdx[0])
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(0, 200, 80), 3));
                else if (j == selectedForSwap[0])
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
                else
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
            }
        };

        JToggleButton[] handBtns = new JToggleButton[n];
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(order.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (idx == handLblIdx[0]) return;
                    if (selectedForSwap[0] == -1) {
                        selectedForSwap[0] = idx;
                    } else if (selectedForSwap[0] == idx) {
                        selectedForSwap[0] = -1;
                    } else {
                        int other = selectedForSwap[0];
                        if (other == handLblIdx[0]) { selectedForSwap[0] = idx; refreshBorders.run(); return; }
                        CardData tmp = order.get(idx); order.set(idx, order.get(other)); order.set(other, tmp);
                        updateLabels.run();
                        selectedForSwap[0] = -1;
                    }
                    refreshBorders.run();
                }
            });
            cardLabels[i] = lbl;

            JToggleButton handBtn = new JToggleButton(txt("→ Hand"));
            handBtn.setFont(FontLoader.loadPixelFont(9));
            handBtns[i] = handBtn;
            handBtn.addItemListener(ie -> {
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    for (int j = 0; j < n; j++) if (j != idx && handBtns[j].isSelected()) handBtns[j].setSelected(false);
                    handLblIdx[0] = idx;
                    selectedForSwap[0] = -1;
                    confirmBtn.setEnabled(true);
                } else {
                    handLblIdx[0] = -1;
                    confirmBtn.setEnabled(false);
                }
                refreshBorders.run();
            });

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl,     BorderLayout.CENTER);
            wrapper.add(handBtn, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { imgCache.put(c, ic); updateLabels.run(); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        JLabel instructions = new JLabel(
                txt("Click '→ Hand' to pick the card for your hand. Swap the rest to order them (left = first at bottom)."),
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelFont(9));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        // Closing the dialog without choosing still takes a card: the first in the chosen order.
        CardData handCard = handLblIdx[0] >= 0 ? order.get(handLblIdx[0]) : order.get(0);
        List<CardData> bottom = new ArrayList<>();
        for (CardData c : order) if (c != handCard) bottom.add(c);
        return new DeckLookDecision(List.of(peekIndexOf(cards, handCard)), List.of(),
                List.of(), peekIndices(cards, bottom));
    }

    /** @return the arrangement chosen; {@code toHand} may be empty if the player placed none there */
    private DeckLookDecision showAddToHandOneToBreakRestBottom(List<CardData> cards) {
        int n = cards.size();
        // dest slot 0 = Hand, slot 1 = Break Zone, slots 2..n-1 = Deck Bottom (left = placed first = deeper)
        String[] destLabels = new String[n];
        destLabels[0] = "Hand";
        if (n > 1) destLabels[1] = "Break Zone";
        for (int i = 2; i < n; i++) destLabels[i] = "Deck Bottom";

        CardData[] destCards = new CardData[n];
        boolean[]  placed    = new boolean[n];
        int[]      selTop    = { -1 };

        JButton okBtn = new JButton("OK");
        okBtn.setFont(FontLoader.loadPixelFont(11));
        okBtn.setEnabled(false);

        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[] topLabels = new JLabel[n];
        JLabel[] botLabels = new JLabel[n];

        Runnable checkOk = () -> {
            for (CardData d : destCards) if (d == null) { okBtn.setEnabled(false); return; }
            okBtn.setEnabled(true);
        };

        Runnable refreshTopBorders = () -> {
            for (int j = 0; j < n; j++) {
                if (placed[j])
                    topLabels[j].setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
                else if (j == selTop[0])
                    topLabels[j].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
                else
                    topLabels[j].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
        }};

        // build top row (source cards)
        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { if (!placed[idx]) showZoom(cards.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (placed[idx]) return;
                    selTop[0] = (selTop[0] == idx) ? -1 : idx;
                    refreshTopBorders.run();
                }
            });
            topLabels[i] = lbl;
            topRow.add(lbl);
        }

        // build bottom row (destination slots)
        JPanel botRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int slotIdx = i;
            Color labelColor = slotIdx == 0 ? new Color(0, 200, 80)
                             : slotIdx == 1 ? new Color(220, 80, 80)
                             : new Color(100, 180, 255);

            JLabel slotCard = makeCardLabel(null);
            slotCard.setText("?");
            slotCard.setBackground(new Color(40, 40, 40));
            slotCard.setBorder(BorderFactory.createLineBorder(labelColor.darker(), 1));
            slotCard.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { if (destCards[slotIdx] != null) showZoom(destCards[slotIdx].imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (selTop[0] != -1) {
                        // place selected card into this slot
                        if (destCards[slotIdx] != null) {
                            // return the evicted card to top row
                            int evictIdx = cards.indexOf(destCards[slotIdx]);
                            if (evictIdx >= 0) placed[evictIdx] = false;
                        }
                        int topIdx = selTop[0];
                        destCards[slotIdx] = cards.get(topIdx);
                        placed[topIdx] = true;
                        selTop[0] = -1;
                        ImageIcon ic = imgCache.get(cards.get(topIdx));
                        slotCard.setIcon(ic);
                        slotCard.setText(ic != null ? null : cards.get(topIdx).name());
                        slotCard.setBorder(BorderFactory.createLineBorder(labelColor, 3));
                        refreshTopBorders.run();
                        checkOk.run();
                    } else if (destCards[slotIdx] != null) {
                        // unassign: return card to top row
                        int evictIdx = cards.indexOf(destCards[slotIdx]);
                        if (evictIdx >= 0) placed[evictIdx] = false;
                        destCards[slotIdx] = null;
                        slotCard.setIcon(null);
                        slotCard.setText("?");
                        slotCard.setBorder(BorderFactory.createLineBorder(labelColor.darker(), 1));
                        refreshTopBorders.run();
                        checkOk.run();
                    }
                }
            });
            botLabels[i] = slotCard;

            JLabel destNameLbl = new JLabel(destLabels[i], SwingConstants.CENTER);
            destNameLbl.setFont(FontLoader.loadPixelFont(9));
            destNameLbl.setForeground(labelColor);

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(slotCard,    BorderLayout.CENTER);
            wrapper.add(destNameLbl, BorderLayout.SOUTH);
            botRow.add(wrapper);
        }

        // async image loading
        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try {
                        ImageIcon ic = get();
                        if (ic == null) return;
                        imgCache.put(c, ic);
                        int j = cards.indexOf(c);
                        topLabels[j].setIcon(ic);
                        topLabels[j].setText(null);
                        for (int s = 0; s < n; s++) {
                            if (destCards[s] == c) { botLabels[s].setIcon(ic); botLabels[s].setText(null); }
                        }
                    } catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        JDialog dlg = new JDialog(frame, "Look — Assign Cards to Destinations", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel instructions = new JLabel(
                "Select a card in the top row, then click a destination. Click it again to return it.",
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelFont(9));
        okBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        centerPanel.add(topRow, BorderLayout.NORTH);
        centerPanel.add(botRow, BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(okBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(centerPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,       BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        // apply results — fill any unassigned slots with remaining unplaced cards (safety fallback)
        List<CardData> unplaced = new ArrayList<>();
        for (int i = 0; i < n; i++) if (!placed[i]) unplaced.add(cards.get(i));
        int ui = 0;
        for (int s = 0; s < n; s++) if (destCards[s] == null && ui < unplaced.size()) destCards[s] = unplaced.get(ui++);

        CardData handCard  = destCards[0];
        CardData breakCard = n > 1 ? destCards[1] : null;
        List<CardData> bottom = new ArrayList<>();
        for (int i = 2; i < n; i++) if (destCards[i] != null) bottom.add(destCards[i]);
        return new DeckLookDecision(
                handCard  == null ? List.of() : List.of(peekIndexOf(cards, handCard)),
                breakCard == null ? List.of() : List.of(peekIndexOf(cards, breakCard)),
                List.of(), peekIndices(cards, bottom));
    }

    /**
     * Lets the player take 1 of the revealed cards into hand; everything else goes to the Break
     * Zone.  {@code config} may restrict which card qualifies ("Add 1 Category VII card among
     * them") — an ineligible card cannot be chosen, and when none of the revealed cards qualifies
     * there is nothing to decide, so no dialog is shown and all of them are broken.
     *
     * @return the arrangement chosen; {@code toHand} is empty when nothing qualified
     */
    private DeckLookDecision showAddToHandRestBreak(List<CardData> cards, LookConfig config) {
        int n = cards.size();
        String filterLabel = config.handFilterLabel();

        boolean[] eligible = new boolean[n];
        boolean   anyEligible = false;
        for (int i = 0; i < n; i++) {
            eligible[i] = config.eligibleForHand(cards.get(i));
            anyEligible |= eligible[i];
        }

        // Nothing qualifies, so there is nothing to decide and no dialog to show. Both clients
        // reach this from the same revealed cards, so they agree without being told.
        if (!anyEligible) {
            log("No " + filterLabel + " card among them — nothing added to hand.");
            return new DeckLookDecision(List.of(), range(0, n), List.of(), List.of());
        }

        JDialog dlg = new JDialog(frame, "Look — Add to Hand, Rest to Break Zone", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        int[] handLblIdx = { -1 };
        JLabel[] cardLabels = new JLabel[n];
        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelFont(11));
        confirmBtn.setEnabled(false);

        JToggleButton[] handBtns = new JToggleButton[n];
        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final CardData c = cards.get(i);
            JLabel lbl = makeCardLabel(c.imageUrl());
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(c.imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
            });
            cardLabels[i] = lbl;

            JToggleButton handBtn = new JToggleButton(txt("→ Hand"));
            handBtn.setFont(FontLoader.loadPixelFont(9));
            // A card the ability does not qualify cannot be the one taken, so its button never
            // arms — the restriction is enforced here rather than left to the player to honour.
            handBtn.setEnabled(eligible[i]);
            if (!eligible[i]) handBtn.setToolTipText("Not a " + filterLabel + " card");
            handBtns[i] = handBtn;
            handBtn.addItemListener(ie -> {
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    for (int j = 0; j < n; j++) if (j != idx && handBtns[j].isSelected()) handBtns[j].setSelected(false);
                    handLblIdx[0] = idx;
                    for (int j = 0; j < n; j++)
                        cardLabels[j].setBorder(BorderFactory.createLineBorder(
                                j == idx ? new Color(0, 200, 80) : new Color(160, 110, 220),
                                j == idx ? 3 : 1));
                    confirmBtn.setEnabled(true);
                } else {
                    handLblIdx[0] = -1;
                    for (JLabel l : cardLabels) l.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
                    confirmBtn.setEnabled(false);
                }
            });

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl,     BorderLayout.CENTER);
            wrapper.add(handBtn, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        JLabel instructions = new JLabel(
                txt(filterLabel == null
                        ? "Click '→ Hand' to choose a card. The rest go to the Break Zone."
                        : "Click '→ Hand' to choose a " + filterLabel
                          + " card. The rest go to the Break Zone."), SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelFont(9));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        // Closing the dialog without choosing still takes a card — but it has to be one the
        // ability allows, so the fallback is the first eligible card rather than the first card.
        int hi = handLblIdx[0];
        if (hi < 0) for (int i = 0; i < n && hi < 0; i++) if (eligible[i]) hi = i;
        List<Integer> broken = new ArrayList<>();
        for (int i = 0; i < n; i++) if (i != hi) broken.add(i);
        return new DeckLookDecision(List.of(hi), broken, List.of(), List.of());
    }

    /**
     * Top/bottom ordering dialog — custom drag-and-drop canvas.
     *
     * @param timed when true, a 20-second countdown auto-resolves any cards still unassigned to the
     *              top of the deck. It exists so a live opponent is not left waiting, so it is off
     *              against the CPU, which has nothing to wait for
     */
    private DeckLookDecision showTopOrBottom(List<CardData> cards, boolean timed) {
        final int TW = 80, TH = 117, GAP = 8, SEP_H = 30;
        final int n      = cards.size();
        final int panelW = Math.max(600, 2 * n * (TW + GAP) + TW + 4 * GAP);
        final int panelH = TH + GAP * 2 + SEP_H + TH + GAP;

        final List<CardData>              stagingList = new ArrayList<>(cards);
        final List<CardData>              topCards    = new ArrayList<>();
        final List<CardData>              bottomCards = new ArrayList<>();
        final Map<CardData, Rectangle>    cardBounds  = new LinkedHashMap<>();
        final Map<CardData, BufferedImage> thumbs     = new LinkedHashMap<>();
        final CardData[] dragging = { null };
        final int[] dragX = { 0 }, dragY = { 0 };
        final Image deckBack = cb.cardbackImage().get();

        final Runnable computeLayout = () -> {
            cardBounds.clear();
            int deckX = panelW / 2 - TW / 2;
            int zoneY = TH + GAP * 2 + SEP_H;

            int stagingVisible = 0;
            for (CardData c : stagingList) if (c != dragging[0]) stagingVisible++;
            int stagW = stagingVisible * TW + Math.max(0, stagingVisible - 1) * GAP;
            int sx0   = (panelW - stagW) / 2;
            int si    = 0;
            for (CardData c : stagingList) {
                if (c == dragging[0]) continue;
                cardBounds.put(c, new Rectangle(sx0 + si * (TW + GAP), GAP, TW, TH));
                si++;
            }

            int topVisible = 0;
            for (CardData c : topCards) if (c != dragging[0]) topVisible++;
            int ti = 0;
            for (CardData c : topCards) {
                if (c == dragging[0]) continue;
                cardBounds.put(c, new Rectangle(deckX - GAP - (topVisible - ti) * (TW + GAP), zoneY, TW, TH));
                ti++;
            }

            int bi = 0;
            for (CardData c : bottomCards) {
                if (c == dragging[0]) continue;
                cardBounds.put(c, new Rectangle(deckX + TW + GAP + bi * (TW + GAP), zoneY, TW, TH));
                bi++;
            }
        };

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelFont(11));
        confirmBtn.setEnabled(false);

        // Shadowed so the yellow stays readable against the dialog's default background.
        JLabel timerLabel = shadowedLabel("20s", 12, new Color(255, 220, 0));
        timerLabel.setVisible(timed);

        JDialog dlg = new JDialog(frame, "Order Cards — Top or Bottom of Deck", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel canvas = new JPanel(null) {
            @Override public Dimension getPreferredSize() { return new Dimension(panelW, panelH); }
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                computeLayout.run();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);

                int deckX = panelW / 2 - TW / 2;
                int zoneY = TH + GAP * 2 + SEP_H;

                g2.setColor(new Color(35, 35, 55));
                g2.fillRect(0, 0, panelW, TH + GAP * 2);
                g2.setColor(new Color(25, 45, 25));
                g2.fillRect(0, zoneY - 4, deckX - GAP / 2, TH + 8);
                g2.setColor(new Color(45, 25, 25));
                g2.fillRect(deckX + TW + GAP / 2, zoneY - 4, panelW - deckX - TW - GAP / 2, TH + 8);

                // The arrows below have no glyph in the pixel fonts, so these labels are drawn and
                // measured with a per-character fallback rather than through FontMetrics.
                Font lblFont  = FontLoader.loadPixelFont(9);
                Font lblAlt   = FontLoader.fallbackFont(9);
                g2.setFont(lblFont);

                g2.setColor(Color.LIGHT_GRAY);
                String stageLbl = stagingList.isEmpty() ? "All cards placed!" : "Drag cards to the Top or Bottom zone";
                float stageW = FontLoader.widthWithFallback(g2, stageLbl, lblFont, lblAlt);
                FontLoader.drawWithFallback(g2, stageLbl, (panelW - stageW) / 2,
                        TH + GAP * 2 + SEP_H / 2, lblFont, lblAlt);

                g2.setColor(new Color(140, 210, 140));
                String topLbl = "← Top of Deck (left = topmost)";
                float topW = FontLoader.widthWithFallback(g2, topLbl, lblFont, lblAlt);
                FontLoader.drawWithFallback(g2, topLbl, Math.max(4, (deckX - topW) / 2),
                        zoneY - 6, lblFont, lblAlt);

                g2.setColor(new Color(210, 140, 140));
                String botLbl = "Bottom of Deck (left = first below) →";
                int bZoneX = deckX + TW + GAP;
                float botW = FontLoader.widthWithFallback(g2, botLbl, lblFont, lblAlt);
                FontLoader.drawWithFallback(g2, botLbl, bZoneX + (panelW - bZoneX - botW) / 2,
                        zoneY - 6, lblFont, lblAlt);

                if (deckBack != null) {
                    g2.drawImage(deckBack, deckX, zoneY, TW, TH, null);
                    g2.setColor(new Color(0, 0, 0, 70));
                    g2.fillRect(deckX, zoneY, TW, TH);
                } else {
                    g2.setColor(Color.GRAY); g2.fillRect(deckX, zoneY, TW, TH);
                }
                g2.setColor(new Color(160, 110, 220));
                g2.drawRect(deckX, zoneY, TW - 1, TH - 1);

                for (Map.Entry<CardData, Rectangle> entry : cardBounds.entrySet()) {
                    Rectangle r = entry.getValue();
                    BufferedImage img = thumbs.get(entry.getKey());
                    if (img != null) g2.drawImage(img, r.x, r.y, r.width, r.height, null);
                    else { g2.setColor(Color.DARK_GRAY); g2.fillRect(r.x, r.y, r.width, r.height); }
                    g2.setColor(new Color(160, 110, 220));
                    g2.drawRect(r.x, r.y, r.width - 1, r.height - 1);
                }

                if (dragging[0] != null) {
                    int gx = dragX[0] - TW / 2, gy = dragY[0] - TH / 2;
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
                    BufferedImage img = thumbs.get(dragging[0]);
                    if (img != null) g2.drawImage(img, gx, gy, TW, TH, null);
                    else { g2.setColor(new Color(160, 110, 220, 150)); g2.fillRect(gx, gy, TW, TH); }
                    g2.setComposite(AlphaComposite.SrcOver);
                }
                g2.dispose();
            }
        };
        canvas.setBackground(new Color(30, 30, 30));

        for (CardData c : cards) {
            new SwingWorker<BufferedImage, Void>() {
                @Override protected BufferedImage doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    if (img == null) return null;
                    BufferedImage buf = new BufferedImage(TW, TH, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = buf.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(img, 0, 0, TW, TH, null);
                    g.dispose();
                    return buf;
                }
                @Override protected void done() {
                    try { BufferedImage bi = get(); if (bi != null) { thumbs.put(c, bi); canvas.repaint(); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        canvas.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                computeLayout.run();
                for (Map.Entry<CardData, Rectangle> entry : cardBounds.entrySet()) {
                    if (entry.getValue().contains(e.getX(), e.getY())) {
                        dragging[0] = entry.getKey();
                        dragX[0] = e.getX(); dragY[0] = e.getY();
                        canvas.repaint(); return;
                    }
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (dragging[0] == null) return;
                CardData card = dragging[0];
                dragging[0] = null;
                stagingList.remove(card); topCards.remove(card); bottomCards.remove(card);

                int deckX = panelW / 2 - TW / 2;
                int ex = e.getX(), ey = e.getY();

                if (ey < TH + GAP * 2) {
                    int sz = stagingList.size();
                    int tw = sz * TW + Math.max(0, sz - 1) * GAP;
                    int sx = (panelW - tw) / 2, idx = sz;
                    for (int i = 0; i < sz; i++) { if (ex < sx + i * (TW + GAP) + TW / 2) { idx = i; break; } }
                    stagingList.add(idx, card);
                } else if (ex < deckX) {
                    int sz = topCards.size(), idx = sz;
                    for (int i = 0; i < sz; i++) {
                        if (ex < deckX - GAP - (sz - i) * (TW + GAP) + TW / 2) { idx = i; break; }
                    }
                    topCards.add(idx, card);
                } else if (ex > deckX + TW) {
                    int bZoneX = deckX + TW + GAP, sz = bottomCards.size(), idx = sz;
                    for (int i = 0; i < sz; i++) {
                        if (ex < bZoneX + i * (TW + GAP) + TW / 2) { idx = i; break; }
                    }
                    bottomCards.add(idx, card);
                } else {
                    stagingList.add(card);
                }
                confirmBtn.setEnabled(stagingList.isEmpty());
                canvas.repaint();
            }
        });
        canvas.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragging[0] != null) { dragX[0] = e.getX(); dragY[0] = e.getY(); canvas.repaint(); }
            }
            @Override public void mouseMoved(MouseEvent e) {
                computeLayout.run();
                for (Map.Entry<CardData, Rectangle> entry : cardBounds.entrySet()) {
                    if (entry.getValue().contains(e.getX(), e.getY())) {
                        showZoom(entry.getKey().imageUrl()); return;
                    }
                }
                hideZoom();
            }
        });

        int[] timeLeft = { 20 };
        javax.swing.Timer[] timerHolder = { null };
        javax.swing.Timer countdown = !timed ? null : new javax.swing.Timer(1000, ae -> {
            int t = --timeLeft[0];
            timerLabel.setText(t + "s");
            if (t <= 5) timerLabel.setForeground(Color.RED);
            if (t <= 0) {
                timerHolder[0].stop();
                topCards.addAll(stagingList);
                stagingList.clear();
                hideZoom();
                dlg.dispose();
            }
        });
        timerHolder[0] = countdown;

        confirmBtn.addActionListener(ae -> {
            if (countdown != null) countdown.stop();
            hideZoom();
            dlg.dispose();
        });

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 6));
        south.add(timerLabel);
        south.add(confirmBtn);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(canvas, BorderLayout.CENTER);
        dlg.getContentPane().add(south,  BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        if (countdown != null) countdown.start();
        dlg.setVisible(true);
        if (countdown != null) countdown.stop();

        return new DeckLookDecision(List.of(), List.of(),
                peekIndices(cards, topCards), peekIndices(cards, bottomCards));
    }

    void showRevealAddUpToMatchingRestBottom(List<CardData> cards, Deque<CardData> deck,
            boolean isP1, int maxAdd, String jobFilter, String categoryFilter, String cardNameFilter, String typeFilter, int maxCost,
            String elementFilter, String orElementFilter) {
        int n = cards.size();
        JDialog dlg = new JDialog(frame, "Reveal — Add to Hand, Rest to Bottom", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<CardData> order = new ArrayList<>(cards);
        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[] cardLabels = new JLabel[n];
        Set<CardData> handSet = new LinkedHashSet<>();
        int[] selectedForSwap = { -1 };
        boolean[] updating = { false };

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelFont(11));
        confirmBtn.setEnabled(true);

        Runnable updateLabels = () -> {
            for (int j = 0; j < n; j++) {
                ImageIcon ic = imgCache.get(order.get(j));
                if (ic != null) { cardLabels[j].setIcon(ic); cardLabels[j].setText(null); }
            }
        };

        JToggleButton[] handBtns = new JToggleButton[n];

        Runnable refreshHandButtons = () -> {
            int count = handSet.size();
            for (int j = 0; j < n; j++) {
                CardData c = order.get(j);
                boolean isChar = c.isForward() || c.isBackup() || c.isMonster();
                boolean noFilters = jobFilter == null && categoryFilter == null
                        && cardNameFilter == null && typeFilter == null && orElementFilter == null;
                boolean matches = noFilters
                        || (jobFilter       != null && CardFilters.meetsJobFilter(c, jobFilter))
                        || (categoryFilter  != null && CardFilters.meetsCategoryFilter(c, categoryFilter))
                        || (cardNameFilter  != null && CardFilters.meetsCardNameFilter(c, cardNameFilter))
                        || (typeFilter      != null && meetsRevealTypeFilter(c, typeFilter))
                        || (orElementFilter != null && CardFilters.meetsElementFilter(c, orElementFilter));
                boolean elemOk = elementFilter == null || CardFilters.meetsElementFilter(c, elementFilter);
                boolean costOk = maxCost < 0 || c.cost() <= maxCost;
                boolean inHand = handSet.contains(c);
                handBtns[j].setEnabled(isChar && matches && elemOk && costOk && (inHand || count < maxAdd));
            }
        };

        Runnable refreshBorders = () -> {
            for (int j = 0; j < n; j++) {
                CardData c = order.get(j);
                if (handSet.contains(c))
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(0, 200, 80), 3));
                else if (j == selectedForSwap[0])
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
                else
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
            }
        };

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(order.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    CardData c = order.get(idx);
                    if (handSet.contains(c)) return;
                    if (selectedForSwap[0] == -1) {
                        selectedForSwap[0] = idx;
                    } else if (selectedForSwap[0] == idx) {
                        selectedForSwap[0] = -1;
                    } else {
                        int other = selectedForSwap[0];
                        if (handSet.contains(order.get(other))) { selectedForSwap[0] = idx; refreshBorders.run(); return; }
                        CardData tmp = order.get(idx); order.set(idx, order.get(other)); order.set(other, tmp);
                        updateLabels.run();
                        updating[0] = true;
                        for (int j = 0; j < n; j++) handBtns[j].setSelected(handSet.contains(order.get(j)));
                        updating[0] = false;
                        refreshHandButtons.run();
                        selectedForSwap[0] = -1;
                    }
                    refreshBorders.run();
                }
            });
            cardLabels[i] = lbl;

            JToggleButton handBtn = new JToggleButton(txt("→ Hand"));
            handBtn.setFont(FontLoader.loadPixelFont(9));
            handBtns[i] = handBtn;
            handBtn.addItemListener(ie -> {
                if (updating[0]) return;
                CardData c = order.get(idx);
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    handSet.add(c);
                } else {
                    handSet.remove(c);
                }
                selectedForSwap[0] = -1;
                refreshHandButtons.run();
                refreshBorders.run();
            });

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl,     BorderLayout.CENTER);
            wrapper.add(handBtn, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        // Initial button enabled state
        refreshHandButtons.run();

        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { imgCache.put(c, ic); updateLabels.run(); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        String filterDesc = typeFilter     != null ? typeFilter + "s"
                : jobFilter      != null ? "Job [" + jobFilter + "] Characters"
                : categoryFilter != null ? "Category [" + categoryFilter + "] Characters"
                : "Characters";
        if (elementFilter != null) filterDesc = elementFilter + " " + filterDesc;
        if (orElementFilter != null) filterDesc = orElementFilter + " or " + filterDesc;
        if (maxCost >= 0) filterDesc += " of cost " + maxCost + " or less";
        JLabel instructions = new JLabel(
                txt("Toggle '→ Hand' on " + filterDesc + " (up to " + maxAdd
                        + "). Swap the rest to order (left = first at bottom)."),
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelFont(9));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        for (int i = 0; i < n; i++) deck.pollFirst();
        for (CardData c : handSet) {
            if (isP1) gameState.getP1Hand().add(c);
            else      gameState.getP2Hand().add(c);
            log(c.name() + " → hand");
        }
        if (!handSet.isEmpty()) {
            if (isP1) cb.refreshP1Hand().run();
            else      cb.refreshP2Hand().run();
        }
        for (CardData c : order) {
            if (!handSet.contains(c)) { deck.addLast(c); log(c.name() + " → bottom of deck"); }
        }
        if (isP1) cb.refreshP1Deck().run();
        else      cb.refreshP2Deck().run();
    }

    /**
     * Reveals {@code cards} (already peeked from the top of {@code deck}). The player toggles
     * up to {@code maxAdd} cards to hand; any card whose name equals {@code excludeName} cannot
     * be chosen for hand. All non-hand cards go to the Break Zone.
     */
    void showRevealAddUpToExcludingNameRestBz(List<CardData> cards, Deque<CardData> deck,
            boolean isP1, int maxAdd, String excludeName) {
        int n = cards.size();
        JDialog dlg = new JDialog(frame,
                "Reveal — Add to Hand (up to " + maxAdd + "), Rest to Break Zone", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[] cardLabels = new JLabel[n];
        Set<CardData> handSet = new LinkedHashSet<>();

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelFont(11));

        JToggleButton[] handBtns = new JToggleButton[n];

        Runnable refreshHandButtons = () -> {
            int count = handSet.size();
            for (int j = 0; j < n; j++) {
                CardData c = cards.get(j);
                boolean excluded = c.name().equalsIgnoreCase(excludeName);
                boolean inHand = handSet.contains(c);
                handBtns[j].setEnabled(!excluded && (inHand || count < maxAdd));
            }
        };

        Runnable refreshBorders = () -> {
            for (int j = 0; j < n; j++) {
                CardData c = cards.get(j);
                boolean excluded = c.name().equalsIgnoreCase(excludeName);
                if (handSet.contains(c))
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(0, 200, 80), 3));
                else if (excluded)
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(Color.RED, 2));
                else
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
            }
        };

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(cards.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
            });
            cardLabels[i] = lbl;

            JToggleButton handBtn = new JToggleButton(txt("→ Hand"));
            handBtn.setFont(FontLoader.loadPixelFont(9));
            handBtns[i] = handBtn;
            handBtn.addItemListener(ie -> {
                CardData c = cards.get(idx);
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) handSet.add(c);
                else handSet.remove(c);
                refreshHandButtons.run();
                refreshBorders.run();
            });

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl,     BorderLayout.CENTER);
            wrapper.add(handBtn, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        refreshHandButtons.run();
        refreshBorders.run();

        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try {
                        ImageIcon ic = get();
                        int j = cards.indexOf(c);
                        if (ic != null && j >= 0) { imgCache.put(c, ic); cardLabels[j].setIcon(ic); cardLabels[j].setText(null); }
                    } catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        JLabel instructions = new JLabel(
                txt("Toggle '→ Hand' to add cards (up to " + maxAdd
                        + "). Card Name " + excludeName + " (red) must go to Break Zone."),
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelFont(9));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        // Consume all peeked cards from the top of the deck, then distribute
        for (int i = 0; i < n; i++) deck.pollFirst();
        List<CardData> bz = isP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
        for (CardData c : cards) {
            if (handSet.contains(c)) {
                if (isP1) gameState.getP1Hand().add(c);
                else      gameState.getP2Hand().add(c);
                log(c.name() + " → hand");
            } else {
                bz.add(c);
                log(c.name() + " → Break Zone");
            }
        }
        if (!handSet.isEmpty()) {
            if (isP1) cb.refreshP1Hand().run();
            else      cb.refreshP2Hand().run();
        }
        if (isP1) cb.refreshP1Deck().run();
        else      cb.refreshP2Deck().run();
    }

    /**
     * Reveals {@code cards} (already peeked from the top of {@code deck}).
     * Player chooses exactly 1 card whose name matches {@code cardName} to play onto
     * the field; the remaining cards go to the bottom of the deck in any order.
     * If no matching card exists, all cards go to the bottom and nothing is played.
     */
    /**
     * "Reveal N cards. Play up to {@code maxPlay} matching {@code typeFilter} onto the field;
     * return the rest to the bottom of the deck in any order."
     *
     * <p>The player clicks "→ Field" to select up to {@code maxPlay} eligible cards.
     * Clicking two non-selected cards swaps their bottom-of-deck order.
     */
    void showRevealPlayTypeOntoFieldRestBottom(List<CardData> cards, Deque<CardData> deck,
            boolean isP1, int maxPlay, String typeFilter, String categoryFilter, Consumer<CardData> playOntoField) {
        String typeLabel = (categoryFilter != null ? "Category " + categoryFilter + " " : "") + typeFilter;
        java.util.function.Predicate<CardData> eligible = c ->
                meetsRevealTypeFilter(c, typeFilter) && CardFilters.meetsCategoryFilter(c, categoryFilter);
        showRevealPlayOntoFieldRestBottomImpl(cards, deck, isP1, maxPlay, typeLabel, eligible,
                false, playOntoField);
    }

    void showRevealPlayElementTypeCostOntoField(List<CardData> cards, Deque<CardData> deck,
            boolean isP1, int maxPlay, String element, String typeFilter, int maxCost,
            boolean restToHand, Consumer<CardData> playOntoField) {
        String typeLabel = (element != null ? element + " " : "") + typeFilter
                + (maxCost >= 0 ? " of cost " + maxCost + " or less" : "");
        java.util.function.Predicate<CardData> eligible = c ->
                meetsRevealTypeFilter(c, typeFilter)
                && (element == null || c.containsElement(element))
                && (maxCost < 0 || c.cost() <= maxCost);
        showRevealPlayOntoFieldRestBottomImpl(cards, deck, isP1, maxPlay, typeLabel, eligible,
                restToHand, playOntoField);
    }

    /**
     * "Play up to {@code maxPlay} Card Name {@code cardName} or Job {@code job} of cost
     * {@code maxCost} or less among them onto the field; rest to the bottom of the deck in any
     * order." — combined Card-Name-or-Job filter (e.g. "Card Name Moogle (XIV) or Job Moogle").
     */
    void showRevealPlayNamedOrJobMaxCostOntoFieldRestBottom(List<CardData> cards, Deque<CardData> deck,
            boolean isP1, int maxPlay, String cardName, String job, int maxCost, Consumer<CardData> playOntoField) {
        String typeLabel = "Card Name " + cardName + " or Job " + job
                + (maxCost >= 0 ? " of cost " + maxCost + " or less" : "");
        java.util.function.Predicate<CardData> eligible = c ->
                (CardFilters.meetsCardNameFilter(c, cardName) || CardFilters.meetsJobFilter(c, job))
                && (maxCost < 0 || c.cost() <= maxCost);
        showRevealPlayOntoFieldRestBottomImpl(cards, deck, isP1, maxPlay, typeLabel, eligible,
                false, playOntoField);
    }

    /**
     * @param restToHand where the revealed cards that were not played go — the ability user's hand
     *                   when true (26-053L Bartz), the bottom of the deck otherwise. Bottom-of-deck
     *                   order is the player's to set, so the swap controls only matter then; cards
     *                   going to hand have no order to choose.
     */
    private void showRevealPlayOntoFieldRestBottomImpl(List<CardData> cards, Deque<CardData> deck,
            boolean isP1, int maxPlay, String typeLabel,
            java.util.function.Predicate<CardData> eligible, boolean restToHand,
            Consumer<CardData> playOntoField) {
        int n = cards.size();
        JDialog dlg = new JDialog(frame, "Reveal — Play up to " + maxPlay + " " + typeLabel
                + (restToHand ? " onto Field, Rest to Hand" : " onto Field, Rest to Bottom"), true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<CardData> order     = new ArrayList<>(cards);
        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[] cardLabels      = new JLabel[n];
        Set<CardData> fieldSet   = new LinkedHashSet<>();
        int[] selectedForSwap    = { -1 };
        boolean[] updating       = { false };

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelFont(11));

        Runnable updateLabels = () -> {
            for (int j = 0; j < n; j++) {
                ImageIcon ic = imgCache.get(order.get(j));
                if (ic != null) { cardLabels[j].setIcon(ic); cardLabels[j].setText(null); }
            }
        };

        JToggleButton[] fieldBtns = new JToggleButton[n];

        Runnable refreshFieldButtons = () -> {
            int count = fieldSet.size();
            for (int j = 0; j < n; j++) {
                CardData c = order.get(j);
                boolean inField = fieldSet.contains(c);
                fieldBtns[j].setEnabled(eligible.test(c) && (inField || count < maxPlay));
            }
        };

        Runnable refreshBorders = () -> {
            for (int j = 0; j < n; j++) {
                CardData c = order.get(j);
                if (fieldSet.contains(c))
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(0, 200, 80), 3));
                else if (j == selectedForSwap[0])
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
                else
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
            }
        };

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(order.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    CardData c = order.get(idx);
                    if (fieldSet.contains(c)) return;
                    if (selectedForSwap[0] == -1) {
                        selectedForSwap[0] = idx;
                    } else if (selectedForSwap[0] == idx) {
                        selectedForSwap[0] = -1;
                    } else {
                        int other = selectedForSwap[0];
                        if (fieldSet.contains(order.get(other))) { selectedForSwap[0] = idx; refreshBorders.run(); return; }
                        CardData tmp = order.get(idx); order.set(idx, order.get(other)); order.set(other, tmp);
                        updateLabels.run();
                        updating[0] = true;
                        for (int j = 0; j < n; j++) fieldBtns[j].setSelected(fieldSet.contains(order.get(j)));
                        updating[0] = false;
                        refreshFieldButtons.run();
                        selectedForSwap[0] = -1;
                    }
                    refreshBorders.run();
                }
            });
            cardLabels[i] = lbl;

            JToggleButton fieldBtn = new JToggleButton(txt("→ Field"));
            fieldBtn.setFont(FontLoader.loadPixelFont(9));
            fieldBtns[i] = fieldBtn;
            fieldBtn.addItemListener(ie -> {
                if (updating[0]) return;
                CardData c = order.get(idx);
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    fieldSet.add(c);
                } else {
                    fieldSet.remove(c);
                }
                selectedForSwap[0] = -1;
                refreshFieldButtons.run();
                refreshBorders.run();
            });

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl,      BorderLayout.CENTER);
            wrapper.add(fieldBtn, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        refreshFieldButtons.run();

        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { imgCache.put(c, ic); updateLabels.run(); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        JLabel instructions = new JLabel(
                txt("Click '→ Field' on up to " + maxPlay + " " + typeLabel + "(s) to play. "
                        + (restToHand
                            ? "The rest go to your hand."
                            : "Swap the rest to set bottom-of-deck order (left = first).")),
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelFont(9));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        for (int i = 0; i < n; i++) deck.pollFirst();
        for (CardData c : order) {
            if (fieldSet.contains(c)) continue;
            if (restToHand) {
                (isP1 ? gameState.getP1Hand() : gameState.getP2Hand()).add(c);
                log(c.name() + " → hand");
            } else {
                deck.addLast(c);
                log(c.name() + " → bottom of deck");
            }
        }
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();
        if (restToHand) { if (isP1) cb.refreshP1Hand().run(); else cb.refreshP2Hand().run(); }

        for (CardData c : fieldSet) {
            log(c.name() + " played onto field");
            playOntoField.accept(c);
        }
    }

    /**
     * "Reveal N cards. Add up to {@code handMax} matching {@code handTypeFilter} to hand,
     * OR play up to {@code fieldMax} matching {@code fieldJobFilter}+{@code fieldTypeFilter}
     * onto the field. Only one branch fires; the rest go to the bottom of the deck."
     *
     * <p>Each revealed card shows two toggle buttons. Selecting any button disables every
     * other button across all cards — only one card can be sent to one destination.
     */
    void showRevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(List<CardData> cards, Deque<CardData> deck,
            boolean isP1, int handMax, String handTypeFilter, int fieldMax,
            String fieldJobFilter, String fieldTypeFilter, Consumer<CardData> playOntoField) {
        int n = cards.size();
        String title = "Reveal — Add " + handTypeFilter + " to Hand  OR  Play "
                + (fieldJobFilter != null ? "Job " + fieldJobFilter + " " : "") + fieldTypeFilter + " onto Field";
        JDialog dlg = new JDialog(frame, title, true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<CardData> order            = new ArrayList<>(cards);
        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[]  cardLabels            = new JLabel[n];
        CardData[] chosenCard           = { null };
        String[]   chosenDest           = { null };   // "hand" | "field" | null
        int[]      selectedForSwap      = { -1 };
        boolean[]  updating             = { false };

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelFont(11));

        Runnable updateLabels = () -> {
            for (int j = 0; j < n; j++) {
                ImageIcon ic = imgCache.get(order.get(j));
                if (ic != null) { cardLabels[j].setIcon(ic); cardLabels[j].setText(null); }
            }
        };

        JToggleButton[] handBtns  = new JToggleButton[n];
        JToggleButton[] fieldBtns = new JToggleButton[n];

        Runnable refreshButtons = () -> {
            boolean anyChosen = chosenCard[0] != null;
            for (int j = 0; j < n; j++) {
                CardData c       = order.get(j);
                boolean isChosen = c == chosenCard[0];
                boolean handEligible  = meetsRevealTypeFilter(c, handTypeFilter);
                boolean fieldEligible = meetsRevealTypeFilter(c, fieldTypeFilter)
                        && (fieldJobFilter == null || CardFilters.meetsJobFilter(c, fieldJobFilter));
                handBtns[j].setEnabled(handEligible   && (!anyChosen || (isChosen && "hand".equals(chosenDest[0]))));
                fieldBtns[j].setEnabled(fieldEligible && (!anyChosen || (isChosen && "field".equals(chosenDest[0]))));
            }
        };

        Runnable refreshBorders = () -> {
            for (int j = 0; j < n; j++) {
                CardData c = order.get(j);
                if (c == chosenCard[0])
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(0, 200, 80), 3));
                else if (j == selectedForSwap[0])
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
                else
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
            }
        };

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(order.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    CardData c = order.get(idx);
                    if (c == chosenCard[0]) return;     // chosen card is locked
                    if (selectedForSwap[0] == -1) {
                        selectedForSwap[0] = idx;
                    } else if (selectedForSwap[0] == idx) {
                        selectedForSwap[0] = -1;
                    } else {
                        int other = selectedForSwap[0];
                        if (order.get(other) == chosenCard[0]) { selectedForSwap[0] = idx; refreshBorders.run(); return; }
                        CardData tmp = order.get(idx); order.set(idx, order.get(other)); order.set(other, tmp);
                        updateLabels.run();
                        updating[0] = true;
                        for (int j = 0; j < n; j++) {
                            handBtns[j].setSelected(order.get(j) == chosenCard[0] && "hand".equals(chosenDest[0]));
                            fieldBtns[j].setSelected(order.get(j) == chosenCard[0] && "field".equals(chosenDest[0]));
                        }
                        updating[0] = false;
                        refreshButtons.run();
                        selectedForSwap[0] = -1;
                    }
                    refreshBorders.run();
                }
            });
            cardLabels[i] = lbl;

            JToggleButton handBtn  = new JToggleButton(txt("→ Hand"));
            JToggleButton fieldBtn = new JToggleButton(txt("→ Field"));
            handBtn.setFont(FontLoader.loadPixelFont(9));
            fieldBtn.setFont(FontLoader.loadPixelFont(9));
            handBtns[i]  = handBtn;
            fieldBtns[i] = fieldBtn;

            handBtn.addItemListener(ie -> {
                if (updating[0]) return;
                CardData c = order.get(idx);
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    chosenCard[0] = c; chosenDest[0] = "hand";
                    updating[0] = true; fieldBtns[idx].setSelected(false); updating[0] = false;
                } else {
                    if (c == chosenCard[0] && "hand".equals(chosenDest[0])) { chosenCard[0] = null; chosenDest[0] = null; }
                }
                selectedForSwap[0] = -1;
                refreshButtons.run(); refreshBorders.run();
            });

            fieldBtn.addItemListener(ie -> {
                if (updating[0]) return;
                CardData c = order.get(idx);
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    chosenCard[0] = c; chosenDest[0] = "field";
                    updating[0] = true; handBtns[idx].setSelected(false); updating[0] = false;
                } else {
                    if (c == chosenCard[0] && "field".equals(chosenDest[0])) { chosenCard[0] = null; chosenDest[0] = null; }
                }
                selectedForSwap[0] = -1;
                refreshButtons.run(); refreshBorders.run();
            });

            JPanel btnRow = new JPanel(new java.awt.GridLayout(1, 2, 2, 0));
            btnRow.setOpaque(false);
            btnRow.add(handBtn);
            btnRow.add(fieldBtn);

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl,    BorderLayout.CENTER);
            wrapper.add(btnRow, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        refreshButtons.run();

        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { imgCache.put(c, ic); updateLabels.run(); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        JLabel instructions = new JLabel(
                txt("Select 1 card: '→ Hand' (" + handTypeFilter + ") or '→ Field' ("
                + (fieldJobFilter != null ? "Job " + fieldJobFilter + " " : "") + fieldTypeFilter
                + "). Swap others to set bottom-of-deck order (left = first)."),
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelFont(9));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRowPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRowPanel.add(confirmBtn);
        south.add(btnRowPanel, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        // Commit choices after dialog closes
        for (int i = 0; i < n; i++) deck.pollFirst();
        for (CardData c : order) {
            if (c == chosenCard[0]) continue;
            deck.addLast(c);
            log(c.name() + " → bottom of deck");
        }
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();

        if (chosenCard[0] != null && "field".equals(chosenDest[0])) {
            log(chosenCard[0].name() + " played onto field");
            playOntoField.accept(chosenCard[0]);
        } else if (chosenCard[0] != null) {
            if (isP1) gameState.getP1Hand().add(chosenCard[0]);
            else      gameState.getP2Hand().add(chosenCard[0]);
            log(chosenCard[0].name() + " → hand");
            if (isP1) cb.refreshP1Hand().run(); else cb.refreshP2Hand().run();
        }
    }

    void showRevealPlayNamedOntoFieldRestBottom(List<CardData> cards, Deque<CardData> deck,
            boolean isP1, String cardName, int maxCost, Consumer<CardData> playOntoField) {
        String costSuffix = maxCost >= 0 ? " of cost " + maxCost + " or less" : "";
        int n = cards.size();
        JDialog dlg = new JDialog(frame, "Reveal — Play " + cardName + costSuffix + " onto Field, Rest to Bottom", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        List<CardData> order    = new ArrayList<>(cards);
        Map<CardData, ImageIcon> imgCache = new LinkedHashMap<>();
        JLabel[] cardLabels     = new JLabel[n];
        CardData[] chosenToPlay = { null };
        int[] selectedForSwap   = { -1 };
        boolean[] updating      = { false };

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelFont(11));

        Runnable updateLabels = () -> {
            for (int j = 0; j < n; j++) {
                ImageIcon ic = imgCache.get(order.get(j));
                if (ic != null) { cardLabels[j].setIcon(ic); cardLabels[j].setText(null); }
            }
        };

        JToggleButton[] fieldBtns = new JToggleButton[n];

        Runnable refreshFieldButtons = () -> {
            for (int j = 0; j < n; j++) {
                CardData c   = order.get(j);
                boolean eligible = c.name().equalsIgnoreCase(cardName)
                        && (maxCost < 0 || c.cost() <= maxCost);
                boolean chosen   = chosenToPlay[0] == c;
                fieldBtns[j].setEnabled(eligible && (chosen || chosenToPlay[0] == null));
            }
        };

        Runnable refreshBorders = () -> {
            for (int j = 0; j < n; j++) {
                CardData c = order.get(j);
                if (chosenToPlay[0] == c)
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(0, 200, 80), 3));
                else if (j == selectedForSwap[0])
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
                else
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
            }
        };

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(null);
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(order.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
                @Override public void mousePressed(MouseEvent e) {
                    CardData c = order.get(idx);
                    if (chosenToPlay[0] == c) return;
                    if (selectedForSwap[0] == -1) {
                        selectedForSwap[0] = idx;
                    } else if (selectedForSwap[0] == idx) {
                        selectedForSwap[0] = -1;
                    } else {
                        int other = selectedForSwap[0];
                        if (chosenToPlay[0] == order.get(other)) { selectedForSwap[0] = idx; refreshBorders.run(); return; }
                        CardData tmp = order.get(idx); order.set(idx, order.get(other)); order.set(other, tmp);
                        updateLabels.run();
                        updating[0] = true;
                        for (int j = 0; j < n; j++) fieldBtns[j].setSelected(chosenToPlay[0] == order.get(j));
                        updating[0] = false;
                        refreshFieldButtons.run();
                        selectedForSwap[0] = -1;
                    }
                    refreshBorders.run();
                }
            });
            cardLabels[i] = lbl;

            JToggleButton fieldBtn = new JToggleButton(txt("→ Field"));
            fieldBtn.setFont(FontLoader.loadPixelFont(9));
            fieldBtns[i] = fieldBtn;
            fieldBtn.addItemListener(ie -> {
                if (updating[0]) return;
                CardData c = order.get(idx);
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    chosenToPlay[0] = c;
                } else {
                    if (chosenToPlay[0] == c) chosenToPlay[0] = null;
                }
                selectedForSwap[0] = -1;
                refreshFieldButtons.run();
                refreshBorders.run();
            });

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl,      BorderLayout.CENTER);
            wrapper.add(fieldBtn, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        refreshFieldButtons.run();

        for (CardData c : cards) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(c.imageUrl());
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { imgCache.put(c, ic); updateLabels.run(); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }

        JLabel instructions = new JLabel(
                txt("Click '→ Field' on 1 Card Name " + cardName + costSuffix
                        + " to play. Swap the rest to order (left = first at bottom)."),
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelFont(9));
        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        for (int i = 0; i < n; i++) deck.pollFirst();
        CardData played = chosenToPlay[0];
        for (CardData c : order) {
            if (c == played) continue;
            deck.addLast(c);
            log(c.name() + " → bottom of deck");
        }
        if (isP1) cb.refreshP1Deck().run(); else cb.refreshP2Deck().run();

        if (played != null) {
            log(played.name() + " played onto field");
            playOntoField.accept(played);
        } else {
            log("No Card Name " + cardName + costSuffix + " selected — all cards to bottom");
        }
    }

    /**
     * "Look at N cards. Put 1 on top of your deck and the other(s) to the bottom."
     *
     * <p>Player picks exactly one card to remain on top.  All other peeked cards go to the
     * bottom of the deck in the order they were peeked.  For the canonical N=2 form there is
     * one binary choice; for N=1 the only card stays on top with no UI prompt.
     */
    private DeckLookDecision showPickOneTopRestBottom(List<CardData> cards) {
        int n = cards.size();

        // Trivial case: only one card peeked — it just stays on top, nothing to decide.
        if (n == 1) return DeckLookDecision.keepOnTop(1);

        JDialog dlg = new JDialog(frame, "Look — Pick 1 for Top of Deck", true);
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JButton confirmBtn = new JButton("Confirm");
        confirmBtn.setFont(FontLoader.loadPixelFont(11));
        confirmBtn.setEnabled(false);

        int[] topIdx = { -1 };
        JToggleButton[] topBtns = new JToggleButton[n];
        JLabel[] cardLabels = new JLabel[n];

        JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        for (int i = 0; i < n; i++) {
            final int idx = i;
            JLabel lbl = makeCardLabel(cards.get(i).imageUrl());
            lbl.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { showZoom(cards.get(idx).imageUrl()); }
                @Override public void mouseExited(MouseEvent e)  { hideZoom(); }
            });
            cardLabels[i] = lbl;

            JToggleButton btn = new JToggleButton(txt("→ Top"));
            btn.setFont(FontLoader.loadPixelFont(9));
            topBtns[i] = btn;
            btn.addItemListener(ie -> {
                if (ie.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                    for (int j = 0; j < n; j++) if (j != idx && topBtns[j].isSelected()) topBtns[j].setSelected(false);
                    topIdx[0] = idx;
                    confirmBtn.setEnabled(true);
                } else if (topIdx[0] == idx) {
                    topIdx[0] = -1;
                    confirmBtn.setEnabled(false);
                }
                for (int j = 0; j < n; j++) {
                    cardLabels[j].setBorder(BorderFactory.createLineBorder(
                            j == topIdx[0] ? new Color(0, 200, 80) : new Color(160, 110, 220),
                            j == topIdx[0] ? 3 : 1));
                }
            });

            JPanel wrapper = new JPanel(new BorderLayout(0, 2));
            wrapper.setOpaque(false);
            wrapper.add(lbl, BorderLayout.CENTER);
            wrapper.add(btn, BorderLayout.SOUTH);
            cardsPanel.add(wrapper);
        }

        JLabel instructions = new JLabel(
                "Pick exactly 1 card to put on top of your deck. The rest go to the bottom.",
                SwingConstants.CENTER);
        instructions.setFont(FontLoader.loadPixelFont(9));

        confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(instructions, BorderLayout.NORTH);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        btnRow.add(confirmBtn);
        south.add(btnRow, BorderLayout.SOUTH);

        dlg.getContentPane().setLayout(new BorderLayout(0, 4));
        dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
        dlg.getContentPane().add(south,      BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(frame);
        dlg.setVisible(true);

        int chosen = topIdx[0] >= 0 ? topIdx[0] : 0;
        List<Integer> bottom = new ArrayList<>();
        for (int i = 0; i < n; i++) if (i != chosen) bottom.add(i);
        return new DeckLookDecision(List.of(), List.of(), List.of(chosen), bottom);
    }

    // ── Shared helpers ──────────────────────────────────────────────────────────

    /** Creates a standard card-image label with placeholder styling. Pass {@code null} url to skip async load. */
    private JLabel makeCardLabel(String imageUrl) {
        JLabel lbl = new JLabel("...", SwingConstants.CENTER);
        lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
        lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
        lbl.setOpaque(true);
        lbl.setBackground(Color.DARK_GRAY);
        lbl.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
        if (imageUrl != null) {
            new SwingWorker<ImageIcon, Void>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    Image img = ImageCache.load(imageUrl);
                    return img == null ? null
                            : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
                    catch (InterruptedException | ExecutionException ignored) {}
                }
            }.execute();
        }
        return lbl;
    }

    private static boolean meetsRevealTypeFilter(CardData c, String type) {
        return switch (type.toLowerCase()) {
            case "monster"   -> c.isMonster();
            case "forward"   -> c.isForward();
            case "backup"    -> c.isBackup();
            case "character" -> c.isForward() || c.isBackup() || c.isMonster();
            case "summon"    -> c.isSummon();
            default          -> false;
        };
    }
}
