package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static shufflingway.TestCards.*;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

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

	/** {@link #castAsP2} for a "Select 1 of the N following actions" Summon, taking option {@code option}. */
	private static void castAsP2Selecting(MainWindow mw, CardData summon, int option) {
		GameContext ctx = spy(mw.buildGameContext(false));
		doAnswer(inv -> List.of(inv.<List<String>>getArgument(1).get(option)))
				.when(ctx).chooseActions(any(), anyList(), anyInt(), anyBoolean());
		Consumer<GameContext> fn = ActionResolver.parse(summon.summonEffect(), summon);
		assertNotNull(fn, summon.name() + " parses");
		fn.accept(ctx);
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
	// Break Zone. Add it to your hand.'" (sic: "into" is as printed)
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
	// =========================================================================================

	private static final String ODIN_3_102R = "Choose 1 Forward. If it has 7000 power or less, break it. If you "
			+ "control a Job Class Zero Cadet Forward, break it regardless of its power instead.";

	@Test
	void odinBreaksAForwardOf7000PowerOrLess() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Odin", "Lightning", 4, ODIN_3_102R));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void odinLeavesAForwardOver7000Power() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Odin", "Lightning", 4, ODIN_3_102R));
		assertTrue(mw.p1ForwardCards.contains(theirs));
	}

	@Test
	void odinBreaksAnyForwardWithAClassZeroCadet() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 8000);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, makeForwardWithJob("Cadet", "Lightning", 2, 5000, "Class Zero Cadet"));
		castAsP2(mw, makeSummon("Odin", "Lightning", 4, ODIN_3_102R));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
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
	// 9-017C Belias: "Choose 1 Forward. Until the end of the turn, it gains +1000 power and First
	// Strike. Draw 1 card. If you have received 4 points of damage or more, it also gains Haste
	// until the end of the turn."
	// =========================================================================================

	private static final String BELIAS_9_017C = "EX BURST Choose 1 Forward. Until the end of the turn, it "
			+ "gains +1000 power and First Strike. Draw 1 card. If you have received 4 points of damage or "
			+ "more, it also gains Haste until the end of the turn.";

	/** P2 casts Belias with {@code damage} points of damage, its one Forward the only choice. */
	private static MainWindow castBelias(int damage) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Fire", 3, 7000));
		for (int i = 0; i < damage; i++) mw.gameState.getP2DamageZone().add(makeForward("Damage " + i, "Fire", 1, 1000));
		CardData belias = makeSummon("Belias", "Fire", 1, BELIAS_9_017C);
		Consumer<GameContext> fn = ActionResolver.parse(belias.summonEffect(), belias);
		assertNotNull(fn);
		fn.accept(mw.buildGameContext(false));
		return mw;
	}

	@Test
	void beliasGrantsHasteAtFourDamage() {
		MainWindow mw = castBelias(4);
		assertEquals(8000, mw.effectiveP2ForwardPower(0));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.FIRST_STRIKE));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.HASTE));
	}

	@Test
	void beliasGrantsNoHasteBelowFourDamage() {
		MainWindow mw = castBelias(3);
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.FIRST_STRIKE));
		assertFalse(mw.effectiveP2HasTrait(0, CardData.Trait.HASTE));
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
}
