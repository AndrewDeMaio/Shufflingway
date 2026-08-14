package shufflingway;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;

import shufflingway.dialog.DebugCardPickerDialog;

class DebugUtility {

    private final MainWindow mw;

    DebugUtility(MainWindow mw) {
        this.mw = mw;
    }

    void spawnOnField() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
            return;
        }
        DebugCardPickerDialog.pickRepeated(mw.frame, "Spawn Card on Field", this::spawnSelectedOnField);
    }

    private void spawnSelectedOnField(DebugCardPickerDialog.Selection sel) {
        CardData card = mw.buildCardDataFromSerial(sel.serial());
        if (card == null) {
            JOptionPane.showMessageDialog(mw.frame, "Card not found: " + sel.serial(), "Debug Spawn", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean isP1 = sel.isP1();
        String who = isP1 ? "P1" : "P2";
        mw.gameState.getIdentity().put(card, isP1);
        if (card.isForward()) {
            if (isP1) mw.placeCardInForwardZone(card); else mw.placeP2CardInForwardZone(card);
        } else if (card.isMonster()) {
            if (isP1) mw.placeCardInMonsterZone(card); else mw.placeP2CardInMonsterZone(card);
        } else if (card.isBackup()) {
            boolean hasSlot = isP1 ? mw.hasAvailableBackupSlot() : mw.p2HasAvailableBackupSlot();
            if (!hasSlot) {
                JOptionPane.showMessageDialog(mw.frame, who + " has no free Backup slot.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (isP1) mw.placeCardInFirstBackupSlot(card); else mw.placeP2CardInFirstBackupSlot(card);
        } else {
            addCardToHand(card, isP1);
            mw.logEntry("[Debug] " + card.name() + " is a Summon — added to " + who + " hand instead of field.");
            return;
        }
        mw.logEntry("[Debug] Spawned " + card.name() + " (" + sel.serial() + ") onto " + who + " field.");
    }

    void addToHand() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
            return;
        }
        DebugCardPickerDialog.pickRepeated(mw.frame, "Add Card to Hand", this::addSelectedToHand, this::clearHand, "Clear Hand");
    }

    private void addSelectedToHand(DebugCardPickerDialog.Selection sel) {
        CardData card = mw.buildCardDataFromSerial(sel.serial());
        if (card == null) {
            JOptionPane.showMessageDialog(mw.frame, "Card not found: " + sel.serial(), "Debug Spawn", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean isP1 = sel.isP1();
        mw.gameState.getIdentity().put(card, isP1);
        addCardToHand(card, isP1);
        mw.logEntry("[Debug] Added " + card.name() + " (" + sel.serial() + ") to " + (isP1 ? "P1" : "P2") + " hand.");
    }

    private void addCardToHand(CardData card, boolean isP1) {
        if (isP1) { mw.gameState.getP1Hand().add(card); mw.refreshP1HandLabel(); }
        else      { mw.gameState.getP2Hand().add(card); mw.refreshP2HandCountLabel(); }
    }

    /** Debug helper: removes every card from the given player's hand and refreshes the hand display. */
    private void clearHand(boolean isP1) {
        var hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
        int removed = hand.size();
        if (removed == 0) return;
        hand.clear();
        if (isP1) mw.refreshP1HandLabel(); else mw.refreshP2HandCountLabel();
        mw.logEntry("[Debug] Removed all " + removed + " card(s) from " + (isP1 ? "P1" : "P2") + "'s hand.");
    }

    void addToBreakZone() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Spawn", JOptionPane.WARNING_MESSAGE);
            return;
        }
        DebugCardPickerDialog.pickRepeated(mw.frame, "Add Card to BZ", this::addSelectedToBreakZone, this::clearBreakZone, "Clear BZ");
    }

    private void addSelectedToBreakZone(DebugCardPickerDialog.Selection sel) {
        CardData card = mw.buildCardDataFromSerial(sel.serial());
        if (card == null) {
            JOptionPane.showMessageDialog(mw.frame, "Card not found: " + sel.serial(), "Debug Spawn", JOptionPane.ERROR_MESSAGE);
            return;
        }
        boolean isP1 = sel.isP1();
        mw.gameState.getIdentity().put(card, isP1);
        mw.addToBreakZone(card);
        mw.logEntry("[Debug] Added " + card.name() + " (" + sel.serial() + ") to " + (isP1 ? "P1" : "P2") + " Break Zone.");
    }

    /** Debug helper: removes every card from the given player's Break Zone and refreshes its display. */
    private void clearBreakZone(boolean isP1) {
        var bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
        int removed = bz.size();
        if (removed == 0) return;
        bz.clear();
        if (isP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
        mw.logEntry("[Debug] Removed all " + removed + " card(s) from " + (isP1 ? "P1" : "P2") + "'s Break Zone.");
    }

    /**
     * Debug tool: place a named counter on (or remove one from) any card on the field.
     * Counter names are freeform — they may be multi-word ("Guinea Pig") or all-caps
     * ("EXP"), so the input is used as typed with only leading/trailing whitespace
     * trimmed. Changes refresh the owning field slot immediately so they are visible
     * on the board while the dialog stays open.
     */
    void addRemoveCounters() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Counters", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JTextField nameField = new JTextField(16);

        List<CardData> rowCards = new ArrayList<>();
        DefaultTableModel model = new DefaultTableModel(new Object[] { "Player", "Name", "Type", "Position" }, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        collectBoardRows(rowCards, model);

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(420, 220));

        JDialog dialog = new JDialog(mw.frame, "Add/Remove Counters", false);

        JButton addBtn = new JButton("Add", plusMinusIcon(true, new Color(0x2e9e46)));
        addBtn.addActionListener(e -> applyCounterChange(dialog, table, rowCards, nameField, true));
        JButton removeBtn = new JButton("Remove", plusMinusIcon(false, new Color(0xc0392b)));
        removeBtn.addActionListener(e -> applyCounterChange(dialog, table, rowCards, nameField, false));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        top.add(new JLabel("Counter name:"));
        top.add(nameField);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 6));
        buttons.add(addBtn);
        buttons.add(removeBtn);

        dialog.setLayout(new BorderLayout());
        dialog.add(top, BorderLayout.NORTH);
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(mw.frame);
        dialog.setVisible(true);
    }

    /** Rebuilds the counter dialog's table rows from the cards currently on both fields. */
    private void collectBoardRows(List<CardData> rowCards, DefaultTableModel model) {
        rowCards.clear();
        model.setRowCount(0);
        for (boolean isP1 : new boolean[] { true, false }) {
            CardData[] backups = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
            for (int i = 0; i < backups.length; i++) {
                if (backups[i] != null) addBoardRow(rowCards, model, isP1, backups[i], i + 1);
            }
            List<CardData> forwards = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
            for (int i = 0; i < forwards.size(); i++) addBoardRow(rowCards, model, isP1, forwards.get(i), i + 1);
            List<CardData> monsters = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
            for (int i = 0; i < monsters.size(); i++) addBoardRow(rowCards, model, isP1, monsters.get(i), i + 1);
        }
    }

    private void addBoardRow(List<CardData> rowCards, DefaultTableModel model, boolean isP1, CardData card, int position) {
        rowCards.add(card);
        model.addRow(new Object[] { isP1 ? "1" : "2", card.name(), card.type(), position });
    }

    /** Applies a single +1/-1 counter change to the selected card and refreshes its field slot. */
    private void applyCounterChange(JDialog dialog, JTable table, List<CardData> rowCards, JTextField nameField, boolean add) {
        int row = table.getSelectedRow();
        if (row < 0 || row >= rowCards.size()) {
            JOptionPane.showMessageDialog(dialog, "Select a card in the table first.", "Debug Counters", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Freeform name ("Guinea Pig", "EXP") — trim only leading/trailing whitespace.
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "Enter a counter name first.", "Debug Counters", JOptionPane.WARNING_MESSAGE);
            return;
        }
        CardData card = rowCards.get(row);
        if (add) {
            mw.gameState.placeCounters(card, name, 1);
            mw.logEntry("[Debug] Added 1 " + name + " Counter to " + card.name()
                    + "  [now: " + mw.gameState.getCountersMap(card) + "]");
        } else {
            if (mw.gameState.removeCounters(card, name, 1) == 0) return; // no such counter — do nothing
            mw.logEntry("[Debug] Removed 1 " + name + " Counter from " + card.name()
                    + "  [now: " + mw.gameState.getCountersMap(card) + "]");
        }
        refreshCounterOwnerSlot(card);
    }

    /** Refreshes whichever field slot currently holds {@code card}, if any (updates the on-screen counter badge). */
    private void refreshCounterOwnerSlot(CardData card) {
        for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
            if (mw.p1ForwardCards.get(i) == card) { mw.refreshP1ForwardSlot(i); return; }
        }
        for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
            if (mw.p2ForwardCards.get(i) == card) { mw.refreshP2ForwardSlot(i); return; }
        }
        for (int i = 0; i < mw.p1BackupCards.length; i++) {
            if (mw.p1BackupCards[i] == card) { mw.refreshP1BackupSlot(i); return; }
        }
        for (int i = 0; i < mw.p2BackupCards.length; i++) {
            if (mw.p2BackupCards[i] == card) { mw.refreshP2BackupSlot(i); return; }
        }
        for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
            if (mw.p1MonsterCards.get(i) == card) { mw.refreshP1MonsterSlot(i); return; }
        }
        for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
            if (mw.p2MonsterCards.get(i) == card) { mw.refreshP2MonsterSlot(i); return; }
        }
    }

    /** Paints a small round-capped {@code +} (or {@code −}) icon in the given color. */
    private static Icon plusMinusIcon(boolean plus, Color color) {
        int sz = 12;
        BufferedImage img = new BufferedImage(sz, sz, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int mid = sz / 2;
        g.drawLine(2, mid, sz - 3, mid);
        if (plus) g.drawLine(mid, 2, mid, sz - 3);
        g.dispose();
        return new ImageIcon(img);
    }

    /** One "label: control" row of {@link #setDamageAndCrystals}'s form, at {@code row}. */
    private static void addDialogRow(JPanel panel, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(2, 0, 2, 8);
        panel.add(new JLabel(label), c);

        c.gridx  = 1;
        c.insets = new Insets(2, 0, 2, 0);
        c.fill    = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(field, c);
    }

    /** A rule across both columns, separating one group of rows from the next. */
    private static void addSeparatorRow(JPanel panel, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx     = 0;
        c.gridy     = row;
        c.gridwidth = 2;
        c.fill      = GridBagConstraints.HORIZONTAL;
        c.insets    = new Insets(8, 0, 8, 0);
        panel.add(new JSeparator(SwingConstants.HORIZONTAL), c);
    }

    /**
     * Sets both players' damage counts and Crystal counts directly.
     *
     * <p>Damage is a row of 0–6 buttons because that is the whole range a player can sit at;
     * Crystals have no comparable ceiling, so they get a spinner. Both are written straight to
     * {@link GameState} rather than through the effects that normally change them, so nothing here
     * fires "you receive damage" or "gain a 《C》" triggers — this is a state setter, and a debug
     * tool that fired triggers could not be used to set up the board a trigger is being tested on.
     */
    void setDamageAndCrystals() {
        if (!mw.gameInProgress()) {
            JOptionPane.showMessageDialog(mw.frame, "Start a game first.", "Debug Damage/Crystals",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        int cur1 = mw.gameState.getP1DamageZone().size();
        int cur2 = mw.gameState.getP2DamageZone().size();
        int curC1 = mw.gameState.getP1Crystals();
        int curC2 = mw.gameState.getP2Crystals();

        int[] p1Value = {cur1};
        int[] p2Value = {cur2};
        JButton[] p1Buttons = makeDamageButtons(p1Value, cur1);
        JButton[] p2Buttons = makeDamageButtons(p2Value, cur2);
        JSpinner p1Crystals = makeCrystalSpinner(curC1);
        JSpinner p2Crystals = makeCrystalSpinner(curC2);

        JPanel p1Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        for (JButton b : p1Buttons) p1Row.add(b);
        JPanel p2Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        for (JButton b : p2Buttons) p2Row.add(b);

        JTextField serialField = new JTextField(10);
        String HINT = "(optional)";
        serialField.setForeground(Color.GRAY);
        serialField.setText(HINT);
        serialField.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                if (serialField.getText().equals(HINT)) {
                    serialField.setText("");
                    serialField.setForeground(Color.BLACK);
                }
            }
            @Override public void focusLost(FocusEvent e) {
                if (serialField.getText().isEmpty()) {
                    serialField.setForeground(Color.GRAY);
                    serialField.setText(HINT);
                }
            }
        });

        // The serial belongs with the damage rows — it is the card the added damage is dealt with,
        // and it is read only when a damage count goes up. The rule separates it from the Crystal
        // rows, which nothing above them feeds. GridBag rather than GridLayout so the separator can
        // span both columns while the labels stay in one aligned column.
        JPanel panel = new JPanel(new GridBagLayout());
        int row = 0;
        addDialogRow(panel, row++, "P1 Damage (current: " + cur1 + "):", p1Row);
        addDialogRow(panel, row++, "P2 Damage (current: " + cur2 + "):", p2Row);
        addDialogRow(panel, row++, "Card serial (for additions):", serialField);
        addSeparatorRow(panel, row++);
        addDialogRow(panel, row++, "P1 Crystals (current: " + curC1 + "):", p1Crystals);
        addDialogRow(panel, row,   "P2 Crystals (current: " + curC2 + "):", p2Crystals);

        int result = JOptionPane.showConfirmDialog(mw.frame, panel, "Set Damage/Crystals",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        int target1 = p1Value[0];
        int target2 = p2Value[0];
        int targetC1 = (Integer) p1Crystals.getValue();
        int targetC2 = (Integer) p2Crystals.getValue();

        CardData card = null;
        if (target1 > cur1 || target2 > cur2) {
            String serial = serialField.getText().trim();
            if (serial.isEmpty() || serial.equals(HINT)) serial = "1-001H";
            card = mw.buildCardDataFromSerial(serial);
            if (card == null) {
                JOptionPane.showMessageDialog(mw.frame, "Card not found: " + serial,
                        "Debug Damage", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        List<CardData> dz1 = mw.gameState.getP1DamageZone();
        if (target1 > cur1) {
            for (int i = 0; i < target1 - cur1; i++) dz1.add(card);
        } else {
            for (int i = cur1 - 1; i >= target1; i--) dz1.remove(i);
        }
        mw.refreshDamageZoneSlots(true);

        List<CardData> dz2 = mw.gameState.getP2DamageZone();
        if (target2 > cur2) {
            for (int i = 0; i < target2 - cur2; i++) { dz2.add(card); mw.p2DamageCount++; }
        } else {
            for (int i = cur2 - 1; i >= target2; i--) dz2.remove(i);
            mw.p2DamageCount = target2;
        }
        mw.refreshDamageZoneSlots(false);

        // Applied as a delta because GameState exposes add/spend rather than a setter; computing it
        // from the current count is also what keeps the total off negative.
        mw.gameState.addP1Crystals(targetC1 - curC1);
        mw.gameState.addP2Crystals(targetC2 - curC2);
        mw.refreshCrystalDisplays();

        mw.logEntry("[Debug] Damage set — P1: " + target1 + ", P2: " + target2
                + (card != null ? " (card: " + card.name() + ")" : "")
                + "; Crystals set — P1: " + targetC1 + ", P2: " + targetC2);
    }

    /** 0–20 covers any board a debug session needs; the display renders the count as a number. */
    private JSpinner makeCrystalSpinner(int initial) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initial, 0, 20, 1));
        spinner.setPreferredSize(new Dimension(56, 24));
        return spinner;
    }

    private JButton[] makeDamageButtons(int[] valueHolder, int initial) {
        JButton[] buttons = new JButton[7];
        for (int i = 0; i <= 6; i++) {
            int idx = i;
            buttons[i] = new JButton(String.valueOf(i));
            buttons[i].setPreferredSize(new Dimension(28, 28));
            buttons[i].setMargin(new Insets(0, 0, 0, 0));
            buttons[i].setFocusPainted(false);
            buttons[i].addActionListener(e -> {
                valueHolder[0] = idx;
                applyDamageButtonColors(buttons, idx);
            });
        }
        applyDamageButtonColors(buttons, initial);
        return buttons;
    }

    private void applyDamageButtonColors(JButton[] buttons, int value) {
        for (int i = 0; i < buttons.length; i++) {
            boolean filled = i <= value;
            buttons[i].setOpaque(filled);
            buttons[i].setContentAreaFilled(filled);
            buttons[i].setBackground(filled ? Color.RED : null);
        }
    }
}
