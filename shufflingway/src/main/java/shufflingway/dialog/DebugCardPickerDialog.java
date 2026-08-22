package shufflingway.dialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.event.KeyEvent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

import shufflingway.ImageCache;

/**
 * Non-modal searchable card picker over the entire card database. Each {@code + Add} click feeds the
 * selected card serial and target player to a caller-supplied action and leaves the dialog open, so
 * multiple cards can be added in one sitting. Used by the debug spawn/add tooling.
 *
 * <p>A full card image for the selected row sits beside the table, so the card being added can be
 * read in full rather than inferred from the row's columns.
 */
public class DebugCardPickerDialog extends JDialog {

    /**
     * A single add: the chosen card serial, which player it targets, how it arrives, and which of
     * that player's holding zones it lands in.
     */
    public record Selection(String serial, boolean isP1, Origin origin, Zone zone) {}

    /**
     * Which holding zone an added card goes to. Only the flows that show the zone selector can
     * report anything but {@link #BREAK_ZONE}; the others leave it at that default and ignore it.
     */
    public enum Zone {
        BREAK_ZONE("BZ"),
        RFP("RFP");

        /** How the zone is named on the radio button and in the clear button's label. */
        private final String label;

        Zone(String label) { this.label = label; }

        public String label() { return label; }
    }

    /**
     * Where a spawned card is treated as having come from. The engine's own distinction is binary —
     * {@code MainWindow.lastCardWasCast} is what gates {@code castOnly} abilities and what holds back
     * the "enters ... other than from your hand" watchers — so {@code HAND} means "as if cast" and
     * {@code BREAK_ZONE} stands for every other way a card can arrive.
     */
    public enum Origin { HAND, BREAK_ZONE }

    private static final String DB_URL = scraper.AppPaths.dbUrl();
    /** Model columns. The last two are hidden from the table; only {@code Card Text} stays searchable. */
    private static final String[] COLUMNS =
            {"Serial", "Name", "Type", "Element", "Cost", "Power", "Card Text", "Image URL"};
    private static final int COL_TEXT  = 6;
    private static final int COL_IMAGE = 7;
    /** Every model column the search box looks at — i.e. all of them but the image URL. */
    private static final int[] SEARCHABLE_COLUMNS = {0, 1, 2, 3, 4, 5, COL_TEXT};

    /** Card aspect is 429×600; this is that shape at a size the picker can carry beside the table. */
    private static final int PREVIEW_W = 250;
    private static final int PREVIEW_H = 350;

    /** Sorts serials numerically on the set prefix (e.g. "9-001C" before "10-001H"). */
    private static final java.util.Comparator<Object> SERIAL_ORDER = (a, b) -> {
        String sa = a == null ? "" : a.toString();
        String sb = b == null ? "" : b.toString();
        int da = sa.indexOf('-'), db = sb.indexOf('-');
        if (da > 0 && db > 0) {
            try {
                int na = Integer.parseInt(sa.substring(0, da));
                int nb = Integer.parseInt(sb.substring(0, db));
                if (na != nb) return Integer.compare(na, nb);
            } catch (NumberFormatException ignored) {}
        }
        return sa.compareTo(sb);
    };

    private final DefaultTableModel tableModel;
    private final TableRowSorter<DefaultTableModel> sorter;
    private final JTable table;
    private final JLabel previewLabel;
    /**
     * Image URL the preview is currently loading. Arrowing down the table starts a worker per row,
     * and they can finish out of order — each one only paints if it is still the pending request.
     */
    private String pendingPreviewUrl;
    /** Target player for the spawn/add; defaults to P1. */
    private boolean targetIsP1 = true;
    /** Arrival origin for the spawn flow; defaults to the way a card normally reaches the field. */
    private Origin origin = Origin.HAND;
    /** Destination zone for the add; defaults to the Break Zone. */
    private Zone zone = Zone.BREAK_ZONE;
    /**
     * The lower-left clear button, or {@code null} when the caller asked for none. Held so the
     * radio buttons can relabel it: in the zone-aware flow its text names the exact zone it is
     * about to wipe, and that pair moves as the radios move.
     */
    private JButton clearButton;
    /** What {@link #clearButton} should currently say -- a fixed string, or a derived one. */
    private java.util.function.Supplier<String> clearLabel = () -> "";

