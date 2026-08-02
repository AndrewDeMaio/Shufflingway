package shufflingway;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import shufflingway.net.GameConnection;
import shufflingway.net.MatchSetup;

/**
 * The opponent seat when P2 is a human on another client.
 *
 * <p><b>Phase 1 scope.</b> The lobby now agrees on decks, shuffle seed and first player, so both
 * clients deal an identical game — that is what this phase delivers. Replicating <em>actions</em>
 * over the wire is the next phase, so every decision below is still a stub: it declines, logs
 * what it was asked for, and lets the local game continue rather than stalling it.
 *
 * <p>The important thing it already does is refuse to play for the remote player. Before this
 * existed, connecting left {@link ComputerPlayer} driving P2 — the AI would take the human
 * opponent's turn on both clients at once.
 */
class RemoteOpponent implements OpponentController {

	private final MainWindow mw;
	private final GameConnection connection;
	private final MatchSetup setup;
	private boolean cancelled = false;

	RemoteOpponent(MainWindow mw, GameConnection connection, MatchSetup setup) {
		this.mw         = mw;
		this.connection = connection;
		this.setup      = setup;
	}

	MatchSetup setup()          { return setup; }
	GameConnection connection() { return connection; }

	@Override
	public void cancel() { cancelled = true; }

	@Override
	public boolean isCpu() { return false; }

	@Override
	public void runTurn() {
		if (cancelled) return;
		mw.logEntry("[P2] Opponent's turn — waiting for them to act (turn replication not yet wired up).");
	}

	@Override
	public void requestBlocker(int effectiveAttackerPower, ForwardTarget attacker, boolean forcedBlock,
	                           Consumer<ForwardTarget> onChosen) {
		mw.logEntry("[P2] Block declaration not yet sent over the network — treating as no block.");
		onChosen.accept(null);
	}

	@Override
	public void requestPartyBlocker(List<Integer> attackerIndices, int combinedPower,
	                                Consumer<Integer> onChosen) {
		mw.logEntry("[P2] Party block not yet sent over the network — treating as no block.");
		onChosen.accept(null);
	}

	@Override
	public void requestPartyBlockerDamage(List<Integer> attackerIndices, int blockerPower,
	                                      Consumer<Map<Integer, Integer>> onAssigned) {
		onAssigned.accept(Map.of());
	}

	@Override
	public void requestReactiveShields(Runnable onDone) {
		// Nothing to offer yet; pass priority straight on so the local game keeps moving.
		onDone.run();
	}
}
