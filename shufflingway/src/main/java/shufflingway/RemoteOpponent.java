package shufflingway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.json.JSONArray;
import org.json.JSONObject;

import shufflingway.net.ActionType;
import shufflingway.net.GameAction;
import shufflingway.net.GameConnection;
import shufflingway.net.MatchSetup;

/**
 * The opponent seat when P2 is a human on another client.
 *
 * <p>Where {@link ComputerPlayer} computes P2's turn, this replays it: the remote client sends
 * what its player did, and this applies the same state changes to P2's side of the local board.
 * Both clients run the same engine over the same seeded decks, so the opponent's draws do not
 * have to be transmitted — drawing two cards here pulls the same two cards it did there.
 *
 * <p><b>Phase 2 scope.</b> Turn flow is replicated: opening hands, phase advance, draws, and
 * end-of-turn cleanup, in both directions. Card plays (Phase 3) and combat (Phase 4) are not,
 * so the decision callbacks below still decline rather than asking the remote player.
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

	MatchSetup setup() { return setup; }

	@Override
	public void cancel() { cancelled = true; }

	@Override
	public boolean isCpu() { return false; }

	// ── Outbound ─────────────────────────────────────────────────────────

	void send(GameAction action) {
		if (cancelled || !connection.isConnected()) return;
		connection.send(action);
	}

	// ── Inbound ──────────────────────────────────────────────────────────

	/**
	 * Applies one action from the remote player. Always called on the EDT.
	 *
	 * @return true if the action was consumed here
	 */
	boolean onActionReceived(GameAction action) {
		if (cancelled || mw.gameState.isP1GameOver()) return false;
		switch (action.type()) {
			case KEEP_HAND      -> applyKeepHand(action.payload());
			case MULLIGAN       -> applyMulligan(action.payload());
			case ADVANCE_PHASE  -> applyPhaseAdvance(action.payload());
			case PLAY_CARD      -> applyPlayCard(action.payload());
			case DISCARD_HAND   -> applyDiscard(action.payload());
			default             -> { return false; }
		}
		return true;
	}

	/** The opponent discarded from hand without generating CP (the end-phase trim to five). */
	private void applyDiscard(JSONObject payload) {
		List<Integer> selected = new ArrayList<>(indices(payload, "indices"));
		List<CardData> hand = mw.gameState.getP2Hand();
		for (int idx : selected) {
			if (idx < 0 || idx >= hand.size()) {
				mw.reportDesync("opponent discarded hand card " + idx + ", but their hand holds "
						+ hand.size() + " cards here");
				return;
			}
		}
		selected.sort(java.util.Collections.reverseOrder());
		for (int idx : selected) mw.playerBreakFromHand(false, idx);
		mw.logEntry("[P2] Discarded " + selected.size() + " card(s) — hand reduced to 5");
		mw.refreshP2HandCountLabel();
		mw.refreshP2BreakLabel();
	}

	/**
	 * The opponent played a card from hand. Their hand lives here as P2's, in the same order,
	 * so the index identifies the same card — but it is checked against the name they sent
	 * before anything is spent, because acting on the wrong card would corrupt the board
	 * silently while a rejected play is merely a reported desync.
	 */
	private void applyPlayCard(JSONObject payload) {
		int handIdx = payload.optInt("handIdx", -1);
		List<CardData> hand = mw.gameState.getP2Hand();
		if (handIdx < 0 || handIdx >= hand.size()) {
			mw.reportDesync("opponent played hand card " + handIdx + ", but their hand holds "
					+ hand.size() + " cards here");
			return;
		}
		CardData card     = hand.get(handIdx);
		String   expected = payload.optString("card", "");
		if (!card.name().equals(expected)) {
			mw.reportDesync("opponent played \"" + expected + "\" from hand slot " + handIdx
					+ ", which holds \"" + card.name() + "\" here");
			return;
		}

		Map<Integer, String> overrides = new LinkedHashMap<>();
		JSONObject rawOverrides = payload.optJSONObject("backupElements");
		if (rawOverrides != null)
			for (String key : rawOverrides.keySet())
				overrides.put(Integer.valueOf(key), rawOverrides.getString(key));

		// The caster already chose any Summon targets — replay that choice rather than making one
		// here, where "P2" is the AI branch and would pick something else entirely.
		//
		// The side flips on the way across: the two clients sit on opposite sides of the same
		// board, so what the caster recorded as their own field is this client's P2, and what they
		// recorded as their opponent's is this client's P1. Slot indices need no such adjustment —
		// both clients hold each zone in the same order.
		JSONArray rawTargets = payload.optJSONArray("summonTargets");
		List<ForwardTarget> summonTargets = new ArrayList<>();
		boolean targetsAreReplayed = rawTargets != null;
		if (rawTargets != null) {
			for (int i = 0; i < rawTargets.length(); i++) {
				JSONObject t = rawTargets.getJSONObject(i);
				summonTargets.add(new ForwardTarget(!t.getBoolean("p1"), t.getInt("idx"),
						ForwardTarget.CardZone.valueOf(t.getString("zone"))));
			}
		}

		mw.executePlay(false, card, handIdx,
				indices(payload, "discards"), indices(payload, "backups"), overrides,
				summonTargets, targetsAreReplayed);
	}

	/** The opponent settled on an opening hand order; mirror it so hand indices line up. */
	private void applyKeepHand(JSONObject payload) {
		if (!mw.gameState.reorderP2Hand(indices(payload, "order"))) {
			mw.reportDesync("opponent's opening hand order did not match the hand we dealt them");
			return;
		}
		mw.refreshP2HandCountLabel();
		mw.logEntry("[P2] Opponent keeps their hand.");
		mw.noteRemoteHandKept();
	}

	/** The opponent mulliganed: put those cards on the bottom of their deck and redraw. */
	private void applyMulligan(JSONObject payload) {
		if (!mw.gameState.mulliganP2(indices(payload, "bottomOrder"))) {
			mw.reportDesync("opponent's mulligan order did not match the hand we dealt them");
			return;
		}
		mw.refreshP2DeckLabel();
		mw.refreshP2HandCountLabel();
		mw.logEntry("[P2] Opponent takes a mulligan.");
	}

	/**
	 * The opponent's phase advanced. Applying the same transition locally lands on the mirrored
	 * state — same phase and turn, with the players swapped — so a disagreement afterwards is a
	 * desync and is reported as one.
	 */
	private void applyPhaseAdvance(JSONObject payload) {
		String  expectedPhase = payload.optString("phase", "");
		int     expectedTurn  = payload.optInt("turn", -1);
		boolean extraTurn     = payload.optBoolean("extraTurn", false);
		if (mw.gameState.getCurrentPhase() == null) {
			mw.reportDesync("opponent advanced a phase before this client's game had begun");
			return;
		}

		// An extra turn wraps END to ACTIVE without handing the turn over, so it takes the
		// matching transition here — otherwise this client would think the turn had come back.
		GameState.GamePhase entered = extraTurn
				? mw.gameState.advancePhaseExtraTurn()
				: mw.gameState.advancePhase();
		mw.refreshPhaseTracker();

		if (!entered.name().equals(expectedPhase) || mw.gameState.getTurnNumber() != expectedTurn) {
			mw.reportDesync("phase drift — opponent is in " + expectedPhase + " on turn "
					+ expectedTurn + ", this client reached " + entered.name() + " on turn "
					+ mw.gameState.getTurnNumber());
			return;
		}

		if (mw.gameState.getCurrentPlayer() == GameState.Player.P1) {
			// END wrapped to ACTIVE, so the turn has come back to the local player.
			mw.turnPhases().runP1TurnStart();
		} else {
			enterOpponentPhase(entered);
		}
	}

	/** Runs the mechanical work for a phase the opponent has just entered. */
	private void enterOpponentPhase(GameState.GamePhase phase) {
		switch (phase) {
			case ACTIVE -> mw.turnPhases().runP2ActivePhase();

			case DRAW -> {
				int drawCount = mw.gameState.getTurnNumber() == 1 ? 1 : 2;
				List<CardData> drawn = mw.turnPhases().runP2DrawPhase(drawCount);
				mw.logEntry("[P2] Draw Phase — Drew " + drawn.size() + " card(s)");
				if (drawn.size() < drawCount) mw.triggerGameOver("P2 milled out — You Win!");
			}

			case MAIN_1 -> {
				mw.logEntry("[P2] Main Phase 1");
				mw.processWarpCounters(false);
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase1(false);
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase1EachTurn();
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfOppMainPhase1(true);
			}

			case ATTACK -> {
				mw.logEntry("[P2] Attack Phase");
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhase(false);
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhaseEachTurn(false);
				mw.refreshAllP2ForwardSlots();
			}

			case MAIN_2 -> {
				mw.logEntry("[P2] Main Phase 2");
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase2(false);
			}

			case END -> {
				mw.logEntry("[P2] End Phase");
				mw.turnPhases().runP2EndOfTurnCleanup();
				mw.p2Turn.resetCastTracking();
			}
		}
	}

	private static List<Integer> indices(JSONObject payload, String key) {
		JSONArray arr = payload.optJSONArray(key);
		if (arr == null) return List.of();
		List<Integer> out = new ArrayList<>(arr.length());
		for (int i = 0; i < arr.length(); i++) out.add(arr.getInt(i));
		return out;
	}

	// ── OpponentController: decision requests ────────────────────────────

	@Override
	public void runTurn() {
		if (cancelled) return;
		// The local client already advanced into the opponent's Active Phase; run its mechanical
		// half so their board activates here too, then wait for them to drive the rest.
		mw.turnPhases().runP2ActivePhase();
		mw.logEntry("[P2] Opponent's turn.");
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

	/**
	 * Builds a PLAY_CARD for a card the local player is playing.
	 *
	 * <p>Everything travels as an index — hand slot, discard slots, backup slots — because both
	 * clients hold these zones in the same order. The card name rides along only so the
	 * receiver can check the indices still line up before acting on them.
	 */
	static GameAction playCardAction(CardData card, int handIdx, List<Integer> discards,
	                                 List<Integer> backupDulls, Map<Integer, String> backupElements,
	                                 List<ForwardTarget> summonTargets) {
		JSONObject overrides = new JSONObject();
		backupElements.forEach((slot, element) -> overrides.put(String.valueOf(slot), element));
		JSONArray targets = new JSONArray();
		if (summonTargets != null) {
			for (ForwardTarget t : summonTargets) {
				targets.put(new JSONObject()
						.put("p1", t.isP1())
						.put("idx", t.idx())
						.put("zone", t.zone().name()));
			}
		}
		return GameAction.of(ActionType.PLAY_CARD, new JSONObject()
				.put("handIdx", handIdx)
				.put("card", card.name())
				.put("discards", new JSONArray(discards))
				.put("backups", new JSONArray(backupDulls))
				.put("backupElements", overrides)
				// A cast Summon chooses its targets before the opponent may respond, so the choice
				// belongs to the caster and travels with the play. Always present, so the receiver
				// can tell "chose nothing" from an older client that never chose at all.
				.put("summonTargets", targets));
	}

	/** Builds a DISCARD_HAND for hand cards the local player is discarding without payment. */
	static GameAction discardAction(List<Integer> indices) {
		return GameAction.of(ActionType.DISCARD_HAND, new JSONObject()
				.put("indices", new JSONArray(indices)));
	}

	/** Builds an ADVANCE_PHASE for a phase the local player has just entered. */
	static GameAction phaseAdvanceAction(GameState.GamePhase phase, int turn, boolean extraTurn) {
		return GameAction.of(ActionType.ADVANCE_PHASE, new JSONObject()
				.put("phase", phase.name())
				.put("turn", turn)
				.put("extraTurn", extraTurn));
	}
}
