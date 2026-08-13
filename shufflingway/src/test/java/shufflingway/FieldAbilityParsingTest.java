package shufflingway;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import org.junit.jupiter.api.Test;

public class FieldAbilityParsingTest {

    private static final java.util.regex.Pattern LIMIT_BREAK_PREFIX =
            java.util.regex.Pattern.compile("(?i)^Limit\\s+Break\\s+--\\s+");

    // -------------------------------------------------------------------------
    // Per-card coverage
    // -------------------------------------------------------------------------

    @Test
    void reportFieldAbilityParsingCoverage() throws Exception {
        File dbFile = new File("shufflingway.db");
        if (!dbFile.exists()) {
            System.out.println("[FieldAbilityParsingTest] shufflingway.db not found — skipping.");
            return;
        }

        int totalCards      = 0;
        int noAbilities     = 0;
        int fullyParsed     = 0;
        int partiallyParsed = 0;
        int noneParsed      = 0;

        List<String> examplesFully   = new ArrayList<>();
        List<String> examplesPartial = new ArrayList<>();
        List<String> examplesNone    = new ArrayList<>();
        java.util.Random rng         = new java.util.Random();

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
             Statement  stmt = conn.createStatement();
             ResultSet  rs   = stmt.executeQuery(
                     "SELECT name_en, element, cost, power, type_en, ex_burst, multicard, " +
                     "limit_break, lb_cost, image_url, text_en, job_en, category_1, category_2 " +
                     "FROM cards ORDER BY serial")) {

            while (rs.next()) {
                totalCards++;
                String textEn = rs.getString("text_en");
                if (textEn == null || textEn.isBlank()) { noAbilities++; continue; }

                String typeEn = rs.getString("type_en");
                List<FieldAbility> abilities = CardData.parseFieldAbilities(textEn, typeEn).stream()
                        .filter(fa -> !LIMIT_BREAK_PREFIX.matcher(fa.effectText()).find())
                        .toList();
                if (abilities.isEmpty()) { noAbilities++; continue; }

                CardData source = buildSource(rs, textEn);

                int parsed = 0;
                for (FieldAbility fa : abilities)
                    if (isFieldAbilityRecognized(fa, source, typeEn)) parsed++;

                String example = formatCardExample(source.name(), abilities, source);
                if (parsed == abilities.size()) {
                    fullyParsed++;
                    reservoirAdd(examplesFully, example, fullyParsed, rng);
                } else if (parsed > 0) {
                    partiallyParsed++;
                    reservoirAdd(examplesPartial, example, partiallyParsed, rng);
                } else {
                    noneParsed++;
                    reservoirAdd(examplesNone, example, noneParsed, rng);
                }
            }
        }

