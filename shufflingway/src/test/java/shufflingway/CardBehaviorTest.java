package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Consolidated behavioral tests for one-off card-specific action-ability logic — each section
 * below targets a single card or narrow bug fix, exercised against a mocked or minimally
 * constructed {@link GameContext}/{@link CardData} rather than just asserting that the text
 * parses. Kept in one file/class so the whole set runs together as a single suite.
 */
public class CardBehaviorTest {

    // =========================================================================================
    // Firion: "If you control 5 or more Characters, Firion gains Haste and 'When Firion attacks,
    // draw 1 card.'  Discard 1 card: If the discarded card is of Fire Element, until the end of
    // the turn, Firion gains +2000 power and First Strike. If the discarded card is of Water
    // Element, Firion gains +2000 power until the end of the turn and activate Firion."
    //
    // The CPU's block-selection logic must consider spending this discard trick, but only when
    // it actually changes the outcome of the block.
    // =========================================================================================

    private static final String FIRION_TEXT =
            "If you control 5 or more Characters, Firion gains Haste and \"When Firion attacks, draw 1 card.\"[[br]]   "
            + "Discard 1 card: If the discarded card is of Fire Element, until the end of the turn, Firion gains +2000 power and First Strike. "
            + "If the discarded card is of Water Element, Firion gains +2000 power until the end of the turn and activate Firion.";

    private static CardData makeForward(String name, String element, int cost, int power) {
        return makeForward(name, element, cost, power, List.of());
    }

    private static CardData makeForward(String name, String element, int cost, int power, List<ActionAbility> abilities) {
        return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                abilities, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    /** Builds a Forward carrying the given innate traits (e.g. CANNOT_BE_BROKEN). */
    private static CardData makeForwardWithTraits(String name, String element, int power,
            Set<CardData.Trait> traits) {
        return new CardData(null, name, element, 3, power, "Forward", false, 0, false, false,
                traits, 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    /** A Forward carrying {@code traits}, for the combat paths that read Brave / Haste. */
    private static CardData makeTraitForward(String name, String element, int cost, int power,
            CardData.Trait... traits) {
        return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
                Set.of(traits), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    /** Builds a Backup whose field ability grants power to its controller's Forwards. */
    private static CardData makeBackupWithPowerGrant(String name, String element, String text) {
        return new CardData(null, name, element, 2, 0, "Backup", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(),
                CardData.parseFieldPowerGrants(text, "Backup"),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, text);
    }

    /** Builds a card of the given type/element carrying a single {@code job}. */
    private static CardData makeJobCard(String name, String element, String type, String job) {
        return new CardData(null, name, element, 3, 7000, type, false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                job, null, null, "");
    }

    /** Builds a Forward carrying {@code job} and the auto abilities parsed from {@code text}. */
    private static CardData makeJobForwardWithAutos(String name, String element, int power,
            String job, String text) {
        return new CardData(null, name, element, 2, power, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), CardData.parseAutoAbilities(text), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                job, null, null, text);
    }

    private static CardData makeFirion(int power) {
        return makeForward("Firion", "Fire", 2, power, CardData.parseActionAbilities(FIRION_TEXT));
    }

    /** Builds a real MainWindow (no window shown) with Firion on P2's field and a P1 attacker declared. */
    private static MainWindow firionSetUp(CardState firionState, int firionPower, int attackerPower,
            List<CardData> p2HandCards) {
        MainWindow mw = new MainWindow();
        mw.placeCardInForwardZone(makeForward("Attacker", "Water", 3, attackerPower)); // P1 idx 0
        mw.placeP2CardInForwardZone(makeFirion(firionPower));                          // P2 idx 0
        mw.p2ForwardStates.set(0, firionState);
        mw.gameState.getP2Hand().addAll(p2HandCards);
        return mw;
    }

    // =========================================================================================
    // Hien 17-016L: "If you control 5 or more Fire Characters and/or Category XIV Characters,
    // Hien gains Haste."
    //
    // A count condition could only AND its filters together, so there was no way to express
    // "Fire OR Category XIV" — the text produced no IfControlBoost at all and Hien never gained
    // Haste. Count conditions now carry a list of alternatives, and the count is a union over one
    // pool: a card that is both Fire and Category XIV still only counts once.
    // =========================================================================================

    private static final String HIEN_HASTE =
            "If you control 5 or more Fire Characters and/or Category XIV Characters, Hien gains Haste.";

    /** Builds a Forward with an element and a category (no job). */
    private static CardData makeCategoryForward(String name, String element, String category) {
        return new CardData(null, name, element, 3, 7000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, category, null, "");
    }

    /** Puts Hien plus {@code allies} on P1's field and reports whether Hien has Haste. */
    private static boolean hienHasHaste(CardData... allies) {
        MainWindow mw = new MainWindow();
        CardData hien = new CardData(null, "Hien", "Fire", 4, 8000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), CardData.parseFieldAbilities(HIEN_HASTE, "Forward"),
                CardData.parseIfControlBoosts(HIEN_HASTE, "Hien"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, "XIV", null, HIEN_HASTE);
        mw.placeCardInForwardZone(hien);
        for (CardData ally : allies) mw.placeCardInForwardZone(ally);
        return mw.effectiveP1HasTrait(0, CardData.Trait.HASTE);
    }

    @Test
    void hienGainsHasteFromAMixedFireAndCategoryXivBoard() {
        // Hien himself is Fire and Category XIV, so 4 more qualifying Characters reach 5.
        assertTrue(hienHasHaste(
                makeForward("Ally A", "Fire", 2, 5000),                 // Fire only
                makeForward("Ally B", "Fire", 2, 5000),                 // Fire only
                makeCategoryForward("Ally C", "Ice", "XIV"),            // Category only
                makeCategoryForward("Ally D", "Wind", "XIV")),          // Category only
                "the count is a union of the two filters, not an intersection");
    }

    @Test
    void hienDoesNotGainHasteBelowFiveQualifyingCharacters() {
        assertFalse(hienHasHaste(
                makeForward("Ally A", "Fire", 2, 5000),
                makeCategoryForward("Ally C", "Ice", "XIV")),
                "3 qualifying Characters is short of the 5 required");
    }

    @Test
    void charactersMatchingNeitherFilterDoNotCountTowardHiensHaste() {
        assertFalse(hienHasHaste(
                makeForward("Ally A", "Fire", 2, 5000),
                makeCategoryForward("Ally C", "Ice", "XIV"),
                makeForward("Stranger A", "Water", 2, 5000),
                makeForward("Stranger B", "Water", 2, 5000),
                makeForward("Stranger C", "Water", 2, 5000)),
                "6 Characters on the field but only 3 satisfy either filter");
    }

    @Test
    void aCardMatchingBothOfHiensFiltersIsCountedOnce() {
        // Four Fire Category-XIV Characters (plus Hien, also both) is 5 cards, not 10.
        assertTrue(hienHasHaste(
                makeCategoryForward("Ally A", "Fire", "XIV"),
                makeCategoryForward("Ally B", "Fire", "XIV"),
                makeCategoryForward("Ally C", "Fire", "XIV"),
                makeCategoryForward("Ally D", "Fire", "XIV")),
                "5 dual-matching Characters is exactly the threshold");
        assertFalse(hienHasHaste(
                makeCategoryForward("Ally A", "Fire", "XIV"),
                makeCategoryForward("Ally B", "Fire", "XIV"),
                makeCategoryForward("Ally C", "Fire", "XIV")),
                "4 of them is still 4 — matching both filters must not count twice");
    }

    // =========================================================================================
    // Ramada 17-125R: "[Sharp Spear] 《S》: Until the end of the turn, Ramada gains +2000 power,
    // Haste and 'If Ramada deals damage to your opponent, the damage becomes 2 instead.'"
    //
    // The power/traits half already worked; the quoted ability did not, so the whole effect failed
    // to parse. "the damage becomes N instead" was only ever modelled for damage coming IN to a
    // card — there was no outgoing form, so nothing set the damage a card deals to a player.
    // It is a replacement, not a multiplier: N may be 0 (Ba'Gamnan 2-088C prints exactly this
    // ability with N = 0, and was dealing 1 damage).
    // =========================================================================================

    private static final String RAMADA_SHARP_SPEAR =
            "Until the end of the turn, Ramada gains +2000 power, Haste and "
            + "\"If Ramada deals damage to your opponent, the damage becomes 2 instead.\"";

    /** Puts a Forward on P1's field and resolves {@code effect} with it as the source. */
    private static MainWindow resolveSelfEffect(CardData self, String effect) {
        MainWindow mw = new MainWindow();
        mw.placeCardInForwardZone(self);
        Consumer<GameContext> fn = ActionResolver.parse(effect, self);
        assertNotNull(fn, "effect should parse: " + effect);
        fn.accept(mw.buildGameContext(true));
        return mw;
    }

    @Test
    void ramadasSharpSpearSetsHisCombatDamageToTwo() {
        CardData ramada = makeForward("Ramada", "Water", 4, 7000);
        MainWindow mw = new MainWindow();
        mw.placeCardInForwardZone(ramada);

        assertEquals(1, mw.combatDamagePointsToOpponent(ramada), "1 point before the ability is used");

        ActionResolver.parse(RAMADA_SHARP_SPEAR, ramada).accept(mw.buildGameContext(true));

        assertEquals(2, mw.combatDamagePointsToOpponent(mw.p1ForwardCards.get(0)),
                "Sharp Spear replaces his damage to the opponent with 2");
    }

    @Test
    void ramadasSharpSpearAlsoGrantsThePowerAndHaste() {
        CardData ramada = makeForward("Ramada", "Water", 4, 7000);
        MainWindow mw = resolveSelfEffect(ramada, RAMADA_SHARP_SPEAR);

        assertEquals(9000, mw.effectiveP1ForwardPower(0), "+2000 power still applies alongside the grant");
        assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.HASTE), "and Haste");
    }

    @Test
    void ramadasDamageOverrideExpiresAtEndOfTurn() {
        CardData ramada = makeForward("Ramada", "Water", 4, 7000);
        MainWindow mw = resolveSelfEffect(ramada, RAMADA_SHARP_SPEAR);
        CardData onField = mw.p1ForwardCards.get(0);

        for (Consumer<GameContext> eot : new ArrayList<>(mw.endOfTurnEffects))
            eot.accept(mw.buildGameContext(true));

        assertEquals(1, mw.combatDamagePointsToOpponent(onField),
                "\"until the end of the turn\" — back to 1 point next turn");
    }

    @Test
    void baGamnanPrintsTheSameAbilityWithZeroAndDealsNoDamage() {
        // 2-088C prints it rather than granting it, so the printed path must honour N = 0 too.
        String printed = "If Ba'Gamnan deals damage to your opponent, the damage becomes 0 instead.";
        CardData baGamnan = new CardData(null, "Ba'Gamnan", "Wind", 3, 7000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), CardData.parseFieldAbilities(printed, "Forward"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, printed);
        MainWindow mw = new MainWindow();
        mw.placeCardInForwardZone(baGamnan);

        assertEquals(0, mw.combatDamagePointsToOpponent(baGamnan),
                "the replacement sets the damage to 0 — it is not a multiplier");
    }

    @Test
    void aDamageReplacementOverridesTheDoubler() {
        // Whichever order they are acquired in, "becomes N" replaces rather than stacks.
        CardData ramada = makeForward("Ramada", "Water", 4, 7000);
        MainWindow mw = resolveSelfEffect(ramada, RAMADA_SHARP_SPEAR);
        CardData onField = mw.p1ForwardCards.get(0);
        ActionResolver.parse("Ramada gains \"If Ramada deals damage to a Forward or your opponent, "
                + "double the damage instead.\" until the end of the turn.", onField)
                .accept(mw.buildGameContext(true));

        assertEquals(2, mw.combatDamagePointsToOpponent(onField),
                "still 2 — the replacement wins over doubling rather than yielding 4");
    }

    // =========================================================================================
    // Caius 18-108H: "When Caius attacks, select 1 of the 2 following actions. / 'Your opponent
    // discards 1 card.' / 'Caius gains "If Caius deals damage to a Forward or your opponent,
    // double the damage instead." until the end of the turn.'"
    //
    // The damage paths already understood that field ability when printed on a card, but a granted
    // one landed nowhere they looked, and the grant itself did not even parse: the quoted-ability
    // grant handled only three specific abilities, and its pattern required double quotes while
    // the nested wording inside a "select 1 of 2" option is printed with single ones. So picking
    // the second option silently did nothing and Caius kept dealing 1 damage.
    // =========================================================================================

    private static final String CAIUS_DOUBLER_OPTION =
            "Caius gains 'If Caius deals damage to a Forward or your opponent, "
            + "double the damage instead.' until the end of the turn.";

    /** Puts Caius on P1's field and resolves his damage-doubler option, returning the window. */
    private static MainWindow caiusGrantsDoubler() {
        MainWindow mw = new MainWindow();
        CardData caius = makeForward("Caius", "Fire", 2, 9000);
        mw.placeCardInForwardZone(caius);
        Consumer<GameContext> fn = ActionResolver.parse(CAIUS_DOUBLER_OPTION, caius);
        assertNotNull(fn, "the granted-doubler option should parse");
        fn.accept(mw.buildGameContext(true));
        return mw;
    }

    @Test
    void caiusDealsDoubleDamageToTheOpponentOnceGranted() {
        MainWindow mw = new MainWindow();
        CardData caius = makeForward("Caius", "Fire", 2, 9000);
        mw.placeCardInForwardZone(caius);

        assertFalse(mw.sourceHasOutgoingDmgToOpponentDoubler(caius),
                "no doubler before the option is taken — this is the 1-damage case");

        ActionResolver.parse(CAIUS_DOUBLER_OPTION, caius).accept(mw.buildGameContext(true));

        assertTrue(mw.sourceHasOutgoingDmgToOpponentDoubler(mw.p1ForwardCards.get(0)),
                "after the grant his combat damage to the opponent doubles");
    }

    @Test
    void caiusDealsDoubleCombatDamageToAForwardOnceGranted() {
        MainWindow mw = caiusGrantsDoubler();
        CardData caius  = mw.p1ForwardCards.get(0);
        CardData victim = makeForward("Victim", "Ice", 3, 7000);

        assertEquals(2, mw.fieldAbilityCombatOutgoingMult(caius, victim),
                "the same granted ability covers damage dealt to a Forward");
    }

    @Test
    void aGrantedFieldAbilityReadsBackThroughTheEffectiveView() {
        MainWindow mw = caiusGrantsDoubler();
        CardData caius = mw.p1ForwardCards.get(0);

        assertTrue(caius.fieldAbilities().isEmpty(), "nothing is printed on the card itself");
        assertEquals(1, mw.effectiveFieldAbilities(caius).size(),
                "the grant is only visible through the effective-abilities view");
    }

    @Test
    void caiusDamageDoublerExpiresAtEndOfTurn() {
        MainWindow mw = caiusGrantsDoubler();
        CardData caius = mw.p1ForwardCards.get(0);

        for (Consumer<GameContext> eot : new ArrayList<>(mw.endOfTurnEffects))
            eot.accept(mw.buildGameContext(true));

        assertFalse(mw.sourceHasOutgoingDmgToOpponentDoubler(caius),
                "\"until the end of the turn\" — the grant does not carry into the next turn");
        assertTrue(mw.effectiveFieldAbilities(caius).isEmpty());
    }

    @Test
    void theDoublerGrantIsNotHandedToAnUnrelatedCard() {
        MainWindow mw = caiusGrantsDoubler();
        CardData other = makeForward("Other", "Fire", 2, 5000);
        mw.placeCardInForwardZone(other);

        assertFalse(mw.sourceHasOutgoingDmgToOpponentDoubler(other),
                "the grant is keyed to the instance that gained it");
    }

    // =========================================================================================
    // Cissnei 22-028H: "When 1 or more Job Member of the Turks Forwards other than Cissnei you
    // control attack, activate Cissnei. Cissnei can attack once more this turn."
    //
    // The subject combines a Job filter with an "other than <self>" exclusion and a count form,
    // which no existing trigger subject covered — it fell through to the plain "attacks" trigger,
    // whose triggerCard must equal the card's own name, so the ability never fired at all.
    // The count form ("1 or more …") describes the declaration, so a party of qualifying Turks
    // fires it once rather than once per member.
    // =========================================================================================

    private static final String CISSNEI_TEXT =
            "Haste[[br]]   Cissnei enters the field dull.[[br]]   When 1 or more Job Member of the Turks "
            + "Forwards other than Cissnei you control attack, activate Cissnei. "
            + "Cissnei can attack once more this turn.";

    private static final String TURK = "Member of the Turks";

    /** P1 field with a dull Cissnei in slot 0, then {@code allies} in the following slots. */
    private static MainWindow cissneiSetUp(CardData... allies) {
        MainWindow mw = new MainWindow();
        mw.placeCardInForwardZone(makeJobForwardWithAutos("Cissnei", "Ice", 6000, TURK, CISSNEI_TEXT));
        mw.p1ForwardStates.set(0, CardState.DULL);
        for (CardData ally : allies) mw.placeCardInForwardZone(ally);
        return mw;
    }

    @Test
    void cissneiActivatesWhenAnotherTurkAttacks() {
        CardData reno = makeJobCard("Reno", "Ice", "Forward", TURK);
        MainWindow mw = cissneiSetUp(reno);

        mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(reno, true);

        assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0),
                "a fellow Turk attacking activates Cissnei");
    }

    @Test
    void cissneiDoesNotActivateOnHerOwnAttack() {
        MainWindow mw = cissneiSetUp();
        CardData cissnei = mw.p1ForwardCards.get(0);

        mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(cissnei, true);

        assertEquals(CardState.DULL, mw.p1ForwardStates.get(0),
                "\"other than Cissnei\" excludes her own attack");
    }

    @Test
    void cissneiDoesNotActivateWhenANonTurkAttacks() {
        CardData cloud = makeJobCard("Cloud", "Ice", "Forward", "SOLDIER");
        MainWindow mw = cissneiSetUp(cloud);

        mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(cloud, true);

        assertEquals(CardState.DULL, mw.p1ForwardStates.get(0),
                "the Job filter keeps non-Turks from triggering it");
    }

    @Test
    void cissneiDoesNotActivateForAnOpposingTurk() {
        MainWindow mw = cissneiSetUp();
        CardData rude = makeJobCard("Rude", "Ice", "Forward", TURK);
        mw.placeP2CardInForwardZone(rude);

        mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(rude, false);

        assertEquals(CardState.DULL, mw.p1ForwardStates.get(0),
                "the subject is \"you control\" — an opponent's Turk does not count");
    }

    @Test
    void aPartyOfTurksFiresCissneiOnlyOnce() {
        CardData reno = makeJobCard("Reno", "Ice", "Forward", TURK);
        CardData rude = makeJobCard("Rude", "Ice", "Forward", TURK);
        MainWindow mw = cissneiSetUp(reno, rude);
        mw.p1Turn.attackDeclarationsThisTurn = 1;   // one declaration covering both attackers

        mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(reno, true);
        mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(rude, true);

        assertEquals(1, mw.autoAbilityTriggers.firedThisDeclaration.size(),
                "\"1 or more … attack\" is one event for the declaration, not one per attacker");
    }

    @Test
    void aSecondAttackDeclarationFiresCissneiAgain() {
        CardData reno = makeJobCard("Reno", "Ice", "Forward", TURK);
        MainWindow mw = cissneiSetUp(reno);

        mw.p1Turn.attackDeclarationsThisTurn = 1;
        mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(reno, true);
        mw.p1ForwardStates.set(0, CardState.DULL);   // Cissnei attacked, so she is dull again
        mw.p1Turn.attackDeclarationsThisTurn = 2;
        mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(reno, true);

        assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0),
                "the once-per-declaration guard resets for a new attack declaration");
    }

    // Cissnei carries no "This effect will trigger only once per turn." clause (contrast Noel
    // 10-097R, which does), so every separate declaration by another Turk hands her another attack.
    // Three attacks off two allies is the intended reading, not a bug.
    @Test
    void chainedTurkDeclarationsEachGiveCissneiAnotherAttack() {
        CardData elena = makeJobCard("Elena", "Ice", "Forward", TURK);
        CardData reeve = makeJobCard("Reeve", "Ice", "Forward", TURK);
        MainWindow mw = cissneiSetUp(elena, reeve);
        CardData cissnei = mw.p1ForwardCards.get(0);
        assertFalse(cissnei.autoAbilities().get(0).oncePerTurn(),
                "no once-per-turn clause is what allows the chain");

        // Cissnei attacks on her own declaration and dulls.
        mw.p1Turn.attackDeclarationsThisTurn = 1;
        mw.recordAttackDeclared(cissnei);
        mw.p1ForwardStates.set(0, CardState.DULL);
        assertFalse(mw.hasAttackRemaining(cissnei), "one attack is all she gets unaided");

        // Elena declares separately — Cissnei is activated and handed a second attack.
        mw.p1Turn.attackDeclarationsThisTurn = 2;
        mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(elena, true);
        assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0), "activated for the second attack");
        assertTrue(mw.hasAttackRemaining(cissnei), "and permitted to take it");

        // She takes it and dulls again.
        mw.recordAttackDeclared(cissnei);
        mw.p1ForwardStates.set(0, CardState.DULL);
        mw.p1Turn.attackDeclarationsThisTurn = 3;
        assertFalse(mw.hasAttackRemaining(cissnei), "the second grant is spent");

        // Reeve declares separately — a third attack.
        mw.p1Turn.attackDeclarationsThisTurn = 4;
        mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(reeve, true);
        assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0), "activated again");
        assertTrue(mw.hasAttackRemaining(cissnei), "third attack granted");
        assertEquals(3, mw.attacksAllowed(cissnei), "one base attack plus one grant per declaration");
    }

    // Both halves of Cissnei's trigger matter: "activate Cissnei" satisfies the active requirement,
    // and "can attack once more" raises the count. Neither alone lets her attack again.
    @Test
    void activatingCissneiWithoutTheGrantWouldNotBeEnough() {
        CardData reno = makeJobCard("Reno", "Ice", "Forward", TURK);
        MainWindow mw = cissneiSetUp(reno);
        CardData cissnei = mw.p1ForwardCards.get(0);
        mw.recordAttackDeclared(cissnei);

        // Activation alone — the state is right but the count is spent.
        mw.p1ForwardStates.set(0, CardState.ACTIVE);
        assertFalse(mw.hasAttackRemaining(cissnei),
                "re-activating an attacked Forward must not by itself buy another attack");

        // The trigger supplies both halves.
        mw.p1Turn.attackDeclarationsThisTurn = 1;
        mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(reno, true);
        assertTrue(mw.hasAttackRemaining(cissnei));
    }

    // Odin (XVI) 24-112L Iron Flash, the action-ability twin of Cissnei's trigger.
    @Test
    void odinIronFlashActivatesAndReportsBothHalves() {
        CardData odin = makeForward("Odin (XVI)", "Lightning", 5, 9000);
        String effect = "Activate Odin (XVI). Odin (XVI) can attack once more this turn.";
        // Both halves must be named AND described; the description chain was missing AttackOnceMore,
        // which is what reported this ability as only partially parsed.
        assertEquals("ActivateNamedCard + AttackOnceMore",
                ActionResolver.matchedPatternName(effect, odin));
        assertEquals("ActivateNamedCard + AttackOnceMore",
                ActionResolver.fullDescription(effect, odin));

        Consumer<GameContext> fn = ActionResolver.parse(effect, odin);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        // The activate half resolves through selectCharacters/activateTarget; that route is covered
        // end to end against a real board by the Cissnei tests above.
        verify(ctx).grantAttackOnceMore("Odin (XVI)");
    }

    // A Brave Forward does not dull when it attacks, so it stays active. What stops it attacking
    // again is the attack count — and a multi-attack permission is what lifts that. Before attacks
    // were counted this was gated on being DULL, so Brave attackers could never use one at all.
    @Test
    void aBraveForwardStaysActiveAndAttacksAgainOnlyWithAPermission() {
        MainWindow mw = new MainWindow();
        CardData gilgamesh = makeTraitForward("Gilgamesh", "Wind", 5, 9000, CardData.Trait.BRAVE);
        mw.placeCardInForwardZone(gilgamesh);
        mw.gameState.getIdentity().put(gilgamesh, true);

        mw.executeP1Attack(List.of(0));
        assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0), "Brave does not dull on attack");
        assertFalse(mw.hasAttackRemaining(gilgamesh), "but one attack per turn is still the limit");

        mw.buildGameContext(true).grantMaxAttacksUntilEndOfTurn(gilgamesh, 2);
        assertTrue(mw.hasAttackRemaining(gilgamesh), "\"can attack twice\" now actually applies");

        mw.executeP1Attack(List.of(0));
        assertFalse(mw.hasAttackRemaining(gilgamesh), "and stops after the second");
    }

    // Ravana 14-087L: "can attack 4 times in the same turn" — the count form the old boolean
    // could not express at all.
    @Test
    void aFourAttackPermissionIsReadAsFour() {
        assertEquals(4, CardData.parseMaxAttacksPerTurn(
                "Ravana, Savior of the Gnath can attack 4 times in the same turn.",
                "Ravana, Savior of the Gnath"));
        assertEquals(2, CardData.parseMaxAttacksPerTurn(
                "Tifa can attack twice in the same turn.", "Tifa"));
        assertEquals(2, CardData.parseMaxAttacksPerTurn(
                "This Forward can attack twice per turn.", "This Forward"));
        assertEquals(1, CardData.parseMaxAttacksPerTurn("Tifa gains Haste.", "Tifa"));
    }

    // =========================================================================================
    // Cocytus 8-031R: "When Cocytus enters the field, choose up to 2 Forwards. If you control 4 or
    // more Ice Characters, Freeze them."  The "if you control" clause gates the Freeze, not the
    // choosing — the targets are picked either way, but they are only frozen when the condition
    // holds. Only the "deal it X damage" form of this followup was condition-aware, so every other
    // action (Freeze here) fell through to a matcher that scanned for the verb and ignored the
    // condition entirely.
    // =========================================================================================

    private static final String COCYTUS_EFFECT =
            "choose up to 2 Forwards. If you control 4 or more Ice Characters, Freeze them.";

    /** Resolves Cocytus's enters-the-field effect with {@code iceCount} Ice Characters controlled. */
    private static GameContext resolveCocytus(ForwardTarget chosen, int iceCount) {
        Consumer<GameContext> fn = ActionResolver.parse(COCYTUS_EFFECT, makeForward("Cocytus", "Ice", 4, 8000));
        assertNotNull(fn, "Cocytus's conditional-freeze effect should parse");
        GameContext ctx = mock(GameContext.class);
        // A mock returns an empty list here, which selectTargets reads as "targets were preloaded"
        // and short-circuits on — null is what an ordinary choose-your-own-target effect sees.
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(List.of(chosen));
        when(ctx.selfFieldCount(eq("Ice"), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(iceCount);
        fn.accept(ctx);
        return ctx;
    }

    @Test
    void cocytusFreezesTheChosenForwardsWithFourIceCharacters() {
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        verify(resolveCocytus(t, 4)).freezeTarget(t);
    }

    @Test
    void cocytusDoesNotFreezeWithFewerThanFourIceCharacters() {
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        verify(resolveCocytus(t, 3), never()).freezeTarget(any());
    }

    @Test
    void firionDiscardConditionalElementBranchesParsesCorrectly() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(FIRION_TEXT);
        assertEquals(1, abilities.size());
        List<ActionResolver.DiscardElementBranch> branches =
                ActionResolver.discardConditionalElementBranches(abilities.get(0).effectText());
        assertNotNull(branches);
        assertEquals(2, branches.size());
        assertEquals("Fire", branches.get(0).element());
        assertTrue(branches.get(0).effectText().toLowerCase().contains("first strike"));
        assertEquals("Water", branches.get(1).element());
        assertTrue(branches.get(1).effectText().toLowerCase().contains("activate"));
    }

    // =========================================================================================
    // The two "If the discarded card is of X Element" clauses are independent conditions, not an
    // if/else. Discarding a multi-element card such as a Water/Fire Forward satisfies both, so
    // both effects apply. The resolver used to read only the discarded card's *primary* element
    // and then run at most one branch, so a Water/Fire discard silently lost the other half.
    // =========================================================================================

    /** Resolves Firion's discard ability against a mock, with the given discarded-card elements. */
    private static GameContext resolveFirionDiscard(CardData firion, List<String> discardedElements) {
        ActionAbility ab = CardData.parseActionAbilities(FIRION_TEXT).get(0);
        Consumer<GameContext> fn = ActionResolver.parse(ab.effectText(), firion);
        assertNotNull(fn, "Firion's discard-conditional ability should parse");
        GameContext ctx = mock(GameContext.class);
        when(ctx.lastDiscardedCostCardElements()).thenReturn(discardedElements);
        fn.accept(ctx);
        return ctx;
    }

    /** The trait sets Firion was boosted with, in call order. */
    private static List<EnumSet<CardData.Trait>> capturedBoostTraits(GameContext ctx, CardData firion, int times) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<EnumSet<CardData.Trait>> traits = ArgumentCaptor.forClass(EnumSet.class);
        verify(ctx, times(times)).boostSourceForward(eq(firion), eq(2000), traits.capture());
        return traits.getAllValues();
    }

    @Test
    void aWaterFireDiscardTriggersBothOfFirionsBranches() {
        CardData firion = makeFirion(7000);
        GameContext ctx = resolveFirionDiscard(firion, List.of("Water", "Fire"));

        List<EnumSet<CardData.Trait>> traits = capturedBoostTraits(ctx, firion, 2);
        assertTrue(traits.stream().anyMatch(t -> t.contains(CardData.Trait.FIRST_STRIKE)),
                "the Fire branch grants First Strike");
        verify(ctx).logEntry("Effect: Activate Firion");
    }

    @Test
    void aFireOnlyDiscardTriggersOnlyFirionsFireBranch() {
        CardData firion = makeFirion(7000);
        GameContext ctx = resolveFirionDiscard(firion, List.of("Fire"));

        List<EnumSet<CardData.Trait>> traits = capturedBoostTraits(ctx, firion, 1);
        assertTrue(traits.get(0).contains(CardData.Trait.FIRST_STRIKE));
        verify(ctx, never()).logEntry("Effect: Activate Firion");
    }

    @Test
    void aWaterOnlyDiscardTriggersOnlyFirionsWaterBranch() {
        CardData firion = makeFirion(7000);
        GameContext ctx = resolveFirionDiscard(firion, List.of("Water"));

        List<EnumSet<CardData.Trait>> traits = capturedBoostTraits(ctx, firion, 1);
        assertFalse(traits.get(0).contains(CardData.Trait.FIRST_STRIKE),
                "the Water branch is a plain boost — First Strike belongs to the Fire branch");
        verify(ctx).logEntry("Effect: Activate Firion");
    }

    @Test
    void anUnmatchedDiscardTriggersNeitherOfFirionsBranches() {
        CardData firion = makeFirion(7000);
        GameContext ctx = resolveFirionDiscard(firion, List.of("Earth"));

        verify(ctx, never()).boostSourceForward(any(), anyInt(), any());
        verify(ctx, never()).logEntry("Effect: Activate Firion");
    }

    @Test
    void firionsWaterBranchLogsItsPowerBoostOnlyOnce() {
        // boostSourceForward is what actually reports the boost (and reports suppression instead
        // when it does not land), so the resolver must not announce the same sentence itself.
        CardData firion = makeFirion(7000);
        GameContext ctx = resolveFirionDiscard(firion, List.of("Water"));

        verify(ctx, never()).logEntry("Firion gains +2000 power until end of turn");
    }

    @Test
    void firionDeclinesToBlockAndDoesNotDiscardWhenHandHasNoMatchingElement() {
        // Firion (4000) can't survive a 6000-power attacker unblocked-boosted; hand has only an
        // Earth card, so neither branch of the ability can do anything — must not be used.
        MainWindow mw = firionSetUp(CardState.ACTIVE, 4000, 6000,
                List.of(makeForward("Filler", "Earth", 1, 1000)));
        int handSizeBefore = mw.gameState.getP2Hand().size();

        ForwardTarget blk = new ComputerPlayer(mw).chooseBlocker(6000,
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));

        assertNull(blk, "should decline to block rather than lose Firion for nothing");
        assertEquals(handSizeBefore, mw.gameState.getP2Hand().size(), "must not waste a card with no benefit");
    }

    @Test
    void firionUsesFireBranchToWinAnOtherwiseLosingBlock() {
        // Firion (4000) alone can't survive/break a 5000-power attacker, but +2000 power and
        // First Strike (6000) does. Hand has the needed Fire card plus an unrelated one.
        CardData fireCard = makeForward("Fire Fodder", "Fire", 1, 1000);
        MainWindow mw = firionSetUp(CardState.ACTIVE, 4000, 5000,
                List.of(makeForward("Filler", "Earth", 1, 1000), fireCard));

        ForwardTarget blk = new ComputerPlayer(mw).chooseBlocker(5000,
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));

        assertNotNull(blk, "should use the Fire branch to turn this into a winning block");
        assertEquals(0, blk.idx());
        assertEquals(ForwardTarget.CardZone.FORWARD, blk.zone());
        assertFalse(mw.gameState.getP2Hand().contains(fireCard), "the Fire card should have been discarded");
        assertEquals(6000, mw.effectiveP2ForwardPower(0), "Firion should now be boosted to 6000 power");
        assertTrue(mw.p2ForwardTempTraits.get(0).contains(CardData.Trait.FIRST_STRIKE), "Firion should have gained First Strike");
    }

    @Test
    void firionUsesWaterBranchToActivateADullFirionSoItCanBlock() {
        // Dull Firion is normally not even a candidate; the Water branch both boosts power and
        // activates it, so it should become the chosen blocker against a 5000-power attacker.
        CardData waterCard = makeForward("Water Fodder", "Water", 1, 1000);
        MainWindow mw = firionSetUp(CardState.DULL, 4000, 5000,
                List.of(makeForward("Filler", "Earth", 1, 1000), waterCard));

        ForwardTarget blk = new ComputerPlayer(mw).chooseBlocker(5000,
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));

        assertNotNull(blk, "should activate dull Firion via the Water branch so it can block");
        assertEquals(0, blk.idx());
        assertFalse(mw.gameState.getP2Hand().contains(waterCard), "the Water card should have been discarded");
        assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "Firion should now be active");
        assertEquals(6000, mw.effectiveP2ForwardPower(0), "Firion should now be boosted to 6000 power");
    }

    @Test
    void firionDoesNotUseTrickWhenAnAdequateBlockerAlreadyExists() {
        // Firion (7000, active) already survives/breaks a 5000-power attacker unaided — the
        // ability must not be spent for no reason.
        CardData fireCard = makeForward("Fire Fodder", "Fire", 1, 1000);
        MainWindow mw = firionSetUp(CardState.ACTIVE, 7000, 5000,
                List.of(fireCard));
        int handSizeBefore = mw.gameState.getP2Hand().size();

        ForwardTarget blk = new ComputerPlayer(mw).chooseBlocker(5000,
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));

        assertNotNull(blk);
        assertEquals(0, blk.idx());
        assertEquals(handSizeBefore, mw.gameState.getP2Hand().size(), "must not spend the card when already winning");
    }

    // =========================================================================================
    // Llednar: "Discard 2 cards: Remove all Fortune Counters from Llednar. Each player can use
    // this ability." — the "Remove all [Name] Counters from [CardName]." action and the
    // "Each player can use this ability." flag.
    // =========================================================================================

    @Test
    void llednarAbilityParsesDiscardCostAndUsableByEitherPlayerFlag() {
        String text = "When Llednar enters the field due to your cast, place 1 Fortune Counter on Llednar.[[br]]   "
                + "If a Fortune Counter is placed on Llednar, Llednar cannot be broken.[[br]]   "
                + "Discard 2 cards: Remove all Fortune Counters from Llednar. Each player can use this ability.";

        List<ActionAbility> abilities = CardData.parseActionAbilities(text);
        assertEquals(1, abilities.size());
        ActionAbility ability = abilities.get(0);

        assertTrue(ability.usableByEitherPlayer());
        assertEquals(1, ability.discardCosts().size());
        assertEquals(2, ability.discardCosts().get(0).count());
        assertEquals("Remove all Fortune Counters from Llednar. Each player can use this ability.",
                ability.effectText());
    }

    @Test
    void llednarRemoveAllCountersClearsExactCurrentCount() {
        CardData source = mock(CardData.class);
        when(source.name()).thenReturn("Llednar");
        GameContext ctx = mock(GameContext.class);
        when(ctx.getCounters(source, "Fortune")).thenReturn(3);

        Consumer<GameContext> fn = ActionResolver.parse(
                "Remove all Fortune Counters from Llednar. Each player can use this ability.", source);
        assertNotNull(fn);
        fn.accept(ctx);

        verify(ctx).removeCounters(source, "Fortune", 3);
    }

    @Test
    void llednarRemoveAllCountersIsNoOpWhenNonePresent() {
        CardData source = mock(CardData.class);
        when(source.name()).thenReturn("Llednar");
        GameContext ctx = mock(GameContext.class);
        when(ctx.getCounters(source, "Fortune")).thenReturn(0);

        Consumer<GameContext> fn = ActionResolver.parse(
                "Remove all Fortune Counters from Llednar. Each player can use this ability.", source);
        assertNotNull(fn);
        fn.accept(ctx);

        verify(ctx, never()).removeCounters(any(), any(), anyInt());
    }

    @Test
    void llednarRemoveAllCountersOnlyAppliesToNamedTarget() {
        // "Llednar" refers to itself; a differently-named source must not match.
        CardData source = mock(CardData.class);
        when(source.name()).thenReturn("Someone Else");

        Consumer<GameContext> fn = ActionResolver.parse(
                "Remove all Fortune Counters from Llednar. Each player can use this ability.", source);
        assertNull(fn);
    }

    // =========================================================================================
    // Samurai / Bard / Summoner: the CP_FIXED "extra cost" pattern — "If you cast [Name], you
    // may pay 《Element》《N》 as an extra cost." plus the paired "If you paid the extra cost,
    // [effect]" auto-ability clause.
    // =========================================================================================

    private static CardData makeExtraCostCard(String name, String element, String textEn) {
        return new CardData(null, name, element, 1, 4000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, textEn);
    }

    @Test
    void samuraiParsesAsWindPlusTwoGenericFixedCp() {
        CardData samurai = makeExtraCostCard("Samurai", "Fire",
                "If you cast Samurai, you may pay 《Wind》《2》 as an extra cost.[[br]]"
                + "When Samurai enters the field, choose 1 Forward of cost 6 or more. If you paid the extra cost, break it.");
        ExtraCost ec = samurai.extraCost();
        assertNotNull(ec);
        assertEquals(ExtraCost.Type.CP_FIXED, ec.type());
        assertEquals(List.of("Wind", "", ""), ec.cpElements());
    }

    @Test
    void bardParsesAsEarthPlusThreeGenericFixedCp() {
        CardData bard = makeExtraCostCard("Bard", "Ice",
                "If you cast Bard, you may pay 《Earth》《3》 as an extra cost.[[br]]"
                + "When Bard enters the field, choose 1 dull Forward. If you paid the extra cost, break it.");
        ExtraCost ec = bard.extraCost();
        assertNotNull(ec);
        assertEquals(ExtraCost.Type.CP_FIXED, ec.type());
        assertEquals(List.of("Earth", "", "", ""), ec.cpElements());
    }

    @Test
    void samuraiBreaksTargetOnlyWhenExtraCostPaid() {
        String rawEffect = "choose 1 Forward of cost 6 or more. If you paid the extra cost, break it.";

        String paidText = ActionResolver.applyExtraCostPaid(rawEffect);
        String notPaidText = ActionResolver.stripExtraCostClause(rawEffect);

        GameContext ctx = mock(GameContext.class);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));

        Consumer<GameContext> paidFn = ActionResolver.parse(paidText, null);
        assertNotNull(paidFn, "paid-branch text should parse: " + paidText);
        paidFn.accept(ctx);
        verify(ctx).breakTarget(t);

        // Not-paid branch: the clause is stripped entirely, leaving just "choose a Forward" with
        // no follow-up action — nothing should break.
        Consumer<GameContext> notPaidFn = ActionResolver.parse(notPaidText, null);
        if (notPaidFn != null) {
            GameContext ctx2 = mock(GameContext.class);
            notPaidFn.accept(ctx2);
            verify(ctx2, never()).breakTarget(any());
        }
    }

    // Summoner's whole ability is the condition itself — no unconditional lead-in before
    // "If you paid the extra cost" (unlike Samurai's "Choose 1 Forward … If you paid …").
    // This shape previously broke applyExtraCostPaid/stripExtraCostClause (both require a
    // non-empty prefix before the clause), which — worse — meant ActionResolver.parse() was
    // handed the raw, still-conditional text and would NPE on the null-source smoke test,
    // or silently execute the effect unconditionally in the real (non-null source) game path.
    @Test
    void summonerSelectsOpponentForwardOnlyWhenExtraCostPaid() {
        CardData summoner = mock(CardData.class);
        when(summoner.name()).thenReturn("Summoner");
        String rawEffect = "if you paid the extra cost, your opponent selects 1 Forward they control. Put it into the Break Zone.";

        String paidText = ActionResolver.applyExtraCostPaid(rawEffect);
        assertEquals("Your opponent selects 1 Forward they control. Put it into the Break Zone.", paidText);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));

        Consumer<GameContext> paidFn = ActionResolver.parse(paidText, summoner);
        assertNotNull(paidFn, "paid-branch text should parse: " + paidText);
        paidFn.accept(ctx);
        verify(ctx).forceTargetToBreakZone(t);

        // Not-paid: the whole ability was the condition, so stripping it leaves nothing at all.
        String notPaidText = ActionResolver.stripExtraCostClause(rawEffect);
        assertTrue(notPaidText.isBlank(), "not-paid text should be empty: [" + notPaidText + "]");
    }

    // =========================================================================================
    // Yuffie: regression test for a bug where activating a "Choose any number of [targets]..."
    // action ability (e.g. "Doom of the Living": "Choose any number of Forwards. Divide 24000
    // damage among them as you like.") silently failed — no target prompt, no effect, nothing on
    // the stack — because ActionResolver.preSelectTargets had its own separate copy of the
    // "Choose N" count extraction that didn't know about the "any number of" branch and threw a
    // NumberFormatException parsing a null count group, which aborted activation before the
    // ability ever reached the stack.
    // =========================================================================================

    private static CardData makePreSelectCard(String name) {
        return new CardData(null, name, "Wind", 3, 6000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    @Test
    void anyNumberChooseDoesNotThrowAndPromptsWithUnboundedMax() {
        CardData yuffie = makePreSelectCard("Yuffie");
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(
                eq(Integer.MAX_VALUE), eq(true), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));

        List<ForwardTarget> result = assertDoesNotThrow(() ->
                ActionResolver.preSelectTargets(
                        "Choose any number of Forwards. Divide 24000 damage among them as you like. (Units must be 1000.)",
                        yuffie, 0, ctx));

        assertEquals(List.of(t), result);
        verify(ctx).selectCharacters(eq(Integer.MAX_VALUE), eq(true), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    void plainChooseCountStillWorks() {
        // Regression guard: the fix must not break the ordinary "Choose N" path.
        CardData card = makePreSelectCard("Barret");
        GameContext ctx = mock(GameContext.class);
        when(ctx.selectCharacters(
                eq(2), eq(true), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of());

        List<ForwardTarget> result = assertDoesNotThrow(() ->
                ActionResolver.preSelectTargets(
                        "Choose up to 2 Forwards. Divide 10000 damage among them as you like. (Units must be 1000.)",
                        card, 0, ctx));

        assertNotNull(result);
    }

    // =========================================================================================
    // Mime: "Mime's power becomes the same as your opponent's weakest Forward until the end of
    // the turn."
    // =========================================================================================

    private static final String MIME_TEXT =
            "Mime's power becomes the same as your opponent's weakest Forward until the end of the turn.";

    @Test
    void mimeSetsSourcePowerToOpponentsLowestForwardPower() {
        CardData mime = mock(CardData.class);
        when(mime.name()).thenReturn("Mime");

        Consumer<GameContext> fn = ActionResolver.parse(MIME_TEXT, mime);
        assertNotNull(fn, "Expected Mime's ability text to parse");

        GameContext ctx = mock(GameContext.class);
        when(ctx.opponentLowestForwardPower()).thenReturn(3000);

        fn.accept(ctx);

        verify(ctx).setSourceForwardBasePower(mime, 3000, EnumSet.noneOf(CardData.Trait.class));
    }

    @Test
    void mimeDoesNotFireWhenSourceNameDoesNotMatch() {
        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Not Mime");

        Consumer<GameContext> fn = ActionResolver.parse(MIME_TEXT, other);

        assertNull(fn, "Ability text naming Mime should not resolve for a differently-named source");
    }

    // =========================================================================================
    // "《Dull》, discard 1 card: Choose 3 cards in your opponent's Break Zone. Remove them from
    // the game. If the discarded card is of Water Element, also draw 1 card, then discard 1
    // card." — the single-branch, additive-only discard-element conditional attached as a
    // secondary effect after a "Choose ... Remove them from the game" primary.
    // =========================================================================================

    private static final String DISCARD_RFG_EFFECT_TEXT =
            "Choose 3 cards in your opponent's Break Zone. Remove them from the game. "
            + "If the discarded card is of Water Element, also draw 1 card, then discard 1 card.";

    private static void stubOpponentBzTargets(GameContext ctx, List<ForwardTarget> result) {
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.selectCharactersFromBreakZone(
                eq(3), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(result);
    }

    @Test
    void removesThreeChosenCardsFromOpponentBzRegardlessOfDiscardElement() {
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.BREAK_ZONE);
        ForwardTarget t1 = new ForwardTarget(false, 1, ForwardTarget.CardZone.BREAK_ZONE);
        ForwardTarget t2 = new ForwardTarget(false, 2, ForwardTarget.CardZone.BREAK_ZONE);
        stubOpponentBzTargets(ctx, List.of(t0, t1, t2));
        when(ctx.lastDiscardedCostCardElements()).thenReturn(List.of("Fire"));

        Consumer<GameContext> fn = ActionResolver.parse(DISCARD_RFG_EFFECT_TEXT, null);
        assertNotNull(fn);
        fn.accept(ctx);

        verify(ctx).removeTargetFromGame(t0);
        verify(ctx).removeTargetFromGame(t1);
        verify(ctx).removeTargetFromGame(t2);
        verify(ctx, never()).drawCards(anyInt());
        verify(ctx, never()).selfDiscard(anyInt());
    }

    @Test
    void alsoDrawsThenDiscardsWhenDiscardedCardIsWater() {
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.BREAK_ZONE);
        stubOpponentBzTargets(ctx, List.of(t0));
        when(ctx.lastDiscardedCostCardElements()).thenReturn(List.of("Water"));

        Consumer<GameContext> fn = ActionResolver.parse(DISCARD_RFG_EFFECT_TEXT, null);
        assertNotNull(fn);
        fn.accept(ctx);

        verify(ctx).removeTargetFromGame(t0);
        verify(ctx).drawCards(1);
        verify(ctx).selfDiscard(1);
    }

    // Sahagin Chief self-bounce classification — the CPU uses these to avoid sacrificing a card to
    // the Break Zone just to return its own Forward to hand (a self-defeating proactive play).
    @Test
    void classifiesSahaginSelfOnlyForwardBounce() {
        String selfOnly = "Choose 1 Forward you control. Return it to its owner's hand.";
        assertTrue(ActionResolver.isReturnForwardToHandEffect(selfOnly));
        assertTrue(ActionResolver.isReturnOwnForwardToHandEffect(selfOnly),
                "\"Forward you control\" bounce must be recognised as self-only");
    }

    @Test
    void classifiesSahaginAnyTargetForwardBounceAsNotSelfOnly() {
        String anyTarget = "Choose up to 2 Forwards. Return them to their owners' hands. "
                + "You can only use this ability if 3 or more Monster Counters are placed on Sahagin Chief.";
        assertTrue(ActionResolver.isReturnForwardToHandEffect(anyTarget));
        assertFalse(ActionResolver.isReturnOwnForwardToHandEffect(anyTarget),
                "an unqualified \"Forwards\" bounce can hit the opponent and is not self-only");
    }

    @Test
    void nonBounceEffectIsNotClassifiedAsForwardBounce() {
        assertFalse(ActionResolver.isReturnForwardToHandEffect(
                "Choose 1 Forward. Deal it 8000 damage."));
        assertFalse(ActionResolver.isReturnForwardToHandEffect(
                "Choose 1 Backup. Return it to its owner's hand."));
    }

    // Delita (16-014R): "Remove 1 Forward other than Delita from the game: Delita gains \"If Delita
    // deals damage to a Forward, the damage increases by 2000 instead.\" until the end of the turn."
    // The action ability grants the source its own outgoing-flat-boost field ability for the turn.
    private static final String DELITA_GRANT_EFFECT =
            "Delita gains \"If Delita deals damage to a Forward, the damage increases by 2000 instead.\" "
            + "until the end of the turn.";

    @Test
    void delitaGrantsSelfOutgoingDamageBoostUntilEot() {
        CardData delita = makeForward("Delita", "Ice", 4, 8000);
        assertEquals("GainOutgoingDmgBoostUntilEot",
                ActionResolver.matchedPatternName(DELITA_GRANT_EFFECT, delita));
        Consumer<GameContext> fn = ActionResolver.parse(DELITA_GRANT_EFFECT, delita);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).boostSelfOutgoingDamageThisTurn(delita, 2000);
    }

    @Test
    void delitaGrantActionAbilityParsesFromCardText() {
        String abilityLine = "Remove 1 Forward other than Delita from the game: " + DELITA_GRANT_EFFECT;
        CardData delita = makeForward("Delita", "Ice", 4, 8000);
        ActionAbility ab = CardData.parseActionAbilities(abilityLine).stream().findFirst().orElse(null);
        assertNotNull(ab, "the action ability should parse");
        assertFalse(ab.removeFromGameCosts().isEmpty(), "its cost is remove-from-game");
        assertNotNull(ActionResolver.parse(ab.effectText(), delita),
                "the granted-ability effect text (with its quotes) should be recognized");
    }

    // Hilda (6-122H): "draw 1 card for each Forward you control. You can only draw up to 4 cards
    // with this ability." — draws min(Forwards, 4).
    private static final String HILDA_DRAW =
            "draw 1 card for each Forward you control. You can only draw up to 4 cards with this ability.";

    @Test
    void hildaDrawsOnePerForwardBelowCap() {
        Consumer<GameContext> fn = ActionResolver.parse(HILDA_DRAW, null);
        assertNotNull(fn, "Hilda's draw-per-Forward ability should parse");
        GameContext ctx = mock(GameContext.class);
        when(ctx.selfForwardCount()).thenReturn(2);
        fn.accept(ctx);
        verify(ctx).drawCards(2);
    }

    @Test
    void hildaDrawCapsAtFour() {
        Consumer<GameContext> fn = ActionResolver.parse(HILDA_DRAW, null);
        GameContext ctx = mock(GameContext.class);
        when(ctx.selfForwardCount()).thenReturn(6);
        fn.accept(ctx);
        verify(ctx).drawCards(4);
    }

    @Test
    void hildaDrawsNothingWithNoForwards() {
        Consumer<GameContext> fn = ActionResolver.parse(HILDA_DRAW, null);
        GameContext ctx = mock(GameContext.class);
        when(ctx.selfForwardCount()).thenReturn(0);
        fn.accept(ctx);
        verify(ctx, never()).drawCards(anyInt());
    }

    // "Choose 1 Forward. Remove the top card of your deck from the game. If the removed card is a
    // Forward, break it. If not, deal it 3000 damage." — both branches act on the chosen Forward.
    private static final String RFP_TOP_DECK_IF_FWD =
            "Choose 1 Forward. Remove the top card of your deck from the game. "
            + "If the removed card is a Forward, break it. If not, deal it 3000 damage.";

    private static GameContext rfpTopDeckMock(ForwardTarget t, boolean removedForward) {
        GameContext ctx = mock(GameContext.class);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(List.of(t));
        when(ctx.removeTopCardOfDeckFromGameIsForward()).thenReturn(removedForward);
        return ctx;
    }

    @Test
    void topDeckRemovalCountReportsNeededCardsForActivationGate() {
        // canActivateAbility uses this to forbid the ability when the deck can't supply the removal.
        assertEquals(1, ActionResolver.topDeckRemovalCount(RFP_TOP_DECK_IF_FWD));
        assertEquals(3, ActionResolver.topDeckRemovalCount("Remove the top 3 cards of your deck from the game."));
        assertEquals(0, ActionResolver.topDeckRemovalCount("Choose 1 Forward. Break it."));
    }

    @Test
    void removeTopDeckBreaksChosenTargetWhenRemovedIsForward() {
        Consumer<GameContext> fn = ActionResolver.parse(RFP_TOP_DECK_IF_FWD, null);
        assertNotNull(fn, "the remove-top-deck conditional ability should parse");
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        GameContext ctx = rfpTopDeckMock(t, true);
        fn.accept(ctx);
        verify(ctx).breakTarget(t);
        verify(ctx, never()).damageTarget(eq(t), anyInt());
    }

    @Test
    void removeTopDeckDamagesChosenTargetWhenRemovedIsNotForward() {
        Consumer<GameContext> fn = ActionResolver.parse(RFP_TOP_DECK_IF_FWD, null);
        assertNotNull(fn);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        GameContext ctx = rfpTopDeckMock(t, false);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 3000);
        verify(ctx, never()).breakTarget(t);
    }

    // Granted field abilities via "gains \"…\" until the end of the turn":
    // Tsukinowa (cannot be blocked by cost), Ace/Tifa (can attack twice, with traits/power).
    @Test
    void tsukinowaGrantsSelfCannotBeBlockedByCost() {
        CardData tsukinowa = makeForward("Tsukinowa", "Wind", 2, 5000);
        String effect = "Tsukinowa gains \"Tsukinowa cannot be blocked by a Forward of cost 5 or more.\" "
                + "until the end of the turn.";
        assertEquals("GainsQuotedFieldAbilityUntilEot", ActionResolver.matchedPatternName(effect, tsukinowa));
        Consumer<GameContext> fn = ActionResolver.parse(effect, tsukinowa);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).grantSelfCannotBeBlockedByCost(tsukinowa, 5, true);
    }

    // Layle 28-076C: the grant is followed by "Each player can use this ability.", a restriction
    // sentence captured as a flag on the ability — it must not defeat the end-anchored grant pattern.
    @Test
    void layleGrantsSelfCannotBlockDespiteTrailingEachPlayerSentence() {
        CardData layle = makeForward("Layle", "Wind", 1, 3000);
        String effect = "Layle gains \"Layle cannot block.\" until the end of the turn. "
                + "Each player can use this ability.";
        assertEquals("GainsQuotedFieldAbilityUntilEot", ActionResolver.matchedPatternName(effect, layle));
        Consumer<GameContext> fn = ActionResolver.parse(effect, layle);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).grantSelfCannotBlockUntilEndOfTurn(layle);
    }

    @Test
    void layleActionAbilityIsUsableByEitherPlayer() {
        String text = "If Layle is dealt damage, the damage becomes 0 instead.[[br]]"
                + "《1》: Layle gains \"Layle cannot block.\" until the end of the turn. "
                + "Each player can use this ability.";
        List<ActionAbility> abilities = CardData.parseActionAbilities(text);
        assertEquals(1, abilities.size());
        ActionAbility ability = abilities.get(0);
        assertTrue(ability.usableByEitherPlayer());
        CardData layle = makeForward("Layle", "Wind", 1, 3000);
        assertNotNull(ActionResolver.parse(ability.effectText(), layle),
                "Layle's action ability effect should resolve");
    }

    @Test
    void aceGrantsBraveAndAttackTwice() {
        CardData ace = makeForward("Ace", "Fire", 3, 7000);
        String effect = "Until the end of the turn, Ace gains Brave and \"Ace can attack twice in the same turn.\"";
        Consumer<GameContext> fn = ActionResolver.parse(effect, ace);
        assertNotNull(fn, "Ace's grant (Brave + attack twice) should parse");
        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).boostSourceForward(eq(ace), eq(0), eq(java.util.EnumSet.of(CardData.Trait.BRAVE)));
        verify(ctx).grantMaxAttacksUntilEndOfTurn(ace, 2);
    }

    @Test
    void tifaGrantsPowerHasteAndAttackTwice() {
        CardData tifa = makeForward("Tifa", "Fire", 4, 8000);
        String effect = "Until the end of the turn, Tifa gains +2000 power, Haste and "
                + "\"Tifa can attack twice in the same turn.\"";
        Consumer<GameContext> fn = ActionResolver.parse(effect, tifa);
        assertNotNull(fn, "Tifa's grant (power + Haste + attack twice) should parse");
        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).boostSourceForward(eq(tifa), eq(2000), eq(java.util.EnumSet.of(CardData.Trait.HASTE)));
        verify(ctx).grantMaxAttacksUntilEndOfTurn(tifa, 2);
    }

    // Gladiolus: "Choose 1 Forward. Deal it damage equal to Gladiolus' power." — the card's own
    // text uses a bare apostrophe (no trailing 's'), so "<name>'s power" must accept "'s?".
    @Test
    void dealDamageEqualToNamedPowerAllowsApostropheWithoutS() {
        String full = "Choose 1 Forward. Deal it damage equal to Gladiolus' power.";
        Consumer<GameContext> fn = ActionResolver.parse(full, null);
        assertNotNull(fn, "\"Gladiolus' power\" (apostrophe, no s) should parse");
        GameContext ctx = mock(GameContext.class);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(List.of(t));
        when(ctx.fieldForwardPowerByName("Gladiolus")).thenReturn(6000);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 6000);
    }

    @Test
    void dealDamageEqualToNamedPowerStillWorksWithApostropheS() {
        String full = "Choose 1 Forward. Deal it damage equal to Ifrit's power.";
        Consumer<GameContext> fn = ActionResolver.parse(full, null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(List.of(t));
        when(ctx.fieldForwardPowerByName("Ifrit")).thenReturn(9000);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 9000);
    }

    // Amarant: "When Amarant enters the field, choose 1 Forward. Deal it 3000 damage for every
    // 2 Fire Characters you control." — "for every N" scales by groups of N, rounding down, and
    // must not be claimed by the flat-damage followup (which would drop the scaling entirely).
    private static final String AMARANT_DAMAGE_PER_TWO_FIRE =
            "Choose 1 Forward. Deal it 3000 damage for every 2 Fire Characters you control.";

    /** Stubs a single chosen Forward and the Fire-Character count Amarant scales off. */
    private static ForwardTarget stubChooseWithFireCharacterCount(GameContext ctx, int fireCharacters) {
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(List.of(t));
        when(ctx.countSelfFieldCards(true, true, true, null, null, null, "fire", -1))
                .thenReturn(fireCharacters);
        return t;
    }

    @Test
    void damageForEveryTwoScalesByGroupCount() {
        Consumer<GameContext> fn = ActionResolver.parse(AMARANT_DAMAGE_PER_TWO_FIRE, null);
        assertNotNull(fn, "\"for every 2 Fire Characters you control\" should parse");
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseWithFireCharacterCount(ctx, 4);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 6000);
    }

    @Test
    void damageForEveryTwoRoundsGroupCountDown() {
        Consumer<GameContext> fn = ActionResolver.parse(AMARANT_DAMAGE_PER_TWO_FIRE, null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseWithFireCharacterCount(ctx, 5);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 6000);
    }

    @Test
    void damageForEveryTwoDealsNothingBelowOneFullGroup() {
        Consumer<GameContext> fn = ActionResolver.parse(AMARANT_DAMAGE_PER_TWO_FIRE, null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseWithFireCharacterCount(ctx, 1);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 0);
    }

    // Tonberry: "When Tonberry is put from the field into the Break Zone, choose 1 Forward opponent
    // controls. Deal it 1000 damage for every 2 Forwards in your Break Zone." — a Break Zone count
    // filtered by card type, which the Card-Name-only Break Zone branch could not express.
    private static final String TONBERRY_DAMAGE_PER_TWO_BZ_FORWARDS =
            "Choose 1 Forward opponent controls. Deal it 1000 damage for every 2 Forwards in your Break Zone.";

    /** Stubs a single chosen Forward; Break Zone counts are stubbed per test. */
    private static ForwardTarget stubChooseOneTarget(GameContext ctx) {
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(List.of(t));
        return t;
    }

    @Test
    void damageForEveryTwoBreakZoneForwardsScalesByGroupCount() {
        Consumer<GameContext> fn = ActionResolver.parse(TONBERRY_DAMAGE_PER_TWO_BZ_FORWARDS, null);
        assertNotNull(fn, "\"for every 2 Forwards in your Break Zone\" should parse");
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseOneTarget(ctx);
        when(ctx.countSelfBreakZoneCardsByType(true, false, false, false)).thenReturn(7);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 3000);
    }

    // The Break Zone count is type-filtered, so Backups/Monsters/Summons sharing the zone must not
    // inflate it — Tonberry itself is a Monster and lands there before its own ability resolves.
    @Test
    void damageForEveryTwoBreakZoneForwardsCountsForwardsOnly() {
        Consumer<GameContext> fn = ActionResolver.parse(TONBERRY_DAMAGE_PER_TWO_BZ_FORWARDS, null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseOneTarget(ctx);
        when(ctx.countSelfBreakZoneCardsByType(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(0);
        when(ctx.countSelfBreakZoneCardsByType(true, false, false, false)).thenReturn(4);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 2000);
        verify(ctx, never()).countSelfBreakZoneCards(any(), any());
    }

    // Yuna 20-117L (Holy): "Deal it 3000 damage for each Summon in your Break Zone." — the same
    // Break-Zone-by-type source, ungrouped, over the Summon type.
    @Test
    void damageForEachBreakZoneSummonScalesPerSummon() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 3000 damage for each Summon in your Break Zone.", null);
        assertNotNull(fn, "\"for each Summon in your Break Zone\" should parse");
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseOneTarget(ctx);
        when(ctx.countSelfBreakZoneCardsByType(false, false, false, true)).thenReturn(3);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 9000);
    }

    // Atomos 25-053H and Cyan 24-003H: "Deal it 1000 damage for each card in your Break Zone."
    // "card" means the whole zone, so it must reach the unfiltered count rather than the
    // type-filtered one that backs "Forwards in your Break Zone".
    @Test
    void damageForEachBreakZoneCardCountsTheWholeZone() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 1000 damage for each card in your Break Zone.", null);
        assertNotNull(fn, "\"for each card in your Break Zone\" should parse");
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseOneTarget(ctx);
        when(ctx.countSelfBreakZoneCards(null, null)).thenReturn(6);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 6000);
        verify(ctx, never()).countSelfBreakZoneCardsByType(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    // "Card Name X in your Break Zone" must still reach the name-filtered count, not the type one.
    @Test
    void damageForEachNamedBreakZoneCardStillUsesNameFilter() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 1000 damage for each Card Name Shiva in your Break Zone.", null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseOneTarget(ctx);
        when(ctx.countSelfBreakZoneCards("Shiva", null)).thenReturn(2);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 2000);
        verify(ctx, never()).countSelfBreakZoneCardsByType(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    // "for each" stays group size 1 — the widened pattern must not change the ungrouped form.
    @Test
    void damageForEachStillScalesOnePerCharacter() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 1000 damage for each Fire Character you control.", null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseWithFireCharacterCount(ctx, 3);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 3000);
    }

    // "If the discarded card is of Wind Element, it also loses all its abilities until the end of the
    // turn." — the target-additive discard conditional tacked onto a "Choose 1 Forward" primary.
    private static final String DISCARD_COND_LOSE_ABILITIES =
            "If the discarded card is of Wind Element, it also loses all its abilities until the end of the turn.";

    @Test
    void discardConditionalTargetLosesAbilitiesWhenElementMatches() {
        Consumer<GameContext> fn = ActionResolver.parse(DISCARD_COND_LOSE_ABILITIES, null);
        assertNotNull(fn, "target-additive discard conditional (\"it also loses...\") should parse");
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.lastChosenTargets()).thenReturn(List.of(t));
        when(ctx.lastDiscardedCostCardElements()).thenReturn(List.of("Wind"));
        fn.accept(ctx);
        verify(ctx).targetLoseAllAbilitiesUntilEndOfTurn(t);
    }

    @Test
    void discardConditionalTargetNoAbilityLossWhenElementDiffers() {
        Consumer<GameContext> fn = ActionResolver.parse(DISCARD_COND_LOSE_ABILITIES, null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        when(ctx.lastDiscardedCostCardElements()).thenReturn(List.of("Fire"));
        fn.accept(ctx);
        verify(ctx, never()).targetLoseAllAbilitiesUntilEndOfTurn(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void discardConditionalRoutesThroughChooseForwardPrimary() {
        String full = "Choose 1 Forward. It loses 1000 power until the end of the turn. "
                + "If the discarded card is of Wind Element, it also loses all its abilities until the end of the turn.";
        Consumer<GameContext> fn = ActionResolver.parse(full, null);
        assertNotNull(fn, "the full Choose-Forward + conditional-followup effect should parse");
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(List.of(t));
        when(ctx.lastChosenTargets()).thenReturn(List.of(t));
        when(ctx.lastDiscardedCostCardElements()).thenReturn(List.of("Wind"));
        fn.accept(ctx);
        verify(ctx).targetLoseAllAbilitiesUntilEndOfTurn(t);
    }

    // Corsair: "draw 1 card, then discard 1 card from your hand. If the discarded card is a
    // Multi-Element card, draw 1 card, then discard 1 card from your hand." — additive repeat.
    private static final String CORSAIR_DRAW_DISCARD =
            "draw 1 card, then discard 1 card from your hand. If the discarded card is a "
            + "Multi-Element card, draw 1 card, then discard 1 card from your hand.";

    @Test
    void drawDiscardRepeatsWhenDiscardIsMultiElement() {
        Consumer<GameContext> fn = ActionResolver.parse(CORSAIR_DRAW_DISCARD, null);
        assertNotNull(fn, "draw/discard + Multi-Element conditional should parse");
        GameContext ctx = mock(GameContext.class);
        when(ctx.lastDiscardedCardIsMultiElement()).thenReturn(true);
        fn.accept(ctx);
        verify(ctx, times(2)).drawCards(1);
        verify(ctx, times(2)).selfDiscard(1);
    }

    @Test
    void drawDiscardDoesNotRepeatWhenDiscardNotMultiElement() {
        Consumer<GameContext> fn = ActionResolver.parse(CORSAIR_DRAW_DISCARD, null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        when(ctx.lastDiscardedCardIsMultiElement()).thenReturn(false);
        fn.accept(ctx);
        verify(ctx, times(1)).drawCards(1);
        verify(ctx, times(1)).selfDiscard(1);
    }

    // Prishe: "Discard 1 card: Prishe gains +2000 power ... If the discarded card is a Card Name
    // Prishe, Prishe gains +4000 power ... instead." — the "instead" (replacement) discard conditional.
    private static final String PRISHE_INSTEAD =
            "Prishe gains +2000 power until the end of the turn. If the discarded card is a Card Name "
            + "Prishe, Prishe gains +4000 power until the end of the turn instead.";

    @Test
    void discardConditionalSelfBoostUsesInsteadWhenNameMatches() {
        CardData prishe = makeForward("Prishe", "Wind", 3, 5000);
        Consumer<GameContext> fn = ActionResolver.parse(PRISHE_INSTEAD, prishe);
        assertNotNull(fn, "self-boost 'instead' discard conditional should parse");
        GameContext ctx = mock(GameContext.class);
        when(ctx.lastDiscardedCostCardName()).thenReturn("Prishe");
        fn.accept(ctx);
        verify(ctx).boostSourceForward(eq(prishe), eq(4000), any());
        verify(ctx, never()).boostSourceForward(eq(prishe), eq(2000), any());
    }

    @Test
    void discardConditionalSelfBoostUsesBaseWhenNameDiffers() {
        CardData prishe = makeForward("Prishe", "Wind", 3, 5000);
        Consumer<GameContext> fn = ActionResolver.parse(PRISHE_INSTEAD, prishe);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        when(ctx.lastDiscardedCostCardName()).thenReturn("Ulmia");
        fn.accept(ctx);
        verify(ctx).boostSourceForward(eq(prishe), eq(2000), any());
        verify(ctx, never()).boostSourceForward(eq(prishe), eq(4000), any());
    }

    // Gogo (15-028H) "Mimic": replay a special ability used this turn, without paying its cost.
    private static final String GOGO_TEXT =
            "When a Forward or Monster you control uses an action ability, Gogo uses the same action "
            + "ability without paying the cost. This effect will trigger only once per turn.[[br]]   "
            + "[[s]]Mimic [[/]]《S》《Dull》: Use 1 special ability that a Character has used this turn "
            + "other than Ability Name Mimic without paying the cost.";

    private static final String MIMIC_EFFECT =
            "Use 1 special ability that a Character has used this turn other than Ability Name Mimic "
            + "without paying the cost.";

    @Test
    void gogoMimicSpecialAbilityParses() {
        ActionAbility mimic = CardData.parseActionAbilities(GOGO_TEXT).stream()
                .filter(a -> a.abilityName().equalsIgnoreCase("Mimic")).findFirst().orElse(null);
        assertNotNull(mimic, "Gogo's Mimic special ability should parse");
        assertTrue(mimic.isSpecial(),   "Mimic has an 《S》 cost");
        assertTrue(mimic.requiresDull(), "Mimic has a 《Dull》 cost");
        assertEquals(MIMIC_EFFECT, mimic.effectText());
    }

    @Test
    void mimicEffectIsRecognizedAndImplemented() {
        assertTrue(ActionResolver.isUseSpecialAbilityUsedThisTurnEffect(MIMIC_EFFECT));
        CardData gogo = makeForward("Gogo", "Fire", 5, 9000);
        assertNotNull(ActionResolver.parse(MIMIC_EFFECT, gogo),
                "Mimic must resolve to an implemented effect so the ability is activatable");
    }

    @Test
    void mimicEffectDelegatesToContextExcludingItself() {
        CardData gogo = makeForward("Gogo", "Fire", 5, 9000);
        Consumer<GameContext> fn = ActionResolver.parse(MIMIC_EFFECT, gogo);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        // The excluded ability name ("Mimic") is threaded through so Gogo can't copy another Mimic.
        verify(ctx).useSpecialAbilityUsedThisTurn(gogo, "Mimic");
    }

    // Kadaj's second modal action: "Choose up to 2 cards from either player's Break Zone.
    // Remove them from the game." must offer BOTH break zones (bothZones=true), not just the
    // controller's. Regression guard for the dropped bothZones flag on the RFG followup path.
    @Test
    void eitherPlayerBreakZoneRfgOffersBothZones() {
        GameContext ctx = mock(GameContext.class);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.selectCharactersFromBreakZone(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of());

        String text = "Choose up to 2 cards from either player's Break Zone. Remove them from the game.";
        Consumer<GameContext> fn = ActionResolver.parse(text, null);
        assertNotNull(fn);
        fn.accept(ctx);

        // maxCount=2, upTo=true, bothZones=true (4th arg)
        verify(ctx).selectCharactersFromBreakZone(
                eq(2), eq(true), anyBoolean(), eq(true),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
    }

    // =========================================================================================
    // Rubicante: "Name 1 Element. During this turn, if Rubicante is dealt damage by abilities of
    // the named Element, the damage becomes 0 instead." — ability-only element damage
    // nullification, distinct from Hein's combined immunity+nullification block.
    // =========================================================================================

    @Test
    void rubicanteNullifiesDamageFromNamedElementAbilitiesOnly() {
        CardData rubicante = mock(CardData.class);
        when(rubicante.name()).thenReturn("Rubicante");

        Consumer<GameContext> fn = ActionResolver.parse(
                "Name 1 Element. During this turn, if Rubicante is dealt damage by abilities of the named Element, "
                + "the damage becomes 0 instead.", rubicante);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        when(ctx.selectElement(anyString())).thenReturn("Fire");

        fn.accept(ctx);

        verify(ctx).nullifyNamedCardDamageByElementAbilityOnly("Rubicante", "Fire");
        verify(ctx, never()).shieldNamedCardCannotBeChosenByElement(any(), any());
    }

    @Test
    void rubicanteDoesNotFireWhenSourceNameDoesNotMatch() {
        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Not Rubicante");

        Consumer<GameContext> fn = ActionResolver.parse(
                "Name 1 Element. During this turn, if Rubicante is dealt damage by abilities of the named Element, "
                + "the damage becomes 0 instead.", other);

        assertNull(fn);
    }

    // =========================================================================================
    // Necron: ETB "choose 1 Forward of cost 5 or less opponent controls. Remove it from the
    // game for as long as Necron is on the field." + action "《Ice》《Dull》: Choose 1 card
    // removed by Necron's ability. Put it into the Break Zone." — temporary exile that returns
    // when Necron leaves the field, unless first sent to the Break Zone.
    // =========================================================================================

    @Test
    void necronEtbRemovesChosenForwardWhileNecronOnField() {
        CardData necron = mock(CardData.class);
        when(necron.name()).thenReturn("Necron");

        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward of cost 5 or less opponent controls. "
                + "Remove it from the game for as long as Necron is on the field.", necron);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(t));

        fn.accept(ctx);

        verify(ctx).removeTargetFromGameWhileNamedCardOnField(t, "Necron");
        verify(ctx, never()).removeTargetFromGame(any());
    }

    @Test
    void necronActionPutsRemovedCardIntoBreakZone() {
        CardData necron = mock(CardData.class);
        when(necron.name()).thenReturn("Necron");

        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 card removed by Necron's ability. Put it into the Break Zone.", necron);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).putCardRemovedBySourceIntoBreakZone(necron);
    }

    @Test
    void necronActionDoesNotFireForDifferentlyNamedSource() {
        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Not Necron");

        assertNull(ActionResolver.parse(
                "Choose 1 card removed by Necron's ability. Put it into the Break Zone.", other));
    }

    // =========================================================================================
    // Auron: "During this turn, the next damage dealt to you becomes 0 and deal Auron 8000
    // damage instead. You can only use this ability once per turn." — player shield whose
    // consumption redirects the damage to Auron.
    // =========================================================================================

    @Test
    void auronPlayerShieldWithRedirectParses() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "During this turn, the next damage dealt to you becomes 0 and deal Auron 8000 damage "
                + "instead. You can only use this ability once per turn.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).shieldPlayerNextDamageRedirect("Auron", 8000);
        verify(ctx, never()).shieldPlayerNextDamage();
    }

    // =========================================================================================
    // Sephiroth (hand ability): "《Ice》《2》, remove Sephiroth in your hand from the game:
    // Choose 1 dull Forward. Break it. Until the end of your turn, you can cast Sephiroth
    // removed by this ability's cost. You can only use this ability if Sephiroth is in your
    // hand." — RFG-from-hand cost + break + RFP-castable-this-turn followup.
    // =========================================================================================

    private static final String SEPHIROTH_EFFECT_TEXT =
            "Choose 1 dull Forward. Break it. Until the end of your turn, you can cast Sephiroth "
            + "removed by this ability's cost. You can only use this ability if Sephiroth is in your hand.";

    @Test
    void sephirothAbilityCostParsesAsHandRfgWithHandRestriction() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(
                "《Ice》《2》, remove Sephiroth in your hand from the game: " + SEPHIROTH_EFFECT_TEXT);
        assertEquals(1, abilities.size());
        ActionAbility a = abilities.get(0);
        assertTrue(a.whileCardInHand(), "hand-only restriction should be set");
        assertEquals(1, a.removeFromGameCosts().size());
        RemoveFromGameCost rfg = a.removeFromGameCosts().get(0);
        assertEquals("HAND", rfg.zone());
        assertEquals("Sephiroth", rfg.cardName());
        assertEquals(1, rfg.count());
    }

    @Test
    void sephirothBreaksDullForwardAndRegistersRfgCostCardCastable() {
        Consumer<GameContext> fn = ActionResolver.parse(SEPHIROTH_EFFECT_TEXT, null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(t));

        fn.accept(ctx);

        verify(ctx).breakTarget(t);
        verify(ctx).makeRfgCostCardCastableThisTurn("Sephiroth");
    }

    // =========================================================================================
    // Chaos: "Choose 1 Forward. Break it. You can only use this ability during your turn and if
    // Chaos is in the Break Zone." — combined your-turn-only + BZ-activation restriction.
    // =========================================================================================

    private static final String CHAOS_ABILITY_TEXT =
            "Choose 1 Forward. Break it. You can only use this ability during your turn "
            + "and if Chaos is in the Break Zone.";

    @Test
    void chaosCombinedRestrictionIsFullyRecognized() {
        // The combined restriction must be stripped as one sentence: the coverage description
        // should see a clean "Choose 1 Forward. Break it." with no "?" (unrecognized) layer.
        String desc = ActionResolver.fullDescription(CHAOS_ABILITY_TEXT, null);
        assertNotNull(desc);
        assertFalse(desc.contains("?"), "restriction fragment left a partially-recognized layer: " + desc);
    }

    @Test
    void chaosEffectBreaksChosenForwardAndKeepsRestrictions() {
        Consumer<GameContext> fn = ActionResolver.parse(CHAOS_ABILITY_TEXT, null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(t));

        fn.accept(ctx);

        verify(ctx).breakTarget(t);
    }

    // =========================================================================================
    // Gau: "Dull active Gau: Choose 1 Monster. Until the end of the turn, it also becomes a
    // Forward with 8000 power." — bare-name dull cost + chosen-Monster temporary-Forward effect.
    // =========================================================================================

    @Test
    void gauAbilityCostParsesAsBareNameDull() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(
                "Dull active Gau: Choose 1 Monster. Until the end of the turn, "
                + "it also becomes a Forward with 8000 power.");
        assertEquals(1, abilities.size());
        ActionAbility a = abilities.get(0);
        assertEquals(1, a.dullForwardCosts().size());
        DullForwardCost dc = a.dullForwardCosts().get(0);
        assertEquals("Gau", dc.cardName());
        assertEquals("active", dc.condition());
        assertEquals("Choose 1 Monster. Until the end of the turn, it also becomes a Forward with 8000 power.",
                a.effectText().trim());
    }

    @Test
    void gauChosenMonsterBecomesForwardUntilEot() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Monster. Until the end of the turn, it also becomes a Forward with 8000 power.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.MONSTER);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(t));

        fn.accept(ctx);

        verify(ctx).makeTargetTemporaryForward(t, 8000);
    }

    // =========================================================================================
    // Yuna Doublecast: "When you cast a Summon this turn, you may cast 1 Summon from your hand
    // with a cost inferior to that of the Summon you cast without paying its cost." — turn-long
    // rolling free-Summon field effect.
    // =========================================================================================

    @Test
    void doublecastActivatesRollingFreeSummonEffect() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "When you cast a Summon this turn, you may cast 1 Summon from your hand with a cost "
                + "inferior to that of the Summon you cast without paying its cost.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).activateDoublecastFreeSummons();
    }

    // =========================================================================================
    // Maat: "Maat gains "Maat cannot be broken by opposing Summons or abilities that don't deal
    // damage." until the end of the turn." — standalone quoted-gains form of the non-damage-only
    // cannot-be-broken shield.
    // =========================================================================================

    private static final String MAAT_SHIELD_TEXT =
            "Maat gains \"Maat cannot be broken by opposing Summons or abilities that don't deal "
            + "damage.\" until the end of the turn.";

    @Test
    void maatGainsNonDamageBreakShieldUntilEndOfTurn() {
        CardData maat = mock(CardData.class);
        when(maat.name()).thenReturn("Maat");

        Consumer<GameContext> fn = ActionResolver.parse(MAAT_SHIELD_TEXT, maat);
        assertNotNull(fn);

        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Zidane");

        GameContext ctx = mock(GameContext.class);
        when(ctx.isP1()).thenReturn(true);
        when(ctx.p1ForwardCount()).thenReturn(2);
        when(ctx.p1Forward(0)).thenReturn(other);
        when(ctx.p1Forward(1)).thenReturn(maat);

        fn.accept(ctx);

        verify(ctx).shieldCannotBeBrokenByNonDmg(new ForwardTarget(true, 1, ForwardTarget.CardZone.FORWARD));
        verify(ctx, never()).shieldSourceForward(any());
    }

    @Test
    void maatShieldDoesNotFireForDifferentlyNamedSource() {
        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Not Maat");

        assertNull(ActionResolver.parse(MAAT_SHIELD_TEXT, other));
    }

    // =========================================================================================
    // "During this turn, if a Job Dancer or Card Name Dancer you control is dealt damage by a
    // Summon or an ability, the damage becomes 0 instead." — persistent turn-scoped, filtered
    // own-side Summon/ability damage nullification (also covers Dancers entering later).
    // =========================================================================================

    private static final String DANCER_SHIELD_TEXT =
            "During this turn, if a Job Dancer or Card Name Dancer you control is dealt damage "
            + "by a Summon or an ability, the damage becomes 0 instead.";

    @Test
    void registersPersistentFilterShieldingOnlyDancers() {
        Consumer<GameContext> fn = ActionResolver.parse(DANCER_SHIELD_TEXT, null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<CardData>> captor =
                ArgumentCaptor.forClass((Class<Predicate<CardData>>) (Class<?>) Predicate.class);
        verify(ctx).shieldOwnForwardsAbilityDamageFilter(captor.capture());
        Predicate<CardData> filter = captor.getValue();

        CardData jobDancer = mock(CardData.class);
        when(jobDancer.hasJob("Dancer")).thenReturn(true);
        CardData namedDancer = mock(CardData.class);
        when(namedDancer.hasJob("Dancer")).thenReturn(false);
        when(namedDancer.name()).thenReturn("Dancer");
        CardData other = mock(CardData.class);
        when(other.hasJob("Dancer")).thenReturn(false);
        when(other.name()).thenReturn("Warrior of Light");

        assertTrue(filter.test(jobDancer), "Job Dancer should be shielded");
        assertTrue(filter.test(namedDancer), "Card Name Dancer should be shielded");
        assertFalse(filter.test(other), "Non-Dancer should not be shielded");
    }

    // =========================================================================================
    // "Choose 1 Summon in your Break Zone. Remove it from the game. During this game, you can
    // cast it at any time you could normally cast it." — the plain-phrasing variant (no "as
    // though you owned it") of the Shantotto-style BZ-Summon-castable-forever pattern.
    // =========================================================================================

    @Test
    void chooseSummonInOwnBzRemoveFromGameCastableDuringGameParses() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Summon in your Break Zone. Remove it from the game. "
                + "During this game, you can cast it at any time you could normally cast it.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).chooseSummonsFromBzMakeCastable(1, false, false, false, false);
    }

    // =========================================================================================
    // Cloud / Strago / Vivi / Zell / Bahamut / Eden / Barret: the "Divide N damage [equally]
    // among ..." action-ability pattern, exercised against real card texts pulled from
    // shufflingway.db. These assert on the actual damage amounts/targets dealt via a mocked
    // GameContext, not just that the text parses.
    // =========================================================================================

    private static GameContext divideMockContext(boolean isP1) {
        GameContext ctx = mock(GameContext.class);
        when(ctx.isP1()).thenReturn(isP1);
        // Mockito's default answer returns an empty List (not null) for collection-typed methods;
        // selectTargets() treats a non-null return from consumePreloadedTargets() as "already
        // chosen" and skips selectCharacters() entirely, so this must be explicitly null here.
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        return ctx;
    }

    private static void divideStubSelectCharacters(GameContext ctx, List<ForwardTarget> result) {
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(result);
    }

    private static Consumer<GameContext> divideParse(String effectText) {
        Consumer<GameContext> fn = ActionResolver.parse(effectText, null);
        assertNotNull(fn, "Expected \"" + effectText + "\" to parse");
        return fn;
    }

    // --- Cloud: "Choose up to 2 Forwards. Divide 10000 damage among them equally." ---

    @Test
    void cloudSplitsEquallyAcrossTwoTargets() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget t1 = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0, t1));

        divideParse("Choose up to 2 Forwards. Divide 10000 damage among them equally.").accept(ctx);

        verify(ctx).damageTarget(t0, 5000);
        verify(ctx).damageTarget(t1, 5000);
        verify(ctx, never()).divideDamageAmount(anyInt(), any(), any());
    }

    @Test
    void cloudDealsFullAmountToSingleTarget() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));

        divideParse("Choose up to 2 Forwards. Divide 10000 damage among them equally.").accept(ctx);

        verify(ctx).damageTarget(t0, 10000);
    }

    // --- Strago: "Divide 12000 damage equally among all the Forwards opponent controls
    //             (round up to the nearest 1000)." — no Choose clause, blanket target. ---

    @Test
    void stragoRoundsUpPerTargetWhenNotEvenlyDivisible() {
        GameContext ctx = divideMockContext(true);
        when(ctx.p2ForwardCount()).thenReturn(5);
        when(ctx.p1ForwardCount()).thenReturn(0);

        divideParse("Divide 12000 damage equally among all the Forwards opponent controls (round up to the nearest 1000).")
                .accept(ctx);

        // 12000 / 5 = 2400 -> rounds up to 3000 per target; total dealt (15000) exceeds the stated 12000.
        for (int i = 0; i < 5; i++) {
            verify(ctx).damageTarget(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD), 3000);
        }
    }

    // --- Vivi: "...Divide 7000 damage among them as you like. If you control a Category IX
    //           Forward other than Vivi, divide 10000 damage among them instead..." ---

    @Test
    void viviDealsBoostedDamageWhenOtherCategoryForwardPresent() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));
        when(ctx.controlConditionMetExcluding(any(), eq("Vivi"))).thenReturn(true);

        divideParse("Choose any number of Forwards opponent controls. Divide 7000 damage among them as you like. "
                + "If you control a Category IX Forward other than Vivi, divide 10000 damage among them instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).controlConditionMetExcluding(any(), eq("Vivi"));
        verify(ctx).damageTarget(t0, 10000);
    }

    @Test
    void viviDealsBaseDamageWhenNoOtherCategoryForwardPresent() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));
        when(ctx.controlConditionMetExcluding(any(), eq("Vivi"))).thenReturn(false);

        divideParse("Choose any number of Forwards opponent controls. Divide 7000 damage among them as you like. "
                + "If you control a Category IX Forward other than Vivi, divide 10000 damage among them instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).damageTarget(t0, 7000);
    }

    // --- Zell: "...Divide 5000 damage among them as you like. If you control 4 or more
    //           Category VIII Characters, divide 9000 damage among them as you like instead..." ---

    @Test
    void zellDealsBoostedDamageWhenCountThresholdMet() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));
        when(ctx.controlConditionMet(any())).thenReturn(true);

        divideParse("Choose any number of Forwards. Divide 5000 damage among them as you like. "
                + "If you control 4 or more Category VIII Characters, divide 9000 damage among them as you like instead. "
                + "(Units must be 1000.)").accept(ctx);

        // No "other than" clause in Zell's condition — must use the non-excluding check.
        verify(ctx).controlConditionMet(any());
        verify(ctx, never()).controlConditionMetExcluding(any(), any());
        verify(ctx).damageTarget(t0, 9000);
    }

    @Test
    void zellDealsBaseDamageWhenCountThresholdNotMet() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));
        when(ctx.controlConditionMet(any())).thenReturn(false);

        divideParse("Choose any number of Forwards. Divide 5000 damage among them as you like. "
                + "If you control 4 or more Category VIII Characters, divide 9000 damage among them as you like instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).damageTarget(t0, 5000);
    }

    // --- Bahamut: "...Divide 10000 damage among them as you like. If you have received 5 points
    //              of damage or more, divide 15000 damage among those instead..." ---

    @Test
    void bahamutDealsBoostedDamageWhenSelfDamageThresholdMet() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));
        when(ctx.selfDamageCount()).thenReturn(5);

        divideParse("Choose up to 2 Forwards. Divide 10000 damage among them as you like. "
                + "If you have received 5 points of damage or more, divide 15000 damage among those instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).damageTarget(t0, 15000);
    }

    @Test
    void bahamutDealsBaseDamageWhenSelfDamageThresholdNotMet() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));
        when(ctx.selfDamageCount()).thenReturn(2);

        divideParse("Choose up to 2 Forwards. Divide 10000 damage among them as you like. "
                + "If you have received 5 points of damage or more, divide 15000 damage among those instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).damageTarget(t0, 10000);
    }

    // --- Eden: "...Divide 30000 damage among them as you like. (Units must be 1000.)
    //           This damage cannot be reduced." ---

    @Test
    void edenUsesUnreducedDamageWhenCannotBeReduced() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));

        divideParse("Choose up to 2 Forwards. Divide 30000 damage among them as you like. (Units must be 1000.) "
                + "This damage cannot be reduced.").accept(ctx);

        verify(ctx).damageTargetUnreduced(t0, 30000);
        verify(ctx, never()).damageTarget(any(), anyInt());
    }

    // --- Barret: "Choose up to 2 Forwards. Divide 10000 damage among them as you like.
    //             (Units must be 1000.)" — baseline multi-target "as you like" dialog path. ---

    @Test
    void barretInvokesAllocationDialogForMultipleTargets() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget t1 = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0, t1));
        CardData c0 = mock(CardData.class);
        CardData c1 = mock(CardData.class);
        when(ctx.p2Forward(0)).thenReturn(c0);
        when(ctx.p2Forward(1)).thenReturn(c1);
        when(ctx.divideDamageAmount(eq(10000), any(), eq(List.of(c0, c1))))
                .thenReturn(List.of(4000, 6000));

        divideParse("Choose up to 2 Forwards. Divide 10000 damage among them as you like. (Units must be 1000.)")
                .accept(ctx);

        verify(ctx).damageTarget(t0, 4000);
        verify(ctx).damageTarget(t1, 6000);
    }

    @Test
    void barretSkipsZeroAllocationTargets() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget t1 = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0, t1));
        when(ctx.p2Forward(anyInt())).thenReturn(mock(CardData.class));
        when(ctx.divideDamageAmount(eq(10000), any(), any()))
                .thenReturn(List.of(10000, 0));

        divideParse("Choose up to 2 Forwards. Divide 10000 damage among them as you like. (Units must be 1000.)")
                .accept(ctx);

        verify(ctx).damageTarget(t0, 10000);
        verify(ctx, never()).damageTarget(eq(t1), anyInt());
    }

    // =========================================================================================
    // Leon: "When Leon enters the field, your opponent gains control of Leon." / "When a
    // Category II Character enters your opponent's field, your opponent gains control of Leon."
    // — permanent control transfer of the source card itself to its own controller's opponent.
    // =========================================================================================

    private static final String LEON_TEXT =
            "When Leon enters the field, your opponent gains control of Leon.[[br]]   "
            + "When a Category II Character enters your opponent's field, your opponent gains control of Leon.";

    @Test
    void leonAutoAbilitiesParseWithExpectedTriggersAndSubjects() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(LEON_TEXT);
        assertEquals(2, autos.size());

        assertEquals("enters the field", autos.get(0).trigger());
        assertEquals("Leon", autos.get(0).triggerCard());
        assertTrue(autos.get(0).effectText().equalsIgnoreCase("your opponent gains control of Leon."));

        assertEquals("enters opponent's field", autos.get(1).trigger());
        assertEquals("a Category II Character", autos.get(1).triggerCard());
        assertTrue(autos.get(1).effectText().equalsIgnoreCase("your opponent gains control of Leon."));
    }

    @Test
    void opponentGainsControlOfSourceParsesAndDelegatesToGameContext() {
        CardData leon = mock(CardData.class);
        when(leon.name()).thenReturn("Leon");

        Consumer<GameContext> fn = ActionResolver.parse("Your opponent gains control of Leon.", leon);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).giveSourceControlToOpponent(leon);
    }

    @Test
    void opponentGainsControlDoesNotFireWhenSourceNameDoesNotMatch() {
        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Not Leon");

        Consumer<GameContext> fn = ActionResolver.parse("Your opponent gains control of Leon.", other);

        assertNull(fn);
    }

    @Test
    void giveForwardControlToOpponentMovesFromP1ToP2PreservingDamageAndState() {
        MainWindow mw = new MainWindow();
        CardData leon = makeForward("Leon", "Fire", 3, 5000);
        mw.placeCardInForwardZone(leon); // P1 idx 0
        mw.p1ForwardStates.set(0, CardState.DULL);
        mw.p1ForwardDamage.set(0, 2000);

        mw.giveForwardControlToOpponent(leon);

        assertFalse(mw.p1ForwardCards.contains(leon), "Leon should have left P1's field");
        assertEquals(1, mw.p2ForwardCards.size());
        assertSame(leon, mw.p2ForwardCards.get(0));
        assertEquals(CardState.DULL, mw.p2ForwardStates.get(0), "state should carry over");
        assertEquals(2000, (int) mw.p2ForwardDamage.get(0), "damage should carry over");
    }

    @Test
    void giveForwardControlToOpponentMovesFromP2ToP1() {
        MainWindow mw = new MainWindow();
        CardData leon = makeForward("Leon", "Fire", 3, 5000);
        mw.placeP2CardInForwardZone(leon); // P2 idx 0

        mw.giveForwardControlToOpponent(leon);

        assertFalse(mw.p2ForwardCards.contains(leon), "Leon should have left P2's field");
        assertEquals(1, mw.p1ForwardCards.size());
        assertSame(leon, mw.p1ForwardCards.get(0));
    }

    @Test
    void giveForwardControlToOpponentAppliesUniquenessRuleAgainstExistingCopy() {
        // The opponent already controls their own Leon; once control crosses over, the two
        // same-named copies conflict under the uniqueness rule and both go to the Break Zone.
        MainWindow mw = new MainWindow();
        CardData incomingLeon  = makeForward("Leon", "Fire", 3, 5000);
        CardData existingLeon  = makeForward("Leon", "Fire", 3, 5000);
        mw.gameState.getIdentity().put(incomingLeon, true);   // owned by P1
        mw.gameState.getIdentity().put(existingLeon, false);  // owned by P2
        mw.placeCardInForwardZone(incomingLeon);    // P1 idx 0
        mw.placeP2CardInForwardZone(existingLeon);  // P2 idx 0

        mw.giveForwardControlToOpponent(incomingLeon);

        assertFalse(mw.p1ForwardCards.contains(incomingLeon));
        assertFalse(mw.p2ForwardCards.contains(existingLeon));
        assertTrue(mw.gameState.getP1BreakZone().contains(incomingLeon));
        assertTrue(mw.gameState.getP2BreakZone().contains(existingLeon));
    }

    // =========================================================================================
    // Sahagin Chief: "When a Water Character other than Sahagin Chief enters your field, place 1
    // Monster Counter on Sahagin Chief." — "other than Sahagin Chief" excludes only the source
    // instance (a card naming itself means that specific card), so another copy of Sahagin Chief
    // entering the field must still place a counter on the existing one.
    // =========================================================================================

    private static final String SAHAGIN_CHIEF_TEXT =
            "When a Water Character other than Sahagin Chief enters your field, place 1 Monster Counter on Sahagin Chief.[[br]]   "
            + "Put Sahagin Chief into the Break Zone: Choose 1 Forward you control. Return it to its owner's hand.[[br]]   "
            + "Put Sahagin Chief into the Break Zone: Choose up to 2 Forwards. Return them to their owners' hands. "
            + "You can only use this ability if 3 or more Monster Counters are placed on Sahagin Chief.";

    private static CardData makeSahaginChief() {
        // multicard = true: Sahagin Chief is exempt from the same-name uniqueness rule, so two
        // copies can share the field.
        return new CardData(null, "Sahagin Chief", "Water", 2, 5000, "Monster", false, 0, false, true,
                Set.of(), 0, List.of(), null, List.of(),
                CardData.parseActionAbilities(SAHAGIN_CHIEF_TEXT), CardData.parseAutoAbilities(SAHAGIN_CHIEF_TEXT),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, SAHAGIN_CHIEF_TEXT);
    }

    @Test
    void sahaginChiefCountersFromAnotherCopyButNotItself() {
        MainWindow mw = new MainWindow();
        CardData chiefA = makeSahaginChief();
        CardData chiefB = makeSahaginChief();
        mw.gameState.getIdentity().put(chiefA, true);
        mw.gameState.getIdentity().put(chiefB, true);

        // Placing chiefA fires its own enters-field trigger; its watcher sees chiefA entering and
        // must self-exclude ("other than Sahagin Chief" = this instance), so no counter yet.
        mw.placeCardInMonsterZone(chiefA);
        assertEquals(0, mw.gameState.getCounters(chiefA, "Monster"),
                "Sahagin Chief entering must not counter itself");

        // chiefB enters: chiefA's watcher fires (a different Sahagin Chief instance, still a Water
        // Character "other than" the source), while chiefB self-excludes. Both stay on the field
        // because the multicard is exempt from the same-name uniqueness rule.
        mw.placeCardInMonsterZone(chiefB);
        assertEquals(2, mw.p1MonsterCards.size(), "multicard Sahagin Chiefs both remain on the field");
        assertEquals(1, mw.gameState.getCounters(chiefA, "Monster"),
                "A different Sahagin Chief entering should counter the existing one");
        assertEquals(0, mw.gameState.getCounters(chiefB, "Monster"),
                "The entering Sahagin Chief must not counter itself");
    }

    // Sahagin Chief's "Choose up to 2 Forwards. Return them to their owners' hands." — regression
    // for two return-to-hand bugs:
    //  1) Returning two Forwards controlled by the SAME player must return both. Each return
    //     compacts that player's Forward list, so processing them in selection (ascending) order
    //     left the second target's index stale and only one card came back.
    //  2) A Monster (or Backup) that has become a Forward this turn is a legal target and must be
    //     returned from its actual zone, not silently skipped because its zone isn't FORWARD.
    private static Consumer<GameContext> sahaginReturnEffect() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose up to 2 Forwards. Return them to their owners' hands.", null);
        assertNotNull(fn, "Sahagin Chief return-to-hand effect should parse");
        return fn;
    }

    @Test
    void sahaginChiefReturnsBothForwardsControlledBySamePlayer() {
        MainWindow mw = new MainWindow();
        CardData a = makeForward("Opp A", "Water", 2, 5000);
        CardData b = makeForward("Opp B", "Water", 3, 6000);
        mw.gameState.getIdentity().put(a, false);   // owned by P2
        mw.gameState.getIdentity().put(b, false);
        mw.placeP2CardInForwardZone(a);             // P2 idx 0
        mw.placeP2CardInForwardZone(b);             // P2 idx 1

        GameContext ctx = mw.buildGameContext(true); // P1 activates the ability
        ctx.preloadTargets(List.of(
                new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD),
                new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD)));

        sahaginReturnEffect().accept(ctx);

        assertTrue(mw.p2ForwardCards.isEmpty(), "both opponent Forwards should leave the field");
        assertTrue(mw.gameState.getP2Hand().contains(a), "Opp A should be back in P2's hand");
        assertTrue(mw.gameState.getP2Hand().contains(b), "Opp B should be back in P2's hand");
    }

    @Test
    void sahaginChiefReturnsMonsterActingAsForward() {
        MainWindow mw = new MainWindow();
        CardData monster = new CardData(null, "Water Beast", "Water", 2, 5000, "Monster",
                false, 0, false, false, Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
        mw.gameState.getIdentity().put(monster, true);  // owned by P1
        mw.placeCardInMonsterZone(monster);             // P1 monster idx 0
        // The monster becomes a Forward until end of turn — now a legal "Forward" target.
        mw.p1MonsterTempForwardPower.put(monster, 5000);
        assertTrue(mw.isP1MonsterTemporarilyForward(0), "monster should count as a Forward");

        GameContext ctx = mw.buildGameContext(true);
        ctx.preloadTargets(List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.MONSTER)));

        sahaginReturnEffect().accept(ctx);

        assertTrue(mw.p1MonsterCards.isEmpty(), "the monster-as-Forward should leave the field");
        assertTrue(mw.gameState.getP1Hand().contains(monster), "the monster should be back in P1's hand");
    }

    // =========================================================================================
    // Moogle (XIV): "At the end of each of your turns, reveal the top 3 cards of your deck. Play
    // up to 1 Card Name Moogle (XIV) or Job Moogle of cost 3 or less among them onto the field
    // and return the other cards to the bottom of your deck in any order." — the combined
    // Card-Name-or-Job filter with a cost ceiling on a "reveal top N, play up to M" effect.
    // =========================================================================================

    private static final String MOOGLE_XIV_TEXT =
            "At the end of each of your turns, reveal the top 3 cards of your deck. Play up to 1 "
            + "Card Name Moogle (XIV) or Job Moogle of cost 3 or less among them onto the field "
            + "and return the other cards to the bottom of your deck in any order.";

    @Test
    void moogleXivAutoAbilityParsesAsEndOfTurnGlobalTrigger() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(MOOGLE_XIV_TEXT);
        assertEquals(1, autos.size());
        AutoAbility fa = autos.get(0);
        assertEquals("end of your turn", fa.trigger());
        assertEquals("", fa.triggerCard());
        assertTrue(fa.effectText().toLowerCase().startsWith("reveal the top 3 cards of your deck"));
    }

    @Test
    void moogleXivRevealPlayNamedOrJobMaxCostParsesAndResolves() {
        String effectText = CardData.parseAutoAbilities(MOOGLE_XIV_TEXT).get(0).effectText();

        Consumer<GameContext> fn = ActionResolver.parse(effectText, null);
        assertNotNull(fn, "Expected \"" + effectText + "\" to parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).revealTopNPlayUpToNamedOrJobWithMaxCostOntoFieldRestBottom(3, 1, "Moogle (XIV)", "Moogle", 3);
    }

    // =========================================================================================
    // Wakka: "EX BURST When Wakka enters the field, reveal the top 3 cards of your deck. Add 1
    // Water or Category X card among them to your hand and return the other cards to the bottom
    // of your deck in any order." — the element-OR-category disjunction on the hand-add filter,
    // fired via the "enters the field" auto ability (despite also carrying the EX Burst marker).
    // =========================================================================================

    private static final String WAKKA_TEXT =
            "[[ex]]EX BURST[[/]] When Wakka enters the field, reveal the top 3 cards of your deck. "
            + "Add 1 Water or Category X card among them to your hand and return the other cards to "
            + "the bottom of your deck in any order.";

    @Test
    void wakkaAutoAbilityParsesAsEntersFieldTrigger() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(WAKKA_TEXT);
        assertEquals(1, autos.size());
        AutoAbility fa = autos.get(0);
        assertTrue(fa.trigger().contains("enter"), "Expected an 'enters the field' trigger, got: " + fa.trigger());
        assertEquals("Wakka", fa.triggerCard());
        assertTrue(fa.effectText().toLowerCase().startsWith("reveal the top 3 cards of your deck"),
                "Unexpected effect text: " + fa.effectText());
    }

    @Test
    void wakkaRevealAddWaterOrCategoryXParsesAndResolves() {
        String effectText = CardData.parseAutoAbilities(WAKKA_TEXT).get(0).effectText();

        Consumer<GameContext> fn = ActionResolver.parse(effectText, null);
        assertNotNull(fn, "Expected \"" + effectText + "\" to parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        // element "Water" is a disjunct (orElementFilter, last arg), NOT the AND-gate elementFilter.
        verify(ctx).revealTopAddUpToMatchingRestBottom(3, 1, null, "X", null, null, -1, null, "Water");
    }

    private static CardData makeWakka() {
        return new CardData(null, "Wakka", "Water", 4, 7000, "Forward", false, 0, true, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, WAKKA_TEXT);
    }

    @Test
    void wakkaExBurstStripsWhenClauseAndResolvesReveal() {
        CardData wakka = makeWakka();
        // When revealed as EX Burst damage the "When Wakka enters the field," trigger clause is
        // dropped and the bare reveal action runs.
        String ex = wakka.exBurstEffect();
        assertTrue(ex.toLowerCase().startsWith("reveal the top 3 cards of your deck"),
                "EX Burst effect should drop the 'When … enters the field,' clause: " + ex);

        Consumer<GameContext> fn = ActionResolver.parse(ex, wakka);
        assertNotNull(fn, "Expected EX Burst text to parse: " + ex);
        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).revealTopAddUpToMatchingRestBottom(3, 1, null, "X", null, null, -1, null, "Water");
    }

    // =========================================================================================
    // Jet Bahamut: "When Jet Bahamut enters the field, choose 1 Forward. Deal it 5000 damage. If
    // it is put from the field into the Break Zone this turn, remove it from the game instead."
    // — the secondary clause must mark the chosen target so that ANY later break-to-BZ this turn
    // (battle, another ability, etc.) redirects it to RFG instead.
    // =========================================================================================

    private static final String JET_BAHAMUT_EFFECT_TEXT =
            "choose 1 Forward. Deal it 5000 damage. If it is put from the field into the Break "
            + "Zone this turn, remove it from the game instead.";

    @Test
    void jetBahamutDealsDamageAndMarksTargetForRfgInstead() {
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));
        when(ctx.lastChosenTargets()).thenReturn(List.of(t));

        Consumer<GameContext> fn = ActionResolver.parse(JET_BAHAMUT_EFFECT_TEXT, null);
        assertNotNull(fn, "Expected Jet Bahamut's effect text to parse");
        fn.accept(ctx);

        verify(ctx).damageTarget(t, 5000);
        verify(ctx).markTargetRfgInsteadOfBzThisTurn(t);
    }

    @Test
    void rfgInsteadOfBzMarkerRedirectsFieldBreakToRemovedFromGame() {
        MainWindow mw = new MainWindow();
        CardData victim = makeForward("Victim", "Fire", 3, 5000);
        mw.gameState.getIdentity().put(victim, true); // owned by P1
        mw.placeCardInForwardZone(victim); // P1 idx 0

        mw.buildGameContext(true).markTargetRfgInsteadOfBzThisTurn(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));
        mw.breakP1Forward(0);

        assertFalse(mw.gameState.getP1BreakZone().contains(victim), "should not land in the Break Zone");
        assertTrue(mw.gameState.getP1PermanentRfp().contains(victim), "should be removed from the game instead");
    }

    @Test
    void rfgInsteadOfBzMarkerDoesNotAffectUnmarkedForwards() {
        // Regression guard: an ordinary break (no marker set) must still go to the Break Zone.
        MainWindow mw = new MainWindow();
        CardData bystander = makeForward("Bystander", "Fire", 3, 5000);
        mw.gameState.getIdentity().put(bystander, true); // owned by P1
        mw.placeCardInForwardZone(bystander); // P1 idx 0

        mw.breakP1Forward(0);

        assertTrue(mw.gameState.getP1BreakZone().contains(bystander));
        assertFalse(mw.gameState.getP1PermanentRfp().contains(bystander));
    }

    // =========================================================================================
    // Vayne: "When Vayne enters the field or at the beginning of your Main Phase 1 during each
    // of your turns, choose 1 card removed from the game with a Warp Counter on it. You may
    // remove 1 Warp Counter from it.[[br]]   When a Warp Counter is removed from any player's
    // card, draw 1 card. This effect will trigger only once per turn." — the compound
    // ETF-or-phase trigger, plus the optional-removal Warp Counter effect.
    // =========================================================================================

    private static final String VAYNE_TEXT =
            "When Vayne enters the field or at the beginning of your Main Phase 1 during each of "
            + "your turns, choose 1 card removed from the game with a Warp Counter on it. You may "
            + "remove 1 Warp Counter from it.[[br]]   When a Warp Counter is removed from any "
            + "player's card, draw 1 card. This effect will trigger only once per turn.";

    private static final String VAYNE_CHOOSE_EFFECT_TEXT =
            "choose 1 card removed from the game with a Warp Counter on it. You may remove 1 Warp Counter from it.";

    @Test
    void vayneAutoAbilitiesParseAsThreeCleanEntries() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(VAYNE_TEXT);
        assertEquals(3, autos.size(), "expected ETF + phase + warp-counter-removed entries");

        AutoAbility etf = autos.stream().filter(a -> a.trigger().equals("enters the field")).findFirst().orElse(null);
        assertNotNull(etf);
        assertEquals("Vayne", etf.triggerCard());
        assertEquals(VAYNE_CHOOSE_EFFECT_TEXT, etf.effectText());

        AutoAbility phase = autos.stream().filter(a -> a.trigger().equals("beginning of main phase 1")).findFirst().orElse(null);
        assertNotNull(phase);
        assertEquals("", phase.triggerCard());
        assertEquals(VAYNE_CHOOSE_EFFECT_TEXT, phase.effectText());

        AutoAbility warp = autos.stream().filter(a -> a.trigger().equals("warp counter removed")).findFirst().orElse(null);
        assertNotNull(warp);
        assertEquals("draw 1 card", warp.effectText());
        assertTrue(warp.oncePerTurn(), "the once-per-turn restriction must not leak into the ETF/phase abilities");
        assertFalse(etf.oncePerTurn());
        assertFalse(phase.oncePerTurn());
    }

    @Test
    void vayneChooseEffectTextParsesAndDelegates() {
        Consumer<GameContext> fn = ActionResolver.parse(VAYNE_CHOOSE_EFFECT_TEXT, null);
        assertNotNull(fn, "Expected Vayne's choose/may-remove effect text to parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).chooseAndMayRemoveWarpCounter();
    }

    @Test
    void chooseAndMayRemoveWarpCounterIsNoOpWhenWarpZoneEmpty() {
        MainWindow mw = new MainWindow();
        mw.buildGameContext(true).chooseAndMayRemoveWarpCounter();
        // No exception, no crash — nothing to assert on an empty zone beyond it staying empty.
        assertTrue(mw.gameState.getP1WarpZone().isEmpty());
    }

    @Test
    void chooseAndMayRemoveWarpCounterAiDeclinesAndLeavesCounterUntouched() {
        // promptYouMay() always declines for the non-P1 (AI/opponent) context, so P2's own
        // "you may" decision here must leave the Warp entry completely untouched.
        MainWindow mw = new MainWindow();
        CardData warped = makeForward("Warped One", "Fire", 3, 5000);
        mw.gameState.addToP2WarpZone(warped, 2);

        mw.buildGameContext(false).chooseAndMayRemoveWarpCounter();

        List<GameState.WarpEntry> zone = mw.gameState.getP2WarpZone();
        assertEquals(1, zone.size());
        assertEquals(2, zone.get(0).counters, "AI must decline, leaving the counter count unchanged");
    }

    // =========================================================================================
    // Cid (II): "When Cid (II) enters the field, you may search for 1 card with Warp and add it
    // to your hand." — the generic "search deck" pattern had no way to restrict results to cards
    // with the Warp trait, so this always failed to parse.
    // =========================================================================================

    private static final String CID_II_TEXT =
            "When Cid (II) enters the field, you may search for 1 card with Warp and add it to "
            + "your hand.[[br]]   《Dull》, put Cid (II) into the Break Zone: Choose 1 card removed "
            + "from the game. Remove 1 Warp Counter from it. You can only use this ability during your turn.";

    private static CardData makeForwardWithWarp(String name, String element, int cost, int power, int warpValue) {
        return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
                Set.of(), warpValue, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    @Test
    void cidIiEnterTheFieldAutoAbilityParsesAsOptionalSearchWithWarp() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(CID_II_TEXT);
        assertEquals(1, autos.size(), "the 《Dull》 action ability must not be picked up as an auto-ability");
        AutoAbility fa = autos.get(0);
        assertEquals("enters the field", fa.trigger());
        assertEquals("Cid (II)", fa.triggerCard());
        assertTrue(fa.youMay());
        assertEquals("search for 1 card with Warp and add it to your hand.", fa.effectText());
    }

    @Test
    void searchForCardWithWarpParsesAndRequiresWarpTrait() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "search for 1 card with Warp and add it to your hand.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyInt(), any(), any(), any(), any(), any(), any(), any(),
                eq("hand"), eq(1), eq(false), eq(true));
    }

    @Test
    void searchDeckForCardWithWarpFindsOnlyTheWarpCard() {
        // AI (P2) path avoids the modal card-picker dialog used for a single P1 match.
        MainWindow mw = new MainWindow();
        CardData plain  = makeForwardWithWarp("Plain One", "Fire", 3, 5000, 0);
        CardData warped = makeForwardWithWarp("Warp One", "Fire", 3, 5000, 2);
        mw.gameState.getP2MainDeck().add(plain);
        mw.gameState.getP2MainDeck().add(warped);

        mw.searchDeckForCard(false, true, true, true, true,
                -1, null, null, null, null, null, null, null,
                "hand", 1, false, true);

        assertTrue(mw.gameState.getP2Hand().contains(warped), "the Warp card should have been found");
        assertFalse(mw.gameState.getP2Hand().contains(plain), "the non-Warp card must not match");
    }

    // =========================================================================================
    // Job-scoped "put from the field into the Break Zone this turn" restriction: "Choose 1
    // Forward opponent controls. Break it. You can only use this ability if a Job AVALANCHE
    // Operative you controlled has been put from the field into the Break Zone this turn."
    // =========================================================================================

    private static final String JOB_BROKEN_ABILITY_TEXT =
            "《Dull》: Choose 1 Forward opponent controls. Break it. You can only use this "
            + "ability if a Job AVALANCHE Operative you controlled has been put from the field into "
            + "the Break Zone this turn.";

    @Test
    void jobBrokenThisTurnRestrictionParsesJobNameAndStripsFromEffectText() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(JOB_BROKEN_ABILITY_TEXT);
        assertEquals(1, abilities.size());
        ActionAbility ability = abilities.get(0);

        assertEquals("avalanche operative", ability.requiresJobPutToBZThisTurn());
        assertFalse(ability.requiresForwardPutToBZThisTurn(),
                "the generic (non-job) restriction must not also fire for the job-qualified sentence");
    }

    @Test
    void jobBrokenThisTurnRestrictionGatesActivation() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(JOB_BROKEN_ABILITY_TEXT);
        ActionAbility ability = abilities.get(0);

        MainWindow mw = new MainWindow();
        CardData source = makeForward("Source", "Fire", 1, 5000);
        mw.placeCardInForwardZone(source);

        assertFalse(mw.canActivateAbility(ability, false, CardState.ACTIVE, 0, source, true),
                "must not be usable when no Job AVALANCHE Operative has broken this turn");

        mw.p1Turn.brokenJobsThisTurn.add("avalanche operative");
        assertTrue(mw.canActivateAbility(ability, false, CardState.ACTIVE, 0, source, true),
                "must be usable once a Job AVALANCHE Operative was put from the field into the BZ this turn");
    }

    // =========================================================================================
    // Queen's Speedrush special ability: "Queen gains Haste and 'Queen cannot be blocked.' until
    // the end of the turn." — trailing-order sibling of the EOT-prefixed
    // "Until the end of the turn, [name] gains [traits] and '[name] cannot be blocked.'" pattern.
    // =========================================================================================

    @Test
    void queenGainsTraitsAndCannotBeBlockedTrailingParsesAndResolves() {
        CardData queen = makeForward("Queen", "Lightning", 2, 5000);
        String effectText = "Queen gains Haste and \"Queen cannot be blocked\" until the end of the turn.";

        Consumer<GameContext> fn = ActionResolver.parse(effectText, queen);
        assertNotNull(fn, "Expected \"" + effectText + "\" to parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).boostSourceForward(queen, 0, java.util.EnumSet.of(CardData.Trait.HASTE));
        verify(ctx).setSourceForwardCannotBeBlocked(queen);
    }

    // =========================================================================================
    // Quina's action ability: "Until the end of the turn, Quina gains +2000 power and Quina
    // cannot be chosen by your opponent's abilities. You can only use this ability once per
    // turn." — EOT power boost + opponent-targeting protection in a single sentence.
    // =========================================================================================

    private static final String QUINA_ABILITY_TEXT =
            "Remove the top 5 cards of your deck from the game: Until the end of the turn, "
            + "Quina gains +2000 power and Quina cannot be chosen by your opponent's abilities. "
            + "You can only use this ability once per turn.";

    @Test
    void quinaPowerBoostAndCannotBeChosenParsesCostAndOncePerTurnRestriction() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(QUINA_ABILITY_TEXT);
        assertEquals(1, abilities.size());
        ActionAbility ability = abilities.get(0);

        assertTrue(ability.oncePerTurn());
        assertEquals(1, ability.removeFromGameCosts().size());
        RemoveFromGameCost cost = ability.removeFromGameCosts().get(0);
        assertEquals("DECK", cost.zone());
        assertEquals(5, cost.count());
    }

    @Test
    void quinaPowerBoostAndCannotBeChosenResolves() {
        CardData quina = makeForward("Quina", "Water", 2, 5000);
        List<ActionAbility> abilities = CardData.parseActionAbilities(QUINA_ABILITY_TEXT);
        assertEquals(1, abilities.size());

        Consumer<GameContext> fn = ActionResolver.parse(abilities.get(0).effectText(), quina);
        assertNotNull(fn, "Expected Quina's effect text to parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).boostSourceForward(quina, 2000, java.util.EnumSet.noneOf(CardData.Trait.class));
        verify(ctx).shieldNamedCardCannotBeChosen("Quina", false, true);
    }

    // =========================================================================================
    // "Cancel unless opponent pays" (Dull-style) — Tier 1: "Choose 1 [Summon/ability]. If your
    // opponent doesn't pay 《N》, cancel its effect." and Tier 2: the standalone body of a
    // "chosen by opponent's Summons or abilities" auto-ability.
    // =========================================================================================

    @Test
    void cancelSummonChoosingMyForwardParsesAndDelegatesToGameContext() {
        // "choosing a Forward you control" variant of the existing "targeting a Character you control" pattern.
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Summon choosing a Forward you control. Cancel its effect.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelFilteredAbilityOnStack(any(), any(), eq(true));
    }

    @Test
    void cancelSummonTargetingMyCharacterStillParses() {
        // Regression guard: the original wording must keep working after broadening the pattern.
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Summon targeting a Character you control. Cancel its effect.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelFilteredAbilityOnStack(any(), any(), eq(true));
    }

    // Y'shtola 10-063C: "Summon or ability" widens the eligible stack entries beyond Summons, and
    // "a Backup you control" is a third target noun alongside Character/Forward.
    @Test
    void yshtolaCancelSummonOrAbilityChoosingMyBackupParses() {
        String effect = "Choose 1 Summon or ability choosing a Backup you control. "
                + "Cancel its effect. Draw 1 card.";
        assertEquals("CancelSummonTargetingMyCharacter + DrawCards",
                ActionResolver.matchedPatternName(effect, null));
        Consumer<GameContext> fn = ActionResolver.parse(effect, null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelFilteredAbilityOnStack(any(), any(), eq(true));
        verify(ctx).drawCards(1);
    }

    // The gate that keeps the ability off the menu with nothing eligible on the stack. The filter
    // reported here is the same one the resolution applies, so the two cannot disagree.
    @Test
    void yshtolaCancelFilterAcceptsSummonsAndAbilitiesButNotExBursts() {
        Predicate<StackEntry> filter = ActionResolver.stackCancelFilter(
                "Choose 1 Summon or ability choosing a Backup you control. Cancel its effect. Draw 1 card.",
                true);
        assertNotNull(filter, "a stack-cancel effect must report a filter so activation can be gated");
        assertTrue(filter.test(stackEntry(true, true, false, false)),  "a Summon is eligible");
        assertTrue(filter.test(stackEntry(false, false, true, false)), "an auto-ability is eligible");
        assertFalse(filter.test(stackEntry(false, false, false, true)), "an EX Burst entry is not");
    }

    @Test
    void nonCancelEffectReportsNoStackFilterSoActivationIsUngated() {
        assertNull(ActionResolver.stackCancelFilter("Draw 1 card.", true));
    }

    /** A stack entry with no recorded targets, so controller-target filters leave it eligible. */
    private static StackEntry stackEntry(boolean summon, boolean p1, boolean auto, boolean exBurst) {
        StackEntry e = mock(StackEntry.class);
        when(e.isSummon()).thenReturn(summon);
        when(e.isAutoAbility()).thenReturn(auto);
        when(e.isExBurstEntry()).thenReturn(exBurst);
        when(e.isP1()).thenReturn(p1);
        when(e.preSelectedTargets()).thenReturn(null);
        return e;
    }

    @Test
    void cancelUnlessPaySummonParsesAndDelegatesToGameContext() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Summon. If your opponent doesn't pay 《2》, cancel its effect.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelFilteredAbilityOnStackUnlessOpponentPays(any(), any(), eq(2));
    }

    @Test
    void cancelUnlessPayOpponentsAutoAbilityParses() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 opponent's auto-ability. If your opponent doesn't pay 《2》, cancel its effect.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelFilteredAbilityOnStackUnlessOpponentPays(any(), any(), eq(2));
    }

    @Test
    void cancelUnlessPayOpponentsAutoAbilityParsesWithIndefiniteArticle() {
        // Qun'mi/Vanille-style variants use "Choose an opponent's..." instead of "Choose 1 opponent's...".
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose an opponent's auto-ability. If your opponent doesn't pay 《2》, cancel its effect.", null);
        assertNotNull(fn);
    }

    @Test
    void cancelUnlessPaySummonOrAutoAbilityParsesInsideSelectFollowingActions() {
        String text = "Select 1 of the 3 following actions. "
                + "\"Choose up to 2 Forwards. Dull them.\" "
                + "\"Choose 1 Character. Freeze it.\" "
                + "\"Choose 1 Summon or auto-ability. If your opponent doesn't pay 《1》, cancel its effect.\"";
        Consumer<GameContext> fn = ActionResolver.parse(text, null);
        assertNotNull(fn, "Expected the select-list wrapper to parse");

        GameContext ctx = mock(GameContext.class);
        when(ctx.chooseActions(any(), any(), anyInt(), anyBoolean())).thenReturn(
                List.of("Choose 1 Summon or auto-ability. If your opponent doesn't pay 《1》, cancel its effect."));
        fn.accept(ctx);

        verify(ctx).cancelFilteredAbilityOnStackUnlessOpponentPays(any(), any(), eq(1));
    }

    @Test
    void cancelChosenTargetUnlessPayParsesStandaloneAndDelegatesToGameContext() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "If your opponent doesn't pay 《2》, cancel their effects.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelChosenSelectionUnlessOpponentPays(2);
    }

    @Test
    void cancelChosenTargetUnlessDiscardParsesAndDelegatesToGameContext() {
        // Kuja / Charlotte real card text: discard-cost variant of the pay-to-avoid-cancel mechanic.
        Consumer<GameContext> fn = ActionResolver.parse(
                "If your opponent doesn't discard 1 card, cancel its effect.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelChosenSelectionUnlessOpponentDiscards(1);
    }

    @Test
    void bareCancelChosenTargetParsesToUnconditionalCancel() {
        // Consequent of Phantasmal Girl / Regis / Tama / Yuna after the optional cost is paid upstream:
        // a bare "cancel their effects" / "cancel its effect" just cancels the in-progress selection.
        for (String txt : new String[]{"Cancel their effects.", "Cancel its effect."}) {
            Consumer<GameContext> fn = ActionResolver.parse(txt, null);
            assertNotNull(fn, "Expected \"" + txt + "\" to parse");
            GameContext ctx = mock(GameContext.class);
            fn.accept(ctx);
            verify(ctx).cancelChosenSelection();
        }
    }

    @Test
    void bareCancelDoesNotMatchStackCancelWordings() {
        // Clione ("cancel the Summon's effect") and Hill Gigas ("cancel its effect and break…") must
        // NOT be swallowed by the bare-cancel pattern.
        GameContext ctx = mock(GameContext.class);
        Consumer<GameContext> a = ActionResolver.parse("Cancel the Summon's effect.", null);
        if (a != null) { a.accept(ctx); }
        Consumer<GameContext> b = ActionResolver.parse("Cancel its effect and break that Character.", null);
        if (b != null) { b.accept(ctx); }
        verify(ctx, never()).cancelChosenSelection();
    }

    @Test
    void banonRevealTopIfBackupCancelParsesAndDelegates() {
        // Banon real card text.
        Consumer<GameContext> fn = ActionResolver.parse(
                "Reveal the top card of your deck. If it is a Backup, cancel all effects choosing Banon.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).revealTopDeckCancelChosenIfType("Backup");
    }

    @Test
    void sirenMillTopIfNotForwardCancelParsesAndDelegates() {
        // Siren (V) real card text (post "you may" strip).
        Consumer<GameContext> fn = ActionResolver.parse(
                "Put the top card of your deck into the Break Zone. If the card put into the Break Zone "
                + "is not a Forward, cancel its effects.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).millTopDeckCancelChosenIfNotType("Forward");
    }

    @Test
    void chosenSelectionCancelEffectsAreRecognizedForInlineExecution() {
        // All the reactive-cancel bodies must be flagged so AutoAbilityTriggers runs them inline.
        assertTrue(ActionResolver.isChosenSelectionCancelEffect("If your opponent doesn't pay 《2》, cancel their effects."));
        assertTrue(ActionResolver.isChosenSelectionCancelEffect("If your opponent doesn't pay 《4》 or 《C》, cancel its effects."));
        assertTrue(ActionResolver.isChosenSelectionCancelEffect("If your opponent doesn't discard 1 card, cancel its effect."));
        assertTrue(ActionResolver.isChosenSelectionCancelEffect("Cancel their effects."));
        assertTrue(ActionResolver.isChosenSelectionCancelEffect("Reveal the top card of your deck. If it is a Backup, cancel all effects choosing Banon."));
        assertTrue(ActionResolver.isChosenSelectionCancelEffect("Put the top card of your deck into the Break Zone. If the card put into the Break Zone is not a Forward, cancel its effects."));
        // A non-cancel chosen effect must NOT be flagged (still goes on the stack normally).
        assertFalse(ActionResolver.isChosenSelectionCancelEffect("Your opponent discards 1 card from their hand."));
    }

    @Test
    void cancelChosenTargetUnlessPayOrCrystalParsesAndDelegatesToGameContext() {
        // Zeromus real card text: opponent may pay 4 CP OR 1 Crystal to avoid the cancel.
        Consumer<GameContext> fn = ActionResolver.parse(
                "If your opponent doesn't pay 《4》 or 《C》, cancel its effects.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelChosenSelectionUnlessOpponentPaysOrCrystal(4, 1);
        verify(ctx, never()).cancelChosenSelectionUnlessOpponentPays(anyInt());
    }

    @Test
    void cancelChosenTargetUnlessPayWithoutCrystalStaysPlainPay() {
        // Regression guard: the plain pay form must not route to the crystal variant.
        Consumer<GameContext> fn = ActionResolver.parse(
                "If your opponent doesn't pay 《2》, cancel their effects.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelChosenSelectionUnlessOpponentPays(2);
        verify(ctx, never()).cancelChosenSelectionUnlessOpponentPaysOrCrystal(anyInt(), anyInt());
    }

    @Test
    void cancelChosenTargetUnlessPayParsesReversedClauseOrder() {
        // White Tiger l'Cie Qun'mi's real card text: "its effect is cancelled if your opponent
        // doesn't pay 《N》." instead of "if your opponent doesn't pay 《N》, cancel its effect."
        Consumer<GameContext> fn = ActionResolver.parse(
                "its effect is cancelled if your opponent doesn't pay 《3》.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelChosenSelectionUnlessOpponentPays(3);
    }

    @Test
    void chosenByOpponentSummonOrAbilityTriggerIsExtractedAsDistinctAutoAbility() {
        // Real Cecil text: plural subject ("are chosen") and the "or abilities" suffix previously
        // fell outside AUTO_ABILITY_PATTERN's trigger vocabulary entirely, so this clause was
        // silently dropped rather than merely failing to parse.
        String text = "When Cecil enters the field, draw 1 card.[[br]]"
                + "When 1 or more Characters you control are chosen by your opponent's Summons or abilities, "
                + "if your opponent doesn't pay 《2》, cancel their effects.";
        List<AutoAbility> abilities = CardData.parseAutoAbilities(text);

        AutoAbility chosenFa = abilities.stream()
                .filter(fa -> fa.trigger().equals("chosen by opponent's summon or ability"))
                .findFirst().orElse(null);
        assertNotNull(chosenFa, "Expected a distinct AutoAbility for the chosen-by trigger");
        assertEquals("if your opponent doesn't pay 《2》, cancel their effects.", chosenFa.effectText());

        Consumer<GameContext> fn = ActionResolver.parse(chosenFa.effectText(), null);
        assertNotNull(fn);
    }

    @Test
    void chosenByOpponentSummonOnlyTriggerStaysNarrowWhenAbilitiesNotMentioned() {
        // Existing cards that only say "...Summons" (no "or abilities") must keep the narrow
        // trigger key so they don't start reacting to ability-driven targeting too.
        String text = "First Strike[[br]] When 1 or more Forwards you control are chosen by "
                + "your opponent's Summon, its effect is cancelled if your opponent doesn't pay 《3》.";
        List<AutoAbility> abilities = CardData.parseAutoAbilities(text);

        AutoAbility chosenFa = abilities.stream()
                .filter(fa -> fa.trigger().startsWith("chosen by opponent's summon"))
                .findFirst().orElse(null);
        assertNotNull(chosenFa, "Expected a distinct AutoAbility for the chosen-by trigger");
        assertEquals("chosen by opponent's summon", chosenFa.trigger());
    }

    @Test
    void remediBreakEnteringUnlessOpponentPaysParsesAndDelegates() {
        // Remedi body: "it" is the entering card, supplied via preloaded targets.
        Consumer<GameContext> fn = ActionResolver.parse(
                "If your opponent doesn't pay 《2》, break it.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget entering = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(entering));
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(ctx).opponentMayPayToPreventAction(eq(2), any());

        fn.accept(ctx);

        verify(ctx).opponentMayPayToPreventAction(eq(2), any());
        verify(ctx).breakTarget(entering);
    }

    @Test
    void ifOppNotPayActionIsGenericOverStandardActions() {
        // The wrapper must reuse standard target actions, not be hardcoded to "break".
        assertTrue(ActionResolver.isIfOppNotPayAction("If your opponent doesn't pay 《2》, break it."));
        assertTrue(ActionResolver.isIfOppNotPayAction("If your opponent doesn't pay 《1》, Freeze it."));
        // Cid Raines' self-sacrifice form is NOT this wrapper (so it isn't inline-fired).
        assertFalse(ActionResolver.isIfOppNotPayAction(
                "put Cid Raines into the Break Zone. When you do so, break it."));

        // Freeze variant delegates to freezeTarget on the preloaded target.
        Consumer<GameContext> fn = ActionResolver.parse("If your opponent doesn't pay 《1》, Freeze it.", null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget entering = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(entering));
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(ctx).opponentMayPayToPreventAction(eq(1), any());
        fn.accept(ctx);
        verify(ctx).freezeTarget(entering);
    }

    @Test
    void arkasodaraChooseThenBreakUnlessOpponentPaysParsesAndDelegates() {
        // Arkasodara (20-064C) ETF: choose a dull Forward, then break it unless the opponent pays 3.
        Consumer<GameContext> fn = ActionResolver.parse(
                "choose 1 dull Forward. If your opponent doesn't pay 《3》, break it.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        // Selection returns one opponent Forward target.
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        // Opponent declines to pay → the action runs.
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(ctx).opponentMayPayToPreventAction(eq(3), any());

        fn.accept(ctx);

        verify(ctx).opponentMayPayToPreventAction(eq(3), any());
        verify(ctx).breakTarget(t);
    }

    @Test
    void ceodoreChooseWarpCardFromBreakZoneParsesAndDelegates() {
        // Ceodore (25-044C) ETF effect body.
        Consumer<GameContext> fn = ActionResolver.parse(
                "choose 1 Card with Warp in your Break Zone. Add it to your hand.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).chooseWarpCardFromBreakZoneToHand();
    }

    // =========================================================================================
    // Cloud of Darkness: "When Cloud of Darkness enters the field, if your opponent has 2 cards
    // or less in their hand, your opponent selects 1 Forward they control. Put it into the Break
    // Zone." — an ETF trigger gated on the opponent's hand size, whose inner effect makes the
    // opponent send one of their own Forwards to the Break Zone. Regression guard for the
    // OPPONENT_HAND_CONDITION_PATTERN bug where "N cards or less in their hand" (the real card
    // wording, one "cards") never matched because the pattern demanded a second "cards".
    // =========================================================================================

    private static final String CLOUD_OF_DARKNESS_TEXT =
            "When Cloud of Darkness enters the field, if your opponent has 2 cards or less in their hand, "
            + "your opponent selects 1 Forward they control. Put it into the Break Zone.";

    private static CardData makeCloudOfDarkness() {
        return new CardData(null, "Cloud of Darkness", "Dark", 5, 9000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), CardData.parseAutoAbilities(CLOUD_OF_DARKNESS_TEXT),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, CLOUD_OF_DARKNESS_TEXT);
    }

    @Test
    void cloudOfDarknessParsesAsEntersFieldTriggerWithHandGatedBreak() {
        CardData cloud = makeCloudOfDarkness();
        List<AutoAbility> autos = cloud.autoAbilities();
        assertEquals(1, autos.size(), "Cloud of Darkness has one auto-ability");
        AutoAbility fa = autos.get(0);
        assertEquals("enters the field", fa.trigger());
        assertEquals("Cloud of Darkness", fa.triggerCard());
        assertNotNull(ActionResolver.parse(fa.effectText(), cloud),
                "the hand-gated opponent-break effect should parse");
    }

    /** Drives the parsed ETF effect against a P1-activated context and returns the MainWindow. */
    private static MainWindow runCloudEffect(int opponentHandSize) {
        MainWindow mw = new MainWindow();
        CardData cloud = makeCloudOfDarkness();
        mw.gameState.getIdentity().put(cloud, true);           // Cloud owned/controlled by P1

        CardData oppForward = makeForward("Opp Forward", "Fire", 2, 5000);
        mw.gameState.getIdentity().put(oppForward, false);     // owned by P2
        mw.placeP2CardInForwardZone(oppForward);               // P2 idx 0

        for (int i = 0; i < opponentHandSize; i++) {
            CardData filler = makeForward("Filler " + i, "Ice", 1, 1000);
            mw.gameState.getIdentity().put(filler, false);
            mw.gameState.getP2Hand().add(filler);
        }

        GameContext ctx = mw.buildGameContext(true);           // P1 activates (opponent = P2)
        ctx.preloadTargets(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));

        AutoAbility fa = cloud.autoAbilities().get(0);
        ActionResolver.parse(fa.effectText(), cloud).accept(ctx);
        return mw;
    }

    @Test
    void cloudOfDarknessBreaksOpponentForwardWhenHandIsSmall() {
        MainWindow mw = runCloudEffect(2);   // opponent hand size 2 → condition met (≤ 2)
        assertTrue(mw.p2ForwardCards.isEmpty(), "the opponent's Forward should leave the field");
        assertEquals(1, mw.gameState.getP2BreakZone().size(),
                "the opponent's Forward should be in their Break Zone");
    }

    @Test
    void cloudOfDarknessDoesNothingWhenOpponentHandTooLarge() {
        MainWindow mw = runCloudEffect(3);   // opponent hand size 3 → condition fails (> 2)
        assertEquals(1, mw.p2ForwardCards.size(), "the opponent's Forward should remain on the field");
        assertTrue(mw.gameState.getP2BreakZone().isEmpty(),
                "nothing should be put into the Break Zone");
    }

    // =========================================================================================
    // Odin 21-084H: "Choose 1 Forward or Monster of cost 4 or less. Break it. If you control 5 or
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
    // Brynhildr 15-014H: "EX BURST Choose 1 Forward. Deal it 5000 damage. When it is put from the
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

    // =========================================================================================
    // Yuzuki 13-125R: "If a Fire Forward you control is dealt damage by your opponent's abilities,
    // the damage becomes 0 instead. / If a Water Forward you control is dealt damage, reduce the
    // damage by 2000 instead." — two field-wide protections keyed on the damaged Forward's element.
    // FA_FIELD_DAMAGE_MODIFIER had no element qualifier and no ability source clause, so neither
    // clause matched and Yuzuki reduced nothing. Yuzuki is itself Water/Fire, so it satisfies both:
    // against opponent ability damage the Fire clause zeroes it, which subsumes the Water clause.
    // =========================================================================================

    private static final String YUZUKI_TEXT =
            "If a Fire Forward you control is dealt damage by your opponent's abilities, the damage becomes 0 instead."
            + "[[br]]   If a Water Forward you control is dealt damage, reduce the damage by 2000 instead.";

    /** Yuzuki on P1 idx 0, plus {@code others} from idx 1 up, with a P2-owned ability resolving. */
    private static MainWindow boardWithYuzuki(CardData... others) {
        MainWindow mw = new MainWindow();
        mw.placeCardInForwardZone(makeFieldAbilityCard("Yuzuki", "Water/Fire", "Forward", YUZUKI_TEXT));
        for (CardData c : others) mw.placeCardInForwardZone(c);
        // currentAbilitySourceIsP1 defaults to false, so this stands in for an opponent's ability
        // resolving against P1's board.
        mw.currentAbilitySource = makeForward("Opp Caster", "Wind", 3, 5000);
        return mw;
    }

    @Test
    void yuzukiZeroesOpponentAbilityDamageToAFireForward() {
        MainWindow mw = boardWithYuzuki(makeForward("Fire Ally", "Fire", 3, 8000));
        assertEquals(0, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 1, 5000, true, false),
                "Fire Forward, opponent's ability — damage becomes 0");
    }

    @Test
    void yuzukiOnlyReducesOpponentAbilityDamageToAWaterForward() {
        MainWindow mw = boardWithYuzuki(makeForward("Water Ally", "Water", 3, 8000));
        assertEquals(3000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 1, 5000, true, false),
                "Water Forward — only the 2000 reduction applies, not the Fire nullification");
    }

    @Test
    void yuzukiProtectsItselfAsAWaterFireForward() {
        MainWindow mw = boardWithYuzuki();
        assertEquals(0, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false),
                "Yuzuki is Water/Fire — the Fire clause wins against opponent ability damage");
        assertEquals(3000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 5000, false, false),
                "battle damage is not ability damage — only the Water reduction applies");
    }

    @Test
    void yuzukiIgnoresForwardsOfOtherElements() {
        MainWindow mw = boardWithYuzuki(makeForward("Wind Ally", "Wind", 3, 8000));
        assertEquals(5000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 1, 5000, true, false),
                "neither clause covers a Wind Forward");
    }

    @Test
    void yuzukiFireClauseSkipsItsControllersOwnAbilities() {
        // Mirror board: Yuzuki and the damaged Forward belong to P2, and the resolving ability
        // source is P2's too, so "your opponent's abilities" is not satisfied.
        MainWindow mw = new MainWindow();
        mw.placeP2CardInForwardZone(makeFieldAbilityCard("Yuzuki", "Water/Fire", "Forward", YUZUKI_TEXT));
        mw.placeP2CardInForwardZone(makeForward("Fire Ally", "Fire/Water", 3, 8000));
        mw.currentAbilitySource = makeForward("Own Caster", "Wind", 3, 5000);
        assertEquals(3000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 1, 5000, true, false),
                "a friendly ability does not trigger the Fire nullification; the Water clause still applies");
    }

    // =========================================================================================
    // Cu Sith 10-068C: "EX BURST Choose 1 Forward or Backup in your Break Zone. Add it to your
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
    // Ifrit 25-004H: an alternate cast cost paid by removing a Backup from the game rather than
    // by Crystals. The cost sentence has to be recognised for its own sake AND so summonEffect()
    // strips it — while it stayed in the effect text the resolver matched the combined string and
    // the actual summon effect never ran.
    // =========================================================================================

    private static final String IFRIT_TEXT =
            "Before paying the cost to cast Ifrit, you can remove 1 Fire Backup you control from the game "
            + "to reduce the cost required to cast Ifrit by 2.[[br]]"
            + "Choose 1 Forward and up to 1 other Forward. Deal the former 9000 damage and deal the latter 4000 damage.";

    private static CardData makePlainBackup(String name, String element, int cost) {
        return new CardData(null, name, element, cost, 0, "Backup", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    private static CardData makeSummon(String name, String element, int cost, String text) {
        return new CardData(null, name, element, cost, 0, "Summon", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, text);
    }

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

        mw.p1BackupCards[0] = makePlainBackup("Fire Guy", "Fire", 2);
        mw.p1BackupCards[1] = makePlainBackup("Ice Guy", "Ice", 2);
        mw.p1BackupCards[2] = makePlainBackup("Other Fire Guy", "Fire", 3);

        assertEquals(List.of(0, 2), mw.altFieldRemovalCandidates(ifrit.altFieldRemoval()),
                "only Fire Backups can pay this cost");
    }

    // =========================================================================================
    // Mont Leonis 22-113L: "When Mont Leonis enters the field, choose 1 Fire Forward of cost 3 or
    // less in your Break Zone and 1 Fire Forward of cost 5 or less in your Break Zone. If you
    // control 5 or more Fire Backups, play them onto the field. They gain Haste until the end of
    // the turn. Then, put 1 Backup you control into the Break Zone."
    //
    // Two official FAQ rulings drive the shape:
    //   - unless both Forwards can be chosen the ability is not placed on the stack;
    //   - with too few Backups you "also do not put a Backup into the Break Zone" — the control
    //     condition governs the sacrifice as well as the play.
    // =========================================================================================

    private static final String MONT_LEONIS_TEXT =
            "Limit Break -- 3[[br]]   When Mont Leonis enters the field, choose 1 Fire Forward of cost 3 or less "
            + "in your Break Zone and 1 Fire Forward of cost 5 or less in your Break Zone. If you control 5 or "
            + "more Fire Backups, play them onto the field. They gain Haste until the end of the turn. "
            + "Then, put 1 Backup you control into the Break Zone.";

    private static CardData makeMontLeonis() {
        return new CardData(null, "Mont Leonis", "Fire", 8, 9000, "Forward", true, 3, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), CardData.parseAutoAbilities(MONT_LEONIS_TEXT),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                "Archlord", "FFBE", null, MONT_LEONIS_TEXT);
    }

    /** The card's enters-the-field ability text, as CardData extracts it. */
    private static String montLeonisEffect() {
        CardData mont = makeMontLeonis();
        assertEquals(1, mont.autoAbilities().size(), "the ETB ability should be extracted");
        return mont.autoAbilities().get(0).effectText();
    }

    private static final ForwardTarget BZ_LOW  = new ForwardTarget(true, 1, ForwardTarget.CardZone.BREAK_ZONE);
    private static final ForwardTarget BZ_HIGH = new ForwardTarget(true, 4, ForwardTarget.CardZone.BREAK_ZONE);

    /** A context with a Break Zone deep enough to supply both Forwards. */
    private static GameContext montLeonisContext(boolean conditionMet) {
        GameContext ctx = mock(GameContext.class);
        when(ctx.isP1()).thenReturn(true);
        // Unstubbed this returns an empty list, not null, which makes selectTargets treat the
        // sacrifice as already-targeted and skip the selection entirely.
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.countSelfBreakZoneMatching(true, false, false, false, "fire", 3)).thenReturn(1);
        when(ctx.countSelfBreakZoneMatching(true, false, false, false, "fire", 5)).thenReturn(3);
        when(ctx.selectTwoOwnBreakZoneForwards("fire", 3, 5)).thenReturn(List.of(BZ_LOW, BZ_HIGH));
        when(ctx.controlConditionMet(any())).thenReturn(conditionMet);
        return ctx;
    }

    @Test
    void montLeonisPlaysBothForwardsWithHasteAndTakesTheBackupCost() {
        Consumer<GameContext> fn = ActionResolver.parse(montLeonisEffect(), makeMontLeonis());
        assertNotNull(fn, "Mont Leonis' enters-the-field ability should parse");

        GameContext ctx = montLeonisContext(true);
        ForwardTarget landedLow  = new ForwardTarget(true, 2, ForwardTarget.CardZone.FORWARD);
        ForwardTarget landedHigh = new ForwardTarget(true, 3, ForwardTarget.CardZone.FORWARD);
        when(ctx.playTargetOntoField(BZ_LOW)).thenReturn(landedLow);
        when(ctx.playTargetOntoField(BZ_HIGH)).thenReturn(landedHigh);
        ForwardTarget sacrificed = new ForwardTarget(true, 0, ForwardTarget.CardZone.BACKUP);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(List.of(sacrificed));

        fn.accept(ctx);

        verify(ctx).playTargetOntoField(BZ_LOW);
        verify(ctx).playTargetOntoField(BZ_HIGH);
        EnumSet<CardData.Trait> haste = EnumSet.of(CardData.Trait.HASTE);
        verify(ctx).boostTarget(landedLow, 0, haste);
        verify(ctx).boostTarget(landedHigh, 0, haste);
        verify(ctx).forceTargetToBreakZone(sacrificed);
    }

    // Playing removes the card from the Break Zone, so the deeper index has to go first or the
    // shallower target would slide out from under the second play.
    @Test
    void montLeonisPlaysTheDeeperBreakZoneIndexFirst() {
        Consumer<GameContext> fn = ActionResolver.parse(montLeonisEffect(), makeMontLeonis());
        GameContext ctx = montLeonisContext(true);
        when(ctx.playTargetOntoField(any())).thenReturn(null);

        fn.accept(ctx);

        InOrder order = inOrder(ctx);
        order.verify(ctx).playTargetOntoField(BZ_HIGH);
        order.verify(ctx).playTargetOntoField(BZ_LOW);
    }

    // FAQ: "If you control 4 or less Backups, you also do not put a Backup into the Break Zone."
    @Test
    void montLeonisTakesNoBackupCostWhenTheControlConditionFails() {
        Consumer<GameContext> fn = ActionResolver.parse(montLeonisEffect(), makeMontLeonis());
        GameContext ctx = montLeonisContext(false);

        fn.accept(ctx);

        verify(ctx, never()).playTargetOntoField(any());
        verify(ctx, never()).boostTarget(any(), anyInt(), any());
        verify(ctx, never()).forceTargetToBreakZone(any());
    }

    // FAQ: unless both Forwards can be chosen the auto-ability is never placed on the stack, so
    // the player is not asked to pick anything.
    @Test
    void montLeonisDoesNotTriggerWhenTheBreakZoneCannotSupplyBoth() {
        Consumer<GameContext> fn = ActionResolver.parse(montLeonisEffect(), makeMontLeonis());
        GameContext ctx = mock(GameContext.class);
        when(ctx.countSelfBreakZoneMatching(true, false, false, false, "fire", 3)).thenReturn(1);
        when(ctx.countSelfBreakZoneMatching(true, false, false, false, "fire", 5)).thenReturn(1);

        fn.accept(ctx);

        verify(ctx, never()).selectTwoOwnBreakZoneForwards(any(), anyInt(), anyInt());
        verify(ctx, never()).controlConditionMet(any());
        verify(ctx, never()).playTargetOntoField(any());
        verify(ctx, never()).forceTargetToBreakZone(any());
    }

    @Test
    void montLeonisDoesNotTriggerWithNoCheapEnoughForward() {
        Consumer<GameContext> fn = ActionResolver.parse(montLeonisEffect(), makeMontLeonis());
        GameContext ctx = mock(GameContext.class);
        // Plenty of cost-5 Forwards, but nothing at cost 3 or less to fill the first slot.
        when(ctx.countSelfBreakZoneMatching(true, false, false, false, "fire", 3)).thenReturn(0);
        when(ctx.countSelfBreakZoneMatching(true, false, false, false, "fire", 5)).thenReturn(4);

        fn.accept(ctx);

        verify(ctx, never()).selectTwoOwnBreakZoneForwards(any(), anyInt(), anyInt());
        verify(ctx, never()).playTargetOntoField(any());
    }

    @Test
    void montLeonisReportsItsOwnPatternName() {
        CardData mont = makeMontLeonis();
        String effect = montLeonisEffect();
        assertEquals("ChooseTwoBzFwdPlayIfControl", ActionResolver.matchedPatternName(effect, mont));
        assertEquals("ChooseTwoBzFwdPlayIfControl", ActionResolver.fullDescription(effect, mont));
    }

    // =========================================================================================
    // Black Mage 27-097C: "When Black Mage enters the field, your opponent selects 1 Forward of
    // cost 2 or less they control. Put it into the Break Zone. If you control a Multi-Element
    // Forward, your opponent selects 1 Forward of cost 4 or less they control instead. Put it into
    // the Break Zone." — the "instead" clause replaces the whole base effect (it does not stack),
    // so exactly one of the two cost thresholds applies.
    // =========================================================================================

    private static final String BLACK_MAGE_TEXT =
            "When Black Mage enters the field, your opponent selects 1 Forward of cost 2 or less they control. "
            + "Put it into the Break Zone. If you control a Multi-Element Forward, your opponent selects 1 Forward "
            + "of cost 4 or less they control instead. Put it into the Break Zone.";

    private static CardData makeBlackMage() {
        return new CardData(null, "Black Mage", "Water", 3, 7000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), CardData.parseAutoAbilities(BLACK_MAGE_TEXT),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, BLACK_MAGE_TEXT);
    }

    @Test
    void blackMageParsesAsEntersFieldTriggerWithControlGatedUpgrade() {
        CardData blackMage = makeBlackMage();
        List<AutoAbility> autos = blackMage.autoAbilities();
        assertEquals(1, autos.size(), "Black Mage has one auto-ability");
        AutoAbility fa = autos.get(0);
        assertEquals("enters the field", fa.trigger());
        assertEquals("Black Mage", fa.triggerCard());
        assertNotNull(ActionResolver.parse(fa.effectText(), blackMage),
                "the cost-gated opponent-break effect with its 'instead' upgrade should parse");
    }

    /**
     * Drives the parsed ETF effect against a P1-activated context. P2 controls a single Forward of
     * {@code oppForwardCost}; P1 additionally controls a Multi-Element Forward when
     * {@code withMultiElement} is set. With one eligible target the selection resolves without a
     * dialog, so the cost filter alone decides whether anything is broken.
     */
    private static MainWindow runBlackMageEffect(int oppForwardCost, boolean withMultiElement) {
        MainWindow mw = new MainWindow();
        CardData blackMage = makeBlackMage();
        mw.gameState.getIdentity().put(blackMage, true);        // Black Mage owned/controlled by P1

        if (withMultiElement) {
            CardData multi = makeForward("Multi Forward", "Fire/Water", 3, 5000);
            mw.gameState.getIdentity().put(multi, true);
            mw.placeCardInForwardZone(multi);                   // P1 idx 0
        }

        CardData oppForward = makeForward("Opp Forward", "Fire", oppForwardCost, 5000);
        mw.gameState.getIdentity().put(oppForward, false);
        mw.placeP2CardInForwardZone(oppForward);                // P2 idx 0

        GameContext ctx = mw.buildGameContext(true);            // P1 activates (opponent = P2)
        AutoAbility fa = blackMage.autoAbilities().get(0);
        ActionResolver.parse(fa.effectText(), blackMage).accept(ctx);
        return mw;
    }

    @Test
    void blackMageBreaksCheapForwardWithoutMultiElement() {
        MainWindow mw = runBlackMageEffect(2, false);
        assertTrue(mw.p2ForwardCards.isEmpty(), "the cost 2 Forward is within the base threshold");
        assertEquals(1, mw.gameState.getP2BreakZone().size(),
                "the opponent's Forward should be in their Break Zone");
    }

    @Test
    void blackMageSparesExpensiveForwardWithoutMultiElement() {
        MainWindow mw = runBlackMageEffect(4, false);
        assertEquals(1, mw.p2ForwardCards.size(),
                "cost 4 exceeds the base threshold of 2 — the Forward should remain");
        assertTrue(mw.gameState.getP2BreakZone().isEmpty(), "nothing should be put into the Break Zone");
    }

    @Test
    void blackMageBreaksExpensiveForwardWithMultiElement() {
        MainWindow mw = runBlackMageEffect(4, true);
        assertTrue(mw.p2ForwardCards.isEmpty(),
                "the Multi-Element upgrade raises the threshold to cost 4");
        assertEquals(1, mw.gameState.getP2BreakZone().size(),
                "the opponent's Forward should be in their Break Zone");
    }

    // =========================================================================================
    // Physalis: "When Physalis enters the field or attacks, if your opponent has 3 cards or less
    // in their hand, select 1 of the 2 following actions. If your opponent has no cards in their
    // hand, select up to 2 of the 2 following actions instead. "Choose 1 Character. Dull it and
    // Freeze it." "Draw 1 card."" plus a "《S》《Ice》《Ice》: Choose 1 Forward. Deal it 10000 damage.
    // That Forward's controller discards 1 card." special ability.
    //
    // Three things had to be wired up:
    //  1) The "Dull it and Freeze it" quoted sub-action used to collide with ACTION_ABILITY_PATTERN
    //     (the case-insensitive bare-name dull-cost branch matched the pronoun "it" and its "and …"
    //     continuation devoured the following [[s]] ability's name and 《S》 cost).
    //  2) The base gate — the modal must only fire when the opponent has ≤ 3 cards in hand.
    //  3) The empty-hand upgrade — with an empty opponent hand the player selects up to 2 (not 1).
    // =========================================================================================

    private static final String PHYSALIS_TEXT =
            "When Physalis enters the field or attacks, if your opponent has 3 cards or less in their hand, "
            + "select 1 of the 2 following actions. If your opponent has no cards in their hand, select up to 2 "
            + "of the 2 following actions instead. \"Choose 1 Character. Dull it and Freeze it.\" \"Draw 1 card.\"[[br]]   "
            + "[[s]]Premium Physalis Bullet [[/]]《S》《Ice》《Ice》: Choose 1 Forward. "
            + "Deal it 10000 damage. That Forward's controller discards 1 card from their hand.";

    @Test
    void physalisParsesModalAutoAbilityAndSpecialAbilityWithoutCollision() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(PHYSALIS_TEXT);
        assertEquals(1, autos.size(), "one auto-ability (the modal ETF/attack trigger)");
        AutoAbility fa = autos.get(0);
        assertEquals("enters the field or attacks", fa.trigger());
        assertEquals("Physalis", fa.triggerCard());
        assertNotNull(ActionResolver.parse(fa.effectText(), null), "the modal effect should parse");

        // The [[s]] special ability must survive parsing alongside the "Dull it and Freeze it" quote.
        List<ActionAbility> actions = CardData.parseActionAbilities(PHYSALIS_TEXT);
        assertEquals(1, actions.size(), "one action ability (the S ability)");
        ActionAbility s = actions.get(0);
        assertEquals("Premium Physalis Bullet", s.abilityName());
        assertTrue(s.isSpecial(), "the 《S》 cost must mark it Special");
        assertEquals(List.of("Ice", "Ice"), s.cpCost());
    }

    @Test
    void physalisSelectsOneNormallyButUpToTwoWhenOpponentHandEmpty() {
        String effect = CardData.parseAutoAbilities(PHYSALIS_TEXT).get(0).effectText();
        Consumer<GameContext> fn = ActionResolver.parse(effect, null);
        assertNotNull(fn);

        // Opponent has cards (2): base modal — select exactly 1.
        GameContext some = mock(GameContext.class);
        when(some.opponentHandSize()).thenReturn(2);
        fn.accept(some);
        verify(some).chooseActions(any(), any(), eq(1), eq(false));

        // Opponent hand empty: upgrade — select up to 2.
        GameContext empty = mock(GameContext.class);
        when(empty.opponentHandSize()).thenReturn(0);
        fn.accept(empty);
        verify(empty).chooseActions(any(), any(), eq(2), eq(true));
    }

    @Test
    void physalisBaseGateChecksOpponentHandSize() throws Exception {
        MainWindow mw = new MainWindow();
        java.lang.reflect.Method check = AutoAbilityTriggers.class
                .getDeclaredMethod("checkAutoAbilityCondition", String.class, boolean.class);
        check.setAccessible(true);
        String cond = "your opponent has 3 cards or less in their hand";

        // P1 owns the ability → opponent is P2.
        setHandSize(mw, false, 3);
        assertEquals(true,  check.invoke(mw.autoAbilityTriggers, cond, true), "3 ≤ 3 — condition met");
        setHandSize(mw, false, 4);
        assertEquals(false, check.invoke(mw.autoAbilityTriggers, cond, true), "4 > 3 — condition fails");
        setHandSize(mw, false, 0);
        assertEquals(true,  check.invoke(mw.autoAbilityTriggers, cond, true), "empty hand — condition met");
    }

    /** Sets the given player's hand to exactly {@code n} filler cards. */
    private static void setHandSize(MainWindow mw, boolean isP1, int n) {
        List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
        hand.clear();
        for (int i = 0; i < n; i++) hand.add(makeForward("Filler " + i, "Ice", 1, 1000));
    }

    // =========================================================================================
    // Mog (VI) 9-117C "Dusk Requiem" (《S》《Water》《Water》《Dull》): "Choose 1 Forward. Reveal the top
    // card of your deck. If the revealed card's CP cost is an even number, return chosen Forward to
    // its owner's hand … If … odd …, deal the chosen Forward 4000 damage, dull it and Freeze it …"
    //
    // Guards two bugs that broke this wired-up ability:
    //  1) The preceding "…leaves the field, discard 2 cards from your hand." auto-ability let the
    //     discard-cost group of ACTION_ABILITY_PATTERN run across the [[br]][[s]] markup to the S
    //     ability's colon, swallowing its name and 《S》 cost (name lost, isSpecial cleared).
    //  2) The dedicated reveal-cost-parity parser sat AFTER tryParseChooseCharacter in the parse
    //     chain, so the generic ChooseCharacter parser claimed the effect and only partially
    //     handled it — the parity branch has to be tried first.
    // =========================================================================================

    private static final String MOG_VI_TEXT =
            "When Mog (VI) enters the field, draw 2 cards.[[br]] When Mog (VI) leaves the field, discard 2 cards from your hand.[[br]]"
            + "[[s]]Dusk Requiem[[/]] 《S》《Water》《Water》《Dull》: Choose 1 Forward. Reveal the top card of your deck. "
            + "If the revealed card's CP cost is an even number, return chosen Forward to its owner's hand. Add the revealed card to your hand. "
            + "If the revealed card's CP cost is an odd number, deal the chosen Forward 4000 damage, dull it and Freeze it. Add the revealed card to your hand.";

    @Test
    void mogViDuskRequiemKeepsSpecialIdentityAndRoutesToParityParser() {
        List<ActionAbility> actions = CardData.parseActionAbilities(MOG_VI_TEXT);
        assertEquals(1, actions.size(), "one action ability (the S ability)");
        ActionAbility s = actions.get(0);

        // (1) The [[s]] identity must survive the neighbouring discard-cost auto-ability.
        assertEquals("Dusk Requiem", s.abilityName());
        assertTrue(s.isSpecial(), "《S》 must mark it Special");
        assertTrue(s.requiresDull(), "《Dull》 is part of the cost");
        assertEquals(List.of("Water", "Water"), s.cpCost());

        // (2) The effect must reach the dedicated reveal-cost-parity parser, not generic ChooseCharacter.
        assertEquals("ChooseFwdRevealCostParity",
                ActionResolver.matchedPatternName(s.effectText(), null));
        assertNotNull(ActionResolver.parse(s.effectText(), null));
    }

    // =========================================================================================
    // Ability-granting cards. These grant a quoted "《cost》: effect" ability to a chosen Forward.
    // The quoted grant used to (a) truncate the ETB auto-ability effect at the 《cost》: inside the
    // quote and (b) be mis-parsed as the granting card's OWN action ability. Now the grant text is
    // captured whole, the card exposes no spurious own-abilities, and the grant is applied.
    //   • Machinist 12-057C — grants an EOT ability to up to 2 Forwards.
    //   • Medusa 22-034H     — places a Petrification Counter (drives a cannot-attack/block
    //                          restriction + a "《5》: remove counters" ability off the counter).
    //   • Innocence 13-137S  — a Break-Zone-gated SELF grant; must stay intact (regression guard).
    // =========================================================================================

    private static final String MACHINIST_TEXT =
            "When Machinist enters the field, choose up to 2 Forwards. Until the end of the turn, "
            + "they gain \"《Dull》: Choose 1 Forward. Deal it 4000 damage.\"";
    private static final String MEDUSA_TEXT =
            "When Medusa enters the field, choose 1 Forward. Place 1 Petrification Counter on it and it gains "
            + "\"If a Petrification Counter is placed on this Forward, this Forward cannot attack or block.\" and "
            + "\"《5》: Remove all Petrification Counters from this Forward.\" (These effects do not end at the end of the turn.)";
    private static final String INNOCENCE_TEXT =
            "Brave[[br]]If you have a Card Name Innocence in your Break Zone, Innocence gains "
            + "\"《Fire》《Dull》: Choose 1 Forward. Deal it 10000 damage.\" and "
            + "\"《Ice》《Dull》: Your opponent discards 2 cards from their hand. You can only use this ability during your turn and only once per turn.\"";

    @Test
    void grantingCardsExposeNoOwnAbilitiesAndTheirEtbEffectsParse() {
        // Neither granting card should surface the quoted grant as its OWN action ability.
        assertTrue(CardData.parseActionAbilities(MACHINIST_TEXT).isEmpty(), "Machinist has no own action ability");
        assertTrue(CardData.parseActionAbilities(MEDUSA_TEXT).isEmpty(),    "Medusa has no own action ability");

        // The full ETB effect (grant text no longer truncated at the 《cost》: inside the quote) parses.
        String machEtb = CardData.parseAutoAbilities(MACHINIST_TEXT).get(0).effectText();
        assertEquals("ChooseForwardsGainAbilityEot", ActionResolver.matchedPatternName(machEtb, null));
        String medusaEtb = CardData.parseAutoAbilities(MEDUSA_TEXT).get(0).effectText();
        assertEquals("ChooseForwardPlacePetrification", ActionResolver.matchedPatternName(medusaEtb, null));

        // Innocence's Break-Zone-gated self-grant must remain two intact, gated action abilities.
        List<ActionAbility> inno = CardData.parseActionAbilities(INNOCENCE_TEXT);
        assertEquals(2, inno.size(), "Innocence keeps its two self-granted abilities");
        assertTrue(inno.stream().allMatch(a -> "Innocence".equalsIgnoreCase(a.ownBreakZoneNameRequired())),
                "both abilities stay gated on Innocence being in the Break Zone");
    }

    @Test
    void machinistGrantsEotAbilityToChosenForward() {
        MainWindow mw = new MainWindow();
        CardData machinist = makeForward("Machinist", "Fire", 2, 5000);
        CardData target    = makeForward("Target", "Ice", 3, 7000);
        mw.gameState.getIdentity().put(target, true);
        mw.placeCardInForwardZone(target);   // P1 idx 0

        GameContext ctx = mw.buildGameContext(true);
        ctx.preloadTargets(List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD)));
        String etb = CardData.parseAutoAbilities(MACHINIST_TEXT).get(0).effectText();
        ActionResolver.parse(etb, machinist).accept(ctx);

        List<ActionAbility> granted = mw.p1TempGrantedAbilities.get(target);
        assertNotNull(granted, "the chosen Forward should have a granted ability");
        assertEquals(1, granted.size());
        assertEquals("Choose 1 Forward. Deal it 4000 damage.", granted.get(0).effectText());
        assertTrue(granted.get(0).requiresDull(), "the granted ability keeps its 《Dull》 cost");
    }

    @Test
    void medusaPetrifiesForwardAndTheFiveCostAbilityRemovesIt() {
        MainWindow mw = new MainWindow();
        CardData medusa = makeForward("Medusa", "Earth", 4, 8000);
        CardData target = makeForward("Victim", "Fire", 3, 7000);
        mw.gameState.getIdentity().put(target, true);
        mw.placeCardInForwardZone(target);   // P1 idx 0

        GameContext ctx = mw.buildGameContext(true);
        ctx.preloadTargets(List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD)));
        String etb = CardData.parseAutoAbilities(MEDUSA_TEXT).get(0).effectText();
        ActionResolver.parse(etb, medusa).accept(ctx);

        assertEquals(1, mw.gameState.getCounters(target, "Petrification"), "target is petrified");
        assertTrue(mw.isFieldAbilityCannotAttackOrBlock(target, true),
                "a petrified Forward cannot attack or block");

        // The granted "《5》: Remove all Petrification Counters from this Forward." lifts it.
        ActionResolver.parse("Remove all Petrification Counters from this Forward.", target)
                .accept(mw.buildGameContext(true));
        assertEquals(0, mw.gameState.getCounters(target, "Petrification"), "counters removed");
        assertFalse(mw.isFieldAbilityCannotAttackOrBlock(target, true), "restriction lifted");
    }

    // =========================================================================================
    // Gippal (12-058C): "When a party you control attacks, all Forwards in that party gain +5000
    // power until the end of the turn."  The party-attack trigger must record the attacking party
    // and the followup must boost exactly those Forwards (not other Forwards on the field).
    // =========================================================================================

    private static final String GIPPAL_TEXT =
            "The Forwards forming a party you control gain Brave.[[br]]   "
            + "When a party you control attacks, all Forwards in that party gain +5000 power until the end of the turn.";

    private static CardData makeGippal(int power) {
        return new CardData(null, "Gippal", "Lightning", 5, power, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), CardData.parseAutoAbilities(GIPPAL_TEXT), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, GIPPAL_TEXT);
    }

    @Test
    void gippalPartyAttackBoostsAllPartyForwardsBy5000() {
        MainWindow mw = new MainWindow();
        CardData gippal = makeGippal(9000);
        CardData ally   = makeForward("Ally", "Lightning", 3, 7000);
        CardData bench  = makeForward("Bench", "Lightning", 2, 5000);
        mw.placeCardInForwardZone(gippal); // P1 idx 0
        mw.placeCardInForwardZone(ally);   // P1 idx 1
        mw.placeCardInForwardZone(bench);  // P1 idx 2

        // Gippal + Ally form a party and attack; Bench stays home. P1 acted, so the CPU has
        // priority and Gippal's auto-ability resolves off the stack immediately.
        List<CardData> party = List.of(gippal, ally);
        mw.autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(true, party);

        // The attacking party is recorded, and every Forward in it gained +5000 power.
        assertEquals(party, mw.p1Turn.currentPartyAttackers);
        assertEquals(14000, mw.effectiveP1ForwardPower(0), "Gippal (in party) should be +5000");
        assertEquals(12000, mw.effectiveP1ForwardPower(1), "Ally (in party) should be +5000");
        assertEquals(5000,  mw.effectiveP1ForwardPower(2), "Bench (not in party) should be unchanged");
    }

    // =========================================================================================
    // Chocobo (9-050C): "When a Card Name Chocobo you control forms a party and attacks, all
    // Forwards in that party gain +1000 power until the end of the turn."  The "a Card Name X you
    // control" subject must resolve to a card-name party filter (partyCardName = "Chocobo") so the
    // followup fires only when a Chocobo is actually in the attacking party.
    // =========================================================================================

    private static final String CHOCOBO_TEXT =
            "When a Card Name Chocobo you control forms a party and attacks, all Forwards in that party gain +1000 power until the end of the turn.";

    private static CardData makeChocobo(int power) {
        return new CardData(null, "Chocobo", "Wind", 2, power, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), CardData.parseAutoAbilities(CHOCOBO_TEXT), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, CHOCOBO_TEXT);
    }

    @Test
    void chocoboPartyAttackFiresOnlyWhenAChocoboIsInTheParty() {
        // Positive: Chocobo + ally attack together — the Card-Name filter matches, both get +1000.
        MainWindow mw = new MainWindow();
        CardData chocobo = makeChocobo(3000);
        CardData ally    = makeForward("Ally", "Wind", 3, 7000);
        mw.placeCardInForwardZone(chocobo); // P1 idx 0
        mw.placeCardInForwardZone(ally);    // P1 idx 1
        mw.autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(true, List.of(chocobo, ally));
        assertEquals(4000, mw.effectiveP1ForwardPower(0), "Chocobo (in party) should be +1000");
        assertEquals(8000, mw.effectiveP1ForwardPower(1), "Ally (in party) should be +1000");

        // Negative: two non-Chocobo Forwards attack while Chocobo sits out — filter fails, no boost.
        MainWindow mw2 = new MainWindow();
        CardData benched = makeChocobo(3000);
        CardData a1 = makeForward("A1", "Wind", 3, 7000);
        CardData a2 = makeForward("A2", "Wind", 3, 6000);
        mw2.placeCardInForwardZone(benched); // P1 idx 0 — owns the ability but does not attack
        mw2.placeCardInForwardZone(a1);      // P1 idx 1
        mw2.placeCardInForwardZone(a2);      // P1 idx 2
        mw2.autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(true, List.of(a1, a2));
        assertEquals(7000, mw2.effectiveP1ForwardPower(1), "A1 unchanged — no Chocobo in the party");
        assertEquals(6000, mw2.effectiveP1ForwardPower(2), "A2 unchanged — no Chocobo in the party");
    }

    // =========================================================================================
    // "Cannot be returned to its owner's hand by your opponent's Summons or abilities" family:
    //   • Krile (6-071H)     — action ability followup granting EOT return protection
    //   • Gilgamesh (1-207S) — named permanent field ability
    //   • Ritz (4-072H)      — blanket "Characters you control" field ability
    //   • Black Tortoise l'Cie Gilgamesh (10-069R) — compound dull/return/BZ protection clauses
    //   • Exodus (11-070R)   — EX Burst single-target buff upgrading to all Forwards at 5+ damage
    //   • Asura (23-039R)    — activate-all + return/power-decrease protection grants
    // =========================================================================================

    private static CardData makeForwardWithText(String name, String element, int cost, int power, String textEn) {
        return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                CardData.parseActionAbilities(textEn), CardData.parseAutoAbilities(textEn),
                CardData.parseFieldAbilities(textEn, "Forward"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, textEn);
    }

    private static final String KRILE_TEXT =
            "《Earth》《1》《Dull》: Choose 1 Forward you control. During this turn, it cannot be "
            + "returned to its owner's hand by your opponent's Summons or abilities.";

    @Test
    void krileFollowupGrantsReturnToHandProtectionUntilEot() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(KRILE_TEXT);
        assertEquals(1, abilities.size());
        ActionAbility ability = abilities.get(0);
        assertTrue(ability.requiresDull());
        assertEquals(List.of("Earth", ""), ability.cpCost());

        Consumer<GameContext> fn = ActionResolver.parse(ability.effectText(), null);
        assertNotNull(fn, "Krile's followup should parse");

        GameContext ctx = mock(GameContext.class);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));
        fn.accept(ctx);
        verify(ctx).boostTarget(t, 0,
                java.util.EnumSet.of(CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP));
    }

    private static final String GILGAMESH_TEXT =
            "Gilgamesh cannot be returned to its owner's hand by opponent's Summons or abilities.[[br]] "
            + "《Lightning》《Lightning》: Gilgamesh gains +1000 power until the end of the turn.";

    @Test
    void gilgameshNamedFieldAbilityBlocksOnlyOpponentReturnToHand() {
        CardData gilgamesh = makeForwardWithText("Gilgamesh", "Lightning", 4, 8000, GILGAMESH_TEXT);
        assertTrue(ActionResolver.hasCannotBeReturnedToHandByOppFieldAbility(gilgamesh));

        MainWindow mw = new MainWindow();
        mw.gameState.getIdentity().put(gilgamesh, true);
        mw.placeCardInForwardZone(gilgamesh);

        // Opponent (P2) attempts the return — must be prevented.
        mw.buildGameContext(false).returnP1ForwardToHand(0);
        assertEquals(1, mw.p1ForwardCards.size(), "Gilgamesh must still be on the field");
        assertTrue(mw.gameState.getP1Hand().isEmpty());

        // The controller's own effect may still return it.
        mw.buildGameContext(true).returnP1ForwardToHand(0);
        assertTrue(mw.p1ForwardCards.isEmpty(), "own effects may return Gilgamesh to hand");
        assertEquals(1, mw.gameState.getP1Hand().size());
    }

    private static final String RITZ_TEXT =
            "Characters you control cannot be returned to their owner's hand by your opponent's "
            + "Summons or abilities. [[br]] If you control Card Name Shara, Ritz gains +2000 power.";

    @Test
    void ritzBlanketFieldAbilityProtectsAllOwnCharacters() {
        CardData ritz = makeForwardWithText("Ritz", "Wind", 4, 7000, RITZ_TEXT);
        assertTrue(ActionResolver.hasCharactersCannotBeReturnedFieldAbility(ritz));

        MainWindow mw = new MainWindow();
        CardData ally = makeForward("Ally", "Wind", 2, 5000);
        mw.gameState.getIdentity().put(ritz, true);
        mw.gameState.getIdentity().put(ally, true);
        mw.placeCardInForwardZone(ritz);   // P1 idx 0
        mw.placeCardInForwardZone(ally);   // P1 idx 1

        // Opponent cannot return the ally while Ritz is on the field.
        mw.buildGameContext(false).returnP1ForwardToHand(1);
        assertEquals(2, mw.p1ForwardCards.size(), "ally must still be on the field");

        // P2's own characters are unaffected by P1's Ritz.
        CardData oppFwd = makeForward("Opp Forward", "Fire", 2, 5000);
        mw.gameState.getIdentity().put(oppFwd, false);
        mw.placeP2CardInForwardZone(oppFwd);
        mw.buildGameContext(true).returnP2ForwardToHand(0);
        assertTrue(mw.p2ForwardCards.isEmpty(), "P1 may still return P2's characters");
    }

    private static final String BLACK_TORTOISE_TEXT =
            "Brave[[br]]   Black Tortoise l'Cie Gilgamesh cannot become dull by your opponent's "
            + "Summons or abilities, cannot be returned to its owner's hand by your opponent's Summons "
            + "or abilities, and cannot be put into the Break Zone by your opponent's Summons or "
            + "abilities (If Black Tortoise l'Cie Gilgamesh is broken, put it into the Break Zone).";

    @Test
    void blackTortoiseCompoundClausesSplitIntoIndividualFieldAbilities() {
        List<FieldAbility> fas = CardData.parseFieldAbilities(BLACK_TORTOISE_TEXT, "Forward");
        assertEquals(3, fas.size(), "the compound sentence must split into three individual clauses: " + fas);

        CardData tortoise = makeForwardWithText("Black Tortoise l'Cie Gilgamesh", "Earth", 5, 9000, BLACK_TORTOISE_TEXT);
        assertTrue(ActionResolver.hasCannotBeDulledByOppFieldAbility(tortoise));
        assertTrue(ActionResolver.hasCannotBeReturnedToHandByOppFieldAbility(tortoise));
        assertTrue(ActionResolver.hasCannotBePutIntoBzByOppFieldAbility(tortoise));
    }

    @Test
    void blackTortoiseProtectionsBlockOpponentDullReturnAndBreak() {
        CardData tortoise = makeForwardWithText("Black Tortoise l'Cie Gilgamesh", "Earth", 5, 9000, BLACK_TORTOISE_TEXT);
        MainWindow mw = new MainWindow();
        mw.gameState.getIdentity().put(tortoise, true);
        mw.placeCardInForwardZone(tortoise);

        GameContext opp = mw.buildGameContext(false);
        opp.dullP1Forward(0);
        assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0), "opponent's effects cannot dull it");
        opp.returnP1ForwardToHand(0);
        assertEquals(1, mw.p1ForwardCards.size(), "opponent's effects cannot return it to hand");
        opp.breakP1Forward(0);
        assertEquals(1, mw.p1ForwardCards.size(), "opponent's effects cannot put it into the Break Zone");

        // The controller's own effects are unrestricted.
        GameContext own = mw.buildGameContext(true);
        own.dullP1Forward(0);
        assertEquals(CardState.DULL, mw.p1ForwardStates.get(0), "own effects may still dull it");
        own.breakP1Forward(0);
        assertTrue(mw.p1ForwardCards.isEmpty(), "own effects may still break it");
    }

    private static final String EXODUS_TEXT =
            "[[ex]]EX BURST[[/]] Choose 1 Forward you control. Until the end of the turn, it gains "
            + "+3000 power, Brave and \"This Forward cannot become dull by your opponent's Summons or "
            + "abilities.\" and \"This Forward cannot be returned to its owner's hand by your opponent's "
            + "Summons or abilities.\" If your opponent has received 5 points of damage or more, all the "
            + "Forwards you control gain all previous effects instead.";

    @Test
    void exodusBuffsSingleForwardBelowDamageThreshold() {
        Consumer<GameContext> fn = ActionResolver.parse(EXODUS_TEXT, null);
        assertNotNull(fn, "Exodus's EX Burst should parse");

        GameContext ctx = mock(GameContext.class);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.opponentDamageCount()).thenReturn(4);
        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));
        fn.accept(ctx);

        verify(ctx).boostTarget(t, 3000, java.util.EnumSet.of(
                CardData.Trait.BRAVE,
                CardData.Trait.CANNOT_BE_DULLED_BY_OPP,
                CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP));
        verify(ctx, never()).applyMassFieldPowerBoost(anyInt(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyBoolean(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void exodusBuffsAllOwnForwardsAtDamageThreshold() {
        Consumer<GameContext> fn = ActionResolver.parse(EXODUS_TEXT, null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        when(ctx.opponentDamageCount()).thenReturn(5);
        fn.accept(ctx);

        verify(ctx).applyMassFieldPowerBoost(3000, true, false, false, true, null, -1, null, null, null);
        verify(ctx).applyMassFieldKeywordGrant(java.util.EnumSet.of(
                CardData.Trait.BRAVE,
                CardData.Trait.CANNOT_BE_DULLED_BY_OPP,
                CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP),
                true, false, false, true, null, -1, null, null);
        verify(ctx, never()).boostTarget(any(), anyInt(), any());
    }

    private static final String ASURA_TEXT =
            "Activate all the Forwards you control. Until the end of the turn, all the Forwards you "
            + "control gain \"This Forward cannot be returned to its owner's hand by your opponent's "
            + "Summons or abilities.\" and \"The power of this Forward cannot be decreased by your "
            + "opponent's Summons or abilities.\"";

    @Test
    void asuraActivatesAllAndGrantsReturnAndPowerDecreaseProtection() {
        Consumer<GameContext> fn = ActionResolver.parse(ASURA_TEXT, null);
        assertNotNull(fn, "Asura's effect should parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).applyMassFieldEffect(GameContext.MassAction.ACTIVATE, true, false, false, false, true,
                null, -1, null, -1, null, null);
        verify(ctx).applyMassFieldKeywordGrant(java.util.EnumSet.of(
                CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP,
                CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP),
                true, false, false, true, null, -1, null, null);
    }

    @Test
    void grantedTraitsBlockOpponentReturnAndPowerDecreaseUntilEot() {
        MainWindow mw = new MainWindow();
        CardData fwd = makeForward("Warrior of Light", "Light", 4, 7000);
        mw.gameState.getIdentity().put(fwd, true);
        mw.placeCardInForwardZone(fwd);
        mw.p1ForwardTempTraits.get(0).add(CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP);
        mw.p1ForwardTempTraits.get(0).add(CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP);

        GameContext opp = mw.buildGameContext(false);
        opp.returnP1ForwardToHand(0);
        assertEquals(1, mw.p1ForwardCards.size(), "granted trait must block the opponent's return");

        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);
        opp.boostTarget(t, -2000, java.util.EnumSet.noneOf(CardData.Trait.class));
        assertEquals(0, mw.p1ForwardPowerBoost.get(0),
                "granted trait must block the opponent's power decrease");

        // The controller's own debuff still applies (protection is opponent-only).
        mw.buildGameContext(true).boostTarget(t, -2000, java.util.EnumSet.noneOf(CardData.Trait.class));
        assertEquals(-2000, mw.p1ForwardPowerBoost.get(0));
    }

    // =========================================================================================
    // Cecil: "When Cecil enters the field, if you have a Card Name Cecil with Job Dark Knight
    // in your Break Zone, draw 1 card." — the "if you have a Card Name X with Job Y in your
    // Break Zone" prefix becomes a bzConditionCard + bzConditionJob firing gate.
    // =========================================================================================

    private static final String CECIL_BZ_COND_TEXT =
            "When Cecil enters the field, if you have a Card Name Cecil with Job Dark Knight "
            + "in your Break Zone, draw 1 card.";

    @Test
    void cecilBzNameAndJobConditionParsesAsFiringGate() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(CECIL_BZ_COND_TEXT);
        assertEquals(1, autos.size());
        AutoAbility fa = autos.get(0);
        assertEquals("enters the field", fa.trigger());
        assertEquals("Cecil", fa.triggerCard());
        assertEquals("Cecil", fa.bzConditionCard());
        assertEquals("Dark Knight", fa.bzConditionJob());
        assertEquals("draw 1 card.", fa.effectText());
        assertNotNull(ActionResolver.parse(fa.effectText(), null), "stripped effect should parse");
    }

    @Test
    void bzNameConditionWithoutJobLeavesJobEmpty() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(
                "When Cecil enters the field, if you have a Card Name Golbez in your Break Zone, draw 1 card.");
        assertEquals(1, autos.size());
        AutoAbility fa = autos.get(0);
        assertEquals("Golbez", fa.bzConditionCard());
        assertEquals("", fa.bzConditionJob());
        assertEquals("draw 1 card.", fa.effectText());
    }

    // =========================================================================================
    // Yugiri: "If your opponent doesn't control Forwards, Yugiri gains Haste." — a conditional
    // field boost gated on the opponent controlling exactly zero Forwards.
    // =========================================================================================

    private static final String YUGIRI_TEXT =
            "If your opponent doesn't control Forwards, Yugiri gains Haste.";

    @Test
    void yugiriNoOpponentForwardsHasteParsesAsIfControlBoost() {
        List<IfControlBoost> boosts = CardData.parseIfControlBoosts(YUGIRI_TEXT, "Forward");
        assertEquals(1, boosts.size());
        IfControlBoost icb = boosts.get(0);
        assertEquals("Yugiri", icb.targetCardName());
        assertEquals(Set.of(CardData.Trait.HASTE), icb.grantedTraits());
        assertEquals(0, icb.powerBonus());
        assertEquals(1, icb.conditions().size());
        ControlCondition cond = icb.conditions().get(0);
        assertTrue(cond.opponentControls(), "condition must check the opponent's field");
        assertTrue(cond.exactCount(), "condition must be an exact-count check");
        assertEquals(0, cond.minCount(), "condition must require exactly zero Forwards");
        assertEquals("Forward", cond.cardType());
    }

    @Test
    void yugiriHasteConditionTracksOpponentForwardCount() {
        IfControlBoost icb = CardData.parseIfControlBoosts(YUGIRI_TEXT, "Forward").get(0);
        MainWindow mw = new MainWindow();
        assertTrue(mw.icbConditionsMet(icb, true), "no opponent Forwards — condition met");
        mw.placeP2CardInForwardZone(makeForward("Amon", "Lightning", 3, 7000));
        assertFalse(mw.icbConditionsMet(icb, true), "opponent Forward present — condition unmet");
    }

    // =========================================================================================
    // Other "If your opponent doesn't control …" instances. Famed Mimic Gogo's self-break and
    // King/Queen of Eblan's attack restriction ride pre-existing ActionResolver machinery;
    // Kelger's cost-qualified variant extends the IfControlBoost condition with a minCost filter.
    // =========================================================================================

    /** Builds a Forward whose fieldAbilities are parsed from {@code text}. */
    private static CardData makeFieldAbilityForward(String name, String text) {
        return makeFieldAbilityCard(name, "Fire", "Forward", text);
    }

    /** Builds a card of the given type/element whose fieldAbilities are parsed from {@code text}. */
    private static CardData makeFieldAbilityCard(String name, String element, String type, String text) {
        return new CardData(null, name, element, 3, 7000, type, false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), CardData.parseFieldAbilities(text, type),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, text);
    }

    /** Builds a Forward whose scalingSelfPowerBoosts (and fieldAbilities) are parsed from {@code text}. */
    private static CardData makeScalingSelfForward(String name, String element, int power, String text) {
        return new CardData(null, name, element, 3, power, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), CardData.parseFieldAbilities(text, "Forward"),
                List.of(), List.of(), CardData.parseScalingSelfPowerBoosts(text, "Forward", name),
                List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, text);
    }

    @Test
    void famedMimicGogoSelfBreakParsesWithSourceCard() {
        CardData gogo = makeForward("Famed Mimic Gogo", "Fire", 5, 9000);
        assertNotNull(ActionResolver.parse(
                "If your opponent doesn't control Forwards, put Famed Mimic Gogo into the Break Zone.", gogo),
                "Gogo's conditional self-break should be recognized");
        // Verbiage variant: "any Forwards" must parse the same way
        assertNotNull(ActionResolver.parse(
                "If your opponent doesn't control any Forwards, put Famed Mimic Gogo into the Break Zone.", gogo),
                "the 'any Forwards' wording should also be recognized");
    }

    @Test
    void kingOfEblanCannotAttackGatesOnOpponentForwards() {
        String text = "If your opponent doesn't control any Forwards, King of Eblan cannot attack.";
        CardData king = makeFieldAbilityForward("King of Eblan", text);
        assertNotNull(ActionResolver.parse(text, king), "restriction sentence should be recognized");

        MainWindow mw = new MainWindow();
        assertTrue(mw.isFieldAbilityCannotAttack(king, true), "no opponent Forwards — cannot attack");
        mw.placeP2CardInForwardZone(makeForward("Amon", "Lightning", 3, 7000));
        assertFalse(mw.isFieldAbilityCannotAttack(king, true), "opponent Forward present — attack allowed");

        // Verbiage variant: without "any" must gate the same way
        CardData queen = makeFieldAbilityForward("Queen of Eblan",
                "If your opponent doesn't control Forwards, Queen of Eblan cannot attack.");
        MainWindow mw2 = new MainWindow();
        assertTrue(mw2.isFieldAbilityCannotAttack(queen, true),
                "the wording without 'any' should also be recognized");
    }

    private static final String KELGER_TEXT =
            "If your opponent doesn't control a Forward of cost 5 or more, "
            + "Kelger gains Haste, First Strike, and Brave.";

    @Test
    void kelgerCostQualifiedBoostParsesWithMinCostCondition() {
        List<IfControlBoost> boosts = CardData.parseIfControlBoosts(KELGER_TEXT, "Forward");
        assertEquals(1, boosts.size());
        IfControlBoost icb = boosts.get(0);
        assertEquals("Kelger", icb.targetCardName());
        assertEquals(Set.of(CardData.Trait.HASTE, CardData.Trait.FIRST_STRIKE, CardData.Trait.BRAVE),
                icb.grantedTraits());
        assertEquals(1, icb.conditions().size());
        ControlCondition cond = icb.conditions().get(0);
        assertTrue(cond.opponentControls());
        assertTrue(cond.exactCount());
        assertEquals(0, cond.minCount());
        assertEquals(5, cond.minCost(), "cost qualifier must carry into the condition");
        assertEquals("Forward", cond.cardType());
    }

    @Test
    void kelgerConditionIgnoresCheapOpponentForwards() {
        IfControlBoost icb = CardData.parseIfControlBoosts(KELGER_TEXT, "Forward").get(0);
        MainWindow mw = new MainWindow();
        assertTrue(mw.icbConditionsMet(icb, true), "empty opponent field — condition met");
        mw.placeP2CardInForwardZone(makeForward("Cheap", "Fire", 3, 5000));
        assertTrue(mw.icbConditionsMet(icb, true), "cost-3 Forward doesn't break the condition");
        mw.placeP2CardInForwardZone(makeForward("Big", "Fire", 5, 9000));
        assertFalse(mw.icbConditionsMet(icb, true), "cost-5 Forward breaks the condition");
    }

    // =========================================================================================
    // Tilika / Spiritus: "You can discard [Light and Dark|Dark] Element cards from your hand
    // to produce CP." — a field-wide payment grant handled as a static card property.
    // =========================================================================================

    private static final String TILIKA_DISCARD_CP_TEXT =
            "You can discard Light and Dark Element cards from your hand to produce CP. "
            + "(Light cards produce 2 Light CP each and Dark cards produce 2 Dark CP each.)";

    @Test
    void tilikaLightAndDarkDiscardCpGrantParses() {
        CardData tilika = makeFieldAbilityForward("Tilika", TILIKA_DISCARD_CP_TEXT);
        assertEquals(List.of("Light", "Dark"), tilika.grantsLightDarkDiscardCp());
        // The segment must count as recognized so it doesn't show as an unparsed field ability
        assertNotNull(ActionResolver.parse(TILIKA_DISCARD_CP_TEXT, tilika),
                "discard-for-CP grant should be recognized as a no-op field ability");
    }

    /** Boss card variation: Dark-only grant embedded among Spiritus's other field abilities. */
    private static final String SPIRITUS_TEXT =
            "Spiritus cannot leave the field due to your opponent's Summons or abilities.[[br]]   "
            + "You can play 2 or more Dark Characters onto the field.[[br]]   "
            + "If Spiritus is on the field, Spiritus can produce CP of any Element. [[br]]   "
            + "You can discard Dark Element cards from your hand to produce CP. "
            + "(Dark cards produce 2 Dark CP each.)";

    @Test
    void spiritusDarkOnlyDiscardCpGrantParsesAmongOtherSegments() {
        CardData spiritus = makeFieldAbilityForward("Spiritus", SPIRITUS_TEXT);
        assertEquals(List.of("Dark"), spiritus.grantsLightDarkDiscardCp());
        assertEquals("Dark", spiritus.grantsMultiLightDarkPlay(),
                "sibling multi-play grant segment must still parse");
    }

    @Test
    void hypotheticalLightOnlyDiscardCpGrantParses() {
        CardData card = makeFieldAbilityForward("Materia",
                "You can discard Light Element cards from your hand to produce CP. "
                + "(Light cards produce 2 Light CP each.)");
        assertEquals(List.of("Light"), card.grantsLightDarkDiscardCp());
    }

    @Test
    void noDiscardCpGrantYieldsEmptyList() {
        CardData plain = makeForward("Amon", "Lightning", 3, 7000);
        assertEquals(List.of(), plain.grantsLightDarkDiscardCp());
    }

    // =========================================================================================
    // Wiring: the discard-for-CP grant feeds payment eligibility and affordability.
    // =========================================================================================

    @Test
    void canDiscardForCpGatesLightDarkOnGrant() {
        CardData light = makeForward("Rem", "Light", 3, 7000);
        CardData fire  = makeForward("Ifrit", "Fire", 3, 7000);
        assertTrue(CpPaymentUtils.canDiscardForCp(fire, Set.of()), "non-L/D always discardable");
        assertFalse(CpPaymentUtils.canDiscardForCp(light, Set.of()), "Light blocked without grant");
        assertFalse(CpPaymentUtils.canDiscardForCp(light, Set.of("Dark")), "Dark-only grant doesn't cover Light");
        assertTrue(CpPaymentUtils.canDiscardForCp(light, Set.of("Light", "Dark")), "grant covers Light");
    }

    @Test
    void lightDarkDiscardGrantsCollectFromControlledFieldCards() {
        MainWindow mw = new MainWindow();
        assertEquals(Set.of(), mw.lightDarkDiscardGrants(true), "no grant by default");

        CardData tilika = makeFieldAbilityForward("Tilika", TILIKA_DISCARD_CP_TEXT);
        mw.p1ForwardCards.add(tilika);
        assertEquals(Set.of("Light", "Dark"), mw.lightDarkDiscardGrants(true));
        assertEquals(Set.of(), mw.lightDarkDiscardGrants(false), "P1's grant doesn't apply to P2");

        mw.p2BackupCards[0] = makeFieldAbilityForward("Spiritus", SPIRITUS_TEXT);
        assertEquals(Set.of("Dark"), mw.lightDarkDiscardGrants(false), "Spiritus grants Dark to P2");

        // A card that has lost its abilities stops granting
        mw.lostAbilitiesCards.add(tilika);
        assertEquals(Set.of(), mw.lightDarkDiscardGrants(true));
    }

    @Test
    void tilikaGrantMakesLightHandCardCountTowardAffordability() {
        MainWindow mw = new MainWindow();
        CardData earthCast = makeForward("Enkidu", "Earth", 4, 7000);
        List<CardData> hand = mw.gameState.getP1Hand();
        hand.add(earthCast);                                // idx 0 — the card being cast
        hand.add(makeForward("Gaia", "Earth", 2, 5000));    // idx 1 — 2 CP, satisfies Earth
        hand.add(makeForward("Rem", "Light", 2, 5000));     // idx 2 — Light, needs the grant
        assertFalse(mw.canAffordCard(earthCast, 0), "only 2 CP available without the grant");

        mw.p1ForwardCards.add(makeFieldAbilityForward("Tilika", TILIKA_DISCARD_CP_TEXT));
        assertTrue(mw.canAffordCard(earthCast, 0), "Light discard adds 2 CP once granted");
    }

    @Test
    void spiritusGrantMakesDarkHandCardCountForDarkCast() {
        MainWindow mw = new MainWindow();
        CardData darkCast = makeForward("Cloud of Darkness", "Dark", 4, 9000);
        List<CardData> hand = mw.gameState.getP1Hand();
        hand.add(darkCast);                                     // idx 0 — the card being cast
        hand.add(makeForward("Nightmare", "Dark", 2, 5000));    // idx 1 — Dark, needs the grant
        hand.add(makeForward("Ifrit", "Fire", 2, 5000));        // idx 2 — 2 CP toward L/D cast
        assertFalse(mw.canAffordCard(darkCast, 0), "only 2 CP available without the grant");

        mw.p1BackupCards[0] = makeFieldAbilityForward("Spiritus", SPIRITUS_TEXT);
        assertTrue(mw.canAffordCard(darkCast, 0), "Dark discard adds 2 CP once granted");
    }

    // =========================================================================================
    // P2 payment planner: off-color hand discards act as generic filler (Phase 2b), matching
    // the engine's rules model where only per-element minimums need matching sources.
    // =========================================================================================

    @Test
    void p2PlannerUsesOffColorDiscardsAsGenericFiller() {
        MainWindow mw = new MainWindow();
        ComputerPlayer cpu = new ComputerPlayer(mw);
        CardData darkCast = makeForward("Nightmare", "Dark", 2, 5000);
        List<CardData> hand = mw.gameState.getP2Hand();
        hand.add(darkCast);                                 // idx 0 — the card being cast
        hand.add(makeForward("Ifrit", "Fire", 3, 7000));    // idx 1 — off-color filler
        List<Integer> backups = new ArrayList<>();
        Map<Integer, String> backupElems = new LinkedHashMap<>();
        List<Integer> discards = new ArrayList<>();
        Map<Integer, String> discardElems = new LinkedHashMap<>();
        assertTrue(cpu.p2PlanPayment(darkCast, 2, 0, -1, backups, backupElems, discards, discardElems),
                "off-color Fire discard covers the Dark cast as generic filler");
        assertEquals(List.of(1), discards);
        assertEquals("Dark", discardElems.get(1), "off-color CP deposits into the cast element bucket");
    }

    @Test
    void p2PlannerOffColorDiscardsCannotSatisfyPerElementMinimums() {
        MainWindow mw = new MainWindow();
        ComputerPlayer cpu = new ComputerPlayer(mw);
        CardData dual = makeForward("Fusoya", "Fire/Ice", 2, 7000);
        List<CardData> hand = mw.gameState.getP2Hand();
        hand.add(dual);                                          // idx 0 — the card being cast
        hand.add(makeForward("Ramuh", "Lightning", 3, 7000));    // off-color only
        hand.add(makeForward("Ixion", "Lightning", 3, 7000));
        List<Integer> backups = new ArrayList<>();
        Map<Integer, String> backupElems = new LinkedHashMap<>();
        List<Integer> discards = new ArrayList<>();
        Map<Integer, String> discardElems = new LinkedHashMap<>();
        assertFalse(cpu.p2PlanPayment(dual, 2, 0, -1, backups, backupElems, discards, discardElems),
                "a Fire/Ice cast still needs 1 Fire and 1 Ice from matching sources");
    }

    // =========================================================================================
    // Gladiolus / Foulander: "If [self] deals damage to a Forward, the damage increases by 2000
    // instead." — a self, unconditional outgoing flat boost, optionally behind a "Damage N --"
    // threshold. Applies to both combat and ability damage the source deals to a Forward.
    // =========================================================================================

    private static final String GLADIOLUS_BOOST_TEXT =
            "If Gladiolus deals damage to a Forward, the damage increases by 2000 instead.";

    /** Foulander's full text: the boost segment sits behind a Damage-3 threshold, among siblings. */
    private static final String FOULANDER_TEXT =
            "During your turn, Foulander also becomes a Forward with 4000 power.[[br]]   "
            + "When Foulander attacks, choose 1 Forward opponent controls. Deal it 3000 damage.[[br]]   "
            + "Damage 3 -- If Foulander deals damage to a Forward, the damage increases by 2000 instead.";

    /** Returns the FieldAbility whose text is the self outgoing-flat-boost, or null. */
    private static FieldAbility findOutgoingFlatBoost(CardData card) {
        for (FieldAbility fa : card.fieldAbilities())
            if (AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST.matcher(fa.effectText()).find()) return fa;
        return null;
    }

    @Test
    void gladiolusOutgoingBoostParsesUnconditional() {
        CardData gladiolus = makeFieldAbilityForward("Gladiolus", GLADIOLUS_BOOST_TEXT);
        FieldAbility fa = findOutgoingFlatBoost(gladiolus);
        assertNotNull(fa, "self outgoing flat boost should parse");
        assertEquals(0, fa.damageThreshold(), "no Damage-N prefix — always active");
        java.util.regex.Matcher m = AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST.matcher(fa.effectText());
        assertTrue(m.find());
        assertEquals("Gladiolus", m.group("card").trim());
        assertEquals("2000", m.group("amount"));
    }

    @Test
    void foulanderOutgoingBoostCarriesDamageThresholdAmongSiblings() {
        CardData foulander = makeFieldAbilityForward("Foulander", FOULANDER_TEXT);
        FieldAbility fa = findOutgoingFlatBoost(foulander);
        assertNotNull(fa, "the boost segment should parse out from the sibling abilities");
        assertEquals(3, fa.damageThreshold(), "the 'Damage 3 --' prefix must carry onto the boost");
    }

    @Test
    void grantFormsDoNotMatchSelfBoostPattern() {
        // "a Fire Character you control" and "your Fire Summon or ..." are field-wide grants, not
        // self boosts — they name no specific card and must be left to their own handlers.
        assertFalse(AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST.matcher(
                "If a Fire Character you control deals damage to a Forward, the damage increases by 1000 instead.").find(),
                "friendly-Character grant must not match the self pattern");
        assertFalse(AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST.matcher(
                "If your Fire Summon or a Fire Character you control deals damage to a Forward, the damage increases by 2000 instead.").find(),
                "Summon/Character grant must not match the self pattern");
        // The cost-qualified self variant is a different pattern and must not match this one either.
        assertFalse(AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST.matcher(
                "If Gilgamesh deals damage to a Forward of cost 5 or more, the damage increases by 2000 instead.").find(),
                "cost-qualified variant belongs to FA_OUTGOING_FLAT_BOOST_VS_COST");
    }

    // Vincent 23-119R: "you may put 1 Fire Backup you control into the Break Zone. When you do so,
    // choose 1 Forward opponent controls. Deal it 9000 damage." The element qualifier used to leave
    // FA_PUT_INTO_BZ_WHEN_DO_SO unmatched, so dispatch fell through to the self-break pattern, whose
    // card-name group swallowed "1 Fire Backup you control" and then rejected it for not naming Vincent.
    private static final String VINCENT_PUT_FIRE_BACKUP =
            "put 1 Fire Backup you control into the Break Zone. When you do so, "
            + "choose 1 Forward opponent controls. Deal it 9000 damage.";

    @Test
    void putIntoBreakZoneAcceptsElementQualifiedType() {
        java.util.regex.Matcher m = AutoAbilityTriggers.FA_PUT_INTO_BZ_WHEN_DO_SO.matcher(VINCENT_PUT_FIRE_BACKUP);
        assertTrue(m.find(), "\"1 Fire Backup you control\" should match the put-into-Break-Zone pattern");
        assertEquals("1", m.group("count"));
        assertEquals("Fire", m.group("element"));
        assertEquals("Backup", m.group("type"));
        assertEquals("choose 1 Forward opponent controls. Deal it 9000 damage.", m.group("sub").trim());
    }

    // The bug was a misdispatch, so the ordering that produced it has to stay pinned: the generic
    // pattern must claim this text before the self-break pattern is ever consulted.
    @Test
    void elementQualifiedPutIntoBreakZoneIsNotClaimedBySelfBreak() {
        assertTrue(AutoAbilityTriggers.FA_PUT_INTO_BZ_WHEN_DO_SO.matcher(VINCENT_PUT_FIRE_BACKUP).find(),
                "generic put-into-BZ is checked first and must match");
        java.util.regex.Matcher selfM =
                AutoAbilityTriggers.FA_PUT_SELF_INTO_BZ_IF_DO_SO.matcher(VINCENT_PUT_FIRE_BACKUP);
        assertTrue(selfM.find(), "the self-break pattern still matches this shape — hence the ordering");
        assertEquals("1 Fire Backup you control", selfM.group("cardname"),
                "documents the wrong-handler capture the ordering exists to prevent");
    }

    @Test
    void putIntoBreakZoneStillMatchesUnqualifiedAndJobForms() {
        java.util.regex.Matcher plain = AutoAbilityTriggers.FA_PUT_INTO_BZ_WHEN_DO_SO.matcher(
                "put 1 Character you control into the Break Zone. When you do so, draw 1 card.");
        assertTrue(plain.find(), "the optional element must not break the unqualified form");
        assertNull(plain.group("element"));
        assertEquals("Character", plain.group("type"));

        java.util.regex.Matcher job = AutoAbilityTriggers.FA_PUT_INTO_BZ_WHEN_DO_SO.matcher(
                "put 1 Job Kingsglaive you control into the Break Zone. When you do so, draw 1 card.");
        assertTrue(job.find(), "the Job branch must still win over the element-qualified type branch");
        assertEquals("Kingsglaive", job.group("job").trim());
        assertNull(job.group("type"));
    }

    @Test
    void selfOutgoingBoostHelperRespectsNameLostAbilitiesAndThreshold() {
        MainWindow mw = new MainWindow();
        CardData gladiolus = makeFieldAbilityForward("Gladiolus", GLADIOLUS_BOOST_TEXT);
        assertEquals(2000, mw.selfOutgoingFlatBoostVsForward(gladiolus, true), "unconditional boost applies");

        mw.lostAbilitiesCards.add(gladiolus);
        assertEquals(0, mw.selfOutgoingFlatBoostVsForward(gladiolus, true), "no boost while abilities are lost");

        // A card that doesn't own the ability text gets nothing (name guard).
        assertEquals(0, mw.selfOutgoingFlatBoostVsForward(makeForward("Amon", "Fire", 3, 7000), true));

        // Foulander's boost is gated on 3 damage in its controller's Damage Zone.
        CardData foulander = makeFieldAbilityForward("Foulander", FOULANDER_TEXT);
        assertEquals(0, mw.selfOutgoingFlatBoostVsForward(foulander, true), "no damage yet — gated off");
        List<CardData> dmgZone = mw.gameState.getP1DamageZone();
        dmgZone.add(makeForward("D1", "Fire", 1, 1000));
        dmgZone.add(makeForward("D2", "Fire", 1, 1000));
        assertEquals(0, mw.selfOutgoingFlatBoostVsForward(foulander, true), "2 damage — still gated off");
        dmgZone.add(makeForward("D3", "Fire", 1, 1000));
        assertEquals(2000, mw.selfOutgoingFlatBoostVsForward(foulander, true), "3 damage — boost active");
    }

    @Test
    void gladiolusCombatDamageToForwardIsBoosted() {
        MainWindow mw = new MainWindow();
        CardData gladiolus = makeFieldAbilityForward("Gladiolus", GLADIOLUS_BOOST_TEXT);
        mw.placeCardInForwardZone(gladiolus); // P1 idx 0
        CardData target = makeForward("Amon", "Lightning", 3, 7000);
        assertEquals(10000, mw.modifyOutgoingCombatDamage(true, 0, 8000, target),
                "combat damage to a Forward gains +2000");
        // No target Forward (e.g. direct opponent damage) → the vs-Forward boost doesn't apply.
        assertEquals(8000, mw.modifyOutgoingCombatDamage(true, 0, 8000, null),
                "boost is scoped to dealing damage to a Forward");
    }

    // =========================================================================================
    // Monster/Backup acting as a Forward: a card acting as a Forward is a Forward for every
    // eligible purpose, so combat damage modifiers (self boost, doubler) and incoming-damage
    // protections now apply to it via the zone-aware combat pipeline.
    // =========================================================================================

    @Test
    void monsterAsForwardCombatDamageGetsSelfBoost() {
        MainWindow mw = new MainWindow();
        // Foulander is a Monster; its boost sits behind a Damage-3 threshold.
        CardData foulander = makeFieldAbilityCard("Foulander", "Fire", "Monster", FOULANDER_TEXT);
        mw.p1MonsterCards.add(foulander);
        mw.p1MonsterStates.add(CardState.ACTIVE);
        mw.p1MonsterDamage.add(0);
        CardData target = makeForward("Amon", "Lightning", 3, 7000);

        // Below threshold: no boost.
        assertEquals(4000, mw.modifyOutgoingCombatDamage(true, ForwardTarget.CardZone.MONSTER, 0, 4000, target),
                "monster-as-forward with <3 damage deals unmodified combat damage");

        // At threshold: the self boost applies to its combat damage, just like a real Forward.
        List<CardData> dz = mw.gameState.getP1DamageZone();
        dz.add(makeForward("D1", "Fire", 1, 1000));
        dz.add(makeForward("D2", "Fire", 1, 1000));
        dz.add(makeForward("D3", "Fire", 1, 1000));
        assertEquals(6000, mw.modifyOutgoingCombatDamage(true, ForwardTarget.CardZone.MONSTER, 0, 4000, target),
                "monster-as-forward combat damage now gains +2000");
    }

    @Test
    void backupAsForwardCombatDamageGetsOutgoingDoubler() {
        MainWindow mw = new MainWindow();
        // A backup acting as a Forward with the generic outgoing doubler.
        CardData bruiser = makeFieldAbilityCard("Bruiser", "Fire", "Backup",
                "If Bruiser deals damage to a Forward or your opponent, double the damage instead.");
        mw.p1BackupCards[0] = bruiser;
        mw.p1BackupStates[0] = CardState.ACTIVE;
        CardData target = makeForward("Amon", "Lightning", 3, 7000);
        assertEquals(6000, mw.modifyOutgoingCombatDamage(true, ForwardTarget.CardZone.BACKUP, 0, 3000, target),
                "backup-as-forward combat damage is doubled by its own field ability");
    }

    @Test
    void backupAsForwardReceivesSelfIncomingReduction() {
        MainWindow mw = new MainWindow();
        // A backup acting as a Forward with a self incoming-damage reduction.
        CardData ward = makeFieldAbilityCard("Ward", "Earth", "Backup",
                "If Ward is dealt damage by a Forward, reduce the damage by 3000 instead.");
        mw.p1BackupCards[0] = ward;
        mw.p1BackupStates[0] = CardState.ACTIVE;
        // Combat damage (fromAbility=false) to the backup-as-forward is reduced.
        assertEquals(5000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.BACKUP, 0, 8000, false, false),
                "incoming combat damage to a backup-as-forward is reduced by its field ability");
    }

    @Test
    void forwardZoneCombatDamageUnchangedByZoneAwareRefactor() {
        // Regression: routing through the zone-aware path must not change real Forward-vs-Forward.
        MainWindow mw = new MainWindow();
        CardData gladiolus = makeFieldAbilityForward("Gladiolus", GLADIOLUS_BOOST_TEXT);
        mw.placeCardInForwardZone(gladiolus);
        CardData target = makeForward("Amon", "Lightning", 3, 7000);
        assertEquals(10000, mw.modifyOutgoingCombatDamage(true, ForwardTarget.CardZone.FORWARD, 0, 8000, target));
        assertEquals(10000, mw.modifyOutgoingCombatDamage(true, 0, 8000, target),
                "the FORWARD overload matches the explicit-zone call");
    }

    // =========================================================================================
    // Breaktouch parity: "deals damage to a Forward, break it" and battle Breaktouch resolve
    // against a Monster/Backup acting as a Forward, not only real Forwards.
    // =========================================================================================

    /** Builds a Forward whose autoAbilities are parsed from {@code text}. */
    private static CardData makeAutoAbilityForward(String name, String text) {
        return new CardData(null, name, "Fire", 3, 7000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), CardData.parseAutoAbilities(text), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, text);
    }

    @Test
    void breaktouchBreaksBackupActingAsForward() {
        MainWindow mw = new MainWindow();
        CardData slayer = makeAutoAbilityForward("Slayer", "When Slayer deals damage to a Forward, break it.");
        // The damaged card is an opponent Backup acting as a Forward.
        CardData golem = makeFieldAbilityCard("Golem", "Earth", "Backup", "");
        mw.gameState.getIdentity().put(golem, false); // owned by P2
        mw.p2BackupCards[0]  = golem;
        mw.p2BackupStates[0] = CardState.ACTIVE;
        assertTrue(mw.fireBreaktouchForDamage(slayer, true, false, ForwardTarget.CardZone.BACKUP, 0),
                "'deals damage to a Forward, break it' fires against a backup acting as a Forward");
        assertNull(mw.p2BackupCards[0], "the backup-as-forward was broken");
    }

    @Test
    void breaktouchWithoutTriggerLeavesActingForwardIntact() {
        MainWindow mw = new MainWindow();
        CardData plain = makeForward("Bench", "Fire", 3, 7000); // no break trigger
        CardData golem = makeFieldAbilityCard("Golem", "Earth", "Backup", "");
        mw.gameState.getIdentity().put(golem, false); // owned by P2
        mw.p2BackupCards[0]  = golem;
        mw.p2BackupStates[0] = CardState.ACTIVE;
        assertFalse(mw.fireBreaktouchForDamage(plain, true, false, ForwardTarget.CardZone.BACKUP, 0),
                "no break trigger — nothing fires");
        assertNotNull(mw.p2BackupCards[0], "the backup-as-forward survives");
    }

    // =========================================================================================
    // Counter-conditioned grants: "Each Forward you control with a [X] Counter on it gains
    // [+N power | \"ability\"]." (Legendary Turk, Kimahri, Tidus)
    // =========================================================================================

    private static final String LEGENDARY_TURK_TEXT =
            "Each Forward you control with a Turks Counter on it gains +5000 power.[[br]]   "
            + "Discard 2 cards: Choose 1 Category VII Forward you control. Place 1 Turks Counter on it.[[br]]   "
            + "Put Legendary Turk into the Break Zone: Choose 1 Forward. Break it. "
            + "You can only use this ability if a Turks Counter is placed on Legendary Turk.";

    private static final String KIMAHRI_TEXT =
            "Each Forward you control with a Ronso Counter on it gains \"If this Forward is dealt "
            + "damage by your opponent's Summons or abilities, the damage becomes 0 instead.\"[[br]]   "
            + "When Kimahri enters the field, choose 1 Forward you control. Place 1 Ronso Counter on it.";

    private static final String TIDUS_TEXT =
            "Each Forward you control with a Guardian Counter on it gains \"If this Forward is dealt "
            + "damage by abilities, reduce the damage by 5000 instead.\"[[br]]"
            + "When Tidus enters the field, choose 1 Forward. Place 1 Guardian Counter on it and Tidus.[[br]]"
            + "《Water》《1》: All the Forwards you control gain +1000 power until the end of the turn.";

    @Test
    void legendaryTurkParsesAsCounterPowerGrant() {
        CardData turk = makeFieldAbilityCard("Legendary Turk", "Ice", "Forward", LEGENDARY_TURK_TEXT);
        List<CounterGrant> grants = turk.counterGrants();
        assertEquals(1, grants.size(), "the power grant parses out from the sibling action abilities");
        CounterGrant cg = grants.get(0);
        assertEquals("Turks", cg.counterName());
        assertEquals(5000, cg.powerBonus());
        assertNull(cg.grantedAbilityText());
    }

    @Test
    void kimahriAndTidusParseAsCounterAbilityGrants() {
        CounterGrant ronso = makeFieldAbilityCard("Kimahri", "Water", "Backup", KIMAHRI_TEXT)
                .counterGrants().get(0);
        assertEquals("Ronso", ronso.counterName());
        assertEquals(0, ronso.powerBonus());
        assertEquals("If this Forward is dealt damage by your opponent's Summons or abilities, "
                + "the damage becomes 0 instead.", ronso.grantedAbilityText());
        // The granted text must be recognized by the incoming damage-modifier parser.
        assertTrue(AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(ronso.grantedAbilityText()).find());

        CounterGrant guardian = makeFieldAbilityCard("Tidus", "Water", "Forward", TIDUS_TEXT)
                .counterGrants().get(0);
        assertEquals("Guardian", guardian.counterName());
        assertEquals("If this Forward is dealt damage by abilities, reduce the damage by 5000 instead.",
                guardian.grantedAbilityText());
        // "by abilities" (no article) must now match the incoming damage-modifier parser.
        assertTrue(AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(guardian.grantedAbilityText()).find(),
                "bare 'by abilities' should be recognized");
    }

    @Test
    void turksCounterPowerGrantAppliesOnlyToCountedForward() {
        MainWindow mw = new MainWindow();
        CardData turk    = makeFieldAbilityCard("Legendary Turk", "Ice", "Forward", LEGENDARY_TURK_TEXT);
        CardData counted = makeForward("Rufus", "Ice", 3, 7000);
        mw.placeCardInForwardZone(turk);    // P1 idx 0
        mw.placeCardInForwardZone(counted); // P1 idx 1
        assertEquals(7000, mw.effectiveP1ForwardPower(1), "no counter yet — no boost");

        mw.gameState.placeCounters(counted, "Turks", 1);
        assertEquals(12000, mw.effectiveP1ForwardPower(1), "Turks Counter → +5000");
        // A different counter type does not qualify.
        CardData other = makeForward("Reno", "Ice", 3, 6000);
        mw.placeCardInForwardZone(other);   // P1 idx 2
        mw.gameState.placeCounters(other, "Shuriken", 1);
        assertEquals(6000, mw.effectiveP1ForwardPower(2), "wrong counter type → no boost");
    }

    @Test
    void ronsoCounterGrantZeroesOpponentAbilityDamage() {
        MainWindow mw = new MainWindow();
        mw.p1BackupCards[0] = makeFieldAbilityCard("Kimahri", "Water", "Backup", KIMAHRI_TEXT);
        mw.p1BackupStates[0] = CardState.ACTIVE;
        CardData counted = makeForward("Yuna", "Water", 3, 7000);
        mw.placeCardInForwardZone(counted); // P1 idx 0

        // No counter yet: ability damage passes through unchanged.
        assertEquals(8000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 8000, true, false),
                "no Ronso Counter — damage unmodified");

        mw.gameState.placeCounters(counted, "Ronso", 1);
        assertEquals(0, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 8000, true, false),
                "Ronso Counter — ability damage becomes 0");
        // Combat damage (not from an ability/summon) is unaffected by the grant.
        assertEquals(8000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 8000, false, false),
                "grant only covers Summons/abilities, not battle damage");
    }

    @Test
    void guardianCounterGrantReducesAbilityDamageBy5000() {
        MainWindow mw = new MainWindow();
        mw.placeCardInForwardZone(makeFieldAbilityCard("Tidus", "Water", "Forward", TIDUS_TEXT)); // idx 0
        CardData counted = makeForward("Wakka", "Water", 3, 9000);
        mw.placeCardInForwardZone(counted); // idx 1
        mw.gameState.placeCounters(counted, "Guardian", 1);
        assertEquals(3000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 1, 8000, true, false),
                "Guardian Counter — ability damage reduced by 5000");
    }

    // =========================================================================================
    // Zenos: "You can cast Zenos from your Break Zone." — a self-referential Break-Zone ability
    // that registers the card as castable from the Break Zone while it sits there.
    // =========================================================================================

    private static final String ZENOS_TEXT =
            "You can cast Zenos from your Break Zone.[[br]]   "
            + "When Zenos enters the field from the Break Zone, at the end of the turn, remove Zenos from the game.";

    private static final String ACE_BZ_TEXT =
            "You can only cast up to 2 cards per turn.[[br]]   "
            + "You can cast Forwards from your Break Zone.[[br]]   "
            + "If a card is put into your Break Zone in any situation, remove it from the game instead.[[br]]   "
            + "When Ace enters the field, discard your hand.";

    @Test
    void zenosSelfCastFromBzParsesDistinctlyFromForwardsGrant() {
        CardData zenos = makeFieldAbilityCard("Zenos", "Dark", "Forward", ZENOS_TEXT);
        assertTrue(AutoAbilityTriggers.canCastSelfFromBz(zenos), "Zenos grants itself a Break-Zone cast");
        assertFalse(AutoAbilityTriggers.hasCastForwardsFromBz(zenos), "not the field-wide Forwards grant");

        // Ace's "cast Forwards from BZ" must NOT be read as a self-cast (its name isn't "Forwards").
        CardData ace = makeFieldAbilityCard("Ace", "Light", "Forward", ACE_BZ_TEXT);
        assertTrue(AutoAbilityTriggers.hasCastForwardsFromBz(ace), "Ace grants the field-wide Forwards cast");
        assertFalse(AutoAbilityTriggers.canCastSelfFromBz(ace), "Ace's grant is not a self-cast");
    }

    @Test
    void zenosRegistersAsBzPlayableWhileInBreakZoneAndPrunesWhenItLeaves() {
        MainWindow mw = new MainWindow();
        CardData zenos = makeFieldAbilityCard("Zenos", "Dark", "Forward", ZENOS_TEXT);
        CardData plain = makeForward("Grunt", "Dark", 2, 5000); // no self-cast ability

        mw.gameState.getP1BreakZone().add(zenos);
        mw.gameState.getP1BreakZone().add(plain);
        mw.syncBzSelfCastPlayables(true);

        assertTrue(mw.bzPlayableP1.containsKey(zenos), "Zenos is castable from its own Break Zone");
        assertEquals(PlayableEntry.SourceZone.BREAK_ZONE, mw.bzPlayableP1.get(zenos).source());
        assertFalse(mw.bzPlayableP1.containsKey(plain), "an ordinary Break-Zone card is not registered");

        // Once Zenos leaves the Break Zone, the entry is pruned on the next sync.
        mw.gameState.getP1BreakZone().remove(zenos);
        mw.syncBzSelfCastPlayables(true);
        assertFalse(mw.bzPlayableP1.containsKey(zenos), "entry removed after Zenos leaves the Break Zone");
    }

    // =========================================================================================
    // Forza (12-015H): "You cannot cast Forza." — an absolute cast prohibition. Forza may never be
    // cast from hand, from the Break Zone, or from removed-from-game; only effects that *put* it
    // onto the field can bring it in, at which point its enters-the-field auto ability fires.
    // =========================================================================================

    private static final String FORZA_TEXT =
            "You cannot cast Forza.[[br]]   "
            + "When Forza enters the field, choose 1 Forward opponent controls. Deal it 7000 damage.";

    private static CardData makeForza() {
        return new CardData(null, "Forza", "Fire", 3, 9000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), CardData.parseAutoAbilities(FORZA_TEXT),
                CardData.parseFieldAbilities(FORZA_TEXT, "Forward"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, FORZA_TEXT);
    }

    @Test
    void forzaCastProhibitionParsesAsAStaticRestrictionNotAFieldAbility() {
        CardData forza = makeForza();

        assertTrue(forza.castProhibited(), "\"You cannot cast Forza.\" is an absolute cast prohibition");
        CastRestriction cr = forza.castRestriction();
        assertNotNull(cr);
        assertTrue(cr.castProhibited());

        // The sentence is consumed as a static property, so it must not surface as a field ability.
        assertTrue(forza.fieldAbilities().isEmpty(),
                "the prohibition is a static property, not an unrecognized field ability");

        // The enters-the-field half still parses and resolves.
        assertEquals(1, forza.autoAbilities().size());
        AutoAbility etf = forza.autoAbilities().get(0);
        assertEquals("enters the field", etf.trigger());
        assertNotNull(ActionResolver.parse(etf.effectText(), forza),
                "the ETF damage effect is unaffected by the cast prohibition");
    }

    @Test
    void forzaCastProhibitionDoesNotLeakToSimilarWordings() {
        // Duration-scoped and global "cannot cast" wordings are not properties of their own card.
        CardData vayne = makeForwardWithText("Vayne", "Dark", 5, 9000,
                "When Vayne is put from the field into the Break Zone, during this turn, "
                + "your opponent cannot cast any cards.");
        assertFalse(vayne.castProhibited(), "an opponent-facing, turn-scoped prohibition is not a self-restriction");
        assertNull(vayne.castRestriction());

        CardData shantotto = makeForwardWithText("Shantotto", "Wind", 4, 8000,
                "Players cannot cast Summons.");
        assertFalse(shantotto.castProhibited(), "the global Summon lock is not a self-restriction");
    }

    @Test
    void forzaCannotBeCastFromHandBreakZoneOrRemovedFromGame() {
        MainWindow mw = new MainWindow();
        CardData forza = makeForza();

        assertFalse(mw.castRestrictionMet(forza), "Forza is never castable from hand");

        // "You can cast Forwards from your Break Zone" (Ace) must skip Forza.
        mw.placeCardInForwardZone(makeFieldAbilityCard("Ace", "Light", "Forward", ACE_BZ_TEXT));
        CardData castable = makeForward("Grunt", "Fire", 2, 5000);
        mw.gameState.getP1BreakZone().add(forza);
        mw.gameState.getP1BreakZone().add(castable);
        mw.syncBzForwardPlayables(true);

        assertFalse(mw.bzPlayableP1.containsKey(forza), "Forza is not registered as a Break-Zone cast");
        assertTrue(mw.bzPlayableP1.containsKey(castable), "an ordinary Forward still is");

        // Nor by an explicit borrowed-cast grant from either zone.
        mw.registerBorrowedPlayable(true, forza,
                new PlayableEntry(PlayableEntry.SourceZone.BREAK_ZONE, 0, false, true, false, true));
        mw.registerBorrowedPlayable(true, forza,
                new PlayableEntry(PlayableEntry.SourceZone.RFP, 0, false, true, false, true));
        assertFalse(mw.bzPlayableP1.containsKey(forza), "a borrowed-cast grant cannot override the prohibition");
    }

    // =========================================================================================
    // Palom / Porom: "For each EXP Counter placed on [self], [self] gains +1000 power." — a
    // self power boost scaling by the count of a named counter on the card itself.
    // =========================================================================================

    private static final String PALOM_TEXT =
            "At the end of each of your turns, place 1 EXP Counter on each Job Apprentice Mage you control.[[br]]   "
            + "For each EXP Counter placed on Palom, Palom gains +1000 power.[[br]]   "
            + "《0》: Choose 1 Forward. Deal it 2000 damage. If there are 3 or more EXP Counters placed on Palom, "
            + "deal it 8000 damage instead. You can only use this ability once per turn.";

    @Test
    void palomParsesAsSelfCounterScalingBoost() {
        List<ScalingSelfPowerBoost> boosts = CardData.parseScalingSelfPowerBoosts(PALOM_TEXT, "Forward", "Palom");
        assertEquals(1, boosts.size(), "only the 'For each EXP Counter' segment scales power");
        ScalingSelfPowerBoost ssb = boosts.get(0);
        assertEquals(ScalingSelfPowerBoost.Source.COUNTERS_ON_SELF, ssb.source());
        assertEquals(1000, ssb.perUnit());
        assertEquals("EXP", ssb.cardNameFilter(), "the counter name is carried in cardNameFilter");
        assertEquals(1, ssb.groupSize());
    }

    @Test
    void palomPowerScalesWithExpCountersOnItself() {
        MainWindow mw = new MainWindow();
        CardData palom = makeScalingSelfForward("Palom", "Fire", 5000, PALOM_TEXT);
        mw.placeCardInForwardZone(palom); // P1 idx 0
        assertEquals(5000, mw.effectiveP1ForwardPower(0), "no counters — base power");

        mw.gameState.placeCounters(palom, "EXP", 2);
        assertEquals(7000, mw.effectiveP1ForwardPower(0), "2 EXP Counters → +2000");

        mw.gameState.placeCounters(palom, "EXP", 1);
        assertEquals(8000, mw.effectiveP1ForwardPower(0), "3 EXP Counters → +3000");

        // Counters of a different name do not feed this scaling.
        mw.gameState.placeCounters(palom, "Turks", 5);
        assertEquals(8000, mw.effectiveP1ForwardPower(0), "unrelated counters don't scale power");
    }

    // =========================================================================================
    // Jill (26-034L): "When Jill enters the field, choose up to the same number of Characters as
    // the Job Eikon in your Break Zone and/or Job Eikon you own removed from the game. Dull them."
    // Count = (Job Eikon in own Break Zone) + (Job Eikon the acting player owns removed from game).
    // =========================================================================================

    private static final String JILL_TEXT =
            "Priming \"Shiva (XVI)\" -- 《Ice》《2》[[br]]"
            + "When Jill enters the field, choose up to the same number of Characters as the Job Eikon "
            + "in your Break Zone and/or Job Eikon you own removed from the game. Dull them.[[br]]"
            + "At the beginning of the Attack Phase during each of your turns, you may pay 《Ice》. "
            + "When you do so, choose 1 dull Forward. Break it.";

    private static final String JILL_ETB =
            "choose up to the same number of Characters as the Job Eikon in your Break Zone "
            + "and/or Job Eikon you own removed from the game. Dull them.";

    @Test
    void jillEnterFieldAbilityParsesAsBzRfgJobChoose() {
        boolean recognized = CardData.parseAutoAbilities(JILL_TEXT).stream()
                .anyMatch(a -> "ChooseAsManyAsBzRfgJobCount".equals(
                        ActionResolver.matchedPatternName(a.effectText(), null)));
        assertTrue(recognized, "Jill's ETF ability is recognized as a BZ/RFG job-count dull");
    }

    @Test
    void jillDullsCharactersEqualToEikonInBzAndRfg() {
        MainWindow mw = new MainWindow();
        CardData jill = makeForward("Jill", "Ice", 3, 7000);
        // 1 Job Eikon in P1 Break Zone + 1 Job Eikon P1 owns removed from game = count 2.
        mw.gameState.getP1BreakZone().add(makeJobCard("Ifrit", "Fire", "Summon", "Eikon"));
        CardData rfgEikon = makeJobCard("Garuda", "Wind", "Summon", "Eikon");
        mw.gameState.getIdentity().put(rfgEikon, true); // owned by P1
        mw.gameState.addToPermanentRfp(rfgEikon);
        // A non-Eikon Break Zone card must not be counted.
        mw.gameState.getP1BreakZone().add(makeJobCard("Warrior of Light", "Light", "Forward", "Warrior of Light"));

        mw.placeP2CardInForwardZone(makeForward("A", "Fire", 3, 7000)); // P2 idx 0
        mw.placeP2CardInForwardZone(makeForward("B", "Ice",  3, 7000)); // P2 idx 1

        GameContext ctx = mw.buildGameContext(true);
        ctx.preloadTargets(List.of(
                new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD),
                new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD)));
        ActionResolver.parse(JILL_ETB, jill).accept(ctx);

        assertEquals(CardState.DULL, mw.p2ForwardStates.get(0), "first chosen Character dulled");
        assertEquals(CardState.DULL, mw.p2ForwardStates.get(1), "second chosen Character dulled");
    }

    @Test
    void jillWithNoEikonChoosesNothing() {
        MainWindow mw = new MainWindow();
        CardData jill = makeForward("Jill", "Ice", 3, 7000);
        mw.placeP2CardInForwardZone(makeForward("A", "Fire", 3, 7000)); // P2 idx 0

        GameContext ctx = mw.buildGameContext(true);
        // No preloaded targets: with count 0 the effect must not attempt any selection.
        ActionResolver.parse(JILL_ETB, jill).accept(ctx);
        assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "no Eikon anywhere — nothing dulled");
    }

    // =========================================================================================
    // Forward break rule process — a Forward must break when its power falls to meet or go below
    // its already-accumulated damage, not only when fresh damage lands on it.
    //
    // applyDamageToForward compares damage against power at the moment damage is dealt, so every
    // path that lowers power instead had to gain a check: reduceTarget, setTargetBasePower, and the
    // withdrawal of a field power grant when the granting card leaves the field.
    // =========================================================================================

    /** Places a Forward for P1 carrying {@code damage} already accumulated on it. */
    private static void placeDamagedP1Forward(MainWindow mw, CardData fwd, int damage) {
        mw.gameState.getIdentity().put(fwd, true);
        mw.placeCardInForwardZone(fwd);
        mw.p1ForwardDamage.set(mw.p1ForwardCards.indexOf(fwd), damage);
    }

    @Test
    void powerReductionBreaksForwardWhoseExistingDamageIsNowLethal() {
        MainWindow mw = new MainWindow();
        CardData victim = makeForward("Victim", "Fire", 3, 9000);
        placeDamagedP1Forward(mw, victim, 5000);

        // 9000 power − 5000 = 4000 effective, with 5000 damage already on it.
        mw.buildGameContext(true).reduceTarget(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD),
                5000, EnumSet.noneOf(CardData.Trait.class));

        assertTrue(mw.p1ForwardCards.isEmpty(), "damage 5000 ≥ power 4000 — Victim must break");
        assertTrue(mw.gameState.getP1BreakZone().contains(victim), "Victim goes to its owner's Break Zone");
    }

    @Test
    void powerReductionLeavesForwardAliveWhileDamageStaysBelowPower() {
        MainWindow mw = new MainWindow();
        CardData survivor = makeForward("Survivor", "Fire", 3, 9000);
        placeDamagedP1Forward(mw, survivor, 3000);

        mw.buildGameContext(true).reduceTarget(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD),
                5000, EnumSet.noneOf(CardData.Trait.class));

        assertEquals(1, mw.p1ForwardCards.size(), "damage 3000 < power 4000 — Survivor stays on the field");
        assertEquals(4000, mw.effectiveP1ForwardPower(0), "the reduction still applied");
    }

    @Test
    void setTargetBasePowerBreaksForwardWhoseExistingDamageIsNowLethal() {
        MainWindow mw = new MainWindow();
        CardData victim = makeForward("Victim", "Fire", 3, 9000);
        placeDamagedP1Forward(mw, victim, 5000);

        // "Its power becomes 4000 until the end of the turn" against 5000 accumulated damage.
        mw.buildGameContext(true).setTargetBasePower(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD), 4000);

        assertTrue(mw.p1ForwardCards.isEmpty(), "damage 5000 ≥ power 4000 — Victim must break");
    }

    @Test
    void withdrawnFieldPowerGrantBreaksForwardWhoseDamageIsNowLethal() {
        MainWindow mw = new MainWindow();
        CardData buffer = makeBackupWithPowerGrant(
                "Buffer", "Fire", "The Forwards you control gain +2000 power.");
        assertFalse(buffer.fieldPowerGrants().isEmpty(), "the +2000 field grant should parse");
        mw.gameState.getIdentity().put(buffer, true);
        mw.p1BackupCards[0] = buffer;

        CardData victim = makeForward("Victim", "Fire", 3, 7000);
        placeDamagedP1Forward(mw, victim, 8000);
        assertEquals(9000, mw.effectiveP1ForwardPower(0), "the Backup's grant keeps Victim above its damage");
        assertEquals(1, mw.p1ForwardCards.size(), "Victim survives while the grant stands");

        // The granting Backup leaves the field — Victim drops to 7000 against 8000 damage.
        mw.returnP1BackupToHand(0);

        assertTrue(mw.p1ForwardCards.isEmpty(), "grant withdrawn — damage 8000 ≥ power 7000, Victim must break");
    }

    @Test
    void cannotBeBrokenForwardStillLeavesFieldWhenReducedToZeroPower() {
        MainWindow mw = new MainWindow();
        // Base 3000 power; dropping it by 3000 reaches 0 → rule process, shield does not apply.
        CardData shielded = makeForwardWithTraits("Shielded", "Fire", 3000,
                EnumSet.of(CardData.Trait.CANNOT_BE_BROKEN));
        mw.gameState.getIdentity().put(shielded, true);
        mw.placeCardInForwardZone(shielded);
        assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN), "trait is present");

        mw.buildGameContext(true).reduceTarget(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD),
                3000, EnumSet.noneOf(CardData.Trait.class));

        assertTrue(mw.p1ForwardCards.isEmpty(),
                "0-power rule process moves it to the Break Zone despite 'cannot be broken'");
        assertTrue(mw.gameState.getP1BreakZone().contains(shielded), "it lands in its owner's Break Zone");
    }

    @Test
    void cannotBeBrokenForwardSurvivesLethalDamageWhenPowerStaysPositive() {
        MainWindow mw = new MainWindow();
        CardData shielded = makeForwardWithTraits("Shielded", "Fire", 9000,
                EnumSet.of(CardData.Trait.CANNOT_BE_BROKEN));
        placeDamagedP1Forward(mw, shielded, 5000);

        // 9000 − 5000 = 4000 power, still positive, against 5000 damage: the shield holds.
        mw.buildGameContext(true).reduceTarget(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD),
                5000, EnumSet.noneOf(CardData.Trait.class));

        assertEquals(1, mw.p1ForwardCards.size(),
                "lethal *damage* is survived because the Forward cannot be broken");
        assertEquals(4000, mw.effectiveP1ForwardPower(0), "the reduction still applied");
    }

    // =========================================================================================
    // Lightning (15-041L) — "At the end of each of your turns, if each player has no cards in
    // their hands, Lightning deals your opponent 1 point of damage."
    //
    // The leading ".+?" of DEAL_PLAYER_DAMAGE_TO_OPPONENT used to swallow the whole "if each
    // player has no cards…" clause, so the damage fired unconditionally every end phase.
    // =========================================================================================

    private static final String LIGHTNING_TEXT =
            "The cost required to cast Lightning is increased by 1 for each card in your opponent's hand."
            + "[[br]]   If you have 2 cards or less in your hand, Lightning gains +1000 power and Haste."
            + "[[br]]   At the end of each of your turns, if each player has no cards in their hands, "
            + "Lightning deals your opponent 1 point of damage.";

    @Test
    void lightningEndOfTurnDamageRequiresBothHandsEmpty() {
        AutoAbility eot = CardData.parseAutoAbilities(LIGHTNING_TEXT).stream()
                .filter(a -> "end of your turn".equals(a.trigger()))
                .findFirst().orElse(null);
        assertNotNull(eot, "the end-of-your-turn auto ability should parse");

        Consumer<GameContext> fn = ActionResolver.parse(eot.effectText(), null);
        assertNotNull(fn, "the gated damage effect should parse");

        // Both hands empty — damage fires.
        GameContext both = mock(GameContext.class);
        when(both.yourHandSize()).thenReturn(0);
        when(both.opponentHandSize()).thenReturn(0);
        fn.accept(both);
        verify(both).dealDamageToOpponent(1);

        // Controller holds cards — no damage (the reported bug).
        GameContext mine = mock(GameContext.class);
        when(mine.yourHandSize()).thenReturn(8);
        when(mine.opponentHandSize()).thenReturn(0);
        fn.accept(mine);
        verify(mine, never()).dealDamageToOpponent(anyInt());

        // Opponent holds cards — no damage.
        GameContext theirs = mock(GameContext.class);
        when(theirs.yourHandSize()).thenReturn(0);
        when(theirs.opponentHandSize()).thenReturn(3);
        fn.accept(theirs);
        verify(theirs, never()).dealDamageToOpponent(anyInt());
    }

    // =========================================================================================
    // Bartz 7-059L, Dual-Wield 《S》: "Until the end of the turn, Bartz gains First Strike and
    // Bartz's power becomes 10000." — the power clause replaces Bartz's BASE power, so other
    // boosts and reductions stack on top of it rather than being wiped out by it.
    // =========================================================================================

    private static final String BARTZ_DUAL_WIELD_TEXT =
            "Until the end of the turn, Bartz gains First Strike and Bartz's power becomes 10000.";

    @Test
    void bartzDualWieldSetsBasePowerAndGrantsFirstStrike() {
        CardData bartz = mock(CardData.class);
        when(bartz.name()).thenReturn("Bartz");

        Consumer<GameContext> fn = ActionResolver.parse(BARTZ_DUAL_WIELD_TEXT, bartz);
        assertNotNull(fn, "Expected Dual-Wield's effect text to parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).setSourceForwardBasePower(bartz, 10000,
                EnumSet.of(CardData.Trait.FIRST_STRIKE));
    }

    @Test
    void bartzDualWieldDoesNotFireWhenSourceNameDoesNotMatch() {
        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Not Bartz");

        assertNull(ActionResolver.parse(BARTZ_DUAL_WIELD_TEXT, other),
                "Ability text naming Bartz should not resolve for a differently-named source");
    }

    @Test
    void dualWieldIsParsedOffTheFullCardTextAsASpecialAbility() {
        String cardText =
                "When Bartz enters the field, choose up to 2 Category V Characters. Activate them.[[br]]"
                + "[[s]]Spellblade [[/]]《S》: Choose 1 Forward. Deal it 5000 damage.[[br]]"
                + "[[s]]Dual-Wield[[/]] 《S》: " + BARTZ_DUAL_WIELD_TEXT;

        ActionAbility dualWield = CardData.parseActionAbilities(cardText).stream()
                .filter(a -> "Dual-Wield".equals(a.abilityName()))
                .findFirst().orElse(null);

        assertNotNull(dualWield, "Dual-Wield should be picked up as a named Special Ability");
        assertTrue(dualWield.isSpecial(), "《S》 makes Dual-Wield a Special Ability");
        assertEquals(BARTZ_DUAL_WIELD_TEXT, dualWield.effectText());
    }

    @Test
    void baseWordingWithoutATraitClauseAlsoParses() {
        CardData bartz = mock(CardData.class);
        when(bartz.name()).thenReturn("Bartz");

        Consumer<GameContext> fn = ActionResolver.parse(
                "Until the end of the turn, Bartz's power becomes 10000.", bartz);
        assertNotNull(fn, "the trait-less wording should parse too");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).setSourceForwardBasePower(bartz, 10000,
                EnumSet.noneOf(CardData.Trait.class));
    }

    @Test
    void dualWieldRaisesBartzToTenThousandAndGrantsFirstStrike() {
        MainWindow mw = new MainWindow();
        CardData bartz = makeForward("Bartz", "Wind", 5, 7000);
        placeDamagedP1Forward(mw, bartz, 0);

        ActionResolver.parse(BARTZ_DUAL_WIELD_TEXT, bartz).accept(mw.buildGameContext(true));

        assertEquals(10000, mw.effectiveP1ForwardPower(0));
        assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.FIRST_STRIKE));
    }

    @Test
    void boostsAppliedAfterDualWieldStackOnTopOfTheNewBasePower() {
        MainWindow mw = new MainWindow();
        CardData bartz = makeForward("Bartz", "Wind", 5, 7000);
        placeDamagedP1Forward(mw, bartz, 0);

        ActionResolver.parse(BARTZ_DUAL_WIELD_TEXT, bartz).accept(mw.buildGameContext(true));
        mw.buildGameContext(true).boostTarget(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD),
                2000, EnumSet.noneOf(CardData.Trait.class));

        assertEquals(12000, mw.effectiveP1ForwardPower(0), "the +2000 stacks on the 10000 base");
    }

    @Test
    void boostsAppliedBeforeDualWieldSurviveTheBasePowerChange() {
        MainWindow mw = new MainWindow();
        CardData bartz = makeForward("Bartz", "Wind", 5, 7000);
        placeDamagedP1Forward(mw, bartz, 0);

        mw.buildGameContext(true).boostTarget(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD),
                2000, EnumSet.noneOf(CardData.Trait.class));
        ActionResolver.parse(BARTZ_DUAL_WIELD_TEXT, bartz).accept(mw.buildGameContext(true));

        assertEquals(12000, mw.effectiveP1ForwardPower(0),
                "an earlier boost is not wiped out by the base-power change");
    }

    @Test
    void reductionsAppliedAfterDualWieldStillApplyToTheNewBasePower() {
        MainWindow mw = new MainWindow();
        CardData bartz = makeForward("Bartz", "Wind", 5, 7000);
        placeDamagedP1Forward(mw, bartz, 0);

        ActionResolver.parse(BARTZ_DUAL_WIELD_TEXT, bartz).accept(mw.buildGameContext(true));
        mw.buildGameContext(true).reduceTarget(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD),
                3000, EnumSet.noneOf(CardData.Trait.class));

        assertEquals(7000, mw.effectiveP1ForwardPower(0), "the −3000 applies to the 10000 base");
    }

    @Test
    void basePowerOverrideExpiresAtEndOfTurn() {
        MainWindow mw = new MainWindow();
        CardData bartz = makeForward("Bartz", "Wind", 5, 7000);
        placeDamagedP1Forward(mw, bartz, 0);

        ActionResolver.parse(BARTZ_DUAL_WIELD_TEXT, bartz).accept(mw.buildGameContext(true));
        assertEquals(10000, mw.effectiveP1ForwardPower(0));

        mw.fireEndOfTurnEffects(true);

        assertEquals(7000, mw.effectiveP1ForwardPower(0), "the override lasts only for the turn");
        assertTrue(mw.basePowerOverrides.isEmpty(), "the override entry is dropped, not left to leak");
    }

    // =========================================================================================
    // "Its power becomes N until the end of the turn." (Barbariccia, Diablos, Lulu, Matoya,
    // Penelo, Yagudo) and the self-targeted "[Name]'s power becomes …" (Blue Mage, Mime) both
    // replace the card's BASE power, so boosts and reductions layer on top instead of being
    // wiped out — the same rule Bartz's Dual-Wield follows.
    // =========================================================================================

    private static final String MATOYA_ETF_TEXT =
            "Choose 1 Forward. Its power becomes 4000 until the end of the turn.";

    @Test
    void itsPowerBecomesRoutesThroughTheBasePowerLayer() {
        Consumer<GameContext> fn = ActionResolver.parse(MATOYA_ETF_TEXT, null);
        assertNotNull(fn, "Expected the \"its power becomes N\" wording to parse");

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(t));

        fn.accept(ctx);

        verify(ctx).setTargetBasePower(t, 4000);
    }

    @Test
    void boostsAppliedAfterItsPowerBecomesStackOnTheNewBase() {
        MainWindow mw = new MainWindow();
        CardData victim = makeForward("Victim", "Fire", 3, 9000);
        placeDamagedP1Forward(mw, victim, 0);
        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);

        mw.buildGameContext(true).setTargetBasePower(t, 4000);
        mw.buildGameContext(true).boostTarget(t, 2000, EnumSet.noneOf(CardData.Trait.class));

        assertEquals(6000, mw.effectiveP1ForwardPower(0), "the +2000 stacks on the 4000 base");
    }

    @Test
    void boostsAppliedBeforeItsPowerBecomesSurviveIt() {
        MainWindow mw = new MainWindow();
        CardData victim = makeForward("Victim", "Fire", 3, 9000);
        placeDamagedP1Forward(mw, victim, 0);
        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);

        mw.buildGameContext(true).boostTarget(t, 2000, EnumSet.noneOf(CardData.Trait.class));
        mw.buildGameContext(true).setTargetBasePower(t, 4000);

        assertEquals(6000, mw.effectiveP1ForwardPower(0),
                "the earlier +2000 is no longer wiped out by the power-becomes effect");
    }

    @Test
    void reductionsAppliedBeforeItsPowerBecomesSurviveIt() {
        MainWindow mw = new MainWindow();
        CardData victim = makeForward("Victim", "Fire", 3, 9000);
        placeDamagedP1Forward(mw, victim, 0);
        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);

        mw.buildGameContext(true).reduceTarget(t, 1000, EnumSet.noneOf(CardData.Trait.class));
        mw.buildGameContext(true).setTargetBasePower(t, 4000);

        assertEquals(3000, mw.effectiveP1ForwardPower(0), "the earlier −1000 still applies");
    }

    @Test
    void itsPowerBecomesAppliesToAnOpponentForwardToo() {
        MainWindow mw = new MainWindow();
        CardData victim = makeForward("Victim", "Fire", 3, 9000);
        mw.gameState.getIdentity().put(victim, false);
        mw.placeP2CardInForwardZone(victim);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);

        mw.buildGameContext(true).setTargetBasePower(t, 4000);

        assertEquals(4000, mw.effectiveP2ForwardPower(0));
    }

    @Test
    void itsPowerBecomesExpiresAtEndOfTurn() {
        MainWindow mw = new MainWindow();
        CardData victim = makeForward("Victim", "Fire", 3, 9000);
        placeDamagedP1Forward(mw, victim, 0);

        mw.buildGameContext(true).setTargetBasePower(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD), 4000);
        assertEquals(4000, mw.effectiveP1ForwardPower(0));

        mw.fireEndOfTurnEffects(true);

        assertEquals(9000, mw.effectiveP1ForwardPower(0), "the override lasts only for the turn");
        assertTrue(mw.basePowerOverrides.isEmpty(), "the override entry is dropped, not left to leak");
    }

    @Test
    void sourcePowerBecomesAlsoLeavesEarlierBoostsInPlace() {
        MainWindow mw = new MainWindow();
        CardData mime = makeForward("Mime", "Earth", 2, 4000);
        placeDamagedP1Forward(mw, mime, 0);

        mw.buildGameContext(true).boostTarget(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD),
                1000, EnumSet.noneOf(CardData.Trait.class));
        mw.buildGameContext(true).setSourceForwardBasePower(mime, 3000,
                EnumSet.noneOf(CardData.Trait.class));

        assertEquals(4000, mw.effectiveP1ForwardPower(0),
                "Mime copies a 3000 base and keeps the +1000 it was already carrying");
    }

    // =========================================================================================
    // Wakka 1-216S, Status Reels 《S》《Water》: "Choose 1 Forward. Until the end of the turn, it
    // loses all its abilities and its power becomes 1000."
    //
    // With the duration clause leading, FOLLOWUP_LOSE_ALL_ABILITIES_EOT's "abilities until end of
    // turn" adjacency fails and FOLLOWUP_POWER_REDUCE_UNTIL matched "Until …, it loses" with empty
    // amount and trait groups — so the ability wipe AND the power change were both silently
    // dropped, leaving a no-op reduction.
    // =========================================================================================

    private static final String WAKKA_STATUS_REELS_TEXT =
            "Choose 1 Forward. Until the end of the turn, it loses all its abilities "
            + "and its power becomes 1000.";

    @Test
    void statusReelsWipesAbilitiesAndSetsBasePower() {
        Consumer<GameContext> fn = ActionResolver.parse(WAKKA_STATUS_REELS_TEXT, null);
        assertNotNull(fn, "Expected Status Reels to parse");

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(t));

        fn.accept(ctx);

        verify(ctx).targetLoseAllAbilitiesUntilEndOfTurn(t);
        verify(ctx).setTargetBasePower(t, 1000);
    }

    @Test
    void statusReelsIsNoLongerMisreadAsABarePowerReduction() {
        assertEquals("ChooseCharacter / LoseAllAbilitiesAndPowerBecomes",
                ActionResolver.fullDescription(WAKKA_STATUS_REELS_TEXT, null),
                "the combined clause must win over the bare power-reduction pattern");
    }

    @Test
    void trailingDurationWordOrderParsesTheSameWay() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. It loses all its abilities and its power becomes 1000 "
                + "until the end of the turn.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(t));

        fn.accept(ctx);

        verify(ctx).targetLoseAllAbilitiesUntilEndOfTurn(t);
        verify(ctx).setTargetBasePower(t, 1000);
    }

    @Test
    void plainLoseAllAbilitiesFollowupStillParsesOnItsOwn() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. It loses all its abilities until the end of the turn.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(t));

        fn.accept(ctx);

        verify(ctx).targetLoseAllAbilitiesUntilEndOfTurn(t);
        verify(ctx, never()).setTargetBasePower(any(), anyInt());
    }

    @Test
    void statusReelsAppliesBothHalvesOnTheField() {
        MainWindow mw = new MainWindow();
        CardData victim = makeForwardWithTraits("Victim", "Fire", 9000,
                Set.of(CardData.Trait.BRAVE));
        mw.gameState.getIdentity().put(victim, false);
        mw.placeP2CardInForwardZone(victim);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);

        GameContext ctx = mw.buildGameContext(true);
        ctx.targetLoseAllAbilitiesUntilEndOfTurn(t);
        ctx.setTargetBasePower(t, 1000);

        assertEquals(1000, mw.effectiveP2ForwardPower(0));
        assertFalse(mw.effectiveP2HasTrait(0, CardData.Trait.BRAVE), "Brave is suppressed with the rest");
    }

    @Test
    void statusReelsPowerDropBreaksAnAlreadyDamagedForward() {
        MainWindow mw = new MainWindow();
        CardData victim = makeForward("Victim", "Fire", 3, 9000);
        placeDamagedP1Forward(mw, victim, 5000);

        mw.buildGameContext(true).setTargetBasePower(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD), 1000);

        assertTrue(mw.p1ForwardCards.isEmpty(), "damage 5000 ≥ power 1000 — Victim must break");
    }

    // =========================================================================================
    // Shadow Lord 12-071R, 《Earth》: "Until the end of the turn, Shadow Lord gains Brave and
    // \"EX Bursts of cards put into the Damage Zone due to Shadow Lord cannot be used.\""
    //
    // The quoted clause is source-scoped, not resolution-scoped: it follows Shadow Lord for the
    // rest of the turn, so it needs its own primitive rather than the existing per-ability
    // suppressExBurstsThisAbility flag.
    // =========================================================================================

    private static final String SHADOW_LORD_TEXT =
            "Until the end of the turn, Shadow Lord gains Brave and \"EX Bursts of cards put "
            + "into the Damage Zone due to Shadow Lord cannot be used.\"";

    @Test
    void shadowLordGrantsBraveAndSourceScopedExBurstSuppression() {
        CardData shadowLord = mock(CardData.class);
        when(shadowLord.name()).thenReturn("Shadow Lord");

        Consumer<GameContext> fn = ActionResolver.parse(SHADOW_LORD_TEXT, shadowLord);
        assertNotNull(fn, "Expected Shadow Lord's 《Earth》 ability to parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).boostSourceForward(shadowLord, 0, EnumSet.of(CardData.Trait.BRAVE));
        verify(ctx).grantSelfExBurstSuppression(shadowLord);
        verify(ctx, never()).suppressExBurstsThisAbility();
    }

    @Test
    void shadowLordDoesNotFireWhenSourceNameDoesNotMatch() {
        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Not Shadow Lord");

        assertNull(ActionResolver.parse(SHADOW_LORD_TEXT, other),
                "the quoted clause names Shadow Lord — it must not resolve for another card");
    }

    @Test
    void grantedSuppressionIsRecordedAndExpiresAtEndOfTurn() {
        MainWindow mw = new MainWindow();
        CardData shadowLord = makeForward("Shadow Lord", "Earth", 2, 9000);
        placeDamagedP1Forward(mw, shadowLord, 0);

        ActionResolver.parse(SHADOW_LORD_TEXT, shadowLord).accept(mw.buildGameContext(true));

        assertTrue(mw.exBurstSuppressingSources.containsKey(shadowLord));
        assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.BRAVE), "Brave is granted alongside");

        mw.fireEndOfTurnEffects(true);

        assertFalse(mw.exBurstSuppressingSources.containsKey(shadowLord),
                "the suppression lasts only for the turn");
    }

    @Test
    void damageCreditedToTheSuppressorSuppressesTheExBurst() {
        MainWindow mw = new MainWindow();
        CardData shadowLord = makeForward("Shadow Lord", "Earth", 2, 9000);
        placeDamagedP1Forward(mw, shadowLord, 0);
        ActionResolver.parse(SHADOW_LORD_TEXT, shadowLord).accept(mw.buildGameContext(true));

        assertTrue(mw.exBurstSuppressedBy(shadowLord, makeForward("Burst", "Fire", 5, 7000)),
                "damage dealt by Shadow Lord suppresses the revealed card's EX Burst");
    }

    @Test
    void damageCreditedToAnotherCardStillTriggersTheExBurst() {
        MainWindow mw = new MainWindow();
        CardData shadowLord = makeForward("Shadow Lord", "Earth", 2, 9000);
        CardData bystander  = makeForward("Bystander", "Earth", 2, 5000);
        placeDamagedP1Forward(mw, shadowLord, 0);
        ActionResolver.parse(SHADOW_LORD_TEXT, shadowLord).accept(mw.buildGameContext(true));

        CardData burst = makeForward("Burst", "Fire", 5, 7000);
        assertFalse(mw.exBurstSuppressedBy(bystander, burst), "another attacker's damage is unaffected");
        assertFalse(mw.exBurstSuppressedBy(null, burst), "sourceless damage is unaffected");
    }

    @Test
    void theDamageSourceIsConsumedSoItCannotLeakIntoTheNextPoint() {
        MainWindow mw = new MainWindow();
        CardData shadowLord = makeForward("Shadow Lord", "Earth", 2, 9000);
        placeDamagedP1Forward(mw, shadowLord, 0);

        mw.setPlayerDamageSource(shadowLord);
        assertEquals(shadowLord, mw.consumePlayerDamageSource());
        assertNull(mw.consumePlayerDamageSource(),
                "a second point with no source re-declared must not inherit the first's");
    }

    @Test
    void anUnblockedPartyIsCreditedToItsSuppressingMember() {
        MainWindow mw = new MainWindow();
        CardData ally       = makeForward("Ally", "Earth", 2, 5000);
        CardData shadowLord = makeForward("Shadow Lord", "Earth", 2, 9000);
        placeDamagedP1Forward(mw, ally, 0);
        placeDamagedP1Forward(mw, shadowLord, 0);
        ActionResolver.parse(SHADOW_LORD_TEXT, shadowLord).accept(mw.buildGameContext(true));

        assertEquals(shadowLord, mw.partyExBurstSuppressor(List.of(0, 1), true),
                "one suppressing member is enough to credit the party's damage to it");
        assertNull(mw.partyExBurstSuppressor(List.of(0), true),
                "a party without Shadow Lord credits nobody");
    }

    // =========================================================================================
    // Printed source-scoped EX Burst suppression, the permanent counterpart to Shadow Lord
    // 12-071R's granted clause:
    //   Exdeath 1-122H            "Any card put in the Damage Zone due to Exdeath cannot use its EX Burst."
    //   Arborous Simulacrum 2-118C  same, restricted to cards "of cost 2 or less"
    //   Shadow Lord B-007         the "EX Bursts of cards … cannot be used" wording, printed
    // These are read straight off the source's field abilities — nothing is registered up front.
    // =========================================================================================

    /** Builds a Forward whose field abilities are parsed from {@code text}. */
    private static CardData makeForwardWithFieldAbility(String name, int cost, String text) {
        return new CardData(null, name, "Dark", cost, 7000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), CardData.parseFieldAbilities(text, "Forward"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, text);
    }

    private static final String EXDEATH_TEXT =
            "Any card put in the Damage Zone due to Exdeath cannot use its EX Burst.";
    private static final String ARBOROUS_TEXT =
            "Any card of cost 2 or less put in the Damage Zone due to Arborous Simulacrum "
            + "cannot use its EX Burst.";
    private static final String SHADOW_LORD_PRINTED_TEXT =
            "EX Bursts of cards put into the Damage Zone due to Shadow Lord cannot be used.";

    @Test
    void bothPrintedWordingsAreRecognizedForTheirOwnCard() {
        assertEquals(Integer.MAX_VALUE,
                ActionResolver.exBurstSuppressionMaxCost(EXDEATH_TEXT, "Exdeath"),
                "Exdeath suppresses regardless of cost");
        assertEquals(2,
                ActionResolver.exBurstSuppressionMaxCost(ARBOROUS_TEXT, "Arborous Simulacrum"),
                "Arborous Simulacrum only suppresses cost 2 or less");
        assertEquals(Integer.MAX_VALUE,
                ActionResolver.exBurstSuppressionMaxCost(SHADOW_LORD_PRINTED_TEXT, "Shadow Lord"),
                "the printed Shadow Lord wording is the same rule");
    }

    @Test
    void suppressionDoesNotApplyToADifferentlyNamedSource() {
        assertNull(ActionResolver.exBurstSuppressionMaxCost(EXDEATH_TEXT, "Bartz"),
                "the text names Exdeath — another card's damage is unaffected");
        assertNull(ActionResolver.exBurstSuppressionMaxCost(
                "Any card put in the Damage Zone cannot use its EX Burst.", "Exdeath"),
                "text with no \"due to [Name]\" clause is not a source-scoped suppression");
    }

    @Test
    void exdeathSuppressesTheExBurstOfAnyCostCard() {
        MainWindow mw = new MainWindow();
        CardData exdeath = makeForwardWithFieldAbility("Exdeath", 5, EXDEATH_TEXT);

        assertTrue(mw.exBurstSuppressedBy(exdeath, makeForward("Cheap", "Fire", 1, 2000)));
        assertTrue(mw.exBurstSuppressedBy(exdeath, makeForward("Pricey", "Fire", 9, 9000)));
    }

    @Test
    void arborousSimulacrumOnlySuppressesCheapCards() {
        MainWindow mw = new MainWindow();
        CardData arborous = makeForwardWithFieldAbility("Arborous Simulacrum", 3, ARBOROUS_TEXT);

        assertTrue(mw.exBurstSuppressedBy(arborous, makeForward("Two", "Fire", 2, 3000)),
                "cost 2 is within \"cost 2 or less\"");
        assertFalse(mw.exBurstSuppressedBy(arborous, makeForward("Three", "Fire", 3, 5000)),
                "cost 3 is outside the filter — its EX Burst still fires");
    }

    @Test
    void printedShadowLordWordingWorksWithoutTheGrant() {
        MainWindow mw = new MainWindow();
        CardData printed = makeForwardWithFieldAbility("Shadow Lord", 2, SHADOW_LORD_PRINTED_TEXT);

        assertTrue(mw.exBurstSuppressedBy(printed, makeForward("Burst", "Fire", 5, 7000)));
        assertTrue(mw.exBurstSuppressingSources.isEmpty(),
                "the printed form is read off field abilities, not registered as a grant");
    }

    @Test
    void aSuppressorThatHasLostItsAbilitiesStopsSuppressing() {
        MainWindow mw = new MainWindow();
        CardData exdeath = makeForwardWithFieldAbility("Exdeath", 5, EXDEATH_TEXT);
        CardData burst   = makeForward("Burst", "Fire", 5, 7000);
        assertTrue(mw.exBurstSuppressedBy(exdeath, burst));

        mw.lostAbilitiesCards.add(exdeath);

        assertFalse(mw.exBurstSuppressedBy(exdeath, burst),
                "\"loses all abilities\" takes the printed suppression with it");
    }

    @Test
    void aPrintedSuppressorIsFoundInAnUnblockedParty() {
        MainWindow mw = new MainWindow();
        CardData ally    = makeForward("Ally", "Earth", 2, 5000);
        CardData exdeath = makeForwardWithFieldAbility("Exdeath", 5, EXDEATH_TEXT);
        placeDamagedP1Forward(mw, ally, 0);
        placeDamagedP1Forward(mw, exdeath, 0);

        assertEquals(exdeath, mw.partyExBurstSuppressor(List.of(0, 1), true));
    }

    // =========================================================================================
    // Back Attack — "Like Summons and abilities, this card can be played during either player's
    // Attack Phase or Main Phase."  The trait was parsed but inert: a Back Attack Character was
    // still restricted to its controller's own Main Phase, like any other Character.  It must now
    // share the Summons' cast timing — every priority window in either player's Main or Attack
    // Phase — while keeping every other cast requirement (cost, name conflict, slot, cast limit).
    // =========================================================================================

    private static final String JINNAI_TEXT =
            "Back Attack[[br]] First Strike[[br]] "
            + "When Jinnai enters the field, choose 1 Forward opponent controls. Deal it 4000 damage.";

    private static final String CARBUNCLE_TEXT =
            "Back Attack (Like Summons and abilities, this card can be played during either "
            + "player's Attack Phase or Main Phase.)";

    private static final String GOGO_BACK_ATTACK_TEXT =
            "Back Attack[[br]]You can only cast Gogo during your opponent's turn.[[br]]"
            + "When Gogo enters the field due to your cast, choose 1 auto-ability triggered from "
            + "your opponent's Forward of cost 4 or less. Gogo triggers the same auto-ability.";

    /** Builds a card of any type with its Special Traits parsed from {@code text}, as the real ETL does. */
    private static CardData makeTraitCard(String name, String element, String type, String text) {
        return new CardData(null, name, element, 3, 7000, type, false, 0, false, false,
                CardData.parseTraits(text), 0, List.of(), null, List.of(),
                CardData.parseActionAbilities(text), CardData.parseAutoAbilities(text),
                CardData.parseFieldAbilities(text, type),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, text);
    }

    /** Drives the game to {@code phase} on {@code player}'s turn and syncs the phase tracker. */
    private static void advanceTo(MainWindow mw, GameState.Player player, GameState.GamePhase phase) {
        mw.gameState.startFirstTurn(GameState.Player.P1);
        while (mw.gameState.getCurrentPlayer() != player || mw.gameState.getCurrentPhase() != phase) {
            mw.gameState.advancePhase();
        }
        mw.refreshPhaseTracker();
    }

    @Test
    void backAttackGrantsSummonCastTimingToACharacter() {
        assertTrue(makeTraitCard("Jinnai", "Wind", "Forward", JINNAI_TEXT).castsAtSummonSpeed());
        assertTrue(makeTraitCard("Carbuncle", "Earth", "Backup", CARBUNCLE_TEXT).castsAtSummonSpeed(),
                "the reminder-text-only printing carries the trait too");
        assertTrue(makeTraitCard("Wicked Mask", "Earth", "Monster", "Back Attack").castsAtSummonSpeed(),
                "Back Attack appears on Monsters as well as Forwards and Backups");

        assertTrue(makeForward("Grunt", "Fire", 2, 5000).isSummon() == false);
        assertFalse(makeForward("Grunt", "Fire", 2, 5000).castsAtSummonSpeed(),
                "an ordinary Character is Main-Phase-only");
        assertTrue(makeTraitCard("Ifrit", "Fire", "Summon", "Choose 1 Forward. Deal it 7000 damage.")
                .castsAtSummonSpeed(), "Summons have the timing by card type");
    }

    @Test
    void backAttackCharacterIsCastableDuringOpponentsMainPhase() {
        MainWindow mw = new MainWindow();
        CardData jinnai = makeTraitCard("Jinnai", "Wind", "Forward", JINNAI_TEXT);
        CardData grunt  = makeForward("Grunt", "Fire", 2, 5000);

        advanceTo(mw, GameState.Player.P2, GameState.GamePhase.MAIN_1);
        // P2 has not yet passed, so nobody may act.
        assertFalse(mw.castTimingWindowOpen(jinnai), "no window before P2 passes priority");

        mw.offerP1MainPhasePriority(() -> {});
        assertTrue(mw.castTimingWindowOpen(jinnai), "Back Attack may be cast in P2's Main Phase");
        assertFalse(mw.castTimingWindowOpen(grunt),  "an ordinary Forward may not");
    }

    @Test
    void backAttackCharacterIsCastableAtAnAttackPhasePriorityWindow() {
        MainWindow mw = new MainWindow();
        CardData jinnai = makeTraitCard("Jinnai", "Wind", "Forward", JINNAI_TEXT);
        CardData grunt  = makeForward("Grunt", "Fire", 2, 5000);

        // P1's own Attack Preparation.
        advanceTo(mw, GameState.Player.P1, GameState.GamePhase.ATTACK);
        mw.attackSubStep = 0;
        assertTrue(mw.p1MayActInAttackPhase());
        assertTrue(mw.castTimingWindowOpen(jinnai), "Back Attack may be cast in the Attack Phase");
        assertFalse(mw.castTimingWindowOpen(grunt),  "an ordinary Forward may not");

        // P2's Attack Phase, with P1 holding the Attack Preparation priority window.
        advanceTo(mw, GameState.Player.P2, GameState.GamePhase.ATTACK);
        mw.gameState.getP1Hand().add(jinnai);
        mw.offerP1AttackPrepPriority(() -> {});
        assertTrue(mw.castTimingWindowOpen(jinnai), "Back Attack may be cast during P2's Attack Phase");
        assertFalse(mw.castTimingWindowOpen(grunt));
    }

    @Test
    void ordinaryCastTimingIsUnchanged() {
        MainWindow mw = new MainWindow();
        CardData jinnai = makeTraitCard("Jinnai", "Wind", "Forward", JINNAI_TEXT);
        CardData grunt  = makeForward("Grunt", "Fire", 2, 5000);

        advanceTo(mw, GameState.Player.P1, GameState.GamePhase.MAIN_1);
        assertTrue(mw.castTimingWindowOpen(grunt),  "P1's own Main Phase is open to everything");
        assertTrue(mw.castTimingWindowOpen(jinnai));

        advanceTo(mw, GameState.Player.P1, GameState.GamePhase.END);
        assertFalse(mw.castTimingWindowOpen(grunt),  "the End Phase is not a cast window");
        assertFalse(mw.castTimingWindowOpen(jinnai), "not even for Back Attack — it is neither Main nor Attack");
    }

    @Test
    void aBackAttackCardInHandEarnsAPriorityStopInsteadOfAnAutoPass() {
        MainWindow mw = new MainWindow();
        advanceTo(mw, GameState.Player.P2, GameState.GamePhase.ATTACK);

        // Nothing to act with: the checkpoint passes itself so combat does not stall.
        boolean[] passed = { false };
        mw.offerP1AttackPrepPriority(() -> passed[0] = true);
        assertTrue(passed[0], "an empty board and hand auto-passes the priority window");

        // A Back Attack card in hand is now something the window could be spent on.
        mw.gameState.getP1Hand().add(makeTraitCard("Jinnai", "Wind", "Forward", JINNAI_TEXT));
        boolean[] passedAgain = { false };
        mw.offerP1AttackPrepPriority(() -> passedAgain[0] = true);
        assertFalse(passedAgain[0], "P1 keeps the window to consider casting the Back Attack card");
    }

    @Test
    void gogoCombinesBackAttackTimingWithItsOpponentTurnOnlyRestriction() {
        CardData gogo = makeTraitCard("Gogo", "Water", "Forward", GOGO_BACK_ATTACK_TEXT);
        assertTrue(gogo.castsAtSummonSpeed());
        CastRestriction cr = gogo.castRestriction();
        assertNotNull(cr);
        assertTrue(cr.opponentTurnOnly(), "\"only cast Gogo during your opponent's turn\"");

        MainWindow mw = new MainWindow();

        // P1's own Main Phase: the timing window is open, but the card's own restriction shuts it.
        advanceTo(mw, GameState.Player.P1, GameState.GamePhase.MAIN_1);
        assertTrue(mw.castTimingWindowOpen(gogo));
        assertFalse(mw.castRestrictionMet(gogo), "Gogo may not be cast on P1's own turn");

        // P2's Main Phase with priority: both halves agree, which is the only time Gogo is castable.
        advanceTo(mw, GameState.Player.P2, GameState.GamePhase.MAIN_1);
        mw.offerP1MainPhasePriority(() -> {});
        assertTrue(mw.castTimingWindowOpen(gogo));
        assertTrue(mw.castRestrictionMet(gogo));
    }

    // =========================================================================================
    // "Per turn" cast tracking is scoped to a single turn, and each player gets a fresh allowance
    // on every turn — not only their own.  P1's counters were cleared just once, at the start of
    // P1's turn, so casts made on P1's own turn carried across the boundary and were still counted
    // while P1 held priority during P2's turn: after casting 2 cards under Ace 12-118L, P1 could
    // not cast a Summon or a Back Attack Character on the opponent's turn at all.
    // =========================================================================================

    private static final String ACE_CAST_LIMIT_TEXT = "You can only cast up to 2 cards per turn.";

    @Test
    void castTrackingIsClearedWhenP1sTurnEnds() {
        MainWindow mw = new MainWindow();
        advanceTo(mw, GameState.Player.P1, GameState.GamePhase.END);

        mw.p1Turn.cardsCastThisTurn = 2;
        mw.p1Turn.summonCastThisTurn = true;
        mw.p1Turn.castJobsThisTurn.add("dragoon");
        mw.p1Turn.castNamesThisTurn.add("kain");
        mw.p1Turn.castCountByNameThisTurn.put("kain", 1);

        mw.opponent = new ComputerPlayer(mw);
        mw.onNextPhase();                    // END → P2's turn
        mw.gameState.setP1GameOver(true);    // parks the CPU's queued turn timer before it can fire

        assertEquals(0, mw.p1Turn.cardsCastThisTurn, "the count refreshes for the turn now beginning");
        assertFalse(mw.p1Turn.summonCastThisTurn);
        assertTrue(mw.p1Turn.castJobsThisTurn.isEmpty());
        assertTrue(mw.p1Turn.castNamesThisTurn.isEmpty());
        assertTrue(mw.p1Turn.castCountByNameThisTurn.isEmpty());
    }

    @Test
    void aceCastLimitDoesNotFollowP1IntoTheOpponentsTurn() {
        MainWindow mw = new MainWindow();
        mw.placeCardInForwardZone(makeFieldAbilityCard("Ace", "Light", "Forward", ACE_CAST_LIMIT_TEXT));
        advanceTo(mw, GameState.Player.P1, GameState.GamePhase.END);

        mw.p1Turn.cardsCastThisTurn = 2;
        assertTrue(mw.p1CastLimitReached(), "two casts on P1's own turn exhausts Ace's limit for that turn");

        mw.opponent = new ComputerPlayer(mw);
        mw.onNextPhase();                    // END → P2's turn
        mw.gameState.setP1GameOver(true);

        assertFalse(mw.p1CastLimitReached(),
                "P2's turn is a new turn — P1 may again cast Summons and Back Attack Characters");
    }

    @Test
    void castTrackingIsClearedAgainWhenP1sNextTurnBegins() {
        // The start-of-turn reset still matters in the other direction: casts P1 made while holding
        // priority during P2's turn belong to that turn, not to P1's.
        MainWindow mw = new MainWindow();
        mw.p1Turn.cardsCastThisTurn = 2;
        mw.p1Turn.summonCastThisTurn = true;
        mw.p1Turn.castNamesThisTurn.add("jinnai");

        mw.p1Turn.resetCastTracking();

        assertEquals(0, mw.p1Turn.cardsCastThisTurn);
        assertFalse(mw.p1Turn.summonCastThisTurn);
        assertTrue(mw.p1Turn.castNamesThisTurn.isEmpty());
    }

    // =========================================================================================
    // Dragoon 6-104C: "When Dragoon enters the field, choose 1 Forward. It gains First Strike
    // until the end of the turn."  Three defects surfaced from one game log:
    //   1. the AI aimed the buff at the human's Forward (unqualified "choose 1 Forward" made it
    //      prefer the opponent, which is only right for harmful effects);
    //   2. the log printed the raw enum ("First_strike");
    //   3. a First Strike blow that failed to break its target dropped the return damage entirely.
    // =========================================================================================

    private static final String DRAGOON_ETF_TEXT =
            "choose 1 Forward. It gains First Strike until the end of the turn.";

    // =========================================================================================
    // Titania 13-132S: "You can only cast Titania if you have a Forward, Backup, Monster and a
    // Summon in your Break Zone."  castRestrictionMet only ever read P1's zones, and the CPU's
    // planner never called it at all — so P2 could cast Titania (and every other restricted card)
    // with an empty Break Zone, while P1 was correctly held to the condition.
    // =========================================================================================

    private static final String TITANIA_TEXT =
            "You can only cast Titania if you have a Forward, Backup, Monster and a Summon in your "
            + "Break Zone (before paying the cost for Titania).[[br]]When Titania enters the field, "
            + "choose up to 2 Characters opponent controls. Dull them and Freeze them.";

    private static CardData makeTitania() {
        return makeTraitCard("Titania", "Water", "Forward", TITANIA_TEXT);
    }

    /** Fills {@code bz} with one card of each named type. */
    private static void stockBreakZone(List<CardData> bz, String... types) {
        for (String t : types) bz.add(makeTraitCard("BZ " + t, "Fire", t, ""));
    }

    @Test
    void titaniaBreakZoneRequirementIsReadFromTheCastersOwnZones() {
        MainWindow mw = new MainWindow();
        CardData titania = makeTitania();

        CastRestriction cr = titania.castRestriction();
        assertNotNull(cr);
        assertEquals(Set.of("Forward", "Backup", "Monster", "Summon"), cr.requiredBZTypes());

        // Neither player qualifies on an empty board.
        assertFalse(mw.castRestrictionMet(titania, true));
        assertFalse(mw.castRestrictionMet(titania, false));

        // Stocking P1's Break Zone must not let P2 cast it — the bug was reading P1's zones
        // regardless of who was casting.
        stockBreakZone(mw.gameState.getP1BreakZone(), "Forward", "Backup", "Monster", "Summon");
        assertTrue(mw.castRestrictionMet(titania, true));
        assertFalse(mw.castRestrictionMet(titania, false),
                "P2's requirement is P2's Break Zone, not P1's");

        // P2 with three of the four types still falls short — the reported case was a missing Monster.
        stockBreakZone(mw.gameState.getP2BreakZone(), "Forward", "Backup", "Summon");
        assertFalse(mw.castRestrictionMet(titania, false), "no Monster in P2's Break Zone");

        stockBreakZone(mw.gameState.getP2BreakZone(), "Monster");
        assertTrue(mw.castRestrictionMet(titania, false));
    }

    @Test
    void turnRelativeCastRestrictionsAreRelativeToTheCaster() {
        MainWindow mw = new MainWindow();
        // Gogo 27-099H — "You can only cast Gogo during your opponent's turn."
        CardData gogo = makeTraitCard("Gogo", "Water", "Forward", GOGO_BACK_ATTACK_TEXT);

        advanceTo(mw, GameState.Player.P1, GameState.GamePhase.MAIN_1);
        assertFalse(mw.castRestrictionMet(gogo, true),  "P1 may not cast it on P1's own turn");
        assertTrue(mw.castRestrictionMet(gogo, false),  "but P2 may, since it is P2's opponent's turn");

        advanceTo(mw, GameState.Player.P2, GameState.GamePhase.MAIN_1);
        assertTrue(mw.castRestrictionMet(gogo, true));
        assertFalse(mw.castRestrictionMet(gogo, false));
    }

    @Test
    void theCpuWillNotPlanACastItCannotLegallyMake() {
        MainWindow mw = new MainWindow();
        CardData titania = makeTitania();
        mw.gameState.getP2Hand().add(titania);
        // Enough CP that affordability is never what blocks the plan.
        for (String e : ActionResolverPatterns.ELEMENT_NAMES) mw.gameState.addP2Cp(e, 10);

        ComputerPlayer cpu = new ComputerPlayer(mw);
        assertFalse(cpu.hasLegalHandCast(),
                "P2's Break Zone is empty, so Titania is not a legal cast");

        stockBreakZone(mw.gameState.getP2BreakZone(), "Forward", "Backup", "Monster", "Summon");
        assertTrue(cpu.hasLegalHandCast(),
                "with all four types in the Break Zone the CPU may plan it");
    }

    // =========================================================================================
    // Chocobo 1-075C / 4-062C (+3000) and 1-076C (+2000): "When Chocobo forms a party and
    // attacks, Chocobo and all the Forwards forming a party with it gain +N power until the end
    // of the turn."  The "party attacks" trigger already parsed and fired; what did not resolve
    // was this phrasing of the effect.  It names the same set as the "all Forwards in that party"
    // wording already handled (Gippal, Celestia, Chocobo 9-050C) — the card forming the party is
    // itself in it — so both now route to applyCurrentPartyForwardsPowerBoost.
    // =========================================================================================

    private static final String CHOCOBO_PARTY_TEXT =
            "When Chocobo forms a party and attacks, Chocobo and all the Forwards forming a party "
            + "with it gain +3000 power until the end of the turn.";

    @Test
    void chocoboPartyBoostParsesAsAPartyAttackTrigger() {
        CardData chocobo = makeTraitCard("Chocobo", "Wind", "Forward", CHOCOBO_PARTY_TEXT);
        assertEquals(1, chocobo.autoAbilities().size());
        AutoAbility aa = chocobo.autoAbilities().get(0);
        assertEquals("party attacks", aa.trigger());
        assertEquals("Chocobo", aa.triggerCard());
        assertNotNull(ActionResolver.parse(aa.effectText(), chocobo),
                "the \"[self] and all the Forwards forming a party with it\" effect must resolve");
    }

    @Test
    void chocoboPartyBoostLiftsEveryMemberOfTheAttackingParty() {
        MainWindow mw = new MainWindow();
        CardData chocobo = makeTraitCard("Chocobo", "Wind", "Forward", CHOCOBO_PARTY_TEXT);
        CardData ally    = makeForward("Ally", "Wind", 3, 7000);
        CardData bystander = makeForward("Bystander", "Wind", 3, 7000);
        mw.placeCardInForwardZone(chocobo);   // idx 0
        mw.placeCardInForwardZone(ally);      // idx 1
        mw.placeCardInForwardZone(bystander); // idx 2
        for (CardData c : List.of(chocobo, ally, bystander)) mw.gameState.getIdentity().put(c, true);

        int chocoboBase   = mw.effectiveP1ForwardPower(0);
        int allyBase      = mw.effectiveP1ForwardPower(1);
        int bystanderBase = mw.effectiveP1ForwardPower(2);

        mw.autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(true, List.of(chocobo, ally));

        assertEquals(chocoboBase + 3000, mw.effectiveP1ForwardPower(0),
                "Chocobo boosts itself — it is a member of the party it formed");
        assertEquals(allyBase + 3000, mw.effectiveP1ForwardPower(1),
                "and every Forward forming the party with it");
        assertEquals(bystanderBase, mw.effectiveP1ForwardPower(2),
                "a Forward outside the party is untouched");
    }

    // =========================================================================================
    // Odin (XVI) 29-118L / 24-112L: "When Barnabas (XVI) primes into Odin (XVI), Odin (XVI) gains
    // "<ability>" (This effect does not end at the end of the turn.)"  Two things were wrong:
    // parseAutoAbilities deleted quoted trigger-bearing spans outright, so the grant read
    // "Odin (XVI) gains  (This effect…)" with the granted ability missing; and the engine only had
    // an until-end-of-turn grant primitive, so the permanent wording resolved to nothing.
    // =========================================================================================

    private static final String ODIN_29_118L_TEXT =
            "First Strike[[br]]When Odin (XVI) enters the field or when Barnabas (XVI) primes into "
            + "Odin (XVI), choose 1 Forward opponent controls. Break it.[[br]]When Barnabas (XVI) "
            + "primes into Odin (XVI), Odin (XVI) gains \"When a Character enters your opponent's "
            + "field, dull it and Freeze it.\" (This effect does not end at the end of the turn.)";

    private static final String ODIN_24_112L_TEXT =
            "Haste First Strike[[br]]When Barnabas (XVI) primes into Odin (XVI), Odin (XVI) gains "
            + "\"When Odin (XVI) attacks, activate Odin (XVI).\" and \"Odin (XVI) can attack twice "
            + "in the same turn.\" (This effect does not end at the end of the turn.)";

    @Test
    void aGrantKeepsTheQuotedAbilityItConfers() {
        // The quoted span must survive parsing — deleting it lost the whole point of the grant.
        List<AutoAbility> odin29 = CardData.parseAutoAbilities(ODIN_29_118L_TEXT);
        AutoAbility grant29 = odin29.stream()
                .filter(a -> a.effectText().contains("gains")).findFirst().orElseThrow();
        assertEquals("primed into", grant29.trigger());
        assertEquals("Barnabas (XVI)", grant29.triggerCard());
        assertTrue(grant29.effectText().contains(
                "\"When a Character enters your opponent's field, dull it and Freeze it.\""),
                "the granted ability is part of the effect, not a hole where the quote was");

        // Two quoted clauses joined by "and" both survive.
        AutoAbility grant24 = CardData.parseAutoAbilities(ODIN_24_112L_TEXT).get(0);
        assertTrue(grant24.effectText().contains("\"When Odin (XVI) attacks, activate Odin (XVI).\""));
        assertTrue(grant24.effectText().contains("\"Odin (XVI) can attack twice in the same turn.\""));
    }

    @Test
    void primingGrantResolvesAndOutlastsTheTurn() {
        CardData odin = makeTraitCard("Odin (XVI)", "Lightning", "Forward", ODIN_29_118L_TEXT);
        AutoAbility grant = odin.autoAbilities().stream()
                .filter(a -> a.effectText().contains("gains")).findFirst().orElseThrow();
        assertNotNull(ActionResolver.parse(grant.effectText(), odin),
                "the permanent grant wording must resolve");

        MainWindow mw = new MainWindow();
        mw.placeCardInForwardZone(odin);
        mw.gameState.getIdentity().put(odin, true);
        assertTrue(mw.effectiveAutoAbilities(odin).stream()
                .noneMatch(a -> a.trigger().equals("enters opponent's field")),
                "not granted until the priming happens");

        ActionResolver.parse(grant.effectText(), odin).accept(mw.buildGameContext(true));

        assertTrue(mw.effectiveAutoAbilities(odin).stream()
                .anyMatch(a -> a.trigger().equals("enters opponent's field")),
                "the granted trigger joins Odin's effective abilities");

        // "Does not end at the end of the turn" — an end-of-turn sweep must not take it away.
        mw.grantedFieldAbilities.clear();
        mw.grantedMaxAttacks.clear();
        assertTrue(mw.effectiveAutoAbilities(odin).stream()
                .anyMatch(a -> a.trigger().equals("enters opponent's field")),
                "the grant survives the end-of-turn clear-down");

        // Leaving the field does end it.
        mw.breakP1Forward(0);
        assertTrue(mw.effectiveAutoAbilities(odin).stream()
                .noneMatch(a -> a.trigger().equals("enters opponent's field")),
                "a Character that leaves the field loses what was granted to it");
    }

    @Test
    void aTwoClauseGrantAppliesBothHalves() {
        CardData odin = makeTraitCard("Odin (XVI)", "Lightning", "Forward", ODIN_24_112L_TEXT);
        AutoAbility grant = odin.autoAbilities().get(0);
        Consumer<GameContext> effect = ActionResolver.parse(grant.effectText(), odin);
        assertNotNull(effect, "both clauses are supported, so the grant resolves");

        MainWindow mw = new MainWindow();
        mw.placeCardInForwardZone(odin);
        mw.gameState.getIdentity().put(odin, true);
        assertEquals(1, mw.maxAttacksPerTurn(odin));

        effect.accept(mw.buildGameContext(true));

        assertTrue(mw.effectiveAutoAbilities(odin).stream().anyMatch(a -> a.trigger().equals("attacks")),
                "first clause — the attack trigger");
        assertEquals(2, mw.maxAttacksPerTurn(odin), "second clause — the second-attack permission");
    }

    // =========================================================================================
    // Jed 24-096R: "When Jed attacks, you may pay 《C》. If you do so, draw 1 card."  The optional
    // cost had no standalone parser — only a variant that applies after a "Choose 1 …" primary —
    // so the whole effect went unresolved. Cards whose payoff happened to be a search or a
    // play-from-hand looked fine only because those parsers match with find(): they located the
    // payoff inside the longer string and ran it without ever charging the cost.
    // =========================================================================================

    private static final String JED_TEXT =
            "If you have a 《C》, Jed gains +1000 power and Brave.[[br]]"
            + "When Jed attacks, you may pay 《C》. If you do so, draw 1 card.";

    @Test
    void jedsOptionalCrystalCostParses() {
        CardData jed = makeTraitCard("Jed", "Water", "Forward", JED_TEXT);
        AutoAbility aa = jed.autoAbilities().stream()
                .filter(a -> a.trigger().equals("attacks")).findFirst().orElseThrow();
        assertTrue(aa.youMay(), "\"you may\" is lifted onto the ability");
        assertEquals("pay 《C》. If you do so, draw 1 card.", aa.effectText());
        assertNotNull(ActionResolver.parse(aa.effectText(), jed));
    }

    @SuppressWarnings("unchecked")
    @Test
    void theOptionalCostIsChargedAndOnlyThenDoesTheEffectRun() {
        CardData jed = makeTraitCard("Jed", "Water", "Forward", JED_TEXT);
        AutoAbility aa = jed.autoAbilities().stream()
                .filter(a -> a.trigger().equals("attacks")).findFirst().orElseThrow();
        Consumer<GameContext> effect = ActionResolver.parse(aa.effectText(), jed);

        GameContext ctx = mock(GameContext.class);
        ArgumentCaptor<Consumer<GameContext>> onPay = ArgumentCaptor.forClass(Consumer.class);
        effect.accept(ctx);

        // One Crystal, no CP and no element — and nothing drawn until the cost is actually paid.
        verify(ctx).mayPayCostToEffect(eq(0), isNull(), eq(1), onPay.capture());
        verify(ctx, never()).drawCards(anyInt());

        onPay.getValue().accept(ctx);
        verify(ctx).drawCards(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void theSameWordingIsPricedForCpAndElementCostsToo() {
        CardData src = makeForward("Src", "Ice", 3, 7000);
        ArgumentCaptor<Consumer<GameContext>> onPay = ArgumentCaptor.forClass(Consumer.class);

        GameContext generic = mock(GameContext.class);
        ActionResolver.parse("pay 《4》. If you do so, draw 1 card.", src).accept(generic);
        verify(generic).mayPayCostToEffect(eq(4), isNull(), eq(0), onPay.capture());

        GameContext elemental = mock(GameContext.class);
        ActionResolver.parse("pay 《Fire》. If you do so, draw 1 card.", src).accept(elemental);
        verify(elemental).mayPayCostToEffect(eq(0), eq("Fire"), eq(0), onPay.capture());

        // A cost the payment primitive cannot express is declined outright rather than under-charged.
        assertNull(ActionResolver.parse("pay 《X》. If you do so, draw 1 card.", src),
                "an X cost has no fixed price");
        assertNull(ActionResolver.parse("pay 《Fire》《1》. If you do so, draw 1 card.", src),
                "a mixed element-plus-generic cost is not a single payment");
    }

    @Test
    void cannotBeBrokenGetsATraitTabOnTheField() {
        // The tab feed is data-driven: MainWindow filters Trait.values() through hasGlyph, so a
        // trait is on every slot type (both players' Forwards and Monsters) or none of them.
        assertTrue(shufflingway.graphics.TraitTab.hasGlyph(CardData.Trait.CANNOT_BE_BROKEN));
        assertEquals("Cannot Be Broken", CardData.Trait.CANNOT_BE_BROKEN.displayName());

        // And the trait reaches the feed from ordinary printed card text.
        CardData ardyn = makeTraitCard("Ardyn", "Dark", "Forward",
                "Ardyn cannot be broken.");
        assertTrue(ardyn.hasTrait(CardData.Trait.CANNOT_BE_BROKEN));

        // The damage-only variant is a separate trait and deliberately has no glyph yet — it must
        // not borrow this one, which would overstate the protection on the card.
        assertFalse(shufflingway.graphics.TraitTab.hasGlyph(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG));
    }

    @Test
    void traitNamesAreFormattedForTheLog() {
        assertEquals("First Strike", CardData.Trait.FIRST_STRIKE.displayName());
        assertEquals("Brave", CardData.Trait.BRAVE.displayName());
        assertEquals("Back Attack", CardData.Trait.BACK_ATTACK.displayName());
        assertEquals("Cannot Be Broken", CardData.Trait.CANNOT_BE_BROKEN.displayName());
    }

    @Test
    void aPureBuffIsRecognizedAsBenefitingItsTarget() {
        assertTrue(ActionResolver.chooseEffectBenefitsTarget(DRAGOON_ETF_TEXT));
        assertTrue(ActionResolver.chooseEffectBenefitsTarget(
                "Choose 1 Forward. It gains +2000 power until the end of the turn."));
        assertTrue(ActionResolver.chooseEffectBenefitsTarget(
                "Choose up to 2 Category V Characters. Activate them."));
    }

    @Test
    void harmfulAndMixedEffectsAreNotTreatedAsBuffs() {
        assertFalse(ActionResolver.chooseEffectBenefitsTarget(
                "Choose 1 Forward. Deal it 5000 damage."));
        assertFalse(ActionResolver.chooseEffectBenefitsTarget(
                "Choose 1 Forward. Its power becomes 4000 until the end of the turn."));
        assertFalse(ActionResolver.chooseEffectBenefitsTarget(
                "Choose 1 Forward. Break it."),
                "a break must never be pointed at the AI's own board");
        assertFalse(ActionResolver.chooseEffectBenefitsTarget(
                "Choose 1 Forward. Deal it 3000 damage. It gains Brave until the end of the turn."),
                "a mixed effect is not a pure buff");
    }

    @Test
    void dragoonMarksItsBuffAsSelfPreferredWhenResolved() {
        Consumer<GameContext> fn = ActionResolver.parse(DRAGOON_ETF_TEXT, null);
        assertNotNull(fn, "Expected Dragoon's enter-the-field ability to parse");

        GameContext ctx = mock(GameContext.class);
        when(ctx.consumePreloadedTargets())
                .thenReturn(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));

        fn.accept(ctx);

        verify(ctx).setAiPrefersOwnTargets(true);
    }

    @Test
    void aDamageEffectDoesNotMarkItselfAsSelfPreferred() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 5000 damage.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        when(ctx.consumePreloadedTargets())
                .thenReturn(List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD)));

        fn.accept(ctx);

        verify(ctx, never()).setAiPrefersOwnTargets(anyBoolean());
    }

    /** Places a Forward on P2's field carrying {@code damage} already accumulated on it. */
    private static void placeDamagedP2Forward(MainWindow mw, CardData fwd, int damage) {
        mw.gameState.getIdentity().put(fwd, false);
        mw.placeP2CardInForwardZone(fwd);
        mw.p2ForwardDamage.set(mw.p2ForwardCards.indexOf(fwd), damage);
    }

    @Test
    void aSurvivingAttackerStillTakesDamageFromAFirstStrikeBlocker() {
        MainWindow mw = new MainWindow();
        CardData thancred = makeForward("Thancred", "Water", 4, 8000);          // P2, attacker
        CardData gaius    = makeForwardWithTraits("Gaius", "Earth", 6000,
                Set.of(CardData.Trait.FIRST_STRIKE));                            // P1, blocker
        placeDamagedP2Forward(mw, thancred, 0);
        placeDamagedP1Forward(mw, gaius, 0);

        mw.resolveCombat(thancred, false, 0, gaius, true, 0);

        assertEquals(1, mw.p2ForwardCards.size(), "6000 damage vs 8000 power — Thancred survives");
        assertEquals(6000, mw.p2ForwardDamage.get(0),
                "Gaius struck first but did not break Thancred, so the damage still lands");
        assertTrue(mw.p1ForwardCards.isEmpty(), "Thancred strikes back for 8000 and breaks Gaius");
    }

    @Test
    void aFirstStrikeBlockerThatBreaksTheAttackerTakesNoReturnDamage() {
        MainWindow mw = new MainWindow();
        CardData weak  = makeForward("Weak", "Water", 2, 3000);                  // P2, attacker
        CardData gaius = makeForwardWithTraits("Gaius", "Earth", 8000,
                Set.of(CardData.Trait.FIRST_STRIKE));                            // P1, blocker
        placeDamagedP2Forward(mw, weak, 0);
        placeDamagedP1Forward(mw, gaius, 0);

        mw.resolveCombat(weak, false, 0, gaius, true, 0);

        assertTrue(mw.p2ForwardCards.isEmpty(), "8000 ≥ 3000 — the attacker breaks");
        assertEquals(0, mw.p1ForwardDamage.get(0),
                "First Strike broke the attacker before it could strike back");
    }

    @Test
    void aSurvivingBlockerStillTakesDamageFromAFirstStrikeAttacker() {
        MainWindow mw = new MainWindow();
        CardData striker = makeForwardWithTraits("Striker", "Water", 5000,
                Set.of(CardData.Trait.FIRST_STRIKE));                            // P1, attacker
        CardData wall    = makeForward("Wall", "Earth", 4, 9000);                // P2, blocker
        placeDamagedP1Forward(mw, striker, 0);
        placeDamagedP2Forward(mw, wall, 0);

        mw.resolveCombat(striker, true, 0, wall, false, 0);

        assertEquals(5000, mw.p2ForwardDamage.get(0),
                "the first strike did not break the 9000-power blocker, so its damage lands");
        assertTrue(mw.p1ForwardCards.isEmpty(), "and the surviving blocker strikes back for 9000");
    }

    // =========================================================================================
    // Krile (XIV) 6-071H: "《Earth》《1》《Dull》: Choose 1 Forward you control. During this turn,
    // it cannot be returned to its owner's hand by your opponent's Summons or abilities."
    //
    // The CPU was dulling Krile for this while controlling no Forwards at all. Two separate
    // reasons it should not: nothing to target, and the shield only pays off while an opponent's
    // effect is on the stack — a window the CPU never uses, since it passes priority.
    // =========================================================================================

    private static final String KRILE_XIV_TEXT =
            "Choose 1 Forward you control. During this turn, it cannot be returned to its "
            + "owner's hand by your opponent's Summons or abilities.";

    @Test
    void krilesShieldIsRecognizedAsAnOwnForwardProtection() {
        assertTrue(ActionResolver.targetsOnlyOwnForwards(KRILE_XIV_TEXT));
        assertTrue(ActionResolver.isOwnForwardProtectionEffect(KRILE_XIV_TEXT));
    }

    @Test
    void otherOpponentFacingShieldsAreRecognizedToo() {
        assertTrue(ActionResolver.isOwnForwardProtectionEffect(
                "Choose 1 Forward you control. It cannot be chosen by your opponent's Summons "
                + "or abilities this turn."));
        assertTrue(ActionResolver.isOwnForwardProtectionEffect(
                "Choose 1 Forward you control. It cannot be broken by your opponent's abilities "
                + "this turn."));
    }

    @Test
    void proactivelyUsefulOwnForwardBuffsAreNotTreatedAsShields() {
        assertTrue(ActionResolver.targetsOnlyOwnForwards(
                "Choose 1 Fire Forward you control. It gains +1000 power until the end of the turn."),
                "a buff still no-ops with no Forwards, so it is worth gating on an empty board");
        assertFalse(ActionResolver.isOwnForwardProtectionEffect(
                "Choose 1 Fire Forward you control. It gains +1000 power until the end of the turn."),
                "a power buff pays off immediately — it is not a reactive shield");
        assertFalse(ActionResolver.isOwnForwardProtectionEffect(
                "Choose 1 Forward you control. Activate it."));
    }

    @Test
    void effectsReachingTheOpponentsBoardAreNotOwnForwardOnly() {
        assertFalse(ActionResolver.targetsOnlyOwnForwards(
                "Choose 1 Forward you control and 1 Forward opponent controls. Break them."),
                "an effect that also reaches the opponent's board still does something");
        assertFalse(ActionResolver.targetsOnlyOwnForwards(
                "Choose 1 Forward. Deal it 5000 damage."),
                "unqualified targeting is not own-side only");
        assertFalse(ActionResolver.isOwnForwardProtectionEffect(
                "Choose 1 Forward opponent controls. It cannot be chosen by your opponent's "
                + "abilities this turn."),
                "the shield must protect the controller's own Forward to be reactive-only");
    }

    @Test
    void aShieldAgainstNobodyInParticularIsNotGated() {
        assertFalse(ActionResolver.isOwnForwardProtectionEffect(
                "Choose 1 Forward you control. During this turn, the next damage dealt to it is "
                + "reduced by 2000 instead."),
                "damage reduction pays off in combat the CPU initiates — not opponent-gated");
    }

    @Test
    void aShieldBundledWithAnImmediateBenefitIsStillWorthUsing() {
        // 20-109H — the power boost pays off on its own, so the whole ability must not be gated.
        assertFalse(ActionResolver.isOwnForwardProtectionEffect(
                "Choose 1 Category IV Forward you control. Until the end of the turn, it gains "
                + "+1000 power and \"This Forward cannot be chosen by your opponent's abilities.\""),
                "a shield packaged with +1000 power is not reactive-only");
        // 10-045C — activating your own Forwards is a real tempo gain regardless of the shield.
        assertFalse(ActionResolver.isOwnForwardProtectionEffect(
                "Activate all the Forwards you control. They cannot be chosen by your opponent's "
                + "Summons or abilities this turn."),
                "activation is an immediate benefit");
    }

    @Test
    void turnLongConditionalsAreNotGatedOnAnEmptyBoard() {
        // A Forward played later the same turn would still benefit, so an empty board now is not
        // proof the ability does nothing.
        assertFalse(ActionResolver.targetsOnlyOwnForwards(
                "During this turn, if a Forward you control is dealt damage, reduce the damage "
                + "by 2000 instead."),
                "no immediate target — the effect waits for whatever P2 plays next");
        assertTrue(ActionResolver.targetsOnlyOwnForwards(
                "All the Forwards you control gain +1000 power until the end of the turn."),
                "a board-wide pump does act on the Forwards standing right now");
    }

    // =========================================================================================
    // "If you don't pay 《…》, [consequence]" — an optional cost the controller may pay to avert a
    // consequence: Umaro 15-107H and Cecil 15-073H (《C》), Umaro 8-024C (《Ice》), Leon 28-056C (《2》,
    // behind a control check).  Before this gate existed the consequence patterns matched the text
    // on their own, so Cecil dealt itself damage and Leon broke with no chance to pay.
    // =========================================================================================

    private static final String UMARO_BZ_TEXT =
            "When Umaro enters the field, if you don't pay 《C》, put Umaro into the Break Zone.";
    private static final String UMARO_BZ_ETB =
            "if you don't pay 《C》, put Umaro into the Break Zone.";
    private static final String UMARO_DISCARD_ETB =
            "if you don't pay 《Ice》, discard 1 card from your hand.";
    private static final String CECIL_ETB =
            "if you don't pay 《C》, Cecil deals you 1 point of damage.";
    private static final String LEON_ETB =
            "if you don't control a Card Name Maria and if you don't pay 《2》, put Leon into the Break Zone.";

    private static CardData makeUmaro() {
        return makeForward("Umaro", "Ice", 3, 7000);
    }

    @Test
    void payOrElseAbilitiesParseAsAGateRatherThanTheBareConsequence() {
        List<AutoAbility> umaro = CardData.parseAutoAbilities(UMARO_BZ_TEXT);
        assertEquals(1, umaro.size());
        assertEquals("enters the field", umaro.get(0).trigger());
        assertEquals(UMARO_BZ_ETB, umaro.get(0).effectText());

        CardData src = makeUmaro();
        assertEquals("IfNotPayOrElse", ActionResolver.matchedPatternName(UMARO_BZ_ETB, src));
        assertEquals("IfNotPayOrElse", ActionResolver.matchedPatternName(UMARO_DISCARD_ETB, src));
        assertEquals("IfNotPayOrElse", ActionResolver.matchedPatternName(CECIL_ETB, makeForward("Cecil", "Dark", 3, 7000)));
        assertNotNull(ActionResolver.parse(LEON_ETB, makeForward("Leon", "Dark", 2, 5000)),
                "Leon resolves through the control gate into the pay gate");
    }

    @Test
    void payOrElseOffersTheRightCostAndRunsTheConsequenceOnlyWhenUnpaid() {
        CardData umaro = makeUmaro();
        Consumer<GameContext> crystalGate = ActionResolver.parse(UMARO_BZ_ETB, umaro);
        assertNotNull(crystalGate);

        // 《C》 is a Crystal cost, not CP.
        GameContext ctx = mock(GameContext.class);
        crystalGate.accept(ctx);
        ArgumentCaptor<Runnable> notPaid = ArgumentCaptor.forClass(Runnable.class);
        verify(ctx).mayPayCostOrElse(eq(0), isNull(), eq(1), notPaid.capture());
        verify(ctx, never()).breakSourceCard(any());   // nothing happens until the cost goes unpaid
        notPaid.getValue().run();
        verify(ctx).breakSourceCard(umaro);

        // 《Ice》 is one CP of an element; the consequence is the discard.
        GameContext ctx2 = mock(GameContext.class);
        ActionResolver.parse(UMARO_DISCARD_ETB, umaro).accept(ctx2);
        ArgumentCaptor<Runnable> notPaid2 = ArgumentCaptor.forClass(Runnable.class);
        verify(ctx2).mayPayCostOrElse(eq(0), eq("Ice"), eq(0), notPaid2.capture());
        notPaid2.getValue().run();
        verify(ctx2).selfDiscard(1);
    }

    // Vincent 2-078R: "When Vincent attacks or blocks, you may pay 《2》. If you don't pay 《2》,
    // Vincent breaks after the attack or the block and doesn't deal any damage."
    private static final String VINCENT_TEXT =
            "When Vincent attacks or blocks, you may pay 《2》. If you don't pay 《2》, Vincent breaks "
            + "after the attack or the block and doesn't deal any damage. [[br]]"
            + "When Vincent attacks or blocks, choose 1 Forward. Deal it 4000 damage.";
    private static final String VINCENT_GATE =
            "pay 《2》. If you don't pay 《2》, Vincent breaks after the attack or the block and doesn't deal any damage.";

    @Test
    void vincentsPrintedYouMayIsThePaymentNotAnOptionalAbility() {
        List<AutoAbility> abilities = CardData.parseAutoAbilities(VINCENT_TEXT);
        AutoAbility gate = abilities.stream()
                .filter(a -> a.effectText().startsWith("pay")).findFirst().orElseThrow();
        assertFalse(gate.youMay(),
                "declining an optional ability would skip the consequence — the gate asks about the cost itself");
        assertEquals("IfNotPayOrElse",
                ActionResolver.matchedPatternName(gate.effectText(), makeForward("Vincent", "Dark", 3, 7000)));
    }

    @Test
    void vincentUnpaidDealsNoCombatDamageAndBreaksAfterTheBattle() {
        MainWindow mw = new MainWindow();
        CardData vincent = makeForward("Vincent", "Dark", 3, 7000);
        mw.placeCardInForwardZone(vincent);
        mw.gameState.getIdentity().put(vincent, true);

        // P1 cannot assemble 《2》, so the consequence lands as soon as the trigger resolves.
        ActionResolver.parse(VINCENT_GATE, vincent).accept(mw.buildGameContext(true));
        assertTrue(mw.dealsNoCombatDamageSet.contains(vincent), "deals no damage for this battle");
        assertTrue(mw.breakAfterCombatSet.contains(vincent), "queued to break once the battle ends");

        // Damage he would deal in the battle is zeroed while the mark is up.
        assertEquals(0, mw.modifyOutgoingCombatDamage(true, 0, 7000, makeForward("Blocker", "Ice", 3, 9000)));

        mw.resolvePostCombatBreaks();
        assertTrue(mw.p1ForwardCards.isEmpty(), "broken once the battle finished");
        assertTrue(mw.gameState.getP1BreakZone().contains(vincent));
        assertTrue(mw.dealsNoCombatDamageSet.isEmpty(), "battle-scoped marks are cleared");
        assertTrue(mw.breakAfterCombatSet.isEmpty());
    }

    @Test
    void aVincentThatAlreadyDiedInCombatIsNotBrokenTwice() {
        MainWindow mw = new MainWindow();
        CardData vincent = makeForward("Vincent", "Dark", 3, 7000);
        mw.breakAfterCombatSet.add(vincent);   // marked, but the battle already killed him
        mw.resolvePostCombatBreaks();          // must not throw looking for a card that is gone
        assertTrue(mw.gameState.getP1BreakZone().isEmpty());
        assertTrue(mw.breakAfterCombatSet.isEmpty());
    }

    @Test
    void theAiPaysACrystalWhenItHasOneAndBreaksWhenItDoesNot() {
        // No Crystal — nothing to choose, so the consequence applies.
        MainWindow broke = new MainWindow();
        CardData umaro1 = makeUmaro();
        broke.placeP2CardInForwardZone(umaro1);
        broke.gameState.getIdentity().put(umaro1, false);   // owner, needed once it hits the Break Zone
        ActionResolver.parse(UMARO_BZ_ETB, umaro1).accept(broke.buildGameContext(false));
        assertTrue(broke.p2ForwardCards.isEmpty(), "unpaid — Umaro was broken");
        assertTrue(broke.gameState.getP2BreakZone().contains(umaro1));

        // Holding a Crystal — the AI pays it and stays on the field.
        MainWindow paid = new MainWindow();
        CardData umaro2 = makeUmaro();
        paid.placeP2CardInForwardZone(umaro2);
        paid.gameState.addP2Crystals(1);
        ActionResolver.parse(UMARO_BZ_ETB, umaro2).accept(paid.buildGameContext(false));
        assertEquals(List.of(umaro2), paid.p2ForwardCards, "paid — Umaro stayed");
        assertEquals(0, paid.gameState.getP2Crystals(), "the Crystal was spent");
    }

    // =========================================================================================
    // Ultimecia 27-092H: "…choose 1 Forward. You gain control of it. Then, if you don't pay 《1》 for
    // each CP required to cast chosen Forward, put it into the Break Zone."  The cost is the stolen
    // card's own cost, so it can only be priced once the target is picked — and the card must be
    // tracked by identity, because gaining control moves it to the other side of the board.
    // =========================================================================================

    private static final String ULTIMECIA_STEAL =
            "choose 1 Forward. You gain control of it. Then, if you don't pay 《1》 for each CP "
            + "required to cast chosen Forward, put it into the Break Zone.";

    @Test
    void ultimeciaBreaksTheStolenForwardWhenItsCostGoesUnpaid() {
        MainWindow mw = new MainWindow();
        CardData ultimecia = makeForward("Ultimecia", "Dark", 5, 9000);
        CardData victim    = makeForward("Victim", "Dark", 4, 8000);
        mw.placeP2CardInForwardZone(victim);
        mw.gameState.getIdentity().put(victim, false);   // P2 owns it

        GameContext ctx = mw.buildGameContext(true);
        ctx.preloadTargets(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));
        ActionResolver.parse(ULTIMECIA_STEAL, ultimecia).accept(ctx);

        // P1 has no CP source, so the 《4》 cannot be paid and the Forward it just took is broken.
        assertTrue(mw.p1ForwardCards.isEmpty(), "the stolen Forward did not stay");
        assertTrue(mw.p2ForwardCards.isEmpty());
        assertTrue(mw.gameState.getP2BreakZone().contains(victim),
                "a broken card goes to its owner's Break Zone, not its controller's");
    }

    // =========================================================================================
    // Combat sub-steps track priority, not declarations. Declaring an attacker does not advance to
    // Declare Blockers — that only happens once both players have passed on the declaration, and
    // the same holds for the block: turn player, then opponent, then damage.
    // =========================================================================================

    /** Puts {@code mw} in the Attack Phase with {@code turnPlayerIsP1} to move, at Declare Attackers. */
    private static void enterAttackDeclarationStep(MainWindow mw, boolean turnPlayerIsP1) {
        mw.gameState.startFirstTurn(turnPlayerIsP1 ? GameState.Player.P1 : GameState.Player.P2);
        mw.gameState.advancePhase();   // DRAW
        mw.gameState.advancePhase();   // MAIN_1
        mw.gameState.advancePhase();   // ATTACK
        assertEquals(GameState.GamePhase.ATTACK, mw.gameState.getCurrentPhase());
    }

    @Test
    void declaringAnAttackerHoldsOnTheDeclareAttackersStep() {
        MainWindow mw = new MainWindow();
        enterAttackDeclarationStep(mw, true);
        CardData attacker = makeForward("Attacker", "Fire", 3, 7000);
        mw.placeCardInForwardZone(attacker);
        mw.gameState.getIdentity().put(attacker, true);
        mw.attackSubStep = 1;

        mw.executeP1Attack(List.of(0));

        assertEquals(1, mw.attackSubStep,
                "the tracker stays on Declare Attackers until priority has been passed");
        assertEquals(List.of(attacker), mw.p1DeclaredAttackers, "the attacker is on record either way");
    }

    @Test
    void p2sDeclarationAlsoHoldsOnTheDeclareAttackersStep() {
        MainWindow mw = new MainWindow();
        enterAttackDeclarationStep(mw, false);
        CardData attacker = makeForward("P2 Attacker", "Fire", 3, 7000);
        mw.placeP2CardInForwardZone(attacker);
        mw.gameState.getIdentity().put(attacker, false);
        CardData blocker = makeForward("Blocker", "Ice", 3, 7000);
        mw.placeCardInForwardZone(blocker);
        mw.gameState.getIdentity().put(blocker, true);

        mw.initP1BlockDeclaration(attacker, 0, () -> { });

        assertEquals(1, mw.attackSubStep,
                "P2's declaration does not open the block step until both players have passed");
    }

    // =========================================================================================
    // A party attack sets pendingP2PartyIndices and leaves pendingP2AttackerIdx at -1, so every
    // attacker-side blocking restriction has to read the party rather than that single index —
    // otherwise the checks blew up on p2ForwardCards.get(-1) and no Forward ever came back as a
    // legal blocker, leaving "Take Damage" as P1's only move against a party.
    // =========================================================================================

    /** Opens the block step against a P2 party made of every P2 Forward on the field. */
    private static void openPartyBlockStep(MainWindow mw) {
        List<Integer> party = new ArrayList<>();
        for (int i = 0; i < mw.p2ForwardCards.size(); i++) party.add(i);
        mw.pendingP2PartyIndices = party;
    }

    // =========================================================================================
    // A Forward leaving the field shifts every survivor above it down one slot, and all of P1's
    // per-slot state is keyed by that slot index.  Six removal paths each maintained their own
    // hand-written copy of the update list and had drifted apart: p1ForwardTempJobs was dropped by
    // only two of them (leaving that list a slot longer than its siblings, so every Forward above
    // the hole read the wrong entry), the uniqueness-rule path skipped the two "cannot be blocked"
    // collections. All six now route through removeP1ForwardSlotState. (Attack counts used to be
    // slot-keyed and belonged on this list too; they are keyed by card instance now, so a break
    // cannot misattribute them at all.)
    // =========================================================================================

    /** Every P1 per-slot list, so a test can assert they stay the same length as each other. */
    private static Map<String, Integer> p1SlotListSizes(MainWindow mw) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put("cards",         mw.p1ForwardCards.size());
        sizes.put("states",        mw.p1ForwardStates.size());
        sizes.put("playedOnTurn",  mw.p1ForwardPlayedOnTurn.size());
        sizes.put("damage",        mw.p1ForwardDamage.size());
        sizes.put("powerBoost",    mw.p1ForwardPowerBoost.size());
        sizes.put("powerReduce",   mw.p1ForwardPowerReduction.size());
        sizes.put("tempTraits",    mw.p1ForwardTempTraits.size());
        sizes.put("removedTraits", mw.p1ForwardRemovedTraits.size());
        sizes.put("tempJobs",      mw.p1ForwardTempJobs.size());
        sizes.put("primedTop",     mw.p1ForwardPrimedTop.size());
        sizes.put("frozen",        mw.p1ForwardFrozen.size());
        return sizes;
    }

    private static void assertP1SlotListsAligned(MainWindow mw, int expected, String after) {
        p1SlotListSizes(mw).forEach((name, size) ->
                assertEquals(expected, size, name + " is out of step with the Forward zone after " + after));
    }

    private static MainWindow sixForwardBoard() {
        MainWindow mw = new MainWindow();
        enterAttackDeclarationStep(mw, false);
        for (int i = 0; i < 2; i++) {
            CardData f = makeForward("P2-" + i, "Fire", 3, 7000);
            mw.placeP2CardInForwardZone(f);
            mw.gameState.getIdentity().put(f, false);
        }
        for (int i = 0; i < 6; i++) {
            CardData f = makeForward("P1-" + i, "Ice", 3, 7000);
            mw.placeCardInForwardZone(f);
            mw.gameState.getIdentity().put(f, true);
        }
        return mw;
    }

    @Test
    void everyPathThatRemovesAForwardKeepsThePerSlotListsAligned() {
        // The bounce, deck-return and under-deck paths used to leave tempJobs one entry long.
        MainWindow mw = sixForwardBoard();
        mw.returnP1ForwardToHand(2);
        assertP1SlotListsAligned(mw, 5, "a return to hand");

        mw.returnP1ForwardToDeck(1, false);
        assertP1SlotListsAligned(mw, 4, "a return to the deck");

        mw.returnP1ForwardUnderDeckTop(0, 2);
        assertP1SlotListsAligned(mw, 3, "a return under the top of the deck");

        mw.breakP1Forward(1);
        assertP1SlotListsAligned(mw, 2, "a break");
    }

    @Test
    void breakingAForwardReindexesTheSurvivorsRestrictions() {
        MainWindow mw = sixForwardBoard();
        // Restrict the Forwards above the one about to break.
        mw.p1ForwardCannotBlock.add(4);
        mw.p1ForwardCannotBeBlocked.add(5);
        mw.grantExtraAttack(mw.p1ForwardCards.get(3));

        CardData survivor4 = mw.p1ForwardCards.get(4);
        CardData survivor5 = mw.p1ForwardCards.get(5);
        CardData survivor3 = mw.p1ForwardCards.get(3);

        mw.breakP1Forward(2);

        assertSame(survivor4, mw.p1ForwardCards.get(3));
        assertTrue(mw.p1ForwardCannotBlock.contains(3),
                "the restriction follows its Forward down into slot 3");
        assertFalse(mw.p1ForwardCannotBlock.contains(4),
                "and no longer lands on the Forward that moved into slot 4");

        assertSame(survivor5, mw.p1ForwardCards.get(4));
        assertTrue(mw.p1ForwardCannotBeBlocked.contains(4));

        assertSame(survivor3, mw.p1ForwardCards.get(2));
        assertEquals(2, mw.attacksAllowed(survivor3),
                "a pending extra attack follows its Forward — it is keyed by card, not by slot");
        assertEquals(1, mw.attacksAllowed(mw.p1ForwardCards.get(3)),
                "and does not land on whoever moved into the vacated slot");
    }

    @Test
    void everySurvivingForwardCanStillBeChosenAsABlockerAfterACombatBreak() {
        MainWindow mw = sixForwardBoard();
        openPartyBlockStep(mw);
        for (int i = 0; i < 6; i++) assertTrue(mw.isForwardBlockSelectable(i));

        // P2's attacker and P1's blocker trade lethal damage; both leave the field.
        mw.p1BlockingIdx = 2;
        mw.resolveCombat(mw.p2ForwardCards.get(0), false, 0, mw.p1ForwardCards.get(2), true, 2);
        mw.p1BlockingIdx = -1;
        assertEquals(5, mw.p1ForwardCards.size());
        assertEquals(1, mw.p2ForwardCards.size());

        for (int i = 0; i < 5; i++)
            assertTrue(mw.isForwardBlockSelectable(i),
                    mw.p1ForwardCards.get(i).name() + " must still be a legal blocker for the next attack");
    }

    @Test
    void aForwardCanBeChosenAsBlockerAgainstAPartyAttack() {
        MainWindow mw = new MainWindow();
        enterAttackDeclarationStep(mw, false);
        mw.placeP2CardInForwardZone(makeForward("Party A", "Fire", 3, 7000));
        mw.placeP2CardInForwardZone(makeForward("Party B", "Fire", 3, 5000));
        CardData blocker = makeForward("Blocker", "Ice", 3, 8000);
        mw.placeCardInForwardZone(blocker);
        mw.gameState.getIdentity().put(blocker, true);

        openPartyBlockStep(mw);

        assertTrue(mw.isForwardBlockSelectable(0),
                "an active, unrestricted Forward may block a party attack");
    }

    @Test
    void aPartyMemberThatCannotBeBlockedGatesTheWholeParty() {
        MainWindow mw = new MainWindow();
        enterAttackDeclarationStep(mw, false);
        mw.placeP2CardInForwardZone(makeForward("Party A", "Fire", 3, 7000));
        mw.placeP2CardInForwardZone(makeForward("Party B", "Fire", 3, 5000));
        CardData blocker = makeForward("Blocker", "Ice", 3, 8000);
        mw.placeCardInForwardZone(blocker);
        mw.gameState.getIdentity().put(blocker, true);

        openPartyBlockStep(mw);
        mw.p2ForwardCannotBeBlocked.add(1);

        assertFalse(mw.isForwardBlockSelectable(0),
                "blocking the party means blocking every member, so one unblockable member stops it");
    }

    @Test
    void aDullForwardStillCannotBlockAParty() {
        MainWindow mw = new MainWindow();
        enterAttackDeclarationStep(mw, false);
        mw.placeP2CardInForwardZone(makeForward("Party A", "Fire", 3, 7000));
        mw.placeP2CardInForwardZone(makeForward("Party B", "Fire", 3, 5000));
        CardData blocker = makeForward("Blocker", "Ice", 3, 8000);
        mw.placeCardInForwardZone(blocker);
        mw.gameState.getIdentity().put(blocker, true);
        mw.p1ForwardStates.set(0, CardState.DULL);

        openPartyBlockStep(mw);

        assertFalse(mw.isForwardBlockSelectable(0), "a dull Forward cannot block, party or not");
    }

    // =========================================================================================
    // Louisoix 5-120C: "…you may search for 1 Card Name Alisaie or Card Name Alphinaud and add it
    // to your hand."  The name capture ran to the trailing "and", so the filter became the single
    // unmatchable name "Alisaie or Card Name Alphinaud" and the search found nothing. Names are now
    // split into the pipe-separated form the card-name filter already understood.
    // =========================================================================================

    /** Runs a deck-search effect against a mock and returns the card-name filter it asked for. */
    private static String searchNameFilterFor(String effectText, String sourceName) {
        Consumer<GameContext> fn = ActionResolver.parse(effectText, makeForward(sourceName, "Ice", 3, 7000));
        assertNotNull(fn, "search effect should parse: " + effectText);
        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        ArgumentCaptor<String> nameFilter = ArgumentCaptor.forClass(String.class);
        verify(ctx).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyInt(), any(), nameFilter.capture(), any(), any(), any(), any(), any(),
                any(), anyInt(), anyBoolean(), anyBoolean());
        return nameFilter.getValue();
    }

    @Test
    void aTwoNameSearchLooksForEitherName() {
        assertEquals("Alisaie|Alphinaud", searchNameFilterFor(
                "search for 1 Card Name Alisaie or Card Name Alphinaud and add it to your hand.", "Louisoix"));
        // The filter format is the one the matcher already splits on.
        CardData alphinaud = makeForward("Alphinaud", "Ice", 2, 5000);
        assertTrue(CardFilters.meetsCardNameFilter(alphinaud, "Alisaie|Alphinaud"));
        assertFalse(CardFilters.meetsCardNameFilter(alphinaud, "Alisaie or Card Name Alphinaud"),
                "the old single-name filter could never match either card");
    }

    @Test
    void longerNameListsSplitOnEveryPrintedJoiner() {
        // Comma with no space, comma then "or" (Refia 10-128L).
        assertEquals("Arc|Ingus|Luneth", searchNameFilterFor(
                "search for 1 Card Name Arc,Card Name Ingus, or Card Name Luneth of cost 4 or less "
                + "and play it onto the field.", "Refia"));
        // Comma then plain "or", with a parenthesised name (Nero (XIV), 9-007H).
        assertEquals("Nero (XIV)|Livia|Rhitahtyn", searchNameFilterFor(
                "search for 1 Card Name Nero (XIV), Card Name Livia, or Card Name Rhitahtyn "
                + "and add it to your hand.", "Gaius"));
        // Repeated "or" with no commas (Minwu 6-103H).
        assertEquals("Scott|Minwu|Josef", searchNameFilterFor(
                "search for 1 Card Name Scott or Card Name Minwu or Card Name Josef and add it to your hand.", "Hilda"));
    }

    @Test
    void aNameListMixedWithAJobKeepsBothFilters() {
        // 26-058H — two names OR a job; the names must still split.
        assertEquals("Ashe|Basch", searchNameFilterFor(
                "search for 1 Card Name Ashe, Card Name Basch or Job Judge and add it to your hand.", "Larsa"));
    }

    @Test
    void anOtherThanClauseEndsTheNameRatherThanJoiningIt() {
        // Cyan 11-003R — "other than Card Name Cyan" is an exclusion, not part of the name.
        assertEquals("Samurai", searchNameFilterFor(
                "search for 1 Job Samurai or Card Name Samurai other than Card Name Cyan and add it to your hand.",
                "Cyan"));
    }

    @Test
    void singleNameSearchesAreUnchanged() {
        assertEquals("Ovjang", searchNameFilterFor(
                "search for 1 Card Name Ovjang and add it to your hand.", "Aphmau"));
    }

    // =========================================================================================
    // Libroarian 8-084R: "When Libroarian enters the field, remove the top 4 cards of your deck
    // from the game. / At the end of your turn, add 1 card removed by the previous effect to your
    // hand. Then, if there are no more cards removed by the previous effect left, put Libroarian
    // into the Break Zone."  The second ability refers back to what the first removed, so the
    // removal is recorded against the source card.
    // =========================================================================================

    private static final String LIBROARIAN_TEXT =
            "When Libroarian enters the field, remove the top 4 cards of your deck from the game.[[br]] "
            + "At the end of your turn, add 1 card removed by the previous effect to your hand. Then, if "
            + "there are no more cards removed by the previous effect left, put Libroarian into the Break Zone.";

    private static CardData makeLibroarian() {
        return makeAutoAbilityForward("Libroarian", LIBROARIAN_TEXT);
    }

    @Test
    void aBareDeckTopRemovalIsNotReadAsACardName() {
        // "Remove [CardName] from the game" is loose enough to swallow "the top 4 cards of your
        // deck", which removed nothing at all. Every bare deck-top removal was affected.
        CardData lib = makeLibroarian();
        assertEquals("RemoveTopOfDeckFromGame",
                ActionResolver.matchedPatternName("remove the top 4 cards of your deck from the game.", lib));
        assertEquals("RemoveTopOfDeckFromGame",
                ActionResolver.matchedPatternName("remove the top 10 cards of your deck from the game.", lib));
        // Genuine named removals still resolve as such.
        assertEquals("RemoveNamedFromGame",
                ActionResolver.matchedPatternName("Remove Libroarian from the game.", lib));
    }

    // Lightning 16-124H (Switch Schemata): a self-blink. The two sentences resolve independently —
    // the first removes Lightning from the game, the second schedules it back for the end phase.
    @Test
    void lightningSwitchSchemataRemovesItselfAndSchedulesItsReturn() {
        CardData lightning = makeForward("Lightning", "Lightning", 3, 7000);
        String effect = "Remove Lightning from the game. Play Lightning onto the field at the end of the turn.";
        assertEquals("RemoveNamedFromGame + EndOfTurnPlayNamedOntoField",
                ActionResolver.matchedPatternName(effect, lightning));

        Consumer<GameContext> fn = ActionResolver.parse(effect, lightning);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).removeNamedCardFromGame("Lightning");
        // The return is queued, not immediate, and must not go through the Break Zone route.
        verify(ctx, never()).playAllByNameFromOwnBreakZoneDull(any(), anyBoolean());
        ArgumentCaptor<Consumer<GameContext>> delayed = ArgumentCaptor.forClass(Consumer.class);
        verify(ctx).addEndOfTurnEffect(delayed.capture());

        GameContext endPhase = mock(GameContext.class);
        delayed.getValue().accept(endPhase);
        verify(endPhase).playNamedFromHoldingZoneOntoField("Lightning");
    }

    // The same wording with a pronoun (Kytes 15-047R, Ghost (VII) 20-046C) points at a card chosen
    // earlier in the ability, not at a name any holding zone could be searched for.
    @Test
    void playItOntoFieldAtEndOfTurnIsNotClaimedAsANamedPlay() {
        CardData kytes = makeForward("Kytes", "Wind", 2, 5000);
        assertNull(ActionResolver.parse("Play it onto the field at the end of the turn.", kytes),
                "\"it\" is not a card name the holding-zone lookup could resolve");
    }

    /** Puts Libroarian on P2's field without firing its enter-the-field trigger through the stack. */
    private static CardData placeLibroarianForP2(MainWindow mw) {
        CardData lib = makeLibroarian();
        mw.gameState.getIdentity().put(lib, false);
        mw.suppressAutoAbilityForNextCard = true;
        mw.placeP2CardInForwardZone(lib);
        return lib;
    }

    private static Consumer<GameContext> libroarianAbility(CardData lib, String trigger) {
        AutoAbility a = lib.autoAbilities().stream()
                .filter(x -> trigger.equals(x.trigger())).findFirst().orElseThrow();
        return ActionResolver.parse(a.effectText(), lib);
    }

    @Test
    void libroarianHandsBackOneRemovedCardPerTurnThenBreaks() {
        MainWindow mw = new MainWindow();
        CardData lib = placeLibroarianForP2(mw);
        for (int i = 1; i <= 6; i++) {
            CardData deckCard = makeForward("Deck" + i, "Wind", i, 5000);
            mw.gameState.getIdentity().put(deckCard, false);   // owner, as a real deck registers
            mw.gameState.getP2MainDeck().add(deckCard);
        }

        libroarianAbility(lib, "enters the field").accept(mw.buildGameContext(false));
        assertEquals(2, mw.gameState.getP2MainDeck().size(), "4 of the 6 deck cards left the deck");
        assertEquals(4, mw.gameState.getP2PermanentRfp().size());
        assertEquals(4, mw.cardsRemovedBySource.get(lib).size(), "and were recorded against Libroarian");

        // One comes back per end phase; on the fourth the pile is empty and Libroarian breaks.
        Consumer<GameContext> endOfTurn = libroarianAbility(lib, "end of your turn");
        for (int turn = 1; turn <= 4; turn++) {
            endOfTurn.accept(mw.buildGameContext(false));
            assertEquals(turn, mw.gameState.getP2Hand().size(), "one card retrieved on turn " + turn);
            assertEquals(4 - turn, mw.gameState.getP2PermanentRfp().size());
        }
        assertTrue(mw.p2ForwardCards.isEmpty(), "the last retrieval empties the pile and breaks Libroarian");
        assertTrue(mw.gameState.getP2BreakZone().contains(lib));
        assertFalse(mw.cardsRemovedBySource.containsKey(lib), "the tracking entry is dropped when empty");
    }

    @Test
    void libroarianWithNothingRemovedBreaksImmediately() {
        MainWindow mw = new MainWindow();
        CardData lib = placeLibroarianForP2(mw);

        // Nothing was ever removed (an empty deck on entry), so there is nothing to hand back.
        libroarianAbility(lib, "end of your turn").accept(mw.buildGameContext(false));

        assertTrue(mw.p2ForwardCards.isEmpty(), "no cards left removed — Libroarian goes to the Break Zone");
        assertTrue(mw.gameState.getP2Hand().isEmpty());
    }

    @Test
    void eachLibroarianTracksItsOwnRemovedCards() {
        // One per side — the uniqueness rule forbids two on the same field.
        MainWindow mw = new MainWindow();
        CardData p2Lib = placeLibroarianForP2(mw);
        CardData p1Lib = makeLibroarian();
        mw.gameState.getIdentity().put(p1Lib, true);
        mw.suppressAutoAbilityForNextCard = true;
        mw.placeCardInForwardZone(p1Lib);
        for (int i = 1; i <= 6; i++) {
            CardData deckCard = makeForward("Deck" + i, "Wind", i, 5000);
            mw.gameState.getIdentity().put(deckCard, false);   // owner, as a real deck registers
            mw.gameState.getP2MainDeck().add(deckCard);
        }

        libroarianAbility(p2Lib, "enters the field").accept(mw.buildGameContext(false));
        assertEquals(4, mw.cardsRemovedBySource.get(p2Lib).size());
        assertNull(mw.cardsRemovedBySource.get(p1Lib),
                "the piles are keyed by card identity, not by card name");

        // P1's copy removed nothing, so its end-of-turn ability breaks it — P2's pile is untouched.
        libroarianAbility(p1Lib, "end of your turn").accept(mw.buildGameContext(true));
        assertTrue(mw.p1ForwardCards.isEmpty(), "P1's copy broke with an empty pile");
        assertEquals(1, mw.p2ForwardCards.size(), "P2's copy is unaffected");
        assertEquals(4, mw.gameState.getP2PermanentRfp().size(), "and keeps its four removed cards");
    }

    // =========================================================================================
    // "Cards removed by [CardName]'s ability" — the same pile Libroarian tracks, called back by
    // name: Gutsco 14-010H and Cloud of Darkness B-012 take all of it on leaving the field,
    // Cloud of Darkness 10-140S takes 1 and bins the rest, and Anima 19-123H pays off at 5+.
    // The Cloud of Darkness cards also scale their power off the pile.
    // =========================================================================================

    private static final String GUTSCO_LEAVES =
            "add all the cards removed by Gutsco's ability to your hand.";
    private static final String COD_LEAVES_ONE =
            "add 1 card removed by Cloud of Darkness' ability to your hand, and put the rest of the "
            + "cards into the Break Zone.";
    private static final String ANIMA_END_OF_TURN =
            "remove the top card of your deck from the game. Then, if there are 5 or more cards removed "
            + "by Anima's ability, add them to your hand and break all the Forwards opponent controls.";

    /** Stocks P2's deck with {@code n} identified cards and returns them in deck order. */
    private static List<CardData> stockP2Deck(MainWindow mw, int n) {
        List<CardData> cards = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            CardData c = makeForward("Deck" + i, "Dark", 1, 3000);
            mw.gameState.getIdentity().put(c, false);
            mw.gameState.getP2MainDeck().add(c);
            cards.add(c);
        }
        return cards;
    }

    @Test
    void gutscoTakesItsWholePileBackOnLeavingTheField() {
        MainWindow mw = new MainWindow();
        CardData gutsco = makeAutoAbilityForward("Gutsco", "");
        mw.gameState.getIdentity().put(gutsco, false);
        stockP2Deck(mw, 5);
        GameContext ctx = mw.buildGameContext(false);

        // Three separate removals build one pile.
        for (int i = 0; i < 3; i++) ctx.removeTopCardsOfDeckFromGame(1, gutsco);
        assertEquals(3, mw.cardsRemovedBySource.get(gutsco).size());

        ActionResolver.parse(GUTSCO_LEAVES, gutsco).accept(ctx);
        assertEquals(3, mw.gameState.getP2Hand().size(), "all three came back");
        assertEquals(0, mw.gameState.getP2PermanentRfp().size());
        assertFalse(mw.cardsRemovedBySource.containsKey(gutsco));
    }

    @Test
    void cloudOfDarknessKeepsOneAndBinsTheRest() {
        MainWindow mw = new MainWindow();
        CardData cod = makeAutoAbilityForward("Cloud of Darkness", "");
        mw.gameState.getIdentity().put(cod, false);
        stockP2Deck(mw, 5);
        GameContext ctx = mw.buildGameContext(false);
        for (int i = 0; i < 4; i++) ctx.removeTopCardsOfDeckFromGame(1, cod);

        ActionResolver.parse(COD_LEAVES_ONE, cod).accept(ctx);

        assertEquals(1, mw.gameState.getP2Hand().size(), "one card to hand");
        assertEquals(3, mw.gameState.getP2BreakZone().size(), "the other three to the Break Zone");
        assertEquals(0, mw.gameState.getP2PermanentRfp().size(), "none left out of the game");
    }

    @Test
    void cloudOfDarknessPowerScalesWithItsPile() {
        String codText = "At the end of each of your turns, remove the top card of your deck from the game.[[br]] "
                + "Cloud of Darkness gains +1000 power for each card removed by Cloud of Darkness' ability.";
        List<ScalingSelfPowerBoost> boosts =
                CardData.parseScalingSelfPowerBoosts(codText, "Forward", "Cloud of Darkness");
        assertEquals(1, boosts.size());
        assertEquals(ScalingSelfPowerBoost.Source.CARDS_REMOVED_BY_OWN_ABILITY, boosts.get(0).source());
        assertEquals(1000, boosts.get(0).perUnit());

        MainWindow mw = new MainWindow();
        CardData cod = new CardData(null, "Cloud of Darkness", "Dark", 3, 7000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), boosts,
                List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, codText);
        mw.placeCardInForwardZone(cod);
        mw.gameState.getIdentity().put(cod, true);
        assertEquals(7000, mw.effectiveP1ForwardPower(0), "empty pile — base power");

        for (int i = 1; i <= 3; i++) {
            CardData c = makeForward("Deck" + i, "Dark", 1, 3000);
            mw.gameState.getIdentity().put(c, true);
            mw.gameState.getP1MainDeck().add(c);
        }
        mw.buildGameContext(true).removeTopCardsOfDeckFromGame(3, cod);
        assertEquals(10000, mw.effectiveP1ForwardPower(0), "+1000 per card removed by its own ability");
    }

    @Test
    void animaPaysOffOnlyOnceFiveCardsAreRemoved() {
        MainWindow mw = new MainWindow();
        CardData anima = makeAutoAbilityForward("Anima", "");
        mw.gameState.getIdentity().put(anima, false);
        stockP2Deck(mw, 8);
        CardData victim = makeForward("Victim", "Fire", 3, 7000);
        mw.placeCardInForwardZone(victim);
        mw.gameState.getIdentity().put(victim, true);

        Consumer<GameContext> endOfTurn = ActionResolver.parse(ANIMA_END_OF_TURN, anima);
        assertNotNull(endOfTurn);

        // Turns 1-4: one card leaves the deck each turn and the payoff stays locked.
        for (int turn = 1; turn <= 4; turn++) {
            endOfTurn.accept(mw.buildGameContext(false));
            assertEquals(turn, mw.gameState.getP2PermanentRfp().size(), "pile grows on turn " + turn);
            assertEquals(List.of(victim), mw.p1ForwardCards, "no mass break below the threshold");
            assertTrue(mw.gameState.getP2Hand().isEmpty());
        }

        // Turn 5 reaches five removed cards: they all come back and the opponent's board is swept.
        endOfTurn.accept(mw.buildGameContext(false));
        assertEquals(5, mw.gameState.getP2Hand().size(), "all five returned to hand");
        assertEquals(0, mw.gameState.getP2PermanentRfp().size());
        assertTrue(mw.p1ForwardCards.isEmpty(), "all opposing Forwards broken");
    }

    @Test
    void animaCountsTheCardsItsEnterFieldAbilityRemovedToo() {
        // "cards removed by Anima's ability" covers the 2 Break Zone cards its ETF removes, not just
        // the deck cards — so the removal is credited to whichever ability is resolving.
        MainWindow mw = new MainWindow();
        CardData anima = makeAutoAbilityForward("Anima", "");
        mw.gameState.getIdentity().put(anima, false);
        CardData bz1 = makeForward("BzOne", "Dark", 2, 5000);
        CardData bz2 = makeForward("BzTwo", "Dark", 2, 5000);
        mw.gameState.getP2BreakZone().add(bz1);
        mw.gameState.getP2BreakZone().add(bz2);
        mw.gameState.getIdentity().put(bz1, false);
        mw.gameState.getIdentity().put(bz2, false);

        GameContext ctx = mw.buildGameContext(false);
        mw.currentAbilitySource = anima;                 // as the stack sets while an ability resolves
        try {
            ctx.removeTargetFromGame(new ForwardTarget(false, 1, ForwardTarget.CardZone.BREAK_ZONE));
            ctx.removeTargetFromGame(new ForwardTarget(false, 0, ForwardTarget.CardZone.BREAK_ZONE));
        } finally {
            mw.currentAbilitySource = null;
        }

        assertEquals(2, ctx.cardsRemovedBySourceCount(anima),
                "Break Zone removals join the same pile as deck-top removals");
    }

    // =========================================================================================
    // Vayne 9-022L: All the Forwards opponent controls gain "At the end of your turn, if you don't
    // pay 《1》, break this Forward."  A continuous field grant, so it is read off Vayne at the
    // moment it fires; each granted Forward resolves its own copy, and "this Forward" means the
    // Forward that received the ability — not Vayne.
    // =========================================================================================

    private static final String VAYNE_9_022L_TEXT =
            "All the Forwards opponent controls gain \"At the end of your turn, if you don't pay 《1》, "
            + "break this Forward.\"";

    private static CardData makeVayne() {
        return makeFieldAbilityCard("Vayne", "Ice", "Forward", VAYNE_9_022L_TEXT);
    }

    @Test
    void vaynesGrantIsReadOffItsFieldAbility() {
        CardData vayne = makeVayne();
        ActionResolver.ForwardAbilityGrant grant =
                ActionResolverFieldAbility.tryParseForwardAbilityGrant(vayne.fieldAbilities().get(0).effectText());
        assertNotNull(grant, "the field ability is recognised as a grant");
        assertTrue(grant.affectsOpponent(), "it hits the Forwards the opponent controls");

        // "this Forward" resolves to the grantee, so the granted effect is built per Forward.
        CardData grantee = makeForward("Grantee", "Fire", 3, 7000);
        assertNotNull(ActionResolverFieldAbility.tryParseGrantedEndOfTurnEffect(grant.abilityText(), grantee));
        assertNull(ActionResolverFieldAbility.tryParseGrantedEndOfTurnEffect("At the beginning of your turn, draw 1 card.", grantee),
                "only end-of-turn grants fire from this path");
    }

    @Test
    void theBareEndOfYourTurnWordingIsTheSameTriggerAsEachOfYourTurns() {
        // Rem 9-059R and Death Machine 8-102R print the short form; both mean the controller's
        // own end phase, which is the trigger key "end of your turn".
        List<AutoAbility> rem = CardData.parseAutoAbilities("At the end of your turn, activate Rem.");
        assertEquals(1, rem.size());
        assertEquals("end of your turn", rem.get(0).trigger());
        assertEquals("activate Rem.", rem.get(0).effectText());

        List<AutoAbility> deathMachine = CardData.parseAutoAbilities(
                "At the end of your turn, choose 1 Forward opponent controls. Break it.");
        assertEquals(1, deathMachine.size());
        assertEquals("end of your turn", deathMachine.get(0).trigger());
        assertNotNull(ActionResolver.parse(deathMachine.get(0).effectText(),
                makeForward("Death Machine", "Fire", 5, 9000)));
    }

    @Test
    void vaynesQuotedTriggerDoesNotBecomeVaynesOwnAbility() {
        // The grant prints "At the end of your turn, …" inside quotes. Read as Vayne's own ability
        // it would charge Vayne's controller and break Vayne — it belongs to the Forwards it grants.
        assertTrue(CardData.parseAutoAbilities(VAYNE_9_022L_TEXT).isEmpty(),
                "quoted text is not the printing card's own auto ability");
        assertEquals(1, CardData.parseFieldAbilities(VAYNE_9_022L_TEXT, "Forward").size(),
                "the segment stays a field ability so the grant can be read off it");
    }

    @Test
    void vayneBreaksEachUnpaidOpposingForwardAtTheEndOfTheirTurn() {
        MainWindow mw = new MainWindow();
        CardData vayne = makeVayne();
        mw.placeP2CardInForwardZone(vayne);
        mw.gameState.getIdentity().put(vayne, false);
        CardData a = makeForward("VictimA", "Fire", 3, 7000);
        CardData b = makeForward("VictimB", "Fire", 2, 5000);
        mw.placeCardInForwardZone(a); mw.gameState.getIdentity().put(a, true);
        mw.placeCardInForwardZone(b); mw.gameState.getIdentity().put(b, true);

        mw.autoAbilityTriggers.triggerAutoAbilitiesForEndOfYourTurn(true);

        assertTrue(mw.p1ForwardCards.isEmpty(), "P1 could pay for neither Forward");
        assertTrue(mw.gameState.getP1BreakZone().containsAll(List.of(a, b)),
                "each Forward broke on its own copy of the ability");
        assertEquals(List.of(vayne), mw.p2ForwardCards, "Vayne is not affected by its own grant");
    }

    @Test
    void vaynesGrantDoesNotFireOnItsControllersOwnTurn() {
        MainWindow mw = new MainWindow();
        CardData vayne = makeVayne();
        mw.placeP2CardInForwardZone(vayne);
        mw.gameState.getIdentity().put(vayne, false);
        CardData victim = makeForward("Victim", "Fire", 3, 7000);
        mw.placeCardInForwardZone(victim);
        mw.gameState.getIdentity().put(victim, true);

        mw.autoAbilityTriggers.triggerAutoAbilitiesForEndOfYourTurn(false);   // end of P2's turn

        assertEquals(List.of(victim), mw.p1ForwardCards,
                "\"your turn\" is the granted Forward's controller's turn, not Vayne's");
    }

    @Test
    void theAiPaysVaynesTollOutOfItsBackups() {
        MainWindow mw = new MainWindow();
        CardData vayne = makeVayne();
        mw.placeCardInForwardZone(vayne);              // P1 controls Vayne this time
        mw.gameState.getIdentity().put(vayne, true);
        CardData aiForward = makeForward("Survivor", "Fire", 3, 7000);
        mw.placeP2CardInForwardZone(aiForward);
        mw.gameState.getIdentity().put(aiForward, false);
        CardData backup = makeFieldAbilityCard("Chocobo Rider", "Fire", "Backup", "");
        mw.p2BackupCards[0]  = backup;
        mw.p2BackupStates[0] = CardState.ACTIVE;

        mw.autoAbilityTriggers.triggerAutoAbilitiesForEndOfYourTurn(false);

        assertEquals(List.of(aiForward), mw.p2ForwardCards, "the AI paid rather than lose the Forward");
        assertEquals(CardState.DULL, mw.p2BackupStates[0], "it dulled a Backup for the 《1》");
    }

    @Test
    void aVayneThatHasLeftTheFieldGrantsNothing() {
        MainWindow mw = new MainWindow();
        CardData victim = makeForward("Victim", "Fire", 3, 7000);
        mw.placeCardInForwardZone(victim);
        mw.gameState.getIdentity().put(victim, true);

        mw.autoAbilityTriggers.triggerAutoAbilitiesForEndOfYourTurn(true);

        assertEquals(List.of(victim), mw.p1ForwardCards, "no granter on the field, no toll");
    }

    // =========================================================================================
    // Control transfer runs in both directions off one primitive: whichever side holds the card
    // gives it up to the other. Stealing, handing a card over, and returning a borrowed card at
    // end of turn are the same move, so P2 taking from P1 works exactly as P1 taking from P2.
    // =========================================================================================

    private static final String GAIN_CONTROL = "choose 1 Forward. You gain control of it.";
    private static final String BORROW_EOT =
            "choose 1 Forward. You gain control of it until the end of the turn.";

    /** Runs a "gain control" effect for {@code thiefIsP1} against the opponent's Forward at index 0. */
    private static void resolveSteal(MainWindow mw, boolean thiefIsP1, String effectText) {
        GameContext ctx = mw.buildGameContext(thiefIsP1);
        ctx.preloadTargets(List.of(new ForwardTarget(!thiefIsP1, 0, ForwardTarget.CardZone.FORWARD)));
        ActionResolver.parse(effectText, makeForward("Thief", "Dark", 5, 9000)).accept(ctx);
    }

    @Test
    void eitherPlayerCanTakeControlOfTheOpponentsForward() {
        MainWindow p2Steals = new MainWindow();
        CardData victim1 = makeForward("Victim", "Dark", 4, 8000);
        p2Steals.placeCardInForwardZone(victim1);
        p2Steals.gameState.getIdentity().put(victim1, true);
        resolveSteal(p2Steals, false, GAIN_CONTROL);
        assertEquals(List.of(victim1), p2Steals.p2ForwardCards, "P2 took P1's Forward");
        assertTrue(p2Steals.p1ForwardCards.isEmpty());
        // The parallel per-slot lists must stay the same length as the card list on arrival.
        assertEquals(1, p2Steals.p2ForwardStates.size());
        assertEquals(1, p2Steals.p2ForwardDamage.size());
        assertEquals(1, p2Steals.p2ForwardPrimedTop.size());

        MainWindow p1Steals = new MainWindow();
        CardData victim2 = makeForward("Victim", "Dark", 4, 8000);
        p1Steals.placeP2CardInForwardZone(victim2);
        p1Steals.gameState.getIdentity().put(victim2, false);
        resolveSteal(p1Steals, true, GAIN_CONTROL);
        assertEquals(List.of(victim2), p1Steals.p1ForwardCards, "the original direction still works");
        assertTrue(p1Steals.p2ForwardCards.isEmpty());
    }

    @Test
    void stealingIntoADuplicateSendsBothCopiesToTheirOwnersBreakZones() {
        MainWindow mw = new MainWindow();
        CardData p1Copy = makeForward("Victim", "Dark", 4, 8000);
        CardData p2Copy = makeForward("Victim", "Dark", 4, 8000);
        mw.placeCardInForwardZone(p1Copy);
        mw.gameState.getIdentity().put(p1Copy, true);
        mw.placeP2CardInForwardZone(p2Copy);
        mw.gameState.getIdentity().put(p2Copy, false);

        resolveSteal(mw, false, GAIN_CONTROL);   // P2 already controls a Victim

        assertTrue(mw.p1ForwardCards.isEmpty());
        assertTrue(mw.p2ForwardCards.isEmpty());
        assertTrue(mw.gameState.getP1BreakZone().contains(p1Copy), "each copy went to its own owner");
        assertTrue(mw.gameState.getP2BreakZone().contains(p2Copy));
    }

    @Test
    void aForwardP2BorrowedUntilEndOfTurnGoesBackToP1() {
        MainWindow mw = new MainWindow();
        CardData loaner = makeForward("Loaner", "Dark", 3, 6000);
        mw.placeCardInForwardZone(loaner);
        mw.gameState.getIdentity().put(loaner, true);

        resolveSteal(mw, false, BORROW_EOT);
        assertEquals(List.of(loaner), mw.p2ForwardCards, "borrowed by P2");

        mw.fireEndOfTurnEffects(false);
        assertEquals(List.of(loaner), mw.p1ForwardCards, "returned to P1 at end of turn");
        assertTrue(mw.p2ForwardCards.isEmpty());
    }

    @Test
    void ultimeciaWorksForTheAiNowThatP2CanTakeControl() {
        MainWindow mw = new MainWindow();
        CardData ultimecia = makeForward("Ultimecia", "Dark", 5, 9000);
        CardData victim    = makeForward("Victim", "Dark", 4, 8000);
        mw.placeCardInForwardZone(victim);
        mw.gameState.getIdentity().put(victim, true);   // P1 owns it

        GameContext ctx = mw.buildGameContext(false);   // the AI resolves Ultimecia
        ctx.preloadTargets(List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD)));
        ActionResolver.parse(ULTIMECIA_STEAL, ultimecia).accept(ctx);

        assertTrue(mw.p1ForwardCards.isEmpty(), "the AI took it");
        assertTrue(mw.p2ForwardCards.isEmpty(), "and could not pay 《4》, so it broke");
        assertTrue(mw.gameState.getP1BreakZone().contains(victim),
                "it goes to P1's Break Zone — P1 still owns it");
    }

    @Test
    void ultimeciaChargesTheStolenForwardsOwnCost() {
        CardData ultimecia = makeForward("Ultimecia", "Dark", 5, 9000);
        Consumer<GameContext> steal = ActionResolver.parse(ULTIMECIA_STEAL, ultimecia);
        assertNotNull(steal);
        ForwardTarget target = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);

        for (int cost : new int[]{4, 1}) {
            CardData victim = makeForward("Victim", "Dark", cost, 8000);
            GameContext ctx = mock(GameContext.class);
            when(ctx.consumePreloadedTargets()).thenReturn(List.of(target));
            when(ctx.targetCard(target)).thenReturn(victim);
            when(ctx.selfControlsCard(victim)).thenReturn(true);

            steal.accept(ctx);

            verify(ctx).gainControlOfForward(target, "permanent", false);
            verify(ctx).mayPayCostOrElse(eq(cost), isNull(), eq(0), any(Runnable.class));
        }
    }

    // =========================================================================================
    // Waltrill 8-047C: "When Waltrill enters the field, place up to 2 cards from your hand at the
    // bottom of your deck in any order. Then, draw the same number of cards as were returned to
    // your deck."  The redraw is sized by what was actually returned, and returning nothing is a
    // legal choice — so the count cannot be baked into the parsed effect.
    // =========================================================================================

    private static final String WALTRILL_TEXT =
            "When Waltrill enters the field, place up to 2 cards from your hand at the bottom of your "
            + "deck in any order. Then, draw the same number of cards as were returned to your deck.";

    private static final String WALTRILL_ETB =
            "place up to 2 cards from your hand at the bottom of your deck in any order. "
            + "Then, draw the same number of cards as were returned to your deck.";

    @Test
    void waltrillEnterFieldAbilityParsesAsAnUpToCycle() {
        List<AutoAbility> abilities = CardData.parseAutoAbilities(WALTRILL_TEXT);
        assertEquals(1, abilities.size());
        assertEquals("enters the field", abilities.get(0).trigger());
        assertEquals(WALTRILL_ETB, abilities.get(0).effectText());
        assertEquals("PlaceUpToHandToBottomThenRedraw",
                ActionResolver.matchedPatternName(abilities.get(0).effectText(), null));
    }

    @Test
    void waltrillDrawsExactlyAsManyCardsAsWereReturned() {
        Consumer<GameContext> fn = ActionResolver.parse(WALTRILL_ETB, null);
        assertNotNull(fn);

        // Returned 1 of the allowed 2 → draw 1.
        GameContext ctx = mock(GameContext.class);
        when(ctx.placeUpToFromHandToBottomOfDeck(2)).thenReturn(1);
        fn.accept(ctx);
        verify(ctx).drawCards(1);

        // Returned nothing → draw nothing.
        GameContext none = mock(GameContext.class);
        when(none.placeUpToFromHandToBottomOfDeck(2)).thenReturn(0);
        fn.accept(none);
        verify(none, never()).drawCards(anyInt());
    }

    @Test
    void waltrillCyclesTheAiHandWithoutChangingItsSize() {
        MainWindow mw = new MainWindow();
        CardData a = makeForward("A", "Wind", 2, 5000);
        CardData b = makeForward("B", "Wind", 3, 7000);
        mw.gameState.getP2Hand().add(a);
        mw.gameState.getP2Hand().add(b);
        CardData top1 = makeForward("Top1", "Wind", 1, 3000);
        CardData top2 = makeForward("Top2", "Wind", 1, 3000);
        mw.gameState.getP2MainDeck().add(top1);
        mw.gameState.getP2MainDeck().add(top2);

        ActionResolver.parse(WALTRILL_ETB, null).accept(mw.buildGameContext(false));

        assertEquals(2, mw.gameState.getP2Hand().size(), "returned 2, drew 2");
        assertEquals(List.of(top1, top2), mw.gameState.getP2Hand(),
                "the AI drew the two cards that were on top");
        assertEquals(2, mw.gameState.getP2MainDeck().size(), "deck size is unchanged by a cycle");
        assertTrue(mw.gameState.getP2MainDeck().containsAll(List.of(a, b)),
                "the returned cards went under the deck");
    }

    // =========================================================================================
    // Serafie 1-109R: "EX BURST When Serafie enters the field, each player selects 1 Forward from
    // his/her Break Zone and adds it to his/her hand."  Shares the both-players salvage effect with
    // Cu Chaspel 18-021R ("1 card"), which is unrestricted — Serafie's names a type, so Backups,
    // Monsters and Summons sitting in either Break Zone are not eligible.
    // =========================================================================================

    private static final String SERAFIE_TEXT =
            "[[ex]]EX BURST[[/]] When Serafie enters the field, each player selects 1 Forward "
            + "from his/her Break Zone and adds it to his/her hand.";

    private static final String SERAFIE_ETB =
            "each player selects 1 Forward from his/her Break Zone and adds it to his/her hand.";

    @Test
    void serafieEnterFieldAbilityParsesAsAnEachPlayerSalvage() {
        List<AutoAbility> abilities = CardData.parseAutoAbilities(SERAFIE_TEXT);
        assertEquals(1, abilities.size(), "one enter-the-field trigger");
        assertEquals("enters the field", abilities.get(0).trigger());
        assertEquals(SERAFIE_ETB, abilities.get(0).effectText());
        assertEquals("EachPlayerSalvageFromBreakZone",
                ActionResolver.matchedPatternName(abilities.get(0).effectText(), null));
    }

    @Test
    void serafieSalvagesForwardsOnlyWhileCuChaspelTakesAnyCard() {
        Consumer<GameContext> serafie = ActionResolver.parse(SERAFIE_ETB, null);
        assertNotNull(serafie, "Serafie's enter-the-field ability resolves");
        GameContext ctx = mock(GameContext.class);
        serafie.accept(ctx);
        verify(ctx).eachPlayerSalvageFromBreakZone(1, true, false, false, false);

        Consumer<GameContext> cuChaspel = ActionResolver.parse(
                "each player selects 1 card from their Break Zone and adds it to their hand.", null);
        assertNotNull(cuChaspel);
        GameContext ctx2 = mock(GameContext.class);
        cuChaspel.accept(ctx2);
        verify(ctx2).eachPlayerSalvageFromBreakZone(1, true, true, true, true);
    }

    @Test
    void serafieRetrievesEachPlayersOwnForwardAndSkipsOtherCardTypes() {
        MainWindow mw = new MainWindow();
        // P1's Break Zone holds nothing eligible, so no dialog is raised for P1.
        mw.gameState.getP1BreakZone().add(makeJobCard("Ifrit", "Fire", "Summon", "Eikon"));
        // P2 must skip the Summon and the Backup and take the one Forward.
        mw.gameState.getP2BreakZone().add(makeJobCard("Shiva", "Ice", "Summon", "Eikon"));
        mw.gameState.getP2BreakZone().add(makeJobCard("Sage", "Earth", "Backup", "Scholar"));
        CardData p2Forward = makeForward("Zidane", "Wind", 3, 7000);
        mw.gameState.getP2BreakZone().add(p2Forward);

        ActionResolver.parse(SERAFIE_ETB, null).accept(mw.buildGameContext(true));

        assertEquals(List.of(p2Forward), mw.gameState.getP2Hand(),
                "the AI salvaged its only eligible Forward");
        assertEquals(2, mw.gameState.getP2BreakZone().size(), "Summon and Backup stayed behind");
        assertTrue(mw.gameState.getP1Hand().isEmpty(), "P1 had no eligible Forward to take");
        assertEquals(1, mw.gameState.getP1BreakZone().size(), "P1's Summon stayed behind");
    }

    // =========================================================================================
    // Attack-conditional action abilities ("...while X is attacking", "...while a party you control
    // is attacking") must stay usable after the attack is declared, while P1 holds priority before
    // passing with Next. The Attack button empties p1AttackSelection when it fires the declaration,
    // so the gate reads the declared attackers instead.
    // =========================================================================================

    private static final String WHILE_ATTACKING_ABILITY =
            "《Dull》: Choose 1 Forward. Deal it 4000 damage. "
            + "You can only use this ability while Vaan is attacking.";

    private static final String WHILE_PARTY_ATTACKING_ABILITY =
            "《Dull》: Choose 1 Forward. Deal it 4000 damage. "
            + "You can only use this ability while a party you control is attacking.";

    /** Puts {@code mw} in P1's Attack Phase (ACTIVE → DRAW → MAIN_1 → ATTACK). */
    private static void enterP1AttackPhase(MainWindow mw) {
        mw.gameState.startFirstTurn(GameState.Player.P1);
        mw.gameState.advancePhase();
        mw.gameState.advancePhase();
        mw.gameState.advancePhase();
        assertEquals(GameState.GamePhase.ATTACK, mw.gameState.getCurrentPhase());
    }

    @Test
    void whileAttackingAbilityIsUsableOnceTheAttackIsDeclared() {
        MainWindow mw = new MainWindow();
        enterP1AttackPhase(mw);
        ActionAbility ab = CardData.parseActionAbilities(WHILE_ATTACKING_ABILITY).get(0);
        CardData vaan = makeForward("Vaan", "Wind", 3, 7000, List.of(ab));
        mw.placeCardInForwardZone(vaan); // P1 idx 0

        assertFalse(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, vaan, true),
                "nothing declared yet — Vaan is not attacking");

        // Sub-step 1: Vaan is picked but not yet declared; lining the ability up still works.
        mw.p1AttackSelection.add(0);
        assertTrue(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, vaan, true),
                "selected as an attacker");

        // The Attack button clears the selection as it declares — the ability must survive that.
        mw.p1AttackSelection.clear();
        mw.p1DeclaredAttackers.add(vaan);
        assertTrue(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, vaan, true),
                "declared attacker — usable while P1 holds priority");
    }

    @Test
    void whilePartyAttackingAbilityNeedsTwoDeclaredAttackers() {
        MainWindow mw = new MainWindow();
        enterP1AttackPhase(mw);
        ActionAbility ab = CardData.parseActionAbilities(WHILE_PARTY_ATTACKING_ABILITY).get(0);
        CardData firion = makeForward("Firion", "Fire", 2, 7000, List.of(ab));
        CardData ally   = makeForward("Ally", "Fire", 3, 7000);
        mw.placeCardInForwardZone(firion); // P1 idx 0
        mw.placeCardInForwardZone(ally);   // P1 idx 1

        mw.p1DeclaredAttackers.add(firion);
        assertFalse(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, firion, true),
                "a lone attacker is not a party");

        mw.p1DeclaredAttackers.add(ally);
        assertTrue(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, firion, true),
                "two declared attackers form the party");
    }

    @Test
    void p1AttackersDoNotEnableTheOpponentsAttackConditionalAbilities() {
        MainWindow mw = new MainWindow();
        enterP1AttackPhase(mw);
        ActionAbility ab = CardData.parseActionAbilities(WHILE_ATTACKING_ABILITY).get(0);
        CardData p2Vaan = makeForward("Vaan", "Wind", 3, 7000, List.of(ab));
        mw.placeP2CardInForwardZone(p2Vaan);

        CardData p1Vaan = makeForward("Vaan", "Wind", 3, 7000, List.of(ab));
        mw.placeCardInForwardZone(p1Vaan);
        mw.p1DeclaredAttackers.add(p1Vaan);

        assertTrue(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, p1Vaan, true),
                "P1's Vaan is the one attacking");
        assertFalse(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, p2Vaan, false),
                "the check is per side — P2 has declared no attackers");
    }

    @Test
    void p2sAttackerStaysAttackingAfterTheBlockIsCommitted() {
        MainWindow mw = new MainWindow();
        enterP1AttackPhase(mw);
        ActionAbility ab = CardData.parseActionAbilities(WHILE_ATTACKING_ABILITY).get(0);
        CardData p2Vaan = makeForward("Vaan", "Wind", 3, 7000, List.of(ab));
        mw.placeP2CardInForwardZone(p2Vaan);

        // P2 declared the attack. The engine drops its pending-blocker state the moment P1 commits
        // a block, so the ability must ride on the declared-attacker list, which lives until the
        // combat resolves — otherwise it goes dead at the blocker-declared checkpoint.
        mw.p2DeclaredAttackers.add(p2Vaan);
        assertTrue(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, p2Vaan, false),
                "still attacking through blocker declaration and damage");

        mw.p2DeclaredAttackers.clear(); // combat resolved
        assertFalse(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, p2Vaan, false),
                "no longer attacking once the combat is over");
    }

    // =========================================================================================
    // The Crystal Exarch 13-133S: "At the beginning of the Attack Phase during each player's
    // turn, choose 1 Forward. It gains +2000 power until the end of the turn."
    //
    // "during each player's turn" is the both-turns variant of the "during each of your turns"
    // wording, so it needs its own trigger key ("beginning of attack phase each turn") that the
    // Attack Phase fires for BOTH sides' cards, no matter whose turn it is.
    // =========================================================================================

    private static final String CRYSTAL_EXARCH_TEXT =
            "At the beginning of the Attack Phase during each player's turn, choose 1 Forward. "
            + "It gains +2000 power until the end of the turn.[[br]]"
            + "Damage 5 -- The Crystal Exarch gains \"When The Crystal Exarch attacks, choose 1 Forward. "
            + "If its power is less than The Crystal Exarch's power, break it.\"[[br]]";

    private static final String CRYSTAL_EXARCH_ATTACK_PHASE_EFFECT =
            "choose 1 Forward. It gains +2000 power until the end of the turn.";

    private static CardData makeCrystalExarch() {
        return new CardData(null, "The Crystal Exarch", "Earth", 5, 8000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), CardData.parseAutoAbilities(CRYSTAL_EXARCH_TEXT), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, CRYSTAL_EXARCH_TEXT);
    }

    @Test
    void crystalExarchAttackPhaseAbilityUsesTheEachPlayersTurnTrigger() {
        List<AutoAbility> abilities = CardData.parseAutoAbilities(CRYSTAL_EXARCH_TEXT);
        List<AutoAbility> attackPhase = abilities.stream()
                .filter(a -> "beginning of attack phase each turn".equals(a.trigger()))
                .toList();
        assertEquals(1, attackPhase.size(), "exactly one both-turns Attack Phase trigger");
        assertEquals(CRYSTAL_EXARCH_ATTACK_PHASE_EFFECT, attackPhase.get(0).effectText());
        assertTrue(abilities.stream().noneMatch(a -> "beginning of attack phase".equals(a.trigger())),
                "the your-turns-only trigger must not also match this wording");
        assertNotNull(ActionResolver.parse(attackPhase.get(0).effectText(), null),
                "the choose-and-boost effect resolves");
    }

    @Test
    void crystalExarchAttackPhaseAbilityIsNotLeftAsAnUnhandledFieldAbility() {
        assertTrue(CardData.parseFieldAbilities(CRYSTAL_EXARCH_TEXT, "Forward").stream()
                        .noneMatch(f -> f.effectText().contains("At the beginning of the Attack Phase")),
                "the auto-ability pass claims the segment, so it must not fall through to field abilities");
    }

    @Test
    void aGrantedEachPlayersTurnTriggerIsNotTheGrantingCardsOwnAbility() {
        // Lann 16-102R only grants this ability when its enter-the-field cost is paid, so the
        // quoted wording must not register as an unconditional trigger on Lann itself.
        String lannText = "When Lann enters the field, choose 1 Monster in your Break Zone. You may remove "
                + "it from the game. If you do so, Lann gains +2000 power and \"At the beginning of the "
                + "Attack Phase during each player's turn, choose 1 Forward opponent controls. Dull it.\" "
                + "(This effect does not end at the end of the turn.)";
        assertTrue(CardData.parseAutoAbilities(lannText).stream()
                        .noneMatch(a -> "beginning of attack phase each turn".equals(a.trigger())),
                "a quoted grant is not the card's own trigger");
    }

    @Test
    void crystalExarchBoostsTheChosenForwardBy2000() {
        MainWindow mw = new MainWindow();
        CardData exarch = makeCrystalExarch();
        mw.placeCardInForwardZone(exarch);                                  // P1 idx 0
        mw.placeP2CardInForwardZone(makeForward("Ally", "Earth", 3, 7000)); // P2 idx 0

        // Either side's Forward is a legal choice — here the opponent's is picked.
        GameContext ctx = mw.buildGameContext(true);
        ctx.preloadTargets(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));
        ActionResolver.parse(CRYSTAL_EXARCH_ATTACK_PHASE_EFFECT, exarch).accept(ctx);

        assertEquals(9000, mw.effectiveP2ForwardPower(0), "the chosen Forward gains +2000");
        assertEquals(8000, mw.effectiveP1ForwardPower(0), "The Crystal Exarch itself is unchanged");
    }

    // =========================================================================================
    // Schultz 27-100R: "[[ex]]EX BURST[[/]] When Schultz enters the field, look at the top 3
    // cards of your deck. Return these to the top and/or bottom of your deck in any order.
    // Then, reveal the top card of your deck. If it is a Water card, add it to your hand."
    //
    // Two effects chained by "Then," — the look-at-deck clause used to be all that ran, and
    // before that the greedy "Return [name] to your hand" matcher claimed the whole text.
    // =========================================================================================

    private static final String SCHULTZ_TEXT =
            "[[ex]]EX BURST[[/]] When Schultz enters the field, look at the top 3 cards of your deck. "
            + "Return these to the top and/or bottom of your deck in any order. Then, reveal the top "
            + "card of your deck. If it is a Water card, add it to your hand.";

    @Test
    void schultzEntersTheFieldTriggerCarriesBothClauses() {
        List<AutoAbility> abilities = CardData.parseAutoAbilities(SCHULTZ_TEXT);
        assertEquals(1, abilities.size(), "one enter-the-field trigger");
        AutoAbility etf = abilities.get(0);
        assertEquals("enters the field", etf.trigger());
        assertEquals("Schultz", etf.triggerCard());
        assertTrue(etf.effectText().contains("Then, reveal the top card"),
                "the chained reveal stays in the effect text");
        assertEquals("LookTopDeckTopOrBottom + RevealTopDeck",
                ActionResolver.matchedPatternName(etf.effectText(), null));
    }

    @Test
    void schultzLooksAtTopThreeThenRevealsForAWaterCard() {
        AutoAbility etf = CardData.parseAutoAbilities(SCHULTZ_TEXT).get(0);
        Consumer<GameContext> fn = ActionResolver.parse(etf.effectText(), null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).lookAtTopDeck(new LookConfig(3, LookConfig.LookAction.TOP_OR_BOTTOM_ORDERED));

        ArgumentCaptor<List<RevealClause>> clauses = ArgumentCaptor.forClass(List.class);
        verify(ctx).revealTopDeckCard(clauses.capture(), eq(false));
        assertEquals(1, clauses.getValue().size());
        RevealClause clause = clauses.getValue().get(0);
        assertEquals("addToHand", clause.cardOp());
        assertTrue(clause.condition().test(makeForward("Leviathan", "Water", 3, 7000)),
                "a Water card satisfies the reveal condition");
        assertFalse(clause.condition().test(makeForward("Ifrit", "Fire", 3, 7000)),
                "a non-Water card does not");
    }

    @Test
    void schultzOrdersTheLookBeforeTheReveal() {
        AutoAbility etf = CardData.parseAutoAbilities(SCHULTZ_TEXT).get(0);
        GameContext ctx = mock(GameContext.class);
        ActionResolver.parse(etf.effectText(), null).accept(ctx);

        // The ordering dialog is modal, so the reveal must be queued behind it, not before it.
        InOrder order = inOrder(ctx);
        order.verify(ctx).lookAtTopDeck(any(LookConfig.class));
        order.verify(ctx).revealTopDeckCard(anyList(), anyBoolean());
    }

    @Test
    void returnNamedToHandDoesNotSwallowAWholeSentence() {
        // "Return [name] to your hand" must not stretch from an unrelated "Return" all the way to
        // a later "… to your hand" — that is what hijacked Schultz's ability.
        GameContext ctx = mock(GameContext.class);
        Consumer<GameContext> fn = ActionResolver.parse(
                "look at the top 3 cards of your deck. Return these to the top and/or bottom of your "
                + "deck in any order. Then, reveal the top card of your deck. If it is a Water card, "
                + "add it to your hand.", null);
        assertNotNull(fn);
        fn.accept(ctx);
        verify(ctx, never()).returnNamedCardToYourHand(anyString());
    }

    @Test
    void returnNamedToHandStillMatchesRealCardNames() {
        GameContext ctx = mock(GameContext.class);
        ActionResolver.parse("Return Good King Moggle Mog XII to your hand.", null).accept(ctx);
        verify(ctx).returnNamedCardToYourHand("Good King Moggle Mog XII");
    }

    // =========================================================================================
    // Lunafreya 23-129H: "Limit Break -- 2[[br]] When Lunafreya enters the field, reveal the top
    // 5 cards of your deck. Add 1 card among them to your hand and return the other cards to the
    // bottom of your deck in any order. If the card added to your hand has an EX Burst, you may
    // trigger its EX Burst effect. (This effect is put on the stack.)"
    //
    // The look clause says "reveal", not "look at", and carries a rider that acts on whichever
    // card was taken.
    // =========================================================================================

    private static final String LUNAFREYA_TEXT =
            "Limit Break -- 2[[br]]   When Lunafreya enters the field, reveal the top 5 cards of your "
            + "deck. Add 1 card among them to your hand and return the other cards to the bottom of "
            + "your deck in any order. If the card added to your hand has an EX Burst, you may trigger "
            + "its EX Burst effect. (This effect is put on the stack.)";

    @Test
    void lunafreyaRevealsFiveAndOffersTheAddedCardsExBurst() {
        List<AutoAbility> abilities = CardData.parseAutoAbilities(LUNAFREYA_TEXT);
        assertEquals(1, abilities.size());
        AutoAbility etf = abilities.get(0);
        assertEquals("enters the field", etf.trigger());
        assertEquals("LookTopDeckAddToHandRestBottom + AddedCardExBurst",
                ActionResolver.matchedPatternName(etf.effectText(), null));

        Consumer<GameContext> fn = ActionResolver.parse(etf.effectText(), null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        // The rider can only know what was taken after the look has resolved.
        InOrder order = inOrder(ctx);
        order.verify(ctx).lookAtTopDeck(
                new LookConfig(5, LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM, null, true));
        order.verify(ctx).triggerExBurstOfCardAddedToHand();
    }

    @Test
    void lunafreyaRevealsRatherThanLooksSoTheCardsArePublic() {
        // "Reveal" shows the cards to both players; a "look at" would keep them private to the
        // controller. Same card movement either way, so only this flag carries the difference.
        AutoAbility etf = CardData.parseAutoAbilities(LUNAFREYA_TEXT).get(0);
        GameContext ctx = mock(GameContext.class);
        ActionResolver.parse(etf.effectText(), null).accept(ctx);

        ArgumentCaptor<LookConfig> cfg = ArgumentCaptor.forClass(LookConfig.class);
        verify(ctx).lookAtTopDeck(cfg.capture());
        assertTrue(cfg.getValue().reveal(), "Lunafreya reveals, so both players see the cards");
    }

    @Test
    void theElementalCycleRevealsRatherThanLooks() {
        // Kojin 14-012C and its set-14 siblings (Devout, Vanu Vanu, Paladin, Gnath, Ananta) all
        // reveal, so the opponent is entitled to see what was turned over.
        GameContext ctx = mock(GameContext.class);
        ActionResolver.parse(
                "reveal the top 2 cards of your deck. Add 1 Fire card among them to your hand and "
                + "put the rest into the Break Zone.", null).accept(ctx);
        verify(ctx).lookAtTopDeck(
                new LookConfig(2, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, "Fire", true));
    }

    @Test
    void aLookAtStaysPrivateToItsController() {
        GameContext ctx = mock(GameContext.class);
        ActionResolver.parse(
                "Look at the top 2 cards of your deck. Add 1 Water card among them to your hand and "
                + "put the rest into the Break Zone.", null).accept(ctx);
        verify(ctx).lookAtTopDeck(
                new LookConfig(2, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, "Water", false));
    }

    @Test
    void aPlainAddToHandLookGainsNoExBurstRider() {
        // Baderon 5-132R — same clause, nothing after it.
        GameContext ctx = mock(GameContext.class);
        Consumer<GameContext> fn = ActionResolver.parse(
                "look at the top 3 cards of your deck. Add 1 card among them to your hand and return "
                + "the other cards to the bottom of your deck in any order.", null);
        assertNotNull(fn);
        fn.accept(ctx);
        verify(ctx).lookAtTopDeck(new LookConfig(3, LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM));
        verify(ctx, never()).triggerExBurstOfCardAddedToHand();
    }

    @Test
    void anUnrecognisedRiderOnTheAddToHandLookLeavesTheAbilityUnparsed() {
        // Golem 23-064R's rider ("deal the chosen Forward damage equal to the power of the added
        // Forward") is not supported; running just the look half would quietly drop it.
        assertNull(ActionResolver.parse(
                "reveal the top 3 cards of your deck. Add 1 card among them to your hand and return "
                + "the other cards to the bottom of your deck in any order. If you added a Forward to "
                + "your hand, deal the chosen Forward damage equal to the power of the added Forward.",
                null));
    }

	// ---------------------------------------------------------------- trailing draw

	// A trailing "Draw 1 card." rides behind a complete effect. Whichever pattern matches the
	// leading sentences claims the whole text with find() and parse() returns, so its
	// sentence-splitting fallback never runs and the draw used to be discarded silently.

	// 19-126C Shadow Lord.
	@Test
	void trailingDrawRunsAfterOpponentDiscard() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"your opponent discards 1 card. Draw 1 card.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).forceOpponentDiscard(1);
		verify(ctx).drawCards(1);
	}

	// 28-102R phrasing: the connector is kept out of the draw clause.
	@Test
	void trailingDrawAcceptsThenConnector() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"your opponent discards 1 card. Then, draw 1 card.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).forceOpponentDiscard(1);
		verify(ctx).drawCards(1);
	}

	// "draw N, then discard M" is one effect, not a trailing addition: it must keep resolving
	// through tryParseDrawCards so the discard is not lost.
	@Test
	void drawThenDiscardIsNotTreatedAsATrailingDraw() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse("draw 1 card, then discard 1 card.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).drawCards(1);
	}

	// The composer declines when the leading effect does not resolve, rather than half-resolving
	// it. parse() as a whole may still draw for such text, via the sentence-splitting fallback it
	// has always had — that path is unchanged and is what handles a head no pattern covers.
	@Test
	void trailingDrawComposerDeclinesWhenLeadingEffectDoesNotParse() {
		assertNull(ActionResolverHand.tryParseTrailingDraw(
				"Xyzzy the plugh into the frobnitz. Draw 1 card.", null, 0));
	}

	// ------------------------------------------------- independent-sentence composition

	// A pattern anchored on one sentence claims the whole ability via find(), so every other
	// sentence used to be discarded. Where the sentences are independent, all of them resolve.

	// 16-036C Devout: the crystal was lost because the discard pattern matched the back half.
	@Test
	void independentSentencesBothResolve() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"gain 《C》. Your opponent discards 1 card from their hand.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).gainCrystal(1);
		verify(ctx).forceOpponentDiscard(1);
	}

	// 26-022C Undead Princess, same shape with the shorter discard phrasing.
	@Test
	void independentSentencesBothResolveShortForm() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"gain 《C》. Your opponent discards 1 card.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).gainCrystal(1);
		verify(ctx).forceOpponentDiscard(1);
	}

	// The guard that keeps composition off linked text. Splitting "Choose 1 Forward. Deal it
	// 3000 damage." would leave the damage with no target, so a backward reference in any
	// sentence after the first must block the whole ability from being composed.
	@Test
	void sentencesReferringBackwardsAreNotIndependent() {
		assertTrue(ActionResolverPatterns.DEPENDS_ON_PREVIOUS_SENTENCE
				.matcher("Deal it 3000 damage.").find());
		assertTrue(ActionResolverPatterns.DEPENDS_ON_PREVIOUS_SENTENCE
				.matcher("Return them to their owners' hands.").find());
		assertTrue(ActionResolverPatterns.DEPENDS_ON_PREVIOUS_SENTENCE
				.matcher("If you do so, draw 1 card.").find());
		assertTrue(ActionResolverPatterns.DEPENDS_ON_PREVIOUS_SENTENCE
				.matcher("Break that Forward.").find());

		assertFalse(ActionResolverPatterns.DEPENDS_ON_PREVIOUS_SENTENCE
				.matcher("Your opponent discards 1 card.").find());
		assertFalse(ActionResolverPatterns.DEPENDS_ON_PREVIOUS_SENTENCE
				.matcher("Draw 1 card.").find());
	}

	// A linked ability keeps resolving through the normal chain: the chosen Forward still takes
	// the damage, rather than the two sentences running as unrelated effects. Composition would
	// log each sentence separately; staying linked logs the choose and the damage as one effect.
	@Test
	void linkedChooseAndDamageStillResolvesAsOneEffect() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse("Choose 1 Forward. Deal it 3000 damage.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).logEntry(argThat(s ->
				s.contains("Choose 1 Forward") && s.contains("Deal 3000 damage")));
	}

	// ------------------------------------------------- conditional use restriction

	// 23-053R Meteion: "You can only use this ability if neither player controls Forwards."
	// The condition spans both fields, unlike "if you don't control any Forwards", which
	// inspects only the activating player's side.
	@Test
	void neitherPlayerControlsBecomesABothFieldsCondition() {
		List<ActionAbility> abilities = CardData.parseActionAbilities(
				"《Dull》, put Meteion into the Break Zone: Activate all the Backups you "
				+ "control. Draw 1 card. You can only use this ability if neither player controls Forwards.");
		assertEquals(1, abilities.size());

		ControlCondition cond = abilities.get(0).controlCondition();
		assertNotNull(cond, "the use restriction should be captured as a control condition");
		assertTrue(cond.bothFields(), "must be checked across both players' fields");
		assertTrue(cond.exactCount());
		assertEquals(0, cond.minCount());
		assertEquals("Forward", cond.cardType());
	}

	// The restriction sentence must not remain in the effect text, or it strands what follows it.
	@Test
	void neitherPlayerRestrictionIsStrippedFromTheEffect() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"Activate all the Backups you control. Draw 1 card. "
				+ "You can only use this ability if neither player controls Forwards.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).drawCards(1);
	}

	// ------------------------------------------------- triggered target action

	// 26-032L Charlotte: "When a Character enters your opponent's field, dull it and Freeze it."
	// "it" is the card that fired the trigger, so the effect names no target of its own and the
	// followup pattern it matches is only ever reached behind a Choose primary.
	@Test
	void triggeredTargetActionDullsAndFreezesThePreloadedCard() {
		GameContext ctx = mock(GameContext.class);
		ForwardTarget entering = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		when(ctx.consumePreloadedTargets()).thenReturn(List.of(entering));

		Consumer<GameContext> fn = ActionResolver.parse("dull it and Freeze it.", null);
		assertNotNull(fn, "a bare triggered target action should parse");
		fn.accept(ctx);

		verify(ctx).dullAndFreezeTarget(entering);
	}

	// With no preloaded target the effect must do nothing rather than act on an arbitrary card.
	@Test
	void triggeredTargetActionDoesNothingWithoutAPreloadedTarget() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(List.of());

		Consumer<GameContext> fn = ActionResolver.parse("dull it and Freeze it.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx, never()).dullAndFreezeTarget(any());
	}

	// The standalone hook is anchored: it must not claim the tail of a Choose ability, which
	// carries the same wording as a followup and already resolves through the Choose family.
	@Test
	void triggeredTargetActionDoesNotClaimAChooseFollowup() {
		assertEquals("ChooseCharacter", ActionResolver.matchedPatternName(
				"Choose 1 Forward opponent controls. Dull it and Freeze it.", null));
	}

	// 4-039R Rogue: "When Rogue deals damage to a Forward, dull it and Freeze it." The
	// "deals damage to forward" trigger used to break the damaged Forward unconditionally, so
	// Rogue broke it instead of dulling and freezing it. DamageResolver now keys the Breaktouch
	// shortcut off this predicate, so it must separate the two wordings exactly.
	@Test
	void rogueDamageTriggerIsNotBreaktouch() {
		assertTrue(ActionResolver.isTriggeredTargetAction("dull it and Freeze it."),
				"Rogue's effect must resolve as an action on the damaged card");

		// The genuine Breaktouch wordings must keep the dedicated break path.
		assertFalse(ActionResolver.isTriggeredTargetAction("break it."));
		assertFalse(ActionResolver.isTriggeredTargetAction("break that Forward."));
	}

	// The bare-action hook stays off text that only contains such a clause as a followup, which
	// is what every other corpus occurrence of these wordings actually is.
	@Test
	void bareActionHookIgnoresChooseFollowupsAndPlurals() {
		assertFalse(ActionResolver.isTriggeredTargetAction(
				"Choose 1 Forward opponent controls. Dull it and Freeze it."));
		assertFalse(ActionResolver.isTriggeredTargetAction("Dull them and Freeze them."));
		assertFalse(ActionResolver.isTriggeredTargetAction("If you do so, dull it."));
	}

	// ------------------------------------------------- is-dealt-damage trigger

	// 28-043R Gi Nattak: "When Gi Nattak is dealt damage, choose 1 Forward opponent controls.
	// At the end of your opponent's turn, break it." The whole-text scan for delayed clauses used
	// to lift the second half out as its own ability, orphaning "break it" from the choose.
	@Test
	void giNattakParsesAsOneDamageTriggeredAbility() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(
				"When Gi Nattak is dealt damage, choose 1 Forward opponent controls. "
				+ "At the end of your opponent's turn, break it. This effect will trigger only once per turn.");
		assertEquals(1, autos.size(), "the delayed clause must stay with its trigger");
		assertEquals("is dealt damage", autos.get(0).trigger());
		assertEquals("Gi Nattak", autos.get(0).triggerCard());
		assertTrue(autos.get(0).oncePerTurn());
	}

	// The chosen Forward is picked now and broken later, not broken immediately.
	@Test
	void chooseThenEndOfOpponentTurnQueuesTheActionOnTheChosenCard() {
		GameContext ctx = mock(GameContext.class);
		ForwardTarget chosen = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				any(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(List.of(chosen));

		Consumer<GameContext> fn = ActionResolver.parse(
				"choose 1 Forward opponent controls. At the end of your opponent's turn, break it", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).addEndOfOpponentTurnEffect(any());
		verify(ctx, never()).breakTarget(any());   // deferred, not applied now
	}

	// A delayed clause that names its own targets still stands alone: 20-057L The Goddess breaks
	// every Doom-Countered Forward at end of turn and must keep working as its own ability.
	@Test
	void selfContainedDelayedClauseRemainsItsOwnAbility() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(
				"When The Goddess enters the field, at the end of your opponent's turn, break all the "
				+ "Forwards opponent controls with a Doom Counter on them.");
		assertTrue(autos.stream().anyMatch(x -> x.trigger().equals("end of opponent's turn")),
				"a clause naming its own targets is not orphaned and must survive");
	}

	// -------------------------------------------------------------------------
	// "Your opponent reveals their hand. Select …" — 10 cards whose text used to be
	// claimed by the bare OPPONENT_REVEAL_HAND_PATTERN, which reveals the hand and
	// discards everything after it.
	// -------------------------------------------------------------------------

	// 3-056H Zidane: the plain, unrestricted form. Before, only the reveal happened.
	@Test
	void revealHandSelectAndDiscardActuallyDiscards() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"Your opponent reveals his/her hand. Select 1 card from their hand. "
				+ "Your opponent discards this card.", null);
		assertNotNull(fn);
		fn.accept(ctx);
		verify(ctx).selectFromOpponentHandAndDiscard(eq(1), isNull(), any());
	}

	// 6-044L Zidane / 5-055C Thief: the selection is restricted to a card type, so the
	// filter must actually reject the others rather than being decorative.
	@Test
	void revealHandSelectAndDiscardRestrictsToNamedCardType() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"Your opponent reveals his/her hand. Select 1 Forward from their hand. "
				+ "Your opponent discards this card.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		ArgumentCaptor<Predicate<CardData>> filter = ArgumentCaptor.forClass(Predicate.class);
		verify(ctx).selectFromOpponentHandAndDiscard(eq(1), filter.capture(), any());
		assertTrue(filter.getValue().test(cardOfType("Forward")));
		assertFalse(filter.getValue().test(cardOfType("Backup")));
	}

	// 5-055C Thief says "Character", which is not a card type of its own but the
	// Forward/Backup/Monster union — everything except a Summon.
	@Test
	void revealHandSelectAndDiscardTreatsCharacterAsEveryNonSummon() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"Your opponent reveals his/her hand. Select 1 Character card from their hand. "
				+ "Your opponent discards this card.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		ArgumentCaptor<Predicate<CardData>> filter = ArgumentCaptor.forClass(Predicate.class);
		verify(ctx).selectFromOpponentHandAndDiscard(eq(1), filter.capture(), any());
		assertTrue(filter.getValue().test(cardOfType("Forward")));
		assertTrue(filter.getValue().test(cardOfType("Backup")));
		assertTrue(filter.getValue().test(cardOfType("Monster")));
		assertFalse(filter.getValue().test(cardOfType("Summon")));
	}

	// 12-035C Belle restricts by cost instead of type.
	@Test
	void revealHandSelectAndDiscardRestrictsByCostFloor() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"Your opponent reveals their hand. Select 1 card of cost 4 or more in their hand. "
				+ "Your opponent discards this card.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		ArgumentCaptor<Predicate<CardData>> filter = ArgumentCaptor.forClass(Predicate.class);
		verify(ctx).selectFromOpponentHandAndDiscard(eq(1), filter.capture(), any());
		assertTrue(filter.getValue().test(cardOfCost(4)));
		assertTrue(filter.getValue().test(cardOfCost(7)));
		assertFalse(filter.getValue().test(cardOfCost(3)));
	}

	// 17-029L Xezat states the restriction as an exclusion, and puts it after "in their hand"
	// rather than before, so the pattern has to accept it in that trailing position.
	@Test
	void revealHandSelectAndDiscardHonoursTrailingExclusion() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"Your opponent reveals their hand. Select 1 card in their hand other than a Backup. "
				+ "Your opponent discards this card.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		ArgumentCaptor<Predicate<CardData>> filter = ArgumentCaptor.forClass(Predicate.class);
		verify(ctx).selectFromOpponentHandAndDiscard(eq(1), filter.capture(), any());
		assertFalse(filter.getValue().test(cardOfType("Backup")));
		assertTrue(filter.getValue().test(cardOfType("Forward")));
	}

	// 24-046R Leech Bat / 25-042C Zidane: optional pick, then discard AND draw. Must not be
	// confused with the RFP-and-draw sibling, which removes the card from the game instead.
	@Test
	void revealHandOptionalPickDiscardsAndDrawsRatherThanRemovingFromGame() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"Your opponent reveals their hand. You may select 1 card from their hand. "
				+ "If you do so, your opponent discards it and draws 1 card.", null);
		assertNotNull(fn);
		fn.accept(ctx);
		verify(ctx).revealHandOptPickDiscardOpponentDraws();
		verify(ctx, never()).revealHandOptPickRfpOpponentDraws();
	}

	// 29-054R Great Malboro: the removal is temporary. The three-sentence prefix is shared with
	// the permanent-RFP pattern, so the wrong one claiming it would silently make it permanent.
	@Test
	void greatMalboroRemovalIsTemporaryNotPermanent() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"your opponent reveals their hand. Select up to 2 cards in their hand. "
				+ "Your opponent removes them from the game. "
				+ "At the end of your opponent's turn, add them to their owner's hand.", null);
		assertNotNull(fn);
		fn.accept(ctx);
		verify(ctx).selectFromOpponentHandRfpUntilEndOfOpponentTurn(2);
		verify(ctx, never()).selectFromOpponentHandAndRfp(anyInt());
	}

	// The permanent form must keep working — the new guard sits in front of it.
	@Test
	void revealHandSelectRfpWithoutAReturnClauseStaysPermanent() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"Your opponent reveals their hand. Select 1 card in their hand. "
				+ "Your opponent removes it from the game.", null);
		assertNotNull(fn);
		fn.accept(ctx);
		verify(ctx).selectFromOpponentHandAndRfp(1);
		verify(ctx, never()).selectFromOpponentHandRfpUntilEndOfOpponentTurn(anyInt());
	}

	// Great Malboro's delayed clause must not also be lifted out as a standalone auto ability:
	// "add them to their owner's hand" has no antecedent once detached from the selection.
	@Test
	void greatMalboroParsesAsOneEntersFieldAbility() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(
				"If you control 5 or more Backups, Great Malboro also becomes a Forward with 8000 power."
				+ "[[br]]When Great Malboro enters the field, your opponent reveals their hand. "
				+ "Select up to 2 cards in their hand. Your opponent removes them from the game. "
				+ "At the end of your opponent's turn, add them to their owner's hand.");
		assertEquals(1, autos.size(), "the delayed return must stay with its trigger");
		assertEquals("enters the field", autos.get(0).trigger());
	}

	// -------------------------------------------------------------------------
	// "Choose 1 … in your Break Zone. Put it on top of your deck."
	// The choose header was recognised but this followup was not, so the whole
	// ability resolved to a "followup not yet implemented" log line and did nothing.
	// -------------------------------------------------------------------------

	/** Stubs a single own-Break-Zone hit at index 0. */
	private static ForwardTarget stubOwnBzHit(GameContext ctx) {
		ForwardTarget hit = new ForwardTarget(true, 0, ForwardTarget.CardZone.BREAK_ZONE);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharactersFromBreakZone(
				anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), anyInt(), any(), anyInt(), any(),
				anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
		)).thenReturn(new ArrayList<>(List.of(hit)));
		return hit;
	}

	// 26-077R Noctis. The Category filter has to reach the Break Zone selection, and all three
	// Character types must be eligible — "Character" is not just Forwards.
	@Test
	void noctisPutsChosenCategoryCharacterFromBreakZoneOnTopOfDeck() {
		GameContext ctx = mock(GameContext.class);
		ForwardTarget hit = stubOwnBzHit(ctx);

		Consumer<GameContext> fn = ActionResolver.parse(
				"choose 1 Category XV Character in your Break Zone. Put it on top of your deck.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).selectCharactersFromBreakZone(
				eq(1), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), anyInt(), any(), anyInt(), any(),
				eq(true), eq(true), eq(true),              // Forwards, Backups, Monsters
				any(), any(), eq("XV"), any(), anyBoolean(), any(), anyBoolean());
		verify(ctx).putBreakZoneTargetOnTopOfDeck(hit);
	}

	// 3-118H Odin words the same followup as optional, so it must ask before acting.
	@Test
	void optionalPutOnTopOfDeckIsSkippedWhenDeclined() {
		GameContext ctx = mock(GameContext.class);
		stubOwnBzHit(ctx);
		when(ctx.promptYouMay(any())).thenReturn(false);

		Consumer<GameContext> fn = ActionResolver.parse(
				"choose 1 Card Name Odin in your Break Zone. You may put it on top of your deck.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).promptYouMay(any());
		verify(ctx, never()).putBreakZoneTargetOnTopOfDeck(any());
	}

	@Test
	void optionalPutOnTopOfDeckActsWhenAccepted() {
		GameContext ctx = mock(GameContext.class);
		ForwardTarget hit = stubOwnBzHit(ctx);
		when(ctx.promptYouMay(any())).thenReturn(true);

		Consumer<GameContext> fn = ActionResolver.parse(
				"choose 1 Card Name Odin in your Break Zone. You may put it on top of your deck.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).putBreakZoneTargetOnTopOfDeck(hit);
	}

	// The mandatory form must not ask.
	@Test
	void mandatoryPutOnTopOfDeckDoesNotPrompt() {
		GameContext ctx = mock(GameContext.class);
		stubOwnBzHit(ctx);

		Consumer<GameContext> fn = ActionResolver.parse(
				"choose 1 Category XV Character in your Break Zone. Put it on top of your deck.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx, never()).promptYouMay(any());
	}

	// -------------------------------------------------------------------------
	// 8-147S Fordola: "choose 1 Backup you control. You may remove it from the game.
	// If you do so, Fordola gains +1000 power, Haste, First Strike and Brave.
	// (This effect does not end at the end of the turn.)"
	// The "You may" used to be ignored (the Backup was removed unconditionally) and the
	// payoff was dropped, because the permanent self-buff had no parser.
	// -------------------------------------------------------------------------

	private static final String FORDOLA_TEXT =
			"choose 1 Backup you control. You may remove it from the game. If you do so, Fordola "
			+ "gains +1000 power, Haste, First Strike and Brave. (This effect does not end at the end of the turn.)";

	private static final EnumSet<CardData.Trait> FORDOLA_TRAITS =
			EnumSet.of(CardData.Trait.HASTE, CardData.Trait.FIRST_STRIKE, CardData.Trait.BRAVE);

	/** Stubs a single own-Backup hit plus the progress flag the "If you do so" wrapper reads. */
	private static ForwardTarget stubOwnBackupHit(GameContext ctx, boolean accepts) {
		ForwardTarget hit = new ForwardTarget(true, 0, ForwardTarget.CardZone.BACKUP);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(new ArrayList<>(List.of(hit)));
		when(ctx.promptYouMay(any())).thenReturn(accepts);
		when(ctx.effectMadeProgress()).thenReturn(accepts);
		return hit;
	}

	@Test
	void fordolaRemovesTheBackupAndTakesThePermanentBuffWhenAccepted() {
		CardData fordola = makeForward("Fordola", "Lightning", 4, 8000);
		GameContext ctx = mock(GameContext.class);
		ForwardTarget hit = stubOwnBackupHit(ctx, true);

		Consumer<GameContext> fn = ActionResolver.parse(FORDOLA_TEXT, fordola);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).removeTargetFromGame(hit);
		verify(ctx).boostSourceForwardPermanently(fordola, 1000, FORDOLA_TRAITS);
		// Permanent, not the end-of-turn primitive.
		verify(ctx, never()).boostSourceForward(any(), anyInt(), any());
	}

	@Test
	void fordolaRemovesNothingAndSkipsTheBuffWhenDeclined() {
		CardData fordola = makeForward("Fordola", "Lightning", 4, 8000);
		GameContext ctx = mock(GameContext.class);
		stubOwnBackupHit(ctx, false);

		Consumer<GameContext> fn = ActionResolver.parse(FORDOLA_TEXT, fordola);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).promptYouMay(any());
		verify(ctx).markEffectFizzled();
		verify(ctx, never()).removeTargetFromGame(any());
		verify(ctx, never()).boostSourceForwardPermanently(any(), anyInt(), any());
	}

	// A "Remove it from the game" with no "You may" must still act without asking.
	@Test
	void mandatoryRemoveFromGameFollowupDoesNotPrompt() {
		GameContext ctx = mock(GameContext.class);
		ForwardTarget hit = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(new ArrayList<>(List.of(hit)));

		Consumer<GameContext> fn = ActionResolver.parse(
				"choose 1 Forward opponent controls. Remove it from the game.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).removeTargetFromGame(hit);
		verify(ctx, never()).promptYouMay(any());
	}

	// The parenthetical is the only thing separating the permanent buff from the
	// otherwise identically worded end-of-turn one.
	@Test
	void selfBuffRoutesToPermanentOnlyWithTheParenthetical() {
		CardData fordola = makeForward("Fordola", "Lightning", 4, 8000);

		GameContext perm = mock(GameContext.class);
		Consumer<GameContext> permFn = ActionResolver.parse(
				"Fordola gains +1000 power, Haste, First Strike and Brave. "
				+ "(This effect does not end at the end of the turn.)", fordola);
		assertNotNull(permFn);
		permFn.accept(perm);
		verify(perm).boostSourceForwardPermanently(fordola, 1000, FORDOLA_TRAITS);

		// Same wording ending "until the end of the turn" is a different effect and must not
		// reach the permanent primitive.
		GameContext eot = mock(GameContext.class);
		Consumer<GameContext> eotFn = ActionResolver.parse(
				"Fordola gains +1000 power, Haste, First Strike and Brave until the end of the turn.", fordola);
		if (eotFn != null) {
			eotFn.accept(eot);
			verify(eot, never()).boostSourceForwardPermanently(any(), anyInt(), any());
		}
	}

	// 20-078H Noctis: "put 1 Character you control into the Break Zone. When you do so, play
	// Noctis from the Break Zone onto the field dull. Noctis gains +2000 power. (…)"
	// Only the last sentence parses. The compound-sentence fallback drops what it cannot resolve,
	// which would hand Noctis the buff without paying the cost that gates it.
	@Test
	void compoundFallbackStopsAtAnUnresolvedDoSoConditional() {
		CardData noctis = makeForward("Noctis", "Light", 3, 7000);
		GameContext ctx = mock(GameContext.class);

		Consumer<GameContext> fn = ActionResolver.parse(
				"put 1 Character you control into the Break Zone. "
				+ "When you do so, play Noctis from the Break Zone onto the field dull. "
				+ "Noctis gains +2000 power. (This effect does not end at the end of the turn.)", noctis);

		// Better unparsed than silently granting the payoff of a step that never ran.
		if (fn != null) {
			fn.accept(ctx);
			verify(ctx, never()).boostSourceForwardPermanently(any(), anyInt(), any());
		}
	}

	// Sentences before the conditional are still composed — the guard truncates, it does not
	// discard the whole ability.
	@Test
	void compoundFallbackKeepsSentencesBeforeTheConditional() {
		CardData firion = makeForward("Firion", "Fire", 3, 7000);
		GameContext ctx = mock(GameContext.class);

		Consumer<GameContext> fn = ActionResolver.parse(
				"Draw 1 card. When you do so, some effect that does not parse at all.", firion);
		assertNotNull(fn, "the resolvable leading sentence must still fire");
		fn.accept(ctx);
		verify(ctx).drawCards(1);
	}

	// 11-037L Barthandelus: "choose up to 2 Forwards opponent controls. Deal each of them damage
	// equal to its power minus 2000." The damage-expression followup only accepted "deal it/them",
	// so the plural wording fell through to the choose chain's not-implemented warning.
	// The amount is per target, not one amount shared across them.
	@Test
	void barthandelusDamagesEachChosenForwardByItsOwnPower() {
		GameContext ctx = mock(GameContext.class);
		ForwardTarget big   = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		ForwardTarget small = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(new ArrayList<>(List.of(big, small)));
		when(ctx.effectiveTargetPower(big)).thenReturn(9000);
		when(ctx.effectiveTargetPower(small)).thenReturn(3000);

		Consumer<GameContext> fn = ActionResolver.parse(
				"choose up to 2 Forwards opponent controls. "
				+ "Deal each of them damage equal to its power minus 2000.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		// up to 2 (upTo=true), opponent's side only
		verify(ctx).selectCharacters(eq(2), eq(true), eq(true), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				any(), any(), anyBoolean(), any(), anyBoolean());
		verify(ctx).damageTarget(big, 7000);
		verify(ctx).damageTarget(small, 1000);
	}

	// Power below the subtrahend must floor at 0 rather than healing the Forward.
	@Test
	void powerMinusDamageFloorsAtZero() {
		GameContext ctx = mock(GameContext.class);
		ForwardTarget weak = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(new ArrayList<>(List.of(weak)));
		when(ctx.effectiveTargetPower(weak)).thenReturn(1000);

		Consumer<GameContext> fn = ActionResolver.parse(
				"choose up to 2 Forwards opponent controls. "
				+ "Deal each of them damage equal to its power minus 2000.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).damageTarget(weak, 0);
	}

	// 28-046C Zidane: "reveal the top 5 cards of your deck. Add 1 card of cost 2 or less among
	// them to your hand and return the other cards to the bottom of your deck in any order."
	// The reveal-top-N family required a card type, so the untyped cost-only form did not parse.
	@Test
	void zidaneAddsAnyCardOfCostTwoOrLessFromTheRevealedFive() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"reveal the top 5 cards of your deck. Add 1 card of cost 2 or less among them "
				+ "to your hand and return the other cards to the bottom of your deck in any order.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		// No type/job/category/name filter — cost alone selects. reveal 5, add up to 1, cost <= 2.
		verify(ctx).revealTopAddUpToMatchingRestBottom(5, 1, null, null, null, null, 2);
	}

	// The typed forms keep their filter — the untyped arm must not swallow them.
	@Test
	void typedRevealTopNStillPassesItsTypeFilter() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"reveal the top 5 cards of your deck. Add 1 Character of cost 2 or less among them "
				+ "to your hand and return the other cards to the bottom of your deck in any order.", null);
		assertNotNull(fn);
		fn.accept(ctx);
		verify(ctx).revealTopAddUpToMatchingRestBottom(5, 1, null, null, null, "Character", 2);
	}

	// A bare "Add 1 card among them" restricts nothing and belongs to the later, more general
	// parser. This one must not claim it, or that parser is shadowed.
	@Test
	void untypedRevealTopNWithoutACostClauseIsNotClaimedHere() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"reveal the top 5 cards of your deck. Add 1 card among them to your hand "
				+ "and return the other cards to the bottom of your deck in any order.", null);
		assertNotNull(fn);
		fn.accept(ctx);
		// Whatever handles it, it must not be the cost-filtered reveal-top-N path.
		verify(ctx, never()).revealTopAddUpToMatchingRestBottom(anyInt(), anyInt(),
				any(), any(), any(), any(), eq(2));
	}

	// -------------------------------------------------------------------------
	// 20-107H Urianger: "if 1 or more of your cards have been removed from the game,
	// you may search for 1 Category XIV Forward and add it to your hand."
	// The search parsed but the gate did not, so it searched unconditionally.
	// -------------------------------------------------------------------------

	private static final String URIANGER_TEXT =
			"if 1 or more of your cards have been removed from the game, "
			+ "you may search for 1 Category XIV Forward and add it to your hand.";

	@Test
	void uriangerDoesNotSearchWithAnEmptyRemovedFromGameZone() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		// countSelfRfgCards is a default method, so a plain mock would stub it out and never
		// reach the per-owner counts. Call it for real — the isP1 routing is what is under test.
		when(ctx.countSelfRfgCards(any(), any())).thenCallRealMethod();
		when(ctx.countP1RfgCards(any(), any())).thenReturn(0);

		Consumer<GameContext> fn = ActionResolver.parse(URIANGER_TEXT, null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx, never()).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
				anyBoolean(), anyBoolean());
	}

	@Test
	void uriangerSearchesOnceAnyOwnedCardIsRemovedFromTheGame() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.promptYouMay(any())).thenReturn(true);   // the search is optional; accept it
		when(ctx.isP1()).thenReturn(true);
		// countSelfRfgCards is a default method, so a plain mock would stub it out and never
		// reach the per-owner counts. Call it for real — the isP1 routing is what is under test.
		when(ctx.countSelfRfgCards(any(), any())).thenCallRealMethod();
		when(ctx.countP1RfgCards(any(), any())).thenReturn(1);

		Consumer<GameContext> fn = ActionResolver.parse(URIANGER_TEXT, null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).searchDeckForCard(eq(true), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), any(), any(), eq("XIV"), any(), any(), any(), eq("hand"), eq(1),
				anyBoolean(), anyBoolean());
	}

	// The zone is the ability user's own — the opponent filling theirs must not satisfy it.
	@Test
	void uriangerIgnoresTheOpponentsRemovedFromGameZone() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		// countSelfRfgCards is a default method, so a plain mock would stub it out and never
		// reach the per-owner counts. Call it for real — the isP1 routing is what is under test.
		when(ctx.countSelfRfgCards(any(), any())).thenCallRealMethod();
		when(ctx.countP1RfgCards(any(), any())).thenReturn(0);
		when(ctx.countP2RfgCards(any(), any())).thenReturn(5);

		Consumer<GameContext> fn = ActionResolver.parse(URIANGER_TEXT, null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx, never()).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
				anyBoolean(), anyBoolean());
	}

	// As P2 the same text must read P2's zone, not P1's.
	@Test
	void selfRfgGateReadsTheAbilityUsersOwnZoneAsP2() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(false);
		// countSelfRfgCards is a default method, so a plain mock would stub it out and never
		// reach the per-owner counts. Call it for real — the isP1 routing is what is under test.
		when(ctx.countSelfRfgCards(any(), any())).thenCallRealMethod();
		when(ctx.countP1RfgCards(any(), any())).thenReturn(9);
		when(ctx.countP2RfgCards(any(), any())).thenReturn(0);

		Consumer<GameContext> fn = ActionResolver.parse(URIANGER_TEXT, null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx, never()).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
				anyBoolean(), anyBoolean());
	}

	// 28-022L states the same gate with a threshold and a Job restriction, which must reach the count.
	@Test
	void selfRfgGateAppliesItsJobFilterAndThreshold() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		// countSelfRfgCards is a default method, so a plain mock would stub it out and never
		// reach the per-owner counts. Call it for real — the isP1 routing is what is under test.
		when(ctx.countSelfRfgCards(any(), any())).thenCallRealMethod();
		when(ctx.countP1RfgCards(null, "Remnant")).thenReturn(1);   // one short of the threshold

		Consumer<GameContext> fn = ActionResolver.parse(
				"if you have 2 or more Job Remnant you own removed from the game, "
				+ "your opponent discards 2 cards.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).countP1RfgCards(null, "Remnant");
		verify(ctx, never()).forceOpponentDiscard(anyInt());
	}

	// -------------------------------------------------------------------------
	// "You may search …" — searching is a public event that opponents' abilities react to
	// (5-130R Tonberry, 13-034H Remedi, 25-111H The Emperor), so declining has to mean the
	// search never happened, not that it happened and found nothing.
	// -------------------------------------------------------------------------

	private static void verifyNoSearch(GameContext ctx) {
		verify(ctx, never()).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
				anyBoolean(), anyBoolean());
	}

	@Test
	void decliningAnOptionalSearchPerformsNoSearchAtAll() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.promptYouMay(any())).thenReturn(false);

		Consumer<GameContext> fn = ActionResolver.parse(
				"you may search for 1 Category XIV Forward and add it to your hand.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).promptYouMay(any());
		verifyNoSearch(ctx);
		verify(ctx).markEffectFizzled();
	}

	// The prompt must come BEFORE the search, or the opponent's search triggers have already
	// fired by the time the player is asked.
	@Test
	void optionalSearchAsksBeforeItSearches() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.promptYouMay(any())).thenReturn(true);

		Consumer<GameContext> fn = ActionResolver.parse(
				"you may search for 1 Category XIV Forward and add it to your hand.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		InOrder order = inOrder(ctx);
		order.verify(ctx).promptYouMay(any());
		order.verify(ctx).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
				anyBoolean(), anyBoolean());
	}

	// A search with no "you may" is mandatory and must not ask.
	@Test
	void mandatorySearchDoesNotPrompt() {
		GameContext ctx = mock(GameContext.class);

		Consumer<GameContext> fn = ActionResolver.parse(
				"search for 1 Category XIV Forward and add it to your hand.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx, never()).promptYouMay(any());
		verify(ctx).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), any(), any(), eq("XIV"), any(), any(), any(), eq("hand"), eq(1),
				anyBoolean(), anyBoolean());
	}

	// Urianger gates on the RFG zone first: a failed condition must not even offer the search.
	@Test
	void uriangerDoesNotOfferTheSearchWhenItsConditionFails() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.countSelfRfgCards(any(), any())).thenCallRealMethod();
		when(ctx.countP1RfgCards(any(), any())).thenReturn(0);

		Consumer<GameContext> fn = ActionResolver.parse(URIANGER_TEXT, null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx, never()).promptYouMay(any());
		verifyNoSearch(ctx);
	}

	// …and when it holds, the player still gets the choice.
	@Test
	void uriangerOffersTheSearchOnceItsConditionHolds() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.countSelfRfgCards(any(), any())).thenCallRealMethod();
		when(ctx.countP1RfgCards(any(), any())).thenReturn(1);
		when(ctx.promptYouMay(any())).thenReturn(false);

		Consumer<GameContext> fn = ActionResolver.parse(URIANGER_TEXT, null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).promptYouMay(any());
		verifyNoSearch(ctx);
	}

	// -------------------------------------------------------------------------
	// Searches whose filter comes off the card the player just chose:
	//   23-130H Luso    — "…search for 1 Job Standard Unit of the same Element as the chosen
	//                      Character and add it to your hand."
	//   12-106R Relm    — "…search for 1 Character with the same name and add it to your hand."
	//   23-078C Alisaie — same, choosing from the Break Zone instead of the field.
	//
	// None of these filters is written in the text. Before this followup existed, ChooseCharacter's
	// generic dispatch matched the trailing "add it to your hand" and returned the chosen Character
	// to hand — the wrong zone, the wrong card, and no search at all.
	// -------------------------------------------------------------------------

	private static final String LUSO_TEXT =
			"choose 1 Character you control. You may search for 1 Job Standard Unit "
			+ "of the same Element as the chosen Character and add it to your hand.";
	private static final String RELM_TEXT =
			"choose 1 Character without 《Multicard》 other than Relm. "
			+ "You may search for 1 Character with the same name and add it to your hand.";
	private static final String ALISAIE_TEXT =
			"choose 1 Character without 《Multicard》 in your Break Zone. "
			+ "You may search for 1 Character with the same name and add it to your hand.";

	/**
	 * Resolves a choose-then-search effect with {@code chosenCard} as the card the player picks.
	 * {@code fromBreakZone} routes the pick through the Break Zone accessor, as Alisaie needs.
	 */
	private static GameContext resolveChooseThenSearch(String text, CardData source,
			CardData chosenCard, boolean fromBreakZone, boolean acceptSearch) {
		Consumer<GameContext> fn = ActionResolver.parse(text, source);
		assertNotNull(fn, "choose-then-search effect should parse");
		ForwardTarget chosen = new ForwardTarget(true, 0,
				fromBreakZone ? ForwardTarget.CardZone.BREAK_ZONE : ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(List.of(chosen));
		when(ctx.selectCharactersFromBreakZone(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean())).thenReturn(List.of(chosen));
		when(ctx.targetCard(chosen)).thenReturn(chosenCard);
		when(ctx.p1BreakZoneCard(0)).thenReturn(chosenCard);
		when(ctx.promptYouMay(any())).thenReturn(acceptSearch);
		fn.accept(ctx);
		return ctx;
	}

	private static GameContext resolveLuso(CardData chosenCard, boolean acceptSearch) {
		return resolveChooseThenSearch(LUSO_TEXT, makeForward("Luso", "Light", 5, 5000),
				chosenCard, false, acceptSearch);
	}

	@Test
	void lusoSearchesForAStandardUnitOfTheChosenCharactersElement() {
		GameContext ctx = resolveLuso(makeForward("Shantotto", "Lightning", 3, 7000), true);

		verify(ctx).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), any(), eq("Standard Unit"), any(), eq("Lightning"),
				any(), any(), eq("hand"), eq(1), anyBoolean(), anyBoolean());
	}

	// A Character with two Elements is each of them, so either satisfies "of the same Element as".
	@Test
	void lusoAcceptsEitherElementOfAMultiElementCharacter() {
		GameContext ctx = resolveLuso(makeForward("Y'shtola", "Water/Wind", 4, 7000), true);

		verify(ctx).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), any(), any(), any(), eq("Water|Wind"),
				any(), any(), eq("hand"), eq(1), anyBoolean(), anyBoolean());
	}

	// The choose is mandatory but the search is not: declining must leave the deck unsearched,
	// since opponents' abilities react to the act of searching.
	@Test
	void lusoDoesNotSearchWhenThePlayerDeclines() {
		GameContext ctx = resolveLuso(makeForward("Shantotto", "Lightning", 3, 7000), false);

		verify(ctx).promptYouMay(any());
		verifyNoSearch(ctx);
	}

	// The chosen Character stays where it is — the trailing "add it to your hand" belongs to the
	// searched card, not to the target.
	@Test
	void lusoDoesNotReturnTheChosenCharacterToHand() {
		GameContext ctx = resolveLuso(makeForward("Shantotto", "Lightning", 3, 7000), true);

		verify(ctx, never()).addTargetToHand(any());
	}

	// Relm copies the chosen Character's name, not its Element.
	@Test
	void relmSearchesForACardSharingTheChosenCharactersName() {
		GameContext ctx = resolveChooseThenSearch(RELM_TEXT, makeForward("Relm", "Wind", 3, 5000),
				makeForward("Shantotto", "Lightning", 3, 7000), false, true);

		verify(ctx).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), eq("Shantotto"), any(), any(), isNull(),
				any(), any(), eq("hand"), eq(1), anyBoolean(), anyBoolean());
	}

	// Alisaie chooses from the Break Zone, where targetCard() cannot reach — the card has to be
	// read through the Break Zone accessor instead.
	@Test
	void alisaieReadsTheChosenNameFromTheBreakZone() {
		GameContext ctx = resolveChooseThenSearch(ALISAIE_TEXT, makeForward("Alisaie", "Fire", 2, 5000),
				makeForward("Shantotto", "Lightning", 3, 7000), true, true);

		verify(ctx).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), eq("Shantotto"), any(), any(), isNull(),
				any(), any(), eq("hand"), eq(1), anyBoolean(), anyBoolean());
	}

	@Test
	void chooseThenSearchIsAttributedToItsOwnFollowup() {
		assertEquals("ChooseCharacter / SearchMatchingChosen",
				ActionResolver.fullDescription(LUSO_TEXT, makeForward("Luso", "Light", 5, 5000)));
		assertEquals("ChooseCharacter / SearchMatchingChosen",
				ActionResolver.fullDescription(RELM_TEXT, makeForward("Relm", "Wind", 3, 5000)));
		assertEquals("ChooseCharacter / SearchMatchingChosen",
				ActionResolver.fullDescription(ALISAIE_TEXT, makeForward("Alisaie", "Fire", 2, 5000)));
	}

	// -------------------------------------------------------------------------
	// Searching is a public event. 5-130R Tonberry, 13-034H Remedi and 25-111H The Emperor
	// all watch for it; none of them parsed a single auto ability before.
	// -------------------------------------------------------------------------

	@Test
	void opponentSearchTriggerIsRecognisedInAllThreePrintings() {
		// "searches for 1 or more cards" (Tonberry, Remedi) and the "for"-less Emperor wording.
		List<AutoAbility> tonberry = CardData.parseAutoAbilities(
				"When a Character opponent controls searches for 1 or more cards, "
				+ "put Tonberry into the Break Zone. If you do so, break that Character.");
		assertEquals(1, tonberry.size());
		assertEquals("opponent searches", tonberry.get(0).trigger());
		assertEquals("a Character opponent controls", tonberry.get(0).triggerCard());

		List<AutoAbility> remedi = CardData.parseAutoAbilities(
				"When your opponent searches for 1 or more cards, "
				+ "your opponent discards 1 card from their hand.");
		assertEquals(1, remedi.size());
		assertEquals("opponent searches", remedi.get(0).trigger());
		assertEquals("your opponent", remedi.get(0).triggerCard());

		List<AutoAbility> emperor = CardData.parseAutoAbilities(
				"When your opponent searches 1 or more cards, draw 1 card.");
		assertEquals(1, emperor.size());
		assertEquals("opponent searches", emperor.get(0).trigger());
	}

	// A second "When …" trigger after a search trigger must start its own ability rather than
	// being swallowed into the search ability's effect text.
	@Test
	void aTriggerFollowingASearchTriggerStartsItsOwnAbility() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(
				"When your opponent searches 1 or more cards, draw 1 card. "
				+ "When Someone enters the field, draw 1 card.");
		assertEquals(2, autos.size(), "the search effect must end where the next trigger begins");
		assertEquals("opponent searches", autos.get(0).trigger());
		assertEquals("draw 1 card.", autos.get(0).effectText().trim());
	}

	// Tonberry sacrifices itself first and only then breaks the searcher, which the trigger
	// supplies as a preloaded target ("that Character").
	@Test
	void tonberryBreaksTheSearchingCharacterAfterSacrificingItself() {
		CardData tonberry = makeForward("Tonberry", "Dark", 2, 3000);
		GameContext ctx = mock(GameContext.class);
		ForwardTarget searcher = new ForwardTarget(false, 2, ForwardTarget.CardZone.FORWARD);
		when(ctx.consumePreloadedTargets()).thenReturn(new ArrayList<>(List.of(searcher)));
		when(ctx.effectMadeProgress()).thenReturn(true);

		Consumer<GameContext> fn = ActionResolver.parse(
				"put Tonberry into the Break Zone. If you do so, break that Character.", tonberry);
		assertNotNull(fn);
		fn.accept(ctx);

		InOrder order = inOrder(ctx);
		order.verify(ctx).breakSourceCard(tonberry);
		order.verify(ctx).breakTarget(searcher);
	}

	// If Tonberry could not be put into the Break Zone, the searcher survives.
	@Test
	void tonberryDoesNotBreakTheSearcherIfItCouldNotSacrificeItself() {
		CardData tonberry = makeForward("Tonberry", "Dark", 2, 3000);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets())
				.thenReturn(new ArrayList<>(List.of(new ForwardTarget(false, 2, ForwardTarget.CardZone.FORWARD))));
		when(ctx.effectMadeProgress()).thenReturn(false);

		Consumer<GameContext> fn = ActionResolver.parse(
				"put Tonberry into the Break Zone. If you do so, break that Character.", tonberry);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx, never()).breakTarget(any());
	}

	// With nothing preloaded the action has no target and must do nothing rather than guess.
	@Test
	void demonstrativeBreakDoesNothingWithoutAPreloadedTarget() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);

		Consumer<GameContext> fn = ActionResolver.parse("break that Character.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx, never()).breakTarget(any());
	}

	// "When you do so, break that Forward" (20-102L) binds to a card named earlier in its own
	// ability, not to a trigger's card. The anchored demonstrative must not claim it.
	@Test
	void demonstrativeBreakDoesNotClaimADoSoFollowup() {
		assertNotNull(ActionResolverState.tryParseTriggeredTargetAction("break that Character.", 0),
				"the bare demonstrative is the form Tonberry needs");
		assertNull(ActionResolverState.tryParseTriggeredTargetAction("When you do so, break that Forward.", 0),
				"a do-so followup binds to a card named earlier, not to the trigger's card");
		assertNull(ActionResolverState.tryParseTriggeredTargetAction("break it.", 0),
				"the ambiguous pronoun form stays out — see TRIGGERED_TARGET_ACTION_BARE");
		// Breaktouch's own wording. DamageResolver keys its dedicated break path off this
		// predicate, so admitting the Forward form here would divert Breaktouch.
		assertNull(ActionResolverState.tryParseTriggeredTargetAction("break that Forward.", 0),
				"\"that Forward\" is Breaktouch and must keep the damage path");
	}

	// -------------------------------------------------------------------------
	// "discards … due to your Summons or abilities" (an 11-card family) and
	// "1 or more cards are added to your opponent's hand from the Break Zone".
	// -------------------------------------------------------------------------

	@Test
	void discardByEffectTriggerAcceptsEveryPrintedWording() {
		// count and "from their hand" both vary across printings
		for (String text : List.of(
				"When your opponent discards a card from their hand due to your Summons or abilities, draw 1 card.",
				"When your opponent discards 1 or more cards from their hand due to your Summons or abilities, draw 1 card.",
				"When your opponent discards 1 or more cards due to your Summons or abilities, draw 1 card.",
				"When your opponent discards a card from his/her hand due to your Summons or abilities, draw 1 card.")) {
			List<AutoAbility> autos = CardData.parseAutoAbilities(text);
			assertEquals(1, autos.size(), text);
			assertEquals("opponent discards by effect", autos.get(0).trigger(), text);
		}
	}

	// 27-036L Locke watches Characters and Summons separately, so the discarded type has to
	// reach the trigger label — otherwise both of its abilities fire on any discard.
	@Test
	void discardByEffectTriggerSeparatesCharacterAndSummonWatchers() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(
				"When your opponent discards 1 or more Characters due to your Summons or abilities, "
				+ "choose 1 Character. Dull it and Freeze it.[[br]]"
				+ "When your opponent discards 1 or more Summons due to your Summons or abilities, draw 1 card.");
		assertEquals(2, autos.size());
		assertEquals("opponent discards character by effect", autos.get(0).trigger());
		assertEquals("opponent discards summon by effect",    autos.get(1).trigger());
	}

	// The cause clause always says "Summons", so classifying on the whole trigger would read
	// every one of these as watching for a discarded Summon.
	@Test
	void discardByEffectClassifiesOnWhatWasDiscardedNotOnTheCause() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(
				"When your opponent discards 1 or more cards due to your Summons or abilities, draw 1 card.");
		assertEquals(1, autos.size());
		assertEquals("opponent discards by effect", autos.get(0).trigger(),
				"\"due to your Summons\" names the cause, not the discarded card");
	}

	// 13-034H Remedi's second ability is optional and once per turn; both must survive.
	@Test
	void remediSecondAbilityKeepsItsOptionalAndOncePerTurnFlags() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(
				"When your opponent discards a card from their hand due to your Summons or abilities, "
				+ "you may search for 1 Card Name Cid Randell and add it to your hand. "
				+ "This effect will trigger only once per turn.");
		assertEquals(1, autos.size());
		assertEquals("opponent discards by effect", autos.get(0).trigger());
		assertTrue(autos.get(0).youMay(),      "the search is optional");
		assertTrue(autos.get(0).oncePerTurn(), "and limited to once per turn");
	}

	// 25-111H The Emperor: both of its triggers, and neither swallowing the other's text.
	@Test
	void emperorParsesBothSalvageAndSearchTriggers() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(
				"When 1 or more cards are added to your opponent's hand from the Break Zone, draw 1 card.[[br]]"
				+ "When your opponent searches 1 or more cards, draw 1 card.");
		assertEquals(2, autos.size());
		assertEquals("opponent salvages from break zone", autos.get(0).trigger());
		assertEquals("opponent searches", autos.get(1).trigger());
	}

	// "added to your opponent's hand from the Break Zone" contains "Break Zone", which the
	// put-into-break-zone branch would otherwise claim.
	@Test
	void salvageTriggerIsNotMistakenForPutIntoBreakZone() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(
				"When 1 or more cards are added to your opponent's hand from the Break Zone, draw 1 card.");
		assertEquals(1, autos.size());
		assertNotEquals("put into break zone", autos.get(0).trigger());
		assertEquals("opponent salvages from break zone", autos.get(0).trigger());
	}

	// 24-006C Clive states two Jobs. meetsJobFilter reads bar-separated names, so the printed
	// "Job Eikon or Job Dominant" has to be rewritten or the condition can never be satisfied.
	@Test
	void selfRfgGateNormalisesADisjunctiveJobList() {
		CardData clive = makeForward("Clive", "Fire", 5, 9000);
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.countSelfRfgCards(any(), any())).thenCallRealMethod();
		when(ctx.countP1RfgCards(null, "Eikon|Dominant")).thenReturn(1);

		Consumer<GameContext> fn = ActionResolver.parse(
				"If any Job Eikon or Job Dominant you own are removed from the game, "
				+ "Clive gains +4000 power and Brave.", clive);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).countP1RfgCards(null, "Eikon|Dominant");
		verify(ctx).boostSourceForward(eq(clive), eq(4000), any());
	}

	// 17-091L Exdeath's gate counts BOTH players' removed-from-game zones. The new owner-scoped
	// gate must not claim it — the two wordings mean different things and read different zones.
	@Test
	void combinedRfgGateStillCountsBothPlayersZones() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.countRemovedFromGame()).thenReturn(5);   // below the threshold

		Consumer<GameContext> fn = ActionResolver.parse(
				"If there are 20 or more cards removed from the game, "
				+ "your opponent selects 1 Character they control. Remove it from the game.", null);
		assertNotNull(fn);
		fn.accept(ctx);

		verify(ctx).countRemovedFromGame();
		verify(ctx, never()).countSelfRfgCards(any(), any());
	}

	// The permanent buff belongs to the source card only — a different card's name must not match.
	@Test
	void permanentSelfBuffRejectsAnotherCardsName() {
		CardData other = makeForward("Vincent", "Dark", 5, 9000);
		assertNull(ActionResolver.parse(
				"Fordola gains +1000 power, Haste, First Strike and Brave. "
				+ "(This effect does not end at the end of the turn.)", other));
	}

	// =========================================================================================
	// Emet-Selch (12-024H): "When Emet-Selch is chosen by your opponent's Summons or abilities,
	// remove Emet-Selch from the game. If you do so, play Emet-Selch onto the field at the end of
	// the turn."
	//
	// The subject names the card itself, so only the copy actually chosen may react. Dispatch used
	// to fire every "chosen by opponent's Summon or ability" ability across the chosen player's
	// whole field without consulting the subject, so targeting any friendly Character removed
	// Emet-Selch. The subject-driven cards ("a Forward you control") must keep firing field-wide.
	// =========================================================================================

	private static final String EMET_SELCH_TEXT =
			"When Emet-Selch is chosen by your opponent's Summons or abilities, "
			+ "remove Emet-Selch from the game. "
			+ "If you do so, play Emet-Selch onto the field at the end of the turn.";

	/** A Forward whose auto-abilities are parsed from {@code text}. */
	private static CardData makeAutoAbilityForward(String name, String element, int power, String text) {
		return new CardData(null, name, element, 5, power, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), CardData.parseAutoAbilities(text), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	/** Seats {@code card} on P1's Forward row with its owner recorded, as a real game would. */
	private static void placeP1Forward(MainWindow mw, CardData card) {
		mw.gameState.getIdentity().put(card, true);
		mw.placeCardInForwardZone(card);
	}

	@Test
	void emetSelchDoesNotReactToAnotherFriendlyForwardBeingChosen() {
		MainWindow mw = new MainWindow();
		CardData emet = makeAutoAbilityForward("Emet-Selch", "Ice", 9000, EMET_SELCH_TEXT);
		CardData ally = makeForward("Ally", "Ice", 3, 7000);
		placeP1Forward(mw, emet);
		placeP1Forward(mw, ally);

		// The opponent's ability chooses Ally. Emet-Selch's subject names itself, so it sees nothing.
		mw.autoAbilityTriggers.triggerAutoAbilitiesForChosenByOpponentSummonOrAbility(true, List.of(ally));

		assertTrue(mw.p1ForwardCards.contains(emet),
				"Emet-Selch must stay put when a different Forward is the chosen card");
	}

	@Test
	void emetSelchReactsWhenItIsItselfChosen() {
		MainWindow mw = new MainWindow();
		CardData emet = makeAutoAbilityForward("Emet-Selch", "Ice", 9000, EMET_SELCH_TEXT);
		CardData ally = makeForward("Ally", "Ice", 3, 7000);
		placeP1Forward(mw, emet);
		placeP1Forward(mw, ally);

		mw.autoAbilityTriggers.triggerAutoAbilitiesForChosenByOpponentSummonOrAbility(true, List.of(emet));

		assertFalse(mw.p1ForwardCards.contains(emet),
				"Emet-Selch is the chosen card — it removes itself from the game");
		assertTrue(mw.p1ForwardCards.contains(ally), "the untargeted Forward is unaffected");
	}

	@Test
	void filterSubjectChosenTriggerStillFiresForAnyMatchingForward() {
		// Tama (18-059R) shape: "a Forward you control" is a filter, not a self-reference, so the
		// watcher reacts to any of its controller's Forwards being chosen — including another card.
		String text = "When a Forward you control is chosen by your opponent's Summon or ability, "
				+ "draw 1 card.";
		MainWindow mw = new MainWindow();
		mw.gameState.initializeDeck(List.of(makeForward("Deck Card", "Ice", 2, 5000)), List.of());
		mw.gameState.getP1Hand().clear();

		CardData watcher = makeAutoAbilityForward("Tama", "Ice", 7000, text);
		CardData ally    = makeForward("Ally", "Ice", 3, 7000);
		placeP1Forward(mw, watcher);
		placeP1Forward(mw, ally);

		mw.autoAbilityTriggers.triggerAutoAbilitiesForChosenByOpponentSummonOrAbility(true, List.of(ally));

		assertEquals(1, mw.gameState.getP1Hand().size(),
				"a filter subject is satisfied by any matching Forward, not just the watcher itself");
	}

	// =========================================================================================
	// Rydia (15-083L): "《0》: Cast 1 Summon of cost equal to or less than the number of Growth
	// Counters placed on Rydia from your hand without paying the cost. You can only use this
	// ability once per turn."
	//
	// The cost ceiling is not a literal, and is not known until the ability is activated, so it
	// travels the same xValue channel a printed "X" does: the ability carries counterScaleName
	// "Growth", the activation path reads that counter off the source card into xValue, and the
	// effect uses it as the cap. The counter itself comes from Rydia's own enters-field trigger,
	// so the two abilities have to agree on the counter's name for either to be worth anything.
	// =========================================================================================

	private static final String RYDIA_TEXT =
			"If Rydia is dealt damage by your opponent's Summons or abilities, the damage becomes 0 instead.[[br]]   "
			+ "When Rydia or a Category IV Character enters your field, place 2 Growth Counters on Rydia.[[br]]   "
			+ "《0》: Cast 1 Summon of cost equal to or less than the number of Growth Counters placed on Rydia "
			+ "from your hand without paying the cost. You can only use this ability once per turn.";

	private static CardData makeRydia() {
		return new CardData(null, "Rydia", "Earth", 2, 5000, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				CardData.parseActionAbilities(RYDIA_TEXT), CardData.parseAutoAbilities(RYDIA_TEXT),
				CardData.parseFieldAbilities(RYDIA_TEXT, "Forward"),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				"Summoner", "IV", null, RYDIA_TEXT);
	}

	@Test
	void rydiaActionAbilityCostsNothingAndScalesOffGrowthCounters() {
		List<ActionAbility> abilities = CardData.parseActionAbilities(RYDIA_TEXT);
		assertEquals(1, abilities.size(), "only the 《0》 segment is an action ability");
		ActionAbility ab = abilities.get(0);
		assertTrue(ab.cpCost().isEmpty(), "《0》 is a real ability with no CP cost");
		assertFalse(ab.requiresDull(), "no 《Dull》 in the cost");
		assertTrue(ab.oncePerTurn(), "\"only once per turn\" gates re-activation");
		assertFalse(ab.hasXCost(), "the ceiling comes from counters, not from CP paid as X");
		assertEquals("Growth", ab.counterScaleName(),
				"activation must read Growth Counters off Rydia into xValue");
	}

	@Test
	void rydiaCastsASummonCappedAtItsGrowthCounterCount() {
		CardData rydia = makeRydia();
		String effect = rydia.actionAbilities().get(0).effectText();
		assertEquals("CastSummonFromHandFree", ActionResolver.matchedPatternName(effect, rydia));

		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(effect, rydia, 4).accept(ctx);
		verify(ctx).castSummonFromHandFree(4, false, null);

		// With no counters placed the cap is 0, not "any cost" — the absent-cost-clause reading
		// of this pattern would let Rydia cast anything in hand for free on turn one.
		GameContext noCounters = mock(GameContext.class);
		ActionResolver.parse(effect, rydia, 0).accept(noCounters);
		verify(noCounters).castSummonFromHandFree(0, false, null);
	}

	@Test
	void rydiaEnterFieldTriggerPlacesTheCountersHerActionAbilityReads() {
		MainWindow mw = new MainWindow();
		CardData rydia = makeRydia();
		placeP1Forward(mw, rydia);
		assertEquals(2, mw.gameState.getCounters(rydia, "Growth"),
				"Rydia's own entry satisfies the first half of the trigger's subject");

		placeP1Forward(mw, makeCategoryForward("Edge", "Earth", "IV"));
		assertEquals(4, mw.gameState.getCounters(rydia, "Growth"),
				"any Category IV Character entering adds 2 more");

		placeP1Forward(mw, makeCategoryForward("Vaan", "Wind", "XII"));
		assertEquals(4, mw.gameState.getCounters(rydia, "Growth"),
				"a Character of another Category does not feed Growth Counters");

		assertEquals("Growth", rydia.actionAbilities().get(0).counterScaleName(),
				"the action ability reads the counter name this trigger writes");
	}

	// =========================================================================================
	// Mog (XIII-2) (5-153S): "《Dull》: Choose 1 Card Name Serah or Card Name Noel. It gains
	// First Strike or Brave until the end of the turn."
	//
	// The only card in the corpus that offers a *choice* of trait rather than granting a fixed
	// set, so the followup has to be read as a disjunction — "First Strike and Brave" would be
	// two grants, "First Strike or Brave" is one grant the player picks. The pick happens after
	// the target is chosen, which is the printed order and also the useful one: which trait helps
	// depends on which Character got picked.
	// =========================================================================================

	private static final String MOG_XIII2_TEXT =
			"《Dull》: Choose 1 Card Name Serah or Card Name Noel. "
			+ "It gains First Strike or Brave until the end of the turn.";

	private static CardData makeMogXiii2() {
		return new CardData(null, "Mog (XIII-2)", "Ice", 2, 0, "Backup", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				CardData.parseActionAbilities(MOG_XIII2_TEXT), List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				"Moogle", "XIII", null, MOG_XIII2_TEXT);
	}

	/** Stubs the target picker to return {@code chosen} and the option prompt to answer {@code pick}. */
	private static GameContext mogContext(ForwardTarget chosen, String pick) {
		GameContext ctx = mock(GameContext.class);
		// Unstubbed this returns an empty list, which selectTargets treats as a real preloaded
		// selection and the whole effect silently no-ops.
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
			.thenReturn(new ArrayList<>(List.of(chosen)));
		when(ctx.selectOption(any(), any())).thenReturn(pick);
		return ctx;
	}

	@Test
	void mogTraitChoiceAbilityIsFullyDescribed() {
		CardData mog = makeMogXiii2();
		List<ActionAbility> abilities = mog.actionAbilities();
		assertEquals(1, abilities.size());
		assertTrue(abilities.get(0).requiresDull(), "《Dull》 is the whole cost");

		String effect = abilities.get(0).effectText();
		// The description chain used to report "ChooseCharacter / ?" — the choice followup
		// resolved but had no name, so the ability read as only partially understood.
		assertEquals("ChooseCharacter / KeywordGrantChoice",
				ActionResolver.fullDescription(effect, mog));
	}

	@Test
	void mogOffersOnlySerahAndNoelThenAsksWhichTrait() {
		CardData mog = makeMogXiii2();
		ForwardTarget serah = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mogContext(serah, "Brave");

		ActionResolver.parse(mog.actionAbilities().get(0).effectText(), mog).accept(ctx);

		// Eligibility is a name filter over both printed names, not "any Forward".
		verify(ctx).selectCharacters(eq(1), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), eq("Serah|Noel"), any(), any(), anyBoolean(), any(), anyBoolean());

		ArgumentCaptor<String[]> choices = ArgumentCaptor.forClass(String[].class);
		verify(ctx).selectOption(any(), choices.capture());
		assertArrayEquals(new String[]{"First Strike", "Brave"}, choices.getValue(),
				"both traits are offered, each as its own option");

		// The target is settled before the trait is asked for — the printed order.
		InOrder order = inOrder(ctx);
		order.verify(ctx).selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
		order.verify(ctx).selectOption(any(), any());
		order.verify(ctx).boostTarget(eq(serah), eq(0), any());
	}

	@Test
	void mogGrantsWhicheverTraitWasPicked() {
		CardData mog = makeMogXiii2();
		String effect = mog.actionAbilities().get(0).effectText();
		ForwardTarget noel = new ForwardTarget(true, 1, ForwardTarget.CardZone.FORWARD);

		GameContext brave = mogContext(noel, "Brave");
		ActionResolver.parse(effect, mog).accept(brave);
		verify(brave).boostTarget(noel, 0, EnumSet.of(CardData.Trait.BRAVE));

		GameContext firstStrike = mogContext(noel, "First Strike");
		ActionResolver.parse(effect, mog).accept(firstStrike);
		verify(firstStrike).boostTarget(noel, 0, EnumSet.of(CardData.Trait.FIRST_STRIKE));

		// Dismissing the picker still resolves the grant — the ability is already on the stack,
		// and the first printed trait is the standing default.
		GameContext dismissed = mogContext(noel, null);
		ActionResolver.parse(effect, mog).accept(dismissed);
		verify(dismissed).boostTarget(noel, 0, EnumSet.of(CardData.Trait.FIRST_STRIKE));
	}

	/** A bare hand card carrying only the two fields the reveal-hand filters read. */
	private static CardData handCard(String type, int cost) {
		return new CardData(null, "X", "Fire", cost, 7000, type, false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	private static CardData cardOfType(String type) { return handCard(type, 3); }

	private static CardData cardOfCost(int cost)    { return handCard("Forward", cost); }
}
