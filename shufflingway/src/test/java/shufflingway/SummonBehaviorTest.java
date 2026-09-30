package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static shufflingway.TestCards.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Behaviour tests for Summons, one section per card, against a real {@link MainWindow} wherever
 * the effect touches the board.
 *
 * <p>Sections run in serial order — set by set, then card number within a set — so a card's tests
 * are found by its serial, and a new set's Summons go on the end. Each section opens with a banner
 * naming the card and its printed text.
 *
 * <p>Summons resolve from P2's seat through {@link #castAsP2}: the CPU makes the choices, so each
 * board is built to leave it one legal target, with an illegal one beside it wherever the card
 * restricts what it may choose. A P1 choice opens a modal dialog and hangs the JVM, so an effect
 * that makes P1 choose is set up so P1 has only one answer, or answered through a spy.
 */
class SummonBehaviorTest {

	/** Resolves {@code summon}'s effect from P2's seat, as a cast would once it leaves the Stack. */
	private static void castAsP2(MainWindow mw, CardData summon) {
		Consumer<GameContext> fn = ActionResolver.parse(summon.summonEffect(), summon);
		assertNotNull(fn, summon.name() + " parses");
		fn.accept(mw.buildGameContext(false));
	}

	/**
	 * Resolves {@code summon}'s effect from P1's seat. Only for effects where P1 makes no choice —
	 * "your opponent selects …", which the CPU then answers.
	 */
	private static void castAsP1(MainWindow mw, CardData summon) {
		Consumer<GameContext> fn = ActionResolver.parse(summon.summonEffect(), summon);
		assertNotNull(fn, summon.name() + " parses");
		fn.accept(mw.buildGameContext(true));
	}

	/**
	 * {@link #castAsP2} for a "Select 1 (or up to N) of the M following actions" Summon, taking
	 * {@code options} in the order given.
	 */
	private static void castAsP2Selecting(MainWindow mw, CardData summon, int... options) {
		GameContext ctx = spy(mw.buildGameContext(false));
		doAnswer(inv -> {
			List<String> offered = inv.getArgument(1);
			List<String> taken = new ArrayList<>();
			for (int option : options) taken.add(offered.get(option));
			return taken;
		}).when(ctx).chooseActions(any(), anyList(), anyInt(), anyBoolean());
		Consumer<GameContext> fn = ActionResolver.parse(summon.summonEffect(), summon);
		assertNotNull(fn, summon.name() + " parses");
		fn.accept(ctx);
	}