        int withAbilities = fullyParsed + partiallyParsed + noneParsed;
        System.out.printf("%n=== Field Ability Parsing Coverage (per card) ===%n");
        System.out.printf("Total cards:             %5d%n", totalCards);
        System.out.printf("No field abilities:      %5d%n", noAbilities);
        System.out.printf("With field abilities:    %5d%n", withAbilities);
        System.out.printf("  Fully parsed:          %5d  (%.1f%%)%n", fullyParsed,     pct(fullyParsed,     withAbilities));
        System.out.printf("  Partially parsed:      %5d  (%.1f%%)%n", partiallyParsed, pct(partiallyParsed, withAbilities));
        System.out.printf("  Nothing parsed:        %5d  (%.1f%%)%n", noneParsed,      pct(noneParsed,      withAbilities));
        System.out.println();
        printExamples("Fully parsed",     examplesFully);
        printExamples("Partially parsed", examplesPartial);
        printExamples("Unrecognized",     examplesNone);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Continuous static effects ("The Forwards you control gain +N power", "If you control X,
     * Y gains Z") aren't handled by {@link ActionResolver} — they route through
     * {@link CardData#parseFieldPowerGrants} and {@link CardData#parseIfControlBoosts} during
     * card construction. So a field-ability is "recognized" if any of those three parsers
     * accept its text.
     */
    static boolean isFieldAbilityRecognized(FieldAbility fa, CardData source, String typeEn) {
        if (ActionResolver.parse(fa.effectText(), source) != null) return true;
        if (!CardData.parseFieldPowerGrants(fa.effectText(), typeEn).isEmpty()) return true;
        if (!CardData.parseIfControlBoosts(fa.effectText(), typeEn).isEmpty()) return true;
        if (CardData.SELF_LIGHT_DARK_PLAY_EXCEPTION_PATTERN.matcher(fa.effectText()).matches()) return true;
        if (CardData.MULTI_LIGHT_DARK_PLAY_PATTERN.matcher(fa.effectText()).matches()) return true;
        if (CardData.MULTI_NAME_PLAY_PATTERN.matcher(fa.effectText()).matches()) return true;
        if (CardData.LIGHT_DARK_DISCARD_CP_PATTERN.matcher(fa.effectText()).matches()) return true;
        if (CardData.COUNTER_GRANT_PATTERN.matcher(fa.effectText()).matches()) return true;
        if (AutoAbilityTriggers.FA_CAST_SELF_FROM_BZ.matcher(fa.effectText().trim()).matches()) return true;
        // Passives the engine reads straight off the card rather than routing through ActionResolver.
        // Each is matched exactly as its AutoAbilityTriggers.has* counterpart does, so the report
        // cannot claim recognition for text the engine would reject.
        if (AutoAbilityTriggers.FA_SELF_CAST_LIMIT.matcher(fa.effectText().trim()).matches()) return true;
        if (AutoAbilityTriggers.FA_BOTH_CAST_LIMIT.matcher(fa.effectText().trim()).matches()) return true;
        if (AutoAbilityTriggers.FA_BZ_TO_RFG_ANY_SITUATION.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_CHARACTER_FIELD_TO_BZ_MAY_RFG.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OPP_DAMAGED_FORWARD_FIELD_TO_BZ_RFG.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_FIELD_DAMAGE_MODIFIER.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_FIELD_DAMAGE_EXACT_NULLIFY.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_ELEMENT_FORWARD_DAMAGE_BOOST.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_PARTY_DAMAGE_PROTECTION.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_NULLIFY_SUMMON_DAMAGE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_NULLIFY_ABILITY_DAMAGE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_NULLIFY_OPPONENT_ABILITY_DAMAGE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_REDUCE_ABILITY_DAMAGE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OPP_FORWARD_POWER_BOOST_SUPPRESSED.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OPP_FORWARD_SELF_BOOST_SUPPRESSED.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OPP_FORWARD_ETF_SUPPRESSED.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST_VS_COST.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_INCOMING_REDUCTION_VS_COST.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_DAMAGE_WHILE_DULL_REDUCTION.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_DAMAGE_ZERO_WHILE_DULL.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_NULLIFY_TRAIT_FORWARD_DAMAGE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_RECV_PLAYER_DAMAGE_ACTIVE_DULL_ZERO.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_DISCARD_JOB_TO_CAST.matcher(fa.effectText()).find()) return true;
        if (ActionResolverFieldAbility.tryParseBeginningOfOppMainPhase1FieldAbility(fa.effectText(), source) != null) return true;
        if (AutoAbilityTriggers.FA_OPPONENT_MUST_BLOCK.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_OPPONENT_MUST_CHOOSE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_FIELD_FORWARDS_MUST_BLOCK.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_FIELD_FORWARDS_MUST_ATTACK.matcher(fa.effectText()).find()) return true;
        if (CardData.COUNTER_SCALED_OPP_DEBUFF.matcher(fa.effectText()).matches()) return true;
        // Both self-named compulsions are only recognised when the text names their own carrier.
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_SELF_MUST_BLOCK,  fa, source)) return true;
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_SELF_MUST_ATTACK, fa, source)) return true;
        if (AutoAbilityTriggers.FA_ALL_FORWARDS_LOSE_HASTE.matcher(fa.effectText()).find()) return true;
        if (AutoAbilityTriggers.FA_FORWARDS_CANNOT_GAIN_HASTE.matcher(fa.effectText()).find()) return true;
        if (!CardData.parseSelfTraitGrant(fa.effectText(), source.name()).isEmpty()) return true;
        if (CardData.parseSelfNonDmgBreakShield(fa.effectText(), source.name())) return true;
        if (CardData.parseSelfNonDmgBreakShieldDirect(fa.effectText(), source.name())) return true;
        // Applied as the printed CANNOT_BE_BROKEN trait rather than through a field-ability path,
        // and honoured by breakTarget for a card in any zone — Backups included.
        if (CardData.parseSelfCannotBeBroken(fa.effectText(), source.name())) return true;
        // Conditional printings, re-evaluated per query by FieldGrantCalculator.
        if (CardData.parseIfControlNonDmgBreakShield(fa.effectText(), source.name()) != null) return true;
        if (CardData.parseFieldNonDmgBreakShieldGrant(fa.effectText()) != null) return true;
        if (CardData.parseSelfCannotBeBrokenDuringYourTurn(fa.effectText(), source.name())) return true;
        if (CardData.parseSelfCannotBeBrokenDuringAttackPhase(fa.effectText(), source.name())) return true;
        if (CardData.parseSelfCannotBeBrokenWithCounter(fa.effectText(), source.name()) != null) return true;
        if (CardData.parseIfOpponentHandSizeCannotBeBrokenThreshold(fa.effectText(), source.name()) >= 0) return true;
        if (CardData.TRAIT_ONLY_SEGMENT.matcher(fa.effectText()).matches()) return true;
        if (CardData.parseOpponentForwardsEnterDull(fa.effectText())) return true;
        if (CardData.parseFieldCannotBeBlockedByCost(fa.effectText(), source.name()) != null) return true;
        if (CardData.parseCannotBeBlockedByHigherPower(fa.effectText(), source.name())) return true;
        if (CardData.parseCannotBlockAtAll(fa.effectText(), source.name())) return true;
        if (CardData.parseCannotBlockHigherPower(fa.effectText(), source.name())) return true;
        if (CardData.parseCannotBlockParty(fa.effectText(), source.name())) return true;
        if (CardData.parseCannotAttackOrBlock(fa.effectText(), source.name())) return true;
        if (CardData.parseMaxAttacksPerTurn(fa.effectText(), source.name()) > 1) return true;
        if (CardData.parseAttacksPerOwnDamage(fa.effectText(), source.name())) return true;
        if (CardData.isHasJobsOfForwardsAbility(fa.effectText())) return true;
        if (CardData.parseIfSelfJobCountTraitGrantThreshold(fa.effectText(), source.name()) >= 0) return true;
        if (CardData.parseIfSelfLbFaceUpCountTraitGrantThreshold(fa.effectText(), source.name()) >= 0) return true;
        return CardData.isBackupCpAbility(fa.effectText());
    }

    private static CardData buildSource(ResultSet rs, String textEn) throws Exception {
        return new CardData(
                rs.getString("image_url"),
                rs.getString("name_en"),
                rs.getString("element"),
                rs.getInt("cost"),
                rs.getInt("power"),
                rs.getString("type_en"),
                rs.getInt("limit_break") != 0,
                rs.getObject("lb_cost") != null ? rs.getInt("lb_cost") : 0,
                rs.getInt("ex_burst") != 0,
                rs.getInt("multicard") != 0,
                CardData.parseTraits(textEn, rs.getString("name_en")),
                CardData.parseWarpValue(textEn),
                CardData.parseWarpCost(textEn),
                CardData.parsePrimingTarget(textEn),
                CardData.parsePrimingCost(textEn),
                CardData.parseActionAbilities(textEn),
                CardData.parseAutoAbilities(textEn),
                CardData.parseFieldAbilities(textEn, rs.getString("type_en")),
                CardData.parseIfControlBoosts(textEn, rs.getString("type_en")),
                CardData.parseFieldPowerGrants(textEn, rs.getString("type_en")),
                CardData.parseScalingSelfPowerBoosts(textEn, rs.getString("type_en"), rs.getString("name_en")),
                CardData.parseFieldCostReductions(textEn, rs.getString("type_en")),
                CardData.parseSelfCostModifiers(textEn),
                CardData.parseFieldPrimingAnyElements(textEn, rs.getString("type_en")),
                CardData.parseFieldPartyAnyElements(textEn, rs.getString("type_en")),
                CardData.parseWarpCostAnyElement(textEn),
                CardData.parseCanFormPartyAnyElement(textEn),
                CardData.parseFieldCannotBeBlockedByCost(textEn, rs.getString("name_en")),
                CardData.parseCannotBeBlockedByHigherPower(textEn, rs.getString("name_en")),
                CardData.parseCannotBlockAtAll(textEn, rs.getString("name_en")),
                CardData.parseCannotBlockHigherPower(textEn, rs.getString("name_en")),
                CardData.parseCannotBlockParty(textEn, rs.getString("name_en")),
                CardData.parseCannotAttackOrBlock(textEn, rs.getString("name_en")),
                CardData.parseMaxAttacksPerTurn(textEn, rs.getString("name_en")),
                rs.getString("job_en"),
                rs.getString("category_1"), rs.getString("category_2"), textEn);
    }

    private static String formatCardExample(String name, List<FieldAbility> abilities, CardData source) {
        StringBuilder sb = new StringBuilder();
        sb.append("  Card: ").append(name).append('\n');
        String typeEn = source.type();
        for (FieldAbility fa : abilities) {
            boolean ok   = isFieldAbilityRecognized(fa, source, typeEn);
            String  desc = describeFieldAbility(fa, source, typeEn);
            sb.append("  [").append(ok ? "OK" : "--").append("] ")
              .append(fa.effectText()).append(dmgTag(fa.damageThreshold())).append('\n');
            sb.append("       ").append(desc != null ? desc : "(none)").append('\n');
        }
        return sb.toString();
    }

    /**
     * True when {@code compulsion} matches {@code fa} <em>and</em> the card it names is the card
     * carrying it. The engine applies the same test, so the report cannot claim recognition for a
     * "This Forward must block if possible." grant that no self-named path would act on.
     */
    private static boolean selfNamedCompulsion(java.util.regex.Pattern compulsion, FieldAbility fa,
            CardData source) {
        java.util.regex.Matcher m = compulsion.matcher(fa.effectText());
        return m.find() && m.group("card").trim().equalsIgnoreCase(source.name());
    }

    static String describeFieldAbility(FieldAbility fa, CardData source, String typeEn) {
        String desc = ActionResolver.fullDescription(fa.effectText(), source);
        if (desc != null) return desc;
        List<FieldPowerGrant> grants = CardData.parseFieldPowerGrants(fa.effectText(), typeEn);
        if (!grants.isEmpty()) return "FieldPowerGrant " + grants;
        List<IfControlBoost> boosts = CardData.parseIfControlBoosts(fa.effectText(), typeEn);
        if (!boosts.isEmpty()) return "IfControlBoost " + boosts;
        Matcher m;
        m = CardData.SELF_LIGHT_DARK_PLAY_EXCEPTION_PATTERN.matcher(fa.effectText());
        if (m.matches()) return "SelfPlayException[" + m.group("element") + "]";
        m = CardData.MULTI_LIGHT_DARK_PLAY_PATTERN.matcher(fa.effectText());
        if (m.matches()) return "MultiLightDarkPlay[" + m.group("element") + "]";
        m = CardData.MULTI_NAME_PLAY_PATTERN.matcher(fa.effectText());
        if (m.matches()) return "MultiNamePlay[" + m.group("cardname") + "]";
        m = CardData.LIGHT_DARK_DISCARD_CP_PATTERN.matcher(fa.effectText());
        if (m.matches()) {
            return "LightDarkDiscardCp[" + m.group("e1")
                + (m.group("e2") != null ? ", " + m.group("e2") : "") + "]";
        }
        m = CardData.COUNTER_GRANT_PATTERN.matcher(fa.effectText());
        if (m.matches()) {
            String grant = m.group("grant").trim();
            return "CounterGrant[" + m.group("counter").trim() + ": "
                + (grant.startsWith("\"") ? "ability" : grant.replaceAll("[.!]$", "")) + "]";
        }
        m = AutoAbilityTriggers.FA_CAST_SELF_FROM_BZ.matcher(fa.effectText().trim());
        if (m.matches()) return "CastFromBreakZone[" + m.group("name").trim() + "]";
        if (AutoAbilityTriggers.FA_SELF_CAST_LIMIT.matcher(fa.effectText().trim()).matches())
            return "SelfCastLimit[2 per turn]";
        if (AutoAbilityTriggers.FA_BOTH_CAST_LIMIT.matcher(fa.effectText().trim()).matches())
            return "BothCastLimit[2 per turn]";
        if (AutoAbilityTriggers.FA_BZ_TO_RFG_ANY_SITUATION.matcher(fa.effectText()).find())
            return "BzToRfgAnySituation";
        if (AutoAbilityTriggers.FA_CHARACTER_FIELD_TO_BZ_MAY_RFG.matcher(fa.effectText()).find())
            return "CharacterFieldToBzMayRfg";
        if (AutoAbilityTriggers.FA_OPP_DAMAGED_FORWARD_FIELD_TO_BZ_RFG.matcher(fa.effectText()).find())
            return "OppDamagedFwdFieldToBzRfg";
        m = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(fa.effectText());
        if (m.find()) {
            String src      = m.group("sourceclause");
            String reduceBy = m.group("reduceby");
            String setsTo   = m.group("setsto");
            String increase = m.group("increaseby");
            String dbl      = m.group("double");
            String effect   = dbl != null ? "×2"
                            : reduceBy != null ? "reduce " + reduceBy
                            : increase != null ? "+" + increase
                            : "becomes " + setsTo;
            return "DmgModifier[" + (src != null ? src.trim() : "any") + ": " + effect + "]";
        }
        m = AutoAbilityTriggers.FA_ELEMENT_FORWARD_DAMAGE_BOOST.matcher(fa.effectText());
        if (m.find()) return "ElementFwdDmgBoost[" + m.group("element") + " Fwd +" + m.group("amount") + "]";
        m = AutoAbilityTriggers.FA_FIELD_DAMAGE_MODIFIER.matcher(fa.effectText());
        if (m.find()) {
            String src      = m.group("sourceclause");
            String reduceBy = m.group("reduceby");
            String setsTo   = m.group("setsto");
            String cat      = m.group("category");
            String job      = m.group("job");
            String cost     = m.group("cost");
            String costcmp  = m.group("costcmp");
            String except   = m.group("except1") != null ? m.group("except1") : m.group("except2");
            StringBuilder tag = new StringBuilder("FieldDmgModifier[");
            if (cat  != null) tag.append("Cat.").append(cat).append(' ');
            if (job  != null) tag.append("Job.").append(job).append(' ');
            if (cost != null) tag.append("cost").append(cost).append(costcmp != null ? costcmp : "?").append(' ');
            tag.append("Fwds");
            if (except != null) tag.append(" excl.").append(except.trim());
            tag.append(": ").append(src != null ? src.trim() : "any");
            tag.append(" → ").append(reduceBy != null ? "reduce " + reduceBy : "becomes " + setsTo);
            tag.append(']');
            return tag.toString();
        }
        m = AutoAbilityTriggers.FA_PARTY_DAMAGE_PROTECTION.matcher(fa.effectText());
        if (m.find()) return "PartyDmgProtection[" + m.group("source") + "]";
        m = AutoAbilityTriggers.FA_NULLIFY_SUMMON_DAMAGE.matcher(fa.effectText());
        if (m.find()) return "NullifySummonDmg";
        m = AutoAbilityTriggers.FA_NULLIFY_ABILITY_DAMAGE.matcher(fa.effectText());
        if (m.find()) return "NullifyAbilityDmg";
        m = AutoAbilityTriggers.FA_NULLIFY_OPPONENT_ABILITY_DAMAGE.matcher(fa.effectText());
        if (m.find()) return "NullifyOpponentAbilityDmg";
        m = AutoAbilityTriggers.FA_REDUCE_ABILITY_DAMAGE.matcher(fa.effectText());
        if (m.find()) return "ReduceAbilityDmg[" + m.group("reduction") + "]";
        if (AutoAbilityTriggers.FA_OPP_FORWARD_POWER_BOOST_SUPPRESSED.matcher(fa.effectText()).find())
            return "OppFwdPowerBoostSuppressed";
        if (AutoAbilityTriggers.FA_OPP_FORWARD_SELF_BOOST_SUPPRESSED.matcher(fa.effectText()).find())
            return "OppFwdSelfBoostSuppressed";
        if (AutoAbilityTriggers.FA_OPP_FORWARD_ETF_SUPPRESSED.matcher(fa.effectText()).find())
            return "OppForwardEtfSuppressed";
        m = AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST_VS_COST.matcher(fa.effectText());
        if (m.find()) return "OutgoingFlatBoostVsCost[cost≥" + m.group("cost") + " +" + m.group("amount") + "]";
        m = AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST.matcher(fa.effectText());
        if (m.find()) return "OutgoingFlatBoost[" + m.group("card") + " +" + m.group("amount") + "]";
        m = AutoAbilityTriggers.FA_INCOMING_REDUCTION_VS_COST.matcher(fa.effectText());
        if (m.find()) return "IncomingReductionVsCost[cost≥" + m.group("cost") + " -" + m.group("amount") + "]";
        m = AutoAbilityTriggers.FA_DAMAGE_WHILE_DULL_REDUCTION.matcher(fa.effectText());
        if (m.find()) return "DmgWhileDullReduction[-" + m.group("amount") + "]";
        m = AutoAbilityTriggers.FA_DAMAGE_ZERO_WHILE_DULL.matcher(fa.effectText());
        if (m.find()) return "DmgZeroWhileDull[" + m.group("card") + "]";
        m = AutoAbilityTriggers.FA_NULLIFY_TRAIT_FORWARD_DAMAGE.matcher(fa.effectText());
        if (m.find()) {
            String t2 = m.group("trait2");
            return "NullifyTraitFwdDmg[" + m.group("trait1").trim() + (t2 != null ? " or " + t2.trim() : "") + "]";
        }
        m = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(fa.effectText());
        if (m.find()) return "OutgoingDmgDoubler[to " + m.group("target") + "]";
        m = AutoAbilityTriggers.FA_RECV_PLAYER_DAMAGE_ACTIVE_DULL_ZERO.matcher(fa.effectText());
        if (m.find()) return "RecvPlayerDmgActiveDullZero[" + m.group("card") + "]";
        m = AutoAbilityTriggers.FA_OPPONENT_MUST_BLOCK.matcher(fa.effectText());
        if (m.find()) return "OpponentMustBlock[" + m.group("cardname") + "]";
        m = AutoAbilityTriggers.FA_OPPONENT_MUST_CHOOSE.matcher(fa.effectText());
        if (m.find()) return "OpponentMustChoose[" + m.group("cardname")
                + (m.group("summons") != null ? " summons+abilities" : " abilities") + "]";
        m = AutoAbilityTriggers.FA_FIELD_FORWARDS_MUST_BLOCK.matcher(fa.effectText());
        if (m.find()) return "FieldForwardsMustBlock[" + (m.group("scope") != null ? m.group("scope") : "all") + "]";
        m = AutoAbilityTriggers.FA_FIELD_FORWARDS_MUST_ATTACK.matcher(fa.effectText());
        if (m.find()) return "FieldForwardsMustAttack[" + (m.group("scope") != null ? m.group("scope") : "all") + "]";
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_SELF_MUST_BLOCK,  fa, source)) return "SelfMustBlock";
        if (selfNamedCompulsion(AutoAbilityTriggers.FA_SELF_MUST_ATTACK, fa, source)) return "SelfMustAttack";
        m = CardData.COUNTER_SCALED_OPP_DEBUFF.matcher(fa.effectText());
        if (m.matches()) return "CounterScaledOppDebuff[-" + m.group("power") + "/" + m.group("counter") + "]";
        if (AutoAbilityTriggers.FA_ALL_FORWARDS_LOSE_HASTE.matcher(fa.effectText()).find()) return "AllForwardsLoseHaste";
        if (AutoAbilityTriggers.FA_FORWARDS_CANNOT_GAIN_HASTE.matcher(fa.effectText()).find()) return "ForwardsCannotGainHaste";
        if (CardData.isBackupCpAbility(fa.effectText())) return "BackupCpAbility";
        int lbN = CardData.parseIfSelfLbFaceUpCountTraitGrantThreshold(fa.effectText(), source.name());
        if (lbN >= 0) return "LbFaceUpTraitGrant[n≥" + lbN + " " + CardData.parseIfSelfLbFaceUpCountTraitGrantTraits(fa.effectText()) + "]";
        if (CardData.parseSelfNonDmgBreakShieldDirect(fa.effectText(), source.name())) return "SelfNonDmgBreakShield";
        if (CardData.parseAttacksPerOwnDamage(fa.effectText(), source.name()))
            return "MaxAttacks[own damage]";
        if (CardData.parseSelfCannotBeBroken(fa.effectText(), source.name())) return "SelfCannotBeBroken";
        if (CardData.parseIfControlNonDmgBreakShield(fa.effectText(), source.name()) != null)
            return "SelfNonDmgBreakShield[if control]";
        if (CardData.parseSelfNonDmgBreakShield(fa.effectText(), source.name()))
            return "SelfNonDmgBreakShield";
        CardData.NonDmgBreakShieldGrant ndg = CardData.parseFieldNonDmgBreakShieldGrant(fa.effectText());
        if (ndg != null) {
            String who = ndg.job()      != null ? "Job " + ndg.job()
                       : ndg.cardName() != null ? "Card Name " + ndg.cardName()
                       : ndg.category() != null ? "Category " + ndg.category()
                       : ndg.element();
            return "FieldNonDmgBreakShield[" + who + "]";
        }
        if (CardData.parseSelfCannotBeBrokenDuringYourTurn(fa.effectText(), source.name()))
            return "SelfCannotBeBroken[your turn]";
        if (CardData.parseSelfCannotBeBrokenDuringAttackPhase(fa.effectText(), source.name()))
            return "SelfCannotBeBroken[Attack Phase]";
        String cbbCounter = CardData.parseSelfCannotBeBrokenWithCounter(fa.effectText(), source.name());
        if (cbbCounter != null) return "SelfCannotBeBroken[" + cbbCounter + " Counter]";
        int oppHst = CardData.parseIfOpponentHandSizeCannotBeBrokenThreshold(fa.effectText(), source.name());
        if (oppHst >= 0) return "IfOppHandSize≤" + oppHst + ":CannotBeBroken";
        return null;
    }

    private static String dmgTag(int threshold) {
        return threshold > 0 ? "  [Damage ≥" + threshold + "]" : "";
    }

    private static void reservoirAdd(List<String> reservoir, String item, int seen, java.util.Random rng) {
        if (reservoir.size() < 3) {
            reservoir.add(item);
        } else {
            int j = rng.nextInt(seen);
            if (j < 3) reservoir.set(j, item);
        }
    }

    private static void printExamples(String label, List<String> examples) {
        System.out.printf("--- %s ---%n", label);
        if (examples.isEmpty()) {
            System.out.println("(none)");
        } else {
            for (int i = 0; i < examples.size(); i++) {
                if (i > 0) System.out.println();
                System.out.print(examples.get(i));
            }
        }
        System.out.println();
    }

    private static double pct(int n, int total) {
        return total == 0 ? 0.0 : n * 100.0 / total;
    }
}
