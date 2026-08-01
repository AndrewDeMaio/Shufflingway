package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

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
 * Choose parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverChoose {

	private ActionResolverChoose() {}

    /**
     * Parses "[if cond,] Select N of the M following actions. "a" "b" ...".
     * Returns an effect that asks the player to choose {@code select} of the quoted
     * sub-actions (via {@link GameContext#chooseActions}), then re-parses and applies
     * each chosen sub-action. Returns {@code null} if the text is not this shape.
     */
    static Consumer<GameContext> tryParseSelectFollowingActions(String text, CardData source) {
        Matcher m = SELECT_FOLLOWING_ACTIONS.matcher(text);
        if (!m.find()) return null;

        final boolean baseUpTo      = m.group("upTo") != null;
        final int     baseSelect    = Integer.parseInt(m.group("select"));
        String actionsRaw = m.group("actions");

        // Detect inline conditional upgrade:
        // "If you control N or more [E] [T], select [up to] M of the K following actions instead."
        final boolean hasCondUpgrade;
        final int     condMinCount;
        final String  condElem;
        final boolean condInclFwd, condInclBkp, condInclMon;
        final boolean condUpTo;
        final int     condSelect;

        Matcher upgradeM = SELECT_FOLLOWING_ACTIONS_CONDITIONAL_UPGRADE.matcher(actionsRaw);
        if (upgradeM.find()) {
            hasCondUpgrade = true;
            condMinCount   = Integer.parseInt(upgradeM.group("condCount"));
            condElem       = upgradeM.group("condElement");
            String ct      = upgradeM.group("condType").toLowerCase();
            condInclFwd    = ct.startsWith("forward") || ct.startsWith("character");
            condInclBkp    = ct.startsWith("backup")  || ct.startsWith("character");
            condInclMon    = ct.startsWith("monster")  || ct.startsWith("character");
            condUpTo       = upgradeM.group("condUpTo") != null;
            condSelect     = Integer.parseInt(upgradeM.group("condSelect"));
            actionsRaw     = actionsRaw.substring(upgradeM.end());
        } else {
            hasCondUpgrade = false;
            condMinCount   = 0; condElem = null;
            condInclFwd    = false; condInclBkp = false; condInclMon = false;
            condUpTo       = false; condSelect   = 0;
        }

        // Detect an opponent-hand-size upgrade:
        // "If your opponent has [no|N cards or less] cards in their hand, select [up to] M ... instead."
        final boolean hasHandUpgrade;
        final int     handUpgThreshold;
        final boolean handUpgUpTo;
        final int     handUpgSelect;

        Matcher handUpM = SELECT_FOLLOWING_ACTIONS_HAND_UPGRADE.matcher(actionsRaw);
        if (handUpM.find()) {
            hasHandUpgrade   = true;
            handUpgThreshold = handUpM.group("handCount") != null ? Integer.parseInt(handUpM.group("handCount")) : 0;
            handUpgUpTo      = handUpM.group("handUpTo") != null;
            handUpgSelect    = Integer.parseInt(handUpM.group("handSelect"));
            actionsRaw       = actionsRaw.substring(handUpM.end());
        } else {
            hasHandUpgrade   = false;
            handUpgThreshold = 0; handUpgUpTo = false; handUpgSelect = 0;
        }

        Matcher qm = SELECT_FOLLOWING_QUOTED_ACTION.matcher(actionsRaw);
        List<String> actions = new ArrayList<>();
        while (qm.find()) actions.add(qm.group(1).trim());
        if (actions.isEmpty()) return null;

        return ctx -> {
            int     effSelect = baseSelect;
            boolean effUpTo   = baseUpTo;
            if (hasCondUpgrade
                    && ctx.selfFieldCount(condElem, condInclFwd, condInclBkp, condInclMon) >= condMinCount) {
                effSelect = condSelect;
                effUpTo   = condUpTo;
            }
            if (hasHandUpgrade && ctx.opponentHandSize() <= handUpgThreshold) {
                effSelect = handUpgSelect;
                effUpTo   = handUpgUpTo;
            }
            List<String> chosen = ctx.chooseActions(source, actions, effSelect, effUpTo);
            if (chosen == null || chosen.isEmpty()) {
                ctx.logEntry("Select actions — none chosen");
                return;
            }
            for (String actionText : chosen) {
                Consumer<GameContext> effect = parse(actionText, source);
                if (effect == null) {
                    ctx.logEntry("Select actions — unrecognized: " + actionText);
                } else {
                    ctx.logEntry((ctx.isP1() ? "Selected: " : "AI selected ") + actionText);
                    effect.accept(ctx);
                }
            }
        };
    }
    /**
     * Parses "Choose [up to] N [condition] [element] [targets] [of cost X] [control] [zone]
     * [sep] followup".
     *
     * <p>Supported target types: Forward(s), Forward(s) or Monster(s), Backup(s), Character(s).
     * <p>Supported followup actions:
     * <ul>
     *   <li>"Deal [it|them] N damage"                        — fixed damage to each chosen target</li>
     *   <li>"Deal it damage equal to the highest power Forward you control" — damage = highest P1 forward power</li>
     *   <li>"Deal it damage equal to &lt;name&gt;'s power"          — damage = named field card's power</li>
     *   <li>"Deal it damage equal to half of &lt;name&gt;'s power"  — damage = floor(named power / 2) to nearest 1000</li>
     *   <li>"Deal it damage equal to its power [minus N]"    — damage = target's own power (minus N)</li>
     *   <li>"Dull it/them"                 — dulls each chosen target</li>
     *   <li>"Freeze it/them"               — freezes each chosen target</li>
     *   <li>"Dull it/them and freeze…"     — dulls and freezes each chosen target</li>
     *   <li>"Break it/them"                — breaks each chosen target</li>
     *   <li>"Remove it/them from the game" — removes each chosen target from the game</li>
     *   <li>"Play it/them onto the field"  — moves chosen targets from their zone onto the field</li>
     *   <li>"Add it/them to your hand"     — moves chosen targets to P1's hand</li>
     *   <li>"Return it to its owner's hand" — returns chosen forward to its owner's hand</li>
     *   <li>"Return it to your hand"        — returns chosen forward to P1's hand</li>
     *   <li>"it cannot block this turn"    — marks chosen forward as ineligible to block this turn</li>
     *   <li>"If possible, it must block this turn" — marks chosen forward as required to block if eligible</li>
     *   <li>"Put it at the top or bottom of its owner's deck" — player chooses placement</li>
     * </ul>
     */
    static Consumer<GameContext> tryParseChooseOneEach(String text, CardData source) {
        Matcher m = CHOOSE_ONE_EACH_PATTERN.matcher(text);
        if (!m.find()) return null;

        int    count1     = Integer.parseInt(m.group("count1"));
        String targets1   = m.group("targets1");
        String tgt1Lower  = targets1.toLowerCase();
        boolean fwd1 = tgt1Lower.contains("forward") || tgt1Lower.contains("character");
        boolean bak1 = tgt1Lower.contains("backup")  || tgt1Lower.contains("character");
        boolean mon1 = tgt1Lower.contains("monster") || tgt1Lower.contains("character");

        int    count2     = Integer.parseInt(m.group("count2"));
        String targets2   = m.group("targets2");
        String tgt2Lower  = targets2.toLowerCase();
        boolean fwd2 = tgt2Lower.contains("forward") || tgt2Lower.contains("character");
        boolean bak2 = tgt2Lower.contains("backup")  || tgt2Lower.contains("character");
        boolean mon2 = tgt2Lower.contains("monster") || tgt2Lower.contains("character");

        String followup  = m.group("followup").trim();
        String logPrefix = "Choose " + count1 + " " + targets1 + " (yours) and "
                + count2 + " " + targets2 + " (opponent)";

        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(logPrefix + " — Return to owner's hand");
                List<ForwardTarget> selfTs = selectTargets(ctx, count1, false,
                        false, true, null, null, null, false, -1, null, -1, null,
                        fwd1, bak1, mon1, null, null, null, null, false, null, false);
                List<ForwardTarget> oppTs = selectTargets(ctx, count2, false,
                        true, false, null, null, null, false, -1, null, -1, null,
                        fwd2, bak2, mon2, null, null, null, null, false, null, false);
                List<ForwardTarget> all = new ArrayList<>(selfTs);
                all.addAll(oppTs);
                returnTargetsToOwnersHand(ctx, all);
            };
        }

        if (FOLLOWUP_EACH_FORWARD_MUTUAL_POWER_DAMAGE.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(logPrefix + " — Each deals damage equal to its power to the other");
                List<ForwardTarget> selfTs = selectTargets(ctx, count1, false,
                        false, true, null, null, null, false, -1, null, -1, null,
                        fwd1, bak1, mon1, null, null, null, null, false, null, false);
                List<ForwardTarget> oppTs = selectTargets(ctx, count2, false,
                        true, false, null, null, null, false, -1, null, -1, null,
                        fwd2, bak2, mon2, null, null, null, null, false, null, false);
                if (selfTs.isEmpty() || oppTs.isEmpty()) return;
                ForwardTarget selfT = selfTs.get(0);
                ForwardTarget oppT  = oppTs.get(0);
                // Snapshot both powers before either damage is applied
                int selfPower = Math.max(0, ctx.effectiveTargetPower(selfT));
                int oppPower  = Math.max(0, ctx.effectiveTargetPower(oppT));
                ctx.logEntry("Mutual damage: self Forward (" + selfPower + ") ↔ opp Forward (" + oppPower + ")");
                ctx.damageTarget(selfT, oppPower);
                ctx.damageTarget(oppT,  selfPower);
            };
        }

        Matcher btpM = FORMER_BOOST_THEN_POWER_DAMAGE_TO_LATTER.matcher(followup);
        if (btpM.find()) {
            int boost = Integer.parseInt(btpM.group("boost"));
            EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
            return ctx -> {
                ctx.logEntry(logPrefix + " — boost former +" + boost + ", deal its power to latter");
                List<ForwardTarget> selfTs = selectTargets(ctx, count1, false,
                        false, true, null, null, null, false, -1, null, -1, null,
                        fwd1, bak1, mon1, null, null, null, null, false, null, false);
                List<ForwardTarget> oppTs = selectTargets(ctx, count2, false,
                        true, false, null, null, null, false, -1, null, -1, null,
                        fwd2, bak2, mon2, null, null, null, null, false, null, false);
                if (selfTs.isEmpty() || oppTs.isEmpty()) return;
                ctx.boostTarget(selfTs.get(0), boost, noTraits);
                int power = Math.max(0, ctx.effectiveTargetPower(selfTs.get(0)));
                ctx.logEntry("Former power after boost: " + power + " → dealing to latter");
                ctx.damageTarget(oppTs.get(0), power);
            };
        }

        return null;
    }
    static Consumer<GameContext> tryParseChooseFormerLatter(String text, CardData source) {
        Matcher m = CHOOSE_FORMER_LATTER_PATTERN.matcher(text);
        if (!m.find()) return null;

        String effects      = m.group("effects").trim();
        String effectsLower = effects.toLowerCase(java.util.Locale.ROOT);
        if (!effectsLower.contains("the former") || !effectsLower.contains("the latter")) return null;

        // Parse target descriptors (shared for all effect paths below)
        boolean upTo1  = m.group("upTo1") != null;
        int     count1 = Integer.parseInt(m.group("count1"));
        String  desc1  = m.group("desc1").trim();

        boolean upTo2    = m.group("upTo2") != null;
        int     count2   = Integer.parseInt(m.group("count2"));
        String  desc2Raw = m.group("desc2").trim();

        boolean excludeFirstChosen = false;
        String  desc2 = desc2Raw;
        if (desc2Raw.toLowerCase(java.util.Locale.ROOT).startsWith("other ")) {
            excludeFirstChosen = true;
            desc2 = desc2Raw.substring(6).trim();
        }

        TargetDesc td1 = parseTargetDesc(desc1);
        TargetDesc td2 = parseTargetDesc(desc2);

        // Special case: desc2 has a dynamic cost constraint on a BZ Backup that TARGET_DESC_PATTERN
        // cannot represent (e.g. "Backup with a cost equal to or less than that Forward in your BZ").
        // Parse effects normally and supply the cost filter at execution time.
        if (td2 == null && td1 != null && DESC_BZ_BACKUP_COST_RELATIVE.matcher(desc2).matches()) {
            String kLabel = "Choose " + (upTo1 ? "up to " : "") + count1 + " " + desc1
                          + " and " + (upTo2 ? "up to " : "") + count2 + " " + desc2Raw;
            int kLatterIdx = effectsLower.indexOf("the latter");
            int kAndIdx    = effects.lastIndexOf(" and ", kLatterIdx);
            if (kAndIdx >= 0) {
                String kFmrEff = effects.substring(0, kAndIdx).trim()
                        .replaceAll("(?i)\\bthe\\s+former\\b", "it").replaceAll("\\.$", "").trim();
                String kLtrEff = effects.substring(kAndIdx + 5).trim()
                        .replaceAll("(?i)\\bthe\\s+latter\\b", "it").replaceAll("\\.$", "").trim();
                BiConsumer<GameContext, List<ForwardTarget>> kFmrAct =
                        parseFormerLatterGroupAction(kFmrEff);
                BiConsumer<GameContext, List<ForwardTarget>> kLtrAct =
                        parseFormerLatterGroupAction(kLtrEff);
                if (kFmrAct != null && kLtrAct != null) {
                    final TargetDesc kTd1 = td1;
                    final BiConsumer<GameContext, List<ForwardTarget>>
                            fkFmr = kFmrAct, fkLtr = kLtrAct;
                    return ctx -> {
                        ctx.logEntry(kLabel);
                        List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                                kTd1.opponentOnly(), kTd1.selfOnly(),
                                kTd1.condition(), kTd1.element(), null, false,
                                kTd1.costVal(), kTd1.costCmp(), -1, null,
                                kTd1.fwd(), kTd1.bkp(), kTd1.mon(),
                                null, null, null, kTd1.excludeName(), false, null, false);
                        if (ts1.isEmpty()) return;
                        ForwardTarget fwdTgt = ts1.get(0);
                        CardData fwdCard = fwdTgt.isP1()
                                ? ctx.p1Forward(fwdTgt.idx()) : ctx.p2Forward(fwdTgt.idx());
                        int formerCost = fwdCard.cost();
                        List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                                false, true, null, null, "in your Break Zone", false,
                                formerCost, "less", -1, null,
                                false, true, false,
                                null, null, null, null, false, null, false);
                        fkFmr.accept(ctx, ts1);
                        fkLtr.accept(ctx, ts2);
                    };
                }
            }
            return null;
        }

        if (td1 == null || td2 == null) return null;

        boolean fExcludeFirst = excludeFirstChosen;
        String  fDesc2Static  = td2.excludeName();
        String label = "Choose " + (upTo1 ? "up to " : "") + count1 + " " + desc1
                     + " and " + (upTo2 ? "up to " : "") + count2 + " " + desc2Raw;

        // Special case: "The former gains +N power until end of turn. Then, the former deals
        // damage equal to its power to the latter." — boost, then deal boosted power as damage.
        Matcher btpM = FORMER_BOOST_THEN_POWER_DAMAGE_TO_LATTER.matcher(effects);
        if (btpM.find()) {
            int boost = Integer.parseInt(btpM.group("boost"));
            EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excludeForTs2a = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excludeForTs2a, false, null, false);

                ts1.forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (!ts1.isEmpty() && !ts2.isEmpty()) {
                    int formerPower = ctx.effectiveTargetPower(ts1.get(0));
                    ts2.forEach(t -> ctx.damageTarget(t, formerPower));
                }
            };
        }

        // Special case: "During this turn, the next damage dealt to the former is [received by|dealt to] the latter instead."
        Matcher redirectM = FORMER_LATTER_DAMAGE_REDIRECT.matcher(effects);
        if (redirectM.find()) {
            String redirectSuffix = redirectM.group("suffix").trim();
            Consumer<GameContext> redirectBonus = redirectSuffix.isEmpty() ? null : parse(redirectSuffix, source);
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excludeForTs2r = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excludeForTs2r, false, null, false);

                if (!ts1.isEmpty() && !ts2.isEmpty())
                    ctx.redirectNextIncomingDamage(ts1.get(0), ts2.get(0));
                if (redirectBonus != null) redirectBonus.accept(ctx);
            };
        }

        // Special case: "Until the end of the turn, the former gains +N power [and Traits]. Deal the latter N damage."
        Matcher fbtldM = FORMER_BOOST_TRAITS_LATTER_DIRECT_DAMAGE.matcher(effects);
        if (fbtldM.matches()) {
            int boost = Integer.parseInt(fbtldM.group("boost"));
            EnumSet<CardData.Trait> boostTraits = parseTraits(fbtldM.group("traits"));
            int damage = Integer.parseInt(fbtldM.group("damage"));
            String fbtldSuffix = fbtldM.group("suffix").trim();
            Consumer<GameContext> fbtldBonus = fbtldSuffix.isEmpty() ? null : parse(fbtldSuffix, source);
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2fbtld = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2fbtld, false, null, false);

                ts1.forEach(t -> ctx.boostTarget(t, boost, boostTraits));
                ts2.forEach(t -> ctx.damageTarget(t, damage));
                if (fbtldBonus != null) fbtldBonus.accept(ctx);
            };
        }

        // Special case: "Until the end of the turn, the former loses [traits]. Then, the latter
        // gains all the abilities lost by the previous effect until the end of the turn."
        Matcher fltgM = FORMER_LOSES_TRAITS_LATTER_GAINS.matcher(effects);
        if (fltgM.matches()) {
            EnumSet<CardData.Trait> traitsToLose = parseTraits(fltgM.group("traits"));
            if (!traitsToLose.isEmpty()) {
                return ctx -> {
                    ctx.logEntry(label);
                    String zone1 = td1.fromBreakZone()
                            ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                    List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                            td1.opponentOnly(), td1.selfOnly(),
                            td1.condition(), td1.element(), zone1, td1.opponentBz(),
                            td1.costVal(), td1.costCmp(), -1, null,
                            td1.fwd(), td1.bkp(), td1.mon(),
                            null, null, null, td1.excludeName(), false, null, false);

                    String excl2flt = fExcludeFirst && !ts1.isEmpty()
                            ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                    String zone2 = td2.fromBreakZone()
                            ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                    List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                            td2.opponentOnly(), td2.selfOnly(),
                            td2.condition(), td2.element(), zone2, td2.opponentBz(),
                            td2.costVal(), td2.costCmp(), -1, null,
                            td2.fwd(), td2.bkp(), td2.mon(),
                            null, null, null, excl2flt, false, null, false);

                    if (!ts1.isEmpty()) {
                        ForwardTarget former = ts1.get(0);
                        EnumSet<CardData.Trait> actuallyLost = EnumSet.noneOf(CardData.Trait.class);
                        for (CardData.Trait tr : traitsToLose)
                            if (ctx.effectiveTargetHasTrait(former, tr)) actuallyLost.add(tr);
                        ctx.removeTraitsUntilEotFromTarget(former, traitsToLose);
                        if (!ts2.isEmpty() && !actuallyLost.isEmpty())
                            ctx.boostTarget(ts2.get(0), 0, actuallyLost);
                    }
                };
            }
        }

        // Special case: escalating BZ-count conditionals (dull former; ≥N1 dull latter; ≥N2 freeze; ≥N3 discard).
        Matcher bzEscM = FORMER_DULL_LATTER_BZ_NAME_ESCALATE.matcher(effects);
        if (bzEscM.matches()) {
            int n1 = Integer.parseInt(bzEscM.group("n1"));
            String bzCardName = bzEscM.group("cardname").trim();
            int n2 = Integer.parseInt(bzEscM.group("n2"));
            int n3 = Integer.parseInt(bzEscM.group("n3"));
            int discardN = Integer.parseInt(bzEscM.group("discardN"));
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2bz = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2bz, false, null, false);

                ts1.forEach(ctx::dullTarget);
                int bzCount = ctx.countSelfBreakZoneCards(bzCardName, null);
                if (bzCount >= n1) ts2.forEach(ctx::dullTarget);
                if (bzCount >= n2) {
                    ts1.forEach(ctx::freezeTarget);
                    ts2.forEach(ctx::freezeTarget);
                }
                if (bzCount >= n3) ctx.forceOpponentDiscard(discardN);
            };
        }

        // Special case: "+N power and cannot-dull-by-opp; conditional damage to latter = highest own Forward power."
        Matcher bdicM = FORMER_BOOST_DULL_IMMUNITY_COND_DAMAGE_LATTER.matcher(effects);
        if (bdicM.matches()) {
            int boost = Integer.parseInt(bdicM.group("boost"));
            int dmgThresh = Integer.parseInt(bdicM.group("dmgthresh"));
            EnumSet<CardData.Trait> dullImmunity = EnumSet.of(CardData.Trait.CANNOT_BE_DULLED_BY_OPP);
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2di = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2di, false, null, false);

                ts1.forEach(t -> ctx.boostTarget(t, boost, dullImmunity));
                if (ctx.selfDamageCount() >= dmgThresh && !ts2.isEmpty()) {
                    int highestPower = ctx.selfHighestForwardPower();
                    ctx.damageTarget(ts2.get(0), highestPower);
                }
            };
        }

        // Special case: "Break the former. If [card] enters the field due to Warp, also break the latter."
        if (FORMER_BREAK_COND_WARP_LATTER_BREAK.matcher(effects).matches()) {
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2bw = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2bw, false, null, false);

                sortedByIdxDesc(ts1, true) .forEach(ctx::breakTarget);
                sortedByIdxDesc(ts1, false).forEach(ctx::breakTarget);
                if (ctx.sourceEnteredViaWarp()) {
                    sortedByIdxDesc(ts2, true) .forEach(ctx::breakTarget);
                    sortedByIdxDesc(ts2, false).forEach(ctx::breakTarget);
                }
            };
        }

        // Special case: "Deal the former N damage. If you control M or more Backups, also deal the latter N damage."
        Matcher bkpDmgM = FORMER_DAMAGE_COND_BACKUP_COUNT_LATTER_DAMAGE.matcher(effects);
        if (bkpDmgM.matches()) {
            int dmg1 = Integer.parseInt(bkpDmgM.group("dmg1"));
            int bkpThresh = Integer.parseInt(bkpDmgM.group("n"));
            int dmg2 = Integer.parseInt(bkpDmgM.group("dmg2"));
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2bd = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2bd, false, null, false);

                ts1.forEach(t -> ctx.damageTarget(t, dmg1));
                if (ctx.countSelfFieldCards(false, true, false, null, null) >= bkpThresh)
                    ts2.forEach(t -> ctx.damageTarget(t, dmg2));
            };
        }

        // Special case: "The former deals damage equal to its power to the latter."
        if (FORMER_DEALS_POWER_DAMAGE_TO_LATTER.matcher(effects).matches()) {
            return ctx -> {
                ctx.logEntry(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2fp = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2fp, false, null, false);

                if (!ts1.isEmpty() && !ts2.isEmpty()) {
                    int formerPower = ctx.effectiveTargetPower(ts1.get(0));
                    ctx.damageTarget(ts2.get(0), formerPower);
                }
            };
        }

        // Generic split: prefer comma-after-former when it precedes the " and " split point,
        // since some cards use ", Action the latter" instead of "and Action the latter".
        // (e.g. "Break the former, dull and Freeze the latter.")
        int latterIdx = effectsLower.indexOf("the latter");
        int andIdx    = effects.lastIndexOf(" and ", latterIdx);
        int formerIdx = effectsLower.indexOf("the former");

        int splitIdx = andIdx, splitLen = 5;
        if (formerIdx >= 0) {
            // Look for ", " after the end of the "the former" phrase
            int commaAfterFormer = effects.indexOf(", ", formerIdx + 10);
            if (commaAfterFormer >= 0 && commaAfterFormer < latterIdx
                    && (andIdx < 0 || commaAfterFormer < andIdx)) {
                // Guard: don't use comma split if the latter portion starts with "and "
                // (that's an Oxford comma before the real "and", not a true split point)
                String afterComma = effects.substring(commaAfterFormer + 2).trim().toLowerCase(java.util.Locale.ROOT);
                if (!afterComma.startsWith("and ")) {
                    splitIdx = commaAfterFormer;
                    splitLen = 2;
                }
            }
        }
        if (splitIdx < 0) return null;

        String formerRaw = effects.substring(0, splitIdx).trim();
        String latterRaw = effects.substring(splitIdx + splitLen).trim();

        // Substitute pronouns and strip any trailing period
        String formerEff = formerRaw.replaceAll("(?i)\\bthe\\s+former\\b", "it").replaceAll("\\.$", "").trim();
        String latterEff = latterRaw.replaceAll("(?i)\\bthe\\s+latter\\b", "it").replaceAll("\\.$", "").trim();

        BiConsumer<GameContext, List<ForwardTarget>> formerAction =
                parseFormerLatterGroupAction(formerEff);
        BiConsumer<GameContext, List<ForwardTarget>> latterAction =
                parseFormerLatterGroupAction(latterEff);
        if (formerAction == null || latterAction == null) return null;

        BiConsumer<GameContext, List<ForwardTarget>> fFormerAction = formerAction;
        BiConsumer<GameContext, List<ForwardTarget>> fLatterAction = latterAction;

        return ctx -> {
            ctx.logEntry(label);
            String zone1 = td1.fromBreakZone()
                    ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
            List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                    td1.opponentOnly(), td1.selfOnly(),
                    td1.condition(), td1.element(), zone1, td1.opponentBz(),
                    td1.costVal(), td1.costCmp(), -1, null,
                    td1.fwd(), td1.bkp(), td1.mon(),
                    null, null, null, td1.excludeName(), false, null, false);

            String excludeForTs2 = fExcludeFirst && !ts1.isEmpty()
                    ? getTargetCardName(ctx, ts1.get(0))
                    : fDesc2Static;

            String zone2 = td2.fromBreakZone()
                    ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
            List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                    td2.opponentOnly(), td2.selfOnly(),
                    td2.condition(), td2.element(), zone2, td2.opponentBz(),
                    td2.costVal(), td2.costCmp(), -1, null,
                    td2.fwd(), td2.bkp(), td2.mon(),
                    null, null, null, excludeForTs2, false, null, false);

            fFormerAction.accept(ctx, ts1);
            fLatterAction.accept(ctx, ts2);
        };
    }
    /**
     * Parses "Choose 1 Forward you control other than [CardName]. During this turn, the next
     * damage dealt to it is dealt to [CardName] instead." — one-shot damage redirect where the
     * player picks a Forward to shield and a named card on the field absorbs the damage.
     */
    static Consumer<GameContext> tryParseChooseForwardRedirectToNamed(String text) {
        Matcher m = CHOOSE_FORWARD_REDIRECT_TO_NAMED.matcher(text);
        if (!m.find()) return null;

        String shieldName   = m.group("shield").trim();
        String redirectName = m.group("redirect").trim();
        if (!shieldName.equalsIgnoreCase(redirectName)) return null;

        String logMsg = "Choose 1 Forward you control other than " + shieldName
                + " → redirect next incoming damage to " + shieldName;

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            List<ForwardTarget> targets = selectTargets(ctx, 1, false,
                    false, true,
                    null, null, null, false,
                    -1, null, -1, null,
                    true, false, false,
                    null, null, null, shieldName,
                    false, null, false);
            if (targets.isEmpty()) return;

            List<ForwardTarget> redirectTargets = selectTargets(ctx, 1, false,
                    false, true,
                    null, null, null, false,
                    -1, null, -1, null,
                    true, false, false,
                    null, redirectName, null, null,
                    false, null, false);
            if (redirectTargets.isEmpty()) return;

            ctx.redirectNextIncomingDamage(targets.get(0), redirectTargets.get(0));
        };
    }
    static Consumer<GameContext> tryParseChooseTwoMixedTypes(String text, CardData source) {
        Matcher m = CHOOSE_TWO_MIXED_TYPES_PATTERN.matcher(text);
        if (!m.find()) return null;

        int count1 = Integer.parseInt(m.group("count1"));
        String tgt1 = m.group("type1").toLowerCase();
        boolean fwd1 = tgt1.contains("forward") || tgt1.contains("character");
        boolean bak1 = tgt1.contains("backup")  || tgt1.contains("character");
        boolean mon1 = tgt1.contains("monster") || tgt1.contains("character");

        int count2 = Integer.parseInt(m.group("count2"));
        String tgt2 = m.group("type2").toLowerCase();
        boolean fwd2 = tgt2.contains("forward") || tgt2.contains("character");
        boolean bak2 = tgt2.contains("backup")  || tgt2.contains("character");
        boolean mon2 = tgt2.contains("monster") || tgt2.contains("character");

        String control = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");

        String followup = m.group("followup").trim();
        BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(followup, 0);
        if (action == null) return null;

        String label = "Choose " + count1 + " " + m.group("type1") + " and " + count2 + " " + m.group("type2");
        return ctx -> {
            ctx.logEntry(label);
            List<ForwardTarget> ts1 = selectTargets(ctx, count1, false, opponentOnly, selfOnly,
                    null, null, null, false, -1, null, -1, null,
                    fwd1, bak1, mon1, null, null, null, null, false, null, false);
            List<ForwardTarget> ts2 = selectTargets(ctx, count2, false, opponentOnly, selfOnly,
                    null, null, null, false, -1, null, -1, null,
                    fwd2, bak2, mon2, null, null, null, null, false, null, false);
            List<ForwardTarget> all = new ArrayList<>(ts1);
            all.addAll(ts2);
            action.accept(ctx, all);
        };
    }
    static Consumer<GameContext> tryParseChooseThreeMixedTypes(String text, CardData source) {
        Matcher m = CHOOSE_THREE_MIXED_TYPES_PATTERN.matcher(text);
        if (!m.find()) return null;

        int count1 = Integer.parseInt(m.group("count1"));
        String tgt1 = m.group("type1").toLowerCase();
        boolean fwd1 = tgt1.contains("forward") || tgt1.contains("character");
        boolean bak1 = tgt1.contains("backup")  || tgt1.contains("character");
        boolean mon1 = tgt1.contains("monster") || tgt1.contains("character");

        int count2 = Integer.parseInt(m.group("count2"));
        String tgt2 = m.group("type2").toLowerCase();
        boolean fwd2 = tgt2.contains("forward") || tgt2.contains("character");
        boolean bak2 = tgt2.contains("backup")  || tgt2.contains("character");
        boolean mon2 = tgt2.contains("monster") || tgt2.contains("character");

        int count3 = Integer.parseInt(m.group("count3"));
        String tgt3 = m.group("type3").toLowerCase();
        boolean fwd3 = tgt3.contains("forward") || tgt3.contains("character");
        boolean bak3 = tgt3.contains("backup")  || tgt3.contains("character");
        boolean mon3 = tgt3.contains("monster") || tgt3.contains("character");

        String followup = m.group("followup").trim();
        String label = "Choose up to " + count1 + " " + m.group("type1")
                + ", up to " + count2 + " " + m.group("type2")
                + ", and up to " + count3 + " " + m.group("type3");

        if (FOLLOWUP_REMOVE_FROM_GAME.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(label + " — Remove From Game");
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, true, false, false,
                        null, null, null, false, -1, null, -1, null,
                        fwd1, bak1, mon1, null, null, null, null, false, null, false);
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, true, false, false,
                        null, null, null, false, -1, null, -1, null,
                        fwd2, bak2, mon2, null, null, null, null, false, null, false);
                List<ForwardTarget> ts3 = selectTargets(ctx, count3, true, false, false,
                        null, null, null, false, -1, null, -1, null,
                        fwd3, bak3, mon3, null, null, null, null, false, null, false);
                List<ForwardTarget> all = new ArrayList<>(ts1);
                all.addAll(ts2);
                all.addAll(ts3);
                sortedByIdxDesc(all, true) .forEach(t -> ctx.removeTargetFromGame(t));
                sortedByIdxDesc(all, false).forEach(t -> ctx.removeTargetFromGame(t));
            };
        }

        BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(followup, 0);
        if (action == null) return null;

        return ctx -> {
            ctx.logEntry(label);
            List<ForwardTarget> ts1 = selectTargets(ctx, count1, true, false, false,
                    null, null, null, false, -1, null, -1, null,
                    fwd1, bak1, mon1, null, null, null, null, false, null, false);
            List<ForwardTarget> ts2 = selectTargets(ctx, count2, true, false, false,
                    null, null, null, false, -1, null, -1, null,
                    fwd2, bak2, mon2, null, null, null, null, false, null, false);
            List<ForwardTarget> ts3 = selectTargets(ctx, count3, true, false, false,
                    null, null, null, false, -1, null, -1, null,
                    fwd3, bak3, mon3, null, null, null, null, false, null, false);
            List<ForwardTarget> all = new ArrayList<>(ts1);
            all.addAll(ts2);
            all.addAll(ts3);
            action.accept(ctx, all);
        };
    }
    /**
     * Strips a trailing "When it is put from the field into the Break Zone this turn, draw N"
     * delayed trigger, parses the rest as an ordinary choose-and-act effect, and arms the mark so
     * {@link #selectTargets} applies it to the chosen targets. Arming before the inner effect runs
     * is what makes the trigger survive a lethal primary: the mark is on the Forward before the
     * damage that breaks it.
     */
    static Consumer<GameContext> tryParseChooseCharacter(String text, CardData source, int xValue) {
        Matcher bzDrawM = CHOOSE_THEN_WHEN_PUT_TO_BZ_DRAW.matcher(text.trim());
        if (bzDrawM.matches()) {
            int drawCount = Integer.parseInt(bzDrawM.group("count"));
            Consumer<GameContext> inner = tryParseChooseCharacterInner(bzDrawM.group("head").trim(), source, xValue);
            if (inner == null) return null;
            return ctx -> {
                ctx.armDrawOnFieldToBzMark(drawCount);
                inner.accept(ctx);
                ctx.consumeDrawOnFieldToBzMark();   // clear if the effect never selected a target
            };
        }
        return tryParseChooseCharacterInner(text, source, xValue);
    }
    static Consumer<GameContext> tryParseChooseCharacterInner(String text, CardData source, int xValue) {
        text = ELEM_TYPE_OR_ELEM_TYPE.matcher(text).replaceAll("$1 or $3 $2");
        text = escapePeriodInName(text, source);
        Matcher m = CHOOSE_CHARACTER_PATTERN.matcher(text);
        if (!m.find()) return null;

        boolean any          = m.group("anycount") != null;
        boolean upTo         = m.group("upto") != null;
        int     maxCount     = any ? Integer.MAX_VALUE : Integer.parseInt(m.group("count"));
        String  rawElement   = m.group("element");
        String  element      = rawElement != null && rawElement.contains(" or ")
                ? rawElement.replaceAll("(?i)\\s+or\\s+", "|") : rawElement;
        // Resolve condition: "blocking [Name]"/"blocking a Job [Job]" overrides the standard condition.
        // Post-target qualifiers ("that entered the field this turn") are normalized to the same string.
        String  rawCondition  = m.group("condition");
        String  postCondition = m.group("postcondition");
        String  blockingName  = m.group("blockingname");
        String  blockingJob   = m.group("blockingjob");
        String  traitGroup    = m.group("trait");
        String  condition     = blockingName  != null ? "blocking:"     + blockingName.trim()
                              : blockingJob   != null ? "blocking-job:" + blockingJob.trim()
                              : postCondition != null ? "entered the field this turn"
                              : traitGroup    != null ? "trait:"        + traitGroup.trim().replace(" ", "_").toUpperCase(java.util.Locale.ROOT)
                              : rawCondition;
        String  targets      = m.group("targets");
        String  tgtLower = targets.toLowerCase();
        String  jobFilter;
        String  cardNameFilter;
        boolean inclForwards;
        boolean inclBackups;
        boolean inclMonsters;

        if (tgtLower.startsWith("[job ")) {
            Matcher jm = JOB_BRACKET_PATTERN.matcher(targets);
            jobFilter      = jm.find() ? jm.group(1).trim() : null;
            cardNameFilter = null;
            inclForwards   = true;
            inclBackups    = false;
            inclMonsters   = false;
        } else if (tgtLower.startsWith("[card name ")) {
            Matcher nm = CARD_NAME_BRACKET_PATTERN.matcher(targets);
            cardNameFilter = nm.find() ? nm.group(1).trim() : null;
            jobFilter      = null;
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("card name ") && tgtLower.contains(" or job ")) {
            // "Card Name X Forward or Job Y Forward" — mixed card-name + job filter, both typed
            int orJobIdx = tgtLower.indexOf(" or job ");
            String cardNamePart = targets.substring("Card Name ".length(), orJobIdx).trim();
            cardNameFilter = cardNamePart.replaceAll("(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
            String jobPart = targets.substring(orJobIdx + " or job ".length()).trim();
            jobFilter    = jobPart.replaceAll("(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
            inclForwards = tgtLower.contains("forward");
            inclBackups  = tgtLower.contains("backup");
            inclMonsters = tgtLower.contains("monster");
        } else if (tgtLower.startsWith("card name ")) {
            // Support "Card Name X" and "Card Name X or Card Name Y [or …]"
            String rest = targets.substring("Card Name ".length());
            String[] nameParts = rest.split("(?i)\\s+or\\s+Card\\s+Name\\s+");
            cardNameFilter = String.join("|", nameParts).trim();
            jobFilter      = null;
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("job ") && tgtLower.contains("or card name ")) {
            int orCnIdx    = tgtLower.indexOf("or card name ");
            String rawJob  = targets.substring("Job ".length(), orCnIdx)
                                    .trim().replaceAll("(?i)\\s*and\\s*/\\s*$", "").trim();
            List<String> jobParts = new ArrayList<>();
            for (String p : rawJob.split("(?i)\\s+or\\s+Job\\s+")) jobParts.add(p.trim());
            jobFilter      = String.join("|", jobParts);
            cardNameFilter = targets.substring(orCnIdx + "or card name ".length()).trim();
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("job ")) {
            List<String> jobs = new ArrayList<>();
            Matcher wm = JOB_WRITTEN_SEGMENT.matcher(targets);
            while (wm.find()) jobs.add(wm.group(1).trim());
            boolean bareJob = jobs.isEmpty();
            if (bareJob)
                for (String p : targets.substring("Job ".length()).trim().split("(?i)\\s+or\\s+Job\\s+"))
                    jobs.add(p.trim());
            jobFilter      = String.join("|", jobs);
            cardNameFilter = null;
            inclForwards   = true;
            inclBackups    = bareJob;
            inclMonsters   = bareJob;
        } else {
            jobFilter      = null;
            cardNameFilter = null;
            boolean isGenericCard = tgtLower.equals("card") || tgtLower.equals("cards");
            inclForwards   = isGenericCard || tgtLower.contains("forward") || tgtLower.contains("character");
            inclBackups    = isGenericCard || tgtLower.contains("backup")  || tgtLower.contains("character");
            inclMonsters   = isGenericCard || tgtLower.contains("monster") || tgtLower.contains("character");
        }
        boolean inclSummons  = tgtLower.contains("summon")
                           || tgtLower.equals("card") || tgtLower.equals("cards");
        String  categoryFilter = m.group("category");
        String  excludeName      = restorePeriodInName(m.group("excludename") != null ? m.group("excludename").trim() : null, source);
        String  rawExcludeKw     = m.group("excludekw");
        boolean withoutMulticard = "Multicard".equalsIgnoreCase(rawExcludeKw != null ? rawExcludeKw.trim() : null);
        String  rawExcludeElem = m.group("excludeelem");
        final String fExcludeElem = rawExcludeElem != null ? rawExcludeElem.trim() : null;
        String  costStr      = m.group("cost");
        String  costListStr  = m.group("costlist");
        String  rawCostCmp   = m.group("costcmp");
        int     costVal      = costStr != null ? Integer.parseInt(costStr) : -1;
        // Convert digit-valued costcmp into the "or_…" sentinel understood by meetsCostConstraint.
        // Supports single ("cost N or M") and list ("cost A, B, … or Z") forms.
        String  costCmp;
        if (rawCostCmp != null && rawCostCmp.matches("\\d+")) {
            String tail = costListStr != null
                    ? costListStr.replaceAll("\\s+", "") + "," + rawCostCmp
                    : rawCostCmp;
            costCmp = "or_" + tail;
        } else {
            costCmp = rawCostCmp;
        }
        String  powerStr     = m.group("power");
        String  powerCmp     = m.group("powercmp");
        int     powerVal     = powerStr != null ? Integer.parseInt(powerStr) : -1;
        String  control      = m.group("control");
        boolean opponentOnly = control != null && !control.equalsIgnoreCase("you control");
        boolean selfOnly     = "you control".equalsIgnoreCase(control);
        String  zone         = m.group("zone");
        boolean bothZones    = zone != null && (zone.toLowerCase(java.util.Locale.ROOT).contains("either player")
                                             || zone.toLowerCase(java.util.Locale.ROOT).contains("any player"));
        boolean opponentZone = zone != null && !bothZones && zone.toLowerCase(java.util.Locale.ROOT).contains("opponent");

        String  followup     = restorePeriodInName(m.group("followup").trim(), source);
        boolean unreduced    = CANNOT_BE_REDUCED_PATTERN.matcher(followup).find();

        // If the followup contains ". " (sentence boundary), split into a primary effect
        // (applied to selected targets) and a secondary standalone effect that follows.
        // E.g. "Break it. <name> deals you 1 damage." → primary="Break it", secondary parsed separately.
        final String primaryFollowup;
        final String secondaryText;
        final Consumer<GameContext> secondary;
        {
            int dotSpaceIdx = followup.indexOf(". ");
            if (dotSpaceIdx >= 0) {
                primaryFollowup = followup.substring(0, dotSpaceIdx).trim();
                String stripped = stripRestrictionSentences(followup.substring(dotSpaceIdx + 2).trim());
                secondaryText = stripped.isEmpty() ? null : stripped;
                if (secondaryText == null) {
                    secondary = null;
                } else {
                    // Special case: "You may [cost]. When/If you do so, use this ability again."
                    // Captured here so the replay Consumer closes over the full original effect text.
                    Matcher replayM = MAY_COST_REPLAY_ABILITY.matcher(secondaryText);
                    if (replayM.find()) {
                        String payCost     = replayM.group("payCost");
                        String dullName    = replayM.group("dullName");
                        String discardName = replayM.group("discardName");
                        final String capturedText = text;
                        Consumer<GameContext> replayEffect =
                                ctx2 -> { Consumer<GameContext> inner = parse(capturedText, source, 0); if (inner != null) inner.accept(ctx2); };
                        if (payCost != null) {
                            final String elem = payCost.trim();
                            secondary = ctx -> ctx.mayPayToReplayAbility(elem, replayEffect);
                        } else if (dullName != null) {
                            final String name = dullName.trim();
                            secondary = ctx -> ctx.mayDullActiveCardToReplayAbility(name, replayEffect);
                        } else {
                            final String name = discardName.trim();
                            secondary = ctx -> ctx.mayDiscardCardNameToReplayAbility(name, replayEffect);
                        }
                    } else {
                        // Special case: "That Forward's controller discards N card(s) from their hand."
                        // The discarder depends on the chosen target's controller, which is read back
                        // from GameContext.lastChosenTargets() (populated by selectTargets).
                        Matcher ctrlDiscM = FOLLOWUP_TARGET_CONTROLLER_DISCARDS.matcher(secondaryText);
                        if (ctrlDiscM.matches()) {
                            final int discardCount = Integer.parseInt(ctrlDiscM.group("count"));
                            secondary = ctx -> {
                                List<ForwardTarget> chosen = ctx.lastChosenTargets();
                                for (ForwardTarget t : chosen) {
                                    if (t.isP1() == ctx.isP1()) ctx.selfDiscard(discardCount);
                                    else                        ctx.forceOpponentDiscard(discardCount);
                                }
                            };
                        } else if (FOLLOWUP_BREAK.matcher(secondaryText).find()) {
                            // "Break it." as a secondary applies to the same targets chosen for the primary.
                            secondary = ctx -> {
                                List<ForwardTarget> chosen = ctx.lastChosenTargets();
                                sortedByIdxDesc(chosen, true) .forEach(ctx::breakTarget);
                                sortedByIdxDesc(chosen, false).forEach(ctx::breakTarget);
                            };
                        } else if (FOLLOWUP_CANNOT_BE_BROKEN.matcher(secondaryText).find()
                                || FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE.matcher(secondaryText).find()) {
                            secondary = ctx -> ctx.lastChosenTargets().forEach(ctx::shieldCannotBeBroken);
                        } else if (FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG.matcher(secondaryText).find()) {
                            secondary = ctx -> ctx.lastChosenTargets().forEach(ctx::shieldCannotBeBrokenByNonDmg);
                        } else if (FOLLOWUP_IF_PUT_TO_BZ_THIS_TURN_RFG_INSTEAD.matcher(secondaryText).find()) {
                            secondary = ctx -> ctx.lastChosenTargets().forEach(ctx::markTargetRfgInsteadOfBzThisTurn);
                        } else {
                            Matcher rfpM = SECONDARY_PLAY_REMOVED_ONTO_FIELD.matcher(secondaryText);
                            if (rfpM.find()) {
                                boolean dullIt = rfpM.group("dull") != null;
                                secondary = ctx -> ctx.playLastRemovedFromRfpOntoField(dullIt);
                            } else {
                                Consumer<GameContext> parsed = parse(secondaryText, source);
                                secondary = (parsed != null) ? parsed
                                        : ctx -> ctx.logEntry("[ActionResolver] Secondary followup not yet implemented: " + secondaryText);
                            }
                        }
                    }
                }
            } else {
                primaryFollowup = followup;
                secondaryText   = null;
                secondary = null;
            }
        }

        // Detect "You may [followup]" — followup is optional; player may decline the action after choosing the target
        final boolean followupIsOptional = primaryFollowup.toLowerCase(java.util.Locale.ROOT).startsWith("you may ");
        final String strippedPrimaryFollowup = followupIsOptional
                ? primaryFollowup.substring("You may ".length()).trim() : primaryFollowup;

        // Shared log prefix helper (captured once, reused in all lambdas)
        String costLabel     = CardFilters.formatCostFilterLabel(costVal, costCmp);
        String powerLabel    = powerVal >= 0
                ? " of power " + powerVal + (powerCmp != null ? " or " + powerCmp : "") : "";
        String controlLabel  = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String categoryLabel = categoryFilter != null ? " Category " + categoryFilter : "";
        String excludeLabel  = excludeName != null ? " (excl. " + excludeName + ")" : "";
        String zoneLabel     = zone != null
                ? " in " + (bothZones ? "either player's" : opponentZone ? "opponent's" : "your") + " Break Zone" : "";
        String choosePrefix = "Choose " + (upTo ? "up to " : any ? "any number of " : "") + (maxCount < Integer.MAX_VALUE ? maxCount : "")
                + (condition != null ? " " + condition : "")
                + (element   != null ? " " + element   : "")
                + categoryLabel + " " + targets + costLabel + powerLabel + controlLabel + excludeLabel + zoneLabel;

        // --- "You may pay 《Element》. If you do so, [target action]." ---
        // Checked against the full followup before the primary/secondary split so the conditional is not lost.
        {
            Matcher youMayPayM = FOLLOWUP_YOU_MAY_PAY_ELEMENT_IF_DO_SO.matcher(followup);
            if (youMayPayM.matches()) {
                String cpElem    = youMayPayM.group("element").trim();
                String cpEffText = youMayPayM.group("effect").trim();
                BiConsumer<GameContext, List<ForwardTarget>> cpAction =
                        parseTargetAction(cpEffText, xValue);
                if (cpAction != null) {
                    return ctx -> {
                        ctx.logEntry(choosePrefix + " — You may pay 《" + cpElem + "》; if so: " + cpEffText);
                        List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                                opponentOnly, selfOnly, condition, element, zone, opponentZone,
                                costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                        ctx.mayPayElementCpToEffect(cpElem, ctx2 -> cpAction.accept(ctx2, ts));
                    };
                }
            }
        }

        // --- "If your opponent doesn't pay 《N》, [target action]." (Arkasodara) ---
        // The opponent may pay to prevent the action against the chosen target(s).
        {
            Matcher notPayM = FOLLOWUP_IF_OPP_NOT_PAY_ACTION.matcher(followup);
            if (notPayM.matches()) {
                int notPayCost = Integer.parseInt(notPayM.group("cost").trim());
                String notPayEffText = notPayM.group("effect").trim();
                BiConsumer<GameContext, List<ForwardTarget>> notPayAction =
                        parseTargetAction(notPayEffText, xValue);
                if (notPayAction != null) {
                    return ctx -> {
                        ctx.logEntry(choosePrefix + " — unless opponent pays 《" + notPayCost + "》: " + notPayEffText);
                        List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                                opponentOnly, selfOnly, condition, element, zone, opponentZone,
                                costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                        if (ts.isEmpty()) return;
                        ctx.opponentMayPayToPreventAction(notPayCost, () -> notPayAction.accept(ctx, ts));
                    };
                }
            }
        }

        // --- "[action]. Then, if you don't pay 《1》 per CP of the chosen card's cost, break it." ---
        // Checked against the full followup before the primary/secondary split, since the split
        // would drop the trailing clause and leave the primary action unconditional.
        {
            Matcher perCpM = FOLLOWUP_THEN_PAY_PER_TARGET_COST_OR_BREAK.matcher(followup);
            if (perCpM.matches()) {
                String primaryText = perCpM.group("primary").trim();
                BiConsumer<GameContext, List<ForwardTarget>> primaryAction =
                        parseTargetAction(primaryText, xValue);
                // "You gain control of it" is not one of parseTargetAction's verbs, and it is the
                // primary the only printed card (Ultimecia 27-092H) uses.
                if (primaryAction == null && FOLLOWUP_GAIN_CONTROL.matcher(primaryText).find())
                    primaryAction = (c2, ts2) -> ts2.forEach(t -> c2.gainControlOfForward(t, "permanent", false));
                if (primaryAction != null) {
                    final BiConsumer<GameContext, List<ForwardTarget>> fPrimary = primaryAction;
                    return ctx -> {
                        ctx.logEntry(choosePrefix + " — " + primaryText
                                + ", then pay 《1》 per CP of its cost or put it into the Break Zone");
                        List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                                opponentOnly, selfOnly, condition, element, zone, opponentZone,
                                costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                        if (ts.isEmpty()) return;
                        // Resolve the cards up front: the primary action may change control of them,
                        // which invalidates the side/index a ForwardTarget carries.
                        List<CardData> chosen = new ArrayList<>();
                        for (ForwardTarget t : ts) {
                            CardData c = ctx.targetCard(t);
                            if (c != null) chosen.add(c);
                        }
                        fPrimary.accept(ctx, ts);
                        for (CardData c : chosen) {
                            // Only charge for a card the primary actually handed over — a steal that
                            // did not go through leaves nothing to pay for or break.
                            if (!ctx.selfControlsCard(c)) continue;
                            ctx.mayPayCostOrElse(c.cost(), null, 0, () -> ctx.breakSourceCard(c));
                        }
                    };
                }
            }
        }

        // --- "You may discard 1 Card Name X from your hand. If you do so, deal it N damage." ---
        // Checked against the full followup before the primary/secondary split.
        Matcher mayDiscardNamedM = FOLLOWUP_MAY_DISCARD_NAMED_DEAL_DAMAGE.matcher(followup);
        if (mayDiscardNamedM.matches()) {
            String discardName = mayDiscardNamedM.group("cardname").trim();
            int    damage      = Integer.parseInt(mayDiscardNamedM.group("amount"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — May discard Card Name " + discardName + ", if so deal " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ctx.mayDiscardCardNameFromHand(discardName, ctx2 -> {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx2.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx2.damageTarget(t, damage));
                });
            };
        }

        // --- "Divide N damage" ---
        Matcher divideM = DIVIDE_DAMAGE_PATTERN.matcher(followup);
        if (divideM.find())
        {
            int baseDamage = Integer.parseInt(divideM.group("amount"));
            final boolean equally = divideM.group("mode") != null;

            int dotSpaceIdxCond = followup.indexOf(". ");
            String followup_cond = dotSpaceIdxCond >= 0 ? followup.substring(dotSpaceIdxCond + 2) : "";
            Matcher divideCondM = DIVIDE_DAMAGE_INSTEAD_COND.matcher(followup_cond);
            final DamageInsteadCondition insteadCond;
            final int altDamage;
            if (divideCondM.find()) {
                DamageInsteadCondition parsedCond = parseDamageInsteadCondition(divideCondM.group("cond").trim());
                // Anchored to "divide N damage" specifically — a bare \d+ search would wrongly
                // grab a digit embedded in the condition text itself (e.g. "Category FFTA2").
                Matcher mAmp = DIVIDE_DAMAGE_PATTERN.matcher(followup_cond);
                insteadCond = parsedCond;
                altDamage   = (parsedCond != null && mAmp.find()) ? Integer.parseInt(mAmp.group("amount")) : baseDamage;
            } else {
                insteadCond = null;
                altDamage   = baseDamage;
            }

            final boolean fUnreduced = unreduced;
            final int fBaseDamage = baseDamage;
            return ctx -> {
                int fDamage = fBaseDamage;
                if (insteadCond != null && insteadConditionMet(ctx, insteadCond)) fDamage = altDamage;

                List<ForwardTarget> ts = selectTargets(ctx, maxCount, any || upTo,
                        opponentOnly, selfOnly, null, null, null, false,
                        -1, null, -1, null,
                        true, false, false,
                        null, null, null, null, false, null, false);
                if (ts.isEmpty()) return;

                if (equally) {
                    int perTarget = roundUpToThousand(fDamage, ts.size());
                    sortedByIdxDesc(ts, true) .forEach(t -> damageTargetMaybeUnreduced(ctx, t, perTarget, fUnreduced));
                    sortedByIdxDesc(ts, false).forEach(t -> damageTargetMaybeUnreduced(ctx, t, perTarget, fUnreduced));
                } else if (ts.size() == 1) {
                    // Nothing to divide — skip the allocation dialog and deal it all.
                    damageTargetMaybeUnreduced(ctx, ts.get(0), fDamage, fUnreduced);
                } else {
                    List<CardData> cards = new ArrayList<>();
                    for (ForwardTarget t : ts) {
                        cards.add(t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx()));
                    }
                    List<Integer> allocation = ctx.divideDamageAmount(fDamage, "Divide Damage: ", cards);
                    Map<ForwardTarget, Integer> amountByTarget = new HashMap<>();
                    for (int i = 0; i < ts.size(); i++) amountByTarget.put(ts.get(i), allocation.get(i));
                    sortedByIdxDesc(ts, true) .forEach(t -> { int amt = amountByTarget.get(t); if (amt > 0) damageTargetMaybeUnreduced(ctx, t, amt, fUnreduced); });
                    sortedByIdxDesc(ts, false).forEach(t -> { int amt = amountByTarget.get(t); if (amt > 0) damageTargetMaybeUnreduced(ctx, t, amt, fUnreduced); });
                }
            };
        }

        // --- "Deal it N damage. If <cond>, deal it M damage instead." ---
        // Matched against the full followup before the primary/secondary split to avoid losing the condition.
        Matcher insteadM = FOLLOWUP_DAMAGE_INSTEAD.matcher(followup);
        if (insteadM.find()) {
            int    baseDmg   = Integer.parseInt(insteadM.group("base"));
            int    altDmg    = Integer.parseInt(insteadM.group("alt"));
            String condText  = insteadM.group("cond").trim();
            DamageInsteadCondition insteadCond = parseDamageInsteadCondition(condText);
            if (insteadCond != null) {
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — Deal " + baseDmg + "/" + altDmg + " damage (if " + condText + ")");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, resolveInsteadDamage(ctx, t, insteadCond, baseDmg, altDmg)));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, resolveInsteadDamage(ctx, t, insteadCond, baseDmg, altDmg)));
                };
            }
        }

        // --- General EX Burst instead ("P. If [name] results from an EX Burst, A instead.") ---
        // Checked before the for-each and fixed-damage handlers so the condition isn't lost.
        // FOLLOWUP_DAMAGE_INSTEAD already covers fixed-damage EX burst cases above; this handles
        // the for-each damage and non-damage EX burst instead variants.
        Matcher exBurstM = FOLLOWUP_INSTEAD_EXBURST.matcher(followup);
        if (exBurstM.find()) {
            String primaryText = exBurstM.group("primary").trim();
            String altText     = exBurstM.group("alt").trim();
            BiConsumer<GameContext, List<ForwardTarget>> primaryAction =
                    parseTargetAction(primaryText, xValue);
            BiConsumer<GameContext, List<ForwardTarget>> altAction =
                    parseTargetAction(altText, xValue);
            if (primaryAction != null && altAction != null) {
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — EX Burst: " + primaryText + " / " + altText);
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    (ctx.isExBurst() ? altAction : primaryAction).accept(ctx, ts);
                };
            }
        }

        // --- "If opponent has N cards or less…, [action1]. If no cards…, [action2] instead." ---
        // Two-tier hand condition — checked against the full followup before the dot-split.
        Matcher dblHandM = OPPONENT_HAND_DOUBLE_CONDITION_PATTERN.matcher(followup);
        if (dblHandM.matches()) {
            int    threshold  = Integer.parseInt(dblHandM.group("n"));
            String eff1Text   = dblHandM.group("effect1").trim();
            String eff2Text   = dblHandM.group("effect2").trim();
            BiConsumer<GameContext, List<ForwardTarget>> action1 = parseTargetAction(eff1Text, xValue);
            BiConsumer<GameContext, List<ForwardTarget>> action2 = parseTargetAction(eff2Text, xValue);
            if (action1 != null && action2 != null) {
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — hand condition (≤" + threshold + "/0)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                            jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    int hs = ctx.opponentHandSize();
                    if (hs == 0)           action2.accept(ctx, ts);
                    else if (hs <= threshold) action1.accept(ctx, ts);
                };
            }
        }

        // --- "If opponent has [no|N cards or less] cards in hand, [action]" as single followup ---
        Matcher handM = OPPONENT_HAND_CONDITION_PATTERN.matcher(primaryFollowup);
        if (handM.matches()) {
            String nStr      = handM.group("n");
            int    threshold = nStr != null ? Integer.parseInt(nStr) : 0;
            String effText   = handM.group("effect").trim();
            BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(effText, xValue);
            if (action != null) {
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — hand condition");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                            jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    int hs = ctx.opponentHandSize();
                    boolean condMet = (nStr != null) ? hs <= threshold : hs == 0;
                    if (condMet) action.accept(ctx, ts);
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- "Select 1 number and reveal the top card of your deck.
        //      If the revealed card is of the same cost as the selected number, break it." ---
        // "it" = the chosen Forward selected in the choose step, not the revealed card.
        // Checked against the full followup (not primaryFollowup) so the compound text isn't split.
        if (FOLLOWUP_SELECT_NUMBER_REVEAL_BREAK.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Select number + reveal, break if cost matches");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty()) return;
                ForwardTarget target = ts.get(0);
                int n = ctx.selectNumber(0, 11, "Select a number:");
                ctx.logEntry("Selected number: " + n);
                ctx.revealTopDeckCard(java.util.List.of(
                        new RevealClause(card -> card.cost() == n, null,
                                rCtx -> rCtx.breakTarget(target))), false);
            };
        }

        // --- "Remove the top card of your deck from the game. Deal it N damage for each CP required to play the removed card." ---
        Matcher rfpTopDeckPerCpM = FOLLOWUP_RFP_TOP_DECK_AND_DAMAGE_PER_CP.matcher(followup);
        if (rfpTopDeckPerCpM.find()) {
            int baseDmg = Integer.parseInt(rfpTopDeckPerCpM.group("base"));
            return ctx -> {
                int cpCost = ctx.removeTopCardOfDeckFromGameAndGetCost();
                int damage = baseDmg * cpCost;
                ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (RFP top of deck, " + baseDmg + "×CP=" + cpCost + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
            };
        }

        // --- "Remove the top card of your deck from the game. If the removed card is a Forward, break it. If not, deal it N damage." ---
        Matcher rfpTopDeckIfFwdM = FOLLOWUP_RFP_TOP_DECK_IF_FORWARD_BREAK_ELSE_DAMAGE.matcher(followup);
        if (rfpTopDeckIfFwdM.find()) {
            int dmg = Integer.parseInt(rfpTopDeckIfFwdM.group("dmg"));
            return ctx -> {
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ctx.removeTopCardOfDeckFromGameIsForward()) {
                    ctx.logEntry(choosePrefix + " — removed card is a Forward: break the chosen Forward");
                    sortedByIdxDesc(ts, true) .forEach(ctx::breakTarget);
                    sortedByIdxDesc(ts, false).forEach(ctx::breakTarget);
                } else {
                    ctx.logEntry(choosePrefix + " — removed card is not a Forward: deal the chosen Forward " + dmg + " damage");
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, dmg));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, dmg));
                }
            };
        }

        // --- "Reveal the top N cards of your deck. Deal it M damage for each CP required to play the revealed cards. Add all the revealed cards to your hand." ---
        Matcher revealDmgPerCpM = FOLLOWUP_REVEAL_TOP_N_DAMAGE_PER_CP_ADD_ALL_TO_HAND.matcher(followup);
        if (revealDmgPerCpM.find()) {
            int revealCount = Integer.parseInt(revealDmgPerCpM.group("n"));
            int baseDmg     = Integer.parseInt(revealDmgPerCpM.group("base"));
            return ctx -> {
                int totalCp = ctx.revealTopNAndAddAllToHandGetTotalCP(revealCount);
                int damage  = baseDmg * totalCp;
                ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (reveal top " + revealCount + ", " + baseDmg + "×totalCP=" + totalCp + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
            };
        }

        // --- "Reveal the top N cards of your deck. For each Job [Job] revealed this way, deal it M damage. Then, place the revealed cards at the bottom of your deck in any order." ---
        Matcher revealJobDmgM = FOLLOWUP_REVEAL_TOP_N_JOB_DEAL_DMG_PLACE_BOTTOM.matcher(followup);
        if (revealJobDmgM.find()) {
            int    revealCount  = Integer.parseInt(revealJobDmgM.group("n"));
            String revealJob    = revealJobDmgM.group("job").trim();
            int    dmgPerMatch  = Integer.parseInt(revealJobDmgM.group("dmg"));
            return ctx -> {
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                int matchCount = ctx.revealTopNCountJobPlaceAllAtBottom(revealCount, revealJob);
                if (ts.isEmpty() || matchCount == 0) {
                    ctx.logEntry(choosePrefix + " — 0 Job " + revealJob + " revealed, no damage");
                    return;
                }
                int totalDmg = matchCount * dmgPerMatch;
                ctx.logEntry(choosePrefix + " — Deal " + totalDmg + " damage (" + matchCount + "×" + dmgPerMatch + " for Job " + revealJob + ")");
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, totalDmg));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, totalDmg));
            };
        }

        // --- "Deal it N damage for each [Name] Counter placed on [card]." (counter-scaled xValue) ---
        // Must be checked before FOLLOWUP_DAMAGE_FOR_EACH, which would match on the flat N and drop the for-each.
        Matcher dmgForEachCounterM = FOLLOWUP_DAMAGE_FOR_EACH_COUNTER.matcher(primaryFollowup);
        if (dmgForEachCounterM.find()) {
            int perUnit = Integer.parseInt(dmgForEachCounterM.group("perunit"));
            String counterName = dmgForEachCounterM.group("counterName").trim();
            return ctx -> {
                int damage = perUnit * xValue;
                ctx.logEntry(choosePrefix + " — " + perUnit + " damage ×" + xValue + " " + counterName + " Counter(s) = " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Deal it N damage for each [source]" followup ---
        Matcher forEachM = FOLLOWUP_DAMAGE_FOR_EACH.matcher(primaryFollowup);
        if (forEachM.find()) {
            int    baseDmg        = Integer.parseInt(forEachM.group("base"));
            String perStr         = forEachM.group("per");
            int    perDmg         = perStr != null ? Integer.parseInt(perStr) : 0;
            boolean subtract      = "minus".equalsIgnoreCase(forEachM.group("op"));
            boolean srcSelfDmg    = forEachM.group("selfdmg")  != null;
            String  srcJobBracket = forEachM.group("jobbname") != null ? forEachM.group("jobbname").trim() : null;
            String  srcJobWritten = forEachM.group("jobwname") != null ? forEachM.group("jobwname").trim() : null;
            String  srcJobWType   = forEachM.group("jobwtype") != null ? forEachM.group("jobwtype").trim() : null;
            String  srcCharType   = forEachM.group("chartype");
            String  srcCategory   = srcCharType != null && forEachM.group("category") != null ? forEachM.group("category").trim() : null;
            String  srcElement    = srcCharType != null && forEachM.group("element")  != null ? forEachM.group("element").toLowerCase(java.util.Locale.ROOT) : null;
            int     srcCostFilter = srcCharType != null && forEachM.group("costfilter") != null ? Integer.parseInt(forEachM.group("costfilter")) : -1;
            String  srcBzName     = forEachM.group("bzname")   != null ? forEachM.group("bzname").trim()   : null;
            boolean srcOppHand    = forEachM.group("opphand")   != null;
            boolean srcCrystal    = forEachM.group("crystal")   != null;
            boolean srcCpDiffElem = forEachM.group("cpDiffElem") != null;
            // if none of the above → xpaid
            boolean charFwd = srcCharType != null && (srcCharType.equalsIgnoreCase("forward")   || srcCharType.equalsIgnoreCase("forwards")   || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            boolean charBkp = srcCharType != null && (srcCharType.equalsIgnoreCase("backup")    || srcCharType.equalsIgnoreCase("backups")    || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            boolean charMon = srcCharType != null && (srcCharType.equalsIgnoreCase("monster")   || srcCharType.equalsIgnoreCase("monsters")   || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            String sourceLabel;
            if      (srcSelfDmg)           sourceLabel = "P1 damage";
            else if (srcJobBracket != null) sourceLabel = "[Job (" + srcJobBracket + ")] you control";
            else if (srcJobWritten != null) sourceLabel = "Job " + srcJobWritten + (srcJobWType != null ? " " + srcJobWType : "") + " you control";
            else if (srcCharType   != null) sourceLabel = (srcCategory != null ? "Category " + srcCategory + " " : "") + (srcElement != null ? srcElement + " " : "") + srcCharType + (srcCostFilter != -1 ? " of cost " + srcCostFilter : "") + " you control";
            else if (srcBzName     != null) sourceLabel = "Card Name " + srcBzName + " in BZ";
            else if (srcOppHand)           sourceLabel = "opponent hand";
            else if (srcCrystal)           sourceLabel = "《C》 you have";
            else if (srcCpDiffElem)        sourceLabel = "CP of a different Element paid to cast";
            else                            sourceLabel = "X CP paid";
            String op = subtract ? " - " : " + ";
            String logLabel = perDmg > 0
                    ? baseDmg + op + perDmg + "×[" + sourceLabel + "]"
                    : baseDmg + "×[" + sourceLabel + "]";
            return ctx -> {
                int n;
                if      (srcSelfDmg)           n = ctx.p1DamageCount();
                else if (srcJobBracket != null) n = ctx.countSelfFieldCards(true, true, true, srcJobBracket, null);
                else if (srcJobWritten != null) {
                    boolean jwFwd = srcJobWType == null || srcJobWType.matches("(?i)Forwards?");
                    boolean jwBkp = srcJobWType == null || srcJobWType.matches("(?i)Backups?");
                    boolean jwMon = srcJobWType == null || srcJobWType.matches("(?i)Monsters?");
                    n = ctx.countSelfFieldCards(jwFwd, jwBkp, jwMon, srcJobWritten, null);
                }
                else if (srcCharType   != null) n = ctx.countSelfFieldCards(charFwd, charBkp, charMon, null, null, srcCategory, srcElement, srcCostFilter);
                else if (srcBzName     != null) n = ctx.countSelfBreakZoneCards(srcBzName, null);
                else if (srcOppHand)           n = ctx.opponentHandSize();
                else if (srcCrystal)           n = ctx.crystalCount();
                else if (srcCpDiffElem)        n = ctx.castPaymentDistinctElements();
                else                            n = xValue;
                int damage = perDmg > 0
                        ? (subtract ? Math.max(0, baseDmg - perDmg * n) : baseDmg + perDmg * n)
                        : baseDmg * n;
                ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (" + logLabel + ", n=" + n + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull + Damage followup ---
        Matcher dullDmgM = FOLLOWUP_DULL_AND_DAMAGE.matcher(primaryFollowup);
        if (dullDmgM.find()) {
            int damage = Integer.parseInt(dullDmgM.group("amount"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Dull & Deal " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> { ctx.dullTarget(t); ctx.damageTarget(t, damage); });
                sortedByIdxDesc(ts, false).forEach(t -> { ctx.dullTarget(t); ctx.damageTarget(t, damage); });
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "If your opponent controls N or more Forwards, deal it X damage" followup ---
        Matcher oppFwdCondM = FOLLOWUP_IF_OPPONENT_CONTROLS_FORWARDS_DAMAGE.matcher(primaryFollowup);
        if (oppFwdCondM.matches()) {
            int minCount = Integer.parseInt(oppFwdCondM.group("count"));
            int damage   = Integer.parseInt(oppFwdCondM.group("amount"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — If opponent controls ≥" + minCount + " Forwards, deal " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ctx.opponentForwardCount() >= minCount) {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "If you control N or more [Element] [Type], deal it X damage" followup ---
        Matcher selfFieldCondM = FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_DAMAGE.matcher(primaryFollowup);
        if (selfFieldCondM.matches()) {
            int    minCount    = Integer.parseInt(selfFieldCondM.group("count"));
            int    damage      = Integer.parseInt(selfFieldCondM.group("amount"));
            String condElement  = selfFieldCondM.group("element");  // null if absent
            String condTypeRaw  = selfFieldCondM.group("type");
            String condType     = condTypeRaw.toLowerCase();
            boolean cFwd = condType.startsWith("forward") || condType.startsWith("character");
            boolean cBkp = condType.startsWith("backup")  || condType.startsWith("character");
            boolean cMon = condType.startsWith("monster")  || condType.startsWith("character");
            return ctx -> {
                String label = "If you control ≥" + minCount + " "
                        + (condElement != null ? condElement + " " : "")
                        + condTypeRaw + ", deal " + damage + " damage";
                ctx.logEntry(choosePrefix + " — " + label);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ctx.selfFieldCount(condElement, cFwd, cBkp, cMon) >= minCount) {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "If you control N or more [Element] [Type], <action> it/them" followup ---
        // Must precede the plain action handlers below: those scan for their verb with find(), so
        // they would match straight through this condition and apply the action unconditionally.
        Matcher selfFieldActionM = FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_ACTION.matcher(primaryFollowup);
        if (selfFieldActionM.matches()) {
            String actionText = selfFieldActionM.group("action").trim();
            BiConsumer<GameContext, List<ForwardTarget>> condAction =
                    parseTargetAction(actionText, xValue);
            if (condAction != null) {
                int    minCount    = Integer.parseInt(selfFieldActionM.group("count"));
                String condElement = selfFieldActionM.group("element");  // null if absent
                String condTypeRaw = selfFieldActionM.group("type");
                String condType    = condTypeRaw.toLowerCase();
                boolean cFwd = condType.startsWith("forward") || condType.startsWith("character");
                boolean cBkp = condType.startsWith("backup")  || condType.startsWith("character");
                boolean cMon = condType.startsWith("monster") || condType.startsWith("character");
                return ctx -> {
                    String label = "If you control ≥" + minCount + " "
                            + (condElement != null ? condElement + " " : "")
                            + condTypeRaw + ", " + actionText;
                    ctx.logEntry(choosePrefix + " — " + label);
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                            jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    if (ctx.selfFieldCount(condElement, cFwd, cBkp, cMon) >= minCount)
                        condAction.accept(ctx, ts);
                    else
                        ctx.logEntry("Condition not met — " + actionText + " skipped");
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Split effect: [action A] the first [type] … and [action B] the other ---
        Matcher foM = FOLLOWUP_FIRST_AND_OTHER.matcher(primaryFollowup);
        if (foM.find()) {
            final String firstpfx    = foM.group("firstpfx").trim();
            final String firstsfx    = foM.group("firstsfx").trim().toLowerCase();
            final String othereffect = foM.group("othereffect").trim().toLowerCase();
            Matcher dmgAmt = Pattern.compile("(?i)deal\\s+(?<n>\\d+)\\s+damage").matcher(firstpfx);
            final int firstDamage = dmgAmt.find() ? Integer.parseInt(dmgAmt.group("n")) : 0;
            return ctx -> {
                ctx.logEntry(choosePrefix + " — " + firstpfx + " first; " + othereffect + " other");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (!ts.isEmpty()) {
                    ForwardTarget first = ts.get(0);
                    if      (firstsfx.contains("from the game"))  ctx.removeTargetFromGame(first);
                    else if (firstsfx.contains("to its owner")) {
                        if (first.zone() == ForwardTarget.CardZone.FORWARD) {
                            if (first.isP1()) ctx.returnP1ForwardToHand(first.idx());
                            else              ctx.returnP2ForwardToHand(first.idx());
                        }
                    }
                    else if (firstDamage > 0)                          ctx.damageTarget(first, firstDamage);
                    else if (firstpfx.equalsIgnoreCase("dull"))        ctx.dullTarget(first);
                    else if (firstpfx.equalsIgnoreCase("break"))       ctx.breakTarget(first);
                    else if (firstpfx.equalsIgnoreCase("freeze"))      ctx.freezeTarget(first);
                    else if (firstpfx.equalsIgnoreCase("activate"))    ctx.activateTarget(first);
                }
                if (ts.size() > 1) {
                    ForwardTarget other = ts.get(1);
                    if      (othereffect.contains("freeze") && othereffect.contains("dull")) ctx.dullAndFreezeTarget(other);
                    else if (othereffect.equals("activate"))                                  ctx.activateTarget(other);
                    else if (othereffect.equals("break"))                                     ctx.breakTarget(other);
                    else if (othereffect.equals("dull"))                                      ctx.dullTarget(other);
                    else if (othereffect.equals("freeze"))                                    ctx.freezeTarget(other);
                    else if (othereffect.contains("from the game"))                           ctx.removeTargetFromGame(other);
                    else if (othereffect.contains("to its owner")) {
                        if (other.zone() == ForwardTarget.CardZone.FORWARD) {
                            if (other.isP1()) ctx.returnP1ForwardToHand(other.idx());
                            else              ctx.returnP2ForwardToHand(other.idx());
                        }
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Damage + controller damage followup ("Deal it N damage and M point(s) of damage to that Forward's controller") ---
        Matcher ctrlDmgM = FOLLOWUP_DAMAGE_AND_CONTROLLER_DAMAGE.matcher(strippedPrimaryFollowup);
        if (ctrlDmgM.find()) {
            int damage        = Integer.parseInt(ctrlDmgM.group("amount"));
            int controllerDmg = Integer.parseInt(ctrlDmgM.group("controllerdmg"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Deal " + damage + " damage + " + controllerDmg + " to controller");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                for (ForwardTarget t : ts) {
                    if (t.isP1()) ctx.dealDamageToSelf(controllerDmg);
                    else          ctx.dealDamageToOpponent(controllerDmg);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Damage followup (fixed amount) ---
        Matcher dmgM = FOLLOWUP_DAMAGE.matcher(strippedPrimaryFollowup);
        if (dmgM.find()) {
            int damage = Integer.parseInt(dmgM.group("amount"));
            String alsoCard = dmgM.group("also") != null ? dmgM.group("also").trim() : null;
            return ctx -> {
                String unredSuffix = unreduced ? " (cannot be reduced)" : "";
                ctx.logEntry(alsoCard != null
                        ? choosePrefix + " — Deal " + damage + " damage (and to " + alsoCard + ")" + unredSuffix
                        : choosePrefix + " — Deal " + damage + " damage" + unredSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                Consumer<GameContext> doDamage = ctx2 -> {
                    if (unreduced) {
                        sortedByIdxDesc(ts, true) .forEach(t -> ctx2.damageTargetUnreduced(t, damage));
                        sortedByIdxDesc(ts, false).forEach(t -> ctx2.damageTargetUnreduced(t, damage));
                    } else {
                        sortedByIdxDesc(ts, true) .forEach(t -> ctx2.damageTarget(t, damage));
                        sortedByIdxDesc(ts, false).forEach(t -> ctx2.damageTarget(t, damage));
                    }
                    if (alsoCard != null) ctx2.damageFieldForwardByName(alsoCard, damage);
                    if (secondary != null) secondary.accept(ctx2);
                };
                if (followupIsOptional && !ts.isEmpty()) ctx.playerMayDoEffect("Deal it " + damage + " damage?", doDamage);
                else if (!followupIsOptional) doDamage.accept(ctx);
            };
        }

        // --- Mutual power-as-damage between source and chosen Forward ---
        if (source != null) {
            Matcher mutM = FOLLOWUP_MUTUAL_POWER_DAMAGE.matcher(primaryFollowup);
            if (mutM.find() && mutM.group("srcname").trim().equalsIgnoreCase(source.name())) {
                String srcName = source.name();
                return ctx -> {
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    if (ts.isEmpty()) { if (secondary != null) secondary.accept(ctx); return; }
                    int srcPower = Math.max(0, ctx.fieldForwardPowerByName(srcName));
                    for (ForwardTarget t : ts) {
                        int tgtPower = Math.max(0, ctx.effectiveTargetPower(t));
                        ctx.logEntry(choosePrefix + " — Mutual power damage: " + srcName + " (" + srcPower
                                + ") ↔ chosen Forward (" + tgtPower + ")");
                        ctx.damageTarget(t, srcPower);
                        ctx.damageFieldForwardByName(srcName, tgtPower);
                    }
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Damage followup (computed amount) ---
        Matcher exprM = FOLLOWUP_DAMAGE_EXPR.matcher(primaryFollowup);
        if (exprM.find()) {
            if (exprM.group("highest") != null) {
                return ctx -> {
                    int damage = ctx.highestP1ForwardPower();
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (highest Forward power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("halfcard") != null) {
                String  cardName = exprM.group("halfcard").trim();
                boolean roundUp  = "up".equalsIgnoreCase(exprM.group("halfrounding"));
                return ctx -> {
                    int raw    = Math.max(0, ctx.fieldForwardPowerByName(cardName));
                    int damage = roundUp ? halfPowerDamage(raw) : (raw / 2 / 1000) * 1000;
                    String dir = roundUp ? "up" : "down";
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (half of " + cardName + "'s power, round " + dir + ")");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("halfitspower") != null) {
                boolean roundUp = "up".equalsIgnoreCase(exprM.group("halfitsrounding"));
                String dir = roundUp ? "up" : "down";
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — Deal damage equal to half of its power (round " + dir + ")");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> {
                        int raw = Math.max(0, ctx.effectiveTargetPower(t));
                        ctx.damageTarget(t, roundUp ? halfPowerDamage(raw) : (raw / 2 / 1000) * 1000);
                    });
                    sortedByIdxDesc(ts, false).forEach(t -> {
                        int raw = Math.max(0, ctx.effectiveTargetPower(t));
                        ctx.damageTarget(t, roundUp ? halfPowerDamage(raw) : (raw / 2 / 1000) * 1000);
                    });
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("itspower") != null) {
                int subtract = exprM.group("minus") != null ? Integer.parseInt(exprM.group("minus")) : 0;
                String logSuffix = subtract > 0 ? " — Deal damage equal to its power minus " + subtract
                                                 : " — Deal damage equal to its power";
                return ctx -> {
                    ctx.logEntry(choosePrefix + logSuffix);
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, Math.max(0, ctx.effectiveTargetPower(t) - subtract)));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, Math.max(0, ctx.effectiveTargetPower(t) - subtract)));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("dullforward") != null) {
                return ctx -> {
                    int damage = Math.max(0, ctx.dullForwardCostPower());
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (dull Forward cost power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("discardedfwd") != null) {
                return ctx -> {
                    int damage = Math.max(0, ctx.lastDiscardedForwardPower());
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (discarded Forward's power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("bzcostfwd") != null) {
                return ctx -> {
                    int damage = Math.max(0, ctx.bzCostForwardPower());
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (BZ-cost Forward's power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("card") != null) {
                String cardName = exprM.group("card").trim();
                return ctx -> {
                    int damage = Math.max(0, ctx.fieldForwardPowerByName(cardName));
                    ctx.logEntry(choosePrefix + " — Deal " + damage + " damage (" + cardName + "'s power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Activate + Gain control (EOT) followup (must precede plain Activate) ---
        if (FOLLOWUP_ACTIVATE_AND_GAIN_CONTROL_EOT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Activate & Gain control until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.activateTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.activateTarget(t));
                ts.forEach(t -> ctx.gainControlOfForward(t, "endOfTurn", true));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Gain control while named card on field ---
        Matcher gcWhileM = FOLLOWUP_GAIN_CONTROL_WHILE_CARD.matcher(primaryFollowup);
        if (gcWhileM.find()) {
            String condCard = gcWhileM.group("condCard").trim();
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Gain control while " + condCard + " is on field");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.gainControlOfForward(t, "whileCardOnField:" + condCard, false));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Gain control until EOT ---
        if (FOLLOWUP_GAIN_CONTROL_EOT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Gain control until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.gainControlOfForward(t, "endOfTurn", false));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Gain control (permanent) ---
        if (FOLLOWUP_GAIN_CONTROL.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Gain control");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.gainControlOfForward(t, "permanent", false));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot-be-chosen followups (gains form, then both, Summons, abilities) ---
        {   // scoped block so scope-parsing locals don't leak
            String fp = primaryFollowup;
            Matcher gcM = FOLLOWUP_GAINS_CANNOT_BE_CHOSEN.matcher(fp);
            if (!gcM.find()) gcM = null;
            boolean chosenBoth      = gcM != null || FOLLOWUP_CANNOT_BE_CHOSEN_BOTH.matcher(fp).find();
            boolean chosenSummons   = chosenBoth  || (gcM == null && FOLLOWUP_CANNOT_BE_CHOSEN_SUMMONS.matcher(fp).find());
            boolean chosenAbilities = chosenBoth  || (gcM == null && FOLLOWUP_CANNOT_BE_CHOSEN_ABILITIES.matcher(fp).find());
            if (chosenSummons || chosenAbilities) {
                final boolean bs = chosenSummons, ba = chosenAbilities;
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — Cannot be chosen by opponent's"
                            + (bs && ba ? " Summons or abilities" : bs ? " Summons" : " abilities"));
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    ts.forEach(t -> ctx.shieldCannotBeChosen(t, bs, ba));
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Cannot-be-returned-to-hand followup ("During this turn, it cannot be returned…") ---
        if (FOLLOWUP_CANNOT_BE_RETURNED_TO_HAND.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cannot be returned to owner's hand by opponent this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.boostTarget(t, 0,
                        EnumSet.of(CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP)));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Activate + Negate damage followup (must precede plain Activate to avoid partial match) ---
        if (FOLLOWUP_ACTIVATE_AND_NEGATE_DAMAGE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Activate & Negate damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.activateTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.activateTarget(t));
                ts.forEach(ctx::negateAllDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Negate all damage followup ---
        if (FOLLOWUP_NEGATE_DAMAGE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Negate damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::negateAllDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull-or-Activate toggle followup (must precede FOLLOWUP_ACTIVATE/DULL since it contains both) ---
        if (FOLLOWUP_DULL_OR_ACTIVATE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Dull or Activate (toggle)");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.toggleTargetDullActivate(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.toggleTargetDullActivate(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Activate followup ---
        if (FOLLOWUP_ACTIVATE.matcher(primaryFollowup).find()) {
            // Detect "It gains +N power [traits] until end of turn" secondary and apply inline.
            final int activateBoost;
            final EnumSet<CardData.Trait> activateTraits;
            final Consumer<GameContext> activateSecondary;
            {
                Matcher bm = secondaryText != null ? FOLLOWUP_POWER_BOOST.matcher(secondaryText) : null;
                if (bm == null) { bm = secondaryText != null ? FOLLOWUP_POWER_BOOST_UNTIL.matcher(secondaryText) : null; }
                if (bm != null && bm.find()) {
                    activateBoost      = Integer.parseInt(bm.group(1));
                    activateTraits     = parseTraits(bm.group(2));
                    activateSecondary  = null;
                } else {
                    activateBoost      = 0;
                    activateTraits     = EnumSet.noneOf(CardData.Trait.class);
                    activateSecondary  = secondary;
                }
            }
            String activateLogSuffix = activateBoost > 0 ? boostLogSuffix(activateBoost, activateTraits) : "";
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Activate" + activateLogSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.activateTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.activateTarget(t));
                if (activateBoost > 0) {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, activateBoost, activateTraits));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, activateBoost, activateTraits));
                } else if (activateSecondary != null) {
                    activateSecondary.accept(ctx);
                }
            };
        }

        // --- Dull-or-Freeze followup (must precede FOLLOWUP_DULL since it contains "Dull it") ---
        if (FOLLOWUP_DULL_OR_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Dull or Freeze");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.dullOrFreezeTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.dullOrFreezeTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull followup ---
        if (FOLLOWUP_DULL.matcher(primaryFollowup).find()
                && !FOLLOWUP_DULL_AND_FREEZE.matcher(primaryFollowup).find()
                && !FOLLOWUP_DULL_OR_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Dull");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.dullTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.dullTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull + Freeze followup ---
        if (FOLLOWUP_DULL_AND_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Dull & Freeze");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.dullAndFreezeTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.dullAndFreezeTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Freeze followup ---
        if (FOLLOWUP_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Freeze");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.freezeTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.freezeTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Break followup ---
        if (FOLLOWUP_BREAK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Break");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.breakTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.breakTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Loses all its abilities and its power becomes N until end of turn" (Wakka 1-216S) ---
        Matcher loseAndBecomeM = FOLLOWUP_LOSE_ABILITIES_AND_POWER_BECOMES.matcher(primaryFollowup);
        if (loseAndBecomeM.find()) {
            int targetPower = Integer.parseInt(loseAndBecomeM.group("power"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Lose all abilities, base power becomes "
                        + targetPower + " until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(ctx::targetLoseAllAbilitiesUntilEndOfTurn);
                sortedByIdxDesc(ts, false).forEach(ctx::targetLoseAllAbilitiesUntilEndOfTurn);
                // Descending order: dropping to the new power can break a Forward, which shifts
                // the indices of every target above it in the same zone.
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.setTargetBasePower(t, targetPower));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.setTargetBasePower(t, targetPower));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Lose all abilities until end of turn followup ---
        if (FOLLOWUP_LOSE_ALL_ABILITIES_EOT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Lose all abilities until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(ctx::targetLoseAllAbilitiesUntilEndOfTurn);
                sortedByIdxDesc(ts, false).forEach(ctx::targetLoseAllAbilitiesUntilEndOfTurn);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Remove them from the game. If these cards are of the same card type, also draw N card(s)." ---
        Matcher rfpSameTypeDrawM = FOLLOWUP_RFP_IF_SAME_TYPE_DRAW.matcher(followup);
        if (rfpSameTypeDrawM.find()) {
            int drawCount = Integer.parseInt(rfpSameTypeDrawM.group("count"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Remove From Game (if same type, draw " + drawCount + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                java.util.Set<String> typesSeen = new java.util.HashSet<>();
                for (ForwardTarget t : ts) {
                    CardData card = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
                    if (card != null) typesSeen.add(card.type().toLowerCase(java.util.Locale.ROOT));
                }
                sortedByIdxDesc(ts, true) .forEach(ctx::removeTargetFromGame);
                sortedByIdxDesc(ts, false).forEach(ctx::removeTargetFromGame);
                if (!ts.isEmpty() && typesSeen.size() == 1) ctx.drawCards(drawCount);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Remove from game + named card followup (e.g. "Remove it and Shuyin from the game") ---
        Matcher rfgNamedM = FOLLOWUP_REMOVE_FROM_GAME_AND_NAMED.matcher(primaryFollowup);
        if (rfgNamedM.find()) {
            String alsoNamed = rfgNamedM.group("named").trim();
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Remove From Game (+ " + alsoNamed + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.removeTargetFromGame(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.removeTargetFromGame(t));
                ctx.removeNamedCardFromGame(alsoNamed);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Remove it from the game for as long as [Name] is on the field." (Necron) ---
        // Must precede the plain remove-from-game followup, whose pattern is a prefix of this one.
        Matcher rfgWhileM = FOLLOWUP_REMOVE_FROM_GAME_WHILE_ON_FIELD.matcher(primaryFollowup);
        if (rfgWhileM.find()) {
            String watcherName = rfgWhileM.group("name").trim();
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Remove from game while " + watcherName + " is on the field");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.removeTargetFromGameWhileNamedCardOnField(t, watcherName));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.removeTargetFromGameWhileNamedCardOnField(t, watcherName));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Optional remove from game followup ---
        // Must precede the plain remove-from-game followup, whose pattern is a suffix of this one
        // and would remove the card without asking. Declining fizzles the effect so that an
        // enclosing "If you do so, …" sequence correctly skips its payoff (8-147S Fordola).
        if (FOLLOWUP_MAY_REMOVE_FROM_GAME.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — You may Remove From Game");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone, bothZones,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty() || !ctx.promptYouMay("Remove the chosen card from the game?")) {
                    ctx.logEntry("  declined — nothing removed");
                    ctx.markEffectFizzled();
                    return;
                }
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.removeTargetFromGame(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.removeTargetFromGame(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Remove from game followup ---
        if (FOLLOWUP_REMOVE_FROM_GAME.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Remove From Game");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone, bothZones,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.removeTargetFromGame(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.removeTargetFromGame(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Play onto field followup ---
        // --- "If its cost is equal to or less than the number of Job X you control, play it onto the field." ---
        // Must be checked before the generic PlayOntoField handler so the condition is enforced.
        Matcher costLeJobM = FOLLOWUP_PLAY_IF_COST_LE_JOB_COUNT.matcher(primaryFollowup);
        if (costLeJobM.matches()) {
            String condJob = costLeJobM.group("job").trim();
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Play onto Field if cost ≤ count of Job " + condJob + " you control");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                int jobCount = ctx.countSelfFieldCards(true, true, true, condJob, null);
                for (ForwardTarget t : sortedByIdxDesc(ts, true) .collect(java.util.stream.Collectors.toList())) {
                    CardData card = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
                    if (card != null && card.cost() <= jobCount) ctx.playTargetOntoField(t);
                }
                for (ForwardTarget t : sortedByIdxDesc(ts, false).collect(java.util.stream.Collectors.toList())) {
                    CardData card = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
                    if (card != null && card.cost() <= jobCount) ctx.playTargetOntoField(t);
                }
            };
        }

        if (FOLLOWUP_PLAY_ONTO_FIELD.matcher(primaryFollowup).find()) {
            // Check for "When it enters the field, if it is [cond], [inner]" conditional secondary.
            // Peek at the chosen card's data before playing so we can evaluate the condition after.
            final Predicate<CardData> etfCond;
            final Consumer<GameContext> etfInner;
            final String etfInnerText;
            if (secondaryText != null) {
                Matcher etfM = FOLLOWUP_PLAY_ONTO_FIELD_WHEN_ENTERS_CONDITIONAL.matcher(secondaryText);
                if (etfM.matches()) {
                    Predicate<CardData> parsedCond = parseRevealCondition(etfM.group("cond").trim());
                    String innerTxt = etfM.group("inner").trim();
                    Consumer<GameContext> inner = parsedCond != null ? parse(innerTxt, source) : null;
                    etfCond      = (parsedCond != null && inner != null) ? parsedCond : null;
                    etfInner     = (parsedCond != null && inner != null) ? inner      : null;
                    etfInnerText = (parsedCond != null && inner != null) ? innerTxt   : null;
                } else {
                    etfCond = null; etfInner = null; etfInnerText = null;
                }
            } else {
                etfCond = null; etfInner = null; etfInnerText = null;
            }
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Play onto Field");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                List<CardData> chosenCards = new ArrayList<>();
                if (etfCond != null) {
                    for (ForwardTarget t : ts) {
                        CardData c = zone != null
                                ? (t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx()))
                                : null;
                        if (c != null) chosenCards.add(c);
                    }
                }
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.playTargetOntoField(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.playTargetOntoField(t));
                if (etfCond != null && etfInner != null) {
                    boolean anyMatched = chosenCards.stream().anyMatch(etfCond);
                    if (anyMatched) {
                        ctx.logEntry("ETF Condition met — " + etfInnerText);
                        etfInner.accept(ctx);
                    }
                } else if (secondary != null) {
                    secondary.accept(ctx);
                }
            };
        }

        // --- Add to hand followup ---
        if (FOLLOWUP_ADD_TO_HAND.matcher(primaryFollowup).find()) {
            // Detect a conditional secondary that depends on the added card, e.g.
            // "If it is a Card Name Tifa, …" or "If the added card is not a Category II card, …".
            // When matched, the inner effect runs only if the chosen card satisfies the condition,
            // and the generic secondary parse is suppressed.
            final Predicate<CardData> addedCardCond;
            final Consumer<GameContext> conditionalInner;
            final String conditionalInnerText;
            if (secondaryText != null) {
                Matcher condM = FOLLOWUP_ADD_TO_HAND_CONDITIONAL_SECONDARY.matcher(secondaryText);
                if (condM.matches()) {
                    Predicate<CardData> cond = parseRevealCondition(condM.group("cond").trim());
                    String innerTxt = condM.group("inner").trim();
                    Consumer<GameContext> inner = cond != null ? parse(innerTxt, source) : null;
                    addedCardCond       = (cond != null && inner != null) ? cond  : null;
                    conditionalInner    = (cond != null && inner != null) ? inner : null;
                    conditionalInnerText = (cond != null && inner != null) ? innerTxt : null;
                } else {
                    addedCardCond        = null;
                    conditionalInner     = null;
                    conditionalInnerText = null;
                }
            } else {
                addedCardCond        = null;
                conditionalInner     = null;
                conditionalInnerText = null;
            }

            return ctx -> {
                ctx.logEntry(choosePrefix + " — Add to Hand");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Peek at chosen cards before they leave the Break Zone so the conditional
                // secondary can inspect them.
                List<CardData> chosenCards = new ArrayList<>();
                if (addedCardCond != null) {
                    for (ForwardTarget t : ts) {
                        CardData c = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
                        if (c != null) chosenCards.add(c);
                    }
                }
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.addTargetToHand(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.addTargetToHand(t));

                if (addedCardCond != null && conditionalInner != null) {
                    boolean anyMatched = chosenCards.stream().anyMatch(addedCardCond);
                    if (anyMatched) {
                        ctx.logEntry("Condition met (added card) — " + conditionalInnerText);
                        conditionalInner.accept(ctx);
                    }
                } else if (secondary != null) {
                    secondary.accept(ctx);
                }
            };
        }

        // --- Return it and [NamedCard] to their owners' hands ---
        Matcher retNamedM = FOLLOWUP_RETURN_AND_NAMED_TO_OWNERS_HAND.matcher(primaryFollowup);
        if (retNamedM.find()) {
            String alsoNamed = retNamedM.group("named").trim();
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Return to owner's hand (+ " + alsoNamed + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                returnTargetsToOwnersHand(ctx, ts);
                ctx.returnNamedCardToOwnersHand(alsoNamed);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "If its cost ≤ number of cards in your hand, return to owner's hand" (Leviathan EX Burst) ---
        if (FOLLOWUP_RETURN_IF_COST_LE_HAND.matcher(strippedPrimaryFollowup).matches()) {
            return ctx -> {
                int handSize = ctx.yourHandSize();
                ctx.logEntry(choosePrefix + " — Return to owner's hand if cost ≤ hand size (" + handSize + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Filter to eligible-by-cost targets first (indices are still valid here, before any
                // removal), then return them highest-index-first per side to avoid index shifting.
                List<ForwardTarget> toReturn = new ArrayList<>();
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    CardData card = t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx());
                    if (card == null || card.cost() > handSize) {
                        if (card != null) ctx.logEntry("Cost " + card.cost() + " > hand size " + handSize + " — condition not met");
                        continue;
                    }
                    ctx.logEntry("Cost " + card.cost() + " ≤ hand size " + handSize + " — returning to hand");
                    toReturn.add(t);
                }
                returnTargetsToOwnersHand(ctx, toReturn);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Return to owner's hand followup ---
        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(strippedPrimaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Return to owner's hand");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                Consumer<GameContext> doReturn = ctx2 -> {
                    returnTargetsToOwnersHand(ctx2, ts);
                    if (secondary != null) secondary.accept(ctx2);
                };
                if (followupIsOptional && !ts.isEmpty()) ctx.playerMayDoEffect("Return it to its owner's hand?", doReturn);
                else if (!followupIsOptional) doReturn.accept(ctx);
            };
        }

        // --- Return to your hand followup ---
        if (FOLLOWUP_RETURN_TO_YOUR_HAND.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Return to your hand");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true)
                        .filter(t -> t.zone() == ForwardTarget.CardZone.FORWARD)
                        .forEach(t -> ctx.returnP1ForwardToHand(t.idx()));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put at top or bottom of owner's deck followup (player chooses) ---
        if (FOLLOWUP_PUT_TOP_OR_BOTTOM_OF_DECK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Put at top or bottom of owner's deck (player chooses)");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) {
                        String cardName = ctx.p1Forward(t.idx()).name();
                        boolean toTop = ctx.askTopOrBottom(cardName);
                        if (toTop) ctx.returnP1ForwardToDeckTop(t.idx());
                        else       ctx.returnP1ForwardToDeckBottom(t.idx());
                    } else {
                        String cardName = ctx.p2Forward(t.idx()).name();
                        boolean toTop = ctx.askTopOrBottom(cardName);
                        if (toTop) ctx.returnP2ForwardToDeckTop(t.idx());
                        else       ctx.returnP2ForwardToDeckBottom(t.idx());
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put at bottom of owner's deck followup ---
        if (FOLLOWUP_PUT_BOTTOM_OF_DECK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Put at bottom of owner's deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardToDeckBottom(t.idx());
                    else          ctx.returnP2ForwardToDeckBottom(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Conditional power-vs-source "put on top of deck" followup (e.g. Wakka) ---
        Matcher ifPowerCmpSourceM = FOLLOWUP_IF_POWER_CMP_SOURCE_PUT_ON_DECK_TOP.matcher(primaryFollowup);
        if (ifPowerCmpSourceM.find()) {
            boolean wantLessOrEqual = "less".equalsIgnoreCase(ifPowerCmpSourceM.group("cmp"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Conditional power check vs source, put on top of owner's deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Find source card's current effective power on the field
                int sourcePower = source.power();
                outer:
                for (int pi = 0; pi <= 1; pi++) {
                    boolean p1 = pi == 0;
                    int cnt = p1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
                    for (int i = 0; i < cnt; i++) {
                        if ((p1 ? ctx.p1Forward(i) : ctx.p2Forward(i)) == source) {
                            sourcePower = ctx.effectiveTargetPower(
                                    new ForwardTarget(p1, i, ForwardTarget.CardZone.FORWARD));
                            break outer;
                        }
                    }
                }
                final int sp = sourcePower;
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    int targetPower = ctx.effectiveTargetPower(t);
                    boolean condMet = wantLessOrEqual ? targetPower <= sp : targetPower >= sp;
                    if (condMet) {
                        ctx.logEntry("  power " + targetPower + (wantLessOrEqual ? " ≤ " : " ≥ ") + sp + " — bounced to deck top");
                        if (t.isP1()) ctx.returnP1ForwardToDeckTop(t.idx());
                        else          ctx.returnP2ForwardToDeckTop(t.idx());
                    } else {
                        ctx.logEntry("  power " + targetPower + (wantLessOrEqual ? " > " : " < ") + sp + " — condition not met, no effect");
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put on top of your own deck followup (Break Zone salvage) ---
        // Must precede the owner's-deck followup below only in spirit — the two phrasings are
        // disjoint ("your deck" vs "its owner's deck") — but they are kept adjacent so the pair
        // stays visible as one decision.
        Matcher topOwnDeckM = FOLLOWUP_PUT_TOP_OF_YOUR_DECK.matcher(primaryFollowup);
        if (topOwnDeckM.find()) {
            boolean optional = topOwnDeckM.group("may") != null;
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Put on top of your deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (!ts.isEmpty() && optional
                        && !ctx.promptYouMay("Put the chosen card on top of your deck?")) {
                    ctx.logEntry("  declined — card stays in the Break Zone");
                } else {
                    // Descending index order: each removal shifts the Break Zone entries after it.
                    sortedByIdxDesc(ts, true).forEach(ctx::putBreakZoneTargetOnTopOfDeck);
                    sortedByIdxDesc(ts, false).forEach(ctx::putBreakZoneTargetOnTopOfDeck);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put on top of owner's deck followup ---
        if (FOLLOWUP_PUT_TOP_OF_DECK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Put on top of owner's deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardToDeckTop(t.idx());
                    else          ctx.returnP2ForwardToDeckTop(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put under top N cards of owner's deck followup ---
        Matcher underTopM = FOLLOWUP_PUT_UNDER_TOP_OF_DECK.matcher(primaryFollowup);
        if (underTopM.find()) {
            int underPos = underTopM.group("numword") != null ? 4 : 1;
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Put under top " + underPos + " card(s) of owner's deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardUnderDeckTop(t.idx(), underPos);
                    else          ctx.returnP2ForwardUnderDeckTop(t.idx(), underPos);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot block followup ---
        if (FOLLOWUP_CANNOT_BLOCK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cannot block this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardCannotBlock(t.idx());
                    else          ctx.setP2ForwardCannotBlock(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot be blocked followup ---
        if (FOLLOWUP_CANNOT_BE_BLOCKED.matcher(primaryFollowup).find()) {
            Matcher bm = FOLLOWUP_CANNOT_BE_BLOCKED.matcher(primaryFollowup);
            bm.find();
            String bCostStr  = bm.group("costval");
            String bCostCmp  = bm.group("costcmp");
            final int   bCostVal = bCostStr != null ? Integer.parseInt(bCostStr) : -1;
            final boolean bIsMore = "more".equalsIgnoreCase(bCostCmp);
            String bCostLabel = bCostVal >= 0 ? " by cost " + bCostVal + " or " + bCostCmp : "";
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cannot be blocked" + bCostLabel + " this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (bCostVal >= 0) {
                        if (t.isP1()) ctx.setP1ForwardCannotBeBlockedByCost(t.idx(), bCostVal, bIsMore);
                        else          ctx.setP2ForwardCannotBeBlockedByCost(t.idx(), bCostVal, bIsMore);
                    } else {
                        if (t.isP1()) ctx.setP1ForwardCannotBeBlocked(t.idx());
                        else          ctx.setP2ForwardCannotBeBlocked(t.idx());
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Only blocked by Forward of cost ≤ own cost followup ---
        if (FOLLOWUP_ONLY_BLOCKED_BY_COST_LE_OWN.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Can only be blocked by a Forward of cost ≤ its own this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    int ownCost = (t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx())).cost();
                    if (t.isP1()) ctx.setP1ForwardCannotBeBlockedByCost(t.idx(), ownCost + 1, true);
                    else          ctx.setP2ForwardCannotBeBlockedByCost(t.idx(), ownCost + 1, true);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot be blocked if element CP was paid followup ---
        if (FOLLOWUP_CANNOT_BE_BLOCKED_IF_ELEMENT_CP.matcher(primaryFollowup).find()) {
            Matcher bm = FOLLOWUP_CANNOT_BE_BLOCKED_IF_ELEMENT_CP.matcher(primaryFollowup);
            bm.find();
            final String elem    = bm.group("element");
            String eCostStr      = bm.group("costval");
            String eCostCmp      = bm.group("costcmp");
            final int   bCostVal = eCostStr != null ? Integer.parseInt(eCostStr) : -1;
            final boolean bIsMore = "more".equalsIgnoreCase(eCostCmp);
            String bCostLabel    = bCostVal >= 0 ? " by cost " + bCostVal + " or " + eCostCmp : "";
            return ctx -> {
                if (!ctx.wasElementCpPaid(elem)) {
                    ctx.logEntry(choosePrefix + " — " + elem + " CP not paid, skipping cannot-be-blocked bonus");
                    return;
                }
                ctx.logEntry(choosePrefix + " — Cannot be blocked" + bCostLabel + " this turn (" + elem + " CP paid)");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (bCostVal >= 0) {
                        if (t.isP1()) ctx.setP1ForwardCannotBeBlockedByCost(t.idx(), bCostVal, bIsMore);
                        else          ctx.setP2ForwardCannotBeBlockedByCost(t.idx(), bCostVal, bIsMore);
                    } else {
                        if (t.isP1()) ctx.setP1ForwardCannotBeBlocked(t.idx());
                        else          ctx.setP2ForwardCannotBeBlocked(t.idx());
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Must block followup ---
        if (FOLLOWUP_MUST_BLOCK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Must block if possible this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardMustBlock(t.idx());
                    else          ctx.setP2ForwardMustBlock(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot attack (this turn) followup ---
        if (FOLLOWUP_CANNOT_ATTACK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cannot attack this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardCannotAttack(t.idx());
                    else          ctx.setP2ForwardCannotAttack(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Must attack (this turn) followup ---
        if (FOLLOWUP_MUST_ATTACK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Must attack if possible this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardMustAttack(t.idx());
                    else          ctx.setP2ForwardMustAttack(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot attack or block (this turn) followup ---
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cannot attack or block this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) { ctx.setP1ForwardCannotAttack(t.idx()); ctx.setP1ForwardCannotBlock(t.idx()); }
                    else          { ctx.setP2ForwardCannotAttack(t.idx()); ctx.setP2ForwardCannotBlock(t.idx()); }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot attack or block until end of opponent's/next turn (persistent) followup ---
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_PERSISTENT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cannot attack or block until end of next turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardCannotAttackOrBlockPersistent(t.idx());
                    else          ctx.setP2ForwardCannotAttackOrBlockPersistent(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power-becomes followup: "Its power becomes N until end of turn" ---
        Matcher becomesM = FOLLOWUP_POWER_BECOMES.matcher(primaryFollowup);
        if (becomesM.find()) {
            int targetPower = Integer.parseInt(becomesM.group(1));
            return ctx -> {
                ctx.logEntry(choosePrefix + " → base power becomes " + targetPower);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Descending order: dropping to the new power can break a Forward, which shifts
                // the indices of every target above it in the same zone.
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.setTargetBasePower(t, targetPower));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.setTargetBasePower(t, targetPower));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost followup (standard order: "it/they gains +N power [, traits] until…") ---
        Matcher boostM = FOLLOWUP_POWER_BOOST.matcher(primaryFollowup);
        if (boostM.find()) {
            int boost = Integer.parseInt(boostM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostM.group(2));
            String logSuffix = boostLogSuffix(boost, traits);
            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost for each [element] [type] you control (must precede plain UNTIL boost) ---
        Matcher boostForEachM = FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH.matcher(primaryFollowup);
        if (boostForEachM.find()) {
            boolean untilPrefix = boostForEachM.group(1) != null;
            int    perUnit    = Integer.parseInt(untilPrefix ? boostForEachM.group(1) : boostForEachM.group(4));
            String srcElem    = untilPrefix ? boostForEachM.group("element") : boostForEachM.group("element2");
            String srcType    = (untilPrefix ? boostForEachM.group("chartype") : boostForEachM.group("chartype2")).toLowerCase();
            boolean cntFwd    = srcType.startsWith("forward") || srcType.startsWith("character");
            boolean cntBkp    = srcType.startsWith("backup")  || srcType.startsWith("character");
            boolean cntMon    = srcType.startsWith("monster")  || srcType.startsWith("character");
            String logSuffix  = " +" + perUnit + "×[" + (srcElem != null ? srcElem + " " : "") + boostForEachM.group("chartype") + " you control] until EOT";
            return ctx -> {
                int n      = ctx.countSelfFieldCards(cntFwd, cntBkp, cntMon, null, null, null, srcElem);
                int boost  = perUnit * n;
                ctx.logEntry(choosePrefix + logSuffix + " (n=" + n + ", boost=" + boost + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost for each Job [name] you control (must precede plain UNTIL boost) ---
        Matcher boostForEachJobM = FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_JOB.matcher(primaryFollowup);
        if (boostForEachJobM.find()) {
            boolean untilPrefixJ = boostForEachJobM.group("amount") != null;
            int    perUnitJ  = Integer.parseInt(untilPrefixJ ? boostForEachJobM.group("amount") : boostForEachJobM.group("amount2"));
            String jobBracket = untilPrefixJ ? boostForEachJobM.group("jobb") : boostForEachJobM.group("jobb2");
            String jobWritten = untilPrefixJ ? boostForEachJobM.group("jobw") : boostForEachJobM.group("jobw2");
            String jobTypeStr = untilPrefixJ ? boostForEachJobM.group("jobt") : boostForEachJobM.group("jobt2");
            String jobNameJ   = (jobBracket != null ? jobBracket : jobWritten).trim();
            boolean jwFwd = jobTypeStr == null || jobTypeStr.matches("(?i)Forwards?");
            boolean jwBkp = jobTypeStr == null || jobTypeStr.matches("(?i)Backups?");
            boolean jwMon = jobTypeStr == null || jobTypeStr.matches("(?i)Monsters?");
            String logSuffixJ = " +" + perUnitJ + "×[Job " + jobNameJ + " you control] until EOT";
            return ctx -> {
                int n     = ctx.countSelfFieldCards(jwFwd, jwBkp, jwMon, jobNameJ, null);
                int boost = perUnitJ * n;
                ctx.logEntry(choosePrefix + logSuffixJ + " (n=" + n + ", boost=" + boost + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Until…, it gains +N power for each [Name] Counter placed on [card]." (counter-scaled xValue) ---
        // Must be checked before FOLLOWUP_POWER_BOOST_UNTIL, which would match only the +N and drop the for-each.
        Matcher boostForEachCounterM = FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_COUNTER.matcher(primaryFollowup);
        if (boostForEachCounterM.find()) {
            int perUnit = Integer.parseInt(boostForEachCounterM.group("perunit"));
            String counterName = boostForEachCounterM.group("counterName").trim();
            return ctx -> {
                int boost = perUnit * xValue;
                ctx.logEntry(choosePrefix + " — +" + perUnit + " power ×" + xValue + " " + counterName + " Counter(s) = +" + boost + " until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Until…, it gains +N power for each point of damage you have received." ---
        // Must be checked before FOLLOWUP_POWER_BOOST_UNTIL, which matches on the +N and drops the for-each.
        Matcher boostUntilSelfDmgM = FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_SELF_DMG.matcher(primaryFollowup);
        if (boostUntilSelfDmgM.find()) {
            int perUnit = Integer.parseInt(boostUntilSelfDmgM.group("perunit"));
            return ctx -> {
                int dmgCount = ctx.p1DamageCount();
                int boost    = perUnit * dmgCount;
                ctx.logEntry(choosePrefix + " — +"+perUnit+" power ×" + dmgCount + " damage = +" + boost + " power until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost followup (until-prefix order: "Until…, it/they gains +N power [and traits]") ---
        Matcher boostUntilM = FOLLOWUP_POWER_BOOST_UNTIL.matcher(primaryFollowup);
        if (boostUntilM.find()) {
            int boost = Integer.parseInt(boostUntilM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostUntilM.group(2));
            String logSuffix = boostLogSuffix(boost, traits);

            // Detect "If its power has become N or less/more, return [name] to hand" secondary
            // and handle it inline so we have access to the target list for the power check.
            final String    crCard;
            final int       crThreshold;
            final boolean   crOrLess;
            final boolean   crToOwner;
            final Consumer<GameContext> boostSecondary;
            {
                Matcher crM = secondaryText != null ? CONDITIONAL_POWER_RETURN.matcher(secondaryText) : null;
                if (crM != null && crM.find()) {
                    crCard       = crM.group("name").trim();
                    crThreshold  = Integer.parseInt(crM.group("threshold"));
                    crOrLess     = "less".equalsIgnoreCase(crM.group("cmp"));
                    crToOwner    = crM.group("toowner") != null;
                    boostSecondary = null;
                } else {
                    crCard       = null;
                    crThreshold  = 0;
                    crOrLess     = false;
                    crToOwner    = false;
                    boostSecondary = secondary;
                }
            }

            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, traits));
                if (crCard != null) {
                    boolean condMet = ts.stream().anyMatch(t -> {
                        int p = ctx.effectiveTargetPower(t);
                        return crOrLess ? p <= crThreshold : p >= crThreshold;
                    });
                    if (condMet) {
                        ctx.logEntry("Condition met (power " + (crOrLess ? "≤ " : "≥ ") + crThreshold + "): return " + crCard + " to " + (crToOwner ? "owner's" : "your") + " hand");
                        if (crToOwner) ctx.returnNamedCardToOwnersHand(crCard);
                        else           ctx.returnNamedCardToYourHand(crCard);
                    } else {
                        ctx.logEntry("Condition not met: " + crCard + " stays (power " + (crOrLess ? "> " : "< ") + crThreshold + ")");
                    }
                } else if (boostSecondary != null) {
                    boostSecondary.accept(ctx);
                }
            };
        }

        // --- Trait-choice grant followup: "it gains [T1] or [T2] until end of turn" ---
        Matcher choiceM = FOLLOWUP_KEYWORD_GRANT_CHOICE.matcher(primaryFollowup);
        if (choiceM.find()) {
            String t1Name = choiceM.group("t1").trim();
            String t2Name = choiceM.group("t2").trim();
            EnumSet<CardData.Trait> t1Traits = parseTraits(t1Name);
            EnumSet<CardData.Trait> t2Traits = parseTraits(t2Name);
            return ctx -> {
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty()) return;
                String chosen = ctx.selectOption("Grant " + t1Name + " or " + t2Name + "?",
                        new String[]{t1Name, t2Name});
                EnumSet<CardData.Trait> traits = (chosen != null && chosen.equalsIgnoreCase(t2Name)) ? t2Traits : t1Traits;
                String logLabel = chosen != null ? chosen : t1Name;
                ctx.logEntry(choosePrefix + " — grants " + logLabel);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, 0, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, 0, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Keyword-only grant followup: "it/they gains Haste [and …] until end of turn" ---
        Matcher keywordM = FOLLOWUP_KEYWORD_GRANT.matcher(primaryFollowup);
        if (keywordM.find()) {
            EnumSet<CardData.Trait> traits = parseTraits(keywordM.group(1));
            String logSuffix = boostLogSuffix(0, traits);
            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, 0, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, 0, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Keyword-only grant followup (EOT prefix: "Until end of turn, it gains Haste [and …]") ---
        Matcher keywordUntilM = FOLLOWUP_KEYWORD_GRANT_UNTIL.matcher(primaryFollowup);
        if (keywordUntilM.find()) {
            EnumSet<CardData.Trait> traits = parseTraits(keywordUntilM.group(1));
            String logSuffix = boostLogSuffix(0, traits);
            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, 0, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, 0, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power / trait reduce followup (standard order: "it/they loses N power [, traits] until…") ---
        Matcher reduceM = FOLLOWUP_POWER_REDUCE.matcher(primaryFollowup);
        if (reduceM.find()) {
            int reduction = reduceM.group(1) != null ? Integer.parseInt(reduceM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceM.group(2));
            String logSuffix = reduceLogSuffix(reduction, traits);
            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power reduce for each card in your hand ("Until…, it/they loses N power for each card in your hand") ---
        Matcher reduceForEachHandM = FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH_HAND.matcher(primaryFollowup);
        if (reduceForEachHandM.find()) {
            int perCard = Integer.parseInt(reduceForEachHandM.group(1));
            return ctx -> {
                int n = ctx.yourHandSize();
                int reduction = perCard * n;
                ctx.logEntry(choosePrefix + " -" + perCard + "×[your hand] until EOT (n=" + n + ", reduction=" + reduction + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power reduce for each [element] [type] you control (must precede plain UNTIL reduce) ---
        Matcher reduceForEachM = FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH.matcher(primaryFollowup);
        if (reduceForEachM.find()) {
            boolean untilPrefix = reduceForEachM.group(1) != null;
            int    perUnit    = Integer.parseInt(untilPrefix ? reduceForEachM.group(1) : reduceForEachM.group(4));
            String srcElem    = untilPrefix ? reduceForEachM.group("element") : reduceForEachM.group("element2");
            String srcType    = (untilPrefix ? reduceForEachM.group("chartype") : reduceForEachM.group("chartype2")).toLowerCase();
            boolean cntFwd    = srcType.startsWith("forward") || srcType.startsWith("character");
            boolean cntBkp    = srcType.startsWith("backup")  || srcType.startsWith("character");
            boolean cntMon    = srcType.startsWith("monster")  || srcType.startsWith("character");
            String typeLabel  = untilPrefix ? reduceForEachM.group("chartype") : reduceForEachM.group("chartype2");
            String logSuffix  = " -" + perUnit + "×[" + (srcElem != null ? srcElem + " " : "") + typeLabel + " you control] until EOT";
            return ctx -> {
                int n         = ctx.countSelfFieldCards(cntFwd, cntBkp, cntMon, null, null, null, srcElem);
                int reduction = perUnit * n;
                ctx.logEntry(choosePrefix + logSuffix + " (n=" + n + ", reduction=" + reduction + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power / trait reduce followup (until-prefix order: "Until…, it/they loses N power [and traits]") ---
        Matcher reduceUntilM = FOLLOWUP_POWER_REDUCE_UNTIL.matcher(primaryFollowup);
        if (reduceUntilM.find()) {
            int reduction = reduceUntilM.group(1) != null ? Integer.parseInt(reduceUntilM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceUntilM.group(2));
            String logSuffix = reduceLogSuffix(reduction, traits);
            return ctx -> {
                ctx.logEntry(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Opponent discard followup ---
        Matcher discardM = OPPONENT_DISCARD.matcher(primaryFollowup);
        if (discardM.find()) {
            int count = Integer.parseInt(discardM.group(1));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Opponent discards " + count);
                ctx.forceOpponentDiscard(count);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Self-referential boost followup: "<cardName> gains [+N power] [traits] until end of turn" ---
        if (source != null) {
            Matcher selfM = SELF_POWER_BOOST.matcher(primaryFollowup);
            if (selfM.find() && selfM.group("selfsubject").trim().equalsIgnoreCase(source.name())) {
                int boost = selfM.group("selfamount") != null ? Integer.parseInt(selfM.group("selfamount")) : 0;
                EnumSet<CardData.Trait> traits = parseTraits(selfM.group("selftraits"));
                String logSuffix = boostLogSuffix(boost, traits);
                return ctx -> {
                    ctx.logEntry(choosePrefix + " — " + source.name() + logSuffix);
                    ctx.boostSourceForward(source, boost, traits);
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Cancel effect followup (counters a Summon on the stack) ---
        if (FOLLOWUP_CANCEL_EFFECT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Cancel its effect");
                ctx.cancelStackEntry();
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next incoming damage = 0 followup ---
        if (FOLLOWUP_SHIELD_NEXT_DMG_ZERO.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: next damage becomes 0");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldNextIncomingDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next ability/summon damage reduced by N followup ---
        Matcher shieldAbilRedM = FOLLOWUP_SHIELD_NEXT_ABILITY_DMG_REDUCTION.matcher(primaryFollowup);
        if (shieldAbilRedM.find()) {
            int reduction = Integer.parseInt(shieldAbilRedM.group("reduction"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: next ability/summon damage reduced by " + reduction);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.shieldNextAbilityIncomingDamageReduction(t, reduction));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next incoming damage reduced by N followup ---
        Matcher shieldRedM = FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION.matcher(primaryFollowup);
        if (shieldRedM.find()) {
            int reduction = Integer.parseInt(shieldRedM.group("reduction"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: next damage reduced by " + reduction);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.shieldNextIncomingDamageReduction(t, reduction));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Incoming damage increased by N followup ---
        Matcher dmgIncM = FOLLOWUP_DEBUFF_INCOMING_DMG_INCREASE.matcher(primaryFollowup);
        if (dmgIncM.find()) {
            int amount = Integer.parseInt(dmgIncM.group("amount"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Debuff: incoming damage increased by " + amount);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.debuffIncomingDamageIncrease(t, amount));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next outgoing damage = 0 followup ---
        if (FOLLOWUP_SHIELD_NEXT_OUTGOING_ZERO.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: next outgoing damage becomes 0");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldNextOutgoingDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Per-card non-lethal protection followup ---
        if (FOLLOWUP_SHIELD_NONLETHAL.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: damage less than power becomes 0 this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldNonLethal);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "It gains ability-damage shield" followup ---
        if (FOLLOWUP_GAINS_SHIELD_ABILITY_ONLY.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: gains ability-damage nullification until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldAbilityOnlyDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Cannot be broken" until end of turn ---
        if (FOLLOWUP_CANNOT_BE_BROKEN.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: cannot be broken until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldCannotBeBroken);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "It cannot be broken this turn." (simple form) ---
        if (FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: cannot be broken this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldCannotBeBroken);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Cannot be broken by opposing Summons or abilities that don't deal damage" ---
        if (FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Shield: cannot be broken by opposing non-damage effects this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldCannotBeBrokenByNonDmg);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Breaktouch battle: "When this Forward deals battle damage to a Forward, break that Forward" until EOT ---
        if (FOLLOWUP_GAINS_BREAKTOUCH_BATTLE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Breaktouch (battle damage) until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldBreaktouchBattle);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- End-of-turn conditional damage followup ---
        // e.g. "At the end of this turn, if you control <name>, deal it N damage."
        Matcher eotDmgM = FOLLOWUP_END_OF_TURN_COND_DAMAGE.matcher(primaryFollowup);
        if (eotDmgM.find()) {
            String condCard = eotDmgM.group("cardName").trim();
            int damage      = Integer.parseInt(eotDmgM.group("damage"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — End of turn: if you control " + condCard + ", deal " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (!ts.isEmpty()) {
                    ctx.addEndOfTurnEffect(endCtx -> {
                        if (endCtx.abilityUserControlsCard(condCard)) {
                            sortedByIdxDesc(ts, true) .forEach(t -> endCtx.damageTarget(t, damage));
                            sortedByIdxDesc(ts, false).forEach(t -> endCtx.damageTarget(t, damage));
                        } else {
                            endCtx.logEntry("End-of-turn damage skipped: " + condCard + " not on field");
                        }
                    });
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Select a Job. It gains that Job until the end of the turn." ---
        // Checked against the full followup (before dot-split) so both sentences are seen together.
        if (FOLLOWUP_SELECT_JOB_GRANT.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Select a Job, grant until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty()) return;
                String job = ctx.selectJobFromDatabase();
                if (job == null || job.isBlank()) return;
                ts.forEach(t -> ctx.grantJobUntilEndOfTurn(t, job));
            };
        }

        // --- "If it deals damage to a Forward this turn, the damage increases by N instead." ---
        Matcher outBoostM = FOLLOWUP_OUTGOING_DMG_BOOST_THIS_TURN.matcher(primaryFollowup);
        if (outBoostM.find()) {
            int amount = Integer.parseInt(outBoostM.group("amount"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — Outgoing damage +" + amount + " to Forwards this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.boostForwardOutgoingDamageThisTurn(t, amount));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Until the end of the turn, it also becomes a Forward with N power." (Gau) ---
        Matcher becomeFwdM = BECOME_FORWARD_UNTIL_EOT_PATTERN.matcher(primaryFollowup);
        if (becomeFwdM.find()) {
            int power = Integer.parseInt(becomeFwdM.group("power"));
            return ctx -> {
                ctx.logEntry(choosePrefix + " — becomes a Forward with " + power + " power until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.makeTargetTemporaryForward(t, power));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // Recognised "Choose" header but followup not yet implemented
        Consumer<GameContext> warnEffect = ctx -> ctx.logEntry(
                "[ActionResolver] Choose effect — followup not yet implemented: " + followup);
        return secondary == null ? warnEffect : warnEffect.andThen(secondary);
    }
    static Consumer<GameContext> tryParseChooseForwardDoubleIncomingThisTurn(String text) {
        if (!CHOOSE_FORWARD_DOUBLE_INCOMING_THIS_TURN.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Choose 1 Forward — incoming damage doubled this turn");
            List<ForwardTarget> ts = ctx.selectCharacters(1, false, false, false,
                    null, null, -1, null, -1, null, true, false, false,
                    null, null, null, null, false, null, false);
            if (!ts.isEmpty()) ctx.doubleForwardIncomingDamageThisTurn(ts.get(0));
        };
    }
    static Consumer<GameContext> tryParseChooseForwardDoubleNextOutgoing(String text) {
        Matcher m = CHOOSE_FORWARD_DOUBLE_NEXT_OUTGOING.matcher(text);
        if (!m.find()) return null;
        String rawJob = m.group("job");
        final String jobFilter = rawJob != null ? rawJob.trim() : null;
        return ctx -> {
            String label = jobFilter != null ? "Job " + jobFilter + " " : "";
            ctx.logEntry("Choose 1 " + label + "Forward — next outgoing damage doubled this turn");
            List<ForwardTarget> ts = ctx.selectCharacters(1, false, false, false,
                    null, null, -1, null, -1, null, true, false, false,
                    jobFilter, null, null, null, false, null, false);
            if (!ts.isEmpty()) ctx.doubleForwardNextOutgoingDamage(ts.get(0));
        };
    }
    /**
     * Parses "Choose 1 card removed by [SourceName]'s ability. Put it into the Break Zone." —
     * requires the named card to be the ability source so the exile tracking can be looked up
     * by instance identity.
     */
    static Consumer<GameContext> tryParseChooseCardRemovedBySourceToBz(String text, CardData source) {
        Matcher m = CHOOSE_CARD_REMOVED_BY_SOURCE_TO_BZ.matcher(text.trim());
        if (!m.matches()) return null;
        if (source == null || !m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.putCardRemovedBySourceIntoBreakZone(source);
    }
    /** Parses "select 1 [Forward|Backup|Monster|Character] you control. Put it into the Break Zone." */
    static Consumer<GameContext> tryParseSelectControlledCharacterToBz(String text) {
        Matcher m = SELECT_1_CHARACTER_YOU_CONTROL_TO_BZ.matcher(text.trim());
        if (!m.matches()) return null;
        String type    = m.group("type");
        boolean inclFwd = type.matches("(?i)Forward|Character");
        boolean inclBkp = type.matches("(?i)Backup|Character");
        boolean inclMon = type.matches("(?i)Monster|Character");
        return ctx -> {
            ctx.logEntry("Effect: select 1 " + type + " you control → Break Zone");
            ctx.selectControlledTypeAndBreak(inclFwd, inclBkp, inclMon);
        };
    }
    /**
     * Parses a bare "Cancel its/their effect(s)." — the consequent of a reactive "chosen by opponent's
     * Summons or abilities" auto-ability whose optional cost was already paid upstream (Phantasmal
     * Girl, Regis, Tama, Yuna). Unconditionally cancels the in-progress selection.
     */
    static Consumer<GameContext> tryParseCancelChosenTargetBare(String text) {
        if (!CANCEL_CHOSEN_TARGET_BARE.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: cancel the effect choosing your Character(s)");
            ctx.cancelChosenSelection();
        };
    }
    /**
     * Parses "Choose 1 [ability type(s)] [optional 'that has only one target']. You may choose
     * another target to become the new target (...)."
     */
    static Consumer<GameContext> tryParseRedirectAbilityTarget(String text) {
        Matcher m = REDIRECT_ABILITY_TARGET.matcher(text.trim());
        if (!m.find()) return null;
        String types = m.group("types").trim();
        java.util.function.Predicate<StackEntry> filter = parseAbilityTypeFilter(types);
        String prompt = "Choose 1 " + types + " to redirect:";
        return ctx -> {
            ctx.logEntry("Effect: Redirect target of " + types + " on stack");
            ctx.redirectAbilityTarget(filter, prompt);
        };
    }
    /**
     * Parses "Select 1 number." abilities where the selected number is used as a cost filter
     * for a follow-on mass-field effect, damage sweep, or attack restriction.
     *
     * <p>Supported inner effects (appearing after "Select 1 number."):
     * <ul>
     *   <li>Any mass field action (Break/Dull/Freeze/Dull and Freeze) "of that cost" or
     *       "of the same cost as the selected number" — delegates to
     *       {@link GameContext#applyMassFieldEffect} with the chosen number as {@code costVal}.</li>
     *   <li>"All Forwards of that cost cannot attack this turn."</li>
     *   <li>"Deal N damage to all the Forwards of the same cost as the selected number [opponent controls]."</li>
     * </ul>
     * <p>Dual-selection variant: when "Your opponent selects 1 number." follows immediately,
     * both P1's and P2's numbers are obtained and the inner "Break all Forwards of cost equal
     * to either number." is applied for each.
     */
    static Consumer<GameContext> tryParseSelectNumber(String text, CardData source) {
        Matcher hm = SELECT_NUMBER_HEADER.matcher(text);
        if (!hm.find()) return null;

        String rest = text.substring(hm.end()).trim();

        // Dual-selection variant: "Your opponent selects 1 number."
        Matcher om = SELECT_NUMBER_OPPONENT_ALSO.matcher(rest);
        boolean dualSelect = om.find();
        if (dualSelect) rest = rest.substring(om.end()).trim();

        final String innerText = rest;

        // --- Dual variant: "Break all Forwards of cost equal to either number." ---
        // P1 selects via dialog; the opponent AI picks the cost most common among P1's forwards.
        if (dualSelect && SELECT_NUMBER_INNER_EITHER_BREAK.matcher(innerText).find()) {
            return ctx -> {
                int n1 = ctx.selectNumber(0, 11, "Select a number:");
                ctx.logEntry("Effect: Player selects number " + n1);
                int n2 = aiMostCommonP1ForwardCost(ctx);
                ctx.logEntry("Effect: Opponent selects number " + n2 + " (AI)");
                ctx.logEntry("Effect: Break all Forwards of cost " + n1
                        + (n1 != n2 ? " or " + n2 : ""));
                ctx.applyMassFieldEffect(GameContext.MassAction.BREAK,
                        true, false, false, false, false, null, n1, null, -1, null, null);
                if (n1 != n2)
                    ctx.applyMassFieldEffect(GameContext.MassAction.BREAK,
                            true, false, false, false, false, null, n2, null, -1, null, null);
            };
        }

        // --- "All Forwards of that cost cannot attack this turn." ---
        if (SELECT_NUMBER_INNER_CANNOT_ATTACK.matcher(innerText).find()) {
            return ctx -> {
                int n = ctx.selectNumber(0, 11, "Select a number:");
                ctx.logEntry("Effect: Select number " + n
                        + " — all Forwards of cost " + n + " cannot attack this turn");
                for (int i = 0; i < ctx.p1ForwardCount(); i++)
                    if (ctx.p1Forward(i).cost() == n) ctx.setP1ForwardCannotAttack(i);
                for (int i = 0; i < ctx.p2ForwardCount(); i++)
                    if (ctx.p2Forward(i).cost() == n) ctx.setP2ForwardCannotAttack(i);
            };
        }

        // --- General case: substitute the selected number into the inner text and re-parse. ---
        // Supported placeholders:
        //   "of that cost"                         → "of cost N"
        //   "the same cost as the selected number" → "cost N"  (e.g. inside DEAL_DAMAGE_TO_FORWARDS)
        String probeText = innerText
                .replaceAll("(?i)of\\s+that\\s+cost\\b", "of cost 3")
                .replaceAll("(?i)the\\s+same\\s+cost\\s+as\\s+the\\s+selected\\s+number", "cost 3");
        if (parse(probeText, source) == null) return null;  // inner effect not yet supported

        return ctx -> {
            int n = ctx.selectNumber(0, 11, "Select a number:");
            ctx.logEntry("Effect: Select number " + n);
            String resolved = innerText
                    .replaceAll("(?i)of\\s+that\\s+cost\\b", "of cost " + n)
                    .replaceAll("(?i)the\\s+same\\s+cost\\s+as\\s+the\\s+selected\\s+number",
                            "cost " + n);
            Consumer<GameContext> effect = parse(resolved, source);
            if (effect != null) {
                effect.accept(ctx);
            } else {
                ctx.logEntry("[ActionResolver] SelectNumber: inner effect not parseable: " + resolved);
            }
        };
    }
    /**
     * Parses "Your opponent selects N [condition] [type] [of cost C or less/more] they control
     * [sep] followup". Supported followups: "Put it into the Break Zone" and "dull/dulls it".
     */
    static Consumer<GameContext> tryParseOpponentSelects(String text) {
        Matcher m = OPPONENT_SELECTS_PATTERN.matcher(text);
        if (!m.find()) return null;

        int     count     = Integer.parseInt(m.group("count"));
        String  condition = m.group("condition");
        String  element   = m.group("element");
        String  targets   = m.group("targets");
        String  tgtLower  = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        String  followup  = m.group("followup").trim();
        int     costVal   = m.group("cost") != null ? Integer.parseInt(m.group("cost")) : -1;
        String  costCmp   = m.group("costcmp") != null ? m.group("costcmp").toLowerCase() : null;

        String prefix = "Opponent selects " + count
                + (condition != null ? " " + condition : "")
                + (element   != null ? " " + element   : "")
                + " " + targets
                + (costVal >= 0 ? " of cost " + costVal + " or " + costCmp : "")
                + " (opponent)";

        if (FOLLOWUP_PUT_TO_BREAK_ZONE.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(prefix + " — Force to Break Zone");
                List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                        condition, element, costVal, costCmp, -1, null,
                        inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, false);
                sortedByIdxDesc(ts, false).forEach(ctx::forceTargetToBreakZone);
            };
        }

        if (FOLLOWUP_DULL.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(prefix + " — Dull");
                List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                        condition, element, costVal, costCmp, -1, null,
                        inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, false);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            };
        }

        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(prefix + " — Return to owner's hand");
                List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                        condition, element, costVal, costCmp, -1, null,
                        inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, false);
                sortedByIdxDesc(ts, false).forEach(t -> {
                    switch (t.zone()) {
                        case FORWARD -> { if (t.isP1()) ctx.returnP1ForwardToHand(t.idx());
                                          else          ctx.returnP2ForwardToHand(t.idx()); }
                        case BACKUP  -> { if (t.isP1()) ctx.returnP1BackupToHand(t.idx());
                                          else          ctx.returnP2BackupToHand(t.idx()); }
                        case MONSTER -> { if (t.isP1()) ctx.returnP1MonsterToHand(t.idx());
                                          else          ctx.returnP2MonsterToHand(t.idx()); }
                    }
                });
            };
        }

        return ctx -> ctx.logEntry(
                "[ActionResolver] Opponent selects — followup not yet implemented: " + followup);
    }
    static Consumer<GameContext> tryParseChooseForwardsGainAbilityEot(String text) {
        Matcher m = CHOOSE_FORWARDS_GAIN_ABILITY_EOT.matcher(text.trim());
        if (!m.matches()) return null;
        boolean upTo  = m.group("upto") != null;
        int     count = Integer.parseInt(m.group("count"));
        String  ability = m.group("ability").trim();
        return ctx -> {
            ctx.logEntry("Effect: choose " + (upTo ? "up to " : "") + count
                    + " Forward(s) — grant until end of turn: " + ability);
            List<ForwardTarget> ts = selectTargets(ctx, count, upTo, false, false, null, null, null, false,
                    -1, null, -1, null, true, false, false, null, null, null, null, false, null, false);
            for (ForwardTarget t : ts) ctx.grantEotActionAbility(t, ability);
        };
    }
    static Consumer<GameContext> tryParseChooseForwardPlacePetrification(String text) {
        if (!CHOOSE_FORWARD_PLACE_PETRIFICATION.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: choose 1 Forward — place 1 Petrification Counter (cannot attack/block; 《5》 to remove)");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false, false, false, null, null, null, false,
                    -1, null, -1, null, true, false, false, null, null, null, null, false, null, false);
            if (ts.isEmpty()) return;
            ForwardTarget t = ts.get(0);
            CardData fwd = t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx());
            if (fwd != null) ctx.placeCounters(fwd, "Petrification", 1);
        };
    }
    /**
     * Parses "Choose 1 Forward opponent controls. [Name] gains its Special Ability until the end of the turn.
     * You can use this ability without paying any cost but only once."
     * Copies every isSpecial() ability from the chosen Forward to {@code source} as a free, once-per-turn
     * temp ability (all costs removed) that expires at end of turn.
     */
    static Consumer<GameContext> tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(
            String text, CardData source) {
        Matcher m = CHOOSE_OPP_FWD_GAINS_SPECIAL_ABILITY_FREE_ONCE.matcher(text.trim());
        if (!m.matches()) return null;
        String logName = m.group("sourceName");
        return ctx -> {
            ctx.logEntry(logName + " — Choose 1 Forward opponent controls to copy its Special Ability");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false, true, false,
                    null, null, null, false, -1, null, -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            if (ts.isEmpty()) return;
            ForwardTarget t = ts.get(0);
            CardData chosen = t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx());
            if (chosen == null) return;
            List<ActionAbility> specials = chosen.actionAbilities().stream()
                    .filter(ActionAbility::isSpecial)
                    .collect(java.util.stream.Collectors.toList());
            if (specials.isEmpty()) {
                ctx.logEntry(chosen.name() + " has no Special Ability to copy");
                return;
            }
            for (ActionAbility original : specials)
                ctx.grantCopiedSpecialAbilityFreeOnce(source, original);
        };
    }
    /**
     * Parses "Choose as many [Type] [opponent controls] as [the] [CountSource] you control. [Dull/Activate] them."
     * The count is computed at resolution time from the acting player's field cards matching the count source.
     */
    static Consumer<GameContext> tryParseChooseAsManyAsFieldCount(String text, CardData source) {
        Matcher m = CHOOSE_AS_MANY_AS_FIELD_COUNT.matcher(text.trim());
        if (!m.matches()) return null;

        String targetTypeRaw = m.group("targetType").trim();
        String targetSide    = m.group("targetSide");
        String countSrc      = m.group("countSrc").trim();
        String followupText  = m.group("followup").trim();

        String tgtLow = targetTypeRaw.toLowerCase();
        boolean inclForwards = tgtLow.startsWith("forward") || tgtLow.startsWith("character");
        boolean inclBackups  = tgtLow.startsWith("backup")  || tgtLow.startsWith("character");
        boolean inclMonsters = tgtLow.startsWith("monster") || tgtLow.startsWith("character");

        boolean opponentOnly = targetSide != null && targetSide.toLowerCase().contains("opponent");
        boolean selfOnly     = !opponentOnly;

        String  countJobFilter = null;
        String  countCatFilter = null;
        boolean countFwds = true, countBkps = true, countMons = true;

        Matcher jbm = JOB_BRACKET_PATTERN.matcher(countSrc);
        if (jbm.find()) {
            countJobFilter = jbm.group(1).trim();
        } else if (countSrc.toLowerCase().startsWith("category ")) {
            String rest = countSrc.substring("category ".length()).trim();
            int sp = rest.indexOf(' ');
            if (sp >= 0) {
                countCatFilter = rest.substring(0, sp);
                String csType = rest.substring(sp + 1).trim().toLowerCase();
                countFwds = csType.startsWith("forward") || csType.startsWith("character");
                countBkps = csType.startsWith("backup")  || csType.startsWith("character");
                countMons = csType.startsWith("monster") || csType.startsWith("character");
            } else {
                countCatFilter = rest;
            }
        } else if (countSrc.toLowerCase().startsWith("job ")) {
            String rest = countSrc.substring("job ".length()).trim();
            countJobFilter = rest.replaceAll("(?i)\\s+(Forwards?|Backups?|Monsters?|Characters?)\\s*$", "").trim();
        } else {
            String csTypeLow = countSrc.toLowerCase().replaceAll("s$", "");
            if (csTypeLow.equals("forward") || csTypeLow.equals("backup")
                    || csTypeLow.equals("monster") || csTypeLow.equals("character")) {
                countFwds = csTypeLow.equals("forward") || csTypeLow.equals("character");
                countBkps = csTypeLow.equals("backup")  || csTypeLow.equals("character");
                countMons = csTypeLow.equals("monster") || csTypeLow.equals("character");
            } else {
                return null;
            }
        }

        boolean doActivate = FOLLOWUP_ACTIVATE.matcher(followupText).find();
        boolean doDull     = FOLLOWUP_DULL.matcher(followupText).find();
        boolean doFreeze   = !doActivate && !doDull && FOLLOWUP_FREEZE.matcher(followupText).find();
        if (!doActivate && !doDull && !doFreeze) return null;

        final String  fJob = countJobFilter, fCat = countCatFilter;
        final boolean fCFwds = countFwds, fCBkps = countBkps, fCMons = countMons;
        final boolean fOppOnly = opponentOnly, fSelfOnly = selfOnly;
        final boolean fFwds = inclForwards, fBkps = inclBackups, fMons = inclMonsters;
        final String  action = doActivate ? "Activate" : doDull ? "Dull" : "Freeze";
        final String  logPfx = "Choose up to as many " + targetTypeRaw
                + (targetSide != null ? " " + targetSide : " you control")
                + " as " + countSrc + " you control";

        return ctx -> {
            int count = ctx.countSelfFieldCards(fCFwds, fCBkps, fCMons, fJob, null, fCat);
            if (count <= 0) {
                ctx.logEntry(logPfx + " — count=0, nothing to choose");
                ctx.markEffectFizzled();
                return;
            }
            ctx.logEntry(logPfx + " (count=" + count + ") — " + action);
            List<ForwardTarget> ts = selectTargets(ctx, count, true,
                    fOppOnly, fSelfOnly, null, null, null, false,
                    -1, null, -1, null,
                    fFwds, fBkps, fMons, null, null, null, null, false, null, false);
            if (doActivate) {
                sortedByIdxDesc(ts, true) .forEach(ctx::activateTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::activateTarget);
            } else if (doDull) {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            } else {
                sortedByIdxDesc(ts, true) .forEach(ctx::freezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::freezeTarget);
            }
        };
    }
    /**
     * Parses "Choose up to the same number of Characters as the Job X in your Break Zone and/or
     * Job X you own removed from the game. [Dull/Activate/Freeze] them." (Jill 26-034L). The count
     * is computed at resolution time from the acting player's Break Zone and removed-from-game zone.
     */
    static Consumer<GameContext> tryParseChooseAsManyAsBzRfgJobCount(String text) {
        Matcher m = CHOOSE_AS_MANY_AS_BZ_RFG_JOB.matcher(text.trim());
        if (!m.matches()) return null;

        String targetTypeRaw = m.group("targetType").trim();
        String job           = m.group("job").trim();
        String followupText  = m.group("followup").trim();

        String tgtLow = targetTypeRaw.toLowerCase();
        final boolean inclForwards = tgtLow.startsWith("forward") || tgtLow.startsWith("character");
        final boolean inclBackups  = tgtLow.startsWith("backup")  || tgtLow.startsWith("character");
        final boolean inclMonsters = tgtLow.startsWith("monster") || tgtLow.startsWith("character");

        boolean doActivate = FOLLOWUP_ACTIVATE.matcher(followupText).find();
        boolean doDull     = FOLLOWUP_DULL.matcher(followupText).find();
        boolean doFreeze   = !doActivate && !doDull && FOLLOWUP_FREEZE.matcher(followupText).find();
        if (!doActivate && !doDull && !doFreeze) return null;

        final String  action = doActivate ? "Activate" : doDull ? "Dull" : "Freeze";
        final boolean fActivate = doActivate, fDull = doDull;
        final String  logPfx = "Choose up to as many " + targetTypeRaw
                + " as Job " + job + " in your Break Zone and/or removed from the game";
        return ctx -> {
            int count = ctx.countSelfBreakZoneAndRfgCards(null, job);
            if (count <= 0) {
                ctx.logEntry(logPfx + " — count=0, nothing to choose");
                ctx.markEffectFizzled();
                return;
            }
            ctx.logEntry(logPfx + " (count=" + count + ") — " + action);
            List<ForwardTarget> ts = selectTargets(ctx, count, true,
                    false, false, null, null, null, false,
                    -1, null, -1, null,
                    inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, false);
            if (fActivate) {
                sortedByIdxDesc(ts, true) .forEach(ctx::activateTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::activateTarget);
            } else if (fDull) {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            } else {
                sortedByIdxDesc(ts, true) .forEach(ctx::freezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::freezeTarget);
            }
        };
    }

    /**
     * Parses "Choose &lt;target&gt;. At the end of your opponent's turn, &lt;action&gt; it."
     * (28-043R Gi Nattak) — the target is picked when the ability resolves, the action lands at
     * the end of the opponent's next turn.
     *
     * <p>Neither half stands alone: the choose clause on its own silently drops the delayed
     * action, which is how this card used to resolve, and the delayed clause on its own has no
     * target. The chosen targets are captured from {@link GameContext#lastChosenTargets} and
     * closed over, so the queued effect acts on the cards picked now rather than re-selecting
     * later.
     */
    static Consumer<GameContext> tryParseChooseThenEndOfOppTurnAction(
            String text, CardData source, int xValue) {
        Matcher m = CHOOSE_THEN_END_OF_OPP_TURN_ACTION.matcher(text.trim());
        if (!m.find()) return null;

        final boolean upTo = m.group("upto") != null;
        final int count = Integer.parseInt(m.group("count"));
        final String actionText = m.group("action").trim();

        BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(actionText, xValue);
        if (action == null) return null;

        return ctx -> {
            ctx.logEntry("Choose " + (upTo ? "up to " : "") + count
                    + " Forward(s) opponent controls — " + actionText
                    + " at the end of your opponent's turn");
            List<ForwardTarget> chosen = List.copyOf(ctx.selectCharacters(count, upTo, true, false,
                    null, null, -1, null, -1, null,
                    true, false, false, null, null, null, null, false, null, false));
            if (chosen.isEmpty()) {
                ctx.logEntry("End-of-opponent-turn effect: nothing chosen — not queued");
                return;
            }
            ctx.addEndOfOpponentTurnEffect(later -> action.accept(later, chosen));
        };
    }
}
