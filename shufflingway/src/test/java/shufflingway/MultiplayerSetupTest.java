package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import shufflingway.net.MatchSetup;

/**
 * The Phase 1 multiplayer invariant: two clients starting from the same {@link MatchSetup}
 * deal the same game, each seating itself as P1.
 *
 * <p>These build both clients' {@link GameState} in one JVM and compare them, which is the
 * closest a unit test gets to the two-window check — {@code MainWindow} is not headless.
 */
class MultiplayerSetupTest {

    private static final List<String> HOST_SERIALS   = List.of("h1", "h2", "h3");
    private static final List<String> JOINER_SERIALS = List.of("j1", "j2", "j3");

    private static CardData card(String name, int cost) {
        return new CardData(null, name, "Fire", cost, 5000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    /** A 50-card deck whose cards are all distinguishable, so any reordering shows up. */
    private static List<CardData> deck(String prefix) {
        List<CardData> cards = new ArrayList<>();
        for (int i = 0; i < 50; i++) cards.add(card(prefix + "-" + i, i % 9));
        return cards;
    }

    private static List<String> names(java.util.Collection<CardData> cards) {
        return cards.stream().map(CardData::name).toList();
    }

    private static MatchSetup hostSetup(long seed, boolean hostGoesFirst) {
        return new MatchSetup(1, JOINER_SERIALS, "Joiner Deck", "Joiner", seed, true, hostGoesFirst);
    }

    private static MatchSetup joinerSetup(long seed, boolean hostGoesFirst) {
        return new MatchSetup(2, HOST_SERIALS, "Host Deck", "Host", seed, false, hostGoesFirst);
    }

    /**
     * Builds the game as one client sees it: its own deck as P1, the opponent's as P2.
     * This mirrors what {@code MainWindow.loadMultiplayerDecks} does.
     */
    private static GameState dealFor(MatchSetup setup) {
        GameState state = new GameState();
        List<CardData> own      = setup.localIsHost() ? deck("host") : deck("joiner");
        List<CardData> opponent = setup.localIsHost() ? deck("joiner") : deck("host");
        state.initializeDeck(own, List.of(), setup.localDeckRandom());
        state.initializeP2MainDeck(opponent, setup.remoteDeckRandom());
        return state;
    }

    @Test
    void bothClientsDealTheSameDeckOrderFromOneSeed() {
        GameState host   = dealFor(hostSetup(0xC0FFEEL, true));
        GameState joiner = dealFor(joinerSetup(0xC0FFEEL, true));

        // The host's deck is P1 on the host's client and P2 on the joiner's — same order in both.
        assertEquals(names(host.getP1MainDeck()), names(joiner.getP2MainDeck()),
                "the host's deck must be dealt identically on both clients");
        assertEquals(names(host.getP2MainDeck()), names(joiner.getP1MainDeck()),
                "the joiner's deck must be dealt identically on both clients");
    }

    @Test
    void openingDealChecksumsAgreeAcrossClients() {
        GameState host   = dealFor(hostSetup(42L, false));
        GameState joiner = dealFor(joinerSetup(42L, false));

        assertEquals(MatchChecksum.ofOpeningDeal(host, true, false),
                     MatchChecksum.ofOpeningDeal(joiner, false, false),
                     "mirrored clients must produce the same opening-deal digest");
    }

    @Test
    void checksumCatchesAClientThatDealtADifferentGame() {
        GameState host   = dealFor(hostSetup(42L, false));
        GameState joiner = dealFor(joinerSetup(43L, false)); // wrong seed — a desync

        assertNotEquals(MatchChecksum.ofOpeningDeal(host, true, false),
                        MatchChecksum.ofOpeningDeal(joiner, false, false),
                        "a differing deal must not digest the same");
    }

    @Test
    void checksumCoversTheAgreedFirstPlayer() {
        GameState host = dealFor(hostSetup(42L, false));
        assertNotEquals(MatchChecksum.ofOpeningDeal(host, true, true),
                        MatchChecksum.ofOpeningDeal(host, true, false),
                        "disagreeing about who moves first is itself a desync");
    }

    @Test
    void theTwoDecksDoNotShareAShuffleStream() {
        // Both decks drawing from one stream would correlate them; they get separate streams.
        List<CardData> a = deck("x");
        List<CardData> b = deck("x");
        MatchSetup setup = hostSetup(7L, true);
        java.util.Collections.shuffle(a, setup.hostDeckRandom());
        java.util.Collections.shuffle(b, setup.joinerDeckRandom());
        assertNotEquals(names(a), names(b));
    }

    @Test
    void firstPlayerFollowsTheHostsFlip() {
        assertTrue(hostSetup(1L, true).localGoesFirst());
        assertFalse(joinerSetup(1L, true).localGoesFirst());
        assertFalse(hostSetup(1L, false).localGoesFirst());
        assertTrue(joinerSetup(1L, false).localGoesFirst());
    }

    @Test
    void seededDealIsReproducible() {
        assertEquals(names(dealFor(hostSetup(99L, true)).getP1MainDeck()),
                     names(dealFor(hostSetup(99L, true)).getP1MainDeck()),
                     "the same seed must deal the same game every run");
    }

    // ── Opening hand replication ─────────────────────────────────────────
    //
    // The sending client settles its own hand through keepHand/mulligan; the receiving client
    // applies the permutation to its P2 copy. These assert the two land in the same place.

    /** Deals both clients and draws each side's opening five, as game start does. */
    private static GameState[] dealtPair(long seed) {
        GameState host   = dealFor(hostSetup(seed, true));
        GameState joiner = dealFor(joinerSetup(seed, true));
        host.drawP2OpeningHand();
        joiner.drawP2OpeningHand();
        return new GameState[] { host, joiner };
    }

    @Test
    void keepingAReorderedHandLeavesBothClientsAgreeingOnIt() {
        GameState[] pair = dealtPair(5L);
        GameState host = pair[0], joiner = pair[1];

        // The host arranges their opening five and keeps it.
        List<CardData> drawn = host.drawOpeningHand();
        List<Integer> order = List.of(3, 0, 4, 1, 2);
        List<CardData> arranged = order.stream().map(drawn::get).toList();
        host.keepHand(arranged);

        // The joiner's client holds that same hand as P2 and applies the permutation.
        assertTrue(joiner.reorderP2Hand(order));
        assertEquals(names(host.getP1Hand()), names(joiner.getP2Hand()),
                "hand order must agree, since later actions address cards by index");
    }

    @Test
    void mulliganLeavesBothClientsWithTheSameHandAndDeck() {
        GameState[] pair = dealtPair(11L);
        GameState host = pair[0], joiner = pair[1];

        List<CardData> drawn = host.drawOpeningHand();
        List<Integer> bottomOrder = List.of(2, 4, 0, 3, 1);
        host.mulligan(bottomOrder.stream().map(drawn::get).toList());

        assertTrue(joiner.mulliganP2(bottomOrder));

        assertEquals(names(host.getP1MainDeck()), names(joiner.getP2MainDeck()),
                "the mulliganed cards must go to the bottom in the same order on both clients");
    }

    @Test
    void mulliganWithoutReplicationDesyncsTheDeck() {
        // Guards the reason MULLIGAN is on the wire at all: it reorders the deck.
        GameState[] pair = dealtPair(11L);
        GameState host = pair[0], joiner = pair[1];

        List<CardData> drawn = host.drawOpeningHand();
        host.mulligan(new ArrayList<>(drawn));

        assertNotEquals(names(host.getP1MainDeck()), names(joiner.getP2MainDeck()),
                "an unreplicated mulligan must not silently look like agreement");
    }

    // ── Card play replication ────────────────────────────────────────────
    //
    // executePlay is parameterised by player, so the opponent's play runs the same code against
    // P2's zones. These assert the two seats really do produce the same result, which is the
    // whole reason there is one implementation instead of a P1 copy and a P2 copy.

    private static CardData backup(String name, String element, int cost) {
        return new CardData(null, name, element, cost, 0, "Backup", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    @Test
    void aPlayCostsTheSameInEitherSeat() {
        // One Backup cast for 2, paid by discarding two same-element cards from hand.
        MainWindow p1Seat = new MainWindow();
        MainWindow p2Seat = new MainWindow();
        for (MainWindow mw : List.of(p1Seat, p2Seat)) {
            List<CardData> hand = mw == p1Seat ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
            hand.add(backup("Cast Me", "Fire", 2));
            hand.add(backup("Pay A", "Fire", 3));
            hand.add(backup("Pay B", "Fire", 3));
        }

        p1Seat.executePlay(true,  p1Seat.gameState.getP1Hand().get(0), 0,
                List.of(1, 2), List.of(), Map.of());
        p2Seat.executePlay(false, p2Seat.gameState.getP2Hand().get(0), 0,
                List.of(1, 2), List.of(), Map.of());

        assertEquals(0, p1Seat.gameState.getP1Hand().size(), "hand is emptied by the cast and its payment");
        assertEquals(p1Seat.gameState.getP1Hand().size(), p2Seat.gameState.getP2Hand().size());
        assertEquals(names(p1Seat.gameState.getP1BreakZone()), names(p2Seat.gameState.getP2BreakZone()),
                "the same cards must reach the Break Zone in the same order");
        assertEquals(p1Seat.p1BackupCards[0].name(), p2Seat.p2BackupCards[0].name(),
                "the cast card must land in the same backup slot on both boards");
        assertEquals(0, p1Seat.gameState.getP1CpForElement("Fire"),
                "CP generated for payment is cleared once the cost is paid");
        assertEquals(p1Seat.gameState.getP1CpForElement("Fire"),
                     p2Seat.gameState.getP2CpForElement("Fire"));
    }

    @Test
    void discardIndicesBelowThePlayedCardShiftItCorrectly() {
        // The played card sits above its payment in hand, so removing the payment first moves it.
        MainWindow mw = new MainWindow();
        List<CardData> hand = mw.gameState.getP2Hand();
        hand.add(backup("Pay A", "Ice", 3));
        hand.add(backup("Pay B", "Ice", 3));
        hand.add(backup("Cast Me", "Ice", 2));

        mw.executePlay(false, hand.get(2), 2, List.of(0, 1), List.of(), Map.of());

        assertTrue(mw.gameState.getP2Hand().isEmpty(),
                "the played card must be removed, not left behind by a stale index");
        assertEquals("Cast Me", mw.p2BackupCards[0].name());
        // Discards are applied high-index-first so the lower indices stay valid, which puts the
        // later card in the Break Zone first. Deterministic, so both clients agree on it.
        assertEquals(List.of("Pay B", "Pay A"), names(mw.gameState.getP2BreakZone()));
    }

    @Test
    void aMalformedHandOrderIsRejectedRatherThanApplied() {
        GameState joiner = dealtPair(3L)[1];
        List<String> before = names(joiner.getP2Hand());

        assertFalse(joiner.reorderP2Hand(List.of(0, 0, 1, 2, 3)), "duplicate index");
        assertFalse(joiner.reorderP2Hand(List.of(0, 1, 2, 3)),    "wrong length");
        assertFalse(joiner.reorderP2Hand(List.of(0, 1, 2, 3, 9)), "out of range");
        assertFalse(joiner.mulliganP2(List.of(0, 1, 2, 3, 9)),    "out of range");

        assertEquals(before, names(joiner.getP2Hand()),
                "a rejected order must leave the hand untouched so the desync can be reported");
    }
}