    private DebugCardPickerDialog(JFrame parent, String title,
            java.util.function.Consumer<Selection> addAction,
            java.util.function.BiConsumer<Boolean, Zone> clearAction, String clearActionLabel,
            boolean showOrigin, boolean showZone) {
        super(parent, title, false);
        setSize(720 + PREVIEW_W + 16, 560);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout());

        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        sorter = new TableRowSorter<>(tableModel);
        sorter.setComparator(0, SERIAL_ORDER);
        sorter.setSortKeys(java.util.List.of(new javax.swing.RowSorter.SortKey(0, SortOrder.ASCENDING)));
        table.setRowSorter(sorter);
        // Hide Card Text (still searchable via its model index) and the Image URL that feeds the
        // preview. Removing COL_TEXT shifts the image column down into its view slot, hence twice.
        table.removeColumn(table.getColumnModel().getColumn(COL_TEXT));
        table.removeColumn(table.getColumnModel().getColumn(COL_TEXT));
        table.getColumnModel().getColumn(1).setPreferredWidth(140);

        previewLabel = new JLabel("Select a card to preview", SwingConstants.CENTER);
        previewLabel.setPreferredSize(new Dimension(PREVIEW_W, PREVIEW_H));
        previewLabel.setBorder(BorderFactory.createEtchedBorder());
        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        previewPanel.add(previewLabel, BorderLayout.NORTH);

