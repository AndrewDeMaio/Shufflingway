package shufflingway;

import static shufflingway.ActionResolver.*;
import static shufflingway.ActionResolverDamage.*;
import static shufflingway.ActionResolverPower.*;
import static shufflingway.ActionResolverSearch.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Break parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverBreak {

	private ActionResolverBreak() {}

    /**
     * Edea: "Choose 1 Forward opponent controls with a cost inferior or equal to the number
     * of [Element] [Backups/Forwards] you control. Break it."
     */
    static Consumer<GameContext> tryParseChooseOppFwdDynCostBreak(String text) {
        Matcher m = CHOOSE_OPP_FWD_DYN_COST_BREAK.matcher(text);
        if (!m.find()) return null;
        String element  = m.group("element");
        String cardtype = m.group("cardtype").toLowerCase();
        boolean inclFwd = cardtype.startsWith("forward");
        boolean inclBkp = !inclFwd;
        String followupText = m.group("followup").trim();
        if (!followupText.toLowerCase().contains("break it")) return null;
        return ctx -> {
            int ceiling = ctx.selfFieldCount(element, inclFwd, inclBkp, false);
            ctx.logEntry("Choose 1 Forward opponent controls with cost ≤ " + ceiling
                    + " (# " + element + " " + cardtype + " you control)");
            List<ForwardTarget> ts = ctx.selectCharacters(1, false, true, false,
                    null, null, ceiling, "less", -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            ts.forEach(ctx::breakTarget);
        };
    }
    /** Parses "Each player selects N [type](s) from their Break Zone and adds it/them to their hand." */
    static Consumer<GameContext> tryParseEachPlayerSalvageFromBreakZone(String text) {
        Matcher m = EACH_PLAYER_SALVAGE_FROM_BREAK_ZONE.matcher(text);
        if (!m.find()) return null;
        int count   = Integer.parseInt(m.group("count"));
        String type = m.group("type");
        String tl   = type.toLowerCase(java.util.Locale.ROOT);
        boolean anyCard = tl.equals("card");
        boolean fwds = anyCard || tl.equals("forward") || tl.equals("character");
        boolean bkps = anyCard || tl.equals("backup")  || tl.equals("character");
        boolean mons = anyCard || tl.equals("monster") || tl.equals("character");
        boolean smns = anyCard;   // "1 card" is unrestricted; every named type excludes Summons
        return ctx -> {
            ctx.logEntry("Effect: Each player salvages " + count + " " + type
                    + "(s) from their Break Zone to hand");
            ctx.eachPlayerSalvageFromBreakZone(count, fwds, bkps, mons, smns);
        };
    }
    /** Parses "Both players select 1 Forward they control and put it into the Break Zone." */
    static Consumer<GameContext> tryParseBothPlayersSelectForwardToBreakZone(String text) {
        Matcher m = BOTH_PLAYERS_SELECT_FORWARD_TO_BREAK_ZONE.matcher(text);
        if (!m.find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Both players select 1 Forward they control and put it into the Break Zone");
            ctx.eachPlayerSelectForwardAndBreak();
        };
    }
    /** Parses "Each player selects up to N Forwards or Monsters they control (select as many as possible). Put them into the Break Zone." */
    static Consumer<GameContext> tryParseEachPlayerSelectUpToNToBreakZone(String text) {
        Matcher m = EACH_PLAYER_SELECT_UP_TO_N_TO_BREAK_ZONE.matcher(text);
        if (!m.find()) return null;
        int    count    = Integer.parseInt(m.group("count"));
        String tgtLower = m.group("targets").toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        return ctx -> {
            ctx.logEntry("Effect: Each player selects up to " + count + " Forwards/Monsters and puts them in Break Zone");
            ctx.eachPlayerSelectUpToNAndBreak(count, inclForwards, inclMonsters);
        };
    }
    /** Parses "Your opponent randomly removes N card(s) in their hand from the game." */
    static Consumer<GameContext> tryParseOpponentRandomHandRfp(String text) {
        Matcher m = OPPONENT_RANDOM_HAND_RFP.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent randomly removes " + count + " hand card(s) from the game");
            ctx.forceOpponentRandomHandRfp(count);
        };
    }
    /**
     * Parses "Your opponent removes N card(s) in their hand from the game."
     * (opponent chooses which cards, not random).
     */
    static Consumer<GameContext> tryParseOpponentHandRfp(String text) {
        Matcher m = OPPONENT_HAND_RFP.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent removes " + count + " hand card(s) from the game");
            ctx.forceOpponentHandRfp(count);
        };
    }
    /** Parses "Break [CardName]." when CardName is the source card — breaks the source forward/monster. */
    static Consumer<GameContext> tryParseBreakSourceCard(String text, CardData source) {
        if (source == null) return null;   // the pattern is keyed to the source card
        Matcher m = BREAK_SOURCE_CARD.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (!name.equalsIgnoreCase(source.name()) && !isSelfReference(name)) return null;
        return ctx -> {
            ctx.logEntry("Effect: Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }
    static Consumer<GameContext> tryParseBreakBlockingForward(String text) {
        if (!BREAK_BLOCKING_FORWARD.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Break the blocking Forward");
            ctx.breakBlockingForward();
        };
    }
    static Consumer<GameContext> tryParseBreakForwardThatBlocksCard(String text) {
        Matcher m = BREAK_FORWARD_THAT_BLOCKS_CARD.matcher(text.trim());
        if (!m.matches()) return null;
        String attackerName = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: Break the Forward that blocks " + attackerName);
            ctx.breakForwardBlockingAttacker(attackerName);
        };
    }
    static Consumer<GameContext> tryParsePutSourceIntoBreakZone(String text, CardData source) {
        if (source == null) return null;   // the pattern is keyed to the source card's own name
        Matcher m = PUT_SOURCE_INTO_BREAK_ZONE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry("Effect: Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }
    static Consumer<GameContext> tryParseIfOppNoForwardsPutToBreakZone(String text, CardData source) {
        if (source == null) return null;
        Matcher m = IF_OPP_NO_FORWARDS_PUT_TO_BREAK_ZONE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            if (ctx.opponentForwardCount() > 0) return;
            ctx.logEntry("Effect: opponent controls no Forwards — Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }
    /**
     * Parses "If there are N or more cards removed from the game, &lt;effect&gt;".
     * The inner effect only fires when the combined permanent-RFP count of both players meets the threshold.
     */
    static Consumer<GameContext> tryParseIfRfpCount(String text, CardData source) {
        Matcher m = IF_RFP_COUNT_INNER.matcher(text.trim());
        if (!m.find()) return null;
        int minRfp = Integer.parseInt(m.group("count"));
        String innerText = m.group("inner").trim();
        Consumer<GameContext> innerEffect = parse(innerText, source);
        if (innerEffect == null) return null;
        return ctx -> {
            int totalRfp = ctx.countRemovedFromGame();
            if (totalRfp >= minRfp) innerEffect.accept(ctx);
            else ctx.logEntry("Condition not met: need " + minRfp + "+ cards RFP, have " + totalRfp);
        };
    }
    static Consumer<GameContext> tryParseOpponentPutsForwardToBreakZone(String text) {
        Matcher m = OPPONENT_PUTS_FORWARD_TO_BREAK_ZONE_PATTERN.matcher(text);
        if (!m.find()) return null;

        int     count     = Integer.parseInt(m.group("count"));
        String  condition = m.group("condition");
        String  targets   = m.group("targets");
        String  tgtLower  = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("character");

        String condLabel = condition != null ? " " + condition : "";
        String logLabel  = "Opponent puts " + count + condLabel + " " + targets
                         + " they control → Break Zone";

        return ctx -> {
            ctx.logEntry("Effect: " + logLabel);
            List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                    condition, null, -1, null, -1, null,
                    inclForwards, false, inclMonsters, null, null, null, null, false, null, false);
            sortedByIdxDesc(ts, false).forEach(ctx::forceTargetToBreakZone);
        };
    }
    static Consumer<GameContext> tryParsePlayAllByNameFromBreakZone(String text) {
        Matcher m = PLAY_ALL_FROM_BREAK_ZONE_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        String cardName = m.group("cardname").trim();
        boolean dull = m.group("dull") != null;
        return ctx -> {
            ctx.logEntry("Effect: Play all Card Name " + cardName + " from Break Zone → field" + (dull ? " dull" : ""));
            ctx.playAllByNameFromOwnBreakZoneDull(cardName, dull);
        };
    }
    static Consumer<GameContext> tryParsePlaySourceFromBreakZone(String text, CardData source) {
        if (source == null) return null;
        Matcher m = PLAY_SOURCE_FROM_BREAK_ZONE.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (!name.equalsIgnoreCase(source.name())) return null;
        boolean dull = m.group("dull") != null;
        return ctx -> {
            ctx.logEntry("Effect: Play " + name + " from Break Zone → field" + (dull ? " dull" : ""));
            ctx.playAllByNameFromOwnBreakZoneDull(name, dull);
        };
    }
}
