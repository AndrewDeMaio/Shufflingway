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
import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import shufflingway.graphics.CardAnimation;
import org.mockito.InOrder;

/**
 * Consolidated behavioral tests for one-off card-specific action-ability logic — each section
 * below targets a single card or narrow bug fix, exercised against a mocked or minimally
 * constructed {@link GameContext}/{@link CardData} rather than just asserting that the text
 * parses. Kept in one file/class so the whole set runs together as a single suite.
 *
 * <p><b>Finding your way around.</b> This file is ~10,000 lines and must never be read whole —
 * that is a ~150k-token read, and even a stray 2,000-line window is ~30k. Every one of its 113
 * sections opens with a banner comment in one fixed shape, so a single search prints the table
 * of contents and the line to jump to:
 *
 * <pre>    Grep: pattern "^[ \t]+// ={10,}\s*$", -A 1, this file</pre>
 *
 * then {@code Read} with an {@code offset} around the hit. Two details of that pattern are load
 * bearing. The trailing {@code \s} class catches the carriage return — these sources are CRLF,
 * so a bare {@code $} matches nothing at all. And the leading class is spelled {@code [ \t]+}
 * rather than the obvious alternative, which would put a star immediately before a slash and so
 * end this comment early. Every section is delimited top and bottom by the same rule, so the
 * {@code -A 1} line separates the two — an opening banner is followed by its title, a closing
 * one by a blank line.
 *
 * <p>Adding a section means opening it with a banner in that same shape. Three styles of divider
 * were in use here, and the two that did not match this one left a 1,300-line stretch invisible
 * to the search, and so unfindable without reading the region.
 *
 * <p><b>What lives here.</b> Sections run in the order they were written — roughly the order
 * cards were wired up — not grouped by theme, so the search above is the index rather than any
 * list kept here (a list with line numbers in it would be stale by the next commit). Each
 * section is one of three kinds, and its opening banner says which:
 *
 * <ul>
 *   <li><b>Effect wiring</b> (51 sections) — {@code ActionResolver.parse} against a
 *       {@code mock(GameContext.class)}, asserting the right primitive is called with the right
 *       arguments. Stub {@code consumePreloadedTargets()} or choose-target effects silently
 *       no-op.</li>
 *   <li><b>Board behaviour</b> (56 sections) — a real {@code new MainWindow()}: field state,
 *       combat, damage and break rules, control transfer, the Stack.</li>
 *   <li><b>Parsing</b> (6 sections) — parse outcome, {@code matchedPatternName},
 *       {@code fullDescription} and the {@code CardData.parse*Abilities} splitters, with no
 *       execution at all.</li>
 * </ul>
 *
 * <p><b>Shared factories.</b> Most helpers are local to their section and sit directly above it.
 * These are the ones used file-wide — look here before writing another:
 *
 * <ul>
 *   <li>{@code makeForward}, and the {@code makeForwardWith…} variants for traits, raw text,
 *       Warp and field abilities</li>
 *   <li>{@code makeSummon}, {@code makeJobCard}, {@code makeCategoryForward},
 *       {@code makeTraitCard}, {@code makeFieldAbilityCard} / {@code makeFieldAbilityForward}</li>
 *   <li>{@code makeAutoAbilityForward} — two overloads: {@code (name, text)} and
 *       {@code (name, element, power, text)}</li>
 *   <li>{@code placeP1Forward} / {@code placeDamagedP1Forward} — seat a card on the board with
 *       its owner recorded, as a real game would</li>
 *   <li>{@code fwd(isP1, idx)} for a {@link ForwardTarget}; {@code summonChoosing} and
 *       {@code abilityChoosing} for a {@link StackEntry} that has already chosen</li>
 * </ul>
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

    // Luneth 16-019R, second Attack-Phase option: "Deal it 1000 damage for each Job Warrior of
    // Light or Fire Character you control." Two source halves on different axes — a job and an
    // element+type. The plain Job branch of FOLLOWUP_DAMAGE_FOR_EACH used to swallow the whole
    // phrase as one job name ("Warrior of Light or Fire Character"), which no card ever has, so
    // the ability always dealt 0. The union branch has to be checked ahead of it.
    private static final String LUNETH_DAMAGE_PER_WOL_OR_FIRE =
            "Choose 1 Forward. Deal it 1000 damage for each Job Warrior of Light or Fire Character you control.";

    /**
     * Stubs the three counts the union source is built from: the job half over all card types,
     * the element half over the types its noun spans, and their overlap.
     */
    private static ForwardTarget stubChooseWithJobOrElementCounts(
            GameContext ctx, String job, String element, int jobCount, int elemCount, int bothCount) {
        ForwardTarget t = stubChooseOneTarget(ctx);
        when(ctx.countSelfFieldCards(true, true, true, job, null)).thenReturn(jobCount);
        when(ctx.countSelfFieldCards(true, true, true, null, null, null, element, -1)).thenReturn(elemCount);
        when(ctx.countSelfFieldCards(true, true, true, job, null, null, element, -1)).thenReturn(bothCount);
        return t;
    }

    // A Fire Warrior of Light satisfies both halves, and Luneth's own board is full of them —
    // summing the two counts would double every one of them.
    @Test
    void damageForEachJobOrElementCountsOverlapOnce() {
        Consumer<GameContext> fn = ActionResolver.parse(LUNETH_DAMAGE_PER_WOL_OR_FIRE, null);
        assertNotNull(fn, "\"for each Job X or [Element] Type you control\" should parse");
        GameContext ctx = mock(GameContext.class);
        // 3 Warriors of Light, 4 Fire Characters, 2 cards that are both → 5 distinct cards.
        ForwardTarget t = stubChooseWithJobOrElementCounts(ctx, "Warrior of Light", "fire", 3, 4, 2);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 5000);
    }

    // The job half is not type-restricted, so a non-Fire Warrior of Light Backup still counts
    // even though the element half only reaches Characters of that Element.
    @Test
    void damageForEachJobOrElementCountsEitherHalfAlone() {
        Consumer<GameContext> fn = ActionResolver.parse(LUNETH_DAMAGE_PER_WOL_OR_FIRE, null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseWithJobOrElementCounts(ctx, "Warrior of Light", "fire", 2, 0, 0);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 2000);
    }

    // Ace 25-005H (Jackpot Shot) prints the same source with "and/or" instead of "or".
    @Test
    void damageForEachJobAndOrElementUsesTheSameUnion() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 2000 damage for each Job Class Zero Cadet and/or Fire Character you control.",
                null);
        assertNotNull(fn, "the \"and/or\" spelling should reach the same union source");
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseWithJobOrElementCounts(ctx, "Class Zero Cadet", "fire", 2, 3, 1);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 8000);
    }

    // Monk 11-079C and Gekkou 11-006C: "for each Job Ninja or Card Name Ninja you control" — the
    // union's second half is a card name rather than an element. The generic vanilla cards named
    // after a job all carry the Job "Standard Unit", so in printed card data the two halves are
    // disjoint; they can still overlap in play, because a job-granting effect can put the Job
    // Ninja on a card already named Ninja.
    private static ForwardTarget stubChooseWithJobOrNameCounts(
            GameContext ctx, String job, String name, int jobCount, int nameCount, int bothCount) {
        ForwardTarget t = stubChooseOneTarget(ctx);
        when(ctx.countSelfFieldCards(true, true, true, job, null)).thenReturn(jobCount);
        when(ctx.countSelfFieldCards(true, true, true, null, name)).thenReturn(nameCount);
        when(ctx.countSelfFieldCards(true, true, true, job, name)).thenReturn(bothCount);
        return t;
    }

    @Test
    void damageForEachJobOrCardNameAddsTheTwoDisjointHalves() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 2000 damage for each Job Ninja or Card Name Ninja you control.", null);
        assertNotNull(fn, "\"for each Job X or Card Name Y you control\" should parse");
        GameContext ctx = mock(GameContext.class);
        // 2 Forwards with the Job Ninja plus 3 vanilla cards named Ninja, no overlap → 5.
        ForwardTarget t = stubChooseWithJobOrNameCounts(ctx, "Ninja", "Ninja", 2, 3, 0);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 10000);
    }

    @Test
    void damageForEachJobOrCardNameCountsOverlapOnce() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 3000 damage for each Job Monk or Card Name Monk you control.", null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        // A card named Monk that has also been granted the Job Monk is in both halves → 3, not 4.
        ForwardTarget t = stubChooseWithJobOrNameCounts(ctx, "Monk", "Monk", 2, 2, 1);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 9000);
    }

    // Freya 26-095R prints both zones: her attack trigger counts the field, Dragon's Crest counts
    // the Break Zone. The Break Zone union must reach the zone counts and leave the field alone.
    @Test
    void damageForEachJobOrCardNameInBreakZoneUsesTheZoneCounts() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 2000 damage for each Job Dragoon and/or Card Name Dragoon in your Break Zone.",
                null);
        assertNotNull(fn, "the Break Zone union should parse");
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseOneTarget(ctx);
        when(ctx.countSelfBreakZoneCards(null, "Dragoon")).thenReturn(2);
        when(ctx.countSelfBreakZoneCards("Dragoon", null)).thenReturn(4);
        when(ctx.countSelfBreakZoneCards("Dragoon", "Dragoon")).thenReturn(1);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 10000);
        verify(ctx, never()).countSelfFieldCards(anyBoolean(), anyBoolean(), anyBoolean(), any(), any());
    }

    // Freya's other half, over the field, on the same "and/or" spelling.
    @Test
    void damageForEachJobAndOrCardNameOnTheFieldStaysOnTheField() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 2000 damage for each Job Dragoon and/or Card Name Dragoon you control.", null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseWithJobOrNameCounts(ctx, "Dragoon", "Dragoon", 1, 2, 0);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 6000);
        verify(ctx, never()).countSelfBreakZoneCards(any(), any());
    }

    // The plain "Card Name X in your Break Zone" source shares the zone phrasing with the new
    // Break Zone union, so it must still reach the name-only count.
    @Test
    void damageForEachPlainBreakZoneNameIsNotClaimedByTheUnionBranch() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 1000 damage for each Card Name Dragoon in your Break Zone.", null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseOneTarget(ctx);
        when(ctx.countSelfBreakZoneCards("Dragoon", null)).thenReturn(3);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 3000);
        verify(ctx, never()).countSelfBreakZoneCards(any(), eq("Dragoon"));
    }

    // Fran 10-060L: "Deal it 1000 damage for each Job Sky Pirate other than Fran you control."
    // Fran has the Job Sky Pirate herself, so the exclusion is what stops her from counting
    // herself — and it drops every card named Fran, not just the ability's source.
    private static final String FRAN_DAMAGE_PER_OTHER_SKY_PIRATE =
            "Choose 1 Forward. Deal it 1000 damage for each Job Sky Pirate other than Fran you control.";

    @Test
    void damageForEachJobOtherThanNameSubtractsTheExcludedCards() {
        Consumer<GameContext> fn = ActionResolver.parse(FRAN_DAMAGE_PER_OTHER_SKY_PIRATE, null);
        assertNotNull(fn, "\"for each Job X other than Name you control\" should parse");
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseOneTarget(ctx);
        // 3 Sky Pirates on the field, one of which is Fran → 2 count.
        when(ctx.countSelfFieldCards(true, true, true, "Sky Pirate", null)).thenReturn(3);
        when(ctx.countSelfFieldCards(true, true, true, "Sky Pirate", "Fran")).thenReturn(1);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 2000);
    }

    // With Fran as the only Sky Pirate the ability does nothing — the case the old parse got
    // wrong in the other direction, matching a job named "Sky Pirate other than Fran".
    @Test
    void damageForEachJobOtherThanNameIsZeroWhenOnlyTheExcludedCardQualifies() {
        Consumer<GameContext> fn = ActionResolver.parse(FRAN_DAMAGE_PER_OTHER_SKY_PIRATE, null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseOneTarget(ctx);
        when(ctx.countSelfFieldCards(true, true, true, "Sky Pirate", null)).thenReturn(1);
        when(ctx.countSelfFieldCards(true, true, true, "Sky Pirate", "Fran")).thenReturn(1);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 0);
    }

    // Joshua 26-009L: "for each Job Eikon in your Break Zone and/or Job Eikon you own removed
    // from the game" — one job counted across two zones. The zones are disjoint, so this one sums
    // outright; countSelfBreakZoneAndRfgCards already does that.
    @Test
    void damageForEachJobAcrossBreakZoneAndRemovedFromGameSumsBothZones() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 2000 damage for each Job Eikon in your Break Zone and/or Job Eikon you own removed from the game.",
                null);
        assertNotNull(fn, "the Break-Zone-plus-removed-from-game source should parse");
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseOneTarget(ctx);
        when(ctx.countSelfBreakZoneAndRfgCards(null, "Eikon")).thenReturn(3);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 6000);
    }

    // The backreference ties both halves to one job, because the count behind them takes a single
    // job filter. A card naming two different jobs must not quietly be counted as only the first.
    @Test
    void damageForEachAcrossZonesDoesNotClaimTwoDifferentJobs() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 2000 damage for each Job Eikon in your Break Zone and/or Job Dominant you own removed from the game.",
                null);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseOneTarget(ctx);
        if (fn != null) fn.accept(ctx);
        verify(ctx, never()).countSelfBreakZoneAndRfgCards(any(), eq("Eikon"));
        verify(ctx, never()).damageTarget(t, 6000);
    }

    // The union branch sits ahead of the plain Job branch, so the plain form must still reach the
    // single job count and must not consult the element counts.
    @Test
    void damageForEachPlainJobStillUsesTheSingleJobCount() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward. Deal it 1000 damage for each Job Warrior of Light you control.", null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = stubChooseOneTarget(ctx);
        when(ctx.countSelfFieldCards(true, true, true, "Warrior of Light", null)).thenReturn(4);
        fn.accept(ctx);
        verify(ctx).damageTarget(t, 4000);
        verify(ctx, never()).countSelfFieldCards(anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyInt());
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
                CardData.parseTraits(text, name), 0, List.of(), null, List.of(),
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
    // Benedikta 29-053L: "When Benedikta enters the field, choose up to 2 Wind Backups.
    // Activate them."
    //
    // No parser work: ChooseCharacter already reaches the Backup zone with an element filter, and
    // "Activate them." is an existing followup. What this pins is that the pieces compose into the
    // right selection — the card is Wind, and an element filter dropped on the floor would let it
    // activate anything. The corpus text reads "choose up 2", missing the printed "to"; that is a
    // data defect fixed in the ETL, so the ability is tested against what the card actually says.
    // =========================================================================================

    @Test
    void benediktaActivatesUpToTwoWindBackups() {
        GameContext ctx = mock(GameContext.class);
        // Without this the mock hands back an empty preloaded list and the selection no-ops.
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        ForwardTarget one = new ForwardTarget(true, 0, ForwardTarget.CardZone.BACKUP);
        ForwardTarget two = new ForwardTarget(true, 3, ForwardTarget.CardZone.BACKUP);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(new ArrayList<>(List.of(one, two)));

        List<AutoAbility> autos = CardData.parseAutoAbilities(
                "When Benedikta enters the field, choose up to 2 Wind Backups. Activate them.");
        assertEquals(1, autos.size());
        assertEquals("enters the field", autos.get(0).trigger());

        Consumer<GameContext> fn = ActionResolver.parse(autos.get(0).effectText(),
                makeForward("Benedikta", "Wind", 4, 8000));
        assertNotNull(fn, "the effect has to resolve, not just split off the trigger");
        fn.accept(ctx);

        verify(ctx).selectCharacters(eq(2), eq(true), anyBoolean(), anyBoolean(), any(), eq("Wind"),
                anyInt(), any(), anyInt(), any(),
                eq(false), eq(true), eq(false),          // Backups only — not Forwards, not Monsters
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
        verify(ctx).activateTarget(one);
        verify(ctx).activateTarget(two);
    }

    // =========================================================================================
    // "You can play 2 or more Card Name X onto the field" is a permission its own controller
    // holds, so the uniqueness rule has to read it from that player's field.
    //
    // isMultiNameExceptionActive scanned P1's zones whoever was asking, and the rule process hid
    // that by only consulting it when the incoming card was P1's. The result was that P2 lost a
    // second copy to a rule a card on their own field says does not apply to them — and, had the
    // guard not been there, P1's granter would have licensed P2's duplicate instead.
    // =========================================================================================

    private static final String MULTI_NAME_GRANT =
            "You can play 2 or more Card Name Cid onto the field.";

    /**
     * Seats {@code card} on P2's Forward row with its owner recorded. The owner matters here in a
     * way it does not for most placements: the uniqueness rule sends the loser to its owner's
     * Break Zone, and that lookup is by identity.
     */
    private static void placeP2Forward(MainWindow mw, CardData card) {
        mw.gameState.getIdentity().put(card, false);
        mw.placeP2CardInForwardZone(card);
    }

    @Test
    void aMultiNameGrantOnP2sFieldProtectsP2sSecondCopy() {
        MainWindow mw = new MainWindow();
        mw.placeP2CardInFirstBackupSlot(
                makeFieldAbilityCard("Cid Grantor", "Fire", "Backup", MULTI_NAME_GRANT));

        placeP2Forward(mw, makeForward("Cid", "Fire", 3, 7000));
        placeP2Forward(mw, makeForward("Cid", "Fire", 3, 7000));

        assertEquals(2, mw.p2ForwardCards.size(),
                "the grant is on P2's own field, so both copies stay");
    }

    @Test
    void withoutTheGrantP2sSecondCopyStillGoesToTheBreakZone() {
        MainWindow mw = new MainWindow();
        placeP2Forward(mw, makeForward("Cid", "Fire", 3, 7000));
        placeP2Forward(mw, makeForward("Cid", "Fire", 3, 7000));

        assertEquals(1, mw.p2ForwardCards.size(),
                "with no grant anywhere the uniqueness rule applies to P2 as it always did");
    }

    @Test
    void aMultiNameGrantOnP1sFieldDoesNotLicenseP2sDuplicate() {
        MainWindow mw = new MainWindow();
        mw.placeCardInFirstBackupSlot(
                makeFieldAbilityCard("Cid Grantor", "Fire", "Backup", MULTI_NAME_GRANT));

        placeP2Forward(mw, makeForward("Cid", "Fire", 3, 7000));
        placeP2Forward(mw, makeForward("Cid", "Fire", 3, 7000));

        assertEquals(1, mw.p2ForwardCards.size(),
                "the permission belongs to P1 — reading it for P2 is the same seat mix-up in reverse");
    }

    // =========================================================================================
    // The uniqueness rule gate on priming is per player, not per board.
    //
    // Priming is blocked when it would immediately break the card it just fetched. That check
    // used to scan both fields, so an opponent controlling the target locked you out of priming
    // your own copy — a board both players are entitled to. The rule process it mirrors,
    // sendToBreakZoneByUniquenessRule, walks one side's zones only.
    // =========================================================================================

    @Test
    void anOpponentsCopyDoesNotBlockPrimingYourOwn() {
        MainWindow mw = new MainWindow();
        mw.placeP2CardInForwardZone(makeForward("Odin", "Ice", 5, 9000));

        assertFalse(mw.priming.primingTargetOnField("Odin", true),
                "both players may control an Odin, so the opponent's must not gate P1's prime");
        assertTrue(mw.priming.primingTargetOnField("Odin", false),
                "the copy is P2's own, so it does gate P2 priming into that name");
    }

    @Test
    void yourOwnCopyStillBlocksPriming() {
        MainWindow mw = new MainWindow();
        placeP1Forward(mw, makeForward("Odin", "Ice", 5, 9000));

        assertTrue(mw.priming.primingTargetOnField("Odin", true),
                "priming into a name you already control would break it on arrival");
        assertFalse(mw.priming.primingTargetOnField("Odin", false),
                "and it is no constraint at all on the opponent");
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

    // Cid (WOFF) 4-034R prints "Cid (WOFF) cannot be broken." on a Backup. breakTarget checks the
    // printed trait before it splits by zone, so the protection reaches a Backup — the parenthesised
    // card name is the part worth pinning, since it is what the sentence parser has to carry.
    @Test
    void unconditionalCannotBeBrokenAppliesToABackup() {
        String text = "Cid (WOFF) cannot be broken.[[br]] 《Dull》, put Cid (WOFF) into the Break Zone: "
                + "Choose 1 Monster of cost 2 or less in your Break Zone. Play it onto the field. "
                + "You can only use this ability during your turn.";
        CardData cid = makeTraitCard("Cid (WOFF)", "Water", "Backup", text);
        assertTrue(cid.hasTrait(CardData.Trait.CANNOT_BE_BROKEN),
                "the printed trait is what protects a Backup — breakTarget reads it in every zone");
        assertTrue(CardData.parseSelfCannotBeBroken("Cid (WOFF) cannot be broken.", "Cid (WOFF)"));
    }

    // The sentence parser is anchored at both ends and name-checked, so the qualified and
    // conditional forms must not read as unconditional protection. Each of these is a real card.
    @Test
    void unconditionalCannotBeBrokenRejectsTheQualifiedForms() {
        // Trailing qualifier — Galuf 7-067L is protected only on his controller's turn.
        assertFalse(CardData.parseSelfCannotBeBroken(
                "Galuf cannot be broken during your turn.", "Galuf"));
        // Leading condition — Galuf 12-056H, only during an Attack Phase.
        assertFalse(CardData.parseSelfCannotBeBroken(
                "During each Attack Phase, Galuf cannot be broken.", "Galuf"));
        // Leading condition — Llednar 13-108L, only while a Fortune Counter is on him.
        assertFalse(CardData.parseSelfCannotBeBroken(
                "If a Fortune Counter is placed on Llednar, Llednar cannot be broken.", "Llednar"));
        // The narrower non-damage shield is a different trait and has its own parser.
        assertFalse(CardData.parseSelfCannotBeBroken(
                "Vincent cannot be broken by opposing Summons or abilities that don't deal damage.", "Vincent"));
        // A sentence about somebody else's protection is not a self-grant.
        assertFalse(CardData.parseSelfCannotBeBroken(
                "The Backups you control cannot be broken.", "Auron"));
    }

    // =========================================================================================
    // Conditional "cannot be broken" — Galuf 7-067L, Galuf 12-056H, Llednar 13-108L
    //
    // The trait used to come from a find() over the whole card text, so a condition in front of
    // the sentence ("During each Attack Phase, …") or behind it ("… during your turn") was simply
    // not seen and all three were permanently unbreakable. They are now granted per query by
    // FieldGrantCalculator, which can answer differently as the turn and phase move.
    // =========================================================================================

    /** Puts {@code card} on P1's field as its owner and returns the MainWindow driving it. */
    private static MainWindow placeOwnP1Forward(MainWindow mw, CardData card) {
        mw.gameState.getIdentity().put(card, true);
        mw.placeCardInForwardZone(card);
        return mw;
    }

    @Test
    void cannotBeBrokenDuringYourTurnHoldsOnlyOnItsControllersTurn() {
        MainWindow mw = new MainWindow();
        CardData galuf = makeTraitCard("Galuf", "Earth", "Forward", "Galuf cannot be broken during your turn.");
        assertFalse(galuf.hasTrait(CardData.Trait.CANNOT_BE_BROKEN),
                "the qualifier means this is not an unconditional printed trait");
        placeOwnP1Forward(mw, galuf);

        advanceTo(mw, GameState.Player.P1, GameState.GamePhase.MAIN_1);
        assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN), "protected on his own turn");

        advanceTo(mw, GameState.Player.P2, GameState.GamePhase.MAIN_1);
        assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN), "exposed on the opponent's turn");
    }

    @Test
    void cannotBeBrokenDuringEachAttackPhaseHoldsInBothPlayersAttackPhases() {
        MainWindow mw = new MainWindow();
        CardData galuf = makeTraitCard("Galuf", "Earth", "Forward",
                "During each Attack Phase, Galuf cannot be broken.");
        assertFalse(galuf.hasTrait(CardData.Trait.CANNOT_BE_BROKEN));
        placeOwnP1Forward(mw, galuf);

        advanceTo(mw, GameState.Player.P1, GameState.GamePhase.MAIN_1);
        assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN), "not in Main Phase 1");

        advanceTo(mw, GameState.Player.P1, GameState.GamePhase.ATTACK);
        assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN), "his own Attack Phase");

        // "each Attack Phase" is not "your Attack Phase" — the opponent's counts too.
        advanceTo(mw, GameState.Player.P2, GameState.GamePhase.ATTACK);
        assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN), "the opponent's Attack Phase too");
    }

    @Test
    void cannotBeBrokenWithCounterFollowsTheCounterOnAndOff() {
        MainWindow mw = new MainWindow();
        CardData llednar = makeTraitCard("Llednar", "Fire", "Forward",
                "If a Fortune Counter is placed on Llednar, Llednar cannot be broken.");
        assertFalse(llednar.hasTrait(CardData.Trait.CANNOT_BE_BROKEN));
        placeOwnP1Forward(mw, llednar);

        assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN), "no counter yet");

        mw.gameState.placeCounters(llednar, "Fortune", 1);
        assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN), "counter on → protected");

        // Llednar's own "Remove all Fortune Counters" ability is what turns it back off.
        mw.gameState.removeCounters(llednar, "Fortune", 1);
        assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN), "counter gone → exposed");

        // A different counter must not stand in for the named one.
        mw.gameState.placeCounters(llednar, "Petrification", 1);
        assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN), "wrong counter type");
    }

    // The four cards whose text only ever grants the shield to somebody else used to pick it up
    // themselves, because the whole-text scan could not tell a grant from a self-statement.
    @Test
    void grantingCannotBeBrokenToOthersDoesNotShieldTheGranter() {
        CardData regis = makeTraitCard("Regis", "Earth", "Forward",
                "[[s]]Royal Sigil[[/]] 《S》《Earth》《Lightning》: All the Forwards you control gain "
                + "\"This Forward cannot be broken\" until the end of the turn.");
        assertFalse(regis.hasTrait(CardData.Trait.CANNOT_BE_BROKEN), "Regis 12-122L grants it, he does not have it");

        CardData wol = makeTraitCard("Warrior of Light", "Light", "Forward",
                "Remove Warrior of Light from the game: All the Forwards you control gain "
                + "\"This Forward cannot be broken.\" until the end of the turn. "
                + "You can only use this ability during your opponent's turn.");
        assertFalse(wol.hasTrait(CardData.Trait.CANNOT_BE_BROKEN), "Warrior of Light 16-127L removes itself to grant it");

        CardData antlion = makeTraitCard("Antlion", "Earth", "Monster",
                "《Earth》, discard Antlion, remove 1 Backup from the game: Choose 1 Forward you control. "
                + "Dull it. Until the end of the turn, it gains +2000 power and \"This Forward cannot be broken.\" "
                + "You can only use this ability if Antlion is in your hand.");
        assertFalse(antlion.hasTrait(CardData.Trait.CANNOT_BE_BROKEN),
                "the quote does not follow 'gains' directly, which is what the old strip relied on");
    }

    // Qun'mi already had a correct opponent-hand-size conditional in FieldGrantCalculator; the
    // permanent trait sat on top of it and made the condition unreachable.
    @Test
    void opponentHandSizeCannotBeBrokenIsNoLongerShadowedByAPermanentTrait() {
        CardData qunmi = makeTraitCard("White Tiger l'Cie Qun'mi", "Ice", "Forward",
                "If your opponent has 1 card or less in their hand, White Tiger l'Cie Qun'mi cannot be broken.");
        assertFalse(qunmi.hasTrait(CardData.Trait.CANNOT_BE_BROKEN), "the condition has to be evaluated, not assumed");
        assertEquals(1, CardData.parseIfOpponentHandSizeCannotBeBrokenThreshold(
                "If your opponent has 1 card or less in their hand, White Tiger l'Cie Qun'mi cannot be broken.",
                "White Tiger l'Cie Qun'mi"));
    }

    // =========================================================================================
    // The non-damage break shield had the same whole-text defect as its unconditional sibling:
    // any card whose text merely mentioned it picked it up, ungated. It is now the printed trait
    // only for the bare self-statement; every other printing routes through FieldGrantCalculator.
    // =========================================================================================

    @Test
    void nonDamageBreakShieldStaysPrintedForTheBareSelfStatement() {
        CardData vincent = makeTraitCard("Vincent", "Earth", "Forward",
                "Vincent cannot be broken by opposing Summons or abilities that don't deal damage.");
        assertTrue(vincent.hasTrait(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG));

        // Ceodore 25-044C drops the "Summons or", which the shared tail still accepts.
        CardData ceodore = makeTraitCard("Ceodore", "Wind", "Forward",
                "Ceodore cannot be broken by opposing abilities that don't deal damage.");
        assertTrue(ceodore.hasTrait(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG));
    }

    @Test
    void nonDamageBreakShieldIsNotPrintedForGrantsAndGates() {
        // Doga 5-087R hands it out with an action ability; he never has it himself.
        CardData doga = makeTraitCard("Doga", "Earth", "Backup",
                "《Earth》《Dull》, put Doga into the Break Zone: Choose 1 Character you control. "
                + "During this turn, it cannot be broken by opposing Summons or abilities that don't deal damage.");
        assertFalse(doga.hasTrait(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG));

        // Wol 14-059R is gated behind Damage 3.
        CardData wol = makeTraitCard("Wol", "Earth", "Forward",
                "Damage 3 -- Wol gains +1000 power, Brave and \"Wol cannot be broken by opposing "
                + "Summons or abilities that don't deal damage.\"");
        assertFalse(wol.hasTrait(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG),
                "the Damage 3 gate has to be evaluated, not assumed");

        // Gilgamesh (XI) 10-111H is gated on controlling 5 or more Water Characters.
        CardData gilg = makeTraitCard("Gilgamesh (XI)", "Water", "Forward",
                "If you control 5 or more Water Characters, Gilgamesh (XI) cannot be broken by "
                + "opposing Summons or abilities that don't deal damage.");
        assertFalse(gilg.hasTrait(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG));
        assertNotNull(CardData.parseIfControlNonDmgBreakShield(
                "If you control 5 or more Water Characters, Gilgamesh (XI) cannot be broken by "
                + "opposing Summons or abilities that don't deal damage.", "Gilgamesh (XI)"));
    }

    // Wol's shield now arrives through the damage-gated self-grant path. The pattern had required
    // the quote to follow "gains" directly, so "gains +1000 power, Brave and \"…\"" slipped past it
    // and only the ungated whole-text scan was holding the card up.
    @Test
    void nonDamageBreakShieldFollowsTheDamageGate() {
        MainWindow mw = new MainWindow();
        CardData wol = makeTraitCard("Wol", "Earth", "Forward",
                "Damage 3 -- Wol gains +1000 power, Brave and \"Wol cannot be broken by opposing "
                + "Summons or abilities that don't deal damage.\"");
        placeOwnP1Forward(mw, wol);
        assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG),
                "no damage taken yet");

        for (int i = 0; i < 3; i++)
            mw.gameState.getP1DamageZone().add(makeForward("Damage " + i, "Fire", 1, 1000));
        assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG),
                "Damage 3 reached");
    }

    @Test
    void nonDamageBreakShieldControlConditionTurnsOnAndOff() {
        MainWindow mw = new MainWindow();
        CardData gilg = makeTraitCard("Gilgamesh (XI)", "Water", "Forward",
                "If you control 5 or more Water Characters, Gilgamesh (XI) cannot be broken by "
                + "opposing Summons or abilities that don't deal damage.");
        placeOwnP1Forward(mw, gilg);
        assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG),
                "he is only one Water Character");

        for (int i = 0; i < 4; i++)
            placeOwnP1Forward(mw, makeTraitCard("Water Ally " + i, "Water", "Forward", ""));
        assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG),
                "five Water Characters now");
    }

    // Celestia 13-128L grants to a filtered set she happens to belong to; Rasler 5-166S grants to
    // a set he does not. The old whole-text scan could not tell those apart and shielded both.
    @Test
    void nonDamageBreakShieldGrantCoversTheFilteredSetOnly() {
        MainWindow mw = new MainWindow();
        CardData celestia = makeTraitCard("Celestia", "Water/Ice", "Forward",
                "The Water Characters you control cannot be broken by opposing Summons or "
                + "abilities that don't deal damage.");
        placeOwnP1Forward(mw, celestia);
        placeOwnP1Forward(mw, makeTraitCard("Water Ally", "Water", "Forward", ""));
        placeOwnP1Forward(mw, makeTraitCard("Fire Ally", "Fire", "Forward", ""));

        assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG),
                "Celestia is herself a Water Character");
        assertTrue(mw.effectiveP1HasTrait(1, CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG),
                "the grant reaches other Water Characters, which it never used to");
        assertFalse(mw.effectiveP1HasTrait(2, CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG),
                "a Fire Character is outside the filter");
    }

    @Test
    void nonDamageBreakShieldGrantDoesNotCoverAGranterOutsideItsOwnFilter() {
        MainWindow mw = new MainWindow();
        CardData rasler = makeTraitCard("Rasler", "Water", "Forward",
                "The Card Name Ashe you control cannot be broken by opposing Summons or "
                + "abilities that don't deal damage.");
        placeOwnP1Forward(mw, rasler);
        placeOwnP1Forward(mw, makeTraitCard("Ashe", "Water", "Forward", ""));

        assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG),
                "Rasler is not named Ashe");
        assertTrue(mw.effectiveP1HasTrait(1, CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG),
                "Ashe is");

        // Madam Edel 16-080H is an Adventurer handing the shield to Morze's Soiree Members.
        CardData edel = makeTraitCard("Madam Edel", "Earth", "Forward",
                "The Job Morze's Soiree Member you control gain \"If this Character is dealt damage "
                + "by your opponent's Summons or abilities, the damage becomes 0 instead.\" and "
                + "\"This Character cannot be broken by opposing Summons or abilities that don't deal damage.\"");
        assertFalse(edel.hasTrait(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG));
        CardData.NonDmgBreakShieldGrant g = CardData.parseFieldNonDmgBreakShieldGrant(
                edel.fieldAbilities().get(0).effectText());
        assertNotNull(g, "the quoted-grant wrapper should still parse");
        assertFalse(g.appliesToCard(edel), "an Adventurer is not a Morze's Soiree Member");
    }

    // =========================================================================================
    // Tidus 29-105L — "Damage 1 -- Tidus can attack as many times in the same turn as the points
    // of damage you have received."
    //
    // FIELD_CAN_ATTACK_TWICE deliberately skips this wording: the allowance moves with the damage
    // zone during the turn, so a static int on CardData cannot carry it. It is read per query
    // instead. (Tidus 1-163L's Blitz Ace is the resolved action-ability cousin, not this.)
    // =========================================================================================

    private static final String TIDUS_29_105L_ATTACKS =
            "Tidus can attack as many times in the same turn as the points of damage you have received.";

    @Test
    void attacksPerOwnDamageIsNotAStaticPrintedPermission() {
        assertEquals(1, CardData.parseMaxAttacksPerTurn(TIDUS_29_105L_ATTACKS, "Tidus"),
                "the varying count must not be frozen into maxAttacksPerTurn");
        assertTrue(CardData.parseAttacksPerOwnDamage(TIDUS_29_105L_ATTACKS, "Tidus"));
        assertFalse(CardData.parseAttacksPerOwnDamage(TIDUS_29_105L_ATTACKS, "Yuna"),
                "the sentence names its own card");
    }

    @Test
    void attacksPerOwnDamageTracksTheDamageZone() {
        MainWindow mw = new MainWindow();
        CardData tidus = makeTraitCard("Tidus", "Water", "Forward",
                "Damage 1 -- " + TIDUS_29_105L_ATTACKS);
        placeOwnP1Forward(mw, tidus);

        assertEquals(1, mw.maxAttacksPerTurn(tidus), "no damage — the ordinary single attack");

        for (int i = 0; i < 3; i++)
            mw.gameState.getP1DamageZone().add(makeForward("Damage " + i, "Fire", 1, 1000));
        assertEquals(3, mw.maxAttacksPerTurn(tidus), "three damage — three attacks");

        mw.gameState.getP1DamageZone().add(makeForward("Damage 3", "Fire", 1, 1000));
        assertEquals(4, mw.maxAttacksPerTurn(tidus), "the count follows the zone within the turn");
    }

    // The permission belongs to its controller's damage, not to whichever side asks.
    @Test
    void attacksPerOwnDamageReadsTheControllersDamageZone() {
        MainWindow mw = new MainWindow();
        CardData tidus = makeTraitCard("Tidus", "Water", "Forward",
                "Damage 1 -- " + TIDUS_29_105L_ATTACKS);
        mw.gameState.getIdentity().put(tidus, false);
        mw.placeP2CardInForwardZone(tidus);

        for (int i = 0; i < 2; i++)
            mw.gameState.getP1DamageZone().add(makeForward("P1 Damage " + i, "Fire", 1, 1000));
        assertEquals(1, mw.maxAttacksPerTurn(tidus), "P1's damage is not Tidus's");

        for (int i = 0; i < 2; i++)
            mw.gameState.getP2DamageZone().add(makeForward("P2 Damage " + i, "Fire", 1, 1000));
        assertEquals(2, mw.maxAttacksPerTurn(tidus), "his own controller's damage counts");
    }

    // =========================================================================================
    // Cast / play restrictions — Leo 16-126R and the "cannot play … due to Summons or abilities"
    // family (Graham 12-060R and five siblings).
    // =========================================================================================

    private static final String LEO_TEXT =
            "You must control Characters of cost 1, 2, 3, 4, 5 and 6 to cast Leo.[[br]]   "
            + "You cannot play Leo due to Summons or abilities.[[br]]   "
            + "When Leo enters the field, look at the top 5 cards of your deck. Cast 1 card among "
            + "them without paying the cost and return the other cards to the bottom of your deck in any order.";

    @Test
    void mustControlCostsParsesEveryListedCost() {
        CardData leo = makeTraitCard("Leo", "Light", "Forward", LEO_TEXT);
        CastRestriction cr = leo.castRestriction();
        assertNotNull(cr);
        assertEquals(java.util.Set.of(1, 2, 3, 4, 5, 6), cr.mustControlCosts());
    }

    @Test
    void mustControlCostsNeedsOneCharacterPerListedCost() {
        MainWindow mw = new MainWindow();
        CardData leo = makeTraitCard("Leo", "Light", "Forward", LEO_TEXT);

        // Five of the six costs present — one short, so still not castable.
        for (int cost = 1; cost <= 5; cost++)
            placeOwnP1Forward(mw, makeForward("Ally " + cost, "Fire", cost, 5000));
        assertFalse(mw.castRestrictionMet(leo, true),
                "cost 6 is missing, so the requirement is unmet");

        placeOwnP1Forward(mw, makeForward("Ally 6", "Fire", 6, 5000));
        assertTrue(mw.castRestrictionMet(leo, true), "all six costs covered");
    }

    // One Character has one cost, so duplicates of the same cost cannot cover two requirements.
    @Test
    void mustControlCostsIsNotSatisfiedByDuplicatesOfOneCost() {
        MainWindow mw = new MainWindow();
        CardData leo = makeTraitCard("Leo", "Light", "Forward", LEO_TEXT);
        for (int i = 0; i < 6; i++)
            placeOwnP1Forward(mw, makeForward("Ally " + i, "Fire", 1, 5000));
        assertFalse(mw.castRestrictionMet(leo, true),
                "six cost-1 Characters cover only the cost-1 requirement");
    }

    @Test
    void cannotPlayDueToEffectsDistinguishesTheZoneWording() {
        // Leo names no zone — an effect may not play him out of any of them.
        CardData leo = makeTraitCard("Leo", "Light", "Forward", LEO_TEXT);
        assertTrue(leo.playByEffectProhibited(true),  "blocked from hand");
        assertTrue(leo.playByEffectProhibited(false), "and from every other zone");

        // Graham's wording is narrower: only a play out of hand is blocked.
        CardData graham = makeTraitCard("Graham", "Earth", "Backup",
                "You can only pay with CP produced by Earth Backups to play Graham from your hand "
                + "onto the field.[[br]]You cannot play Graham from your hand due to Summons or "
                + "abilities. [[br]] When Graham enters the field, choose 1 Forward opponent "
                + "controls. Deal it 9000 damage. ");
        assertTrue(graham.playByEffectProhibited(true), "blocked from hand");
        assertFalse(graham.playByEffectProhibited(false),
                "an effect may still play him from the Break Zone");

        // An ordinary card is unaffected.
        CardData plain = makeTraitCard("Plain", "Fire", "Forward", "Brave");
        assertFalse(plain.playByEffectProhibited(true));
        assertFalse(plain.playByEffectProhibited(false));
    }

    // Neither sentence is an ability in its own right, so neither should surface as a field ability.
    @Test
    void castAndPlayRestrictionSentencesAreNotFieldAbilities() {
        CardData leo = makeTraitCard("Leo", "Light", "Forward", LEO_TEXT);
        for (FieldAbility fa : leo.fieldAbilities())
            assertFalse(fa.effectText().toLowerCase().contains("you must control")
                     || fa.effectText().toLowerCase().contains("you cannot play"),
                    "restriction sentence leaked into field abilities: " + fa.effectText());
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
                new LookConfig(5, LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM, null, null, true));
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
                new LookConfig(2, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, "Fire", null, true));
    }

    @Test
    void aLookAtStaysPrivateToItsController() {
        GameContext ctx = mock(GameContext.class);
        ActionResolver.parse(
                "Look at the top 2 cards of your deck. Add 1 Water card among them to your hand and "
                + "put the rest into the Break Zone.", null).accept(ctx);
        verify(ctx).lookAtTopDeck(
                new LookConfig(2, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, "Water", null, false));
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

    // =========================================================================================
    // "Add 1 [Category X] card among them to your hand" — the category branch of the
    // add-to-hand/rest-to-break look.
    //
    // The pattern only understood an element filter, so four cards went unparsed (29-061L Vincent,
    // 25-112H Sarah, and the branch inside 28-031L Snow and 24-061L Basch). Worse, the element
    // filter it did parse was never applied: LookConfig carried it and the dialog ignored it, so
    // every one of those cards let you take any revealed card. Both halves are fixed here — the
    // filter now reaches the dialog, the AI, and the no-eligible-card case.
    // =========================================================================================

    private static final String VINCENT_REVEAL =
            "reveal the top 2 cards of your deck. Add 1 Category VII card among them to your hand "
            + "and put the rest of the cards into the Break Zone.";

    @Test
    void vincentFiltersTheAddToHandByCategory() {
        GameContext ctx = mock(GameContext.class);
        Consumer<GameContext> fn = ActionResolver.parse(VINCENT_REVEAL, null);
        assertNotNull(fn);
        fn.accept(ctx);
        verify(ctx).lookAtTopDeck(
                new LookConfig(2, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, null, "VII", true));
    }

    @Test
    void vincentParsesAsOneEntersFieldAbility() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(
                "When Vincent enters the field, " + VINCENT_REVEAL);
        assertEquals(1, autos.size());
        assertEquals("enters the field", autos.get(0).trigger());
        assertNotNull(ActionResolver.parse(autos.get(0).effectText(), null));
    }

    // 25-112H Sarah names a category that is a word rather than a numeral, so the capture must not
    // stop at the first token boundary it finds.
    @Test
    void aWordCategoryIsCapturedWhole() {
        GameContext ctx = mock(GameContext.class);
        ActionResolver.parse(
                "reveal the top 4 cards of your deck. Add 1 Category MOBIUS card among them to "
                + "your hand and put the rest of the cards into the Break Zone.", null).accept(ctx);
        verify(ctx).lookAtTopDeck(
                new LookConfig(4, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, null, "MOBIUS", true));
    }

    // The element branch and the unfiltered form share this pattern, so widening it must leave
    // both alone.
    @Test
    void wideningForCategoriesLeavesTheElementAndUnfilteredFormsAlone() {
        GameContext elem = mock(GameContext.class);
        ActionResolver.parse(
                "reveal the top 2 cards of your deck. Add 1 Earth card among them to your hand and "
                + "put the rest of the cards into the Break Zone.", null).accept(elem);
        verify(elem).lookAtTopDeck(
                new LookConfig(2, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, "Earth", null, true));

        GameContext plain = mock(GameContext.class);
        ActionResolver.parse(
                "Look at the top 3 cards of your deck. Add 1 card among them to your hand and put "
                + "the rest of the cards into the Break Zone.", null).accept(plain);
        verify(plain).lookAtTopDeck(
                new LookConfig(3, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, null, null, false));
    }

    // The filter is what the dialog and the AI both consult, so it has to reject as well as accept.
    @Test
    void theHandFilterAcceptsOnlyMatchingCards() {
        LookConfig byCategory = new LookConfig(
                2, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, null, "VII", true);
        assertTrue(byCategory.eligibleForHand(makeCategoryForward("Cloud", "Fire", "VII")));
        assertFalse(byCategory.eligibleForHand(makeCategoryForward("Vaan", "Wind", "XII")));

        LookConfig byElement = new LookConfig(
                2, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, "Fire", null, true);
        assertTrue(byElement.eligibleForHand(makeCategoryForward("Cloud", "Fire", "VII")));
        assertFalse(byElement.eligibleForHand(makeCategoryForward("Vaan", "Wind", "XII")));

        LookConfig unfiltered = new LookConfig(2, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK);
        assertTrue(unfiltered.eligibleForHand(makeCategoryForward("Vaan", "Wind", "XII")),
                "an unfiltered look must keep accepting everything");
    }

    @Test
    void theFilterLabelNamesWhicheverRestrictionApplies() {
        assertEquals("Category VII", new LookConfig(
                2, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, null, "VII", true).handFilterLabel());
        assertEquals("Fire", new LookConfig(
                2, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, "Fire", null, true).handFilterLabel());
        assertNull(new LookConfig(2, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK).handFilterLabel());
    }

    // =========================================================================================
    // Bartz 26-053L: "reveal the top 2 cards of your deck. Play up to 1 Character of cost 3 or
    // less among them onto the field and add the other cards to your hand."
    //
    // Every other card in this family sends the unplayed cards to the bottom of the deck; Bartz
    // adds them to hand, which is strictly better and so cannot share a destination. The tail also
    // made the whole ability vanish: ADD_NAMED_TO_YOUR_HAND read "add the other cards to your
    // hand" as a card literally named "the other cards", claimed the text, and Bartz did nothing.
    // =========================================================================================

    private static final String BARTZ_EFFECT =
            "reveal the top 2 cards of your deck. Play up to 1 Character of cost 3 or less "
            + "among them onto the field and add the other cards to your hand.";

    @Test
    void bartzRevealsPlaysAndKeepsTheRest() {
        GameContext ctx = mock(GameContext.class);
        Consumer<GameContext> fn = ActionResolver.parse(BARTZ_EFFECT, null);
        assertNotNull(fn, "the reveal-and-play must not be swallowed by the trailing 'add … to your hand'");
        fn.accept(ctx);
        verify(ctx).revealTopNPlayUpToElementTypeCostOntoField(2, 1, null, "Character", 3, RevealRest.HAND);
        verify(ctx, never()).returnNamedCardToYourHand(any());
    }

    @Test
    void bartzParsesAsOneEntersFieldAbility() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(
                "When Bartz enters the field, " + BARTZ_EFFECT);
        assertEquals(1, autos.size());
        assertEquals("enters the field", autos.get(0).trigger());
        assertNotNull(ActionResolver.parse(autos.get(0).effectText(), null));
    }

    // The siblings keep sending the remainder to the bottom of the deck — the destination is the
    // only thing that separates them, so it must not leak across.
    @Test
    void theBottomOfDeckSiblingsAreUnaffected() {
        GameContext ctx = mock(GameContext.class);
        ActionResolver.parse(
                "reveal the top 5 cards of your deck. Play 1 Forward of cost 2 or less among them "
                + "onto the field and return the other cards to the bottom of your deck in any order.",
                null).accept(ctx);
        verify(ctx).revealTopNPlayUpToElementTypeCostOntoField(5, 1, null, "Forward", 2, RevealRest.BOTTOM);
    }

    // "Then, shuffle the other cards revealed and return them to the bottom" — the third tail in
    // this family, and still a bottom-of-deck one.
    @Test
    void theShuffleThenBottomTailIsStillBottom() {
        GameContext ctx = mock(GameContext.class);
        ActionResolver.parse(
                "reveal the top 5 cards of your deck. Play 1 Forward of cost 3 or less among them "
                + "onto the field. Then, shuffle the other cards revealed and return them to the "
                + "bottom of your deck in any order.", null).accept(ctx);
        verify(ctx).revealTopNPlayUpToElementTypeCostOntoField(5, 1, null, "Forward", 3, RevealRest.BOTTOM);
    }

    // A genuine "Add <card name> to your hand" must still be read as one.
    @Test
    void anActualNamedAddToHandStillResolvesAsOne() {
        GameContext ctx = mock(GameContext.class);
        Consumer<GameContext> fn = ActionResolver.parse("Add Cloud to your hand.", null);
        assertNotNull(fn);
        fn.accept(ctx);
        verify(ctx).returnNamedCardToYourHand("Cloud");
    }

    // =========================================================================================
    // Sarah (FFL) 12-099R / 7-114H: searches that found nothing.
    //
    // Both failed in the regex, silently: a lazy group backtracked past its intended stopping
    // point and swallowed the rest of the sentence, so the search ran with a filter no card could
    // satisfy. The ability parsed, logged, and shuffled — it just never matched anything, which is
    // why the characterization test was blind to it.
    // =========================================================================================

    /** Captures the (job, category) a search text asks for. */
    private static String[] searchJobAndCategory(String text) {
        GameContext ctx = mock(GameContext.class);
        when(ctx.promptYouMay(anyString())).thenReturn(true);
        Consumer<GameContext> fn = ActionResolver.parse(text, null);
        assertNotNull(fn, "text must parse: " + text);
        fn.accept(ctx);
        ArgumentCaptor<String> job = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> cat = ArgumentCaptor.forClass(String.class);
        verify(ctx).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyInt(), any(), any(), job.capture(), cat.capture(), any(), any(), any(),
                any(), anyInt(), anyBoolean(), anyBoolean());
        return new String[] { job.getValue(), cat.getValue() };
    }

    // 12-099R. The category group used to run on to the end of the phrase, so the search looked
    // for a category literally named "FFL Forwards or Job Warrior of Light".
    @Test
    void sarahSearchesForEitherTheCategoryOrTheJob() {
        String[] f = searchJobAndCategory(
                "you may search for up to 2 Category FFL Forwards or Job Warrior of Light Forwards "
                + "and add them to your hand.");
        assertEquals("FFL", f[1]);
        assertEquals("Warrior of Light", f[0]);
    }

    // The reprint says "and/or" for the same effect.
    @Test
    void theReprintsAndOrWordingReadsTheSameWay() {
        String[] f = searchJobAndCategory(
                "you may search for up to 2 Category FFL Forwards and/or Job Warrior of Light "
                + "Forwards and add them to your hand.");
        assertEquals("FFL", f[1]);
        assertEquals("Warrior of Light", f[0]);
    }

    // 7-114H, and 5-123H Aria. "Light" is an element as well as the tail of the job name, and the
    // job group stopped at it — searching for a job called "Warrior of".
    @Test
    void aJobNameEndingInAnElementWordIsNotTruncated() {
        assertEquals("Warrior of Light", searchJobAndCategory(
                "you may search for 1 Job Warrior of Light Forward and add it to your hand.")[0]);
        assertEquals("Warrior of Light", searchJobAndCategory(
                "you may search for 1 Job Warrior of Light Forward of cost 4 or less "
                + "other than Light and Dark and play it onto the field.")[0]);
    }

    @Test
    void anOrdinaryJobFilterIsUnaffected() {
        String[] f = searchJobAndCategory(
                "you may search for 1 Job Knight Forward of cost 3 or less and add it to your hand.");
        assertEquals("Knight", f[0]);
        assertNull(f[1]);
    }

    /** A Forward carrying a job and/or a category, the two things a search identifies cards by. */
    private static CardData makeJobCategoryForward(String name, String job, String category) {
        return new CardData(null, name, "Fire", 3, 7000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                job, category, null, "");
    }

    // The engine half: stated together, the job and the category are alternatives. Run on P2's
    // side, where the search picks without a dialog.
    @Test
    void categoryAndJobStatedTogetherMatchEitherNotBoth() {
        MainWindow mw = new MainWindow();
        mw.gameState.getP2MainDeck().addAll(List.of(
                makeJobCategoryForward("Warrior",  "Warrior of Light", null),
                makeJobCategoryForward("Native",   null,               "FFL"),
                makeJobCategoryForward("Stranger", "Knight",           "XIV")));

        mw.searchDeckForCard(false, true, false, false, false, -1, null,
                null, "Warrior of Light", "FFL", null, null, null, "hand", 2, false, false);

        List<String> hand = mw.gameState.getP2Hand().stream().map(CardData::name).sorted().toList();
        assertEquals(List.of("Native", "Warrior"), hand,
                "either the job or the category qualifies; the card with neither does not");
    }

    // Alone, a category is still a plain requirement rather than a free pass.
    @Test
    void aCategoryOnItsOwnStillExcludesNonMatchingCards() {
        MainWindow mw = new MainWindow();
        mw.gameState.getP2MainDeck().addAll(List.of(
                makeJobCategoryForward("Native",   null, "FFL"),
                makeJobCategoryForward("Stranger", null, "XIV")));

        mw.searchDeckForCard(false, true, false, false, false, -1, null,
                null, null, "FFL", null, null, null, "hand", 2, false, false);

        assertEquals(List.of("Native"),
                mw.gameState.getP2Hand().stream().map(CardData::name).toList());
    }

    // =========================================================================================
    // 2-093H Raubahn: "choose 1 Forward you control and 1 Forward opponent controls. The first
    // one deals the second damage equal to its power."
    //
    // The same effect as 23-069C Narasimha and 16-078C Demonolith ("the former deals damage equal
    // to its power to the latter"), written two ways: Raubahn names the recipient first, in the
    // ditransitive, and calls the two groups "the first one"/"the second" instead of
    // former/latter. The parser gated on the literal words "the former" and "the latter" before
    // any pattern ran, so Raubahn chose both targets and then did nothing at all.
    // =========================================================================================

    private static final String RAUBAHN_EFFECT =
            "choose 1 Forward you control and 1 Forward opponent controls. "
            + "The first one deals the second damage equal to its power.";

    private static final String NARASIMHA_EFFECT =
            "choose 1 Forward you control and 1 Forward opponent controls. "
            + "The former deals damage equal to its power to the latter.";

    @Test
    void raubahnResolvesAsTheSameEffectAsItsFormerLatterSiblings() {
        assertNotNull(ActionResolver.parse(RAUBAHN_EFFECT, null));
        assertEquals(ActionResolver.fullDescription(NARASIMHA_EFFECT, null),
                ActionResolver.fullDescription(RAUBAHN_EFFECT, null),
                "two wordings of one effect must resolve the same way");
        assertEquals("ChooseFormerLatter", ActionResolver.fullDescription(RAUBAHN_EFFECT, null));
    }

    // "its" is the first Forward's power, and only the second one takes the damage.
    @Test
    void raubahnDealsTheChosenAllysPowerToTheChosenEnemy() {
        GameContext ctx = mock(GameContext.class);
        ForwardTarget mine   = new ForwardTarget(true,  0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget theirs = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(List.of(mine), List.of(theirs));
        when(ctx.effectiveTargetPower(mine)).thenReturn(8000);

        ActionResolver.parse(RAUBAHN_EFFECT, null).accept(ctx);

        verify(ctx).damageTarget(theirs, 8000);
        verify(ctx, never()).damageTarget(eq(mine), anyInt());
    }

    // The mutual sibling: 19-062R Nacht, 4-093R Hecatoncheir, 13-118C Sarah (MOBIUS), 14-074C and
    // 12-070C Monk. Both Forwards deal damage, and both amounts are the powers as they stood
    // before either was applied — if the first damage broke a Forward or reduced its power, the
    // return damage would otherwise shrink or vanish.
    private static final String MUTUAL_EFFECT =
            "choose 1 Forward you control and 1 Forward opponent controls. "
            + "Each Forward deals damage equal to its power to the other.";

    @Test
    void mutualPowerDamageHitsBothForwardsWithPreDamagePowers() {
        GameContext ctx = mock(GameContext.class);
        ForwardTarget mine   = new ForwardTarget(true,  0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget theirs = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(List.of(mine), List.of(theirs));
        when(ctx.effectiveTargetPower(mine)).thenReturn(8000);
        when(ctx.effectiveTargetPower(theirs)).thenReturn(5000);

        Consumer<GameContext> fn = ActionResolver.parse(MUTUAL_EFFECT, null);
        assertNotNull(fn);
        fn.accept(ctx);

        verify(ctx).damageTarget(theirs, 8000);
        verify(ctx).damageTarget(mine, 5000);
    }

    // 14-074C / 12-070C Monk restrict their own side to "1 Job Monk Forward or Card Name Monk
    // Forward you control". The mutual damage must still land on both.
    @Test
    void mutualPowerDamageSurvivesAJobFilterOnTheChoosersSide() {
        GameContext ctx = mock(GameContext.class);
        ForwardTarget mine   = new ForwardTarget(true,  0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget theirs = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(List.of(mine), List.of(theirs));
        when(ctx.effectiveTargetPower(mine)).thenReturn(8000);
        when(ctx.effectiveTargetPower(theirs)).thenReturn(5000);

        Consumer<GameContext> fn = ActionResolver.parse(
                "choose 1 Job Monk Forward or Card Name Monk Forward you control and "
                + "1 Forward opponent controls. "
                + "Each Forward deals damage equal to its power to the other.", null);
        assertNotNull(fn);
        fn.accept(ctx);

        verify(ctx).damageTarget(theirs, 8000);
        verify(ctx).damageTarget(mine, 5000);
    }

    // The mutual and one-way forms must not be confused: only one Forward takes damage in
    // Raubahn's, and only the opponent's.
    @Test
    void theOneWayFormDoesNotDamageTheChoosersOwnForward() {
        GameContext ctx = mock(GameContext.class);
        ForwardTarget mine   = new ForwardTarget(true,  0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget theirs = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
                any(), any(), anyBoolean(), any(), anyBoolean()))
                .thenReturn(List.of(mine), List.of(theirs));
        when(ctx.effectiveTargetPower(mine)).thenReturn(8000);
        when(ctx.effectiveTargetPower(theirs)).thenReturn(5000);

        ActionResolver.parse(RAUBAHN_EFFECT, null).accept(ctx);

        verify(ctx).damageTarget(theirs, 8000);
        verify(ctx, never()).damageTarget(eq(mine), anyInt());
    }

    @Test
    void raubahnParsesAsOneEntersFieldAbility() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(
                "[[ex]]EX BURST [[/]]When Raubahn enters the field, " + RAUBAHN_EFFECT);
        assertEquals(1, autos.size());
        assertEquals("enters the field", autos.get(0).trigger());
        assertNotNull(ActionResolver.parse(autos.get(0).effectText(), null));
    }

    // Widening the pronoun gate must not let through a first/second text whose effects nothing
    // understands — that would turn "chose nothing useful" into a silent no-op that claims to work.
    @Test
    void anUnknownFirstSecondEffectIsStillRejected() {
        assertNull(ActionResolverChoose.tryParseChooseFormerLatter(
                "choose 1 Forward you control and 1 Forward opponent controls. "
                + "The first one waltzes with the second.", null));
    }

	// =========================================================================================
	// Trailing draw.
	//
	// A trailing "Draw 1 card." rides behind a complete effect. Whichever pattern matches the
	// leading sentences claims the whole text with find() and parse() returns, so its
	// sentence-splitting fallback never runs and the draw used to be discarded silently.
	// =========================================================================================

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

	// =========================================================================================
	// Independent-sentence composition.
	//
	// A pattern anchored on one sentence claims the whole ability via find(), so every other
	// sentence used to be discarded. Where the sentences are independent, all of them resolve.
	// =========================================================================================

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

	// =========================================================================================
	// Conditional use restriction.
	//
	// 23-053R Meteion: "You can only use this ability if neither player controls Forwards."
	// The condition spans both fields, unlike "if you don't control any Forwards", which
	// inspects only the activating player's side.
	// =========================================================================================

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

	// =========================================================================================
	// Triggered target action.
	//
	// 26-032L Charlotte: "When a Character enters your opponent's field, dull it and Freeze it."
	// "it" is the card that fired the trigger, so the effect names no target of its own and the
	// followup pattern it matches is only ever reached behind a Choose primary.
	// =========================================================================================

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

	// =========================================================================================
	// Is-dealt-damage trigger.
	//
	// 28-043R Gi Nattak: "When Gi Nattak is dealt damage, choose 1 Forward opponent controls.
	// At the end of your opponent's turn, break it." The whole-text scan for delayed clauses used
	// to lift the second half out as its own ability, orphaning "break it" from the choose.
	// =========================================================================================

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

	// 20-057L The Goddess. A delayed clause inside a trigger is that trigger's one-shot, so it must
	// NOT also be lifted into a standalone ability: it used to be, and the card then broke Forwards
	// at the end of every opponent turn forever instead of once.
	@Test
	void delayedClauseInsideATriggerIsNotLiftedIntoItsOwnAbility() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(
				"When The Goddess enters the field, at the end of your opponent's turn, break all the "
				+ "Forwards opponent controls with a Doom Counter on them.");
		assertEquals(1, autos.size(), "one trigger, one ability — the delay is part of it");
		assertEquals("enters the field", autos.get(0).trigger());
		assertTrue(autos.stream().noneMatch(x -> x.trigger().equals("end of opponent's turn")),
				"a recurring end-of-turn ability here would fire every opponent turn, not once");
	}

	// The delay governs the break, so the break must be queued rather than run on resolution.
	// Both clauses use find(), so an ordering slip here breaks every Forward the moment
	// The Goddess enters — which is what used to happen.
	@Test
	void theGoddessQueuesTheBreakRatherThanApplyingItOnEntry() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"at the end of your opponent's turn, break all the Forwards opponent controls "
				+ "with a Doom Counter on them.", null);
		assertNotNull(fn);
		fn.accept(ctx);
		verify(ctx).addEndOfOpponentTurnEffect(any());
		verify(ctx, never()).applyMassFieldEffect(any(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyBoolean(), anyBoolean(), any(), anyInt(), any(), anyInt(), any(), any(), any(), any());
	}

	@Test
	void theGoddessPlacesDoomCountersOnEveryOpposingForward() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"place 1 Doom Counter on all the Forwards opponent controls.", null);
		assertNotNull(fn);
		fn.accept(ctx);
		verify(ctx).placeCountersOnAllForwards("Doom", 1, true, false);
	}

	// The counter clause trails "opponent controls", past where the regex used to end. Under
	// find() the restriction was silently dropped and every opposing Forward broke.
	@Test
	void theDoomCounterRestrictionReachesTheMassEffect() {
		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(
				"break all the Forwards opponent controls with a Doom Counter on them.", null).accept(ctx);
		verify(ctx).applyMassFieldEffect(eq(GameContext.MassAction.BREAK),
				eq(true), eq(false), eq(false), eq(true), eq(false),
				isNull(), eq(-1), isNull(), eq(-1), isNull(), isNull(), any(), eq("Doom"));
	}

	// End to end on a real board: only the Doom-Countered Forward breaks.
	@Test
	void onlyDoomCounteredForwardsAreBroken() {
		MainWindow mw = new MainWindow();
		CardData doomed = makeForward("Doomed", "Fire", 3, 7000);
		CardData spared = makeForward("Spared", "Fire", 3, 7000);
		placeP2Forward(mw, doomed);
		placeP2Forward(mw, spared);
		mw.gameState.placeCounters(doomed, "Doom", 1);

		mw.buildGameContext(true).applyMassFieldEffect(GameContext.MassAction.BREAK,
				true, false, false, true, false, null, -1, null, -1, null, null,
				EnumSet.noneOf(CardData.Trait.class), "Doom");

		assertEquals(List.of(spared), mw.p2ForwardCards,
				"the Forward without a Doom Counter must survive");
	}

	// The same call with no counter filter still sweeps the whole field.
	@Test
	void anUnfilteredMassBreakStillTakesEveryForward() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("A", "Fire", 3, 7000));
		placeP2Forward(mw, makeForward("B", "Fire", 3, 7000));

		mw.buildGameContext(true).applyMassFieldEffect(GameContext.MassAction.BREAK,
				true, false, false, true, false, null, -1, null, -1, null, null);

		assertTrue(mw.p2ForwardCards.isEmpty());
	}

	// =========================================================================================
	// "Your opponent reveals their hand. Select …" — 10 cards whose text used to be
	// claimed by the bare OPPONENT_REVEAL_HAND_PATTERN, which reveals the hand and
	// discards everything after it.
	// =========================================================================================

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

	// =========================================================================================
	// Don Corneo 14-035C: "your opponent reveals 3 cards from their hand. Select 1 card among
	// them. Your opponent discards this card."
	//
	// Two players decide here — the opponent picks what to show, the ability user picks what dies
	// — which is why this cannot ride on the whole-hand reveal above. That one exposes the entire
	// hand and would let the user take the best card in it, making Don Corneo strictly stronger
	// than printed.
	// =========================================================================================

	private static final String DON_CORNEO_EFFECT =
			"your opponent reveals 3 cards from their hand. Select 1 card among them. "
			+ "Your opponent discards this card.";

	@Test
	void donCorneoRevealsThreeCardsRatherThanTheWholeHand() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(DON_CORNEO_EFFECT, null);
		assertNotNull(fn);
		fn.accept(ctx);
		verify(ctx).opponentRevealsSelectOneDiscard(3);
		verify(ctx, never()).selectFromOpponentHandAndDiscard(anyInt(), any(), any());
	}

	// The whole-hand sibling sits directly behind this pattern in all three dispatch chains and
	// opens with the same words, so it has to keep claiming its own text.
	@Test
	void wholeHandRevealIsStillClaimedByTheWholeHandParser() {
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"Your opponent reveals their hand. Select 1 card from their hand. "
				+ "Your opponent discards this card.", null);
		assertNotNull(fn);
		fn.accept(ctx);
		verify(ctx).selectFromOpponentHandAndDiscard(eq(1), isNull(), any());
		verify(ctx, never()).opponentRevealsSelectOneDiscard(anyInt());
	}

	@Test
	void donCorneoReportsAPatternName() {
		assertEquals("OpponentRevealNSelectOneDiscard",
				ActionResolver.matchedPatternName(DON_CORNEO_EFFECT, null));
	}

	@Test
	void donCorneoParsesAsOneEntersFieldAbility() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(
				"When Don Corneo enters the field, " + DON_CORNEO_EFFECT);
		assertEquals(1, autos.size());
		assertEquals("enters the field", autos.get(0).trigger());
		assertNotNull(ActionResolver.parse(autos.get(0).effectText(), null),
				"the effect text must still parse once split off from its trigger");
	}

	// An empty hand has nothing to reveal and nothing to discard, so neither player is owed a
	// dialog. Hand size is read here, as the effect resolves, not when it went on the Stack — a
	// response that refilled the hand in between still counts.
	@Test
	void emptyOpponentHandRevealsAndDiscardsNothing() {
		MainWindow mw = new MainWindow();
		mw.buildGameContext(false).opponentRevealsSelectOneDiscard(3);
		assertTrue(mw.gameState.getP1Hand().isEmpty());
		assertTrue(mw.gameState.getP1BreakZone().isEmpty());
	}

	// Holding 3 or fewer makes the reveal forced, so the revealing player is never asked which
	// cards to show — there is no decision in it. Both clients derive this case independently
	// rather than exchanging it.
	@Test
	void handOfThreeOrFewerIsRevealedWithoutAskingWhichCards() {
		MainWindow mw = new MainWindow();
		mw.gameState.getP1Hand().addAll(List.of(
				makeForward("Cheap", "Fire", 1, 3000),
				makeForward("Dear",  "Fire", 7, 9000)));
		assertEquals(List.of(0, 1), mw.revealHandCards(true, 3));
	}

	// What the CPU volunteers is a real decision: showing its best card would be a strictly worse
	// one, so the three it reveals are its least valuable.
	@Test
	void cpuRevealsItsLeastValuableCards() {
		MainWindow mw = new MainWindow();
		mw.gameState.getP2Hand().addAll(List.of(
				makeForward("Dear",    "Fire", 8, 9000),
				makeForward("Cheap",   "Fire", 1, 3000),
				makeForward("Middle",  "Fire", 4, 6000),
				makeForward("Cheaper", "Fire", 0, 1000)));
		List<Integer> revealed = mw.revealHandCards(false, 3);
		assertEquals(List.of(1, 2, 3), revealed);
		assertFalse(revealed.contains(0), "the CPU must not volunteer its best card");
	}

	@Test
	void cpuSelectsTheMostExpensiveCardItWasShown() {
		MainWindow mw = new MainWindow();
		mw.gameState.getP1Hand().addAll(List.of(
				makeForward("Cheap",  "Fire", 1, 3000),
				makeForward("Dear",   "Fire", 7, 9000),
				makeForward("Middle", "Fire", 4, 6000)));
		assertEquals(1, mw.selectRevealedHandCard(false, List.of(0, 1, 2)));
	}

	// The whole effect end to end, on the one path that needs no dialogs: P2 owns Don Corneo, P1
	// holds 2 cards so the reveal is forced, and the CPU selector takes the dearer of the two.
	@Test
	void donCorneoDiscardsTheBestCardOfAForcedReveal() {
		MainWindow mw = new MainWindow();
		CardData cheap = makeForward("Cheap", "Fire", 1, 3000);
		CardData dear  = makeForward("Dear",  "Fire", 7, 9000);
		mw.gameState.getP1Hand().addAll(List.of(cheap, dear));
		mw.buildGameContext(false).opponentRevealsSelectOneDiscard(3);
		assertEquals(List.of(cheap), mw.gameState.getP1Hand());
		assertEquals(List.of(dear),  mw.gameState.getP1BreakZone());
	}

	// =========================================================================================
	// "Choose 1 … in your Break Zone. Put it on top of your deck."
	// The choose header was recognised but this followup was not, so the whole
	// ability resolved to a "followup not yet implemented" log line and did nothing.
	// =========================================================================================

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

	// =========================================================================================
	// 8-147S Fordola: "choose 1 Backup you control. You may remove it from the game.
	// If you do so, Fordola gains +1000 power, Haste, First Strike and Brave.
	// (This effect does not end at the end of the turn.)"
	// The "You may" used to be ignored (the Backup was removed unconditionally) and the
	// payoff was dropped, because the permanent self-buff had no parser.
	// =========================================================================================

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

	// =========================================================================================
	// 20-107H Urianger: "if 1 or more of your cards have been removed from the game,
	// you may search for 1 Category XIV Forward and add it to your hand."
	// The search parsed but the gate did not, so it searched unconditionally.
	// =========================================================================================

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

	// =========================================================================================
	// "You may search …" — searching is a public event that opponents' abilities react to
	// (5-130R Tonberry, 13-034H Remedi, 25-111H The Emperor), so declining has to mean the
	// search never happened, not that it happened and found nothing.
	// =========================================================================================

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

	// =========================================================================================
	// Searches whose filter comes off the card the player just chose:
	//   23-130H Luso    — "…search for 1 Job Standard Unit of the same Element as the chosen
	//                      Character and add it to your hand."
	//   12-106R Relm    — "…search for 1 Character with the same name and add it to your hand."
	//   23-078C Alisaie — same, choosing from the Break Zone instead of the field.
	//
	// None of these filters is written in the text. Before this followup existed, ChooseCharacter's
	// generic dispatch matched the trailing "add it to your hand" and returned the chosen Character
	// to hand — the wrong zone, the wrong card, and no search at all.
	// =========================================================================================

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

	// =========================================================================================
	// Searching is a public event. 5-130R Tonberry, 13-034H Remedi and 25-111H The Emperor
	// all watch for it; none of them parsed a single auto ability before.
	// =========================================================================================

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

	// =========================================================================================
	// "discards … due to your Summons or abilities" (an 11-card family) and
	// "1 or more cards are added to your opponent's hand from the Break Zone".
	// =========================================================================================

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

	// =========================================================================================
	// Target redirection — Faris 21-114L and Edge 15-045H.
	//
	// Faris: "《0》: Choose 1 Summon or ability that is choosing only Faris. You may choose
	//        another Water Forward you control to become the new target (…)."
	// Edge:  "《0》: Choose 1 Summon or ability that is choosing only 1 Wind Forward you control.
	//        The Summon or ability is now choosing Edge instead, if possible."
	//
	// Mirror images: Faris is eligible on entries pointed at *her* and sends them elsewhere;
	// Edge is eligible on entries pointed at his side and pulls them onto *himself*. Both act on
	// an entry already on the Stack, which is why the redirect rewrites StackEntry rather than
	// doing anything at resolution time.
	// =========================================================================================

	private static final String FARIS_REDIRECT =
			"Choose 1 Summon or ability that is choosing only Faris. You may choose another Water "
			+ "Forward you control to become the new target (The newly chosen Forward must be a valid choice).";
	private static final String EDGE_REDIRECT =
			"Choose 1 Summon or ability that is choosing only 1 Wind Forward you control. "
			+ "The Summon or ability is now choosing Edge instead, if possible.";

	private static CardData makeFaris() {
		return makeForward("Faris", "Water", 4, 8000, CardData.parseActionAbilities("《0》: " + FARIS_REDIRECT));
	}

	private static CardData makeEdge() {
		return makeForward("Edge", "Wind", 2, 5000, CardData.parseActionAbilities("《0》: " + EDGE_REDIRECT));
	}

	/** A Summon entry belonging to {@code isP1} that has chosen exactly {@code targets}. */
	private static StackEntry summonChoosing(CardData summon, boolean isP1, ForwardTarget... targets) {
		return new StackEntry(summon, null, isP1, 0, List.of(targets));
	}

	private static ForwardTarget fwd(boolean isP1, int idx) {
		return new ForwardTarget(isP1, idx, ForwardTarget.CardZone.FORWARD);
	}

	@Test
	void eachRedirectSpecIsAnchoredToItsOwnCardsName() {
		TargetRedirect faris = ActionResolver.targetRedirect(FARIS_REDIRECT, makeFaris());
		assertNotNull(faris);
		assertTrue(faris.eligibleOnSourceItself(), "eligible on entries choosing Faris herself");
		assertEquals("Water", faris.newTargetElement());
		assertFalse(faris.toSource());
		assertTrue(faris.optional(), "\"You may choose\" — declining is legal");

		TargetRedirect edge = ActionResolver.targetRedirect(EDGE_REDIRECT, makeEdge());
		assertNotNull(edge);
		assertEquals("Wind", edge.eligibleElement());
		assertTrue(edge.toSource(), "the entry ends up choosing Edge");
		assertNull(edge.newTargetElement(), "no player pick — the destination is fixed");

		// The card named in the text has to *be* the source. Otherwise a future card quoting
		// this wording would redirect onto a permanent its own text never mentions.
		assertNull(ActionResolver.targetRedirect(FARIS_REDIRECT, makeForward("Lenna", "Water", 4, 8000)));
		assertNull(ActionResolver.targetRedirect(EDGE_REDIRECT, makeForward("Rydia", "Wind", 2, 5000)));
	}

	@Test
	void edgeIsEligibleOnlyForEntriesChoosingExactlyOneOfYourWindForwards() {
		MainWindow mw = new MainWindow();
		CardData edge   = makeEdge();
		CardData yuffie = makeForward("Yuffie", "Wind", 3, 7000);
		CardData vivi    = makeForward("Vivi", "Fire", 3, 7000);
		placeP1Forward(mw, edge);    // P1 idx 0
		placeP1Forward(mw, yuffie);  // P1 idx 1
		placeP1Forward(mw, vivi);    // P1 idx 2
		mw.placeP2CardInForwardZone(makeForward("Shantotto", "Wind", 3, 7000)); // P2 idx 0

		CardData summon = makeForward("Ramuh", "Wind", 3, 0);
		Predicate<StackEntry> eligible =
				mw.redirectEligibility(ActionResolver.targetRedirect(EDGE_REDIRECT, edge), edge, true);

		assertTrue(eligible.test(summonChoosing(summon, false, fwd(true, 1))),
				"an opponent's Summon choosing your Wind Forward is the whole point");
		assertFalse(eligible.test(summonChoosing(summon, false, fwd(true, 2))),
				"a Fire Forward is not a Wind Forward");
		assertFalse(eligible.test(summonChoosing(summon, false, fwd(false, 0))),
				"a Wind Forward the opponent controls is not one you control");
		assertFalse(eligible.test(summonChoosing(summon, false, fwd(true, 1), fwd(true, 2))),
				"\"choosing only 1\" excludes an entry choosing two");
		assertFalse(eligible.test(new StackEntry(summon, null, false)),
				"an entry that chose nothing has no target to move");
	}

	@Test
	void edgePullsTheChosenTargetOntoHimself() {
		MainWindow mw = new MainWindow();
		CardData edge   = makeEdge();
		CardData yuffie = makeForward("Yuffie", "Wind", 3, 7000);
		placeP1Forward(mw, edge);    // P1 idx 0
		placeP1Forward(mw, yuffie);  // P1 idx 1

		StackEntry entry = summonChoosing(makeForward("Ramuh", "Wind", 3, 0), false, fwd(true, 1));
		mw.gameState.pushStack(entry);

		ActionResolver.parse(edge.actionAbilities().get(0).effectText(), edge)
				.accept(mw.buildGameContext(true));

		assertEquals(1, mw.gameState.getStack().size(), "the entry still resolves — this is not a cancel");
		assertEquals(List.of(fwd(true, 0)), mw.gameState.getStack().get(0).preSelectedTargets(),
				"Ramuh is now choosing Edge instead of Yuffie");
	}

	@Test
	void edgeDoesNothingWhenHeIsNotAValidChoice() {
		MainWindow mw = new MainWindow();
		CardData edge   = makeEdge();
		CardData yuffie = makeForward("Yuffie", "Wind", 3, 7000);
		placeP1Forward(mw, edge);
		placeP1Forward(mw, yuffie);
		// "if possible" — Edge is shielded from the opponent's Summons, so he cannot be chosen.
		mw.cannotBeChosenBySummons.add(edge);

		StackEntry entry = summonChoosing(makeForward("Ramuh", "Wind", 3, 0), false, fwd(true, 1));
		mw.gameState.pushStack(entry);

		ActionResolver.parse(edge.actionAbilities().get(0).effectText(), edge)
				.accept(mw.buildGameContext(true));

		assertEquals(List.of(fwd(true, 1)), mw.gameState.getStack().get(0).preSelectedTargets(),
				"the Summon keeps its original target");
	}

	@Test
	void farisPushesTheEffectOntoAnotherWaterForwardSheControls() {
		MainWindow mw = new MainWindow();
		// Run this from P2's side: the human's pick is a modal dialog, while the AI path takes
		// the first legal candidate and exercises the same eligibility and rewrite code.
		CardData faris = makeFaris();
		mw.placeP2CardInForwardZone(faris);                                  // P2 idx 0
		mw.placeP2CardInForwardZone(makeForward("Syldra", "Fire", 3, 7000)); // P2 idx 1 — wrong Element
		mw.placeP2CardInForwardZone(makeForward("Lenna", "Water", 3, 7000)); // P2 idx 2

		StackEntry entry = summonChoosing(makeForward("Shiva", "Ice", 2, 0), true, fwd(false, 0));
		mw.gameState.pushStack(entry);

		ActionResolver.parse(faris.actionAbilities().get(0).effectText(), faris)
				.accept(mw.buildGameContext(false));

		assertEquals(List.of(fwd(false, 2)), mw.gameState.getStack().get(0).preSelectedTargets(),
				"Shiva now chooses Lenna — Faris herself is excluded by \"another\", Syldra by Element");
	}

	@Test
	void aPendingCancellationSurvivesTheRedirectRewrite() {
		MainWindow mw = new MainWindow();
		CardData edge = makeEdge();
		placeP1Forward(mw, edge);
		placeP1Forward(mw, makeForward("Yuffie", "Wind", 3, 7000));

		StackEntry entry = summonChoosing(makeForward("Ramuh", "Wind", 3, 0), false, fwd(true, 1));
		mw.gameState.pushStack(entry);
		mw.cancelledStackEntries.add(entry);

		mw.redirectStackEntryTargets(entry, List.of(fwd(true, 0)));

		StackEntry updated = mw.gameState.getStack().get(0);
		assertNotSame(entry, updated, "StackEntry is a record, so the redirect substitutes a copy");
		assertTrue(mw.cancelledStackEntries.contains(updated),
				"the cancellation has to move to the copy or the entry quietly un-cancels");
		assertFalse(mw.cancelledStackEntries.contains(entry), "and must not linger on the stale instance");
	}

	private static final String CALBRENA_REDIRECT =
			"choose 1 ability that is choosing only 1 Character either player controls. "
			+ "The ability is now choosing Calbrena instead, if possible.";
	private static final String WICKED_MASK_REDIRECT =
			"choose 1 Summon that is choosing only 1 Character in any zone. You may choose another "
			+ "Character to become the new target (The newly chosen Character must be a valid choice).";
	private static final String AEMO_REDIRECT =
			"Choose 1 auto-ability or action ability that has only one target. You may choose "
			+ "another target to become the new target (The newly chosen target must be a valid choice).";

	/** An ability entry belonging to {@code isP1} that has chosen exactly {@code targets}. */
	private static StackEntry abilityChoosing(CardData src, boolean isP1, ForwardTarget... targets) {
		return new StackEntry(src,
				CardData.parseActionAbilities("《Dull》: Choose 1 Forward. Break it.").get(0),
				isP1, 0, List.of(targets));
	}

	@Test
	void theWholeRedirectFamilyIsRecognised() {
		assertNotNull(ActionResolver.targetRedirect(CALBRENA_REDIRECT, makeForward("Calbrena", "Ice", 3, 7000)));
		assertNotNull(ActionResolver.targetRedirect(WICKED_MASK_REDIRECT, makeForward("Wicked Mask", "Ice", 3, 6000)));
		// Aemo names no card, so any source will do — its eligibility is "has only one target".
		TargetRedirect aemo = ActionResolver.targetRedirect(AEMO_REDIRECT, makeForward("Aemo", "Water", 2, 0));
		assertNotNull(aemo);

		// Entry kind is the axis that separates the two free-pick cards.
		assertEquals(TargetRedirect.EntryKind.ABILITY, aemo.entryKind(), "Aemo cannot touch Summons");
		assertEquals(TargetRedirect.EntryKind.SUMMON,
				ActionResolver.targetRedirect(WICKED_MASK_REDIRECT, makeForward("Wicked Mask", "Ice", 3, 6000)).entryKind(),
				"Wicked Mask only touches Summons");
		assertEquals(TargetRedirect.EntryKind.ABILITY,
				ActionResolver.targetRedirect(CALBRENA_REDIRECT, makeForward("Calbrena", "Ice", 3, 7000)).entryKind());

		// Calbrena still has to name itself, like Edge does.
		assertNull(ActionResolver.targetRedirect(CALBRENA_REDIRECT, makeForward("Cid", "Ice", 3, 7000)));
	}

	@Test
	void entryKindDecidesWhichStackEntriesEachCardCanTouch() {
		MainWindow mw = new MainWindow();
		CardData calbrena = makeForward("Calbrena", "Ice", 3, 7000);
		CardData victim   = makeForward("Victim", "Fire", 3, 7000);
		placeP1Forward(mw, calbrena); // P1 idx 0
		placeP1Forward(mw, victim);   // P1 idx 1
		mw.placeP2CardInForwardZone(makeForward("Golbez", "Dark", 4, 8000)); // P2 idx 0

		CardData other = makeForward("Other", "Ice", 3, 0);
		Predicate<StackEntry> calbrenaCan = mw.redirectEligibility(
				ActionResolver.targetRedirect(CALBRENA_REDIRECT, calbrena), calbrena, true);
		Predicate<StackEntry> maskCan = mw.redirectEligibility(
				ActionResolver.targetRedirect(WICKED_MASK_REDIRECT, makeForward("Wicked Mask", "Ice", 3, 6000)),
				calbrena, true);

		assertTrue(calbrenaCan.test(abilityChoosing(other, false, fwd(true, 1))),
				"Calbrena redirects abilities");
		assertFalse(calbrenaCan.test(summonChoosing(other, false, fwd(true, 1))),
				"but never a Summon — its text says \"ability\"");
		assertTrue(maskCan.test(summonChoosing(other, false, fwd(true, 1))),
				"Wicked Mask redirects Summons");
		assertFalse(maskCan.test(abilityChoosing(other, false, fwd(true, 1))),
				"but never an ability");

		// Calbrena's pool is "either player controls" — a Break Zone selection is not on a field.
		assertTrue(calbrenaCan.test(abilityChoosing(other, false, fwd(false, 0))),
				"a Character the opponent controls still counts");
		assertFalse(calbrenaCan.test(new StackEntry(other, null, null, false, 0, false,
				List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.BREAK_ZONE)), false, false, 0)),
				"\"either player controls\" excludes a Break Zone target");
	}

	@Test
	void calbrenaPullsAnAbilityOntoHerself() {
		MainWindow mw = new MainWindow();
		CardData calbrena = makeForward("Calbrena", "Ice", 3, 7000);
		placeP1Forward(mw, calbrena);                            // P1 idx 0
		placeP1Forward(mw, makeForward("Ally", "Ice", 3, 7000));  // P1 idx 1

		StackEntry entry = abilityChoosing(makeForward("Sephiroth", "Dark", 5, 9000), false, fwd(true, 1));
		mw.gameState.pushStack(entry);

		ActionResolver.parse(CALBRENA_REDIRECT, calbrena).accept(mw.buildGameContext(true));

		assertEquals(List.of(fwd(true, 0)), mw.gameState.getStack().get(0).preSelectedTargets(),
				"the ability now points at Calbrena");
	}

	@Test
	void aemoCanPushAnAbilityOntoTheOpponentsOwnCharacter() {
		MainWindow mw = new MainWindow();
		CardData aemo = makeForward("Aemo", "Water", 2, 0);
		// Driven from P2's side so the pick resolves without the modal chooser; the AI takes the
		// first legal candidate, which is P1's Forward at index 0.
		mw.placeP2CardInForwardZone(aemo);
		placeP1Forward(mw, makeForward("Zidane", "Wind", 2, 5000)); // P1 idx 0
		mw.placeP2CardInForwardZone(makeForward("Kuja", "Dark", 4, 8000)); // P2 idx 1 — currently chosen

		StackEntry entry = abilityChoosing(makeForward("Bahamut", "Fire", 5, 9000), true, fwd(false, 1));
		mw.gameState.pushStack(entry);

		ActionResolver.parse(AEMO_REDIRECT, aemo).accept(mw.buildGameContext(false));

		List<ForwardTarget> after = mw.gameState.getStack().get(0).preSelectedTargets();
		assertEquals(List.of(fwd(true, 0)), after,
				"\"another target\" reaches either field — the pool is not limited to your own side");
	}

	@Test
	void aemoLeavesAnEntryAloneWhenNoOtherTargetIsAValidChoice() {
		MainWindow mw = new MainWindow();
		CardData aemo = makeForward("Aemo", "Water", 2, 0);
		CardData kuja = makeForward("Kuja", "Dark", 4, 8000);
		mw.placeP2CardInForwardZone(aemo); // P2 idx 0
		mw.placeP2CardInForwardZone(kuja); // P2 idx 1
		placeP1Forward(mw, makeForward("Zidane", "Wind", 2, 5000)); // P1 idx 0 — currently chosen

		// The entry is P1's own ability, so P1's field offers nothing but the card it already
		// chose. Shielding P2's two Characters from the opponent's abilities empties the pool.
		mw.cannotBeChosenByAbilities.add(aemo);
		mw.cannotBeChosenByAbilities.add(kuja);

		StackEntry entry = abilityChoosing(makeForward("Bahamut", "Fire", 5, 9000), true, fwd(true, 0));
		mw.gameState.pushStack(entry);

		ActionResolver.parse(AEMO_REDIRECT, aemo).accept(mw.buildGameContext(false));

		assertEquals(List.of(fwd(true, 0)), mw.gameState.getStack().get(0).preSelectedTargets(),
				"no valid replacement — the entry keeps its original target");
	}

	@Test
	void protectionOnlyBlocksARedirectOntoTheEntryOwnersOpponent() {
		MainWindow mw = new MainWindow();
		CardData aemo     = makeForward("Aemo", "Water", 2, 0);
		CardData ownShield = makeForward("Warded", "Wind", 2, 5000);
		mw.placeP2CardInForwardZone(aemo);                          // P2 idx 0
		placeP1Forward(mw, ownShield);                              // P1 idx 0
		placeP1Forward(mw, makeForward("Zidane", "Wind", 2, 5000));  // P1 idx 1 — currently chosen
		// "Cannot be chosen by your opponent's abilities" does not stop its own controller's
		// ability, so P1's own ability may still be pointed at it.
		mw.cannotBeChosenByAbilities.add(ownShield);

		StackEntry entry = abilityChoosing(makeForward("Bahamut", "Fire", 5, 9000), true, fwd(true, 1));
		mw.gameState.pushStack(entry);

		ActionResolver.parse(AEMO_REDIRECT, aemo).accept(mw.buildGameContext(false));

		assertEquals(List.of(fwd(true, 0)), mw.gameState.getStack().get(0).preSelectedTargets(),
				"the shield is against the opponent's abilities, and this ability is its controller's own");
	}

	// =========================================================================================
	// Targets are chosen when an effect goes on the Stack, not when it resolves.
	//
	// This is the rule the whole redirect family depends on: "choose 1 Summon that is choosing
	// only X" can only mean anything if a Summon on the Stack has already chosen. Action
	// abilities always worked this way; Summons and auto-abilities used to choose at resolution,
	// which left Wicked Mask inert and Faris/Edge unable to catch the Summons they exist for.
	// =========================================================================================

	/** P2 casts {@code summon}: it chooses its targets and goes on the Stack, minus the overlay. */
	private static void castSummon(MainWindow mw, CardData summon) {
		mw.pushSummonOnStack(summon, false, 0, 0, false, null, false);
	}

	@Test
	void aCastSummonRecordsItsTargetOnTheStackEntry() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Zidane", "Wind", 2, 5000)); // P1 idx 0 — the only Forward

		castSummon(mw, makeSummon("Shiva", "Ice", 2,
				"Choose 1 Forward. Deal it 7000 damage."));

		List<ForwardTarget> chosen = mw.gameState.getStack().get(0).preSelectedTargets();
		assertNotNull(chosen, "a Summon that chooses has to record the choice, or nothing can respond to it");
		assertEquals(List.of(fwd(true, 0)), chosen);
	}

	@Test
	void aSummonThatChoosesNothingUpFrontStoresNoTargets() {
		MainWindow mw = new MainWindow();
		castSummon(mw, makeSummon("Cure", "Water", 1, "Draw 1 card."));
		assertNull(mw.gameState.getStack().get(0).preSelectedTargets(),
				"nothing to choose means nothing preloaded — resolution behaves exactly as before");
	}

	@Test
	void edgeRedirectsASummonThatWasActuallyCast() {
		MainWindow mw = new MainWindow();
		CardData edge = makeEdge();
		placeP1Forward(mw, edge);                                   // P1 idx 0, cost 2
		placeP1Forward(mw, makeForward("Yuffie", "Wind", 3, 7000)); // P1 idx 1, cost 3

		// Cost-restricted so the cast's own choice is deterministic: only Yuffie qualifies.
		castSummon(mw, makeSummon("Ramuh", "Wind", 3,
				"Choose 1 Forward of cost 3 or more. Deal it 7000 damage."));
		assertEquals(List.of(fwd(true, 1)), mw.gameState.getStack().get(0).preSelectedTargets(),
				"the Summon chose Yuffie as it was cast");

		ActionResolver.parse(edge.actionAbilities().get(0).effectText(), edge)
				.accept(mw.buildGameContext(true));

		assertEquals(List.of(fwd(true, 0)), mw.gameState.getStack().get(0).preSelectedTargets(),
				"Edge pulls a genuinely cast Summon onto himself — the case that was unreachable "
				+ "while Summons chose at resolution time");
	}

	@Test
	void wickedMaskCanRedirectACastSummon() {
		MainWindow mw = new MainWindow();
		CardData mask = makeForward("Wicked Mask", "Ice", 3, 6000);
		mw.placeP2CardInForwardZone(mask);                          // P2 idx 0
		mw.placeP2CardInForwardZone(makeForward("Kuja", "Dark", 4, 8000)); // P2 idx 1
		placeP1Forward(mw, makeForward("Zidane", "Wind", 2, 5000)); // P1 idx 0 — the cast's target

		castSummon(mw, makeSummon("Shiva", "Ice", 2,
				"Choose 1 Forward. Deal it 7000 damage."));
		assertEquals(List.of(fwd(true, 0)), mw.gameState.getStack().get(0).preSelectedTargets());

		// Driven from P2's side so the pick resolves without the modal chooser.
		ActionResolver.parse(WICKED_MASK_REDIRECT, mask).accept(mw.buildGameContext(false));

		assertNotEquals(List.of(fwd(true, 0)), mw.gameState.getStack().get(0).preSelectedTargets(),
				"Wicked Mask only ever touches Summons, so it was completely inert before this");
	}

	// =========================================================================================
	// A "when this is chosen by your opponent's Summons or abilities" trigger resolves BEFORE the
	// effect that chose it — under the rules it goes on the Stack above that effect.
	//
	// The engine cannot express that once the chooser is already resolving: a selection made
	// mid-resolution happens with the chooser off the Stack and about to act on what it picked, so
	// a stacked trigger resolves *after* it. Emet-Selch (12-024H) was dealt its lethal damage and
	// broken before the removal that should have made that damage fizzle ever ran. The whole
	// family therefore resolves inline, and the selection is re-anchored afterwards: a
	// ForwardTarget is a position, and the zone list closes up behind a card that just left.
	// =========================================================================================

	/** P2's ability chooses {@code maxCount} of P1's Forwards — the AI side, so no modal chooser. */
	private static List<ForwardTarget> opponentChoosesP1Forwards(MainWindow mw, int maxCount) {
		return mw.buildGameContext(false).selectCharacters(maxCount, false, true, false, null, null,
				-1, null, -1, null, true, false, false, null, null, null, null, false, null, false);
	}

	@Test
	void aChosenTriggerResolvesInsteadOfWaitingOnTheStack() {
		MainWindow mw = new MainWindow();
		CardData emet = makeAutoAbilityForward("Emet-Selch", "Ice", 9000, EMET_SELCH_TEXT);
		mw.placeP2CardInForwardZone(emet);
		mw.gameState.getIdentity().put(emet, false);

		// Fired for P2's side: a P2-owned entry is one the Stack overlay never auto-resolves, so
		// anything that reached the Stack here would still be sitting on it, unresolved.
		mw.autoAbilityTriggers.triggerAutoAbilitiesForChosenByOpponentSummonOrAbility(false, List.of(emet));

		assertTrue(mw.gameState.getStack().isEmpty(),
				"the trigger has to resolve now, not queue up behind the effect that chose");
		assertFalse(mw.p2ForwardCards.contains(emet),
				"Emet-Selch removes itself from the game as it is chosen");
	}

	@Test
	void aSelectionDropsTheCardThatLeftInResponseToBeingChosen() {
		MainWindow mw = new MainWindow();
		CardData emet = makeAutoAbilityForward("Emet-Selch", "Ice", 9000, EMET_SELCH_TEXT);
		placeP1Forward(mw, emet);                                   // P1 idx 0 — the only Forward

		assertEquals(List.of(), opponentChoosesP1Forwards(mw, 1),
				"the only chosen card left the field, so the effect has nothing left to act on — "
				+ "which is what makes its lethal damage fizzle");
		assertFalse(mw.p1ForwardCards.contains(emet), "and it warped away rather than being broken");
	}

	@Test
	void aSurvivingSelectionFollowsItsCardPastTheGapLeftBehind() {
		MainWindow mw = new MainWindow();
		CardData emet   = makeAutoAbilityForward("Emet-Selch", "Ice", 9000, EMET_SELCH_TEXT);
		CardData sephir = makeForward("Sephiroth", "Dark", 5, 9000);
		placeP1Forward(mw, emet);                                   // P1 idx 0
		placeP1Forward(mw, sephir);                                 // P1 idx 1

		// Both are chosen; Emet-Selch leaves, closing the gap Sephiroth then slides into.
		assertEquals(List.of(fwd(true, 0)), opponentChoosesP1Forwards(mw, 2),
				"Sephiroth is still chosen, now at index 0 — index 1 would point past the row, and "
				+ "keeping Emet-Selch's index 0 would land the damage meant for it on Sephiroth");
	}

	// =========================================================================================
	// Tidus (1-163L) — "Blitz Ace" 《S》《Water》《Water》: "Until the end of the turn Tidus gains
	// Brave. Tidus can attack as many times as your points of damage this turn."
	//
	// Two independent sentences, so they parse as two standalone effects and compose. The second
	// is a multi-attack permission whose size is read off the damage zone when the ability
	// resolves — a resolved special ability grants a fixed permission, not one that keeps pace
	// with damage taken later in the turn.
	// =========================================================================================

	private static final String BLITZ_ACE =
			"Until the end of the turn Tidus gains Brave. "
			+ "Tidus can attack as many times as your points of damage this turn.";

	/** Puts {@code points} cards into P1's damage zone. */
	private static void dealP1Damage(MainWindow mw, int points) {
		for (int i = 0; i < points; i++)
			mw.gameState.getP1DamageZone().add(makeForward("Damage " + i, "Fire", 1, 1000));
	}

	@Test
	void blitzAceComposesItsTwoIndependentSentences() {
		CardData tidus = makeForward("Tidus", "Water", 4, 7000);
		assertEquals("SelfBoostUntilEot + SelfAttacksPerOwnDamage",
				ActionResolver.fullDescription(BLITZ_ACE, tidus));

		// The subject has to be the source in both halves — the sentences name Tidus, and a
		// grant that silently applied to whoever activated it would be a different card.
		CardData wakka = makeForward("Wakka", "Water", 3, 7000);
		assertNull(ActionResolver.parse(BLITZ_ACE, wakka));
	}

	@Test
	void blitzAceGrantsBraveAndOneAttackPerPointOfDamage() {
		MainWindow mw = new MainWindow();
		CardData tidus = makeForward("Tidus", "Water", 4, 7000);
		placeP1Forward(mw, tidus);
		dealP1Damage(mw, 3);

		ActionResolver.parse(BLITZ_ACE, tidus).accept(mw.buildGameContext(true));

		assertTrue(mw.p1ForwardTempTraits.get(0).contains(CardData.Trait.BRAVE), "gains Brave");
		assertEquals(3, mw.attacksAllowed(tidus), "3 points of damage — 3 attacks");
	}

	@Test
	void blitzAceFixesTheAttackCountWhenItResolves() {
		MainWindow mw = new MainWindow();
		CardData tidus = makeForward("Tidus", "Water", 4, 7000);
		placeP1Forward(mw, tidus);
		dealP1Damage(mw, 2);

		ActionResolver.parse(BLITZ_ACE, tidus).accept(mw.buildGameContext(true));
		assertEquals(2, mw.attacksAllowed(tidus));

		// Damage taken after the ability resolved does not enlarge a permission already granted.
		dealP1Damage(mw, 3);
		assertEquals(2, mw.attacksAllowed(tidus),
				"the permission was sized when it resolved, not re-read on each attack");
	}

	@Test
	void blitzAceOnAnUndamagedPlayerStillLeavesTidusHisNormalAttack() {
		MainWindow mw = new MainWindow();
		CardData tidus = makeForward("Tidus", "Water", 4, 7000);
		placeP1Forward(mw, tidus);

		ActionResolver.parse(BLITZ_ACE, tidus).accept(mw.buildGameContext(true));

		// A permission of 0 cannot take away the one attack every Forward has: the granted count
		// is the stronger of the two, never a cap.
		assertEquals(1, mw.attacksAllowed(tidus), "no damage — no extra attacks, but not zero either");
		assertTrue(mw.p1ForwardTempTraits.get(0).contains(CardData.Trait.BRAVE),
				"the Brave half does not depend on the damage count");
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

	// =========================================================================================
	// Element-qualified Job/Category field grants: "The Ice Job Standard Unit Forwards you
	// control gain +2000 power." (3-040C DGS Trooper 1st Class). FIELD_GRANT_PATTERN carried a
	// Job/Category filter but no element, and FIELD_GRANT_BARE_PATTERN carried an element but no
	// Job/Category — so a text needing both fell between them and produced no grant at all. The
	// two filters stack: a card must clear both to be boosted.
	// =========================================================================================

	private static final String DGS_TROOPER_GRANT =
			"The Ice Job Standard Unit Forwards you control gain +2000 power.";

	/** Puts a grant-carrying Backup into P1's first Backup slot, as the engine's field-grant scan expects. */
	private static CardData placeP1GrantBackup(MainWindow mw, String grantText) {
		CardData backup = makeBackupWithPowerGrant("Granter", "Ice", grantText);
		mw.gameState.getIdentity().put(backup, true);
		mw.p1BackupCards[0] = backup;
		return backup;
	}

	@Test
	void elementQualifiedJobGrantCapturesBothFilters() {
		List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(DGS_TROOPER_GRANT, "Forward");

		assertEquals(1, grants.size(), "the grant must parse — it used to fall between two patterns");
		FieldPowerGrant g = grants.get(0);
		assertEquals("Ice", g.elementFilter(), "the element prefix is captured, not discarded");
		assertEquals("Standard Unit", g.jobFilter(), "the Job filter survives the added element prefix");
		assertTrue(g.inclForwards(), "Forwards are the target type");
		assertFalse(g.inclBackups(), "Backups are not");
		assertEquals(2000, g.powerBonus());
	}

	@Test
	void elementQualifiedJobGrantRequiresBothFilters() {
		MainWindow mw = new MainWindow();
		placeP1GrantBackup(mw, DGS_TROOPER_GRANT);

		placeP1Forward(mw, makeJobCard("Trooper",  "Ice",  "Forward", "Standard Unit")); // idx 0
		placeP1Forward(mw, makeJobCard("Burner",   "Fire", "Forward", "Standard Unit")); // idx 1
		placeP1Forward(mw, makeJobCard("Iceblade", "Ice",  "Forward", "Dragoon"));       // idx 2

		assertEquals(9000, mw.effectiveP1ForwardPower(0), "Ice and Standard Unit — both filters hold");
		assertEquals(7000, mw.effectiveP1ForwardPower(1), "right Job, wrong Element — no boost");
		assertEquals(7000, mw.effectiveP1ForwardPower(2), "right Element, wrong Job — no boost");
	}

	@Test
	void aJobGrantWithNoElementPrefixStillMatchesEveryElement() {
		// Regression on the widening: the element group is optional, so the unqualified form must
		// keep granting across elements rather than picking one up from the surrounding text.
		MainWindow mw = new MainWindow();
		placeP1GrantBackup(mw, "The Job Standard Unit Forwards you control gain +2000 power.");

		placeP1Forward(mw, makeJobCard("Trooper", "Ice",  "Forward", "Standard Unit"));
		placeP1Forward(mw, makeJobCard("Burner",  "Fire", "Forward", "Standard Unit"));

		assertNull(CardData.parseFieldPowerGrants(
						"The Job Standard Unit Forwards you control gain +2000 power.", "Forward")
					.get(0).elementFilter(),
				"no element prefix means no element filter");
		assertEquals(9000, mw.effectiveP1ForwardPower(0));
		assertEquals(9000, mw.effectiveP1ForwardPower(1), "an unqualified Job grant is element-blind");
	}

	@Test
	void aJobNameBeginningWithAnElementWordIsNotClippedByThePrefix() {
		// The element group sits ahead of the "Job" keyword, so it can only ever consume a word
		// before it — a Job whose own name starts with an element stays intact.
		List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(
				"The Job Ice Warrior Forwards you control gain +1000 power.", "Forward");

		assertEquals(1, grants.size());
		assertEquals("Ice Warrior", grants.get(0).jobFilter(), "the Job name keeps its leading element word");
		assertNull(grants.get(0).elementFilter(), "and nothing was lifted out of it into the element filter");
	}

	// =========================================================================================
	// "The Card Name Serah you control cannot be chosen by your opponent's Summons or abilities."
	// (10-097R Noel, 19-134S Mog (XIII-2); siblings protect Sazh, Balthier and Madam Edel) — a
	// permanent targeting immunity handed to a *named* card with no "If you control" condition.
	// The engine's continuous immunity check reads IfControlBoost, so this is stored as one with
	// an empty conditions list — the same shape the unconditional "cannot be blocked" grant
	// already uses — rather than a second immunity mechanism the targeting code would have to
	// consult separately.
	// =========================================================================================

	private static final String NOEL_SERAH_SHIELD =
			"The Card Name Serah you control cannot be chosen by your opponent's Summons or abilities.";

	/** Builds a card whose field abilities and IfControlBoosts are both parsed from {@code text}. */
	private static CardData makeIcbCard(String name, String element, String type, String text) {
		return new CardData(null, name, element, 3, 7000, type, false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), CardData.parseFieldAbilities(text, type),
				CardData.parseIfControlBoosts(text, type),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	/** One ability-scoped selection of a single Forward on the opponent's side, driven by P2 (the AI). */
	private static List<ForwardTarget> p2ChoosesOneOpposingForward(MainWindow mw) {
		return mw.buildGameContext(false).selectCharacters(
				1, false, true, false, null, null, -1, null, -1, null,
				true, false, false, null, null, null, null, false, null, false);
	}

	/** The same selection aimed at P2's own Forwards — the side the effect's controller owns. */
	private static List<ForwardTarget> p2ChoosesOneOfItsOwnForwards(MainWindow mw) {
		return mw.buildGameContext(false).selectCharacters(
				1, false, false, true, null, null, -1, null, -1, null,
				true, false, false, null, null, null, null, false, null, false);
	}

	@Test
	void namedCannotBeChosenGrantParsesAsAnUnconditionalIcb() {
		List<IfControlBoost> boosts = CardData.parseIfControlBoosts(NOEL_SERAH_SHIELD, "Forward");

		assertEquals(1, boosts.size(), "the grant must parse — it produced nothing at all before");
		IfControlBoost icb = boosts.get(0);
		assertEquals("Serah", icb.targetCardName(), "the protection is pinned to the named card");
		assertTrue(icb.conditions().isEmpty(), "there is no \"If you control\" clause to satisfy");
		assertTrue(icb.cannotBeChosenBySummons());
		assertTrue(icb.cannotBeChosenByAbilities());
		assertEquals(0, icb.powerBonus(), "it grants no power");
	}

	@Test
	void aSingleScopeVariantSetsOnlyItsOwnFlag() {
		// 16-062C Lexa shields Madam Edel from Summons only; 5-157S Fran shields Balthier from
		// abilities only. Reading "or abilities" onto either would hand out protection the card
		// does not print.
		IfControlBoost summonsOnly = CardData.parseIfControlBoosts(
				"The Card Name Madam Edel you control cannot be chosen by your opponent's Summons.",
				"Backup").get(0);
		assertTrue(summonsOnly.cannotBeChosenBySummons());
		assertFalse(summonsOnly.cannotBeChosenByAbilities(), "Summons only — abilities still reach it");

		IfControlBoost abilitiesOnly = CardData.parseIfControlBoosts(
				"The Card Name Balthier you control cannot be chosen by your opponent's abilities.",
				"Forward").get(0);
		assertFalse(abilitiesOnly.cannotBeChosenBySummons(), "abilities only — Summons still reach it");
		assertTrue(abilitiesOnly.cannotBeChosenByAbilities());
	}

	@Test
	void theOpponentsAbilityCannotChooseTheProtectedCard() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Serah", "Ice", 3, 7000));                          // idx 0
		placeP1Forward(mw, makeIcbCard("Noel", "Ice", "Forward", NOEL_SERAH_SHIELD));      // idx 1

		List<ForwardTarget> chosen = p2ChoosesOneOpposingForward(mw);

		assertEquals(1, chosen.size(), "Noel is still a legal target, so the effect resolves");
		assertEquals(1, chosen.get(0).idx(), "the only Forward P2 may choose is the granter itself");
	}

	@Test
	void withoutTheGranterOnTheFieldSerahIsChoosableAgain() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Serah", "Ice", 3, 7000));

		List<ForwardTarget> chosen = p2ChoosesOneOpposingForward(mw);

		assertEquals(1, chosen.size(), "with nothing granting immunity Serah is an ordinary target");
		assertEquals(0, chosen.get(0).idx());
	}

	// -----------------------------------------------------------------------------------------
	// Scope: "by your opponent's Summons or abilities" protects against the opponent only. The
	// controller may still choose their own shielded card — targeting your own Forward with your
	// own buff is a normal play. Texts that omit the qualifier ("cannot be chosen by Summons")
	// keep blocking whoever is choosing, so the two scopes are tracked separately end to end.
	// -----------------------------------------------------------------------------------------

	/**
	 * 1-201S Rikku's second sentence — the one card in the corpus whose targeting immunity omits
	 * "your opponent's", so it really does bind both players.
	 */
	private static final String RIKKU_SYMMETRIC_SHIELD =
			"If you control Card Name Paine, Rikku gains +2000 power and "
			+ "Rikku cannot be chosen by abilities.";

	/** 12-037L Ashe — the same construction with the qualifier present. */
	private static final String ASHE_OPPONENT_SHIELD =
			"If you control 6 or more Characters, Ashe gains +2000 power and "
			+ "\"Ashe cannot be chosen by your opponent's abilities.\"";

	@Test
	void theQualifierDecidesWhetherTheImmunityIsOpponentScoped() {
		IfControlBoost opponentScoped = CardData.parseIfControlBoosts(ASHE_OPPONENT_SHIELD, "Forward").get(0);
		assertTrue(opponentScoped.cannotBeChosenByAbilities());
		assertTrue(opponentScoped.chosenImmunityOpponentOnly(),
				"\"by your opponent's\" scopes the protection to the other player");

		IfControlBoost symmetric = CardData.parseIfControlBoosts(RIKKU_SYMMETRIC_SHIELD, "Forward").get(0);
		assertTrue(symmetric.cannotBeChosenByAbilities());
		assertFalse(symmetric.chosenImmunityOpponentOnly(),
				"with no qualifier the protection applies to either player");
	}

	@Test
	void theControllerMayStillChooseTheirOwnOpponentShieldedCard() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Serah", "Ice", 3, 7000));
		placeP2Forward(mw, makeIcbCard("Noel", "Ice", "Forward", NOEL_SERAH_SHIELD));

		// P2 owns both the granter and Serah, and is the one choosing.
		List<ForwardTarget> chosen = p2ChoosesOneOfItsOwnForwards(mw);

		assertEquals(1, chosen.size(), "the shield names the opponent, not the controller");
		assertTrue(chosen.stream().anyMatch(t -> !t.isP1()), "and the pick comes off P2's own row");
	}

	@Test
	void anUnqualifiedImmunityStillBlocksTheControllerToo() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeIcbCard("Rikku", "Water", "Forward", RIKKU_SYMMETRIC_SHIELD)); // idx 0
		placeP2Forward(mw, makeForward("Paine", "Water", 3, 7000));                           // idx 1

		List<ForwardTarget> chosen = p2ChoosesOneOfItsOwnForwards(mw);

		assertEquals(1, chosen.size(), "Paine is unprotected and still eligible");
		assertEquals(1, chosen.get(0).idx(),
				"Rikku is not — an unqualified \"cannot be chosen\" binds her own controller as well");
	}

	@Test
	void bothHalvesOfASummonsOrAbilitiesScopeAreRecorded() {
		// 21-051R Tiamat prints both halves under one "cannot be chosen by". A Summons matcher and
		// an abilities matcher both anchored on that shared prefix cannot each claim their half,
		// and the abilities one lost every time — Tiamat was immune to opposing Summons but not to
		// opposing abilities.
		IfControlBoost icb = CardData.parseIfControlBoosts(
				"If you control 7 or more Wind Characters, Tiamat gains \"Tiamat cannot be chosen "
				+ "by your opponent's Summons or abilities.\" and \"If Tiamat is dealt damage by "
				+ "your opponent's Summons or abilities, the damage becomes 0 instead.\"",
				"Forward").get(0);

		assertTrue(icb.cannotBeChosenBySummons(), "the Summons half");
		assertTrue(icb.cannotBeChosenByAbilities(), "and the abilities half, which used to be dropped");
		assertTrue(icb.chosenImmunityOpponentOnly());
	}

	// -----------------------------------------------------------------------------------------
	// Redirects. "The newly chosen target must be a valid choice", so a redirect has to judge
	// eligibility by the same rule the original selection did. It was consulting only the
	// turn-scoped shields, which left a permanently protected card protected right up until
	// someone pointed a redirect at it.
	// -----------------------------------------------------------------------------------------

	@Test
	void redirectLegalityHonoursTheSameTwoScopes() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Serah", "Ice", 3, 7000));
		placeP1Forward(mw, makeIcbCard("Noel", "Ice", "Forward", NOEL_SERAH_SHIELD));
		CardData serah = mw.p1ForwardCards.get(0);

		assertTrue(mw.isProtectedFromChoice(serah, true, false, true, null),
				"an opposing Summon may not be redirected onto her");
		assertFalse(mw.isProtectedFromChoice(serah, true, true, true, null),
				"her own controller's Summon may — the grant names the opponent");
	}

	@Test
	void aPermanentlyShieldedCardCannotBeRedirectedInto() {
		MainWindow mw = new MainWindow();
		CardData edge = makeEdge();
		placeP1Forward(mw, edge);                                        // idx 0
		placeP1Forward(mw, makeForward("Yuffie", "Wind", 3, 7000));      // idx 1
		// A field ability, not a turn-scoped shield: this is the case the redirect path missed.
		placeP1Forward(mw, makeIcbCard("Rydia", "Wind", "Forward",
				"The Card Name Edge you control cannot be chosen by your opponent's Summons or abilities."));

		StackEntry entry = summonChoosing(makeForward("Ramuh", "Wind", 3, 0), false, fwd(true, 1));
		mw.gameState.pushStack(entry);

		ActionResolver.parse(edge.actionAbilities().get(0).effectText(), edge)
				.accept(mw.buildGameContext(true));

		assertEquals(List.of(fwd(true, 1)), mw.gameState.getStack().get(0).preSelectedTargets(),
				"Edge cannot be chosen by that Summon, so \"if possible\" fails and Yuffie keeps it");
	}

	@Test
	void theProtectionCoversOnlyTheGrantersOwnSideOfTheField() {
		// "you control" — P1's Noel must not shield a Serah sitting on P2's field.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeIcbCard("Noel", "Ice", "Forward", NOEL_SERAH_SHIELD));
		placeP2Forward(mw, makeForward("Serah", "Ice", 3, 7000));

		assertTrue(mw.icbGrantsImmunity("Serah", true, false),
				"a Serah on P1's field is covered by P1's Noel");
		assertFalse(mw.icbGrantsImmunity("Serah", false, false),
				"a Serah on P2's field is not — the grant is scoped to the granter's controller");
	}

	@Test
	void theGrantWorksFromTheBackupRowToo() {
		// Mog (XIII-2) prints the same line as a Backup, so the immunity must not be tied to the
		// granter being a Forward.
		MainWindow mw = new MainWindow();
		CardData mog = makeIcbCard("Mog (XIII-2)", "Ice", "Backup", NOEL_SERAH_SHIELD);
		mw.gameState.getIdentity().put(mog, true);
		mw.p1BackupCards[0] = mog;
		placeP1Forward(mw, makeForward("Serah", "Ice", 3, 7000));

		assertTrue(p2ChoosesOneOpposingForward(mw).isEmpty(),
				"Serah is P1's only Forward and she is shielded — P2's ability finds nothing");

		// The same line names Summons as well, and the two halves are separate sets in the
		// targeting code — an immunity that only landed in one of them would pass the check above.
		mw.currentResolutionIsSummon = true;
		assertTrue(p2ChoosesOneOpposingForward(mw).isEmpty(),
				"and an opposing Summon finds nothing either");
	}

	@Test
	void elementQualifiedCategoryGrantCapturesBothFilters() {
		// The prefix precedes the whole Job/Category alternation, so the Category branch gains it too.
		List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(
				"The Water Category VII Forwards you control gain +1000 power.", "Forward");

		assertEquals(1, grants.size());
		assertEquals("Water", grants.get(0).elementFilter());
		assertEquals("VII", grants.get(0).categoryFilter());
	}

	// =========================================================================================
	// Lightning 4-115L: "Remove Lightning from the game. Then, play Lightning onto the field
	// dull." — an immediate self-blink, as against the delayed "…at the end of the turn" form on
	// Lightning 16-124H. Neither sentence refers back to the other with a pronoun, so the
	// independent-sentence rule accepted them and resolved the replay through
	// PLAY_SOURCE_ONTO_FIELD_PATTERN, which reads the Break Zone. The removal in front of it had
	// just put the card in the RFG zone, so the replay found nothing and the ability was a
	// one-way exile. Both sentences are now claimed together and the replay reads the RFG zone.
	// =========================================================================================

	private static final String LIGHTNING_BLINK =
			"Remove Lightning from the game. Then, play Lightning onto the field dull.";

	@Test
	void lightningRemovesItselfAndComesStraightBackDull() {
		CardData lightning = makeForward("Lightning", "Lightning", 5, 7000);

		Consumer<GameContext> fn = ActionResolver.parse(LIGHTNING_BLINK, lightning);
		assertNotNull(fn);
		GameContext ctx = mock(GameContext.class);
		fn.accept(ctx);

		InOrder order = inOrder(ctx);
		order.verify(ctx).removeNamedCardFromGame("Lightning");
		// The RFG route, not the Break-Zone one — the card is in the RFG zone by this point.
		order.verify(ctx).playLastRemovedFromRfpOntoField(true);
		verify(ctx, never()).playAllByNameFromOwnBreakZoneDull(any(), anyBoolean());
	}

	@Test
	void theSelfBlinkIsClaimedAsOneAbilityRatherThanTwoSentences() {
		CardData lightning = makeForward("Lightning", "Lightning", 5, 7000);
		assertEquals("RemoveSelfThenPlaySelfOntoField",
				ActionResolver.matchedPatternName(LIGHTNING_BLINK, lightning));
		assertEquals("RemoveSelfThenPlaySelfOntoField",
				ActionResolver.fullDescription(LIGHTNING_BLINK, lightning),
				"all three chains have to agree, or the characterization file records a split");
	}

	@Test
	void theDelayedSelfBlinkIsLeftToItsOwnParser() {
		// Lightning 16-124H (Switch Schemata) shares the whole first sentence and most of the
		// second. Its replay is scheduled for the end phase and must not be pulled forward.
		CardData lightning = makeForward("Lightning", "Lightning", 3, 7000);
		assertEquals("RemoveNamedFromGame + EndOfTurnPlayNamedOntoField",
				ActionResolver.matchedPatternName(
						"Remove Lightning from the game. Play Lightning onto the field at the end of the turn.",
						lightning));
	}

	@Test
	void theBlinkOnlyClaimsTextWhereBothHalvesNameTheSource() {
		// "Remove X from the game. Play Y onto the field" is two effects on two cards, and the
		// RFG-top lookup this parser uses would hand the wrong card back.
		CardData lightning = makeForward("Lightning", "Lightning", 5, 7000);
		assertNotEquals("RemoveSelfThenPlaySelfOntoField",
				ActionResolver.matchedPatternName(
						"Remove Lightning from the game. Then, play Snow onto the field dull.", lightning));
	}

	@Test
	void theBlinkCarriesTheDullFlagOffTheCardText() {
		CardData snow = makeForward("Snow", "Ice", 4, 8000);
		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse("Remove Snow from the game. Then, play Snow onto the field.", snow)
				.accept(ctx);
		verify(ctx).playLastRemovedFromRfpOntoField(false);
	}

	@Test
	void aFourWordCardNameStillBlinks() {
		// Shiva, Lady of Frost 14-036L is the same ability without the "dull", and it fared worse
		// than Lightning: PLAY_SOURCE_ONTO_FIELD_PATTERN caps the card name at three words, so the
		// second sentence did not parse at all, the independent-sentence rule declined, and
		// RemoveNamedFromGame claimed the whole text with find(). The replay was dropped outright.
		CardData shiva = makeForward("Shiva, Lady of Frost", "Ice", 5, 9000);
		String effect = "Remove Shiva, Lady of Frost from the game. "
				+ "Then, play Shiva, Lady of Frost onto the field.";
		assertEquals("RemoveSelfThenPlaySelfOntoField",
				ActionResolver.matchedPatternName(effect, shiva));

		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(effect, shiva).accept(ctx);
		verify(ctx).removeNamedCardFromGame("Shiva, Lady of Frost");
		verify(ctx).playLastRemovedFromRfpOntoField(false);
	}

	// =========================================================================================
	// Dio 26-075C: "Choose 1 Forward. It gains "This Forward must block Dio if possible." until
	// the end of the turn." The followup was unrecognised, so the ability chose a Forward and did
	// nothing at all. It is the blocker-side mirror of "Opponent must block [X] if possible":
	// that one sits on the attacker and compels any eligible blocker, this one sits on a single
	// Forward and compels only that Forward, and only against the named attacker — so the two
	// share neither the pattern nor the enforcement. The grant rides on grantedFieldAbilities,
	// which is keyed by card instance and dropped at end of turn.
	// =========================================================================================

	private static final String DIO_MUST_BLOCK = "Choose 1 Forward. It gains "
			+ "\"This Forward must block Dio if possible.\" until the end of the turn.";

	/** A board where P2's Dio attacks, P1 holds a compelled Blocker and an unaffected Bystander. */
	private static MainWindow dioCompelsABlocker() {
		MainWindow mw = new MainWindow();
		enterAttackDeclarationStep(mw, false);
		CardData dio = makeForward("Dio", "Earth", 3, 7000);
		mw.placeP2CardInForwardZone(dio);
		mw.gameState.getIdentity().put(dio, false);
		CardData blocker = makeForward("Blocker", "Ice", 3, 8000);
		mw.placeCardInForwardZone(blocker);
		mw.gameState.getIdentity().put(blocker, true);
		CardData bystander = makeForward("Bystander", "Ice", 3, 8000);
		mw.placeCardInForwardZone(bystander);
		mw.gameState.getIdentity().put(bystander, true);

		mw.buildGameContext(true).grantFieldAbilityUntilEndOfTurn(
				new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD),
				"This Forward must block Dio if possible.");
		mw.pendingP2Attacker = dio;
		return mw;
	}

	@Test
	void dioGrantsTheMustBlockAbilityToTheChosenForward() {
		CardData dio = makeForward("Dio", "Earth", 3, 7000);
		assertEquals("MustBlockNamed", ActionResolver.matchedFollowupName(
				"It gains \"This Forward must block Dio if possible.\" until the end of the turn.", dio));

		ForwardTarget chosen = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(new ArrayList<>(List.of(chosen)));
		ActionResolver.parse(DIO_MUST_BLOCK, dio).accept(ctx);

		// The text is stored verbatim, because the block rules match it as a printed field ability.
		verify(ctx).grantFieldAbilityUntilEndOfTurn(chosen, "This Forward must block Dio if possible.");
	}

	@Test
	void theGrantedCompulsionReadsBackThroughTheEffectiveView() {
		MainWindow mw = dioCompelsABlocker();
		CardData blocker = mw.p1ForwardCards.get(0);
		CardData dio     = mw.p2ForwardCards.get(0);

		assertTrue(blocker.fieldAbilities().isEmpty(), "nothing is printed on the card itself");
		assertTrue(mw.forwardCompelledToBlock(blocker, dio),
				"the compulsion is only visible through the effective-abilities view");
	}

	@Test
	void theCompelledForwardIsTheOnlyLegalBlockerAgainstDio() {
		MainWindow mw = dioCompelsABlocker();

		assertTrue(mw.isForwardBlockSelectable(0), "the compelled Forward must be declarable");
		assertFalse(mw.isForwardBlockSelectable(1),
				"and it is the only one — declaring the bystander would dodge the compulsion");
	}

	@Test
	void theCompulsionOnlyBitesAgainstTheNamedAttacker() {
		MainWindow mw = dioCompelsABlocker();
		CardData other = makeForward("Palmer", "Earth", 2, 5000);
		mw.placeP2CardInForwardZone(other);
		mw.gameState.getIdentity().put(other, false);
		mw.pendingP2Attacker = other;

		assertFalse(mw.forwardCompelledToBlock(mw.p1ForwardCards.get(0), other),
				"\"must block Dio\" says nothing about anyone else attacking");
		assertTrue(mw.isForwardBlockSelectable(1),
				"so the bystander is free to block a different attacker");
	}

	@Test
	void ifPossibleLiftsTheCompulsionWhenTheCompelledForwardCannotBlock() {
		MainWindow mw = dioCompelsABlocker();
		mw.p1ForwardStates.set(0, CardState.DULL);

		assertFalse(mw.isForwardBlockSelectable(0), "a dull Forward cannot block at all");
		assertTrue(mw.isForwardBlockSelectable(1),
				"\"if possible\" — an impossible compulsion frees the rest of the board rather "
						+ "than locking the block step");
	}

	@Test
	void theCompulsionExpiresAtEndOfTurn() {
		MainWindow mw = dioCompelsABlocker();
		CardData blocker = mw.p1ForwardCards.get(0);
		CardData dio     = mw.p2ForwardCards.get(0);
		assertTrue(mw.forwardCompelledToBlock(blocker, dio));

		for (Consumer<GameContext> eot : new ArrayList<>(mw.endOfTurnEffects))
			eot.accept(mw.buildGameContext(true));

		assertFalse(mw.forwardCompelledToBlock(blocker, dio),
				"\"until the end of the turn\" — the grant does not carry into the next turn");
	}

	@Test
	void theCompulsionFollowsTheCardRatherThanTheSlot() {
		MainWindow mw = new MainWindow();
		enterAttackDeclarationStep(mw, false);
		CardData dio = makeForward("Dio", "Earth", 3, 7000);
		mw.placeP2CardInForwardZone(dio);
		mw.gameState.getIdentity().put(dio, false);
		CardData doomed  = makeForward("Doomed", "Ice", 3, 8000);
		CardData blocker = makeForward("Blocker", "Ice", 3, 8000);
		mw.placeCardInForwardZone(doomed);    // slot 0
		mw.placeCardInForwardZone(blocker);   // slot 1
		mw.gameState.getIdentity().put(doomed, true);
		mw.gameState.getIdentity().put(blocker, true);
		mw.buildGameContext(true).grantFieldAbilityUntilEndOfTurn(
				new ForwardTarget(true, 1, ForwardTarget.CardZone.FORWARD),
				"This Forward must block Dio if possible.");

		// The Forward below it leaves; every survivor shifts down a slot.
		mw.breakP1Forward(0);

		assertSame(blocker, mw.p1ForwardCards.get(0));
		assertTrue(mw.forwardCompelledToBlock(blocker, dio),
				"the grant is keyed by instance, so no slot re-indexing can lose it");
	}

	@Test
	void theCompulsionDoesNotLandOnAnUnrelatedForward() {
		MainWindow mw = dioCompelsABlocker();
		assertFalse(mw.forwardCompelledToBlock(mw.p1ForwardCards.get(1), mw.p2ForwardCards.get(0)),
				"only the chosen Forward is compelled");
	}

	// =========================================================================================
	// "Opponent must block [X] if possible." OpponentController.requestBlocker has carried a
	// forcedBlock parameter all along, and ComputerPlayer acts on it — but no call site ever
	// passed it true, so the compulsion was enforced only when the human was the one blocking.
	// Attacking into the AI with such a Forward, it simply declined the block and took the
	// damage. MainWindow now computes the flag at all four block-request sites (Forward, Monster
	// and Backup attackers, plus the party), and the party request gained the parameter its
	// single-attacker sibling already had.
	// =========================================================================================

	/** A P1 Forward whose printed text compels the opponent to block it. */
	private static CardData makeMustBeBlockedAttacker(String name) {
		return makeFieldAbilityCard(name, "Fire", "Forward",
				"Opponent must block " + name + " if possible.");
	}

	@Test
	void theMustBeBlockedRuleReadsThePrintedAbility() {
		MainWindow mw = new MainWindow();
		CardData bully = makeMustBeBlockedAttacker("Bully");
		assertTrue(mw.attackerMustBeBlocked(bully));
		assertFalse(mw.attackerMustBeBlocked(makeForward("Meek", "Fire", 3, 7000)));
	}

	@Test
	void theMustBeBlockedRuleAlsoSeesAGrantedCopy() {
		// The check read CardData.fieldAbilities() directly, so a granted copy of the same text
		// was invisible to it — the one thing effectiveFieldAbilities exists to prevent.
		MainWindow mw = new MainWindow();
		CardData bully = makeForward("Bully", "Fire", 3, 7000);
		mw.placeCardInForwardZone(bully);
		assertFalse(mw.attackerMustBeBlocked(bully), "nothing printed on the card");

		mw.buildGameContext(true).grantSelfFieldAbilityUntilEndOfTurn(
				bully, "Opponent must block Bully if possible.");

		assertTrue(mw.attackerMustBeBlocked(bully), "a grant compels the block exactly as printing it would");
	}

	/** P1 attacks with {@code attackerPower}; P2 holds two Forwards, neither able to survive it. */
	private static MainWindow doomedBlockerBoard(int attackerPower) {
		MainWindow mw = new MainWindow();
		mw.placeCardInForwardZone(makeForward("Attacker", "Fire", 5, attackerPower));
		mw.placeP2CardInForwardZone(makeForward("Sturdy", "Ice", 3, 5000));
		mw.placeP2CardInForwardZone(makeForward("Frail",  "Ice", 2, 3000));
		return mw;
	}

	@Test
	void theAiDeclinesAHopelessBlockWhenItIsNotForced() {
		MainWindow mw = doomedBlockerBoard(9000);
		ComputerPlayer cp = new ComputerPlayer(mw);
		ForwardTarget attacker = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);

		assertNull(cp.chooseBlocker(9000, attacker, false),
				"no Forward survives, so taking the damage is the better play");
	}

	@Test
	void theAiBlocksAHopelessAttackWhenItIsForced() {
		MainWindow mw = doomedBlockerBoard(9000);
		ComputerPlayer cp = new ComputerPlayer(mw);
		ForwardTarget attacker = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);

		ForwardTarget chosen = cp.chooseBlocker(9000, attacker, true);
		assertNotNull(chosen, "declining is not on offer once the compulsion applies");
		assertEquals(1, chosen.idx(), "and the cheapest loss is the weakest Forward");
	}

	@Test
	void aForcedBlockStillPrefersASurvivorWhenThereIsOne() {
		MainWindow mw = doomedBlockerBoard(4000);   // Sturdy (5000) now survives
		ComputerPlayer cp = new ComputerPlayer(mw);
		ForwardTarget attacker = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);

		ForwardTarget chosen = cp.chooseBlocker(4000, attacker, true);
		assertEquals(0, chosen.idx(),
				"the weakest-Forward fallback is for hopeless blocks only, not a blanket rule");
	}

	@Test
	void aForcedPartyBlockAlsoThrowsTheWeakestForwardInFront() {
		MainWindow mw = new MainWindow();
		mw.placeCardInForwardZone(makeForward("Party A", "Fire", 3, 7000));
		mw.placeCardInForwardZone(makeForward("Party B", "Fire", 3, 6000));
		mw.placeP2CardInForwardZone(makeForward("Sturdy", "Ice", 3, 4000));
		mw.placeP2CardInForwardZone(makeForward("Frail",  "Ice", 2, 2000));
		ComputerPlayer cp = new ComputerPlayer(mw);

		List<Integer> party = List.of(0, 1);
		List<Integer> answer = new ArrayList<>();
		cp.requestPartyBlocker(party, 13000, false, chosen -> answer.add(chosen));
		assertEquals(1, answer.size(), "the callback answers exactly once either way");
		assertNull(answer.get(0),
				"neither Forward survives the weakest party member, so the AI takes the damage");

		answer.clear();
		cp.requestPartyBlocker(party, 13000, true, chosen -> answer.add(chosen));
		assertEquals(List.of(1), answer, "forced, it blocks with the weakest Forward");
	}

	@Test
	void thePartyBlockRequestCarriesTheCompulsionFromAnySingleMember() {
		// Blocking a party means blocking every member, so one member with the ability is enough.
		MainWindow mw = new MainWindow();
		CardData plain = makeForward("Plain", "Fire", 3, 7000);
		CardData bully = makeMustBeBlockedAttacker("Bully");
		mw.placeCardInForwardZone(plain);
		mw.placeCardInForwardZone(bully);

		assertFalse(mw.attackerMustBeBlocked(mw.p1ForwardCards.get(0)));
		assertTrue(mw.attackerMustBeBlocked(mw.p1ForwardCards.get(1)));
	}

	// =========================================================================================
	// Hyoh 16-097H, a 1-cost 3000-power Forward that pumps itself twice:
	//   《Lightning》: Hyoh gains Haste and Hyoh's power becomes 7000.
	//   《L》《L》《L》: Hyoh gains "If Hyoh deals damage to your opponent, the damage becomes 2
	//                  instead." and Hyoh's power becomes 10000. You can only use this ability if
	//                  Hyoh has 7000 power or more.
	//   (These effects do not end at the end of the turn.)
	// Neither ability parsed at all. The wording states no duration, which in this game means the
	// effect lasts while the card is on the field — the parenthetical on the printed card is a
	// reminder of that rule, not a separate effect, and Hyoh's is a card-level line that never
	// reaches the resolver anyway. So permanence here comes from the absence of "until the end of
	// the turn", which is what separates this from SelfBasePowerBecomesUntil (Bartz 29-052H).
	// =========================================================================================

	private static final String HYOH_PUMP_1 = "Hyoh gains Haste and Hyoh's power becomes 7000.";
	private static final String HYOH_PUMP_2 =
			"Hyoh gains \"If Hyoh deals damage to your opponent, the damage becomes 2 instead.\" "
			+ "and Hyoh's power becomes 10000. "
			+ "You can only use this ability if Hyoh has 7000 power or more.";
	private static final String HYOH_CARD_TEXT =
			"《Lightning》: " + HYOH_PUMP_1 + "[[br]]《Lightning》《Lightning》《Lightning》: " + HYOH_PUMP_2;

	private static MainWindow hyohOnField() {
		MainWindow mw = new MainWindow();
		// canActivateAbility reads live turn state, so the board needs a phase to sit in,
		// and it ends on an affordability check — three active Lightning Backups cover the
		// costlier of Hyoh's two abilities.
		advanceTo(mw, GameState.Player.P1, GameState.GamePhase.MAIN_1);
		for (int i = 0; i < 3; i++) {
			mw.p1BackupCards[i]  = makePlainBackup("Sparker " + i, "Lightning", 2);
			mw.p1BackupStates[i] = CardState.ACTIVE;
		}
		mw.placeCardInForwardZone(makeForward("Hyoh", "Lightning", 1, 3000,
				CardData.parseActionAbilities(HYOH_CARD_TEXT)));
		return mw;
	}

	@Test
	void hyohsFirstAbilityGrantsHasteAndSetsBasePower() {
		CardData hyoh = makeForward("Hyoh", "Lightning", 1, 3000);
		assertEquals("SelfGainsAndBasePowerBecomesPermanent",
				ActionResolver.matchedPatternName(HYOH_PUMP_1, hyoh));

		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(HYOH_PUMP_1, hyoh).accept(ctx);
		verify(ctx).setSourceForwardBasePowerPermanently(
				hyoh, 7000, EnumSet.of(CardData.Trait.HASTE));
		// Not the end-of-turn primitive — that is the whole difference between the two wordings.
		verify(ctx, never()).setSourceForwardBasePower(any(), anyInt(), any());
	}

	@Test
	void hyohsSecondAbilityGrantsTheDamageSetterAndSetsBasePower() {
		CardData hyoh = makeForward("Hyoh", "Lightning", 1, 3000);
		assertEquals("SelfGainsAndBasePowerBecomesPermanent",
				ActionResolver.matchedPatternName(HYOH_PUMP_2, hyoh),
				"the trailing use-restriction sentence must not defeat the end anchor");

		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(HYOH_PUMP_2, hyoh).accept(ctx);
		// Granted verbatim: DamageResolver matches this exact wording off the effective view.
		verify(ctx).grantSelfFieldAbilityPermanently(hyoh,
				"If Hyoh deals damage to your opponent, the damage becomes 2 instead.");
		verify(ctx).setSourceForwardBasePowerPermanently(
				hyoh, 10000, EnumSet.noneOf(CardData.Trait.class));
	}

	@Test
	void theUntilEndOfTurnWordingStillRoutesToTheTemporaryPrimitive() {
		// Bartz 29-052H prints the same shape with a duration, and must not become permanent.
		CardData bartz = makeForward("Bartz", "Wind", 5, 7000);
		String eot = "Until the end of the turn, Bartz gains Haste and Bartz's power becomes 9000.";
		assertEquals("SelfBasePowerBecomesUntil", ActionResolver.matchedPatternName(eot, bartz));

		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(eot, bartz).accept(ctx);
		verify(ctx).setSourceForwardBasePower(bartz, 9000, EnumSet.of(CardData.Trait.HASTE));
		verify(ctx, never()).setSourceForwardBasePowerPermanently(any(), anyInt(), any());
	}

	@Test
	void bothOfHyohsGrantsSurviveTheEndOfTurn() {
		MainWindow mw = hyohOnField();
		CardData hyoh = mw.p1ForwardCards.get(0);
		ActionResolver.parse(HYOH_PUMP_1, hyoh).accept(mw.buildGameContext(true));
		ActionResolver.parse(HYOH_PUMP_2, hyoh).accept(mw.buildGameContext(true));

		assertEquals(10000, mw.effectiveP1ForwardPower(0));
		assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.HASTE));
		assertEquals(Integer.valueOf(2), mw.damageResolver.outgoingDamageToOpponentOverride(hyoh));

		for (Consumer<GameContext> e : new ArrayList<>(mw.endOfTurnEffects))
			e.accept(mw.buildGameContext(true));

		assertEquals(10000, mw.effectiveP1ForwardPower(0),
				"\"do not end at the end of the turn\" — the base power stays replaced");
		assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.HASTE), "and so does Haste");
		assertEquals(Integer.valueOf(2), mw.damageResolver.outgoingDamageToOpponentOverride(hyoh),
				"and so does the granted damage setter");
	}

	@Test
	void hyohsGrantsAreDroppedWhenHeLeavesTheField() {
		MainWindow mw = hyohOnField();
		CardData hyoh = mw.p1ForwardCards.get(0);
		ActionResolver.parse(HYOH_PUMP_2, hyoh).accept(mw.buildGameContext(true));
		assertEquals(Integer.valueOf(2), mw.damageResolver.outgoingDamageToOpponentOverride(hyoh));

		mw.clearPermanentGrants(hyoh);

		assertNull(mw.damageResolver.outgoingDamageToOpponentOverride(hyoh),
				"a Character that leaves the field loses everything granted to it");
		assertFalse(mw.basePowerOverrides.containsKey(hyoh),
				"including the replaced base power, which has no end-of-turn hook to remove it");
	}

	@Test
	void theGrantedDamageSetterReadsBackThroughTheEffectiveView() {
		MainWindow mw = hyohOnField();
		CardData hyoh = mw.p1ForwardCards.get(0);
		assertTrue(hyoh.fieldAbilities().isEmpty(), "nothing is printed on the card itself");

		ActionResolver.parse(HYOH_PUMP_2, hyoh).accept(mw.buildGameContext(true));

		assertEquals(1, mw.effectiveFieldAbilities(hyoh).size(),
				"the permanent grant joins the same view the end-of-turn ones use");
	}

	// --- The "if Hyoh has 7000 power or more" activation gate -------------------------------

	@Test
	void hyohsSecondAbilityCarriesThePowerRestriction() {
		List<ActionAbility> abilities = CardData.parseActionAbilities(HYOH_CARD_TEXT);
		assertEquals(2, abilities.size());
		assertEquals(0, abilities.get(0).requiresSelfPowerAtLeast(), "the first ability is ungated");
		assertEquals(7000, abilities.get(1).requiresSelfPowerAtLeast());
	}

	@Test
	void theSecondAbilityIsLockedUntilTheFirstOneHasRunAndThenOpens() {
		MainWindow mw = hyohOnField();
		CardData hyoh = mw.p1ForwardCards.get(0);
		ActionAbility gated = hyoh.actionAbilities().get(1);

		assertFalse(mw.canActivateAbility(gated, false, CardState.ACTIVE, 0, hyoh, true),
				"printed power is 3000 — the 3-Lightning ability is out of reach");

		ActionResolver.parse(HYOH_PUMP_1, hyoh).accept(mw.buildGameContext(true));

		assertTrue(mw.canActivateAbility(gated, false, CardState.ACTIVE, 0, hyoh, true),
				"the gate reads current power, which is the point — the first ability unlocks the second");
	}

	@Test
	void thePowerGateIsIgnoredByAbilitiesThatDoNotCarryIt() {
		MainWindow mw = hyohOnField();
		CardData hyoh = mw.p1ForwardCards.get(0);
		assertTrue(mw.canActivateAbility(hyoh.actionAbilities().get(0), false, CardState.ACTIVE, 0, hyoh, true),
				"a 3000-power Hyoh can still use the ungated ability");
	}

	@Test
	void theRestrictionSentenceIsStrippedFromTheEffectText() {
		// Left in place it would split the ability into two sentences and defeat the end anchor.
		assertEquals("Hyoh gains \"If Hyoh deals damage to your opponent, the damage becomes 2 "
				+ "instead.\" and Hyoh's power becomes 10000",
				ActionResolver.stripRestrictionSentences(HYOH_PUMP_2));
	}

	// Ramza 16-017R prints the trait half of the same wording and was equally unparsed.
	@Test
	void theSameWordingOnRamzaIsAlsoClaimed() {
		CardData ramza = makeForward("Ramza", "Lightning", 3, 5000);
		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse("Ramza gains Haste and Ramza's power becomes 9000.", ramza).accept(ctx);
		verify(ctx).setSourceForwardBasePowerPermanently(
				ramza, 9000, EnumSet.of(CardData.Trait.HASTE));
	}

	// Roche 29-076H and Young Excenmille 23-100L print the same shape with the reminder spelled
	// out inline. The reminder is accepted, but their quoted clauses have no permanent grant
	// primitive, so the parser declines rather than applying the power half on its own.
	@Test
	void theInlineReminderIsAcceptedWhenTheQuotedClauseIsSupported() {
		CardData caius = makeForward("Caius", "Fire", 2, 9000);
		GameContext ctx = mock(GameContext.class);
		Consumer<GameContext> fn = ActionResolver.parse(
				"Caius gains \"If Caius deals damage to a Forward or your opponent, double the "
				+ "damage instead.\" and Caius's power becomes 12000. "
				+ "(This effect does not end at the end of the turn.)", caius);
		assertNotNull(fn, "the trailing reminder is the same effect, not a different one");
		fn.accept(ctx);
		verify(ctx).setSourceForwardBasePowerPermanently(
				caius, 12000, EnumSet.noneOf(CardData.Trait.class));
	}

	@Test
	void anUnsupportedQuotedClauseDeclinesRatherThanApplyingHalfTheAbility() {
		// "cannot be blocked by Forwards of cost N or less" has an end-of-turn grant primitive but
		// no permanent one, so granting it here would be silently inert. The parser declines the
		// whole match rather than applying the power half on its own — half an ability is worse
		// than an unparsed one, and an unparsed one is at least visible in the coverage report.
		CardData vaan = makeForward("Vaan", "Wind", 3, 7000);
		String text = "Vaan gains \"Vaan cannot be blocked by Forwards of cost 2 or less.\" and "
				+ "Vaan's power becomes 9000. (This effect does not end at the end of the turn.)";
		assertNull(ActionResolverFieldAbility.tryParseSelfGainsAndBasePowerBecomesPermanent(text, vaan));
	}

	// =========================================================================================
	// The two clauses the permanent self-grant used to decline, because neither is a field
	// ability any rule reads: targeting immunity lives in dedicated sets, and the must-attack
	// compulsion in an index set. Both now have permanent primitives, so the whole ability lands
	// rather than the parser refusing it and leaving the power half unapplied too.
	//
	//   23-100L Young Excenmille — 《C》: … gains "… cannot be chosen by your opponent's
	//                             abilities." and … power becomes 9000.
	//   29-076H Roche           — Remove 1 Card Name Roche in your Break Zone from the game:
	//                             … gains "Roche must attack once per turn if possible." and
	//                             … power becomes 9000.
	//
	// Roche's half needed the must-attack rule built from nothing: p1ForwardMustAttack was
	// written by the choose chain and re-indexed on every removal path, but never read, so "it
	// must attack this turn if possible" had no effect whatsoever. Both sources now feed one
	// check at the attack-phase exit.
	// =========================================================================================

	private static final String EXCENMILLE_PUMP =
			"Young Excenmille gains \"Young Excenmille cannot be chosen by your opponent's "
			+ "abilities.\" and Young Excenmille's power becomes 9000. "
			+ "(This effect does not end at the end of the turn.)";
	private static final String ROCHE_PUMP =
			"Roche gains \"Roche must attack once per turn if possible.\" and Roche's power "
			+ "becomes 9000. (This effect does not end at the end of the turn.)";

	@Test
	void excenmilleShieldsHimselfAndSetsHisPower() {
		CardData ye = makeForward("Young Excenmille", "Water", 5, 5000);
		assertEquals("SelfGainsAndBasePowerBecomesPermanent",
				ActionResolver.matchedPatternName(EXCENMILLE_PUMP, ye),
				"CannotBeChosen used to claim this off the quoted clause and drop the power half");

		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(EXCENMILLE_PUMP, ye).accept(ctx);
		verify(ctx).shieldSelfCannotBeChosenPermanently(ye, false, true);
		verify(ctx).setSourceForwardBasePowerPermanently(ye, 9000, EnumSet.noneOf(CardData.Trait.class));
	}

	@Test
	void rocheTakesTheCompulsionAndSetsHisPower() {
		CardData roche = makeForward("Roche", "Fire", 4, 6000);
		assertEquals("SelfGainsAndBasePowerBecomesPermanent",
				ActionResolver.matchedPatternName(ROCHE_PUMP, roche));

		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(ROCHE_PUMP, roche).accept(ctx);
		verify(ctx).grantSelfMustAttackOncePerTurnPermanently(roche);
		verify(ctx).setSourceForwardBasePowerPermanently(roche, 9000, EnumSet.noneOf(CardData.Trait.class));
	}

	// --- Young Excenmille's shield ----------------------------------------------------------

	private static MainWindow excenmilleShielded() {
		MainWindow mw = new MainWindow();
		CardData ye = makeForward("Young Excenmille", "Water", 5, 5000);
		mw.placeCardInForwardZone(ye);
		ActionResolver.parse(EXCENMILLE_PUMP, ye).accept(mw.buildGameContext(true));
		return mw;
	}

	@Test
	void theShieldStopsTheOpponentsAbilitiesButNotTheirSummons() {
		MainWindow mw = excenmilleShielded();
		CardData ye = mw.p1ForwardCards.get(0);

		assertTrue(mw.isProtectedFromChoice(ye, true, false, false, null),
				"an opponent's ability cannot choose him");
		assertFalse(mw.isProtectedFromChoice(ye, true, false, true, null),
				"the clause names abilities only — Summons still reach him");
	}

	@Test
	void theShieldDoesNotStopHisOwnControllersAbilities() {
		MainWindow mw = excenmilleShielded();
		assertFalse(mw.isProtectedFromChoice(mw.p1ForwardCards.get(0), true, true, false, null),
				"\"your opponent's abilities\" — his own side may still target him");
	}

	@Test
	void theShieldAndThePowerBothSurviveTheEndOfTurn() {
		MainWindow mw = excenmilleShielded();
		CardData ye = mw.p1ForwardCards.get(0);
		assertEquals(9000, mw.effectiveP1ForwardPower(0));

		for (Consumer<GameContext> e : new ArrayList<>(mw.endOfTurnEffects))
			e.accept(mw.buildGameContext(true));
		// The turn-scoped shield sets are emptied wholesale at the boundary; the permanent ones
		// are a separate pair precisely so that does not take this grant with it.
		mw.cannotBeChosenByAbilities.clear();

		assertTrue(mw.isProtectedFromChoice(ye, true, false, false, null));
		assertEquals(9000, mw.effectiveP1ForwardPower(0));
	}

	@Test
	void theShieldIsDroppedWhenHeLeavesTheField() {
		MainWindow mw = excenmilleShielded();
		CardData ye = mw.p1ForwardCards.get(0);
		mw.clearPermanentGrants(ye);
		assertFalse(mw.isProtectedFromChoice(ye, true, false, false, null));
	}

	// --- Roche's compulsion, and the must-attack rule it required ---------------------------

	/** P1's attack phase, declaration sub-step, with Roche on the field under his compulsion. */
	private static MainWindow rocheCompelled() {
		MainWindow mw = new MainWindow();
		enterP1AttackPhase(mw);
		mw.attackSubStep = 1;   // declaration sub-step
		CardData roche = makeForward("Roche", "Fire", 4, 6000);
		mw.placeCardInForwardZone(roche);
		mw.p1ForwardPlayedOnTurn.set(0, 0);   // not summoning-sick
		ActionResolver.parse(ROCHE_PUMP, roche).accept(mw.buildGameContext(true));
		return mw;
	}

	@Test
	void rocheMustAttackAndTheCompulsionClearsOnceHeHas() {
		MainWindow mw = rocheCompelled();
		CardData roche = mw.p1ForwardCards.get(0);

		assertEquals(0, mw.p1ForwardCompelledToAttackIdx(),
				"the phase cannot be left while he can still attack");

		mw.recordAttackDeclared(roche);

		assertEquals(-1, mw.p1ForwardCompelledToAttackIdx(),
				"\"once per turn\" — one attack satisfies it for this turn");
	}

	@Test
	void theCompulsionReArmsOnTheFollowingTurn() {
		MainWindow mw = rocheCompelled();
		mw.recordAttackDeclared(mw.p1ForwardCards.get(0));
		assertEquals(-1, mw.p1ForwardCompelledToAttackIdx());

		// A new turn resets the attack counts; the compulsion itself is not turn-scoped.
		mw.attacksMadeThisTurn.clear();

		assertEquals(0, mw.p1ForwardCompelledToAttackIdx(),
				"it is a standing rule, not a one-turn instruction");
	}

	@Test
	void ifPossibleLiftsTheCompulsionWhenRocheCannotAttack() {
		MainWindow mw = rocheCompelled();
		mw.p1ForwardStates.set(0, CardState.DULL);
		assertEquals(-1, mw.p1ForwardCompelledToAttackIdx(),
				"a dull Forward cannot attack, so the compulsion must not strand the attack phase");
	}

	@Test
	void theCompulsionIsDroppedWhenRocheLeavesTheField() {
		MainWindow mw = rocheCompelled();
		CardData roche = mw.p1ForwardCards.get(0);
		mw.clearPermanentGrants(roche);
		assertEquals(-1, mw.p1ForwardCompelledToAttackIdx());
	}

	@Test
	void theOneTurnMustAttackInstructionIsNowHonouredToo() {
		// p1ForwardMustAttack was populated by the choose chain and re-indexed on every removal
		// path, but nothing ever read it — "it must attack this turn if possible" did nothing.
		MainWindow mw = new MainWindow();
		enterP1AttackPhase(mw);
		mw.attackSubStep = 1;   // declaration sub-step
		mw.placeCardInForwardZone(makeForward("Zell", "Fire", 3, 7000));
		mw.p1ForwardPlayedOnTurn.set(0, 0);

		assertEquals(-1, mw.p1ForwardCompelledToAttackIdx(), "no instruction yet");

		mw.p1ForwardMustAttack.add(0);

		assertEquals(0, mw.p1ForwardCompelledToAttackIdx());
	}

	// =========================================================================================
	// Sarah (MOBIUS) 16-115H: "When you gain a 《C》, reveal the top card of your deck. If it is a
	// Backup, add it to your hand. If it is not a Backup, put it at the top or bottom of your
	// deck." The trigger did not exist — AUTO_ABILITY_PATTERN's alternation had no crystal-gain
	// phrasing, so the whole sentence fell out of the card and only her ETB survived parsing.
	//
	// It fires once per Crystal rather than once per effect: an ability handing over 《C》《C》
	// meets "gain a 《C》" twice. That follows the closest analogue already in the engine — a
	// multi-point damage effect fires "you receive damage" per point, because each point is dealt
	// as its own action. Nine cards in the corpus gain two Crystals at once, so the distinction
	// is real rather than theoretical.
	// =========================================================================================

	private static final String SARAH_MOBIUS_TEXT =
			"When Sarah (MOBIUS) enters the field, gain 《C》.[[br]]   "
			+ "When you gain a 《C》, reveal the top card of your deck. If it is a Backup, add it "
			+ "to your hand. If it is not a Backup, put it at the top or bottom of your deck.";
	private static final String SARAH_REVEAL =
			"reveal the top card of your deck. If it is a Backup, add it to your hand. "
			+ "If it is not a Backup, put it at the top or bottom of your deck.";

	@Test
	void sarahsCrystalTriggerIsParsedAsItsOwnAutoAbility() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(SARAH_MOBIUS_TEXT);
		assertEquals(2, autos.size(), "the crystal-gain sentence used to be dropped entirely");

		AutoAbility onGain = autos.stream()
				.filter(a -> "gain crystal".equals(a.trigger())).findFirst().orElse(null);
		assertNotNull(onGain, "the trigger vocabulary had no crystal-gain phrasing at all");
		assertEquals("you", onGain.triggerCard(), "the trigger watches the player, not a card");
		assertTrue(onGain.effectText().toLowerCase().startsWith("reveal the top card"));
	}

	@Test
	void sarahsRevealResolvesAsOneConditionalEffect() {
		CardData sarah = makeForward("Sarah (MOBIUS)", "Water", 3, 7000);
		assertEquals("RevealTopToHandIfTypeElseTopOrBottom",
				ActionResolver.matchedPatternName(SARAH_REVEAL, sarah));

		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(SARAH_REVEAL, sarah).accept(ctx);
		verify(ctx).revealTopAddToHandIfType("Backup");
	}

	@Test
	void theTwoBranchesMustNameTheSameType() {
		// A text that keeps one type and misses on another is a different effect; the
		// back-reference is what stops this parser claiming it.
		CardData sarah = makeForward("Sarah (MOBIUS)", "Water", 3, 7000);
		assertNull(ActionResolverSearch.tryParseRevealTopToHandIfTypeElseTopOrBottom(
				"Reveal the top card of your deck. If it is a Backup, add it to your hand. "
				+ "If it is not a Forward, put it at the top or bottom of your deck."));
		assertNotNull(ActionResolverSearch.tryParseRevealTopToHandIfTypeElseTopOrBottom(SARAH_REVEAL),
				"the matching-type form still resolves");
		assertNotNull(sarah);
	}

	/** A board with Sarah on P1's field and {@code top} sitting on top of P1's deck. */
	private static MainWindow sarahWithDeckTop(CardData top) {
		MainWindow mw = new MainWindow();
		mw.placeCardInForwardZone(makeForward("Sarah (MOBIUS)", "Water", 3, 7000,
				List.of()));
		mw.gameState.getP1MainDeck().add(top);
		mw.gameState.getP1MainDeck().add(makeForward("Filler", "Fire", 2, 5000));
		return mw;
	}

	@Test
	void aRevealedBackupGoesToHand() {
		CardData backup = makePlainBackup("Sage", "Water", 2);
		MainWindow mw = sarahWithDeckTop(backup);

		mw.buildGameContext(true).revealTopAddToHandIfType("Backup");

		assertTrue(mw.gameState.getP1Hand().contains(backup), "a Backup is taken");
		assertEquals(1, mw.gameState.getP1MainDeck().size(), "and leaves the deck");
	}

	@Test
	void aRevealedNonBackupStaysInTheDeck() {
		// Driven from P2's seat: the miss hands the top-or-bottom choice to lookAtTopDeck, and a
		// local seat would answer it through a modal dialog. P2 is the CPU here, so the same code
		// path resolves without one — which is the point of routing the choice through
		// PlayerChoice rather than deciding it inline.
		MainWindow mw = new MainWindow();
		mw.gameState.getP2MainDeck().add(makeForward("Not A Backup", "Fire", 3, 7000));
		mw.gameState.getP2MainDeck().add(makeForward("Filler", "Fire", 2, 5000));

		mw.buildGameContext(false).revealTopAddToHandIfType("Backup");

		assertTrue(mw.gameState.getP2Hand().isEmpty(), "a non-Backup is never added to hand");
		assertEquals(2, mw.gameState.getP2MainDeck().size(),
				"it goes to the top or bottom — either way it stays in the deck");
	}

	@Test
	void anEmptyDeckIsANoOpRatherThanACrash() {
		MainWindow mw = new MainWindow();
		mw.buildGameContext(true).revealTopAddToHandIfType("Backup");
		assertTrue(mw.gameState.getP1Hand().isEmpty());
	}

	// --- Firing: once per Crystal ------------------------------------------------------------

	/** Sarah on the field with her real crystal trigger, over a deck of {@code n} Backups. */
	private static MainWindow sarahOverBackupDeck(int n) {
		MainWindow mw = new MainWindow();
		CardData sarah = makeForward("Sarah (MOBIUS)", "Water", 3, 7000);
		mw.placeCardInForwardZone(sarah);
		mw.grantedAutoAbilities.put(mw.p1ForwardCards.get(0),
				new ArrayList<>(CardData.parseAutoAbilities(SARAH_MOBIUS_TEXT).stream()
						.filter(a -> "gain crystal".equals(a.trigger())).toList()));
		for (int i = 0; i < n; i++) mw.gameState.getP1MainDeck().add(makePlainBackup("Sage " + i, "Water", 2));
		return mw;
	}

	@Test
	void gainingOneCrystalFiresTheTriggerOnce() {
		MainWindow mw = sarahOverBackupDeck(3);
		mw.buildGameContext(true).gainCrystal(1);
		assertEquals(1, mw.gameState.getP1Hand().size(), "one Crystal, one reveal");
	}

	@Test
	void gainingTwoCrystalsAtOnceFiresTheTriggerTwice() {
		MainWindow mw = sarahOverBackupDeck(3);
		mw.buildGameContext(true).gainCrystal(2);
		assertEquals(2, mw.gameState.getP1Hand().size(),
				"《C》《C》 is two Crystals gained, so \"gain a 《C》\" is met twice");
	}

	@Test
	void theOpponentsCrystalGainDoesNotFireP1sTrigger() {
		MainWindow mw = sarahOverBackupDeck(3);
		mw.buildGameContext(false).gainCrystal(1);
		assertTrue(mw.gameState.getP1Hand().isEmpty(),
				"the trigger watches its own controller gaining, not either player");
	}

	// =========================================================================================
	// Sarah (MOBIUS) 16-115H's third ability:
	//   《C》: Until the end of the turn, Sarah (MOBIUS) gains +1000 power and "If Sarah (MOBIUS)
	//         is dealt damage less than her power, the damage becomes 0 instead."
	// UntilEotGainsPowerTraitsAndQuoted matched the shape but declined, because the quoted clause
	// was not a self-grant grantedSelfFieldAbilityEffect knew how to route — so the power half was
	// dropped with it.
	//
	// The clause needed no new primitive: FA_DAMAGE_MODIFIER already covers this exact wording
	// (down to the his/her/its variants), and several cards print it outright — Y'shtola 12-119L,
	// Barret 14-121L, Aymeric 6-106H. What was missing is that the reader in DamageResolver
	// scanned CardData.fieldAbilities() directly, so a granted copy was invisible to it. Reading
	// the effective view instead is what makes granting the text verbatim work.
	// =========================================================================================

	private static final String SARAH_SHIELD_PUMP =
			"Until the end of the turn, Sarah (MOBIUS) gains +1000 power and "
			+ "\"If Sarah (MOBIUS) is dealt damage less than her power, the damage becomes 0 instead.\"";
	private static final String SARAH_SHIELD_CLAUSE =
			"If Sarah (MOBIUS) is dealt damage less than her power, the damage becomes 0 instead.";

	@Test
	void sarahsPumpAppliesBothThePowerAndTheShield() {
		CardData sarah = makeForward("Sarah (MOBIUS)", "Water", 3, 7000);
		assertEquals("UntilEotGainsPowerTraitsAndQuoted",
				ActionResolver.matchedPatternName(SARAH_SHIELD_PUMP, sarah));

		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(SARAH_SHIELD_PUMP, sarah).accept(ctx);
		verify(ctx).boostSourceForward(sarah, 1000, EnumSet.noneOf(CardData.Trait.class));
		// Granted verbatim — the damage rules match this wording, so it needs no translation.
		verify(ctx).grantSelfFieldAbilityUntilEndOfTurn(sarah, SARAH_SHIELD_CLAUSE);
	}

	@Test
	void theGrantedShieldTurnsNonLethalDamageIntoZero() {
		MainWindow mw = new MainWindow();
		CardData sarah = makeForward("Sarah (MOBIUS)", "Water", 3, 7000);
		mw.placeCardInForwardZone(sarah);

		mw.buildGameContext(true).damageP1Forward(0, 5000);
		assertEquals(5000, mw.p1ForwardDamage.get(0), "unprotected, the damage lands");

		mw.p1ForwardDamage.set(0, 0);
		ActionResolver.parse(SARAH_SHIELD_PUMP, sarah).accept(mw.buildGameContext(true));

		assertEquals(8000, mw.effectiveP1ForwardPower(0), "+1000 power");
		mw.buildGameContext(true).damageP1Forward(0, 5000);
		assertEquals(0, mw.p1ForwardDamage.get(0),
				"5000 is less than her 8000 power, so the granted clause zeroes it");
	}

	@Test
	void theGrantedShieldStillLetsLethalDamageThrough() {
		MainWindow mw = new MainWindow();
		CardData sarah = makeForward("Sarah (MOBIUS)", "Water", 3, 7000);
		mw.placeCardInForwardZone(sarah);
		mw.gameState.getIdentity().put(sarah, true);   // lethal damage breaks her, which reads it
		ActionResolver.parse(SARAH_SHIELD_PUMP, sarah).accept(mw.buildGameContext(true));

		// 8000 equals her boosted power, so the clause does not cover it and she breaks.
		mw.buildGameContext(true).damageP1Forward(0, 8000);
		assertTrue(mw.p1ForwardCards.isEmpty(),
				"\"less than her power\" — damage equal to it is not covered");
		assertTrue(mw.gameState.getP1BreakZone().contains(sarah));
	}

	@Test
	void theGrantedShieldIsReadThroughTheEffectiveView() {
		// The reader used to scan the printed list only, which is what made the grant inert.
		MainWindow mw = new MainWindow();
		CardData sarah = makeForward("Sarah (MOBIUS)", "Water", 3, 7000);
		mw.placeCardInForwardZone(sarah);
		assertTrue(sarah.fieldAbilities().isEmpty(), "nothing is printed on the card itself");

		ActionResolver.parse(SARAH_SHIELD_PUMP, sarah).accept(mw.buildGameContext(true));

		assertEquals(1, mw.effectiveFieldAbilities(sarah).size());
		assertTrue(AutoAbilityTriggers.FA_DAMAGE_MODIFIER
				.matcher(mw.effectiveFieldAbilities(sarah).get(0).effectText()).matches(),
				"granted in the wording the damage rules already recognise");
	}

	@Test
	void theGrantedShieldExpiresAtEndOfTurn() {
		MainWindow mw = new MainWindow();
		CardData sarah = makeForward("Sarah (MOBIUS)", "Water", 3, 7000);
		mw.placeCardInForwardZone(sarah);
		ActionResolver.parse(SARAH_SHIELD_PUMP, sarah).accept(mw.buildGameContext(true));

		// Only the grant is asserted here. The +1000 is an ordinary boostSourceForward boost,
		// zeroed by the end-phase cleanup inside onNextPhase's MAIN_2 branch rather than by an
		// end-of-turn effect -- existing behaviour this change does not touch, and out of reach
		// of a unit test without driving the phase machine through a priority window.
		mw.fireEndOfTurnEffects(true);

		assertTrue(mw.effectiveFieldAbilities(sarah).isEmpty(), "the granted shield is gone");
		mw.buildGameContext(true).damageP1Forward(0, 5000);
		assertEquals(5000, mw.p1ForwardDamage.get(0), "non-lethal damage lands again next turn");
	}

	@Test
	void aPrintedCopyOfTheSameClauseIsUnaffected() {
		// Y'shtola 12-119L prints it. The reader change must not alter how a printing behaves.
		MainWindow mw = new MainWindow();
		CardData yshtola = makeFieldAbilityCard("Y'shtola", "Water", "Forward",
				"If Y'shtola is dealt damage less than her power, the damage becomes 0 instead.");
		mw.placeCardInForwardZone(yshtola);

		mw.buildGameContext(true).damageP1Forward(0, 5000);
		assertEquals(0, mw.p1ForwardDamage.get(0), "printed protection still applies");
	}

	// =========================================================================================
	// Warrior of Light 19-128L: "When Warrior of Light enters the field due to your cast, activate
	// all the Backups you control. Draw 1 card." The "due to your cast" qualifier is carried as
	// AutoAbility.castOnly and gated in executeAutoAbilityImpl against MainWindow.lastCardWasCast,
	// which is set true only around the cast-from-hand placement and reset immediately after — so
	// every other route onto the field (Break Zone, RFG, deck, Warp, a borrowed cast) sees false.
	//
	// These lock that down from both ends: the gate is real, and the routes that must not fire it
	// genuinely leave the flag clear rather than relying on the test to clear it by hand.
	// =========================================================================================

	private static final String WOL_TEXT =
			"When Warrior of Light enters the field due to your cast, "
			+ "activate all the Backups you control. Draw 1 card.";

	/** P1 with a dull Backup and a stocked deck — both halves of the payoff are observable. */
	private static MainWindow wolBoard() {
		MainWindow mw = new MainWindow();
		mw.placeCardInFirstBackupSlot(makePlainBackup("Sage", "Water", 2));
		mw.p1BackupStates[0] = CardState.DULL;
		for (int i = 0; i < 3; i++) mw.gameState.getP1MainDeck().add(makeForward("Deck" + i, "Fire", 1, 3000));
		return mw;
	}

	@Test
	void warriorOfLightCarriesTheCastOnlyQualifier() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(WOL_TEXT);
		assertEquals(1, autos.size());
		assertTrue(autos.get(0).castOnly(), "\"due to your cast\" is what makes this cast-only");
		assertFalse(autos.get(0).warpOnly());
		assertEquals("enters the field", autos.get(0).trigger());
		assertEquals("AllFieldEffect + DrawCards",
				ActionResolver.matchedPatternName(autos.get(0).effectText(),
						makeAutoAbilityForward("Warrior of Light", WOL_TEXT)));
	}

	@Test
	void warriorOfLightFiresWhenCastFromHand() {
		MainWindow mw = wolBoard();
		CardData wol = makeAutoAbilityForward("Warrior of Light", WOL_TEXT);
		int deckBefore = mw.gameState.getP1MainDeck().size();

		// What the cast-from-hand path does around the placement.
		mw.lastCardWasCast = true;
		mw.placeCardInForwardZone(wol);
		mw.lastCardWasCast = false;

		assertEquals(CardState.ACTIVE, mw.p1BackupStates[0], "the Backup is activated");
		assertEquals(1, mw.gameState.getP1Hand().size(), "and a card is drawn");
		assertEquals(deckBefore - 1, mw.gameState.getP1MainDeck().size());
	}

	@Test
	void warriorOfLightDoesNotFireWhenItEntersAnyOtherWay() {
		MainWindow mw = wolBoard();
		CardData wol = makeAutoAbilityForward("Warrior of Light", WOL_TEXT);
		int deckBefore = mw.gameState.getP1MainDeck().size();

		mw.placeCardInForwardZone(wol);   // no cast in progress

		assertEquals(CardState.DULL, mw.p1BackupStates[0], "the Backup stays dull");
		assertTrue(mw.gameState.getP1Hand().isEmpty(), "and nothing is drawn");
		assertEquals(deckBefore, mw.gameState.getP1MainDeck().size());
	}

	@Test
	void aBreakZoneReplayDoesNotCountAsACast() {
		// Not a hand-set flag: this drives a real non-cast entry and checks the route itself
		// leaves lastCardWasCast clear. A cast immediately beforehand is the case that would
		// catch a flag left stale rather than scoped to its own placement.
		MainWindow mw = wolBoard();
		CardData decoy = makeForward("Decoy", "Fire", 2, 5000);
		mw.lastCardWasCast = true;
		mw.placeCardInForwardZone(decoy);
		mw.lastCardWasCast = false;

		CardData wol = makeAutoAbilityForward("Warrior of Light", WOL_TEXT);
		mw.gameState.getP1BreakZone().add(wol);
		int deckBefore = mw.gameState.getP1MainDeck().size();

		mw.buildGameContext(true).playAllByNameFromOwnBreakZoneDull("Warrior of Light", false);

		assertTrue(mw.p1ForwardCards.contains(wol), "he is on the field");
		assertEquals(CardState.DULL, mw.p1BackupStates[0],
				"but a Break Zone replay is not a cast, so the Backup stays dull");
		assertTrue(mw.gameState.getP1Hand().isEmpty(), "and nothing is drawn");
		assertEquals(deckBefore, mw.gameState.getP1MainDeck().size());
	}

	@Test
	void aBorrowedCastDoesNotCountAsYourCast() {
		// The two "cast an opponent's card" paths set lastCardWasCast false explicitly, because
		// casting a card you do not own is not "your cast". Pinned here so that stays true.
		MainWindow mw = wolBoard();
		CardData wol = makeAutoAbilityForward("Warrior of Light", WOL_TEXT);

		mw.lastCardWasCast = false;   // what removeBorrowedSourceCard's callers leave behind
		mw.placeCardInForwardZone(wol);

		assertEquals(CardState.DULL, mw.p1BackupStates[0]);
		assertTrue(mw.gameState.getP1Hand().isEmpty());
	}

	// =========================================================================================
	// Faris 18-012L: "When Faris enters the field or attacks, deal 1000 damage to all Forwards.
	// When Faris or a Job Warrior of Light Forward you control is dealt damage, choose up to 1
	// Forward opponent controls. Deal it 3000 damage.  Damage 3 -- When Faris enters the field,
	// you may search for 1 Job Warrior of Light other than Card Name Faris and add it to your hand."
	//
	// The middle ability is the watcher form of "is dealt damage". Every other printing of that
	// trigger names the card itself, so dispatch used to consult only the card that took the
	// damage; this one sits on Faris and watches damage dealt to her Warrior of Light stablemates,
	// so it fires only if the dispatch walks her controller's whole field.
	//
	// It fires per instance of damage, which is what makes it pay: her own first ability deals a
	// separate 1000 to every Forward, so a board of Warriors of Light answers with one 3000-damage
	// trigger each.
	//
	// Everything here is run from P2's seat. The trigger chooses its target as it goes on the
	// Stack, and that is a modal dialog for the human seat but the AI path for P2.
	// =========================================================================================

	private static final String FARIS_18_012L_TEXT =
			"When Faris enters the field or attacks, deal 1000 damage to all Forwards.[[br]]   "
			+ "When Faris or a Job Warrior of Light Forward you control is dealt damage, "
			+ "choose up to 1 Forward opponent controls. Deal it 3000 damage.[[br]]   "
			+ "Damage 3 -- When Faris enters the field, you may search for 1 Job Warrior of Light "
			+ "other than Card Name Faris and add it to your hand.";

	private static CardData makeFaris18012L() {
		return makeJobForwardWithAutos("Faris", "Fire", 7000, "Pirate/Warrior of Light", FARIS_18_012L_TEXT);
	}

	/** A Job Warrior of Light Forward with no abilities of its own — something for Faris to watch. */
	private static CardData makeWarriorOfLight(String name) {
		return makeJobForwardWithAutos(name, "Fire", 7000, "Warrior of Light", "");
	}

	/** P2 fields {@code p2Cards}; P1 fields one Forward for the trigger to point at. */
	private static MainWindow farisBoard(CardData... p2Cards) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Genesis", "Ice", 3, 9000));
		for (CardData c : p2Cards) {
			// Seated without running her enters-the-field sweep: that sweep is damage, and these
			// tests are about what answers damage — it would fire the very trigger under test
			// before the test had begun.
			mw.suppressAutoAbilityForNextCard = true;
			placeP2Forward(mw, c);
		}
		return mw;
	}

	/** The Forwards whose "is dealt damage" trigger is now waiting on the Stack, in push order. */
	private static List<CardData> triggerSources(MainWindow mw) {
		return mw.gameState.getStack().stream().map(StackEntry::source).toList();
	}

	@Test
	void farisParsesIntoHerThreeAbilities() {
		CardData faris = makeFaris18012L();
		assertEquals(3, faris.autoAbilities().size());

		AutoAbility etfOrAttack = faris.autoAbilities().get(0);
		assertEquals("enters the field or attacks", etfOrAttack.trigger());
		assertEquals("DealDamageToForwards",
				ActionResolver.matchedPatternName(etfOrAttack.effectText(), faris));

		AutoAbility watcher = faris.autoAbilities().get(1);
		assertEquals("is dealt damage", watcher.trigger());
		assertEquals("Faris or a Job Warrior of Light Forward you control", watcher.triggerCard(),
				"the compound subject is what makes this the watcher form");

		AutoAbility search = faris.autoAbilities().get(2);
		assertEquals("enters the field", search.trigger());
		assertEquals(3, search.damageThreshold(), "\"Damage 3 --\" gates the search");
		assertTrue(search.youMay());
	}

	@Test
	void farisAnswersDamageDealtToAWarriorOfLightSheControls() {
		CardData faris = makeFaris18012L();
		MainWindow mw = farisBoard(faris, makeWarriorOfLight("Bartz"));

		mw.applyDamageToForward(false, 1, 1000, true, false);

		assertEquals(List.of(faris), triggerSources(mw),
				"the damage landed on Bartz, but the ability that answers it is Faris's");
	}

	@Test
	void farisIgnoresDamageToAForwardThatIsNotAWarriorOfLight() {
		MainWindow mw = farisBoard(makeFaris18012L(), makeForward("Zidane", "Wind", 3, 7000));

		mw.applyDamageToForward(false, 1, 1000, true, false);

		assertTrue(mw.gameState.getStack().isEmpty(), "the subject filter is a Job, not any Forward");
	}

	@Test
	void farisIgnoresDamageToTheOpponentsWarriorOfLight() {
		MainWindow mw = farisBoard(makeFaris18012L());
		placeP1Forward(mw, makeWarriorOfLight("Firion"));   // P1 idx 1

		mw.applyDamageToForward(true, 1, 1000, true, false);

		assertTrue(mw.gameState.getStack().isEmpty(), "\"you control\" scopes the watch to her own side");
	}

	@Test
	void farisAnsweringHerOwnDamageTriggersOnceAndNotTwice() {
		CardData faris = makeFaris18012L();
		MainWindow mw = farisBoard(faris);

		mw.applyDamageToForward(false, 0, 1000, true, false);

		// She satisfies both halves of her own subject — she is Faris, and she is a Job Warrior of
		// Light Forward she controls. One damage is still one trigger.
		assertEquals(List.of(faris), triggerSources(mw));
	}

	@Test
	void everyInstanceOfHerOwnSweepAnswersSeparately() {
		CardData faris = makeFaris18012L();
		MainWindow mw = farisBoard(faris, makeWarriorOfLight("Bartz"), makeForward("Zidane", "Wind", 3, 7000));

		// Her enters-the-field/attacks ability, run as the board would run it.
		ActionResolver.parse(faris.autoAbilities().get(0).effectText(), faris)
				.accept(mw.buildGameContext(false));

		assertEquals(List.of(faris, faris), triggerSources(mw),
				"1000 to all Forwards is four separate damages; two land on Warriors of Light she "
				+ "controls, and each is answered in its own right");
	}

	@Test
	void farisAnswersBattleDamageAndDoesSoEvenWhenItBreaksHer() {
		CardData faris = makeFaris18012L();
		MainWindow mw = farisBoard(faris);
		CardData attacker = mw.p1ForwardCards.get(0);   // 9000 — lethal to her 7000

		mw.resolveCombat(attacker, true, 0, faris, false, 0);

		assertFalse(mw.p2ForwardCards.contains(faris), "the blow was lethal");
		assertEquals(List.of(faris), triggerSources(mw),
				"the trigger is on being dealt damage, not on surviving it");
	}

	@Test
	void firstStrikeThatDealsNoDamageBackTriggersNothing() {
		CardData faris = makeFaris18012L();
		MainWindow mw = farisBoard(faris);
		CardData attacker = makeTraitForward("Zack", "Fire", 3, 9000, CardData.Trait.FIRST_STRIKE);
		mw.p1ForwardCards.set(0, attacker);
		mw.gameState.getIdentity().put(attacker, true);

		mw.resolveCombat(attacker, true, 0, faris, false, 0);

		// She is broken by the first strike and never lands a blow, so Zack is dealt no damage —
		// but she was dealt hers, and answers it.
		assertEquals(List.of(faris), triggerSources(mw));
	}

	// The other subject form, sharing a field with Faris. 4-085H Dadaluma is the ordinary printing
	// — she names herself — and dispatch now walks the whole field to find Faris, so Dadaluma is
	// the guard that the walk did not quietly turn every printing of the trigger into a watcher.
	private static final String DADALUMA_TEXT =
			"When Dadaluma is dealt damage, choose up to 1 Forward opponent controls. Deal it 4000 damage.";

	@Test
	void aSelfNamingTriggerStaysDeafToAStablematesDamage() {
		CardData faris    = makeFaris18012L();
		CardData dadaluma = makeAutoAbilityForward("Dadaluma", DADALUMA_TEXT);
		MainWindow mw = farisBoard(faris, dadaluma, makeWarriorOfLight("Bartz"));

		mw.applyDamageToForward(false, 2, 1000, true, false);

		assertEquals(List.of(faris), triggerSources(mw),
				"Faris watches her Warrior of Light; Dadaluma names herself and hears nothing");
	}

	@Test
	void aSelfNamingTriggerStillAnswersItsOwnDamage() {
		CardData faris    = makeFaris18012L();
		CardData dadaluma = makeAutoAbilityForward("Dadaluma", DADALUMA_TEXT);
		MainWindow mw = farisBoard(faris, dadaluma);

		mw.applyDamageToForward(false, 1, 1000, true, false);

		assertEquals(List.of(dadaluma), triggerSources(mw),
				"her own trigger is unaffected, and Dadaluma is no Warrior of Light for Faris to watch");
	}

	// =========================================================================================
	// Refia 19-102L: "At the beginning of the Attack Phase during each of your turns, activate all
	// the Job Warrior of Light you control. When 4 or more dull Characters are activated by this
	// effect, draw 1 card.  Dull 4 active Job Warrior of Light: Choose 1 Forward opponent controls.
	// Put it at the top or bottom of its owner's deck. You can only use this ability once per turn."
	//
	// Two halves, each of which was reaching the board incomplete.
	//
	// The sweep parsed, but only as far as its first sentence: the draw is conditional on what the
	// sweep woke up, and there was no way to ask. It counts activations, not eligible cards — a
	// Warrior of Light that was already active is not "activated by this effect" — so the count is
	// taken by the sweep itself and read back through lastMassActivateCount().
	//
	// The dull cost parsed its Job as "W". DULL_COST_ITEM_PATTERN is run over the extracted cost
	// text with nothing behind it, and every clause that can follow a Job name is optional, so the
	// lazy name stopped at one character and the whole item still matched. hasJob("W") is false for
	// everything, so the cost could never be paid — the ability was dead on every Job printing in
	// the corpus, not just this one.
	// =========================================================================================

	private static final String REFIA_TEXT =
			"At the beginning of the Attack Phase during each of your turns, activate all the Job "
			+ "Warrior of Light you control. When 4 or more dull Characters are activated by this "
			+ "effect, draw 1 card.[[br]]   Dull 4 active Job Warrior of Light: Choose 1 Forward "
			+ "opponent controls. Put it at the top or bottom of its owner's deck. You can only use "
			+ "this ability once per turn.";

	private static CardData makeRefia() {
		return new CardData(null, "Refia", "Water", 3, 7000, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				CardData.parseActionAbilities(REFIA_TEXT), CardData.parseAutoAbilities(REFIA_TEXT),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				"Warrior of Light", "III", null, REFIA_TEXT);
	}

	/** The sweep half, as the beginning-of-Attack-Phase trigger would run it. */
	private static Consumer<GameContext> refiaSweep(CardData refia) {
		return ActionResolver.parse(refia.autoAbilities().get(0).effectText(), refia);
	}

	/** A dull Job Warrior of Light on P1's field. */
	private static void placeDullWarriorOfLight(MainWindow mw, String name) {
		placeP1Forward(mw, makeJobForwardWithAutos(name, "Water", 7000, "Warrior of Light", ""));
		mw.p1ForwardStates.set(mw.p1ForwardCards.size() - 1, CardState.DULL);
	}

	@Test
	void refiaParsesIntoASweepTriggerAndAJobDullCost() {
		CardData refia = makeRefia();

		assertEquals(1, refia.autoAbilities().size());
		AutoAbility sweep = refia.autoAbilities().get(0);
		assertEquals("beginning of attack phase", sweep.trigger(),
				"\"during each of your turns\" is the controller-only form, not the each-turn one");
		assertEquals("AllFieldEffect + DrawCards",
				ActionResolver.fullDescription(sweep.effectText(), refia),
				"the conditional draw is part of the effect, not a dropped second sentence");

		assertEquals(1, refia.actionAbilities().size());
		ActionAbility ab = refia.actionAbilities().get(0);
		assertTrue(ab.oncePerTurn());
		assertEquals(1, ab.dullForwardCosts().size());
		DullForwardCost cost = ab.dullForwardCosts().get(0);
		assertEquals(4, cost.count());
		assertEquals("active", cost.condition());
		assertEquals("Warrior of Light", cost.job(), "the whole Job name, not its first letter");
		assertEquals("ChooseCharacter / PutTopOrBottomOfDeck",
				ActionResolver.fullDescription(ab.effectText(), refia));
	}

	// The truncation hit every shape the Job and Category branches accept, so the fix is pinned
	// against the other wordings in the corpus rather than only against Refia's.
	@Test
	void dullCostsCaptureWholeJobAndCategoryNames() {
		assertEquals("Sky Pirate", CardData.parseActionAbilities(
				"Dull 2 active Job Sky Pirate: Draw 1 card.").get(0).dullForwardCosts().get(0).job());
		assertEquals("Scion of the Seventh Dawn", CardData.parseActionAbilities(
				"Dull 1 active Job Scion of the Seventh Dawn Forward: Draw 1 card.")
				.get(0).dullForwardCosts().get(0).job(),
				"the card-type suffix ends the name and is not part of it");
		assertEquals("XII", CardData.parseActionAbilities(
				"Dull 1 active Category XII Character: Draw 1 card.")
				.get(0).dullForwardCosts().get(0).category());

		DullForwardCost orName = CardData.parseActionAbilities(
				"Dull 1 active Job Chocobo or Card Name Chocobo: Draw 1 card.")
				.get(0).dullForwardCosts().get(0);
		assertEquals("Chocobo", orName.job(), "the alternative also ends the Job name");
		assertEquals("Chocobo", orName.orCardName());

		// "and/or" is the shape that could lose its alternative: the "and" also satisfies the
		// end-of-item lookahead, so the alternative has to be claimed before the item can end.
		DullForwardCost andOr = CardData.parseActionAbilities(
				"Dull a total of 2 active Job Ninja and/or Card Name Ninja: Draw 1 card.")
				.get(0).dullForwardCosts().get(0);
		assertEquals("Ninja", andOr.job());
		assertEquals("Ninja", andOr.orCardName());
	}

	@Test
	void refiasAbilityIsUnusableUntilFourWarriorsOfLightAreActive() {
		MainWindow mw = new MainWindow();
		CardData refia = makeRefia();
		placeP1Forward(mw, refia);
		ActionAbility ab = refia.actionAbilities().get(0);

		for (int i = 0; i < 2; i++)
			placeP1Forward(mw, makeJobForwardWithAutos("Ally" + i, "Water", 7000, "Warrior of Light", ""));
		assertFalse(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, refia, true),
				"3 active Warriors of Light — Refia herself counts, but that is one short");

		placeP1Forward(mw, makeJobForwardWithAutos("Ally2", "Water", 7000, "Warrior of Light", ""));
		assertTrue(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, refia, true),
				"the fourth makes the cost payable");

		placeP1Forward(mw, makeJobForwardWithAutos("Onion Knight", "Water", 7000, "Onion Knight", ""));
		mw.p1ForwardStates.set(1, CardState.DULL);
		assertFalse(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, refia, true),
				"a dull one cannot be dulled again, and another Job does not stand in for it");
	}

	@Test
	void theSweepActivatesOnlyWarriorsOfLightAndDrawsAtFour() {
		MainWindow mw = new MainWindow();
		CardData refia = makeRefia();
		placeP1Forward(mw, refia);
		mw.p1ForwardStates.set(0, CardState.DULL);
		for (int i = 0; i < 3; i++) placeDullWarriorOfLight(mw, "Ally" + i);
		placeP1Forward(mw, makeJobForwardWithAutos("Onion Knight", "Water", 7000, "Onion Knight", ""));
		mw.p1ForwardStates.set(4, CardState.DULL);
		mw.gameState.getP1MainDeck().add(makeForward("Top", "Water", 1, 3000));

		refiaSweep(refia).accept(mw.buildGameContext(true));

		for (int i = 0; i < 4; i++)
			assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(i), "Warrior of Light " + i + " woke up");
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(4), "the Onion Knight is another Job");
		assertEquals(1, mw.gameState.getP1Hand().size(), "4 activated — the draw is on");
	}

	@Test
	void theDrawCountsWhatWokeUpRatherThanWhoWasEligible() {
		MainWindow mw = new MainWindow();
		CardData refia = makeRefia();
		placeP1Forward(mw, refia);              // left active — nothing happens to her
		for (int i = 0; i < 3; i++) placeDullWarriorOfLight(mw, "Ally" + i);
		mw.gameState.getP1MainDeck().add(makeForward("Top", "Water", 1, 3000));

		refiaSweep(refia).accept(mw.buildGameContext(true));

		assertEquals(3, mw.buildGameContext(true).lastMassActivateCount(),
				"4 Warriors of Light were swept, but only the 3 dull ones were activated");
		assertTrue(mw.gameState.getP1Hand().isEmpty(), "3 activated is under the threshold — no draw");
	}

	@Test
	void aLaterSweepDoesNotInheritTheEarlierTally() {
		MainWindow mw = new MainWindow();
		CardData refia = makeRefia();
		placeP1Forward(mw, refia);
		mw.p1ForwardStates.set(0, CardState.DULL);
		for (int i = 0; i < 3; i++) placeDullWarriorOfLight(mw, "Ally" + i);
		for (int i = 0; i < 2; i++) mw.gameState.getP1MainDeck().add(makeForward("Top" + i, "Water", 1, 3000));

		refiaSweep(refia).accept(mw.buildGameContext(true));
		assertEquals(1, mw.gameState.getP1Hand().size());

		// Everything is active now, so the second sweep activates nothing and must say so.
		refiaSweep(refia).accept(mw.buildGameContext(true));
		assertEquals(0, mw.buildGameContext(true).lastMassActivateCount(),
				"the count belongs to the sweep that just ran");
		assertEquals(1, mw.gameState.getP1Hand().size(), "and so there is no second draw");
	}

	// "Characters", not "Forwards" — and 21 of the corpus's Warriors of Light are Backups, so the
	// fourth activation is often one of them.
	@Test
	void aDullWarriorOfLightBackupCountsTowardTheFour() {
		MainWindow mw = new MainWindow();
		CardData refia = makeRefia();
		placeP1Forward(mw, refia);
		mw.p1ForwardStates.set(0, CardState.DULL);
		for (int i = 0; i < 2; i++) placeDullWarriorOfLight(mw, "Ally" + i);
		mw.placeCardInFirstBackupSlot(makeJobCard("Arc", "Water", "Backup", "Warrior of Light"));
		mw.p1BackupStates[0] = CardState.DULL;
		mw.gameState.getP1MainDeck().add(makeForward("Top", "Water", 1, 3000));

		refiaSweep(refia).accept(mw.buildGameContext(true));

		assertEquals(CardState.ACTIVE, mw.p1BackupStates[0], "the Backup shares the Job");
		assertEquals(1, mw.gameState.getP1Hand().size(),
				"3 Forwards and 1 Backup is 4 dull Characters activated");
	}

	@Test
	void theSweepLeavesTheOpponentsWarriorsOfLightAlone() {
		MainWindow mw = new MainWindow();
		CardData refia = makeRefia();
		placeP1Forward(mw, refia);
		mw.p1ForwardStates.set(0, CardState.DULL);
		placeP2Forward(mw, makeJobForwardWithAutos("Firion", "Water", 7000, "Warrior of Light", ""));
		mw.p2ForwardStates.set(0, CardState.DULL);

		refiaSweep(refia).accept(mw.buildGameContext(true));

		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0));
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0), "\"you control\" scopes the sweep");
	}

	// =========================================================================================
	// "The Forwards opponent controls lose 2000 power for each Poison Counter on them."
	// (Gargas 17-045R) — the only printed counter grant that differs from the "Each Forward you
	// control with a [X] Counter on it gains …" family on both axes at once: it scales with the
	// counter count rather than paying out once at one or more, and it reaches across the field.
	// Both differences ride on CounterGrant, so the same accessor serves the whole family.
	// =========================================================================================

	private static final String GARGAS_DEBUFF =
			"The Forwards opponent controls lose 2000 power for each Poison Counter on them.";

	@Test
	void theCounterScaledDebuffParsesAsAPerCounterOpponentGrant() {
		CardData gargas = makeIcbCard("Gargas", "Wind", "Forward", GARGAS_DEBUFF);

		assertEquals(1, gargas.counterGrants().size());
		CounterGrant cg = gargas.counterGrants().get(0);
		assertEquals("Poison", cg.counterName());
		assertEquals(-2000, cg.powerBonus(), "\"lose\" is stored as a negative bonus");
		assertTrue(cg.perCounter(), "\"for each\" scales with the count");
		assertTrue(cg.affectsOpponent());
		assertNull(cg.grantedAbilityText());
	}

	@Test
	void poisonCountersStackTheirPowerLossAcrossTheField() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeIcbCard("Gargas", "Wind", "Forward", GARGAS_DEBUFF));
		CardData clean   = makeForward("Clean",   "Fire", 3, 7000);
		CardData poisoned = makeForward("Poisoned", "Fire", 3, 7000);
		mw.placeP2CardInForwardZone(clean);     // P2 idx 0
		mw.placeP2CardInForwardZone(poisoned);  // P2 idx 1

		assertEquals(7000, mw.effectiveP2ForwardPower(0), "no counters, no loss");

		mw.gameState.placeCounters(poisoned, "Poison", 3);

		assertEquals(1000, mw.effectiveP2ForwardPower(1), "7000 - 3 x 2000");
		assertEquals(7000, mw.effectiveP2ForwardPower(0), "the clean Forward is untouched");
	}

	@Test
	void theDebuffDoesNotReachItsOwnControllersForwards() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeIcbCard("Gargas", "Wind", "Forward", GARGAS_DEBUFF));
		CardData ally = makeForward("Ally", "Wind", 3, 7000);
		placeP1Forward(mw, ally);
		mw.gameState.placeCounters(ally, "Poison", 2);

		assertEquals(7000, mw.effectiveP1ForwardPower(1),
				"\"opponent controls\" scopes the debuff to the other side of the field");
	}

	@Test
	void aCounterNamedByADifferentGrantIsNotCounted() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeIcbCard("Gargas", "Wind", "Forward", GARGAS_DEBUFF));
		CardData other = makeForward("Other", "Fire", 3, 7000);
		mw.placeP2CardInForwardZone(other);
		mw.gameState.placeCounters(other, "Fortune", 3);

		assertEquals(7000, mw.effectiveP2ForwardPower(0), "only Poison Counters feed this grant");
	}

	// =========================================================================================
	// The field-wide block compulsion: "The Forwards you control must block if possible."
	// (General Leo 15-021R), plus the two printings that differ only in whose Forwards they name —
	// Jack Garland 24-079L ("opponent controls") and Layle 16-083H ("All Forwards"). Unlike
	// "Opponent must block X if possible" this sits on neither combatant: it names a side, and
	// because only one Forward can block an attack, it means that side may not decline a block it
	// could make. So it feeds the same "forced" decision from the other direction.
	// =========================================================================================

	private static final String LEO_MUST_BLOCK    = "The Forwards you control must block if possible.";
	private static final String GARLAND_MUST_BLOCK = "The Forwards opponent controls must block if possible.";
	private static final String LAYLE_MUST_BLOCK  = "All Forwards must block if possible.";

	@Test
	void theBlockCompulsionBindsWhicheverSideItsTextNames() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeIcbCard("General Leo", "Fire", "Forward", LEO_MUST_BLOCK));

		assertTrue(mw.forwardsMustBlock(true), "\"you control\" binds the printing card's own side");
		assertFalse(mw.forwardsMustBlock(false), "and not the other one");
	}

	@Test
	void theOpponentControlsPrintingBindsTheOtherSide() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeIcbCard("Jack Garland", "Lightning", "Forward", GARLAND_MUST_BLOCK));

		assertFalse(mw.forwardsMustBlock(true), "Jack Garland does not compel its own controller");
		assertTrue(mw.forwardsMustBlock(false));
	}

	@Test
	void theAllForwardsPrintingBindsBothSides() {
		MainWindow mw = new MainWindow();
		mw.placeP2CardInForwardZone(makeIcbCard("Layle", "Earth", "Forward", LAYLE_MUST_BLOCK));

		assertTrue(mw.forwardsMustBlock(true));
		assertTrue(mw.forwardsMustBlock(false), "\"All Forwards\" names no controller, so it binds everyone");
	}

	@Test
	void aFieldWideCompulsionForcesTheBlockWithoutNamingAnAttacker() {
		MainWindow mw = new MainWindow();
		CardData plainAttacker = makeForward("Plain", "Fire", 3, 7000);

		assertFalse(mw.blockIsCompelled(plainAttacker, true), "nothing compels the block yet");

		placeP1Forward(mw, makeIcbCard("General Leo", "Fire", "Forward", LEO_MUST_BLOCK));

		assertTrue(mw.blockIsCompelled(plainAttacker, true),
				"the attacker carries no compulsion — the defender's own field supplies it");
		assertFalse(mw.blockIsCompelled(plainAttacker, false), "the attacking side is not compelled");
	}

	@Test
	void aCardThatLostItsAbilitiesStopsCompellingTheBlock() {
		MainWindow mw = new MainWindow();
		CardData leo = makeIcbCard("General Leo", "Fire", "Forward", LEO_MUST_BLOCK);
		placeP1Forward(mw, leo);
		assertTrue(mw.forwardsMustBlock(true));

		mw.lostAbilitiesCards.add(leo);

		assertFalse(mw.forwardsMustBlock(true), "a passive read off the field goes when the abilities do");
	}





	// =========================================================================================
	// Shelke 16-029R: "The power of Forwards you control cannot be decreased by your opponent's
	// Summons or abilities." (Kalmia 18-090R prints the same sentence on a Backup.)
	//
	// Wired as a passive grant of POWER_CANNOT_BE_DECREASED_BY_OPP rather than a new mechanism:
	// every power-decrease path already consults that trait, and already scopes the block to a
	// decrease coming from the Forward's opponent. Asura 23-039R hands the same trait out through
	// the quoted per-card wording, so both printings meet at one enforcement point.
	//
	// The twin printed beside it on Shelke — "the power of Forwards opponent controls cannot be
	// increased…" — goes the other way, scanning the opposing field, because it restricts who may
	// boost rather than marking the Forwards, and so has no per-card trait to hang on.
	// =========================================================================================

	private static final String SHELKE_NO_DECREASE =
			"The power of Forwards you control cannot be decreased by your opponent's Summons or abilities.";

	@Test
	void theNoDecreaseLineParsesAsATraitGrantToYourForwards() {
		List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(SHELKE_NO_DECREASE, "Forward");

		assertEquals(1, grants.size());
		FieldPowerGrant g = grants.get(0);
		assertEquals(Set.of(CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP), g.grantedTraits());
		assertEquals(0, g.powerBonus(), "it grants a trait, not power");
		assertTrue(g.inclForwards());
		assertFalse(g.inclBackups(), "the grant reaches Forwards even when printed on a Backup");
		assertFalse(g.affectsOpponent());
	}

	@Test
	void shelkeMarksEveryForwardYouControlIncludingHerself() {
		MainWindow mw = new MainWindow();
		CardData ally = makeForward("Ally", "Ice", 3, 7000);
		placeP1Forward(mw, ally);
		assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP));

		placeP1Forward(mw, makeGrantForward("Shelke", "Ice", SHELKE_NO_DECREASE));
		placeP2Forward(mw, makeForward("Foe", "Fire", 3, 7000));

		assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP));
		assertTrue(mw.effectiveP1HasTrait(1, CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP),
				"the text names no exception, so Shelke marks herself too");
		assertFalse(mw.effectiveP2HasTrait(0, CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP),
				"\"you control\" — the opponent's Forwards are untouched");
	}

	@Test
	void theGrantAlsoReachesFromTheBackupRow() {
		// Kalmia 18-090R prints the identical sentence on a Backup.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Ally", "Ice", 3, 7000));
		mw.placeCardInFirstBackupSlot(makeBackupWithPowerGrant("Kalmia", "Ice", SHELKE_NO_DECREASE));

		assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP));
	}

	@Test
	void anOpponentsEffectCannotLowerAMarkedForwardsPower() {
		MainWindow mw = new MainWindow();
		CardData ally = makeForward("Ally", "Ice", 3, 7000);
		placeP1Forward(mw, ally);
		placeP1Forward(mw, makeGrantForward("Shelke", "Ice", SHELKE_NO_DECREASE));

		mw.buildGameContext(false).boostTarget(
				new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD), -3000,
				EnumSet.noneOf(CardData.Trait.class));

		assertEquals(7000, mw.effectiveP1ForwardPower(0), "P2's effect is blocked outright");
	}

	@Test
	void theControllersOwnEffectsMayStillLowerIt() {
		// The trait blocks the opponent only — a cost or drawback of your own still applies.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Ally", "Ice", 3, 7000));
		placeP1Forward(mw, makeGrantForward("Shelke", "Ice", SHELKE_NO_DECREASE));

		mw.buildGameContext(true).boostTarget(
				new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD), -3000,
				EnumSet.noneOf(CardData.Trait.class));

		assertEquals(4000, mw.effectiveP1ForwardPower(0));
	}

	@Test
	void aPositiveBoostFromTheOpponentIsUnaffected() {
		// The trait is about decreases; nothing here should touch an incoming buff.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Ally", "Ice", 3, 7000));
		placeP1Forward(mw, makeGrantForward("Shelke", "Ice", SHELKE_NO_DECREASE));

		mw.buildGameContext(false).boostTarget(
				new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD), 2000,
				EnumSet.noneOf(CardData.Trait.class));

		assertEquals(9000, mw.effectiveP1ForwardPower(0));
	}

	@Test
	void theLimitBreakDeclarationIsNotAFieldAbility() {
		// It is a cost declaration the ETL already reads into lb_cost, so it grants the card
		// nothing and must not sit in fieldAbilities() as a record nothing will ever parse.
		String text = "Limit Break -- 1[[br]]Jack Garland is also Card Name Garland in all situations.[[br]]"
				+ "Jack Garland cannot be blocked by a Monster that is also a Forward.";
		List<FieldAbility> fas = CardData.parseFieldAbilities(text, "Forward");

		assertEquals(1, fas.size(), "only the block restriction is an ability");
		assertEquals("Jack Garland cannot be blocked by a Monster that is also a Forward.",
				fas.get(0).effectText(), "the alias declaration was already excluded; the LB line now is too");
	}

	@Test
	void aLimitBreakCardKeepsItsRealAbilitiesAtTheirNewIndices() {
		// The exclusion renumbers every LB card's abilities, so the guard is that nothing is lost.
		String text = "(Cards with 《LB》 cannot be included in your main deck.)[[br]]Limit Break -- 1[[br]]"
				+ "Brave[[br]]Maat must attack once per turn if possible.";
		List<FieldAbility> fas = CardData.parseFieldAbilities(text, "Forward");

		assertEquals(1, fas.size(), "the reminder, the LB line and the keyword all drop out");
		assertEquals("Maat must attack once per turn if possible.", fas.get(0).effectText());
	}

	// =========================================================================================
	// The targeting shield handed to a filtered *set* rather than a named card:
	// "The Monsters other than Silver Dragon you control cannot be chosen by your opponent's
	// Summons or abilities." (23-044R), the Backup twin on Yaag Rosch 22-086C, and the
	// trait-filtered "The Forwards with Brave other than White Tiger l'Cie Nimbus you control
	// cannot be chosen by your opponent's abilities." (23-035H).
	//
	// Stored the way the Card Name form already was — an IfControlBoost with no conditions — but
	// carrying a target filter instead of a name. The immunity lookup resolves a filter by walking
	// the field, so nothing downstream needed a new mechanism. The trait half is read against the
	// card's *current* traits, since Brave can be granted or stripped.
	// =========================================================================================

	private static final String SILVER_DRAGON_SHIELD =
			"The Monsters other than Silver Dragon you control cannot be chosen by your opponent's Summons or abilities.";
	private static final String NIMBUS_SHIELD =
			"The Forwards with Brave other than White Tiger l'Cie Nimbus you control "
			+ "cannot be chosen by your opponent's abilities.";

	@Test
	void theFilteredShieldParsesAsAConditionlessBoostOverASet() {
		List<IfControlBoost> icbs = CardData.parseIfControlBoosts(SILVER_DRAGON_SHIELD, "Monster");

		assertEquals(1, icbs.size());
		IfControlBoost icb = icbs.get(0);
		assertTrue(icb.conditions().isEmpty(), "no \"If you control\" gate — it is always on");
		assertNotNull(icb.targetFilter(), "a set, not a name");
		assertTrue(icb.targetFilter().inclMonsters());
		assertFalse(icb.targetFilter().inclForwards());
		assertEquals("Silver Dragon", icb.targetFilter().exceptCardName());
		assertTrue(icb.cannotBeChosenBySummons());
		assertTrue(icb.cannotBeChosenByAbilities());
		assertTrue(icb.chosenImmunityOpponentOnly(), "\"your opponent's\" scopes it to the other player");
	}

	@Test
	void theTraitFilteredShieldKeepsBothItsFilterAndItsNarrowerScope() {
		List<IfControlBoost> icbs = CardData.parseIfControlBoosts(NIMBUS_SHIELD, "Forward");

		assertEquals(1, icbs.size());
		IfControlBoost icb = icbs.get(0);
		assertEquals(Set.of(CardData.Trait.BRAVE), icb.targetFilter().traitFilter());
		assertEquals("White Tiger l'Cie Nimbus", icb.targetFilter().exceptCardName(),
				"the apostrophe in l'Cie must survive the exclusion capture");
		assertFalse(icb.cannotBeChosenBySummons(), "this printing names abilities only");
		assertTrue(icb.cannotBeChosenByAbilities());
	}

	@Test
	void silverDragonShieldsTheOtherMonstersButNotItself() {
		MainWindow mw = new MainWindow();
		CardData dragon = makeIcbCard("Silver Dragon", "Wind", "Monster", SILVER_DRAGON_SHIELD);
		CardData ally   = makeIcbCard("Zu", "Wind", "Monster", "");
		mw.placeCardInMonsterZone(dragon);
		mw.placeCardInMonsterZone(ally);

		for (boolean bySummon : new boolean[] { true, false }) {
			assertTrue(mw.isProtectedFromChoice(ally, true, false, bySummon, null),
					"the other Monster is shielded from both");
			assertFalse(mw.isProtectedFromChoice(dragon, true, false, bySummon, null),
					"\"other than Silver Dragon\" leaves the printing card exposed");
			assertFalse(mw.isProtectedFromChoice(ally, true, true, bySummon, null),
					"opponent-scoped — its own controller may still choose it");
		}
	}

	@Test
	void nimbusShieldsBraveForwardsFromAbilitiesOnly() {
		MainWindow mw = new MainWindow();
		CardData nimbus = makeIcbCard("White Tiger l'Cie Nimbus", "Ice", "Forward", NIMBUS_SHIELD);
		CardData brave  = makeForwardWithTraits("Bravo", "Ice", 7000, Set.of(CardData.Trait.BRAVE));
		CardData meek   = makeForwardWithTraits("Meek",  "Ice", 7000, Set.of());
		placeP1Forward(mw, nimbus);
		placeP1Forward(mw, brave);
		placeP1Forward(mw, meek);

		assertTrue(mw.isProtectedFromChoice(brave, true, false, false, null), "abilities are blocked");
		assertFalse(mw.isProtectedFromChoice(brave, true, false, true, null),
				"Summons are not — this printing names abilities only");
		assertFalse(mw.isProtectedFromChoice(meek, true, false, false, null), "no Brave, no shield");
		assertFalse(mw.isProtectedFromChoice(nimbus, true, false, false, null), "and not the printing card");
	}

	@Test
	void nimbusFollowsBraveThatWasGrantedRatherThanPrinted() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeIcbCard("White Tiger l'Cie Nimbus", "Ice", "Forward", NIMBUS_SHIELD));
		CardData meek = makeForwardWithTraits("Meek", "Ice", 7000, Set.of());
		placeP1Forward(mw, meek);
		assertFalse(mw.isProtectedFromChoice(meek, true, false, false, null));

		mw.p1ForwardTempTraits.get(1).add(CardData.Trait.BRAVE);

		assertTrue(mw.isProtectedFromChoice(meek, true, false, false, null),
				"the filter reads current traits, so a granted Brave brings the shield with it");
	}

	// =========================================================================================
	// Yuffie 3-069C: "If you control Card Name Vincent, the cost for playing Yuffie onto the field
	// becomes 0." Every other self-cost modifier shifts the printed cost by a delta; this one
	// replaces it. So SelfCostModifier gained a setsToCost mode that stays out of the delta
	// arithmetic — its scaling type is read only as the condition that switches it on. The
	// condition itself (IF_CONTROL_NAME) already existed for the "reduced by N" printings.
	//
	// Jack Garland 29-123R: "cannot be blocked by a Monster that is also a Forward" — a restriction
	// on the blocker's card type, barring exactly the Monsters an effect turned into Forwards, and
	// leaving Backups acting as Forwards alone since those are not Monsters.
	// =========================================================================================

	private static final String YUFFIE_FREE_WITH_VINCENT =
			"If you control Card Name Vincent, the cost for playing Yuffie onto the field becomes 0.";
	private static final String GARLAND_NO_MONSTER_BLOCK =
			"Jack Garland cannot be blocked by a Monster that is also a Forward.";

	/** Yuffie as printed: cost 2, with her self-cost modifier parsed from her text. */
	private static CardData makeYuffie() {
		return new CardData(null, "Yuffie", "Wind", 2, 5000, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), CardData.parseFieldAbilities(YUFFIE_FREE_WITH_VINCENT, "Forward"),
				List.of(), List.of(), List.of(), List.of(),
				CardData.parseSelfCostModifiers(YUFFIE_FREE_WITH_VINCENT),
				List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, YUFFIE_FREE_WITH_VINCENT);
	}

	@Test
	void theCostReplacementParsesAsASetToRatherThanADelta() {
		List<SelfCostModifier> mods = CardData.parseSelfCostModifiers(YUFFIE_FREE_WITH_VINCENT);

		assertEquals(1, mods.size(), "\"becomes 0\" used to fall outside the self-cost pattern entirely");
		SelfCostModifier mod = mods.get(0);
		assertEquals(0, mod.setsToCost(), "the replacement value, not a reduction amount");
		assertEquals(SelfCostModifier.ScalingType.IF_CONTROL_NAME, mod.scalingType());
		assertEquals("Vincent", mod.param1());
		assertEquals(0, mod.amountPerUnit(), "a replacement contributes nothing to the delta arithmetic");
		assertFalse(mod.isIncrease());
	}

	@Test
	void yuffieCostsHerPrintedCostWithoutVincent() {
		MainWindow mw = new MainWindow();
		assertEquals(2, mw.effectiveCastCost(makeYuffie()));

		placeP1Forward(mw, makeForward("Cid", "Wind", 3, 7000));
		assertEquals(2, mw.effectiveCastCost(makeYuffie()), "some other Forward is not Vincent");
	}

	@Test
	void yuffieIsFreeWhileVincentIsOnTheField() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Vincent", "Wind", 4, 8000));

		assertEquals(0, mw.effectiveCastCost(makeYuffie()));
	}

	@Test
	void aVincentOnTheBackupRowAlsoMakesYuffieFree() {
		// IF_CONTROL_NAME reads Forwards and Backups alike — "you control Card Name Vincent" names
		// no card type.
		MainWindow mw = new MainWindow();
		mw.placeCardInFirstBackupSlot(makeJobCard("Vincent", "Wind", "Backup", "Shinra"));

		assertEquals(0, mw.effectiveCastCost(makeYuffie()));
	}

	@Test
	void theCostLineLeavesYuffiesOtherFieldAbilityIntact() {
		// The cost sentence is claimed by the self-cost system and so drops out of fieldAbilities();
		// the grant printed beside it on the real card must be unaffected.
		String full = YUFFIE_FREE_WITH_VINCENT
				+ "[[br]] The Card Name Vincent you control gains +1000 power and Brave.";

		assertTrue(CardData.parseSelfCostModifiers(full).size() == 1, "the cost line still parses");
		assertEquals(1, CardData.parseFieldPowerGrants(full, "Forward").size(),
				"and the grant beside it is untouched");
	}

	@Test
	void jackGarlandBarsMonstersThatBecameForwards() {
		MainWindow mw = new MainWindow();
		CardData garland = makeIcbCard("Jack Garland", "Lightning", "Forward", GARLAND_NO_MONSTER_BLOCK);

		assertTrue(mw.barsMonsterForwardBlockers(garland));
		assertFalse(mw.barsMonsterForwardBlockers(makeForward("Plain", "Fire", 3, 7000)));

		mw.lostAbilitiesCards.add(garland);
		assertFalse(mw.barsMonsterForwardBlockers(garland), "the restriction goes with the abilities");
	}

	@Test
	void aMonsterActingAsAForwardCannotBeDeclaredAgainstJackGarland() {
		MainWindow mw = new MainWindow();
		CardData monster = makeForwardWithTraits("Chocobo", "Wind", 5000, Set.of());
		mw.placeCardInMonsterZone(monster);
		mw.p1MonsterTempForwardPower.put(monster, 5000);   // an effect made it a Forward
		mw.placeP2CardInForwardZone(makeForward("Plain", "Fire", 3, 7000));

		// P2 attacks with an ordinary Forward: the Monster may block.
		mw.pendingP2Attacker    = mw.p2ForwardCards.get(0);
		mw.pendingP2AttackerIdx = 0;
		assertTrue(mw.isMonsterBlockSelectable(0), "nothing bars it yet");

		// Swap the attacker for Jack Garland and the same Monster is out.
		CardData garland = makeIcbCard("Jack Garland", "Lightning", "Forward", GARLAND_NO_MONSTER_BLOCK);
		mw.p2ForwardCards.set(0, garland);
		mw.pendingP2Attacker = garland;

		assertFalse(mw.isMonsterBlockSelectable(0),
				"the Monster is a Forward only by grant, which is exactly what the text names");
	}

	// =========================================================================================
	// Four unrelated standing restrictions, one card each.
	//
	// Berserker 3-091C "cannot form parties" — a restriction on grouping, not on attacking: it
	// still attacks alone, so only a selection of two or more is barred.
	//
	// Elena 11-088R is phrased as a permission ("can only attack if …") with two arms that differ
	// in count and filter, so it is read directly rather than through ControlCondition. The second
	// arm's "other than Elena" is load-bearing: Elena is a Member of the Turks herself.
	//
	// Tulien 21-072H boosts battle damage from every Forward you control — the unfiltered form of
	// the Element-scoped boost that already existed.
	//
	// Ba'Gamnan 2-088C needed no wiring at all: DamageResolver has read this pattern since before
	// this session. Its test pins behaviour that was already correct, and the recognizer entry
	// closes a reporting gap.
	// =========================================================================================

	private static final String BERSERKER_NO_PARTY = "Berserker cannot form parties.";
	private static final String ELENA_ATTACK_GATE =
			"Elena can only attack if you control 4 or more Forwards, "
			+ "or if you control a Job Member of the Turks Forward other than Elena.";
	private static final String TULIEN_BATTLE_BOOST =
			"If a Forward you control deals battle damage to a Forward, the damage increases by 2000 instead.";
	private static final String BAGAMNAN_NO_PLAYER_DAMAGE =
			"If Ba'Gamnan deals damage to your opponent, the damage becomes 0 instead.";

	/** A Forward carrying {@code job} whose field abilities are parsed from {@code text}. */
	private static CardData makeJobFieldForward(String name, String element, String job, String text) {
		return new CardData(null, name, element, 3, 7000, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), CardData.parseFieldAbilities(text, "Forward"),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				job, null, null, text);
	}

	@Test
	void aCardThatCannotFormPartiesMayStillAttackAlone() {
		MainWindow mw = new MainWindow();
		CardData berserker = makeIcbCard("Berserker", "Earth", "Forward", BERSERKER_NO_PARTY);
		placeP1Forward(mw, berserker);
		placeP1Forward(mw, makeForward("Ally", "Earth", 3, 7000));

		assertTrue(mw.cannotFormParties(berserker));
		assertTrue(mw.canFormValidParty(true, List.of(0)), "attacking by itself is not a party");
		assertFalse(mw.canFormValidParty(true, List.of(0, 1)), "but it may not be grouped");
		assertFalse(mw.canFormValidParty(true, List.of(1, 0)), "in either order");
		assertTrue(mw.canFormValidParty(true, List.of(1)), "the ally is unaffected");
	}

	@Test
	void theNoPartyRestrictionGoesWithTheCardsAbilities() {
		MainWindow mw = new MainWindow();
		CardData berserker = makeIcbCard("Berserker", "Earth", "Forward", BERSERKER_NO_PARTY);
		placeP1Forward(mw, berserker);
		placeP1Forward(mw, makeForward("Ally", "Earth", 3, 7000));
		mw.lostAbilitiesCards.add(berserker);

		assertTrue(mw.canFormValidParty(true, List.of(0, 1)), "with its abilities gone, so is the restriction");
	}

	@Test
	void elenaCannotAttackWithNeitherArmSatisfied() {
		MainWindow mw = new MainWindow();
		CardData elena = makeJobFieldForward("Elena", "Lightning", "Member of the Turks", ELENA_ATTACK_GATE);
		placeP1Forward(mw, elena);

		assertTrue(mw.isFieldAbilityCannotAttack(elena, true),
				"one Forward, and the only Member of the Turks is Elena herself");
	}

	@Test
	void elenaAttacksOnceTheForwardCountArmIsMet() {
		MainWindow mw = new MainWindow();
		CardData elena = makeJobFieldForward("Elena", "Lightning", "Member of the Turks", ELENA_ATTACK_GATE);
		placeP1Forward(mw, elena);
		for (int i = 0; i < 3; i++) placeP1Forward(mw, makeForward("Body" + i, "Fire", 2, 5000));

		assertFalse(mw.isFieldAbilityCannotAttack(elena, true), "4 Forwards including Elena clears the first arm");
	}

	@Test
	void elenaAttacksOnceAnotherTurkIsOnTheField() {
		MainWindow mw = new MainWindow();
		CardData elena = makeJobFieldForward("Elena", "Lightning", "Member of the Turks", ELENA_ATTACK_GATE);
		placeP1Forward(mw, elena);
		placeP1Forward(mw, makeJobCard("Reno", "Lightning", "Forward", "Member of the Turks"));

		assertFalse(mw.isFieldAbilityCannotAttack(elena, true), "two Forwards only — the Job arm is what clears it");
	}

	@Test
	void elenaDoesNotSatisfyHerOwnJobArm() {
		MainWindow mw = new MainWindow();
		CardData elena = makeJobFieldForward("Elena", "Lightning", "Member of the Turks", ELENA_ATTACK_GATE);
		placeP1Forward(mw, elena);
		placeP1Forward(mw, makeJobCard("Rude", "Lightning", "Forward", "Soldier"));

		assertTrue(mw.isFieldAbilityCannotAttack(elena, true),
				"\"other than Elena\" is what stops her own Job from clearing the gate");
	}

	@Test
	void tulienBoostsBattleDamageFromEveryForwardYouControl() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeIcbCard("Tulien", "Earth", "Forward", TULIEN_BATTLE_BOOST));
		placeP1Forward(mw, makeForward("Ally", "Fire", 3, 7000));
		CardData victim = makeForward("Victim", "Ice", 3, 7000);
		mw.placeP2CardInForwardZone(victim);

		assertEquals(9000, mw.modifyOutgoingCombatDamage(true, 1, 7000, victim),
				"the boost is not scoped to an Element, so the Fire ally gets it");
		assertEquals(7000, mw.modifyOutgoingCombatDamage(false, 0, 7000, mw.p1ForwardCards.get(1)),
				"\"you control\" keeps it off the opponent's Forwards");
	}

	@Test
	void baGamnanDealsNoPlayerDamage() {
		// Regression cover for a rule DamageResolver already enforced — see the section note.
		MainWindow mw = new MainWindow();
		CardData bagamnan = makeIcbCard("Ba'Gamnan", "Earth", "Forward", BAGAMNAN_NO_PLAYER_DAMAGE);
		placeP1Forward(mw, bagamnan);

		assertEquals(0, mw.combatDamagePointsToOpponent(bagamnan));
		assertEquals(1, mw.combatDamagePointsToOpponent(makeForward("Plain", "Fire", 3, 7000)),
				"and an ordinary Forward still deals its 1 point");
	}

	@Test
	void theQualifiedPlayerDamagePrintingIsNotTreatedAsTheUnconditionalOne() {
		// Lightning 26-098L's "If Lightning forming a party deals damage…" matches the pattern with
		// card="Lightning forming a party"; the name check is the only thing keeping it out.
		MainWindow mw = new MainWindow();
		CardData lightning = makeIcbCard("Lightning", "Lightning", "Forward",
				"If Lightning forming a party deals damage to your opponent, the damage becomes 2 instead.");
		placeP1Forward(mw, lightning);

		assertEquals(1, mw.combatDamagePointsToOpponent(lightning),
				"the party qualifier is unimplemented, so it must not be applied unconditionally");
	}

	// =========================================================================================
	// The two remaining combat compulsions, both standing rather than turn-scoped.
	//
	// Field-wide must-attack — "All Forwards must attack once per turn if possible." (Layle
	// 16-083H) and the "opponent controls" printing (Jack Garland 24-079L) — is the attack-side
	// twin of the block form and shares its side-scoping, so both read one helper.
	//
	// Self-named must-block — "Ricard must block if possible." (6-103H) and its reversed printing
	// "If possible, Cecil must block." (2-129L) — names no attacker, so it rides the existing
	// per-Forward path rather than the turn-scoped index set: that path already checks the
	// compelled Forward can block before restricting the choice, which is what "if possible" asks.
	//
	// The self-named must-attack form (Berserker, Umaro, Reddas — seven printings) is the same
	// sentence with "attack" in it and lands on the mechanism Roche 29-076H's granted version
	// already used, so it is wired here too.
	// =========================================================================================

	private static final String LAYLE_MUST_ATTACK   = "All Forwards must attack once per turn if possible.";
	private static final String GARLAND_MUST_ATTACK = "The Forwards opponent controls must attack once per turn if possible.";
	private static final String UMARO_MUST_ATTACK   = "Umaro must attack once per turn if possible.";
	private static final String REDDAS_MUST_ATTACK  = "Reddas must attack at least once per turn if possible.";
	private static final String RICARD_MUST_BLOCK   = "Ricard must block if possible.";
	private static final String CECIL_MUST_BLOCK    = "If possible, Cecil must block.";

	/** P1's attack phase at the declaration sub-step, with {@code fwds} placed and not summoning-sick. */
	private static MainWindow p1AttackPhaseWith(MainWindow mw, CardData... fwds) {
		enterP1AttackPhase(mw);
		mw.attackSubStep = 1;
		for (CardData f : fwds) {
			mw.placeCardInForwardZone(f);
			mw.p1ForwardPlayedOnTurn.set(mw.p1ForwardCards.size() - 1, 0);
		}
		return mw;
	}

	@Test
	void theFieldWideAttackCompulsionScopesLikeItsBlockTwin() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeIcbCard("Jack Garland", "Lightning", "Forward", GARLAND_MUST_ATTACK));

		assertFalse(mw.forwardsMustAttack(true), "\"opponent controls\" does not bind its own side");
		assertTrue(mw.forwardsMustAttack(false));

		MainWindow all = new MainWindow();
		all.placeP2CardInForwardZone(makeIcbCard("Layle", "Earth", "Forward", LAYLE_MUST_ATTACK));
		assertTrue(all.forwardsMustAttack(true));
		assertTrue(all.forwardsMustAttack(false), "\"All Forwards\" binds both sides");
	}

	@Test
	void anOpponentsGarlandForcesYourForwardIntoTheAttack() {
		MainWindow mw = new MainWindow();
		p1AttackPhaseWith(mw, makeForward("Grunt", "Fire", 2, 5000));
		assertEquals(-1, mw.p1ForwardCompelledToAttackIdx(), "nothing compels it yet");

		mw.placeP2CardInForwardZone(makeIcbCard("Jack Garland", "Lightning", "Forward", GARLAND_MUST_ATTACK));

		assertEquals(0, mw.p1ForwardCompelledToAttackIdx(),
				"the compulsion is printed across the field, not on the Forward it binds");

		mw.recordAttackDeclared(mw.p1ForwardCards.get(0));

		assertEquals(-1, mw.p1ForwardCompelledToAttackIdx(), "\"once per turn\" — one attack settles it");
	}

	@Test
	void aSelfNamedAttackCompulsionIsHonouredInBothItsPrintings() {
		for (String[] card : new String[][] { { "Umaro", UMARO_MUST_ATTACK }, { "Reddas", REDDAS_MUST_ATTACK } }) {
			MainWindow mw = new MainWindow();
			p1AttackPhaseWith(mw, makeIcbCard(card[0], "Ice", "Forward", card[1]));

			assertTrue(mw.selfMustAttackOncePerTurn(mw.p1ForwardCards.get(0)), card[0]);
			assertEquals(0, mw.p1ForwardCompelledToAttackIdx(), card[0] + " must be sent in");

			mw.recordAttackDeclared(mw.p1ForwardCards.get(0));
			assertEquals(-1, mw.p1ForwardCompelledToAttackIdx(), card[0] + " has now attacked");
		}
	}

	@Test
	void aSelfNamedCompulsionIgnoresAnAllyCarryingTheText() {
		// The name in the text has to be the carrier's own — otherwise a Forward standing next to
		// Umaro would be swept into his compulsion.
		MainWindow mw = new MainWindow();
		CardData umaro = makeIcbCard("Umaro", "Ice", "Forward", UMARO_MUST_ATTACK);
		CardData ally  = makeForward("Ally", "Ice", 2, 5000);
		p1AttackPhaseWith(mw, umaro, ally);

		assertTrue(mw.selfMustAttackOncePerTurn(umaro));
		assertFalse(mw.selfMustAttackOncePerTurn(ally), "the ally carries no such ability");

		mw.recordAttackDeclared(umaro);
		assertEquals(-1, mw.p1ForwardCompelledToAttackIdx(), "the ally is under no compulsion of its own");
	}

	@Test
	void bothPrintingsOfTheSelfNamedBlockCompulsionBind() {
		MainWindow mw = new MainWindow();
		CardData ricard = makeIcbCard("Ricard", "Lightning", "Forward", RICARD_MUST_BLOCK);
		CardData cecil  = makeIcbCard("Cecil",  "Water",     "Forward", CECIL_MUST_BLOCK);
		CardData attacker = makeForward("Attacker", "Fire", 3, 7000);

		assertTrue(mw.forwardCompelledToBlock(ricard, attacker), "\"[card] must block if possible.\"");
		assertTrue(mw.forwardCompelledToBlock(cecil,  attacker), "\"If possible, [card] must block.\"");
		// It names no attacker, so it binds against whatever is attacking.
		assertTrue(mw.forwardCompelledToBlock(ricard, makeForward("Someone Else", "Ice", 1, 3000)));
	}

	@Test
	void theGrantedThisForwardWordingIsNotTreatedAsSelfNamed() {
		// Tulien 21-072H hands out "This Forward must block if possible." The self-named path must
		// not claim it — the turn-scoped index set is what carries that grant.
		MainWindow mw = new MainWindow();
		CardData granted = makeIcbCard("Recipient", "Fire", "Forward",
				"This Forward must block if possible.");

		assertFalse(mw.forwardCompelledToBlock(granted, makeForward("Attacker", "Fire", 3, 7000)),
				"\"This Forward\" is not the carrier's name, so the self-named path leaves it alone");
	}

	@Test
	void bothStandingCompulsionsGoWithTheCardsAbilities() {
		MainWindow mw = new MainWindow();
		CardData ricard = makeIcbCard("Ricard", "Lightning", "Forward", RICARD_MUST_BLOCK);
		CardData umaro  = makeIcbCard("Umaro",  "Ice",       "Forward", UMARO_MUST_ATTACK);
		CardData attacker = makeForward("Attacker", "Fire", 3, 7000);
		assertTrue(mw.forwardCompelledToBlock(ricard, attacker));
		assertTrue(mw.selfMustAttackOncePerTurn(umaro));

		mw.lostAbilitiesCards.add(ricard);
		mw.lostAbilitiesCards.add(umaro);

		assertFalse(mw.forwardCompelledToBlock(ricard, attacker));
		assertFalse(mw.selfMustAttackOncePerTurn(umaro));
	}

	// =========================================================================================
	// Element- and trait-filtered bare field grants: "The Multi-Element Forwards you control gain
	// +1000 power." (13-096R Nichol) and "The Forwards with Brave other than Ash you control gain
	// +3000 power." (21-062H Ash). FIELD_GRANT_BARE_PATTERN listed the eight printed elements but
	// not the "Multi-Element" pseudo-element, and had no slot at all for a "with Trait" filter, so
	// both texts matched nothing and each card silently granted nobody anything. The trait filter
	// is evaluated against the target's *current* traits, not only its printed ones — Ash's own
	// Dull ability hands Brave to a Forward, and that Forward must then draw the +3000.
	// =========================================================================================

	private static final String NICHOL_GRANT =
			"The Multi-Element Forwards you control gain +1000 power.";
	private static final String ASH_GRANT =
			"The Forwards with Brave other than Ash you control gain +3000 power.";

	@Test
	void theMultiElementPseudoElementIsAcceptedAsAGrantFilter() {
		List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(NICHOL_GRANT, "Forward");

		assertEquals(1, grants.size(), "the grant must parse — the bare pattern used to reject it");
		FieldPowerGrant g = grants.get(0);
		assertEquals("Multi-Element", g.elementFilter());
		assertTrue(g.inclForwards());
		assertFalse(g.inclBackups(), "Backups are outside the named target type");
		assertEquals(1000, g.powerBonus());
	}

	@Test
	void aMultiElementGrantBoostsOnlyCardsCarryingMoreThanOneElement() {
		MainWindow mw = new MainWindow();
		placeP1GrantBackup(mw, NICHOL_GRANT);

		placeP1Forward(mw, makeForward("Duo",  "Fire/Ice", 3, 7000)); // idx 0
		placeP1Forward(mw, makeForward("Mono", "Fire",     3, 7000)); // idx 1

		assertEquals(8000, mw.effectiveP1ForwardPower(0), "two elements is Multi-Element");
		assertEquals(7000, mw.effectiveP1ForwardPower(1), "one element is not");
	}

	@Test
	void aTraitFilteredGrantParsesTheTraitAndTheExclusionTogether() {
		List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(ASH_GRANT, "Forward");

		assertEquals(1, grants.size(), "\"with Brave\" used to leave the whole text unmatched");
		FieldPowerGrant g = grants.get(0);
		assertEquals(Set.of(CardData.Trait.BRAVE), g.traitFilter());
		assertEquals("Ash", g.exceptCardName(), "the exclusion still parses behind the trait clause");
		assertEquals(3000, g.powerBonus());
		assertNull(g.elementFilter(), "no element word means no element filter");
	}

	@Test
	void aTraitFilteredGrantSkipsForwardsWithoutTheTraitAndTheNamedException() {
		MainWindow mw = new MainWindow();
		placeP1GrantBackup(mw, ASH_GRANT);

		// Ash prints Brave itself, so only "other than Ash" keeps it off its own grant.
		placeP1Forward(mw, makeForwardWithTraits("Ash",   "Earth", 7000, Set.of(CardData.Trait.BRAVE)));
		placeP1Forward(mw, makeForwardWithTraits("Bravo", "Earth", 7000, Set.of(CardData.Trait.BRAVE)));
		placeP1Forward(mw, makeForwardWithTraits("Meek",  "Earth", 7000, Set.of()));

		assertEquals(7000, mw.effectiveP1ForwardPower(0), "the grant excludes the card that prints it");
		assertEquals(10000, mw.effectiveP1ForwardPower(1), "a Brave ally is boosted");
		assertEquals(7000, mw.effectiveP1ForwardPower(2), "a Forward without Brave is not");
	}

	@Test
	void aTraitFilteredGrantSeesBraveThatWasGrantedRatherThanPrinted() {
		MainWindow mw = new MainWindow();
		placeP1GrantBackup(mw, ASH_GRANT);
		placeP1Forward(mw, makeForwardWithTraits("Meek", "Earth", 7000, Set.of()));

		mw.p1ForwardTempTraits.get(0).add(CardData.Trait.BRAVE);

		assertEquals(10000, mw.effectiveP1ForwardPower(0),
				"Ash's own Dull ability grants Brave until end of turn — the filter has to see it");
	}

	@Test
	void aTraitFilteredGrantDropsAForwardWhoseTraitWasRemoved() {
		MainWindow mw = new MainWindow();
		placeP1GrantBackup(mw, ASH_GRANT);
		placeP1Forward(mw, makeForwardWithTraits("Bravo", "Earth", 7000, Set.of(CardData.Trait.BRAVE)));

		mw.p1ForwardRemovedTraits.get(0).add(CardData.Trait.BRAVE);

		assertEquals(7000, mw.effectiveP1ForwardPower(0),
				"printed Brave that has been stripped no longer satisfies \"with Brave\"");
	}

	@Test
	void anUnfilteredBareGrantIsStillBlindToElementAndTraits() {
		// Regression on the widening: both new groups are optional, so the plain form must keep
		// granting to every Forward regardless of element count or traits.
		MainWindow mw = new MainWindow();
		placeP1GrantBackup(mw, "The Forwards you control gain +1000 power.");

		placeP1Forward(mw, makeForward("Mono", "Fire", 3, 7000));
		placeP1Forward(mw, makeForwardWithTraits("Bravo", "Fire", 7000, Set.of(CardData.Trait.BRAVE)));

		FieldPowerGrant g = CardData.parseFieldPowerGrants(
				"The Forwards you control gain +1000 power.", "Forward").get(0);
		assertNull(g.elementFilter());
		assertTrue(g.traitFilter().isEmpty());
		assertEquals(8000, mw.effectiveP1ForwardPower(0));
		assertEquals(8000, mw.effectiveP1ForwardPower(1));
	}

	// =========================================================================================
	// Element-filtered grants that hand out more than power — Poppy 18-048R, whose three field
	// abilities boost a different element each. Two are "+1000 power and <Trait>", which the
	// bare grant pattern could not express (it stopped at the power) and the Job/Category pattern
	// would not reach (Poppy names no Job). The third grants a quoted ability rather than a trait,
	// which is stored as a condition-less IfControlBoost carrying a cost filter — that path
	// existed for Vaan 15-044L's Job form and only needed the element branch.
	// =========================================================================================

	private static final String POPPY_TEXT =
			"The Wind Forwards you control gain \"This Forward cannot be blocked by a Forward of cost 3 or more.\"[[br]]"
			+ "   The Ice Forwards you control gain +1000 power and First Strike.[[br]]"
			+ "   The Earth Forwards you control gain +1000 power and Brave.";

	/** Builds a Forward whose field power grants and IfControlBoosts are both parsed from {@code text}. */
	private static CardData makeGrantForward(String name, String element, String text) {
		return new CardData(null, name, element, 3, 7000, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), CardData.parseFieldAbilities(text, "Forward"),
				CardData.parseIfControlBoosts(text, "Forward"),
				CardData.parseFieldPowerGrants(text, "Forward"),
				List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	@Test
	void anElementGrantCarriesBothItsPowerAndItsTrait() {
		List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(POPPY_TEXT, "Forward");

		assertEquals(2, grants.size(), "the two power grants parse; the third line is an ICB");
		FieldPowerGrant ice = grants.get(0);
		assertEquals("Ice", ice.elementFilter());
		assertEquals(1000, ice.powerBonus());
		assertEquals(Set.of(CardData.Trait.FIRST_STRIKE), ice.grantedTraits(),
				"the trailing \"and First Strike\" used to be dropped with the whole match");
		FieldPowerGrant earth = grants.get(1);
		assertEquals("Earth", earth.elementFilter());
		assertEquals(1000, earth.powerBonus());
		assertEquals(Set.of(CardData.Trait.BRAVE), earth.grantedTraits());
	}

	@Test
	void eachElementGrantReachesOnlyItsOwnElement() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeGrantForward("Poppy", "Wind", POPPY_TEXT)); // idx 0
		placeP1Forward(mw, makeForward("Icer",  "Ice",   3, 7000));        // idx 1
		placeP1Forward(mw, makeForward("Digger", "Earth", 3, 7000));       // idx 2

		assertEquals(8000, mw.effectiveP1ForwardPower(1));
		assertTrue(mw.effectiveP1HasTrait(1, CardData.Trait.FIRST_STRIKE), "Ice gets First Strike");
		assertFalse(mw.effectiveP1HasTrait(1, CardData.Trait.BRAVE), "and not the Earth grant's Brave");

		assertEquals(8000, mw.effectiveP1ForwardPower(2));
		assertTrue(mw.effectiveP1HasTrait(2, CardData.Trait.BRAVE), "Earth gets Brave");
		assertFalse(mw.effectiveP1HasTrait(2, CardData.Trait.FIRST_STRIKE));

		assertEquals(7000, mw.effectiveP1ForwardPower(0), "Poppy is Wind — neither power grant hits it");
	}

	@Test
	void theElementFilteredUnblockableGrantParsesAsACostFilteredBoost() {
		List<IfControlBoost> icbs = CardData.parseIfControlBoosts(POPPY_TEXT, "Forward");

		IfControlBoost cnb = icbs.stream().filter(i -> i.cannotBeBlockedByCost() != null).findFirst()
				.orElseThrow(() -> new AssertionError("the Wind line must produce a cost-filtered ICB"));
		assertArrayEquals(new int[] { 3, 1 }, cnb.cannotBeBlockedByCost(), "cost 3 or more");
		assertEquals("Wind", cnb.targetFilter().elementFilter(),
				"the element branch feeds the same target filter the Job form uses");
		assertTrue(cnb.conditions().isEmpty(), "the grant is always active — no \"If you control\" gate");
	}

	@Test
	void onlyWindForwardsShakeOffTheExpensiveBlocker() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeGrantForward("Poppy", "Wind", POPPY_TEXT)); // idx 0
		placeP1Forward(mw, makeForward("Gale", "Wind", 3, 7000));          // idx 1
		placeP1Forward(mw, makeForward("Icer", "Ice",  3, 7000));          // idx 2

		assertTrue(mw.p1AttackerCostFiltersExclude(1, 3), "a Wind ally cannot be blocked by cost 3");
		assertTrue(mw.p1AttackerCostFiltersExclude(0, 4),
				"Poppy is Wind and the grant names no exception, so it covers itself too");
		assertFalse(mw.p1AttackerCostFiltersExclude(1, 2), "a cheaper blocker is still allowed");
		assertFalse(mw.p1AttackerCostFiltersExclude(2, 3), "and the Ice Forward gets no such grant");
	}

	// =========================================================================================
	// "For each Forward other than Bartz you control with the same Job as Bartz, Bartz gains
	// +2000 power." (18-047H). Every other scaling filter resolves from card text — a Job name, a
	// Category, an element — but this one compares against whatever Jobs the source has *on the
	// field*, and Bartz's own enters-the-field ability names a third Job onto himself. So the
	// filter is carried as a flag and resolved at count time against permanentExtraJobMap, rather
	// than being frozen into a job string at parse time.
	// =========================================================================================

	private static final String BARTZ_TEXT =
			"When Bartz enters the field, name 1 Job and 1 Element other than Light or Dark. "
			+ "Bartz gains named Job and Element. (This effect does not end at the end of the turn.)[[br]]"
			+ "   For each Forward other than Bartz you control with the same Job as Bartz, Bartz gains +2000 power.[[br]]"
			+ "   Bartz cannot be chosen by your opponent's Summons or abilities that share its Element.";

	/** Builds a Forward carrying {@code job} whose scaling self boosts are parsed from {@code text}. */
	private static CardData makeJobScalingForward(String name, String element, int power,
			String job, String text) {
		return new CardData(null, name, element, 2, power, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), CardData.parseFieldAbilities(text, "Forward"),
				List.of(), List.of(), CardData.parseScalingSelfPowerBoosts(text, "Forward", name),
				List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				job, null, null, text);
	}

	private static CardData placeBartz(MainWindow mw) {
		CardData bartz = makeJobScalingForward("Bartz", "Wind", 5000, "Wanderer/Warrior of Light", BARTZ_TEXT);
		placeP1Forward(mw, bartz);
		return bartz;
	}

	@Test
	void theSameJobQualifierParsesAsAFlagRatherThanAJobName() {
		List<ScalingSelfPowerBoost> boosts =
				CardData.parseScalingSelfPowerBoosts(BARTZ_TEXT, "Forward", "Bartz");

		assertEquals(1, boosts.size(), "the trailing \"with the same Job as\" used to defeat the match");
		ScalingSelfPowerBoost b = boosts.get(0);
		assertEquals(ScalingSelfPowerBoost.Source.OTHER_FORWARDS_YOU_CONTROL, b.source());
		assertEquals(2000, b.perUnit());
		assertTrue(b.sameJobAsSelf());
		assertNull(b.jobFilter(), "the Job is not knowable at parse time, so none is recorded");
	}

	@Test
	void bartzCountsOnlyTheForwardsSharingOneOfHisJobs() {
		MainWindow mw = new MainWindow();
		placeBartz(mw);
		placeP1Forward(mw, makeJobCard("Faris",  "Wind",  "Forward", "Wanderer"));
		placeP1Forward(mw, makeJobCard("Firion", "Water", "Forward", "Warrior of Light"));
		placeP1Forward(mw, makeJobCard("Kain",   "Wind",  "Forward", "Dragoon"));

		assertEquals(9000, mw.effectiveP1ForwardPower(0),
				"5000 base, +2000 for each of the two Forwards sharing a printed Job");
	}

	@Test
	void aNamedJobWidensWhoCountsForBartz() {
		MainWindow mw = new MainWindow();
		CardData bartz = placeBartz(mw);
		placeP1Forward(mw, makeJobCard("Kain", "Wind", "Forward", "Dragoon"));

		assertEquals(5000, mw.effectiveP1ForwardPower(0), "a Dragoon shares nothing with Bartz yet");

		// What "Bartz gains named Job" does when the enters-the-field ability resolves.
		mw.permanentExtraJobMap.put(bartz, "Dragoon");

		assertEquals(7000, mw.effectiveP1ForwardPower(0),
				"the named Job counts as Bartz's own, so the Dragoon now shares one");
	}

	@Test
	void aNamedJobOnAnAllyAlsoMakesItCount() {
		MainWindow mw = new MainWindow();
		placeBartz(mw);
		CardData ally = makeJobCard("Kain", "Wind", "Forward", "Dragoon");
		placeP1Forward(mw, ally);

		mw.permanentExtraJobMap.put(ally, "Wanderer");

		assertEquals(7000, mw.effectiveP1ForwardPower(0),
				"the comparison reads granted Jobs on both sides, not just on Bartz");
	}

	@Test
	void bartzCountsNeitherHimselfNorAJoblessForward() {
		// A second Bartz cannot be tested against here: the uniqueness rule breaks the first copy
		// on arrival, so "other than Bartz" only ever has to keep Bartz from counting himself.
		MainWindow mw = new MainWindow();
		placeBartz(mw);

		assertEquals(5000, mw.effectiveP1ForwardPower(0), "alone, Bartz does not count himself");

		placeP1Forward(mw, makeForward("Drifter", "Wind", 2, 7000)); // no Job at all

		assertEquals(5000, mw.effectiveP1ForwardPower(0), "and a jobless Forward shares nothing");
	}

	@Test
	void theOpponentsMatchingJobsDoNotCountForBartz() {
		MainWindow mw = new MainWindow();
		placeBartz(mw);
		placeP2Forward(mw, makeJobCard("Faris", "Wind", "Forward", "Wanderer"));

		assertEquals(5000, mw.effectiveP1ForwardPower(0), "\"you control\" scopes the count");
	}

	// =========================================================================================
	// "If Cagnazzo deals damage or is dealt damage while dull, the damage becomes 0 instead (this
	// includes player damage)." (2-124H) — one sentence covering three damage paths, all gated on
	// the same state. The gate has to be read when the damage applies rather than when the battle
	// opens, because Cagnazzo's own "When Cagnazzo blocks, dull Cagnazzo" fires mid-battle: it
	// blocks while active, dulls itself, and then neither deals nor takes damage.
	// =========================================================================================

	private static final String CAGNAZZO_TEXT =
			"Cagnazzo cannot block Forwards forming a party.[[br]]"
			+ "When Cagnazzo blocks, dull Cagnazzo.[[br]]"
			+ "If Cagnazzo deals damage or is dealt damage while dull, the damage becomes 0 instead "
			+ "(this includes player damage).";

	private static CardData placeCagnazzo(MainWindow mw) {
		CardData cagnazzo = makeIcbCard("Cagnazzo", "Water", "Forward", CAGNAZZO_TEXT);
		placeP1Forward(mw, cagnazzo);
		return cagnazzo;
	}

	@Test
	void anActiveCagnazzoTakesDamageNormally() {
		MainWindow mw = new MainWindow();
		placeCagnazzo(mw);

		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0));
		assertEquals(5000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 5000, false, false),
				"the replacement is gated on being dull, not on carrying the ability");
	}

	@Test
	void aDullCagnazzoTakesNoDamageFromEitherSource() {
		MainWindow mw = new MainWindow();
		placeCagnazzo(mw);
		mw.p1ForwardStates.set(0, CardState.DULL);

		assertEquals(0, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 5000, false, false),
				"battle damage becomes 0");
		assertEquals(0, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 9000, true, false),
				"and so does ability damage — the text names no source");
		assertEquals(0, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 9000, true, true),
				"a replacement to 0 is not a reduction, so unreduced damage does not slip past it");
	}

	@Test
	void aDullCagnazzoDealsNoCombatDamage() {
		MainWindow mw = new MainWindow();
		placeCagnazzo(mw);
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		placeP2Forward(mw, victim);

		assertEquals(5000, mw.modifyOutgoingCombatDamage(true, 0, 5000, victim), "active, it deals its power");

		mw.p1ForwardStates.set(0, CardState.DULL);

		assertEquals(0, mw.modifyOutgoingCombatDamage(true, 0, 5000, victim), "dull, it deals nothing");
	}

	@Test
	void aDullCagnazzoDealsNoPlayerDamage() {
		MainWindow mw = new MainWindow();
		CardData cagnazzo = placeCagnazzo(mw);

		assertEquals(1, mw.combatDamagePointsToOpponent(cagnazzo), "an unblocked attack normally deals 1");

		mw.p1ForwardStates.set(0, CardState.DULL);

		assertEquals(0, mw.combatDamagePointsToOpponent(cagnazzo),
				"\"(this includes player damage)\" puts the replacement on this path too");
	}

	// =========================================================================================
	// "Summons and/or abilities of your opponent must choose X if possible." — a taunt, and the
	// targeting counterpart of the existing "Opponent must block X if possible". Seven card names
	// print it across ten printings, split between "and" and "or" with no difference in meaning,
	// so the pattern accepts both; Angeal 28-060R prints an abilities-only form that must leave
	// Summons free. Enforced by narrowing the eligible target set, so every pick the selection can
	// still make is a compelled one.
	// =========================================================================================

	private static final String YAAG_ROSCH_TEXT =
			"Summons or abilities of your opponent must choose Yaag Rosch if possible.";
	private static final String AURON_TEXT =
			"Summons and abilities of your opponent must choose Auron if possible.";
	private static final String ANGEAL_TEXT =
			"Abilities of your opponent must choose Angeal if possible.";

	/** P2 (the AI side) picks 1 Forward from P1's field, exercising the real eligibility path. */
	private static List<ForwardTarget> oppChoosesOneP1Forward(MainWindow mw) {
		return mw.buildGameContext(false).selectCharacters(1, false, true, false, null, null,
				-1, null, -1, null, true, false, false, null, null, null, null, false, null, false);
	}

	@Test
	void bothConjunctionsOfTheTauntReadTheSame() {
		MainWindow mw = new MainWindow();
		CardData yaag  = makeIcbCard("Yaag Rosch", "Water", "Backup",  YAAG_ROSCH_TEXT);
		CardData auron = makeIcbCard("Auron",      "Water", "Forward", AURON_TEXT);

		for (boolean bySummon : new boolean[] { true, false }) {
			assertTrue(mw.mustBeChosenByOpponent(yaag,  bySummon), "\"Summons or abilities\" binds both");
			assertTrue(mw.mustBeChosenByOpponent(auron, bySummon), "\"Summons and abilities\" binds both");
		}
	}

	@Test
	void theAbilitiesOnlyTauntLeavesSummonsFree() {
		MainWindow mw = new MainWindow();
		CardData angeal = makeIcbCard("Angeal", "Earth", "Forward", ANGEAL_TEXT);

		assertTrue(mw.mustBeChosenByOpponent(angeal, false), "abilities are bound");
		assertFalse(mw.mustBeChosenByOpponent(angeal, true), "Summons are not — the text does not name them");
	}

	@Test
	void anOpponentsAbilityIsForcedOntoTheTauntingForward() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Decoy A", "Fire", 3, 7000));
		placeP1Forward(mw, makeIcbCard("Auron", "Water", "Forward", AURON_TEXT));
		placeP1Forward(mw, makeForward("Decoy B", "Fire", 3, 7000));

		List<ForwardTarget> chosen = oppChoosesOneP1Forward(mw);

		assertEquals(1, chosen.size());
		assertEquals("Auron", mw.p1ForwardCards.get(chosen.get(0).idx()).name(),
				"two decoys were eligible; the taunt is what leaves only one legal pick");
	}

	@Test
	void anOpponentsSummonIsForcedOntoTheTauntingForwardToo() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Decoy", "Fire", 3, 7000));
		placeP1Forward(mw, makeIcbCard("Auron", "Water", "Forward", AURON_TEXT));
		mw.currentResolutionIsSummon = true;

		List<ForwardTarget> chosen = oppChoosesOneP1Forward(mw);

		assertEquals(1, chosen.size());
		assertEquals("Auron", mw.p1ForwardCards.get(chosen.get(0).idx()).name(),
				"\"Summons and abilities\" reaches the Summon path, which reads a different flag");
	}

	// -----------------------------------------------------------------------------------------
	// The same compulsion on the redirect path. A redirect re-chooses what an entry already on the
	// Stack is pointing at, and its candidate pool screens for immunity but used not to screen for
	// a taunt — so "The newly chosen target must be a valid choice", printed on every effect that
	// offers a free pick, was being enforced with half the rule.
	//
	// Note the direction. Every such effect says "*another* target", so the entry's current target
	// is always excluded: a taunt card already being chosen is never in the pool, and this can only
	// pull a redirect ONTO a taunt, never forbid one that moves off it. Reuses the redirect
	// section's summonChoosing/fwd/makeFaris helpers.
	// -----------------------------------------------------------------------------------------

	/** An action-ability entry belonging to {@code isP1} that has chosen exactly {@code targets}. */
	private static StackEntry abilityChoosing(boolean isP1, ForwardTarget... targets) {
		CardData src = makeForward("Prompter", "Fire", 2, 5000,
				CardData.parseActionAbilities("《0》: Choose 1 Forward. Deal it 1000 damage."));
		return new StackEntry(src, src.actionAbilities().get(0), isP1, 0, List.of(targets));
	}

	@Test
	void aRedirectPoolNarrowsToTheTauntingCard() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Caster", "Fire", 3, 7000));                  // P1 idx 0
		mw.placeP2CardInForwardZone(makeForward("Decoy", "Water", 3, 7000));         // P2 idx 0
		mw.placeP2CardInForwardZone(makeIcbCard("Auron", "Water", "Forward", AURON_TEXT)); // P2 idx 1

		// P1's Summon is choosing P1's own Forward; a free-pick redirect may point it anywhere.
		StackEntry entry = summonChoosing(makeForward("Shiva", "Ice", 2, 0), true, fwd(true, 0));

		assertEquals(List.of(fwd(false, 1)),
				mw.redirectCandidatesAnywhere(entry, mw.p1ForwardCards.get(0)),
				"Auron taunts P1's Summons, so it is the only place the redirect may land");
	}

	@Test
	void anAbilitiesOnlyTauntDoesNotNarrowASummonRedirect() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Caster", "Fire", 3, 7000));
		mw.placeP2CardInForwardZone(makeForward("Decoy", "Earth", 3, 7000));
		mw.placeP2CardInForwardZone(makeIcbCard("Angeal", "Earth", "Forward", ANGEAL_TEXT));

		StackEntry summon  = summonChoosing(makeForward("Shiva", "Ice", 2, 0), true, fwd(true, 0));
		StackEntry ability = abilityChoosing(true, fwd(true, 0));
		CardData exclude   = mw.p1ForwardCards.get(0);

		assertEquals(2, mw.redirectCandidatesAnywhere(summon, exclude).size(),
				"a Summon is not bound by an abilities-only taunt, so both P2 Forwards stay open");
		assertEquals(List.of(fwd(false, 1)), mw.redirectCandidatesAnywhere(ability, exclude),
				"the same board narrows for an ability entry");
	}

	@Test
	void aTauntBindsTheEntrysControllerNotWhoeverWorksTheRedirect() {
		MainWindow mw = new MainWindow();
		// P2 controls Faris and can move an effect choosing her onto another Water Forward — but the
		// effect is P1's, and P2's own Auron taunts it, so P2 is forced to take the hit on Auron.
		CardData faris = makeFaris();
		mw.placeP2CardInForwardZone(faris);                                                // P2 idx 0
		mw.placeP2CardInForwardZone(makeForward("Lenna", "Water", 3, 7000));               // P2 idx 1
		mw.placeP2CardInForwardZone(makeIcbCard("Auron", "Water", "Forward", AURON_TEXT)); // P2 idx 2

		StackEntry entry = summonChoosing(makeForward("Shiva", "Ice", 2, 0), true, fwd(false, 0));
		mw.gameState.pushStack(entry);

		ActionResolver.parse(faris.actionAbilities().get(0).effectText(), faris)
				.accept(mw.buildGameContext(false));

		assertEquals(List.of(fwd(false, 2)), mw.gameState.getStack().get(0).preSelectedTargets(),
				"Lenna is a legal Water Forward, but the taunt makes Auron the only valid choice");
	}

	@Test
	void aRedirectPoolIsUntouchedWhenTheTauntIsOnTheEntrysOwnSide() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeIcbCard("Auron", "Water", "Forward", AURON_TEXT)); // P1 idx 0
		placeP1Forward(mw, makeForward("Caster", "Fire", 3, 7000));               // P1 idx 1
		mw.placeP2CardInForwardZone(makeForward("Decoy", "Water", 3, 7000));      // P2 idx 0

		// The Summon belongs to P1, who also controls Auron — a taunt never binds its own controller.
		StackEntry entry = summonChoosing(makeForward("Shiva", "Ice", 2, 0), true, fwd(true, 1));

		assertEquals(2, mw.redirectCandidatesAnywhere(entry, mw.p1ForwardCards.get(1)).size(),
				"Auron and the Decoy both stay in the pool");
	}

	// -----------------------------------------------------------------------------------------
	// The other half of "The newly chosen target must be a valid choice": a redirect used to screen
	// candidates for immunity only, never for the constraints the redirected effect's own text
	// imposes. So a Summon that chose "1 Forward of cost 3 or less" could be pushed onto a cost 7
	// Forward, or onto a Backup, or onto the side of the field its text never offered.
	//
	// The constraints are replayed from one decoding of the card text (ActionResolver.targetSpec),
	// the same one that ran when the effect first chose — two decodings that could disagree is what
	// would make the rule enforceable at one moment and not the other. Each test asserts the spec
	// decodes, so a text this cannot read fails loudly instead of passing through the null fallback.
	// -----------------------------------------------------------------------------------------

	private static StackEntry summonEntryFor(String text, boolean isP1, ForwardTarget... targets) {
		CardData summon = makeSummon("Blizzard", "Ice", 2, text);
		assertNotNull(ActionResolver.targetSpec(text, summon),
				"the test is meaningless unless the effect's targeting decodes");
		return summonChoosing(summon, isP1, targets);
	}

	@Test
	void aRedirectCannotLandOnATargetTheEffectsCostFilterExcluded() {
		MainWindow mw = new MainWindow();
		mw.placeP2CardInForwardZone(makeForward("Cheap",  "Fire", 2, 5000)); // P2 idx 0 — chosen
		mw.placeP2CardInForwardZone(makeForward("Pricey", "Fire", 7, 9000)); // P2 idx 1
		mw.placeP2CardInForwardZone(makeForward("Cheap2", "Fire", 2, 5000)); // P2 idx 2

		StackEntry entry = summonEntryFor(
				"Choose 1 Forward of cost 3 or less. Deal it 5000 damage.", true, fwd(false, 0));

		assertEquals(List.of(fwd(false, 2)), mw.redirectCandidatesAnywhere(entry, mw.p2ForwardCards.get(0)),
				"the cost 7 Forward was never a legal choice for this Summon");
	}

	@Test
	void aRedirectCannotLandOnAZoneTheEffectNeverOffered() {
		MainWindow mw = new MainWindow();
		mw.placeP2CardInForwardZone(makeForward("Chosen", "Fire", 3, 7000)); // P2 idx 0 — chosen
		mw.placeP2CardInForwardZone(makeForward("Other",  "Fire", 3, 7000)); // P2 idx 1
		mw.placeP2CardInFirstBackupSlot(makeJobCard("Sage", "Fire", "Backup", "Sage"));

		StackEntry entry = summonEntryFor("Choose 1 Forward. Deal it 5000 damage.", true, fwd(false, 0));

		assertEquals(List.of(fwd(false, 1)), mw.redirectCandidatesAnywhere(entry, mw.p2ForwardCards.get(0)),
				"\"Choose 1 Forward\" never offered the Backup, so a redirect cannot reach it either");
	}

	@Test
	void aRedirectRespectsTheEffectsControlClause() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Caster's Own", "Fire", 3, 7000));    // P1 idx 0
		mw.placeP2CardInForwardZone(makeForward("Chosen", "Fire", 3, 7000)); // P2 idx 0 — chosen
		mw.placeP2CardInForwardZone(makeForward("Other",  "Fire", 3, 7000)); // P2 idx 1

		StackEntry entry = summonEntryFor(
				"Choose 1 Forward opponent controls. Deal it 5000 damage.", true, fwd(false, 0));

		assertEquals(List.of(fwd(false, 1)), mw.redirectCandidatesAnywhere(entry, mw.p2ForwardCards.get(0)),
				"\"opponent controls\" is relative to the effect's controller, so P1's own Forward is out");
	}

	@Test
	void anUndecodableEffectLeavesTheRedirectPoolAsWideAsItWas() {
		MainWindow mw = new MainWindow();
		mw.placeP2CardInForwardZone(makeForward("Chosen", "Fire", 3, 7000));
		mw.placeP2CardInForwardZone(makeForward("Other",  "Fire", 7, 9000));

		// A Summon whose text this cannot decode imposes no constraint — the fallback that keeps the
		// redirect no stricter than it was before the spec existed.
		CardData odd = makeSummon("Oddity", "Ice", 2, "Something entirely unparseable happens.");
		assertNull(ActionResolver.targetSpec(odd.summonEffect(), odd));
		StackEntry entry = summonChoosing(odd, true, fwd(false, 0));

		assertEquals(List.of(fwd(false, 1)), mw.redirectCandidatesAnywhere(entry, mw.p2ForwardCards.get(0)),
				"the cost 9000-power Forward stays available — nothing was decoded to exclude it");
	}

	@Test
	void aSelectionWiderThanTheTauntsIsLeftUnrestricted() {
		// The documented limit: one taunt cannot constrain a two-card choice, because the second
		// pick is free and the select dialog has no way to force one card and free the other. The
		// selection is left whole rather than wrongly narrowed to the taunt alone.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeIcbCard("Auron", "Water", "Forward", AURON_TEXT));
		placeP1Forward(mw, makeForward("Decoy", "Fire", 3, 7000));

		assertEquals(2, mw.buildGameContext(false).selectCharacters(2, false, true, false, null, null,
				-1, null, -1, null, true, false, false, null, null, null, null, false, null, false).size(),
				"both Forwards stay eligible, so both can be chosen");
	}

	// =========================================================================================
	// Lava Spider 8-022R: "The attacking Forwards you control gain +3000 power."
	//
	// The first same-side grant whose filter is board state rather than a card attribute, so it
	// cannot live in FieldPowerGrant.appliesToCard with the rest — MainWindow gates on it while
	// summing contributions, reading the same declared-attacker list that "while [card] is
	// attacking" abilities consult.
	// =========================================================================================

	private static final String LAVA_SPIDER_TEXT = "The attacking Forwards you control gain +3000 power.";

	/** Builds a Monster whose field abilities, ICBs and power grants are parsed from {@code text}. */
	private static CardData makeGrantMonster(String name, String element, String text) {
		return new CardData(null, name, element, 1, 0, "Monster", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), CardData.parseFieldAbilities(text, "Monster"),
				CardData.parseIfControlBoosts(text, "Monster"),
				CardData.parseFieldPowerGrants(text, "Monster"),
				List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	@Test
	void theAttackingFilterParsesAsAStateFilterNotACardFilter() {
		List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(LAVA_SPIDER_TEXT, "Monster");

		assertEquals(1, grants.size());
		FieldPowerGrant g = grants.get(0);
		assertTrue(g.attackingOnly());
		assertEquals(3000, g.powerBonus());
		assertTrue(g.inclForwards());
		assertFalse(g.affectsOpponent());
		assertTrue(g.appliesToCard(makeForward("Idle", "Fire", 3, 7000)),
				"appliesToCard cannot see the board, so it passes a Forward that is standing still");
	}

	@Test
	void anIdleForwardGetsNothingFromLavaSpider() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Ally", "Fire", 3, 7000));
		mw.placeCardInMonsterZone(makeGrantMonster("Lava Spider", "Fire", LAVA_SPIDER_TEXT));

		assertEquals(7000, mw.effectiveP1ForwardPower(0), "nothing has been declared as an attacker");
	}

	@Test
	void aDeclaredAttackerPicksTheBoostUp() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Ally", "Fire", 3, 7000));
		mw.placeCardInMonsterZone(makeGrantMonster("Lava Spider", "Fire", LAVA_SPIDER_TEXT));

		mw.p1DeclaredAttackers.add(mw.p1ForwardCards.get(0));

		assertEquals(10000, mw.effectiveP1ForwardPower(0));
	}

	@Test
	void theBoostIsAlreadyVisibleWhileAttackersAreStillBeingPicked() {
		// declaredAttackers falls back to the in-progress selection before Attack is pressed, so the
		// boost shows in the power the player is deciding against rather than appearing afterwards.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Ally", "Fire", 3, 7000));
		mw.placeCardInMonsterZone(makeGrantMonster("Lava Spider", "Fire", LAVA_SPIDER_TEXT));

		mw.p1AttackSelection.add(0);

		assertEquals(10000, mw.effectiveP1ForwardPower(0));
	}

	@Test
	void onlyTheDeclaredForwardIsBoosted() {
		// The same-field twin case — two records that compare equal, one attacking — cannot be
		// built: the uniqueness rule breaks the older copy the moment the second is seated. So the
		// identity matching in the gate is defensive, and what is testable is that a Forward left
		// out of the declaration is left out of the boost.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Runner",  "Fire", 3, 7000)); // idx 0
		placeP1Forward(mw, makeForward("Homebody", "Fire", 3, 7000)); // idx 1
		mw.placeCardInMonsterZone(makeGrantMonster("Lava Spider", "Fire", LAVA_SPIDER_TEXT));

		mw.p1DeclaredAttackers.add(mw.p1ForwardCards.get(0));

		assertEquals(10000, mw.effectiveP1ForwardPower(0));
		assertEquals(7000, mw.effectiveP1ForwardPower(1), "it stayed home");
	}

	@Test
	void theBoostLiftsWhenTheCombatEnds() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Ally", "Fire", 3, 7000));
		mw.placeCardInMonsterZone(makeGrantMonster("Lava Spider", "Fire", LAVA_SPIDER_TEXT));
		mw.p1DeclaredAttackers.add(mw.p1ForwardCards.get(0));
		assertEquals(10000, mw.effectiveP1ForwardPower(0));

		mw.p1DeclaredAttackers.clear();

		assertEquals(7000, mw.effectiveP1ForwardPower(0), "the grant is live only while the attack is");
	}

	@Test
	void theOpponentsAttackersAreNotBoosted() {
		MainWindow mw = new MainWindow();
		mw.placeCardInMonsterZone(makeGrantMonster("Lava Spider", "Fire", LAVA_SPIDER_TEXT));
		placeP2Forward(mw, makeForward("Foe", "Ice", 3, 7000));

		mw.p2DeclaredAttackers.add(mw.p2ForwardCards.get(0));

		assertEquals(7000, mw.effectiveP2ForwardPower(0), "\"you control\" — the boost does not cross the field");
	}

	// =========================================================================================
	// Faris 21-114L: "The power of the Job Pirate Forwards and Card Name Viking Forwards other
	// than Faris you control becomes 8000."
	//
	// A base-power replacement rather than a bonus, so it lands in FieldPowerGrant.basePowerSet,
	// which MainWindow substitutes for the printed power before boosts and reductions are summed
	// — those still stack on top of the replaced value, in both directions.
	//
	// The two filters are ORed, but appliesToCard ANDs job against card name, so the text emits
	// one grant per branch. Overlap is harmless: both set the same base power.
	// =========================================================================================

	private static final String FARIS_BASE_POWER =
			"The power of the Job Pirate Forwards and Card Name Viking Forwards other than Faris you control becomes 8000.";

	/** Builds a Forward carrying a job, a printed power, and grants parsed from {@code text}. */
	private static CardData makeGrantForwardWithJob(String name, String element, int power,
			String job, String text) {
		return new CardData(null, name, element, 3, power, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), CardData.parseFieldAbilities(text, "Forward"),
				CardData.parseIfControlBoosts(text, "Forward"),
				CardData.parseFieldPowerGrants(text, "Forward"),
				List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				job, null, null, text);
	}

	/** Faris on P1 idx 0, then {@code others} from idx 1 up. */
	private static MainWindow boardWithFaris(CardData... others) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeGrantForwardWithJob("Faris", "Water", 8000, "Pirate", FARIS_BASE_POWER));
		for (CardData c : others) placeP1Forward(mw, c);
		return mw;
	}

	@Test
	void theBecomesLineParsesIntoOneGrantPerFilterBranch() {
		List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(FARIS_BASE_POWER, "Forward");

		assertEquals(2, grants.size(), "job and card name are ANDed by appliesToCard, so the OR needs two");
		FieldPowerGrant job = grants.get(0);
		assertEquals("Pirate", job.jobFilter());
		assertNull(job.inclCardName());
		FieldPowerGrant name = grants.get(1);
		assertNull(name.jobFilter());
		assertEquals("Viking", name.inclCardName());
		for (FieldPowerGrant g : grants) {
			assertEquals(8000, g.basePowerSet());
			assertEquals(0, g.powerBonus(), "it replaces the base power, it does not add to it");
			assertEquals("Faris", g.exceptCardName());
			assertTrue(g.inclForwards());
			assertFalse(g.inclBackups());
		}
	}

	@Test
	void aWeakPirateIsRaisedToTheReplacedBasePower() {
		MainWindow mw = boardWithFaris(makeGrantForwardWithJob("Deckhand", "Water", 3000, "Pirate", ""));

		assertEquals(8000, mw.effectiveP1ForwardPower(1));
	}

	@Test
	void aStrongPirateIsBroughtDownToIt() {
		// "becomes" replaces the value outright — it is not a floor.
		MainWindow mw = boardWithFaris(makeGrantForwardWithJob("Captain", "Water", 9000, "Pirate", ""));

		assertEquals(8000, mw.effectiveP1ForwardPower(1));
	}

	@Test
	void theCardNameBranchReachesAForwardWithNoMatchingJob() {
		MainWindow mw = boardWithFaris(makeForward("Viking", "Water", 2, 5000));

		assertEquals(8000, mw.effectiveP1ForwardPower(1), "Card Name Viking matches with no Job at all");
	}

	@Test
	void aForwardMatchingNeitherFilterKeepsItsPrintedPower() {
		MainWindow mw = boardWithFaris(makeGrantForwardWithJob("Mage", "Water", 5000, "Black Mage", ""));

		assertEquals(5000, mw.effectiveP1ForwardPower(1));
	}

	@Test
	void farisIsExcludedFromHerOwnGrant() {
		// She is a Job Pirate herself, so only the "other than Faris" clause keeps her out; her
		// printed power here is deliberately not 8000 so the exclusion is visible.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeGrantForwardWithJob("Faris", "Water", 6000, "Pirate", FARIS_BASE_POWER));

		assertEquals(6000, mw.effectiveP1ForwardPower(0));
	}

	@Test
	void theOpponentsPiratesAreUntouched() {
		MainWindow mw = boardWithFaris();
		placeP2Forward(mw, makeGrantForwardWithJob("Foe", "Water", 3000, "Pirate", ""));

		assertEquals(3000, mw.effectiveP2ForwardPower(0), "\"you control\" — the replacement stays on one side");
	}

	@Test
	void boostsAndReductionsStackOnTopOfTheReplacedBase() {
		MainWindow mw = boardWithFaris(makeGrantForwardWithJob("Deckhand", "Water", 3000, "Pirate", ""));

		mw.p1ForwardPowerBoost.set(1, 2000);
		assertEquals(10000, mw.effectiveP1ForwardPower(1), "the boost lands on 8000, not on the printed 3000");

		mw.p1ForwardPowerReduction.set(1, 4000);
		assertEquals(6000, mw.effectiveP1ForwardPower(1));
	}

	@Test
	void aOneShotPowerReplacementOverridesTheContinuousOne() {
		// basePowerOverrides holds a replacement an effect applied at a definite moment, which
		// normally resolves after the continuous grant was already in place. Documented limit: the
		// engine keeps no timestamps, so a Faris arriving later is resolved the same way.
		MainWindow mw = boardWithFaris(makeGrantForwardWithJob("Deckhand", "Water", 3000, "Pirate", ""));

		mw.basePowerOverrides.put(mw.p1ForwardCards.get(1), 1000);

		assertEquals(1000, mw.effectiveP1ForwardPower(1));
	}

	@Test
	void thePiratesFallBackToTheirPrintedPowerWhenFarisLeaves() {
		MainWindow mw = boardWithFaris(makeGrantForwardWithJob("Deckhand", "Water", 3000, "Pirate", ""));
		assertEquals(8000, mw.effectiveP1ForwardPower(1));

		mw.lostAbilitiesCards.add(mw.p1ForwardCards.get(0));

		assertEquals(3000, mw.effectiveP1ForwardPower(1), "a passive read off the field goes when the abilities do");
	}

	// =========================================================================================
	// Adelard 17-001H: "The damage dealt by your abilities to Forwards opponent controls cannot
	// be reduced."
	//
	// The field-wide, permanent version of what "This damage cannot be reduced." does for a single
	// damage sentence, so it routes into the same `unreduced` path in modifyIncomingDamage rather
	// than adding a second notion of unreducible damage.
	//
	// "your abilities" excludes Summons — the corpus writes "Summons or abilities" when it means
	// both, and the engine already reads a bare "ability" that way for nullifyAbilityOnlyDmgSet.
	// Cu Chaspel 11-004C prints the turn-scoped, source-agnostic relative and goes through
	// disableOpponentDamageReduction instead.
	// =========================================================================================

	private static final String ADELARD_UNREDUCIBLE =
			"The damage dealt by your abilities to Forwards opponent controls cannot be reduced.";

	private static final String SHIELDED_TEXT =
			"If Shielded is dealt damage, reduce the damage by 2000 instead.";

	/**
	 * A shielded Forward on P2 idx 0 with a P1-owned ability resolving against it, plus
	 * {@code p1Field} seated on P1's Forward row.
	 */
	private static MainWindow boardWithShieldedTarget(CardData... p1Field) {
		MainWindow mw = new MainWindow();
		for (CardData c : p1Field) placeP1Forward(mw, c);
		placeP2Forward(mw, makeFieldAbilityCard("Shielded", "Ice", "Forward", SHIELDED_TEXT));
		mw.currentAbilitySource     = makeForward("Caster", "Fire", 3, 5000);
		mw.currentAbilitySourceIsP1 = true;
		return mw;
	}

	@Test
	void theUnreducibleLineSurvivesAsAFieldAbility() {
		List<FieldAbility> fas = CardData.parseFieldAbilities(ADELARD_UNREDUCIBLE, "Forward");

		assertEquals(1, fas.size());
		assertTrue(AutoAbilityTriggers.FA_ABILITY_DAMAGE_TO_OPP_FORWARDS_UNREDUCIBLE
				.matcher(fas.get(0).effectText()).matches());
	}

	@Test
	void aReductionShieldStillWorksWithNoAdelardOnTheField() {
		MainWindow mw = boardWithShieldedTarget();

		assertEquals(3000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false),
				"the control: 5000 less the shield's 2000");
	}

	@Test
	void adelardStripsTheShieldFromHisControllersAbilityDamage() {
		MainWindow mw = boardWithShieldedTarget(
				makeFieldAbilityForward("Adelard", ADELARD_UNREDUCIBLE));

		assertEquals(5000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false));
	}

	@Test
	void aSummonsDamageIsStillReduced() {
		MainWindow mw = boardWithShieldedTarget(
				makeFieldAbilityForward("Adelard", ADELARD_UNREDUCIBLE));
		mw.currentResolutionIsSummon = true;

		assertEquals(3000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false),
				"the text says \"abilities\", and a Summon is not one");
	}

	@Test
	void battleDamageIsStillReduced() {
		MainWindow mw = boardWithShieldedTarget(
				makeFieldAbilityForward("Adelard", ADELARD_UNREDUCIBLE));

		assertEquals(3000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, false, false),
				"\"dealt by your abilities\" — combat damage is untouched");
	}

	@Test
	void adelardDoesNotStripTheShieldFromHisOwnSidesForwards() {
		// Mirror board: Adelard and the shielded Forward both belong to P2, and P1's ability is what
		// is resolving, so "your abilities … to Forwards opponent controls" is not satisfied.
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeFieldAbilityCard("Shielded", "Ice", "Forward", SHIELDED_TEXT));
		placeP2Forward(mw, makeFieldAbilityForward("Adelard", ADELARD_UNREDUCIBLE));
		mw.currentAbilitySource     = makeForward("Caster", "Fire", 3, 5000);
		mw.currentAbilitySourceIsP1 = true;

		assertEquals(3000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false));
	}

	@Test
	void anAdelardThatLostItsAbilitiesStopsStrippingTheShield() {
		CardData adelard = makeFieldAbilityForward("Adelard", ADELARD_UNREDUCIBLE);
		MainWindow mw = boardWithShieldedTarget(adelard);

		mw.lostAbilitiesCards.add(adelard);

		assertEquals(3000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false));
	}

	// =========================================================================================
	// Cu Chaspel 11-004C: "《Dull》: The damage dealt to Forwards opponent controls cannot be
	// reduced this turn." — the turn-scoped, source-agnostic relative of Adelard 17-001H.
	//
	// The activated ability already parsed and already reached disableOpponentDamageReduction.
	// What it could not do was stop the one reduction that ran ahead of the guard: a duplicate
	// FA_REDUCE_ABILITY_DAMAGE block sat above the "cannot be reduced" check in
	// modifyIncomingDamage, matching text that FA_DAMAGE_MODIFIER below the check already
	// handled. Three defects fell out of that one block, and removing it fixes all three:
	//
	//   * the reduction was applied twice, once by each matcher (Brute Bomber 28-019R);
	//   * it escaped every "cannot be reduced" route — Cu Chaspel, Adelard, and the per-sentence
	//     "This damage cannot be reduced." — because all three are checked below it;
	//   * its "Damage N --" gate lived only in the removed block, so the surviving matcher
	//     ignored the gate entirely (Siren (V) 22-098H, Tidus 26-112H, Brute Bomber).
	//
	// The golden file could not have caught any of this: it records parse outcome, pattern name
	// and description, and the description for these cards was already coming from the surviving
	// matcher. Only the damage arithmetic was wrong.
	// =========================================================================================

	private static final String CU_CHASPEL_TEXT =
			"《Dull》: The damage dealt to Forwards opponent controls cannot be reduced this turn.";

	private static final String BRUTE_BOMBER_TEXT =
			"Damage 3 -- If Brute Bomber is dealt damage by abilities, reduce the damage by 2000 instead.";

	private static final String SIREN_TEXT =
			"Damage 3 -- If Siren (V) is dealt damage, reduce the damage by 1000 instead.";

	/** Builds a Forward whose action abilities and field abilities are both parsed from {@code text}. */
	private static CardData makeActionAndFieldCard(String name, String type, String text) {
		return new CardData(null, name, "Fire", 2, "Backup".equals(type) ? 0 : 7000, type,
				false, 0, false, false, Set.of(), 0, List.of(), null, List.of(),
				CardData.parseActionAbilities(text), List.of(),
				CardData.parseFieldAbilities(text, type),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	/** Puts {@code n} cards into {@code isP1}'s damage zone, for the "Damage N --" gates. */
	private static void takeDamage(MainWindow mw, boolean isP1, int n) {
		List<CardData> zone = isP1 ? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone();
		for (int i = 0; i < n; i++) zone.add(makeForward("Damage " + i, "Fire", 1, 1000));
	}

	/** {@code target} on P2 idx 0 with a P1-owned ability resolving against it. */
	private static MainWindow boardWithP2Target(CardData target) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, target);
		mw.currentAbilitySource     = makeForward("Caster", "Fire", 3, 5000);
		mw.currentAbilitySourceIsP1 = true;
		return mw;
	}

	/** Activates Cu Chaspel's ability on behalf of {@code isP1}. */
	private static void activateCuChaspel(MainWindow mw, boolean isP1) {
		CardData cu = makeActionAndFieldCard("Cu Chaspel", "Backup", CU_CHASPEL_TEXT);
		assertEquals(1, cu.actionAbilities().size(), "the 《Dull》 cost is stripped into an action ability");
		ActionResolver.parse(cu.actionAbilities().get(0).effectText(), cu)
				.accept(mw.buildGameContext(isP1));
	}

	@Test
	void cuChaspelStripsReductionFromDamageToTheOpponentsForwards() {
		MainWindow mw = boardWithP2Target(
				makeFieldAbilityCard("Shielded", "Ice", "Forward", SHIELDED_TEXT));
		assertEquals(3000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false),
				"the control: the shield is live before the ability is used");

		activateCuChaspel(mw, true);

		assertEquals(5000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false));
	}

	@Test
	void cuChaspelCoversBattleDamageToo() {
		// "The damage dealt to Forwards opponent controls" names no source, unlike Adelard's
		// "dealt by your abilities", so combat is covered as well.
		MainWindow mw = boardWithP2Target(
				makeFieldAbilityCard("Shielded", "Ice", "Forward", SHIELDED_TEXT));
		activateCuChaspel(mw, true);

		assertEquals(5000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, false, false));
	}

	@Test
	void cuChaspelLeavesItsOwnControllersForwardsAlone() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeFieldAbilityCard("Shielded", "Ice", "Forward", SHIELDED_TEXT));
		mw.currentAbilitySource     = makeForward("Caster", "Fire", 3, 5000);
		mw.currentAbilitySourceIsP1 = false;

		activateCuChaspel(mw, true);

		assertEquals(3000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false),
				"\"Forwards opponent controls\" — P1's own Forwards keep their shields");
	}

	@Test
	void theByAbilitiesReductionIsAppliedOnceNotTwice() {
		// The text matched two patterns that both subtracted, so 5000 arrived as 1000.
		MainWindow mw = boardWithP2Target(makeFieldAbilityCard("Brute Bomber", "Fire", "Forward", BRUTE_BOMBER_TEXT));
		takeDamage(mw, false, 3);

		assertEquals(3000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false));
	}

	@Test
	void theByAbilitiesReductionStillIgnoresSummonsAndCombat() {
		MainWindow mw = boardWithP2Target(makeFieldAbilityCard("Brute Bomber", "Fire", "Forward", BRUTE_BOMBER_TEXT));
		takeDamage(mw, false, 3);

		mw.currentResolutionIsSummon = true;
		assertEquals(5000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false),
				"\"by abilities\" excludes Summons");

		mw.currentResolutionIsSummon = false;
		assertEquals(5000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, false, false),
				"and excludes battle damage");
	}

	@Test
	void aDamageGatedReductionIsDormantBelowItsThreshold() {
		// The "Damage 3 --" gate lived only in the removed block, so the surviving matcher reduced
		// damage from the first turn regardless of how much its controller had taken.
		MainWindow mw = boardWithP2Target(makeFieldAbilityCard("Siren (V)", "Wind", "Forward", SIREN_TEXT));

		assertEquals(5000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, false, false),
				"no damage taken yet — the ability is not active");
	}

	@Test
	void aDamageGatedReductionWakesAtItsThreshold() {
		MainWindow mw = boardWithP2Target(makeFieldAbilityCard("Siren (V)", "Wind", "Forward", SIREN_TEXT));
		takeDamage(mw, false, 3);

		assertEquals(4000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, false, false));
	}

	@Test
	void theGateCountsTheAbilityControllersOwnDamageNotTheOpponents() {
		MainWindow mw = boardWithP2Target(makeFieldAbilityCard("Siren (V)", "Wind", "Forward", SIREN_TEXT));
		takeDamage(mw, true, 3);

		assertEquals(5000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, false, false),
				"Siren is P2's, so P1's damage zone must not wake her");
	}

	@Test
	void aPerSentenceCannotBeReducedNowLiftsTheByAbilitiesReduction() {
		// "This damage cannot be reduced." reaches modifyIncomingDamage as the unreduced flag, and
		// used to arrive too late to stop this particular reduction.
		MainWindow mw = boardWithP2Target(makeFieldAbilityCard("Brute Bomber", "Fire", "Forward", BRUTE_BOMBER_TEXT));
		takeDamage(mw, false, 3);

		assertEquals(5000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, true, true));
	}

	@Test
	void cuChaspelLiftsTheByAbilitiesReductionToo() {
		MainWindow mw = boardWithP2Target(makeFieldAbilityCard("Brute Bomber", "Fire", "Forward", BRUTE_BOMBER_TEXT));
		takeDamage(mw, false, 3);
		activateCuChaspel(mw, true);

		assertEquals(5000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false));
	}

	@Test
	void adelardLiftsTheByAbilitiesReductionToo() {
		MainWindow mw = boardWithP2Target(makeFieldAbilityCard("Brute Bomber", "Fire", "Forward", BRUTE_BOMBER_TEXT));
		takeDamage(mw, false, 3);
		placeP1Forward(mw, makeFieldAbilityForward("Adelard", ADELARD_UNREDUCIBLE));

		assertEquals(5000, mw.modifyIncomingDamage(false, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false));
	}

	// =========================================================================================
	// Auron 1-002R: "The Backups you control cannot be broken by your opponent's Summons or
	// abilities."
	//
	// Alone in the corpus on two counts: it filters on type with no Job/Category/Element/Card Name,
	// and it omits the "that don't deal damage" qualifier every other printing carries. Both are
	// handled by giving it its own pattern rather than loosening the shared one, whose required
	// filter and required qualifier are what keep it off the other 26 "cannot be broken" printings.
	//
	// The missing qualifier costs nothing: a Backup has no power, so it can never be broken by
	// damage, and the two wordings pick out the same set of breaks for it.
	//
	// Wiring it exposed a gap in breakTarget — off the Forward row it read traits straight off the
	// printed card, so no field-granted protection could reach a Backup at all.
	// =========================================================================================

	private static final String AURON_BACKUP_SHIELD =
			"The Backups you control cannot be broken by your opponent's Summons or abilities.";

	private static ForwardTarget bkp(boolean isP1, int idx) {
		return new ForwardTarget(isP1, idx, ForwardTarget.CardZone.BACKUP);
	}

	@Test
	void theBareTypeShieldParsesWithNoAttributeFilter() {
		CardData.NonDmgBreakShieldGrant g =
				CardData.parseFieldNonDmgBreakShieldGrant(AURON_BACKUP_SHIELD);

		assertNotNull(g);
		assertNull(g.job());
		assertNull(g.cardName());
		assertNull(g.category());
		assertNull(g.element());
		assertTrue(g.inclBackups());
		assertFalse(g.inclForwards(), "the text names Backups, so Auron does not shield himself");
		assertFalse(g.inclMonsters());
	}

	@Test
	void auronKeepsTheOpponentFromBreakingYourBackup() {
		MainWindow mw = new MainWindow();
		mw.placeCardInFirstBackupSlot(makePlainBackup("Sage", "Fire", 2));
		assertNotNull(mw.p1BackupCards[0]);

		placeP1Forward(mw, makeFieldAbilityForward("Auron", AURON_BACKUP_SHIELD));
		mw.buildGameContext(false).breakTarget(bkp(true, 0));

		assertNotNull(mw.p1BackupCards[0], "P2's effect cannot break it while Auron is out");
	}

	@Test
	void anUnprotectedBackupIsStillBroken() {
		// The control: without Auron the same call goes through, so the test above is not vacuous.
		MainWindow mw = new MainWindow();
		mw.placeCardInFirstBackupSlot(makePlainBackup("Sage", "Fire", 2));

		mw.buildGameContext(false).breakTarget(bkp(true, 0));

		assertNull(mw.p1BackupCards[0]);
	}

	@Test
	void auronDoesNotShieldForwardsOrTheOpponentsBackups() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeFieldAbilityForward("Auron", AURON_BACKUP_SHIELD)); // P1 idx 0
		placeP1Forward(mw, makeForward("Ally", "Fire", 3, 7000));                  // P1 idx 1
		mw.placeP2CardInFirstBackupSlot(makePlainBackup("Their Sage", "Ice", 2));

		mw.buildGameContext(false).breakTarget(fwd(true, 1));
		assertEquals(1, mw.p1ForwardCards.size(), "\"Backups\" — a Forward is not covered");

		mw.buildGameContext(true).breakTarget(bkp(false, 0));
		assertNull(mw.p2BackupCards[0], "\"you control\" — the shield does not cross the field");
	}

	@Test
	void anAuronThatLostItsAbilitiesStopsShieldingTheBackup() {
		MainWindow mw = new MainWindow();
		mw.placeCardInFirstBackupSlot(makePlainBackup("Sage", "Fire", 2));
		CardData auron = makeFieldAbilityForward("Auron", AURON_BACKUP_SHIELD);
		placeP1Forward(mw, auron);

		mw.lostAbilitiesCards.add(auron);
		mw.buildGameContext(false).breakTarget(bkp(true, 0));

		assertNull(mw.p1BackupCards[0]);
	}

	// =========================================================================================
	// Squall 16-011L: "If either player has 2 cards or less in their hands, Squall gains Haste."
	// and "If both you and your opponent have no cards in hand, Squall gains First Strike, Brave
	// and \"Squall can attack twice in the same turn.\""
	//
	// Both are hand-size-conditional self grants, differing only in the quantifier: "either" is
	// satisfied by the smaller of the two hands, "both" by the larger, so one pattern reads both
	// and one comparison evaluates them.
	//
	// The traits travel the ordinary conditional-trait route. The multi-attack permission cannot:
	// CardData.maxAttacksPerTurn() is frozen at construction and this allowance moves with the hands
	// during the turn, so it is read at query time alongside Tidus 29-105L's damage-scaled one.
	// =========================================================================================

	private static final String SQUALL_HASTE =
			"If either player has 2 cards or less in their hands, Squall gains Haste.";

	private static final String SQUALL_EMPTY_HANDS =
			"If both you and your opponent have no cards in hand, Squall gains First Strike, Brave "
			+ "and \"Squall can attack twice in the same turn.\"";

	private static final String SQUALL_TEXT = SQUALL_HASTE + "[[br]]" + SQUALL_EMPTY_HANDS;

	/** Squall on P1 idx 0 with both hands filled to the given sizes. */
	private static MainWindow boardWithSquall(int p1Hand, int p2Hand) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeFieldAbilityCard("Squall", "Fire", "Forward", SQUALL_TEXT));
		for (int i = 0; i < p1Hand; i++) mw.gameState.getP1Hand().add(makeForward("P1 Card " + i, "Fire", 1, 1000));
		for (int i = 0; i < p2Hand; i++) mw.gameState.getP2Hand().add(makeForward("P2 Card " + i, "Ice", 1, 1000));
		return mw;
	}

	@Test
	void theTwoHandSizeConditionsParseWithTheirOwnQuantifiers() {
		CardData.HandSizeSelfGrant either = CardData.parseHandSizeSelfGrant(SQUALL_HASTE, "Squall");
		assertNotNull(either);
		assertFalse(either.bothPlayers());
		assertEquals(2, either.maxCards());
		assertEquals(Set.of(CardData.Trait.HASTE), either.traits());
		assertEquals(1, either.maxAttacks(), "this line carries no multi-attack permission");

		CardData.HandSizeSelfGrant both = CardData.parseHandSizeSelfGrant(SQUALL_EMPTY_HANDS, "Squall");
		assertNotNull(both);
		assertTrue(both.bothPlayers());
		assertEquals(0, both.maxCards(), "\"no cards in hand\" is the threshold-0 spelling");
		assertEquals(Set.of(CardData.Trait.FIRST_STRIKE, CardData.Trait.BRAVE), both.traits());
		assertEquals(2, both.maxAttacks());
	}

	@Test
	void aGrantNamingSomeoneElseIsNotClaimed() {
		assertNull(CardData.parseHandSizeSelfGrant(SQUALL_HASTE, "Zell"),
				"the grant is self-targeted — it only acts for the card it names");
	}

	@Test
	void squallGainsHasteWhenEitherHandIsSmallEnough() {
		assertFalse(boardWithSquall(5, 5).effectiveP1HasTrait(0, CardData.Trait.HASTE));
		assertTrue(boardWithSquall(2, 5).effectiveP1HasTrait(0, CardData.Trait.HASTE),
				"his controller's hand alone satisfies \"either player\"");
		assertTrue(boardWithSquall(5, 1).effectiveP1HasTrait(0, CardData.Trait.HASTE),
				"and so does the opponent's alone");
	}

	@Test
	void theEmptyHandGrantNeedsBothHandsEmpty() {
		MainWindow oneLeft = boardWithSquall(0, 1);
		assertFalse(oneLeft.effectiveP1HasTrait(0, CardData.Trait.FIRST_STRIKE));
		assertFalse(oneLeft.effectiveP1HasTrait(0, CardData.Trait.BRAVE));
		assertTrue(oneLeft.effectiveP1HasTrait(0, CardData.Trait.HASTE),
				"the other line is satisfied at this hand size, and the two are independent");

		MainWindow empty = boardWithSquall(0, 0);
		assertTrue(empty.effectiveP1HasTrait(0, CardData.Trait.FIRST_STRIKE));
		assertTrue(empty.effectiveP1HasTrait(0, CardData.Trait.BRAVE));
	}

	@Test
	void theSecondAttackArrivesAndLeavesWithTheHands() {
		MainWindow empty = boardWithSquall(0, 0);
		assertEquals(2, empty.maxAttacksPerTurn(empty.p1ForwardCards.get(0)));

		// Drawing a card mid-turn takes the permission away again.
		empty.gameState.getP1Hand().add(makeForward("Drawn", "Fire", 1, 1000));
		assertEquals(1, empty.maxAttacksPerTurn(empty.p1ForwardCards.get(0)));
	}

	@Test
	void aSquallThatLostItsAbilitiesGetsNeither() {
		MainWindow mw = boardWithSquall(0, 0);
		mw.lostAbilitiesCards.add(mw.p1ForwardCards.get(0));

		assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.FIRST_STRIKE));
		assertEquals(1, mw.maxAttacksPerTurn(mw.p1ForwardCards.get(0)));
	}

	// =========================================================================================
	// Daisy 18-060H: "If a Forward you control other than Daisy is dealt damage, the damage is
	// dealt to Daisy instead." — and Tidus 26-112H, the same effect filtered by card name rather
	// than by exclusion ("If a Card Name Yuna Forward you control is dealt damage…").
	//
	// A one-shot redirect map already existed for "the next damage dealt to A is received by B";
	// this is its continuous relative, resolved at the same point in applyDamageToForward and
	// ahead of modifyIncomingDamage, so it is the stand-in's own protections that apply to the
	// damage rather than the original target's.
	//
	// LIMIT: battle damage is not redirected. Combat resolves in resolveCombat, which computes
	// each side's damage, break check and First Strike interaction against fixed attacker/blocker
	// indices; moving the recipient there means recomputing power and break against a third card
	// and deciding what "the blocker was broken" means for the First Strike cancel. That is a
	// restructure of three combat paths, not a hook, so it is deliberately left out — see the test
	// at the end of this section, which pins the current behaviour rather than the correct one.
	// =========================================================================================

	private static final String DAISY_REDIRECT =
			"If a Forward you control other than Daisy is dealt damage, the damage is dealt to Daisy instead.";

	private static final String TIDUS_REDIRECT =
			"If a Card Name Yuna Forward you control is dealt damage, the damage is dealt to Tidus instead.";

	@Test
	void bothRedirectShapesParse() {
		CardData.DamageRedirectGrant daisy = CardData.parseDamageRedirectGrant(DAISY_REDIRECT, "Daisy");
		assertNotNull(daisy);
		assertNull(daisy.cardNameFilter(), "Daisy covers every Forward, narrowed only by the exclusion");
		assertEquals("Daisy", daisy.exceptCardName());

		CardData.DamageRedirectGrant tidus = CardData.parseDamageRedirectGrant(TIDUS_REDIRECT, "Tidus");
		assertNotNull(tidus);
		assertEquals("Yuna", tidus.cardNameFilter());
		assertNull(tidus.exceptCardName(), "the name filter is what keeps Tidus out of his own net");

		assertNull(CardData.parseDamageRedirectGrant(DAISY_REDIRECT, "Rosa"),
				"the redirect only ever moves damage onto the card that prints it");
	}

	@Test
	void daisyTakesTheDamageAimedAtAnAlly() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Ally", "Earth", 3, 7000));                    // idx 0
		placeP1Forward(mw, makeFieldAbilityCard("Daisy", "Earth", "Forward", DAISY_REDIRECT)); // idx 1

		mw.applyDamageToForward(true, 0, 5000, true, false);

		assertEquals(0, mw.p1ForwardDamage.get(0), "the ally is dealt none of it");
		assertEquals(5000, mw.p1ForwardDamage.get(1));
	}

	@Test
	void daisyDoesNotRedirectHerOwnDamage() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeFieldAbilityCard("Daisy", "Earth", "Forward", DAISY_REDIRECT));

		mw.applyDamageToForward(true, 0, 5000, true, false);

		assertEquals(5000, mw.p1ForwardDamage.get(0), "\"other than Daisy\" — and a stand-in never covers itself");
	}

	@Test
	void daisyDoesNotReachAcrossTheField() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeFieldAbilityCard("Daisy", "Earth", "Forward", DAISY_REDIRECT));
		placeP2Forward(mw, makeForward("Foe", "Ice", 3, 7000));

		mw.applyDamageToForward(false, 0, 5000, true, false);

		assertEquals(5000, mw.p2ForwardDamage.get(0), "\"a Forward you control\"");
		assertEquals(0, mw.p1ForwardDamage.get(0));
	}

	@Test
	void tidusCoversOnlyTheNamedForward() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Yuna",  "Water", 3, 7000)); // idx 0
		placeP1Forward(mw, makeForward("Wakka", "Water", 3, 7000)); // idx 1
		placeP1Forward(mw, makeFieldAbilityCard("Tidus", "Water", "Forward", TIDUS_REDIRECT)); // idx 2

		mw.applyDamageToForward(true, 1, 3000, true, false);
		assertEquals(3000, mw.p1ForwardDamage.get(1), "Wakka is not a Card Name Yuna Forward");
		assertEquals(0, mw.p1ForwardDamage.get(2));

		mw.applyDamageToForward(true, 0, 4000, true, false);
		assertEquals(0, mw.p1ForwardDamage.get(0));
		assertEquals(4000, mw.p1ForwardDamage.get(2));
	}

	@Test
	void itIsTheStandInsOwnProtectionsThatApply() {
		// The redirect resolves before the incoming modifiers, so the ally's shield never gets a
		// say and Daisy's does — the damage was dealt to Daisy, not merely moved after the fact.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeFieldAbilityCard("Ally", "Earth", "Forward",
				"If Ally is dealt damage, reduce the damage by 4000 instead."));
		placeP1Forward(mw, makeFieldAbilityCard("Daisy", "Earth", "Forward",
				DAISY_REDIRECT + "[[br]]If Daisy is dealt damage, reduce the damage by 1000 instead."));

		mw.applyDamageToForward(true, 0, 5000, true, false);

		assertEquals(0, mw.p1ForwardDamage.get(0));
		assertEquals(4000, mw.p1ForwardDamage.get(1), "Daisy's 1000 reduction, not the ally's 4000");
	}

	@Test
	void aDaisyThatLostHerAbilitiesStopsSoakingDamage() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Ally", "Earth", 3, 7000));
		CardData daisy = makeFieldAbilityCard("Daisy", "Earth", "Forward", DAISY_REDIRECT);
		placeP1Forward(mw, daisy);

		mw.lostAbilitiesCards.add(daisy);
		mw.applyDamageToForward(true, 0, 5000, true, false);

		assertEquals(5000, mw.p1ForwardDamage.get(0));
		assertEquals(0, mw.p1ForwardDamage.get(1));
	}

	@Test
	void battleDamageIsNotRedirected() {
		// Pins the documented limit above rather than the correct rules answer: "is dealt damage"
		// names no source, so the card should soak combat damage too. Change this test when the
		// combat paths learn to move the recipient.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Ally", "Earth", 3, 7000));
		placeP1Forward(mw, makeFieldAbilityCard("Daisy", "Earth", "Forward", DAISY_REDIRECT));
		placeP2Forward(mw, makeForward("Attacker", "Ice", 3, 5000));

		mw.resolveCombat(mw.p2ForwardCards.get(0), false, 0, mw.p1ForwardCards.get(0), true, 0);

		assertEquals(5000, mw.p1ForwardDamage.get(0), "the blocker still takes it");
		assertEquals(0, mw.p1ForwardDamage.get(1));
	}

	// =========================================================================================
	// "When [subject] is priming" — Dion 24-109R, Barnabas (XVI) 24-113R, Anabella 26-021C,
	// Vivian 26-084H, Cidolfus 29-085R.
	//
	// Board behaviour. AUTO_ABILITY_PATTERN's trigger list is closed, and "is priming" was not on
	// it, so none of these five produced an AutoAbility at all — the text fell through parsing
	// silently and nothing fired. Priming also never touches the Stack, so the dispatch has to be
	// called from the two sites where a prime completes, alongside the "primed into" one that
	// watches the far end of the same act.
	// =========================================================================================

	private static final String DION_24_109R_TEXT =
			"Brave[[br]]Priming \"Bahamut (XVI)\" -- 《3》[[br]]"
			+ "When Dion or a Character you control is priming, draw 1 card.";

	/** 26-084H Vivian's watcher clause — a pure filter over the controller's Characters. */
	private static final String VIVIAN_PRIMING_TEXT =
			"When a Character you control is priming, draw 1 card.";

	/** Gives P1 a deck to draw off and an empty hand to count into. */
	private static MainWindow boardWithDrawableDeck() {
		MainWindow mw = new MainWindow();
		mw.gameState.initializeDeck(List.of(
				makeForward("Deck A", "Water", 2, 5000),
				makeForward("Deck B", "Water", 2, 5000)), List.of());
		mw.gameState.getP1Hand().clear();
		mw.gameState.getP2Hand().clear();
		return mw;
	}

	@Test
	void primingTriggerParsesAsItsOwnTriggerNotAsAnEnterTheField() {
		List<AutoAbility> abilities = CardData.parseAutoAbilities(DION_24_109R_TEXT);
		AutoAbility fa = abilities.stream()
				.filter(a -> a.trigger().equals("is priming")).findFirst()
				.orElseThrow(() -> new AssertionError("no is-priming ability parsed from Dion's text"));
		assertEquals("Dion or a Character you control", fa.triggerCard(),
				"the whole disjunction is the subject, resolved at dispatch time");
		assertEquals("draw 1 card.", fa.effectText());
		// The unknown-trigger fallback is "enters the field", which is what this used to become
		// once the trigger clause failed to match anything in the list.
		assertTrue(abilities.stream().noneMatch(a -> a.trigger().equals("enters the field")),
				"priming is not an enter-the-field event");
	}

	@Test
	void aCardWithAPrimingTriggerDrawsWhenItIsTheOnePriming() {
		MainWindow mw = boardWithDrawableDeck();
		CardData dion = makeTraitCard("Dion", "Water", "Forward", DION_24_109R_TEXT);
		placeP1Forward(mw, dion);

		mw.autoAbilityTriggers.triggerAutoAbilitiesForPriming(dion, true);

		assertEquals(1, mw.gameState.getP1Hand().size(),
				"Dion names itself in the subject, so its own prime fires it");
	}

	@Test
	void aPrimedCardIsStillTheOneWatchingItsOwnPrime() {
		// The prime sites set the top card before dispatching, so the slot walk reports the Eikon
		// where Dion sits. Dion's ability was live at the instant it paid and must still fire.
		MainWindow mw = boardWithDrawableDeck();
		CardData dion = makeTraitCard("Dion", "Water", "Forward", DION_24_109R_TEXT);
		placeP1Forward(mw, dion);
		mw.p1ForwardPrimedTop.set(0, makeForward("Bahamut (XVI)", "Water", 5, 9000));

		mw.autoAbilityTriggers.triggerAutoAbilitiesForPriming(dion, true);

		assertEquals(1, mw.gameState.getP1Hand().size());
	}

	@Test
	void aFilterSubjectFiresForAnyOfTheControllersCharactersPriming() {
		MainWindow mw = boardWithDrawableDeck();
		mw.placeCardInFirstBackupSlot(makeTraitCard("Vivian", "Fire", "Backup", VIVIAN_PRIMING_TEXT));
		CardData clive = makeForward("Clive", "Fire", 3, 7000);
		placeP1Forward(mw, clive);

		mw.autoAbilityTriggers.triggerAutoAbilitiesForPriming(clive, true);

		assertEquals(1, mw.gameState.getP1Hand().size(),
				"\"a Character you control\" is a filter, satisfied by the Forward that primed");
	}

	@Test
	void aWatcherDoesNotFireOnTheOpponentsPrime() {
		MainWindow mw = boardWithDrawableDeck();
		mw.placeCardInFirstBackupSlot(makeTraitCard("Vivian", "Fire", "Backup", VIVIAN_PRIMING_TEXT));
		CardData theirs = makeForward("Clive", "Fire", 3, 7000);
		placeP2Forward(mw, theirs);

		mw.autoAbilityTriggers.triggerAutoAbilitiesForPriming(theirs, false);

		assertEquals(0, mw.gameState.getP1Hand().size(),
				"the subject is scoped to the watcher's own side");
	}

	@Test
	void aSelfNamedPrimingSubjectDoesNotFireForAnotherCardsPrime() {
		// Every printing pairs the self-name with a filter that subsumes it, so this pins the
		// identity reading rather than a card: a subject naming the watcher means that copy.
		MainWindow mw = boardWithDrawableDeck();
		CardData watcher = makeTraitCard("Dion", "Water", "Forward",
				"When Dion is priming, draw 1 card.");
		placeP1Forward(mw, watcher);
		CardData other = makeForward("Joshua", "Fire", 3, 7000);
		placeP1Forward(mw, other);

		mw.autoAbilityTriggers.triggerAutoAbilitiesForPriming(other, true);
		assertEquals(0, mw.gameState.getP1Hand().size(), "a different card primed");

		mw.autoAbilityTriggers.triggerAutoAbilitiesForPriming(watcher, true);
		assertEquals(1, mw.gameState.getP1Hand().size(), "and this one is the watcher itself");
	}

	// =========================================================================================
	// Princess Sarah 28-102R: "When Princess Sarah enters the field, look at the top card of your
	// deck. You may put it at the bottom of your deck. Then, draw 1 card. Gain 《C》."
	//
	// Effect wiring. Two faults stacked. The look/bottom pattern only knew the "place the card"
	// printing, not this card's "put it"; and GAIN_CRYSTAL matched the final sentence with find(),
	// claiming the whole ability, so parse() returned a crystal gain and nothing else. The second
	// fault is general — 28-103C Astrologian loses a deck reorder the same way — so the fix is a
	// trailing-gain composer mirroring the trailing-draw one, sitting immediately ahead of the
	// bare crystal parser rather than at the top of the chain, where it would have rerouted the
	// dozen Choose-then-effect printings that already compose the gain inside their own parser.
	// =========================================================================================

	private static final String PRINCESS_SARAH_EFFECT =
			"look at the top card of your deck. You may put it at the bottom of your deck. "
			+ "Then, draw 1 card. Gain 《C》.";

	@Test
	void princessSarahLooksDrawsAndGainsInThatOrder() {
		Consumer<GameContext> fn = ActionResolver.parse(PRINCESS_SARAH_EFFECT, null);
		assertNotNull(fn);

		GameContext ctx = mock(GameContext.class);
		fn.accept(ctx);

		ArgumentCaptor<LookConfig> look = ArgumentCaptor.forClass(LookConfig.class);
		InOrder order = inOrder(ctx);
		order.verify(ctx).lookAtTopDeck(look.capture());
		order.verify(ctx).drawCards(1);
		order.verify(ctx).gainCrystal(1);

		assertEquals(1, look.getValue().count());
		assertEquals(LookConfig.LookAction.BOTTOM_OR_KEEP, look.getValue().action());
	}

	@Test
	void theTrailingGainIsReportedAsACompositeNotAsTheWholeAbility() {
		assertEquals("LookTopDeckBottomOrKeep + DrawCards + GainCrystal",
				ActionResolver.matchedPatternName(PRINCESS_SARAH_EFFECT, null));
		assertEquals("LookTopDeckBottomOrKeep + DrawCards + GainCrystal",
				ActionResolver.fullDescription(PRINCESS_SARAH_EFFECT, null));
	}

	@Test
	void bothPrintingsOfTheLookThenBottomWordingResolveTheSame() {
		// 1-169C Geomancer and friends print "place the card"; Princess Sarah prints "put it".
		for (String text : List.of(
				"Look at the top card of your deck. You may place the card at the bottom of your deck.",
				"Look at the top card of your deck. You may put it at the bottom of your deck.")) {
			Consumer<GameContext> fn = ActionResolver.parse(text, null);
			assertNotNull(fn, text);
			GameContext ctx = mock(GameContext.class);
			fn.accept(ctx);
			verify(ctx).lookAtTopDeck(argThat(
					c -> c.count() == 1 && c.action() == LookConfig.LookAction.BOTTOM_OR_KEEP));
		}
	}

	@Test
	void astrologianReordersTheTopThreeAndGainsACrystal() {
		// 28-103C, the other card the trailing-gain composer rescued: same bug, a deck reorder lost
		// instead of a look-and-draw.
		String effect = "look at the top 3 cards of your deck. "
				+ "Return them to the top of your deck in any order. Gain 《C》.";
		assertEquals("LookTopDeckReturnTopOrdered + GainCrystal",
				ActionResolver.matchedPatternName(effect, null));

		Consumer<GameContext> fn = ActionResolver.parse(effect, null);
		assertNotNull(fn);
		GameContext ctx = mock(GameContext.class);
		fn.accept(ctx);

		ArgumentCaptor<LookConfig> look = ArgumentCaptor.forClass(LookConfig.class);
		InOrder order = inOrder(ctx);
		order.verify(ctx).lookAtTopDeck(look.capture());
		order.verify(ctx).gainCrystal(1);
		assertEquals(3, look.getValue().count());
		assertEquals(LookConfig.LookAction.RETURN_TOP_ORDERED, look.getValue().action());
	}

	@Test
	void theComposerDeclinesTextWhereTheGainIsNotItsOwnTrailingSentence() {
		// The gain must start a sentence of its own at the end of the ability. Anything else is a
		// single effect with its own parser, and composing it would resolve the leading clause as
		// though it stood alone — dropping the condition in the first case below.
		assertNull(ActionResolverCost.tryParseTrailingGainCrystal(
				"If your opponent has a 《C》, also gain 《C》.", null, 0));
		assertNull(ActionResolverCost.tryParseTrailingGainCrystal(
				"Gain 《C》.", null, 0), "no leading effect to compose with");
		assertNull(ActionResolverCost.tryParseTrailingGainCrystal(
				"Gain 《C》. Draw 1 card.", null, 0), "the gain is not the trailing sentence");
	}

	// =========================================================================================
	// Steiner 4-129L: "When Steiner enters the field, if you have received 3 points of damage or
	// more, draw 1 card."
	//
	// Parsing + board behaviour. The damage gate is written inline after the trigger rather than
	// as the "Damage 3 --" prefix the parser knows, so the effect text reaching the resolver was
	// "if you have received 3 points of damage or more, draw 1 card." — which matches nothing, so
	// the ability never drew at any damage total. The gate is lifted into the same damageThreshold
	// field the prefix feeds, where executeAutoAbilityImpl already enforces it.
	// =========================================================================================

	private static final String STEINER_4_129L_TEXT =
			"When Steiner enters the field, if you have received 3 points of damage or more, draw 1 card."
			+ "[[br]] Dull 1 active Water Forward other than Steiner: "
			+ "Steiner gains +1000 power until the end of the turn.";

	@Test
	void anInlineDamageGateBecomesTheAbilitysThreshold() {
		AutoAbility fa = CardData.parseAutoAbilities(STEINER_4_129L_TEXT).stream()
				.filter(a -> a.trigger().equals("enters the field")).findFirst().orElseThrow();
		assertEquals(3, fa.damageThreshold(), "the condition is the threshold, not part of the effect");
		assertEquals("draw 1 card.", fa.effectText());
		assertNotNull(ActionResolver.parse(fa.effectText(), null),
				"and what is left is an effect the resolver already knows");
	}

	@Test
	void steinerDrawsOnlyOnceTheDamageThresholdIsMet() {
		MainWindow mw = boardWithDrawableDeck();
		CardData steiner = makeTraitCard("Steiner", "Water", "Forward", STEINER_4_129L_TEXT);
		placeP1Forward(mw, steiner);

		dealP1Damage(mw, 2);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForEntersField(steiner, true);
		assertEquals(0, mw.gameState.getP1Hand().size(), "two damage is under the gate");

		dealP1Damage(mw, 1);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForEntersField(steiner, true);
		assertEquals(1, mw.gameState.getP1Hand().size(), "three is \"3 or more\"");
	}

	@Test
	void aLaterDamageConditionIsNotMistakenForTheGate() {
		// The gate is anchored to the head of the effect. A condition further in is qualifying one
		// clause of a larger effect ("… break it. If you have received 5 …, do X instead."), and
		// lifting that into a whole-ability threshold would suppress the unconditional part.
		String trailing = "choose 1 Forward. Break it. "
				+ "If you have received 5 points of damage or more, draw 1 card.";
		AutoAbility fa = CardData.parseAutoAbilities(
				"When Tester enters the field, " + trailing).get(0);
		assertEquals(0, fa.damageThreshold());
		assertEquals(trailing, fa.effectText());
	}

	// =========================================================================================
	// Noel 19-136S: "When Noel attacks, until the end of the turn, Noel gains +1000 power for each
	// Category XIII Character you control."
	//
	// Effect wiring. The scaling boost existed only in two narrower forms — on a chosen Forward
	// ("it gains +N power for each [Element] Type you control") and on the source counting Crystals
	// — so this self-targeted, Category-counted printing matched nothing at all. The new parser is
	// the self-targeted twin, and must sit ahead of the flat self-boost parsers: they read the same
	// "<Name> gains +N power … until end of turn" frame and would hand out a flat +1000.
	// =========================================================================================

	private static final String NOEL_19_136S_EFFECT =
			"until the end of the turn, Noel gains +1000 power for each Category XIII Character you control.";

	@Test
	void noelScalesHisBoostByTheCategoryHeCounts() {
		CardData noel = makeTraitCard("Noel", "Wind", "Forward", NOEL_19_136S_EFFECT);
		Consumer<GameContext> fn = ActionResolver.parse(NOEL_19_136S_EFFECT, noel);
		assertNotNull(fn);

		GameContext ctx = mock(GameContext.class);
		when(ctx.ownFieldCountByCategory("XIII", "Character")).thenReturn(3);
		fn.accept(ctx);

		verify(ctx).boostSourceForward(eq(noel), eq(3000), argThat(Set::isEmpty));
	}

	@Test
	void theMultiplierIsReadWhenTheAbilityResolves() {
		CardData noel = makeTraitCard("Noel", "Wind", "Forward", NOEL_19_136S_EFFECT);
		Consumer<GameContext> fn = ActionResolver.parse(NOEL_19_136S_EFFECT, noel);

		GameContext ctx = mock(GameContext.class);
		when(ctx.ownFieldCountByCategory("XIII", "Character")).thenReturn(0);
		fn.accept(ctx);
		verify(ctx).boostSourceForward(eq(noel), eq(0), any());

		// A Character arriving between the trigger and the resolution counts, so the same parsed
		// consumer must ask again rather than close over the first answer.
		when(ctx.ownFieldCountByCategory("XIII", "Character")).thenReturn(5);
		fn.accept(ctx);
		verify(ctx).boostSourceForward(eq(noel), eq(5000), any());
	}

	@Test
	void bothWordOrdersOfTheScalingSelfBoostResolve() {
		// Noel leads with the duration; the trailing-duration order is the commoner printing.
		String trailing = "Noel gains +1000 power for each Category XIII Character you control "
				+ "until the end of the turn.";
		CardData noel = makeTraitCard("Noel", "Wind", "Forward", trailing);
		assertEquals("StandaloneSelfBoostForEachControlled",
				ActionResolver.matchedPatternName(trailing, noel));
		assertEquals("StandaloneSelfBoostForEachControlled",
				ActionResolver.matchedPatternName(NOEL_19_136S_EFFECT, noel));
	}

	@Test
	void aScalingBoostNamingSomeOtherCardIsNotTheSourcesOwn() {
		// The subject is checked against the source, which is what stops this claiming a boost
		// that some other card's text hands out.
		CardData noel = makeTraitCard("Noel", "Wind", "Forward", NOEL_19_136S_EFFECT);
		assertNull(ActionResolverPower.tryParseStandaloneSelfBoostForEachControlled(
				"until the end of the turn, Serah gains +1000 power for each Category XIII Character you control.",
				noel));
	}

	// =========================================================================================
	// Ace 16-002H: "When Ace attacks, Ace gains +1000 power for each different Element among
	// Characters you control until the end of the turn."
	//
	// Effect wiring. Same self-targeted frame as Noel above, but the multiplier counts distinct
	// Elements rather than cards, which no parser in the family read — the counted noun does not
	// follow "for each" directly, so the sibling parser cannot reach it and the ability was
	// unparsed. Multi-element Characters contribute each of their Elements, which is what makes
	// this a distinct count rather than a filtered card count.
	// =========================================================================================

	private static final String ACE_16_002H_EFFECT =
			"Ace gains +1000 power for each different Element among Characters you control "
			+ "until the end of the turn.";

	@Test
	void aceScalesHisBoostByTheDistinctElementsHeControls() {
		CardData ace = makeTraitCard("Ace", "Fire", "Forward", ACE_16_002H_EFFECT);
		Consumer<GameContext> fn = ActionResolver.parse(ACE_16_002H_EFFECT, ace);
		assertNotNull(fn, "\"for each different Element among Characters you control\" should parse");

		GameContext ctx = mock(GameContext.class);
		when(ctx.selfDistinctElementCount(true, true, true)).thenReturn(4);
		fn.accept(ctx);

		verify(ctx).boostSourceForward(eq(ace), eq(4000), argThat(Set::isEmpty));
	}

	@Test
	void theDistinctElementCountIsNotACardCount() {
		// The whole point of the clause: 5 Characters sharing 2 Elements is +2000, not +5000.
		// Reading it off a card count would also make a Fire/Ice Character worth 1 instead of 2.
		CardData ace = makeTraitCard("Ace", "Fire", "Forward", ACE_16_002H_EFFECT);
		Consumer<GameContext> fn = ActionResolver.parse(ACE_16_002H_EFFECT, ace);

		GameContext ctx = mock(GameContext.class);
		when(ctx.selfDistinctElementCount(true, true, true)).thenReturn(2);
		when(ctx.ownFieldCount(any())).thenReturn(5);
		fn.accept(ctx);

		verify(ctx).boostSourceForward(eq(ace), eq(2000), any());
		verify(ctx, never()).ownFieldCount(any());
	}

	@Test
	void bothWordOrdersOfTheDistinctElementBoostResolve() {
		CardData ace = makeTraitCard("Ace", "Fire", "Forward", ACE_16_002H_EFFECT);
		String leading = "Until the end of the turn, Ace gains +1000 power for each different "
				+ "Element among Characters you control.";
		assertEquals("StandaloneSelfBoostForEachDistinctElement",
				ActionResolver.matchedPatternName(ACE_16_002H_EFFECT, ace));
		assertEquals("StandaloneSelfBoostForEachDistinctElement",
				ActionResolver.matchedPatternName(leading, ace));
	}

	@Test
	void aDistinctElementBoostNamingSomeOtherCardIsNotTheSourcesOwn() {
		CardData ace = makeTraitCard("Ace", "Fire", "Forward", ACE_16_002H_EFFECT);
		assertNull(ActionResolverPower.tryParseStandaloneSelfBoostForEachDistinctElement(
				"Machina gains +1000 power for each different Element among Characters you control "
				+ "until the end of the turn.", ace));
	}

	// =========================================================================================
	// Gau 19-089H: "When Gau enters the field, choose 1 Category VI Forward you control. Until the
	// end of the turn, it gains +1000 power for each Category VI Character you control."
	//
	// Effect wiring. The chosen-target followup counted an optional Element and a card type, but
	// had no Category alternative — so the text still parsed, via the plain "it gains +N power
	// until end of turn" followup sitting behind it, and handed out a flat +1000 with the
	// multiplier silently dropped. The Category qualifier brings the followup into step with the
	// self-targeted form, which has admitted one since Noel.
	// =========================================================================================

	private static final String GAU_19_089H_EFFECT =
			"Choose 1 Category VI Forward you control. Until the end of the turn, "
			+ "it gains +1000 power for each Category VI Character you control.";

	@Test
	void gauScalesTheChosenForwardsBoostByCategory() {
		Consumer<GameContext> fn = ActionResolver.parse(GAU_19_089H_EFFECT, null);
		assertNotNull(fn);

		GameContext ctx = mock(GameContext.class);
		ForwardTarget t = stubChooseOneTarget(ctx);
		when(ctx.countSelfFieldCards(true, true, true, null, null, "VI", null)).thenReturn(3);
		fn.accept(ctx);

		verify(ctx).boostTarget(eq(t), eq(3000), argThat(Set::isEmpty));
	}

	@Test
	void theCategoryFollowupIsClaimedAheadOfTheFlatBoost() {
		// The regression this guards: FOLLOWUP_POWER_BOOST_UNTIL matches the "+1000 power" prefix
		// on its own, so if the for-each form does not claim the text first the multiplier is lost
		// and the ability quietly becomes a flat boost.
		assertEquals("ChooseCharacter / PowerBoostUntilForEach",
				ActionResolver.fullDescription(GAU_19_089H_EFFECT, null));
	}

	@Test
	void theElementFollowupStillCountsByElement() {
		// The Category group is optional and must not disturb the Element reading beside it.
		String elementText = "Choose 1 Forward you control. Until the end of the turn, "
				+ "it gains +1000 power for each Fire Character you control.";
		Consumer<GameContext> fn = ActionResolver.parse(elementText, null);
		assertNotNull(fn);

		GameContext ctx = mock(GameContext.class);
		ForwardTarget t = stubChooseOneTarget(ctx);
		when(ctx.countSelfFieldCards(true, true, true, null, null, null, "Fire")).thenReturn(2);
		fn.accept(ctx);

		verify(ctx).boostTarget(eq(t), eq(2000), argThat(Set::isEmpty));
	}

	// =========================================================================================
	// Relm 11-124H: "When Relm enters the field, you may search for up to 1 Monster of cost 1 and
	// up to 1 Monster of cost 2 and play them onto the field."
	// Cherukiki 19-109H: "《Dull》: Search for up to 1 Card Name Kukki-Chebukki and up to 1 Card
	// Name Makki-Chebukki and play them onto the field."
	//
	// Effect wiring. Two searches of the one deck in a single sentence, in two forms — two costs,
	// or two card names. The general search parser describes a single pool, and the two printings
	// broke on it differently: Relm's it declined outright, leaving the ability inert, while
	// Cherukiki's it accepted and mis-read, its lazy name group running through the conjunction to
	// search for a card called "Kukki-Chebukki and up to 1 Card Name Makki-Chebukki". That search
	// can never hit, so the ability looked wired and quietly did nothing.
	// =========================================================================================

	private static final String RELM_11_124H_EFFECT =
			"search for up to 1 Monster of cost 1 and up to 1 Monster of cost 2 "
			+ "and play them onto the field.";

	/** The argument list both halves of this search share — only the cost differs. */
	private static void verifyMonsterSearchOfCost(GameContext ctx, InOrder order, int cost) {
		order.verify(ctx).searchDeckForCard(false, false, true, false, cost, null, null, null,
				null, null, null, null, "field", 1, false, false);
	}

	@Test
	void relmSearchesBothCostsAndPlaysThemOntoTheField() {
		Consumer<GameContext> fn = ActionResolver.parse(RELM_11_124H_EFFECT, null);
		assertNotNull(fn, "two costs in one search sentence should parse");

		GameContext ctx = mock(GameContext.class);
		fn.accept(ctx);

		// In printed order: the searches are two prompts, and the player meets them as written.
		InOrder order = inOrder(ctx);
		verifyMonsterSearchOfCost(ctx, order, 1);
		verifyMonsterSearchOfCost(ctx, order, 2);
		verify(ctx, times(2)).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(),
				anyBoolean(), anyBoolean());
	}

	@Test
	void theSecondHalfIsNotClaimedAwayBySinglePoolSearch() {
		// The ordering guard: tryParseSearchDeck sits behind this one, and reaching it first would
		// silently halve the ability.
		assertEquals("DualSearchPlayOntoField",
				ActionResolver.matchedPatternName(RELM_11_124H_EFFECT, null));
		assertEquals("DualSearchPlayOntoField",
				ActionResolver.matchedPatternName("you may " + RELM_11_124H_EFFECT, null));
		assertEquals("DualSearchPlayOntoField",
				ActionResolver.matchedPatternName(CHERUKIKI_19_109H_EFFECT, null));
	}

	@Test
	void anOrdinaryOneCostSearchIsStillTheSinglePoolParsers() {
		assertEquals("SearchDeck", ActionResolver.matchedPatternName(
				"search for up to 1 Monster of cost 1 and play it onto the field.", null));
		assertEquals("SearchDeck", ActionResolver.matchedPatternName(
				"Search for 1 Card Name Kukki-Chebukki and play it onto the field.", null));
	}

	private static final String CHERUKIKI_19_109H_EFFECT =
			"Search for up to 1 Card Name Kukki-Chebukki and up to 1 Card Name Makki-Chebukki "
			+ "and play them onto the field.";

	/** A name half searches every card type, the way the single-pool parser reads a bare name. */
	private static void verifyNameSearch(GameContext ctx, InOrder order, String cardName) {
		order.verify(ctx).searchDeckForCard(true, true, true, true, -1, null, cardName, null,
				null, null, null, null, "field", 1, false, false);
	}

	@Test
	void cherukikiSearchesEachNameSeparately() {
		Consumer<GameContext> fn = ActionResolver.parse(CHERUKIKI_19_109H_EFFECT, null);
		assertNotNull(fn);

		GameContext ctx = mock(GameContext.class);
		fn.accept(ctx);

		InOrder order = inOrder(ctx);
		verifyNameSearch(ctx, order, "Kukki-Chebukki");
		verifyNameSearch(ctx, order, "Makki-Chebukki");
	}

	@Test
	void neitherNameSwallowsTheConjunction() {
		// The failure mode this replaces: one search for a card whose name is the whole rest of
		// the sentence. Nothing in the deck is ever called that, so the ability did nothing at all.
		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(CHERUKIKI_19_109H_EFFECT, null).accept(ctx);
		verify(ctx, never()).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), argThat(n -> n != null && n.contains("up to")), any(), any(), any(),
				any(), any(), any(), anyInt(), anyBoolean(), anyBoolean());
	}

	// =========================================================================================
	// Nox Suzaku 15-130H: "At the end of each of your turns, reveal the top 3 cards of your deck.
	// Play up to 1 Forward of cost 4 or less among them onto the field and put the rest of the
	// cards into the Break Zone."
	//
	// Effect wiring. The reveal-and-play family already handled the reveal and the play; it was
	// the disposal that had nowhere to go — the leftovers could reach the bottom of the deck or
	// the ability user's hand, and a Break Zone was neither, so the whole sentence failed to
	// match. The destination is a RevealRest now rather than a boolean.
	// =========================================================================================

	private static final String NOX_SUZAKU_15_130H_EFFECT =
			"reveal the top 3 cards of your deck. Play up to 1 Forward of cost 4 or less among "
			+ "them onto the field and put the rest of the cards into the Break Zone.";

	@Test
	void noxSuzakuRevealsThreePlaysOneAndBreaksTheRest() {
		Consumer<GameContext> fn = ActionResolver.parse(NOX_SUZAKU_15_130H_EFFECT, null);
		assertNotNull(fn, "\"put the rest of the cards into the Break Zone\" should parse");

		GameContext ctx = mock(GameContext.class);
		fn.accept(ctx);
		verify(ctx).revealTopNPlayUpToElementTypeCostOntoField(
				3, 1, null, "Forward", 4, RevealRest.BREAK_ZONE);
	}

	@Test
	void theBreakZoneDestinationSendsTheLeftoversToTheBreakZone() {
		// The arrangement both seats build. Bottom-of-deck must stay empty: it is where every
		// other card in this family sends its leftovers, so a leak there would look like business
		// as usual in the log.
		List<CardData> revealed = List.of(
				makeForward("Cheap", "Dark", 2, 5000),
				makeForward("Dear", "Dark", 7, 9000),
				makeSummon("Summon", "Dark", 1, ""));
		Predicate<CardData> eligible = c -> c.isForward() && c.cost() <= 4;

		DeckLookDecision d = LookAtDeckDialogs.cpuRevealPlayOntoField(
				revealed, 1, eligible, RevealRest.BREAK_ZONE);

		assertEquals(List.of(0), d.toField(), "only the cost-2 Forward is playable");
		assertEquals(Set.of(1, 2), Set.copyOf(d.toBreak()));
		assertTrue(d.toBottom().isEmpty(), "the leftovers must not also reach the deck");
		assertTrue(d.toHand().isEmpty());
	}

	@Test
	void theOtherTwoRevealDestinationsAreUnchanged() {
		List<CardData> revealed = List.of(makeForward("A", "Dark", 2, 5000),
				makeForward("B", "Dark", 7, 9000));
		Predicate<CardData> eligible = c -> c.cost() <= 4;

		DeckLookDecision bottom = LookAtDeckDialogs.cpuRevealPlayOntoField(
				revealed, 1, eligible, RevealRest.BOTTOM);
		assertEquals(List.of(1), bottom.toBottom());
		assertTrue(bottom.toBreak().isEmpty());

		DeckLookDecision hand = LookAtDeckDialogs.cpuRevealPlayOntoField(
				revealed, 1, eligible, RevealRest.HAND);
		assertEquals(List.of(1), hand.toHand());
		assertTrue(hand.toBreak().isEmpty());
	}

	// =========================================================================================
	// Vanille 7-065H: "When Vanille enters the field, choose 1 dull Forward. Select 1 number and
	// reveal the top card of your deck. If the revealed card is of the same cost as the selected
	// number, break it."
	//
	// Parsing. The effect already resolved correctly — the break is conditional on the reveal, and
	// "it" is the chosen Forward rather than the revealed card. The description was what lagged:
	// the followup was split at ". ", separating the reveal from the cost test that consumes it,
	// so the ability reported as "? + Break" — the shape an unconditional break would have, which
	// is precisely the bug this card would exhibit if the wiring ever regressed.
	// =========================================================================================

	private static final String VANILLE_7_065H_EFFECT =
			"choose 1 dull Forward. Select 1 number and reveal the top card of your deck. "
			+ "If the revealed card is of the same cost as the selected number, break it.";

	@Test
	void vanilleIsDescribedAsOneConditionalRevealRatherThanABareBreak() {
		assertEquals("ChooseCharacter / SelectNumberRevealBreak",
				ActionResolver.fullDescription(VANILLE_7_065H_EFFECT, null));
	}

	@Test
	void vanilleBreaksTheChosenForwardOnlyWhenTheCostsMatch() {
		Consumer<GameContext> fn = ActionResolver.parse(VANILLE_7_065H_EFFECT, null);
		assertNotNull(fn);

		GameContext ctx = mock(GameContext.class);
		ForwardTarget t = stubChooseOneTarget(ctx);
		when(ctx.selectNumber(anyInt(), anyInt(), any())).thenReturn(4);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<RevealClause>> clauses = ArgumentCaptor.forClass(List.class);
		fn.accept(ctx);
		verify(ctx).revealTopDeckCard(clauses.capture(), eq(false));

		assertEquals(1, clauses.getValue().size());
		RevealClause clause = clauses.getValue().get(0);
		assertTrue(clause.condition().test(makeForward("Match", "Earth", 4, 7000)),
				"cost 4 is the selected number");
		assertFalse(clause.condition().test(makeForward("Miss", "Earth", 5, 7000)));

		// And the card broken is the one that was chosen, not the one revealed.
		clause.effect().accept(ctx);
		verify(ctx).breakTarget(t);
	}

	// =========================================================================================
	// Terra 15-037L: "When Terra enters the field, you may reveal any number of Summons from your
	// hand. When you do so, choose up to the same number of Characters as the Summons you
	// revealed. Dull them and Freeze them."
	//
	// Effect wiring. The target count is not in the text — it is however many Summons the player
	// decided to reveal, and that is known only once the reveal has happened. Handled where the
	// reveal already lives, in AutoAbilityTriggers beside 13-033R Levnato's conditional version:
	// the count is written back into the follow-up sentence, and the resolver then reads the
	// ordinary "choose up to N Characters" shape it already knows.
	// =========================================================================================

	private static final String TERRA_15_037L_FOLLOWUP =
			"choose up to the same number of Characters as the Summons you revealed. "
			+ "Dull them and Freeze them.";

	@Test
	void theRevealedCountIsWrittenIntoTheFollowupSentence() {
		assertEquals("choose up to 2 Characters. Dull them and Freeze them.",
				AutoAbilityTriggers.withRevealedCount(TERRA_15_037L_FOLLOWUP, 2));
	}

	@Test
	void theRewrittenFollowupDullsAndFreezesThatManyCharacters() {
		String resolved = AutoAbilityTriggers.withRevealedCount(TERRA_15_037L_FOLLOWUP, 2);
		Consumer<GameContext> fn = ActionResolver.parse(resolved, null);
		assertNotNull(fn, "the rewritten sentence must be one the resolver already reads");

		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		ForwardTarget a = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		ForwardTarget b = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(a, b));
		fn.accept(ctx);

		verify(ctx).dullAndFreezeTarget(a);
		verify(ctx).dullAndFreezeTarget(b);
	}

	private static final String TERRA_15_037L_TEXT =
			"When Terra enters the field, you may reveal any number of Summons from your hand. "
			+ "When you do so, " + TERRA_15_037L_FOLLOWUP;

	/** Terra entering P2's field with {@code summonsInHand} Summons to reveal, and one P1 target. */
	private static MainWindow boardWithTerraEntering(int summonsInHand) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Victim", "Fire", 3, 7000));
		mw.gameState.getP2Hand().clear();
		for (int i = 0; i < summonsInHand; i++)
			mw.gameState.getP2Hand().add(makeSummon("Firaga " + i, "Fire", 2, ""));
		mw.autoAbilityTriggers.triggerAutoAbilitiesForEntersField(
				makeAutoAbilityForward("Terra", "Ice", 8000, TERRA_15_037L_TEXT), false);
		return mw;
	}

	@Test
	void revealingASummonDullsAndFreezesThatManyCharacters() {
		// The AI seat, so the whole path runs without a dialog: one Summon revealed buys one
		// target, and the effect reaches the board rather than stopping at "Unrecognized effect".
		MainWindow mw = boardWithTerraEntering(1);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.p1ForwardFrozen.get(0), "dull and Freeze, not dull alone");
	}

	@Test
	void withNoSummonsToRevealTheFollowupNeverRuns() {
		// "When you do so" — revealing nothing means it never happened, and a count of 0 must not
		// fall through to a choose that takes a target anyway.
		MainWindow mw = boardWithTerraEntering(0);
		assertNotEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertFalse(mw.p1ForwardFrozen.get(0));
	}

	@Test
	void terrasEtfIsOneOptionalAbilityCarryingTheWholeReveal() {
		AutoAbility fa = CardData.parseAutoAbilities(
				"When Terra enters the field, you may reveal any number of Summons from your hand. "
				+ "When you do so, " + TERRA_15_037L_FOLLOWUP).get(0);
		assertEquals("enters the field", fa.trigger());
		assertTrue(fa.youMay(), "the reveal is the player's to decline");
		assertTrue(fa.effectText().startsWith("reveal any number of Summons from your hand."),
				"the follow-up must stay attached to the reveal that sizes it: " + fa.effectText());
	}

	// =========================================================================================
	// Board behaviour: three auto abilities that each move a card between zones the choose chain
	// could not previously reach — Fiona 16-118C (self to deck top on being chosen), Feral Chaos
	// B-010 (a named card out of the RFG zone on leaving the field), Lich 21-079R (a permanent
	// end-of-turn break granted to an opponent's Forward on entering).
	//
	// Every card sits on the P2 seat so the AI path resolves the optional prompt and the target
	// selection without a dialog, the way boardWithTerraEntering does above.
	// =========================================================================================

	private static final String FIONA_TEXT =
			"[[ex]]EX BURST[[/]] When Fiona enters the field, draw 1 card.[[br]]   "
			+ "When Fiona is chosen by your opponent's Summons or abilities, "
			+ "you may put Fiona on top of its owner's deck.";

	private static final String FERAL_CHAOS_TEXT =
			"Feral Chaos is also Card Name Chaos in all situations.[[br]]   Haste First Strike Brave[[br]]   "
			+ "When Feral Chaos leaves the field, select 1 of your Card Name Chaos removed from the game. "
			+ "Add it to your hand.";

	private static final String LICH_TEXT =
			"When Lich enters the field, choose 1 Forward opponent controls. It gains "
			+ "\"At the end of each of your turns, break this Forward. "
			+ "(This effect does not end at the end of the turn.)\"";

	@Test
	void fionaChosenByAnOpponentsAbilityGoesToTheTopOfItsOwnersDeck() {
		MainWindow mw = new MainWindow();
		CardData fiona = makeAutoAbilityForward("Fiona", "Water", 8000, FIONA_TEXT);
		placeP2Forward(mw, fiona);
		mw.gameState.getP2MainDeck().clear();

		mw.autoAbilityTriggers.triggerAutoAbilitiesForChosenByOpponentSummonOrAbility(false, List.of(fiona));

		assertFalse(mw.p2ForwardCards.contains(fiona), "Fiona leaves the field");
		assertEquals(fiona, mw.gameState.getP2MainDeck().peekFirst(), "and lands on top of its own deck");
	}

	@Test
	void aBouncedForwardIsPutIntoTheDeckExactlyOnce() {
		// Regression: returnP1/P2ForwardToDeck used to add the card twice — once unconditionally
		// and once through the addFirst/addLast that picks the end — inflating the deck by 1 on
		// every bounce, and leaving a second copy of the card to be drawn later.
		MainWindow mw = new MainWindow();
		CardData bouncer = makeForward("Bouncer", "Fire", 3, 7000);
		placeP1Forward(mw, bouncer);
		mw.gameState.getP1MainDeck().clear();

		mw.returnP1ForwardToDeck(0, false);

		assertEquals(1, mw.gameState.getP1MainDeck().size());
		assertEquals(bouncer, mw.gameState.getP1MainDeck().peekFirst());
	}

	/**
	 * Resolves {@code card}'s single auto ability against a real board on the P2 seat.
	 *
	 * <p>Goes straight to the effect rather than through {@code triggerAutoAbilities…}: everything
	 * except the reactive "chosen by opponent's …" family is <em>pushed onto the Stack</em> by
	 * {@code executeAutoAbilityImpl} and resolved later by the turn loop, which a unit test has no
	 * way to drive. The P2 seat is what makes the AI answer the target selection without a dialog.
	 */
	private static void resolveP2AutoAbility(MainWindow mw, CardData card) {
		AutoAbility fa = card.autoAbilities().get(0);
		ActionResolver.parse(fa.effectText(), card).accept(mw.buildGameContext(false));
	}

	@Test
	void feralChaosLeavingTheFieldSalvagesAChaosFromTheRfgZone() {
		MainWindow mw = new MainWindow();
		CardData feral = makeAutoAbilityForward("Feral Chaos", "Dark", 10000, FERAL_CHAOS_TEXT);
		// A second Feral Chaos already removed from the game. It is a Chaos only through its
		// "is also Card Name Chaos in all situations" alias, which is what the filter must read.
		CardData exiled = makeAutoAbilityForward("Feral Chaos", "Dark", 10000, FERAL_CHAOS_TEXT);
		mw.gameState.getIdentity().put(exiled, false);
		mw.gameState.addToPermanentRfp(exiled);
		mw.gameState.getP2Hand().clear();

		resolveP2AutoAbility(mw, feral);

		assertTrue(mw.gameState.getP2Hand().contains(exiled), "the exiled Chaos is added to hand");
		assertFalse(mw.gameState.getP2PermanentRfp().contains(exiled), "and leaves the RFG zone");
	}

	@Test
	void feralChaosSalvageIgnoresACardThatIsNotAChaos() {
		MainWindow mw = new MainWindow();
		CardData feral = makeAutoAbilityForward("Feral Chaos", "Dark", 10000, FERAL_CHAOS_TEXT);
		CardData bystander = makeForward("Bystander", "Dark", 3, 7000);
		mw.gameState.getIdentity().put(bystander, false);
		mw.gameState.addToPermanentRfp(bystander);
		mw.gameState.getP2Hand().clear();

		resolveP2AutoAbility(mw, feral);

		assertTrue(mw.gameState.getP2Hand().isEmpty(), "no Chaos to salvage — the hand is untouched");
		assertTrue(mw.gameState.getP2PermanentRfp().contains(bystander), "and the RFG zone keeps it");
	}

	@Test
	void lichGrantsTheChosenForwardAnEndOfTurnBreakThatOutlaststheTurn() {
		MainWindow mw = new MainWindow();
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		placeP1Forward(mw, victim);

		resolveP2AutoAbility(mw, makeAutoAbilityForward("Lich", "Earth", 9000, LICH_TEXT));

		List<AutoAbility> granted = mw.effectiveAutoAbilities(victim);
		assertEquals(1, granted.size(), "the chosen Forward carries the granted ability: " + granted);
		assertEquals("end of your turn", granted.get(0).trigger());
		// "(This effect does not end at the end of the turn.)" — it has to land in the permanent
		// map, not in grantedFieldAbilities, which the turn boundary empties wholesale.
		assertTrue(mw.grantedAutoAbilities.containsKey(victim),
				"the grant belongs to the permanent map");
		assertFalse(mw.grantedFieldAbilities.containsKey(victim),
				"and not to the map cleared at the end of the turn");
	}

	@Test
	void lichsGrantReachesTheOpponentsForwardNotItsOwnSide() {
		MainWindow mw = new MainWindow();
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		CardData ally   = makeForward("Ally", "Earth", 3, 7000);
		placeP1Forward(mw, victim);
		placeP2Forward(mw, ally);

		resolveP2AutoAbility(mw, makeAutoAbilityForward("Lich", "Earth", 9000, LICH_TEXT));

		assertTrue(mw.grantedAutoAbilities.containsKey(victim), "\"Forward opponent controls\"");
		assertFalse(mw.grantedAutoAbilities.containsKey(ally), "never Lich's own side");
	}

	@Test
	void aQuotedGrantIsNotRaidedForEffectsPrintedInsideIt() {
		// 12-013C Ninja grants "When this Forward attacks, choose 1 Forward. Deal it 5000 damage."
		// The damage belongs to the granted attack trigger. The followup split is quote-aware so
		// the sentence break inside the quotation does not tear the grant in half, and the grant
		// is claimed ahead of the find()-based parsers so none of them resolves that inner clause
		// as damage this choose deals.
		String ninja = "When Ninja enters the field, choose 1 Forward. It gains "
				+ "\"When this Forward attacks, choose 1 Forward. Deal it 5000 damage.\" "
				+ "until the end of the turn.";
		CardData source = makeForward("Ninja", "Wind", 3, 7000);
		String effect = CardData.parseAutoAbilities(ninja).get(0).effectText();

		GameContext ctx = mock(GameContext.class);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(fwd(false, 0)));
		ActionResolver.parse(effect, source).accept(ctx);

		verify(ctx, never()).damageTarget(any(), anyInt());
	}

	// =========================================================================================
	// Board behaviour: three field abilities — Ark Angel EV 4-097H's "by a Character" incoming
	// damage reduction, and the "Damage 3 -- [Self] gains Brave and "…"" grants on Yumcax 18-067C
	// and Gilgamesh 18-074L, whose three halves (trait, multi-attack permission, quoted trigger)
	// each reach a different reader and all re-evaluate as the damage zone changes.
	// =========================================================================================

	private static final String YUMCAX_TEXT =
			"Warp 3 -- 《Earth》《1》[[br]]   If Yumcax is dealt damage, reduce the damage by 2000 instead.[[br]]   "
			+ "Damage 3 -- Yumcax gains Brave and "
			+ "\"When Yumcax is put from the field into the Break Zone, draw 1 card.\"";

	private static final String GILGAMESH_18_074L_TEXT =
			"Damage 3 -- Gilgamesh gains Brave and \"Gilgamesh can attack twice in the same turn.\"";

	/** A Forward built from its printed text, with every field-ability list parsed as a real card's is. */
	private static CardData makeFieldTextForward(String name, String element, int power, String text) {
		return new CardData(null, name, element, 5, power, "Forward", false, 0, false, false,
				CardData.parseTraits(text, name), 0, List.of(), null, List.of(),
				List.of(), CardData.parseAutoAbilities(text),
				CardData.parseFieldAbilities(text, "Forward"),
				CardData.parseIfControlBoosts(text, "Forward"),
				CardData.parseFieldPowerGrants(text, "Forward"),
				List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false,
				CardData.parseMaxAttacksPerTurn(text, name),
				null, null, null, text);
	}

	/** Fills P1's damage zone to {@code points} and its deck with {@code deck} drawable cards. */
	private static MainWindow boardWithP1Damage(int points, int deck) {
		MainWindow mw = new MainWindow();
		mw.gameState.getP1MainDeck().clear();
		mw.gameState.getP1Hand().clear();
		for (int i = 0; i < deck; i++) {
			CardData c = makeForward("Filler" + i, "Fire", 1, 1000);
			mw.gameState.getIdentity().put(c, true);
			mw.gameState.getP1MainDeck().add(c);
		}
		for (int i = 0; i < points; i++) {
			CardData d = makeForward("Dmg" + i, "Fire", 1, 1000);
			mw.gameState.getIdentity().put(d, true);
			mw.gameState.getP1DamageZone().add(d);
		}
		return mw;
	}

	@Test
	void arkAngelReducesDamageDealtByACharacter() {
		String text = "If Ark Angel EV is dealt damage by a Character, reduce the damage by 2000 instead.";
		CardData ark = makeFieldTextForward("Ark Angel EV", "Lightning", 6000, text);
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, ark);

		// Battle damage from a Forward, and a Character's ability damage, are both "by a Character".
		assertEquals(3000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 5000, false, false),
				"battle damage is reduced");
		assertEquals(3000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false),
				"a Character's ability damage is reduced too");
	}

	@Test
	void arkAngelDoesNotReduceSummonDamage() {
		// A Summon is not a Character, so the clause does not cover it.
		String text = "If Ark Angel EV is dealt damage by a Character, reduce the damage by 2000 instead.";
		CardData ark = makeFieldTextForward("Ark Angel EV", "Lightning", 6000, text);
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, ark);
		mw.currentResolutionIsSummon = true;

		assertEquals(5000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false));
	}

	@Test
	void yumcaxGainsBraveAndItsBreakTriggerOnlyAtThreeDamage() {
		MainWindow below = boardWithP1Damage(2, 5);
		CardData yumcaxBelow = makeFieldTextForward("Yumcax", "Earth", 9000, YUMCAX_TEXT);
		placeP1Forward(below, yumcaxBelow);
		assertFalse(below.fieldGrantCalculator.computeConditionalTraitsForTarget(yumcaxBelow, true)
				.contains(CardData.Trait.BRAVE), "no Brave below the threshold");
		assertTrue(below.effectiveAutoAbilities(yumcaxBelow).isEmpty(), "and no granted trigger");

		MainWindow at = boardWithP1Damage(3, 5);
		CardData yumcax = makeFieldTextForward("Yumcax", "Earth", 9000, YUMCAX_TEXT);
		placeP1Forward(at, yumcax);
		assertTrue(at.fieldGrantCalculator.computeConditionalTraitsForTarget(yumcax, true)
				.contains(CardData.Trait.BRAVE), "Brave at 3 damage");
		assertEquals(1, at.effectiveAutoAbilities(yumcax).size(), "and the quoted trigger is live");

		at.breakP1Forward(0);
		assertEquals(1, at.gameState.getP1Hand().size(),
				"the granted \"when put into the Break Zone\" trigger draws as Yumcax leaves");
	}

	@Test
	void gilgameshGainsBraveAndASecondAttackOnlyAtThreeDamage() {
		MainWindow below = boardWithP1Damage(2, 0);
		CardData gilBelow = makeFieldTextForward("Gilgamesh", "Lightning", 7000, GILGAMESH_18_074L_TEXT);
		placeP1Forward(below, gilBelow);
		assertEquals(1, below.maxAttacksPerTurn(gilBelow));
		assertFalse(below.fieldGrantCalculator.computeConditionalTraitsForTarget(gilBelow, true)
				.contains(CardData.Trait.BRAVE));

		MainWindow at = boardWithP1Damage(3, 0);
		CardData gil = makeFieldTextForward("Gilgamesh", "Lightning", 7000, GILGAMESH_18_074L_TEXT);
		placeP1Forward(at, gil);
		assertEquals(2, at.maxAttacksPerTurn(gil), "\"can attack twice in the same turn\"");
		assertTrue(at.fieldGrantCalculator.computeConditionalTraitsForTarget(gil, true)
				.contains(CardData.Trait.BRAVE), "Brave comes from the same grant");
	}

	@Test
	void aCardsOwnPutIntoBreakZoneTriggerFires() {
		// Regression: the broken card is removed from the field lists before the break-zone
		// triggers are dispatched, so nothing walked reached its own "When [self] is put from the
		// field into the Break Zone" — only other cards' watchers ever fired.
		MainWindow mw = boardWithP1Damage(0, 5);
		CardData probe = makeAutoAbilityForward("Probe", "Fire", 7000,
				"When Probe is put from the field into the Break Zone, draw 1 card.");
		placeP1Forward(mw, probe);

		mw.breakP1Forward(0);

		assertEquals(1, mw.gameState.getP1Hand().size());
	}

	@Test
	void anExtraCostDeclarationIsNotAFieldAbility() {
		// "If you cast X, you may pay 《…》 as an extra cost." is a cast-time option read by
		// CardData.extraCost; the effect it enables lives in a later "if you paid the extra cost"
		// clause. Reported as an unrecognized field ability until it was excluded.
		String machinist = "If you cast Machinist, you may pay 《Fire》《3》 as an extra cost.[[br]]"
				+ "When Machinist enters the field, choose 1 Forward. If you paid the extra cost, deal it 8000 damage.";
		assertTrue(CardData.parseFieldAbilities(machinist, "Backup").isEmpty(),
				"the extra-cost declaration is not a field ability");
		assertNotNull(makeFieldTextForward("Machinist", "Lightning", 0, machinist).extraCost(),
				"and it is still read as an extra cost");
	}

	// =========================================================================================
	// Cast taxes — "The cost required for [all players|your opponent] to cast X is increased by N."
	//
	// Ultimecia 18-105H prints both halves of the family on one card, and neither parsed before:
	// the affected player was hard-coded to "your opponent", and the spec had to be a positive
	// type list, so "cards other than a Backup" matched nothing.  Her second line is behind
	// "Damage 5 --", which FieldCostReduction had no way to record — the prefix simply sat in
	// front of a ^-anchored pattern and stopped it matching.  Garnet 28-098H shows the cost of
	// that gap in the other direction: her "Damage 3 -- … is reduced by 1" matched under find(),
	// which ignores the prefix, so the discount was live from turn one.
	//
	// Terra 1-046H prints "increases by 1" where every later card prints "is increased by 1".
	// Same effect, and it parsed as nothing at all.
	// =========================================================================================

	private static final String ULTIMECIA_18_105H_TEXT =
			"The cost required for all players to cast cards other than a Backup is increased by 1.[[br]]"
			+ "   Damage 5 -- The cost required for your opponent to cast cards other than a Backup is increased by 1.[[br]]"
			+ "   [[s]]Hell's Judgement[[/]] 《S》《3》《Dull》: All the Forwards other than Ultimecia lose 8000 power until the end of the turn.";
	private static final String TERRA_1_046H_TAX =
			"The cost required for your opponent to cast Summons increases by 1.";
	private static final String GARNET_28_098H_DISCOUNT =
			"Damage 3 -- The cost required to cast your Summons is reduced by 1 (it cannot become 0).";
	private static final String GOGO_27_099H_RESTRICTION =
			"You can only cast Gogo during your opponent's turn.";

	/** A Forward built from its printed text, with the cast-cost modifiers parsed alongside. */
	private static CardData makeCostTextForward(String name, String element, int cost, String text) {
		return new CardData(null, name, element, cost, 7000, "Forward", false, 0, false, false,
				CardData.parseTraits(text, name), 0, List.of(), null, List.of(),
				CardData.parseActionAbilities(text), CardData.parseAutoAbilities(text),
				CardData.parseFieldAbilities(text, "Forward"),
				CardData.parseIfControlBoosts(text, "Forward"),
				CardData.parseFieldPowerGrants(text, "Forward"),
				List.of(),
				CardData.parseFieldCostReductions(text, "Forward"),
				CardData.parseSelfCostModifiers(text),
				List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	@Test
	void ultimeciaParsesAsTwoTaxesThatDifferInWhoPaysAndWhen() {
		List<FieldCostReduction> mods =
				CardData.parseFieldCostReductions(ULTIMECIA_18_105H_TEXT, "Forward");

		assertEquals(2, mods.size(), "one line per printed sentence; the action ability contributes none");

		FieldCostReduction allPlayers = mods.get(0);
		assertEquals(-1, allPlayers.amountPerUnit(), "an increase is stored as a negative reduction");
		assertFalse(allPlayers.opponentOnly(), "\"all players\" taxes Ultimecia's controller too");
		assertFalse(allPlayers.ownerOnly());
		assertEquals(0, allPlayers.damageThreshold(), "the first line is unconditional");
		assertTrue(allPlayers.inclForwards());
		assertTrue(allPlayers.inclMonsters());
		assertTrue(allPlayers.inclSummons());
		assertFalse(allPlayers.inclBackups(), "\"cards other than a Backup\" is the complement of Backup");

		FieldCostReduction damageGated = mods.get(1);
		assertEquals(-1, damageGated.amountPerUnit());
		assertTrue(damageGated.opponentOnly(), "the second line names the opponent");
		assertEquals(5, damageGated.damageThreshold(), "\"Damage 5 --\" is carried, not discarded");
	}

	@Test
	void neitherUltimeciaLineIsReportedAsAnUnrecognizedFieldAbility() {
		// Both are static card properties read through fieldCostReductions(), so — like every
		// other cost declaration — they must not survive as field abilities.
		assertTrue(CardData.parseFieldAbilities(ULTIMECIA_18_105H_TEXT, "Forward").isEmpty());
	}

	@Test
	void ultimeciasFirstTaxAppliesToBothPlayersButSparesBackups() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeCostTextForward("Ultimecia", "Dark", 5, ULTIMECIA_18_105H_TEXT));

		CardData forward = makeForward("Squall", "Ice", 3, 8000);
		CardData backup  = makeJobCard("Quistis", "Ice", "Backup", "SeeD");

		assertEquals(4, mw.applyFieldReductions(3, forward, true),  "her own controller pays it too");
		assertEquals(4, mw.applyFieldReductions(3, forward, false), "and so does the opponent");
		assertEquals(3, mw.applyFieldReductions(3, backup,  true),  "Backups are exempt");
		assertEquals(3, mw.applyFieldReductions(3, backup,  false));
	}

	@Test
	void ultimeciasSecondTaxIsSilentUntilHerControllerHasFiveDamage() {
		MainWindow below = boardWithP1Damage(4, 0);
		placeP1Forward(below, makeCostTextForward("Ultimecia", "Dark", 5, ULTIMECIA_18_105H_TEXT));
		assertEquals(4, below.applyFieldReductions(3, makeForward("Squall", "Ice", 3, 8000), false),
				"one point short: only the all-players line is live");

		MainWindow at = boardWithP1Damage(5, 0);
		placeP1Forward(at, makeCostTextForward("Ultimecia", "Dark", 5, ULTIMECIA_18_105H_TEXT));
		assertEquals(5, at.applyFieldReductions(3, makeForward("Squall", "Ice", 3, 8000), false),
				"both lines stack on the opponent");
		assertEquals(4, at.applyFieldReductions(3, makeForward("Squall", "Ice", 3, 8000), true),
				"the damage-gated half never touches her own controller");
	}

	@Test
	void terrasOlderIncreasesByWordingTaxesOpponentSummonsToo() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeCostTextForward("Terra", "Ice", 4, TERRA_1_046H_TAX));

		CardData summon = makeSummon("Shiva", "Ice", 2, "");
		assertEquals(3, mw.applyFieldReductions(2, summon, false), "the opponent pays the tax");
		assertEquals(2, mw.applyFieldReductions(2, summon, true),  "Terra's controller does not");
	}

	@Test
	void garnetsDamageGatedDiscountWaitsForHerThirdDamage() {
		CardData summon = makeSummon("Ramuh", "Lightning", 3, "");

		MainWindow below = boardWithP1Damage(2, 0);
		placeP1Forward(below, makeCostTextForward("Garnet", "Water", 4, GARNET_28_098H_DISCOUNT));
		assertEquals(3, below.applyFieldReductions(3, summon, true),
				"\"Damage 3 --\" used to be ignored, making this discount live from turn one");

		MainWindow at = boardWithP1Damage(3, 0);
		placeP1Forward(at, makeCostTextForward("Garnet", "Water", 4, GARNET_28_098H_DISCOUNT));
		assertEquals(2, at.applyFieldReductions(3, summon, true));
	}

	@Test
	void gogosBackAttackRestrictionIsACastRestrictionNotAFieldAbility() {
		assertTrue(CardData.parseFieldAbilities(GOGO_27_099H_RESTRICTION, "Forward").isEmpty(),
				"the sentence is read through castRestriction(), like every other cast condition");

		CastRestriction cr = makeFieldTextForward("Gogo", "Water", 8000, GOGO_27_099H_RESTRICTION)
				.castRestriction();
		assertNotNull(cr);
		assertTrue(cr.opponentTurnOnly(), "Back Attack: castable only on the opponent's turn");
	}

	// =========================================================================================
	// Nine 13-123L: "You can dull 1 active Fire Job Class Zero Cadet Forward you control and
	// 1 active Lightning Job Class Zero Cadet Forward you control (instead of paying the CP cost)
	// to cast Nine."
	//
	// The alternate-cost family could pay with Crystals, CP, a removed Backup or a Break Zone
	// removal, but not by dulling, so this sentence parsed as nothing and was reported as an
	// unrecognized field ability. It reuses DullForwardCost — the same record ability costs use —
	// so the filters and the eligibility test are the ones already written.
	//
	// The two clauses have to be solved together. A Fire/Lightning Cadet matches both and can only
	// be dulled once, so checking each clause on its own would call a board with one such Forward
	// payable.
	// =========================================================================================

	private static final String NINE_13_123L_ALT_COST =
			"You can dull 1 active Fire Job Class Zero Cadet Forward you control and "
			+ "1 active Lightning Job Class Zero Cadet Forward you control "
			+ "(instead of paying the CP cost) to cast Nine.";
	private static final String PHOENIX_26_017R_ALT_COST =
			"During your turn, you can dull 2 active Fire Forwards you control "
			+ "(instead of paying the CP cost) to cast Phoenix.";

	/** A Class Zero Cadet Forward of the given element(s) — "Fire", or "Fire/Lightning" for both. */
	private static CardData makeCadet(String name, String element) {
		return makeJobCard(name, element, "Forward", "Class Zero Cadet");
	}

	private static CardData nine() {
		return makeFieldTextForward("Nine", "Fire", 8000, NINE_13_123L_ALT_COST);
	}

	@Test
	void ninesAlternateCostParsesAsTwoDistinctDullRequirements() {
		List<DullForwardCost> costs = nine().altDullCosts();

		assertEquals(2, costs.size(), "one clause per element");
		assertEquals(1, costs.get(0).count());
		assertEquals("Fire", costs.get(0).element());
		assertEquals("Class Zero Cadet", costs.get(0).job(), "the Job runs up to \"Forward\", not past it");
		assertEquals("Lightning", costs.get(1).element());
		assertEquals("Class Zero Cadet", costs.get(1).job());
		assertFalse(nine().altDullYourTurnOnly(), "no timing clause on the Forward printing");
	}

	@Test
	void theAlternateCostSentenceIsNotAFieldAbility() {
		assertTrue(CardData.parseFieldAbilities(NINE_13_123L_ALT_COST, "Forward").isEmpty());
	}

	@Test
	void ninesAlternateCostNeedsBothElementsActiveOnTheField() {
		MainWindow empty = new MainWindow();
		assertFalse(empty.canPayAltDullCost(nine()), "no Forwards at all");

		MainWindow oneElement = new MainWindow();
		placeP1Forward(oneElement, makeCadet("Ace", "Fire"));
		placeP1Forward(oneElement, makeCadet("Cater", "Fire"));
		assertFalse(oneElement.canPayAltDullCost(nine()), "two Fire Cadets still leave the Lightning clause unpaid");

		MainWindow both = new MainWindow();
		placeP1Forward(both, makeCadet("Ace", "Fire"));
		placeP1Forward(both, makeCadet("Trey", "Lightning"));
		assertTrue(both.canPayAltDullCost(nine()));
	}

	@Test
	void oneDualElementCadetCannotPayBothClausesAtOnce() {
		MainWindow alone = new MainWindow();
		placeP1Forward(alone, makeCadet("Rem", "Fire/Lightning"));
		assertFalse(alone.canPayAltDullCost(nine()),
				"it matches either clause, but it can only be dulled once");

		MainWindow withPartner = new MainWindow();
		placeP1Forward(withPartner, makeCadet("Rem", "Fire/Lightning"));
		placeP1Forward(withPartner, makeCadet("Ace", "Fire"));
		assertTrue(withPartner.canPayAltDullCost(nine()),
				"Ace covers Fire, which frees Rem for Lightning");
	}

	@Test
	void aDullCadetCannotBePaidWithAndNorCanAnOffJobForward() {
		MainWindow dulled = new MainWindow();
		placeP1Forward(dulled, makeCadet("Ace", "Fire"));
		placeP1Forward(dulled, makeCadet("Trey", "Lightning"));
		dulled.p1ForwardStates.set(1, CardState.DULL);
		assertFalse(dulled.canPayAltDullCost(nine()), "the cost names active Forwards");

		MainWindow offJob = new MainWindow();
		placeP1Forward(offJob, makeCadet("Ace", "Fire"));
		placeP1Forward(offJob, makeJobCard("Ramza", "Lightning", "Forward", "Squire"));
		assertFalse(offJob.canPayAltDullCost(nine()), "a Lightning Forward that is no Cadet does not count");
	}

	@Test
	void theSummonPrintingCarriesItsDuringYourTurnRestriction() {
		CardData phoenix = makeFieldTextForward("Phoenix", "Fire", 0, PHOENIX_26_017R_ALT_COST);
		List<DullForwardCost> costs = phoenix.altDullCosts();

		assertEquals(1, costs.size());
		assertEquals(2, costs.get(0).count(), "one clause covering two Forwards");
		assertEquals("Fire", costs.get(0).element());
		assertNull(costs.get(0).job(), "no Job filter on this printing");
		assertTrue(phoenix.altDullYourTurnOnly());
	}

	// =========================================================================================
	// "[Name] cannot leave the field due to your opponent's Summons or abilities."
	// Chaos B-001, Spiritus B-002, President Shinra B-029, Hojo B-030 — all Backups.
	//
	// Wider than the "cannot be broken" shield next to it: every way an opponent's effect could
	// move the card off the field is covered, so it is carried as its own printed trait and
	// consulted at each exit — break, remove from game, return to hand. What it does not cover is
	// anything that is not an opponent's Summon or ability: the controller's own effects, and
	// causes like combat damage or a cost its controller pays.
	// =========================================================================================

	private static final String HOJO_B_030_SHIELD =
			"Hojo cannot leave the field due to your opponent's Summons or abilities.";

	/** A Backup built from its printed text, with traits and field abilities parsed. */
	private static CardData makeTextBackup(String name, String element, String text) {
		return new CardData(null, name, element, 2, 0, "Backup", false, 0, false, false,
				CardData.parseTraits(text, name), 0, List.of(), null, List.of(),
				List.of(), CardData.parseAutoAbilities(text),
				CardData.parseFieldAbilities(text, "Backup"),
				CardData.parseIfControlBoosts(text, "Backup"),
				CardData.parseFieldPowerGrants(text, "Backup"),
				List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	private static ForwardTarget p1Backup(int slot) {
		return new ForwardTarget(true, slot, ForwardTarget.CardZone.BACKUP);
	}

	@Test
	void theShieldIsAPrintedTraitAndOnlyOnTheCardItNames() {
		assertTrue(CardData.parseTraits(HOJO_B_030_SHIELD, "Hojo")
				.contains(CardData.Trait.CANNOT_LEAVE_FIELD_BY_OPP));
		assertFalse(CardData.parseTraits(HOJO_B_030_SHIELD, "Rufus")
				.contains(CardData.Trait.CANNOT_LEAVE_FIELD_BY_OPP),
				"the sentence is a statement about Hojo, not about whoever quotes it");
		assertFalse(CardData.parseTraits("Hojo cannot be broken.", "Hojo")
				.contains(CardData.Trait.CANNOT_LEAVE_FIELD_BY_OPP),
				"and the narrower break shield is a different trait");
	}

	@Test
	void anOpponentsEffectCannotBreakRemoveOrBounceHojo() {
		MainWindow mw = new MainWindow();
		mw.placeCardInFirstBackupSlot(makeTextBackup("Hojo", "Dark", HOJO_B_030_SHIELD));

		GameContext opp = mw.buildGameContext(false);

		opp.breakTarget(p1Backup(0));
		assertNotNull(mw.p1BackupCards[0], "the opponent's ability cannot break him");
		assertTrue(mw.gameState.getP1BreakZone().isEmpty());

		opp.removeTargetFromGame(p1Backup(0));
		assertNotNull(mw.p1BackupCards[0], "nor remove him from the game");

		opp.returnP1BackupToHand(0);
		assertNotNull(mw.p1BackupCards[0], "nor return him to hand");
		assertTrue(mw.gameState.getP1Hand().isEmpty());
	}

	@Test
	void hojosOwnControllerIsUnrestricted() {
		MainWindow mw = new MainWindow();
		mw.placeCardInFirstBackupSlot(makeTextBackup("Hojo", "Dark", HOJO_B_030_SHIELD));

		mw.buildGameContext(true).breakTarget(p1Backup(0));

		assertNull(mw.p1BackupCards[0], "the shield names the opponent's effects only");
		assertEquals(1, mw.gameState.getP1BreakZone().size());
	}

	@Test
	void aBackupWithoutTheShieldStillLeavesTheFieldNormally() {
		MainWindow mw = new MainWindow();
		mw.placeCardInFirstBackupSlot(makeTextBackup("Shinra Trooper", "Dark", ""));

		mw.buildGameContext(false).breakTarget(p1Backup(0));

		assertNull(mw.p1BackupCards[0], "the guard is scoped to the printed trait");
	}

	// =========================================================================================
	// Kam'lanaut 18-072C: "Kam'lanaut cannot be chosen by a Multi-Element Forward's ability."
	//
	// The narrowest immunity in the family, and the only one that reads the card doing the
	// choosing rather than the card being chosen. Both halves of "Multi-Element Forward" are
	// load-bearing: a Summon is not a Forward, so it is never caught however many Elements it
	// carries, and a single-Element Forward's ability is ordinary.
	//
	// No player is named, so — like "cannot be chosen by Summons" and unlike the "by your
	// opponent's ..." printings — it also stops Kam'lanaut's own controller.
	// =========================================================================================

	private static final String KAMLANAUT_18_072C_IMMUNITY =
			"Kam'lanaut cannot be chosen by a Multi-Element Forward's ability.";

	private static CardData kamlanaut() {
		return makeForwardWithText("Kam'lanaut", "Light", 4, 8000, KAMLANAUT_18_072C_IMMUNITY);
	}

	@Test
	void theImmunityIsReadOffKamlanautAndOnlyWhenItNamesHim() {
		assertTrue(ActionResolver.hasCannotBeChosenByMultiElementForwardAbility(kamlanaut()));
		assertFalse(ActionResolver.hasCannotBeChosenByMultiElementForwardAbility(
						makeForwardWithText("Eald'narche", "Light", 4, 8000, KAMLANAUT_18_072C_IMMUNITY)),
				"the sentence is a statement about Kam'lanaut, not about whoever quotes it");
	}

	@Test
	void onlyAMultiElementForwardsAbilityIsBlocked() {
		MainWindow mw = new MainWindow();
		CardData kam = kamlanaut();
		placeP1Forward(mw, kam);

		CardData multiFwd  = makeForward("Y'shtola", "Wind/Water", 4, 8000);
		CardData singleFwd = makeForward("Vaan", "Wind", 3, 7000);
		CardData multiSum  = makeSummon("Ragnarok", "Fire/Ice", 3, "");

		assertTrue(mw.isProtectedFromChoice(kam, true, false, false, multiFwd),
				"a Multi-Element Forward's ability cannot choose him");
		assertFalse(mw.isProtectedFromChoice(kam, true, false, false, singleFwd),
				"one Element is not Multi-Element");
		assertFalse(mw.isProtectedFromChoice(kam, true, false, true, multiSum),
				"a Summon is not a Forward, whatever its Elements");
		assertFalse(mw.isProtectedFromChoice(kam, true, false, false, null),
				"an unknown source cannot be shown to be one");
	}

	@Test
	void theImmunityBindsKamlanautsOwnControllerToo() {
		MainWindow mw = new MainWindow();
		CardData kam = kamlanaut();
		placeP1Forward(mw, kam);
		CardData multiFwd = makeForward("Y'shtola", "Wind/Water", 4, 8000);

		assertTrue(mw.isProtectedFromChoice(kam, true, true, false, multiFwd),
				"no player is named, so the shield is symmetric");
	}

	@Test
	void anElementOverrideOnTheSourceIsWhatCounts() {
		// The Element a card has now, not the one it was printed with — the same reading the
		// share-its-Element immunity beside this one uses.
		MainWindow mw = new MainWindow();
		CardData kam = kamlanaut();
		placeP1Forward(mw, kam);
		CardData multiFwd = makeForward("Y'shtola", "Wind/Water", 4, 8000);

		mw.elementOverrideMap.put(multiFwd, "Wind");

		assertFalse(mw.isProtectedFromChoice(kam, true, false, false, multiFwd),
				"an effect has made it single-Element, so its ability chooses freely");
	}

	// =========================================================================================
	// King 9-010R: "You can discard 1 Job Class Zero Cadet (instead of paying the CP cost) to
	// play King from your hand onto the field."
	//
	// The discard alt cost already existed for False Hero 18-087C's "… to cast False Hero", but
	// two things kept King's from working. His tail is the "play … from your hand onto the field"
	// wording, which the pattern did not carry; and the entry was only ever looked for on cards
	// already on the field. Both corpus printings put the sentence on the card the cost buys —
	// which is in hand at the moment it matters, so the field scan could never see it. False
	// Hero's cost parsed and was reported as recognized, but was never actually offered.
	// =========================================================================================

	private static final String KING_9_010R_ALT_COST =
			"You can discard 1 Job Class Zero Cadet (instead of paying the CP cost) "
			+ "to play King from your hand onto the field.";
	private static final String FALSE_HERO_18_087C_ALT_COST =
			"You can discard 1 Job Manikin (instead of paying the CP cost) to cast False Hero.";

	@Test
	void bothTailsOfTheDiscardAltCostNameTheirCard() {
		Matcher king = AutoAbilityTriggers.FA_DISCARD_JOB_TO_CAST.matcher(KING_9_010R_ALT_COST);
		assertTrue(king.find(), "the \"play … from your hand onto the field\" tail used to miss entirely");
		assertEquals("King", AutoAbilityTriggers.discardJobToCastTarget(king),
				"the name stops before \"from your hand\"");
		assertEquals("1", king.group("count"));
		assertEquals("Class Zero Cadet", king.group("job").trim());

		Matcher falseHero = AutoAbilityTriggers.FA_DISCARD_JOB_TO_CAST.matcher(FALSE_HERO_18_087C_ALT_COST);
		assertTrue(falseHero.find(), "and the original \"to cast\" tail still matches");
		assertEquals("False Hero", AutoAbilityTriggers.discardJobToCastTarget(falseHero));
		assertEquals("Manikin", falseHero.group("job").trim());
	}

	@Test
	void kingsOwnPrintingIsFoundWhileHeSitsInHand() {
		MainWindow mw = new MainWindow();
		CardData king = makeForwardWithText("King", "Fire", 5, 8000, KING_9_010R_ALT_COST);
		mw.gameState.getP1Hand().add(king);

		assertEquals(1, mw.findDiscardCastGrants(king, true).size(),
				"the cost is printed on King himself, and the field scan cannot see a card in hand");
	}

	@Test
	void aDiscardCostPrintedAboutSomebodyElseIsNotOffered() {
		MainWindow mw = new MainWindow();
		CardData other = makeForwardWithText("Ace", "Fire", 4, 7000, KING_9_010R_ALT_COST);
		mw.gameState.getP1Hand().add(other);

		assertTrue(mw.findDiscardCastGrants(other, true).isEmpty(),
				"the sentence buys King, not whoever is holding it");
	}

	// =========================================================================================
	// The any-Summon shield's find() over-run.
	//
	// STANDALONE_NAMED_CANNOT_BE_CHOSEN_ANY_SUMMON ended at "Summons" and matched with find(), so
	// it claimed every longer sentence beginning that way and installed a blanket any-Summon
	// shield in place of the real effect. Three cards were affected, and in each the substitution
	// was silent — the printed qualifier simply vanished:
	//
	//   Kam'lanaut 5-148H  "…or abilities that share its Element"  → immune to ALL Summons
	//   Rubicante  2-023H  "Name 1 Element. …of the named Element" → never reached its own parser
	//   Hein      10-129L  same, plus a damage nullification half  → likewise
	//
	// Requiring the sentence to end at "Summons" sends each on to the branch that reads it. The
	// characterization file could not catch this: every branch of tryParseCannotBeChosenStandalone
	// reports the one label "CannotBeChosen", so the description never moved while the effect did.
	// Hence the assertions below are on what the parsed effect calls, not on what it is named.
	// =========================================================================================

	private static final String KAMLANAUT_5_148H_SHARED_ELEMENT =
			"Kam'lanaut cannot be chosen by Summons or abilities that share its Element.";
	private static final String RUBICANTE_2_023H_BARRIER_SHIFT =
			"[[s]]Barrier Shift[[/]] 《S》《Fire》: Name 1 Element. "
			+ "Rubicante cannot be chosen by Summons or abilities of the named Element this turn.";
	private static final String HEIN_10_129L_NAME_ELEMENT =
			"Discard 1 card: Name 1 Element. During this turn, Hein cannot be chosen by Summons or "
			+ "abilities of the named Element and if Hein is dealt damage by a Summon or an ability "
			+ "of the named Element, the damage becomes 0 instead.";

	@Test
	void theUnqualifiedShieldStillMatchesTheCardsThatPrintIt() {
		// The four clean printings in the corpus, which the tightened tail must not disturb.
		assertTrue(ActionResolver.hasCannotBeChosenByAnySummonFieldAbility(
				makeForwardWithText("Belgemine", "Water", 4, 8000,
						"Belgemine cannot be chosen by Summons.")));
		assertTrue(ActionResolver.hasCannotBeChosenByAnySummonFieldAbility(
				makeForwardWithText("Mecha Chocobo", "Wind", 3, 7000,
						"Mecha Chocobo cannot be chosen by Summons.")));
	}

	@Test
	void kamlanautIsOnlyImmuneToSummonsSharingHisElement() {
		CardData kam = makeForwardWithText("Kam'lanaut", "Dark", 5, 9000, KAMLANAUT_5_148H_SHARED_ELEMENT);

		assertFalse(ActionResolver.hasCannotBeChosenByAnySummonFieldAbility(kam),
				"the blanket shield was the bug: his printing qualifies which Summons");
		assertTrue(ActionResolver.hasCannotBeChosenByOwnElementFieldAbility(kam));

		MainWindow mw = new MainWindow();
		placeP1Forward(mw, kam);

		assertTrue(mw.isProtectedFromChoice(kam, true, false, true, makeSummon("Bahamut", "Dark", 5, "")),
				"a Dark Summon shares his Element");
		assertFalse(mw.isProtectedFromChoice(kam, true, false, true, makeSummon("Ifrit", "Fire", 3, "")),
				"a Fire Summon does not, and used to be blocked anyway");
		assertTrue(mw.isProtectedFromChoice(kam, true, false, false,
						makeForward("Dark Knight", "Dark", 4, 8000)),
				"\"Summons or abilities\" — a Dark ability is caught too");
	}

	@Test
	void rubicanteReachesHisOwnNameAnElementParser() {
		CardData rubicante = makeForwardWithText("Rubicante", "Fire", 5, 9000, RUBICANTE_2_023H_BARRIER_SHIFT);
		String effect = rubicante.actionAbilities().get(0).effectText();

		Consumer<GameContext> fn = ActionResolver.parse(effect, rubicante);
		assertNotNull(fn);

		GameContext ctx = mock(GameContext.class);
		when(ctx.selectElement(anyString())).thenReturn("Ice");
		fn.accept(ctx);

		verify(ctx).shieldNamedCardCannotBeChosenByElement("Rubicante", "Ice");
		verify(ctx, never()).shieldNamedCardCannotBeChosenByAnySummon(anyString());
	}

	@Test
	void heinKeepsBothHalvesOfHisNamedElementEffect() {
		CardData hein = makeForwardWithText("Hein", "Dark", 4, 8000, HEIN_10_129L_NAME_ELEMENT);
		String effect = hein.actionAbilities().get(0).effectText();

		Consumer<GameContext> fn = ActionResolver.parse(effect, hein);
		assertNotNull(fn);

		GameContext ctx = mock(GameContext.class);
		when(ctx.selectElement(anyString())).thenReturn("Wind");
		fn.accept(ctx);

		verify(ctx).shieldNamedCardCannotBeChosenByElement("Hein", "Wind");
		verify(ctx).nullifyNamedCardDamageByElement("Hein", "Wind");
		verify(ctx, never()).shieldNamedCardCannotBeChosenByAnySummon(anyString());
	}

	// =========================================================================================
	// Threshold self-grants that read something other than the board's card counts.
	//
	// Galuf 3-077H  "If you have 4 or more cards in your hand, Galuf gains +2000 power."
	//               "If you have 5 or more cards in your hand, Galuf gains Brave."
	// Kefka 3-079H  "If you control 3 or more different Element Backups, Kefka gains +3000 power."
	//               "If you control 5 or more different Element Backups, Kefka gains Brave."
	//
	// Both print one line per threshold, so each card carries two independent IfControlBoosts that
	// switch on at different points — the power one alone between the thresholds, both above.
	//
	// IfControlBoost already had a hand-size condition, but only as a ceiling ("N cards or less",
	// Zidane-style); Galuf's is a floor and the comparison runs the other way, so it needed its
	// own field rather than a reused one. Kefka counts DISTINCT Elements among his Backups, which
	// is not the same question as the count of Backups that share no Element (the condition next
	// to it): two Fire Backups and an Ice one are 3 Backups but only 2 Elements.
	// =========================================================================================

	private static final String GALUF_3_077H_TEXT =
			"If you have 4 or more cards in your hand, Galuf gains +2000 power.[[br]]"
			+ "If you have 5 or more cards in your hand, Galuf gains Brave.";
	private static final String KEFKA_3_079H_TEXT =
			"If you control 3 or more different Element Backups, Kefka gains +3000 power.[[br]]"
			+ "If you control 5 or more different Element Backups, Kefka gains Brave.";

	/** Puts {@code n} throwaway cards into P1's hand. */
	private static void fillP1Hand(MainWindow mw, int n) {
		mw.gameState.getP1Hand().clear();
		for (int i = 0; i < n; i++) mw.gameState.getP1Hand().add(makeForward("Card" + i, "Fire", 1, 1000));
	}

	@Test
	void galufsTwoLinesParseAsSeparateHandSizeFloors() {
		List<IfControlBoost> boosts = CardData.parseIfControlBoosts(GALUF_3_077H_TEXT, "Forward");

		assertEquals(2, boosts.size(), "one grant per printed threshold");
		assertEquals(4, boosts.get(0).minOwnHandSize());
		assertEquals(2000, boosts.get(0).powerBonus());
		assertEquals(5, boosts.get(1).minOwnHandSize());
		assertTrue(boosts.get(1).grantedTraits().contains(CardData.Trait.BRAVE));
		assertEquals(0, boosts.get(0).maxOwnHandSize(),
				"the ceiling condition is a different field; reusing it would invert the test");
	}

	@Test
	void galufsGrantsSwitchOnAtTheirOwnThresholds() {
		MainWindow mw = new MainWindow();
		List<IfControlBoost> boosts = CardData.parseIfControlBoosts(GALUF_3_077H_TEXT, "Forward");
		IfControlBoost power = boosts.get(0), brave = boosts.get(1);

		fillP1Hand(mw, 3);
		assertFalse(mw.icbConditionsMet(power, true), "3 cards is below both thresholds");
		assertFalse(mw.icbConditionsMet(brave, true));

		fillP1Hand(mw, 4);
		assertTrue(mw.icbConditionsMet(power, true), "+2000 power at 4");
		assertFalse(mw.icbConditionsMet(brave, true), "but not Brave yet");

		fillP1Hand(mw, 5);
		assertTrue(mw.icbConditionsMet(power, true), "both hold at 5");
		assertTrue(mw.icbConditionsMet(brave, true));
	}

	@Test
	void kefkaCountsDistinctElementsNotBackups() {
		MainWindow mw = new MainWindow();
		List<IfControlBoost> boosts = CardData.parseIfControlBoosts(KEFKA_3_079H_TEXT, "Forward");
		assertEquals(2, boosts.size());
		IfControlBoost power = boosts.get(0);
		assertEquals(3, power.minDifferentElementBackups());
		assertEquals(3000, power.powerBonus());

		mw.p1BackupCards[0] = makeJobCard("Fire A", "Fire", "Backup", "Soldier");
		mw.p1BackupCards[1] = makeJobCard("Fire B", "Fire", "Backup", "Soldier");
		mw.p1BackupCards[2] = makeJobCard("Ice A",  "Ice",  "Backup", "Soldier");
		assertFalse(mw.icbConditionsMet(power, true),
				"three Backups, but only two Elements between them");

		mw.p1BackupCards[3] = makeJobCard("Wind A", "Wind", "Backup", "Soldier");
		assertTrue(mw.icbConditionsMet(power, true), "Fire, Ice and Wind is three Elements");
	}

	@Test
	void aMultiElementBackupCountsForEachElementItCarries() {
		MainWindow mw = new MainWindow();
		IfControlBoost power = CardData.parseIfControlBoosts(KEFKA_3_079H_TEXT, "Forward").get(0);

		mw.p1BackupCards[0] = makeJobCard("Fire A", "Fire",     "Backup", "Soldier");
		mw.p1BackupCards[1] = makeJobCard("Dual",   "Ice/Wind", "Backup", "Soldier");

		assertTrue(mw.icbConditionsMet(power, true), "Fire + Ice + Wind across two Backups");
	}

	// =========================================================================================
	// Kimahri 1-103C: "Kimahri gains Elements of all the Characters opponent controls except
	// Light and Dark."
	//
	// Unlike "[Name] has all the Elements except X", which names a fixed set once and for all,
	// this is a standing query over the other side of the board — the Elements it grants change
	// as the opponent's Characters come and go. So it cannot be answered on the card, and instead
	// widens MainWindow's effectiveElements, which is what board-aware Element comparisons
	// already go through.
	// =========================================================================================

	private static final String KIMAHRI_1_103C_TEXT =
			"Kimahri gains Elements of all the Characters opponent controls except Light and Dark.";

	/** Kimahri as printed: an Earth Backup whose only text is the Element-gaining ability. */
	private static CardData kimahri() {
		return new CardData(null, "Kimahri", "Earth", 2, 0, "Backup", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(),
				CardData.parseFieldAbilities(KIMAHRI_1_103C_TEXT, "Backup"),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, KIMAHRI_1_103C_TEXT);
	}

	@Test
	void theExclusionListIsReadOffKimahriAndOnlyWhenHeNamesHimself() {
		assertEquals(Set.of("Light", "Dark"), kimahri().gainsOpponentCharacterElementsExcept());
		assertNull(makeForwardWithText("Kiros", "Earth", 3, 7000, KIMAHRI_1_103C_TEXT)
						.gainsOpponentCharacterElementsExcept(),
				"the sentence is about Kimahri, not about whoever quotes it");
	}

	@Test
	void kimahriGainsTheElementsFacingHimAndNoOthers() {
		MainWindow mw = new MainWindow();
		CardData kim = kimahri();
		mw.placeCardInFirstBackupSlot(kim);
		placeP2Forward(mw, makeForward("Squall", "Ice", 4, 8000));
		placeP2Forward(mw, makeForward("Cloud", "Fire", 4, 8000));

		assertTrue(mw.effectiveContainsElement(kim, "Earth"), "his printed Element stays");
		assertTrue(mw.effectiveContainsElement(kim, "Ice"));
		assertTrue(mw.effectiveContainsElement(kim, "Fire"));
		assertFalse(mw.effectiveContainsElement(kim, "Water"), "nobody facing him is Water");
	}

	@Test
	void theExcludedElementsAreNeverGained() {
		MainWindow mw = new MainWindow();
		CardData kim = kimahri();
		mw.placeCardInFirstBackupSlot(kim);
		placeP2Forward(mw, makeForward("Cecil", "Light", 5, 9000));
		placeP2Forward(mw, makeForward("Golbez", "Dark", 5, 9000));

		assertFalse(mw.effectiveContainsElement(kim, "Light"));
		assertFalse(mw.effectiveContainsElement(kim, "Dark"));
		assertEquals(List.of("Earth"), mw.effectiveElements(kim),
				"an opposing board of only Light and Dark leaves him as printed");
	}

	@Test
	void hisOwnSideContributesNothing() {
		MainWindow mw = new MainWindow();
		CardData kim = kimahri();
		mw.placeCardInFirstBackupSlot(kim);
		placeP1Forward(mw, makeForward("Ally", "Wind", 3, 7000));

		assertFalse(mw.effectiveContainsElement(kim, "Wind"),
				"the ability reads the Characters the opponent controls");
	}

	@Test
	void theGainTracksTheOpposingBoardRatherThanBeingFixedOnce() {
		MainWindow mw = new MainWindow();
		CardData kim = kimahri();
		mw.placeCardInFirstBackupSlot(kim);
		assertFalse(mw.effectiveContainsElement(kim, "Ice"), "empty board opposite him");

		CardData squall = makeForward("Squall", "Ice", 4, 8000);
		placeP2Forward(mw, squall);
		assertTrue(mw.effectiveContainsElement(kim, "Ice"));

		mw.buildGameContext(false).breakTarget(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD));
		assertFalse(mw.effectiveContainsElement(kim, "Ice"),
				"the Element leaves with the Character that supplied it");
	}

	// =========================================================================================
	// The gained Elements reaching the rest of the engine.
	//
	// effectiveContainsElement is only worth having if the Element-sensitive rules consult it, so
	// every field-card Element check now routes through it — targeting, counting, mass effects,
	// grants, and a Backup's CP production. What deliberately still reads printed Elements is
	// every card NOT on the field: hand, deck, Break Zone and removed-from-game. Kimahri gains
	// Elements while he is on the field, so a card sitting in a Break Zone has nothing to gain.
	// =========================================================================================

	/** Kimahri on P1's Backup row, with {@code oppElements} worth of Characters facing him. */
	private static MainWindow boardWithKimahriFacing(String... oppElements) {
		MainWindow mw = new MainWindow();
		mw.p1BackupCards[0] = kimahri();
		for (String e : oppElements) placeP2Forward(mw, makeForward("Opp " + e, e, 3, 7000));
		return mw;
	}

	@Test
	void aGainedElementCountsTowardFieldCounts() {
		MainWindow mw = boardWithKimahriFacing("Ice");
		GameContext ctx = mw.buildGameContext(true);

		assertEquals(1, ctx.countP1FieldCards(false, true, false, null, null, null, "Ice"),
				"Kimahri counts as an Ice Backup while an Ice Character faces him");
		assertEquals(1, ctx.countP1FieldCards(false, true, false, null, null, null, "Earth"),
				"and still as the Earth Backup he is printed as");
		assertEquals(0, ctx.countP1FieldCards(false, true, false, null, null, null, "Water"));
	}

	@Test
	void aGainedElementCountsForSelfFieldCount() {
		MainWindow mw = boardWithKimahriFacing("Wind");
		GameContext ctx = mw.buildGameContext(true);

		assertEquals(1, ctx.selfFieldCount("Wind", false, true, false));
		assertEquals(0, ctx.selfFieldCount("Fire", false, true, false));
	}

	@Test
	void aGainedElementSatisfiesAnIfYouControlCondition() {
		MainWindow mw = boardWithKimahriFacing("Lightning");
		ControlCondition lightningBackup = new ControlCondition(
				List.of(), 1, false, "Backup", "Lightning", null, null, 0, List.of());

		assertTrue(mw.controlConditionMet(lightningBackup, true),
				"\"if you control a Lightning Backup\" reads what he counts as now");
	}

	@Test
	void aGainedElementLetsHimProduceThatCpElement() {
		// A Backup produces CP of its Elements, and a gained Element is one of its Elements.
		MainWindow mw = boardWithKimahriFacing("Ice");
		CardData iceCard = makeForward("Shiva's Pupil", "Ice", 1, 5000);
		mw.gameState.getP1Hand().add(iceCard);
		mw.p1BackupStates[0] = CardState.ACTIVE;

		assertTrue(mw.canAffordCard(iceCard, 0),
				"the only CP source is Kimahri, and he counts as Ice while an Ice Character faces him");
	}

	@Test
	void withoutTheGainHeCannotPayForThatElement() {
		MainWindow mw = new MainWindow();
		mw.p1BackupCards[0] = kimahri();
		mw.p1BackupStates[0] = CardState.ACTIVE;
		CardData iceCard = makeForward("Shiva's Pupil", "Ice", 1, 5000);
		mw.gameState.getP1Hand().add(iceCard);

		assertFalse(mw.canAffordCard(iceCard, 0),
				"nothing faces him, so he is the Earth Backup he is printed as");
	}

	@Test
	void aCardInTheBreakZoneGainsNothing() {
		MainWindow mw = boardWithKimahriFacing("Ice");
		CardData buried = kimahri();
		mw.gameState.getP1BreakZone().add(buried);

		assertFalse(mw.effectiveContainsElement(buried, "Ice"),
				"the ability works while he is on the field; a Break Zone copy controls nothing");
		assertTrue(mw.effectiveContainsElement(buried, "Earth"));
	}

	// =========================================================================================
	// Bartz 19-048C: "《Dull》, put Bartz at the bottom of its owner's deck: Draw 2 cards."
	//
	// The cost phrase was not one ACTION_ABILITY_PATTERN knew, so the whole line failed to match
	// as an action ability and fell through to the field-ability bucket — which is where the
	// report found it. Bartz 16-128H's neighbouring "《4》, put Bartz into the Break Zone:" already
	// worked; this is the same shape sending the card somewhere else.
	//
	// The new group is appended after every numbered cost group on purpose: one inserted higher up
	// would renumber groups 6-11, which the parse site reads positionally.
	// =========================================================================================

	private static final String BARTZ_19_048C_TEXT =
			"《Dull》, put Bartz at the bottom of its owner's deck: Draw 2 cards.";

	@Test
	void bartzsLineParsesAsAnActionAbilityRatherThanAFieldOne() {
		assertTrue(CardData.parseFieldAbilities(BARTZ_19_048C_TEXT, "Forward").isEmpty(),
				"an unrecognised cost used to leave the whole ability in the field bucket");

		List<ActionAbility> abilities = CardData.parseActionAbilities(BARTZ_19_048C_TEXT);
		assertEquals(1, abilities.size());
		ActionAbility ab = abilities.get(0);
		assertTrue(ab.requiresDull(), "《Dull》 is still read alongside the new cost");
		assertEquals("Bartz", ab.bottomOfDeckCostCardName());
		assertEquals("Draw 2 cards.", ab.effectText());
		assertTrue(ab.cpCost().isEmpty(), "the cost is the dull and the card itself, no CP");
	}

	@Test
	void theBottomOfDeckCostSurvivesACostReduction() {
		// withReducedCp rebuilds the record; routing that through the compatibility constructor
		// would silently drop the cost from the copy.
		ActionAbility ab = CardData.parseActionAbilities(BARTZ_19_048C_TEXT).get(0);
		assertEquals("Bartz", ab.withReducedCp(1).bottomOfDeckCostCardName());
	}

	@Test
	void payingTheCostPutsBartzUnderHisOwnersDeck() {
		MainWindow mw = new MainWindow();
		CardData bartz = makeForwardWithText("Bartz", "Wind", 2, 5000, BARTZ_19_048C_TEXT);
		placeP1Forward(mw, bartz);
		int deckBefore = mw.gameState.getP1MainDeck().size();

		mw.autoAbilityTriggers.payBottomOfDeckCost(bartz.actionAbilities().get(0), bartz, true);

		assertTrue(mw.p1ForwardCards.isEmpty(), "he leaves the field to pay");
		assertEquals(deckBefore + 1, mw.gameState.getP1MainDeck().size());
		assertEquals("Bartz", mw.gameState.getP1MainDeck().peekLast().name(), "at the bottom, not the top");
		assertTrue(mw.gameState.getP1BreakZone().isEmpty(), "the deck, not the Break Zone");
	}

	@Test
	void aCostNamingSomebodyElseIsNotPaidBySource() {
		MainWindow mw = new MainWindow();
		CardData other = makeForwardWithText("Faris", "Wind", 2, 5000, BARTZ_19_048C_TEXT);
		placeP1Forward(mw, other);

		mw.autoAbilityTriggers.payBottomOfDeckCost(other.actionAbilities().get(0), other, true);

		assertEquals(1, mw.p1ForwardCards.size(),
				"the sentence names Bartz, so it says nothing about the card quoting it");
	}

	@Test
	void onlyTheCopyThatUsedTheAbilityLeaves() {
		// CardData is a record, so both players' Bartz are equal and only identity tells them
		// apart. The uniqueness rule keeps a player from controlling two, so one per side is the
		// board that can actually hold a pair.
		MainWindow mw = new MainWindow();
		CardData mine   = makeForwardWithText("Bartz", "Wind", 2, 5000, BARTZ_19_048C_TEXT);
		CardData theirs = makeForwardWithText("Bartz", "Wind", 2, 5000, BARTZ_19_048C_TEXT);
		placeP1Forward(mw, mine);
		placeP2Forward(mw, theirs);

		mw.autoAbilityTriggers.payBottomOfDeckCost(theirs.actionAbilities().get(0), theirs, false);

		assertTrue(mw.p2ForwardCards.isEmpty(), "the copy that used the ability paid");
		assertEquals(1, mw.p1ForwardCards.size(), "and the other player's Bartz is untouched");
		assertSame(mine, mw.p1ForwardCards.get(0));
		assertEquals("Bartz", mw.gameState.getP2MainDeck().peekLast().name(),
				"under its own owner's deck, not the other player's");
	}

	// =========================================================================================
	// Ravana, Savior of the Gnath 14-087L: "Ravana, Savior of the Gnath cannot gain Brave."
	//
	// A restriction on *gaining*, not on having. It sits beside "can attack 4 times in the same
	// turn", and Brave (attacking without dulling) is what would make that unbounded — so the
	// card bars the grant rather than removing a trait. Ravana prints no Brave of his own, but the
	// distinction is still the one the wording makes, and it is what separates this from the
	// "lose Haste" printings, which strip a trait already held.
	//
	// The suppression therefore applies only to the granted sources — temp traits, permanent
	// grants and conditional field grants — and leaves a printed trait alone.
	// =========================================================================================

	private static final String RAVANA_14_087L_TEXT =
			"Ravana, Savior of the Gnath can attack 4 times in the same turn.[[br]]"
			+ "Ravana, Savior of the Gnath cannot gain Brave.";

	private static CardData ravana() {
		return makeForwardWithText("Ravana, Savior of the Gnath", "Lightning", 5, 9000, RAVANA_14_087L_TEXT);
	}

	@Test
	void theRestrictionIsReadOffRavanaAndNamesOneTrait() {
		assertEquals(Set.of(CardData.Trait.BRAVE), ravana().cannotGainTraits());
		assertTrue(makeForwardWithText("Gnath Soldier", "Lightning", 3, 7000, RAVANA_14_087L_TEXT)
						.cannotGainTraits().isEmpty(),
				"the sentence names Ravana, so it says nothing about whoever quotes it");
	}

	@Test
	void aGrantedBraveDoesNotStickToRavana() {
		MainWindow mw = new MainWindow();
		CardData rav = ravana();
		placeP1Forward(mw, rav);

		mw.p1ForwardTempTraits.get(0).add(CardData.Trait.BRAVE);
		assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.BRAVE),
				"an until-end-of-turn grant is exactly what he cannot gain");

		mw.permanentTraits.computeIfAbsent(rav, k -> EnumSet.noneOf(CardData.Trait.class))
				.add(CardData.Trait.BRAVE);
		assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.BRAVE),
				"and a permanent grant is still a grant");
	}

	@Test
	void theRestrictionIsNarrowToTheTraitItNames() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, ravana());

		mw.p1ForwardTempTraits.get(0).add(CardData.Trait.FIRST_STRIKE);
		assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.FIRST_STRIKE),
				"only Brave is named");
	}

	@Test
	void aPrintedTraitIsNotOneThatWasGained() {
		// A card printing both the trait and the restriction keeps the trait: it never gained it.
		MainWindow mw = new MainWindow();
		CardData braveByPrint = makeForwardWithTraits("Ravana, Savior of the Gnath", "Lightning", 9000,
				Set.of(CardData.Trait.BRAVE));
		placeP1Forward(mw, braveByPrint);

		assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.BRAVE));
	}

	@Test
	void anotherForwardCanStillBeGrantedBrave() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, ravana());
		placeP1Forward(mw, makeForward("Ally", "Lightning", 3, 7000));

		mw.p1ForwardTempTraits.get(1).add(CardData.Trait.BRAVE);

		assertTrue(mw.effectiveP1HasTrait(1, CardData.Trait.BRAVE),
				"the restriction is Ravana's own, not a field-wide suppression");
	}

	// =========================================================================================
	// "you may pay an extra 《…》" — the same extra-cost declaration with the marker printed before
	// the tokens rather than after.
	//
	// EXTRA_COST_SUMMON only knew "you may pay 《…》 as an extra cost", so four cards' declarations
	// parsed as nothing: Prishe 14-128H, Ixion 17-090R, Fenrir 8-081R and Fina 8-060L — whose
	// lead-in is "If you pay the cost to play Fina onto the field" rather than "If you cast".
	// On Prishe (a Forward) the unmatched line showed up as an unrecognized field ability, which
	// is how it was found; on the Summons it simply meant the extra cost was never offered.
	// =========================================================================================

	private static final String PRISHE_14_128H_EXTRA_COST =
			"If you cast Prishe, you may pay an extra 《Wind》《Earth》《1》.";
	private static final String FINA_8_060L_EXTRA_COST =
			"If you pay the cost to play Fina onto the field, you may pay an extra 《Wind》《Wind》《Wind》.";

	@Test
	void anExtraCostIsNotAFieldAbilityInEitherWordOrder() {
		assertTrue(CardData.parseFieldAbilities(PRISHE_14_128H_EXTRA_COST, "Forward").isEmpty(),
				"this is a cast-time option, not a continuous effect");
		assertTrue(CardData.parseFieldAbilities(FINA_8_060L_EXTRA_COST, "Forward").isEmpty());
	}

	@Test
	void theMarkerFirstWordingStillYieldsTheSameFixedCpCost() {
		ExtraCost prishe = makeForwardWithText("Prishe", "Wind", 4, 8000, PRISHE_14_128H_EXTRA_COST)
				.extraCost();
		assertNotNull(prishe, "\"pay an extra 《…》\" used to parse as nothing at all");
		assertEquals(ExtraCost.Type.CP_FIXED, prishe.type());
		assertEquals(List.of("Wind", "Earth", ""), prishe.cpElements(),
				"《1》 is one generic CP, and the element order is as printed");

		ExtraCost fina = makeForwardWithText("Fina", "Wind", 5, 9000, FINA_8_060L_EXTRA_COST).extraCost();
		assertNotNull(fina, "the play-onto-the-field lead-in is the same declaration");
		assertEquals(List.of("Wind", "Wind", "Wind"), fina.cpElements());
	}

	@Test
	void theOriginalAsAnExtraCostWordingIsUnaffected() {
		// The tail-marker spelling shares the lead-in, so widening the pattern must not disturb it.
		String machinist = "If you cast Machinist, you may pay 《Fire》《3》 as an extra cost.";
		ExtraCost ec = makeForwardWithText("Machinist", "Fire", 3, 7000, machinist).extraCost();
		assertNotNull(ec);
		assertEquals(List.of("Fire", "", "", ""), ec.cpElements());
	}

	@Test
	void prishesOtherPrintedLinesAreLeftAlone() {
		// Only the declaration drops out; the damage-gated grant beside it is a real field ability.
		String full = PRISHE_14_128H_EXTRA_COST
				+ "[[br]]   Damage 3 -- Prishe gains +1000 power and Brave.";
		List<FieldAbility> fas = CardData.parseFieldAbilities(full, "Forward");

		assertEquals(1, fas.size(), "the extra-cost line is gone, the grant stays");
		assertEquals(3, fas.get(0).damageThreshold());
	}

	// =========================================================================================
	// The Magus Sisters (XIV) 20-083R — three field abilities, none of which parsed.
	//
	// Two separate causes:
	//
	// 1. "The Magus Sisters (XIV) cannot be chosen by your opponent's Summons." matched nothing
	//    because the card-name group excluded parentheses, and the group has to run right up to
	//    "cannot" — so it stalled on the ")". This is a general shape, not one card's quirk: 140
	//    cards carry a parenthesised name and 114 quote it in their own text.
	//
	// 2. "The Forwards opponent controls lose Haste." / "… cannot gain Haste." are the one-sided
	//    twins of Edward 2-031C's unqualified pair, and the engine only knew the global form —
	//    both the patterns and the suppression, which was a single board-wide boolean. Magus
	//    Sisters binds one side only, so the check had to learn which side is being asked about.
	// =========================================================================================

	private static final String MAGUS_SISTERS_20_083R_TEXT =
			"The Magus Sisters (XIV) cannot be chosen by your opponent's Summons.[[br]]"
			+ "The Forwards opponent controls lose Haste.[[br]]"
			+ "The Forwards opponent controls cannot gain Haste.";
	private static final String EDWARD_2_031C_TEXT =
			"All Forwards lose Haste.[[br]]Forwards cannot gain Haste.";

	private static CardData magusSisters() {
		return makeForwardWithText("The Magus Sisters (XIV)", "Earth", 5, 9000, MAGUS_SISTERS_20_083R_TEXT);
	}

	@Test
	void aParenthesisedNameCanNameItself() {
		CardData magus = magusSisters();
		assertEquals(3, magus.fieldAbilities().size());
		assertNotNull(ActionResolver.parse(magus.fieldAbilities().get(0).effectText(), magus),
				"the \")\" in the name used to stop the pattern dead");
	}

	@Test
	void magusSistersSuppressesHasteOnlyAcrossTheTable() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, magusSisters());
		placeP1Forward(mw, makeForwardWithTraits("Ally", "Earth", 7000, Set.of(CardData.Trait.HASTE)));
		placeP2Forward(mw, makeForwardWithTraits("Enemy", "Fire", 7000, Set.of(CardData.Trait.HASTE)));

		assertTrue(mw.effectiveP1HasTrait(1, CardData.Trait.HASTE),
				"her controller's own Forwards keep Haste — the sentence names the opponent's");
		assertFalse(mw.effectiveP2HasTrait(0, CardData.Trait.HASTE),
				"and the opposing Forward loses it");
	}

	@Test
	void theSuppressionFollowsWhoeverControlsHer() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, magusSisters());
		placeP1Forward(mw, makeForwardWithTraits("Ally", "Earth", 7000, Set.of(CardData.Trait.HASTE)));

		assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.HASTE),
				"on P2's side she suppresses P1's Forwards instead");
	}

	@Test
	void aGrantedHasteIsSuppressedAcrossTheTableToo() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, magusSisters());
		placeP2Forward(mw, makeForward("Enemy", "Fire", 3, 7000));

		mw.p2ForwardTempTraits.get(0).add(CardData.Trait.HASTE);

		assertFalse(mw.effectiveP2HasTrait(0, CardData.Trait.HASTE),
				"\"cannot gain Haste\" covers what \"lose Haste\" would not");
	}

	@Test
	void theUnqualifiedPrintingStillBindsBothSides() {
		// Edward 2-031C names no player, so a copy on one side must still reach the other.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForwardWithText("Edward", "Water", 2, 5000, EDWARD_2_031C_TEXT));
		placeP1Forward(mw, makeForwardWithTraits("Ally", "Water", 7000, Set.of(CardData.Trait.HASTE)));
		placeP2Forward(mw, makeForwardWithTraits("Enemy", "Fire", 7000, Set.of(CardData.Trait.HASTE)));

		assertFalse(mw.effectiveP1HasTrait(1, CardData.Trait.HASTE), "including his own side");
		assertFalse(mw.effectiveP2HasTrait(0, CardData.Trait.HASTE));
	}

	@Test
	void withNoSuppressorHasteIsUntouched() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForwardWithTraits("Ally", "Earth", 7000, Set.of(CardData.Trait.HASTE)));
		placeP2Forward(mw, makeForwardWithTraits("Enemy", "Fire", 7000, Set.of(CardData.Trait.HASTE)));

		assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.HASTE));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.HASTE));
	}

	// =========================================================================================
	// Royal Ripeness 5-007H — "cannot be chosen by [Element] Summons or [Element] abilities"
	//
	// The third member of the named-immunity family and the only one that names a literal
	// Element. Its two siblings read something other than the text to decide: Kam'lanaut 5-148H
	// ("share its Element") follows the carrier's own Element wherever an effect moves it, and
	// Rubicante 2-023H ("of the named Element") takes the Element from a player on resolution.
	// Royal Ripeness prints the Element, so the Element is captured rather than looked up — the
	// shield is Fire even if something makes Royal Ripeness Ice.
	//
	// It names no player, so it binds whoever is choosing, its own controller included.
	// =========================================================================================

	private static final String ROYAL_RIPENESS_5_007H_SHIELD =
			"Royal Ripeness cannot be chosen by Fire Summons or Fire abilities.";

	@Test
	void royalRipenessNamesTheElementItIsImmuneTo() {
		CardData ripeness = makeFieldAbilityCard("Royal Ripeness", "Fire", "Monster",
				ROYAL_RIPENESS_5_007H_SHIELD);

		assertEquals("Fire", ActionResolver.cannotBeChosenByElementFieldAbility(ripeness));
		// Neither sibling may claim it: one would make the shield follow the card's Element, the
		// other expects a player to name one.
		assertFalse(ActionResolver.hasCannotBeChosenByOwnElementFieldAbility(ripeness));
		assertFalse(ActionResolver.hasCannotBeChosenByAnySummonFieldAbility(ripeness),
				"the printing qualifies which Summons, so the blanket shield must not match");
	}

	@Test
	void theShieldIsSelfNamedLikeTheRestOfItsFamily() {
		CardData impostor = makeFieldAbilityCard("Cactuar", "Earth", "Monster",
				ROYAL_RIPENESS_5_007H_SHIELD);
		assertNull(ActionResolver.cannotBeChosenByElementFieldAbility(impostor),
				"the text shields Royal Ripeness, not whoever is holding the sentence");
	}

	@Test
	void onlyTheNamedElementsSummonsAndAbilitiesAreBlocked() {
		MainWindow mw = new MainWindow();
		CardData ripeness = makeFieldAbilityCard("Royal Ripeness", "Fire", "Monster",
				ROYAL_RIPENESS_5_007H_SHIELD);
		mw.placeCardInMonsterZone(ripeness);

		assertTrue(mw.isProtectedFromChoice(ripeness, true, false, true,
						makeSummon("Ifrit", "Fire", 3, "")),
				"a Fire Summon");
		assertTrue(mw.isProtectedFromChoice(ripeness, true, false, false,
						makeForward("Fire Mage", "Fire", 3, 7000)),
				"\"or Fire abilities\" — a Fire card's ability is caught too");
		assertFalse(mw.isProtectedFromChoice(ripeness, true, false, true,
						makeSummon("Shiva", "Ice", 2, "")),
				"an Ice Summon is not what the text names");
		assertFalse(mw.isProtectedFromChoice(ripeness, true, false, false, null),
				"nothing to compare an Element against");
	}

	@Test
	void theShieldBindsRoyalRipenessesOwnControllerToo() {
		MainWindow mw = new MainWindow();
		CardData ripeness = makeFieldAbilityCard("Royal Ripeness", "Fire", "Monster",
				ROYAL_RIPENESS_5_007H_SHIELD);
		mw.placeCardInMonsterZone(ripeness);

		assertTrue(mw.isProtectedFromChoice(ripeness, true, true, true,
						makeSummon("Ifrit", "Fire", 3, "")),
				"no player is named, so its controller's own Fire Summon is blocked as well");
	}

	// =========================================================================================
	// Sin 14-045H — "During your opponent's turn, the Forwards opponent controls cannot use
	// action abilities."
	//
	// Doubly scoped, and both halves point at the same player — the one who does NOT control Sin.
	// It binds that player's Forwards, and only while the turn is theirs, which is what makes it
	// a tax on responding to Sin's controller rather than a blanket lock. Enforced in
	// canActivateAbility, the single gate the ability menu and the AI both pass through.
	//
	// Action abilities only: under rule 6-1-1 a Special Ability is its own kind of ability, not a
	// form of action ability.
	// =========================================================================================

	private static final String SIN_14_045H_LOCK =
			"During your opponent's turn, the Forwards opponent controls cannot use action abilities.";
	private static final String PLAIN_ACTION_ABILITY =
			"《Dull》: Choose 1 Forward. Deal it 4000 damage.";

	/** P2 fields Sin; P1 fields a Forward with an ordinary action ability. Returns that Forward. */
	private static CardData sinFacingAnAbilityUser(MainWindow mw, GameState.Player whoseTurn) {
		advanceTo(mw, whoseTurn, GameState.GamePhase.MAIN_1);
		mw.placeP2CardInFirstBackupSlot(makeFieldAbilityCard("Sin", "Wind", "Backup", SIN_14_045H_LOCK));
		CardData user = makeForward("Vaan", "Wind", 3, 7000,
				CardData.parseActionAbilities(PLAIN_ACTION_ABILITY));
		placeP1Forward(mw, user);
		return user;
	}

	@Test
	void sinLocksTheOpposingForwardsOutOnTheirOwnTurn() {
		MainWindow mw = new MainWindow();
		CardData user = sinFacingAnAbilityUser(mw, GameState.Player.P1);
		ActionAbility ab = user.actionAbilities().get(0);

		assertTrue(mw.forwardActionAbilitiesLockedFor(true));
		assertFalse(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, user, true),
				"P1's turn, and P2 controls Sin");
	}

	@Test
	void theLockLiftsOnSinsControllersOwnTurn() {
		MainWindow mw = new MainWindow();
		CardData user = sinFacingAnAbilityUser(mw, GameState.Player.P2);
		ActionAbility ab = user.actionAbilities().get(0);

		assertFalse(mw.forwardActionAbilitiesLockedFor(true),
				"\"during your opponent's turn\" — this is not it");
		assertTrue(mw.canActivateAbility(ab, false, CardState.ACTIVE, 0, user, true));
	}

	@Test
	void sinDoesNotLockItsOwnControllersForwards() {
		MainWindow mw = new MainWindow();
		advanceTo(mw, GameState.Player.P2, GameState.GamePhase.MAIN_1);
		mw.placeP2CardInFirstBackupSlot(makeFieldAbilityCard("Sin", "Wind", "Backup", SIN_14_045H_LOCK));
		CardData ally = makeForward("Ally", "Wind", 3, 7000,
				CardData.parseActionAbilities(PLAIN_ACTION_ABILITY));
		placeP2Forward(mw, ally);

		assertFalse(mw.forwardActionAbilitiesLockedFor(false),
				"the text locks the opponent's Forwards, never its own side's");
		assertTrue(mw.canActivateAbility(ally.actionAbilities().get(0), false,
				CardState.ACTIVE, 0, ally, false));
	}

	@Test
	void aBackupsAbilityIsNotAForwardsAbility() {
		MainWindow mw = new MainWindow();
		advanceTo(mw, GameState.Player.P1, GameState.GamePhase.MAIN_1);
		mw.placeP2CardInFirstBackupSlot(makeFieldAbilityCard("Sin", "Wind", "Backup", SIN_14_045H_LOCK));
		CardData backup = makeForward("Scholar", "Wind", 2, 0,
				CardData.parseActionAbilities(PLAIN_ACTION_ABILITY));
		mw.placeCardInFirstBackupSlot(backup);

		assertTrue(mw.forwardActionAbilitiesLockedFor(true), "the lock is up");
		assertFalse(mw.isFieldForward(backup, true), "but a Backup is not a Forward");
		assertTrue(mw.canActivateAbility(backup.actionAbilities().get(0), false,
				CardState.ACTIVE, 0, backup, true));
	}

	@Test
	void aSpecialAbilityIsNotAnActionAbility() {
		MainWindow mw = new MainWindow();
		advanceTo(mw, GameState.Player.P1, GameState.GamePhase.MAIN_1);
		mw.placeP2CardInFirstBackupSlot(makeFieldAbilityCard("Sin", "Wind", "Backup", SIN_14_045H_LOCK));
		CardData bartz = makeForward("Bartz", "Wind", 3, 7000, CardData.parseActionAbilities(
				"[[s]]Spellblade[[/]] 《S》: Choose 1 Forward. Deal it 5000 damage."));
		placeP1Forward(mw, bartz);
		mw.gameState.getP1Hand().add(makeForward("Bartz", "Wind", 3, 7000)); // discarded for the 《S》

		ActionAbility s = bartz.actionAbilities().get(0);
		assertTrue(s.isSpecial(), "《S》 marks it Special");
		assertTrue(mw.forwardActionAbilitiesLockedFor(true), "the lock is up");
		assertTrue(mw.canActivateAbility(s, false, CardState.ACTIVE, 0, bartz, true),
				"rule 6-1-1 makes a Special Ability its own kind of ability, not an action ability");
	}

	@Test
	void sinLeavingTheFieldEndsTheLock() {
		MainWindow mw = new MainWindow();
		CardData user = sinFacingAnAbilityUser(mw, GameState.Player.P1);
		mw.p2BackupCards[0] = null;

		assertFalse(mw.forwardActionAbilitiesLockedFor(true));
		assertTrue(mw.canActivateAbility(user.actionAbilities().get(0), false,
				CardState.ACTIVE, 0, user, true));
	}

	// =========================================================================================
	// Kalmia 18-090R — "All cards in your Break Zone cannot be chosen by your opponent's Summons
	// or abilities."
	//
	// The wider twin of the Break Zone shield already in the engine: every card type rather than
	// Summons alone, and every way an effect could choose one rather than removal from the game
	// specifically. Opponent-scoped, so the zone's owner keeps choosing their own cards — which
	// is what leaves their recursion working.
	//
	// It shields against being CHOSEN. An effect that takes the whole zone without picking
	// anything is a different thing and is not stopped here.
	// =========================================================================================

	private static final String KALMIA_18_090R_BZ_SHIELD =
			"All cards in your Break Zone cannot be chosen by your opponent's Summons or abilities.";

	/** Every argument {@code selectCharactersFromBreakZone} takes past its first four, unfiltered. */
	private static List<ForwardTarget> chooseFromBz(GameContext ctx, boolean opponentZone) {
		return ctx.selectCharactersFromBreakZone(1, false, opponentZone, false,
				null, null, -1, null, -1, null, true, true, true,
				null, null, null, null, true, null, false);
	}

	@Test
	void kalmiaClosesHerBreakZoneToTheOpponent() {
		MainWindow mw = new MainWindow();
		mw.placeCardInFirstBackupSlot(
				makeFieldAbilityCard("Kalmia", "Water", "Backup", KALMIA_18_090R_BZ_SHIELD));
		mw.gameState.getP1BreakZone().add(makeForward("Fallen", "Water", 3, 7000));

		assertTrue(mw.bzCardsProtectedFromOppChoice(true));
		assertTrue(chooseFromBz(mw.buildGameContext(false), true).isEmpty(),
				"P2 reaching into P1's Break Zone finds nothing it may choose");
	}

	@Test
	void theOwnerStillChoosesFromTheirOwnBreakZone() {
		MainWindow mw = new MainWindow();
		mw.placeP2CardInFirstBackupSlot(
				makeFieldAbilityCard("Kalmia", "Water", "Backup", KALMIA_18_090R_BZ_SHIELD));
		mw.gameState.getP2BreakZone().add(makeForward("Fallen", "Water", 3, 7000));

		assertEquals(1, chooseFromBz(mw.buildGameContext(false), false).size(),
				"\"your opponent's\" — Kalmia's controller is not bound by it");
	}

	@Test
	void withoutKalmiaTheBreakZoneIsOpen() {
		MainWindow mw = new MainWindow();
		mw.gameState.getP1BreakZone().add(makeForward("Fallen", "Water", 3, 7000));

		assertFalse(mw.bzCardsProtectedFromOppChoice(true));
		assertEquals(1, chooseFromBz(mw.buildGameContext(false), true).size());
	}

	// =========================================================================================
	// "All the …" grants, a grant with a damage rider, and an absolute block-power threshold
	//
	//   Golbez 19-077L  "All the Job Archfiend Forwards you control gain +3000 power."
	//   Cecil 2-129L    the same prefix, plus a damage shield printed as a rider on the grant
	//   Ark Angel MR 8-045R  "cannot be blocked by a Forward of power 7000 or more"
	//
	// The first two are the only cards in the corpus that write "All the"; every grant pattern is
	// anchored on "^The ", so the prefix is normalised once rather than in a dozen places. Cecil's
	// rider is two effects in one sentence, split into the two the engine already reads — with
	// "they" resolved back to the grant's own filter — rather than teaching either parser about a
	// sentence carrying the other's effect.
	// =========================================================================================

	@Test
	void allTheIsTheSameGrantAsThe() {
		String all = "All the Job Archfiend Forwards you control gain +3000 power.";
		String the = "The Job Archfiend Forwards you control gain +3000 power.";
		assertEquals(CardData.parseFieldPowerGrants(the, "Forward").toString(),
				CardData.parseFieldPowerGrants(all, "Forward").toString());
		assertEquals(1, CardData.parseFieldPowerGrants(all, "Forward").size());
	}

	private static final String CECIL_2_129L_GRANT =
			"All the Forwards other than Cecil you control gain +1000 power, and if they are dealt "
			+ "damage by a Summon or an ability, the damage becomes 0 instead.";

	@Test
	void theGrantHalfOfCecilsRiderIsAnOrdinaryPowerGrant() {
		List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(CECIL_2_129L_GRANT, "Forward");
		assertEquals(1, grants.size());
		assertEquals(CardData.parseFieldPowerGrants(
						"The Forwards other than Cecil you control gain +1000 power.", "Forward").toString(),
				grants.toString(),
				"the rider must not change what the power half grants");
	}

	@Test
	void theRiderHalfBecomesTheCanonicalFieldDamageWording() {
		String[] split = CardData.splitGrantWithDamageRider(
				"The Forwards other than Cecil you control gain +1000 power, and if they are dealt "
				+ "damage by a Summon or an ability, the damage becomes 0 instead.");
		assertNotNull(split);

		Matcher m = AutoAbilityTriggers.FA_FIELD_DAMAGE_MODIFIER.matcher(split[1]);
		assertTrue(m.matches(), "the rewritten rider has to land in the parser that owns that shape");
		assertEquals("Cecil", m.group("except1").trim(),
				"\"they\" resolves back to the grant's own exclusion, so Cecil is not shielded by it");
		assertEquals("0", m.group("setsto"));
	}

	@Test
	void aGrantWithNoRiderIsLeftAlone() {
		assertNull(CardData.splitGrantWithDamageRider(
				"The Forwards you control gain +1000 power."));
		assertNull(CardData.splitGrantWithDamageRider(
				"If a Forward you control is dealt damage by a Summon, the damage becomes 0 instead."));
	}

	@Test
	void arkAngelReadsAnAbsolutePowerThreshold() {
		String text = "Ark Angel MR cannot be blocked by a Forward of power 7000 or more.";
		assertArrayEquals(new int[]{7000, 1},
				CardData.parseFieldCannotBeBlockedByPower(text, "Ark Angel MR"));
		assertNull(CardData.parseFieldCannotBeBlockedByPower(text, "Somebody Else"),
				"self-named, like the rest of the block-restriction family");
		// The relative twin must not claim it — that one moves with the attacker's own power.
		assertFalse(CardData.parseCannotBeBlockedByHigherPower(text, "Ark Angel MR"));
	}

	@Test
	void theAbsoluteThresholdExcludesTheRightBlockers() {
		int[] filter = CardData.parseFieldCannotBeBlockedByPower(
				"Ark Angel MR cannot be blocked by a Forward of power 7000 or more.", "Ark Angel MR");
		assertTrue(MainWindow.blockerPowerExcluded(9000, filter));
		assertTrue(MainWindow.blockerPowerExcluded(7000, filter), "\"or more\" includes the boundary");
		assertFalse(MainWindow.blockerPowerExcluded(6000, filter));
	}

	// =========================================================================================
	// Three field abilities whose machinery already existed and whose wording did not
	//
	//   Ephemeral Vision 2-123C  "If you control 4 Forwards or more, …"  — the count qualifier
	//       printed after the noun. The only card in the corpus that prints it that way round, so
	//       it is normalised into the leading form rather than loosening the count pattern.
	//   Gawain 7-107R            "…by a Forward's ability"  — a source clause between two that
	//       already existed and narrower than both: "by a Forward" is battle damage, "by an
	//       ability" is any ability. Left unwired it fell to "by a Forward" and inverted.
	//   Rosa 2-143R              "Whenever …"  — already wired as an auto-ability. It was ALSO
	//       being emitted as a field ability, because the exclusion that keeps auto-abilities out
	//       of field-ability parsing read "When " and not "Whenever ".
	// =========================================================================================

	@Test
	void aCountQualifierPrintedAfterTheNounReadsTheSame() {
		String trailing = "If you control 4 Forwards or more, Ephemeral Vision gains +3000 power.";
		String leading  = "If you control 4 or more Forwards, Ephemeral Vision gains +3000 power.";

		List<IfControlBoost> boosts = CardData.parseIfControlBoosts(trailing, "Forward");
		assertEquals(1, boosts.size());
		assertEquals(CardData.parseIfControlBoosts(leading, "Forward").toString(), boosts.toString(),
				"the two wordings are the same condition and must parse to the same boost");
	}

	@Test
	void theTrailingFormIsNotMistakenForAnUnqualifiedNoun() {
		// The normalisation is anchored, so it cannot reach into a longer condition and rewrite a
		// threshold that belongs to some other clause.
		assertEquals(List.of(), CardData.parseIfControlBoosts(
				"If you control 4 Forwards or more Backups, Nobody gains +3000 power.", "Forward"));
	}

	private static final String GAWAIN_7_107R =
			"If Gawain is dealt damage by a Forward's ability, the damage becomes 0 instead.";

	@Test
	void gawainReadsTheAbilitySourceRatherThanTheBattleSource() {
		Matcher m = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(GAWAIN_7_107R);
		assertTrue(m.matches(), "the source clause has to be one the modifier recognises");
		assertEquals("Gawain", m.group("card"));
		assertEquals("0", m.group("setsto"));
		assertEquals("by a Forward's ability", m.group("sourceclause").trim(),
				"the bare \"by a Forward\" branch must not claim it — it means battle damage");
	}

	@Test
	void theBareForwardSourceStillMeansBattleDamage() {
		Matcher m = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(
				"If Gawain is dealt damage by a Forward, the damage becomes 0 instead.");
		assertTrue(m.matches());
		assertEquals("by a Forward", m.group("sourceclause").trim(),
				"inserting the narrower branch ahead of it must not disturb the wording it had");
	}

	@Test
	void wheneverIsTheSameTriggerWordAsWhen() {
		String rosa = "Whenever a Forward you control is chosen by your opponent's Summon, "
				+ "you may draw 1 card.";

		assertEquals(1, CardData.parseAutoAbilities(rosa).size(), "it is a triggered ability");
		assertEquals("chosen by opponent's summon", CardData.parseAutoAbilities(rosa).get(0).trigger());
		assertEquals(List.of(), CardData.parseFieldAbilities(rosa, "Backup"),
				"and must not be emitted as a field ability on top of that");
	}

	@Test
	void theWheneverExclusionDoesNotSwallowOrdinaryFieldAbilities() {
		// The exclusion is a prefix test, so a field ability that merely contains the word is safe.
		String fa = "The Forwards you control gain +1000 power whenever you control a Backup.";
		assertEquals(1, CardData.parseFieldAbilities(fa, "Backup").size());
	}

	// =========================================================================================
	// Syldra 29-101H — two alternatives, each with its own cost ceiling
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
	// Combat glows — what a Forward slot says about the attack in progress
	//
	// Red marks a card that is part of a declared attack, on either side. The defender is choosing
	// a blocker against it, so it stays up for the whole combat: declaration, both priority
	// checkpoints, and damage. Gray marks one that has attacked as many times as it may this turn.
	//
	// Gray is deliberately narrower than "cannot attack". A Forward played this turn, or held down
	// by an effect, has spent nothing and stays unmarked — the mark is for a threat that is used
	// up, not one that was never there. Dulling says as much for most attackers, but not for the
	// ones worth marking: Brave leaves a card active, and so does a re-activation.
	// =========================================================================================

	/**
	 * A Forward on P2's field that has declared {@code attacks} attacks, with the board sitting in
	 * the Attack Phase — the only phase in which the exhausted mark means anything.
	 */
	private static CardData attackedTwice(MainWindow mw, CardData card, int attacks) {
		if (mw.gameState.getCurrentPhase() != GameState.GamePhase.ATTACK)
			advanceTo(mw, GameState.Player.P2, GameState.GamePhase.ATTACK);
		placeP2Forward(mw, card);
		for (int i = 0; i < attacks; i++) mw.recordAttackDeclared(card);
		return card;
	}

	@Test
	void aDeclaredAttackerGlowsRed() {
		MainWindow mw = new MainWindow();
		CardData attacker = attackedTwice(mw, makeForward("Sephiroth", "Dark", 5, 9000), 1);
		mw.p2DeclaredAttackers.add(attacker);

		assertEquals(CardAnimation.GLOW_ATTACKING, mw.combatGlowFor(attacker, false),
				"red for as long as P1 is choosing a blocker against it");
	}

	@Test
	void aSpentAttackerGlowsGrayOnceTheCombatIsOver() {
		MainWindow mw = new MainWindow();
		CardData attacker = attackedTwice(mw, makeForward("Sephiroth", "Dark", 5, 9000), 1);
		mw.p2DeclaredAttackers.add(attacker);
		assertEquals(CardAnimation.GLOW_ATTACKING, mw.combatGlowFor(attacker, false));

		mw.p2DeclaredAttackers.clear();   // combat resolved; the card is still on the board

		assertEquals(CardAnimation.GLOW_EXHAUSTED, mw.combatGlowFor(attacker, false),
				"one declaration allowed, one spent — it will not be attacking again");
	}

	@Test
	void attackingIsRankedAboveExhausted() {
		MainWindow mw = new MainWindow();
		// Mid-combat an attacker has almost always spent its last declaration already, so the two
		// conditions overlap constantly. What the player needs then is that it is swinging.
		CardData attacker = attackedTwice(mw, makeForward("Sephiroth", "Dark", 5, 9000), 1);
		mw.p2DeclaredAttackers.add(attacker);

		assertEquals(CardAnimation.GLOW_ATTACKING, mw.combatGlowFor(attacker, false));
	}

	@Test
	void aForwardWithAnAttackLeftIsNotExhausted() {
		MainWindow mw = new MainWindow();
		CardData tifa = attackedTwice(mw, makeForward("Tifa", "Fire", 4, 8000), 1);
		mw.grantExtraAttack(tifa);

		assertNull(mw.combatGlowFor(tifa, false), "\"can attack once more this turn\" — not spent yet");

		mw.recordAttackDeclared(tifa);
		assertEquals(CardAnimation.GLOW_EXHAUSTED, mw.combatGlowFor(tifa, false),
				"now both declarations are gone");
	}

	@Test
	void theExhaustedMarkComesOffWhenTheAttackPhaseEnds() {
		MainWindow mw = new MainWindow();
		CardData attacker = attackedTwice(mw, makeForward("Sephiroth", "Dark", 5, 9000), 1);
		assertEquals(CardAnimation.GLOW_EXHAUSTED, mw.combatGlowFor(attacker, false));

		mw.gameState.advancePhase();   // ATTACK → MAIN_2
		assertEquals(GameState.GamePhase.MAIN_2, mw.gameState.getCurrentPhase());

		assertNull(mw.combatGlowFor(attacker, false),
				"attacksMadeThisTurn still holds it, but nobody was attacking again anyway");
	}

	@Test
	void aForwardThatNeverAttackedIsNotMarked() {
		MainWindow mw = new MainWindow();
		CardData fresh = attackedTwice(mw, makeForward("Rookie", "Wind", 2, 5000), 0);

		assertNull(mw.combatGlowFor(fresh, false),
				"summoning sickness is not exhaustion — it has spent nothing");
	}

	@Test
	void theGlowFollowsTheCardAndNotItsName() {
		MainWindow mw = new MainWindow();
		CardData attacker = attackedTwice(mw, makeForward("Dark Knight", "Ice", 3, 7000), 1);
		CardData twin     = attackedTwice(mw, makeForward("Dark Knight", "Ice", 3, 7000), 0);
		mw.p2DeclaredAttackers.add(attacker);

		assertNull(mw.combatGlowFor(twin, false),
				"CardData is a record, so a value compare would light up the copy standing next to it");
	}

	// =========================================================================================
	// A card's own name in its own text refers to that card, not to every printing sharing it
	//
	// The break-zone dispatch is the one trigger family that scans the whole board, because its
	// subjects may name somebody else ("a Forward you control", "Geomancer"). That scan ended in
	// a bare name compare, so a self-named subject answered for any card with the same name:
	// blocking with Dark Knight 1-054C and losing it fired the opposing Dark Knight 1-055C's
	// "Dark Knight deals you 1 point of damage" — a card reading a stranger's death as its own,
	// and damaging the wrong player for it.
	//
	// Every other trigger family is already safe by construction: they consult only the abilities
	// of the card that entered, attacked or departed, so the name compare there is a self-check.
	// =========================================================================================

	private static final String DARK_KNIGHT_1_055C =
			"When Dark Knight is put from the field into the Break Zone, "
			+ "Dark Knight deals you 1 point of damage.";

	/**
	 * The auto-abilities the break put on the Stack, by the card each belongs to. A trigger that
	 * fires is pushed rather than resolved, so this is what "it fired" looks like from outside.
	 */
	private static List<CardData> triggeredSources(MainWindow mw) {
		return mw.gameState.getStack().stream()
				.filter(StackEntry::isAutoAbility).map(StackEntry::source).toList();
	}

	/** Puts {@code card} on {@code isP1}'s Forward row and takes it straight off, as a break does. */
	private static CardData broken(MainWindow mw, boolean isP1, CardData card) {
		if (isP1) { placeP1Forward(mw, card); mw.p1ForwardCards.remove(card); }
		else      { placeP2Forward(mw, card); mw.p2ForwardCards.remove(card); }
		return card;
	}

	@Test
	void anotherPrintingSharingTheNameDoesNotAnswerForIt() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeAutoAbilityForward("Dark Knight", "Ice", 5000, DARK_KNIGHT_1_055C));
		// P1 blocks with Dark Knight 1-054C — same name, no break trigger of its own — and loses it.
		CardData blocker = broken(mw, true, makeForward("Dark Knight", "Ice", 4, 8000));

		mw.autoAbilityTriggers.triggerAutoAbilitiesForBreakZone(blocker, true, Set.of());

		assertEquals(List.of(), triggeredSources(mw),
				"P2's Dark Knight means itself — P1's blocker dying is not its own departure");
	}

	@Test
	void theCardStillFiresOnItsOwnDeparture() {
		MainWindow mw = new MainWindow();
		CardData dk = broken(mw, false,
				makeAutoAbilityForward("Dark Knight", "Ice", 5000, DARK_KNIGHT_1_055C));

		mw.autoAbilityTriggers.triggerAutoAbilitiesForBreakZone(dk, false, Set.of());

		assertEquals(List.of(dk), triggeredSources(mw),
				"it is the card that broke, so its own trigger answers");
	}

	@Test
	void aSecondCopyOnTheSameSideIsStillADifferentCard() {
		MainWindow mw = new MainWindow();
		CardData survivor = makeAutoAbilityForward("Dark Knight", "Ice", 5000, DARK_KNIGHT_1_055C);
		placeP2Forward(mw, survivor);
		CardData twin = broken(mw, false,
				makeAutoAbilityForward("Dark Knight", "Ice", 5000, DARK_KNIGHT_1_055C));

		mw.autoAbilityTriggers.triggerAutoAbilitiesForBreakZone(twin, false, Set.of());

		assertEquals(List.of(twin), triggeredSources(mw),
				"one point, from the copy that broke — the survivor does not add a second");
	}

	@Test
	void aSubjectNamingSomebodyElseStillMatchesByName() {
		MainWindow mw = new MainWindow();
		CardData watcher = makeAutoAbilityForward("Watcher", "Ice", 5000,
				"When Geomancer is put from the field into the Break Zone, "
				+ "Watcher deals you 1 point of damage.");
		placeP2Forward(mw, watcher);
		CardData geomancer = broken(mw, true, makeForward("Geomancer", "Ice", 2, 5000));

		mw.autoAbilityTriggers.triggerAutoAbilitiesForBreakZone(geomancer, true, Set.of());

		assertEquals(List.of(watcher), triggeredSources(mw),
				"the name compare is only narrowed where the text names its own card");
	}

	@Test
	void aFormingAPartySubjectFiresForItsOwnCardOnly() {
		// Chocobo 25-045C. Self-named through a qualifier, so it was reachable from neither side:
		// the board scan could not see the card that had just left it, and the self-dispatch only
		// accepted a bare name. It now answers for its own death, and only in a party.
		String text = "When Chocobo forming a party is put from the field into the Break Zone, "
				+ "Chocobo deals you 1 point of damage.";
		MainWindow mw = new MainWindow();
		CardData chocobo = broken(mw, false, makeAutoAbilityForward("Chocobo", "Wind", 3000, text));

		mw.autoAbilityTriggers.triggerAutoAbilitiesForBreakZone(chocobo, false, Set.of(chocobo));
		assertEquals(List.of(chocobo), triggeredSources(mw), "it was in a party when it broke");

		MainWindow alone = new MainWindow();
		CardData solo = broken(alone, false, makeAutoAbilityForward("Chocobo", "Wind", 3000, text));
		alone.autoAbilityTriggers.triggerAutoAbilitiesForBreakZone(solo, false, Set.of());
		assertEquals(List.of(), triggeredSources(alone), "the qualifier still has to hold");
	}

	@Test
	void aKalmiaThatHasLostItsAbilitiesShieldsNothing() {
		MainWindow mw = new MainWindow();
		CardData kalmia = makeFieldAbilityCard("Kalmia", "Water", "Backup", KALMIA_18_090R_BZ_SHIELD);
		mw.placeCardInFirstBackupSlot(kalmia);
		mw.lostAbilitiesCards.add(kalmia);

		assertFalse(mw.bzCardsProtectedFromOppChoice(true));
	}

	// =========================================================================================
	// Baigan 9-072H: "If Baigan is dealt 3000 damage or less, the damage becomes 0 instead." —
	// the "or less" direction of the damage-modifier threshold, whose only printing before this
	// was "or more".
	// =========================================================================================

	private static final String BAIGAN_9_072H =
			"If Baigan is dealt 3000 damage or less, the damage becomes 0 instead.";

	@Test
	void baiganZeroesDamageAtOrBelowItsThreshold() {
		MainWindow mw = new MainWindow();
		mw.placeCardInForwardZone(makeFieldAbilityCard("Baigan", "Earth", "Forward", BAIGAN_9_072H));

		assertEquals(0, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 2000, false, false),
				"below the threshold");
		assertEquals(0, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 3000, false, false),
				"'or less' includes the threshold itself");
		assertEquals(4000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 4000, false, false),
				"above it the damage lands in full");
	}

	@Test
	void theOrMoreDirectionStillGatesTheOtherWay() {
		// The branch this shares its threshold group with, asserted alongside so a change to one
		// cannot quietly invert the other.
		MainWindow mw = new MainWindow();
		mw.placeCardInForwardZone(makeFieldAbilityCard("Ward", "Earth", "Forward",
				"If Ward is dealt 5000 damage or more, reduce the damage by 2000 instead."));

		assertEquals(4000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 4000, false, false),
				"below the threshold nothing applies");
		assertEquals(3000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 5000, false, false),
				"'or more' includes the threshold itself");
	}

	// =========================================================================================
	// Vermilion Bird l'Cie Caetuna 6-010H: "If a Forward is dealt damage by your Fire Summon,
	// the damage increases by 1000 instead." — read on the CASTER's side, so it boosts the
	// Summon its controller cast rather than protecting the Forward being hit.
	// =========================================================================================

	private static final String CAETUNA_6_010H =
			"If a Forward is dealt damage by your Fire Summon, the damage increases by 1000 instead.";

	@Test
	void caetunaBoostsItsControllersFireSummonDamage() {
		MainWindow mw = new MainWindow();
		mw.placeCardInFirstBackupSlot(makeFieldAbilityCard(
				"Vermilion Bird l'Cie Caetuna", "Fire", "Backup", CAETUNA_6_010H));
		mw.currentResolutionIsSummon  = true;
		mw.currentSummonSource        = makeFieldAbilityCard("Ifrit", "Fire", "Summon", "");
		mw.currentSummonSourceIsP1    = true;

		assertEquals(8000, mw.damageResolver.applyCasterSideElementSummonDamageBoosts(7000, false),
				"P1's Fire Summon hitting a P2 Forward gets the boost");
		assertEquals(7000, mw.damageResolver.applyCasterSideElementSummonDamageBoosts(7000, true),
				"it does not boost damage aimed back at its own side");

		mw.currentSummonSource = makeFieldAbilityCard("Shiva", "Ice", "Summon", "");
		assertEquals(7000, mw.damageResolver.applyCasterSideElementSummonDamageBoosts(7000, false),
				"the Element has to match");
	}

	// =========================================================================================
	// Number 24 20-036H / The Emperor 17-130L: "If a [X] Counter is placed on [Self], [Self]
	// gains \"If [Self] is dealt damage, remove 1 [X] Counter from [Self] and the damage becomes
	// 0 instead.\"" — a self-only counter grant whose granted ability spends the counter that
	// conditions it, so each counter buys exactly one shield.
	// =========================================================================================

	private static final String NUMBER_24_20_036H =
			"When Number 24 enters the field or at the beginning of your Main Phase 1 during each "
			+ "of your turns, place 1 Barrier Counter on Number 24.[[br]]   "
			+ "If a Barrier Counter is placed on Number 24, Number 24 gains \"If Number 24 is dealt "
			+ "damage, remove 1 Barrier Counter from Number 24 and the damage becomes 0 instead.\"";

	@Test
	void number24ParsesAsASelfOnlyCounterAbilityGrant() {
		CardData n24 = makeFieldAbilityCard("Number 24", "Ice", "Forward", NUMBER_24_20_036H);
		List<CounterGrant> grants = n24.counterGrants();
		assertEquals(1, grants.size());
		CounterGrant cg = grants.get(0);
		assertEquals("Barrier", cg.counterName());
		assertEquals(0, cg.powerBonus());
		assertTrue(cg.selfOnly(), "the grant names its own carrier, not every Forward beside it");
		assertEquals("If Number 24 is dealt damage, remove 1 Barrier Counter from Number 24 and "
				+ "the damage becomes 0 instead.", cg.grantedAbilityText());
		assertTrue(AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(cg.grantedAbilityText()).find(),
				"the counter-removal clause must not push the text out of the damage-modifier parser");
	}

	@Test
	void aGrantNamingAnotherCardIsNotReadAsASelfGrant() {
		CardData impostor = makeFieldAbilityCard("Number 24", "Ice", "Forward",
				"If a Barrier Counter is placed on Number 25, Number 25 gains \"If Number 25 is "
				+ "dealt damage, the damage becomes 0 instead.\"");
		assertTrue(impostor.counterGrants().isEmpty(),
				"both name captures are checked against the carrier");
	}

	@Test
	void eachBarrierCounterBuysExactlyOneShield() {
		MainWindow mw = new MainWindow();
		CardData n24 = makeFieldAbilityCard("Number 24", "Ice", "Forward", NUMBER_24_20_036H);
		mw.placeCardInForwardZone(n24);

		assertEquals(9000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 9000, false, false),
				"no counter — no grant, so no shield");

		mw.gameState.placeCounters(n24, "Barrier", 2);
		assertEquals(0, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 9000, false, false));
		assertEquals(1, mw.gameState.getCounters(n24, "Barrier"), "the shield spent one counter");
		assertEquals(0, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 9000, false, false));
		assertEquals(0, mw.gameState.getCounters(n24, "Barrier"), "and the second one too");
		assertEquals(9000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 9000, false, false),
				"with the counters gone the grant is gone with them");
	}

	@Test
	void aSelfOnlyCounterGrantDoesNotReachTheForwardBesideIt() {
		MainWindow mw = new MainWindow();
		mw.placeCardInForwardZone(makeFieldAbilityCard("Number 24", "Ice", "Forward", NUMBER_24_20_036H));
		CardData neighbour = makeForward("Shiva", "Ice", 3, 7000);
		mw.placeCardInForwardZone(neighbour);          // P1 idx 1
		mw.gameState.placeCounters(neighbour, "Barrier", 1);

		assertEquals(9000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 1, 9000, false, false),
				"same counter, different card — the grant stays home");
		assertEquals(1, mw.gameState.getCounters(neighbour, "Barrier"), "and spends nothing");
	}

	// =========================================================================================
	// Colkhab 18-041C, Owe 17-092R, Illua 5-099H, The Fiend 20-114L: "During each turn, when
	// [self] is chosen by your opponent's Summon or ability for the first time in that turn,
	// [effect]" — the reactive chosen-by trigger with its per-turn limit stated up front. The
	// wording opens with "During", so it used to fall past both the auto-ability pattern and the
	// field-ability exclusion and land in the field-ability list unparsed.
	// =========================================================================================

	private static final String COLKHAB_18_041C =
			"During each turn, when Colkhab is chosen by your opponent's Summon or ability for the "
			+ "first time in that turn, each player puts the top card of their deck into the Break "
			+ "Zone. If both cards are of the same card type, cancel its effect.";

	@Test
	void colkhabBecomesAOncePerTurnChosenByTrigger() {
		List<AutoAbility> autos = CardData.parseAutoAbilities(COLKHAB_18_041C);
		assertEquals(1, autos.size());
		AutoAbility aa = autos.get(0);
		assertEquals("chosen by opponent's summon or ability", aa.trigger());
		assertEquals("Colkhab", aa.triggerCard());
		assertTrue(aa.oncePerTurn(), "'for the first time in that turn' is the per-turn limit");
		assertEquals(0, aa.damageThreshold());
		assertTrue(CardData.parseFieldAbilities(COLKHAB_18_041C, "Forward").isEmpty(),
				"and it is no longer also emitted as a field ability");
	}

	@Test
	void theFiendKeepsItsDamageGateAndItsOwnName() {
		// Two of these on one card: the subject capture used to run past the [[br]] to the second
		// "is chosen by", swallowing the "Damage 3 --" gate into the card name.
		String text = "During each turn, when your opponent casts a Summon for the first time in "
				+ "that turn, cancel its effect.[[br]]   Damage 3 -- During each turn, when The Fiend "
				+ "is chosen by your opponent's ability for the first time in that turn, cancel its effect.";
		List<AutoAbility> autos = CardData.parseAutoAbilities(text);
		assertEquals(1, autos.size(), "only the chosen-by half is claimed");
		assertEquals("The Fiend", autos.get(0).triggerCard());
		assertEquals(3, autos.get(0).damageThreshold(), "the Damage 3 gate survives");
		assertEquals("chosen by opponent's summon or ability", autos.get(0).trigger(),
				"an ability-only printing rides the broad trigger — there is no ability-only dispatch");
	}

	@Test
	void colkhabsEffectDelegatesToTheTwoSidedMillCancel() {
		Consumer<GameContext> fn = ActionResolver.parse(
				"Each player puts the top card of their deck into the Break Zone. If both cards "
				+ "are of the same card type, cancel its effect.", null);
		assertNotNull(fn);
		GameContext ctx = mock(GameContext.class);
		fn.accept(ctx);
		verify(ctx).millTopDeckBothCancelChosenIfSameType();
	}

	@Test
	void twoMilledCardsOfTheSameTypeCancelTheSelection() {
		MainWindow mw = colkhabBoardWithDeckTops(
				makeForward("Mine", "Wind", 2, 5000), makeForward("Theirs", "Fire", 2, 5000));

		mw.buildGameContext(true).millTopDeckBothCancelChosenIfSameType();

		assertTrue(mw.lastChosenSelectionCancelled, "both Forwards — the effect that chose is cancelled");
		assertEquals(1, mw.gameState.getP1BreakZone().size(), "and both cards were milled");
		assertEquals(1, mw.gameState.getP2BreakZone().size());
	}

	@Test
	void twoMilledCardsOfDifferentTypesLetTheSelectionStand() {
		MainWindow mw = colkhabBoardWithDeckTops(
				makeForward("Mine", "Wind", 2, 5000), makePlainBackup("Theirs", "Fire", 2));

		mw.buildGameContext(true).millTopDeckBothCancelChosenIfSameType();

		assertFalse(mw.lastChosenSelectionCancelled, "a Forward and a Backup are no pair");
	}

	@Test
	void anEmptyDeckLeavesNoPairToCompare() {
		MainWindow mw = new MainWindow();
		mw.gameState.getIdentity().put(makeForward("Unused", "Wind", 2, 5000), true);

		mw.buildGameContext(true).millTopDeckBothCancelChosenIfSameType();

		assertFalse(mw.lastChosenSelectionCancelled, "nothing milled, nothing cancelled");
	}

	/** A board whose two decks are topped by {@code p1Top} and {@code p2Top}, owners registered. */
	private static MainWindow colkhabBoardWithDeckTops(CardData p1Top, CardData p2Top) {
		MainWindow mw = new MainWindow();
		mw.gameState.getIdentity().put(p1Top, true);
		mw.gameState.getIdentity().put(p2Top, false);
		mw.gameState.getP1MainDeck().add(p1Top);
		mw.gameState.getP2MainDeck().add(p2Top);
		return mw;
	}

	// =========================================================================================
	// Ardyn 8-068L: "At the beginning of your opponent's Attack Phase, your opponent selects 1
	// Character he/she controls. He/she may put it into the Break Zone. If he/she does so, Ardyn
	// cannot block this turn." — a trigger on the phase the card's controller is NOT taking, and
	// an option that belongs to the opponent rather than to the card.
	// =========================================================================================

	private static final String ARDYN_8_068L_EFFECT =
			"Your opponent selects 1 Character he/she controls. He/she may put it into the Break "
			+ "Zone. If he/she does so, Ardyn cannot block this turn.";

	@Test
	void ardynsAbilityIsATriggerOnTheOpponentsAttackPhase() {
		String text = "Brave[[br]] Ardyn cannot be broken.[[br]] At the beginning of your opponent's "
				+ "Attack Phase, " + ARDYN_8_068L_EFFECT;
		List<AutoAbility> autos = CardData.parseAutoAbilities(text);
		assertEquals(1, autos.size());
		assertEquals("beginning of opponent's attack phase", autos.get(0).trigger());
		assertEquals(ARDYN_8_068L_EFFECT, autos.get(0).effectText());
		assertEquals(List.of("Ardyn cannot be broken."),
				CardData.parseFieldAbilities(text, "Forward").stream().map(FieldAbility::effectText).toList(),
				"only the standing restriction is left behind as a field ability");
	}

	@Test
	void theTriggerFiresForThePlayerWhoseAttackPhaseItIsNot() {
		MainWindow own = ardynFacing(2);
		own.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfOppAttackPhase(true);
		assertEquals(2, own.p2ForwardCards.size(), "not on its own controller's Attack Phase");

		MainWindow theirs = ardynFacing(2);
		theirs.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfOppAttackPhase(false);
		assertEquals(1, theirs.p2ForwardCards.size(), "but on the opponent's, who paid a Character");
		assertTrue(theirs.p1ForwardCannotBlock.contains(0));
	}

	/** Ardyn 8-068L on P1's field at index 0, facing {@code oppForwards} P2 Forwards. */
	private static MainWindow ardynFacing(int oppForwards) {
		MainWindow mw = new MainWindow();
		mw.placeCardInForwardZone(makeAutoAbilityForward("Ardyn", "Earth", 9000,
				"At the beginning of your opponent's Attack Phase, " + ARDYN_8_068L_EFFECT));
		for (int i = 0; i < oppForwards; i++)
			placeP2Forward(mw, makeForward("Opp" + i, "Fire", i + 1, 3000));
		return mw;
	}

	@Test
	void ardynsEffectIsNotClaimedByThePlainOpponentSelectsParser() {
		CardData ardyn = makeForward("Ardyn", "Earth", 5, 9000);
		assertEquals("OppSelectsMayBreakElseSelfCannotBlock",
				ActionResolver.matchedPatternName(ARDYN_8_068L_EFFECT, ardyn),
				"OpponentSelects would read it as a forced break and drop the block restriction");
		// Self-named: the restriction lands on the printing card, so another name is declined.
		assertNull(ActionResolverChoose.tryParseOppSelectsMayBreakElseSelfCannotBlock(
				ARDYN_8_068L_EFFECT, makeForward("Somebody Else", "Earth", 5, 9000)));
	}

	@Test
	void takingTheOfferBreaksACharacterAndStopsArdynBlocking() {
		MainWindow mw = new MainWindow();
		CardData ardyn = makeForward("Ardyn", "Earth", 5, 9000);
		mw.placeCardInForwardZone(ardyn);                                  // P1 idx 0
		placeP2Forward(mw, makeForward("Spare", "Fire", 1, 3000));         // the cheap one it gives up
		placeP2Forward(mw, makeForward("Keeper", "Fire", 7, 9000));

		ActionResolver.parse(ARDYN_8_068L_EFFECT, ardyn).accept(mw.buildGameContext(true));

		assertEquals(List.of("Keeper"), mw.p2ForwardCards.stream().map(CardData::name).toList(),
				"the CPU spends its cheapest Character");
		assertTrue(mw.p1ForwardCannotBlock.contains(0), "and buys Ardyn's block off for the turn");
	}

	@Test
	void anOpponentWithOneCharacterLeftDeclinesAndArdynStillBlocks() {
		MainWindow mw = new MainWindow();
		CardData ardyn = makeForward("Ardyn", "Earth", 5, 9000);
		mw.placeCardInForwardZone(ardyn);                                  // P1 idx 0
		placeP2Forward(mw, makeForward("Lonely", "Fire", 1, 3000));

		ActionResolver.parse(ARDYN_8_068L_EFFECT, ardyn).accept(mw.buildGameContext(true));

		assertEquals(1, mw.p2ForwardCards.size(), "it will not empty its board for this");
		assertFalse(mw.p1ForwardCannotBlock.contains(0), "so Ardyn keeps its block");
	}

	@Test
	void anOpponentWithNoCharactersIsNotEvenAsked() {
		MainWindow mw = new MainWindow();
		CardData ardyn = makeForward("Ardyn", "Earth", 5, 9000);
		mw.placeCardInForwardZone(ardyn);

		ActionResolver.parse(ARDYN_8_068L_EFFECT, ardyn).accept(mw.buildGameContext(true));

		assertTrue(mw.p1ForwardCannotBlock.isEmpty(), "no offer to take, no restriction");
	}

	// =========================================================================================
	// Damage-gated self grants: "Damage N -- [Self] gains [+P power] [traits] [and \"[quoted]\"]."
	// (Elle 13-088H, Ritz 11-063L, Charlotte 13-023R, The Fiend 20-114L)
	//
	// The power half of these had no reader at all: ActionResolverPower.tryParseFieldSelfPowerBoost
	// turns the same sentence into an effect, which is what an *ability* resolving it would run —
	// but a field ability is never resolved, so the coverage report's "OK" was hollow and the
	// Forward stood at its printed power. Read per query now, because the gate opens and shuts as
	// the damage zone fills.
	// =========================================================================================

	private static final String ELLE_13_088H     = "Damage 3 -- Elle gains +2000 power.";
	private static final String RITZ_11_063L     = "Damage 3 -- Ritz gains \"Ritz cannot be blocked.\"";
	private static final String CHARLOTTE_13_023R =
			"Damage 3 -- Charlotte gains +2000 power and "
			+ "\"The damage dealt to Charlotte is reduced by 2000 instead.\"";

	/** Builds a Forward whose fieldAbilities <em>and</em> fieldPowerGrants are parsed from {@code text}. */
	private static CardData makeForwardWithPowerGrant(String name, String element, int power, String text) {
		return new CardData(null, name, element, 3, power, "Forward", false, 0, false, false,
				CardData.parseTraits(text, name), 0, List.of(), null, List.of(),
				List.of(), List.of(), CardData.parseFieldAbilities(text, "Forward"),
				List.of(), CardData.parseFieldPowerGrants(text, "Forward"),
				List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	/** Puts {@code n} cards into P1's damage zone, so a "Damage N --" gate can open. */
	private static void giveP1Damage(MainWindow mw, int n) {
		for (int i = 0; i < n; i++)
			mw.gameState.getP1DamageZone().add(makeForward("Dmg" + i, "Fire", 1, 1000));
	}

	@Test
	void aDamageGatedSelfPowerGrantOnlyAppliesOnceTheGateOpens() {
		MainWindow mw = new MainWindow();
		mw.placeCardInForwardZone(makeFieldAbilityCard("Elle", "Water", "Forward", ELLE_13_088H));

		assertEquals(7000, mw.effectiveP1ForwardPower(0), "below the threshold, the printed power stands");
		giveP1Damage(mw, 2);
		assertEquals(7000, mw.effectiveP1ForwardPower(0), "still short of Damage 3");
		giveP1Damage(mw, 1);
		assertEquals(9000, mw.effectiveP1ForwardPower(0), "at Damage 3 the grant is live");
	}

	@Test
	void aSelfPowerGrantIsReadOnlyForItsOwnCard() {
		assertEquals(2000, CardData.parseSelfPowerGrant("Elle gains +2000 power.", "Elle"));
		assertEquals(0, CardData.parseSelfPowerGrant("Elle gains +2000 power.", "Somebody Else"),
				"a grant naming another card is not this card's");
		assertEquals(0, CardData.parseSelfPowerGrant(
				"The Forwards you control gain +2000 power.", "Elle"),
				"and a field-wide grant belongs to parseFieldPowerGrants, not here");
	}

	@Test
	void ritzCannotBeBlockedOnlyAtDamageThree() {
		MainWindow mw = new MainWindow();
		CardData ritz = makeFieldAbilityCard("Ritz", "Wind", "Forward", RITZ_11_063L);
		mw.placeCardInForwardZone(ritz);

		assertFalse(mw.hasSelfCannotBeBlockedFieldAbility(ritz, true), "the gate is shut at 0 damage");
		giveP1Damage(mw, 3);
		assertTrue(mw.hasSelfCannotBeBlockedFieldAbility(ritz, true), "and open at 3");

		mw.lostAbilitiesCards.add(ritz);
		assertFalse(mw.hasSelfCannotBeBlockedFieldAbility(ritz, true),
				"a Ritz that has lost its abilities is blockable again");
	}

	@Test
	void theQuotedCannotBeBlockedMustNameItsOwnCarrier() {
		CardData ritz = makeFieldAbilityCard("Ritz", "Wind", "Forward", RITZ_11_063L);
		CardData.SelfGainsQuotedGrant g = CardData.parseSelfGainsQuotedGrant(
				ritz.fieldAbilities().get(0).effectText(), "Ritz");
		assertNotNull(g);
		assertEquals(List.of("Ritz cannot be blocked."), g.passiveTexts());
		assertTrue(g.abilityTexts().isEmpty(), "a standing restriction is not a triggered ability");
		assertNull(CardData.parseSelfGainsQuotedGrant(
				"Ritz gains \"Shara cannot be blocked.\"", "Ritz"),
				"a clause about another card leaves the grant unreadable, as before");
	}

	@Test
	void charlotteGainsPowerAndAShieldAtDamageThree() {
		MainWindow mw = new MainWindow();
		CardData charlotte = makeFieldAbilityCard("Charlotte", "Ice", "Forward", CHARLOTTE_13_023R);
		mw.placeCardInForwardZone(charlotte);

		assertEquals(7000, mw.effectiveP1ForwardPower(0));
		assertEquals(5000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false),
				"no reduction below the threshold");

		giveP1Damage(mw, 3);
		assertEquals(9000, mw.effectiveP1ForwardPower(0), "the power half of the same sentence");
		assertEquals(3000, mw.modifyIncomingDamage(true, ForwardTarget.CardZone.FORWARD, 0, 5000, true, false),
				"and the quoted half reduces the damage by 2000");
	}

	@Test
	void charlottesPassiveIsRewrittenIntoTheCanonicalDamageWording() {
		CardData.SelfGainsQuotedGrant g = CardData.parseSelfGainsQuotedGrant(
				"Charlotte gains +2000 power and "
				+ "\"The damage dealt to Charlotte is reduced by 2000 instead.\"", "Charlotte");
		assertNotNull(g);
		assertEquals(List.of("If Charlotte is dealt damage, reduce the damage by 2000 instead."),
				g.passiveTexts(),
				"the object-position spelling is rewritten so FA_DAMAGE_MODIFIER reads it unchanged");
		assertTrue(g.traits().isEmpty(), "the power is not a trait");
	}

	@Test
	void theFiendReadsAPossessiveNameAsItsOwnPower() {
		// "less than The Fiend's power" — the same clause every other printing spells "its power".
		String text = "Damage 5 -- The Fiend gains +1000 power, Brave and "
				+ "\"If The Fiend is dealt damage less than The Fiend's power, the damage becomes 0 instead.\"";
		CardData.SelfGainsQuotedGrant g = CardData.parseSelfGainsQuotedGrant(
				makeFieldAbilityCard("The Fiend", "Dark", "Forward", text)
						.fieldAbilities().get(0).effectText(), "The Fiend");
		assertNotNull(g, "the whole grant used to decline over the power clause");
		assertEquals(Set.of(CardData.Trait.BRAVE), g.traits());
		assertEquals(1, g.passiveTexts().size());
		assertTrue(AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(g.passiveTexts().get(0)).find(),
				"already canonical, so it is carried through unrewritten");
	}

	@Test
	void aQuotedPassiveWithNoReaderStillDeclinesTheWholeGrant() {
		// Kefka 23-004R. The outgoing-damage doubler is read off printed field abilities only, so
		// accepting this clause would hand Kefka +2000 power and Haste while the ability it was
		// bought with did nothing.
		assertNull(CardData.parseSelfGainsQuotedGrant(
				"Kefka gains +2000 power, Haste and "
				+ "\"If Kefka deals damage to a Forward or your opponent, double the damage instead.\"",
				"Kefka"));
	}

	// =========================================================================================
	// Tchakka 18-092C: "The Forwards of an Element other than Water lose 1000 power." — a debuff
	// naming no controller, so it reaches both boards, and excluding an Element rather than
	// selecting one.
	// =========================================================================================

	private static final String TCHAKKA_18_092C =
			"The Forwards of an Element other than Water lose 1000 power.";

	@Test
	void tchakkaShrinksEveryNonWaterForwardOnBothBoards() {
		MainWindow mw = new MainWindow();
		mw.placeCardInForwardZone(makeForwardWithPowerGrant("Tchakka", "Water", 7000, TCHAKKA_18_092C));
		mw.placeCardInForwardZone(makeForward("Ally", "Fire", 3, 7000));      // P1 idx 1
		placeP2Forward(mw, makeForward("Enemy", "Fire", 3, 7000));            // P2 idx 0
		placeP2Forward(mw, makeForward("Wet Enemy", "Water", 3, 7000));       // P2 idx 1

		assertEquals(7000, mw.effectiveP1ForwardPower(0), "Tchakka is Water — it spares itself");
		assertEquals(6000, mw.effectiveP1ForwardPower(1), "its own side is not exempt");
		assertEquals(6000, mw.effectiveP2ForwardPower(0), "and neither is the opponent's");
		assertEquals(7000, mw.effectiveP2ForwardPower(1), "Water is spared wherever it stands");
	}

	@Test
	void aMultiElementForwardCarryingTheNamedElementIsSpared() {
		MainWindow mw = new MainWindow();
		mw.placeCardInForwardZone(makeForwardWithPowerGrant("Tchakka", "Water", 7000, TCHAKKA_18_092C));
		mw.placeCardInForwardZone(makeForward("Hybrid", "Water/Fire", 3, 7000));  // P1 idx 1

		assertEquals(7000, mw.effectiveP1ForwardPower(1),
				"\"other than Water\" is not \"not only Water\" — having the Element is enough");
	}

	@Test
	void theDebuffParsesAsOneGrantPerSide() {
		List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(TCHAKKA_18_092C, "Forward");
		assertEquals(2, grants.size(), "one side each, since a grant scopes to one at a time");
		assertEquals(1, grants.stream().filter(FieldPowerGrant::affectsOpponent).count());
		for (FieldPowerGrant g : grants) {
			assertEquals(-1000, g.powerBonus());
			assertEquals("Water", g.excludeElement());
		}
	}

	// =========================================================================================
	// Chocobo 2-060C: "The Forwards forming a party with Chocobo gain First Strike." — a grant
	// conditioned on board state that exists only while a party is declared.
	// =========================================================================================

	private static final String CHOCOBO_2_060C =
			"First Strike[[br]]The Forwards forming a party with Chocobo gain First Strike.";

	@Test
	void chocobosPartyGrantIsLiveOnlyWhileThePartyIs() {
		MainWindow mw = new MainWindow();
		CardData chocobo = makeForwardWithPowerGrant("Chocobo", "Wind", 7000, CHOCOBO_2_060C);
		CardData friend  = makeForward("Moogle", "Wind", 3, 7000);
		mw.placeCardInForwardZone(chocobo);  // idx 0
		mw.placeCardInForwardZone(friend);   // idx 1

		assertFalse(mw.effectiveP1HasTrait(1, CardData.Trait.FIRST_STRIKE),
				"no party declared — nobody is forming one with Chocobo");

		mw.p1DeclaredAttackers.addAll(List.of(chocobo, friend));
		assertTrue(mw.effectiveP1HasTrait(1, CardData.Trait.FIRST_STRIKE),
				"attacking together is what forms the party");
	}

	@Test
	void aLoneChocoboFormsAPartyWithNobody() {
		MainWindow mw = new MainWindow();
		CardData chocobo = makeForwardWithPowerGrant("Chocobo", "Wind", 7000, CHOCOBO_2_060C);
		CardData bystander = makeForward("Moogle", "Wind", 3, 7000);
		mw.placeCardInForwardZone(chocobo);
		mw.placeCardInForwardZone(bystander);

		mw.p1DeclaredAttackers.add(chocobo);   // attacking alone
		assertFalse(mw.effectiveP1HasTrait(1, CardData.Trait.FIRST_STRIKE),
				"a Forward sitting at home is not in the attacker's party");
	}

	@Test
	void thePartyGrantCarriesTheNameItWasPrintedWith() {
		List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(CHOCOBO_2_060C, "Forward");
		assertEquals(1, grants.size());
		assertEquals("Chocobo", grants.get(0).partyWithCardName(),
				"carried rather than reduced to a self-reference, so a future printing naming "
				+ "somebody else cannot silently become one");
		assertEquals(Set.of(CardData.Trait.FIRST_STRIKE), grants.get(0).grantedTraits());
	}

	// =========================================================================================
	// Tifa 11-071L: "If you control a Card Name Cloud, the cost for playing Tifa onto the field is
	// reduced by 1 and can be paid with CP of any Element." — one sentence carrying a conditional
	// discount and a payment permission. The trailing clause used to defeat the end anchor, losing
	// both halves.
	// =========================================================================================

	private static final String TIFA_11_071L =
			"If you control a Card Name Cloud, the cost for playing Tifa onto the field is "
			+ "reduced by 1 and can be paid with CP of any Element.";

	/** Tifa 11-071L as a 5-cost hand card carrying her own cost modifier. */
	private static CardData makeTifa() {
		return new CardData(null, "Tifa", "Earth", 5, 8000, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), CardData.parseFieldAbilities(TIFA_11_071L, "Forward"),
				List.of(), List.of(), List.of(), List.of(),
				CardData.parseSelfCostModifiers(TIFA_11_071L),
				List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, TIFA_11_071L);
	}

	@Test
	void tifasDiscountAndPaymentPermissionBothWaitOnCloud() {
		MainWindow mw = new MainWindow();
		CardData tifa = makeTifa();

		assertEquals(5, mw.effectiveCastCost(tifa), "no Cloud — the printed cost stands");
		assertFalse(mw.selfGrantsAnyElement(tifa), "and no payment permission either");

		mw.placeCardInForwardZone(makeForward("Cloud", "Ice", 4, 8000));
		assertEquals(4, mw.effectiveCastCost(tifa), "Cloud on the field opens the discount");
		assertTrue(mw.selfGrantsAnyElement(tifa), "and the same condition opens the permission");
	}

	@Test
	void tifasSentenceIsOneCostModifierRatherThanAFieldAbility() {
		List<SelfCostModifier> mods = CardData.parseSelfCostModifiers(TIFA_11_071L);
		assertEquals(1, mods.size());
		SelfCostModifier mod = mods.get(0);
		assertEquals(1, mod.amountPerUnit());
		assertFalse(mod.isIncrease());
		assertEquals(SelfCostModifier.ScalingType.IF_CONTROL_NAME, mod.scalingType());
		assertEquals("Cloud", mod.param1());
		assertTrue(mod.anyElement(), "the trailing clause rides the same modifier");
		assertTrue(CardData.parseFieldAbilities(TIFA_11_071L, "Forward").isEmpty(),
				"and the sentence is no longer also reported as an unhandled field ability");
	}

	// =========================================================================================
	// Variable counter costs: "remove X [Name] Counters from [Self]:" (Lenna 12-109L, Leo 13-067L)
	//
	// The counter-cost pattern accepted only a literal count, so the whole ability failed to parse
	// as an action ability and fell through into the field-ability list. The effect half already
	// worked — Zemus 5-108L prints it verbatim behind a 《X》 CP cost — so what X means here is the
	// same xValue that cost produces, sourced from the counters spent instead of from CP.
	// =========================================================================================

	private static final String LENNA_12_109L_ABILITY =
			"《Dull》, remove X Arise Counters from Lenna: Choose 1 Forward in your Break Zone. "
			+ "If its cost is X, play it onto the field.";
	private static final String LEO_13_067L_ABILITY =
			"《1》《Dull》, remove X Kingdom Counters from Leo: Choose 1 Forward other than Card Name "
			+ "Leo, Light or Dark in your Break Zone. If its cost is X, play it onto the field. "
			+ "You can only use this ability during your turn and only once per turn.";

	@Test
	void aVariableCounterCostParsesAsAnActionAbility() {
		for (String[] c : new String[][]{{"Lenna", LENNA_12_109L_ABILITY, "Arise"},
		                                 {"Leo",   LEO_13_067L_ABILITY,   "Kingdom"}}) {
			List<ActionAbility> abilities = CardData.parseActionAbilities(c[1]);
			assertEquals(1, abilities.size(), c[0] + " parses as one action ability");
			List<CounterCost> costs = abilities.get(0).counterCosts();
			assertEquals(1, costs.size(), c[0] + " carries its counter cost");
			assertTrue(costs.get(0).variable(), c[0] + "'s amount is chosen at activation");
			assertEquals(c[2], costs.get(0).counterName());
			assertEquals(c[0], costs.get(0).cardName());
			assertTrue(CardData.parseFieldAbilities(c[1], "Forward").isEmpty(),
					c[0] + " no longer leaks into the field-ability list");
		}
	}

	@Test
	void aFixedCounterCostStillParsesAsBefore() {
		List<CounterCost> costs = CardData.parseActionAbilities(
				"Remove 1 Shuriken Counter from Edge: Choose 1 Forward. Deal it 3000 damage.")
				.get(0).counterCosts();
		assertEquals(1, costs.size());
		assertFalse(costs.get(0).variable(), "a literal count is not the X form");
		assertEquals(1, costs.get(0).count());
	}

	@Test
	void aVariableCostNeedsOnlyOneCounterToBeActivatable() {
		MainWindow mw = new MainWindow();
		CardData lenna = makeForward("Lenna", "Light", 5, 9000,
				CardData.parseActionAbilities(LENNA_12_109L_ABILITY));
		mw.placeCardInForwardZone(lenna);
		CounterCost cc = lenna.actionAbilities().get(0).counterCosts().get(0);

		assertFalse(mw.autoAbilityTriggers.counterCostSatisfied(cc, lenna),
				"X = 0 buys nothing, so an empty card cannot pay");
		mw.gameState.placeCounters(lenna, "Arise", 1);
		assertTrue(mw.autoAbilityTriggers.counterCostSatisfied(cc, lenna));
	}

	@Test
	void theCountersSpentBecomeTheXTheEffectReads() {
		// Driven from P2's seat so the amount is chosen without a dialog.
		MainWindow mw = new MainWindow();
		CardData lenna = makeForward("Lenna", "Light", 5, 9000,
				CardData.parseActionAbilities(LENNA_12_109L_ABILITY));
		placeP2Forward(mw, lenna);
		mw.gameState.placeCounters(lenna, "Arise", 5);
		// Costs 2 and 4 are reachable; 7 is not, and nothing should be spent reaching for it.
		for (int cost : new int[]{2, 4, 7})
			mw.gameState.getP2BreakZone().add(makeForward("Bz" + cost, "Light", cost, 5000));

		mw.autoAbilityTriggers.executeP2AbilityActivation(
				lenna.actionAbilities().get(0), lenna, () -> {}, new ArrayList<>(), new ArrayList<>(), 0);

		assertEquals(4, mw.gameState.peekStack().xValue(),
				"the most expensive Break Zone Forward within reach sets X");
		assertEquals(1, mw.gameState.getCounters(lenna, "Arise"), "and 4 of the 5 counters paid for it");
	}

	@Test
	void anUnreachableBreakZoneDoesNotStopTheAbilityResolving() {
		MainWindow mw = new MainWindow();
		CardData lenna = makeForward("Lenna", "Light", 5, 9000,
				CardData.parseActionAbilities(LENNA_12_109L_ABILITY));
		placeP2Forward(mw, lenna);
		mw.gameState.placeCounters(lenna, "Arise", 3);
		mw.gameState.getP2BreakZone().add(makeForward("Pricey", "Light", 9, 9000));

		mw.autoAbilityTriggers.executeP2AbilityActivation(
				lenna.actionAbilities().get(0), lenna, () -> {}, new ArrayList<>(), new ArrayList<>(), 0);

		assertEquals(3, mw.gameState.peekStack().xValue(),
				"nothing is reachable, so the full range stays open rather than the cost being blocked");
	}
}
