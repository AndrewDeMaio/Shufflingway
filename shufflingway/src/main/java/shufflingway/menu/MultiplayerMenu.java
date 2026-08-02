package shufflingway.menu;

import shufflingway.net.GameAction;
import shufflingway.net.GameConnection;
import shufflingway.net.HostLobbyDialog;
import shufflingway.net.JoinLobbyDialog;
import shufflingway.net.MatchSetup;

import javax.swing.*;
import java.util.function.Consumer;

/**
 * Multiplayer menu — lets P1 host or join a game over a direct TCP connection.
 * Once the lobby has agreed on decks, shuffle seed and first player, the active
 * {@link GameConnection} is stored and the resulting {@link MatchSetup} is handed to the
 * main window, which starts the game from it.
 */
public class MultiplayerMenu extends JMenu {

    private GameConnection activeConnection;
    private final JMenuItem disconnectItem;

    /**
     * @param onConnected receives the agreed match parameters; the main window starts the
     *                    networked game from them
     */
    public MultiplayerMenu(JFrame owner, Consumer<MatchSetup> onConnected,
                           Runnable onDisconnected, Consumer<GameAction> onActionReceived) {
        super("Multiplayer");

        JMenuItem hostItem = new JMenuItem("Host Game…");
        JMenuItem joinItem = new JMenuItem("Join Game…");
        disconnectItem = new JMenuItem("Disconnect");
        disconnectItem.setEnabled(false);

        hostItem.addActionListener(e -> {
            HostLobbyDialog dlg = new HostLobbyDialog(owner);
            dlg.setVisible(true);
            // A connection without a setup means the lobby was cancelled after connecting.
            if (dlg.getConnection() != null && dlg.getSetup() != null)
                activate(dlg.getConnection(), dlg.getSetup(), owner,
                        onConnected, onDisconnected, onActionReceived);
        });

        joinItem.addActionListener(e -> {
            JoinLobbyDialog dlg = new JoinLobbyDialog(owner);
            dlg.setVisible(true);
            if (dlg.getConnection() != null && dlg.getSetup() != null)
                activate(dlg.getConnection(), dlg.getSetup(), owner,
                        onConnected, onDisconnected, onActionReceived);
        });

        disconnectItem.addActionListener(e -> disconnect(owner, onDisconnected));

        add(hostItem);
        add(joinItem);
        addSeparator();
        add(disconnectItem);
    }

    private void activate(GameConnection conn, MatchSetup setup, JFrame owner,
                          Consumer<MatchSetup> onConnected, Runnable onDisconnected,
                          Consumer<GameAction> onActionReceived) {
        if (activeConnection != null) activeConnection.close();
        activeConnection = conn;
        disconnectItem.setEnabled(true);

        conn.addListener(new shufflingway.net.ConnectionListener() {
            @Override
            public void onActionReceived(GameAction action) {
                SwingUtilities.invokeLater(() -> onActionReceived.accept(action));
            }
            @Override
            public void onDisconnected(String reason) {
                SwingUtilities.invokeLater(() -> {
                    activeConnection = null;
                    disconnectItem.setEnabled(false);
                    if (onDisconnected != null) onDisconnected.run();
                    JOptionPane.showMessageDialog(owner,
                        "Opponent disconnected: " + reason,
                        "Disconnected", JOptionPane.WARNING_MESSAGE);
                });
            }
        });

        // Hand the setup over before starting the reader: onConnected only queues the game
        // start on the EDT, and queuing it first guarantees it runs ahead of any inbound action.
        // Started the other way round, a fast peer's first message could be processed against a
        // game that had not been built yet.
        onConnected.accept(setup);
        conn.start();
    }

    private void disconnect(JFrame owner, Runnable onDisconnected) {
        if (activeConnection != null) {
            activeConnection.send(GameAction.of(shufflingway.net.ActionType.DISCONNECT,
                    new org.json.JSONObject().put("reason", "Player left")));
            activeConnection.close();
            activeConnection = null;
        }
        disconnectItem.setEnabled(false);
        if (onDisconnected != null) onDisconnected.run();
        JOptionPane.showMessageDialog(owner, "Disconnected.", "Multiplayer",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** Returns the active connection, or {@code null} if not connected. */
    public GameConnection getActiveConnection() { return activeConnection; }
}
