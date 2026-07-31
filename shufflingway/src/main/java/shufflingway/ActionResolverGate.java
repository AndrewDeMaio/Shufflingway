package shufflingway;

import static shufflingway.ActionResolver.*;
import static shufflingway.ActionResolverBreak.*;
import static shufflingway.ActionResolverChoose.*;
import static shufflingway.ActionResolverCost.*;
import static shufflingway.ActionResolverDamage.*;
import static shufflingway.ActionResolverFieldAbility.*;
import static shufflingway.ActionResolverHand.*;
import static shufflingway.ActionResolverPlay.*;
import static shufflingway.ActionResolverPower.*;
import static shufflingway.ActionResolverRestriction.*;
import static shufflingway.ActionResolverSearch.*;
import static shufflingway.ActionResolverState.*;

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
 * Gate parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverGate {

	private ActionResolverGate() {}

    /**
     * Parses "If you [do not] control X, Y" — resolves Y only when the control condition is
     * (un)met at resolution time. Returns {@code null} when the condition or inner effect cannot
     * be parsed so the text falls through to the regular matchers (preserving prior behaviour).
     */
    static Consumer<GameContext> tryParseIfControlCondOtherThan(String text, CardData source, int xValue) {
        Matcher m = IF_CONTROL_COND_OTHER_THAN.matcher(text.trim());
        if (!m.matches()) return null;
        ControlCondition cc = CardData.parseControlCondition(m.group("cond").trim());
        if (cc == null) return null;
        boolean negated    = m.group("neg") != null;
        String excludeName = m.group("exclude").trim();
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            boolean met = ctx.controlConditionMetExcluding(cc, excludeName);
            if (met != negated) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: control condition (excl. " + excludeName + ") not met — skipped");
            }
        };
    }
    static Consumer<GameContext> tryParseControlConditionGate(String text, CardData source, int xValue) {
        Matcher m = CONTROL_CONDITION_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        ControlCondition cc = CardData.parseControlCondition(m.group("cond").trim());
        if (cc == null) return null;
        boolean negated = m.group("neg") != null;
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            if (ctx.controlConditionMet(cc) != negated) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: control condition not met — skipped");
            }
        };
    }
    /**
     * Parses "&lt;base&gt;. If you control X, &lt;alternative&gt; instead." — resolves exactly one of the
     * two branches, never both. Returns {@code null} when the condition or either branch cannot be
     * parsed, so the text falls through to the regular matchers.
     */
    static Consumer<GameContext> tryParseControlGatedInsteadUpgrade(String text, CardData source, int xValue) {
        Matcher m = CONTROL_GATED_INSTEAD_UPGRADE.matcher(text.trim());
        if (!m.matches()) return null;
        ControlCondition cc = CardData.parseControlCondition(m.group("cond").trim());
        if (cc == null) return null;
        // A bare count carries no filters and would be tested against every field card. It means the
        // wording elided the noun from the preceding clause ("… if you control 3 or more Category
        // FFTA Characters, draw 1 card. If you control 5 or more, draw 2 cards instead." — Marche
        // 16-122R), which this parser cannot recover, so leave such text to the other matchers.
        if (!cc.isNamedMode() && !cc.requiresCrystal() && cc.orAlternatives().isEmpty()
                && cc.cardType() == null && cc.element() == null
                && cc.job() == null && cc.category() == null && cc.orCardNames().isEmpty())
            return null;

        String rest    = m.group("rest").trim();
        String altText = m.group("alt").trim() + "." + (rest.isEmpty() ? "" : " " + rest);
        Consumer<GameContext> baseFn = parse(m.group("base").trim(), source, xValue);
        Consumer<GameContext> altFn  = parse(altText, source, xValue);
        if (baseFn == null || altFn == null) return null;

        return ctx -> {
            if (ctx.controlConditionMet(cc)) {
                ctx.logEntry("Effect: you control " + cc + " — replacement effect applies instead");
                altFn.accept(ctx);
            } else {
                baseFn.accept(ctx);
            }
        };
    }
    static Consumer<GameContext> tryParseOpponentControlsCardGate(String text, CardData source, int xValue) {
        Matcher m = OPP_CONTROL_CARD_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        String cond    = m.group("cond").toLowerCase();
        String typeRaw = m.group("type");
        String normType = Character.toUpperCase(typeRaw.charAt(0))
                + typeRaw.substring(1).toLowerCase().replaceAll("s$", "");
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            if (ctx.opponentControlsCard(normType, cond)) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: opponent has no " + cond + " " + normType + " — skipped");
            }
        };
    }
    static Consumer<GameContext> tryParseIfOppControlsNOrMoreCondTypeGate(String text, CardData source, int xValue) {
        Matcher m = IF_OPP_CONTROLS_N_OR_MORE_COND_TYPE_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        int    threshold = Integer.parseInt(m.group("count"));
        String cond      = m.group("cond").toLowerCase();
        String typeRaw   = m.group("type");
        String normType  = Character.toUpperCase(typeRaw.charAt(0))
                + typeRaw.substring(1).toLowerCase().replaceAll("s$", "");
        boolean inclFwds = normType.equals("Forward")   || normType.equals("Character");
        boolean inclBkps = normType.equals("Backup")    || normType.equals("Character");
        boolean inclMons = normType.equals("Monster")   || normType.equals("Character");
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int cnt = ctx.countOppFieldCardsWithCondition(inclFwds, inclBkps, inclMons, cond);
            if (cnt >= threshold) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: " + threshold + "+ " + cond + " " + normType + "(s) required, opponent has " + cnt + " — skipped");
            }
        };
    }
    static Consumer<GameContext> tryParseIfControlAtMost(String text, CardData source, int xValue) {
        Matcher m = IF_CONTROL_AT_MOST.matcher(text.trim());
        if (!m.matches()) return null;
        int max          = Integer.parseInt(m.group("max"));
        String category  = m.group("category");
        String type      = m.group("type").trim();
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        String label = (category != null ? "Category " + category + " " : "") + type;
        return ctx -> {
            int count = category != null
                    ? ctx.ownFieldCountByCategory(category, type)
                    : ctx.ownFieldCount(type);
            if (count <= max) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: control " + count + " " + label + " (max " + max + ") — skipped");
            }
        };
    }
    /**
     * Parses Siren (V)'s "Put the top card of your deck into the Break Zone. If the card put into
     * the Break Zone is not a [Type], cancel its/their effect(s)." — mills the top deck card and
     * cancels the in-progress selection when it is not of the given type.
     */
    static Consumer<GameContext> tryParseCancelChosenMillTopIfNotType(String text) {
        Matcher m = CANCEL_CHOSEN_MILL_TOP_IF_NOT_TYPE.matcher(text.trim());
        if (!m.find()) return null;
        String type = m.group("type");
        return ctx -> {
            ctx.logEntry("Effect: mill top of deck; if not a " + type + ", cancel the effect choosing your Character(s)");
            ctx.millTopDeckCancelChosenIfNotType(type);
        };
    }
}