        // Fires for mouse clicks and keyboard navigation alike, so arrowing through the table
        // previews as it goes.
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showPreviewForSelectedRow();
        });

        JTextField searchField = new JTextField(24);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { applyFilter(searchField.getText()); }
            @Override public void removeUpdate(DocumentEvent e)  { applyFilter(searchField.getText()); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(searchField.getText()); }
        });

        JPanel searchPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        searchPanel.add(new JLabel("Search:"));
        searchPanel.add(searchField);

        JRadioButton p1Radio = new JRadioButton("P1", true);
        JRadioButton p2Radio = new JRadioButton("P2");
        ButtonGroup targetGroup = new ButtonGroup();
        targetGroup.add(p1Radio);
        targetGroup.add(p2Radio);
        p1Radio.addActionListener(e -> { targetIsP1 = true;  refreshClearButtonLabel(); });
        p2Radio.addActionListener(e -> { targetIsP1 = false; refreshClearButtonLabel(); });
        JPanel targetPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        targetPanel.add(new JLabel("Target player:"));
        targetPanel.add(p1Radio);
        targetPanel.add(p2Radio);

        // Destination zone, above the player it belongs to: the two together name one zone, and
        // reading them in that order matches the clear button they drive ("Clear P1 BZ").
        JPanel zonePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        if (showZone) {
            JRadioButton bzZoneRadio  = new JRadioButton("BZ", true);
            JRadioButton rfpZoneRadio = new JRadioButton("RFP");
            bzZoneRadio.setToolTipText("Add to the selected player's Break Zone.");
            rfpZoneRadio.setToolTipText("Add to the selected player's Removed From Game zone.");
            ButtonGroup zoneGroup = new ButtonGroup();
            zoneGroup.add(bzZoneRadio);
            zoneGroup.add(rfpZoneRadio);
            bzZoneRadio .addActionListener(e -> { zone = Zone.BREAK_ZONE; refreshClearButtonLabel(); });
            rfpZoneRadio.addActionListener(e -> { zone = Zone.RFP;        refreshClearButtonLabel(); });
            zonePanel.add(new JLabel("Zone:"));
            zonePanel.add(bzZoneRadio);
            zonePanel.add(rfpZoneRadio);
        }

        JPanel northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
        if (showZone) northPanel.add(zonePanel);
        northPanel.add(targetPanel);
        northPanel.add(searchPanel);

        JButton addButton = new JButton("+ Add");
        JButton closeButton = new JButton("Close");
        addButton.addActionListener(e -> addSelected(addAction));
        closeButton.addActionListener(e -> dispose());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(closeButton);
        buttonPanel.add(addButton);

        JPanel southPanel = new JPanel(new BorderLayout());
        JPanel leftPanel  = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        // Optional lower-left action (e.g. the "Add Card to Hand"/"Add Card to BZ" flows): wipe the
        // corresponding zone of whichever player is currently selected by the target radio buttons.
        if (clearAction != null) {
            // Zone-aware callers get a label built from the live radio pair rather than a fixed
            // string, so the button always names the one zone it is about to wipe.
            clearLabel = showZone
                    ? () -> "Clear " + (targetIsP1 ? "P1" : "P2") + " " + zone.label()
                    : () -> clearActionLabel;
            clearButton = new JButton();
            clearButton.setToolTipText("Remove all cards from the selected player's zone.");
            clearButton.addActionListener(e -> clearAction.accept(targetIsP1, zone));
            refreshClearButtonLabel();
            leftPanel.add(clearButton);
        }
        // Arrival origin, for the spawn flow. A card cast from hand fires castOnly enter-the-field
        // abilities that a card arriving any other way does not — and suppresses the abilities
        // watching for an arrival that was not a cast — so the two are worth spawning separately.
        if (showOrigin) {
            JRadioButton handRadio = new JRadioButton("Hand (cast)", true);
            JRadioButton bzRadio   = new JRadioButton("Break Zone");
            handRadio.setToolTipText("Enters as if cast from hand: castOnly abilities fire, and the "
                    + "card counts toward what its controller has cast this turn.");
            bzRadio.setToolTipText("Enters the way an effect puts a card onto the field: the "
                    + "\"enters other than from your hand\" abilities fire instead.");
            ButtonGroup originGroup = new ButtonGroup();
            originGroup.add(handRadio);
            originGroup.add(bzRadio);
            handRadio.addActionListener(e -> origin = Origin.HAND);
            bzRadio.addActionListener(e -> origin = Origin.BREAK_ZONE);
            leftPanel.add(new JLabel("Enters from:"));
            leftPanel.add(handRadio);
            leftPanel.add(bzRadio);
        }
        if (leftPanel.getComponentCount() > 0) southPanel.add(leftPanel, BorderLayout.WEST);
        southPanel.add(buttonPanel, BorderLayout.EAST);

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) addSelected(addAction);
            }
        });

        add(northPanel, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(previewPanel, BorderLayout.EAST);
        add(southPanel, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(addButton);
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        loadCards();
        searchField.requestFocusInWindow();
    }

    /** Re-reads {@link #clearLabel} onto the button. A no-op when the caller asked for no button. */
    private void refreshClearButtonLabel() {
        if (clearButton != null) clearButton.setText(clearLabel.get());
    }

    private void applyFilter(String text) {
        if (text == null || text.isBlank()) {
            sorter.setRowFilter(null);
            return;
        }
        String[] parts = text.trim().split("%", -1);
        StringBuilder sb = new StringBuilder("(?i)(?s)");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(".*");
            sb.append(Pattern.quote(parts[i]));
        }
        // Columns named explicitly so the hidden image URL cannot match — its host and "_eg.jpg"
        // suffix are shared by every card, so an unrestricted filter would match on fragments of them.
        sorter.setRowFilter(RowFilter.regexFilter(sb.toString(), SEARCHABLE_COLUMNS));
    }

    /**
     * Repaints the preview for whatever row is selected now, clearing it when the selection is
     * empty (which is what a filter that excludes the selected row leaves behind).
     */
    private void showPreviewForSelectedRow() {
        int row = table.getSelectedRow();
        if (row < 0) {
            pendingPreviewUrl = null;
            previewLabel.setIcon(null);
            previewLabel.setText("Select a card to preview");
            return;
        }
        int modelRow = table.convertRowIndexToModel(row);
        loadPreviewAsync((String) tableModel.getValueAt(modelRow, COL_IMAGE));
    }

    /** Loads and scales {@code url} off the EDT, then paints it if no newer request has started. */
    private void loadPreviewAsync(String url) {
        pendingPreviewUrl = url;
        previewLabel.setIcon(null);
        if (url == null || url.isBlank()) {
            previewLabel.setText("No image available");
            return;
        }
        previewLabel.setText("Loading…");

        new SwingWorker<ImageIcon, Void>() {
            @Override protected ImageIcon doInBackground() throws Exception {
                Image img = ImageCache.load(url);
                return img == null ? null
                        : new ImageIcon(img.getScaledInstance(PREVIEW_W, PREVIEW_H, Image.SCALE_SMOOTH));
            }
            @Override protected void done() {
                if (!url.equals(pendingPreviewUrl)) return; // superseded by a later selection
                try {
                    ImageIcon icon = get();
                    previewLabel.setIcon(icon);
                    previewLabel.setText(icon == null ? "No image available" : null);
                } catch (InterruptedException | ExecutionException e) {
                    previewLabel.setIcon(null);
                    previewLabel.setText("Error loading image");
                }
            }
        }.execute();
    }

    /**
     * Feeds the currently selected card (for the current target player) to {@code addAction} and
     * keeps the dialog open so more cards can be added. Warns if no table row is selected.
     */
    private void addSelected(java.util.function.Consumer<Selection> addAction) {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select a card in the table first.",
                    getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        int modelRow = table.convertRowIndexToModel(row);
        String serial = (String) tableModel.getValueAt(modelRow, 0);
        addAction.accept(new Selection(serial, targetIsP1, origin, zone));
    }

    private void loadCards() {
        String sql = "SELECT serial, name_en, type_en, element, cost, power, text_en, image_url FROM cards WHERE serial NOT LIKE 'B-%' AND serial NOT LIKE 'C-%' ORDER BY serial";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                tableModel.addRow(new Object[]{
                        rs.getString("serial"), rs.getString("name_en"), rs.getString("type_en"),
                        rs.getString("element"), rs.getObject("cost"), rs.getObject("power"),
                        rs.getString("text_en"), rs.getString("image_url")
                });
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error loading cards:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Opens the picker (non-modal) and invokes {@code addAction} for each {@code + Add} click with
     * the selected card and target player. The dialog stays open until the user closes it.
     */
    public static void pickRepeated(JFrame parent, String title,
            java.util.function.Consumer<Selection> addAction) {
        show(parent, title, addAction, null, null, false, false);
    }

    /**
     * Variant that also shows the arrival-origin radio buttons, so each add says whether the card
     * is entering as a cast from hand or by some other route. The choice reaches the caller as
     * {@link Selection#origin()}; without this the picker always reports {@link Origin#HAND}.
     */
    public static void pickRepeatedWithOrigin(JFrame parent, String title,
            java.util.function.Consumer<Selection> addAction) {
        show(parent, title, addAction, null, null, true, false);
    }

    /**
     * Variant that also shows a lower-left button labelled {@code clearActionLabel} running
     * {@code clearAction} with the currently selected target player ({@code true} = P1); pass
     * {@code null} to omit the button. The action fires in place and does not close the dialog.
     */
    public static void pickRepeated(JFrame parent, String title,
            java.util.function.Consumer<Selection> addAction,
            java.util.function.Consumer<Boolean> clearAction, String clearActionLabel) {
        show(parent, title, addAction,
                clearAction == null ? null : (isP1, z) -> clearAction.accept(isP1),
                clearActionLabel, false, false);
    }

    /**
     * Variant that adds a Break Zone / RFG selector above the target player, so one picker feeds
     * either holding zone. Each add reports its zone as {@link Selection#zone()}, and the
     * lower-left button relabels itself for the live pair -- "Clear P1 BZ", "Clear P2 RFP" -- so
     * the two radios always describe what it will wipe. Defaults to P1's Break Zone.
     */
    public static void pickRepeatedWithZone(JFrame parent, String title,
            java.util.function.Consumer<Selection> addAction,
            java.util.function.BiConsumer<Boolean, Zone> clearAction) {
        show(parent, title, addAction, clearAction, null, false, true);
    }

    private static void show(JFrame parent, String title,
            java.util.function.Consumer<Selection> addAction,
            java.util.function.BiConsumer<Boolean, Zone> clearAction, String clearActionLabel,
            boolean showOrigin, boolean showZone) {
        DebugCardPickerDialog dialog = new DebugCardPickerDialog(
                parent, title, addAction, clearAction, clearActionLabel, showOrigin, showZone);
        dialog.setVisible(true);
    }
}