	/** A mock whose 20-argument selection call answers with {@code targets}. */
	private static GameContext contextChoosing(List<ForwardTarget> targets) {
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(
				anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), anyInt(), any(), anyInt(), any(),
				anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
		)).thenReturn(targets);
		return ctx;
	}

	/** A Stack entry for an enters-the-field auto-ability of {@code from}, controlled by {@code isP1}. */
	private static StackEntry autoEntryFrom(CardData from, boolean isP1) {
		AutoAbility aa = CardData.parseAutoAbilities(
				"When " + from.name() + " enters the field, draw 1 card.").get(0);
		return new StackEntry(from, null, aa, isP1, 0, false, null, false, false, 0, 0);
	}

	private static int damageOn(MainWindow mw, CardData p1Forward) {
		return mw.p1ForwardDamage.get(mw.p1ForwardCards.indexOf(p1Forward));
	}

	private static void dullP1Forward(MainWindow mw, CardData card) {
		mw.p1ForwardStates.set(mw.p1ForwardCards.indexOf(card), CardState.DULL);
	}

	private static void dullP2Forward(MainWindow mw, CardData card) {
		mw.p2ForwardStates.set(mw.p2ForwardCards.indexOf(card), CardState.DULL);
	}

	private static boolean p1BackupOnField(MainWindow mw, CardData card) {
		return Arrays.asList(mw.p1BackupCards).contains(card);
	}

	private static void fillP2Deck(MainWindow mw, int count) {
		for (int i = 0; i < count; i++) mw.gameState.getP2MainDeck().add(makeForward("Deck " + i, "Water", 2, 5000));
	}

	// =========================================================================================
	// 1-004C Ifrit: "EX BURST Choose 1 Forward. Deal it 4000 damage."
	// =========================================================================================

	private static final String IFRIT_1_004C = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 4000 damage.";

	@Test
	void ifritDeals4000DamageToTheChosenForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 1, IFRIT_1_004C));
		assertEquals(4000, damageOn(mw, theirs));
	}

	@Test
	void ifritBreaksAForwardOf4000Power() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 2, 4000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 1, IFRIT_1_004C));
		assertFalse(mw.p1ForwardCards.contains(theirs));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 1-018L Bahamut: "Choose up to 2 Forwards opponent controls. Deal them 10000 damage. If they are
	// put from the field into the Break Zone this turn, remove them from the game instead."
	// =========================================================================================

	private static final String BAHAMUT_1_018L = "Choose up to 2 Forwards opponent controls. Deal them 10000 "
			+ "damage. If they are put from the field into the Break Zone this turn, remove them from the game instead.";

	@Test
	void bahamutRemovesTheTwoForwardsItBreaksFromTheGame() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Water", 4, 9000);
		CardData b = makeForward("B", "Ice", 4, 8000);
		CardData mine = makeForward("Mine", "Fire", 4, 9000);
		placeP1Forward(mw, a);
		placeP1Forward(mw, b);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 9, BAHAMUT_1_018L));

		assertTrue(mw.p1ForwardCards.isEmpty());
		assertTrue(mw.gameState.getP1RemovedFromGame().containsAll(List.of(a, b)), "removed, not broken");
		assertFalse(mw.gameState.getP1BreakZone().contains(a));
		assertFalse(mw.gameState.getP1BreakZone().contains(b));
		assertEquals(0, mw.p2ForwardDamage.get(0), "opponent's Forwards only");
	}

	@Test
	void aForwardThatSurvivesBahamutIsStillRemovedIfBrokenLaterThisTurn() {
		MainWindow mw = new MainWindow();
		CardData big = makeForward("Big", "Water", 6, 12000);
		placeP1Forward(mw, big);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 9, BAHAMUT_1_018L));
		assertEquals(10000, damageOn(mw, big));

		mw.buildGameContext(false).breakP1Forward(0);
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(big));
		assertFalse(mw.gameState.getP1BreakZone().contains(big));
	}

	// =========================================================================================
	// 1-023R Brynhildr: "EX BURST Choose 1 Forward. Deal it 7000 damage."
	// =========================================================================================

	private static final String BRYNHILDR_1_023R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 7000 damage.";

	@Test
	void brynhildrDeals7000Damage() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Brynhildr", "Fire", 3, BRYNHILDR_1_023R));
		assertEquals(7000, damageOn(mw, theirs));
	}

	@Test
	void brynhildrBreaksAForwardOf7000Power() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Brynhildr", "Fire", 3, BRYNHILDR_1_023R));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 1-038R Shiva: "EX BURST Choose 1 Forward. Dull it and Freeze it."
	// =========================================================================================

	private static final String SHIVA_1_038R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Dull it and Freeze it.";

	@Test
	void shivaDullsAndFreezesTheChosenForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Shiva", "Ice", 3, SHIVA_1_038R));
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.p1ForwardFrozen.get(0));
	}

	// =========================================================================================
	// 1-039C Shiva: "EX BURST Choose 1 dull Forward. Deal it 5000 damage."
	// =========================================================================================

	private static final String SHIVA_1_039C = "[[ex]]EX BURST[[/]] Choose 1 dull Forward. Deal it 5000 damage.";

	@Test
	void shivaDeals5000DamageToADullForwardOnly() {
		// The active one is the costlier kill, so ignoring "dull" would pick it.
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 6, 5000);
		CardData dull   = makeForward("Dull", "Wind", 2, 5000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Shiva", "Ice", 1, SHIVA_1_039C));

		assertTrue(mw.gameState.getP1BreakZone().contains(dull));
		assertEquals(0, damageOn(mw, active), "an active Forward cannot be chosen");
	}

	@Test
	void shivaDoesNothingWithOnlyAnActiveForward() {
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 3, 5000);
		placeP1Forward(mw, active);
		castAsP2(mw, makeSummon("Shiva", "Ice", 1, SHIVA_1_039C));
		assertEquals(0, damageOn(mw, active));
	}

	// =========================================================================================
	// 1-052R Hades: "Choose 1 Forward and 1 Backup opponent controls. Dull the Forward and return the
	// Backup to its owner's hand. Your opponent discards 1 card from his/her hand."
	//
	// One action per chosen type, which ChooseTwoMixedTypes (one action for both groups) cannot
	// read. ChooseCharacter took it instead: it chose only a Forward, left the rest "not yet
	// implemented", and returned a card *named* "the Backup" to hand.
	// =========================================================================================

	private static final String HADES_1_052R = "Choose 1 Forward and 1 Backup opponent controls. Dull the Forward "
			+ "and return the Backup to its owner's hand. Your opponent discards 1 card from his/her hand.";

	@Test
	void hadesDullsTheForwardReturnsTheBackupThenTheOpponentDiscards() {
		// P1's hand is empty, so the returned Backup is the one card there to discard, and P1's
		// forced discard needs no answer.
		MainWindow mw = new MainWindow();
		CardData forward = makeForward("Theirs", "Water", 4, 8000);
		CardData backup  = makeBackup("Their Backup", "Water", 3);
		CardData myBackup = makeBackup("My Backup", "Ice", 3);
		placeP1Forward(mw, forward);
		placeP1Backup(mw, backup);
		placeP2Backup(mw, myBackup);
		castAsP2(mw, makeSummon("Hades", "Ice", 5, HADES_1_052R));

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertFalse(p1BackupOnField(mw, backup));
		assertTrue(mw.gameState.getP1BreakZone().contains(backup), "returned to hand, then discarded");
		assertTrue(mw.gameState.getP1Hand().isEmpty());
		assertSame(myBackup, mw.p2BackupCards[0], "opponent's Backup only");
	}

	@Test
	void hadesSplitChoiceIsNamedForItsOwnParser() {
		assertEquals("ChooseTypeAndTypeSplitActions", ActionResolver.matchedPatternName(HADES_1_052R, null));
	}

	@Test
	void anUnreadableTrailingSentenceLeavesTheSplitChoiceUnclaimed() {
		// Fail closed: the choice is not claimed at the cost of its last sentence.
		assertNull(ActionResolverChoose.tryParseChooseTypeAndTypeSplitActions(
				"Choose 1 Forward and 1 Backup opponent controls. Dull the Forward and return the Backup "
				+ "to its owner's hand. Your opponent frobnicates.", null));
	}

	// =========================================================================================
	// 1-061R Alexander: "EX BURST Choose 1 Forward of cost 5 or more. Break it."
	// =========================================================================================

	private static final String ALEXANDER_1_061R = "[[ex]]EX BURST[[/]] Choose 1 Forward of cost 5 or more. Break it.";

	@Test
	void alexanderBreaksAForwardOfCost5OrMoreOnly() {
		// The cost-4 one is the bigger threat, so ignoring the cost would pick it.
		MainWindow mw = new MainWindow();
		CardData five = makeForward("Five", "Water", 5, 5000);
		CardData four = makeForward("Four", "Water", 4, 12000);
		placeP1Forward(mw, five);
		placeP1Forward(mw, four);
		castAsP2(mw, makeSummon("Alexander", "Wind", 4, ALEXANDER_1_061R));

		assertTrue(mw.gameState.getP1BreakZone().contains(five));
		assertTrue(mw.p1ForwardCards.contains(four), "cost 4 cannot be chosen");
	}

	@Test
	void alexanderDoesNothingWithOnlyACost4Forward() {
		MainWindow mw = new MainWindow();
		CardData four = makeForward("Four", "Water", 4, 8000);
		placeP1Forward(mw, four);
		castAsP2(mw, makeSummon("Alexander", "Wind", 4, ALEXANDER_1_061R));
		assertTrue(mw.p1ForwardCards.contains(four));
	}

	// =========================================================================================
	// 1-062L Valefor: "Return all Forwards to their owners' hands."
	// =========================================================================================

	private static final String VALEFOR_1_062L = "Return all Forwards to their owners' hands.";

	@Test
	void valeforReturnsEveryForwardToItsOwnersHand() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		CardData mine   = makeForward("Mine", "Wind", 3, 7000);
		CardData backup = makeBackup("Their Backup", "Water", 2);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, mine);
		placeP1Backup(mw, backup);
		castAsP2(mw, makeSummon("Valefor", "Wind", 5, VALEFOR_1_062L));

		assertTrue(mw.p1ForwardCards.isEmpty());
		assertTrue(mw.p2ForwardCards.isEmpty());
		assertTrue(mw.gameState.getP1Hand().contains(theirs));
		assertTrue(mw.gameState.getP2Hand().contains(mine));
		assertTrue(p1BackupOnField(mw, backup), "Forwards only");
	}

	// =========================================================================================
	// 1-074R Sylph: "EX BURST Choose 1 Forward. Activate it. All the Forwards you control gain +1000
	// power until the end of the turn."
	// =========================================================================================

	private static final String SYLPH_1_074R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Activate it. All the "
			+ "Forwards you control gain +1000 power until the end of the turn.";

	@Test
	void sylphActivatesOneForwardAndBoostsAllOfYours() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Wind", 3, 5000);
		CardData b = makeForward("B", "Wind", 3, 6000);
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP2Forward(mw, a);
		placeP2Forward(mw, b);
		placeP1Forward(mw, theirs);
		dullP2Forward(mw, a);
		dullP2Forward(mw, b);
		castAsP2(mw, makeSummon("Sylph", "Wind", 1, SYLPH_1_074R));

		long active = mw.p2ForwardStates.stream().filter(s -> s == CardState.ACTIVE).count();
		assertEquals(1, active, "one Forward is activated");
		assertEquals(6000, mw.effectiveP2ForwardPower(0));
		assertEquals(7000, mw.effectiveP2ForwardPower(1));
		assertEquals(7000, mw.effectiveP1ForwardPower(0), "yours only");
	}

	// =========================================================================================
	// 1-106C Golem: "EX BURST Choose 1 Forward. It gains +2000 power until the end of the turn. If it
	// is blocking, it gains +4000 power until the end of the turn instead."
	// =========================================================================================

	private static final String GOLEM_1_106C = "[[ex]]EX BURST[[/]] Choose 1 Forward. It gains +2000 power until "
			+ "the end of the turn. If it is blocking, it gains +4000 power until the end of the turn instead.";

	@Test
	void golemGrants2000Power() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 5000));
		castAsP2(mw, makeSummon("Golem", "Earth", 1, GOLEM_1_106C));
		assertEquals(7000, mw.effectiveP2ForwardPower(0));
	}

	@Test
	void golemGrants4000InsteadToABlockingForward() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 5000));
		mw.p2BlockingIdx = 0;
		castAsP2(mw, makeSummon("Golem", "Earth", 1, GOLEM_1_106C));
		assertEquals(9000, mw.effectiveP2ForwardPower(0), "+4000 instead, not as well");
	}

	// =========================================================================================
	// 1-110C Titan: "Choose 1 Forward you control. Dull it. It cannot be broken this turn."
	// =========================================================================================

	private static final String TITAN_1_110C = "Choose 1 Forward you control. Dull it. It cannot be broken this turn.";

	@Test
	void titanDullsYourForwardAndItCannotBeBrokenThisTurn() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Earth", 3, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Titan", "Earth", 2, TITAN_1_110C));

		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0));
		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0), "yours only");
		mw.buildGameContext(false).breakP2Forward(0);
		assertTrue(mw.p2ForwardCards.contains(mine), "it cannot be broken");
	}

	@Test
	void titanDoesNothingToAForwardTheOpponentControls() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Titan", "Earth", 2, TITAN_1_110C));
		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0));
		mw.buildGameContext(false).breakP1Forward(0);
		assertFalse(mw.p1ForwardCards.contains(theirs), "no protection either");
	}

	// =========================================================================================
	// 1-117R Hecatoncheir: "Choose 1 Backup of cost 3 or more. Break it."
	// =========================================================================================

	private static final String HECATONCHEIR_1_117R = "Choose 1 Backup of cost 3 or more. Break it.";

	@Test
	void hecatoncheirBreaksABackupOfCost3OrMoreOnly() {
		MainWindow mw = new MainWindow();
		CardData three = makeBackup("Three", "Water", 3);
		CardData two   = makeBackup("Two", "Water", 2);
		placeP1Backup(mw, three);
		placeP1Backup(mw, two);
		castAsP2(mw, makeSummon("Hecatoncheir", "Earth", 3, HECATONCHEIR_1_117R));

		assertTrue(mw.gameState.getP1BreakZone().contains(three));
		assertTrue(p1BackupOnField(mw, two), "cost 2 cannot be chosen");
	}

	@Test
	void hecatoncheirDoesNothingWithOnlyACost2Backup() {
		MainWindow mw = new MainWindow();
		CardData two = makeBackup("Two", "Water", 2);
		placeP1Backup(mw, two);
		castAsP2(mw, makeSummon("Hecatoncheir", "Earth", 3, HECATONCHEIR_1_117R));
		assertTrue(p1BackupOnField(mw, two));
	}

	// =========================================================================================
	// 1-123R Odin: "Choose 1 Forward of cost 4 or less. Break it."
	// =========================================================================================

	private static final String ODIN_1_123R = "Choose 1 Forward of cost 4 or less. Break it.";

	@Test
	void odinBreaksAForwardOfCost4OrLessOnly() {
		// The cost-5 one is the bigger threat, so ignoring the cost would pick it.
		MainWindow mw = new MainWindow();
		CardData four = makeForward("Four", "Water", 4, 5000);
		CardData five = makeForward("Five", "Water", 5, 12000);
		placeP1Forward(mw, four);
		placeP1Forward(mw, five);
		castAsP2(mw, makeSummon("Odin", "Lightning", 4, ODIN_1_123R));

		assertTrue(mw.gameState.getP1BreakZone().contains(four));
		assertTrue(mw.p1ForwardCards.contains(five), "cost 5 cannot be chosen");
	}

	@Test
	void odinDoesNothingWithOnlyACost5Forward() {
		MainWindow mw = new MainWindow();
		CardData five = makeForward("Five", "Water", 5, 9000);
		placeP1Forward(mw, five);
		castAsP2(mw, makeSummon("Odin", "Lightning", 4, ODIN_1_123R));
		assertTrue(mw.p1ForwardCards.contains(five));
	}

	// =========================================================================================
	// 1-124R Odin: "EX BURST Choose 1 Forward. Break it."
	// =========================================================================================

	private static final String ODIN_1_124R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Break it.";

	@Test
	void odinBreaksAnyForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 9, 12000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Odin", "Lightning", 7, ODIN_1_124R));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 1-143C Ramuh: "EX BURST Choose 1 active Forward. Deal it 5000 damage."
	// =========================================================================================

	private static final String RAMUH_1_143C = "[[ex]]EX BURST[[/]] Choose 1 active Forward. Deal it 5000 damage.";

	@Test
	void ramuhDeals5000DamageToAnActiveForwardOnly() {
		// The dull one is the costlier kill, so ignoring "active" would pick it.
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 2, 5000);
		CardData dull   = makeForward("Dull", "Wind", 6, 5000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_1_143C));

		assertTrue(mw.gameState.getP1BreakZone().contains(active));
		assertEquals(0, damageOn(mw, dull), "a dull Forward cannot be chosen");
	}

	@Test
	void ramuhDoesNothingWithOnlyADullForward() {
		MainWindow mw = new MainWindow();
		CardData dull = makeForward("Dull", "Wind", 3, 5000);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_1_143C));
		assertEquals(0, damageOn(mw, dull));
	}

	// =========================================================================================
	// 1-170C Fairy: "EX BURST Choose 1 Forward. Activate it. Draw 1 card."
	// =========================================================================================

	private static final String FAIRY_1_170C = "[[ex]]EX BURST[[/]] Choose 1 Forward. Activate it. Draw 1 card.";

	@Test
	void fairyActivatesTheForwardAndDraws() {
		MainWindow mw = new MainWindow();
		CardData mine = makeForward("Mine", "Water", 3, 7000);
		placeP2Forward(mw, mine);
		dullP2Forward(mw, mine);
		fillP2Deck(mw, 3);
		castAsP2(mw, makeSummon("Fairy", "Water", 2, FAIRY_1_170C));

		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0));
		assertEquals(1, mw.gameState.getP2Hand().size());
		assertEquals(2, mw.gameState.getP2MainDeck().size());
	}

	// =========================================================================================
	// 1-172C Moogle: "EX BURST Draw 2 cards, then discard 1 card from your hand."
	// =========================================================================================

	private static final String MOOGLE_1_172C = "[[ex]]EX BURST[[/]] Draw 2 cards, then discard 1 card from your hand.";

	@Test
	void moogleDrawsTwoThenDiscardsOne() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 3);
		castAsP2(mw, makeSummon("Moogle", "Water", 1, MOOGLE_1_172C));

		assertEquals(1, mw.gameState.getP2MainDeck().size(), "two drawn");
		assertEquals(1, mw.gameState.getP2Hand().size(), "one kept");
		assertEquals(1, mw.gameState.getP2BreakZone().size(), "one discarded");
	}

	// =========================================================================================
	// 1-178R Leviathan: "EX BURST Choose 1 Forward. Return it to its owner's hand."
	// =========================================================================================

	private static final String LEVIATHAN_1_178R = "[[ex]]EX BURST [[/]]Choose 1 Forward. Return it to its owner's hand.";

	@Test
	void leviathanReturnsTheForwardToItsOwnersHand() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Leviathan", "Water", 3, LEVIATHAN_1_178R));

		assertTrue(mw.p1ForwardCards.isEmpty());
		assertTrue(mw.gameState.getP1Hand().contains(theirs), "its owner's hand, not the caster's");
		assertTrue(mw.gameState.getP2Hand().isEmpty());
	}

	// =========================================================================================
	// 1-190S Bahamut Fury: "EX BURST Choose 1 Forward. You may discard 1 card from your hand. If you
	// do so, deal it 7000 damage. If not, deal it 5000 damage."
	//
	// The CPU takes every optional discard it can afford (GameContextImpl.offerOptionalDiscard).
	// =========================================================================================

	private static final String BAHAMUT_FURY_1_190S = "[[ex]]EX BURST[[/]] Choose 1 Forward. You may discard 1 card "
			+ "from your hand. If you do so, deal it 7000 damage. If not, deal it 5000 damage.";

	@Test
	void bahamutFuryDeals7000AfterADiscard() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		CardData spare  = makeForward("Spare", "Fire", 2, 5000);
		placeP1Forward(mw, theirs);
		mw.gameState.getP2Hand().add(spare);
		castAsP2(mw, makeSummon("Bahamut Fury", "Fire", 2, BAHAMUT_FURY_1_190S));

		assertTrue(mw.gameState.getP2BreakZone().contains(spare), "the discard");
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "7000 breaks a 7000 Forward");
	}

	@Test
	void bahamutFuryDeals5000WithNothingToDiscard() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Bahamut Fury", "Fire", 2, BAHAMUT_FURY_1_190S));
		assertEquals(5000, damageOn(mw, theirs));
	}

	/**
	 * "1 card" is any card, and must go through the by-type route rather than the by-name one —
	 * asking for a card named "card" would find nothing and silently take the "if not" branch every
	 * time.
	 */
	@Test
	void bahamutFuryOffersAnyCardRatherThanACardNamedCard() {
		GameContext ctx = contextChoosing(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));
		CardData bahamutFury = makeSummon("Bahamut Fury", "Fire", 2, BAHAMUT_FURY_1_190S);
		ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);

		ActionResolver.parse(bahamutFury.summonEffect(), bahamutFury).accept(ctx);

		verify(ctx).mayDiscardCardOfTypeFromHandOrElse(type.capture(), any(), any());
		assertEquals("card", type.getValue(), "the CardFilters vocabulary for 'any card'");
		verify(ctx, never()).mayDiscardCardNameFromHandOrElse(any(), any(), any());
	}


	// =========================================================================================
	// 1-198S Valefor: "EX BURST Deal 3000 damage to all the Forwards opponent controls. If you control
	// Card Name Yuna, activate all the Backups you control."
	// =========================================================================================

	private static final String VALEFOR_1_198S = "[[ex]]EX BURST[[/]] Deal 3000 damage to all the Forwards opponent "
			+ "controls. If you control Card Name Yuna, activate all the Backups you control.";

	/** P2's two dull Backups and one Forward, P1's two Forwards; {@code withYuna} adds P2's Yuna. */
	private static MainWindow castValefor198(boolean withYuna, CardData small, CardData large, CardData mine) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, small);
		placeP1Forward(mw, large);
		placeP2Forward(mw, mine);
		if (withYuna) placeP2Forward(mw, makeForward("Yuna", "Wind", 3, 7000));
		placeP2Backup(mw, makeBackup("Backup A", "Wind", 2));
		placeP2Backup(mw, makeBackup("Backup B", "Wind", 3));
		mw.p2BackupStates[0] = CardState.DULL;
		mw.p2BackupStates[1] = CardState.DULL;
		castAsP2(mw, makeSummon("Valefor", "Wind", 2, VALEFOR_1_198S));
		return mw;
	}

	@Test
	void valeforDeals3000ToEveryForwardTheOpponentControls() {
		CardData small = makeForward("Small", "Water", 2, 3000);
		CardData large = makeForward("Large", "Water", 4, 8000);
		CardData mine  = makeForward("Mine", "Wind", 2, 3000);
		MainWindow mw = castValefor198(false, small, large, mine);

		assertTrue(mw.gameState.getP1BreakZone().contains(small));
		assertEquals(3000, damageOn(mw, large));
		assertEquals(0, mw.p2ForwardDamage.get(0), "opponent's Forwards only");
		assertEquals(CardState.DULL, mw.p2BackupStates[0], "no Yuna, no activation");
		assertEquals(CardState.DULL, mw.p2BackupStates[1]);
	}

	@Test
	void valeforActivatesYourBackupsIfYouControlYuna() {
		MainWindow mw = castValefor198(true, makeForward("Small", "Water", 2, 3000),
				makeForward("Large", "Water", 4, 8000), makeForward("Mine", "Wind", 2, 3000));
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[1]);
	}

	// =========================================================================================
	// 2-002C Ifrit: "EX BURST Choose 1 Forward. Deal it 6000 damage."
	// =========================================================================================

	private static final String IFRIT_2_002C = "[[ex]]EX BURST [[/]]Choose 1 Forward. Deal it 6000 damage.";

	@Test
	void ifritDeals6000DamageToTheChosenForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 2, IFRIT_2_002C));
		assertEquals(6000, damageOn(mw, theirs));
	}

	@Test
	void ifritBreaksAForwardOf6000Power() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 6000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 2, IFRIT_2_002C));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 2-019R Belias, the Gigas: "EX BURST Choose 1 Forward. Until the end of the turn, it gains +1000
	// power, Haste and First Strike. Draw 1 card."
	// =========================================================================================

	private static final String BELIAS_2_019R = "[[ex]]EX BURST [[/]]Choose 1 Forward. Until the end of the turn, "
			+ "it gains +1000 power, Haste and First Strike. Draw 1 card.";

	@Test
	void beliasGrantsPowerHasteAndFirstStrikeThenDraws() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Fire", 3, 7000));
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Belias, the Gigas", "Fire", 2, BELIAS_2_019R));

		assertEquals(8000, mw.effectiveP2ForwardPower(0));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.HASTE));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.FIRST_STRIKE));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 2-044R Mateus, the Corrupt: "EX BURST Opponent puts 1 attacking Forward into the Break Zone."
	//
	// The opponent selects, so it is cast from P1's seat and the CPU answers. The CPU gives up its
	// cheapest eligible Forward, which makes the cheaper idle one the decoy.
	// =========================================================================================

	private static final String MATEUS_2_044R = "[[ex]]EX BURST [[/]]Opponent puts 1 attacking Forward into the Break Zone.";

	@Test
	void mateusMakesTheOpponentPutAnAttackingForwardIntoTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData idle      = makeForward("Idle", "Fire", 2, 5000);
		CardData attacking = makeForward("Attacking", "Fire", 5, 9000);
		placeP2Forward(mw, idle);
		placeP2Forward(mw, attacking);
		mw.p2DeclaredAttackers.add(attacking);
		castAsP1(mw, makeSummon("Mateus, the Corrupt", "Ice", 5, MATEUS_2_044R));

		assertTrue(mw.gameState.getP2BreakZone().contains(attacking));
		assertTrue(mw.p2ForwardCards.contains(idle), "only an attacking Forward");
	}

	@Test
	void mateusDoesNothingWhenNoForwardIsAttacking() {
		MainWindow mw = new MainWindow();
		CardData idle = makeForward("Idle", "Fire", 2, 5000);
		placeP2Forward(mw, idle);
		castAsP1(mw, makeSummon("Mateus, the Corrupt", "Ice", 5, MATEUS_2_044R));
		assertTrue(mw.p2ForwardCards.contains(idle));
	}

	// =========================================================================================
	// 2-045C Moomba: "EX BURST Choose 1 Forward. Deal it damage equal to its power minus 1000."
	// =========================================================================================

	private static final String MOOMBA_2_045C = "[[ex]]EX BURST [[/]]Choose 1 Forward. Deal it damage equal to its "
			+ "power minus 1000.";

	@Test
	void moombaDealsDamageEqualToTheForwardsPowerMinus1000() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Moomba", "Ice", 2, MOOMBA_2_045C));
		assertEquals(8000, damageOn(mw, theirs));
		assertTrue(mw.p1ForwardCards.contains(theirs), "1000 short of breaking it");
	}

	// =========================================================================================
	// 2-049H Asura: "Select 1 of the 3 following actions. 'Choose up to 2 Forwards. Activate them.'
	// 'Choose up to 5 Backups. Activate them.' 'Choose 1 Character card of cost 2 or less into your
	// Break Zone. Add it to your hand.'"
	//
	// "into" is a misprint for "in", corrected on the Re-059H reprint — which parsed while the
	// original did not. Accepted in the zone slot, which sits between the target descriptor and the
	// followup separator: a card that puts something *into* the Break Zone says so after that
	// separator, out of this group's reach.
	//
	// The CPU takes the first action, so each test picks its action through a spy.
	// =========================================================================================

	private static final String ASURA_2_049H = "Select 1 of the 3 following actions.[[br]]\"Choose up to 2 Forwards. "
			+ "Activate them.\"[[br]]\"Choose up to 5 Backups. Activate them.\"[[br]]\"Choose 1 Character card of "
			+ "cost 2 or less into your Break Zone. Add it to your hand.\"";

	private static CardData asura() {
		return makeSummon("Asura", "Wind", 1, ASURA_2_049H);
	}

	@Test
	void asurasFirstActionActivatesUpToTwoForwards() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Wind", 3, 7000);
		CardData b = makeForward("B", "Wind", 3, 7000);
		placeP2Forward(mw, a);
		placeP2Forward(mw, b);
		dullP2Forward(mw, a);
		dullP2Forward(mw, b);
		castAsP2Selecting(mw, asura(), 0);

		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0));
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(1));
	}

	@Test
	void asurasSecondActionActivatesUpToFiveBackups() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 5; i++) {
			placeP2Backup(mw, makeBackup("Backup " + i, "Wind", 2));
			mw.p2BackupStates[i] = CardState.DULL;
		}
		castAsP2Selecting(mw, asura(), 1);
		for (int i = 0; i < 5; i++) assertEquals(CardState.ACTIVE, mw.p2BackupStates[i], "Backup " + i);
	}

	@Test
	void asurasThirdActionReturnsACheapCharacterFromTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData cheap  = makeForward("Cheap", "Wind", 2, 5000);
		CardData costly = makeForward("Costly", "Wind", 3, 7000);
		mw.gameState.getP2BreakZone().add(costly);
		mw.gameState.getP2BreakZone().add(cheap);
		castAsP2Selecting(mw, asura(), 2);

		assertTrue(mw.gameState.getP2Hand().contains(cheap));
		assertTrue(mw.gameState.getP2BreakZone().contains(costly), "cost 3 cannot be chosen");
	}

	private static final String ASURA_OPTION =
			"Choose 1 Character card of cost 2 or less into your Break Zone. Add it to your hand.";

	@Test
	void asurasMisprintReadsAsItsReprintDoes() {
		assertEquals("ChooseCharacter / AddToHand",
				ActionResolver.fullDescription(ASURA_OPTION, null));
		assertEquals("ChooseCharacter / AddToHand",
				ActionResolver.fullDescription(
						"Choose 1 Character of cost 2 or less in your Break Zone. Add it to your hand.",
						null));
	}

	@Test
	void asuraNamesAllThreeOfItsOptions() {
		String summon = "Select 1 of the 3 following actions. "
				+ "\"Choose up to 2 Forwards. Activate them.\" "
				+ "\"Choose up to 5 Backups. Activate them.\" "
				+ "\"" + ASURA_OPTION + "\"";
		assertEquals("SelectFollowingActions(1 of 3: ChooseCharacter / Activate "
						+ "| ChooseCharacter / Activate | ChooseCharacter / AddToHand)",
				ActionResolver.fullDescription(summon, null));
	}

	// =========================================================================================
	// 2-070R Shemhazai, the Whisperer: "Each Forward can only be blocked by a Forward with a cost
	// inferior or equal to its own this turn."
	// =========================================================================================

	private static final String SHEMHAZAI_2_070R = "Each Forward can only be blocked by a Forward with a cost "
			+ "inferior or equal to its own this turn.";

	@Test
	void shemhazaiStopsCostlierForwardsFromBlocking() {
		MainWindow mw = new MainWindow();
		CardData attacker = makeForward("Attacker", "Fire", 3, 7000);
		placeP1Forward(mw, attacker);
		assertFalse(mw.p1AttackerCostFiltersExclude(attacker, 4), "no restriction before the cast");

		castAsP2(mw, makeSummon("Shemhazai, the Whisperer", "Wind", 1, SHEMHAZAI_2_070R));
		assertTrue(mw.p1AttackerCostFiltersExclude(attacker, 4), "cost 4 cannot block a cost 3");
		assertFalse(mw.p1AttackerCostFiltersExclude(attacker, 3), "equal cost can");
		assertFalse(mw.p1AttackerCostFiltersExclude(attacker, 1), "lower cost can");
	}

	// =========================================================================================
	// 2-080C Carbuncle: "Choose 1 Summon targeting a Character you control. Cancel its effect."
	// =========================================================================================

	private static final String CARBUNCLE_2_080C = "Choose 1 Summon targeting a Character you control. Cancel its effect.";

	/** P1's damage Summon on the Stack, already aimed at {@code target}. */
	private static StackEntry p1SummonAimedAt(MainWindow mw, ForwardTarget target) {
		CardData damage = makeSummon("Thunder", "Lightning", 2, "Choose 1 Forward. Deal it 5000 damage.");
		mw.pushSummonOnStack(damage, true, 0, 0, false, List.of(target), true);
		return mw.gameState.getStack().get(0);
	}

	@Test
	void carbuncleCancelsASummonTargetingYourForward() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 7000));
		StackEntry entry = p1SummonAimedAt(mw, new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD));
		castAsP2(mw, makeSummon("Carbuncle", "Earth", 3, CARBUNCLE_2_080C));
		assertTrue(mw.cancelledStackEntries.contains(entry));
	}

	@Test
	void carbuncleLeavesASummonTargetingTheOpponentsForward() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 7000));
		placeP1Forward(mw, makeForward("Theirs", "Fire", 3, 7000));
		StackEntry entry = p1SummonAimedAt(mw, new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));
		castAsP2(mw, makeSummon("Carbuncle", "Earth", 3, CARBUNCLE_2_080C));
		assertFalse(mw.cancelledStackEntries.contains(entry));
	}

	// =========================================================================================
	// 2-087R Hashmal, Bringer of Order: "Name 1 Job or 1 Element. Until the end of the turn, all
	// Forwards you control gain +1000 power and the named Job or Element."
	//
	// It parsed, and the power landed, but the rest did nothing. The Job went to a per-slot row
	// nothing read, so a Forward that gained Warrior matched no Job Warrior filter. The Element
	// was written as an override ("becomes"), so a Fire Forward that gained Ice was Ice alone.
	// =========================================================================================

	private static final String HASHMAL_2_087R = "Name 1 Job or 1 Element. Until the end of the turn, "
			+ "all Forwards you control gain +1000 power and the named Job or Element.";

	/** P2's board with two Forwards, Hashmal resolved with {@code named} as the answer to the prompt. */
	private static MainWindow resolveHashmal(String kind, String named, CardData... forwards) {
		MainWindow mw = new MainWindow();
		for (CardData f : forwards) placeP2Forward(mw, f);
		placeP1Forward(mw, makeForward("Theirs", "Fire", 3, 7000));
		GameContext ctx = spy(mw.buildGameContext(false));
		doReturn(new String[] { kind, named }).when(ctx).selectJobOrElement(anyString());
		Consumer<GameContext> fn = ActionResolver.parse(HASHMAL_2_087R,
				makeSummon("Hashmal, Bringer of Order", "Earth", 5, HASHMAL_2_087R));
		assertNotNull(fn);
		fn.accept(ctx);
		return mw;
	}

	@Test
	void hashmalGrantsTheNamedJobToEveryForwardYouControl() {
		CardData a = makeForward("A", "Fire", 3, 7000);
		CardData b = makeForward("B", "Water", 2, 5000);
		MainWindow mw = resolveHashmal("job", "Warrior", a, b);

		assertTrue(mw.meetsJobFilterEffective(a, "Warrior"));
		assertTrue(mw.meetsJobFilterEffective(b, "Warrior"));
		assertTrue(mw.meetsJobFilterEffective(a, "Knight|Warrior"), "as one of several alternatives");
		assertFalse(mw.meetsJobFilterEffective(mw.p1ForwardCards.get(0), "Warrior"), "yours only");
		assertEquals(8000, mw.effectiveP2ForwardPower(0));
		assertEquals(6000, mw.effectiveP2ForwardPower(1));
	}

	@Test
	void hashmalsElementIsGainedAlongsideTheForwardsOwn() {
		CardData a = makeForward("A", "Fire", 3, 7000);
		MainWindow mw = resolveHashmal("element", "Ice", a);

		assertTrue(mw.effectiveContainsElement(a, "Ice"));
		assertTrue(mw.effectiveContainsElement(a, "Fire"), "gain, not become");
		assertEquals(List.of("Fire", "Ice"), mw.effectiveElements(a));
		assertTrue(mw.effectiveContainsElement(a, "Multi-Element"));
	}

	@Test
	void hashmalsGrantsEndAtTheEndOfTheTurn() {
		CardData a = makeForward("A", "Fire", 3, 7000);
		MainWindow mw = resolveHashmal("element", "Ice", a);
		mw.fireEndOfTurnEffects(false);
		assertFalse(mw.effectiveContainsElement(a, "Ice"));
		assertEquals(List.of("Fire"), mw.effectiveElements(a));
	}

	@Test
	void hashmalsElementReachesOnlyTheCopyOnTheField() {
		// CardData is a record: two copies of one printing are equal. The grant is by identity.
		CardData onField = makeForward("Twin", "Fire", 3, 7000);
		CardData inHand = makeForward("Twin", "Fire", 3, 7000);
		MainWindow mw = resolveHashmal("element", "Ice", onField);
		assertTrue(mw.effectiveContainsElement(onField, "Ice"));
		assertFalse(mw.effectiveContainsElement(inHand, "Ice"));
	}

	// =========================================================================================
	// 2-107C Cyclops: "EX BURST All Forwards opponent controls lose 3000 power until the end of the
	// turn."
	// =========================================================================================

	private static final String CYCLOPS_2_107C = "[[ex]]EX BURST [[/]]All Forwards opponent controls lose 3000 "
			+ "power until the end of the turn.";

	@Test
	void cyclopsTakes3000PowerFromEveryForwardTheOpponentControls() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("A", "Water", 3, 7000));
		placeP1Forward(mw, makeForward("B", "Fire", 5, 9000));
		placeP2Forward(mw, makeForward("Mine", "Lightning", 3, 7000));
		castAsP2(mw, makeSummon("Cyclops", "Lightning", 3, CYCLOPS_2_107C));

		assertEquals(4000, mw.effectiveP1ForwardPower(0));
		assertEquals(6000, mw.effectiveP1ForwardPower(1));
		assertEquals(7000, mw.effectiveP2ForwardPower(0), "opponent's Forwards only");
	}

	// =========================================================================================
	// 2-117R Adrammelech, the Wroth: "EX BURST Choose 1 active Forward. Deal it 7000 damage."
	// =========================================================================================

	private static final String ADRAMMELECH_2_117R = "[[ex]]EX BURST [[/]]Choose 1 active Forward. Deal it 7000 damage.";

	@Test
	void adrammelechDeals7000DamageToAnActiveForward() {
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 4, 7000);
		placeP1Forward(mw, active);
		castAsP2(mw, makeSummon("Adrammelech, the Wroth", "Lightning", 3, ADRAMMELECH_2_117R));
		assertTrue(mw.gameState.getP1BreakZone().contains(active));
	}

	@Test
	void adrammelechDoesNothingWithOnlyADullForward() {
		MainWindow mw = new MainWindow();
		CardData dull = makeForward("Dull", "Water", 4, 7000);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Adrammelech, the Wroth", "Lightning", 3, ADRAMMELECH_2_117R));
		assertEquals(0, damageOn(mw, dull));
	}

	// =========================================================================================
	// 2-133R Cúchulainn, the Impure: "EX BURST Choose 1 Forward opponent controls. It loses 1000
	// power for each dull Character opponent controls until the end of the turn. Draw 1 card."
	// =========================================================================================

	private static final String CUCHULAINN_2_133R = "[[ex]]EX BURST[[/]] Choose 1 Forward opponent controls. It loses "
			+ "1000 power for each dull Character opponent controls until the end of the turn. Draw 1 card.";

	@Test
	void cuchulainnTakes1000PowerPerDullCharacterTheOpponentControlsThenDraws() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Fire", 5, 9000));
		placeP1Backup(mw, makeBackup("Dull A", "Fire", 2));
		placeP1Backup(mw, makeBackup("Dull B", "Fire", 2));
		placeP1Backup(mw, makeBackup("Active", "Fire", 2));
		mw.p1BackupStates[0] = CardState.DULL;
		mw.p1BackupStates[1] = CardState.DULL;
		placeP2Backup(mw, makeBackup("My Dull", "Water", 2));
		mw.p2BackupStates[0] = CardState.DULL;
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Cúchulainn, the Impure", "Water", 4, CUCHULAINN_2_133R));

		assertEquals(7000, mw.effectiveP1ForwardPower(0), "two dull Characters: -2000, not counting yours");
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void cuchulainnTakesNothingWhenNothingIsDullButStillDraws() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Fire", 5, 9000));
		placeP1Backup(mw, makeBackup("Active", "Fire", 2));
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Cúchulainn, the Impure", "Water", 4, CUCHULAINN_2_133R));

		assertEquals(9000, mw.effectiveP1ForwardPower(0));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void aSelfSideStateCountIsLeftAloneRatherThanCountedWrong() {
		// GameContext counts a card state on the opponent's field only, so the self-side reading
		// would drop "dull" and count every Character. The branch declines it instead; the text
		// falls through to the plain reduce rather than silently over-counting.
		CardData cu = makeSummon("Cuchulainn, the Impure", "Water", 4, CUCHULAINN_2_133R);
		String selfSide = "Choose 1 Forward opponent controls. It loses 1000 power for each dull "
				+ "Character you control until the end of the turn.";
		assertNotEquals("ChooseCharacter / PowerReduceUntilForEach",
				ActionResolver.fullDescription(selfSide, cu),
				"no printing has this shape, and counting it as unconditioned would be wrong");
	}


	// =========================================================================================
	// 2-140C Leviathan: "EX BURST All Water Forwards gain +2000 power until the end of the turn."
	// =========================================================================================

	private static final String LEVIATHAN_2_140C = "[[ex]]EX BURST [[/]]All Water Forwards gain +2000 power until "
			+ "the end of the turn.";

	@Test
	void leviathanBoostsEveryWaterForwardOnBothSides() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("My Water", "Water", 3, 7000));
		placeP2Forward(mw, makeForward("My Fire", "Fire", 3, 7000));
		placeP1Forward(mw, makeForward("Their Water", "Water", 3, 5000));
		castAsP2(mw, makeSummon("Leviathan", "Water", 1, LEVIATHAN_2_140C));

		assertEquals(9000, mw.effectiveP2ForwardPower(0));
		assertEquals(7000, mw.effectiveP2ForwardPower(1), "Water only");
		assertEquals(7000, mw.effectiveP1ForwardPower(0), "all Water Forwards, the opponent's too");
	}

	// =========================================================================================
	// 3-002R Ifrit: "EX BURST Choose 1 Forward. Deal it 7000 damage. If you control a Job Class Zero
	// Cadet Forward, deal it 8000 damage instead."
	// =========================================================================================

	private static final String IFRIT_3_002R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 7000 damage. If you "
			+ "control a Job Class Zero Cadet Forward, deal it 8000 damage instead.";

	@Test
	void ifritDeals7000Damage() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 4, IFRIT_3_002R));
		assertEquals(7000, damageOn(mw, theirs));
	}

	@Test
	void ifritDeals8000InsteadWithAClassZeroCadet() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, makeForwardWithJob("Cadet", "Fire", 2, 5000, "Class Zero Cadet"));
		castAsP2(mw, makeSummon("Ifrit", "Fire", 4, IFRIT_3_002R));
		assertEquals(8000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 3-020H Phoenix: "Choose up to 1 Forward of cost 2 or less in your Break Zone. Play it onto the
	// field. Deal 2000 damage to all the Forwards opponent controls."
	// =========================================================================================

	private static final String PHOENIX_3_020H = "Choose up to 1 Forward of cost 2 or less in your Break Zone. "
			+ "Play it onto the field. Deal 2000 damage to all the Forwards opponent controls.";

	@Test
	void phoenixPlaysACheapForwardFromTheBreakZoneThenDamagesTheOpponentsForwards() {
		MainWindow mw = new MainWindow();
		CardData cheap  = makeForward("Cheap", "Fire", 2, 5000);
		CardData costly = makeForward("Costly", "Fire", 3, 7000);
		mw.gameState.getP2BreakZone().add(costly);
		mw.gameState.getP2BreakZone().add(cheap);
		CardData small = makeForward("Small", "Water", 1, 2000);
		CardData large = makeForward("Large", "Water", 4, 8000);
		placeP1Forward(mw, small);
		placeP1Forward(mw, large);
		castAsP2(mw, makeSummon("Phoenix", "Fire", 4, PHOENIX_3_020H));

		assertTrue(mw.p2ForwardCards.contains(cheap), "played onto the field");
		assertTrue(mw.gameState.getP2BreakZone().contains(costly), "cost 3 cannot be chosen");
		assertEquals(0, mw.p2ForwardDamage.get(mw.p2ForwardCards.indexOf(cheap)), "opponent's Forwards only");
		assertTrue(mw.gameState.getP1BreakZone().contains(small));
		assertEquals(2000, damageOn(mw, large));
	}

	// =========================================================================================
	// 3-032R Shiva: "Choose up to 2 Forwards opponent controls. Dull them."
	// =========================================================================================

	private static final String SHIVA_3_032R = "Choose up to 2 Forwards opponent controls. Dull them.";

	@Test
	void shivaDullsTwoOfTheOpponentsForwards() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 3; i++) placeP1Forward(mw, makeForward("Theirs " + i, "Water", 3, 7000));
		placeP2Forward(mw, makeForward("Mine", "Ice", 3, 7000));
		castAsP2(mw, makeSummon("Shiva", "Ice", 2, SHIVA_3_032R));

		long dull = mw.p1ForwardStates.stream().filter(s -> s == CardState.DULL).count();
		assertEquals(2, dull);
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "opponent's Forwards only");
	}

	// =========================================================================================
	// 3-037H Zalera, the Death Seraph: "Break all the dull Forwards of costs 2, 3, 5, 7, 11, and 13
	// opponent controls."
	// =========================================================================================

	private static final String ZALERA_3_037H = "Break all the dull Forwards of costs 2, 3, 5, 7, 11, and 13 "
			+ "opponent controls.";

	@Test
	void zaleraBreaksTheOpponentsDullForwardsOfPrimeCost() {
		MainWindow mw = new MainWindow();
		CardData two = makeForward("Two", "Water", 2, 5000);
		CardData three = makeForward("Three", "Water", 3, 7000);
		CardData four = makeForward("Four", "Water", 4, 8000);
		CardData five = makeForward("Five", "Water", 5, 9000);
		CardData activeThree = makeForward("Active Three", "Water", 3, 7000);
		CardData mine = makeForward("Mine", "Ice", 2, 5000);
		for (CardData c : List.of(two, three, four, five, activeThree)) placeP1Forward(mw, c);
		for (CardData c : List.of(two, three, four, five)) dullP1Forward(mw, c);
		placeP2Forward(mw, mine);
		dullP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Zalera, the Death Seraph", "Ice", 4, ZALERA_3_037H));

		assertTrue(mw.gameState.getP1BreakZone().containsAll(List.of(two, three, five)));
		assertTrue(mw.p1ForwardCards.contains(four), "4 is not on the list");
		assertTrue(mw.p1ForwardCards.contains(activeThree), "dull Forwards only");
		assertTrue(mw.p2ForwardCards.contains(mine), "opponent's Forwards only");
	}

	// =========================================================================================
	// 3-061R Diablos: "EX BURST Choose 1 Forward. Deal it 1000 damage for each Character you control.
	// If you control a Job Class Zero Cadet Forward, select up to 3 Backups you control. Activate
	// them."
	// =========================================================================================

	private static final String DIABLOS_3_061R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 1000 damage for each "
			+ "Character you control. If you control a Job Class Zero Cadet Forward, select up to 3 Backups you "
			+ "control. Activate them.";

	/** P2 with {@code mine} and two dull Backups — three Characters — against one P1 Forward. */
	private static MainWindow castDiablos(CardData mine, CardData theirs) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, mine);
		placeP2Backup(mw, makeBackup("Backup A", "Wind", 2));
		placeP2Backup(mw, makeBackup("Backup B", "Wind", 2));
		mw.p2BackupStates[0] = CardState.DULL;
		mw.p2BackupStates[1] = CardState.DULL;
		castAsP2(mw, makeSummon("Diablos", "Wind", 3, DIABLOS_3_061R));
		return mw;
	}

	@Test
	void diablosDeals1000DamagePerCharacterYouControl() {
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		MainWindow mw = castDiablos(makeForward("Mine", "Wind", 3, 7000), theirs);
		assertEquals(3000, damageOn(mw, theirs));
		assertEquals(CardState.DULL, mw.p2BackupStates[0], "no Cadet, no activation");
	}

	@Test
	void diablosActivatesYourBackupsWithAClassZeroCadet() {
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		MainWindow mw = castDiablos(makeForwardWithJob("Cadet", "Wind", 3, 7000, "Class Zero Cadet"), theirs);
		assertEquals(3000, damageOn(mw, theirs));
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[1]);
	}

	/** Stubs the Backup picker: any selection that includes Backups. */
	private static List<ForwardTarget> anyBackupSelection(GameContext ctx) {
		return ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), eq(true), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
	}

	@Test
	void theCadetsControlGateActivatesThreeOfYourOwnBackups() {
		// 3-061R's second half, as the choose chain hands it over.
		ForwardTarget backup = new ForwardTarget(true, 1, ForwardTarget.CardZone.BACKUP);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.controlConditionMet(any())).thenReturn(true);
		when(anyBackupSelection(ctx)).thenReturn(List.of(backup));

		ActionResolver.parse("If you control a Job Class Zero Cadet Forward, select up to 3 Backups you "
				+ "control. Activate them.", null).accept(ctx);

		verify(ctx).selectCharacters(eq(3), eq(true), eq(false), eq(true), any(), any(),
				anyInt(), any(), anyInt(), any(), eq(false), eq(true), eq(false),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
		verify(ctx).activateTarget(backup);
	}

	// =========================================================================================
	// 3-071H Chaos, Walker of the Wheel: "EX BURST Choose 1 Forward opponent controls. Break it. If
	// that Forward is put into the Break Zone, your opponent may play 1 Forward from their hand
	// onto the field."
	//
	// The last sentence was read as the caster's own play from their own hand, on every cast. The
	// opponent's offer is checked through a spy: P1 answering it for real opens a dialog.
	// =========================================================================================

	private static final String CHAOS_3_071H = "[[ex]]EX BURST[[/]] Choose 1 Forward opponent controls. Break it. If "
			+ "that Forward is put into the Break Zone, your opponent may play 1 Forward from their hand onto the field.";

	/** P2 casts Chaos through a spy that declines the opponent's play; returns the spy. */
	private static GameContext castChaosAsP2(MainWindow mw) {
		GameContext ctx = spy(mw.buildGameContext(false));
		doReturn(null).when(ctx).opponentMayPlayCharacterFromHand(anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean(), any());
		CardData chaos = makeSummon("Chaos, Walker of the Wheel", "Wind", 3, CHAOS_3_071H);
		ActionResolver.parse(chaos.summonEffect(), chaos).accept(ctx);
		return ctx;
	}

	@Test
	void chaosBreaksTheForwardAndOffersTheOpponentAPlay() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		GameContext ctx = castChaosAsP2(mw);

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
		verify(ctx).opponentMayPlayCharacterFromHand(eq(true), eq(false), eq(false), anyInt(), any(), anyInt(),
				any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean(), any());
	}

	@Test
	void chaosNeverPlaysFromTheCastersHand() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 4, 8000));
		CardData mineInHand = makeForward("In My Hand", "Wind", 2, 5000);
		mw.gameState.getP2Hand().add(mineInHand);
		castChaosAsP2(mw);

		assertTrue(mw.gameState.getP2Hand().contains(mineInHand));
		assertTrue(mw.p2ForwardCards.isEmpty());
	}

	@Test
	void chaosOffersNothingWhenTheForwardIsNotPutIntoTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		mw.p1ForwardTempTraits.get(0).add(CardData.Trait.CANNOT_BE_BROKEN);
		GameContext ctx = castChaosAsP2(mw);

		assertTrue(mw.p1ForwardCards.contains(theirs));
		verify(ctx, never()).opponentMayPlayCharacterFromHand(anyBoolean(), anyBoolean(), anyBoolean(), anyInt(),
				any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean(), any());
	}

	// =========================================================================================
	// 3-074R Atomos: "Choose 1 Forward. Deal it damage equal to the highest power Forward you control."
	// =========================================================================================

	private static final String ATOMOS_3_074R = "Choose 1 Forward. Deal it damage equal to the highest power "
			+ "Forward you control.";

	@Test
	void atomosDealsDamageEqualToYourHighestPowerForward() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Small", "Earth", 2, 5000));
		placeP2Forward(mw, makeForward("Big", "Earth", 4, 8000));
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Atomos", "Earth", 4, ATOMOS_3_074R));
		assertEquals(8000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 3-087H Zeromus, the Condemner: "EX BURST Choose up to 1 Forward from your Break Zone of cost
	// equal to or less than the damage you have been dealt. Return it to your hand. Your opponent
	// selects 1 Forward of cost equal to or less than the damage you have been dealt and puts it
	// into the Break Zone."
	//
	// "Your opponent selects" was the caster choosing the opponent's Forward. Each half is tested
	// from the seat where only the CPU has a choice to make.
	// =========================================================================================

	private static final String ZEROMUS_3_087H = "[[ex]]EX BURST[[/]] Choose up to 1 Forward from your Break Zone of "
			+ "cost equal to or less than the damage you have been dealt. Return it to your hand. Your opponent "
			+ "selects 1 Forward of cost equal to or less than the damage you have been dealt and puts it into the "
			+ "Break Zone.";

	private static void takeDamage(List<CardData> damageZone, int points) {
		for (int i = 0; i < points; i++) damageZone.add(makeForward("Damage " + i, "Earth", 1, 1000));
	}

	@Test
	void zeromusReturnsAForwardFromYourBreakZoneWithinYourDamage() {
		// P2 casts at 3 damage. P1's one Forward costs 5, over the limit, so P1 has nothing to select.
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP2DamageZone(), 3);
		CardData three = makeForward("Three", "Earth", 3, 7000);
		CardData four  = makeForward("Four", "Earth", 4, 8000);
		mw.gameState.getP2BreakZone().add(four);
		mw.gameState.getP2BreakZone().add(three);
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Zeromus, the Condemner", "Earth", 7, ZEROMUS_3_087H));

		assertTrue(mw.gameState.getP2Hand().contains(three));
		assertTrue(mw.gameState.getP2BreakZone().contains(four), "cost 4 is over 3 damage");
		assertTrue(mw.p1ForwardCards.contains(theirs), "cost 5 is over 3 damage");
	}

	@Test
	void zeromusLetsTheOpponentSelectWhichForwardTheyLose() {
		// P1 casts at 3 damage with an empty Break Zone; the CPU gives up its cheapest eligible one.
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP1DamageZone(), 3);
		CardData two   = makeForward("Two", "Water", 2, 5000);
		CardData three = makeForward("Three", "Water", 3, 7000);
		CardData five  = makeForward("Five", "Water", 5, 9000);
		placeP2Forward(mw, three);
		placeP2Forward(mw, two);
		placeP2Forward(mw, five);
		castAsP1(mw, makeSummon("Zeromus, the Condemner", "Earth", 7, ZEROMUS_3_087H));

		assertTrue(mw.gameState.getP2BreakZone().contains(two));
		assertTrue(mw.p2ForwardCards.containsAll(List.of(three, five)));
	}

	// =========================================================================================
	// 3-102R Odin: "Choose 1 Forward. If it has 7000 power or less, break it. If you control a Job
	// Class Zero Cadet Forward, break it regardless of its power instead."
	//
	// Two sentences over one chosen Forward, and "instead" makes the second a replacement for the
	// first's test rather than a second break. Left to the plain break followup, find() matched
	// "break it" in the first sentence and broke whatever was chosen: the card ignored its power
	// gate AND the condition that lifts it, and broke any Forward on the table.
	//
	// The power read is the effective one, not the printed one — a 7000 Forward holding a lend is
	// out of range, and a 9000 one that has been cut down is in it.
	// =========================================================================================

	private static final String ODIN_BREAK_SUMMON =
			"Choose 1 Forward. If it has 7000 power or less, break it. "
			+ "If you control a Job Class Zero Cadet Forward, break it regardless of its power instead.";

	/** Casts Odin at P2's only Forward and reports whether it was broken. */
	private static boolean odinBreaks(MainWindow mw) {
		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(ODIN_BREAK_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);
		return mw.p2ForwardCards.isEmpty();
	}

	/** A board with one P2 Forward of {@code power}, and optionally a Class Zero Cadet for P1. */
	private static MainWindow odinBoard(int power, boolean withCadet) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Victim", "Fire", 3, power));
		if (withCadet) placeP1Forward(mw, makeJobCard("Ace", "Fire", "Forward", "Class Zero Cadet"));
		return mw;
	}

	@Test
	void odinBreaksAForwardInsideThePowerGate() {
		assertTrue(odinBreaks(odinBoard(7000, false)), "7000 is \"7000 power or less\"");
		assertTrue(odinBreaks(odinBoard(3000, false)));
	}

	@Test
	void andLeavesOneAboveIt() {
		// The whole point of the gate, and what the card was doing to every Forward regardless.
		assertFalse(odinBreaks(odinBoard(9000, false)), "9000 is out of range with no Cadet out");
		assertFalse(odinBreaks(odinBoard(8000, false)));
	}

	@Test
	void unlessYouControlAClassZeroCadetForward() {
		assertTrue(odinBreaks(odinBoard(9000, true)), "the Cadet lifts the gate entirely");
	}

	@Test
	void andTheCadetHasToBeOneYouControl() {
		// "If you control" — the opponent's Cadet is not yours, and lifts nothing.
		MainWindow mw = odinBoard(9000, false);
		placeP2Forward(mw, makeJobCard("Ace", "Fire", "Forward", "Class Zero Cadet"));

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		ActionResolver.parse(ODIN_BREAK_SUMMON, null).accept(ctx);

		assertEquals(2, mw.p2ForwardCards.size(), "nothing broke");
	}

	@Test
	void andThePowerReadIsTheEffectiveOne() {
		// A 7000 Forward holding a +3000 lend is out of range when Odin resolves.
		MainWindow lent = odinBoard(7000, false);
		lent.buildGameContext(true).boostTarget(fwd(false, 0), 3000,
				EnumSet.noneOf(CardData.Trait.class));
		assertEquals(10000, lent.effectiveP2ForwardPower(0));
		assertFalse(odinBreaks(lent), "the lend puts it out of the gate");

		// And a 9000 one that has been cut down is inside it.
		MainWindow cut = odinBoard(9000, false);
		cut.buildGameContext(true).reduceTarget(fwd(false, 0), 3000,
				EnumSet.noneOf(CardData.Trait.class));
		assertEquals(6000, cut.effectiveP2ForwardPower(0));
		assertTrue(odinBreaks(cut), "and the reduction brings it in");
	}

	@Test
	void odinsTwoClausesAreNoLongerDescribedAsTwoBreaks() {
		assertEquals("ChooseCharacter / BreakIfPower(7000-) | IfControl(1+ Class Zero Cadet Forward: Break)",
				ActionResolver.fullDescription(ODIN_BREAK_SUMMON, null));
	}

	@Test
	void andAJobNameIsReadApartFromTheTypeNounAfterIt() {
		// Odin's condition exposed this, but it is not Odin's: the Job group ends on a lookahead
		// and so consumed no space, leaving the type group to start at one and fail. The lazy Job
		// group then backtracked over the noun, and 36 printings of "if you control a Job X
		// Forward" asked for a Job no card has.
		//
		// Asserted on the parts rather than on the rendering, because the singular form renders
		// identically either way — "1+ Class Zero Cadet Forward" whether the noun is the type or
		// the tail of the Job name, which is why nothing caught it.
		ControlCondition singular = CardData.parseControlCondition("a Job Class Zero Cadet Forward");
		assertNotNull(singular);
		assertEquals("Class Zero Cadet", singular.job(), "the noun is not part of the Job name");
		assertEquals("Forward", singular.cardType());

		ControlCondition plural = CardData.parseControlCondition("2 or more Job Standard Unit Backups");
		assertNotNull(plural);
		assertEquals("Standard Unit", plural.job());
		assertEquals("Backup", plural.cardType());
		assertEquals(2, plural.minCount());

		// And a condition with no Job at all still reads its type from the same slot.
		ControlCondition plain = CardData.parseControlCondition("a Fire Forward");
		assertNotNull(plain);
		assertNull(plain.job());
		assertEquals("Forward", plain.cardType());
		assertEquals("Fire", plain.element());
	}

	// =========================================================================================
	// 3-112H Exodus, the Judge-Sal: "Select 1 number. Your opponent selects 1 number. Break all
	// Forwards of cost equal to either number."
	//
	// The opponent's number used to be worked out by the AI from P1's Forwards, whoever cast it: a
	// human facing the CPU's Exodus never picked, and two networked clients worked out different
	// numbers from their mirrored boards.
	// =========================================================================================

	private static final String EXODUS_3_112H = "Select 1 number. Your opponent selects 1 number. Break all Forwards "
			+ "of cost equal to either number.";

	@Test
	void exodusBreaksEveryForwardOfEitherNumberOnBothSides() {
		MainWindow mw = new MainWindow();
		CardData p1Three = makeForward("P1 Three", "Water", 3, 7000);
		CardData p1Four  = makeForward("P1 Four", "Water", 4, 8000);
		CardData p1Five  = makeForward("P1 Five", "Water", 5, 9000);
		CardData p2Three = makeForward("P2 Three", "Lightning", 3, 7000);
		CardData p2Two   = makeForward("P2 Two", "Lightning", 2, 5000);
		for (CardData c : List.of(p1Three, p1Four, p1Five)) placeP1Forward(mw, c);
		for (CardData c : List.of(p2Three, p2Two)) placeP2Forward(mw, c);
		GameContext ctx = spy(mw.buildGameContext(false));
		doReturn(3).when(ctx).selectNumber(anyInt(), anyInt(), anyString());
		doReturn(5).when(ctx).opponentSelectsNumber(anyInt(), anyInt(), anyString());
		CardData exodus = makeSummon("Exodus, the Judge-Sal", "Lightning", 4, EXODUS_3_112H);
		ActionResolver.parse(exodus.summonEffect(), exodus).accept(ctx);

		assertEquals(List.of(p1Four), mw.p1ForwardCards);
		assertEquals(List.of(p2Two), mw.p2ForwardCards);
	}

	@Test
	void exodusAsksTheOpponentForTheSecondNumber() {
		// P1 casts and names 2; the CPU, answering for itself, names the cost most of P1's share.
		MainWindow mw = new MainWindow();
		CardData fourA = makeForward("Four A", "Water", 4, 8000);
		CardData fourB = makeForward("Four B", "Water", 4, 8000);
		CardData six   = makeForward("Six", "Water", 6, 10000);
		CardData cpuTwo = makeForward("CPU Two", "Lightning", 2, 5000);
		for (CardData c : List.of(fourA, fourB, six)) placeP1Forward(mw, c);
		placeP2Forward(mw, cpuTwo);
		GameContext ctx = spy(mw.buildGameContext(true));
		doReturn(2).when(ctx).selectNumber(anyInt(), anyInt(), anyString());
		CardData exodus = makeSummon("Exodus, the Judge-Sal", "Lightning", 4, EXODUS_3_112H);
		ActionResolver.parse(exodus.summonEffect(), exodus).accept(ctx);

		assertEquals(List.of(six), mw.p1ForwardCards, "the CPU named 4");
		assertTrue(mw.gameState.getP2BreakZone().contains(cpuTwo), "P1 named 2");
	}

	// =========================================================================================
	// 3-123R Famfrit, the Darkening Cloud: "EX BURST Both players select 1 Forward they control and
	// put it into the Break Zone."
	//
	// P1 is given no Forwards, so only the CPU has a choice to make; P1 selecting opens a dialog.
	// =========================================================================================

	private static final String FAMFRIT_3_123R = "[[ex]]EX BURST[[/]] Both players select 1 Forward they control and "
			+ "put it into the Break Zone.";

	@Test
	void famfritHasTheCasterPutTheirCheapestForwardIntoTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData two  = makeForward("Two", "Water", 2, 5000);
		CardData five = makeForward("Five", "Water", 5, 9000);
		placeP2Forward(mw, five);
		placeP2Forward(mw, two);
		castAsP2(mw, makeSummon("Famfrit, the Darkening Cloud", "Water", 3, FAMFRIT_3_123R));

		assertTrue(mw.gameState.getP2BreakZone().contains(two));
		assertTrue(mw.p2ForwardCards.contains(five), "one Forward each");
	}

	// =========================================================================================
	// 3-135H Syldra: "Choose up to 2 Forwards opponent controls. Return them to their owners' hand."
	// =========================================================================================

	private static final String SYLDRA_3_135H = "Choose up to 2 Forwards opponent controls. Return them to their "
			+ "owners' hand.";

	@Test
	void syldraReturnsTwoOfTheOpponentsForwardsToTheirHand() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Fire", 3, 7000);
		CardData b = makeForward("B", "Fire", 4, 8000);
		CardData mine = makeForward("Mine", "Water", 3, 7000);
		placeP1Forward(mw, a);
		placeP1Forward(mw, b);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Syldra", "Water", 6, SYLDRA_3_135H));

		assertTrue(mw.gameState.getP1Hand().containsAll(List.of(a, b)));
		assertTrue(mw.p2ForwardCards.contains(mine), "opponent's Forwards only");
	}

	// =========================================================================================
	// 3-145L Ultima, the High Seraph: "If you control a Light Forward, the cost to cast Ultima, The
	// High Seraph is reduced by 2. Remove from the game all the Forwards on the field other than
	// Light and Dark. Then, remove from the top of your deck twice the number of cards removed by
	// the previous effect."
	// =========================================================================================

	private static final String ULTIMA_3_145L = "If you control a Light Forward, the cost to cast Ultima, The High "
			+ "Seraph is reduced by 2. [[br]] Remove from the game all the Forwards on the field other than Light "
			+ "and Dark. Then, remove from the top of your deck twice the number of cards removed by the previous "
			+ "effect.";

	@Test
	void ultimaRemovesEveryForwardButLightAndDarkThenTwiceThatFromYourDeck() {
		MainWindow mw = new MainWindow();
		CardData fire  = makeForward("Fire One", "Fire", 3, 7000);
		CardData light = makeForward("Light One", "Light", 3, 7000);
		CardData water = makeForward("Water One", "Water", 3, 7000);
		CardData dark  = makeForward("Dark One", "Dark", 3, 7000);
		placeP1Forward(mw, fire);
		placeP1Forward(mw, light);
		placeP2Forward(mw, water);
		placeP2Forward(mw, dark);
		fillP2Deck(mw, 6);
		castAsP2(mw, makeSummon("Ultima, the High Seraph", "Light", 7, ULTIMA_3_145L));

		assertTrue(mw.gameState.getP1RemovedFromGame().contains(fire));
		assertTrue(mw.gameState.getP2RemovedFromGame().contains(water));
		assertEquals(List.of(light), mw.p1ForwardCards);
		assertEquals(List.of(dark), mw.p2ForwardCards);
		assertEquals(2, mw.gameState.getP2MainDeck().size(), "two removed, so four off the deck");
	}

	// =========================================================================================
	// 3-147L Zodiark, Keeper of Precepts: "If you control a Dark Forward, the cost to cast Zodiark,
	// Keeper of Precepts is reduced by 3. Break all the Forwards opponent controls. You receive
	// damage equal to the number of Forwards broken by this effect."
	// =========================================================================================

	private static final String ZODIARK_3_147L = "If you control a Dark Forward, the cost to cast Zodiark, Keeper of "
			+ "Precepts is reduced by 3.[[br]] Break all the Forwards opponent controls. You receive damage equal to "
			+ "the number of Forwards broken by this effect.";

	@Test
	void zodiarkBreaksTheOpponentsForwardsAndYouTakeDamageForEach() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Water", 3, 7000);
		CardData b = makeForward("B", "Water", 5, 9000);
		CardData mine = makeForward("Mine", "Dark", 3, 7000);
		placeP1Forward(mw, a);
		placeP1Forward(mw, b);
		placeP2Forward(mw, mine);
		fillP2Deck(mw, 4);
		castAsP2(mw, makeSummon("Zodiark, Keeper of Precepts", "Dark", 7, ZODIARK_3_147L));

		assertTrue(mw.gameState.getP1BreakZone().containsAll(List.of(a, b)));
		assertTrue(mw.p2ForwardCards.contains(mine), "opponent's Forwards only");
		assertEquals(2, mw.gameState.getP2DamageZone().size(), "one point per Forward broken");
	}

	@Test
	void zodiarkDealsYouNothingWhenItBreaksNothing() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Dark", 3, 7000));
		fillP2Deck(mw, 4);
		castAsP2(mw, makeSummon("Zodiark, Keeper of Precepts", "Dark", 7, ZODIARK_3_147L));

		assertTrue(mw.gameState.getP2DamageZone().isEmpty());
		assertEquals(1, mw.p2ForwardCards.size(), "and its own Forwards are untouched");
	}


	// =========================================================================================
	// 4-003C Ifrit: "EX BURST Choose 1 Forward. Deal it 4000 damage. If you control 5 or more Fire
	// Characters, deal it 7000 damage instead."
	// =========================================================================================

	private static final String IFRIT_4_003C = "[[ex]]EX BURST [[/]]Choose 1 Forward. Deal it 4000 damage. If you "
			+ "control 5 or more Fire Characters, deal it 7000 damage instead.";

	/** P2 with {@code fireBackups} Fire Backups casts Ifrit at P1's 9000 Forward. */
	private static int ifrit4003Damage(int fireBackups) {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		for (int i = 0; i < fireBackups; i++) placeP2Backup(mw, makeBackup("Fire " + i, "Fire", 2));
		castAsP2(mw, makeSummon("Ifrit", "Fire", 2, IFRIT_4_003C));
		return damageOn(mw, theirs);
	}

	@Test
	void ifritDeals4000BelowFiveFireCharacters() {
		assertEquals(4000, ifrit4003Damage(4));
	}

	@Test
	void ifritDeals7000InsteadWithFiveFireCharacters() {
		assertEquals(7000, ifrit4003Damage(5));
	}

	// =========================================================================================
	// 4-016R Bahamut: "Choose 1 Forward. Deal it 8000 damage. If it is put from the field into the
	// Break Zone this turn, remove it from the game instead."
	// =========================================================================================

	private static final String BAHAMUT_4_016R = "Choose 1 Forward. Deal it 8000 damage. If it is put from the field "
			+ "into the Break Zone this turn, remove it from the game instead.";

	@Test
	void bahamutRemovesTheForwardItBreaksFromTheGame() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 4, BAHAMUT_4_016R));

		assertTrue(mw.gameState.getP1RemovedFromGame().contains(theirs));
		assertFalse(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void bahamutDeals8000Damage() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 4, BAHAMUT_4_016R));
		assertEquals(8000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 4-033C Shiva: "EX BURST Choose 1 dull Forward. Deal it 6000 damage and 1000 more damage for each
	// Card Name Shiva in your Break Zone."
	// =========================================================================================

	private static final String SHIVA_4_033C = "[[ex]]EX BURST[[/]] Choose 1 dull Forward. Deal it 6000 damage and "
			+ "1000 more damage for each Card Name Shiva in your Break Zone.";

	@Test
	void shivaDeals6000Plus1000PerShivaInYourBreakZone() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		dullP1Forward(mw, theirs);
		mw.gameState.getP2BreakZone().add(makeSummon("Shiva", "Ice", 2, "Draw 1 card."));
		mw.gameState.getP2BreakZone().add(makeSummon("Shiva", "Ice", 3, "Draw 1 card."));
		mw.gameState.getP2BreakZone().add(makeSummon("Ifrit", "Fire", 2, "Draw 1 card."));
		castAsP2(mw, makeSummon("Shiva", "Ice", 2, SHIVA_4_033C));
		assertEquals(8000, damageOn(mw, theirs), "two Shiva: 6000 + 2000");
	}

	@Test
	void shivaDoesNothingWithOnlyAnActiveForward4033() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 5000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Shiva", "Ice", 2, SHIVA_4_033C));
		assertEquals(0, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 4-046R Lich: "Deal each Forward opponent controls damage equal to half of its power (round up to
	// the nearest 1000)."
	// =========================================================================================

	private static final String LICH_4_046R = "Deal each Forward opponent controls damage equal to half of its power "
			+ "(round up to the nearest 1000).";

	@Test
	void lichDealsHalfPowerRoundedUpToEachOpposingForward() {
		MainWindow mw = new MainWindow();
		CardData odd  = makeForward("Odd", "Water", 3, 7000);
		CardData even = makeForward("Even", "Water", 4, 8000);
		CardData mine = makeForward("Mine", "Ice", 3, 7000);
		placeP1Forward(mw, odd);
		placeP1Forward(mw, even);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Lich", "Ice", 4, LICH_4_046R));

		assertEquals(4000, damageOn(mw, odd), "3500 rounds up to 4000");
		assertEquals(4000, damageOn(mw, even));
		assertEquals(0, mw.p2ForwardDamage.get(0), "opponent's Forwards only");
	}

	// =========================================================================================
	// 4-051H Alexander: "EX BURST Choose 1 Forward of power 9000 or more. Break it."
	// =========================================================================================

	private static final String ALEXANDER_4_051H = "[[ex]]EX BURST[[/]] Choose 1 Forward of power 9000 or more. Break it.";

	@Test
	void alexanderBreaksAForwardOfPower9000OrMore() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Alexander", "Wind", 5, ALEXANDER_4_051H));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void alexanderDoesNothingWithOnlyAForwardUnder9000Power() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Alexander", "Wind", 5, ALEXANDER_4_051H));
		assertTrue(mw.p1ForwardCards.contains(theirs));
	}

	// =========================================================================================
	// 4-052C Alexander: "EX BURST Select 1 of the 2 following actions. 'Choose 1 Monster. Break it.'
	// 'Choose 1 Backup you control. Activate it. Draw 1 card.'"
	// =========================================================================================

	private static final String ALEXANDER_4_052C = "[[ex]]EX BURST[[/]] Select 1 of the 2 following actions.[[br]] "
			+ "\"Choose 1 Monster. Break it.\"[[br]] \"Choose 1 Backup you control. Activate it. Draw 1 card.\"";

	@Test
	void alexandersFirstActionBreaksAMonster() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Water", 3);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, makeSummon("Alexander", "Wind", 2, ALEXANDER_4_052C), 0);
		assertTrue(mw.gameState.getP1BreakZone().contains(monster));
	}

	@Test
	void alexandersSecondActionActivatesYourBackupAndDraws() {
		MainWindow mw = new MainWindow();
		placeP2Backup(mw, makeBackup("Mine", "Wind", 2));
		mw.p2BackupStates[0] = CardState.DULL;
		fillP2Deck(mw, 2);
		castAsP2Selecting(mw, makeSummon("Alexander", "Wind", 2, ALEXANDER_4_052C), 1);

		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 4-073C Atomos: "EX BURST Select 1 of the 2 following actions. 'Choose 1 dull Forward. Break it.'
	// 'Choose 1 Forward. Deal it 8000 damage.'"
	// =========================================================================================

	private static final String ATOMOS_4_073C = "[[ex]]EX BURST[[/]] Select 1 of the 2 following actions.[[br]] "
			+ "\"Choose 1 dull Forward. Break it.\"[[br]] \"Choose 1 Forward. Deal it 8000 damage.\"";

	@Test
	void atomossFirstActionBreaksADullForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		dullP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Atomos", "Earth", 6, ATOMOS_4_073C), 0);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void atomossSecondActionDeals8000Damage() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Atomos", "Earth", 6, ATOMOS_4_073C), 1);
		assertEquals(8000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 4-093R Hecatoncheir: "Choose 1 Forward you control and 1 Forward opponent controls. Each Forward
	// deals damage equal to its power to the other."
	// =========================================================================================

	private static final String HECATONCHEIR_4_093R = "Choose 1 Forward you control and 1 Forward opponent controls. "
			+ "Each Forward deals damage equal to its power to the other.";

	@Test
	void hecatoncheirMakesTheTwoForwardsFight() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Earth", 4, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 5000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Hecatoncheir", "Earth", 2, HECATONCHEIR_4_093R));

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "7000 into a 5000");
		assertEquals(5000, mw.p2ForwardDamage.get(0), "5000 back into a 7000");
	}

	// =========================================================================================
	// 4-114L Raiden: "Choose up to 2 Forwards opponent controls. Remove the first Forward from the
	// game, and break the other."
	// =========================================================================================

	private static final String RAIDEN_4_114L = "Choose up to 2 Forwards opponent controls. Remove the first Forward "
			+ "from the game, and break the other.";

	@Test
	void raidenRemovesOneForwardFromTheGameAndBreaksTheOther() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Water", 3, 7000);
		CardData b = makeForward("B", "Water", 4, 8000);
		CardData mine = makeForward("Mine", "Lightning", 3, 7000);
		placeP1Forward(mw, a);
		placeP1Forward(mw, b);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Raiden", "Lightning", 9, RAIDEN_4_114L));

		assertTrue(mw.p1ForwardCards.isEmpty());
		assertEquals(1, mw.gameState.getP1RemovedFromGame().size(), "one removed");
		assertEquals(1, mw.gameState.getP1BreakZone().size(), "one broken");
		assertTrue(mw.p2ForwardCards.contains(mine), "opponent's Forwards only");
	}

	@Test
	void raidenBreaksTheRightForwardWhenTheFirstSatInALowerSlot() {
		// Removing the first shifts the slots behind it. The other used to be broken by its old
		// index: out of bounds here with two, the wrong Forward with three.
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Water", 3, 7000);
		CardData b = makeForward("B", "Water", 4, 8000);
		CardData c = makeForward("C", "Water", 5, 9000);
		for (CardData f : List.of(a, b, c)) placeP1Forward(mw, f);
		GameContext ctx = spy(mw.buildGameContext(false));
		doReturn(List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD),
				new ForwardTarget(true, 1, ForwardTarget.CardZone.FORWARD))).when(ctx).consumePreloadedTargets();
		CardData raiden = makeSummon("Raiden", "Lightning", 9, RAIDEN_4_114L);
		ActionResolver.parse(raiden.summonEffect(), raiden).accept(ctx);

		assertTrue(mw.gameState.getP1RemovedFromGame().contains(a), "the first, removed");
		assertTrue(mw.gameState.getP1BreakZone().contains(b), "the other, broken");
		assertEquals(List.of(c), mw.p1ForwardCards, "and nothing else");
	}

	// =========================================================================================
	// 4-116C Ramuh: "EX BURST Select 1 of the 2 following actions. 'Choose 1 Forward of cost 2 or less.
	// Break it.' 'Choose 1 Monster of cost 2 or less. Break it.'"
	// =========================================================================================

	private static final String RAMUH_4_116C = "[[ex]]EX BURST[[/]] Select 1 of the 2 following actions.[[br]] "
			+ "\"Choose 1 Forward of cost 2 or less. Break it.\"[[br]] \"Choose 1 Monster of cost 2 or less. Break it.\"";

	@Test
	void ramuhsFirstActionBreaksACheapForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 2, 5000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_4_116C), 0);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void ramuhsFirstActionLeavesACost3Forward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 5000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_4_116C), 0);
		assertTrue(mw.p1ForwardCards.contains(theirs));
	}

	@Test
	void ramuhsSecondActionBreaksACheapMonster() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Water", 2);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_4_116C), 1);
		assertTrue(mw.gameState.getP1BreakZone().contains(monster));
	}

	// =========================================================================================
	// 4-128C PuPu: "EX BURST Discard 1 card. Then, draw 2 cards."
	// =========================================================================================

	private static final String PUPU_4_128C = "[[ex]]EX BURST[[/]] Discard 1 card. Then, draw 2 cards.[[br]]";

	@Test
	void pupuDiscardsOneThenDrawsTwo() {
		MainWindow mw = new MainWindow();
		CardData spare = makeForward("Spare", "Water", 2, 5000);
		mw.gameState.getP2Hand().add(spare);
		fillP2Deck(mw, 3);
		castAsP2(mw, makeSummon("PuPu", "Water", 1, PUPU_4_128C));

		assertTrue(mw.gameState.getP2BreakZone().contains(spare), "the discard");
		assertEquals(2, mw.gameState.getP2Hand().size(), "then two drawn");
		assertEquals(1, mw.gameState.getP2MainDeck().size());
	}

	// =========================================================================================
	// 4-143R Leviathan: "EX BURST Choose 1 Forward you control and 1 Forward opponent controls. Return
	// them to their owners' hand."
	// =========================================================================================

	private static final String LEVIATHAN_4_143R = "[[ex]]EX BURST[[/]] Choose 1 Forward you control and 1 Forward "
			+ "opponent controls. Return them to their owners' hand.";

	@Test
	void leviathanReturnsOneForwardFromEachSideToItsOwner() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Water", 3, 7000);
		CardData theirs = makeForward("Theirs", "Fire", 4, 8000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Leviathan", "Water", 3, LEVIATHAN_4_143R));

		assertTrue(mw.gameState.getP2Hand().contains(mine));
		assertTrue(mw.gameState.getP1Hand().contains(theirs));
	}

	// =========================================================================================
	// 5-003C Ifrit: "EX BURST Choose 1 Forward. You may discard 1 Card Name Ifrit from your hand. If
	// you do so, deal it 10000 damage. If not, deal it 5000 damage."
	//
	// Bahamut Fury 1-190S's shape with a named discard: only an Ifrit in hand buys the 10000, and
	// any other card there is left alone.
	//
	// Unlike Bahamut Fury's any-card discard, the CPU passes on a named one
	// (GameContextImpl.mayDiscardCardNameFromHandOrElse), so the paid branch is driven through a
	// mock that answers the offer.
	// =========================================================================================

	private static final String IFRIT_5_003C = "[[ex]]EX BURST[[/]]Choose 1 Forward. You may discard 1 Card Name "
			+ "Ifrit from your hand. If you do so, deal it 10000 damage. If not, deal it 5000 damage.";

	/** Casts Ifrit against a mock that chose {@code t} and answers the discard offer with {@code discards}. */
	private static GameContext castIfrit5Answering(ForwardTarget t, boolean discards) {
		GameContext ctx = contextChoosing(List.of(t));
		doAnswer(inv -> {
			inv.<Consumer<GameContext>>getArgument(discards ? 1 : 2).accept(ctx);
			return null;
		}).when(ctx).mayDiscardCardNameFromHandOrElse(any(), any(), any());
		CardData ifrit = makeSummon("Ifrit", "Fire", 2, IFRIT_5_003C);
		ActionResolver.parse(ifrit.summonEffect(), ifrit).accept(ctx);
		return ctx;
	}

	@Test
	void ifritOffersADiscardOfACardNamedIfritAndDeals10000IfPaid() {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = castIfrit5Answering(t, true);

		verify(ctx).mayDiscardCardNameFromHandOrElse(eq("Ifrit"), any(), any());
		verify(ctx, never()).mayDiscardCardOfTypeFromHandOrElse(any(), any(), any());
		verify(ctx).damageTarget(t, 10000);
		verify(ctx, never()).damageTarget(t, 5000);
	}

	@Test
	void ifritDeals5000WhenTheDiscardIsDeclined() {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = castIfrit5Answering(t, false);

		verify(ctx).damageTarget(t, 5000);
		verify(ctx, never()).damageTarget(t, 10000);
	}

	@Test
	void ifritDeals5000WhenTheHandHoldsNoIfrit() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		CardData spare  = makeForward("Spare", "Fire", 2, 5000);
		placeP1Forward(mw, theirs);
		mw.gameState.getP2Hand().add(spare);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 2, IFRIT_5_003C));

		assertEquals(5000, damageOn(mw, theirs));
		assertEquals(List.of(spare), mw.gameState.getP2Hand(), "only a card named Ifrit pays");
	}

	// =========================================================================================
	// 5-019L Phoenix: "Choose 1 Forward of cost 3 or less in your Break Zone and up to 1 Forward
	// opponent controls. Play the former onto the field dull and deal the latter 8000 damage."
	// =========================================================================================

	private static final String PHOENIX_5_019L = "Choose 1 Forward of cost 3 or less in your Break Zone and up to 1 "
			+ "Forward opponent controls. Play the former onto the field dull and deal the latter 8000 damage.";

	@Test
	void phoenixPlaysACheapForwardFromTheBreakZoneDullAndDamagesTheOpponents() {
		MainWindow mw = new MainWindow();
		CardData fallen   = makeForward("Fallen", "Fire", 3, 7000);
		CardData tooDear  = makeForward("Too Dear", "Fire", 4, 8000);
		CardData theirs   = makeForward("Theirs", "Water", 4, 8000);
		mw.gameState.getP2BreakZone().add(tooDear);
		mw.gameState.getP2BreakZone().add(fallen);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Phoenix", "Fire", 7, PHOENIX_5_019L));

		assertEquals(List.of(fallen), mw.p2ForwardCards, "cost 3 or less");
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0), "and it enters dull");
		assertTrue(mw.gameState.getP2BreakZone().contains(tooDear), "cost 4 stays behind");
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "8000 breaks an 8000 Forward");
	}

	@Test
	void phoenixStillPlaysTheFormerWithNoOpposingForward() {
		// "up to 1 Forward opponent controls" — an empty side is not a failed choice.
		MainWindow mw = new MainWindow();
		CardData fallen = makeForward("Fallen", "Fire", 2, 5000);
		mw.gameState.getP2BreakZone().add(fallen);
		castAsP2(mw, makeSummon("Phoenix", "Fire", 7, PHOENIX_5_019L));

		assertEquals(List.of(fallen), mw.p2ForwardCards);
		assertFalse(mw.gameState.getP2BreakZone().contains(fallen));
	}

	// =========================================================================================
	// 5-032H Glasya Labolas: "Select up to 2 of the 4 following actions. "Your opponent discards 1
	// card from his/her hand." "Choose 1 Forward. Dull it." "Choose 1 Forward. Freeze it."
	// "Choose 1 dull Forward. Deal it 7000 damage.""
	//
	// P1's hand holds one card whenever the discard is taken, so P1's forced discard needs no
	// answer.
	// =========================================================================================

	private static final String GLASYA_LABOLAS_5_032H = "Select up to 2 of the 4 following actions.[[br]] "
			+ "\"Your opponent discards 1 card from his/her hand.\"[[br]] \"Choose 1 Forward. Dull it.\"[[br]] "
			+ "\"Choose 1 Forward. Freeze it.\"[[br]] \"Choose 1 dull Forward. Deal it 7000 damage.\"";

	private static CardData glasyaLabolas() {
		return makeSummon("Glasya Labolas", "Ice", 3, GLASYA_LABOLAS_5_032H);
	}

	@Test
	void glasyaLabolasDullsThenFreezesTheSameForward() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 4, 8000));
		castAsP2Selecting(mw, glasyaLabolas(), 1, 2);

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.p1ForwardFrozen.get(0));
	}

	@Test
	void glasyaLabolasMakesTheOpponentDiscardAndDamagesOnlyADullForward() {
		MainWindow mw = new MainWindow();
		CardData held   = makeForward("Held", "Water", 2, 5000);
		CardData dull   = makeForward("Dull", "Water", 4, 7000);
		CardData active = makeForward("Active", "Water", 4, 7000);
		mw.gameState.getP1Hand().add(held);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2Selecting(mw, glasyaLabolas(), 0, 3);

		assertTrue(mw.gameState.getP1BreakZone().contains(held), "the discard");
		assertTrue(mw.gameState.getP1BreakZone().contains(dull), "7000 breaks the dull 7000 Forward");
		assertEquals(List.of(active), mw.p1ForwardCards, "an active Forward is not a legal choice");
	}

	@Test
	void glasyaLabolasMayTakeASingleAction() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 4, 8000));
		castAsP2Selecting(mw, glasyaLabolas(), 2);

		assertTrue(mw.p1ForwardFrozen.get(0));
		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0), "the dull was not selected");
	}

	// =========================================================================================
	// 5-044C Mateus, the Corrupt: "Choose 1 blocking Forward. Break it."
	// =========================================================================================

	private static final String MATEUS_5_044C = "Choose 1 blocking Forward. Break it.";

	@Test
	void mateusBreaksTheBlockingForward() {
		MainWindow mw = new MainWindow();
		CardData idle     = makeForward("Idle", "Water", 2, 5000);
		CardData blocking = makeForward("Blocking", "Water", 5, 9000);
		placeP1Forward(mw, idle);
		placeP1Forward(mw, blocking);
		mw.p1BlockingIdx = 1;
		castAsP2(mw, makeSummon("Mateus, the Corrupt", "Ice", 1, MATEUS_5_044C));

		assertTrue(mw.gameState.getP1BreakZone().contains(blocking));
		assertEquals(List.of(idle), mw.p1ForwardCards, "only a blocking Forward");
	}

	@Test
	void mateusDoesNothingWhenNoForwardIsBlocking() {
		MainWindow mw = new MainWindow();
		CardData idle = makeForward("Idle", "Water", 2, 5000);
		placeP1Forward(mw, idle);
		castAsP2(mw, makeSummon("Mateus, the Corrupt", "Ice", 1, MATEUS_5_044C));
		assertEquals(List.of(idle), mw.p1ForwardCards);
	}

	// =========================================================================================
	// 5-049C Asura: "EX BURST Choose 1 Forward you control. Activate it and negate all damage dealt
	// to it. Draw 1 card."
	// =========================================================================================

	private static final String ASURA_5_049C = "[[ex]]EX BURST [[/]]Choose 1 Forward you control. Activate it and "
			+ "negate all damage dealt to it. Draw 1 card.";

	@Test
	void asuraActivatesAndHealsYourForwardThenDraws() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Wind", 3, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		dullP2Forward(mw, mine);
		dullP1Forward(mw, theirs);
		mw.p2ForwardDamage.set(0, 4000);
		mw.p1ForwardDamage.set(0, 4000);
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Asura", "Wind", 2, ASURA_5_049C));

		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0));
		assertEquals(0, mw.p2ForwardDamage.get(0), "the damage already dealt is negated");
		assertEquals(1, mw.gameState.getP2Hand().size(), "then a card drawn");
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0), "a Forward you control only");
		assertEquals(4000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 5-062L Diabolos: "Select up to 2 of the 4 following actions. "Choose 1 Forward of cost 5 or
	// more. Break it." "Choose 1 Forward of cost 4 or less. Until the end of the turn, its power
	// becomes 1000." "Activate all the Forwards you control." "Activate all the Backups you
	// control.""
	//
	// Board tests for the whole card. The section below it covers the second option's wording.
	// =========================================================================================

	private static final String DIABOLOS_5_062L = "Select up to 2 of the 4 following actions.[[br]] "
			+ "\"Choose 1 Forward of cost 5 or more. Break it.\"[[br]] "
			+ "\"Choose 1 Forward of cost 4 or less. Until the end of the turn, its power becomes 1000. \"[[br]] "
			+ "\"Activate all the Forwards you control.\"[[br]] \"Activate all the Backups you control.\"";

	private static CardData diabolos() {
		return makeSummon("Diabolos", "Wind", 5, DIABOLOS_5_062L);
	}

	@Test
	void diabolosBreaksACostlyForwardAndShrinksACheapOne() {
		MainWindow mw = new MainWindow();
		CardData costly = makeForward("Costly", "Water", 5, 9000);
		CardData cheap  = makeForward("Cheap", "Water", 4, 8000);
		placeP1Forward(mw, costly);
		placeP1Forward(mw, cheap);
		castAsP2Selecting(mw, diabolos(), 0, 1);

		assertTrue(mw.gameState.getP1BreakZone().contains(costly), "cost 5 or more");
		assertEquals(List.of(cheap), mw.p1ForwardCards, "cost 4 is not a legal break");
		assertEquals(1000, mw.effectiveP1ForwardPower(0), "but is a legal shrink");
	}

	@Test
	void diabolosFirstOptionLeavesACost4Forward() {
		MainWindow mw = new MainWindow();
		CardData cheap = makeForward("Cheap", "Water", 4, 8000);
		placeP1Forward(mw, cheap);
		castAsP2Selecting(mw, diabolos(), 0);
		assertEquals(List.of(cheap), mw.p1ForwardCards);
	}

	@Test
	void diabolosActivatesYourForwardsAndBackups() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Wind", 3, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		placeP2Backup(mw, makeBackup("My Backup", "Wind", 2));
		dullP2Forward(mw, mine);
		dullP1Forward(mw, theirs);
		mw.p2BackupStates[0] = CardState.DULL;
		castAsP2Selecting(mw, diabolos(), 2, 3);

		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0));
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0), "only the Forwards you control");
	}

	// =========================================================================================
	// 5-062L Diabolos, second option: "Choose 1 Forward of cost 4 or less. Until the end of the
	// turn, its power becomes 1000."
	//
	// The same act FOLLOWUP_POWER_BECOMES already handled, with the duration fronted instead of
	// trailing. Only this card and 3-066R Barbariccia print it that way, and both went unread —
	// the pattern required the "until the end of the turn" to come last.
	// =========================================================================================

	private static final String DIABOLOS_MODAL_SUMMON =
			"Select up to 2 of the 4 following actions. "
			+ "\"Choose 1 Forward of cost 5 or more. Break it.\" "
			+ "\"Choose 1 Forward of cost 4 or less. Until the end of the turn, its power becomes 1000. \" "
			+ "\"Activate all the Forwards you control.\" "
			+ "\"Activate all the Backups you control.\"";

	private static final String FRONTED_POWER_BECOMES =
			"Choose 1 Forward of cost 4 or less. Until the end of the turn, its power becomes 1000.";

	@Test
	void aFrontedUntilEndOfTurnStillSetsThePower() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));

		ActionResolver.parse(FRONTED_POWER_BECOMES, null).accept(ctx);

		verify(ctx).setTargetBasePower(victim, 1000);
	}

	@Test
	void andTheOtherPrintingOfThatWordingReadsTheSameWay() {
		// 3-066R Barbariccia's enters-the-field ability, the corpus's only other fronted form.
		assertEquals("ChooseCharacter / PowerBecomes",
				ActionResolver.fullDescription(
						"Choose 1 Forward opponent controls. Until the end of the turn, its power becomes 1000.",
						null));
	}

	@Test
	void diabolosNamesAllFourOfItsOptions() {
		assertEquals("SelectFollowingActions(up to 2 of 4: ChooseCharacter / Break "
						+ "| ChooseCharacter / PowerBecomes | AllFieldEffect | AllFieldEffect)",
				ActionResolver.fullDescription(DIABOLOS_MODAL_SUMMON, null));
	}

	// =========================================================================================
	// 5-077H Carbuncle: "Choose 1 Forward. It gains +2000 power until the end of the turn. If its
	// power has become 9000 or less, return Carbuncle to your hand."
	//
	// Cast through the Stack rather than castAsP2: returning the Summon is something only a real
	// resolution can do, since the card goes to hand in place of the Break Zone as it leaves.
	// =========================================================================================

	private static final String CARBUNCLE_5_077H = "Choose 1 Forward. It gains +2000 power until the end of the "
			+ "turn. If its power has become 9000 or less, return Carbuncle to your hand.";

	/** P2 casts Carbuncle on its own Forward of {@code power}; P1 passes and it resolves. */
	private static MainWindow castCarbuncleOn(int power, CardData carbuncle) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, power));
		mw.gameState.getIdentity().put(carbuncle, false);  // whose Break Zone it goes to
		mw.pushSummonOnStack(carbuncle, false, 0, 0, false, null, false);
		mw.passStackPriority();
		return mw;
	}

	@Test
	void carbuncleReturnsToHandWhenThePowerEndsAt9000OrLess() {
		CardData carbuncle = makeSummon("Carbuncle", "Earth", 2, CARBUNCLE_5_077H);
		MainWindow mw = castCarbuncleOn(5000, carbuncle);

		assertEquals(7000, mw.effectiveP2ForwardPower(0));
		assertTrue(mw.gameState.getP2Hand().contains(carbuncle));
		assertFalse(mw.gameState.getP2BreakZone().contains(carbuncle));
	}

	@Test
	void carbuncleReturnsAtExactly9000() {
		CardData carbuncle = makeSummon("Carbuncle", "Earth", 2, CARBUNCLE_5_077H);
		MainWindow mw = castCarbuncleOn(7000, carbuncle);

		assertEquals(9000, mw.effectiveP2ForwardPower(0));
		assertTrue(mw.gameState.getP2Hand().contains(carbuncle), "\"9000 or less\" includes 9000");
	}

	@Test
	void carbuncleGoesToTheBreakZoneWhenThePowerEndsAbove9000() {
		CardData carbuncle = makeSummon("Carbuncle", "Earth", 2, CARBUNCLE_5_077H);
		MainWindow mw = castCarbuncleOn(8000, carbuncle);

		assertEquals(10000, mw.effectiveP2ForwardPower(0));
		assertTrue(mw.gameState.getP2BreakZone().contains(carbuncle));
		assertFalse(mw.gameState.getP2Hand().contains(carbuncle));
	}

	@Test
	void carbuncleIsDescribedWithItsGate() {
		// "It gains +2000 power until the end of the turn" puts the duration last, which takes the
		// boost's other branch from "Until the end of the turn, it gains"; both read the gate.
		assertEquals("ChooseCharacter / PowerBoost + IfPowerBecame(9000 or less: ReturnNamedToHand)",
				ActionResolver.fullDescription(CARBUNCLE_5_077H, null));
	}

	// =========================================================================================
	// 5-081C Cockatrice: "Choose 1 Forward. During this turn, it cannot attack or block, and if it
	// is dealt damage, the damage becomes 0 instead."
	//
	// Two clauses in one sentence, and each half's own branch scans with find() — so whichever ran
	// first would have claimed its clause and dropped the other. Read together, ahead of both.
	//
	// The shield is the unspent kind. The card names no number of hits, so a Forward dealt damage
	// twice in a turn takes neither; the one-shot shield beside it would have stopped only the
	// first.
	// =========================================================================================

	private static final String COCKATRICE_SUMMON =
			"Choose 1 Forward. During this turn, it cannot attack or block, "
			+ "and if it is dealt damage, the damage becomes 0 instead.";

	@Test
	void cockatriceLocksTheForwardAndShieldsIt() {
		MainWindow mw = new MainWindow();
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		placeP2Forward(mw, victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(COCKATRICE_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		assertTrue(mw.p2CannotAttack.contains(victim), "neither half may be dropped");
		assertTrue(mw.p2CannotBlock.contains(victim));
		assertTrue(mw.allIncomingDmgZeroThisTurnSet.contains(victim));
	}

	@Test
	void andTheShieldIsNotSpentByTheFirstHit() {
		// The difference between this shield and the one-shot beside it. "If it is dealt damage,
		// the damage becomes 0" names no number of hits, so the second one is stopped too.
		MainWindow mw = new MainWindow();
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		placeP2Forward(mw, victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		ActionResolver.parse(COCKATRICE_SUMMON, null).accept(ctx);

		ctx.damageTarget(fwd(false, 0), 3000);
		ctx.damageTarget(fwd(false, 0), 3000);

		assertEquals(0, mw.p2ForwardDamage.get(0), "both hits became 0");
		assertEquals(1, mw.p2ForwardCards.size());
	}

	// =========================================================================================
	// 5-100H Odin: "Select 1 of the 2 following actions. "Choose 1 Forward of cost 3 or less. Break
	// it." "Choose 1 Monster of cost 3 or less. Break it.""
	// =========================================================================================

	private static final String ODIN_5_100H = "Select 1 of the 2 following actions.[[br]] \"Choose 1 Forward of cost "
			+ "3 or less. Break it.\"[[br]] \"Choose 1 Monster of cost 3 or less. Break it.\"";

	private static CardData odin5() {
		return makeSummon("Odin", "Lightning", 3, ODIN_5_100H);
	}

	@Test
	void odinsFirstActionBreaksACost3Forward() {
		MainWindow mw = new MainWindow();
		CardData cheap = makeForward("Cheap", "Water", 3, 7000);
		CardData dear  = makeForward("Dear", "Water", 4, 8000);
		placeP1Forward(mw, cheap);
		placeP1Forward(mw, dear);
		castAsP2Selecting(mw, odin5(), 0);

		assertTrue(mw.gameState.getP1BreakZone().contains(cheap));
		assertEquals(List.of(dear), mw.p1ForwardCards, "cost 4 is out of reach");
	}

	@Test
	void odinsSecondActionBreaksACost3Monster() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Water", 3);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, odin5(), 1);
		assertTrue(mw.gameState.getP1BreakZone().contains(monster));
	}

	@Test
	void odinsSecondActionLeavesACost4Monster() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Water", 4);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, odin5(), 1);
		assertTrue(mw.p1MonsterCards.contains(monster));
	}

	// =========================================================================================
	// 5-117C Ramuh: "EX BURST Choose 1 damaged Forward. Deal it 7000 damage."
	// =========================================================================================

	private static final String RAMUH_5_117C = "[[ex]]EX BURST[[/]] Choose 1 damaged Forward. Deal it 7000 damage.";

	@Test
	void ramuhFinishesADamagedForwardAndIgnoresAnUndamagedOne() {
		MainWindow mw = new MainWindow();
		CardData fresh = makeForward("Fresh", "Water", 2, 5000);
		CardData hurt  = makeForward("Hurt", "Water", 4, 8000);
		placeP1Forward(mw, fresh);
		placeP1Forward(mw, hurt);
		mw.p1ForwardDamage.set(1, 1000);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_5_117C));

		assertTrue(mw.gameState.getP1BreakZone().contains(hurt), "1000 + 7000 breaks an 8000 Forward");
		assertEquals(List.of(fresh), mw.p1ForwardCards);
		assertEquals(0, damageOn(mw, fresh), "an undamaged Forward is not a legal choice");
	}

	@Test
	void ramuhDoesNothingWhenNoForwardIsDamaged() {
		MainWindow mw = new MainWindow();
		CardData fresh = makeForward("Fresh", "Water", 2, 5000);
		placeP1Forward(mw, fresh);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_5_117C));
		assertEquals(0, damageOn(mw, fresh));
	}

	// =========================================================================================
	// 5-133H Bismarck: "Select 1 of the 3 following actions. "Choose 1 Monster. Return it to its
	// owner's hand." "Choose 1 Character you control. Return it to its owner's hand." "Choose 1
	// Forward. Halve its power until the end of the turn (round down to the nearest 1000).""
	//
	// Board tests for the first two options. The section below it covers the third.
	// =========================================================================================

	private static final String BISMARCK_5_133H = "Select 1 of the 3 following actions.[[br]] \"Choose 1 Monster. "
			+ "Return it to its owner's hand.\"[[br]] \"Choose 1 Character you control. Return it to its owner's "
			+ "hand.\"[[br]] \"Choose 1 Forward. Halve its power until the end of the turn (round down to the "
			+ "nearest 1000).\"";

	private static CardData bismarck() {
		return makeSummon("Bismarck", "Water", 2, BISMARCK_5_133H);
	}

	@Test
	void bismarcksFirstActionReturnsAMonsterToItsOwner() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Fire", 3);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, bismarck(), 0);

		assertTrue(mw.gameState.getP1Hand().contains(monster), "to its owner's hand, not the caster's");
		assertFalse(mw.p1MonsterCards.contains(monster));
	}

	@Test
	void bismarcksSecondActionReturnsOneOfYourOwnCharacters() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Water", 3, 7000);
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, bismarck(), 1);

		assertTrue(mw.gameState.getP2Hand().contains(mine));
		assertEquals(List.of(theirs), mw.p1ForwardCards, "a Character you control only");
	}

	// =========================================================================================
	// 5-133H Bismarck, third option: "Choose 1 Forward. Halve its power until the end of the turn
	// (round down to the nearest 1000)."
	//
	// The corpus's only printing of "halve". Expressed as a reduction rather than a new base
	// power, because that is what it is — the amount is read per target off the power the Forward
	// has when this resolves, so a lend on it is halved along with the rest.
	// =========================================================================================

	private static final String BISMARCK_OPTION =
			"Choose 1 Forward. Halve its power until the end of the turn (round down to the nearest 1000).";

	private static int bismarckAgainst(int printedPower) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Victim", "Fire", 3, printedPower));

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(BISMARCK_OPTION, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);
		return mw.effectiveP2ForwardPower(0);
	}

	@Test
	void bismarckHalvesAnEvenPowerExactly() {
		assertEquals(4000, bismarckAgainst(8000));
	}

	@Test
	void andRoundsAnOddOneDownToTheNearestThousand() {
		// 9000 halves to 4500, which is not a legal power — the printing says which way to go.
		assertEquals(4000, bismarckAgainst(9000));
		assertEquals(3000, bismarckAgainst(7000));
	}

	@Test
	void andHalvesThePowerTheForwardActuallyHasRatherThanItsPrinted() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Victim", "Fire", 3, 5000));

		GameContext ctx = mw.buildGameContext(true);
		ctx.boostTarget(fwd(false, 0), 3000, EnumSet.noneOf(CardData.Trait.class));
		assertEquals(8000, mw.effectiveP2ForwardPower(0), "the lend is on before Bismarck resolves");

		ctx.preloadTargets(List.of(fwd(false, 0)));
		ActionResolver.parse(BISMARCK_OPTION, null).accept(ctx);

		assertEquals(4000, mw.effectiveP2ForwardPower(0), "8000 halved, not 5000");
	}

	@Test
	void bismarckNamesAllThreeOfItsOptions() {
		String summon = "Select 1 of the 3 following actions. "
				+ "\"Choose 1 Monster. Return it to its owner's hand.\" "
				+ "\"Choose 1 Character you control. Return it to its owner's hand.\" "
				+ "\"Choose 1 Forward. Halve its power until the end of the turn (round down to the nearest 1000).\"";
		assertEquals("SelectFollowingActions(1 of 3: ChooseCharacter / ReturnToOwnersHand "
						+ "| ChooseCharacter / ReturnToOwnersHand | ChooseCharacter / HalvePower)",
				ActionResolver.fullDescription(summon, null));
	}

	// =========================================================================================
	// 5-139C Leviathan: "EX BURST Choose 1 Forward. If its cost is equal to or less than the number of
	// cards in your hand, return it to its owner's hand."
	//
	// "Your hand" is the caster's, P2's here, read when the Summon resolves.
	// =========================================================================================

	private static final String LEVIATHAN_5_139C = "[[ex]]EX BURST[[/]]Choose 1 Forward. If its cost is equal to or "
			+ "less than the number of cards in your hand, return it to its owner's hand.";

	/** Leviathan cast from P2's seat, against P1's lone {@code theirs}, with three cards in P2's hand. */
	private static MainWindow castLeviathan139Against(CardData theirs) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, theirs);
		for (int i = 0; i < 3; i++) mw.gameState.getP2Hand().add(makeForward("Held " + i, "Water", 2, 5000));
		castAsP2(mw, makeSummon("Leviathan", "Water", 1, LEVIATHAN_5_139C));
		return mw;
	}

	@Test
	void leviathanReturnsAForwardCostingNoMoreThanYourHandSize() {
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		MainWindow mw = castLeviathan139Against(theirs);

		assertTrue(mw.gameState.getP1Hand().contains(theirs), "cost 3 against 3 cards in hand");
		assertTrue(mw.p1ForwardCards.isEmpty());
	}

	@Test
	void leviathanLeavesAForwardCostingMoreThanYourHandSize() {
		CardData theirs = makeForward("Theirs", "Fire", 4, 8000);
		MainWindow mw = castLeviathan139Against(theirs);

		assertEquals(List.of(theirs), mw.p1ForwardCards, "cost 4 against 3 cards in hand");
		assertFalse(mw.gameState.getP1Hand().contains(theirs));
	}

	// =========================================================================================
	// 6-017C Bahamut: "Choose 1 Forward. Deal it 10000 damage. Bahamut deals you 1 point of
	// damage. EX Bursts of cards put into the Damage Zone due to this damage cannot be used."
	//
	// The suppression clause was already wired — for "due to this ability", which every other
	// printing says. Bahamut names the damage instead, and the two come to the same thing here:
	// the ability the suppression scopes to is the one resolution that dealt it, and nothing else
	// in this text puts a card into a Damage Zone.
	// =========================================================================================

	private static final String BAHAMUT_SELF_DAMAGE_SUMMON =
			"EX BURST Choose 1 Forward. Deal it 10000 damage. Bahamut deals you 1 point of damage. "
			+ "EX Bursts of cards put into the Damage Zone due to this damage cannot be used.";

	@Test
	void bahamutBurnsHurtsYouAndSuppressesTheExBurst() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));

		Consumer<GameContext> effect = ActionResolver.parse(BAHAMUT_SELF_DAMAGE_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		verify(ctx).damageTarget(victim, 10000);
		verify(ctx).dealDamageToSelf(1);
		verify(ctx).suppressExBurstsThisAbility();
	}

	@Test
	void andTheSuppressionIsNoLongerAnUnreadTail() {
		// Both routes to this description now join with " + ". The secondary reaches the
		// compound fallback again since "this damage" in the last sentence became a backward
		// reference, which is what it is — the string is the same either way, and
		// bahamutBurnsHurtsYouAndSuppressesTheExBurst is what holds the behaviour.
		assertEquals("ChooseCharacter / Damage + DealPlayerDamageToSelf + ExBurstSuppression",
				ActionResolver.fullDescription(BAHAMUT_SELF_DAMAGE_SUMMON, null));
	}

	// =========================================================================================
	// 6-125R Leviathan, third option: "During this turn, if a Forward you control is dealt damage
	// by a Summon, the damage becomes 0 instead."
	//
	// Its B-047 reprint says "by a Summon or an ability" and was the only wording read; this one
	// did not parse at all. They are not the same effect, so the narrower printing gets a shield
	// of its own rather than borrowing the wider one — an ability that is not a Summon still gets
	// through here.
	// =========================================================================================

	private static final String LEVIATHAN_OPTION =
			"During this turn, if a Forward you control is dealt damage by a Summon, "
			+ "the damage becomes 0 instead.";

	private static final String LEVIATHAN_REPRINT_OPTION =
			"During this turn, if a Forward you control is dealt damage by a Summon or an ability, "
			+ "the damage becomes 0 instead.";

	/** Runs {@code option} for P1 and returns the board, with one P1 Forward on the field. */
	private static MainWindow leviathanBoard(String option) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Ally", "Water", 3, 9000));

		GameContext ctx = mw.buildGameContext(true);
		Consumer<GameContext> effect = ActionResolver.parse(option, null);
		assertNotNull(effect, "the printed wording has to parse: " + option);
		effect.accept(ctx);
		return mw;
	}

	/**
	 * Deals 5000 to the P1 Forward with the resolution flagged the way a Summon's or a plain
	 * ability's would be, and reports how much of it landed.
	 */
	private static int leviathanDamageThrough(MainWindow mw, boolean asSummon) {
		GameContext ctx = mw.buildGameContext(true);
		int before = mw.p1ForwardDamage.get(0);
		mw.currentResolutionIsSummon = asSummon;
		try {
			ctx.damageP1Forward(0, 5000);
		} finally {
			mw.currentResolutionIsSummon = false;
		}
		return mw.p1ForwardDamage.get(0) - before;
	}

	@Test
	void leviathanStopsSummonDamage() {
		MainWindow mw = leviathanBoard(LEVIATHAN_OPTION);
		assertEquals(0, leviathanDamageThrough(mw, true));
	}

	@Test
	void butLetsAPlainAbilityThrough() {
		// The printing says "by a Summon" and stops there. Reading it as the reprint's wider
		// sentence would blank damage the card never mentions.
		MainWindow mw = leviathanBoard(LEVIATHAN_OPTION);
		assertEquals(5000, leviathanDamageThrough(mw, false));
	}

	@Test
	void whileTheReprintStopsBoth() {
		MainWindow mw = leviathanBoard(LEVIATHAN_REPRINT_OPTION);
		assertEquals(0, leviathanDamageThrough(mw, true));
		assertEquals(0, leviathanDamageThrough(mw, false));
	}

	@Test
	void leviathanNamesAllThreeOfItsOptions() {
		String summon = "Select 1 of the 3 following actions. "
				+ "\"Choose 1 Forward. Return it to its owner's hand.\" "
				+ "\"Choose 1 action ability. Cancel its effect.\" "
				+ "\"" + LEVIATHAN_OPTION + "\"";
		assertEquals("SelectFollowingActions(1 of 3: ChooseCharacter / ReturnToOwnersHand "
						+ "| CancelAbilityOnStack | AllOwnForwardsNullifyAbilityDamage)",
				ActionResolver.fullDescription(summon, null));
	}

	// =========================================================================================
	// 7-084C Yojimbo: "Choose 1 Forward you control and 1 Forward opponent controls. The former
	// gains +1000 power until the end of the turn. Then, each Forward deals damage equal to its
	// power to the other. If Yojimbo results from an EX Burst, the former gains +3000 power until
	// the end of the turn instead. Then, each Forward deals damage equal to its power to the other."
	//
	// The card spells the effect out once per case, so the mutual-damage sentence appears twice;
	// there is still one boost and one fight. The mutual-damage followup find()'d that sentence
	// and dropped the boost.
	// =========================================================================================

	private static final String YOJIMBO_7_084C = "Choose 1 Forward you control and 1 Forward opponent "
			+ "controls. The former gains +1000 power until the end of the turn. Then, each Forward deals "
			+ "damage equal to its power to the other. If Yojimbo results from an EX Burst, the former "
			+ "gains +3000 power until the end of the turn instead. Then, each Forward deals damage equal "
			+ "to its power to the other.";

	/** P2 casts Yojimbo: its 7000 Forward against P1's 9000 one. */
	private static MainWindow castYojimbo(boolean exBurst, CardData mine, CardData theirs) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		Consumer<GameContext> fn = ActionResolver.parse(YOJIMBO_7_084C,
				makeSummon("Yojimbo", "Earth", 4, YOJIMBO_7_084C));
		assertNotNull(fn);
		fn.accept(mw.buildGameContext(false, exBurst));
		return mw;
	}

	@Test
	void yojimboBoostsTheFormerBy1000ThenTheyFightOnce() {
		CardData mine = makeForward("Mine", "Earth", 3, 7000);
		CardData theirs = makeForward("Theirs", "Fire", 4, 9000);
		MainWindow mw = castYojimbo(false, mine, theirs);

		// 8000 into a 9000: it survives with 8000 damage. 9000 back into an 8000: broken.
		assertTrue(mw.p1ForwardCards.contains(theirs));
		assertEquals(8000, mw.p1ForwardDamage.get(mw.p1ForwardCards.indexOf(theirs)),
				"the boost is in the damage dealt, and it is dealt once");
		assertFalse(mw.p2ForwardCards.contains(mine));
	}

	@Test
	void yojimboFromAnExBurstBoostsBy3000Instead() {
		CardData mine = makeForward("Mine", "Earth", 3, 7000);
		CardData theirs = makeForward("Theirs", "Fire", 4, 9000);
		MainWindow mw = castYojimbo(true, mine, theirs);

		// 10000 into a 9000: broken. 9000 back into a 10000: it survives.
		assertFalse(mw.p1ForwardCards.contains(theirs));
		assertTrue(mw.p2ForwardCards.contains(mine));
		assertEquals(9000, mw.p2ForwardDamage.get(mw.p2ForwardCards.indexOf(mine)));
		assertEquals(10000, mw.effectiveP2ForwardPower(mw.p2ForwardCards.indexOf(mine)));
	}

	// =========================================================================================
	// 9-002H Ifrita: "a total of 5 or more Card Name Ifrita and/or Card Name Ifrit in your Break
	// Zone (before paying the cost for Ifrita)": an Ifrit discarded to pay for her does not count.
	// =========================================================================================

	@Test
	void ifritaCountsHerBreakZoneAsItWasBeforeHerCost() {
		MainWindow mw = new MainWindow();
		CardData ifrita = makeSummon("Ifrita", "Fire", 3, "");
		for (int i = 0; i < 4; i++) mw.gameState.getP1BreakZone().add(makeSummon("Ifrit", "Fire", 2, ""));
		CardData discarded = makeSummon("Ifrit", "Fire", 2, "");
		mw.gameState.getP1BreakZone().add(discarded);
		DamageInsteadCondition parsed = ActionResolver.parseDamageInsteadCondition("you have a total of 5 or "
				+ "more Card Name Ifrita and/or Card Name Ifrit in your Break Zone (before paying the cost for Ifrita)");
		assertInstanceOf(DamageInsteadCondition.BreakZoneNamesBeforePayingAtLeast.class, parsed);
		DamageInsteadCondition.BreakZoneNamesBeforePayingAtLeast b =
				(DamageInsteadCondition.BreakZoneNamesBeforePayingAtLeast) parsed;
		DamageInsteadCondition bound = new DamageInsteadCondition.BreakZoneNamesBeforePayingAtLeast(
				b.min(), b.names(), b.payerName(), ifrita);
		GameContext ctx = mw.buildGameContext(true);
		assertTrue(ActionResolver.insteadConditionMet(ctx, bound), "five in the Break Zone, none paid for her");

		mw.lastCastPaymentCard = ifrita;
		mw.lastCastPaymentDiscards.add(discarded);
		assertFalse(ActionResolver.insteadConditionMet(ctx, bound), "one of the five arrived paying for her");
	}

	// =========================================================================================
	// 9-017C Belias: "Choose 1 Forward. Until the end of the turn, it gains +1000 power and First
	// Strike. Draw 1 card. If you have received 4 points of damage or more, it also gains Haste
	// until the end of the turn."
	//
	// The trailing gate sat past the draw and was dropped.
	// =========================================================================================

	private static final String BELIAS_9_017C = "EX BURST Choose 1 Forward. Until the end of the turn, it "
			+ "gains +1000 power and First Strike. Draw 1 card. If you have received 4 points of damage or "
			+ "more, it also gains Haste until the end of the turn.";

	/** P2 casts Belias at its one Forward with {@code damage} points of damage; true if it gained Haste. */
	private static boolean beliasGrantsHaste(int damage) {
		MainWindow mw = new MainWindow();
		CardData mine = makeForward("Mine", "Fire", 3, 7000);
		placeP2Forward(mw, mine);
		fillP2Deck(mw, 1);
		for (int i = 0; i < damage; i++) mw.gameState.getP2DamageZone().add(makeForward("Damage " + i, "Fire", 1, 1000));
		castAsP2(mw, makeSummon("Belias", "Fire", 1, BELIAS_9_017C));

		assertEquals(8000, mw.effectiveP2ForwardPower(0));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.FIRST_STRIKE));
		assertEquals(1, mw.gameState.getP2Hand().size(), "the draw happens either way");
		return mw.effectiveP2HasTrait(0, CardData.Trait.HASTE);
	}

	@Test
	void beliasGrantsHasteOnlyAtFourDamage() {
		assertFalse(beliasGrantsHaste(3));
		assertTrue(beliasGrantsHaste(4));
	}

	// =========================================================================================
	// 9-068H Mist Dragon, second option: "Choose 1 Forward you control. Dull it. During this turn,
	// if it is dealt damage, the damage becomes 0 instead."
	//
	// The inward-pointing twin of Shiva above, and read by the same branch. Split on ". ", the
	// dull branch would claim the first sentence and the shield would fall to the secondary
	// parser, where "it" names nothing — which is what left the option reported as "Dull + ?".
	// =========================================================================================

	private static final String MIST_DRAGON_OPTION =
			"Choose 1 Forward you control. Dull it. During this turn, if it is dealt damage, "
			+ "the damage becomes 0 instead.";

	@Test
	void mistDragonDullsItsOwnForwardAndShieldsIt() {
		MainWindow mw = new MainWindow();
		CardData ally = makeForward("Ally", "Water", 3, 7000);
		placeP1Forward(mw, ally);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(true, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(MIST_DRAGON_OPTION, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.allIncomingDmgZeroThisTurnSet.contains(ally));

		ctx.damageTarget(fwd(true, 0), 3000);
		ctx.damageTarget(fwd(true, 0), 3000);
		assertEquals(0, mw.p1ForwardDamage.get(0), "both hits became 0");
	}

	@Test
	void mistDragonNamesAllThreeOfItsOptions() {
		String summon = "Select 1 of the 3 following actions. "
				+ "\"Choose 1 Summon of cost 5 or less. Cancel its effect.\" "
				+ "\"Choose 1 Forward you control. Dull it. During this turn, if it is dealt "
				+ "damage, the damage becomes 0 instead.\" "
				+ "\"Remove all the cards in your opponent's Break Zone from the game. Draw 1 card.\"";
		assertEquals("SelectFollowingActions(1 of 3: ChooseCharacter / CancelEffect "
						+ "| ChooseCharacter / DullAndShieldAllIncoming "
						+ "| RemoveAllOppBzFromGame + DrawCards)",
				ActionResolver.fullDescription(summon, null));
	}

	// =========================================================================================
	// 10-068C Cu Sith: "EX BURST Choose 1 Forward or Backup in your Break Zone. Add it to your
	// hand." — "your" is the resolving player's, so the salvaged card must land in the hand of
	// whoever cast it. GameContext#addTargetToHand used to append to P1's hand unconditionally,
	// which handed P2's own Break Zone card to P1 whenever the CPU cast this.
	// =========================================================================================

	private static final String CU_SITH_TEXT =
			"[[ex]]EX BURST [[/]]Choose 1 Forward or Backup in your Break Zone. Add it to your hand.";

	private static CardData makeCuSith() {
		return new CardData(null, "Cu Sith", "Earth", 2, 0, "Summon", false, 0, true, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, CU_SITH_TEXT);
	}

	/**
	 * Resolves Cu Sith's salvage for {@code casterIsP1}, with a single Forward sitting in that
	 * player's Break Zone. One eligible card makes the choice deterministic on both sides.
	 */
	private static MainWindow runCuSithSalvage(boolean casterIsP1) {
		MainWindow mw = new MainWindow();
		CardData cuSith = makeCuSith();

		CardData salvaged = makeForward("Salvage Me", "Earth", 3, 5000);
		mw.gameState.getIdentity().put(salvaged, casterIsP1);
		(casterIsP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone()).add(salvaged);

		GameContext ctx = mw.buildGameContext(casterIsP1);
		ActionResolver.parse(cuSith.summonEffect(), cuSith).accept(ctx);
		return mw;
	}

	@Test
	void cuSithReturnsToTheCastingPlayersHandForP2() {
		MainWindow mw = runCuSithSalvage(false);
		assertEquals(1, mw.gameState.getP2Hand().size(), "the CPU salvages into its own hand");
		assertEquals("Salvage Me", mw.gameState.getP2Hand().get(0).name());
		assertTrue(mw.gameState.getP1Hand().isEmpty(), "P1 must not receive the CPU's Break Zone card");
		assertTrue(mw.gameState.getP2BreakZone().isEmpty(), "the card leaves the CPU's Break Zone");
	}

	@Test
	void cuSithReturnsToTheCastingPlayersHandForP1() {
		MainWindow mw = runCuSithSalvage(true);
		assertEquals(1, mw.gameState.getP1Hand().size(), "P1 salvages into their own hand");
		assertEquals("Salvage Me", mw.gameState.getP1Hand().get(0).name());
		assertTrue(mw.gameState.getP2Hand().isEmpty(), "P2 must not receive P1's Break Zone card");
		assertTrue(mw.gameState.getP1BreakZone().isEmpty(), "the card leaves P1's Break Zone");
	}

	// =========================================================================================
	// 10-076H Titan, third option: "All the Forwards you control gain 'This Forward cannot become
	// dull by your opponent's Summons or abilities' until the end of the turn."
	//
	// The board-wide twin of the single-target grant, and granted the same way a keyword is: the
	// quoted sentence is a protection with a trait behind it, temporary for the turn either way.
	//
	// Titan quotes with ', which is also the apostrophe in "your opponent's", so there is no
	// delimiter to split the blob on — it is taken whole. 23-039R Asura, the other printing of
	// this shape, uses " and grants two at once. A quote with no trait behind it is declined
	// rather than granted in part.
	// =========================================================================================

	private static final String TITAN_OPTION =
			"All the Forwards you control gain 'This Forward cannot become dull by your "
			+ "opponent's Summons or abilities' until the end of the turn.";

	@Test
	void titanGrantsTheProtectionToEveryForwardYouControl() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("First",  "Earth", 3, 7000));
		placeP1Forward(mw, makeForward("Second", "Earth", 2, 5000));
		placeP2Forward(mw, makeForward("Theirs", "Fire",  3, 7000));

		GameContext ctx = mw.buildGameContext(true);
		Consumer<GameContext> effect = ActionResolver.parse(TITAN_OPTION, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_DULLED_BY_OPP));
		assertTrue(mw.effectiveP1HasTrait(1, CardData.Trait.CANNOT_BE_DULLED_BY_OPP));
		assertFalse(mw.effectiveP2HasTrait(0, CardData.Trait.CANNOT_BE_DULLED_BY_OPP),
				"\"you control\" is a filter, not decoration");
	}

	@Test
	void andReadsTheOtherWordOrderTheSameWay() {
		// 23-039R Asura fronts the duration and grants two protections, double-quoted.
		assertEquals("AllFieldQuotedProtectionGrant",
				ActionResolver.fullDescription(
						"Until the end of the turn, all the Forwards you control gain \"This Forward "
						+ "cannot be returned to its owner's hand by your opponent's Summons or "
						+ "abilities.\" and \"The power of this Forward cannot be decreased by your "
						+ "opponent's Summons or abilities.\"", null));
	}

	@Test
	void andDeclinesAQuotedAbilityWithNoTraitBehindIt() {
		// "cannot be broken" prints in exactly this shape and has no trait here. Granting the ones
		// that were understood and dropping the rest would look like the card had resolved.
		assertNull(ActionResolverFieldAbility.tryParseAllFieldQuotedProtectionGrant(
				"All the Forwards you control gain \"This Forward cannot be broken\" until the end of the turn."));
	}

	@Test
	void andDeclinesTheSameGrantWithNoDurationAtAll() {
		// Without a duration this is a permanent printed field ability, which must not be
		// shortened to a turn.
		assertNull(ActionResolverFieldAbility.tryParseAllFieldQuotedProtectionGrant(
				"All the Forwards you control gain 'This Forward cannot become dull by your "
				+ "opponent's Summons or abilities'."));
	}

	@Test
	void titanNamesAllFourOfItsOptions() {
		String summon = "Select up to 2 of the 4 following actions. "
				+ "\"Choose 1 Forward you control. It gains +1000 power until the end of the turn.\" "
				+ "\"All the Forwards you control gain Brave until the end of the turn.\" "
				+ "\"" + TITAN_OPTION + "\" "
				+ "\"Draw 1 card.\"";
		assertEquals("SelectFollowingActions(up to 2 of 4: ChooseCharacter / PowerBoost "
						+ "| AllFieldKeywordGrant | AllFieldQuotedProtectionGrant | DrawCards)",
				ActionResolver.fullDescription(summon, null));
	}

	// =========================================================================================
	// 12-049H Diabolos: "Choose 1 Forward. If its power has been increased or decreased, break it."
	//
	// The plain break followup scans with find() and matched "break it" inside the condition, so
	// the summon broke whatever was chosen — the question it is entirely about was never asked.
	//
	// The question is asked as a comparison rather than as a log of what happened: an
	// until-end-of-turn boost, a reduction, a permanent boost, a one-shot "its power becomes N"
	// and a continuous field grant all land in the same place, and the card does not distinguish
	// them.
	// =========================================================================================

	private static final String DIABOLOS_BREAK_SUMMON =
			"Choose 1 Forward. If its power has been increased or decreased, break it.";

	/** Two P2 Forwards at printed power, for the power-change tests to move one of. */
	private static MainWindow twoPlainForwards(MainWindow mw) {
		placeP2Forward(mw, makeForward("Untouched", "Fire", 3, 7000));
		placeP2Forward(mw, makeForward("Moved", "Fire", 3, 7000));
		return mw;
	}

	@Test
	void aForwardAtItsPrintedPowerIsChosenAndLeftStanding() {
		// The choose still happens — being chosen is an event other cards watch, and only the
		// break is conditional.
		MainWindow mw = twoPlainForwards(new MainWindow());
		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));

		Consumer<GameContext> effect = ActionResolver.parse(DIABOLOS_BREAK_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		assertEquals(2, mw.p2ForwardCards.size(), "nothing had moved, so nothing breaks");
	}

	@Test
	void andABoostedOneIsBroken() {
		MainWindow mw = twoPlainForwards(new MainWindow());
		GameContext ctx = mw.buildGameContext(true);
		ctx.boostTarget(fwd(false, 1), 2000, EnumSet.noneOf(CardData.Trait.class));
		ctx.preloadTargets(List.of(fwd(false, 1)));

		ActionResolver.parse(DIABOLOS_BREAK_SUMMON, null).accept(ctx);

		assertEquals(List.of("Untouched"), mw.p2ForwardCards.stream().map(CardData::name).toList());
	}

	@Test
	void andSoIsAWeakenedOne() {
		// "Increased or decreased" — the card asks whether the power moved, not which way.
		MainWindow mw = twoPlainForwards(new MainWindow());
		GameContext ctx = mw.buildGameContext(true);
		ctx.reduceTarget(fwd(false, 1), 1000, EnumSet.noneOf(CardData.Trait.class));
		ctx.preloadTargets(List.of(fwd(false, 1)));

		ActionResolver.parse(DIABOLOS_BREAK_SUMMON, null).accept(ctx);

		assertEquals(List.of("Untouched"), mw.p2ForwardCards.stream().map(CardData::name).toList());
	}

	@Test
	void aOneShotPowerOverrideCountsAsMovedToo() {
		// "Its power becomes N" replaces the printed value rather than adding to it, and the
		// comparison catches it for the same reason it catches the others.
		MainWindow mw = twoPlainForwards(new MainWindow());
		GameContext ctx = mw.buildGameContext(true);
		ctx.setTargetBasePower(fwd(false, 1), 3000);

		assertTrue(ctx.targetPowerHasChanged(fwd(false, 1)));
		assertFalse(ctx.targetPowerHasChanged(fwd(false, 0)));
	}

	@Test
	void theConditionIsNamedRatherThanDroppedFromTheReport() {
		assertEquals("ChooseCharacter / BreakIfPowerChanged",
				ActionResolver.fullDescription(DIABOLOS_BREAK_SUMMON, null));
	}

	@Test
	void aPlainBreakFollowupIsStillUnconditional() {
		// The guard that makes the ordering above safe rather than lucky: the plain break has to
		// keep claiming its own text, and only its own.
		assertEquals("ChooseCharacter / Break",
				ActionResolver.fullDescription("Choose 1 Forward. Break it.", null));
	}

	// =========================================================================================

	// =========================================================================================
	// 12-108C Remora: "Choose 1 Forward. Draw 1 card. Then, until the end of the turn, it loses
	// 2000 power for each card in your hand."
	//
	// The sentence split put the draw in the primary followup and the reduction in the secondary,
	// and neither half means anything alone — "it" in the second names the card the first never
	// chose. Both were logged as unimplemented and nothing happened at all.
	// =========================================================================================

	private static final String REMORA_SUMMON =
			"Choose 1 Forward. Draw 1 card. Then, until the end of the turn, "
			+ "it loses 2000 power for each card in your hand.";

	@Test
	void remoraDrawsBeforeItCountsTheHand() {
		// The order is the point: the drawn card is in hand when the hand is counted, so it is
		// worth another 2000 of reduction. A hand of 2 that draws to 3 reduces by 6000.
		CardData remora = makeForward("Remora", "Water", 2, 0);
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.yourHandSize()).thenReturn(3);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));

		Consumer<GameContext> effect = ActionResolver.parse(REMORA_SUMMON, remora);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		InOrder order = inOrder(ctx);
		order.verify(ctx).drawCards(1);
		order.verify(ctx).yourHandSize();
		verify(ctx).reduceTarget(eq(victim), eq(6000), any());
	}

	@Test
	void andReportsBothHalvesAsOneClause() {
		CardData remora = makeForward("Remora", "Water", 2, 0);
		assertEquals("ChooseCharacter / DrawThenPowerReduceForEachHand",
				ActionResolver.fullDescription(REMORA_SUMMON, remora));
	}

	// =========================================================================================
	// 13-053R Alexander: "Choose 1 Forward you control and up to 1 Forward opponent controls.
	// Until the end of the turn, the former gains +3000 power and "This Forward cannot become dull
	// by your opponent's Summons or abilities." If you have received 5 points of damage or more,
	// also deal the latter damage equal to the highest power Forward you control."
	//
	// The former/latter branch for this shape was already written; the pattern reaching it allowed
	// a single non-word character after "abilities" where the printing has two — the grant's own
	// full stop and then the closing quote. Declined on that alone, the card fell past every
	// former/latter case to the plain choose parser, which read the selection and then neither
	// half of the effect.
	// =========================================================================================

	private static final String ALEXANDER_FORMER_LATTER_SUMMON =
			"EX BURST Choose 1 Forward you control and up to 1 Forward opponent controls. "
			+ "Until the end of the turn, the former gains +3000 power and \"This Forward cannot "
			+ "become dull by your opponent's Summons or abilities.\" If you have received 5 points "
			+ "of damage or more, also deal the latter damage equal to the highest power Forward "
			+ "you control.";

	private static final ForwardTarget ALEX_MINE   = new ForwardTarget(true,  0, ForwardTarget.CardZone.FORWARD);
	private static final ForwardTarget ALEX_THEIRS = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);

	/** The two selections come back from consecutive {@code selectCharacters} calls, in text order. */
	private static GameContext alexanderContext(int damageTaken) {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selfDamageCount()).thenReturn(damageTaken);
		when(ctx.selfHighestForwardPower()).thenReturn(9000);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(ALEX_MINE))
				.thenReturn(List.of(ALEX_THEIRS));
		return ctx;
	}

	@Test
	void alexanderBoostsAndProtectsYourForwardBelowTheThreshold() {
		GameContext ctx = alexanderContext(4);

		Consumer<GameContext> effect = ActionResolver.parse(ALEXANDER_FORMER_LATTER_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		verify(ctx).boostTarget(ALEX_MINE, 3000,
				EnumSet.of(CardData.Trait.CANNOT_BE_DULLED_BY_OPP));
		verify(ctx, never()).damageTarget(any(), anyInt());
	}

	@Test
	void andBurnsTheirsForYourHighestPowerOnceYouHaveTakenFive() {
		GameContext ctx = alexanderContext(5);

		ActionResolver.parse(ALEXANDER_FORMER_LATTER_SUMMON, null).accept(ctx);

		verify(ctx).boostTarget(ALEX_MINE, 3000,
				EnumSet.of(CardData.Trait.CANNOT_BE_DULLED_BY_OPP));
		verify(ctx).damageTarget(ALEX_THEIRS, 9000);
	}

	@Test
	void alexanderIsReadAsTheFormerLatterCardItIs() {
		assertEquals("ChooseFormerLatter", ActionResolver.fullDescription(ALEXANDER_FORMER_LATTER_SUMMON, null));
	}

	// =========================================================================================
	// 14-047R Choco/Mog: "Choose 1 Forward. Deal it 1000 damage for each Character you control.
	// If you control a Job Chocobo or Card Name Chocobo and a Job Moogle or Card Name Moogle,
	// draw 1 card."
	//
	// A conjunction of two conditions, each wanting a card of its own — not a disjunction, and not
	// one condition over one card. Both existing modes claimed it whole and got it wrong: named
	// mode read it as an either-or over the names "Chocobo and a Job Moogle" and "Moogle", so it
	// drew on any Moogle at all and never on the pair; count mode's Job group, which runs to the
	// next " or Card Name", took 7-062R Hope's "a Job Father and a Job Mother" as one Job name
	// that matches nobody.
	// =========================================================================================

	private static final String CHOCO_MOG_CONDITION =
			"a Job Chocobo or Card Name Chocobo and a Job Moogle or Card Name Moogle";

	/** A board with the given Forwards on P1's side, asked whether Choco/Mog's condition holds. */
	private static boolean chocoMogConditionOn(CardData... p1Forwards) {
		MainWindow mw = new MainWindow();
		for (CardData c : p1Forwards) placeP1Forward(mw, c);
		ControlCondition cond = CardData.parseControlCondition(CHOCO_MOG_CONDITION);
		assertNotNull(cond, "the condition has to parse for the rest of this to mean anything");
		return mw.controlConditionMet(cond, true);
	}

	@Test
	void chocoMogNeedsBothHalves() {
		assertFalse(chocoMogConditionOn(makeJobCard("Chocobo", "Wind", "Forward", "Chocobo")),
				"a Chocobo alone is not enough");
		assertFalse(chocoMogConditionOn(makeJobCard("Mog", "Wind", "Forward", "Moogle")),
				"nor is a Moogle alone — which is what the old read drew on");
		assertTrue(chocoMogConditionOn(
						makeJobCard("Chocobo", "Wind", "Forward", "Chocobo"),
						makeJobCard("Mog", "Wind", "Forward", "Moogle")),
				"both together satisfy it");
	}

	@Test
	void andEachHalfTakesTheJobOrTheCardName() {
		// "a Job Chocobo or Card Name Chocobo" — either arm satisfies that half on its own, which
		// is the disjunction named mode dropped when it read the name and threw the Job away.
		assertTrue(chocoMogConditionOn(
						makeForward("Chocobo", "Wind", 3, 7000),                       // by name
						makeJobCard("Mog", "Wind", "Forward", "Moogle")),              // by job
				"a card named Chocobo pairs with a Job Moogle");
		assertTrue(chocoMogConditionOn(
						makeJobCard("Boco", "Wind", "Forward", "Chocobo"),             // by job
						makeForward("Moogle", "Wind", 3, 7000)),                       // by name
				"and a Job Chocobo pairs with a card named Moogle");
	}

	@Test
	void andOneCardCannotSatisfyBothHalves() {
		// Each half wants a card of its own, which is what separates this from the per-card OR the
		// record already had.
		assertFalse(chocoMogConditionOn(makeJobCard("Chocobo", "Wind", "Forward", "Chocobo|Moogle")),
				"a single Character counts once, however many of the Jobs it carries");
	}

	@Test
	void andHopesPlainerConjunctionReadsTheSameWay() {
		// 7-062R Hope, the other printing of this shape and the one count mode mangled.
		ControlCondition cond = CardData.parseControlCondition("a Job Father and a Job Mother");
		assertNotNull(cond);
		assertEquals("1+ Father & 1+ Mother", cond.toString());
	}

	@Test
	void chocoMogsConditionIsDescribedAsTheConjunctionItIs() {
		assertEquals("ChooseCharacter / DamageForEach + IfControl(1+ Chocobo/Chocobo & 1+ Moogle/Moogle: DrawCards)",
				ActionResolver.fullDescription(
						"EX BURST Choose 1 Forward. Deal it 1000 damage for each Character you control. "
						+ "If you control a Job Chocobo or Card Name Chocobo and a Job Moogle or "
						+ "Card Name Moogle, draw 1 card.", null));
	}

	// =========================================================================================
	// 14-060R Carbuncle: "EX BURST All the Forwards you control gain +2000 power until the end of the
	// turn. If all the Backups you control have Earth Element, draw 1 card."
	//
	// It resolved its power grant and then its conditional draw, but neither the pattern name nor
	// the description could say so: the gate that carries it was in parse()'s chain and in
	// neither report chain, so the record read "AllFieldPowerBoost + ?".
	// =========================================================================================

	private static final String CARBUNCLE_14_060R =
			"[[ex]]EX BURST[[/]] All the Forwards you control gain +2000 power until the end of "
			+ "the turn. If all the Backups you control have Earth Element, draw 1 card.";
	private static GameContext resolveCarbuncle(boolean allBackupsEarth) {
		CardData carbuncle = makeSummon("Carbuncle", "Earth", 1, CARBUNCLE_14_060R);
		Consumer<GameContext> fn = ActionResolver.parse(carbuncle.summonEffect(), carbuncle);
		assertNotNull(fn, "Carbuncle's two halves should parse");
		GameContext ctx = mock(GameContext.class);
		when(ctx.controlConditionMet(any())).thenReturn(allBackupsEarth);
		fn.accept(ctx);
		return ctx;
	}

	@Test
	void carbuncleAlwaysBoostsAndDrawsOnlyOnAnAllEarthBench() {
		verify(resolveCarbuncle(true)).drawCards(1);
		GameContext mixed = resolveCarbuncle(false);
		verify(mixed).applyMassFieldPowerBoost(2000, true, false, false, true, null, -1, null, null, null,
				EnumSet.noneOf(CardData.Trait.class));
		verify(mixed, never()).drawCards(anyInt());
	}

	@Test
	void carbunclesConditionalDrawIsNamedRatherThanLeftAsAQuestionMark() {
		CardData carbuncle = makeSummon("Carbuncle", "Earth", 1, CARBUNCLE_14_060R);
		String text = carbuncle.summonEffect();
		assertEquals("AllFieldPowerBoost + IfAllHaveElement",
				ActionResolver.matchedPatternName(text, carbuncle));
		assertEquals("AllFieldPowerBoost + IfAllHaveElement(Backups=Earth: DrawCards)",
				ActionResolver.fullDescription(text, carbuncle));
	}


	// =========================================================================================
	// 15-014H Brynhildr: "EX BURST Choose 1 Forward. Deal it 5000 damage. When it is put from the
	// field into the Break Zone this turn, draw 1 card." — the trailing sentence is a delayed
	// trigger marked onto the chosen Forward, firing for the caster whenever that Forward later
	// leaves the field for the Break Zone. The mark has to be applied before the damage, or the
	// common case (5000 is lethal) would break the Forward before anything was watching it.
	// =========================================================================================

	private static final String BRYNHILDR_TEXT =
			"Choose 1 Forward. Deal it 5000 damage. "
			+ "When it is put from the field into the Break Zone this turn, draw 1 card.";

	/** P1 casts Brynhildr at P2's only Forward, which has {@code oppPower} power. */
	private static MainWindow castBrynhildrAt(int oppPower) {
		MainWindow mw = new MainWindow();
		mw.gameState.initializeDeck(List.of(
				makeForward("Deck Card A", "Fire", 2, 5000),
				makeForward("Deck Card B", "Fire", 2, 5000)), List.of());
		mw.gameState.getP1Hand().clear();

		CardData victim = makeForward("Victim", "Ice", 3, oppPower);
		mw.gameState.getIdentity().put(victim, false);
		mw.placeP2CardInForwardZone(victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));
		ActionResolver.parse(BRYNHILDR_TEXT, null).accept(ctx);
		return mw;
	}

	@Test
	void brynhildrDrawsWhenItsOwnDamageIsLethal() {
		MainWindow mw = castBrynhildrAt(5000);
		assertTrue(mw.p2ForwardCards.isEmpty(), "5000 damage to a 5000-power Forward breaks it");
		assertEquals(1, mw.gameState.getP2BreakZone().size(), "the Forward reached the Break Zone");
		assertEquals(1, mw.gameState.getP1Hand().size(),
				"the mark is set before the damage, so a lethal hit still draws");
		assertTrue(mw.gameState.getP2Hand().isEmpty(),
				"the caster draws, not the broken Forward's controller");
	}

	@Test
	void brynhildrDrawsLaterWhenTheMarkedForwardSurvivesTheDamage() {
		MainWindow mw = castBrynhildrAt(9000);
		assertEquals(1, mw.p2ForwardCards.size(), "5000 damage does not break a 9000-power Forward");
		assertTrue(mw.gameState.getP1Hand().isEmpty(), "nothing drawn while it is still on the field");

		mw.breakP2Forward(0);   // broken later in the turn by something else
		assertEquals(1, mw.gameState.getP1Hand().size(),
				"the delayed trigger fires whatever puts it into the Break Zone");
	}

	@Test
	void brynhildrDrawTriggerFiresOnlyOnce() {
		MainWindow mw = castBrynhildrAt(5000);
		assertEquals(1, mw.gameState.getP1Hand().size());
		// A second trip through the Break Zone (e.g. replayed from the Break Zone and broken again)
		// must not re-fire a mark that was already consumed.
		mw.addToBreakZone(mw.gameState.getP2BreakZone().get(0), true);
		assertEquals(1, mw.gameState.getP1Hand().size(), "the mark is consumed when it fires");
	}

	@Test
	void brynhildrsDelayedClauseIsNamedRatherThanReportedUnread() {
		// The report used to read the clause as an unread tail on a card that had been drawing all
		// along. The mark carries an arbitrary effect rather than a card count, so the delayed half
		// names what it will do; a draw is one payload among several.
		assertEquals("ChooseCharacter / Damage + OnFieldToBz(DrawCards)",
				ActionResolver.fullDescription(BRYNHILDR_TEXT, null));
	}

	// =========================================================================================
	// 15-053H Diabolos: "Choose 1 Forward. Its power becomes 3000 until the end of the turn. If
	// you have cast 4 or more cards this turn, all the Forwards' power become 3000 until the end
	// of the turn instead."
	//
	// The corpus's only board-wide "power become". Wiring the gate without it left the upgraded
	// branch selected and then unable to do anything, which was a worse answer at 4+ casts than
	// the single-target base it replaced.
	//
	// The choose still happens on the upgraded branch: the card names a target and then overrides
	// every Forward, so the selection is kept for the "when chosen" triggers that watch it.
	// =========================================================================================

	private static final String DIABOLOS_SUMMON =
			"Choose 1 Forward. Its power becomes 3000 until the end of the turn. If you have cast "
			+ "4 or more cards this turn, all the Forwards' power become 3000 until the end of the "
			+ "turn instead.";

	@Test
	void diabolosSetsOneForwardUnderTheThreshold() {
		CardData diabolos = makeForward("Diabolos", "Ice", 4, 0);
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selfCardsCastThisTurn()).thenReturn(3);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));

		ActionResolver.parse(DIABOLOS_SUMMON, diabolos).accept(ctx);

		verify(ctx).setTargetBasePower(victim, 3000);
		verify(ctx, never()).setAllForwardsBasePower(anyInt());
	}

	@Test
	void andTheWholeBoardAtIt() {
		CardData diabolos = makeForward("Diabolos", "Ice", 4, 0);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selfCardsCastThisTurn()).thenReturn(4);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));

		ActionResolver.parse(DIABOLOS_SUMMON, diabolos).accept(ctx);

		verify(ctx).setAllForwardsBasePower(3000);
	}

	@Test
	void theBoardWideSweepReachesBothSides() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Ice", 5, 9000);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		placeP1Forward(mw, mine);
		placeP2Forward(mw, theirs);

		mw.buildGameContext(true).setAllForwardsBasePower(3000);

		assertEquals(3000, mw.effectiveP1ForwardPower(0), "his own side is not spared");
		assertEquals(3000, mw.effectiveP2ForwardPower(0));
	}

	// =========================================================================================
	// 17-014R Bahamut: "Choose up to 2 Forwards. Divide 10000 damage among them as you like. If
	// you have received 5 points of damage or more, divide 15000 damage among those instead."
	//
	// The divide parser has read the alternate amount all along; only the report could not say so,
	// because the description split the followup on ". " and left the condition in the tail. These
	// pin both halves: the amount actually dealt, and the name that now covers the whole clause.
	// =========================================================================================

	private static final String BAHAMUT_SUMMON =
			"Choose up to 2 Forwards. Divide 10000 damage among them as you like. "
			+ "If you have received 5 points of damage or more, divide 15000 damage among those "
			+ "instead. (Units must be 1000.)";

	/** One target, so the allocation dialog is skipped and the whole amount lands on it. */
	private static GameContext bahamutContext(ForwardTarget victim, int damageTaken) {
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selfDamageCount()).thenReturn(damageTaken);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));
		return ctx;
	}

	@Test
	void bahamutDividesTenThousandUntilYouHaveTakenFivePoints() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = bahamutContext(victim, 4);

		ActionResolver.parse(BAHAMUT_SUMMON, null).accept(ctx);

		verify(ctx).damageTarget(victim, 10000);
	}

	@Test
	void bahamutDividesFifteenThousandOnceYouHave() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = bahamutContext(victim, 5);

		ActionResolver.parse(BAHAMUT_SUMMON, null).accept(ctx);

		verify(ctx).damageTarget(victim, 15000);
	}

	@Test
	void bahamutsAlternateAmountIsNoLongerAnUnreadTail() {
		assertEquals("ChooseCharacter / DivideDamageInstead",
				ActionResolver.fullDescription(BAHAMUT_SUMMON, null));
	}

	// =========================================================================================
	// 17-027R Shiva, second option: "Choose 1 Forward. If it deals damage other than battle damage
	// to a Forward this turn, the damage becomes 0 instead."
	//
	// The narrow member of the outgoing-shield family. 23-024R Shiva stops everything its target
	// deals; this one stops only what it deals to a Forward, and only outside combat — so the
	// chosen Forward still attacks, still blocks, and still hurts the player normally.
	// =========================================================================================

	private static final String SHIVA_NON_BATTLE_OPTION =
			"Choose 1 Forward. If it deals damage other than battle damage to a Forward this turn, "
			+ "the damage becomes 0 instead.";

	/** Runs the option on the single P2 Forward and hands back the board it left. */
	private static MainWindow shivaNonBattleOn(CardData victim) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(SHIVA_NON_BATTLE_OPTION, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);
		return mw;
	}

	@Test
	void shivaBlanksTheAbilityDamageItsTargetDealsToAForward() {
		CardData victim = makeForward("Victim", "Ice", 3, 7000);
		MainWindow mw = shivaNonBattleOn(victim);
		placeP1Forward(mw, makeForward("Ally", "Fire", 3, 9000));

		GameContext ctx = mw.buildGameContext(false);
		mw.currentAbilitySource = victim;
		try {
			ctx.damageP1Forward(0, 5000);
		} finally {
			mw.currentAbilitySource = null;
		}

		assertEquals(0, mw.p1ForwardDamage.get(0), "non-battle damage to a Forward becomes 0");
	}

	@Test
	void butLeavesItsCombatDamageAlone() {
		// "other than battle damage" is the whole difference from 23-024R Shiva, which stops that too.
		CardData victim = makeForward("Victim", "Ice", 3, 7000);
		MainWindow mw = shivaNonBattleOn(victim);
		CardData blocker = makeForward("Blocker", "Fire", 3, 9000);
		placeP1Forward(mw, blocker);

		assertEquals(7000, mw.modifyOutgoingCombatDamage(false, 0, 7000, blocker));
	}

	@Test
	void andLeavesItsDamageToThePlayerAlone() {
		// "to a Forward" is the other half of the difference.
		CardData victim = makeForward("Victim", "Ice", 3, 7000);
		MainWindow mw = shivaNonBattleOn(victim);

		assertEquals(1, mw.combatDamagePointsToOpponent(victim));
	}

	@Test
	void shivasNarrowShieldIsNamedApartFromTheWideOne() {
		assertEquals("ChooseCharacter / ZeroOutgoingAbilityDamageToForwards",
				ActionResolver.fullDescription(SHIVA_NON_BATTLE_OPTION, null));
	}

	// =========================================================================================
	// 18-045C Dryad: "If you cast Dryad, you may remove 10 Wind cards in your Break Zone from the
	// game as an extra cost. … If you paid the extra cost, also draw 1 card."
	//
	// The conditional clause is settled before the effect is parsed, not while it resolves: the
	// Stack entry records whether the cost was paid, and resolution rewrites the text to match.
	// Parsing the printed text instead would leave the clause for a find() matcher to claim and
	// draw on every cast — which is why the raw wording deliberately does not resolve it.
	// =========================================================================================

	private static final String DRYAD_SUMMON =
			"Choose 1 Forward. Deal it 2000 damage for each Wind Character you control. "
			+ "If you paid the extra cost, also draw 1 card.";

	@Test
	void dryadDrawsOnlyWhenTheExtraCostWasPaid() {
		CardData dryad = makeForward("Dryad", "Wind", 3, 0);

		String paid = ActionResolver.applyExtraCostPaid(DRYAD_SUMMON);
		assertEquals("ChooseCharacter / DamageForEach + DrawCards",
				ActionResolver.fullDescription(paid, dryad));

		String notPaid = ActionResolver.stripExtraCostClause(DRYAD_SUMMON);
		assertEquals("ChooseCharacter / DamageForEach",
				ActionResolver.fullDescription(notPaid, dryad));
	}

	@Test
	void andTheDamageIsDealtEitherWay() {
		CardData dryad = makeForward("Dryad", "Wind", 3, 0);
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);

		for (String text : List.of(ActionResolver.applyExtraCostPaid(DRYAD_SUMMON),
				ActionResolver.stripExtraCostClause(DRYAD_SUMMON))) {
			GameContext ctx = mock(GameContext.class);
			when(ctx.consumePreloadedTargets()).thenReturn(null);
			when(ctx.countSelfFieldCards(anyBoolean(), anyBoolean(), anyBoolean(),
					any(), any(), any(), any(), anyInt())).thenReturn(3);
			when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
					anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
					any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
					.thenReturn(List.of(victim));

			Consumer<GameContext> effect = ActionResolver.parse(text, dryad);
			assertNotNull(effect, "both branches have to parse: " + text);
			effect.accept(ctx);

			verify(ctx).damageTarget(victim, 6000);   // 2000 x 3 Wind Characters
		}
	}

	@Test
	void andTheRawWordingDoesNotResolveTheClauseItself() {
		// The guard that keeps the draw honest. Resolution always hands parse() one of the two
		// rewritten texts; if the printed wording resolved the clause on its own, a cast that paid
		// nothing would still draw.
		CardData dryad = makeForward("Dryad", "Wind", 3, 0);
		assertEquals("ChooseCharacter / DamageForEach + ?",
				ActionResolver.fullDescription(DRYAD_SUMMON, dryad),
				"the clause is left unread on purpose — the Stack entry settles it");
	}

	// =========================================================================================
	// 18-096C Leviathan: "Choose 1 auto-ability triggered from a Forward. Cancel its effect. If you
	// paid the extra cost and that Forward is on the field, return that Forward to its owner's
	// hand." The paid branch used to resolve as the return alone, looking for a card called "that
	// Forward" — so paying the extra cost bought nothing and cost the cancel.
	// =========================================================================================

	private static final String LEVIATHAN_18_096C =
			"Choose 1 auto-ability triggered from a Forward. Cancel its effect. If you paid the extra "
			+ "cost and that Forward is on the field, return that Forward to its owner's hand.";

	@Test
	void leviathanPaidCancelsAndReturnsTheForwardTheAbilityCameFrom() {
		CardData from = makeForward("Trigger Source", "Fire", 4, 7000);
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.cancelFilteredAbilityOnStack(any(), any(), eq(false))).thenReturn(autoEntryFrom(from, false));

		ActionResolver.parse(ActionResolver.applyExtraCostPaid(LEVIATHAN_18_096C)).accept(ctx);

		verify(ctx).cancelFilteredAbilityOnStack(any(), any(), eq(false));
		verify(ctx).returnCardToOwnersHandIfOnField(from);
	}

	@Test
	void leviathanPaidReturnsNothingWhenNothingWasCancelled() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.cancelFilteredAbilityOnStack(any(), any(), eq(false))).thenReturn(null);

		ActionResolver.parse(ActionResolver.applyExtraCostPaid(LEVIATHAN_18_096C)).accept(ctx);

		verify(ctx, never()).returnCardToOwnersHandIfOnField(any());
	}

	@Test
	void leviathanUnpaidOnlyCancels() {
		CardData from = makeForward("Trigger Source", "Fire", 4, 7000);
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.cancelFilteredAbilityOnStack(any(), any(), eq(false))).thenReturn(autoEntryFrom(from, false));

		ActionResolver.parse(ActionResolver.stripExtraCostClause(LEVIATHAN_18_096C)).accept(ctx);

		verify(ctx).cancelFilteredAbilityOnStack(any(), any(), eq(false));
		verify(ctx, never()).returnCardToOwnersHandIfOnField(any());
		verify(ctx, never()).returnNamedCardToOwnersHand(any());
	}

	@Test
	void leviathanPrintedTextIsNotReadAsAReturnOfACardCalledThatForward() {
		// The runtime only ever parses the text after the extra-cost rewrite. Read as printed, the
		// payoff's condition is unknowable, so the whole ability declines rather than returning.
		assertNull(ActionResolver.parse(LEVIATHAN_18_096C));
	}

	@Test
	void theReturnedForwardIsTheCardItselfNotAnotherCopy() {
		// Identity, because CardData is a record: two copies of one printing are equal(), and the
		// copy still on the field is not "that Forward" once the one the ability came from is gone.
		MainWindow mw = new MainWindow();
		CardData onField = makeForward("Twin", "Fire", 3, 7000);
		CardData gone    = makeForward("Twin", "Fire", 3, 7000);
		placeP2Forward(mw, onField);
		GameContext ctx = mw.buildGameContext(true);

		assertFalse(ctx.returnCardToOwnersHandIfOnField(gone), "the card that left is not on the field");
		assertEquals(List.of(onField), mw.p2ForwardCards, "and the copy that stayed is not taken instead");

		assertTrue(ctx.returnCardToOwnersHandIfOnField(onField));
		assertTrue(mw.p2ForwardCards.isEmpty());
		assertTrue(mw.gameState.getP2Hand().stream().anyMatch(c -> c == onField), "back to its owner's hand");
	}

	// =========================================================================================
	// 18-136S Titan and 24-065H Fenrir: the two extra-cost Summons that refer back to the card
	// that paid the cost, rather than saying "If you paid the extra cost" like the other sixteen.
	//
	// That distinction is the whole reason they need resolution-time handling. The sixteen are
	// settled before the resolver ever sees them - applyExtraCostPaid/stripExtraCostClause rewrite
	// the text into one branch or the other - whereas these two have to read the payment itself,
	// through extraCostRemovedCardPower() and extraCostDiscardedCardCost().
	//
	// Fenrir was the more dangerous of the two: "break it and draw 1 card" contains "break it", so
	// the generic Break followup claimed it and broke the chosen Forward outright, with no cost
	// comparison and no extra cost paid. It counted as "fully parsed" in the coverage report the
	// whole time, because a wrong answer has no "?" in it.
	// =========================================================================================

	// --- Titan 18-136S --------------------------------------------------------------------

	private static final String TITAN_EFFECT =
			"Choose 1 Forward. Deal it damage equal to the power of the Forward removed by the extra cost.";

	@Test
	void titanDealsDamageEqualToTheRemovedForwardsPower() {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = contextChoosing(List.of(t));
		when(ctx.extraCostRemovedCardPower()).thenReturn(8000);

		Consumer<GameContext> fn = ActionResolver.parse(TITAN_EFFECT, makeForward("Titan", "Earth", 4, 0));
		assertNotNull(fn, "Titan should parse");
		fn.accept(ctx);

		verify(ctx).damageTarget(t, 8000);
	}

	/**
	 * The extra cost is optional, and Titan is all payoff — cast without paying it, the Summon
	 * chooses a Forward and does nothing. Dealing 0 damage would not be the same thing: it still
	 * counts as having dealt damage to everything watching for that.
	 */
	@Test
	void titanDealsNoDamageAtAllWhenTheExtraCostWentUnpaid() {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = contextChoosing(List.of(t));
		when(ctx.extraCostRemovedCardPower()).thenReturn(0);

		ActionResolver.parse(TITAN_EFFECT, makeForward("Titan", "Earth", 4, 0)).accept(ctx);

		verify(ctx, never()).damageTarget(any(), anyInt());
	}

	/** A Forward is still chosen either way — the choice is not conditional on the payment. */
	@Test
	void titanStillChoosesATargetWithoutTheExtraCost() {
		GameContext ctx = contextChoosing(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));
		when(ctx.extraCostRemovedCardPower()).thenReturn(0);

		ActionResolver.parse(TITAN_EFFECT, makeForward("Titan", "Earth", 4, 0)).accept(ctx);

		verify(ctx).selectCharacters(
				anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), anyInt(), any(), anyInt(), any(),
				anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
	}

	// --- Fenrir 24-065H -------------------------------------------------------------------

	private static final String FENRIR_EFFECT =
			"Choose 1 Forward opponent controls. If its cost is equal to the cost of the card "
			+ "discarded by the extra cost, break it and draw 1 card.";

	/** Chooses an opposing Forward of the given cost and resolves Fenrir against it. */
	private static GameContext fenrirBoard(int chosenCost, int discardedCost) {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = contextChoosing(List.of(t));
		when(ctx.p2Forward(0)).thenReturn(makeForward("Bahamut", "Fire", chosenCost, 9000));
		when(ctx.extraCostDiscardedCardCost()).thenReturn(discardedCost);
		return ctx;
	}

	@Test
	void fenrirBreaksAndDrawsWhenTheCostsMatch() {
		GameContext ctx = fenrirBoard(3, 3);

		Consumer<GameContext> fn = ActionResolver.parse(FENRIR_EFFECT, makeForward("Fenrir", "Ice", 3, 0));
		assertNotNull(fn, "Fenrir should parse");
		fn.accept(ctx);

		verify(ctx).breakTarget(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD));
		verify(ctx).drawCards(1);
	}

	/** The regression: an unequal cost used to break the Forward anyway. */
	@Test
	void fenrirDoesNothingWhenTheCostsDiffer() {
		GameContext ctx = fenrirBoard(5, 3);

		ActionResolver.parse(FENRIR_EFFECT, makeForward("Fenrir", "Ice", 3, 0)).accept(ctx);

		verify(ctx, never()).breakTarget(any());
		verify(ctx, never()).drawCards(anyInt());
	}

	/** No discard means no cost to match — not a match against cost 0. */
	@Test
	void fenrirDoesNothingWhenTheExtraCostWentUnpaid() {
		GameContext ctx = fenrirBoard(0, 0);

		ActionResolver.parse(FENRIR_EFFECT, makeForward("Fenrir", "Ice", 3, 0)).accept(ctx);

		verify(ctx, never()).breakTarget(any());
		verify(ctx, never()).drawCards(anyInt());
	}

	/**
	 * Both cards must report a followup naming the extra-cost payoff. Fenrir reporting plain
	 * "Break" is what let a card that ignored its own condition sit in the "fully parsed" bucket.
	 */
	@Test
	void theExtraCostPayoffsAreNamedRatherThanReportedAsPlainBreakOrNothing() {
		assertEquals("ChooseCharacter / IfCostEqualsExtraCostDiscardBreakDraw",
				ActionResolver.fullDescription(FENRIR_EFFECT, makeForward("Fenrir", "Ice", 3, 0)));
		assertEquals("ChooseCharacter / DamageEqualToExtraCostPower",
				ActionResolver.fullDescription(TITAN_EFFECT, makeForward("Titan", "Earth", 4, 0)));
	}

	/**
	 * The sixteen "If you paid the extra cost" Summons are decided before the resolver sees them,
	 * and must stay that way — 18-084C Ramuh resolves to one damage figure or the other, never to
	 * an extra-cost lookup.
	 */
	@Test
	void theIfYouPaidWordingIsStillSettledBeforeResolution() {
		String ramuh = "Choose 1 Forward. Deal it 7000 damage. "
				+ "If you paid the extra cost, deal it 15000 damage instead.";
		assertEquals("Choose 1 Forward. Deal it 15000 damage.", ActionResolver.applyExtraCostPaid(ramuh));
		assertEquals("Choose 1 Forward. Deal it 7000 damage.", ActionResolver.stripExtraCostClause(ramuh));

		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = contextChoosing(List.of(t));
		ActionResolver.parse(ActionResolver.applyExtraCostPaid(ramuh), makeForward("Ramuh", "Lightning", 3, 0))
				.accept(ctx);
		verify(ctx).damageTarget(t, 15000);
		verify(ctx, never()).extraCostRemovedCardPower();
	}

	// =========================================================================================
	// 19-101R Leviathan: "Choose 1 Forward opponent controls. Return it to its owner's hand. Until
	// the end of the next turn, your opponent cannot cast any copies of it."
	//
	// The corpus's only cast ban that outlives the turn it is set in, so it is a countdown of turn
	// boundaries rather than a turn-scoped flag: two from the cast, covering the rest of this turn
	// and the whole of the next.
	//
	// Both sentences are read as one clause. Split, "it" in the ban reaches the secondary parser
	// with no referent and the bounce runs alone — which is what left the card at "+ ?".
	// =========================================================================================

	private static final String LEVIATHAN_BOUNCE_SUMMON =
			"Choose 1 Forward opponent controls. Return it to its owner's hand. "
			+ "Until the end of the next turn, your opponent cannot cast any copies of it.";

	/** Bounces the single P2 Forward and returns the board it left. */
	private static MainWindow leviathanBounce(CardData victim) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(LEVIATHAN_BOUNCE_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);
		return mw;
	}

	@Test
	void leviathanBouncesAndBarsTheNameFromComingBack() {
		CardData victim = makeForward("Bounced", "Fire", 3, 7000);
		MainWindow mw = leviathanBounce(victim);

		assertTrue(mw.p2ForwardCards.isEmpty(), "the Forward went back to hand");
		assertFalse(mw.castRestrictionMet(victim, false), "and its owner may not cast it again");
	}

	@Test
	void andTheBanIsOnTheNameRatherThanTheCopy() {
		// "any copies of it" — a second printing of the same card is barred too.
		CardData victim = makeForward("Bounced", "Fire", 3, 7000);
		MainWindow mw = leviathanBounce(victim);

		assertFalse(mw.castRestrictionMet(makeForward("Bounced", "Fire", 3, 7000), false));
		assertTrue(mw.castRestrictionMet(makeForward("Someone Else", "Fire", 3, 7000), false),
				"and nothing else is");
	}

	@Test
	void andItBindsOnlyTheOpponent() {
		CardData victim = makeForward("Bounced", "Fire", 3, 7000);
		MainWindow mw = leviathanBounce(victim);

		assertTrue(mw.castRestrictionMet(victim, true), "the caster is free to cast their own copy");
	}

	@Test
	void andItSurvivesThisTurnsEndButNotTheNextTurnsEnd() {
		// "Until the end of the next turn" is two boundaries from the cast, which is the whole
		// reason this is a countdown rather than one of the turn-scoped flags beside it.
		CardData victim = makeForward("Bounced", "Fire", 3, 7000);
		MainWindow mw = leviathanBounce(victim);

		mw.ageCastNameBans();
		assertFalse(mw.castRestrictionMet(victim, false), "still barred through the next turn");

		mw.ageCastNameBans();
		assertTrue(mw.castRestrictionMet(victim, false), "and free once that turn has ended");
	}

	@Test
	void leviathansBanIsNoLongerAnUnreadTail() {
		assertEquals("ChooseCharacter / ReturnToOwnersHandAndBanCopies",
				ActionResolver.fullDescription(LEVIATHAN_BOUNCE_SUMMON, null));
	}

	// =========================================================================================
	// 21-054H Pandemonium: "Select 1 of the 2 following actions. If you have cast 2 or more cards
	// other than Pandemonium this turn, select up to 2 of the 2 following actions instead." Its
	// own cast is not one of those cards.
	// =========================================================================================

	@Test
	void pandemoniumsOwnCastDoesNotCountTowardItsUpgrade() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.selfCardsCastThisTurn()).thenReturn(2);
		when(ctx.countCardsNamedCastThisTurn("Pandemonium")).thenReturn(1);
		CardData pandemonium = makeSummon("Pandemonium", "Wind", 4, "");
		ActionResolver.parse("Select 1 of the 2 following actions. If you have cast 2 or more cards other than "
				+ "Pandemonium this turn, select up to 2 of the 2 following actions instead. \"Choose 1 Forward of "
				+ "cost 5 or more. Deal it 8000 damage.\" \"Draw 1 card.\"", pandemonium).accept(ctx);
		verify(ctx).chooseActions(eq(pandemonium), anyList(), eq(1), eq(false));
	}

	// =========================================================================================
	// 21-084H Odin: "Choose 1 Forward or Monster of cost 4 or less. Break it. If you control 5 or
	// more Lightning Characters, also draw 1 card." — the gate and its condition both parsed
	// already; the sole blocker was the additive "also" in front of the inner effect, which no
	// pattern starts with. parse() now strips it like the "Then, " connective it sits alongside.
	// =========================================================================================

	private static final String ODIN_TEXT =
			"Choose 1 Forward or Monster of cost 4 or less. Break it. "
			+ "If you control 5 or more Lightning Characters, also draw 1 card.";

	@Test
	void odinConditionalDrawParses() {
		assertNotNull(ActionResolver.parse(ODIN_TEXT, null), "the whole effect should parse");
		assertNotNull(ActionResolver.parse("If you control 5 or more Lightning Characters, also draw 1 card.", null),
				"the 'also'-prefixed gate should parse on its own");
		assertEquals("DrawCards", ActionResolver.fullDescription("also draw 1 card.", null),
				"a leading 'also' is stripped like any other additive connective");
	}

	/** P1 casts Odin at P2's lone cost-3 Forward, with {@code lightningAllies} Lightning Forwards out. */
	private static MainWindow castOdinWith(int lightningAllies) {
		MainWindow mw = new MainWindow();
		mw.gameState.initializeDeck(List.of(
				makeForward("Deck Card A", "Lightning", 2, 5000),
				makeForward("Deck Card B", "Lightning", 2, 5000)), List.of());
		mw.gameState.getP1Hand().clear();

		for (int i = 0; i < lightningAllies; i++) {
			CardData ally = makeForward("Bolt " + i, "Lightning", 2, 5000);
			mw.gameState.getIdentity().put(ally, true);
			mw.placeCardInForwardZone(ally);
		}
		CardData victim = makeForward("Victim", "Ice", 3, 7000);
		mw.gameState.getIdentity().put(victim, false);
		mw.placeP2CardInForwardZone(victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));
		ActionResolver.parse(ODIN_TEXT, null).accept(ctx);
		return mw;
	}

	@Test
	void odinDrawsWhenFiveLightningCharactersAreControlled() {
		MainWindow mw = castOdinWith(5);
		assertTrue(mw.p2ForwardCards.isEmpty(), "the cost 3 Forward is broken either way");
		assertEquals(1, mw.gameState.getP1Hand().size(), "5 Lightning Characters — the draw fires");
	}

	@Test
	void odinSkipsTheDrawBelowFiveLightningCharacters() {
		MainWindow mw = castOdinWith(4);
		assertTrue(mw.p2ForwardCards.isEmpty(), "the break is unconditional");
		assertTrue(mw.gameState.getP1Hand().isEmpty(), "only 4 Lightning Characters — no draw");
	}

	// =========================================================================================
	// 23-024R Shiva: "Choose 1 Forward. Dull it and Freeze it. During this turn, if it deals
	// damage to a Forward or a player, the damage becomes 0 instead."
	//
	// The outgoing mirror of Cockatrice's shield, and unspent for the same reason — the wording
	// names no number of hits. "To a Forward or a player" is every way the card can deal damage,
	// so the mark is read on all four paths: combat damage to a Forward, combat damage to the
	// player, and the same two from the Forward's own abilities. The last two share the mark that
	// 29-012H Neon's Runic already sets on a Stack entry; the combat ones cannot, because combat
	// has no ability source to ask about.
	// =========================================================================================

	private static final String SHIVA_SUMMON =
			"EX BURST Choose 1 Forward. Dull it and Freeze it. During this turn, if it deals "
			+ "damage to a Forward or a player, the damage becomes 0 instead.";

	/** Runs Shiva on the single P2 Forward and hands back the board it left. */
	private static MainWindow shivaOn(CardData victim) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(SHIVA_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);
		return mw;
	}

	@Test
	void shivaDullsFreezesAndDisarms() {
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		MainWindow mw = shivaOn(victim);

		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0), "neither half may be dropped");
		assertTrue(mw.p2ForwardFrozen.get(0));
		assertTrue(mw.allOutgoingDmgZeroThisTurnSet.contains(victim));
	}

	@Test
	void andItsCombatDamageToAForwardBecomesZero() {
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		MainWindow mw = shivaOn(victim);
		CardData blocker = makeForward("Blocker", "Ice", 3, 8000);
		placeP1Forward(mw, blocker);

		assertEquals(0, mw.modifyOutgoingCombatDamage(false, 0, 7000, blocker));
	}

	@Test
	void andItsCombatDamageToThePlayerBecomesZero() {
		// The other half of "a Forward or a player". Player damage is counted in points rather
		// than in power, so zeroing it means the point is never dealt at all.
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		MainWindow mw = shivaOn(victim);

		assertEquals(0, mw.combatDamagePointsToOpponent(victim));
	}

	@Test
	void andTheDisarmIsNotSpentByTheFirstHit() {
		// The difference between this and the one-shot outgoing shield beside it: the card names
		// no number of hits, so the second attack deals nothing either.
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		MainWindow mw = shivaOn(victim);
		CardData blocker = makeForward("Blocker", "Ice", 3, 8000);
		placeP1Forward(mw, blocker);

		assertEquals(0, mw.modifyOutgoingCombatDamage(false, 0, 7000, blocker));
		assertEquals(0, mw.modifyOutgoingCombatDamage(false, 0, 7000, blocker));
	}

	@Test
	void andAnotherCopyOfTheSameCardIsUntouched() {
		// CardData is a record, so a second printing is equals() to the one that was chosen. The
		// mark is identity-keyed for exactly this reason.
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		MainWindow mw = shivaOn(victim);
		CardData twin = makeForward("Victim", "Fire", 3, 7000);
		placeP2Forward(mw, twin);

		assertEquals(1, mw.combatDamagePointsToOpponent(twin), "the twin still deals its point");
	}

	@Test
	void shivaIsDescribedByWhichWayItsShieldPoints() {
		assertEquals("ChooseCharacter / DullAndFreezeAndZeroAllOutgoing",
				ActionResolver.fullDescription(SHIVA_SUMMON, null));
	}

	// =========================================================================================
	// 23-064R Golem: "Choose 1 Forward. Reveal the top 3 cards of your deck. Add 1 card among them
	// to your hand and return the other cards to the bottom of your deck in any order. If you
	// added a Forward to your hand, deal the chosen Forward damage equal to the power of the added
	// Forward."
	//
	// The middle sentence on its own is the wording LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM claims,
	// and that parser declined the card outright rather than reveal and then drop the burn. The
	// Choose chain now reads all three sentences as one clause, so the amount comes from the card
	// the reveal put into hand — read off the copy in hand, whose power is the printed one.
	// =========================================================================================

	private static final String GOLEM_SUMMON =
			"Choose 1 Forward. Reveal the top 3 cards of your deck. Add 1 card among them to your "
			+ "hand and return the other cards to the bottom of your deck in any order. If you "
			+ "added a Forward to your hand, deal the chosen Forward damage equal to the power of "
			+ "the added Forward.";

	private static GameContext golemContext(ForwardTarget victim, CardData added) {
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.cardAddedToHandByLook()).thenReturn(added);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));
		return ctx;
	}

	@Test
	void golemBurnsForThePowerOfTheForwardItAddedToHand() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = golemContext(victim, makeForward("Behemoth", "Earth", 5, 9000));

		ActionResolver.parse(GOLEM_SUMMON, null).accept(ctx);

		ArgumentCaptor<LookConfig> config = ArgumentCaptor.forClass(LookConfig.class);
		verify(ctx).lookAtTopDeck(config.capture());
		assertEquals(3, config.getValue().count());
		assertEquals(LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM, config.getValue().action());
		assertTrue(config.getValue().reveal(), "\"Reveal\" makes the three cards public");

		verify(ctx).damageTarget(victim, 9000);
	}

	@Test
	void golemDealsNothingWhenTheAddedCardIsNotAForward() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = golemContext(victim, makeBackup("Chocobo", "Earth", 2));

		ActionResolver.parse(GOLEM_SUMMON, null).accept(ctx);

		verify(ctx).lookAtTopDeck(any());
		verify(ctx, never()).damageTarget(any(), anyInt());
	}

	@Test
	void golemDealsNothingWhenTheLookAddedNoCardAtAll() {
		// An empty deck adds nothing, and "the power of the added Forward" then names no card.
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = golemContext(victim, null);

		ActionResolver.parse(GOLEM_SUMMON, null).accept(ctx);

		verify(ctx, never()).damageTarget(any(), anyInt());
	}

	@Test
	void golemsThreeSentencesAreDescribedAsOneClause() {
		assertEquals("ChooseCharacter / RevealAddToHandIfForwardDamageAddedPower",
				ActionResolver.fullDescription(GOLEM_SUMMON, null));
	}

	// =========================================================================================
	// 24-056C Cu Sith, second option: "Choose 1 Forward opponent controls. If it deals damage to
	// you or a Forward this turn, the damage becomes 0 instead."
	//
	// The same effect 23-024R Shiva prints as "damage to a Forward or a player", spelled from the
	// caster's side: "you" is the only player a chosen Forward deals damage to. Read by the same
	// branch and marked the same way, so nothing the Forward deals lands.
	// =========================================================================================

	private static final String CU_SITH_OPTION =
			"Choose 1 Forward opponent controls. If it deals damage to you or a Forward this turn, "
			+ "the damage becomes 0 instead.";

	@Test
	void cuSithDisarmsTheirForwardEntirely() {
		MainWindow mw = new MainWindow();
		CardData victim = makeForward("Theirs", "Fire", 3, 7000);
		placeP2Forward(mw, victim);
		CardData blocker = makeForward("Ally", "Water", 3, 9000);
		placeP1Forward(mw, blocker);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(CU_SITH_OPTION, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		assertTrue(mw.allOutgoingDmgZeroThisTurnSet.contains(victim),
				"\"to you or a Forward\" is the same reach as \"to a Forward or a player\"");
		assertEquals(0, mw.modifyOutgoingCombatDamage(false, 0, 7000, blocker));
		assertEquals(0, mw.combatDamagePointsToOpponent(victim));
	}

	@Test
	void cuSithNamesBothOfItsOptions() {
		String summon = "EX BURST Select 1 of the 2 following actions. "
				+ "\"Choose 1 Backup you control. Remove it from the game. Draw 1 card.\" "
				+ "\"" + CU_SITH_OPTION + "\"";
		assertEquals("SelectFollowingActions(1 of 2: ChooseCharacter / RemoveFromGame + DrawCards "
						+ "| ChooseCharacter / ZeroAllOutgoingDamage)",
				ActionResolver.fullDescription(summon, null));
	}

	// =========================================================================================
	// 25-004H Ifrit: an alternate cast cost paid by removing a Backup from the game rather than
	// by Crystals. The cost sentence has to be recognised for its own sake AND so summonEffect()
	// strips it — while it stayed in the effect text the resolver matched the combined string and
	// the actual summon effect never ran.
	// =========================================================================================

	private static final String IFRIT_TEXT =
			"Before paying the cost to cast Ifrit, you can remove 1 Fire Backup you control from the game "
			+ "to reduce the cost required to cast Ifrit by 2.[[br]]"
			+ "Choose 1 Forward and up to 1 other Forward. Deal the former 9000 damage and deal the latter 4000 damage.";

	@Test
	void ifritAlternateCostRemovesAFireBackupAndReducesTheCost() {
		CardData ifrit = makeSummon("Ifrit", "Fire", 4, IFRIT_TEXT);
		CardData.AltFieldRemoval removal = ifrit.altFieldRemoval();
		assertNotNull(removal, "the remove-from-game alternate cost should parse");
		assertEquals(1, removal.count());
		assertEquals("Fire", removal.element());
		assertEquals("Backup", removal.type());
		assertEquals(0, ifrit.altCrystalCost(), "this cost is paid with a Backup, not Crystals");
		assertEquals(List.of("Fire", "Fire"), ifrit.altCpElements(), "cost 4 reduced by 2");
	}

	// A name containing a comma is why this pattern cannot reuse the Crystal variant's [^,]+.
	@Test
	void alternateRemovalCostParsesThroughCommasInTheCardName() {
		String text = "Before paying the cost to cast Mateus, the Corrupt, you can remove 1 Ice Backup you control "
				+ "from the game to reduce the cost required to cast Mateus, the Corrupt by 2.[[br]]"
				+ "Choose 1 dull Forward and up to 1 other Forward. Break the former, dull and Freeze the latter.";
		CardData mateus = makeSummon("Mateus, the Corrupt", "Ice", 4, text);
		assertNotNull(mateus.altFieldRemoval(), "a comma in the card name must not break the match");
		assertEquals("Ice", mateus.altFieldRemoval().element());
		assertEquals(List.of("Ice", "Ice"), mateus.altCpElements());
	}

	@Test
	void ifritSummonEffectDropsTheCostSentence() {
		CardData ifrit = makeSummon("Ifrit", "Fire", 4, IFRIT_TEXT);
		String effect = ifrit.summonEffect();
		assertFalse(effect.contains("Before paying"), "the cost sentence is not part of the effect");
		assertEquals("Choose 1 Forward and up to 1 other Forward. "
				+ "Deal the former 9000 damage and deal the latter 4000 damage.", effect);
		assertNotNull(ActionResolver.parse(effect, ifrit), "the remaining effect should parse");
		assertEquals("ChooseFormerLatter", ActionResolver.fullDescription(effect, ifrit));
	}

	// The neighbouring "remove … from the game" costs are deliberately out of this pattern's reach:
	// each removes from a different place or with a different shape, and claiming them here would
	// report a Backup removal the player never agreed to.
	@Test
	void alternateRemovalCostIgnoresTheBreakZoneAndInsteadOfPayingForms() {
		CardData odin = makeSummon("Odin", "Lightning", 6,
				"Before paying the cost to cast Odin, you can remove 5 Lightning cards in your Break Zone "
				+ "from the game to reduce the cost required to cast Odin by 4.");
		assertNull(odin.altFieldRemoval(), "Break Zone removal is a different cost");

		CardData vayne = makeSummon("Vayne", "Lightning", 5,
				"Before paying the cost to cast Vayne, you can remove any number of active Backups you control "
				+ "from the game to reduce the cost required to cast Vayne by 1 for each Backup removed.");
		assertNull(vayne.altFieldRemoval(), "a variable count is not this fixed-count cost");

		CardData sonon = makeSummon("Sonon", "Earth", 3,
				"You can remove 1 Earth Backup you control and 1 Lightning Backup you control from the game "
				+ "(instead of paying the CP cost) to cast Sonon.");
		assertNull(sonon.altFieldRemoval(), "instead-of-paying is not a cost reduction");
	}

	@Test
	void ifritDealsNineThousandToTheFormerAndFourThousandToTheLatter() {
		CardData ifrit = makeSummon("Ifrit", "Fire", 4, IFRIT_TEXT);
		Consumer<GameContext> fn = ActionResolver.parse(ifrit.summonEffect(), ifrit);
		assertNotNull(fn);

		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		ForwardTarget former = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		ForwardTarget latter = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
		// "up to 1 OTHER Forward" excludes the first by name, so the former must be resolvable.
		when(ctx.p2Forward(0)).thenReturn(makeForward("Former", "Fire", 3, 7000));
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(former)).thenReturn(List.of(latter));

		fn.accept(ctx);
		verify(ctx).damageTarget(former, 9000);
		verify(ctx).damageTarget(latter, 4000);
	}

	// The second target is "up to 1", so declining it must still resolve the first.
	@Test
	void ifritStillDamagesTheFormerWhenNoSecondForwardIsChosen() {
		CardData ifrit = makeSummon("Ifrit", "Fire", 4, IFRIT_TEXT);
		Consumer<GameContext> fn = ActionResolver.parse(ifrit.summonEffect(), ifrit);
		assertNotNull(fn);

		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		ForwardTarget former = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		when(ctx.p2Forward(0)).thenReturn(makeForward("Former", "Fire", 3, 7000));
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(former)).thenReturn(List.of());

		fn.accept(ctx);
		verify(ctx).damageTarget(former, 9000);
		verify(ctx, never()).damageTarget(any(), eq(4000));
	}

	@Test
	void altFieldRemovalCandidatesOffersOnlyMatchingElementBackups() {
		MainWindow mw = new MainWindow();
		CardData ifrit = makeSummon("Ifrit", "Fire", 4, IFRIT_TEXT);

		mw.p1BackupCards[0] = makeBackup("Fire Guy", "Fire", 2);
		mw.p1BackupCards[1] = makeBackup("Ice Guy", "Ice", 2);
		mw.p1BackupCards[2] = makeBackup("Other Fire Guy", "Fire", 3);

		assertEquals(List.of(0, 2), mw.altFieldRemovalCandidates(ifrit.altFieldRemoval()),
				"only Fire Backups can pay this cost");
	}

	// =========================================================================================
	// 25-088H Famfrit, the Darkening Cloud: "Choose 1 auto-ability triggered from a Forward. Put
	// that Forward into the Break Zone. Draw 1 card." The ability is chosen, not cancelled — it
	// still resolves. Unparsed since the compound fallback stopped composing past the choice it
	// could not read; before that it put a Forward nobody chose into the Break Zone.
	// =========================================================================================

	private static final String FAMFRIT_25_088H = "Choose 1 auto-ability triggered from a Forward. "
			+ "Put that Forward into the Break Zone. Draw 1 card.";

	@Test
	void famfritPutsTheChosenAbilitysForwardIntoTheBreakZoneAndDraws() {
		CardData from = makeForward("Trigger Source", "Fire", 4, 7000);
		ForwardTarget slot = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.chooseAbilityOnStack(any(), any())).thenReturn(autoEntryFrom(from, false));
		when(ctx.fieldSlotOf(from)).thenReturn(slot);

		ActionResolver.parse(FAMFRIT_25_088H).accept(ctx);

		verify(ctx).forceTargetToBreakZone(slot);
		verify(ctx, never()).breakTarget(any());
		verify(ctx, never()).cancelFilteredAbilityOnStack(any(), any(), anyBoolean());
		verify(ctx).drawCards(1);
	}

	@Test
	void famfritStillDrawsWhenThatForwardHasLeftTheField() {
		CardData from = makeForward("Trigger Source", "Fire", 4, 7000);
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.chooseAbilityOnStack(any(), any())).thenReturn(autoEntryFrom(from, false));
		when(ctx.fieldSlotOf(from)).thenReturn(null);

		ActionResolver.parse(FAMFRIT_25_088H).accept(ctx);

		verify(ctx, never()).forceTargetToBreakZone(any());
		verify(ctx).drawCards(1);
	}

	@SuppressWarnings("unchecked")
	@Test
	void famfritChoosesOnlyAnAutoAbilityFromAForward() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		ActionResolver.parse(FAMFRIT_25_088H).accept(ctx);
		ArgumentCaptor<Predicate<StackEntry>> filter = ArgumentCaptor.forClass(Predicate.class);
		verify(ctx).chooseAbilityOnStack(filter.capture(), any());
		assertTrue(filter.getValue().test(autoEntryFrom(makeForward("Fwd", "Fire", 9, 7000), false)));
		assertFalse(filter.getValue().test(autoEntryFrom(makeBackup("Bkp", "Fire", 2), false)));
	}

	@Test
	void famfritOnARealBoardLeavesTheAbilityOnTheStack() {
		MainWindow mw = new MainWindow();
		CardData from = makeForward("Trigger Source", "Fire", 4, 7000);
		// The equal copy sits on P1's side: two on one side would trip the uniqueness rule, and on
		// P1's it is the one an equality lookup would reach first, since P1's field is searched first.
		CardData twin = makeForward("Trigger Source", "Fire", 4, 7000);
		mw.placeCardInForwardZone(twin);
		placeP2Forward(mw, from);
		StackEntry entry = autoEntryFrom(from, false);
		mw.gameState.pushStack(entry);
		mw.gameState.getP1MainDeck().add(makeForward("Deck Card", "Fire", 1, 1000));

		ActionResolver.parse(FAMFRIT_25_088H).accept(mw.buildGameContext(true));

		assertTrue(mw.gameState.getP2BreakZone().stream().anyMatch(c -> c == from), "that Forward");
		assertTrue(mw.p1ForwardCards.stream().anyMatch(c -> c == twin), "not its twin");
		assertTrue(mw.gameState.getStack().contains(entry), "chosen, not cancelled");
		assertEquals(1, mw.gameState.getP1Hand().size(), "drew 1");
	}

	// =========================================================================================
	// 26-087R Odin: "Choose 1 Forward with 《LB》 of cost 6 or less. Break it."
	//
	// The only printing that filters by 《LB》. Without an arm for it the choose phrase left
	// " with 《LB》" unmatched before the followup separator and the whole choice failed, so Odin's
	// second action read as unimplemented and the player could only ever take the first.
	// =========================================================================================

	private static final String ODIN_LB_ACTION =
			"Choose 1 Forward with 《LB》 of cost 6 or less. Break it.";

	/** A Limit Break Forward — the printing 《LB》 names. */
	private static CardData makeLbForward(String name, String element, int cost, int power) {
		return new CardData(null, name, element, cost, power, "Forward", true, cost, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	@Test
	void odinsSecondActionParsesAtAll() {
		CardData odin = makeForward("Odin", "Lightning", 5, 0);
		assertNotNull(ActionResolver.parse(ODIN_LB_ACTION, odin));
		assertEquals("ChooseCharacter / Break",
				ActionResolver.fullDescription(ODIN_LB_ACTION, odin));
	}

	@Test
	void andItOffersOnlyLimitBreakForwards() {
		MainWindow mw = new MainWindow();
		CardData lb    = makeLbForward("Limit Broken", "Fire", 5, 9000);
		CardData plain = makeForward("Ordinary", "Fire", 5, 9000);
		placeP2Forward(mw, lb);
		placeP2Forward(mw, plain);

		List<ForwardTarget> eligible = mw.buildGameContext(true).selectCharacters(
				1, false, false, false, CardFilters.LIMIT_BREAK_CONDITION, null,
				6, "less", -1, null, true, false, false,
				null, null, null, null, false, null, false);

		// One eligible target needs no prompt, so the selection answers with it directly.
		assertEquals(1, eligible.size(), "only the 《LB》 Forward qualifies");
		assertEquals(lb, mw.p2ForwardCards.get(eligible.get(0).idx()));
	}

	@Test
	void anUnknownKeywordFilterIsDeclinedRatherThanIgnored() {
		// An unread filter would widen the choice to every Forward on the table, which is exactly
		// what the without-《…》 arm beside it exists to prevent.
		CardData odin = makeForward("Odin", "Lightning", 5, 0);
		assertNull(ActionResolver.parse(
				"Choose 1 Forward with 《Something Else》 of cost 6 or less. Break it.", odin));
	}

	// =========================================================================================

	// =========================================================================================
	// 28-064H Cactuar, second option: "Choose 1 Forward. It gains 'This Forward cannot attack or
	// block.' until the end of the turn. Draw 1 card."
	//
	// The followup pattern already read this sentence — with double quotes around the grant.
	// Cactuar is the one printing that uses ', and nothing else distinguishes the two.
	// =========================================================================================

	private static final String CACTUAR_OPTION =
			"Choose 1 Forward. It gains 'This Forward cannot attack or block.' until the end of "
			+ "the turn. Draw 1 card.";

	@Test
	void cactuarLocksTheForwardAndDraws() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));

		Consumer<GameContext> effect = ActionResolver.parse(CACTUAR_OPTION, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		verify(ctx).setP2ForwardCannotAttack(0);
		verify(ctx).setP2ForwardCannotBlock(0);
		verify(ctx).drawCards(1);
	}

	@Test
	void andTheSingleQuotedGrantIsNamedLikeTheDoubleQuotedOne() {
		assertEquals("ChooseCharacter / CannotAttackOrBlock + DrawCards",
				ActionResolver.fullDescription(CACTUAR_OPTION, null));
		assertEquals("ChooseCharacter / CannotAttackOrBlock + DrawCards",
				ActionResolver.fullDescription(CACTUAR_OPTION.replace('\'', '"'), null));
	}

	// =========================================================================================
	// 29-101H Syldra — two alternatives, each with its own cost ceiling
	//
	// "Play 1 Forward of cost 4 or less other than Multi-Element or 1 Card Name Faris of cost 6 or
	// less among them onto the field." One card is played, from whichever branch it satisfies, and
	// the branches do not share a ceiling — the Faris branch reaches costs the Forward branch
	// cannot, which is why it is printed as a second alternative rather than as a wider filter.
	//
	// The effect also carries a cast restriction in front of it. That is parsed off the card into
	// a CastRestriction and enforced at cast time, so the effect parsers have to step over the
	// sentence; leaving it there is what made this text unparseable.
	// =========================================================================================

	private static final String SYLDRA_29_101H =
			"You can only cast Syldra during your turn. Reveal the top 5 cards of your deck. "
			+ "Play 1 Forward of cost 4 or less other than Multi-Element or 1 Card Name Faris of "
			+ "cost 6 or less among them onto the field and return the other cards to the bottom "
			+ "of your deck in any order.";

	@Test
	void syldraReadsBothAlternativesAndTheirSeparateCeilings() {
		CardData syldra = makeSummon("Syldra", "Water", 4, SYLDRA_29_101H);
		assertEquals("RevealPlayTypeCostOrNamedCostRestBottom",
				ActionResolver.matchedPatternName(SYLDRA_29_101H, syldra),
				"the cast-restriction sentence must not defeat the anchored pattern");

		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(SYLDRA_29_101H, syldra).accept(ctx);
		verify(ctx).revealTopNPlayTypeCostOrNamedCostOntoFieldRestBottom(
				5, "Forward", 4, true, "Faris", 6);
	}

	/** P2's deck, top card first — P2 so the reveal resolves through the AI seat, dialog-free. */
	private static MainWindow syldraDeck(CardData... topFirst) {
		MainWindow mw = new MainWindow();
		for (CardData c : topFirst) mw.gameState.getP2MainDeck().add(c);
		return mw;
	}

	private static void resolveSyldra(MainWindow mw) {
		mw.buildGameContext(false).revealTopNPlayTypeCostOrNamedCostOntoFieldRestBottom(
				5, "Forward", 4, true, "Faris", 6);
	}

	@Test
	void theNamedBranchReachesACostTheTypeBranchCannot() {
		MainWindow mw = syldraDeck(
				makeForward("Faris", "Wind", 6, 9000),        // eligible: named, cost 6
				makeForward("Bartz", "Wind", 3, 7000),        // eligible: Forward, cost 3
				makeForward("Krile", "Wind", 5, 8000),        // too dear for the Forward branch
				makeForward("Twin", "Fire/Water", 4, 8000),   // Multi-Element, excluded
				makeSummon("Shiva", "Ice", 2, ""));           // not a Forward, not Faris

		resolveSyldra(mw);

		assertEquals(1, mw.p2ForwardCards.size());
		assertEquals("Faris", mw.p2ForwardCards.get(0).name(),
				"the dearest eligible card, and only the named branch could supply it");
	}

	@Test
	void theTypeBranchExcludesMultiElementAndAnythingTooDear() {
		MainWindow mw = syldraDeck(
				makeForward("Krile", "Wind", 5, 8000),        // over the Forward ceiling
				makeForward("Twin", "Fire/Water", 4, 8000),   // at the ceiling but Multi-Element
				makeForward("Bartz", "Wind", 3, 7000),        // the only eligible card
				makeForward("Lenna", "Water", 2, 5000),
				makeSummon("Shiva", "Ice", 2, ""));

		resolveSyldra(mw);

		assertEquals(1, mw.p2ForwardCards.size());
		assertEquals("Bartz", mw.p2ForwardCards.get(0).name(),
				"\"other than Multi-Element\" rules out the cost-4 twin above it");
	}

	@Test
	void theRevealedCardLandsOnTheResolvingPlayersField() {
		// Guards the whole reveal-and-play family, not just Syldra: each of them built its
		// placement inline against P1's zones, which are P1-only, so P2 resolving any of them
		// handed the card to P1 instead.
		MainWindow mw = syldraDeck(
				makeForward("Bartz", "Wind", 3, 7000),
				makeForward("Lenna", "Water", 2, 5000),
				makeSummon("Shiva", "Ice", 2, ""));

		resolveSyldra(mw);

		assertEquals(1, mw.p2ForwardCards.size(), "P2 resolved it, so P2 gets the Forward");
		assertEquals(0, mw.p1ForwardCards.size(), "and nothing lands on the opponent's board");
	}

	@Test
	void theCardsNotPlayedGoToTheBottomOfTheDeck() {
		MainWindow mw = syldraDeck(
				makeForward("Bartz", "Wind", 3, 7000),
				makeForward("Krile", "Wind", 5, 8000),
				makeForward("Lenna", "Water", 2, 5000),
				makeForward("Galuf", "Earth", 4, 8000),
				makeSummon("Shiva", "Ice", 2, ""),
				makeForward("Deep Deck", "Fire", 1, 3000));   // never revealed — stays put

		resolveSyldra(mw);

		List<CardData> deck = new ArrayList<>(mw.gameState.getP2MainDeck());
		assertEquals(5, deck.size(), "one of the six was played");
		assertEquals("Deep Deck", deck.get(0).name(),
				"the unrevealed card is now on top; the other four went under it");
		assertFalse(deck.stream().anyMatch(c -> c.name().equals("Galuf")),
				"the dearest eligible Forward was the one played");
	}

	// =========================================================================================
	// 29-116H Madeen: "Madeen cannot be cancelled." The sentence was skipped by the parser (a find()
	// past it) and read by nothing: the Stack's cancel protection answered only for abilities
	// (Yoran-Oran 29-075H) and refused every Summon outright.
	// =========================================================================================

	private static final String MADEEN_29_116H = "Madeen cannot be cancelled.[[br]]Choose 1 Forward opponent "
			+ "controls. You may search for 1 Light Forward and remove it from the game. If you do so, remove "
			+ "the chosen Forward from the game. If not, break the chosen Forward.";

	private static final String CANCEL_A_SUMMON = "Choose 1 Summon or auto-ability. Cancel its effect.";

	@Test
	void madeenCarriesItsCancelProtectionAndResolvesWithoutTheSentence() {
		CardData madeen = makeSummon("Madeen", "Light", 3, MADEEN_29_116H);
		assertTrue(madeen.cannotBeCancelled());
		assertTrue(madeen.summonEffect().startsWith("Choose 1 Forward opponent controls."),
				"a property of the card, not part of what it does: " + madeen.summonEffect());
		assertFalse(makeSummon("Shiva", "Ice", 2, "Draw 1 card.").cannotBeCancelled());
		assertFalse(makeSummon("Odd", "Ice", 2, "Madeen cannot be cancelled. Draw 1 card.").cannotBeCancelled(),
				"only a sentence naming the card itself");
	}

	@Test
	void anOrdinarySummonOnTheStackCanBeCancelled() {
		MainWindow mw = new MainWindow();
		CardData shiva = makeSummon("Shiva", "Ice", 2, "Draw 1 card.");
		mw.pushSummonOnStack(shiva, true, 0, 0, false, null, false);
		StackEntry entry = mw.gameState.getStack().get(0);

		ActionResolver.parse(CANCEL_A_SUMMON, makeSummon("Canceller", "Water", 2, CANCEL_A_SUMMON))
				.accept(mw.buildGameContext(false));

		assertTrue(mw.cancelledStackEntries.contains(entry));
	}

	@Test
	void madeenOnTheStackCannotBeCancelled() {
		MainWindow mw = new MainWindow();
		CardData madeen = makeSummon("Madeen", "Light", 3, MADEEN_29_116H);
		mw.pushSummonOnStack(madeen, true, 0, 0, false, null, false);
		StackEntry entry = mw.gameState.getStack().get(0);
		assertSame(madeen, entry.source());

		ActionResolver.parse(CANCEL_A_SUMMON, makeSummon("Canceller", "Water", 2, CANCEL_A_SUMMON))
				.accept(mw.buildGameContext(false));

		assertFalse(mw.cancelledStackEntries.contains(entry));
		assertFalse(mw.cancelStackEntry(entry), "and refused however the cancel arrives");
		assertFalse(mw.cancelledStackEntries.contains(entry));
	}

	@Test
	void neonCanStillBlankMadeensDamageBecauseThatIsNotACancel() {
		// Madeen's protection is against cancels only. Neon's "the damage becomes 0" lets the
		// Summon resolve, so a cannot-be-cancelled Summon stays within its reach.
		MainWindow mw = new MainWindow();
		CardData madeen = makeSummon("Madeen", "Light", 3, MADEEN_29_116H);
		mw.pushSummonOnStack(madeen, true, 0, 0, false, null, false);
		String neon = "Choose 1 Summon or auto-ability. During this turn, if it deals damage to a Forward or "
				+ "a player, the damage becomes 0 instead.";

		ActionResolver.parse(neon, makeForward("Neon", "Water", 3, 7000)).accept(mw.buildGameContext(false));

		assertTrue(mw.damageZeroedSourcesThisTurn.contains(madeen));
		assertTrue(mw.cancelledStackEntries.isEmpty(), "and it is still not cancelled");
	}

	// Madeen's effect: "You may search for 1 Light Forward and remove it from the game. If you do
	// so, remove the chosen Forward from the game. If not, break the chosen Forward." The same
	// shape as Ark's, with remove and break as its two branches.

	private static void stubSearch(GameContext ctx, boolean found) {
		when(ctx.searchDeckForCard(
				anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), any(), any(), any(), any(), any(), any(),
				any(), anyInt(), anyBoolean(), any())).thenReturn(found);
	}

	private static final String MADEEN_EFFECT =
			"Madeen cannot be cancelled. Choose 1 Forward opponent controls. "
			+ "You may search for 1 Light Forward and remove it from the game. "
			+ "If you do so, remove the chosen Forward from the game. If not, break the chosen Forward.";

	@Test
	void madeenRemovesTheChosenForwardOnlyWhenTheSearchSucceeds() {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = contextChoosing(List.of(t));
		when(ctx.promptYouMay(any())).thenReturn(true);
		stubSearch(ctx, true);

		Consumer<GameContext> fn = ActionResolver.parse(MADEEN_EFFECT, makeForward("Madeen", "Light", 5, 0));
		assertNotNull(fn, "Madeen should parse");
		fn.accept(ctx);

		verify(ctx).removeTargetFromGame(t);
		verify(ctx, never()).breakTarget(any());
	}

	/** Madeen's "if not" is a break, where Ark's is damage — the two branches are read, not assumed. */
	@Test
	void madeenBreaksTheChosenForwardWhenTheSearchIsDeclined() {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = contextChoosing(List.of(t));
		when(ctx.promptYouMay(any())).thenReturn(false);
		stubSearch(ctx, true);

		ActionResolver.parse(MADEEN_EFFECT, makeForward("Madeen", "Light", 5, 0)).accept(ctx);

		verify(ctx).breakTarget(t);
		verify(ctx, never()).removeTargetFromGame(any());
	}

	/**
	 * The precedence guard that makes this work: WhenYouDoSo sits ~200 call sites earlier in
	 * parse() and used to claim the whole sentence, resolving "remove the chosen Forward from the
	 * game" as a remove-by-name and never offering the search that gates it.
	 */
	@Test
	void madeenIsClaimedByTheChooseParserRatherThanWhenYouDoSo() {
		CardData madeen = makeForward("Madeen", "Light", 5, 0);
		assertEquals("ChooseCharacter / MaySearchRfgThenElse",
				ActionResolver.fullDescription(MADEEN_EFFECT, madeen));

		GameContext ctx = contextChoosing(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));
		when(ctx.promptYouMay(any())).thenReturn(true);
		stubSearch(ctx, true);
		ActionResolver.parse(MADEEN_EFFECT, madeen).accept(ctx);
		verify(ctx, never()).removeNamedCardFromGame(any());
	}
}
