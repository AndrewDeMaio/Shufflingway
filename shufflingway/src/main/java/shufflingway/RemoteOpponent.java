package shufflingway;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.JDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import shufflingway.net.ActionType;
import shufflingway.net.ChoiceKind;
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
 * <p><b>Phase 4 scope.</b> Turn flow, card plays and combat are all replicated in both
 * directions: opening hands, phase advance, draws, end-of-turn cleanup, plays from hand, and
 * attack/block declarations with the damage that follows them.
 *
 * <p>On top of that sits the Phase 5 choice protocol: a CHOICE carries one player's answer to a
 * decision the <em>other</em> client is parked on mid-effect. Both clients resolve the same
 * ability, so each side simply waits for whichever half of the decision it does not own — see
 * {@link #awaitAnswer}. What a question asks, how each kind of player answers it and how an answer
 * is read back are described once by a {@link PlayerChoice} and routed by
 * {@link MainWindow#decide}; this class holds only the waiting and the wire format.
 *
 * <p>What is <em>not</em> replicated yet is the opponent's priority windows. {@code MainWindow}
 * still gives the opponent's half of every combat priority round to {@code p2AutoPass}, a timer,
 * and {@link #requestReactiveShields} passes straight through — so a remote player cannot act in
 * response to a declaration. That is the rest of the Phase 5 work, and until it lands it is also
 * what keeps combat safe to replicate this way: with no response window, the only inputs to a
 * battle are the attack and the block, both of which cross the wire, so the two clients cannot
 * diverge partway through one.
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

	/**
	 * Stops this controller. A block that was still outstanding is answered as "no block" rather
	 * than dropped: the local combat is parked on that callback, and leaving it unanswered would
	 * strand the board mid-battle with the attacker dulled and the Attack Phase unable to end.
	 *
	 * <p>A choice being waited on is released for the same reason, and more urgently — that wait is
	 * a modal dialog holding the EDT, so leaving it up would freeze the client outright.
	 */
	@Override
	public void cancel() {
		cancelled = true;
		Consumer<ForwardTarget> block      = pendingBlock;
		Consumer<Integer>       partyBlock = pendingPartyBlock;
		pendingBlock      = null;
		pendingPartyBlock = null;
		mw.setAwaitingRemoteBlock(false);
		if (awaitedChoiceDialog != null) awaitedChoiceDialog.dispose();
		if (block      != null) block.accept(null);
		if (partyBlock != null) partyBlock.accept(null);
	}

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
			case ATTACK         -> applyAttack(action.payload());
			case BLOCK          -> applyBlock(action.payload());
			case CHOICE         -> applyChoice(action.payload());
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

	/**
	 * The opponent declared an attack. What they attacked with is on their own side of their board
	 * and on P2's side of this one, so the side flips while the slot indices do not — the same
	 * convention a replayed Summon target follows.
	 *
	 * <p>The declaration is replayed rather than trusted: this drives the identical
	 * {@code MainWindow} entry point the AI uses, so the attacker dulls, its attack triggers fire,
	 * and the local player is put into block declaration by the same code either opponent reaches.
	 * Only the choice crosses the wire; the rules stay on both clients.
	 */
	private void applyAttack(JSONObject payload) {
		List<Integer> indices = indices(payload, "indices");
		if (indices.isEmpty()) {
			mw.reportDesync("opponent declared an attack with no attackers");
			return;
		}
		ForwardTarget.CardZone zone;
		try {
			zone = ForwardTarget.CardZone.valueOf(payload.optString("zone", ""));
		} catch (IllegalArgumentException e) {
			mw.reportDesync("opponent attacked from an unknown zone \"" + payload.optString("zone", "") + "\"");
			return;
		}
		if (indices.size() > 1 && zone != ForwardTarget.CardZone.FORWARD) {
			mw.reportDesync("opponent declared a party attack from " + zone + ", which cannot form a party");
			return;
		}
		for (int idx : indices) {
			if (mw.remoteAttackerAt(zone, idx) == null) {
				mw.reportDesync("opponent attacked with " + zone + " slot " + idx
						+ ", which holds nothing on this client");
				return;
			}
		}

		int expectedPower = payload.optInt("power", -1);
		int localPower    = mw.remoteAttackPower(zone, indices);
		if (expectedPower >= 0 && expectedPower != localPower)
			mw.reportDesync("opponent's attacker has power " + expectedPower + " there and "
					+ localPower + " here");

		mw.replayRemoteAttack(zone, indices);
	}

	/**
	 * The opponent answered the attack this client is holding. Their blocker sits on their own
	 * side there and on P1's here, so the target is rebuilt against the local board before the
	 * parked callback resumes the combat that was waiting on it.
	 */
	private void applyBlock(JSONObject payload) {
		mw.setAwaitingRemoteBlock(false);
		boolean blocked = payload.optBoolean("blocked", false);

		if (pendingPartyBlock != null) {
			Consumer<Integer> resume = pendingPartyBlock;
			pendingPartyBlock = null;
			if (!blocked) { pendingPartyAttackers = List.of(); resume.accept(null); return; }
			int idx = payload.optInt("idx", -1);
			if (idx < 0 || idx >= mw.p1ForwardCards.size()) {
				mw.reportDesync("opponent blocked the party with Forward " + idx
						+ ", which is not on their field here");
				pendingPartyAttackers = List.of();
				resume.accept(null);
				return;
			}
			partyDamageAnswer     = damageSpread(payload);
			pendingPartyAttackers = List.of();
			resume.accept(idx);
			return;
		}

		if (pendingBlock == null) {
			mw.reportDesync("opponent declared a block with no attack waiting for one");
			return;
		}
		Consumer<ForwardTarget> resume = pendingBlock;
		pendingBlock = null;
		if (!blocked) { resume.accept(null); return; }

		ForwardTarget.CardZone zone;
		try {
			zone = ForwardTarget.CardZone.valueOf(payload.optString("zone", ""));
		} catch (IllegalArgumentException e) {
			mw.reportDesync("opponent blocked from an unknown zone \"" + payload.optString("zone", "") + "\"");
			resume.accept(null);
			return;
		}
		int idx = payload.optInt("idx", -1);
		// The blocker is on the opponent's field, which is this client's P1 side.
		ForwardTarget blocker = new ForwardTarget(true, idx, zone);
		if (mw.fieldCardDataOrNull(blocker) == null) {
			mw.reportDesync("opponent blocked with " + zone + " slot " + idx
					+ ", which holds nothing on their field here");
			resume.accept(null);
			return;
		}
		resume.accept(blocker);
	}

	// ── Two-sided card choices ───────────────────────────────────────────

	/** Answers that have arrived, keyed by kind and removed as they are consumed. */
	private final Map<ChoiceKind, List<Integer>> deliveredChoices = new EnumMap<>(ChoiceKind.class);
	/** The kind {@link #awaitChoice} is parked on, and the dialog holding the EDT while it waits. */
	private ChoiceKind awaitedChoiceKind;
	private JDialog    awaitedChoiceDialog;

	/** The opponent answered a choice. Buffered by kind, then the waiter — if any — is released. */
	private void applyChoice(JSONObject payload) {
		String raw = payload.optString("kind", "");
		ChoiceKind kind;
		try {
			kind = ChoiceKind.valueOf(raw);
		} catch (IllegalArgumentException e) {
			mw.reportDesync("opponent sent a choice of unknown kind \"" + raw + "\"");
			return;
		}
		deliveredChoices.put(kind, indices(payload, "indices"));
		if (kind == awaitedChoiceKind && awaitedChoiceDialog != null) awaitedChoiceDialog.dispose();
	}

	/**
	 * Blocks this client until the opponent answers with a CHOICE of {@code kind}, and returns the
	 * card indices they sent — empty if the answer never came.
	 *
	 * <p>Unlike a block, which parks a callback and returns, an effect mid-resolution has nowhere
	 * to park: {@code GameContext} methods run to completion, and the rules that follow the choice
	 * are the statements after the call.  So this waits in place, in a modal dialog whose nested
	 * event loop keeps delivering inbound actions — including the one that disposes it.
	 *
	 * <p>The answer can also arrive <em>before</em> anyone waits, the sender firing as soon as
	 * their own dialog closes.  {@link #deliveredChoices} is checked first for that reason; racing
	 * the two would hang the client that lost.
	 *
	 * <p><b>Limitation.</b> Answers are keyed by kind alone, not by which decision they belong to.
	 * The protocol is strictly one question at a time — each answer is removed as it is consumed,
	 * and neither side sends until the other has — so a stale entry can only survive if one client
	 * abandoned an effect the other completed.  That is already a reported desync by the time it
	 * happens.  A sequence number would not help: the clients would have to agree on it, and the
	 * case that breaks the keying is exactly the case that would break the counter.
	 */
	private List<Integer> awaitChoice(ChoiceKind kind, String prompt) {
		if (cancelled) return List.of();
		List<Integer> already = deliveredChoices.remove(kind);
		if (already != null) return already;
		JDialog dialog = mw.buildWaitingForOpponentDialog(prompt);
		awaitedChoiceKind   = kind;
		awaitedChoiceDialog = dialog;
		dialog.setVisible(true);
		awaitedChoiceKind   = null;
		awaitedChoiceDialog = null;
		List<Integer> answer = deliveredChoices.remove(kind);
		return answer != null ? answer : List.of();
	}

	/**
	 * Waits for the remote player's answer to {@code choice} and returns it in this client's frame
	 * of reference, or nothing when it never came or cannot be acted on.
	 *
	 * <p>Two things happen to an answer between the wire and the caller, in this order. Each element
	 * is rewritten by {@link PlayerChoice#fromWire} — a field code names a side, and the sender's
	 * own side is this client's opponent — and then the answer is checked for legality, as a whole,
	 * against the board as this client holds it. The check is what stops a disagreement becoming a
	 * corrupted board: an answer this client cannot make sense of is reported as the desync it is,
	 * and the effect declines rather than acting on the nearest plausible card.
	 *
	 * <p>The whole answer is dropped when any part of it is illegal. A partly-applied effect would
	 * leave the two clients further apart than one that did nothing.
	 */
	List<Integer> awaitAnswer(PlayerChoice choice) {
		List<Integer> answer = awaitChoice(choice.kind(), choice.waitPrompt());
		List<Integer> local  = new ArrayList<>(answer.size());
		for (int raw : answer) local.add(choice.fromWire().applyAsInt(raw));
		if (!choice.legal().test(local)) {
			mw.reportDesync("opponent answered " + choice.kind() + " with " + answer + ", but "
					+ choice.legalityNote());
			return List.of();
		}
		return local;
	}

	/** Builds a CHOICE carrying one player's answer to a decision the opponent is parked on. */
	static GameAction choiceAction(ChoiceKind kind, List<Integer> indices) {
		return GameAction.of(ActionType.CHOICE, new JSONObject()
				.put("kind", kind.name())
				.put("indices", new JSONArray(indices)));
	}

	/** Reads the blocker's party damage spread, keyed by attacker slot. */
	static Map<Integer, Integer> damageSpread(JSONObject payload) {
		JSONObject raw = payload.optJSONObject("damage");
		if (raw == null) return Map.of();
		Map<Integer, Integer> out = new LinkedHashMap<>();
		for (String key : raw.keySet()) out.put(Integer.valueOf(key), raw.getInt(key));
		return out;
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

	/**
	 * The block answer the local attack is waiting on, or {@code null} when no attack is pending.
	 *
	 * <p>These are the one place this class holds a continuation. Every other inbound action is
	 * self-contained — it says what happened and this applies it — but a block is the second half
	 * of a question the local board asked, and the combat that follows it cannot proceed until the
	 * answer lands. The request methods park the callback here; {@link #applyBlock} runs it.
	 */
	private Consumer<ForwardTarget> pendingBlock;
	private Consumer<Integer>       pendingPartyBlock;
	/** The party the local player is attacking with, for validating the answer's damage spread. */
	private List<Integer>           pendingPartyAttackers = List.of();

	@Override
	public void requestBlocker(int effectiveAttackerPower, ForwardTarget attacker, boolean forcedBlock,
	                           Consumer<ForwardTarget> onChosen) {
		if (cancelled) { onChosen.accept(null); return; }
		pendingBlock = onChosen;
		mw.logEntry("Waiting for the opponent to declare a blocker...");
		mw.setAwaitingRemoteBlock(true);
	}

	@Override
	public void requestPartyBlocker(List<Integer> attackerIndices, int combinedPower,
	                                Consumer<Integer> onChosen) {
		if (cancelled) { onChosen.accept(null); return; }
		pendingPartyBlock     = onChosen;
		pendingPartyAttackers = List.copyOf(attackerIndices);
		mw.logEntry("Waiting for the opponent to declare a blocker...");
		mw.setAwaitingRemoteBlock(true);
	}

	/**
	 * The blocker's damage spread across the party. It rode in on the same BLOCK the block
	 * declaration did — the remote player assigns it in one interaction, and splitting it into a
	 * second round trip would leave the two clients' combats interleaved — so by the time this is
	 * asked the answer is already here.
	 */
	@Override
	public void requestPartyBlockerDamage(List<Integer> attackerIndices, int blockerPower,
	                                      Consumer<Map<Integer, Integer>> onAssigned) {
		Map<Integer, Integer> answered = partyDamageAnswer;
		partyDamageAnswer = null;
		onAssigned.accept(answered != null ? answered : Map.of());
	}

	/** The spread carried by the BLOCK that answered a party attack; consumed by the request above. */
	private Map<Integer, Integer> partyDamageAnswer;

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

	/**
	 * Builds an ATTACK for a declaration the local player has just made.
	 *
	 * <p>{@code indices} are slots on the sender's own field, which is the receiver's P2 side; more
	 * than one is a party. {@code power} is the sender's effective total and is sent only so the
	 * receiver can cross-check it — it never overrides the receiver's own calculation, because a
	 * disagreement is a desync to report rather than a number to adopt.
	 */
	static GameAction attackAction(ForwardTarget.CardZone zone, List<Integer> indices, int power) {
		return GameAction.of(ActionType.ATTACK, new JSONObject()
				.put("zone", zone.name())
				.put("indices", new JSONArray(indices))
				.put("power", power));
	}

	/**
	 * Builds a BLOCK answering the opponent's attack. A {@code null} zone declines the block.
	 *
	 * @param damage the blocker's spread across a blocked party, keyed by attacker slot; {@code null}
	 *               for a single attacker, where the blocker deals all its power to the one card
	 */
	static GameAction blockAction(ForwardTarget.CardZone zone, int idx, Map<Integer, Integer> damage) {
		JSONObject payload = new JSONObject().put("blocked", zone != null);
		if (zone != null) {
			payload.put("zone", zone.name()).put("idx", idx);
			if (damage != null && !damage.isEmpty()) {
				JSONObject spread = new JSONObject();
				damage.forEach((attacker, amount) -> spread.put(String.valueOf(attacker), amount));
				payload.put("damage", spread);
			}
		}
		return GameAction.of(ActionType.BLOCK, payload);
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
