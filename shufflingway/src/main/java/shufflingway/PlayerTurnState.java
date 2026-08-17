package shufflingway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Per-player, turn-scoped rules state.
 *
 * <p>Every field here was previously a mirrored {@code p1Xxx}/{@code p2Xxx} pair on
 * {@link MainWindow}.  Holding one instance per player removes the duplication and, more
 * importantly, removes the class of bug where a value is updated or cleared for one side
 * and not the other — the reset paths now operate on a whole state object rather than on
 * several dozen individually-named fields.
 *
 * <p>Field lifetimes are unchanged by the move: some values are cleared at a turn boundary,
 * others ("next damage" shields) are consumed on use.  Nothing here resets itself; the reset
 * points remain exactly where they were in {@code MainWindow}.
 *
 * <p>Access it through {@link MainWindow#turn(boolean)} rather than reaching for
 * {@code p1Turn}/{@code p2Turn} directly, so side-relative code reads as
 * {@code turn(isP1).cardsCastThisTurn} instead of a ternary over two fields.
 */
class PlayerTurnState {

	/** This player's Forwards cannot have their power increased this turn. */
	boolean fwdBoostSuppressedThisTurn = false;

	/**
	 * Turn-scoped filter variants of {@code MainWindow.nullifyAbilityDmgSet}: a Forward on this
	 * side matching any filter takes 0 damage from Summons/abilities — this also covers Forwards
	 * that enter the field later in the turn.
	 */
	final List<Predicate<CardData>> nullifyAbilityDmgFilters = new ArrayList<>();

	/** Multiplier applied when any of this player's Forwards receives damage. */
	int forwardIncomingDmgMult = 1;

	/** Player-wide ability outgoing damage multiplier. */
	int abilityOutgoingDmgMult = 1;

	boolean receivedDamageThisTurn = false;

	/** Player shield: the next damage dealt to this player becomes 0. */
	boolean nextDamageZero = false;

	/**
	 * Companion to {@link #nextDamageZero} (Auron): when the player shield consumes, deal
	 * {@link #nextDamageZeroRedirectDmg} to the named Forward on the shield owner's field instead
	 * ("the next damage dealt to you becomes 0 and deal Auron 8000 damage instead").
	 */
	String nextDamageZeroRedirectName = null;
	int nextDamageZeroRedirectDmg = 0;

	boolean forwardPutToBZThisTurn = false;

	/** Set by the opponent: this player's Forwards cannot block targets with power below their own. */
	boolean forwardCannotBlockInferiorPower = false;

	int cardsCastThisTurn = 0;
	boolean summonCastThisTurn = false;
	/**
	 * Summons this player has put on the Stack during the current turn.
	 *
	 * <p>The counted twin of {@link #summonCastThisTurn}, kept separately because the two are set at
	 * different moments: the flag is raised by each casting path before the Summon reaches the Stack,
	 * while this is incremented by {@code MainWindow.pushSummonOnStack} — the single point every cast
	 * funnels through, and the only place from which "is this the first one this turn?" can still be
	 * answered. The Fiend 20-114L asks exactly that.
	 */
	int summonsCastThisTurn = 0;
	final Set<String> castJobsThisTurn = new HashSet<>();
	final Set<String> castNamesThisTurn = new HashSet<>();
	final Map<String, Integer> castCountByNameThisTurn = new HashMap<>();

	boolean turnOpponentFwdBroken = false;
	final Set<String> brokenJobsThisTurn = new HashSet<>();
	final Set<String> brokenElementsThisTurn = new HashSet<>();
	final Set<String> brokenCategoriesThisTurn = new HashSet<>();

	int cardsDrawnThisTurn = 0;
	boolean discardedByEffectThisTurn = false;
	boolean causedOpponentDiscardThisTurn = false;

	boolean formedPartyThisTurn = false;

	/**
	 * The Forwards in the party that most recently formed and attacked this turn.  Set when a party
	 * attack fires; read by party-attack auto-ability followups that act on "all Forwards in that
	 * party" (e.g. Gippal's +5000 power boost).
	 */
	List<CardData> currentPartyAttackers = new ArrayList<>();

	boolean partyAnyElementThisTurn = false;

	int forwardsLeftFieldThisTurn = 0;
	final Set<String> elementForwardsEnteredThisTurn = new HashSet<>();
	final Set<String> cardsTookDamageThisTurn = new HashSet<>();
	boolean forwardEnteredViaWarpThisTurn = false;
	boolean turnOpponentCharReturnedToHand = false;

	boolean nonLethalProtection = false;
	boolean dmgReductionDisabled = false;
	int globalDmgReduction = 0;

	/** Max attack declarations this player may make this turn; {@code Integer.MAX_VALUE} = unlimited. */
	int attackDeclarationLimit = Integer.MAX_VALUE;

	/** Attack declarations made by this player in the current turn (cleared at end of each turn). */
	int attackDeclarationsThisTurn = 0;

	/** Set when an ability forbids this player from searching this turn. */
	boolean cannotSearchThisTurn = false;

	/**
	 * Cards of this player's whose "for the first time in that turn" damage replacement has already
	 * been spent this turn — Edge 15-045H's "During each turn, if Edge is dealt damage by your
	 * opponent's Summons or abilities for the first time in that turn, the damage becomes 0
	 * instead."
	 *
	 * <p>Identity-based, not {@code equals}: {@link CardData} is a record, so two copies of Edge
	 * would share an entry and the second copy's shield would be spent by the first one's use.
	 *
	 * <p>Cleared at <em>every</em> turn boundary rather than only at this player's own, because
	 * "during each turn" means exactly that — the shield comes back on the opponent's turn too.
	 */
	final Set<CardData> firstOppEffectDamageZeroedThisTurn =
			java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

	/**
	 * Clears this player's cast tracking at a turn boundary.
	 *
	 * <p>Every "this turn" cast condition — Ace's "You can only cast up to 2 cards per turn",
	 * "if you have cast a Summon this turn", cost modifiers scaling by cards/jobs/names cast — is
	 * scoped to a single turn, and each player gets a fresh allowance on <em>every</em> turn, not
	 * just their own.  So this is called at both ends of the player's turn: when it finishes, so
	 * casts made on it do not eat into what they may cast while holding priority during the
	 * opponent's turn, and again when their next turn begins, so casts made during the opponent's
	 * turn do not eat into that turn.
	 *
	 * <p>Casting off-turn used to mean Summons only; Back Attack Characters made it routine.
	 */
	void resetCastTracking() {
		cardsCastThisTurn = 0;
		summonCastThisTurn = false;
		summonsCastThisTurn = 0;
		castJobsThisTurn.clear();
		castNamesThisTurn.clear();
		castCountByNameThisTurn.clear();
	}
}
