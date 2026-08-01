package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * FieldAbility parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverFieldAbility {

	private ActionResolverFieldAbility() {}

    static Consumer<GameContext> tryParseGainsQuotedFieldAbilityUntilEot(String text, CardData source) {
        if (source == null) return null;
        Matcher m = GAINS_QUOTED_FIELD_ABILITY_UNTIL_EOT.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        return grantedSelfFieldAbilityEffect(m.group("quoted").trim(), source);
    }
    static Consumer<GameContext> tryParseGainsQuotedAbilitiesPermanent(String text, CardData source) {
        if (source == null) return null;
        Matcher m = GAINS_QUOTED_ABILITIES_PERMANENT.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;

        Consumer<GameContext> first = permanentGrantForClause(m.group("q1").trim(), source);
        if (first == null) return null;
        String second = m.group("q2");
        if (second == null) return first;
        Consumer<GameContext> rest = permanentGrantForClause(second.trim(), source);
        // Both halves or neither — a half-applied grant is worse than an unrecognised one.
        if (rest == null) return null;
        return ctx -> { first.accept(ctx); rest.accept(ctx); };
    }
    /**
     * Parses "[Self] gains +N power[, traits] (This effect does not end at the end of the turn.)"
     * — 8-147S Fordola's payoff, the outlasts-the-turn twin of the {@code SELF_POWER_BOOST}
     * followup handled inside the choose chain.
     *
     * <p>Rejects a match that grants neither power nor a trait, which the all-optional groups
     * would otherwise allow for a bare "X gains (This effect …)".
     */
    static Consumer<GameContext> tryParseSelfPowerBoostPermanent(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_POWER_BOOST_PERMANENT.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        int amount = m.group("amount") != null ? Integer.parseInt(m.group("amount")) : 0;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        if (amount == 0 && traits.isEmpty()) return null;
        return ctx -> ctx.boostSourceForwardPermanently(source, amount, traits);
    }
    static Consumer<GameContext> tryParseOppFwdsLoseAllAbilitiesEot(String text) {
        if (!OPP_FWDS_LOSE_ALL_ABILITIES_EOT.matcher(text).matches()) return null;
        return ctx -> ctx.oppForwardsLoseAllAbilitiesUntilEndOfTurn();
    }
    /** Reads a Forward-ability grant out of a field-ability text, or {@code null} if it is not one. */
    static ForwardAbilityGrant tryParseForwardAbilityGrant(String fieldText) {
        if (fieldText == null) return null;
        Matcher m = FIELD_GRANT_ABILITY_TO_FORWARDS.matcher(fieldText.trim());
        if (!m.matches()) return null;
        boolean affectsOpponent = m.group("who").toLowerCase(java.util.Locale.ROOT).startsWith("opponent");
        return new ForwardAbilityGrant(affectsOpponent, m.group("ability").trim());
    }
    /**
     * Parses the "At the end of your turn, …" half of a granted ability into an effect that runs for
     * {@code grantee} — the Forward that received it, which is what self-references like "this
     * Forward" resolve to. Returns {@code null} when the grant is not an end-of-turn ability or its
     * effect is not supported.
     */
    static Consumer<GameContext> tryParseGrantedEndOfTurnEffect(String abilityText, CardData grantee) {
        if (abilityText == null || grantee == null) return null;
        Matcher m = GRANTED_AT_END_OF_YOUR_TURN.matcher(abilityText.trim());
        if (!m.matches()) return null;
        return parse(m.group("effect").trim(), grantee);
    }
    /**
     * Parses "At the end of each of your turns, &lt;effect&gt;" — a recurring field-ability
     * trigger.  Returns a consumer that executes the inner effect directly; the caller
     * ({@code fireFieldEndOfTurnAbilities}) is responsible for invoking it each end phase.
     * The inner effect is resolved via the full {@link #parse} dispatcher so all supported
     * effect types work.
     */
    static Consumer<GameContext> tryParseEndOfEachTurnFieldAbility(String text, CardData source) {
        Matcher m = AT_END_OF_EACH_TURN_PATTERN.matcher(text);
        if (!m.find()) return null;
        String inner = m.group("inner").trim();
        Consumer<GameContext> innerEffect = parse(inner, source);
        if (innerEffect == null) return null;
        return innerEffect;
    }
    /**
     * Parses "[action] all [the] [element] [targets] [of cost X] [control]".
     *
     * <p>Supported actions: Break, dull, freeze, dull and freeze, Activate.
     * <p>Supported targets: Forwards, Backups, Forwards and Monsters, Characters.
     */
    static Consumer<GameContext> tryParseAllFieldEffect(String text) {
        Matcher m = ALL_FIELD_EFFECT_PATTERN.matcher(text);
        if (!m.find()) return null;

        String rawAction = m.group("action").toLowerCase().replaceAll("\\s+", " ");
        GameContext.MassAction action = switch (rawAction) {
            case "break"          -> GameContext.MassAction.BREAK;
            case "dull"           -> GameContext.MassAction.DULL;
            case "freeze"         -> GameContext.MassAction.FREEZE;
            case "dull and freeze"-> GameContext.MassAction.DULL_AND_FREEZE;
            case "activate"       -> GameContext.MassAction.ACTIVATE;
            default               -> null;
        };
        if (action == null) return null;

        String element   = m.group("element");
        String job       = m.group("job") != null ? m.group("job").trim() : null;
        String category  = m.group("category");
        String targets   = m.group("targets");
        // When no explicit type is given (job-only or category-only), sweep all card types
        boolean inclForwards, inclBackups, inclMonsters;
        if (targets == null) {
            inclForwards = true; inclBackups = true; inclMonsters = (job == null && category == null);
        } else {
            String tgtLower  = targets.toLowerCase();
            inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
            inclBackups  = tgtLower.contains("backup")  || tgtLower.contains("character");
            inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        }

        String costStr = m.group("cost");
        String costCmp = m.group("costcmp");
        int    costVal = costStr != null ? Integer.parseInt(costStr) : -1;

        String excludeCostStr = m.group("excludecost");
        int    excludeCostVal = excludeCostStr != null ? Integer.parseInt(excludeCostStr) : -1;

        String control      = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null && control.toLowerCase().contains("you control");

        String traitStr     = m.group("trait");
        EnumSet<CardData.Trait> traitFilter = parseTraits(traitStr);

        String actionLabel = switch (action) {
            case BREAK           -> "Break";
            case DULL            -> "Dull";
            case FREEZE          -> "Freeze";
            case DULL_AND_FREEZE -> "Dull & Freeze";
            case ACTIVATE        -> "Activate";
            case RETURN_TO_HAND  -> "Return to hand";
        };
        String tgtLabel     = targets != null ? targets : (job != null ? "Job " + job : category != null ? "Cat " + category : "all");
        String costLabel    = costVal >= 0
                ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String exclLabel    = excludeCostVal >= 0 ? " [not cost " + excludeCostVal + "]" : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String traitLabel   = traitStr != null ? " with " + traitStr.trim() : "";
        String logMsg = actionLabel + " all " + tgtLabel + traitLabel + costLabel + exclLabel + controlLabel;

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldEffect(action, inclForwards, inclBackups, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, excludeCostVal, job, category, traitFilter);
        };
    }
    /**
     * Parses "All [the] Job X [targets] [you control] gain Keyword[, ...] until end of turn."
     */
    static Consumer<GameContext> tryParseAllFieldJobKeywordGrant(String text) {
        Matcher m = ALL_FIELD_JOB_KEYWORD_GRANT_PATTERN.matcher(text);
        if (!m.find()) return null;

        String job      = m.group("job").trim();
        String targets  = m.group("targets");
        boolean inclForwards = targets == null || targets.toLowerCase().contains("forward")
                            || targets.toLowerCase().contains("character");
        boolean inclMonsters = targets == null || targets.toLowerCase().contains("monster")
                            || targets.toLowerCase().contains("character");

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");

        EnumSet<CardData.Trait> traits = parseTraits(m.group("keywords"));
        if (traits.isEmpty()) return null;

        String traitNames = traitNamesOnly(traits);
        String typeLabel  = targets != null ? " " + targets : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String logMsg = "All Job " + job + typeLabel + controlLabel + " gain " + traitNames + " until end of turn";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldJobKeywordGrant(traits, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, job);
        };
    }
    /**
     * Parses "All [the] [element] [targets] [of cost N or less/more] [you control] gain
     * Keyword[, Keyword2, ...] until end of turn."
     */
    static Consumer<GameContext> tryParseAllFieldKeywordGrant(String text) {
        Matcher m = ALL_FIELD_KEYWORD_GRANT_PATTERN.matcher(text);
        if (!m.find()) return null;

        String element  = m.group("element");
        String category = m.group("category");
        String targets  = m.group("targets");
        String tgtLower = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");

        String costStr = m.group("cost");
        String costCmp = m.group("costcmp");
        int    costVal = costStr != null ? Integer.parseInt(costStr) : -1;

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null && control.toLowerCase().contains("you control");

        EnumSet<CardData.Trait> traits = parseTraits(m.group("keywords"));
        if (traits.isEmpty()) return null;

        String elemLabel    = element != null ? element + " " : "";
        String catLabel     = category != null ? "Category " + category + " " : "";
        String costLabel    = costVal >= 0 ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String traitNames   = traitNamesOnly(traits);
        String logMsg = "All " + elemLabel + catLabel + targets + costLabel + controlLabel + " gain " + traitNames + " until end of turn";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldKeywordGrant(traits, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, category);
        };
    }
    /**
     * Parses "At the beginning of your Main Phase 1, &lt;effect&gt;" — a recurring
     * field-ability trigger.  Strips the trigger prefix and dispatches the inner effect
     * through the full {@link #parse} chain so any supported effect can follow.
     * {@code fireFieldMainPhase1Abilities} is responsible for invoking it each Main Phase 1 start.
     */
    static Consumer<GameContext> tryParseBeginningOfMainPhase1FieldAbility(String text, CardData source) {
        Matcher m = AT_BEGINNING_OF_MAIN_PHASE_1_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }
    /**
     * Parses "At the beginning of your Main Phase 2, &lt;effect&gt;" — same as
     * {@link #tryParseBeginningOfMainPhase1FieldAbility} but for Main Phase 2.
     */
    static Consumer<GameContext> tryParseBeginningOfMainPhase2FieldAbility(String text, CardData source) {
        Matcher m = AT_BEGINNING_OF_MAIN_PHASE_2_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }
    /**
     * Parses "Each turn, at the beginning of Main Phase 1, &lt;effect&gt;" — fires at the start of
     * BOTH players' Main Phase 1 for all cards the controller has on the field.
     * {@code fireFieldMainPhase1EachTurnAbilities} is responsible for invoking it.
     */
    static Consumer<GameContext> tryParseBeginningOfMainPhase1EachTurnFieldAbility(String text, CardData source) {
        Matcher m = AT_BEGINNING_OF_MAIN_PHASE_1_EACH_TURN_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }
    /**
     * Parses "At the beginning of your opponent's Main Phase 1, &lt;effect&gt;" — fires at the start of
     * the controller's opponent's Main Phase 1.
     * {@code fireFieldOppMainPhase1Abilities} is responsible for invoking it.
     */
    static Consumer<GameContext> tryParseBeginningOfOppMainPhase1FieldAbility(String text, CardData source) {
        Matcher m = AT_BEGINNING_OF_OPP_MAIN_PHASE_1_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }
    /**
     * Parses "At the end of your opponent's turn, &lt;effect&gt;" — fires when the controlling
     * player's opponent ends their turn.
     * {@code fireFieldEndOfOpponentTurnAbilities} is responsible for invoking it.
     */
    static Consumer<GameContext> tryParseEndOfOpponentTurnFieldAbility(String text, CardData source) {
        Matcher m = AT_END_OF_OPP_TURN_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }
}
