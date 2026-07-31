package shufflingway;

import static shufflingway.ActionResolver.*;
import static shufflingway.ActionResolverDamage.*;
import static shufflingway.ActionResolverPower.*;

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
 * Search parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverSearch {

	private ActionResolverSearch() {}

    static Consumer<GameContext> tryParseRfpAllFwdExceptElementsThenTwiceDeck(String text) {
        Matcher m = RFP_ALL_FWD_EXCEPT_ELEMENTS_THEN_TWICE_DECK.matcher(text);
        if (!m.find()) return null;
        String elem1 = m.group("elem1");
        String elem2 = m.group("elem2");
        return ctx -> {
            ctx.logEntry("Effect: Remove from game all Forwards other than " + elem1 + " and " + elem2);
            List<ForwardTarget> toRemove = new ArrayList<>();
            for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                CardData fwd = ctx.p1Forward(i);
                if (!fwd.containsElement(elem1) && !fwd.containsElement(elem2))
                    toRemove.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
            }
            for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                CardData fwd = ctx.p2Forward(i);
                if (!fwd.containsElement(elem1) && !fwd.containsElement(elem2))
                    toRemove.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
            }
            sortedByIdxDesc(toRemove, true) .forEach(ctx::removeTargetFromGame);
            sortedByIdxDesc(toRemove, false).forEach(ctx::removeTargetFromGame);
            int deckRfp = toRemove.size() * 2;
            if (deckRfp > 0) {
                ctx.logEntry("Effect: Remove top " + deckRfp + " card(s) of deck from game (2 × " + toRemove.size() + " removed)");
                ctx.removeTopCardsOfDeckFromGame(deckRfp, null);   // nothing refers back to these
            }
        };
    }
    /** Parses "You may reveal 1 [Element] card from your hand." */
    static Consumer<GameContext> tryParseMayRevealElementFromHand(String text) {
        Matcher m = YOU_MAY_REVEAL_ELEMENT_FROM_HAND.matcher(text.trim());
        if (!m.matches()) return null;
        String element = m.group("element");
        return ctx -> {
            ctx.logEntry("Effect: May reveal 1 " + element + " card from hand");
            ctx.mayRevealCardByElementFromHand(element);
        };
    }
    /** Parses "Your opponent randomly places N card(s) from their hand at the bottom of their deck." */
    static Consumer<GameContext> tryParseOpponentRandomHandToBottomDeck(String text) {
        Matcher m = OPPONENT_RANDOM_HAND_TO_BOTTOM_DECK.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent randomly places " + count + " hand card(s) at bottom of their deck");
            ctx.forceOpponentRandomHandToBottomOfDeck(count);
        };
    }
    /**
     * Parses "Your opponent reveals their hand. Select N card(s) in their hand.
     * Your opponent removes it from the game."
     */
    static Consumer<GameContext> tryParseRevealSelectHandRfp(String text) {
        Matcher m = REVEAL_SELECT_HAND_RFP.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals hand — select " + count + " to remove from game");
            ctx.selectFromOpponentHandAndRfp(count);
        };
    }
    /** Parses "Opponent reveals hand. You may select 1 → remove from game, opponent draws 1." */
    static Consumer<GameContext> tryParseRevealHandOptPickRfpOppDraw(String text) {
        if (!REVEAL_HAND_OPT_PICK_RFP_OPP_DRAW.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals hand — optionally select 1 to RFP, opponent draws 1");
            ctx.revealHandOptPickRfpOpponentDraws();
        };
    }
    static Consumer<GameContext> tryParsePutSourceToBottomOfDeck(String text, CardData source) {
        if (source == null) return null;
        Matcher m = PUT_SOURCE_TO_BOTTOM_OF_DECK.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry("Effect: " + source.name() + " → bottom of its owner's deck");
            ctx.putSourceToBottomOfDeck(source);
        };
    }
    static Consumer<GameContext> tryParseShuffleThenRevealPlayNamedRestBottom(String text, CardData source) {
        Matcher m = SHUFFLE_THEN_REVEAL_PLAY_NAMED_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n           = Integer.parseInt(m.group("n"));
        String cardName = m.group("cardname").trim();
        if (source != null && !cardName.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.shuffleDeck();
            ctx.revealTopNPlayNamedOntoFieldRestBottom(n, cardName);
        };
    }
    static Consumer<GameContext> tryParseRevealPlayNamedWithMaxCostRestBottom(String text) {
        Matcher m = REVEAL_PLAY_NAMED_MAX_COST_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n           = Integer.parseInt(m.group("n"));
        String cardName = m.group("cardname").trim();
        int maxCost     = Integer.parseInt(m.group("maxcost"));
        return ctx -> ctx.revealTopNPlayNamedWithMaxCostOntoFieldRestBottom(n, cardName, maxCost);
    }
    static Consumer<GameContext> tryParseRevealPlayNamedOrJobMaxCostRestBottom(String text) {
        Matcher m = REVEAL_PLAY_NAMED_OR_JOB_MAX_COST_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n           = Integer.parseInt(m.group("n"));
        int max         = Integer.parseInt(m.group("max"));
        String cardName = m.group("cardname").trim();
        String job      = m.group("job").trim();
        int maxCost     = Integer.parseInt(m.group("maxcost"));
        return ctx -> ctx.revealTopNPlayUpToNamedOrJobWithMaxCostOntoFieldRestBottom(n, max, cardName, job, maxCost);
    }
    static Consumer<GameContext> tryParseFlipUntilTypeToHandRestShuffleBottom(String text) {
        if (!FLIP_UNTIL_TYPE_TO_HAND_REST_SHUFFLE_BOTTOM.matcher(text.trim()).matches()) return null;
        return GameContext::flipUntilTypeToHandRestShuffleBottom;
    }
    static Consumer<GameContext> tryParseRevealPlayTypeOntoFieldRestBottom(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_PLAY_TYPE_ONTO_FIELD_REST_BOTTOM.matcher((s.isEmpty() ? text : s).trim());
        if (!m.matches()) return null;
        int n      = Integer.parseInt(m.group("n"));
        int max    = Integer.parseInt(m.group("max"));
        String typeRaw  = m.group("type");
        String normType = Character.toUpperCase(typeRaw.charAt(0))
                + typeRaw.substring(1).toLowerCase();
        String category = m.group("category");
        return ctx -> ctx.revealTopNPlayUpToTypeOntoFieldRestBottom(n, max, normType, category);
    }
    static Consumer<GameContext> tryParseRevealElementCardFromHandIfSoDraw(String text) {
        Matcher m = REVEAL_ELEMENT_CARD_FROM_HAND_IF_SO_DRAW.matcher(text.trim());
        if (!m.matches()) return null;
        String elementRaw = m.group("element");
        String element    = Character.toUpperCase(elementRaw.charAt(0)) + elementRaw.substring(1).toLowerCase();
        int drawCount     = Integer.parseInt(m.group("draw"));
        return ctx -> ctx.revealElementCardFromHandDraw(element, drawCount);
    }
    static Consumer<GameContext> tryParseRevealPlayElementTypeCostOntoFieldRestBottom(String text) {
        return tryParseRevealPlayElementTypeCostOntoFieldRestBottom(text, 0);
    }
    static Consumer<GameContext> tryParseRevealPlayElementTypeCostOntoFieldRestBottom(String text, int xValue) {
        // Strip "You can only cast [CardName] during your Main Phase." restriction prefix.
        String stripped = text.trim().replaceFirst(
                "(?i)You\\s+can\\s+only\\s+cast\\s+[^.]+?during\\s+your\\s+Main\\s+Phase[.!]?\\s*", "").trim();
        Matcher m = REVEAL_PLAY_ELEMENT_TYPE_COST_ONTO_FIELD_REST_BOTTOM.matcher(stripped);
        if (!m.matches()) return null;
        int n           = Integer.parseInt(m.group("n"));
        int max         = Integer.parseInt(m.group("max"));
        String elementRaw = m.group("element");
        String element    = elementRaw != null ? Character.toUpperCase(elementRaw.charAt(0)) + elementRaw.substring(1).toLowerCase() : null;
        String typeRaw  = m.group("type");
        String normType = Character.toUpperCase(typeRaw.charAt(0)) + typeRaw.substring(1).toLowerCase();
        String costStr  = m.group("cost");
        int maxCost     = "X".equalsIgnoreCase(costStr) ? xValue : Integer.parseInt(costStr);
        return ctx -> ctx.revealTopNPlayUpToElementTypeCostOntoFieldRestBottom(n, max, element, normType, maxCost);
    }
    /**
     * Parses Banon's "Reveal the top card of your deck. If it is a [Type], cancel all effects
     * choosing [Name]." — reveals the top deck card and cancels the in-progress selection when it
     * is of the given type.
     */
    static Consumer<GameContext> tryParseCancelChosenRevealTopIfType(String text) {
        Matcher m = CANCEL_CHOSEN_REVEAL_TOP_IF_TYPE.matcher(text.trim());
        if (!m.find()) return null;
        String type = m.group("type");
        return ctx -> {
            ctx.logEntry("Effect: reveal top of deck; if a " + type + ", cancel the effect choosing your Character");
            ctx.revealTopDeckCancelChosenIfType(type);
        };
    }
    static Consumer<GameContext> tryParseRandomRevealHandCastIfSummonFree(String text) {
        if (!RANDOM_REVEAL_HAND_CAST_IF_SUMMON_FREE.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Randomly reveal 1 card from hand — cast it for free if it is a Summon");
            ctx.randomRevealHandCastIfSummonFree();
        };
    }
    static Consumer<GameContext> tryParseSearchAndCastSummonFree(String text) {
        Matcher m = SEARCH_AND_CAST_SUMMON_FREE_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        String element = m.group("element");
        String costStr = m.group("cost");
        int maxCost = costStr != null ? Integer.parseInt(costStr) : -1;
        return ctx -> {
            ctx.logEntry("Effect: Search deck for " + element + " Summon"
                    + (maxCost >= 0 ? " (cost " + maxCost + " or less)" : "") + ", cast for free or Break Zone");
            ctx.searchAndCastSummonFreeFromDeck(maxCost, element);
        };
    }
    /** Parses "Your opponent shows/reveals his/her hand". */
    static Consumer<GameContext> tryParseOpponentRevealHand(String text) {
        Matcher m = OPPONENT_REVEAL_HAND_PATTERN.matcher(text);
        if (!m.find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals hand");
            ctx.revealOpponentHand();
        };
    }
    /**
     * Parses one or more "If it is/has [cond], [action]" clauses following a
     * "Reveal the top card of your deck" header.
     * Each action is either a card-referencing op code or a standalone effect
     * parsed by {@link #parse}.
     */
    static Consumer<GameContext> tryParseChooseFwdRevealCostParity(String text) {
        Matcher m = CHOOSE_FWD_REVEAL_COST_PARITY_PATTERN.matcher(text.trim());
        if (!m.matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Forward, reveal top card — even cost→bounce, odd cost→4000 damage + dull + freeze");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false, false, false, null, null, null, false,
                    -1, null, -1, null, true, false, false, null, null, null, null, false, null, false);
            if (ts.isEmpty()) return;
            ForwardTarget t = ts.get(0);
            ctx.revealTopDeckCostParityEffect(
                ctx2 -> {
                    ctx2.logEntry("Even cost — returning chosen Forward to owner's hand");
                    if (t.isP1()) ctx2.returnP1ForwardToHand(t.idx());
                    else          ctx2.returnP2ForwardToHand(t.idx());
                },
                ctx2 -> {
                    ctx2.logEntry("Odd cost — dealing 4000 damage, dulling and freezing chosen Forward");
                    ctx2.damageTarget(t, 4000);
                    ctx2.dullAndFreezeTarget(t);
                }
            );
        };
    }
    static Consumer<GameContext> tryParseRevealTopDeck(String text, CardData source) {
        Matcher header = REVEAL_TOP_DECK_HEADER.matcher(text);
        if (!header.find()) return null;
        boolean opponentDeck = header.group("who").toLowerCase(java.util.Locale.ROOT).contains("opponent");
        List<RevealClause> clauses = new ArrayList<>();
        Matcher m = REVEAL_CLAUSE_PATTERN.matcher(text);
        while (m.find()) {
            RevealClause clause = buildRevealClause(
                m.group("cond").trim(), m.group("action").trim(), source);
            if (clause == null) return null;
            clauses.add(clause);
        }
        if (clauses.isEmpty()) return null;
        String whose = opponentDeck ? "opponent's" : "your";
        return ctx -> {
            ctx.logEntry("Effect: Reveal top card of " + whose + " deck (" + clauses.size() + " clause(s))");
            ctx.revealTopDeckCard(clauses, opponentDeck);
        };
    }
    static Consumer<GameContext> tryParseEachPlayerRevealCharacterMayPlay(String text) {
        Matcher m = EACH_PLAYER_REVEAL_CHARACTER_MAY_PLAY.matcher(text);
        if (!m.find()) return null;
        String typeStr = m.group("type").trim();
        java.util.function.Predicate<CardData> eligible = card -> meetsTypeCheck(card, typeStr);
        return ctx -> {
            ctx.logEntry("Effect: Each player reveals top card, may play if " + typeStr);
            ctx.revealEachPlayerTopDeckMayPlay(eligible);
        };
    }
    static Consumer<GameContext> tryParseNameJobOrCategoryRevealAddToHand(String text) {
        Matcher m = NAME_JOB_OR_CATEGORY_REVEAL_ADD_TO_HAND.matcher(text);
        if (!m.find()) return null;
        int reveal = Integer.parseInt(m.group("reveal"));
        int maxAdd = Integer.parseInt(m.group("maxAdd"));
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Job or Category — reveal top " + reveal + ", add up to " + maxAdd + " matching Characters to hand");
            String[] choice = ctx.selectJobOrCategory("Name 1 Job or Category:");
            if (choice == null || choice[1] == null || choice[1].isBlank()) return;
            ctx.logEntry("Named " + choice[0] + ": " + choice[1]);
            String jobFilter = "job".equalsIgnoreCase(choice[0]) ? choice[1] : null;
            String catFilter = "category".equalsIgnoreCase(choice[0]) ? choice[1] : null;
            ctx.revealTopAddUpToMatchingRestBottom(reveal, maxAdd, jobFilter, catFilter, null, null);
        };
    }
    static Consumer<GameContext> tryParseRevealTopNCategoryToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_CATEGORY_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        String cat = m.group("cat");
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add 1 Category " + cat + " to hand, rest to bottom");
            ctx.revealTopAddUpToMatchingRestBottom(n, 1, null, cat, null, null);
        };
    }
    /**
     * Parses "reveal the top N cards … Add 1 Job X [or Card Name Y] … bottom of your deck."
     * Splits the captured filter terms into a job filter and a card-name filter (each
     * bar-separated when multiple terms of the same kind appear) and forwards them to
     * {@link GameContext#revealTopAddUpToMatchingRestBottom}.
     */
    static Consumer<GameContext> tryParseRevealTopNJobOrNameToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_JOB_OR_NAME_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        StringBuilder jobs  = new StringBuilder();
        StringBuilder names = new StringBuilder();
        appendFilterTerm(jobs, names, m.group("first"));
        appendFilterTerm(jobs, names, m.group("second"));
        String jobFilter      = jobs.length()  > 0 ? jobs.toString()  : null;
        String cardNameFilter = names.length() > 0 ? names.toString() : null;
        if (jobFilter == null && cardNameFilter == null) return null;
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add 1 ("
                    + (jobFilter      != null ? "Job " + jobFilter           : "")
                    + (jobFilter != null && cardNameFilter != null ? " | " : "")
                    + (cardNameFilter != null ? "Card Name " + cardNameFilter : "")
                    + ") to hand, rest to bottom");
            ctx.revealTopAddUpToMatchingRestBottom(n, 1, jobFilter, null, cardNameFilter, null);
        };
    }
    static Consumer<GameContext> tryParseRevealTopNTypeToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_TYPE_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        // Normalise plural → singular (e.g. "Monsters" → "Monster")
        String typeFilter = m.group("type").replaceAll("(?i)s$", "");
        String costRaw = m.group("cost");
        int maxCost = costRaw != null ? Integer.parseInt(costRaw) : -1;
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max + " " + typeFilter
                    + (maxCost >= 0 ? " of cost " + maxCost + " or less" : "") + " to hand, rest to bottom");
            ctx.revealTopAddUpToMatchingRestBottom(n, max, null, null, null, typeFilter, maxCost);
        };
    }
    static Consumer<GameContext> tryParseRevealTopNElementToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_ELEMENT_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        String normElement = cap(m.group("element"));
        String cat = m.group("cat");
        if (cat != null) {
            // "Add M [Element] or Category [X] card" — element and category are alternatives.
            // The element is a disjunct (orElementFilter), not an AND-gate — "Water OR Category X".
            return ctx -> {
                ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max + " " + normElement
                        + " or Category " + cat + " to hand, rest to bottom");
                ctx.revealTopAddUpToMatchingRestBottom(n, max, null, cat, null, null, -1, null, normElement);
            };
        }
        String typeRaw = m.group("type");
        String typeFilter = typeRaw != null ? cap(typeRaw.replaceAll("(?i)s$", "")) : null;
        // "Add M [Element] [Type]" — the element is an AND-gate on the type (e.g. "Fire Forward").
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max + " " + normElement
                    + (typeFilter != null ? " " + typeFilter : " card") + "(s) to hand, rest to bottom");
            ctx.revealTopAddUpToMatchingRestBottom(n, max, null, null, null, typeFilter, -1, normElement);
        };
    }
    static Consumer<GameContext> tryParseRevealTopNAddUpToExcludingNameRestBz(String text) {
        Matcher m = REVEAL_TOP_N_ADD_UP_TO_EXCLUDING_NAME_REST_BZ.matcher(text.trim());
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        String name = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max
                    + " (excl. Card Name " + name + ") to hand, rest to Break Zone");
            ctx.revealTopAddUpToExcludingNameRestBz(n, max, name);
        };
    }
    static Consumer<GameContext> tryParseRevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(String text) {
        Matcher m = REVEAL_ADD_TYPE_TO_HAND_OR_PLAY_JOB_TYPE_ONTO_FIELD_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n        = Integer.parseInt(m.group("n"));
        int handMax  = Integer.parseInt(m.group("handmax"));
        String handType  = cap(m.group("handtype"));
        int fieldMax = Integer.parseInt(m.group("fieldmax"));
        String fieldJob  = m.group("fieldjob") != null ? m.group("fieldjob").trim() : null;
        String fieldType = cap(m.group("fieldtype"));
        String logDesc = "Reveal top " + n + " — add up to " + handMax + " " + handType
                + " to hand OR play up to " + fieldMax
                + (fieldJob != null ? " Job " + fieldJob + " " : " ") + fieldType + " onto field; rest to bottom";
        return ctx -> {
            ctx.logEntry("Effect: " + logDesc);
            ctx.revealTopNAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(n, handMax, handType, fieldMax, fieldJob, fieldType);
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckOptionallyBreak(String text) {
        if (!LOOK_TOP_DECK_OPTIONALLY_BREAK.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Look at top of deck — may put into Break Zone");
            ctx.lookAtTopDeck(new LookConfig(1, LookConfig.LookAction.BREAK_OR_KEEP));
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckBottomOrKeep(String text) {
        if (!LOOK_TOP_DECK_BOTTOM_OR_KEEP.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Look at top of deck — may place at bottom");
            ctx.lookAtTopDeck(new LookConfig(1, LookConfig.LookAction.BOTTOM_OR_KEEP));
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckReturnTopOrdered(String text) {
        Matcher m = LOOK_TOP_DECK_RETURN_TOP_ORDERED.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — return to top in any order");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.RETURN_TOP_ORDERED));
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckAddToHandRestBottom(String text) {
        Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        boolean reveal = isRevealWording(m.group("verb"));
        Consumer<GameContext> look = ctx -> {
            ctx.logEntry("Effect: " + (reveal ? "Reveal" : "Look at") + " top " + count
                    + " card(s) — add 1 to hand, return rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(
                    count, LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM, null, reveal));
        };
        String tail = text.substring(m.end()).trim();
        if (tail.isEmpty()) return look;
        // Golem 23-064R also continues past this clause, with a rider that is not understood yet;
        // reporting the whole ability as unparsed beats running half of it.
        if (!ADDED_CARD_EX_BURST_RIDER.matcher(tail).matches()) return null;
        return look.andThen(ctx -> {
            ctx.logEntry("Effect: added card's EX Burst may be put on the stack");
            ctx.triggerExBurstOfCardAddedToHand();
        });
    }
    static Consumer<GameContext> tryParseLookTopDeckAddToHandOneToBreakRestBottom(String text) {
        Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — add 1 to hand, 1 to Break Zone, return rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM));
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckAddToHandRestBreak(String text) {
        Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_REST_BREAK.matcher(text);
        if (!m.find()) return null;
        int     count   = Integer.parseInt(m.group("count"));
        String  element = m.group("element");
        boolean reveal  = isRevealWording(m.group("verb"));
        String elemLabel = element != null ? " (" + element + ")" : "";
        return ctx -> {
            ctx.logEntry("Effect: " + (reveal ? "Reveal" : "Look at") + " top " + count
                    + " card(s) — add 1" + elemLabel + " to hand, rest to Break Zone");
            ctx.lookAtTopDeck(new LookConfig(
                    count, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, element, reveal));
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckTopOrBottom(String text, CardData source) {
        Matcher m = LOOK_TOP_DECK_TOP_OR_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        Consumer<GameContext> look = ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — return to top or bottom in any order");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.TOP_OR_BOTTOM_ORDERED));
        };
        return appendThenClause(look, text.substring(m.end()), source);
    }
    static Consumer<GameContext> tryParseLookTopDeckPickOneTopRestBottom(String text) {
        Matcher m = LOOK_TOP_DECK_PICK_ONE_TOP_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — pick 1 on top, rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.PICK_ONE_TOP_REST_BOTTOM));
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckPeek(String text) {
        Matcher m = LOOK_TOP_DECK_PEEK.matcher(text);
        if (!m.find()) return null;
        String countStr = m.group("count");
        int count = (countStr != null) ? Integer.parseInt(countStr) : 1;
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) of deck");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.PEEK));
        };
    }
    static Consumer<GameContext> tryParseRevealTopBreakSameCostAddToHand(String text) {
        if (!REVEAL_TOP_BREAK_SAME_COST_ADD_TO_HAND.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Reveal top of deck — break all opponent Forwards with same cost, add revealed card to hand");
            ctx.revealTopBreakSameCostAddToHand();
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckCastSummonFreeRestBottom(String text, int xValue) {
        Matcher m = LOOK_TOP_DECK_CAST_SUMMON_FREE_REST_BOTTOM.matcher(text.trim());
        if (!m.find()) return null;
        String countStr = m.group("count");
        String costStr  = m.group("cost");
        final int count   = countStr.equalsIgnoreCase("X") ? xValue : Integer.parseInt(countStr);
        final int maxCost = costStr.equalsIgnoreCase("X")  ? xValue : Integer.parseInt(costStr);
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — reveal/cast 1 Summon (cost " + maxCost + " or less) for free, shuffle rest to bottom");
            ctx.lookAtTopDeckCastSummonFreeRestBottom(count, maxCost);
        };
    }
    static Consumer<GameContext> tryParseRemoveTopOfDeckFromGame(String text, CardData source) {
        Matcher m = REMOVE_TOP_OF_DECK_FROM_GAME.matcher(text);
        if (!m.find()) return null;
        String countStr = m.group("count");
        int count = (countStr != null) ? Integer.parseInt(countStr) : 1;
        return ctx -> {
            ctx.logEntry("Effect: Remove top " + count + " card(s) of deck from game");
            // Recorded against the source so a later ability on the same card can call them back
            // ("cards removed by the previous effect" — Libroarian 8-084R).
            ctx.removeTopCardsOfDeckFromGame(count, source);
        };
    }
    static Consumer<GameContext> tryParseShuffleDeck(String text) {
        if (!SHUFFLE_DECK.matcher(text).find()) return null;
        return ctx -> ctx.shuffleDeck();
    }
    static Consumer<GameContext> tryParseOppRfpTopDeckCastable(String text) {
        Matcher m = OPP_RFP_TOPDECK_CASTABLE.matcher(text);
        if (!m.find()) return null;
        String costClause = m.group("cost") != null ? m.group("cost") : "";
        Matcher r = Pattern.compile("(?i)reduced\\s+by\\s+(\\d+)").matcher(costClause);
        final int reduction = r.find() ? Integer.parseInt(r.group(1)) : 0;
        final boolean anyElement = costClause.toLowerCase(java.util.Locale.ROOT).contains("any element");
        return ctx -> {
            ctx.logEntry("Effect: Opponent removes top deck card from game — you may cast it as your own"
                    + (reduction > 0 ? " (cost -" + reduction + ")" : "")
                    + (anyElement ? " [any Element]" : ""));
            ctx.opponentRfpTopDeckMakeCastable(reduction, anyElement);
        };
    }
    static Consumer<GameContext> tryParseOpponentCannotSearchThisTurn(String text) {
        if (!OPPONENT_CANNOT_SEARCH_THIS_TURN.matcher(text).find()) return null;
        return ctx -> ctx.setOpponentCannotSearchThisTurn();
    }
    static Consumer<GameContext> tryParseDualSearchJobAndTypeDontShareElements(String text) {
        Matcher m = DUAL_SEARCH_JOB_AND_TYPE_DONT_SHARE_ELEMENTS.matcher(text);
        if (!m.find()) return null;
        String job  = m.group("job").trim();
        String type = m.group("type").trim();
        return ctx -> {
            ctx.logEntry("Effect: Dual search — Job " + job + " and " + type + " (don't share elements) → hand");
            ctx.searchDeckJobAndTypeDontShareElements(job, type);
        };
    }
    static Consumer<GameContext> tryParseSearchElementOrCategoryCharsDiffCost(String text) {
        Matcher m = SEARCH_ELEMENT_OR_CATEGORY_CHARS_DIFF_COST.matcher(text);
        if (!m.find()) return null;
        String element  = m.group("element").trim();
        String category = m.group("category").trim();
        return ctx -> {
            ctx.logEntry("Effect: Search — 2 " + element + " Characters, 2 Category " + category
                    + " Characters, or 1 of each, each with a different cost → hand");
            ctx.searchDeckElementOrCategoryCharsDifferentCost(element, category);
        };
    }
    /** Parses "Search for N [Element] Summons each with a different cost and add them to your hand." */
    static Consumer<GameContext> tryParseSearchNElementSummonsDiffCost(String text) {
        Matcher m = SEARCH_N_ELEM_SUMMONS_DIFF_COST.matcher(text);
        if (!m.find()) return null;
        int    count   = Integer.parseInt(m.group("count"));
        String element = m.group("element").trim();
        return ctx -> {
            ctx.logEntry("Effect: Search — " + count + " " + element + " Summons, each different cost → hand");
            ctx.searchDeckNElementSummonsDifferentCost(count, element);
        };
    }
    static Consumer<GameContext> tryParseSearchDeck(String text, CardData source, int xValue) {
        Matcher m = SEARCH_DECK_PATTERN.matcher(text);
        if (!m.find()) return null;

        // --- Card name filter ---
        String cardNameFilter = null;
        String bracketName = m.group("bracketname");
        if (bracketName != null) {
            Matcher nm = CARD_NAME_BRACKET_PATTERN.matcher(bracketName);
            if (nm.find()) cardNameFilter = nm.group(1).trim();
        } else {
            String writtenNames = m.group("cardnames");
            if (writtenNames != null) {
                cardNameFilter = splitCardNameList(writtenNames);
            } else {
                String written = m.group("cardname");
                if (written != null) cardNameFilter = written.trim();
            }
        }

        // --- Job filter ---
        String jobFilter = null;
        String bracketJob = m.group("bracketjob");
        if (bracketJob != null) {
            Matcher jm = JOB_BRACKET_PATTERN.matcher(bracketJob);
            if (jm.find()) jobFilter = jm.group(1).trim();
        } else {
            String writtenJob = m.group("jobnm");
            if (writtenJob != null) {
                // "Chocobo or Job Moogle or Job Ninja" → "Chocobo|Moogle|Ninja"
                String[] parts = writtenJob.trim().split("(?i)\\s+or\\s+Job\\s+");
                jobFilter = String.join("|", parts);
            }
        }

        // --- "Job X or Card Name Y" — sets both filters; OR logic applied at match time ---
        String jobnmOr = m.group("jobnmor");
        if (jobnmOr != null) {
            jobFilter = jobnmOr.trim();
            String cnameOr = m.group("cnameor");
            if (cnameOr != null) cardNameFilter = splitCardNameList(cnameOr);
        }

        // --- "Card Name X [, Card Name Y] or Job Z" — sets both filters; OR logic at match time ---
        String cnameJobnmOr = m.group("cnamejobnmor");
        if (cnameJobnmOr != null) {
            cardNameFilter = splitCardNameList(cnameJobnmOr);
            String jobNmCnameOr = m.group("jobnmcnameor");
            if (jobNmCnameOr != null) jobFilter = jobNmCnameOr.trim();
        }

        // --- Category filter ---
        String categoryFilter = m.group("category") != null ? m.group("category").trim() : null;
        String catAfterJob = m.group("catafterjob");
        if (catAfterJob != null && categoryFilter == null) categoryFilter = catAfterJob.trim();

        // --- Element filter (e.g. "Fire or Earth" → "Fire|Earth") ---
        // preelems captures elements that precede a Job/Name filter (e.g. "Fire Job Knight");
        // elements captures elements that follow the filter (classic ordering).
        String preElemsRaw = m.group("preelems");
        String postElemsRaw = m.group("elements");
        String elementsRaw = preElemsRaw != null ? preElemsRaw : postElemsRaw;
        String elementFilter = elementsRaw != null
                ? elementsRaw.trim().replaceAll("(?i)\\s+or\\s+", "|") : null;

        // --- Exclude name (other than Card Name X) ---
        String excludeName = m.group("excludename") != null ? m.group("excludename").trim() : null;

        // --- Exclude element (other than Light or Dark) ---
        String excludeElemRaw = m.group("excludeelem");
        String excludeElem = excludeElemRaw != null ? excludeElemRaw.trim() : null;

        // --- Type flags ---
        String  targets  = m.group("targets");
        boolean anyType  = targets == null || targets.toLowerCase().startsWith("card");
        String  tgtLower;
        if (anyType || targets == null) { tgtLower = ""; }
        else                            { tgtLower = targets.toLowerCase(); }
        boolean inclForwards = anyType || tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = anyType || tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = anyType || tgtLower.contains("monster") || tgtLower.contains("character");
        boolean inclSummons  = anyType || tgtLower.contains("summon");

        // --- Type exclusion (e.g. "card other than a Backup") ---
        String excludeTypeRaw = m.group("excludetype");
        if (excludeTypeRaw != null) {
            String etl = excludeTypeRaw.toLowerCase();
            if (etl.equals("forward")   || etl.equals("character")) inclForwards = false;
            if (etl.equals("backup")    || etl.equals("character")) inclBackups  = false;
            if (etl.equals("monster")   || etl.equals("character")) inclMonsters = false;
            if (etl.equals("summon"))                                inclSummons  = false;
        }

        // --- Cost filter ---
        String costStr = m.group("cost");
        int    costVal = costStr == null ? -1 : Integer.parseInt(costStr);
        String costCmpRaw = m.group("costcmp");
        // "of cost 5 or 6" — numeric second value → encode as "or_6" for meetsCostConstraint
        String costCmp = (costCmpRaw != null && costCmpRaw.matches("\\d+"))
                ? "or_" + costCmpRaw : costCmpRaw;

        // --- Count ---
        String countStr = m.group("count");
        int count = (countStr != null) ? Integer.parseInt(countStr) : 1;

        // --- Destination ---
        String destText   = m.group("destination").toLowerCase();
        boolean entersDull = destText.contains("dull");
        String destination = destText.contains("hand")     ? "hand"
                           : destText.contains("field")    ? "field"
                           : destText.contains("break")    ? "breakZone"
                           : destText.contains("on top")   ? "deckTop"
                           :                                 "underTop";

        // --- Warp trait filter ("card with Warp") ---
        boolean requireWarp = m.group("withwarp") != null;

        // Build log label
        StringBuilder filterDesc = new StringBuilder();
        if (cardNameFilter  != null) filterDesc.append(" [Name ").append(cardNameFilter).append("]");
        if (jobFilter       != null) filterDesc.append(" [Job ").append(jobFilter).append("]");
        if (categoryFilter  != null) filterDesc.append(" [Cat ").append(categoryFilter).append("]");
        if (elementFilter   != null) filterDesc.append(" [").append(elementsRaw).append("]");
        if (excludeName     != null) filterDesc.append(" [not ").append(excludeName).append("]");
        if (excludeElem     != null) filterDesc.append(" [not ").append(excludeElem).append("]");
        if (requireWarp     )        filterDesc.append(" [with Warp]");
        String typeDesc  = (targets != null && !anyType) ? " " + targets : "";
        String costLabel = CardFilters.formatCostFilterLabel(costVal, costCmp);

        // Secondary effect: text following this search clause (e.g. ". Gain 《C》.")
        String afterSearch = text.substring(m.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
        Consumer<GameContext> secondary = afterSearch.isEmpty() ? null : parse(afterSearch, source, xValue);

        final String fName = cardNameFilter, fJob = jobFilter, fCat = categoryFilter;
        final String fElem = elementFilter, fExclude = excludeName, fExclElem = excludeElem;
        final boolean fwd = inclForwards, bk = inclBackups, mn = inclMonsters, sm = inclSummons;
        final int fCount = count;
        final boolean fDull = entersDull;
        final boolean fWarp = requireWarp;
        return ctx -> {
            ctx.logEntry("Effect: Search deck for " + fCount + filterDesc + typeDesc + costLabel + " → " + destination + (fDull ? " dull" : ""));
            ctx.searchDeckForCard(fwd, bk, mn, sm, costVal, costCmp, fName, fJob, fCat, fElem, fExclude, fExclElem, destination, fCount, fDull, fWarp);
            if (secondary != null) secondary.accept(ctx);
        };
    }
}
