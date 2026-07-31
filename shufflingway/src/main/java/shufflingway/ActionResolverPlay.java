package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * Play parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverPlay {

	private ActionResolverPlay() {}

    static Consumer<GameContext> tryParseChooseWarpCardRemoveCounter(String text) {
        if (!CHOOSE_WARP_CARD_REMOVE_COUNTER.matcher(text).find()) return null;
        return GameContext::chooseAndRemoveWarpCounter;
    }
    static Consumer<GameContext> tryParseChooseWarpCardMayRemoveCounter(String text) {
        if (!CHOOSE_WARP_CARD_MAY_REMOVE_COUNTER.matcher(text).find()) return null;
        return GameContext::chooseAndMayRemoveWarpCounter;
    }
    static Consumer<GameContext> tryParseDoublePlayerAbilityOutgoingThisTurn(String text) {
        if (!DOUBLE_PLAYER_ABILITY_OUTGOING_THIS_TURN.matcher(text).find()) return null;
        return ctx -> ctx.doublePlayerAbilityOutgoingDamage();
    }
    /**
     * Parses Doublecast (Yuna): "When you cast a Summon this turn, you may cast 1 Summon from
     * your hand with a cost inferior to that of the Summon you cast without paying its cost."
     */
    static Consumer<GameContext> tryParseDoublecastFreeSummons(String text) {
        if (!DOUBLECAST_FREE_SUMMONS_PATTERN.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Doublecast — after each Summon cast this turn, "
                + "lower-cost hand Summons cast free");
            ctx.activateDoublecastFreeSummons();
        };
    }
    static Consumer<GameContext> tryParseIfCastAtLeast(String text, CardData source, int xValue) {
        Matcher m = IF_CAST_AT_LEAST.matcher(text.trim());
        if (!m.matches()) return null;
        int min = Integer.parseInt(m.group("min"));
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int cast = ctx.selfCardsCastThisTurn();
            if (cast >= min) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: only cast " + cast + " card(s) this turn (need " + min + ") — skipped");
            }
        };
    }
    /**
     * Parses "At the end of your opponent's turn, play [CardName] onto the field." — schedules
     * {@link GameContext#playNamedFromRfpOntoField} to fire at the end of the opponent's next turn.
     */
    static Consumer<GameContext> tryParseEndOfOppTurnPlayNamedOntoField(String text) {
        Matcher m = AT_END_OF_OPP_TURN_PLAY_NAMED_ONTO_FIELD.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        return ctx -> ctx.addEndOfOpponentTurnEffect(ctx2 -> ctx2.playNamedFromRfpOntoField(name));
    }
    static Consumer<GameContext> tryParseIfEitherPlayerNoForwardsPutSourceToBz(String text, CardData source) {
        if (source == null) return null;
        Matcher m = IF_EITHER_PLAYER_NO_FORWARDS_PUT_SOURCE_TO_BZ.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            if (ctx.selfForwardCount() > 0 && ctx.opponentForwardCount() > 0) return;
            ctx.logEntry("Effect: a player controls no Forwards — Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }
    /** No-op recogniser for multi-play grant field abilities handled as static card properties. */
    static Consumer<GameContext> tryParseMultiPlayGrant(String text) {
        if (CardData.MULTI_LIGHT_DARK_PLAY_PATTERN.matcher(text).matches()) return ctx -> {};
        if (CardData.MULTI_NAME_PLAY_PATTERN.matcher(text).matches())       return ctx -> {};
        return null;
    }
    /**
     * Parses "Choose 1 Summon targeting/choosing a Character/Forward you control. Cancel its effect."
     * Only Summons whose pre-selected targets include a card the canceler controls are eligible.
     */
    static Consumer<GameContext> tryParseCancelSummonTargetingMyCharacter(String text) {
        if (!CANCEL_SUMMON_TARGETING_MY_CHARACTER.matcher(text).find()) return null;
        java.util.function.Predicate<StackEntry> filter = StackEntry::isSummon;
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Summon choosing your Character — cancel its effect");
            ctx.cancelFilteredAbilityOnStack(filter, "Choose 1 Summon choosing your Character to cancel:", true);
        };
    }
    /**
     * Recognizes "Players cannot cast Summons." as a known passive field ability.
     * Returns a no-op consumer (the restriction is enforced statically by {@link MainWindow}).
     */
    static Consumer<GameContext> tryParsePlayerCannotCastSummons(String text) {
        if (!PLAYERS_CANNOT_CAST_SUMMONS.matcher(text.trim()).matches()) return null;
        return ctx -> ctx.logEntry("Static: Players cannot cast Summons");
    }
    /**
     * Parses "Choose 1 [Element] Summon in your Break Zone. You can cast it at any time
     * you could normally cast it this turn. The cost required to cast it is reduced by N."
     * At resolution: shows a chooser, moves the picked Summon BZ→hand, and registers a
     * cardname-targeted CostReductionModifier so the existing hand-cast path discounts it.
     */
    static Consumer<GameContext> tryParseChooseSummonInBzCastable(String text) {
        Matcher m = CHOOSE_SUMMON_IN_BZ_CASTABLE.matcher(text);
        if (!m.find()) return null;
        final String element = m.group("element").trim();
        final int    amount  = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 " + element + " Summon in BZ — castable this turn (cost -" + amount + ")");
            ctx.chooseSummonInBzMakeCastable(element, amount);
        };
    }
    static Consumer<GameContext> tryParseChooseFromOppBzCastable(String text) {
        Matcher m = CHOOSE_FROM_OPP_BZ_CASTABLE.matcher(text);
        if (!m.find()) return null;
        String t = m.group("type").toLowerCase(java.util.Locale.ROOT);
        final boolean inclForwards = t.startsWith("forward") || t.startsWith("character");
        final boolean inclBackups  = t.startsWith("backup")  || t.startsWith("character");
        final boolean inclMonsters = t.startsWith("monster") || t.startsWith("character");
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 " + t + " in opponent's BZ, remove from game — castable as your own");
            ctx.chooseFromOpponentBzMakeCastable(inclForwards, inclBackups, inclMonsters);
        };
    }
    static Consumer<GameContext> tryParseChooseSummonsFromBzCastable(String text) {
        Matcher mg = CHOOSE_SUMMONS_FROM_BZ_GAME.matcher(text);
        if (mg.find()) {
            final int count = Integer.parseInt(mg.group("count"));
            final boolean eitherBz = !mg.group("scope").toLowerCase(java.util.Locale.ROOT).equals("your");
            return ctx -> {
                ctx.logEntry("Effect: Choose " + count + " Summon(s) from BZ, remove from game — castable as your own this game");
                ctx.chooseSummonsFromBzMakeCastable(count, eitherBz, false, false, false);
            };
        }
        Matcher mt = CHOOSE_SUMMONS_FROM_BZ_TURN.matcher(text);
        if (mt.find()) {
            final int count = Integer.parseInt(mt.group("count"));
            final boolean eitherBz = !mt.group("scope").toLowerCase(java.util.Locale.ROOT).equals("your");
            String rfgClause = mt.group("rfg") != null ? mt.group("rfg").toLowerCase(java.util.Locale.ROOT) : "";
            final boolean rfgAfterUse = rfgClause.contains("after use");
            return ctx -> {
                ctx.logEntry("Effect: Choose " + count + " Summon(s) from BZ — castable as your own this turn"
                        + (rfgAfterUse ? " (removed from game after use)" : ""));
                ctx.chooseSummonsFromBzMakeCastable(count, eitherBz, true, rfgAfterUse, false);
            };
        }
        return null;
    }
    /**
     * Parses "Play [name] onto [the] field [dull]" for break-zone-origin abilities where
     * the card name matches the source.  Does not require a "from Break Zone" qualifier —
     * BZ-origin abilities say "Play [itself] onto the field" knowing they start in the BZ.
     */
    static Consumer<GameContext> tryParsePlaySourceOntoField(String text, CardData source) {
        if (source == null) return null;
        Matcher m = PLAY_SOURCE_ONTO_FIELD_PATTERN.matcher(text);
        if (!m.find()) return null;
        String name = m.group("name").trim();
        // "it" is a self-referential pronoun (e.g. "play it onto the field" in pay-cost abilities)
        String resolvedName = name.equalsIgnoreCase("it") ? source.name() : name;
        if (!resolvedName.equalsIgnoreCase(source.name())) return null;
        boolean dull = m.group("dull") != null;
        return ctx -> {
            ctx.logEntry("Effect: Play " + resolvedName + " from Break Zone → field" + (dull ? " dull" : ""));
            ctx.playAllByNameFromOwnBreakZoneDull(resolvedName, dull);
        };
    }
}
