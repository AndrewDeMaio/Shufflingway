package shufflingway;

import scraper.CardDatabase;
import java.awt.*;
import java.awt.event.*;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.*;

/**
 * Static factory methods for the "Name a Job" and "Name an Element and Job" dialogs.
 * All dialogs are modal, have no cancel path, and require a confirmed selection to close.
 */
class NameSelectionDialogs {

    /**
     * Shows the job-selection dialog (interactive) or picks from the AI player's field jobs.
     *
     * @param frame            parent frame
     * @param fieldJobCandidates jobs present on the acting player's field; used as the AI candidate pool
     * @param interactive      true = show dialog for a human player
     * @param log              receives log messages
     */
    static String selectJob(JFrame frame, List<String> fieldJobCandidates,
                            boolean interactive, Consumer<String> log) {
        List<String> allJobs = loadJobs(log);
        if (allJobs.isEmpty()) return null;
        if (!interactive) {
            List<String> candidates = fieldJobCandidates.isEmpty() ? allJobs : fieldJobCandidates;
            String picked = candidates.get((int) (Math.random() * candidates.size()));
            log.accept("[AI] selected Job: " + picked);
            return picked;
        }
        return showJobDialog(frame, allJobs);
    }

    /**
     * Shows the combined element + job dialog (interactive) or picks randomly for the AI.
     *
     * @param frame       parent frame
     * @param prompt      label text shown above the element picker
     * @param interactive true = show dialog for a human player
     * @param log         receives log messages
     */
    static String[] selectElementAndJob(JFrame frame, String prompt,
                                        boolean interactive, Consumer<String> log) {
        if (!interactive) {
            String elem = ActionResolver.ELEMENT_NAMES[(int) (Math.random() * ActionResolver.ELEMENT_NAMES.length)];
            List<String> jobs = loadJobs(log);
            String job = jobs.isEmpty() ? "Warrior" : jobs.get((int) (Math.random() * jobs.size()));
            log.accept("[AI] named Element: " + elem + ", Job: " + job);
            return new String[]{elem, job};
        }
        List<String> jobs = loadJobs(log);
        if (jobs.isEmpty()) return null;
        return showElementAndJobDialog(frame, prompt, jobs);
    }

    /**
     * Collects the distinct job names from a set of field cards, splitting multi-job strings
     * (e.g. "Warrior/Rebel") into their components.
     */
    static List<String> collectFieldJobs(List<CardData> fwds, CardData[] bkps, List<CardData> mons) {
        TreeSet<String> out = new TreeSet<>();
        for (CardData c : fwds) splitJobs(c.job(), out);
        for (CardData c : bkps) if (c != null) splitJobs(c.job(), out);
        for (CardData c : mons) splitJobs(c.job(), out);
        return new ArrayList<>(out);
    }

    // -------------------------------------------------------------------------

    private static List<String> loadJobs(Consumer<String> log) {
        try {
            return CardDatabase.loadJobs();
        } catch (SQLException e) {
            log.accept("[Job select] DB error: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static void splitJobs(String job, TreeSet<String> out) {
        if (job == null || job.isBlank()) return;
        for (String part : job.split("/")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
    }

    private static String showJobDialog(JFrame frame, List<String> jobs) {
        String[] result = {null};
        JDialog dialog = new JDialog(frame, "Name a Job", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        JTextField searchField = new JTextField();

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String j : jobs) listModel.addElement(j);
        JList<String> jobList = new JList<>(listModel);
        jobList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!listModel.isEmpty()) jobList.setSelectedIndex(0);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() {
                String text = searchField.getText().toLowerCase();
                listModel.clear();
                for (String j : jobs)
                    if (j.toLowerCase().contains(text)) listModel.addElement(j);
                if (!listModel.isEmpty()) jobList.setSelectedIndex(0);
            }
            @Override public void insertUpdate(DocumentEvent e)  { filter(); }
            @Override public void removeUpdate(DocumentEvent e)  { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });

        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            String sel = jobList.getSelectedValue();
            if (sel != null) { result[0] = sel; dialog.dispose(); }
        });
        jobList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && jobList.getSelectedValue() != null) okButton.doClick();
            }
        });

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        top.add(new JLabel("Name a Job:"), BorderLayout.NORTH);
        top.add(searchField, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottom.add(okButton);

        dialog.setLayout(new BorderLayout(0, 4));
        dialog.add(top, BorderLayout.NORTH);
        dialog.add(new JScrollPane(jobList), BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setSize(280, 420);
        dialog.setLocationRelativeTo(frame);
        SwingUtilities.invokeLater(searchField::requestFocusInWindow);
        dialog.setVisible(true);
        return result[0];
    }

    private static String[] showElementAndJobDialog(JFrame frame, String prompt, List<String> jobs) {
        String[] elemItems = new String[ActionResolver.ELEMENT_NAMES.length + 1];
        elemItems[0] = "— Element —";
        System.arraycopy(ActionResolver.ELEMENT_NAMES, 0, elemItems, 1, ActionResolver.ELEMENT_NAMES.length);

        JComboBox<String> elemCombo = new JComboBox<>(elemItems);

        JTextField searchField = new JTextField();

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String j : jobs) listModel.addElement(j);
        JList<String> jobList = new JList<>(listModel);
        jobList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (!listModel.isEmpty()) jobList.setSelectedIndex(0);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            private void filter() {
                String text = searchField.getText().toLowerCase();
                listModel.clear();
                for (String j : jobs)
                    if (j.toLowerCase().contains(text)) listModel.addElement(j);
                if (!listModel.isEmpty()) jobList.setSelectedIndex(0);
            }
            @Override public void insertUpdate(DocumentEvent e)  { filter(); }
            @Override public void removeUpdate(DocumentEvent e)  { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });

        JButton okBtn = new JButton("OK");
        okBtn.setEnabled(false);
        elemCombo.addItemListener(e -> okBtn.setEnabled(elemCombo.getSelectedIndex() > 0));

        String[] result = {null, null};
        JDialog dialog = new JDialog(frame, "Name Element and Job", true);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        okBtn.addActionListener(e -> {
            String job = jobList.getSelectedValue();
            if (job == null) return;
            result[0] = (String) elemCombo.getSelectedItem();
            result[1] = job;
            dialog.dispose();
        });
        jobList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && jobList.getSelectedValue() != null) okBtn.doClick();
            }
        });

        JPanel elemRow = new JPanel(new BorderLayout(6, 0));
        elemRow.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        elemRow.add(new JLabel("Element:"), BorderLayout.WEST);
        elemRow.add(elemCombo, BorderLayout.CENTER);

        JPanel jobTop = new JPanel(new BorderLayout(0, 4));
        jobTop.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        jobTop.add(new JLabel("Job:"), BorderLayout.NORTH);
        jobTop.add(searchField, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(new JLabel(prompt, SwingConstants.CENTER), BorderLayout.NORTH);
        top.add(elemRow, BorderLayout.CENTER);
        top.add(jobTop, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bottom.add(okBtn);

        dialog.setLayout(new BorderLayout(0, 4));
        dialog.add(top, BorderLayout.NORTH);
        dialog.add(new JScrollPane(jobList), BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setSize(300, 480);
        dialog.setLocationRelativeTo(frame);
        SwingUtilities.invokeLater(searchField::requestFocusInWindow);
        dialog.setVisible(true);
        return result[0] != null ? result : null;
    }
}
