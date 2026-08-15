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

    /** A single add: the chosen card serial and which player it targets. */
    public record Selection(String serial, boolean isP1) {}

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

    private DebugCardPickerDialog(JFrame parent, String title,
            java.util.function.Consumer<Selection> addAction,
            java.util.function.Consumer<Boolean> clearAction, String clearActionLabel) {
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
        p1Radio.addActionListener(e -> targetIsP1 = true);
        p2Radio.addActionListener(e -> targetIsP1 = false);
        JPanel targetPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        targetPanel.add(new JLabel("Target player:"));
        targetPanel.add(p1Radio);
        targetPanel.add(p2Radio);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(targetPanel, BorderLayout.NORTH);
        northPanel.add(searchPanel, BorderLayout.CENTER);

        JButton addButton = new JButton("+ Add");
        JButton closeButton = new JButton("Close");
        addButton.addActionListener(e -> addSelected(addAction));
        closeButton.addActionListener(e -> dispose());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(closeButton);
        buttonPanel.add(addButton);

        JPanel southPanel = new JPanel(new BorderLayout());
        // Optional lower-left action (e.g. the "Add Card to Hand"/"Add Card to BZ" flows): wipe the
        // corresponding zone of whichever player is currently selected by the target radio buttons.
        if (clearAction != null) {
            JButton clearButton = new JButton(clearActionLabel);
            clearButton.setToolTipText("Remove all cards from the selected player's zone.");
            clearButton.addActionListener(e -> clearAction.accept(targetIsP1));
            JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            leftPanel.add(clearButton);
            southPanel.add(leftPanel, BorderLayout.WEST);
        }
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
        addAction.accept(new Selection(serial, targetIsP1));
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
        pickRepeated(parent, title, addAction, null, null);
    }

    /**
     * Variant that also shows a lower-left button labelled {@code clearActionLabel} running
     * {@code clearAction} with the currently selected target player ({@code true} = P1); pass
     * {@code null} to omit the button. The action fires in place and does not close the dialog.
     */
    public static void pickRepeated(JFrame parent, String title,
            java.util.function.Consumer<Selection> addAction,
            java.util.function.Consumer<Boolean> clearAction, String clearActionLabel) {
        DebugCardPickerDialog dialog = new DebugCardPickerDialog(parent, title, addAction, clearAction, clearActionLabel);
        dialog.setVisible(true);
    }
}
