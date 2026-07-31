package shufflingway;

import static shufflingway.ActionResolverGate.*;

import static shufflingway.ActionResolverFieldAbility.*;

import static shufflingway.ActionResolverChoose.*;

import static shufflingway.ActionResolverState.*;

import static shufflingway.ActionResolverRestriction.*;

import static shufflingway.ActionResolverPlay.*;

import static shufflingway.ActionResolverCost.*;

import static shufflingway.ActionResolverHand.*;

import static shufflingway.ActionResolverBreak.*;

import static shufflingway.ActionResolverSearch.*;

import static shufflingway.ActionResolverPower.*;

import static shufflingway.ActionResolverDamage.*;

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
 * Parses Action Ability effect text into executable game effects and resolves
 * them against the live game state via a {@link GameContext}.
 *
 * <h3>Adding new effect types</h3>
 * <ol>
 *   <li>Add a {@code static final Pattern} for the new text pattern.</li>
 *   <li>Add a {@code tryParse*} method that returns a {@code Consumer<GameContext>}
 *       (or {@code null} if the text does not match).</li>
 *   <li>Call it from {@link #parse(String)}.</li>
 * </ol>
 */
public class ActionResolver {

    // -------------------------------------------------------------------------
    // Patterns
    // -------------------------------------------------------------------------

    /**
     * Matches the "Choose" targeted effect header:
     * "Choose [up to] N [condition] [element] [targets] [of cost X [or less|more]] [control] [zone]
     *  [separator] followup"
     * <ul>
     *   <li>Group {@code upto}      — present when "up to" precedes the count</li>
     *   <li>Group {@code count}     — number of cards to choose</li>
     *   <li>Group {@code condition} — optional: "dull", "damaged", "attacking", "blocking", or "active"</li>
     *   <li>Group {@code element}   — optional element name, e.g. "Fire", "Earth"</li>
     *   <li>Group {@code category}  — optional category filter, e.g. "VII" in "Category VII Forward"</li>
     *   <li>Group {@code targets}   — card type(s): "Forward(s)", "Forward(s) or Monster(s)",
     *                                 "Backup(s)", or "Character(s)"</li>
     *   <li>Group {@code cost}      — optional CP cost value, e.g. "3" in "of cost 3 or less"</li>
     *   <li>Group {@code costlist}  — optional comma-separated digits between the first cost and
     *                                 the final " or " term in "cost A, B, C or D" multi-value lists</li>
     *   <li>Group {@code costcmp}   — optional: "less", "more", "higher" (alias for "more"), or a digit value for
     *                                 "cost N or M" / "cost A, B, … or M" filters (absent = exact match)</li>
     *   <li>Group {@code control}   — optional: "opponent controls", "your opponent controls",
     *                                 or "you control"</li>
     *   <li>Group {@code excludekw}   — optional keyword to exclude, from "without 《Keyword》" (e.g. "Multicard")</li>
     *   <li>Group {@code excludename} — optional card name to exclude, from "other than Card Name X"</li>
     *   <li>Group {@code zone}      — optional zone, e.g. "in your Break Zone" or
     *                                 "in your opponent's Break Zone"</li>
     *   <li>Group {@code followup}  — the action to apply to chosen targets</li>
     * </ul>
     */
    static final Pattern CHOOSE_CHARACTER_PATTERN = Pattern.compile(
            "(?i)Choose\\s+" +
                    "(?:(?<anycount>any\\s+number)|(?<upto>up\\s+to\\s+)?(?<count>\\d+))\\s+(?:of\\s+)?" +
                    "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
                    "(?:(?<element>(?:Multi-Element|Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+)?" +
                    "(?:Category\\s+(?<category>.+?)(?=\\s+(?:cards?|Forwards?|Backups?|Characters?|Monsters?|Summons?))\\s+)?" +
                    "(?<targets>cards?|Forwards?(?:\\s+(?:and/or|or)\\s+(?:Monsters?|Backups?))?|Monsters?|Backups?|Characters?|Summons?" +
                    "|\\[Job\\s+\\([^)]+\\)\\]" +
                    "|\\[Card\\s+Name\\s+\\([^)]+\\)\\]" +
                    "|Card\\s+Name\\s+.+?\\s+Forwards?(?:\\s+or\\s+Job\\s+.+?\\s+Forwards?)*" +
                    "|Card\\s+Name\\s+\\S+(?:\\s+\\S+)*?(?:\\s+\\([^)]+\\))?(?:\\s+or\\s+Card\\s+Name\\s+\\S+(?:\\s+\\S+)*?(?:\\s+\\([^)]+\\))?)*" +
                    "|Job\\s+.+?\\s+(?:and/)?or\\s+Card\\s+Name\\s+\\S+" +
                    "|Job\\s+.+?\\s+Forwards?(?:\\s+or\\s+Job\\s+.+?\\s+Forwards?)*" +
                    "|Job\\s+.+?(?=\\s+(?:of\\s+|other\\s+than|in\\s+your|from\\s+your)|[,.]))" +
                    "(?:\\s+Cards?)?" +
                    "(?:\\s+with\\s+(?<trait>Brave|Haste|First\\s+Strike))?" +
                    "(?:\\s+that\\s+(?<postcondition>entered\\s+the\\s+field\\s+this\\s+turn|entered\\s+this\\s+turn))?" +
                    "(?:\\s+without\\s+《(?<excludekw>[^》]+)》)?" +
                    "(?:\\s+of\\s+(?:any|an)\\s+Element\\s+(?:except|other\\s+than)\\s+(?<excludeelem>" +
                    "(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
                    "(?:\\s+and\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*))?" +
                    "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)" +
                    "(?:,\\s*(?<costlist>\\d+(?:\\s*,\\s*\\d+)*))?" +
                    "(?:\\s+or\\s+(?<costcmp>less|more|higher|\\d+))?)?" +
                    "(?:\\s+of\\s+(?:power\\s+)?(?<power>\\d+)(?:\\s+power)?(?:\\s+or\\s+(?<powercmp>less|more))?)?" +
                    "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls|you\\s+control))?" +
                    "(?:\\s+other\\s+than\\s+(?:Card\\s+Name\\s+)?(?<excludename>\\S(?:.*?\\S)?))?" +
                    "(?:\\s+(?<zone>(?:in|from)\\s+(?:your(?:\\s+opponent(?:'s)?)?|the|either\\s+player'?s|any\\s+player'?s)\\s+Break\\s+Zone))?" +
                    "(?:\\s+blocking\\s+" +
                    "(?:(?:a\\s+(?:Job\\s+)?(?<blockingjob>[^.,]+?)(?=\\s*[.,]))" +
                    "|(?<blockingname>[^.,]+?)(?=\\s*[.,])))?" +
                    "(?:[.]\\s+|\\s+and\\s+|,\\s*)" +
                    "(?<followup>.+)"
    );

    /**
     * Matches "Choose N [targets] you control and N [targets] opponent controls. [followup]"
     * — one selection from the active player's side and one from the opponent's side.
     */
    static final Pattern CHOOSE_ONE_EACH_PATTERN = Pattern.compile(
        "(?i)Choose\\s+(?<count1>\\d+)\\s+" +
        "(?<targets1>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "you\\s+control\\s+and\\s+(?<count2>\\d+)\\s+" +
        "(?<targets2>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "(?:your\\s+)?opponent\\s+controls[.]?\\s+" +
        "(?<followup>.+)"
    );

    /**
     * Matches "The former gains +N power until end of turn. Then, the former deals damage equal
     * to its power to the latter." — boost the former, then deal the (post-boost) power as damage to the latter.
     * Group {@code boost} = numeric power amount.
     */
    static final Pattern FORMER_BOOST_THEN_POWER_DAMAGE_TO_LATTER = Pattern.compile(
        "(?i)The\\s+former\\s+gains?\\s+\\+(?<boost>\\d+)\\s+power\\s+until\\s+(?:the\\s+)?end\\s+of\\s+" +
        "(?:(?:the|your)\\s+)?turn[.]\\s+Then[,]?\\s+the\\s+former\\s+deals?\\s+damage\\s+equal\\s+to\\s+" +
        "its\\s+power\\s+to\\s+the\\s+latter[.!]?"
    );

    /**
     * Matches "Choose 1 Forward you control other than [CardName]. During this turn, the next
     * damage dealt to it is dealt to [CardName] instead."
     * Groups: {@code shield} = excluded/redirect card name (first occurrence);
     *         {@code redirect} = redirect target name (second occurrence, should match {@code shield}).
     */
    static final Pattern CHOOSE_FORWARD_REDIRECT_TO_NAMED = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+you\\s+control\\s+other\\s+than\\s+(?<shield>[A-Za-z][^.]+?)[.!]\\s+" +
        "During\\s+this\\s+turn[,.]?\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+it\\s+" +
        "is\\s+(?:received\\s+by|dealt\\s+to)\\s+(?<redirect>[A-Za-z][^.!]+?)\\s+instead[.!]?"
    );

    /**
     * Matches "During this turn, the next damage dealt to the former is received by / dealt to the latter instead."
     * — one-shot damage redirect from former to latter, with an optional trailing bonus clause.
     * Group {@code suffix} = optional bonus text (e.g. BACKUP_CP_DRAW).
     */
    static final Pattern FORMER_LATTER_DAMAGE_REDIRECT = Pattern.compile(
        "(?i)During\\s+this\\s+turn[,.]?\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+the\\s+former\\s+" +
        "is\\s+(?:received\\s+by|dealt\\s+to)\\s+the\\s+latter\\s+instead[.!]?" +
        "(?<suffix>(?:\\s+.+)?)$",
        Pattern.DOTALL
    );

    /**
     * Matches "Until the end of the turn, the former gains +N power [and Traits]. Deal the latter N damage."
     * optionally followed by a bonus clause (e.g. BACKUP_CP_DRAW).
     * Groups: {@code boost} = power amount; {@code traits} = optional trait string;
     * {@code damage} = damage amount; {@code suffix} = optional trailing bonus text.
     */
    static final Pattern FORMER_BOOST_TRAITS_LATTER_DIRECT_DAMAGE = Pattern.compile(
        "(?i)Until\\s+the\\s+end\\s+of\\s+the\\s+turn[,.]?\\s+the\\s+former\\s+gains?\\s+" +
        "\\+(?<boost>\\d+)\\s+[Pp]ower" +
        "(?<traits>(?:\\s*(?:and|,)\\s*(?:Haste|First\\s+Strike|Brave))*)\\s*[.]\\s+" +
        "Deal\\s+the\\s+latter\\s+(?<damage>\\d+)\\s+damage[.!]?" +
        "(?<suffix>(?:\\s+.+)?)$",
        Pattern.DOTALL
    );

    /**
     * Matches "Until the end of the turn, the former loses [traits]. Then, the latter gains all
     * the abilities lost by the previous effect until the end of the turn."
     * Group {@code traits} = the comma/and-separated trait list (Haste, First Strike, Brave, etc.).
     */
    static final Pattern FORMER_LOSES_TRAITS_LATTER_GAINS = Pattern.compile(
        "(?i)Until\\s+the\\s+end\\s+of\\s+the\\s+turn[,.]?\\s+the\\s+former\\s+loses\\s+" +
        "(?<traits>[^.]+?)[.]\\s+Then[,.]?\\s+the\\s+latter\\s+gains\\s+all\\s+the\\s+abilities\\s+" +
        "lost\\s+by\\s+the\\s+previous\\s+effect\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?"
    );

    /**
     * Matches escalating BZ-count conditionals for former/latter: always dull former; if ≥N1
     * Card Name X in BZ dull latter; if ≥N2 freeze both; if ≥N3 opponent discards.
     */
    static final Pattern FORMER_DULL_LATTER_BZ_NAME_ESCALATE = Pattern.compile(
        "(?i)Dull\\s+the\\s+former[.]\\s+If\\s+you\\s+have\\s+(?<n1>\\d+)\\s+or\\s+more\\s+Card\\s+Name\\s+" +
        "(?<cardname>.+?)\\s+in\\s+your\\s+Break\\s+Zone[,.]?\\s+also\\s+dull\\s+the\\s+latter[.]\\s+" +
        "If\\s+you\\s+have\\s+(?<n2>\\d+)\\s+or\\s+more[,.]?\\s+also\\s+Freeze\\s+them[.]\\s+" +
        "If\\s+you\\s+have\\s+(?<n3>\\d+)\\s+or\\s+more[,.]?\\s+also\\s+your\\s+opponent\\s+discards\\s+" +
        "(?<discardN>\\d+)\\s+cards?\\s+from\\s+their\\s+hand[.!]?"
    );

    /**
     * Matches "Until the end of the turn, the former gains +N power and 'This Forward cannot
     * become dull by your opponent's Summons or abilities.' If you have received N damage or more,
     * also deal the latter damage equal to the highest power Forward you control."
     */
    static final Pattern FORMER_BOOST_DULL_IMMUNITY_COND_DAMAGE_LATTER = Pattern.compile(
        "(?i)Until\\s+the\\s+end\\s+of\\s+the\\s+turn[,.]?\\s+the\\s+former\\s+gains\\s+" +
        "\\+(?<boost>\\d+)\\s+power\\s+and\\s+\\W?This\\s+Forward\\s+cannot\\s+become\\s+dull\\s+" +
        "by\\s+your\\s+opponent.s\\s+Summons?\\s+or\\s+abilities\\W?\\s+" +
        "If\\s+you\\s+have\\s+received\\s+(?<dmgthresh>\\d+)\\s+(?:points?\\s+of\\s+)?damage\\s+or\\s+more[,.]?\\s+" +
        "also\\s+deal\\s+the\\s+latter\\s+damage\\s+equal\\s+to\\s+the\\s+highest\\s+power\\s+" +
        "Forward\\s+you\\s+control[.!]?"
    );

    /**
     * Matches "The former deals damage equal to its power to the latter."
     * — former deals its current power as damage to the latter (no boost).
     */
    static final Pattern FORMER_DEALS_POWER_DAMAGE_TO_LATTER = Pattern.compile(
        "(?i)The\\s+former\\s+deals?\\s+damage\\s+equal\\s+to\\s+its\\s+power\\s+to\\s+the\\s+latter[.!]?"
    );

    /**
     * Matches "Break the former. If [card] enters the field due to Warp, also break the latter."
     * — always break the former; break the latter only when the source entered via Warp.
     */
    static final Pattern FORMER_BREAK_COND_WARP_LATTER_BREAK = Pattern.compile(
        "(?i)Break\\s+the\\s+former[.!]?\\s+If\\s+.+?\\s+enters\\s+the\\s+field\\s+due\\s+to\\s+Warp[,.]?\\s+" +
        "also\\s+break\\s+the\\s+latter[.!]?"
    );

    /**
     * Matches "Deal the former N damage. If you control M or more Backups, also deal the latter N damage."
     * Groups: {@code dmg1} = former damage; {@code n} = backup threshold; {@code dmg2} = latter damage.
     */
    static final Pattern FORMER_DAMAGE_COND_BACKUP_COUNT_LATTER_DAMAGE = Pattern.compile(
        "(?i)Deal\\s+the\\s+former\\s+(?<dmg1>\\d+)\\s+damage[.!]?\\s+" +
        "If\\s+you\\s+control\\s+(?<n>\\d+)\\s+or\\s+more\\s+Backups?[,.]?\\s+" +
        "also\\s+deal\\s+the\\s+latter\\s+(?<dmg2>\\d+)\\s+damage[.!]?"
    );

    /**
     * Matches desc2 text "Backup with a cost equal to or less than that Forward in your Break Zone"
     * — a relative cost constraint that depends on the first chosen target at execution time.
     */
    static final Pattern DESC_BZ_BACKUP_COST_RELATIVE = Pattern.compile(
        "(?i)Backup\\s+with\\s+a\\s+cost\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+" +
        "(?:that\\s+Forward|the\\s+former)\\s+in\\s+(?:your|the)\\s+Break\\s+Zone"
    );

    /**
     * Matches "If you have cast a Card Name [X] other than [X] this turn, also [effect]."
     * Fires when the ability owner has cast another copy of the named card earlier this turn.
     * Group {@code name} = the card name; group {@code effect} = the bonus effect text.
     */
    private static final Pattern CAST_CARD_NAME_OTHER_BONUS = Pattern.compile(
        "(?i)[.]?\\s*If\\s+you\\s+have\\s+cast\\s+(?:a\\s+)?Card\\s+Name\\s+(?<name>.+?)" +
        "\\s+other\\s+than\\s+.+?\\s+this\\s+turn[,.]?\\s+also\\s+(?<effect>.+)"
    );

    /**
     * Matches "Choose [up to] N [desc1] and [up to] N [desc2]. [effects]"
     * where the effects text uses "the former" and "the latter" as pronouns for the two target groups.
     */
    static final Pattern CHOOSE_FORMER_LATTER_PATTERN = Pattern.compile(
        "(?i)^Choose\\s+(?<upTo1>up\\s+to\\s+)?(?<count1>\\d+)\\s+(?<desc1>.+?)" +
        "\\s+and\\s+(?<upTo2>up\\s+to\\s+)?(?<count2>\\d+)\\s+(?<desc2>.+?)[.]\\s*" +
        "(?<effects>.+)$",
        Pattern.DOTALL
    );

    /**
     * Parses a single target description in a CHOOSE_FORMER_LATTER clause:
     * "[condition] [element] CardType [of cost N [or less|more]] [control] [zone]"
     */
    static final Pattern TARGET_DESC_PATTERN = Pattern.compile(
        "(?i)^" +
        "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<cardtype>Forwards?|Backups?|Characters?|Monsters?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "(?:\\s+other\\s+than\\s+(?<excludename>.+?))?" +
        "(?:\\s+(?<zone>(?:in|from)\\s+(?:your(?:\\s+opponent(?:'s)?)?|the)\\s+Break\\s+Zone))?" +
        "$"
    );

    /**
     * Matches "Choose N [type1] and N [type2] [control?]. [followup]"
     * — two cards of different types from the same pool.
     * Optional control qualifier ("opponent controls" / "you control"); if absent, any side is valid.
     */
    static final Pattern CHOOSE_TWO_MIXED_TYPES_PATTERN = Pattern.compile(
        "(?i)Choose\\s+(?<count1>\\d+)\\s+(?<type1>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "and\\s+(?<count2>\\d+)\\s+(?<type2>Forwards?|Backups?|Characters?|Monsters?)" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+controls?))?[.]?\\s+" +
        "(?<followup>.+)"
    );

    /**
     * Matches "Choose up to N [type1], up to N [type2], and up to N [type3]. [followup]"
     * — up to one card of each of three different types.
     */
    static final Pattern CHOOSE_THREE_MIXED_TYPES_PATTERN = Pattern.compile(
        "(?i)Choose\\s+up\\s+to\\s+(?<count1>\\d+)\\s+(?<type1>Forwards?|Backups?|Characters?|Monsters?),\\s+" +
        "up\\s+to\\s+(?<count2>\\d+)\\s+(?<type2>Forwards?|Backups?|Characters?|Monsters?),\\s+and\\s+" +
        "up\\s+to\\s+(?<count3>\\d+)\\s+(?<type3>Forwards?|Backups?|Characters?|Monsters?)[.]?\\s+" +
        "(?<followup>.+)"
    );

    /**
     * Matches "Choose 1 Forward. [CardName] deals you N point(s) of damage.
     * If the cost of the Forward is equal to or less than the damage you have received, break it."
     * Groups: {@code name} — the card dealing the damage; {@code amount} — damage dealt.
     */
    static final Pattern CHOOSE_FORWARD_DEAL_SELF_DAMAGE_BREAK_IF_COST_LE_DAMAGE = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward\\." +
        "\\s+(?<name>.+?)\\s+deals?\\s+you\\s+(?<amount>\\d+)\\s+points?\\s+of\\s+damage\\." +
        "\\s+If\\s+the\\s+cost\\s+of\\s+the\\s+Forward\\s+is\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+" +
        "the\\s+damage\\s+you\\s+have\\s+received,?\\s+break\\s+it\\.?"
    );

    /**
     * Matches "Choose 1 Forward other than [CardName]. Until the end of the turn,
     * [CardName] and the chosen Forward lose power of any value less than [CardName]'s power.
     * (Units must be 1000.)"
     * Groups: {@code card} — the named card (must match in all three positions).
     */
    static final Pattern CHOOSE_FORWARD_SHARED_POWER_LOSS_PATTERN = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward\\s+other\\s+than\\s+(?<card>[^.]+?)\\." +
        "\\s+Until\\s+the\\s+end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "(?<card2>[^.]+?)\\s+and\\s+the\\s+chosen\\s+Forward\\s+lose\\s+power\\s+of\\s+any\\s+value\\s+" +
        "less\\s+than\\s+(?<card3>[^.']+?)'s?\\s+power\\.?" +
        "(?:\\s*\\(Units?\\s+must\\s+be\\s+1000\\.?\\))?"
    );

    /**
     * Normalises "Element Type or Element Type" → "Element or Element Type" so that
     * CHOOSE_CHARACTER_PATTERN's element group can capture both elements.
     * E.g. "Light Character or Dark Character" → "Light or Dark Character".
     */
    static final Pattern ELEM_TYPE_OR_ELEM_TYPE = Pattern.compile(
        "(?i)(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+(Forwards?|Backups?|Monsters?|Characters?)" +
        "\\s+or\\s+(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+\\2"
    );

    /** Matches {@code [Job (name)]} bracket notation; group 1 is the job name. */
    static final Pattern JOB_BRACKET_PATTERN = Pattern.compile(
        "(?i)\\[Job\\s+\\(([^)]+)\\)\\]"
    );

    /** Matches {@code [Card Name (name)]} bracket notation; group 1 is the card name. */
    static final Pattern CARD_NAME_BRACKET_PATTERN = Pattern.compile(
        "(?i)\\[Card\\s+Name\\s+\\(([^)]+)\\)\\]"
    );

    /** Matches one {@code Job name Forward(s)} segment in the written job-filter form; group 1 is the job name. */
    static final Pattern JOB_WRITTEN_SEGMENT = Pattern.compile(
        "(?i)Job\\s+(.+?)\\s+Forwards?"
    );

    /** Matches "Cancel its effect." — used to counter a Summon on the stack. */
    static final Pattern FOLLOWUP_CANCEL_EFFECT = Pattern.compile(
        "(?i)Cancel\\s+its\\s+effect\\.?"
    );

    /** Matches Y'shtola-style "Choose 1 Summon or auto-ability. Cancel its effect." */
    private static final Pattern STANDALONE_CANCEL_STACK_ENTRY_PATTERN = Pattern.compile(
        "(?i)Choose\\s+1\\s+Summon\\s+or\\s+auto-ability\\.\\s+Cancel\\s+its\\s+effect\\.?"
    );

    /**
     * Matches "Choose 1 Summon targeting/choosing a Character/Forward you control. Cancel its effect."
     * The zone/type noun ("Character" or "Forward") is captured but not enforced in code — like the
     * ability-on-stack family, {@link GameContext#cancelFilteredAbilityOnStack}'s
     * {@code requiresControllerTarget} flag only restricts to Summons whose stored targets include a
     * card the canceller controls.
     */
    static final Pattern CANCEL_SUMMON_TARGETING_MY_CHARACTER = Pattern.compile(
        "(?i)Choose\\s+1\\s+Summon\\s+(?:targeting|choosing)\\s+an?\\s+(?:Character|Forward)\\s+you\\s+control\\.\\s+Cancel\\s+its\\s+effect\\.?"
    );

    /**
     * Matches the general "Choose 1 [ability type(s)] [optional target filter]. Cancel its effect."
     * family.  Handles any combination of auto-ability / action ability / special ability / ability
     * (two types joined by " or " also accepted).  An optional "that is choosing [filter] you control"
     * or "that has only one target" clause is captured but not enforced in code.
     * Group {@code types} — the raw ability-type string (e.g. "auto-ability", "special ability or auto ability").
     */
    private static final Pattern CANCEL_ABILITY_ON_STACK = Pattern.compile(
        "(?i)Choose\\s+1\\s+" +
        "(?<types>(?:auto[- ]ability|action\\s+ability|special\\s+ability|ability)" +
        "(?:\\s+or\\s+(?:auto[- ]ability|action\\s+ability|special\\s+ability))?)" +
        "(?:\\s+that\\s+(?:is\\s+)?choosing\\s+(?<tgtFilter>[^.]+?))?" +
        "(?:\\s+that\\s+has\\s+only\\s+one\\s+target)?" +
        "\\.\\s*Cancel\\s+its\\s+effect[.!]?"
    );

    /**
     * Matches "Choose 1 [ability type(s)] [optional 'that has only one target'].
     * You may choose another target to become the new target (...)."
     * Group {@code types} — the raw ability-type string.
     */
    static final Pattern REDIRECT_ABILITY_TARGET = Pattern.compile(
        "(?i)Choose\\s+1\\s+" +
        "(?<types>(?:auto[- ]ability|action\\s+ability|special\\s+ability|ability)" +
        "(?:\\s+or\\s+(?:auto[- ]ability|action\\s+ability|special\\s+ability))?)" +
        "(?:\\s+that\\s+has\\s+only\\s+one\\s+target)?" +
        "\\.\\s*You\\s+may\\s+choose\\s+another\\s+target\\s+to\\s+become\\s+the\\s+new\\s+target" +
        "(?:\\s*\\([^)]*\\))?" +
        "[.!]?"
    );

    /**
     * Matches the "Choose 1 [Summon/ability type(s)] [optional 'opponent's']. If your opponent
     * doesn't pay 《N》, cancel its effect." family — a conditional cancel gated on an unpaid CP cost
     * (Dull's active/action-ability cost form). Group {@code opponents} — present when the target
     * must belong to the opponent (e.g. "opponent's auto-ability"). Group {@code types} — same
     * vocabulary as {@link #CANCEL_ABILITY_ON_STACK} plus {@code Summon}. Group {@code cost} — the
     * CP amount that must be paid in full to prevent the cancellation.
     */
    static final Pattern CANCEL_STACK_ENTRY_UNLESS_PAY = Pattern.compile(
        "(?i)Choose\\s+(?:1\\s+|an?\\s+)?" +
        "(?<opponents>opponent's\\s+)?" +
        "(?<types>(?:Summon|auto[- ]ability|action\\s+ability|special\\s+ability|ability)" +
        "(?:\\s+or\\s+(?:Summon|auto[- ]ability|action\\s+ability|special\\s+ability))?)" +
        "(?:\\s+that\\s+(?:is\\s+)?choosing\\s+(?<tgtFilter>[^.]+?))?" +
        "\\.\\s*If\\s+your\\s+opponent\\s+doesn'?t\\s+pay\\s*《\\s*(?<cost>\\d+)\\s*》,?\\s*" +
        "cancel\\s+its\\s+effect[.!]?"
    );

    /**
     * Matches the standalone "If your opponent doesn't pay 《N》[ or 《C》…], cancel its/their effect(s)."
     * clause used as the body of a "chosen by opponent's Summons or abilities" auto-ability — the
     * target is implicit (whatever triggered the reactive ability), so there is no leading
     * "Choose 1..." clause. Group {@code cost} — the CP amount that must be paid in full. Group
     * {@code crystal} — the optional Crystal alternative (one 《C》 per Crystal, e.g. Zeromus's
     * "pay 《4》 or 《C》"); when present the opponent may instead pay that many Crystals.
     */
    static final Pattern CANCEL_CHOSEN_TARGET_UNLESS_PAY = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+doesn'?t\\s+pay\\s*《\\s*(?<cost>\\d+)\\s*》" +
        "(?:\\s+or\\s+(?<crystal>(?:《\\s*C\\s*》)+))?,?\\s*" +
        "cancel\\s+(?:its|their)\\s+effects?[.!]?$"
    );

    /**
     * Matches the reversed-clause-order variant of {@link #CANCEL_CHOSEN_TARGET_UNLESS_PAY}: "its/their
     * effect(s) is/are cancelled if your opponent doesn't pay 《N》." (e.g. White Tiger l'Cie Qun'mi's
     * "First Strike[[br]] When 1 or more Forwards you control are chosen by your opponent's Summon,
     * its effect is cancelled if your opponent doesn't pay 《3》.").
     */
    static final Pattern CANCEL_CHOSEN_TARGET_UNLESS_PAY_REVERSED = Pattern.compile(
        "(?i)^(?:its|their)\\s+effects?\\s+(?:is|are)\\s+cancelled\\s+if\\s+your\\s+opponent\\s+" +
        "doesn'?t\\s+pay\\s*《\\s*(?<cost>\\d+)\\s*》[.!]?$"
    );

    /**
     * Discard-cost sibling of {@link #CANCEL_CHOSEN_TARGET_UNLESS_PAY}: "If your opponent doesn't
     * discard N card(s), cancel its/their effect(s)." (e.g. Kuja, Charlotte). Same implicit-target
     * cancel mechanic, but the opponent must discard from hand instead of paying CP to prevent it.
     * Group {@code count} — the number of cards that must be discarded in full.
     */
    static final Pattern CANCEL_CHOSEN_TARGET_UNLESS_DISCARD = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+doesn'?t\\s+discard\\s+(?<count>\\d+)\\s+cards?,?\\s*" +
        "cancel\\s+(?:its|their)\\s+effects?[.!]?$"
    );

    /**
     * Matches a bare "Cancel its/their effect(s)." — the consequent of a reactive "chosen by opponent's
     * Summons or abilities" auto-ability whose cost was already paid upstream (e.g. Phantasmal Girl's
     * "you may pay 《2》. When you do so, cancel their effects.", or Regis/Tama/Yuna's "…put/discard…,
     * cancel its effect."). Since the paying/cost step is handled before this sub-effect runs, this
     * unconditionally cancels the in-progress selection. Anchored to the whole string so it never
     * matches the "Choose 1 Summon…" stack-cancel forms.
     */
    static final Pattern CANCEL_CHOSEN_TARGET_BARE = Pattern.compile(
        "(?i)^Cancel\\s+(?:its|their)\\s+effects?[.!]?$"
    );

    /**
     * Standalone "If your opponent doesn't pay 《N》, [target action]." — the body of a reactive
     * auto-ability (e.g. Remedi: "…if your opponent doesn't pay 《2》, break it.") whose target is
     * supplied via {@link GameContext#consumePreloadedTargets()} (the entering card). The opponent
     * may pay {@code cost} in full to prevent it; otherwise the action ("break it", "dull it",
     * "Freeze it", …) runs against the preloaded target(s) — parsed by {@link #parseTargetAction}.
     * Groups: {@code cost} — CP amount; {@code effect} — the target action text.
     */
    static final Pattern IF_OPP_NOT_PAY_ACTION = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+doesn'?t\\s+pay\\s+《\\s*(?<cost>\\d+)\\s*》,?\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );

    /**
     * Banon: "Reveal the top card of your deck. If it is a [Type], cancel all effects choosing [Name]."
     * Reveals (peeks) the top card of the controller's deck; if it is of the captured {@code type},
     * the in-progress selection is cancelled. Group {@code type} — Forward / Backup / Monster / Summon.
     */
    static final Pattern CANCEL_CHOSEN_REVEAL_TOP_IF_TYPE = Pattern.compile(
        "(?i)^Reveal\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "If\\s+it\\s+is\\s+an?\\s+(?<type>Forward|Backup|Monster|Summon)s?,\\s*" +
        "cancel\\s+all\\s+effects?\\s+choosing\\s+.+?[.!]?$"
    );

    /**
     * Siren (V): "Put the top card of your deck into the Break Zone. If the card put into the Break
     * Zone is not a [Type], cancel its/their effect(s)." Mills the top card of the controller's deck;
     * if that card is NOT of the captured {@code type}, the in-progress selection is cancelled.
     * Group {@code type} — Forward / Backup / Monster / Summon.
     */
    static final Pattern CANCEL_CHOSEN_MILL_TOP_IF_NOT_TYPE = Pattern.compile(
        "(?i)^Put\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s+" +
        "If\\s+the\\s+card\\s+put\\s+into\\s+the\\s+Break\\s+Zone\\s+is\\s+not\\s+an?\\s+" +
        "(?<type>Forward|Backup|Monster|Summon)s?,\\s*cancel\\s+(?:its|their)\\s+effects?[.!]?$"
    );

    /**
     * Matches "Choose 1 auto-ability. Cancel its effect. If the cancelled auto-ability triggered
     * from a Forward, deal that Forward N damage."
     * Group {@code amount} — damage to deal if the source was a Forward.
     */
    static final Pattern CANCEL_AUTO_ABILITY_DAMAGE_IF_FORWARD = Pattern.compile(
        "(?i)^Choose\\s+1\\s+auto-ability\\.\\s+Cancel\\s+its\\s+effect\\.\\s+" +
        "If\\s+the\\s+cancelled\\s+auto-ability\\s+triggered\\s+from\\s+a\\s+Forward,\\s+" +
        "deal\\s+that\\s+Forward\\s+(?<amount>\\d+)\\s+damage\\.?$"
    );

    /** Matches "deal it/them N damage". */
    /**
     * Matches "Deal it/them [and CardName] N damage".
     * <ul>
     *   <li>{@code also} — optional named Forward that also receives the damage</li>
     *   <li>{@code amount} — fixed damage value</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_DAMAGE = Pattern.compile(
        "(?i)deal\\s+(?:it|them)(?:\\s+and\\s+(?<also>.+?))?\\s+(?<amount>\\d+)\\s+damage"
    );

    /**
     * Matches Titan's unique damage clause:
     * "Deal it damage equal to the power of the Forward removed by the extra cost."
     * At runtime the damage value is read from {@link GameContext#extraCostRemovedCardPower()}.
     */
    private static final Pattern FOLLOWUP_DAMAGE_EXTRA_COST_POWER = Pattern.compile(
        "(?i)deal\\s+it\\s+damage\\s+equal\\s+to\\s+the\\s+power\\s+of\\s+the\\s+Forward\\s+removed\\s+by\\s+the\\s+extra\\s+cost\\.?"
    );

    /**
     * Matches Fenrir's conditional break-and-draw:
     * "If its cost is equal to the cost of the card discarded by the extra cost, break it and draw N card(s)."
     * Group: {@code draw} — number of cards to draw.
     */
    private static final Pattern FOLLOWUP_IF_COST_EQUALS_DISCARD_BREAK_DRAW = Pattern.compile(
        "(?i)if\\s+its\\s+cost\\s+is\\s+equal\\s+to\\s+the\\s+cost\\s+of\\s+the\\s+card\\s+discarded\\s+by\\s+the\\s+extra\\s+cost,?\\s+break\\s+it\\s+and\\s+draw\\s+(?<draw>\\d+)\\s+cards?\\.?"
    );

    /**
     * Matches "Deal it/them N damage and M point(s) of damage to that Forward's controller."
     * Groups: {@code amount} — damage to the chosen Forward; {@code controllerdmg} — card damage dealt to its controller.
     */
    static final Pattern FOLLOWUP_DAMAGE_AND_CONTROLLER_DAMAGE = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+(?<amount>\\d+)\\s+damage\\s+and\\s+(?<controllerdmg>\\d+)\\s+points?\\s+of\\s+damage\\s+to\\s+that\\s+(?:Forward|Character|Monster|Backup)'?s?\\s+controller\\.?"
    );

    /**
     * Matches the "That Forward's controller discards N card(s) from (their|his/her) hand" secondary
     * clause that follows a Choose+followup primary (Physalis, Sephiroth, Hades, …). The discarder
     * is resolved at runtime from {@link GameContext#lastChosenTargets()}.
     * Group {@code count} — number of cards to discard.
     */
    static final Pattern FOLLOWUP_TARGET_CONTROLLER_DISCARDS = Pattern.compile(
        "(?i)^That\\s+Forward(?:'s|s)?\\s+controller\\s+discards?\\s+(?<count>\\d+)\\s+cards?\\s+" +
        "from\\s+(?:their|his/her|his|her)\\s+hand\\.?$"
    );

    /**
     * Matches "You may discard 1 Card Name X from your hand. If you do so, deal it N damage."
     * Groups: {@code cardname}, {@code amount}.
     */
    static final Pattern FOLLOWUP_MAY_DISCARD_NAMED_DEAL_DAMAGE = Pattern.compile(
        "(?i)^you\\s+may\\s+discard\\s+1\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+from\\s+your\\s+hand\\.\\s+If\\s+you\\s+do\\s+so,\\s+deal\\s+it\\s+(?<amount>\\d+)\\s+damage\\.?$"
    );

    /**
     * Matches "Deal it/them N damage. If &lt;condition&gt;, deal it/them M damage instead."
     * Groups: {@code base}, {@code cond}, {@code alt}.
     */
    static final Pattern FOLLOWUP_DAMAGE_INSTEAD = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage\\.\\s+If\\s+(?<cond>.+?),\\s+deal\\s+(?:it|them)\\s+(?<alt>\\d+)\\s+damage\\s+instead\\.?"
    );

    /**
     * Matches any "P. If [name] results from an EX Burst, A instead." followup.
     * Groups: {@code primary} (text before the period), {@code alt} (alternate action text).
     * The card name before "results from an EX Burst" is intentionally not captured.
     */
    static final Pattern FOLLOWUP_INSTEAD_EXBURST = Pattern.compile(
        "(?i)(?<primary>.+?)\\.\\s+If\\s+\\S+(?:\\s+\\S+)*?\\s+results\\s+from\\s+an\\s+EX\\s+Burst,\\s+(?<alt>.+?)\\s+instead[.!]?"
    );

    /**
     * Matches "deal it/them damage equal to &lt;expr&gt;" where the amount is computed
     * from the game state at resolution time.  Exactly one named group will be set:
     * <ul>
     *   <li>{@code highest} — "the highest [power] Forward you control['s power]"</li>
     *   <li>{@code halfcard}     — card name in "half of &lt;name&gt;'s power [(round up/down…)]"</li>
     *   <li>{@code halfrounding} — "up" or "down" when an explicit rounding clause is present (absent = round down, matching legacy behaviour)</li>
     *   <li>{@code itspower} — "its/their power [minus &lt;minus&gt;]"</li>
     *   <li>{@code card}     — card name in "&lt;name&gt;'s power"</li>
     * </ul>
     * Group {@code minus} is set alongside {@code itspower} when a subtraction is present.
     */
    static final Pattern FOLLOWUP_DAMAGE_EXPR = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+damage\\s+equal\\s+to\\s+" +
        "(?:" +
            "(?<highest>the\\s+highest(?:\\s+power)?\\s+Forward(?:\\s+you\\s+control)?(?:'s\\s+power)?)" +
            "|half\\s+of\\s+(?<halfcard>.+?)'s\\s+power(?:\\s*\\(\\s*round\\s+(?<halfrounding>up|down)[^)]*\\))?" +
            "|(?<halfitspower>half\\s+of\\s+(?:its|their)\\s+power)(?:\\s*\\(\\s*round\\s+(?<halfitsrounding>up|down)[^)]*\\))?" +
            "|(?<itspower>(?:its|their)\\s+power)(?:\\s+minus\\s+(?<minus>\\d+))?" +
            "|(?<dullforward>the\\s+power\\s+of\\s+the\\s+dull(?:ed)?\\s+Forward)" +
            "|(?<discardedfwd>the\\s+discarded\\s+Forward(?:'s\\s+power)?)" +
            "|(?<bzcostfwd>the\\s+power\\s+of\\s+the\\s+Forward\\s+put\\s+in(?:to)?\\s+the\\s+Break\\s+Zone)" +
            "|(?<card>.+?)'s?\\s+power" +
        ")"
    );

    /**
     * Matches "&lt;SourceCardName&gt; and the chosen Forward deal damage equal to their respective power to the other."
     * Used as a followup after "Choose 1 Forward …" to apply simultaneous power-as-damage between
     * the source card and the selected target.
     * <ul>
     *   <li>{@code srcname} — the card name on the left side of "and the chosen Forward"; verified
     *       against the ability's source card at match time.</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_MUTUAL_POWER_DAMAGE = Pattern.compile(
        "(?i)(?<srcname>.+?)\\s+and\\s+the\\s+chosen\\s+Forward\\s+deal\\s+damage\\s+equal\\s+to\\s+their\\s+respective\\s+power\\s+to\\s+the\\s+other[.!]?"
    );

    /** Matches "Each Forward deals damage equal to its power to the other." (used in choose-one-each contexts). */
    static final Pattern FOLLOWUP_EACH_FORWARD_MUTUAL_POWER_DAMAGE = Pattern.compile(
        "(?i)Each\\s+Forward\\s+deals\\s+damage\\s+equal\\s+to\\s+its\\s+power\\s+to\\s+the\\s+other[.!]?"
    );

    /**
     * Matches "Deal it/them [base] damage [and [per] more damage] for each [source]".
     * <ul>
     *   <li>{@code base}       — base damage per unit (or fixed base when {@code per} is set)</li>
     *   <li>{@code per}        — additional damage per each unit (the "and N more" form)</li>
     *   <li>{@code selfdmg}    — source is P1's damage-zone count</li>
     *   <li>{@code jobbname}   — bracket job: "[Job (name)] you control"</li>
     *   <li>{@code jobwname}   — written job: "Job Name you control"</li>
     *   <li>{@code chartype}   — type filter: "Forwards/Characters/etc. you control"</li>
     *   <li>{@code costfilter} — optional exact cost: "of cost N" appended to chartype</li>
     *   <li>{@code bzname}     — card name in P1's Break Zone</li>
     *   <li>{@code opphand}    — source is the opponent's hand size</li>
     *   <li>{@code xpaid}      — source is the X CP value paid for this ability</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_DAMAGE_FOR_EACH = Pattern.compile(
        "(?i)deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage" +
        "(?:\\s+(?<op>and|minus)\\s+(?<per>\\d+)\\s+(?:more\\s+)?damage)?" +
        "\\s+for\\s+each\\s+" +
        "(?:" +
            "(?<selfdmg>point\\s+of\\s+damage\\s+you\\s+have\\s+received)" +
            "|\\[Job\\s+\\((?<jobbname>[^)]+)\\)\\]\\s+you\\s+control" +
            "|Job\\s+(?<jobwname>.+?)(?:\\s+(?<jobwtype>Forwards?|Backups?|Monsters?))?\\s+you\\s+control" +
            "|(?:Category\\s+(?<category>\\S+)\\s+)?(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?(?<chartype>Forwards?|Characters?|Backups?|Monsters?)(?:\\s+of\\s+cost\\s+(?<costfilter>\\d+))?\\s+you\\s+control" +
            "|Card\\s+Name\\s+(?<bzname>\\S+(?:\\s+\\([^)]+\\))?)\\s+in\\s+your\\s+Break\\s+Zone" +
            "|(?<opphand>card\\s+in\\s+your\\s+opponent'?s?\\s+hand)" +
            "|(?<xpaid>CP\\s+paid\\s+as\\s+X)" +
            "|(?<crystal>《C》)\\s+you\\s+have" +
            "|(?<cpDiffElem>CP\\s+of\\s+a\\s+different\\s+Element\\s+you\\s+paid\\s+to\\s+cast\\s+\\S+)" +
        ")" +
        "[.!]?"
    );

    /** Matches "Activate it" or "Activate them". */
    static final Pattern FOLLOWUP_ACTIVATE = Pattern.compile(
        "(?i)Activate\\s+(?:it|them)\\.?"
    );

    /** Matches "Dull it or activate it." / "Dull them or activate them." — toggle dull/active. */
    static final Pattern FOLLOWUP_DULL_OR_ACTIVATE = Pattern.compile(
        "(?i)Dulls?\\s+(?:it|them)\\s+or\\s+activates?\\s+(?:it|them)[.!]?"
    );

    /**
     * Matches "Dull it or freeze it." / "Dull them or freeze them." — dull if active,
     * freeze if already dulled. (Order-of-words variants like "dull or freeze it" are not used in card text.)
     */
    static final Pattern FOLLOWUP_DULL_OR_FREEZE = Pattern.compile(
        "(?i)Dulls?\\s+(?:it|them)\\s+or\\s+freezes?\\s+(?:it|them)[.!]?"
    );

    /** Matches "Dull or Freeze it/them" — compact imperative form used in former/latter effects. */
    private static final Pattern FOLLOWUP_DULL_OR_FREEZE_COMPACT = Pattern.compile(
        "(?i)Dull\\s+or\\s+Freeze\\s+(?:it|them)[.!]?"
    );

    /** Matches "dull it/them" or "dulls it/them" (third-person form used in opponent-selects effects). */
    static final Pattern FOLLOWUP_DULL = Pattern.compile(
        "(?i)dulls?\\s+(?:it|them)"
    );

    /** Matches "freeze it" or "freeze them". */
    static final Pattern FOLLOWUP_FREEZE = Pattern.compile(
        "(?i)freeze\\s+(?:it|them)"
    );

    /**
     * Matches "dull it/them and freeze it/them" or compact "dull and freeze it/them"
     * (former/latter effects use a shared pronoun at the end).
     */
    static final Pattern FOLLOWUP_DULL_AND_FREEZE = Pattern.compile(
        "(?i)(?:dull\\s+(?:it|them)\\s+and\\s+freeze|dull\\s+and\\s+freeze)\\s+(?:it|them)"
    );

    /** Matches "Dull it/them and deal it/them N damage". Group {@code amount} is the damage value. */
    static final Pattern FOLLOWUP_DULL_AND_DAMAGE = Pattern.compile(
        "(?i)dull\\s+(?:it|them)\\s+and\\s+deal\\s+(?:it|them)\\s+(?<amount>\\d+)\\s+damage"
    );

    /**
     * Matches split-target effects of the form:
     * "[action A] the first [type] [suffix] [sep] [action B] the other"
     * where action B is drawn from a known vocabulary.
     * <ul>
     *   <li>{@code firstpfx}    — verb phrase before "the first [type]"
     *                             (e.g. "Dull", "Remove", "Deal 8000 damage to")</li>
     *   <li>{@code firstsfx}    — optional non-comma text after "the first [type]"
     *                             (e.g. " from the game", " to its owner's hand")</li>
     *   <li>{@code othereffect} — effect for the second chosen target
     *                             (one of: dull and freeze, activate, break, dull, freeze,
     *                              remove from the game, return to its owner's hand)</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_FIRST_AND_OTHER = Pattern.compile(
        "(?i)(?<firstpfx>.+?)\\s+the\\s+first\\s+(?:Forward|Backup|Character|Monster|one)" +
        "(?<firstsfx>[^,]*?)[,.]?\\s+(?:and\\s+)?" +
        "(?<othereffect>dull\\s+and\\s+freeze|activate|break|dull|freeze" +
        "|remove\\s+from\\s+the\\s+game|return\\s+to\\s+its\\s+owner'?s\\s+hand)" +
        "\\s+the\\s+other\\.?$"
    );

    /** Matches "Break it" or "Break them". */
    static final Pattern FOLLOWUP_BREAK = Pattern.compile(
        "(?i)Break\\s+(?:it|them)"
    );

    /** Matches "It loses all [its] abilities until the end of the turn." */
    static final Pattern FOLLOWUP_LOSE_ALL_ABILITIES_EOT = Pattern.compile(
        "(?i)It\\s+loses\\s+all\\s+(?:its\\s+)?abilities\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches both word orders of "it loses all its abilities and its power becomes N until the
     * end of the turn" — Wakka 1-216S's Status Reels, which wipes abilities and replaces base
     * power in one clause.
     *
     * <p>Must be tried before {@link #FOLLOWUP_LOSE_ALL_ABILITIES_EOT} and
     * {@link #FOLLOWUP_POWER_REDUCE_UNTIL}: with the duration clause leading, the former's
     * "abilities until end of turn" adjacency fails while the latter matches "Until …, it loses"
     * with empty amount and trait groups, so the ability wipe and the power change are both lost.
     *
     * <p>Group {@code power} — the new base power.
     */
    static final Pattern FOLLOWUP_LOSE_ABILITIES_AND_POWER_BECOMES = Pattern.compile(
        "(?i)(?:Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+)?" +
        "(?:it|they)\\s+loses?\\s+all\\s+(?:(?:its|their)\\s+)?abilities\\s+and\\s+" +
        "(?:its|their)\\s+power\\s+becomes?\\s+(?<power>\\d+)" +
        "(?:\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn)?[.!]?"
    );

    /** Matches "Remove it/them from the game". */
    static final Pattern FOLLOWUP_REMOVE_FROM_GAME = Pattern.compile(
        "(?i)Remove\\s+(?:it|them)\\s+from\\s+(?:the\\s+)?game"
    );

    /**
     * Matches the secondary "Then, play the removed Forward onto the field [dull]."
     * Used after a RemoveFromGame primary to play the just-removed card back onto the field.
     * Group {@code dull} — present if the card enters dull.
     */
    static final Pattern SECONDARY_PLAY_REMOVED_ONTO_FIELD = Pattern.compile(
        "(?i)^(?:Then,?\\s+)?play\\s+the\\s+removed\\s+(?:Forward|Character)" +
        "\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?\\s*$"
    );

    /**
     * Matches "Remove it/them and [CardName] from the game" — chosen target(s) plus a named card.
     * Group {@code named} — the additional card name to remove.
     */
    static final Pattern FOLLOWUP_REMOVE_FROM_GAME_AND_NAMED = Pattern.compile(
        "(?i)Remove\\s+(?:it|them)\\s+and\\s+(?<named>.+?)\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /**
     * Matches "Your opponent randomly removes N card(s) in his/her/their hand from the game."
     * Group 1 — count.
     */
    static final Pattern OPPONENT_RANDOM_HAND_RFP = Pattern.compile(
        "(?i)Your\\s+opponent\\s+randomly\\s+removes?\\s+(\\d+)\\s+cards?\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /**
     * Matches "Your opponent randomly places N card(s) from their hand at the bottom of their deck."
     * Group 1 — count.
     */
    static final Pattern OPPONENT_RANDOM_HAND_TO_BOTTOM_DECK = Pattern.compile(
        "(?i)Your\\s+opponent\\s+randomly\\s+places?\\s+(\\d+)\\s+cards?\\s+from\\s+" +
        "(?:his/her|his|her|their)\\s+hand\\s+at\\s+the\\s+bottom\\s+of\\s+(?:his/her|his|her|their)\\s+deck[.!]?"
    );

    /**
     * Matches the style "reveal and select from hand to remove from game":
     * "Your opponent reveals their hand. Select N card(s) in their hand.
     *  Your opponent removes it/them from the game."
     * Group 1 — count of cards to select.
     */
    static final Pattern REVEAL_SELECT_HAND_RFP = Pattern.compile(
        "(?i)Your\\s+opponent\\s+reveals?\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "Select\\s+(\\d+)\\s+cards?\\s+in\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "Your\\s+opponent\\s+removes?\\s+(?:it|them)\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /**
     * Matches "Your opponent reveals their hand. You may select 1 card from their hand.
     * If you do so, remove it from the game and your opponent draws 1 card."
     * (Zidane-style: optional select, you remove it, opponent draws.)
     */
    static final Pattern REVEAL_HAND_OPT_PICK_RFP_OPP_DRAW = Pattern.compile(
        "(?i)Your\\s+opponent\\s+reveals?\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "You\\s+may\\s+select\\s+1\\s+card\\s+from\\s+(?:his/her|his|her|their)\\s+hand[.!]\\s+" +
        "If\\s+you\\s+do\\s+so,\\s+remove\\s+it\\s+from\\s+(?:the\\s+)?game\\s+" +
        "and\\s+your\\s+opponent\\s+draws\\s+1\\s+card[.!]?"
    );

    /**
     * Matches "Your opponent removes N card(s) in his/her/their hand from the game."
     * (opponent chooses which cards — not random).  Group 1 — count.
     */
    static final Pattern OPPONENT_HAND_RFP = Pattern.compile(
        "(?i)Your\\s+opponent\\s+removes?\\s+(\\d+)\\s+cards?\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /** Matches "Remove all the cards in your opponent's Break Zone from the game." */
    private static final Pattern REMOVE_ALL_OPP_BZ_FROM_GAME = Pattern.compile(
        "(?i)^remove\\s+all\\s+the\\s+cards\\s+in\\s+your\\s+opponent'?s\\s+Break\\s+Zone\\s+from\\s+(?:the\\s+)?game[.!]?\\s*$"
    );

    /**
     * Matches "Remove [CardName] from the game." as a standalone sentence.
     * Group {@code named} — the card name.  Does NOT match "Remove it/them …" (pronouns).
     */
    /**
     * Matches "Remove [CardName] from the game." The {@code the top …} guard keeps deck-top removals
     * ("Remove the top 4 cards of your deck from the game", Libroarian 8-084R) out: this pattern is
     * loose enough to read that phrase as a card name and would otherwise claim it first, quietly
     * removing nothing.
     */
    private static final Pattern REMOVE_NAMED_FROM_GAME = Pattern.compile(
        "(?i)Remove\\s+(?!(?:it|them)\\b)(?!the\\s+top\\b)(?<named>.+?)\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /** Matches "You may remove [CardName] from the game." — optional self-RFP. */
    private static final Pattern YOU_MAY_REMOVE_NAMED_FROM_GAME = Pattern.compile(
        "(?i)^you\\s+may\\s+remove\\s+(?<name>.+?)\\s+from\\s+(?:the\\s+)?game[.!]?\\s*$"
    );

    /**
     * Matches "You may reveal 1 [Element] card from your hand."
     * Group {@code element} — the required element name.
     */
    static final Pattern YOU_MAY_REVEAL_ELEMENT_FROM_HAND = Pattern.compile(
        "(?i)^You\\s+may\\s+reveal\\s+1\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
        "\\s+card\\s+from\\s+your\\s+hand[.!]?\\s*$"
    );

    /** Matches "At the end of your opponent's turn, play [CardName] onto the field." */
    static final Pattern AT_END_OF_OPP_TURN_PLAY_NAMED_ONTO_FIELD = Pattern.compile(
        "(?i)^at\\s+the\\s+end\\s+of\\s+your\\s+opponent'?s\\s+turn,?\\s+play\\s+(?<name>.+?)\\s+onto\\s+the\\s+field[.!]?\\s*$"
    );

    /** Matches "Break [CardName]." — used when the source card breaks itself. */
    static final Pattern BREAK_SOURCE_CARD = Pattern.compile(
        "(?i)^break\\s+(?<name>.+?)[.!]?$"
    );

    /** Matches "put [CardName] into the Break Zone[.!]?" where CardName is the source card. */
    static final Pattern PUT_SOURCE_INTO_BREAK_ZONE = Pattern.compile(
        "(?i)^put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );

    /**
     * "you may put [CardName] into the Break Zone. When you do so, [effect]"
     * Prompts the player; if they choose to break the source card, the follow-up effect fires.
     * Groups: {@code name} — card name (must equal source); {@code effect} — the conditional effect.
     */
    private static final Pattern YOU_MAY_PUT_SELF_TO_BZ_WHEN_DO_SO = Pattern.compile(
        "(?i)^you\\s+may\\s+put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s+" +
        "When\\s+you\\s+do\\s+so,\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );

    /**
     * Matches "If your opponent doesn't control [any] Forwards, put [CardName] into the Break Zone."
     * Group {@code name} — the card name that goes to the Break Zone (must equal source name).
     */
    static final Pattern IF_OPP_NO_FORWARDS_PUT_TO_BREAK_ZONE = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+(?:doesn'?t|does\\s+not)\\s+control\\s+(?:any\\s+)?Forwards?," +
        "\\s+put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );

    /**
     * Matches "If either player doesn't control [any] Forwards, put [CardName] into the Break Zone."
     * Fires if either the controller or their opponent has zero Forwards.
     * Group {@code name} — the card name that goes to the Break Zone (must equal source name).
     */
    static final Pattern IF_EITHER_PLAYER_NO_FORWARDS_PUT_SOURCE_TO_BZ = Pattern.compile(
        "(?i)^If\\s+either\\s+player\\s+(?:doesn'?t|does\\s+not)\\s+control\\s+(?:any\\s+)?Forwards?," +
        "\\s+put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );

    /**
     * "If you have received N points of damage, put [CardName] into the Break Zone."
     * Fires when the controlling player's damage zone reaches the threshold.
     * Group {@code points} — the damage count threshold; {@code name} — the card name (must equal source).
     */
    static final Pattern IF_SELF_DAMAGE_POINTS_PUT_TO_BREAK_ZONE = Pattern.compile(
        "(?i)^If\\s+you\\s+have\\s+received\\s+(?<points>\\d+)\\s+points?\\s+of\\s+damage," +
        "\\s+put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s*$"
    );

    /** Matches "break the blocking Forward[.!]?" — fires during "is blocked" triggers. */
    static final Pattern BREAK_BLOCKING_FORWARD = Pattern.compile(
        "(?i)^break\\s+the\\s+blocking\\s+Forward[.!]?$"
    );

    /** Matches "Break the Forward that blocks [Name][.!]?" — group {@code name}. */
    static final Pattern BREAK_FORWARD_THAT_BLOCKS_CARD = Pattern.compile(
        "(?i)^Break\\s+the\\s+Forward\\s+that\\s+blocks?\\s+(?<name>[^.!]+?)[.!]?$"
    );

    /**
     * Matches "Choose 1 card with EX Burst in your Damage Zone. You may trigger its EX Burst effect."
     * with an optional trailing parenthetical rules note.
     */
    static final Pattern CHOOSE_EX_BURST_FROM_DAMAGE_ZONE = Pattern.compile(
        "(?i)choose\\s+1\\s+card\\s+with\\s+EX\\s+Burst\\s+in\\s+your\\s+Damage\\s+Zone[.,]?\\s+" +
        "You\\s+may\\s+trigger\\s+its\\s+EX\\s+Burst\\s+effect[.!]?" +
        "(?:\\s*\\([^)]+\\))?"
    );

    /**
     * Matches the Leviathan/Larsa/Strago Damage-Zone-swap pattern:
     * "Choose 1 card in your Damage Zone. Add it to your hand [and draw 1 card]. [Then,]
     *  Put 1 card from your hand into the Damage Zone (its EX Burst effect will not trigger)."
     * Group {@code draw} — present when the variant draws 1 card between the two halves.
     */
    static final Pattern DAMAGE_ZONE_SWAP_PATTERN = Pattern.compile(
        "(?i)^choose\\s+1\\s+card\\s+in\\s+your\\s+Damage\\s+Zone\\.\\s+" +
        "Add\\s+it\\s+to\\s+your\\s+hand(?<draw>\\s+and\\s+draw\\s+1\\s+card)?\\.\\s+" +
        "(?:Then,?\\s+)?Put\\s+1\\s+card\\s+from\\s+your\\s+hand\\s+into\\s+the\\s+Damage\\s+Zone" +
        "\\s*\\([^)]*\\)\\.?\\s*$"
    );

    /**
     * Matches "Remove the top [N cards / card] of your deck from the game."
     * Group {@code count} — number of cards (absent means 1).
     */
    static final Pattern REMOVE_TOP_OF_DECK_FROM_GAME = Pattern.compile(
        "(?i)Remove\\s+the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+of\\s+your\\s+deck\\s+from\\s+(?:the\\s+)?game\\.?"
    );

    /**
     * Number of top-of-deck cards {@code effectText} removes from the game (1 for "the top card…",
     * N for "the top N cards…"), or {@code 0} if it has no such removal. Used to gate activation:
     * you cannot remove the top card(s) of an empty (or too-small) deck, so the ability is illegal then.
     */
    public static int topDeckRemovalCount(String effectText) {
        if (effectText == null) return 0;
        Matcher m = REMOVE_TOP_OF_DECK_FROM_GAME.matcher(effectText);
        if (!m.find()) return 0;
        String c = m.group("count");
        return c != null ? Integer.parseInt(c) : 1;
    }

    /**
     * Matches the compound followup "Remove the top card of your deck from the game.
     * Deal it/them N damage for each CP required to play/cast the removed card."
     * Group {@code base} — damage per CP.
     */
    static final Pattern FOLLOWUP_RFP_TOP_DECK_AND_DAMAGE_PER_CP = Pattern.compile(
        "(?i)Remove\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck\\s+from\\s+(?:the\\s+)?game\\.\\s+" +
        "Deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage\\s+for\\s+each\\s+CP\\s+required\\s+to\\s+(?:play|cast)\\s+the\\s+removed\\s+card[.!]?"
    );

    /**
     * Matches the compound followup "Remove the top card of your deck from the game. If the removed
     * card is a Forward, break it. If not, deal it N damage." — the break / damage both apply to the
     * Forward chosen by the preceding "Choose 1 Forward" header ({@code it}).
     * Group {@code dmg} — damage dealt when the removed card is not a Forward.
     */
    static final Pattern FOLLOWUP_RFP_TOP_DECK_IF_FORWARD_BREAK_ELSE_DAMAGE = Pattern.compile(
        "(?i)Remove\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck\\s+from\\s+(?:the\\s+)?game\\.\\s+" +
        "If\\s+the\\s+removed\\s+card\\s+is\\s+a\\s+Forward,?\\s+break\\s+it\\.\\s+" +
        "If\\s+not,?\\s+deal\\s+it\\s+(?<dmg>\\d+)\\s+damage[.!]?"
    );

    /**
     * Matches the compound followup "Reveal the top N cards of your deck.
     * Deal it/them M damage for each CP required to play/cast the revealed cards.
     * Add all the revealed cards to your hand."
     * Groups: {@code n} — card count, {@code base} — damage per CP.
     */
    static final Pattern FOLLOWUP_REVEAL_TOP_N_DAMAGE_PER_CP_ADD_ALL_TO_HAND = Pattern.compile(
        "(?i)Reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck\\.\\s+" +
        "Deal\\s+(?:it|them)\\s+(?<base>\\d+)\\s+damage\\s+for\\s+each\\s+CP\\s+required\\s+to\\s+(?:play|cast)\\s+the\\s+revealed\\s+cards?\\.\\s+" +
        "Add\\s+all\\s+(?:the\\s+)?revealed\\s+cards?\\s+to\\s+your\\s+hand[.!]?"
    );

    /**
     * Matches the compound followup "Remove them from the game. If these cards are of the
     * same card type, also draw N card(s)."
     * Group {@code count} — number of cards to draw.
     */
    static final Pattern FOLLOWUP_RFP_IF_SAME_TYPE_DRAW = Pattern.compile(
        "(?i)Remove\\s+them\\s+from\\s+(?:the\\s+)?game[.!]?\\s+" +
        "If\\s+these\\s+cards?\\s+are\\s+of\\s+the\\s+same\\s+card\\s+type,?\\s+" +
        "(?:also\\s+)?draw\\s+(?<count>\\d+)\\s+cards?[.!]?"
    );

    /**
     * Matches the compound followup "Reveal the top N cards of your deck.
     * For each Job [Job] revealed this way, deal it M damage.
     * Then, place the revealed cards at the bottom of your deck in any order."
     * Groups: {@code n} — card count, {@code job} — job name, {@code dmg} — damage per match.
     */
    static final Pattern FOLLOWUP_REVEAL_TOP_N_JOB_DEAL_DMG_PLACE_BOTTOM = Pattern.compile(
        "(?i)Reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "For\\s+each\\s+(?:Job\\s+)?(?<job>.+?)\\s+revealed\\s+this\\s+way,?\\s+" +
        "deal\\s+it\\s+(?<dmg>\\d+)\\s+damage[.!]?\\s+" +
        "(?:Then,?\\s+)?[Pp]lace\\s+the\\s+revealed\\s+cards?\\s+at\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?"
    );

    /** Matches "Shuffle your deck." */
    static final Pattern SHUFFLE_DECK = Pattern.compile(
        "(?i)Shuffle\\s+your\\s+deck\\.?"
    );

    /** Matches "Its auto-ability will not trigger." — suppresses ETF auto-abilities for the played card. */
    static final Pattern ITS_AUTO_ABILITY_WILL_NOT_TRIGGER = Pattern.compile(
        "(?i)Its\\s+auto-ability\\s+will\\s+not\\s+trigger\\.?"
    );

    /** Matches "Play it onto the field" or "Play them onto the field". */
    static final Pattern FOLLOWUP_PLAY_ONTO_FIELD = Pattern.compile(
        "(?i)Play\\s+(?:it|them)\\s+onto\\s+(?:the\\s+)?field"
    );

    /** Matches "Play it onto the field dull" or "Play them onto the field dull". */
    private static final Pattern FOLLOWUP_PLAY_ONTO_FIELD_DULL = Pattern.compile(
        "(?i)Play\\s+(?:it|them)\\s+onto\\s+(?:the\\s+)?field\\s+dull[.!]?"
    );

    /**
     * Matches "When it enters the field, if it is [cond], [inner]" — a conditional secondary
     * for Play-onto-field that fires only when the played card satisfies the condition.
     * Group {@code cond} is fed to {@link #parseRevealCondition}; group {@code inner}
     * is parsed as a standalone effect via {@link #parse}.
     */
    static final Pattern FOLLOWUP_PLAY_ONTO_FIELD_WHEN_ENTERS_CONDITIONAL = Pattern.compile(
        "(?i)^When\\s+it\\s+enters\\s+(?:the\\s+)?field,?\\s+if\\s+it\\s+is\\s+(?<cond>.+?),\\s*(?<inner>.+?)[.!]?$",
        Pattern.DOTALL
    );

    /**
     * Matches "If its cost is equal to or less than the number of Job [job] you control, play it onto the field."
     * Group {@code job} captures the job name (without "Job " prefix).
     */
    static final Pattern FOLLOWUP_PLAY_IF_COST_LE_JOB_COUNT = Pattern.compile(
        "(?i)If\\s+its\\s+cost\\s+is\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+the\\s+number\\s+of\\s+" +
        "Job\\s+(?<job>.+?)\\s+you\\s+control[,.]\\s+play\\s+it\\s+onto\\s+(?:the\\s+)?field[.!]?"
    );

    /**
     * Matches "If its cost is equal to or less than the number of cards in your hand, return it to its owner's hand."
     * Used by Leviathan (5-139C) EX Burst.
     */
    static final Pattern FOLLOWUP_RETURN_IF_COST_LE_HAND = Pattern.compile(
        "(?i)If\\s+its\\s+cost\\s+is\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+the\\s+number\\s+of\\s+" +
        "cards?\\s+in\\s+your\\s+hand,?\\s+return\\s+it\\s+to\\s+its\\s+owner'?s?\\s+hand[.!]?"
    );

    /** Matches "Add it to your hand" or "Add them to your hand". */
    static final Pattern FOLLOWUP_ADD_TO_HAND = Pattern.compile(
        "(?i)Add\\s+(?:it|them)\\s+to\\s+your\\s+hand"
    );

    /**
     * Matches a conditional secondary clause that depends on the card just added to hand:
     * "If (it|the added card) (is|has) [cond], [inner effect]".
     * Group {@code cond} is fed to {@link #parseRevealCondition}; group {@code inner}
     * is parsed as a standalone effect via {@link #parse}.
     */
    static final Pattern FOLLOWUP_ADD_TO_HAND_CONDITIONAL_SECONDARY = Pattern.compile(
        "(?i)^If\\s+(?:it|the\\s+added\\s+card)\\s+(?:is|has)\\s+(?<cond>[^,]+?)" +
        ",\\s*(?<inner>.+?)[.!]?$",
        Pattern.DOTALL
    );

    /**
     * Matches "it cannot block this turn" or
     * "It gains 'This Forward cannot block.' until the end of the turn."
     */
    static final Pattern FOLLOWUP_CANNOT_BLOCK = Pattern.compile(
        "(?i)(?:" +
            "(?:it|they)\\s+cannot\\s+block\\s+this\\s+turn" +
        "|" +
            "(?:it|they)\\s+gains?\\s+['\"]This\\s+Forward\\s+cannot\\s+block\\.['\"]" +
            "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn" +
        ")[.!]?"
    );

    /**
     * Matches "It cannot be blocked [by a Forward of cost N or more/less] this turn."
     * Groups: {@code costval} (optional), {@code costcmp} (optional: "more" or "less")
     */
    static final Pattern FOLLOWUP_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)it\\s+cannot\\s+be\\s+blocked" +
        "(?:\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "\\s+this\\s+turn\\.?"
    );

    /**
     * Matches "It can only be blocked by a Forward of cost equal or inferior to its own this turn."
     */
    static final Pattern FOLLOWUP_ONLY_BLOCKED_BY_COST_LE_OWN = Pattern.compile(
        "(?i)it\\s+can\\s+only\\s+be\\s+blocked\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+" +
        "(?:equal\\s+or\\s+inferior\\s+to|inferior\\s+or\\s+equal\\s+to|equal\\s+to\\s+or\\s+less\\s+than)\\s+" +
        "its\\s+own\\s+this\\s+turn[.!]?"
    );

    /** Matches "All Forwards cannot block this turn." — global block-prevention. */
    static final Pattern STANDALONE_ALL_FORWARDS_CANNOT_BLOCK = Pattern.compile(
        "(?i)All\\s+Forwards?\\s+cannot\\s+block\\s+this\\s+turn[.!]?"
    );

    /** Matches "All Forwards of cost N or less/more cannot block this turn." */
    static final Pattern STANDALONE_FORWARDS_OF_COST_CANNOT_BLOCK = Pattern.compile(
        "(?i)All\\s+Forwards?\\s+of\\s+cost\\s+(?<costval>\\d+)\\s+or\\s+(?<cmp>less|more)\\s+cannot\\s+block\\s+this\\s+turn[.!]?"
    );

    /**
     * Matches "At the end of your next turn, if [Name] is on the field, your opponent loses the game."
     */
    private static final Pattern END_OF_NEXT_TURN_IF_CARD_ON_FIELD_OPP_LOSES = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+your\\s+next\\s+turn,?\\s+if\\s+(?<name>.+?)\\s+is\\s+on\\s+the\\s+field,?\\s+" +
        "your\\s+opponent\\s+loses\\s+the\\s+game[.!]?"
    );

    /**
     * Matches "All the Forwards opponent controls lose all abilities until the end of the turn."
     */
    static final Pattern OPP_FWDS_LOSE_ALL_ABILITIES_EOT = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+(?:(?:your\\s+)?opponent\\s+controls?)\\s+" +
        "lose\\s+all\\s+abilities\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All the Forwards opponent controls lose N power for each CP required to play them
     * until the end of the turn." (Flare Star / Ozma).
     * Group {@code amount} — power lost per CP of cost.
     */
    static final Pattern OPP_FWDS_LOSE_POWER_PER_PLAY_COST = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+(?:(?:your\\s+)?opponent\\s+controls?)\\s+" +
        "lose\\s+(?<amount>\\d+)\\s+power\\s+for\\s+each\\s+CP\\s+required\\s+to\\s+play\\s+them\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All the Forwards opponent controls cannot block Forwards with a power inferior to their own this turn."
     */
    static final Pattern OPP_FWDS_CANNOT_BLOCK_INFERIOR_POWER_THIS_TURN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+(?:(?:your\\s+)?opponent\\s+controls?)\\s+" +
        "cannot\\s+block\\s+Forwards?\\s+with\\s+a\\s+power\\s+inferior\\s+to\\s+their\\s+own\\s+this\\s+turn[.!]?"
    );

    /**
     * Matches "Each Forward can only be blocked by a Forward with a cost inferior or equal to
     * its own this turn." — global rule applying to all attackers on both sides.
     */
    static final Pattern ALL_FWDS_BLOCKED_ONLY_BY_LOWER_COST_THIS_TURN = Pattern.compile(
        "(?i)Each\\s+Forward\\s+can\\s+only\\s+be\\s+blocked\\s+by\\s+a\\s+Forward\\s+with\\s+a\\s+cost\\s+" +
        "inferior\\s+or\\s+equal\\s+to\\s+its\\s+own\\s+this\\s+turn[.!]?"
    );

    /**
     * Matches "During this turn, the power of Forwards opponent controls cannot be increased by Summons or abilities."
     * Action-ability counterpart to the persistent field effect FA_OPP_FORWARD_POWER_BOOST_SUPPRESSED.
     */
    static final Pattern OPP_FWD_POWER_BOOST_SUPPRESSED_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+the\\s+power\\s+of\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls?\\s+" +
        "cannot\\s+be\\s+increased\\s+by\\s+Summons?\\s+or\\s+abilit(?:y|ies)[.!]?"
    );

    /** Matches "[CardName] cannot be blocked this turn." — self-referential standalone form. */
    static final Pattern STANDALONE_SELF_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+cannot\\s+be\\s+blocked" +
        "(?:\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "\\s+this\\s+turn[.!]?"
    );

    /**
     * Matches "If the cost paid to play [name] included [element] CP, it cannot be blocked
     * [by a Forward of cost N or more/less] this turn."
     * Groups: {@code element}, optional {@code costval}/{@code costcmp}
     */
    static final Pattern FOLLOWUP_CANNOT_BE_BLOCKED_IF_ELEMENT_CP = Pattern.compile(
        "(?i)if\\s+the\\s+cost\\s+paid\\s+to\\s+play\\s+.+?\\s+included\\s+" +
        "(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+CP,?\\s+" +
        "it\\s+cannot\\s+be\\s+blocked" +
        "(?:\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<costval>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "\\s+this\\s+turn\\.?"
    );

    /** Matches "if possible, it must block this turn" or the gains-until-EOT equivalent. */
    static final Pattern FOLLOWUP_MUST_BLOCK = Pattern.compile(
        "(?i)(?:" +
            "if\\s+possible[,]?\\s+it\\s+must\\s+block\\s+this\\s+turn" +
            "|it\\s+gains\\s+[\"']If\\s+possible[,]?\\s+this\\s+Forward\\s+must\\s+block\\.?[\"']\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn" +
        ")[.!]?"
    );

    /** Matches "Return it to its owner's hand and draw N card(s)." — group {@code draw} is the count. */
    private static final Pattern FOLLOWUP_RETURN_AND_DRAW = Pattern.compile(
        "(?i)Return\\s+it\\s+to\\s+its\\s+owner's\\s+hand\\s+and\\s+draw\\s+(?<draw>\\d+)\\s+cards?[.!]?"
    );

    /**
     * Matches "Return it and [CardName] to their owners' hand(s)." — chosen target plus a named card.
     * Group {@code named} — the additional card name to return.
     */
    static final Pattern FOLLOWUP_RETURN_AND_NAMED_TO_OWNERS_HAND = Pattern.compile(
        "(?i)Return\\s+it\\s+and\\s+(?<named>.+?)\\s+to\\s+their\\s+owners?'s?\\s+hands?[.!]?"
    );

    /** Matches "Return it/them to its/their owner's/owners' hand/hands." */
    static final Pattern FOLLOWUP_RETURN_TO_OWNERS_HAND = Pattern.compile(
        "(?i)Return\\s+(?:it|them)\\s+to\\s+(?:its|their)\\s+owners?'s?\\s+hands?\\.?"
    );

    /** Matches "Return it/them to your hand/hands." */
    static final Pattern FOLLOWUP_RETURN_TO_YOUR_HAND = Pattern.compile(
        "(?i)Return\\s+(?:it|them)\\s+to\\s+your\\s+hands?\\.?"
    );

    /**
     * Matches "Return all [the] [element] [targets] [control] to their owners' hands."
     * Named groups: {@code element}, {@code targets}, {@code control}.
     */
    static final Pattern ALL_RETURN_TO_HAND_PATTERN = Pattern.compile(
        "(?i)Return\\s+all\\s+(?:the\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+to\\s+(?:(?:its|their)\\s+owner(?:'s|s')?\\s+hands?|your\\s+hand)[.!]?"
    );

    /**
     * Matches "Choose any number of [Forwards[/and Monsters]/Backups/Characters]
     * [opponent controls | you control | &lt;none&gt;].
     * [Return them to their owners' hands.]"
     *
     * <p>The control clause and the return sentence are both optional so the pattern covers
     * abbreviated forms (e.g. Zell/Vivi ETF) as well as the full explicit version.
     */
    static final Pattern CHOOSE_ANY_NUMBER_RETURN_TO_HAND = Pattern.compile(
        "(?i)Choose\\s+any\\s+number\\s+of\\s+" +
        "(?<types>Forwards?(?:\\s+and\\s+Monsters?)?|Monsters?(?:\\s+and\\s+Forwards?)?|Backups?|Characters?)" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "[.!]?(?:\\s*Return\\s+them\\s+to\\s+their\\s+owners?'?s?\\s+hands?[.!]?)?"
    );

    /** Matches "Return [name] to its owner's hand." — named card, not a pronoun. */
    static final Pattern RETURN_NAMED_TO_OWNERS_HAND = Pattern.compile(
        "(?i)Return\\s+(?!(?:it|them)\\b)(?<named>.+?)\\s+to\\s+its\\s+owner(?:'s|s')?\\s+hand[.!]?"
    );

    /**
     * Matches "Return [name] to your hand." — named card, not a pronoun.  The name is limited to
     * 1–5 words ("Good King Moggle Mog XII" is the longest there is); an unbounded name lets a
     * single "Return" swallow whole sentences up to a later "… to your hand", which is how
     * Schultz 27-100R's "Return these to the top and/or bottom … add it to your hand" used to be
     * claimed here instead of by the look-at-deck parsers.
     */
    static final Pattern RETURN_NAMED_TO_YOUR_HAND_STANDALONE = Pattern.compile(
        "(?i)Return\\s+(?!(?:it|them)\\b)(?<named>\\S+(?:\\s+\\S+){0,4})\\s+to\\s+your\\s+hand[.!]?"
    );

    /** Matches "Add [name] to your hand." — named card, not a pronoun or a count. Used for break-zone-origin abilities. */
    static final Pattern ADD_NAMED_TO_YOUR_HAND = Pattern.compile(
        "(?i)\\bAdd\\s+(?!(?:it|them|\\d)\\b)(?<named>.+?)\\s+to\\s+your\\s+hand[.!]?"
    );

    /**
     * Matches "Play [name] onto [the] field [dull]" without requiring a "from Break Zone" qualifier.
     * Used for break-zone-origin abilities where the card plays itself from the BZ.
     * The name is limited to 1–3 words to avoid matching non-source cards.
     */
    static final Pattern PLAY_SOURCE_ONTO_FIELD_PATTERN = Pattern.compile(
        "(?i)\\bPlay\\s+(?<name>\\S+(?:\\s+\\S+){0,2})\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?"
    );

    /**
     * Matches "If its power has become N or less/more, return [name] to your/its owner's hand."
     * Groups: {@code threshold} — power value; {@code cmp} — "less" or "more";
     * {@code name} — card name; {@code toowner} — non-null when "its owner's hand".
     */
    static final Pattern CONDITIONAL_POWER_RETURN = Pattern.compile(
        "(?i)If\\s+its?\\s+power\\s+has\\s+become\\s+(?<threshold>\\d+)\\s+or\\s+(?<cmp>less|more),\\s+" +
        "return\\s+(?<name>.+?)\\s+to\\s+(?:(?<toowner>its\\s+owner(?:'s|s')?)\\s+|your\\s+)hand[.!]?"
    );

    /**
     * Matches "Put [CardName] at the bottom of its owner's deck." — self-referential standalone,
     * used when a card sends itself to the bottom of the deck as part of an ability chain.
     * Group: {@code name} — the card name (must equal source.name()).
     */
    static final Pattern PUT_SOURCE_TO_BOTTOM_OF_DECK = Pattern.compile(
        "(?i)Put\\s+(?<name>.+?)\\s+at\\s+the\\s+bottom\\s+of\\s+its\\s+owner's\\s+deck[.!]?"
    );

    /**
     * Matches "Reveal the top N cards of your deck. Play 1 Card Name X of cost M or less among
     * them onto the field and return the other cards to the bottom of your deck in any order."
     * Groups: {@code n}, {@code cardname}, {@code maxcost}.
     */
    static final Pattern REVEAL_PLAY_NAMED_MAX_COST_REST_BOTTOM = Pattern.compile(
        "(?i)reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+1\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+of\\s+cost\\s+(?<maxcost>\\d+)\\s+or\\s+less\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?"
    );

    /**
     * Matches "Reveal the top N cards of your deck. Play up to M Card Name X or Job Y of cost C
     * or less among them onto the field and return the other cards to the bottom of your deck in
     * any order." — combined Card-Name-or-Job filter with a cost ceiling (e.g. Moogle (XIV)).
     * Groups: {@code n}, {@code max}, {@code cardname}, {@code job}, {@code maxcost}.
     */
    static final Pattern REVEAL_PLAY_NAMED_OR_JOB_MAX_COST_REST_BOTTOM = Pattern.compile(
        "(?i)reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+(?:up\\s+to\\s+)?(?<max>\\d+)\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+or\\s+Job\\s+(?<job>.+?)\\s+" +
        "of\\s+cost\\s+(?<maxcost>\\d+)\\s+or\\s+less\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?"
    );

    /**
     * Matches "Select 1 card type. Turn over one card at a time from the top of your deck until
     * a selected type is revealed. Add it to your hand. Then, shuffle the other cards revealed
     * and return them to the bottom of your deck."
     */
    static final Pattern FLIP_UNTIL_TYPE_TO_HAND_REST_SHUFFLE_BOTTOM = Pattern.compile(
        "(?i)select\\s+1\\s+card\\s+type[.]?\\s+" +
        "Turn\\s+over\\s+one\\s+card\\s+at\\s+a\\s+time\\s+from\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+" +
        "until\\s+a\\s+selected\\s+type\\s+is\\s+revealed[.]?\\s+" +
        "Add\\s+it\\s+to\\s+your\\s+hand[.]?\\s+" +
        "Then,?\\s+shuffle\\s+the\\s+other\\s+cards?\\s+revealed\\s+and\\s+return\\s+them\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck[.!]?"
    );

    /**
     * Matches "Shuffle your deck. Then, reveal the top N cards of your deck.
     * Play 1 Card Name [name] among them onto the field and return the other cards to the
     * bottom of your deck in any order." — used as the 'when you do so' followup on self-bounce
     * abilities that search for a named card.
     * Groups: {@code n} (reveal count), {@code cardname} (card name to play).
     */
    static final Pattern SHUFFLE_THEN_REVEAL_PLAY_NAMED_REST_BOTTOM = Pattern.compile(
        "(?i)shuffle\\s+your\\s+deck[.]?\\s+Then,?\\s+" +
        "reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.]?\\s+" +
        "Play\\s+1\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+among\\s+them\\s+onto\\s+(?:the\\s+)?field\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?"
    );

    /**
     * Matches "Reveal the top N cards of your deck. Play up to M [Type] among them onto the field
     * and return the other cards to the bottom of your deck in any order."
     * <ul>
     *   <li>{@code n}    — number of cards to reveal</li>
     *   <li>{@code max}  — maximum cards to play onto the field ("up to M")</li>
     *   <li>{@code type} — card type filter: Forward, Backup, Monster, or Character</li>
     * </ul>
     */
    static final Pattern REVEAL_PLAY_TYPE_ONTO_FIELD_REST_BOTTOM = Pattern.compile(
        "(?i)reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+(?:up\\s+to\\s+)?(?<max>\\d+)\\s+" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<type>Forward|Backup|Monster|Character)s?\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?$"
    );

    /** Matches "reveal 1 &lt;Element&gt; card from your hand. If you do so, draw N card(s)." */
    static final Pattern REVEAL_ELEMENT_CARD_FROM_HAND_IF_SO_DRAW = Pattern.compile(
        "(?i)^\\s*reveal\\s+1\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+card\\s+from\\s+your\\s+hand[.]?\\s+" +
        "If\\s+you\\s+do\\s+so,?\\s+draw\\s+(?<draw>\\d+)\\s+cards?[.]?\\s*$"
    );

    static final Pattern REVEAL_PLAY_ELEMENT_TYPE_COST_ONTO_FIELD_REST_BOTTOM = Pattern.compile(
        "(?i)^\\s*reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Play\\s+(?:up\\s+to\\s+)?(?<max>\\d+)\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<type>Forward|Backup|Monster|Character)s?\\s+of\\s+cost\\s+(?<cost>\\d+|X)\\s+or\\s+less\\s+" +
        "among\\s+them\\s+onto\\s+(?:the\\s+)?field[,.]?\\s+" +
        "(?:Then,?\\s+shuffle\\s+the\\s+other\\s+cards?\\s+revealed\\s+and\\s+return\\s+them|" +
        "and\\s+return\\s+the\\s+other\\s+cards?)\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    /** Matches "Put it at the top or bottom of its owner's deck." — player chooses placement. Also handles "Your opponent puts it…" */
    static final Pattern FOLLOWUP_PUT_TOP_OR_BOTTOM_OF_DECK = Pattern.compile(
        "(?i)(?:Your\\s+opponent\\s+puts?\\s+it|Put\\s+it)\\s+at\\s+the\\s+top\\s+or\\s+bottom\\s+of\\s+its\\s+owner's\\s+deck\\.?"
    );

    /** Matches "Put it at the bottom of its owner's deck." Also handles "Your opponent puts it…" */
    static final Pattern FOLLOWUP_PUT_BOTTOM_OF_DECK = Pattern.compile(
        "(?i)(?:Your\\s+opponent\\s+puts?\\s+it|Put\\s+it)\\s+at\\s+the\\s+bottom\\s+of\\s+its\\s+owner's\\s+deck\\.?"
    );

    /** Matches "Put it on top of its owner's deck." Also handles "Your opponent puts it…" */
    static final Pattern FOLLOWUP_PUT_TOP_OF_DECK = Pattern.compile(
        "(?i)(?:Your\\s+opponent\\s+puts?\\s+it|Put\\s+it)\\s+on\\s+top\\s+of\\s+its\\s+owner's\\s+deck\\.?"
    );

    /**
     * Matches "If its power is equal to or less/more than [SourceName]'s power, put it on top of
     * its owner's deck." — Wakka-style conditional bounce whose threshold is the source card's power.
     * Groups: {@code sourcename} — name of the card providing the power threshold;
     *         {@code cmp} — "less" or "more".
     */
    static final Pattern FOLLOWUP_IF_POWER_CMP_SOURCE_PUT_ON_DECK_TOP = Pattern.compile(
        "(?i)If\\s+its?\\s+power\\s+is\\s+equal\\s+to\\s+or\\s+(?<cmp>less|more)\\s+than\\s+" +
        "(?<sourcename>.+?)'s\\s+power[,.]?\\s+put\\s+it\\s+on\\s+top\\s+of\\s+its\\s+owner's\\s+deck[.!]?"
    );

    /**
     * Matches "Put it under the top [N] card(s) of its owner's/your deck."
     * Group {@code numword} — present only when a number word precedes "cards" (currently only "four").
     */
    static final Pattern FOLLOWUP_PUT_UNDER_TOP_OF_DECK = Pattern.compile(
        "(?i)Put\\s+it\\s+under\\s+the\\s+top\\s+(?<numword>four\\s+)?cards?\\s+of\\s+(?:its\\s+owner's|your)\\s+deck\\.?"
    );

    /** Matches "it cannot attack this turn" or "they cannot attack this turn". */
    static final Pattern FOLLOWUP_CANNOT_ATTACK = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+attack\\s+this\\s+turn\\.?"
    );

    /** Matches "it must attack this turn if possible". */
    static final Pattern FOLLOWUP_MUST_ATTACK = Pattern.compile(
        "(?i)it\\s+must\\s+attack\\s+this\\s+turn\\s+if\\s+possible\\.?"
    );

    /** Matches "it/they cannot attack or block this turn". */
    static final Pattern FOLLOWUP_CANNOT_ATTACK_OR_BLOCK = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+attack\\s+or\\s+block\\s+this\\s+turn\\.?"
    );

    /**
     * Matches "it cannot attack or block until the end of your opponent's turn" or
     * "…until the end of the next turn".
     */
    static final Pattern FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_PERSISTENT = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+attack\\s+or\\s+block\\s+until\\s+the\\s+end\\s+of\\s+" +
        "(?:your\\s+opponent's|the\\s+next)\\s+turn\\.?"
    );

    /**
     * Standalone "[CardName] cannot attack or block." — permanent self-restriction.
     * {@code cardname} captures the subject name.
     */
    static final Pattern STANDALONE_CANNOT_ATTACK_OR_BLOCK = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+cannot\\s+attack\\s+or\\s+block[.!]?\\s*$"
    );

    /**
     * Standalone "[CardName] cannot attack." — permanent attack-only restriction.
     * {@code cardname} captures the subject name.
     */
    static final Pattern STANDALONE_CANNOT_ATTACK = Pattern.compile(
        "(?i)^(?<cardname>.+?)\\s+cannot\\s+attack[.!]?\\s*$"
    );

    /**
     * "If you don't control a Card Name [X] Forward, [CardName] cannot attack or block."
     * {@code required} — the card name that must be controlled; {@code subject} — the card restricted.
     */
    static final Pattern IF_DONT_CONTROL_CARD_NAME_FWD_CANNOT_ATTACK_OR_BLOCK = Pattern.compile(
        "(?i)If\\s+you\\s+don(?:'t|not)\\s+control\\s+(?:a\\s+)?Card\\s+Name\\s+(?<required>\\S+(?:\\s+\\S+)*)\\s+Forward,?\\s+" +
        "(?<subject>\\S+(?:\\s+\\S+)*)\\s+cannot\\s+attack\\s+or\\s+block[.!]?\\s*$"
    );

    /**
     * "If [N] or less [CounterName] Counter(s) are placed on [CardName], [CardName] cannot attack or block."
     * {@code count} — the counter threshold; {@code countername} — counter type; {@code target} — the card checked;
     * {@code subject} — the card restricted.
     */
    static final Pattern IF_COUNTER_LIMIT_CANNOT_ATTACK_OR_BLOCK = Pattern.compile(
        "(?i)If\\s+(?<count>\\d+)\\s+or\\s+less\\s+(?<countername>\\S+)\\s+Counters?\\s+are\\s+placed\\s+on\\s+" +
        "(?<target>\\S+(?:\\s+\\S+)*),?\\s+(?<subject>\\S+(?:\\s+\\S+)*)\\s+cannot\\s+attack\\s+or\\s+block[.!]?\\s*$"
    );

    /**
     * "If your opponent doesn't control [any] Forwards, [CardName] cannot attack."
     * {@code subject} — the card that cannot attack.
     */
    static final Pattern IF_OPP_NO_FORWARDS_CANNOT_ATTACK = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+(?:doesn'?t|does\\s+not)\\s+control\\s+(?:any\\s+)?Forwards?," +
        "\\s+(?<subject>.+?)\\s+cannot\\s+attack[.!]?\\s*$"
    );

    /**
     * Matches "At the end of this turn, if you control &lt;cardName&gt;, deal it N damage."
     * Used as a Choose followup that queues conditional damage to fire at the end phase.
     * <ul>
     *   <li>Group {@code cardName} — the card the ability user must control</li>
     *   <li>Group {@code damage}   — fixed damage amount</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_END_OF_TURN_COND_DAMAGE = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+this\\s+turn,\\s+if\\s+you\\s+control\\s+(?<cardName>.+?),\\s+deal\\s+it\\s+(?<damage>\\d+)\\s+damage\\.?"
    );

    /** Matches "At the end of this turn, &lt;rest&gt;" — any delayed standalone effect. */
    private static final Pattern AT_END_OF_TURN_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+this\\s+turn,\\s+(?<rest>.+)"
    );

    /** Matches "At the end of the turn, break [CardName]." — a self-break rider on "becomes a Forward" abilities. */
    private static final Pattern AT_END_OF_TURN_BREAK_SOURCE = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+(?:the|this)\\s+turn,\\s+break\\s+.+?[.!]?"
    );

    /**
     * Shared boundary lookahead for the "global" (card-less) phase-trigger patterns' {@code inner}
     * capture group below — stops before the next "[[br]]" marker, the next "When ..." trigger
     * sentence, or a cost-token action-ability header (or end of string). Mirrors the boundary
     * already used by {@code CardData.AUTO_ABILITY_PATTERN} and {@code CardData.WARP_COUNTER_PATTERN}
     * so a multi-ability card text (e.g. "At the beginning of your Main Phase 1, X.[[br]]   When Y,
     * Z.") doesn't have its first inner effect swallow the second ability's text too.
     */
    private static final String GLOBAL_TRIGGER_INNER_BOUNDARY =
        "(?=\\s*\\[\\[br\\]\\]|\\s*When\\s+[^,]+?\\s+(?:forms?\\s+a\\s+party\\s+and\\s+attacks?" +
        "|attacks?|blocks?|enters?|leaves?|is\\s+(?:put|removed|blocked)|deals?|uses?|becomes?)" +
        "|\\s*(?:《[^》]+》)+\\s*:|\\s*$)";

    /**
     * Matches "At the end of each of your turns, &lt;inner&gt;" — a recurring end-of-turn
     * field-ability trigger.
     * <ul>
     *   <li>Group {@code inner} — the effect text that fires each end phase</li>
     * </ul>
     */
    /**
     * Matches "At the end of [each of your turns | your turn], &lt;inner&gt;" — both wordings name the
     * same trigger, the controller's own end phase. The shorter form appears on Libroarian 8-084R,
     * Death Machine 8-102R and Rem 9-059R, and inside Vayne 9-022L's granted ability (which callers
     * must skip, since text quoted on a card is not that card's own ability).
     */
    static final Pattern AT_END_OF_EACH_TURN_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+(?:each\\s+of\\s+your\\s+turns?|your\\s+turn)\\s*,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );

    /** Matches "At the end of each player's turn, &lt;inner&gt;" — fires at both players' end phase. */
    static final Pattern AT_END_OF_EACH_PLAYERS_TURN_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+each\\s+player'?s\\s+turn,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );

    /**
     * "At the end of each player's turn, if [CardName] has received N damage or more, draw M card(s)."
     * Fires at the end of every player's turn (both P1 and P2).
     * Groups: {@code cardname} — the card name (must equal source); {@code damage} — minimum accumulated
     * combat damage; {@code draw} — number of cards to draw.
     */
    static final Pattern AT_END_OF_EACH_PLAYERS_TURN_IF_SELF_FWD_DAMAGE_DRAW = Pattern.compile(
        "(?i)^At\\s+the\\s+end\\s+of\\s+each\\s+player'?s\\s+turn,\\s+" +
        "if\\s+(?<cardname>.+?)\\s+has\\s+received\\s+(?<damage>\\d+)\\s+damage\\s+or\\s+more,\\s+" +
        "draw\\s+(?<draw>\\d+)\\s+cards?[.!]?\\s*$"
    );

    /**
     * "If there are N or more cards removed from the game, &lt;effect&gt;"
     * Group {@code count} is the threshold; {@code inner} is the conditional effect text.
     */
    static final Pattern IF_RFP_COUNT_INNER = Pattern.compile(
        "(?i)^If\\s+there\\s+are\\s+(?<count>\\d+)\\s+or\\s+more\\s+cards?\\s+removed\\s+from\\s+the\\s+game,\\s+(?<inner>.+)",
        Pattern.DOTALL
    );

    /**
     * "At the beginning of your Main Phase 1[ each turn etc.], &lt;effect&gt;"
     * Group {@code inner} captures the effect text after the trigger comma.  Modeled on
     * {@link #AT_END_OF_EACH_TURN_PATTERN} — the inner effect is dispatched through
     * the full {@link #parse} chain so any supported effect can follow the trigger.
     */
    static final Pattern AT_BEGINNING_OF_MAIN_PHASE_1_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+your\\s+Main\\s+Phase\\s+1\\b[^,]*,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );

    /** Same as {@link #AT_BEGINNING_OF_MAIN_PHASE_1_PATTERN} but for Main Phase 2. */
    static final Pattern AT_BEGINNING_OF_MAIN_PHASE_2_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+your\\s+Main\\s+Phase\\s+2\\b[^,]*,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );

    /**
     * "Each turn, at the beginning of Main Phase 1, [inner]" — fires at BOTH players' Main Phase 1 starts.
     * Group {@code inner} — the conditional effect to evaluate.
     */
    static final Pattern AT_BEGINNING_OF_MAIN_PHASE_1_EACH_TURN_PATTERN = Pattern.compile(
        "(?i)Each\\s+turn,?\\s+at\\s+the\\s+beginning\\s+of\\s+Main\\s+Phase\\s+1,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );

    /**
     * "At the beginning of your opponent's Main Phase 1, [inner]" — fires at the start of the
     * opponent's Main Phase 1 (i.e., when the card controller's opponent begins their Main Phase 1).
     * Group {@code inner} — the effect to evaluate.
     */
    static final Pattern AT_BEGINNING_OF_OPP_MAIN_PHASE_1_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+your\\s+opponent'?s\\s+Main\\s+Phase\\s+1\\b[^,]*,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );

    /**
     * "At the end of your opponent's turn, [inner]" — fires at the end of the controlling player's
     * opponent's turn (i.e., whenever the opponent ends their turn).
     * Group {@code inner} — the effect to fire.
     */
    static final Pattern AT_END_OF_OPP_TURN_PATTERN = Pattern.compile(
        "(?i)At\\s+the\\s+end\\s+of\\s+(?:each\\s+of\\s+)?your\\s+opponent'?s\\s+turns?,\\s+" +
        "(?<inner>.+?)\\s*" + GLOBAL_TRIGGER_INNER_BOUNDARY,
        Pattern.DOTALL
    );

    /**
     * "Select 1 Element. &lt;CardName&gt; becomes that Element[ (this effect does not end at the
     * end of the turn)]." Group {@code name} is the card whose element changes; the
     * trailing parenthetical, when present, marks this as a permanent override.  Used by
     * {@link #tryParseElementChange}, which also checks {@code source.name()} matches
     * {@code name} so this parser cannot fire on an unrelated card.
     */
    static final Pattern ELEMENT_CHANGE_PATTERN = Pattern.compile(
        "(?i)^\\s*select\\s+1\\s+Element\\.\\s+" +
        "(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+becomes\\s+that\\s+Element" +
        "(?:\\s*\\(this\\s+effect\\s+does\\s+not\\s+end\\s+at\\s+the\\s+end\\s+of\\s+the\\s+turn\\))?\\s*\\.?\\s*$"
    );

    /** All eight FFTCG element names, in standard order. */
    static final String[] ELEMENT_NAMES = {"Fire", "Ice", "Wind", "Earth", "Lightning", "Water", "Light", "Dark"};

    /**
     * Matches "The [optional filter] Forwards you control can form a party with [anything]
     * Forwards of any Element this turn." — turn-scoped party-element-wildcard grant.
     * Identical to the field-ability form in {@link CardData#FIELD_PARTY_ANY_ELEMENT_PATTERN}
     * except it requires "this turn" at the end.
     */
    static final Pattern GRANT_PARTY_ANY_ELEMENT_THIS_TURN = Pattern.compile(
        "(?i)The\\s+" +
        "(?:Job\\s+(?<job>.+?)\\s+|Category\\s+(?<category>\\S+)\\s+|Card\\s+Name\\s+(?<cardname>\\S+)\\s+)?" +
        "Forwards?\\s+you\\s+control\\s+can\\s+form\\s+a\\s+party\\s+with\\s+" +
        "(?:.+?\\s+)?Forwards?\\s+of\\s+any\\s+Element\\s+this\\s+turn\\s*\\.?"
    );

    /**
     * Matches "Name 1 Element[ other than X[ or Y]]. [CardName] becomes the named Element until the end of the turn."
     * — element-only self-becomes with optional exclusion.
     */
    static final Pattern NAME_ELEMENT_ONLY_SELF_BECOMES = Pattern.compile(
        "(?i)Name\\s+1\\s+Element" +
        "(?:\\s+other\\s+than\\s+(?<exclude>[^.]+))?" +
        "[.!]?\\s+" +
        "(?<name>.+?)\\s+becomes?\\s+the\\s+named\\s+Element" +
        "\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?"
    );

    /**
     * Matches "Name 1 Element and 1 Job" / "Name 1 Job and 1 Element" with an optional
     * "other than X[ or Y]" element exclusion, where the source card becomes the named Element
     * and Job until end of turn.
     */
    static final Pattern NAME_ELEMENT_AND_JOB_SELF_BECOMES = Pattern.compile(
        "(?i)Name\\s+1\\s+(?:Element\\s+and\\s+1\\s+Job|Job\\s+and\\s+1\\s+Element)" +
        "(?:\\s+other\\s+than\\s+(?<exclude>[^.]+))?" +
        "[.!]?\\s+" +
        "(?<name>.+?)\\s+becomes?\\s+the\\s+named\\s+(?:Element\\s+and\\s+Job|Job\\s+and\\s+Element)" +
        "\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?"
    );

    /**
     * Matches "Name 1 Job and 1 Element[ other than X[ or Y]]. &lt;CardName&gt; gains named Job and
     * Element. [(This effect does not end at the end of the turn.)]" — a permanent element and job
     * grant (no EOT revert).
     */
    static final Pattern NAME_JOB_AND_ELEMENT_SELF_GAINS_PERMANENT = Pattern.compile(
        "(?i)Name\\s+1\\s+(?:Job\\s+and\\s+1\\s+Element|Element\\s+and\\s+1\\s+Job)" +
        "(?:\\s+other\\s+than\\s+(?<exclude>[^.]+))?" +
        "[.!]?\\s+" +
        "(?<name>.+?)\\s+gains?\\s+(?:the\\s+)?named\\s+(?:Job\\s+and\\s+Element|Element\\s+and\\s+Job)[.!]?\\s*" +
        "(?:\\(This\\s+effect\\s+does\\s+not\\s+end\\s+at\\s+the\\s+end\\s+of\\s+the\\s+turn\\.?\\))?"
    );

    /**
     * Matches "Name 1 Job or 1 Element. Until the end of the turn, all Forwards you control
     * gain +N power and the named Job or Element."
     */
    static final Pattern NAME_JOB_OR_ELEMENT_ALL_FORWARDS_BOOST = Pattern.compile(
        "(?i)Name\\s+1\\s+(?:Job\\s+or\\s+1\\s+Element|Element\\s+or\\s+1\\s+Job)[.!]?\\s+" +
        "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "all\\s+(?:the\\s+)?Forwards?\\s+you\\s+control\\s+gains?\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower\\s+" +
        "and\\s+(?:the\\s+)?named\\s+(?:Job\\s+or\\s+(?:an?\\s+)?Element|(?:an?\\s+)?Element\\s+or\\s+Job)[.!]?"
    );

    /** Matches the standalone "Name 1 Job" / "Select a Job" ETF effect. */
    private static final Pattern NAME_JOB_STANDALONE = Pattern.compile(
        "(?i)^(?:name\\s+1|select\\s+a)\\s+Job[.!]?$"
    );

    /**
     * Matches "Name 1 Job or Category. Reveal the top N cards of your deck.
     * Add up to M Characters of the named Job or Category among them to your hand
     * and return the other cards to the bottom of your deck in any order."
     */
    static final Pattern NAME_JOB_OR_CATEGORY_REVEAL_ADD_TO_HAND = Pattern.compile(
        "(?i)Name\\s+1\\s+(?:Job\\s+or\\s+Category|Category\\s+or\\s+Job)[.!]?\\s+" +
        "Reveal\\s+the\\s+top\\s+(?<reveal>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+up\\s+to\\s+(?<maxAdd>\\d+)\\s+Characters?\\s+of\\s+the\\s+named\\s+" +
        "(?:Job\\s+or\\s+Category|Category\\s+or\\s+Job)\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck\\s+" +
        "in\\s+any\\s+order[.!]?"
    );

    /**
     * Matches "reveal the top N cards of your deck. Add 1 Category X [Type] among them to your hand
     * and return the other cards to the bottom of your deck in any order."
     * Groups: {@code n} (card count), {@code cat} (category identifier, e.g. "MBM").
     */
    static final Pattern REVEAL_TOP_N_CATEGORY_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+1\\s+Category\\s+(?<cat>\\S+)(?:\\s+(?:Forward|Backup|Character|Monster|card))?\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    /**
     * Matches "reveal the top N cards of your deck. Add 1 Job X [or Card Name Y] [or Job Z ...]
     * among them to your hand and return the other cards to the bottom of your deck in any order."
     * Groups: {@code n} (card count); {@code first}/{@code second} each {@code "Job …"} or
     * {@code "Card Name …"} filter terms. The captured terms are split into a job filter and a
     * card-name filter at parse time.
     */
    /**
     * Matches "Reveal the top N cards of your deck. Add M [Type] [of cost C or less] among them
     * to your hand and return the other cards to the bottom of your deck in any order."
     * The "of cost C or less" clause is optional.
     * Groups: {@code n} (reveal count), {@code max} (max to add), {@code type} (card type),
     * {@code cost} (max cost; {@code null} when the clause is absent).
     */
    static final Pattern REVEAL_TOP_N_TYPE_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+(?<max>\\d+)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?|Summons?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+less)?\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    static final Pattern REVEAL_TOP_N_JOB_OR_NAME_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+1\\s+" +
        "(?<first>(?:Job|Card\\s+Name)\\s+.+?)" +
        "(?:\\s+or\\s+(?<second>(?:Job|Card\\s+Name)\\s+.+?))?" +
        "(?:\\s+(?:Forward|Backup|Character|Monster|card))?\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    /**
     * Matches "Reveal the top N cards of your deck. Add M [Element] [Type|card[s]] among them to
     * your hand and return the other cards to the bottom of your deck in any order", plus the
     * "Add 1 [Element] or Category [X] card …" variant (Wakka) where the optional {@code or Category}
     * clause makes the element and category <em>alternatives</em> (a card qualifies if it contains
     * the element OR belongs to the category).
     * Groups: {@code n} (reveal count), {@code max} (max to add), {@code element} (element name),
     * {@code type} (card type; only in the plain form), {@code cat} (category; only in the "or Category" form).
     */
    static final Pattern REVEAL_TOP_N_ELEMENT_TO_HAND = Pattern.compile(
        "(?i)^\\s*(?:you\\s+may\\s+)?reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+(?<max>\\d+)\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark|Multi-Element)\\s+" +
        "(?:" +
            "or\\s+Category\\s+(?<cat>\\S+)(?:\\s+(?:Forward|Backup|Character|Monster|card)s?)?" +
            "|" +
            "(?:(?<type>Forwards?|Backups?|Monsters?|Characters?)|cards?)" +
        ")\\s+" +
        "among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    /**
     * Matches "Reveal the top N cards of your deck. Add up to M cards other than Card Name [name]
     * among them to your hand, and put the rest of the cards into the Break Zone."
     * Groups: {@code n}, {@code max}, {@code name}.
     */
    static final Pattern REVEAL_TOP_N_ADD_UP_TO_EXCLUDING_NAME_REST_BZ = Pattern.compile(
        "(?i)^\\s*reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+up\\s+to\\s+(?<max>\\d+)\\s+cards?\\s+other\\s+than\\s+Card\\s+Name\\s+(?<name>.+?)\\s+" +
        "among\\s+them\\s+to\\s+your\\s+hand,?\\s+" +
        "and\\s+put\\s+the\\s+rest\\s+of\\s+the\\s+cards?\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s*$"
    );

    /**
     * Matches "Reveal the top N cards of your deck. Add M [Type] among them to your hand or
     * play M [Job] [Type] among them onto the field, and return the other cards to the bottom
     * of your deck in any order."
     * <ul>
     *   <li>{@code n}        — number of cards to reveal</li>
     *   <li>{@code handmax}  — max cards for the add-to-hand branch</li>
     *   <li>{@code handtype} — type filter for the hand branch (Forward/Backup/Monster/Character)</li>
     *   <li>{@code fieldmax} — max cards for the play-onto-field branch</li>
     *   <li>{@code fieldjob} — optional job filter for the field branch (e.g. "Moogle")</li>
     *   <li>{@code fieldtype}— type filter for the field branch</li>
     * </ul>
     */
    static final Pattern REVEAL_ADD_TYPE_TO_HAND_OR_PLAY_JOB_TYPE_ONTO_FIELD_REST_BOTTOM = Pattern.compile(
        "(?i)^\\s*reveal\\s+the\\s+top\\s+(?<n>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Add\\s+(?<handmax>\\d+)\\s+(?<handtype>Forward|Backup|Monster|Character)s?\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+" +
        "or\\s+play\\s+(?<fieldmax>\\d+)\\s+" +
        "(?:Job\\s+(?<fieldjob>.+?)(?=\\s+(?:Forward|Backup|Monster|Character)s?\\s+among)\\s+)?" +
        "(?<fieldtype>Forward|Backup|Monster|Character)s?\\s+among\\s+them\\s+onto\\s+(?:the\\s+)?field,?\\s+" +
        "and\\s+return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck" +
        "(?:\\s+in\\s+any\\s+order)?[.!]?\\s*$"
    );

    // ---- Damage-shield followup patterns (apply to selected "it/them" targets) --------

    /** Matches "During this turn, the next damage dealt to it/him becomes 0 instead." */
    static final Pattern FOLLOWUP_SHIELD_NEXT_DMG_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?:it|him)\\s+becomes\\s+0\\s+instead\\.?"
    );

    /** Matches "During this turn, the next damage dealt to you becomes 0 instead." */
    static final Pattern PLAYER_NEXT_DAMAGE_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+you\\s+becomes\\s+0\\s+instead\\.?"
    );

    /**
     * Matches "During this turn, the next damage dealt to you becomes 0 and deal [Name] N damage
     * instead." (Auron) — the player shield plus a redirect to the named Forward on consumption.
     */
    static final Pattern PLAYER_NEXT_DAMAGE_ZERO_REDIRECT = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+you\\s+becomes\\s+0\\s+" +
        "and\\s+deal\\s+(?<name>.+?)\\s+(?<dmg>\\d+)\\s+damage\\s+instead\\.?"
    );

    /** Matches "During this turn, the next damage dealt to it by Summons or abilities is reduced by N instead." */
    static final Pattern FOLLOWUP_SHIELD_NEXT_ABILITY_DMG_REDUCTION = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+it\\s+by\\s+Summons?\\s+or\\s+abilities\\s+is\\s+reduced\\s+by\\s+(?<reduction>\\d+)\\s+instead\\.?"
    );

    /** Matches "During this turn, the next damage dealt to it is reduced by N instead." or "Reduce the next damage dealt to it this turn by N." */
    static final Pattern FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?:it|him)\\s+is\\s+reduced\\s+by|Reduce\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?:it|him)\\s+this\\s+turn\\s+by)\\s+(?<reduction>\\d+)(?:\\s+instead)?\\.?"
    );

    /** Matches "During this turn, the damage dealt to it is increased by N instead." */
    static final Pattern FOLLOWUP_DEBUFF_INCOMING_DMG_INCREASE = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+damage\\s+dealt\\s+to\\s+it\\s+is\\s+increased\\s+by\\s+(?<amount>\\d+)\\s+instead\\.?"
    );

    /** Matches "During this turn, the next damage it deals to a Forward becomes 0 instead." */
    static final Pattern FOLLOWUP_SHIELD_NEXT_OUTGOING_ZERO = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+it\\s+deals\\s+to\\s+a\\s+Forward\\s+becomes\\s+0\\s+instead\\.?"
    );

    /** Matches "If it deals damage to a Forward [opponent controls] this turn, the damage increases by N instead." */
    static final Pattern FOLLOWUP_OUTGOING_DMG_BOOST_THIS_TURN = Pattern.compile(
        "(?i)If\\s+it\\s+deals\\s+damage\\s+to\\s+a\\s+Forward(?:\\s+opponent\\s+controls?)?\\s+this\\s+turn,?\\s+" +
        "(?:the\\s+damage\\s+increases?|increase\\s+the\\s+damage)\\s+by\\s+(?<amount>\\d+)(?:\\s+instead)?[.!]?"
    );

    /** Matches "If [CardName] deals damage to a Forward this turn, the damage increases by N instead." */
    static final Pattern SELF_OUTGOING_DMG_BOOST_THIS_TURN = Pattern.compile(
        "(?i)If\\s+(?<subject>.+?)\\s+deals\\s+damage\\s+to\\s+a\\s+Forward\\s+this\\s+turn,?\\s+" +
        "the\\s+damage\\s+increases?\\s+by\\s+(?<amount>\\d+)(?:\\s+instead)?[.!]?$"
    );

    /** Matches "During this turn, if it is dealt damage less than its power, the damage becomes 0 instead." */
    static final Pattern FOLLOWUP_SHIELD_NONLETHAL = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+it\\s+is\\s+dealt\\s+damage\\s+less\\s+than\\s+its\\s+power,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
    );

    /**
     * "It gains 'If this Forward is dealt damage by your opponent's abilities, the damage becomes
     * 0 instead.' until the end of the turn."
     */
    static final Pattern FOLLOWUP_GAINS_SHIELD_ABILITY_ONLY = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+['\"]If\\s+this\\s+Forward\\s+is\\s+dealt\\s+damage\\s+by\\s+your\\s+opponent's\\s+abilities,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /** Matches "Negate all [the] damage dealt to it/them." — removes all existing damage immediately. */
    static final Pattern FOLLOWUP_NEGATE_DAMAGE = Pattern.compile(
        "(?i)Negate\\s+all\\s+(?:the\\s+)?damage\\s+dealt\\s+to\\s+(?:it|them)\\.?"
    );

    /**
     * Matches "Activate it/them and negate all [the] damage dealt to it/them."
     * Checked before {@link #FOLLOWUP_ACTIVATE} to prevent the simpler pattern from
     * consuming only the "Activate it" prefix.
     */
    static final Pattern FOLLOWUP_ACTIVATE_AND_NEGATE_DAMAGE = Pattern.compile(
        "(?i)Activate\\s+(?:it|them)\\s+and\\s+negate\\s+all\\s+(?:the\\s+)?damage\\s+dealt\\s+to\\s+(?:it|them)\\.?"
    );

    // ---- Gain-control followup patterns -----------------------------------------------

    /**
     * "Activate it/them and gain control of it/them until the end of the turn."
     * Checked before {@link #FOLLOWUP_ACTIVATE} and {@link #FOLLOWUP_GAIN_CONTROL_EOT}
     * to avoid partial matches on the "Activate" or plain "gain control" prefixes.
     */
    static final Pattern FOLLOWUP_ACTIVATE_AND_GAIN_CONTROL_EOT = Pattern.compile(
        "(?i)Activate\\s+(?:it|them)\\s+and\\s+(?:you\\s+)?gain\\s+control\\s+of\\s+(?:it|them)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /**
     * "gain control of it/them for as long as [card] is on the field."
     * Checked before {@link #FOLLOWUP_GAIN_CONTROL} to avoid the shorter pattern matching first.
     * Group {@code condCard} captures the card name that must remain on the field.
     */
    static final Pattern FOLLOWUP_GAIN_CONTROL_WHILE_CARD = Pattern.compile(
        "(?i)(?:you\\s+)?gain\\s+control\\s+of\\s+(?:it|them)" +
        "\\s+for\\s+as\\s+long\\s+as\\s+(?<condCard>.+?)\\s+is\\s+on\\s+the\\s+field\\.?"
    );

    /** "gain control of it/them until the end of the turn." */
    static final Pattern FOLLOWUP_GAIN_CONTROL_EOT = Pattern.compile(
        "(?i)(?:you\\s+)?gain\\s+control\\s+of\\s+(?:it|them)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /** "you gain control of it/them." — permanent, no duration qualifier. */
    static final Pattern FOLLOWUP_GAIN_CONTROL = Pattern.compile(
        "(?i)(?:you\\s+)?gain\\s+control\\s+of\\s+(?:it|them)\\.?"
    );

    /**
     * Standalone: "Your opponent gains control of [CardName]." — permanent control transfer of
     * the source card itself, away from its own controller, to their opponent (e.g. Leon). The
     * reverse direction of {@link #FOLLOWUP_GAIN_CONTROL}, which is always the ability user
     * gaining control of a chosen target.
     */
    private static final Pattern STANDALONE_OPPONENT_GAINS_CONTROL = Pattern.compile(
        "(?i)^Your\\s+opponent\\s+gains?\\s+control\\s+of\\s+(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\.?\\s*$"
    );

    // ---- Cannot-be-chosen followup patterns -----------------------------------------

    /**
     * "It/They gains 'This Forward/Character cannot be chosen by your opponent's [Summons/abilities].'
     * until the end of the turn."  The grant form is semantically identical to a direct EOT shield.
     * Checked first so the simpler cannot-be-chosen patterns do not match inside the quoted text.
     * Group {@code scope} captures the scope string.
     */
    static final Pattern FOLLOWUP_GAINS_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+['\"]This\\s+(?:Forward|Character)\\s+cannot\\s+be\\s+chosen" +
        "\\s+by\\s+your\\s+opponent's\\s+(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /**
     * "[Cardname] and it gain '[quote]' until the end of your opponent's turn."
     * Rydia-style: source card and chosen target both receive the quoted ability until opponent's EOT.
     */
    private static final Pattern FOLLOWUP_SELF_AND_TARGET_GAIN_QUOTE_UNTIL_OPP_TURN = Pattern.compile(
        "(?i)\\S.*?\\s+and\\s+it\\s+gains?\\s+['\"].+?['\"]\\s+until\\s+the\\s+end\\s+of\\s+your\\s+opponent.s\\s+turn[.!]?"
    );

    /** "The next time you use its special ability this turn, you can do so without paying [cost]."
     *  Edgar-style: waives the special-ability cost for the chosen target once this turn. */
    private static final Pattern FOLLOWUP_TARGET_NEXT_SPECIAL_FREE = Pattern.compile(
        "(?i)The\\s+next\\s+time\\s+you\\s+use\\s+its\\s+special\\s+ability\\s+this\\s+turn,\\s+" +
        "you\\s+can\\s+do\\s+so\\s+without\\s+paying\\s+.+?[.!]?"
    );

    /** "During this turn, you can cast it at any time you could normally cast it as long as you have
     *  no cards in hand."  Minwu (FFBE)-style: allows instant-casting the chosen BZ card this turn. */
    private static final Pattern FOLLOWUP_CAST_IT_FROM_BZ_ANYTIME_NO_HAND = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+you\\s+can\\s+cast\\s+it\\s+at\\s+any\\s+time\\s+" +
        "you\\s+could\\s+normally\\s+cast\\s+it\\s+as\\s+long\\s+as\\s+you\\s+have\\s+no\\s+cards\\s+in\\s+hand[.!]?"
    );

    /**
     * "It/They cannot be chosen by your opponent's Summons or abilities [this turn]."
     * More specific than the Summons-only and abilities-only forms; checked first.
     */
    static final Pattern FOLLOWUP_CANNOT_BE_CHOSEN_BOTH = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "Summons?\\s+or\\s+abilities\\.?"
    );

    /** "It/They cannot be chosen by your opponent's Summons [this turn]." */
    static final Pattern FOLLOWUP_CANNOT_BE_CHOSEN_SUMMONS = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+Summons?\\.?"
    );

    /** "It/They cannot be chosen by your opponent's abilities [this turn]." */
    static final Pattern FOLLOWUP_CANNOT_BE_CHOSEN_ABILITIES = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+abilities\\.?"
    );

    /**
     * "[During this turn,] it/they cannot be returned to its/their owner's hand by your
     * opponent's Summons or abilities [this turn]." — EOT return-to-hand protection for the
     * chosen target(s), enforced via {@link CardData.Trait#CANNOT_BE_RETURNED_TO_HAND_BY_OPP}.
     */
    static final Pattern FOLLOWUP_CANNOT_BE_RETURNED_TO_HAND = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn,\\s+)?(?:it|they)\\s+cannot\\s+be\\s+returned\\s+to\\s+" +
        "(?:its|their)\\s+owner's\\s+hand\\s+by\\s+(?:your\\s+)?opponent's\\s+" +
        "(?:Summons?(?:\\s+or\\s+abilities)?|abilities)\\.?"
    );

    /** "It gains 'This Character/Forward/Monster cannot be broken.' until the end of the turn." Also matches the leading-Until form: "Until the end of the turn, it gains '...'." */
    static final Pattern FOLLOWUP_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)(?:Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,\\s+)?" +
        "(?:it|they)\\s+gains?\\s+['\"]This\\s+(?:Forward|Character|Monster)\\s+cannot\\s+be\\s+broken\\.?['\"]" +
        "(?:\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?)?"
    );

    /** "It cannot be broken this turn." */
    static final Pattern FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE = Pattern.compile(
        "(?i)(?:it|they)\\s+cannot\\s+be\\s+broken\\s+this\\s+turn\\.?"
    );

    /** "During this turn, it cannot be broken by opposing Summons or abilities that don't deal damage." */
    static final Pattern FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn,\\s+)?(?:it|they)\\s+cannot\\s+be\\s+broken\\s+by\\s+" +
        "(?:opposing|your\\s+opponent's)\\s+Summons\\s+or\\s+abilities\\s+that\\s+don'?t\\s+deal\\s+damage\\.?"
    );

    /**
     * "If it is put from the field into the Break Zone this turn, remove it from the game
     * instead." (Jet Bahamut-style) — marks the chosen target for redirect-to-RFG for the rest
     * of the turn, regardless of what later effect breaks it.
     */
    static final Pattern FOLLOWUP_IF_PUT_TO_BZ_THIS_TURN_RFG_INSTEAD = Pattern.compile(
        "(?i)If\\s+(?:it|they)\\s+(?:is|are)\\s+put\\s+from\\s+the\\s+field\\s+into\\s+the\\s+Break\\s+Zone\\s+this\\s+turn,\\s+" +
        "remove\\s+(?:it|them)\\s+from\\s+the\\s+game\\s+instead\\.?"
    );

    /**
     * "&lt;choose + primary&gt;. When it is put from the field into the Break Zone this turn, draw
     * N card(s)." (Brynhildr 15-014H, Ritz 20-062R) — a delayed trigger placed on the chosen
     * target, firing for the player who resolved the ability whenever that Forward later leaves
     * the field for the Break Zone, by combat or by any effect.
     *
     * <p>{@code head} is greedy so it runs up to the <em>last</em> occurrence of the trigger
     * clause, keeping the whole "Choose … . &lt;primary&gt;." prefix intact for the normal parser.
     * Groups: {@code head} — the choose-and-act text; {@code count} — cards drawn.
     */
    static final Pattern CHOOSE_THEN_WHEN_PUT_TO_BZ_DRAW = Pattern.compile(
        "(?is)^(?<head>.+)\\s+When\\s+(?:it|they)\\s+(?:is|are)\\s+put\\s+from\\s+the\\s+field\\s+" +
        "into\\s+the\\s+Break\\s+Zone\\s+this\\s+turn,\\s+draw\\s+(?<count>\\d+)\\s+cards?[.!]?$"
    );

    /** Standalone: "[CardName] gains '[...] cannot be broken.' until end of turn." */
    static final Pattern STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+['\"][^'\"]*?cannot\\s+be\\s+broken\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /** Standalone: "[CardName] cannot be broken this turn." — bare form without 'gains' quoting. */
    static final Pattern STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN_SIMPLE = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+cannot\\s+be\\s+broken\\s+this\\s+turn\\.?"
    );

    /**
     * Standalone: "[CardName] gains "[CardName] cannot be broken by opposing Summons or abilities
     * that don't deal damage." until the end of the turn." — self-shield limited to non-damage
     * breaks (Maat-style), the quoted-gains form of {@link #FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG}.
     */
    static final Pattern STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN_BY_NON_DMG = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+['\"].+?\\s+cannot\\s+be\\s+broken\\s+by\\s+" +
        "(?:opposing|your\\s+opponent's)\\s+Summons\\s+or\\s+abilities\\s+that\\s+don'?t\\s+deal\\s+damage\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /**
     * Standalone: "Dull [CardName]." — dulls the source card with no other effect.
     * Must be tried after {@link #STANDALONE_SELF_DULL_AND_SHIELD_CANNOT_BE_BROKEN} so the
     * compound case is not shadowed.
     */
    static final Pattern STANDALONE_SELF_DULL = Pattern.compile(
        "(?i)^dull\\s+(?<subject>.+?)\\.?\\s*$"
    );

    /**
     * Compound: "Dull [CardName]. [CardName] gains '[...] cannot be broken.' until end of turn."
     * Must be tried before the plain {@link #STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN} matcher so
     * the dull step is not silently dropped.
     */
    static final Pattern STANDALONE_SELF_DULL_AND_SHIELD_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)Dull\\s+(?<subject>.+?)\\.\\s+.+?\\s+gains?\\s+['\"][^'\"]*?cannot\\s+be\\s+broken\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /** Standalone: "All [the] Forwards you control gain '[...] cannot be broken.' until end of turn." */
    static final Pattern STANDALONE_ALL_FORWARDS_SHIELD_CANNOT_BE_BROKEN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+you\\s+control\\s+gains?\\s+" +
        "['\"][^'\"]*?cannot\\s+be\\s+broken\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    /**
     * "During this turn, if a Forward you control is dealt damage by a Summon or an ability,
     *  the damage becomes 0 instead."
     */
    static final Pattern ALL_OWN_FORWARDS_NULLIFY_ABILITY_DAMAGE_PATTERN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+if\\s+(?:a\\s+)?Forwards?\\s+you\\s+control\\s+(?:is|are)\\s+dealt\\s+damage" +
        "\\s+by\\s+(?:a\\s+)?Summons?\\s+or\\s+an?\\s+abilit(?:y|ies),?\\s+the\\s+damage\\s+becomes?\\s+0\\s+instead[.!]?"
    );

    /**
     * Doublecast (Yuna): "When you cast a Summon this turn, you may cast 1 Summon from your hand
     * with a cost inferior to that of the Summon you cast without paying its cost." — turn-long
     * field effect; the free-cast threshold follows the printed cost of the last Summon cast.
     */
    static final Pattern DOUBLECAST_FREE_SUMMONS_PATTERN = Pattern.compile(
        "(?i)When\\s+you\\s+cast\\s+a\\s+Summon\\s+this\\s+turn,?\\s+you\\s+may\\s+cast\\s+1\\s+Summon\\s+" +
        "from\\s+your\\s+hand\\s+with\\s+a\\s+cost\\s+inferior\\s+to\\s+that\\s+of\\s+the\\s+Summon\\s+" +
        "you\\s+cast\\s+without\\s+paying\\s+its\\s+cost[.!]?"
    );

    /**
     * "During this turn, if a Job [X] or Card Name [Y] you control is dealt damage by a Summon
     *  or an ability, the damage becomes 0 instead." — job/card-name-filtered variant of
     * {@link #ALL_OWN_FORWARDS_NULLIFY_ABILITY_DAMAGE_PATTERN}.
     */
    static final Pattern OWN_JOB_OR_NAME_NULLIFY_ABILITY_DAMAGE_PATTERN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+if\\s+a\\s+Job\\s+(?<job>.+?)\\s+or\\s+(?:a\\s+)?Card\\s+Name\\s+(?<cardname>.+?)" +
        "\\s+you\\s+control\\s+(?:is|are)\\s+dealt\\s+damage" +
        "\\s+by\\s+(?:a\\s+)?Summons?\\s+or\\s+an?\\s+abilit(?:y|ies),?\\s+the\\s+damage\\s+becomes?\\s+0\\s+instead[.!]?"
    );

    /** "It gains 'When this Forward deals battle damage to a Forward, break that Forward.' until the end of the turn." */
    static final Pattern FOLLOWUP_GAINS_BREAKTOUCH_BATTLE = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+['\"]When\\s+this\\s+Forward\\s+deals\\s+battle\\s+damage\\s+to\\s+a\\s+Forward,\\s+break\\s+that\\s+Forward\\.?['\"]" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\.?"
    );

    // ---- Standalone cannot-be-chosen patterns ---------------------------------------

    /**
     * "Activate all [the] Forwards/Characters you control. They cannot be chosen by
     * [your opponent's] Summons [or abilities] [this turn]."
     * "your opponent's" and "the" are optional; treated as opponent-only either way.
     * Registered before {@link #tryParseAllFieldEffect} to prevent the activate-all part
     * from consuming the text without the cannot-be-chosen clause.
     */
    static final Pattern STANDALONE_ACTIVATE_AND_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)Activate\\s+all\\s+(?:the\\s+)?(?:Forwards?|Characters?)\\s+you\\s+control\\." +
        "\\s*They\\s+cannot\\s+be\\s+chosen\\s+by\\s+(?:your\\s+opponent's\\s+)?" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*(?:this\\s+turn)?\\s*\\.?"
    );

    /**
     * "This Forward/Character cannot be chosen by your opponent's Summons/abilities."
     * Self-referential: applies protection to the {@code source} card itself.
     */
    static final Pattern STANDALONE_SELF_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)This\\s+(?:Forward|Character)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );

    /**
     * "[CardName] cannot be chosen by your opponent's Summons/abilities."
     * Only matches when {@code cardName} equals the {@code source} card's name.
     */
    static final Pattern STANDALONE_NAMED_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );

    /**
     * "[CardName] cannot be chosen by Summons [during this turn]." — no "your opponent's" qualifier,
     * meaning the protection applies to Summons from either player.
     * Only matches when {@code cardName} equals the {@code source} card's name.
     */
    static final Pattern STANDALONE_NAMED_CANNOT_BE_CHOSEN_ANY_SUMMON = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+(?!your\\s)Summons?" +
        "(?:\\s+during\\s+this\\s+turn)?\\s*\\.?"
    );

    /**
     * "Name 1 Element. During this turn, [CardName] cannot be chosen by Summons or abilities of the named
     * Element and if [CardName] is dealt damage by a Summon or an ability of the named Element, the damage
     * becomes 0 instead." — targeting immunity AND damage nullification for the named element.
     */
    static final Pattern STANDALONE_NAME_ELEMENT_IMMUNE_AND_NULLIFY_DAMAGE = Pattern.compile(
        "(?i)Name\\s+1\\s+Element\\.\\s+During\\s+this\\s+turn,\\s+" +
        "(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+Summons?\\s+or\\s+abilities\\s+of\\s+the\\s+named\\s+Element" +
        "\\s+and\\s+if\\s+[A-Za-z''\\-\\s]+?is\\s+dealt\\s+damage\\s+by\\s+a\\s+Summon\\s+or\\s+an\\s+ability\\s+of\\s+the\\s+named\\s+Element,\\s+" +
        "the\\s+damage\\s+becomes\\s+0\\s+instead\\s*\\.?"
    );

    /**
     * "Name 1 Element. During this turn, if [CardName] is dealt damage by abilities of the named
     * Element, the damage becomes 0 instead." — (Rubicante-style) damage-only nullification,
     * scoped to abilities alone (Summons are not covered), with no targeting immunity.
     */
    static final Pattern STANDALONE_NAME_ELEMENT_NULLIFY_ABILITY_DAMAGE_ONLY = Pattern.compile(
        "(?i)Name\\s+1\\s+Element\\.\\s+During\\s+this\\s+turn,\\s+if\\s+" +
        "(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+abilities\\s+of\\s+the\\s+named\\s+Element,\\s+" +
        "the\\s+damage\\s+becomes\\s+0\\s+instead\\s*\\.?"
    );

    /**
     * "Name 1 Element. [CardName] cannot be chosen by Summons or abilities of the named Element this turn."
     * Action ability: the player names an element, and the card gains immunity to that element this turn.
     */
    static final Pattern STANDALONE_NAME_ELEMENT_AND_IMMUNE = Pattern.compile(
        "(?i)Name\\s+1\\s+Element\\.\\s+" +
        "(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+Summons?\\s+or\\s+abilities\\s+of\\s+the\\s+named\\s+Element\\s+this\\s+turn\\s*\\.?"
    );

    /**
     * "[CardName] cannot be chosen by Summons or abilities that share its Element."
     * Passive field ability: immunity is checked dynamically against the resolving card's element.
     */
    private static final Pattern STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_OWN_ELEMENT = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+Summons?\\s+or\\s+abilities\\s+that\\s+share\\s+its\\s+Element\\s*\\.?"
    );

    /**
     * "The Job X [other than Y] Forwards/Characters you control cannot be chosen by
     * your opponent's Summons/abilities."
     * Group {@code job} is the job name; {@code excl} is the optional excluded card name.
     */
    static final Pattern STANDALONE_JOB_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)The\\s+Job\\s+(?<job>[^.]+?)(?:\\s+other\\s+than\\s+(?<excl>[^.]+?))?" +
        "\\s+(?:Forwards?|Characters?)\\s+you\\s+control\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );

    /**
     * "Players cannot cast Summons." — global static restriction while this card is on the field.
     * Both players are prevented from casting Summons from hand or break zone.
     */
    static final Pattern PLAYERS_CANNOT_CAST_SUMMONS = Pattern.compile(
        "(?i)^Players?\\s+cannot\\s+cast\\s+Summons?\\.?$"
    );

    /**
     * "All Summons in your Break Zone cannot be removed from the game by your opponent's
     * Summons or abilities." — protects the owner's BZ Summons from the opponent's RFG effects.
     */
    static final Pattern FA_BZ_SUMMONS_PROTECTED_FROM_OPP_RFG = Pattern.compile(
        "(?i)All\\s+Summons?\\s+in\\s+your\\s+Break\\s+Zone\\s+cannot\\s+be\\s+removed\\s+from\\s+the\\s+game\\s+" +
        "by\\s+your\\s+opponent.?s\\s+(?:Summons?\\s+or\\s+)?abilities[.!]?"
    );

    /**
     * "[CardName] cannot become dull by your opponent's Summons or abilities."
     * Permanent self-protection while this card is on the field.
     */
    static final Pattern STANDALONE_NAMED_CANNOT_BECOME_DULL_OPP = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+become\\s+dull\\s+by\\s+your\\s+opponent's\\s+" +
        "(?:Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );

    /**
     * "[CardName] cannot be returned to its owner's hand by [your] opponent's Summons or abilities."
     * Permanent self-protection while this card is on the field (Gilgamesh).
     */
    static final Pattern STANDALONE_NAMED_CANNOT_BE_RETURNED_TO_HAND_OPP = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+returned\\s+to\\s+(?:its|their)\\s+owner's\\s+hand" +
        "\\s+by\\s+(?:your\\s+)?opponent's\\s+(?:Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );

    /**
     * "Characters you control cannot be returned to their owner's hand by your opponent's
     * Summons or abilities." — blanket protection for every character the controller controls
     * while this card is on the field.
     */
    static final Pattern STANDALONE_CHARACTERS_CANNOT_BE_RETURNED_TO_HAND_OPP = Pattern.compile(
        "(?i)Characters\\s+you\\s+control\\s+cannot\\s+be\\s+returned\\s+to\\s+their\\s+owner's\\s+hand" +
        "\\s+by\\s+(?:your\\s+)?opponent's\\s+(?:Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );

    /**
     * "[CardName] cannot be put into the Break Zone by [your] opponent's Summons or abilities."
     * Permanent self-protection while this card is on the field (Black Tortoise l'Cie Gilgamesh).
     */
    static final Pattern STANDALONE_NAMED_CANNOT_BE_PUT_INTO_BZ_OPP = Pattern.compile(
        "(?i)(?<name>[A-Z][A-Za-z''\\-\\s]+?)\\s+cannot\\s+be\\s+put\\s+into\\s+the\\s+Break\\s+Zone" +
        "\\s+by\\s+(?:your\\s+)?opponent's\\s+(?:Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );

    // ---- Standalone damage-shield patterns (apply globally or to a named card) --------

    /** "Negate all [the] damage dealt to all the Forwards/Characters you control." */
    static final Pattern STANDALONE_NEGATE_DAMAGE_OWN = Pattern.compile(
        "(?i)Negate\\s+all\\s+(?:the\\s+)?damage\\s+dealt\\s+to\\s+all\\s+the\\s+" +
        "(?:Forwards?|Characters?)\\s+you\\s+control\\.?"
    );

    /**
     * "Activate all the Forwards/Characters you control and negate all [the] damage dealt to them."
     * Handled by {@link #tryParseNegateAllDamage} before {@link #tryParseAllFieldEffect}
     * so that the "activate all" part does not consume the full text without the negate clause.
     */
    static final Pattern STANDALONE_ACTIVATE_AND_NEGATE_DAMAGE_OWN = Pattern.compile(
        "(?i)Activate\\s+all\\s+the\\s+(?:Forwards?|Characters?)\\s+you\\s+control" +
        "\\s+and\\s+negate\\s+all\\s+(?:the\\s+)?damage\\s+dealt\\s+to\\s+them\\.?"
    );

    /** "During this turn, if a Forward you control is dealt damage less than its power, the damage becomes 0 instead." */
    static final Pattern STANDALONE_NONLETHAL_PROTECTION = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+a\\s+Forward\\s+you\\s+control\\s+is\\s+dealt\\s+damage\\s+less\\s+than\\s+its\\s+power,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
    );

    /** "During this turn, if a Forward you control is dealt damage, reduce the damage by N instead." */
    static final Pattern STANDALONE_GLOBAL_DMG_REDUCTION = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+a\\s+Forward\\s+you\\s+control\\s+is\\s+dealt\\s+damage,\\s+reduce\\s+the\\s+damage\\s+by\\s+(?<reduction>\\d+)\\s+instead\\.?"
    );

    /**
     * "During this turn, if &lt;cardName&gt; is dealt damage by your opponent's Summons or abilities,
     * the damage becomes 0 instead."
     */
    static final Pattern STANDALONE_NULLIFY_ABILITY_DAMAGE = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+(?<card>.+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+your\\s+opponent's\\s+Summons?\\s+or\\s+abilities,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
    );

    /**
     * Returns {@code true} if the effect grants the source card itself immunity to ability/summon
     * damage for the turn ("if [cardName] is dealt damage by Summons or abilities, it becomes 0").
     * These are reactive defensive abilities: the CPU should use them in response to opponent
     * actions, not proactively during its own main phase.
     */
    public static boolean isReactiveDamageShield(String effectText, CardData source) {
        if (source == null || effectText == null) return false;
        Matcher m = STANDALONE_NULLIFY_ABILITY_DAMAGE.matcher(effectText);
        return m.find() && m.group("card").trim().equalsIgnoreCase(source.name());
    }

    /**
     * "During this turn, the next damage dealt to [name] becomes 0 instead."
     * "The next damage dealt to Card Name [name] becomes 0 this turn."
     */
    static final Pattern STANDALONE_SHIELD_NEXT_DMG_ZERO_NAMED = Pattern.compile(
        "(?i)(?:During\\s+this\\s+turn,\\s+)?the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?!(?:it|him|them)\\b)(?:Card\\s+Name\\s+)?(?<name>[A-Za-z][^.]+?)\\s+becomes\\s+0\\s+(?:instead|this\\s+turn)[.!]?"
    );

    /** "During this turn, the next damage dealt to [name] is reduced by N instead." — named card, not pronoun. */
    static final Pattern STANDALONE_SHIELD_NEXT_DMG_REDUCTION_NAMED = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+dealt\\s+to\\s+(?!(?:it|them)\\b)(?<name>[A-Za-z][^.]+?)\\s+is\\s+reduced\\s+by\\s+(?<reduction>\\d+)\\s+instead[.!]?"
    );

    /** "The damage dealt to Forwards opponent controls cannot be reduced this turn." */
    static final Pattern STANDALONE_DISABLE_OPPONENT_DMG_REDUCTION = Pattern.compile(
        "(?i)The\\s+damage\\s+dealt\\s+to\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls\\s+cannot\\s+be\\s+reduced\\s+this\\s+turn\\.?"
    );

    /** "This damage cannot be reduced." — modifier on a preceding damage sentence. */
    static final Pattern CANNOT_BE_REDUCED_PATTERN = Pattern.compile(
        "(?i)This\\s+damage\\s+cannot\\s+be\\s+reduced[.!]?"
    );

    /**
     * Matches "Activate &lt;cardName&gt;[.]" as a standalone named-card activate effect.
     * Also handles "Activate Card Name X [and Card Name Y] [you control]" for
     * multi-target Card Name notation.
     * Excludes the pronoun forms ("Activate it/them") and the mass form ("Activate all …"),
     * which are handled separately.
     */
    static final Pattern ACTIVATE_NAMED_CARD = Pattern.compile(
        "(?i)Activate\\s+(?!(?:it|them|all)\\b)(?<card>[A-Za-z][^.]+?)\\.?\\s*$"
    );

    /** Matches "[name] can attack once more this turn." */
    private static final Pattern ATTACK_ONCE_MORE = Pattern.compile(
        "(?i)^(?<name>[A-Za-z][^.]+?)\\s+can\\s+attack\\s+once\\s+more\\s+this\\s+turn[.!]?"
    );

    /** Matches "During this turn, your opponent may only declare attack once." */
    private static final Pattern OPPONENT_ATTACK_ONCE_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+your\\s+opponent\\s+may\\s+only\\s+declare\\s+attack\\s+once\\.?"
    );

    static final Pattern OPPONENT_CANNOT_SEARCH_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+your\\s+opponent\\s+cannot\\s+search\\.?"
    );

    /** Returns {@code true} if the effect is "During this turn, your opponent cannot search." */
    public static boolean isOpponentCannotSearchAbility(String effectText) {
        return effectText != null && OPPONENT_CANNOT_SEARCH_THIS_TURN.matcher(effectText).find();
    }

    /** Splits "and Card Name" within an activate target list. */
    static final Pattern ACTIVATE_AND_CARD_NAME_SPLIT = Pattern.compile(
        "(?i)\\s+and\\s+Card\\s+Name\\s+"
    );

    /** Matches "Remove &lt;cardName&gt; from [the] Battle." — Escape-type ability effect. */
    private static final Pattern REMOVE_FROM_BATTLE = Pattern.compile(
        "(?i)Remove\\s+(?<card>.+?)\\s+from\\s+(?:the\\s+)?Battle\\.?\\s*$"
    );

    /**
     * Matches "Your opponent discards N card(s) [from his/her/their hand]".
     * <ul>
     *   <li>Group 1 — number of cards to discard</li>
     * </ul>
     */
    /**
     * Matches "name 1 card type. Then, your opponent discard 1 card.
     * If the discarded card is the named card type, you draw 1 card."
     */
    static final Pattern NAME_CARD_TYPE_OPP_DISCARD_DRAW_IF_MATCH = Pattern.compile(
        "(?i)name\\s+1\\s+card\\s+type[.!]?\\s+Then,?\\s+your\\s+opponent\\s+discards?\\s+1\\s+card[.!]?\\s+" +
        "If\\s+the\\s+discarded\\s+card\\s+is\\s+the\\s+named\\s+card\\s+type,\\s+you\\s+draw\\s+1\\s+card[.!]?"
    );

    static final Pattern OPPONENT_DISCARD = Pattern.compile(
        "(?i)Your\\s+opponent\\s+discards?\\s+(\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );

    /** Matches "Each player discards N card(s) [from his/her/their hand]". Group {@code count} = N. */
    static final Pattern EACH_PLAYER_DISCARD = Pattern.compile(
        "(?i)each\\s+player\\s+discards?\\s+(?<count>\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );

    /** Matches "Each player draws N card(s)." Group {@code count} = N. */
    static final Pattern EACH_PLAYER_DRAW = Pattern.compile(
        "(?i)each\\s+player\\s+draws?\\s+(?<count>\\d+)\\s+cards?[.!]?"
    );

    /**
     * Matches "Each player selects N [card|Forward|Backup|Monster|Character](s) from their Break
     * Zone and adds it/them to their hand." — Cu Chaspel 18-021R (any card), Serafie 1-109R
     * (Forwards only).
     * <ul>
     *   <li>Group {@code count} — N</li>
     *   <li>Group {@code type}  — the card-type filter; "card" means no restriction</li>
     * </ul>
     */
    static final Pattern EACH_PLAYER_SALVAGE_FROM_BREAK_ZONE = Pattern.compile(
        "(?i)each\\s+player\\s+selects?\\s+(?<count>\\d+)\\s+" +
        "(?<type>card|Forward|Backup|Monster|Character)s?\\s+from\\s+" +
        "(?:their|his/her|his|her)\\s+Break\\s+Zone\\s+and\\s+adds?\\s+(?:it|them)\\s+to\\s+" +
        "(?:their|his/her|his|her)\\s+hand[.!]?"
    );

    /**
     * Matches "select N [Forward|Backup|Monster|Character] in/from your Break Zone and add it to your hand."
     * Group {@code count} = N; {@code type} = card type word.
     */
    static final Pattern SELECT_CHARACTER_FROM_BZ_TO_HAND = Pattern.compile(
        "(?i)^select\\s+(?<count>\\d+)\\s+(?<type>Forward|Backup|Monster|Character)s?" +
        "\\s+(?:in|from)\\s+your\\s+Break\\s+Zone\\s+and\\s+add\\s+it\\s+to\\s+your\\s+hand[.!]?$"
    );

    /** Ceodore: "Choose 1 Card with Warp in your Break Zone. Add it to your hand." */
    static final Pattern CHOOSE_WARP_CARD_FROM_BZ_TO_HAND = Pattern.compile(
        "(?i)^choose\\s+1\\s+Card\\s+with\\s+Warp\\s+(?:in|from)\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "Add\\s+it\\s+to\\s+your\\s+hand[.!]?$"
    );

    /**
     * Matches "Each player who doesn't control N or more Forwards discards M card(s) [from their hand]."
     * Groups: {@code min} — forward threshold; {@code count} — cards to discard.
     */
    static final Pattern EACH_PLAYER_WHO_DOESNT_CONTROL_FORWARDS_DISCARD = Pattern.compile(
        "(?i)each\\s+player\\s+who\\s+doesn't\\s+control\\s+(?<min>\\d+)\\s+or\\s+more\\s+Forwards?" +
        "\\s+discards?\\s+(?<count>\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );

    /**
     * Matches the compound form "Each player discards N cards. If you control [Card Name (X)] /
     * Card Name X, your opponent discards M more cards [from his/her/their hand]".
     * Groups: {@code count}, {@code bracketname} or {@code plainname}, {@code extra}.
     */
    static final Pattern EACH_PLAYER_DISCARD_WITH_CONDITIONAL = Pattern.compile(
        "(?i)each\\s+player\\s+discards?\\s+(?<count>\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?\\s+" +
        "if\\s+you\\s+control\\s+" +
        "(?:\\[Card\\s+Name\\s+\\((?<bracketname>[^)]+)\\)\\]|Card\\s+Name\\s+(?<plainname>\\S+))" +
        ",\\s+your\\s+opponent\\s+discards?\\s+(?<extra>\\d+)\\s+more\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );

    /**
     * Matches "Each player selects 1 Forward they control. Deal them N damage."
     * Group {@code amount} — damage dealt to each selected Forward.
     */
    static final Pattern EACH_PLAYER_SELECT_FORWARD_DAMAGE = Pattern.compile(
        "(?i)each\\s+player\\s+selects?\\s+1\\s+Forward\\s+they\\s+control[.!]?\\s+" +
        "Deal\\s+them\\s+(?<amount>\\d+)\\s+damage[.!]?"
    );

    /**
     * Matches "Both players select 1 Forward they control and put it into the Break Zone."
     * Used for Famfrit-style EX Burst effects where each side simultaneously sends one Forward to the Break Zone.
     */
    static final Pattern BOTH_PLAYERS_SELECT_FORWARD_TO_BREAK_ZONE = Pattern.compile(
        "(?i)(?:Both|Each)\\s+players?\\s+selects?\\s+1\\s+Forward\\s+they\\s+control" +
        "\\s+and\\s+puts?\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /** Matches "select 1 [Forward|Backup|Monster|Character] you control. Put it into the Break Zone." */
    /**
     * Matches "select 1 [type] of cost N or less other than [name] you control. Put it into the Break Zone."
     * Groups: {@code type}, {@code costval}, {@code excludename}.
     */
    static final Pattern SELECT_1_CHAR_COST_LE_EXCL_TO_BZ = Pattern.compile(
        "(?i)^[Ss]elect\\s+1\\s+(?<type>Forward|Backup|Monster|Character)\\s+of\\s+cost\\s+(?<costval>\\d+)\\s+or\\s+less\\s+" +
        "other\\s+than\\s+(?<excludename>.+?)\\s+you\\s+control[.!]?\\s+Put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );

    static final Pattern SELECT_1_CHARACTER_YOU_CONTROL_TO_BZ = Pattern.compile(
        "(?i)^[Ss]elect\\s+1\\s+(?<type>Forward|Backup|Monster|Character)\\s+you\\s+control[.!]?\\s+Put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?$"
    );

    /**
     * Matches "Each player selects up to N Forwards or Monsters he/she/they controls/control
     * (select as many as possible). Put them into the Break Zone."
     * Groups: {@code count} — max per player; {@code targets} — card type(s).
     */
    static final Pattern EACH_PLAYER_SELECT_UP_TO_N_TO_BREAK_ZONE = Pattern.compile(
        "(?i)Each\\s+player\\s+selects?\\s+up\\s+to\\s+(?<count>\\d+)\\s+" +
        "(?<targets>Forwards?(?:\\s+(?:and/or|or)\\s+(?:Monsters?|Backups?))?|Monsters?|Characters?)\\s+" +
        "(?:he/she|they)\\s+controls?\\s*" +
        "(?:\\(select\\s+as\\s+many\\s+as\\s+possible\\)[.!]?\\s*)?" +
        "Put\\s+them\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /**
     * Matches "Each player reveals the top card of his/her deck. Each player who revealed a
     * [type] may play it onto the field." Group {@code type} = card type condition.
     */
    static final Pattern EACH_PLAYER_REVEAL_CHARACTER_MAY_PLAY = Pattern.compile(
        "(?i)^\\s*Each\\s+player\\s+reveals?\\s+the\\s+top\\s+card\\s+of\\s+" +
        "(?:his/her|his|her|their)\\s+deck[.!]?\\s+" +
        "Each\\s+player\\s+who\\s+revealed\\s+(?:a\\s+)?(?<type>Forward|Backup|Character|Monster)\\s+" +
        "may\\s+play\\s+it\\s+onto\\s+the\\s+field[.!]?\\s*$"
    );

    /**
     * Matches "each player may search for N Forward(s) of power X or more and add it/them to his/her hand."
     * Groups: {@code count}, {@code power}.
     */
    static final Pattern EACH_PLAYER_MAY_SEARCH_FORWARD_MIN_POWER = Pattern.compile(
        "(?i)^\\s*each\\s+player\\s+may\\s+search\\s+for\\s+(?<count>\\d+)\\s+Forwards?\\s+" +
        "of\\s+power\\s+(?<power>\\d+)\\s+or\\s+more\\s+and\\s+add\\s+it(?:/them|s)?\\s+to\\s+" +
        "(?:his/her|his|her|their)\\s+hand[.!]?\\s*$"
    );

    /** Matches "Discard your hand. Then, draw N card(s)." Group 1 = draw count. */
    static final Pattern DISCARD_HAND_THEN_DRAW = Pattern.compile(
        "(?i)Discard\\s+your\\s+hand[.,]?\\s+[Tt]hen[,]?\\s+draw\\s+(\\d+)\\s+cards?[.!]?\\s*$"
    );

    /** Matches "Discard your hand." as a standalone effect. */
    static final Pattern DISCARD_HAND = Pattern.compile(
        "(?i)Discard\\s+your\\s+hand[.!]?\\s*$"
    );

    /**
     * Matches "discard 1 &lt;Type&gt;." — player discards one card of the named type from hand.
     * Used as the primary clause in "discard 1 X. When you do so, Y." sequences.
     * The "you may" qualifier is stripped by the AutoAbility parser before this is reached.
     */
    static final Pattern DISCARD_TYPE = Pattern.compile(
        "(?i)discard\\s+1\\s+(?<type>Summon|Forward|Backup|Monster|Character)[.!]?"
    );

    /** Matches "Discard 1 Job [X] from your hand[.]" — player discards a card with the named job. */
    static final Pattern DISCARD_JOB_FROM_HAND = Pattern.compile(
        "(?i)^discard\\s+1\\s+Job\\s+(?<job>.+?)\\s+from\\s+your\\s+hand[.!]?$"
    );

    /** Matches "You may discard 1 &lt;element&gt; card" — player may optionally discard a card matching the element. */
    static final Pattern DISCARD_ELEMENT_FROM_HAND = Pattern.compile(
        "(?i)^(?:you\\s+may\\s+)?discard\\s+1\\s+(?<element>Multi-Element|Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+card(?:\\s+from\\s+your\\s+hand)?[.!]?$"
    );

    /** Matches "Your opponent randomly discards N card(s) [from his/her/their hand]". Group 1 = count. */
    static final Pattern OPPONENT_RANDOM_DISCARD = Pattern.compile(
        "(?i)Your\\s+opponent\\s+randomly\\s+discards?\\s+(\\d+)\\s+cards?" +
        "(?:\\s+from\\s+(?:his/her|his|her|their)\\s+hand)?[.!]?"
    );

    /**
     * Matches "Your opponent draws N card(s), then randomly discards M card(s)".
     * Group 1 = draw count, Group 2 = discard count.
     */
    static final Pattern OPPONENT_DRAW_THEN_RANDOM_DISCARD = Pattern.compile(
        "(?i)Your\\s+opponent\\s+draws?\\s+(\\d+)\\s+cards?[,.]?\\s+then\\s+randomly\\s+discards?\\s+(\\d+)\\s+cards?[.!]?"
    );

    /** Matches "Your opponent draws N card(s)." — simple opponent draw with no followup. */
    static final Pattern OPPONENT_DRAW = Pattern.compile(
        "(?i)Your\\s+opponent\\s+draws?\\s+(\\d+)\\s+cards?[.!]?$"
    );

    /**
     * Matches "Your opponent selects N [condition] [element] [type] [of cost C or less/more]
     * they control [sep] followup".
     * <ul>
     *   <li>Group {@code count}     — number of cards the opponent must select</li>
     *   <li>Group {@code condition} — optional state filter</li>
     *   <li>Group {@code element}   — optional element filter</li>
     *   <li>Group {@code targets}   — card type(s)</li>
     *   <li>Group {@code cost}      — optional cost threshold</li>
     *   <li>Group {@code costcmp}   — {@code less} or {@code more}; both are inclusive of {@code cost}</li>
     *   <li>Group {@code followup}  — action applied to the selected card(s)</li>
     * </ul>
     */
    static final Pattern OPPONENT_SELECTS_PATTERN = Pattern.compile(
        "(?i)^Your\\s+opponent\\s+selects?\\s+(?<count>\\d+)\\s+" +
        "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<targets>(?:Forwards?|Backups?|Characters?|Monsters?)(?:\\s+(?:and/or|or|and)\\s+(?:Forwards?|Backups?|Characters?|Monsters?))?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+(?<costcmp>less|more))?" +
        "\\s+(?:they|he/she|he|she)\\s+controls?" +
        "(?:[.]\\s*|\\s+and\\s+)" +
        "(?<followup>.+)",
        Pattern.DOTALL
    );

    /**
     * Matches both variants of the "opponent puts attacking Forward to Break Zone" effect:
     * <ul>
     *   <li>"Opponent puts 1 attacking Forward into the Break Zone."</li>
     *   <li>"Your opponent puts 1 attacking Forward he/she controls into the Break Zone."</li>
     * </ul>
     * The second variant is the precise reprint; both resolve identically — the opponent
     * chooses one of their own matching Forwards and sends it to the Break Zone.
     */
    static final Pattern OPPONENT_PUTS_FORWARD_TO_BREAK_ZONE_PATTERN = Pattern.compile(
        "(?i)(?:Your\\s+)?[Oo]pponent\\s+puts?\\s+(?<count>\\d+)\\s+" +
        "(?:(?<condition>dull|damaged|attacking|blocking|active)\\s+)?" +
        "(?<targets>Forwards?|Characters?)" +
        "(?:\\s+(?:he|she|they)(?:\\s*/\\s*(?:he|she|they))?\\s+controls?)?" +
        "\\s+into\\s+the\\s+Break\\s+Zone[.]?"
    );

    /**
     * Matches the compound EX Burst effect:
     * "Choose up to 1 Forward from your Break Zone of cost equal to or less than the damage you
     *  have been dealt. Return it to your hand. Your opponent selects 1 Forward of cost equal to
     *  or less than the damage you have been dealt and puts it into the Break Zone."
     */
    static final Pattern BZ_FWD_TO_HAND_OPP_FWD_TO_BZ_BY_DAMAGE = Pattern.compile(
        "(?i)Choose\\s+up\\s+to\\s+1\\s+Forward\\s+from\\s+your\\s+Break\\s+Zone\\s+of\\s+cost\\s+" +
        "equal\\s+to\\s+or\\s+less\\s+than\\s+the\\s+damage\\s+you\\s+have\\s+been\\s+dealt\\.\\s*" +
        "Return\\s+it\\s+to\\s+your\\s+hand\\.\\s*" +
        "Your\\s+opponent\\s+selects?\\s+1\\s+Forward\\s+of\\s+cost\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+" +
        "the\\s+damage\\s+you\\s+have\\s+been\\s+dealt\\s+and\\s+puts?\\s+it\\s+into\\s+the\\s+Break\\s+Zone\\.?"
    );

    /**
     * Matches "Your opponent puts the top N card(s) of his/her/their deck into the Break Zone
     * [. Draw M card(s)]".
     * <ul>
     *   <li>Group {@code count} — number of cards to mill; absent means 1 ("the top card")</li>
     *   <li>Group {@code draw}  — optional number of cards to draw afterward</li>
     * </ul>
     */
    private static final Pattern OPPONENT_MILL_PATTERN = Pattern.compile(
        "(?i)Your\\s+opponent\\s+puts?\\s+" +
        "(?:the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+of" +
        "|(?<count2>\\d+)\\s+cards?\\s+from\\s+the\\s+top\\s+of)\\s+" +
        "(?:his/her|his|her|their)\\s+deck\\s+into\\s+the\\s+Break\\s+Zone" +
        "(?:[.!]?\\s*(?:You\\s+)?[Dd]raw\\s+(?<draw>\\d+)\\s+cards?[.!]?)?"
    );

    static final Pattern DIVIDE_DAMAGE_PATTERN = Pattern.compile(
            "(?i)Divide\\s+(?<amount>\\d+)\\s+damage\\b(?:.*?\\b(?<mode>equally)\\b)?"
    );

    /**
     * Matches the condition clause of "If &lt;cond&gt;, divide M damage among them [as you like|
     * equally] instead." — captures just {@code cond}; the alt amount is re-extracted separately
     * via {@link #DIVIDE_DAMAGE_PATTERN} against the same substring.
     */
    static final Pattern DIVIDE_DAMAGE_INSTEAD_COND = Pattern.compile(
            "(?i)^If\\s+(?<cond>.+?),\\s*(?=[Dd]ivide\\s+\\d+\\s+damage)"
    );

    /**
     * Matches "Divide N damage equally among all the Forwards/Backups/Characters [you control|
     * opponent controls][ (round up to the nearest 1000)]." — a blanket, no-choice variant of
     * the "Choose ... Divide N damage" pattern (e.g. Strago's "Grand Delta").
     * Groups: {@code amount}, {@code type}, {@code control}.
     */
    static final Pattern DIVIDE_DAMAGE_EQUALLY_AMONG_ALL = Pattern.compile(
            "(?i)^Divide\\s+(?<amount>\\d+)\\s+damage\\s+equally\\s+among\\s+all\\s+(?:the\\s+)?" +
            "(?<type>Forwards?|Backups?|Characters?)" +
            "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls|you\\s+control))?" +
            "(?:\\s*\\([^)]*\\))?[.!]?\\s*$"
    );

    /**
     * Matches "Your opponent puts the top N cards of his/her deck into the Break Zone.
     * If both [all] cards are of the same Element, draw M card(s)."
     * Groups: {@code count}, {@code draw}.
     */
    static final Pattern OPPONENT_MILL_IF_SAME_ELEMENT_DRAW = Pattern.compile(
        "(?i)Your\\s+opponent\\s+puts?\\s+" +
        "(?:the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of|(?<count2>\\d+)\\s+cards?\\s+from\\s+the\\s+top\\s+of)\\s+" +
        "(?:his/her|his|her|their)\\s+deck\\s+into\\s+the\\s+Break\\s+Zone[.!]?\\s+" +
        "If\\s+(?:both|all)\\s+(?:the\\s+)?cards?\\s+are\\s+of\\s+the\\s+same\\s+Element,?\\s+" +
        "draw\\s+(?<draw>\\d+)\\s+cards?[.!]?"
    );

    private static final Pattern SELF_MILL_PATTERN = Pattern.compile(
        "(?i)Put\\s+the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+" +
        "of\\s+your\\s+deck\\s+into\\s+the\\s+Break\\s+Zone"
    );

    /**
     * Matches "Play 1 [elements] [filter] [type] of cost … from your hand onto the field [dull]".
     * <ul>
     *   <li>{@code preelems}   — element(s) appearing BEFORE the job/name filter (e.g. "Ice" in "Play 1 Ice Forward")</li>
     *   <li>Filter alternatives (all optional): {@code f1}/{@code f2} bracket filters,
     *       {@code cardname} written card name, {@code category}, {@code jobnm}</li>
     *   <li>{@code targets}    — card type (optional when {@code cardname} is set)</li>
     *   <li>Cost alternatives (all optional):
     *       {@code dynfilter} — "equal to or less than the number of X you control";
     *       {@code cost}/{@code costalt} — numeric cost with optional "less", "more", or a second value</li>
     *   <li>{@code excludename} — card name to exclude ("other than Card Name X")</li>
     *   <li>{@code dull}      — present when the card enters the field dulled</li>
     * </ul>
     */

    /**
     * "Cast 1 Summon [of cost N or less] from your hand without paying [its|the] cost[.
     * Then, return that Summon to your hand after use instead of putting it in the Break Zone.]"
     * Groups: {@code cost} — numeric cost cap or "X"; {@code returnToHand} — present for the
     * "return to hand after use" variant.
     */
    static final Pattern CAST_SUMMON_FROM_HAND_FREE = Pattern.compile(
        "(?i)Cast\\s+1\\s+Summon" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+|X)\\s+or\\s+less)?" +
        "(?:\\s+other\\s+than\\s+(?<excludeelems>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*))?" +
        "\\s+from\\s+your\\s+hand\\s+without\\s+paying\\s+(?:its|the)\\s+cost[.!]?" +
        "(?<returnToHand>\\s*Then,?\\s+return\\s+that\\s+Summon\\s+to\\s+your\\s+hand\\s+after\\s+use" +
        "\\s+instead\\s+of\\s+putting\\s+it\\s+in\\s+the\\s+Break\\s+Zone[.!]?)?"
    );

    /**
     * "Randomly reveal 1 card from your hand. If it is a Summon, you may cast it without paying the cost."
     */
    static final Pattern RANDOM_REVEAL_HAND_CAST_IF_SUMMON_FREE = Pattern.compile(
        "(?i)Randomly\\s+reveal\\s+1\\s+card\\s+from\\s+your\\s+hand[.!]?\\s+" +
        "If\\s+it\\s+is\\s+a\\s+Summon,?\\s+you\\s+may\\s+cast\\s+it\\s+without\\s+paying\\s+(?:its|the)\\s+cost[.!]?"
    );

    /**
     * "Cast a Summon from your hand. The cost required to cast it is reduced by N (it cannot become 0)."
     * Group {@code amount} — the reduction amount.
     */
    static final Pattern CAST_SUMMON_FROM_HAND_DISCOUNTED = Pattern.compile(
        "(?i)Cast\\s+a\\s+Summon\\s+from\\s+your\\s+hand[.!]?\\s+" +
        "The\\s+cost\\s+required\\s+to\\s+cast\\s+it\\s+is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?:\\s*\\(it\\s+cannot\\s+become\\s+0\\))?[.!]?"
    );

    /**
     * "Search for 1 [Element] Summon [of cost N or less] and cast it without paying [its|the] cost.
     * If you do not cast it, put the Summon into the Break Zone."
     * Groups: {@code element} — element name; {@code cost} — optional numeric cost cap.
     */
    static final Pattern SEARCH_AND_CAST_SUMMON_FREE_PATTERN = Pattern.compile(
        "(?i)search\\s+for\\s+1\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Summon" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+less)?" +
        "\\s+and\\s+cast\\s+it\\s+without\\s+paying\\s+(?:its|the)\\s+cost[.!]?" +
        "(?:\\s+If\\s+you\\s+do\\s+not\\s+cast\\s+it,\\s+put\\s+the\\s+Summon\\s+into\\s+the\\s+Break\\s+Zone[.!]?)?"
    );

    static final Pattern PLAY_FROM_HAND_PATTERN = Pattern.compile(
        "(?i)Play\\s+1\\s+" +
        // Element(s) before any filter (e.g. "Ice" in "Play 1 Ice Forward")
        "(?:(?<preelems>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+)?" +
        "(?:" +
            // Bracket filter(s): [Job (x)] and/or [Card Name (x)]
            "(?<f1>\\[(?:Job|Card\\s+Name)\\s+\\([^)]+\\)\\])" +
            "(?:\\s+or\\s+(?<f2>\\[(?:Job|Card\\s+Name)\\s+\\([^)]+\\)\\]))?" +
            "\\s+" +
        "|" +
            // Written card name — stops at cost or "from your"
            "Card\\s+Name\\s+(?<cardname>.+?)\\s+(?=of\\s+cost|from\\s+your|[.!])" +
        "|" +
            // Category filter: lookahead keeps the type in the targets group
            "Category\\s+(?<category>.+?)\\s+(?=Forwards?|Backups?|Monsters?|Characters?)" +
        "|" +
            // Written job OR card name: "Job X or Card Name Y" (no explicit type required)
            "Job\\s+(?<jobnmor>.+?)\\s+or\\s+Card\\s+Name\\s+(?<cnameor>\\S+(?:\\s+\\([^)]+\\))?)" +
        "|" +
            // Written job: lookahead keeps the type in the targets group
            "Job\\s+(?<jobnm>.+?)\\s+(?=Forwards?|Backups?|Monsters?|Characters?)" +
        "|" +
            // Written job with no explicit type (e.g. "Job Archfiend from your hand") — any character type
            "Job\\s+(?<jobnmonly>.+?)\\s+(?=of\\s+cost|from\\s+your|other\\s+than)" +
        ")?" +
        // Type is optional when a card-name filter is present
        "(?<targets>Forwards?|Backups?|Monsters?|Characters?(?:\\s+Cards?)?)?" +
        "\\s*" +
        // Element exclusion: "of any Element except Ice [and Water] [and ]"
        "(?:of\\s+any\\s+Element\\s+except\\s+(?<excludeelem>" +
            "(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+and\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+(?:and\\s+)?)?" +
        "(?:" +
            // Dynamic cost: "of cost equal to or less than the number of X you control"
            "of\\s+cost\\s+equal\\s+to\\s+or\\s+less\\s+than\\s+the\\s+number\\s+of\\s+" +
            "(?<dynfilter>.+?)\\s+you\\s+control" +
        "|" +
            // Standard / two-value: "of cost N [or less|more|M]"
            "of\\s+cost\\s+(?<cost>\\d+|X)(?:\\s+or\\s+(?<costalt>less|more|\\d+))?" +
        ")?" +
        "\\s*" +
        // Exclusion
        "(?:other\\s+than\\s+Card\\s+Name\\s+(?<excludename>\\S+(?:\\s+\\([^)]+\\))?)\\s+)?" +
        "(?:with\\s+(?<trait>Warp)\\s+)?" +
        "from\\s+your\\s+hand\\s+onto\\s+(?:the\\s+)?field" +
        // Dull modifier
        "(?:\\s+(?<dull>dull))?" +
        "[.!]?"
    );

    /** Matches "play any number of [Job X] [type] from your hand onto [the] field". */
    static final Pattern PLAY_ANY_NUMBER_FROM_HAND_PATTERN = Pattern.compile(
        "(?i)(?:Then,?\\s+)?(?:you\\s+may\\s+)?[Pp]lay\\s+any\\s+number\\s+of\\s+" +
        "(?:Job\\s+(?<jobnm>.+?)\\s+)?" +
        "(?<targets>Forwards?|Backups?|Monsters?|Characters?)?" +
        "\\s*from\\s+your\\s+hand\\s+onto\\s+(?:the\\s+)?field[.!]?"
    );

    /**
     * Matches "Search for [up to] 1 [elements] [filter] [elements] [type] [other than Card Name X] [of cost N [or less|more]] and [destination]".
     * <ul>
     *   <li>Group {@code bracketname} — {@code [Card Name (name)]} bracket notation (older cards)</li>
     *   <li>Group {@code bracketjob}  — {@code [Job (name)]} bracket notation</li>
     *   <li>Group {@code cardname}    — written card name without brackets, e.g. {@code "Cait Sith"}</li>
     *   <li>Group {@code category}   — category substring, e.g. {@code "XV"}</li>
     *   <li>Group {@code jobnmor}    — job part of {@code "Job X or Card Name Y"} (OR logic with {@code cnameor})</li>
     *   <li>Group {@code cnameor}    — card name part of {@code "Job X or Card Name Y"}</li>
     *   <li>Group {@code jobnm}      — written job name without brackets, e.g. {@code "King"}</li>
     *   <li>Group {@code preelems}   — element(s) appearing BEFORE the job/name filter, e.g. {@code "Fire"} in {@code "Search for 1 Fire Job Knight"}</li>
     *   <li>Group {@code elements}   — element(s) appearing AFTER the job/name filter; {@code preelems} takes priority when both could apply</li>
     *   <li>Group {@code targets}    — card type word; absent or {@code "card"} means any type</li>
     *   <li>Group {@code withwarp}   — present for {@code "card with Warp"}; restricts results to cards with the Warp trait</li>
     *   <li>Group {@code excludename}— card name to exclude, from {@code "other than Card Name X"}</li>
     *   <li>Group {@code cost}       — optional cost number</li>
     *   <li>Group {@code costcmp}    — optional {@code "less"} or {@code "more"}</li>
     *   <li>Group {@code destination}— full destination phrase</li>
     * </ul>
     */
    /**
     * Matches "Search for up to 1 Job [job] and up to 1 [Type] that don't share Elements, and add them to your hand."
     * Used by cards like Rydia that fetch one card from each of two overlapping pools with an element-disjointness constraint.
     */
    static final Pattern DUAL_SEARCH_JOB_AND_TYPE_DONT_SHARE_ELEMENTS = Pattern.compile(
        "(?i)search\\s+for\\s+up\\s+to\\s+1\\s+Job\\s+(?<job>.+?)(?=\\s+and\\s+up\\s+to\\b)" +
        "\\s+and\\s+up\\s+to\\s+1\\s+(?<type>Summon|Forward|Backup|Monster|Character)" +
        "\\s+that\\s+don.t\\s+share\\s+[Ee]lements,?\\s+and\\s+add\\s+them\\s+to\\s+your\\s+hand[.!]?"
    );

    /**
     * Matches "Search for 2 [Element] Characters, 2 Category [X] Characters, or 1 of each,
     * each with a different cost, and add them to your hand."
     * Groups: {@code element}, {@code category}.
     */
    static final Pattern SEARCH_ELEMENT_OR_CATEGORY_CHARS_DIFF_COST = Pattern.compile(
        "(?i)Search\\s+for\\s+2\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Characters?,\\s+" +
        "2\\s+Category\\s+(?<category>\\S+)\\s+Characters?,\\s+or\\s+1\\s+of\\s+each,\\s+" +
        "each\\s+with\\s+a\\s+different\\s+cost,?\\s+and\\s+add\\s+them\\s+to\\s+your\\s+hand[.!]?"
    );

    /**
     * Matches "Search for N [Element] Summons each with a different cost and add them to your hand."
     * Groups: {@code count}, {@code element}.
     */
    static final Pattern SEARCH_N_ELEM_SUMMONS_DIFF_COST = Pattern.compile(
        "(?i)Search\\s+for\\s+(?<count>\\d+)\\s+" +
        "(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Summons?" +
        "\\s+each\\s+with\\s+a\\s+different\\s+cost\\s+and\\s+add\\s+them\\s+to\\s+your\\s+hand[.!]?"
    );

    static final Pattern SEARCH_DECK_PATTERN = Pattern.compile(
        "(?i)Search\\s+for\\s+(?:up\\s+to\\s+)?(?<count>\\d+)\\s+" +
        // Element(s) that precede the job/name filter (e.g. "Fire Job Knight")
        "(?:(?<preelems>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+)?" +
        "(?:" +
            // Bracket card name: [Card Name (name)]
            "(?<bracketname>\\[Card\\s+Name\\s+\\([^)]+\\)\\])\\s+" +
        "|" +
            // Bracket job: [Job (name)]
            "(?<bracketjob>\\[Job\\s+\\([^)]+\\)\\])\\s+" +
        "|" +
            // "Card Name X [Type] or Job Y" — OR logic; must come before plain Card Name alternative
            "Card\\s+Name\\s+(?<cnamejobnmor>.+?)" +
            "(?:\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card))?" +
            "\\s+(?:and/)?or\\s+Job\\s+(?<jobnmcnameor>.+?)" +
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+other\\b|\\s+and\\b)\\s*" +
        "|" +
            // "Card Name A[, Card Name B][, or Card Name C]" — several names, OR'd together. Must
            // precede the single-name alternative, whose lazy group would otherwise run to the
            // trailing "and" and take the whole list as one (unmatchable) name.
            "Card\\s+Name\\s+(?<cardnames>.+?(?:\\s*,\\s*|\\s+(?:and/)?or\\s+)Card\\s+Name\\s+.+?)" +
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+other\\b|\\s+and\\b)" +
            "\\s+" +
        "|" +
            // Written card name without brackets — ends at type word, "of cost", or "and"
            "Card\\s+Name\\s+(?<cardname>.+?)" +
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+other\\b|\\s+and\\b)" +
            "\\s+" +
        "|" +
            // Category filter — lookahead keeps the type word in the targets group
            "Category\\s+(?<category>.+?)\\s+" +
            "(?=Forwards?|Backups?|Monsters?|Summons?|Characters?|card\\b)" +
        "|" +
            // "Job X [Type] or Card Name Y" — OR logic; must come before plain Job alternative
            "Job\\s+(?<jobnmor>.+?)" +
            "(?:\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card))?" +
            "\\s+(?:and/)?or\\s+Card\\s+Name\\s+(?<cnameor>.+?)" +
            "(?=\\s+of\\s+cost|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b|\\s+other\\b|\\s+and\\b)\\s*" +
        "|" +
            // Written job — lookahead keeps element, type word, "of cost", "other than", Category, or "and" ahead
            "Job\\s+(?<jobnm>.+?)(?=\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\b" +
            "|\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?|card)\\b" +
            "|\\s+of\\s+cost\\b|\\s+other\\b|\\s+Category\\b|\\s+and\\b)\\s*" +
        ")?" +
        // Optional Category filter following a Job filter (e.g. "Job Standard Unit Category FFCC")
        "(?:Category\\s+(?<catafterjob>\\S+)\\s+)?" +
        "(?:(?<elements>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*)\\s+)?" +
        "(?<targets>(?:Forwards?|Backups?|Monsters?|Summons?|Characters?)(?:\\s+or\\s+(?:Forwards?|Backups?|Monsters?|Summons?|Characters?))*|cards?)?\\s*" +
        "(?<withwarp>with\\s+Warp)?\\s*" +
        "(?:\\s+other\\s+than\\s+a(?:n)?\\s+(?<excludetype>Forward|Backup|Monster|Summon|Character))?\\s*" +
        "(?:\\s+other\\s+than\\s+Card\\s+Name\\s+(?<excludename>.+?)(?=\\s+of\\s+cost|\\s+and\\b))?" +
        "(?:of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more|\\d+))?\\s*)?" +
        "(?:\\s+other\\s+than\\s+(?<excludeelem>(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+or\\s+(?:Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark))*))?\\s*" +
        "and\\s+" +
        "(?<destination>" +
            "add\\s+it\\s+to\\s+your\\s+hand" +
            "|add\\s+them\\s+to\\s+your\\s+hand" +
            "|play\\s+it\\s+onto\\s+(?:the\\s+)?field(?:\\s+dull)?" +
            "|play\\s+them\\s+onto\\s+(?:the\\s+)?field(?:\\s+dull)?" +
            "|put\\s+it\\s+on\\s+top\\s+of\\s+(?:your|its\\s+owner's)\\s+deck" +
            "|put\\s+it\\s+under\\s+the\\s+top\\s+card\\s+of\\s+(?:your|its\\s+owner's)\\s+deck" +
            "|put\\s+it\\s+into\\s+(?:the\\s+)?Break\\s+Zone" +
            "|put\\s+them\\s+into\\s+(?:the\\s+)?Break\\s+Zone" +
        ")" +
        "[.!]?"
    );

    /** Matches "Your opponent shows/reveals his/her/their hand". */
    static final Pattern OPPONENT_REVEAL_HAND_PATTERN = Pattern.compile(
        "(?i)Your\\s+opponent\\s+(?:shows?|reveals?)\\s+(?:his/her|his|her|their)\\s+hand[.!]?"
    );

    /**
     * Matches "Choose 1 Forward. Reveal the top card of your deck. If the revealed card's
     * CP cost is an even number, [eveneffect]. Add the revealed card to your hand.
     * If the revealed card's CP cost is an odd number, [oddeffect]. Add the revealed card to your hand."
     */
    static final Pattern CHOOSE_FWD_REVEAL_COST_PARITY_PATTERN = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward[.!]?\\s+" +
        "Reveal\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "If\\s+the\\s+revealed\\s+card's\\s+CP\\s+cost\\s+is\\s+an?\\s+even\\s+number,\\s+" +
        "(?<eveneffect>.+?)[.!]?\\s+Add\\s+the\\s+revealed\\s+card\\s+to\\s+your\\s+hand[.!]?\\s+" +
        "If\\s+the\\s+revealed\\s+card's\\s+CP\\s+cost\\s+is\\s+an?\\s+odd\\s+number,\\s+" +
        "(?<oddeffect>.+?)[.!]?\\s+Add\\s+the\\s+revealed\\s+card\\s+to\\s+your\\s+hand[.!]?$",
        Pattern.DOTALL
    );

    /**
     * Anchored prefix that confirms the effect text is a deck-reveal ability.
     * Group {@code who} captures the deck owner phrase so callers can tell
     * whether it is the ability user's own deck or the opponent's.
     * The clauses themselves are iterated with {@link #REVEAL_CLAUSE_PATTERN}.
     */
    static final Pattern REVEAL_TOP_DECK_HEADER = Pattern.compile(
        "(?i)^\\s*Reveal\\s+the\\s+top\\s+card\\s+of\\s+" +
        "(?<who>opponent's|your)\\s+deck[.!]?"
    );

    /**
     * Iteratively matches each "If it is/has [cond], [action]" clause within a
     * reveal-top-deck effect text.
     * <ul>
     *   <li>Group {@code cond}   — full condition text (passed to {@link #parseRevealCondition})</li>
     *   <li>Group {@code action} — full action text (card-op or standalone effect)</li>
     * </ul>
     * The lookahead stops each {@code action} capture before the next clause or end of text.
     */
    static final Pattern REVEAL_CLAUSE_PATTERN = Pattern.compile(
        "If\\s+it\\s+(?:is|has)\\s+(?<cond>[^,]+?)\\s*,\\s*(?<action>.+?)" +
        "(?=[.!]?\\s+If\\s+it\\s+(?:is|has)\\b|[.!]?\\s*$)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    /**
     * Matches "Put it into the Break Zone" — a forced send that bypasses
     * "cannot be broken" protections, unlike {@code FOLLOWUP_BREAK}.
     */
    static final Pattern FOLLOWUP_PUT_TO_BREAK_ZONE = Pattern.compile(
        "(?i)Put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /**
     * Matches "&lt;subject&gt; gains [+N power] [, traits] until end of turn" where the subject
     * may be a card name (checked against the source at runtime) rather than "it"/"they".
     * <ul>
     *   <li>Group {@code selfsubject} — the word(s) before "gains"</li>
     *   <li>Group {@code selfamount}  — optional numeric power amount</li>
     *   <li>Group {@code selftraits}  — optional traits string</li>
     * </ul>
     */
    static final Pattern SELF_POWER_BOOST = Pattern.compile(
        "(?i)(?<selfsubject>.+?)\\s+gains?\\s+" +
        "(?:\\+(?<selfamount>\\d+)\\s+[Pp]ower)?" +
        "(?<selftraits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "if [CardName] has received N damage or more, draw M card(s)." —
     * the inner effect extracted from "At the end of each player's turn, …".
     * Groups: {@code cardname}, {@code damage}, {@code draw}.
     */
    static final Pattern IF_SELF_FWD_RECEIVED_DAMAGE_DRAW = Pattern.compile(
        "(?i)^if\\s+(?<cardname>.+?)\\s+has\\s+received\\s+(?<damage>\\d+)\\s+damage\\s+or\\s+more,\\s+" +
        "draw\\s+(?<draw>\\d+)\\s+cards?[.!]?\\s*$"
    );

    /**
     * Matches "if you have N or more cards in your hand, [subject] gains [+P power] [traits]
     * until end of turn[. If you have M or more cards, [subject] also gains +Q power until end of turn]."
     * Groups: {@code min1}, {@code subject}, {@code amount1}, {@code traits1}, {@code min2}, {@code amount2}.
     */
    static final Pattern IF_HAND_SIZE_SELF_BOOST = Pattern.compile(
        "(?i)if\\s+you\\s+have\\s+(?<min1>\\d+)\\s+or\\s+more\\s+cards?\\s+in\\s+your\\s+hand,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+" +
        "(?:\\+(?<amount1>\\d+)\\s+[Pp]ower)?" +
        "(?<traits1>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?" +
        "(?:\\s+If\\s+you\\s+have\\s+(?<min2>\\d+)\\s+or\\s+more\\s+cards?\\s+in\\s+your\\s+hand,\\s+" +
        ".+?also\\s+gains?\\s+\\+(?<amount2>\\d+)\\s+[Pp]ower\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?)?"
    );

    /**
     * Matches "CardName gains +N power for each 《C》 you have until end of turn."
     * Groups: {@code subject}, {@code amount}.
     */
    static final Pattern SELF_POWER_BOOST_FOR_EACH_CRYSTAL = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+" +
        "for\\s+each\\s+《C》\\s+you\\s+have" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "[subject] gains +N power until the end of the turn and activate [activateName]."
     * Groups: {@code subject}, {@code amount}, {@code activateName}.
     */
    static final Pattern SELF_POWER_BOOST_AND_ACTIVATE = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s+and\\s+activate\\s+" +
        "(?<activateName>.+?)[.!]?\\s*$"
    );

    /**
     * Matches "[CardName]'s power becomes the same as that Forward's power until the end of the turn."
     * Used as a secondary effect after choosing and removing a Forward from the Break Zone.
     * Group {@code name} — the card whose power is set (should match the source card).
     */
    static final Pattern SOURCE_POWER_BECOMES_SAME_AS_REMOVED_FORWARD = Pattern.compile(
        "(?i)(?<name>.+?)'s\\s+power\\s+becomes\\s+the\\s+same\\s+as\\s+that\\s+Forward's\\s+power" +
        "\\s+until\\s+the\\s+end\\s+of\\s+(?:the\\s+)?turn[.!]?\\s*$"
    );

    /**
     * Matches "[CardName]'s power becomes the same as your opponent's weakest Forward until the
     * end of the turn." Group {@code name} — the card whose power is set (should match the source card).
     */
    static final Pattern SOURCE_POWER_BECOMES_OPPONENT_WEAKEST_FORWARD = Pattern.compile(
        "(?i)(?<name>.+?)'s\\s+power\\s+becomes\\s+the\\s+same\\s+as\\s+your\\s+opponent's\\s+weakest\\s+Forward" +
        "\\s+until\\s+the\\s+end\\s+of\\s+(?:the\\s+)?turn[.!]?\\s*$"
    );

    /**
     * Matches "During this turn, if [CardName] deals damage to a Forward, double the damage instead."
     * Groups: {@code subject} — the card name.
     */
    static final Pattern DOUBLE_OUTGOING_DAMAGE_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+(?<subject>.+?)\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward," +
        "\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );

    /**
     * Matches "During this turn, if a Forward opponent controls is dealt damage, double the damage instead."
     */
    static final Pattern DOUBLE_OPPONENT_INCOMING_DAMAGE_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+a\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls\\s+" +
        "is\\s+dealt\\s+damage,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );

    /**
     * Matches "If a Forward receives damage this turn, the damage increases by N instead."
     */
    static final Pattern ALL_FORWARD_INCOMING_DMG_INCREASE_THIS_TURN = Pattern.compile(
        "(?i)If\\s+a\\s+Forward\\s+receives\\s+damage\\s+this\\s+turn,\\s+the\\s+damage\\s+increases?\\s+by\\s+(?<amount>\\d+)(?:\\s+instead)?[.!]?"
    );

    /**
     * Matches "If [subject] deals damage to a Forward this turn, double the damage instead."
     * (Ninja-style variant — "this turn" appears at the end rather than "During this turn" at the start.)
     */
    static final Pattern DOUBLE_OUTGOING_DAMAGE_THIS_TURN_ALT = Pattern.compile(
        "(?i)If\\s+(?<subject>.+?)\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward\\s+this\\s+turn,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );

    /**
     * Matches "Choose 1 Forward opponent controls with a cost inferior or equal to the number of
     * [Element] [Backups/Forwards] you control. [followup]"
     * Groups: {@code element} — element name; {@code cardtype} — "Backups" or "Forwards";
     *         {@code followup} — effect sentence(s) to apply to the chosen targets.
     */
    static final Pattern CHOOSE_OPP_FWD_DYN_COST_BREAK = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls\\s+with\\s+a\\s+cost\\s+" +
        "(?:inferior\\s+or\\s+equal\\s+to|equal\\s+or\\s+inferior\\s+to|equal\\s+to\\s+or\\s+(?:less\\s+than|inferior))\\s+" +
        "the\\s+number\\s+of\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+" +
        "(?<cardtype>Backups?|Forwards?)\\s+you\\s+control[.,]?\\s+(?<followup>.+)"
    );

    /**
     * Matches "Choose 1 Forward [control?] with a power inferior to [CardName]'s [power]. [followup]"
     * Groups: {@code control} — optional "opponent controls" / "you control";
     *         {@code sourcename} — name of the card whose power sets the ceiling;
     *         {@code followup} — effect sentence(s) to apply to the chosen targets.
     */
    static final Pattern CHOOSE_FWD_POWER_INFERIOR_TO_SOURCE = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+" +
        "(?:(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control)\\s+)?" +
        "with\\s+a\\s+power\\s+inferior\\s+to\\s+(?<sourcename>.+?)'s(?:\\s+power)?[.,]?\\s+(?<followup>.+)"
    );

    /**
     * Matches "Dull all [the] Forwards with a power [equal or inferior / inferior or equal /
     * equal to or less than] to [CardName]'s [your] opponent controls."
     * Groups: {@code sourcename} — name of the card whose power is the ceiling.
     */
    static final Pattern DULL_ALL_OPP_FWDS_POWER_LE_SOURCE = Pattern.compile(
        "(?i)Dull\\s+all\\s+(?:the\\s+)?Forwards?\\s+with\\s+a\\s+power\\s+" +
        "(?:equal\\s+or\\s+inferior\\s+to|inferior\\s+or\\s+equal\\s+to|equal\\s+to\\s+or\\s+less\\s+than)\\s+" +
        "(?<sourcename>.+?)'s\\s+(?:(?:your\\s+)?opponent\\s+controls?)[.!]?"
    );

    /**
     * Matches "Choose 1 Forward in your Break Zone with a cost inferior to that of the removed
     * Forward. Play it onto the field." — the follow-up half of a Hojo-style remove-then-play chain.
     */
    static final Pattern CHOOSE_FWD_BZ_COST_INFERIOR_TO_REMOVED_PLAY = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+in\\s+your\\s+Break\\s+Zone\\s+with\\s+a\\s+cost\\s+" +
        "inferior\\s+to\\s+that\\s+of\\s+the\\s+removed\\s+Forward[.,]?\\s+" +
        "Play\\s+it\\s+onto\\s+(?:the\\s+)?field[.!]?"
    );

    /**
     * Matches "Choose 1 Forward. During this turn, if it is dealt damage, double the damage instead."
     */
    static final Pattern CHOOSE_FORWARD_DOUBLE_INCOMING_THIS_TURN = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward[.,]?\\s+During\\s+this\\s+turn,\\s+if\\s+it\\s+is\\s+dealt\\s+damage,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );

    /**
     * Matches "Choose 1 [Job X] Forward. During this turn, the next damage it deals to a Forward
     * becomes double the damage instead. [You can only use this ability once per turn.]"
     * <ul>
     *   <li>Group {@code job} — optional job filter (e.g. {@code "Headhunter"})</li>
     * </ul>
     */
    static final Pattern CHOOSE_FORWARD_DOUBLE_NEXT_OUTGOING = Pattern.compile(
        "(?i)Choose\\s+1\\s+(?:Job\\s+(?<job>.+?)\\s+)?Forward[.,]?\\s+" +
        "During\\s+this\\s+turn,\\s+the\\s+next\\s+damage\\s+it\\s+deals\\s+to\\s+a\\s+Forward\\s+" +
        "becomes\\s+double\\s+the\\s+damage\\s+instead[.!]?" +
        "(?:\\s+You\\s+can\\s+only\\s+use\\s+this\\s+ability\\s+once\\s+per\\s+turn\\.?)?"
    );

    /**
     * Matches "During this turn, if your ability deals damage to a Forward, double the damage instead."
     */
    static final Pattern DOUBLE_PLAYER_ABILITY_OUTGOING_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+if\\s+your\\s+ability\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
    );

    /**
     * Matches "&lt;subject&gt; gains +N power [and traits]." with no duration clause — a permanent
     * passive field-ability self-boost (e.g. "Gilgamesh gains +1000 power.",
     * "Cid Raines gains +1000 power and First Strike.").
     * <ul>
     *   <li>Group {@code subject} — card name before "gains"</li>
     *   <li>Group {@code amount}  — numeric power amount</li>
     *   <li>Group {@code traits}  — optional traits string (e.g. "and First Strike")</li>
     * </ul>
     */
    static final Pattern FIELD_SELF_POWER_BOOST = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower" +
        "(?<traits>(?:\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "[.!]?\\s*$"
    );

    /**
     * Matches "it/they gains/gain +N power [, Haste[, First Strike[, and Brave]]] until end of turn".
     * <ul>
     *   <li>Group 1 — numeric power amount</li>
     *   <li>Group 2 — optional traits string, e.g. {@code ", Haste, and First Strike"}</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_POWER_BOOST = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+\\+(\\d+)\\s+[Pp]ower" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:(?:the|your)\\s+)?turn"
    );

    /**
     * Matches "Until the end of the turn, it/they gains/gain +N power [and traits]".
     * <ul>
     *   <li>Group 1 — numeric power amount</li>
     *   <li>Group 2 — optional traits string</li>
     * </ul>
     */
    /**
     * Matches either word order of the "gains +N power for each [Element] [Type] you control" followup:
     * <ul>
     *   <li>"Until end of turn, it gains +N power for each [Element] Type you control."</li>
     *   <li>"It gains +N power for each [Element] Type you control until end of turn."</li>
     * </ul>
     * Groups: 1 = per-unit amount, {@code element} = optional element, {@code chartype} = card type.
     */
    static final Pattern FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH = Pattern.compile(
        "(?i)(?:" +
            "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
            "(?:it|they)\\s+gains?\\s+\\+(\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?<chartype>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
        "|" +
            "(?:it|they)\\s+gains?\\s+\\+(\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<element2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?<chartype2>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
            "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn" +
        ")[.!]?"
    );

    /**
     * Matches "Until the end of the turn, it gains +N power for each [Name] Counter placed on [card]."
     * Groups: {@code perunit} = per-counter power boost; {@code counterName} = counter type name.
     * Uses {@code xValue} captured before any BZ-cost payment cleared the counters.
     * Must be checked before {@link #FOLLOWUP_POWER_BOOST_UNTIL}, which would match only the +N.
     */
    static final Pattern FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_COUNTER = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+\\+(?<perunit>\\d+)\\s+[Pp]ower\\s+" +
        "for\\s+each\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+.+?[.!]?$",
        Pattern.DOTALL
    );

    /**
     * Matches "Place N [Name] Counter(s) on it[/them]."
     * Groups: {@code count} — number of counters; {@code name} — counter type name.
     */
    private static final Pattern FOLLOWUP_PLACE_COUNTER_ON_IT = Pattern.compile(
        "(?i)Place\\s+(?<count>\\d+)\\s+(?<name>.+?)\\s+Counters?\\s+on\\s+(?:it|them)[.!]?"
    );

    /**
     * Matches "Select 1 Counter placed on it, and remove the selected Counter."
     * The counter type is chosen by the player at resolution time (dialog if multiple types).
     */
    private static final Pattern FOLLOWUP_REMOVE_ONE_COUNTER = Pattern.compile(
        "(?i)Select\\s+1\\s+Counter\\s+placed\\s+on\\s+(?:it|them)[,.]?\\s+" +
        "and\\s+remove\\s+the\\s+selected\\s+Counter[.!]?"
    );

    /**
     * Matches "Deal it N damage for each [Name] Counter(s) placed on [card]."
     * Groups: {@code perunit} = damage per counter; {@code counterName} = counter type name.
     * Uses {@code xValue} captured before any BZ-cost payment cleared the counters.
     * Must be checked before {@link #FOLLOWUP_DAMAGE_FOR_EACH}, which would match only the flat N damage.
     */
    static final Pattern FOLLOWUP_DAMAGE_FOR_EACH_COUNTER = Pattern.compile(
        "(?i)Deal\\s+it\\s+(?<perunit>\\d+)\\s+damage\\s+" +
        "for\\s+each\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+.+?[.!]?$",
        Pattern.DOTALL
    );

    /**
     * Matches "it gains +N power for each [Job (name)] / Job name [Type] you control until end of turn"
     * in both word orders (until-prefix or until-suffix).
     * Groups: {@code amount}/{@code amount2} = per-unit amount; {@code jobb}/{@code jobb2} = bracket job name;
     * {@code jobw}/{@code jobw2} = written job name; {@code jobt}/{@code jobt2} = optional type qualifier.
     * Must be checked before {@link #FOLLOWUP_POWER_BOOST_UNTIL}, which would match the +N and drop the rest.
     */
    static final Pattern FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_JOB = Pattern.compile(
        "(?i)(?:" +
            "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
            "(?:it|they)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:\\[Job\\s+\\((?<jobb>[^)]+)\\)\\]|Job\\s+(?<jobw>.+?)(?:\\s+(?<jobt>Forwards?|Backups?|Monsters?))?)" +
            "\\s+you\\s+control" +
        "|" +
            "(?:it|they)\\s+gains?\\s+\\+(?<amount2>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:\\[Job\\s+\\((?<jobb2>[^)]+)\\)\\]|Job\\s+(?<jobw2>.+?)(?:\\s+(?<jobt2>Forwards?|Backups?|Monsters?))?)" +
            "\\s+you\\s+control" +
            "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn" +
        ")[.!]?"
    );

    /**
     * Matches "Until the end of the turn, it gains +N power for each point of damage you have received."
     * Group {@code perunit} = per-damage power amount.
     * Must be checked before {@link #FOLLOWUP_POWER_BOOST_UNTIL}, which would match the +N and drop the rest.
     */
    static final Pattern FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_SELF_DMG = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+\\+(?<perunit>\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+point\\s+of\\s+damage\\s+you\\s+have\\s+received[.!]?"
    );

    static final Pattern FOLLOWUP_POWER_BOOST_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:(?:the|your)\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+\\+(\\d+)\\s+[Pp]ower" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)"
    );

    /**
     * "It/they gains [TraitA] or [TraitB] until [the] end of [the] turn." — player picks one trait.
     * Groups {@code t1} and {@code t2} are the two trait names.  Must be checked before
     * {@link #FOLLOWUP_KEYWORD_GRANT} since the latter doesn't handle the "or" separator.
     */
    static final Pattern FOLLOWUP_KEYWORD_GRANT_CHOICE = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+" +
        "(?<t1>Haste|First\\s+Strike|Brave)\\s+or\\s+(?<t2>Haste|First\\s+Strike|Brave)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn"
    );

    /**
     * Matches "it/they gains Haste/First Strike/Brave [and …] until end of turn" with no power amount.
     * <ul>
     *   <li>Group 1 — traits string, e.g. {@code "Haste"} or {@code "Haste and First Strike"}</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_KEYWORD_GRANT = Pattern.compile(
        "(?i)(?:it|they)\\s+gains?\\s+" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))+)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn"
    );

    /**
     * Alternate word order: "Until the end of the turn, it/they gains Haste/First Strike/Brave [and …]"
     * with no power amount (EOT prefix, keywords only).
     * <ul>
     *   <li>Group 1 — traits string, e.g. {@code "Haste and First Strike"}</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_KEYWORD_GRANT_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+gains?\\s+" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))+)" +
        "[.!]?"
    );

    /**
     * Matches standalone "Until the end of the turn, &lt;subject&gt; gains +N power [and traits]".
     * Used when the subject is a specific card name rather than "it"/"they".
     * <ul>
     *   <li>Group {@code subject} — card name or pronoun before "gains"</li>
     *   <li>Group {@code amount}  — numeric power amount</li>
     *   <li>Group {@code traits}  — optional traits string</li>
     * </ul>
     */
    /**
     * Matches "Until [the] end of [the] turn, &lt;subject&gt; gains +N power and
     * '<em>When &lt;subject&gt; attacks, &lt;attackEffect&gt;</em>'."
     * Used by action abilities that temporarily grant a power boost AND an attack auto-ability
     * (e.g. Black Mage's 《C》 ability).
     * <ul>
     *   <li>Group {@code subject}      — card name, must match {@code source.name()}</li>
     *   <li>Group {@code amount}       — power boost value</li>
     *   <li>Group {@code attackEffect} — the effect text that fires when the card attacks</li>
     * </ul>
     */
    static final Pattern STANDALONE_POWER_BOOST_AND_ATTACK_TRIGGER = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+and\\s+" +
        "\"When\\s+[^\"]+?\\s+attacks?\\s*,\\s+(?<attackEffect>[^\"]+?)\"\\s*[.!]?\\s*$",
        Pattern.DOTALL
    );

    /**
     * Matches "Until the end of the turn, [Name] gains +N power and [Name]/it cannot be
     * chosen by your opponent's Summons/abilities." (Quina) — a self-buff granting a power
     * boost AND opponent-targeting protection simultaneously.
     * <ul>
     *   <li>Group {@code subject}  — card name before "gains"; must match {@code source.name()}</li>
     *   <li>Group {@code amount}   — power boost value</li>
     *   <li>Group {@code subject2} — card name (or "it") before "cannot be chosen"</li>
     *   <li>Group {@code scope}    — "Summons", "abilities", or "Summons or abilities"</li>
     * </ul>
     */
    static final Pattern STANDALONE_POWER_BOOST_AND_CANNOT_BE_CHOSEN = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+\\+(?<amount>\\d+)\\s+[Pp]ower\\s+and\\s+" +
        "(?<subject2>.+?)\\s+cannot\\s+be\\s+chosen\\s+by\\s+your\\s+opponent's\\s+" +
        "(?<scope>Summons?(?:\\s+or\\s+abilities)?|abilities)\\s*\\.?"
    );

    /**
     * Matches "Until the end of the turn, [name] gains [traits] and '[name] cannot be blocked.'"
     * Used when a self-buff grants keyword traits AND unblockable status simultaneously.
     * Groups: {@code subject} — card name; {@code traits} — keyword list.
     */
    static final Pattern STANDALONE_GAINS_TRAITS_AND_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+" +
        "(?<traits>(?:Haste|First\\s+Strike|Brave)(?:\\s+and\\s+(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+and\\s+\".+?\\s+cannot\\s+be\\s+blocked\\.?\"[.!]?"
    );

    /**
     * Matches "[name] gains [traits] and '[name] cannot be blocked.' until the end of the turn."
     * — trailing-order sibling of {@link #STANDALONE_GAINS_TRAITS_AND_CANNOT_BE_BLOCKED} (e.g.
     * Queen's Speedrush: {@code Queen gains Haste and "Queen cannot be blocked" until the end of
     * the turn.}). Groups: {@code subject} — card name; {@code traits} — keyword list.
     */
    static final Pattern STANDALONE_GAINS_TRAITS_AND_CANNOT_BE_BLOCKED_TRAILING = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+" +
        "(?<traits>(?:Haste|First\\s+Strike|Brave)(?:\\s+and\\s+(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+and\\s+\".+?\\s+cannot\\s+be\\s+blocked\\.?\"" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /** Matches "Choose 1 card removed from the game. Remove 1 Warp Counter from it." */
    static final Pattern CHOOSE_WARP_CARD_REMOVE_COUNTER = Pattern.compile(
        "(?i)^Choose\\s+1\\s+card\\s+removed\\s+from\\s+the\\s+game\\.\\s*" +
        "Remove\\s+1\\s+Warp\\s+Counter\\s+from\\s+it[.!]?"
    );

    /**
     * Matches "Choose 1 card removed from the game with a Warp Counter on it. You may remove 1
     * Warp Counter from it." (Vayne) — the optional-removal variant: choosing the target is
     * mandatory, but the removal itself is a "you may" decision.
     */
    static final Pattern CHOOSE_WARP_CARD_MAY_REMOVE_COUNTER = Pattern.compile(
        "(?i)^Choose\\s+1\\s+card\\s+removed\\s+from\\s+the\\s+game\\s+with\\s+a\\s+Warp\\s+Counter\\s+on\\s+it\\.\\s*" +
        "You\\s+may\\s+remove\\s+1\\s+Warp\\s+Counter\\s+from\\s+it[.!]?"
    );

    /** Matches "[Name] gains '[Name] cannot be blocked.' until the end of the turn." */
    static final Pattern STANDALONE_GAINS_CANNOT_BE_BLOCKED = Pattern.compile(
        "(?i)(?<subject>.+?)\\s+gains?\\s+\".+?\\s+cannot\\s+be\\s+blocked\\.?\"" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    static final Pattern STANDALONE_POWER_BOOST_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+gains?\\s+" +
        "(?:\\+(?<amount>\\d+)\\s+[Pp]ower)?" +
        "(?<traits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "[.\\s]*$"
    );

    /**
     * Matches "Until the end of the turn, &lt;subject&gt;['s power becomes N | gains traits and
     * &lt;subject&gt;'s power becomes N]" — e.g. Bartz 7-059L's Dual-Wield:
     * "Until the end of the turn, Bartz gains First Strike and Bartz's power becomes 10000."
     *
     * <p>Unlike the "its power becomes N" wording handled by {@link #FOLLOWUP_POWER_BECOMES},
     * this form replaces the card's <em>base</em> power, so boosts and reductions from other
     * effects still apply on top of the new value.
     * <ul>
     *   <li>Group {@code subject} — the card named before "gains" (absent when there is no trait clause)</li>
     *   <li>Group {@code traits}  — the granted keywords (absent when there is no trait clause)</li>
     *   <li>Group {@code powersubject} — the card whose power is set</li>
     *   <li>Group {@code power}   — the new base power</li>
     * </ul>
     */
    static final Pattern STANDALONE_SELF_BASE_POWER_BECOMES_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:(?<subject>.+?)\\s+gains?\\s+" +
        "(?<traits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))+)\\s+and\\s+)?" +
        "(?<powersubject>.+?)'s\\s+power\\s+becomes\\s+(?<power>\\d+)[.!]?\\s*$"
    );

    /**
     * Matches "Double the power of &lt;subject&gt; until [the] end of [the] turn".
     * <ul>
     *   <li>Group {@code subject} — card name before "until"</li>
     * </ul>
     */
    static final Pattern STANDALONE_DOUBLE_POWER_UNTIL = Pattern.compile(
        "(?i)Double\\s+the\\s+power\\s+of\\s+(?<subject>.+?)\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "Until the end of the turn, &lt;subject&gt; doubles its power [and gains traits]".
     * <ul>
     *   <li>Group {@code subject} — card name before "doubles"</li>
     *   <li>Group {@code traits}  — optional trailing text (e.g. "and gains First Strike and Brave")</li>
     * </ul>
     */
    static final Pattern STANDALONE_DOUBLES_ITS_POWER_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+doubles?\\s+its\\s+power(?<traits>[^.!]*)"
    );

    /**
     * Matches "At the beginning of your next turn's Main Phase 1 and until the end of the same
     * turn, &lt;subject&gt;'s power will double."
     * <ul>
     *   <li>Group {@code subject} — card name before "'s power will double"</li>
     * </ul>
     */
    static final Pattern STANDALONE_DOUBLE_POWER_MAIN_PHASE_NEXT_TURN = Pattern.compile(
        "(?i)At\\s+the\\s+beginning\\s+of\\s+your\\s+next\\s+turn's\\s+Main\\s+Phase\\s+1" +
        "\\s+and\\s+until\\s+the\\s+end\\s+of\\s+the\\s+same\\s+turn\\s*,\\s+" +
        "(?<subject>.+?)'s\\s+power\\s+will\\s+double[.!]?"
    );

    /**
     * Matches "it/they loses/lose [N power] [, traits] until end of turn".
     * Both power and traits are optional, but at least one must be present in practice.
     * <ul>
     *   <li>Group 1 — optional numeric power amount (absent = traits-only)</li>
     *   <li>Group 2 — optional traits string</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_POWER_REDUCE = Pattern.compile(
        "(?i)(?:it|they)\\s+loses?\\s+" +
        "(?:(\\d+)\\s+[Pp]ower)?" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn"
    );

    /** Matches "Its/Their power becomes N until the end of the turn." — group 1 is the target power. */
    static final Pattern FOLLOWUP_POWER_BECOMES = Pattern.compile(
        "(?i)(?:its?|their)\\s+power\\s+becomes?\\s+(\\d+)\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "Until the end of the turn, it/they loses/lose [N power] [and traits]".
     * <ul>
     *   <li>Group 1 — optional numeric power amount</li>
     *   <li>Group 2 — optional traits string</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_POWER_REDUCE_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+loses?\\s+" +
        "(?:(\\d+)\\s+[Pp]ower)?" +
        "((?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)"
    );

    /** Matches "Until [of] the end of [the] turn, it/they loses N power for each card in your hand." */
    static final Pattern FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH_HAND = Pattern.compile(
        "(?i)Until\\s+(?:of\\s+)?(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?:it|they)\\s+loses?\\s+(\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+card\\s+in\\s+your\\s+hand[.!]?"
    );

    /**
     * Matches either word order of the "loses N power for each [Element] [Type] you control" followup
     * (the reduce counterpart of {@link #FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH}):
     * <ul>
     *   <li>"Until end of turn, it loses N power for each [Element] Type you control."</li>
     *   <li>"It loses N power for each [Element] Type you control until end of turn."</li>
     * </ul>
     * Groups: 1 = per-unit amount (until-prefix order), 4 = per-unit amount (suffix order);
     * {@code element}/{@code chartype} (until-prefix) or {@code element2}/{@code chartype2} (suffix).
     */
    static final Pattern FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH = Pattern.compile(
        "(?i)(?:" +
            "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
            "(?:it|they)\\s+loses?\\s+(\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?<chartype>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
        "|" +
            "(?:it|they)\\s+loses?\\s+(\\d+)\\s+[Pp]ower\\s+for\\s+each\\s+" +
            "(?:(?<element2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
            "(?<chartype2>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
            "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn" +
        ")[.!]?"
    );

    /** Matches "it/they loses N power" with no timing qualifier — implied EOT in former/latter context. */
    private static final Pattern FOLLOWUP_POWER_REDUCE_BARE = Pattern.compile(
        "(?i)(?:it|they)\\s+loses?\\s+(\\d+)\\s+[Pp]ower[.!]?"
    );

    /**
     * Matches standalone "Until the end of the turn, &lt;subject&gt; loses [N power] [and traits]".
     * <ul>
     *   <li>Group {@code subject} — card name or pronoun before "loses"</li>
     *   <li>Group {@code amount}  — optional numeric power amount</li>
     *   <li>Group {@code traits}  — optional traits string</li>
     * </ul>
     */
    static final Pattern STANDALONE_POWER_REDUCE_UNTIL = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn\\s*,\\s+" +
        "(?<subject>.+?)\\s+loses?\\s+" +
        "(?:(?<amount>\\d+)\\s+[Pp]ower)?" +
        "(?<traits>(?:\\s*,?\\s*(?:and\\s+)?(?:Haste|First\\s+Strike|Brave))*)" +
        "[.\\s]*$"
    );

    /**
     * Matches mass-effect actions on all field cards of a given type:
     * "[action] all [the] [element] [targets] [of cost X [or less|more]] [other than cost Y] [control]"
     * <ul>
     *   <li>Group {@code action}      — "Break", "dull", "freeze", "dull and freeze", or "Activate"</li>
     *   <li>Group {@code element}     — optional element name</li>
     *   <li>Group {@code targets}     — "Forwards", "Backups", "Forwards and Monsters", or "Characters"</li>
     *   <li>Group {@code cost}        — optional CP cost value (inclusive filter)</li>
     *   <li>Group {@code costcmp}     — optional comparison: "less" or "more"</li>
     *   <li>Group {@code excludecost} — optional exact cost to exclude, from "other than cost N"</li>
     *   <li>Group {@code control}     — optional: "opponent controls" or "you control"</li>
     * </ul>
     */
    static final Pattern ALL_FIELD_EFFECT_PATTERN = Pattern.compile(
        "(?i)(?<action>Break|Activate|dull\\s+and\\s+freeze|dull|freeze)\\s+" +
        "all\\s+(?:the\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?:Job\\s+(?<job>.+?)(?=\\s+(?:Forwards?|Backups?|Characters?|you\\b|opponent\\b)|\\s*[.!]?$))?" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)?" +
        "(?:\\s+with\\s+(?<trait>(?:Haste|First\\s+Strike|Brave)(?:\\s*(?:,\\s*(?:or\\s+)?|\\s+or\\s+)(?:Haste|First\\s+Strike|Brave))*))?" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+cost\\s+(?<excludecost>\\d+))?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "[.!]?"
    );

    /**
     * Matches "All [the] [element] Forwards/Backups/Characters [of cost N [or less|more]]
     * [you control | opponent controls] gain +N power until [the] end of [the] turn."
     * <ul>
     *   <li>Group {@code element}  — optional element name</li>
     *   <li>Group {@code targets}  — "Forwards", "Forwards and Monsters", etc.</li>
     *   <li>Group {@code cost}     — optional CP cost value</li>
     *   <li>Group {@code costcmp}  — optional comparison: "less" or "more"</li>
     *   <li>Group {@code control}  — optional: "opponent controls" or "you control"</li>
     *   <li>Group {@code amount}   — power amount to add</li>
     * </ul>
     */
    static final Pattern ALL_FIELD_POWER_BOOST_PATTERN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+(?<excludename>.+?))?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches the party-attack followup that boosts every Forward in the party that just formed
     * and attacked, in either of the two printed phrasings:
     * <ul>
     *   <li>"all Forwards in that party gain/lose +N power until [the] end of [the] turn."
     *       (Gippal +5000, Celestia / Chocobo 9-050C +1000)</li>
     *   <li>"[Self] and all the Forwards forming a party with it gain/lose +N power until [the]
     *       end of [the] turn." (Chocobo 1-075C / 4-062C +3000, Chocobo 1-076C +2000)</li>
     * </ul>
     * The two name the same set — the card forming the party is itself a member of it — so both
     * resolve through {@link GameContext#applyCurrentPartyForwardsPowerBoost} against the
     * recorded attacking party.  The subject of the second form is left unanchored rather than
     * matched against the card's name, so reprints and aliases are not excluded by a name that
     * no longer matches; the trigger has already established whose party attacked.
     * Groups: {@code verb}, {@code amount}.
     */
    static final Pattern PARTY_FORWARDS_POWER_BOOST_PATTERN = Pattern.compile(
        "(?i)(?:all\\s+Forwards?\\s+in\\s+that\\s+party" +
        "|[A-Za-z][^.,]*?\\s+and\\s+all\\s+the\\s+Forwards?\\s+forming\\s+a\\s+party\\s+with\\s+it)\\s+" +
        "(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All [the] Forwards of the same Element as [Card Name] X you control
     * gain +N power until [the] end of [the] turn."
     * Groups: {@code name}, {@code control}, {@code verb}, {@code amount}.
     */
    static final Pattern ALL_FORWARDS_SAME_ELEMENT_AS_NAMED_POWER_BOOST = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+of\\s+the\\s+same\\s+Element\\s+as\\s+" +
        "(?:Card\\s+Name\\s+)?(?<name>[A-Za-z][A-Za-z0-9\\s''\\-]*?)\\s+" +
        "(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control)\\s+" +
        "(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All Job X and Card Name Y [you control | opponent controls]
     * gain +N power until [the] end of [the] turn."
     * Groups: {@code job}, {@code cardname}, {@code control}, {@code verb}, {@code amount}.
     */
    static final Pattern ALL_FIELD_JOB_CARDNAME_POWER_BOOST_PATTERN = Pattern.compile(
        "(?i)All\\s+Job\\s+(?<job>[\\w][\\w\\s]*?)\\s+and\\s+Card\\s+Name\\s+(?<cardname>[\\w][\\w\\s]*?)\\s+" +
        "(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control)\\s+" +
        "(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "[The] Card Name X [Forward] and Card Name Y [Forward] [you control | opponent controls]
     * gain +N power until [the] end of [the] turn."
     * Groups: {@code name1}, {@code name2}, {@code control}, {@code verb}, {@code amount}.
     */
    static final Pattern TWO_CARD_NAMES_POWER_BOOST_PATTERN = Pattern.compile(
        "(?i)(?:The\\s+)?Card\\s+Name\\s+(?<name1>[\\w][\\w\\s''\\-]*?)" +
        "(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?" +
        "\\s+and\\s+Card\\s+Name\\s+(?<name2>[\\w][\\w\\s''\\-]*?)" +
        "(?:\\s+(?:Forwards?|Backups?|Monsters?|Characters?))?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All [the] Job X Forwards/Backups/Characters [you control | opponent controls]
     * gain +N power until [the] end of [the] turn."
     * Groups: {@code job}, {@code targets}, {@code control}, {@code verb}, {@code amount}.
     */
    static final Pattern ALL_FIELD_JOB_POWER_BOOST_PATTERN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Job\\s+(?<job>[A-Za-z][A-Za-z\\s''\\-]*?)\\s+" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All [the] Job X [targets] [you control | opponent controls]
     * gain Keyword[, ...] until end of turn."
     * Groups: {@code job}, {@code targets} (optional), {@code control}, {@code keywords}.
     */
    static final Pattern ALL_FIELD_JOB_KEYWORD_GRANT_PATTERN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Job\\s+(?<job>[A-Za-z][A-Za-z\\s''\\-]*?)" +
        "(?:\\s+(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?))?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+gains?\\s+(?<keywords>(?:(?:Haste|First\\s+Strike|Brave)(?:\\s*[,]?\\s*(?:and\\s+)?)?)+)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "All [the] [element] [Category X] [targets] [of cost N [or less|more]]
     * [you control | opponent controls] gain Keyword[, Keyword2, ...] until end of turn."
     * Groups: {@code element}, {@code category}, {@code targets}, {@code cost}, {@code costcmp},
     * {@code control}, {@code keywords}.
     */
    static final Pattern ALL_FIELD_KEYWORD_GRANT_PATTERN = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+gains?\\s+(?<keywords>(?:(?:Haste|First\\s+Strike|Brave)(?:\\s*[,]?\\s*(?:and\\s+)?)?)+)" +
        "\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?"
    );

    /**
     * Matches "Until end of turn, all [the] [element] [Category X] [targets] [you control]
     * gain/lose +N power [and Keywords]."
     * Groups: {@code element}, {@code category}, {@code targets}, {@code cost}, {@code costcmp},
     * {@code control}, {@code verb}, {@code amount}, {@code keywords}.
     */
    static final Pattern UNTIL_EOT_ALL_FIELD_POWER_BOOST_PATTERN = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "all\\s+(?:the\\s+)?" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<targets>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+(?<control>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+(?<verb>gains?|loses?)\\s+\\+?(?<amount>\\d+)\\s+[Pp]ower" +
        "(?:\\s+and\\s+(?<keywords>(?:(?:Haste|First\\s+Strike|Brave)(?:,?\\s+(?:and\\s+)?)?)+))?[.!]?"
    );

    /**
     * Matches "Until end of turn, all [the] [targets1] [you control] gain +N power
     * and all [the] [targets2] [opponent controls] lose N power."
     * Groups: {@code targets1}, {@code control1}, {@code amount1},
     *         {@code targets2}, {@code control2}, {@code amount2}.
     */
    static final Pattern UNTIL_EOT_DUAL_POWER_SHIFT_PATTERN = Pattern.compile(
        "(?i)Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "all\\s+(?:the\\s+)?" +
        "(?:(?<element1>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<targets1>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+(?<control1>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+gains?\\s+\\+?(?<amount1>\\d+)\\s+[Pp]ower" +
        "\\s+and\\s+all\\s+(?:the\\s+)?" +
        "(?:(?<element2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<targets2>Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Characters?)" +
        "(?:\\s+(?<control2>(?:your\\s+)?opponent\\s+controls?|you\\s+control))?" +
        "\\s+loses?\\s+\\+?(?<amount2>\\d+)\\s+[Pp]ower[.!]?"
    );

    /**
     * Matches "Draw N card(s)[, then discard M card(s)]".
     * <ul>
     *   <li>Group 1 — number of cards to draw</li>
     *   <li>Group 2 — optional discard count afterward (absent = draw only)</li>
     * </ul>
     */
    // ---- "Select 1 number" patterns -------------------------------------------

    /** Matches the "Select 1 number." opening of an ability that lets the active player pick a cost. */
    static final Pattern SELECT_NUMBER_HEADER = Pattern.compile(
        "(?i)^Select\\s+1\\s+number\\.\\s*"
    );

    /** Matches "Your opponent selects 1 number." — appears as a second header in dual-selection abilities. */
    static final Pattern SELECT_NUMBER_OPPONENT_ALSO = Pattern.compile(
        "(?i)^Your\\s+opponent\\s+selects\\s+1\\s+number\\.\\s*"
    );

    /**
     * Inner effect: "All [the] Forwards of that cost cannot attack this turn."
     * Cannot be handled by the general substitution path since "cannot attack" is not
     * a MassAction in {@link GameContext.MassAction}.
     */
    static final Pattern SELECT_NUMBER_INNER_CANNOT_ATTACK = Pattern.compile(
        "(?i)All\\s+(?:the\\s+)?Forwards?\\s+of\\s+that\\s+cost\\s+cannot\\s+attack\\s+this\\s+turn\\.?"
    );

    /**
     * Inner effect for the dual-number case: "Break all Forwards of cost equal to either number."
     * Both P1's and P2's chosen numbers are used as cost filters.
     */
    static final Pattern SELECT_NUMBER_INNER_EITHER_BREAK = Pattern.compile(
        "(?i)Break\\s+all\\s+Forwards?\\s+of\\s+cost\\s+equal\\s+to\\s+either\\s+number\\.?"
    );

    /**
     * Followup used inside {@link #tryParseChooseCharacter}:
     * "Select 1 number and reveal the top card of your deck.
     *  If the revealed card is of the same cost as the selected number, break it."
     * "It" refers to the previously chosen Forward, not the revealed card.
     */
    static final Pattern FOLLOWUP_SELECT_NUMBER_REVEAL_BREAK = Pattern.compile(
        "(?i)Select\\s+1\\s+number\\s+and\\s+reveal\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck\\.\\s+" +
        "If\\s+the\\s+revealed\\s+card\\s+is\\s+of\\s+the\\s+same\\s+cost\\s+as\\s+the\\s+selected\\s+number,\\s+break\\s+it\\.?"
    );

    /**
     * Followup used inside {@link #tryParseChooseCharacter}:
     * "Select a Job. It gains that Job until the end of the turn." or
     * "Name 1 Job. It gains the named Job until the end of the turn."
     * Matched against the full followup (before the dot-split) so both sentences are seen together.
     */
    static final Pattern FOLLOWUP_SELECT_JOB_GRANT = Pattern.compile(
        "(?i)^(?:Select\\s+a|Name\\s+1)\\s+Job[.!]?\\s+" +
        "It\\s+gains?\\s+(?:that|the\\s+named)\\s+Job\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$"
    );

    /**
     * Matches "Look at the top card of your deck. You may put it into the Break Zone."
     */
    static final Pattern LOOK_TOP_DECK_OPTIONALLY_BREAK = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "You\\s+may\\s+put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /**
     * Matches "Look at the top card of your deck. You may place the card at the bottom of your deck."
     */
    static final Pattern LOOK_TOP_DECK_BOTTOM_OR_KEEP = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "You\\s+may\\s+place\\s+(?:the\\s+)?card\\s+at\\s+the\\s+bottom\\s+of\\s+your\\s+deck[.!]?"
    );

    /**
     * Matches "Look at the top N cards of your deck. Return them to the top of your deck in any order."
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    static final Pattern LOOK_TOP_DECK_RETURN_TOP_ORDERED = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Return\\s+them\\s+to\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?"
    );

    /**
     * Matches "Look at / Reveal the top N cards of your deck. Add 1 card among them to your hand
     * and return the other cards to the bottom of your deck in any order."  Cards that continue
     * past this clause are handled by {@link #ADDED_CARD_EX_BURST_RIDER}.
     * <ul>
     *   <li>Group {@code count} — number of cards to look at / reveal</li>
     *   <li>Group {@code verb}  — which wording was used; "Reveal" makes the cards public</li>
     * </ul>
     */
    static final Pattern LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM = Pattern.compile(
        "(?i)(?<verb>Look\\s+at|Reveal)\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Add\\s+1\\s+card\\s+among\\s+them\\s+to\\s+your\\s+hand\\s+and\\s+" +
        "return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?"
    );

    /**
     * Matches Lunafreya 23-129H's rider on the clause above: "If the card added to your hand has
     * an EX Burst, you may trigger its EX Burst effect." plus its parenthetical rules note.
     */
    static final Pattern ADDED_CARD_EX_BURST_RIDER = Pattern.compile(
        "(?i)^[\\s.!]*If\\s+the\\s+card\\s+added\\s+to\\s+your\\s+hand\\s+has\\s+an\\s+EX\\s+Burst,\\s*" +
        "you\\s+may\\s+trigger\\s+its\\s+EX\\s+Burst\\s+effect[.!]?" +
        "(?:\\s*\\([^)]*\\))?\\s*$"
    );

    /**
     * Matches "Look at the top N cards of your deck. Add 1 card among them to your hand,
     * put 1 card into the Break Zone and return the other cards to the bottom of your deck
     * in any order."
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    static final Pattern LOOK_TOP_DECK_ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Add\\s+1\\s+card\\s+among\\s+them\\s+to\\s+your\\s+hand[,.]?\\s*" +
        "put\\s+1\\s+card\\s+into\\s+the\\s+Break\\s+Zone\\s+and\\s+" +
        "return\\s+the\\s+other\\s+cards?\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?"
    );

    /**
     * Matches "Look at / Reveal the top N cards of your deck. Add 1 [Element] card among them
     * to your hand and put the rest of the cards into the Break Zone."
     * <ul>
     *   <li>Group {@code count}   — number of cards to look at / reveal</li>
     *   <li>Group {@code verb}    — which wording was used; "Reveal" makes the cards public</li>
     *   <li>Group {@code element} — optional element filter on the card added to hand</li>
     * </ul>
     */
    static final Pattern LOOK_TOP_DECK_ADD_TO_HAND_REST_BREAK = Pattern.compile(
        "(?i)(?<verb>Look\\s+at|Reveal)\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Add\\s+1\\s+(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?card\\s+among\\s+them\\s+to\\s+your\\s+hand[,]?\\s+and\\s+" +
        "put\\s+the\\s+rest\\s+(?:of\\s+the\\s+cards?\\s+)?into\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /**
     * Matches "Look at the top N cards of your deck. Return these to the top and/or bottom of
     * your deck in any order."  Anything the card adds after this clause is picked up separately
     * via {@link #TRAILING_THEN_CLAUSE} (Schultz 27-100R chains a reveal onto it).
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    static final Pattern LOOK_TOP_DECK_TOP_OR_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Return\\s+(?:them|these)\\s+to\\s+the\\s+top\\s+and[/\\s]?(?:or\\s+)?bottom\\s+of\\s+your\\s+deck\\s+in\\s+any\\s+order[.!]?"
    );

    /**
     * Matches the text left over after a primary clause when the card continues with a
     * "Then, [effect]" sentence.  Group {@code rest} is the follow-on effect text, ready to be
     * handed back to {@link #parse}.
     */
    private static final Pattern TRAILING_THEN_CLAUSE = Pattern.compile(
        "(?i)^[\\s.!]*Then,?\\s+(?<rest>\\S.*)$", Pattern.DOTALL
    );

    /**
     * Matches "Look at the top N cards of your deck. Put 1 card among them on top of your
     * deck and the other(s) to the bottom of your deck."
     * Strict 1-to-top, rest-to-bottom split.
     * <ul>
     *   <li>Group {@code count} — number of cards to look at</li>
     * </ul>
     */
    static final Pattern LOOK_TOP_DECK_PICK_ONE_TOP_REST_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s*" +
        "Put\\s+1\\s+card\\s+among\\s+them\\s+on\\s+top\\s+of\\s+your\\s+deck\\s+and\\s+" +
        "the\\s+others?\\s+to\\s+the\\s+bottom\\s+of\\s+your\\s+deck[.!]?"
    );

    /**
     * Catch-all: matches any bare "Look at the top [N cards / card] of your deck" with no
     * further action clause — treated as a pure peek (card stays on top, player just sees it).
     * <ul>
     *   <li>Group {@code count} — number of cards, or absent for the singular "top card" form</li>
     * </ul>
     */
    static final Pattern LOOK_TOP_DECK_PEEK = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?:(?<count>\\d+)\\s+cards?|card)\\s+of\\s+your\\s+deck[.!]?"
    );

    /**
     * Matches "Look at the top X cards of your deck. Reveal 1 Summon of cost X or less among
     * them and cast it without paying the cost. Then, shuffle the other cards and return them
     * to the bottom of your deck."
     * Groups: {@code count} — card count (numeric or {@code X});
     *         {@code cost}  — cost cap (numeric or {@code X}).
     */
    static final Pattern LOOK_TOP_DECK_CAST_SUMMON_FREE_REST_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+top\\s+(?<count>\\d+|X)\\s+cards?\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Reveal\\s+1\\s+Summon\\s+of\\s+cost\\s+(?<cost>\\d+|X)\\s+or\\s+less\\s+among\\s+them\\s+" +
        "and\\s+cast\\s+it\\s+without\\s+paying\\s+(?:its|the)\\s+cost[.!]?\\s+" +
        "(?:Then,?\\s+)?shuffle\\s+the\\s+other\\s+cards?\\s+and\\s+return\\s+them\\s+" +
        "to\\s+the\\s+bottom\\s+of\\s+(?:your|the)\\s+deck[.!]?"
    );

    /**
     * "Reveal the top card of your deck. Break all Forwards opponent controls with the same cost
     * as the revealed card. Add the revealed card to your hand."
     */
    static final Pattern REVEAL_TOP_BREAK_SAME_COST_ADD_TO_HAND = Pattern.compile(
        "(?i)Reveal\\s+the\\s+top\\s+card\\s+of\\s+your\\s+deck[.!]?\\s+" +
        "Break\\s+all\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls?\\s+with\\s+the\\s+same\\s+cost\\s+" +
        "as\\s+the\\s+revealed\\s+card[.!]?\\s+" +
        "Add\\s+the\\s+revealed\\s+card\\s+to\\s+your\\s+hand[.!]?"
    );

    /**
     * Detects "select [up to] N of the M following actions" — handled by MainWindow's
     * {@code executeSelectFollowingActionsAutoAbility}, not by ActionResolver's parse chain.
     * Used only for pattern-name reporting.
     */
    static final Pattern SELECT_FOLLOWING_ACTIONS_DETECT = Pattern.compile(
        "(?i)^(?:" +
        "(?:if\\s+[^,]+,\\s+)?select\\s+(?:up\\s+to\\s+)?\\d+\\s+of\\s+the\\s+\\d+\\s+following\\s+actions?" +
        "|select\\s+the\\s+following\\s+actions?\\s+from\\s+top\\s+to\\s+bottom\\b" +
        ")"
    );

    /**
     * Captures the components of "[if cond,] select [up to] N of the M following actions. "a" "b" ..."
     * so the action-ability parse chain can resolve it as a modal choice.
     */
    static final Pattern SELECT_FOLLOWING_ACTIONS = Pattern.compile(
        "(?i)^(?:if\\s+[^,]+,\\s+)?select\\s+(?<upTo>up\\s+to\\s+)?(?<select>\\d+)\\s+of\\s+the\\s+"
        + "(?<total>\\d+)\\s+following\\s+actions?[.!]?\\s*(?<actions>.+)$",
        Pattern.DOTALL
    );

    /** Extracts the individual quoted action strings from the {@code actions} capture group. */
    static final Pattern SELECT_FOLLOWING_QUOTED_ACTION = Pattern.compile("\"([^\"]+)\"");

    /**
     * Matches an inline conditional upgrade sentence that may appear before the quoted actions:
     * "If you control N or more [Element] [Type], select [up to] M of the K following actions instead."
     * Groups: {@code condCount}, {@code condElement} (optional), {@code condType},
     *         {@code condUpTo} (optional), {@code condSelect}.
     */
    static final Pattern SELECT_FOLLOWING_ACTIONS_CONDITIONAL_UPGRADE = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<condCount>\\d+)\\s+or\\s+more\\s+" +
        "(?:(?<condElement>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<condType>Forwards?|Backups?|Monsters?|Characters?|Summons?),\\s+" +
        "select\\s+(?<condUpTo>up\\s+to\\s+)?(?<condSelect>\\d+)\\s+of\\s+the\\s+\\d+\\s+" +
        "following\\s+actions?\\s+instead[.!]?\\s*",
        Pattern.DOTALL
    );

    /**
     * Matches an inline conditional upgrade gated on the opponent's hand size, appearing before the
     * quoted actions: "If your opponent has [no|N cards or less] cards in their hand, select [up to]
     * M of the K following actions instead." (e.g. Physalis' empty-hand upgrade to select up to 2).
     * Groups: {@code handCount} (absent means "no cards" = 0), {@code handUpTo} (optional),
     *         {@code handSelect}.
     */
    static final Pattern SELECT_FOLLOWING_ACTIONS_HAND_UPGRADE = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+has\\s+" +
        "(?:no\\s+cards?|(?<handCount>\\d+)\\s+cards?\\s+or\\s+less)\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand,\\s+" +
        "select\\s+(?<handUpTo>up\\s+to\\s+)?(?<handSelect>\\d+)\\s+of\\s+the\\s+\\d+\\s+" +
        "following\\s+actions?\\s+instead[.!]?\\s*",
        Pattern.DOTALL
    );

    /**
     * Matches "Place N [Name] Counter(s) on [CardName][.]".
     * <ul>
     *   <li>Group {@code count} — number of counters to place</li>
     *   <li>Group {@code name}  — counter name (e.g. {@code "Shuriken"})</li>
     *   <li>Group {@code target} — card name the counters are placed on</li>
     * </ul>
     */
    static final Pattern PLACE_COUNTERS = Pattern.compile(
        "(?i)Place\\s+(?<count>\\d+)\\s+(?<name>.+?)\\s+Counters?\\s+on\\s+(?<target>[^.!,]+)\\s*[.!]?"
    );

    /**
     * Matches "Remove all [Name] Counters from [CardName][.]".
     * <ul>
     *   <li>Group {@code name}   — counter name (e.g. {@code "Fortune"})</li>
     *   <li>Group {@code target} — card name the counters are removed from</li>
     * </ul>
     */
    static final Pattern REMOVE_ALL_COUNTERS = Pattern.compile(
        "(?i)Remove\\s+all\\s+(?<name>.+?)\\s+Counters?\\s+from\\s+(?<target>[^.!,]+)\\s*[.!]?"
    );

    /**
     * Matches "Place N [Name] Counter(s) on [CardName] for each [Type] you control."
     * Groups: {@code count}, {@code name}, {@code target}, {@code type}.
     */
    static final Pattern PLACE_COUNTERS_FOR_EACH = Pattern.compile(
        "(?i)^[Pp]lace\\s+(?<count>\\d+)\\s+(?<name>.+?)\\s+Counters?\\s+on\\s+(?<target>.+?)" +
        "\\s+for\\s+each\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control[.!]?$"
    );

    /**
     * Matches "Choose 1 Forward opponent controls. [Name] gains its Special Ability until the end of the turn.
     * You can use this ability without paying any cost but only once."
     * Group {@code sourceName} — card name that gains the ability (used for logging).
     */
    static final Pattern CHOOSE_OPP_FWD_GAINS_SPECIAL_ABILITY_FREE_ONCE = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls[,.]?\\s+" +
        "(?<sourceName>.+?)\\s+gains\\s+its\\s+Special\\s+Abilit(?:y|ies)\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn[.!]?\\s+" +
        "You\\s+can\\s+use\\s+this\\s+ability\\s+without\\s+paying\\s+any\\s+cost\\s+but\\s+only\\s+once[.!]?\\s*$"
    );

    /**
     * Matches "Choose 1 Forward opponent controls which has been dealt damage this turn.
     * If that Forward has a special ability or an action ability, break it."
     */
    static final Pattern CHOOSE_OPP_DAMAGED_FWD_IF_HAS_ABILITY_BREAK = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls\\s+" +
        "which\\s+has\\s+been\\s+dealt\\s+damage\\s+this\\s+turn[,.]?\\s+" +
        "If\\s+that\\s+Forward\\s+has\\s+(?:a\\s+special\\s+ability|an?\\s+action\\s+ability)" +
        "(?:\\s+or\\s+(?:a\\s+special\\s+ability|an?\\s+action\\s+ability))*,?\\s+break\\s+it[.!]?\\s*$"
    );

    /**
     * Matches "Choose as many &lt;Type&gt; [opponent controls] as [the] &lt;CountSource&gt; you control. &lt;Followup&gt;"
     * where the count is derived at resolution time from the acting player's field.
     * Group {@code targetType} — card type to choose (Forward/Character/etc.).
     * Group {@code targetSide} — "opponent controls" if targeting the opponent's cards; null = self.
     * Group {@code countSrc} — job-bracket, "Category X Type", "Job X", or plain card-type count source.
     * Group {@code followup} — effect to apply (Dull/Activate/Freeze).
     */
    static final Pattern CHOOSE_AS_MANY_AS_FIELD_COUNT = Pattern.compile(
        "(?i)^Choose\\s+(?:as\\s+many|up\\s+to\\s+the\\s+same\\s+number\\s+of)\\s+" +
        "(?<targetType>Forwards?|Characters?|Backups?|Monsters?)(?:\\s+Cards?)?\\s+" +
        "(?:(?<targetSide>(?:your\\s+)?opponent\\s+controls|you\\s+control)\\s+)?" +
        "as\\s+(?:the\\s+)?" +
        "(?<countSrc>\\[Job\\s*\\([^)]+\\)\\]|Category\\s+\\S+(?:\\s+(?:Forwards?|Characters?|Backups?|Monsters?))?|Job\\s+.+?(?=\\s+you\\s+control)|Forwards?|Backups?|Monsters?|Characters?)" +
        "\\s+you\\s+control[,.]?\\s+" +
        "(?<followup>.+)$"
    );

    /**
     * Matches "Choose up to the same number of Characters as the Job X in your Break Zone
     * and/or Job X you own removed from the game. [Dull/Activate/Freeze] them." (Jill 26-034L).
     * The count is computed at resolution time as (Job X in own Break Zone) + (Job X the acting
     * player owns removed from the game). Group {@code targetType}, {@code job}, {@code followup}.
     */
    static final Pattern CHOOSE_AS_MANY_AS_BZ_RFG_JOB = Pattern.compile(
        "(?i)^Choose\\s+(?:as\\s+many|up\\s+to\\s+the\\s+same\\s+number\\s+of)\\s+" +
        "(?<targetType>Forwards?|Characters?|Backups?|Monsters?)(?:\\s+Cards?)?\\s+" +
        "as\\s+(?:the\\s+)?Job\\s+(?<job>.+?)\\s+in\\s+your\\s+Break\\s+Zone\\s+and/or\\s+" +
        "Job\\s+.+?\\s+you\\s+own\\s+removed\\s+from\\s+the\\s+game[,.]?\\s+" +
        "(?<followup>.+)$"
    );

    /**
     * Matches "Choose up to the same number of Characters as the [Name] Counters placed on [card]. Activate them."
     * At resolution time {@code xValue} holds the counter count captured before the card was put into the Break Zone.
     * Group {@code counterName} — counter type (e.g. "Monster"); group {@code card} — source card name.
     */
    static final Pattern CHOOSE_COUNTER_SCALE_CHARS_ACTIVATE = Pattern.compile(
        "(?i)Choose\\s+up\\s+to\\s+the\\s+same\\s+number\\s+of\\s+Characters?\\s+as\\s+the\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+(?<card>.+?)[,.]\\s*Activate\\s+them[.!]?"
    );

    /**
     * Matches "Look at the same number of cards from the top of your deck as the [Name] Counters placed on [card].
     * Add 1 card among them to your hand. Then, shuffle the other cards and return them to the bottom of your deck."
     * At resolution time {@code xValue} holds the counter count captured before the card was put into the Break Zone.
     * Group {@code counterName} — counter type (e.g. "Monster"); group {@code card} — source card name.
     */
    static final Pattern LOOK_COUNTER_SCALE_ADD_TO_HAND_REST_BOTTOM = Pattern.compile(
        "(?i)Look\\s+at\\s+the\\s+same\\s+number\\s+of\\s+cards?\\s+from\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+as\\s+the\\s+(?<counterName>.+?)\\s+Counters?\\s+placed\\s+on\\s+(?<card>.+?)[,.]" +
        ".+?Add\\s+1\\s+card.+?to\\s+your\\s+hand.+?(?:shuffle|return).+?bottom.+?deck[.!]?"
    );

    /** Matches "Gain 《C》[《C》...]." — captures one or more consecutive Crystal symbols. */
    static final Pattern GAIN_CRYSTAL = Pattern.compile(
        "(?i)Gain\\s+(?<crystals>(?:《C》)+)[.!]?"
    );

    /** Matches "Gain 《C》 for each CP paid as X." — crystal count equals the X value paid. */
    static final Pattern GAIN_CRYSTAL_PER_X = Pattern.compile(
        "(?i)Gain\\s+《C》\\s+for\\s+each\\s+CP\\s+paid\\s+as\\s+X[.!]?"
    );

    /**
     * Matches "If your opponent has a 《C》, [also] gain 《C》."
     * Grants 1 Crystal only when the opponent currently holds at least one Crystal.
     */
    static final Pattern GAIN_CRYSTAL_IF_OPPONENT_HAS = Pattern.compile(
        "(?i)If\\s+your\\s+opponent\\s+has\\s+a\\s+《C》,\\s+(?:also\\s+)?gain\\s+《C》[.!]?"
    );

    /**
     * Matches "Draw N card(s), then place M card(s) from your hand at the bottom of your deck."
     * Group 1 = draw count, Group 2 = place count.
     */
    static final Pattern DRAW_THEN_PLACE_HAND_TO_BOTTOM = Pattern.compile(
        "(?i)Draw\\s+(\\d+)\\s+cards?[,.]?\\s+then\\s+place\\s+(\\d+)\\s+cards?\\s+from\\s+your\\s+hand\\s+at\\s+the\\s+bottom\\s+of\\s+your\\s+deck[.!]?"
    );

    /**
     * Matches "place up to N cards from your hand at the bottom of your deck [in any order]. Then,
     * draw the same number of cards as were returned to your deck." (Waltrill 8-047C) — the redraw
     * is sized by how many cards the player actually returned, which may be none.
     * Group {@code max} = the cap on cards returned.
     */
    static final Pattern PLACE_UP_TO_HAND_TO_BOTTOM_THEN_REDRAW = Pattern.compile(
        "(?i)place\\s+up\\s+to\\s+(?<max>\\d+)\\s+cards?\\s+from\\s+your\\s+hand\\s+at\\s+the\\s+bottom\\s+" +
        "of\\s+your\\s+deck(?:\\s+in\\s+any\\s+order)?[.,]?\\s+Then[,.]?\\s+draw\\s+the\\s+same\\s+number\\s+" +
        "of\\s+cards?\\s+as\\s+(?:were|was)\\s+returned\\s+to\\s+your\\s+deck[.!]?"
    );

    /**
     * Matches "pay 《Element》[…]. When you do so, [followup]."
     * Used when an auto-ability's effect text begins with an explicit CP payment followed by
     * a conditional effect clause.
     * Groups: {@code cost} — the raw CP token(s); {@code followup} — the effect text after the condition.
     */
    static final Pattern PAY_CP_WHEN_DO_SO = Pattern.compile(
        "(?i)^\\s*pay\\s+(?<cost>(?:《[^》]+》\\s*)+)[.!]?\\s+When\\s+you\\s+do\\s+so[,.]?\\s+(?<followup>.+)$",
        Pattern.DOTALL
    );

    /**
     * Matches "[you may pay 《X》.] if you don't pay 《X》, [consequence]" — an optional cost the
     * ability's controller may pay to avert a consequence: Umaro 15-107H and Cecil 15-073H (《C》),
     * Umaro 8-024C (《Ice》), Leon 28-056C and Vincent 2-078R (《N》). An offer clause printed ahead
     * of the gate is absorbed, since it names the same cost the gate then tests.
     *
     * <p>The comma after the cost is required: it separates this gate from Ultimecia 27-092H's
     * "if you don't pay 《1》 for each CP required to cast chosen Forward…", whose per-CP cost this
     * pattern must not claim.
     * <ul>
     *   <li>Group {@code cost}        — the token inside 《》: a number, an element name, or "C"</li>
     *   <li>Group {@code consequence} — what happens when the cost goes unpaid</li>
     * </ul>
     */
    static final Pattern IF_NOT_PAY_OR_ELSE = Pattern.compile(
        "(?i)^(?:you\\s+may\\s+)?(?:pay\\s+《[^》]+》[.!]?\\s+)?" +
        "if\\s+you\\s+don'?t\\s+pay\\s+《(?<cost>[^》]+)》\\s*,\\s+(?<consequence>.+)$",
        Pattern.DOTALL
    );

    /**
     * Matches "You may pay 《Element》. If you do so, [effect]." — an optional CP payment followed
     * by a conditional target action, used as the followup inside {@link #tryParseChooseCharacter}.
     * Groups: {@code element} — the element name (e.g. "Ice"); {@code effect} — the conditional action text.
     */
    static final Pattern FOLLOWUP_YOU_MAY_PAY_ELEMENT_IF_DO_SO = Pattern.compile(
        "(?i)^You\\s+may\\s+pay\\s+《(?<element>[^》]+)》[.!]?\\s+If\\s+you\\s+do\\s+so[,.]?\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );

    /**
     * Matches "[primary action]. Then, if you don't pay 《1》 for each CP required to cast chosen
     * [type], put it into the Break Zone." (Ultimecia 27-092H) — a followup whose cost is the
     * chosen card's own cost, so it can only be priced once the target is known.
     * Group {@code primary} — the action applied to the target before the cost is demanded.
     */
    static final Pattern FOLLOWUP_THEN_PAY_PER_TARGET_COST_OR_BREAK = Pattern.compile(
        "(?i)^(?<primary>.+?)[.!]\\s*Then[,.]?\\s+if\\s+you\\s+don'?t\\s+pay\\s+《1》\\s+for\\s+each\\s+CP\\s+" +
        "required\\s+to\\s+cast\\s+(?:the\\s+)?chosen\\s+\\w+\\s*,\\s+put\\s+it\\s+into\\s+the\\s+Break\\s+Zone[.!]?$",
        Pattern.DOTALL
    );

    /**
     * Matches "If your opponent doesn't pay 《N》, [target action]." — the followup inside
     * {@link #tryParseChooseCharacter} (e.g. Arkasodara: "choose 1 dull Forward. If your opponent
     * doesn't pay 《3》, break it."). The opponent may pay {@code cost} CP in full to prevent the
     * action; otherwise it runs against the chosen target(s).
     * Groups: {@code cost} — CP amount; {@code effect} — the target action text (e.g. "break it").
     */
    static final Pattern FOLLOWUP_IF_OPP_NOT_PAY_ACTION = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+doesn'?t\\s+pay\\s+《\\s*(?<cost>\\d+)\\s*》,?\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );

    static final Pattern DRAW_DISCARD_RETRIGGER_IF_CARD_NAME = Pattern.compile(
        "(?i)^Draw\\s+(?<draw>\\d+)\\s+cards?\\s+then\\s+discard\\s+(?<discard>\\d+)\\s+cards?[.!]?\\s+" +
        "If\\s+you\\s+discard\\s+a\\s+Card\\s+Name\\s+(?<name>.+?)\\s+by\\s+this\\s+effect,\\s+" +
        "trigger\\s+this\\s+auto-ability\\s+again[.!]?\\s*$"
    );

    static final Pattern DRAW_CARDS = Pattern.compile(
        "(?i)^Draw\\s+(\\d+)\\s+cards?(?:\\s*[,.]?\\s*then\\s+discard\\s+(\\d+)\\s+cards?)?[.!]?"
    );

    /**
     * Matches "Discard N card(s)[,] then draw M card(s)".
     * <ul>
     *   <li>Group 1 — number of cards to discard</li>
     *   <li>Group 2 — number of cards to draw afterward</li>
     * </ul>
     */
    static final Pattern DISCARD_THEN_DRAW = Pattern.compile(
        "(?i)^Discard\\s+(\\d+)\\s+cards?[,.]?\\s+then\\s+draw\\s+(\\d+)\\s+cards?[.!]?"
    );

    /**
     * Matches "&lt;subject&gt; deals your opponent N point(s) of damage."
     * <ul>
     *   <li>Group {@code amount} — number of damage points dealt to the opponent player</li>
     * </ul>
     */
    static final Pattern DEAL_PLAYER_DAMAGE_TO_OPPONENT = Pattern.compile(
        "(?i).+?\\s+deals?\\s+your\\s+opponent\\s+(?<amount>\\d+)\\s+points?\\s+of\\s+damage[.!]?"
    );

    /**
     * Matches "&lt;subject&gt; deals you N point(s) of damage." or "receive N point(s) of damage."
     * <ul>
     *   <li>Group {@code amount} — number of damage points dealt to the ability user</li>
     * </ul>
     */
    static final Pattern DEAL_PLAYER_DAMAGE_TO_SELF = Pattern.compile(
        "(?i)(?:.+?\\s+deals?\\s+you|receive)\\s+(?<amount>\\d+)\\s+points?\\s+of\\s+damage[.!]?"
    );

    /**
     * Matches: "Deal X damage to all [the] [condition] Forwards [of cost N [or less|more]] [other than Job Y] [opponent controls]"
     * <ul>
     *   <li>Group {@code amount}     — numeric damage value</li>
     *   <li>Group {@code condition}  — optional "damaged", "dull", "attacking", or "blocking"</li>
     *   <li>Group {@code cost}       — optional cost filter value</li>
     *   <li>Group {@code costcmp}    — optional comparison: "less" or "more"</li>
     *   <li>Group {@code excludejob} — optional job name to exclude, from "other than Job Y"</li>
     *   <li>Group {@code opponent}   — present when "opponent controls" appears</li>
     * </ul>
     */
    static final Pattern DEAL_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)Deal\\s+(?<amount>\\d+)\\s+damage\\s+to\\s+all(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking|active)\\s+)?" +
        "Forwards?" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+Job\\s+(?<excludejob>.+?)(?=\\s+(?:your\\s+)?opponent\\s+controls\\b|[.!]?$))?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?" +
        "[.!]?"
    );

    /** Matches "Deal N damage to [all] Forwards of all Elements except [Element]." */
    static final Pattern DEAL_DAMAGE_TO_FORWARDS_EXCEPT_ELEMENT = Pattern.compile(
        "(?i)Deal\\s+(?<amount>\\d+)\\s+damage\\s+to\\s+(?:all(?:\\s+the)?\\s+)?Forwards?\\s+" +
        "of\\s+all\\s+Elements?\\s+except\\s+(?<excludeelem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)[.!]?"
    );

    /**
     * Matches "Remove from the game all the Forwards on the field other than [elem1] and [elem2].
     * Then, remove from the top of your deck twice the number of cards removed by the previous effect."
     * Groups: {@code elem1}, {@code elem2}.
     */
    static final Pattern RFP_ALL_FWD_EXCEPT_ELEMENTS_THEN_TWICE_DECK = Pattern.compile(
        "(?i)Remove\\s+from\\s+(?:the\\s+)?game\\s+all\\s+(?:the\\s+)?Forwards?\\s+on\\s+(?:the\\s+)?field\\s+" +
        "other\\s+than\\s+(?<elem1>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+and\\s+(?<elem2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)[.!]?\\s*" +
        "Then,?\\s+remove\\s+from\\s+the\\s+top\\s+of\\s+your\\s+deck\\s+twice\\s+the\\s+number\\s+of\\s+cards\\s+removed\\s+by\\s+(?:the\\s+)?previous\\s+effect[.!]?"
    );

    /** Matches "No Forward of cost N or less/more can attack this turn." */
    static final Pattern NO_FORWARD_COST_CANNOT_ATTACK = Pattern.compile(
        "(?i)No\\s+Forward(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?\\s+can\\s+attack\\s+this\\s+turn[.!]?"
    );

    /** Matches "During this turn, the Forwards you control cannot be chosen by EX Bursts." */
    static final Pattern OWN_FORWARDS_CANNOT_BE_CHOSEN_BY_EX_BURST = Pattern.compile(
        "(?i)During\\s+this\\s+turn,?\\s+the\\s+Forwards?\\s+you\\s+control\\s+cannot\\s+be\\s+chosen\\s+by\\s+EX\\s+Bursts?[.!]?"
    );

    /** Matches "EX Bursts of cards put into the Damage Zone due to this ability cannot be used." */
    static final Pattern EX_BURST_SUPPRESSION_PATTERN = Pattern.compile(
        "(?i)EX\\s+Bursts?\\s+of\\s+cards?\\s+put\\s+into\\s+the\\s+Damage\\s+Zone\\s+due\\s+to\\s+this\\s+ability\\s+cannot\\s+be\\s+used[.!]?"
    );

    /**
     * Alternate word order: "Deal all [the] [condition] Forwards [of cost N] [other than Job Y] [opponent controls] X damage."
     * Same named groups as {@link #DEAL_DAMAGE_TO_FORWARDS} so {@link #tryParseDealDamageToForwards} can share extraction logic.
     */
    static final Pattern DEAL_DAMAGE_TO_FORWARDS_ALT = Pattern.compile(
        "(?i)Deal\\s+all(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking|active)\\s+)?" +
        "Forwards?" +
        "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<costcmp>less|more))?)?" +
        "(?:\\s+other\\s+than\\s+Job\\s+(?<excludejob>.+?)(?=\\s+(?:your\\s+)?opponent\\s+controls\\b|\\s+\\d+\\s+damage))?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?" +
        "\\s+(?<amount>\\d+)\\s+damage[.!]?"
    );

    /**
     * Matches: "Deal X damage for each [Element]? [Category Y]? Type you control to all [the] Forwards [opponent controls]"
     * <ul>
     *   <li>Group {@code base}      — base damage per matching card</li>
     *   <li>Group {@code element}   — optional element filter ("Wind", "Fire", etc.)</li>
     *   <li>Group {@code category}  — optional category filter</li>
     *   <li>Group {@code chartype}  — Forwards/Backups/Monsters/Characters</li>
     *   <li>Group {@code condition} — optional "damaged"/"dull"/etc. target filter</li>
     *   <li>Group {@code opponent}  — present when "opponent controls" appears</li>
     * </ul>
     */
    static final Pattern DEAL_DAMAGE_TO_FORWARDS_FOR_EACH = Pattern.compile(
        "(?i)Deal\\s+(?<base>\\d+)\\s+damage\\s+for\\s+each\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<chartype>Forwards?|Characters?|Backups?|Monsters?)\\s+" +
        "(?:(?<oppcount>(?:your\\s+)?opponent\\s+controls)|you\\s+control)" +
        "\\s+to\\s+all(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking|active)\\s+)?" +
        "Forwards?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?" +
        "[.!]?"
    );

    /**
     * Matches "For each Job [job] and[/or] Card [Nn]ame [name] you control, deal N damage to all Forwards [opponent controls]."
     * Groups: {@code job}, {@code cardname}, {@code amount}, {@code opponent}.
     */
    static final Pattern FOR_EACH_JOB_AND_NAME_DEAL_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)^For\\s+each\\s+Job\\s+(?<job>.+?)\\s+and(?:/or)?\\s+Card\\s+[Nn]ame\\s+(?<cardname>.+?)\\s+you\\s+control,?\\s+" +
        "deal\\s+(?<amount>\\d+)\\s+damage\\s+to\\s+all(?:\\s+the)?\\s+Forwards?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?[.!]?$"
    );

    /**
     * Matches "deal N damage for each Job X or [a] Card Name Y you control to all [the] Forwards opponent controls."
     * Groups: {@code amount}, {@code job}, {@code cardname}.
     */
    private static final Pattern DEAL_N_FOR_EACH_JOB_OR_NAME_TO_OPP_FORWARDS = Pattern.compile(
        "(?i)deal\\s+(?<amount>\\d+)\\s+damage\\s+for\\s+each\\s+" +
        "Job\\s+(?<job>.+?)\\s+or\\s+(?:a\\s+)?Card\\s+[Nn]ame\\s+(?<cardname>.+?)\\s+you\\s+control\\s+" +
        "to\\s+all\\s+(?:the\\s+)?Forwards?(?:\\s+(?:your\\s+)?opponent\\s+controls)?[.!]?$"
    );

    /**
     * Matches "deal N damage and M more damage for each Card Name [name] in your Break Zone
     * to all [the] Forwards [opponent controls]."
     * Groups: {@code base} — fixed base damage; {@code per} — additional per copy; {@code cardname} — name filter;
     * {@code opponent} — present when "opponent controls" appears.
     */
    static final Pattern DEAL_BASE_PLUS_BZ_NAME_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)^deal\\s+(?<base>\\d+)\\s+damage\\s+and\\s+(?<per>\\d+)\\s+more\\s+damage\\s+" +
        "for\\s+each\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+in\\s+your\\s+Break\\s+Zone\\s+" +
        "to\\s+all(?:\\s+the)?\\s+Forwards?" +
        "(?:\\s+(?<opponent>(?:your\\s+)?opponent\\s+controls))?[.!]?$"
    );

    /**
     * Matches "Until the end of the turn, [CardName] gains 'When [CardName] attacks, [innerEffect]'"
     * — grants the source card a temporary attack trigger for this turn.
     * Group {@code inner} — the effect text inside the quoted auto-ability.
     */
    private static final Pattern SELF_GAINS_WHEN_ATTACKS_EOT = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+(?:the\\s+)?turn,?\\s+.+?\\s+gains?\\s+\"When\\s+.+?\\s+attacks?,\\s+(?<inner>.+?)\"[.!]?$"
    );

    /**
     * Matches "Deal [N] damage to the Forward that blocks [CardName][.]"
     * Used by "is blocked" auto-abilities and action abilities that target the current combat blocker.
     * <ul>
     *   <li>Group {@code amount} — fixed damage value</li>
     *   <li>Group {@code name}   — name of the card being blocked</li>
     * </ul>
     */
    static final Pattern DAMAGE_TO_COMBAT_BLOCKER = Pattern.compile(
        "(?i)Deal\\s+(?<amount>\\d+)\\s+damage\\s+to\\s+the\\s+Forward\\s+that\\s+blocks?\\s+(?<name>.+?)[.!]?$"
    );

    /**
     * Matches "Deal each [condition] Forward[s] [opponent controls] damage equal to half of its power
     * [(round up to the nearest 1000)]."
     * <ul>
     *   <li>Group {@code condition} — optional "damaged", "dull", "attacking", or "blocking"</li>
     *   <li>Group {@code opponent}  — present when "opponent controls" appears</li>
     * </ul>
     */
    static final Pattern DEAL_HALF_POWER_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)Deal\\s+each(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking)\\s+)?" +
        "Forwards?\\s+" +
        "(?<opponent>(?:your\\s+)?opponent\\s+controls\\s+)?" +
        "damage\\s+equal\\s+to\\s+half\\s+of\\s+its\\s+power" +
        "(?:\\s*\\(\\s*round\\s+up\\s+to\\s+the\\s+nearest\\s+1000\\s*\\))?" +
        "[.!]?"
    );

    /**
     * Matches "Deal each [condition] Forward[s] [opponent controls] damage equal to its power minus N."
     * Groups: {@code condition}, {@code opponent}, {@code amount}.
     */
    static final Pattern DEAL_POWER_MINUS_N_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)Deal\\s+each(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking)\\s+)?" +
        "Forwards?\\s+" +
        "(?<opponent>(?:your\\s+)?opponent\\s+controls\\s+)?" +
        "damage\\s+equal\\s+to\\s+its\\s+power\\s+minus\\s+(?<amount>\\d+)" +
        "[.!]?"
    );

    /**
     * Matches "Deal damage equal to half of [name]'s power to all [the] [condition] Forward[s]
     * [opponent controls] [(round up/down to the nearest 1000)]."
     * <ul>
     *   <li>Group {@code sourcename} — name of the card whose power determines damage</li>
     *   <li>Group {@code condition}  — optional "damaged", "dull", "attacking", or "blocking"</li>
     *   <li>Group {@code opponent}   — present when "opponent controls" appears</li>
     *   <li>Group {@code rounding}   — "up" or "down" (absent defaults to round down)</li>
     * </ul>
     */
    static final Pattern DEAL_HALF_SOURCE_POWER_DAMAGE_TO_FORWARDS = Pattern.compile(
        "(?i)Deal\\s+damage\\s+equal\\s+to\\s+half\\s+of\\s+(?<sourcename>.+?)'s\\s+power\\s+" +
        "to\\s+all(?:\\s+the)?\\s+" +
        "(?:(?<condition>damaged|dull|attacking|blocking)\\s+)?" +
        "Forwards?\\s*" +
        "(?<opponent>(?:your\\s+)?opponent\\s+controls)?\\s*" +
        "(?:\\(\\s*round\\s+(?<rounding>up|down)[^)]*\\))?\\s*" +
        "[.!]?"
    );

    /**
     * Matches "During this turn, the cost required to cast your next [filter] is reduced by N
     * [(it cannot become 0)][.]"
     * <ul>
     *   <li>{@code element}  — optional element qualifier (e.g. "Wind")</li>
     *   <li>{@code category} — optional Category qualifier (e.g. "XIII")</li>
     *   <li>{@code job}      — optional Job qualifier (e.g. "Knight")</li>
     *   <li>{@code cardname} — specific card name (alternative to {@code type})</li>
     *   <li>{@code type}     — card type: Forward(s)/Backup(s)/Monster(s)/Summon(s)/card</li>
     *   <li>{@code amount}   — numeric reduction</li>
     *   <li>{@code floorone} — present when "(it cannot become 0)" clause is present</li>
     * </ul>
     */
    static final Pattern COST_REDUCTION_THIS_TURN = Pattern.compile(
        "(?i)During\\s+this\\s+turn,\\s+the\\s+cost\\s+required\\s+to\\s+cast\\s+your\\s+next\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?:" +
            // Combined "Job X or Card Name Y" — captured with OR semantics in the modifier
            "Job\\s+(?<joborg>.+?)\\s+(?:and/)?or\\s+Card\\s+Name\\s+(?<cnameborg>\\S+)" +
            // Existing: optional job then card-name or type
            "|(?:Job\\s+(?<job>.+?)\\s+)?(?:Card\\s+Name\\s+(?<cardname>\\S+)|(?<type>Forwards?|Backups?|Monsters?|Summons?|card))" +
        ")\\s+" +
        "is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?<floorone>\\s*\\(it\\s+cannot\\s+become\\s+0\\))?[.!]?"
    );

    /**
     * Matches "The cost required to play your [filter] onto the field this turn is reduced by N
     * [(it cannot become 0)][.]" — applies to all matching plays this turn (not consumed on use).
     */
    static final Pattern PLAY_COST_REDUCTION_THIS_TURN = Pattern.compile(
        "(?i)The\\s+cost\\s+required\\s+to\\s+(?:play|cast)\\s+your\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?:Job\\s+(?<job>.+?)\\s+)?" +
        "(?:Card\\s+Name\\s+(?<cardname>\\S+)|(?<type>Forwards?|Backups?|Monsters?|Characters?))\\s+" +
        "(?:onto\\s+the\\s+field\\s+)?this\\s+turn\\s+is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?<floorone>\\s*\\(it\\s+cannot\\s+become\\s+0\\))?[.!]?"
    );

    /**
     * Matches "Choose 1 Summon in your Break Zone. Add it to your hand. During this turn,
     * the cost required to cast your next Summon is reduced by N [(it cannot become 0)]."
     * <ul>
     *   <li>Group {@code amount}   — cost reduction</li>
     *   <li>Group {@code floorone} — present when "(it cannot become 0)" clause appears</li>
     * </ul>
     */
    static final Pattern CHOOSE_SUMMON_FROM_BZ_TO_HAND_WITH_COST_REDUCTION = Pattern.compile(
        "(?i)Choose\\s+1\\s+Summon\\s+in\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "Add\\s+it\\s+to\\s+your\\s+hand[.!]?\\s+" +
        "During\\s+this\\s+turn,?\\s+the\\s+cost\\s+required\\s+to\\s+cast\\s+your\\s+next\\s+Summon\\s+" +
        "is\\s+reduced\\s+by\\s+(?<amount>\\d+)" +
        "(?<floorone>\\s*\\(it\\s+cannot\\s+become\\s+0\\))?[.!]?\\s*$"
    );

    /**
     * Matches "Choose N Summons in your Break Zone. Add 1 of them to your hand, and remove the rest from the game."
     * Group {@code total} — number of Summons to choose.
     */
    static final Pattern CHOOSE_N_SUMMONS_BZ_PICK_ONE_HAND_REST_RFG = Pattern.compile(
        "(?i)Choose\\s+(?<total>\\d+)\\s+Summons?\\s+in\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "Add\\s+1\\s+of\\s+them\\s+to\\s+your\\s+hand[,.]?(?:\\s+and)?\\s+remove\\s+the\\s+rest\\s+from\\s+the\\s+game[.!]?\\s*$"
    );

    /**
     * Matches "Choose 1 [Element] Summon in your Break Zone. You can cast it at any time you
     * could normally cast it this turn. The cost required to cast it is reduced by N."
     * Used by abilities that "borrow" a Summon from the Break Zone for one extra cast.
     * <ul>
     *   <li>Group {@code element} — required element of the chosen Summon</li>
     *   <li>Group {@code amount}  — cost reduction applied to that Summon's next cast</li>
     * </ul>
     */
    static final Pattern CHOOSE_SUMMON_IN_BZ_CASTABLE = Pattern.compile(
        "(?i)Choose\\s+1\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Summon\\s+in\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "You\\s+can\\s+cast\\s+it\\s+at\\s+any\\s+time\\s+you\\s+could\\s+normally\\s+cast\\s+it\\s+this\\s+turn[.!]?\\s+" +
        "The\\s+cost\\s+required\\s+to\\s+cast\\s+it\\s+is\\s+reduced\\s+by\\s+(?<amount>\\d+)[.!]?"
    );

    /**
     * "Your opponent removes the top card of their deck from the game [face down]. You can [look at
     * it and/or] cast it as though you owned it at any time you could normally cast it. The cost for
     * casting it [is reduced by N and] can be paid using CP of any Element." (Lani 12-018H, Zidane 16-048H)
     */
    static final Pattern OPP_RFP_TOPDECK_CASTABLE = Pattern.compile(
        "(?is)your\\s+opponent\\s+removes\\s+the\\s+top\\s+card\\s+of\\s+their\\s+deck\\s+from\\s+the\\s+game(?:\\s+face\\s+down)?[.!]?\\s+" +
        "You\\s+can\\s+(?:look\\s+at\\s+it\\s+and/or\\s+)?cast\\s+it\\s+as\\s+though\\s+you\\s+owned\\s+it\\s+at\\s+any\\s+time\\s+you\\s+could\\s+normally\\s+cast\\s+it[.!]?" +
        "(?<cost>.*)$"
    );

    /**
     * "Choose 1 [Forward|Backup|Monster|Character] in your opponent's Break Zone. Remove it from the
     * game. [During this game,] you can cast it as though you owned it at any time you could normally
     * cast it." (Bel Dat 20-056H — Forward; Zidane 24-044H — Character)
     */
    static final Pattern CHOOSE_FROM_OPP_BZ_CASTABLE = Pattern.compile(
        "(?is)Choose\\s+1\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+in\\s+your\\s+opponent'?s\\s+Break\\s+Zone[.!]?\\s+" +
        "Remove\\s+it\\s+from\\s+the\\s+game[.!]?\\s+" +
        "(?:During\\s+this\\s+game,?\\s+)?[Yy]ou\\s+can\\s+cast\\s+it\\s+as\\s+though\\s+you\\s+owned\\s+it\\s+at\\s+any\\s+time\\s+you\\s+could\\s+normally\\s+cast\\s+it[.!]?"
    );

    /**
     * "Choose N Summon(s) [in|from] [your and/or your opponent's|either player's|your] Break Zone.
     * Remove it/them from the game. During this game, you can cast it/them [as though you owned
     * it/them ]at any time you could normally cast it/them ..." (Shantotto 23-067R; also the plain
     * "you can cast it at any time you could normally cast it" phrasing without "as though you owned it").
     */
    static final Pattern CHOOSE_SUMMONS_FROM_BZ_GAME = Pattern.compile(
        "(?is)[Cc]hoose\\s+(?<count>\\d+)\\s+Summons?\\s+(?:in|from)\\s+(?<scope>your\\s+and/or\\s+your\\s+opponent'?s|either\\s+player'?s|your\\s+opponent'?s|your)\\s+Break\\s+Zone[.!]?\\s+" +
        "Remove\\s+(?:it|them)\\s+from\\s+the\\s+game[.!]?\\s+" +
        "During\\s+this\\s+game,?\\s+you\\s+can\\s+cast\\s+(?:it|them)\\s+" +
        "(?:as\\s+though\\s+you\\s+owned\\s+(?:it|them)\\s+)?.*"
    );

    /**
     * "Choose N Summon(s) from [either player's|your and/or your opponent's|your] Break Zone. You can
     * cast it as though you owned it this turn. [If you cast it, remove that Summon from the game after
     * use instead of putting it in the Break Zone.]" (Krile 12-061L)
     */
    static final Pattern CHOOSE_SUMMONS_FROM_BZ_TURN = Pattern.compile(
        "(?is)[Cc]hoose\\s+(?<count>\\d+)\\s+Summons?\\s+from\\s+(?<scope>your\\s+and/or\\s+your\\s+opponent'?s|either\\s+player'?s|your\\s+opponent'?s|your)\\s+Break\\s+Zone[.!]?\\s+" +
        "You\\s+can\\s+cast\\s+(?:it|them)\\s+as\\s+though\\s+you\\s+owned\\s+(?:it|them)\\s+this\\s+turn[.!]?" +
        "(?<rfg>.*)$"
    );

    /**
     * "Choose 1 Summon of cost N or less in your Break Zone. Cast it without paying the cost.
     * Remove that Summon from the game after use instead of putting it in the Break Zone."
     */
    static final Pattern CHOOSE_SUMMON_IN_BZ_MAX_COST_FREE_CAST_RFG = Pattern.compile(
        "(?is)Choose\\s+1\\s+Summon\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+less\\s+in\\s+your\\s+Break\\s+Zone[.!]?\\s+" +
        "Cast\\s+it\\s+without\\s+paying\\s+the\\s+cost[.!]?\\s+" +
        "Remove\\s+that\\s+Summon\\s+from\\s+the\\s+game\\s+after\\s+use\\s+instead\\s+of\\s+putting\\s+it\\s+in\\s+the\\s+Break\\s+Zone[.!]?"
    );

    /**
     * "Choose 1 Forward with N power or less and up to 1 Forward in your opponent's Break Zone.
     * Remove them from the game."
     */
    static final Pattern CHOOSE_FWD_POWER_LE_AND_OPT_OPP_BZ_FWD_RFP = Pattern.compile(
        "(?i)Choose\\s+1\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power\\s+or\\s+less" +
        "\\s+and\\s+up\\s+to\\s+1\\s+Forward\\s+in\\s+your\\s+opponent(?:'s)?\\s+Break\\s+Zone[.!]?\\s+" +
        "Remove\\s+them\\s+from\\s+(?:the\\s+)?game[.!]?"
    );

    /** Matches "Take 1 more turn after this one. At the end of that turn, you lose the game." */
    private static final Pattern EXTRA_TURN_THEN_LOSE = Pattern.compile(
        "(?i)Take\\s+1\\s+more\\s+turn\\s+after\\s+this\\s+one[.!]?\\s+" +
        "At\\s+the\\s+end\\s+of\\s+that\\s+turn,\\s+you\\s+lose\\s+the\\s+game[.!]?"
    );

    /**
     * Matches "Until the end of the turn, all the Monsters you control also become Forwards with N power."
     * Group {@code power} captures the power value.
     */
    private static final Pattern ALL_MONSTERS_BECOME_FORWARDS_UNTIL_EOT_PATTERN = Pattern.compile(
        "(?i)^Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,?\\s+" +
        "all\\s+(?:the\\s+)?Monsters?\\s+you\\s+control\\s+also\\s+become\\s+Forwards?\\s+with\\s+(?<power>\\d+)\\s+power[.!]?"
    );

    /**
     * Matches "Until the end of the turn, [CardName] also becomes a Forward with N power."
     * Used for action abilities on Monsters.  Group {@code power} captures the power value.
     */
    static final Pattern BECOME_FORWARD_UNTIL_EOT_PATTERN = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power"
    );

    /**
     * Extended form: "…becomes a Forward with N power and "Put [name] into the Break Zone: [effect]"."
     * Groups: {@code power}, {@code bzName}, {@code bzEffect}.
     */
    private static final Pattern BECOME_FORWARD_AND_BZ_ACTION = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power" +
        "\\s+and\\s+\"Put\\s+(?<bzName>.+?)\\s+into\\s+the\\s+Break\\s+Zone:\\s+(?<bzEffect>[^\"]+?)\"\\s*[.!]?",
        Pattern.DOTALL
    );

    /**
     * Extended form: "…becomes a Forward with N power and "When [name] attacks, [effect]"."
     * Groups: {@code power}, {@code attackEffect}.
     */
    private static final Pattern BECOME_FORWARD_AND_ATTACK_TRIGGER = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power" +
        "\\s+and\\s+\"When\\s+[^\"]+?\\s+attacks?\\s*,\\s+(?<attackEffect>[^\"]+?)\"\\s*[.!]?",
        Pattern.DOTALL
    );

    /**
     * Extended form: "…becomes a Forward with N power and "When [name] blocks or is blocked, [effect]"."
     * Groups: {@code power}, {@code blockEffect}.
     */
    private static final Pattern BECOME_FORWARD_AND_BLOCK_TRIGGER = Pattern.compile(
        "(?i)^Until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+.+?\\s+also\\s+becomes?\\s+a\\s+Forward\\s+with\\s+(?<power>\\d+)\\s+power" +
        "\\s+and\\s+\"When\\s+[^\"]+?\\s+blocks?(?:\\s+or\\s+is\\s+blocked)?\\s*,\\s+(?<blockEffect>[^\"]+?)\"\\s*[.!]?",
        Pattern.DOTALL
    );

    /**
     * Matches "If the CP paid to cast [Name] was only produced by Backups, [also] draw N card(s)."
     * Group {@code count} — number of cards to draw.
     */
    static final Pattern BACKUP_CP_DRAW = Pattern.compile(
        "(?i)If\\s+the\\s+CP\\s+paid\\s+to\\s+cast\\s+.+?\\s+was\\s+only\\s+produced\\s+by\\s+Backups?," +
        "\\s+(?:also\\s+)?draw\\s+(?<count>\\d+)\\s+cards?[.!]?"
    );

    /**
     * Matches "If your opponent has [no | N cards or less] cards in his/her hand, [effect][ instead][.!]?"
     * <ul>
     *   <li>{@code n}       — numeric threshold; absent when the condition is "no cards" (threshold = 0)</li>
     *   <li>{@code effect}  — the conditional inner effect text</li>
     *   <li>{@code instead} — present when "instead" immediately follows the effect</li>
     * </ul>
     */
    static final Pattern OPPONENT_HAND_CONDITION_PATTERN = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+has\\s+" +
        // Each branch carries its own trailing noun: the "no" branch ("no cards in their hand")
        // needs it, while "N cards or less" already consumes one "cards" and the real card wording
        // ("2 cards or less in their hand") runs straight into "in" with no second "cards".
        "(?:no\\s+cards?|(?<n>\\d+)\\s+cards?\\s+or\\s+less)\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand,?\\s*" +
        "(?<effect>.+?)" +
        "(?<instead>\\s+instead)?[.!]?$"
    );

    /**
     * Matches "If your opponent has N cards or more in their hand, [effect]."
     * Fires the inner effect only when the opponent's hand meets the minimum threshold.
     * Groups: {@code n} — minimum hand size; {@code effect} — the conditional effect text.
     */
    static final Pattern OPPONENT_HAND_MIN_CONDITION_PATTERN = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+has\\s+(?<n>\\d+)\\s+cards?\\s+or\\s+more\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand,?\\s*" +
        "(?<effect>.+?)\\s*[.!]?$"
    );

    /**
     * Matches a two-clause hand condition used as a Choose followup:
     * "If your opponent has N cards or less …, [action1]. If your opponent has no cards …, [action2] instead."
     * <ul>
     *   <li>{@code n}       — upper threshold for the relaxed condition</li>
     *   <li>{@code effect1} — action applied when 0 &lt; handSize ≤ N</li>
     *   <li>{@code effect2} — action applied when handSize == 0 (overrides effect1)</li>
     * </ul>
     */
    static final Pattern OPPONENT_HAND_DOUBLE_CONDITION_PATTERN = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+has\\s+(?<n>\\d+)\\s+cards?\\s+or\\s+less\\s+cards?\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand,?\\s*(?<effect1>.+?)[.!]\\s+" +
        "If\\s+your\\s+opponent\\s+has\\s+no\\s+cards?\\s+in\\s+" +
        "(?:his/her|his|her|their)\\s+hand,?\\s*(?<effect2>.+?)\\s+instead[.!]?$"
    );

    /**
     * Matches "If your opponent controls N or more Forwards, deal it/them X damage[.!]?"
     * as a choose-character followup or standalone conditional effect.
     * <ul>
     *   <li>{@code count}  — minimum number of opponent Forwards required</li>
     *   <li>{@code amount} — damage to deal when the condition is met</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_IF_OPPONENT_CONTROLS_FORWARDS_DAMAGE = Pattern.compile(
        "(?i)^If\\s+your\\s+opponent\\s+controls\\s+(?<count>\\d+)\\s+or\\s+more\\s+Forwards?,\\s+" +
        "deal\\s+(?:it|them)\\s+(?<amount>\\d+)\\s+damage[.!]?$"
    );

    /**
     * Matches "If you control N or more [Element] [Type], deal it/them X damage[.!]?"
     * as a choose-character followup.
     * <ul>
     *   <li>{@code count}   — minimum number of own field cards required</li>
     *   <li>{@code element} — optional element filter (e.g. "Fire"); absent = any</li>
     *   <li>{@code type}    — card type: Forward(s), Backup(s), Monster(s), Character(s), Summon(s)</li>
     *   <li>{@code amount}  — damage to deal when the condition is met</li>
     * </ul>
     */
    static final Pattern FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_DAMAGE = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<count>\\d+)\\s+or\\s+more\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?|Summons?),?\\s+" +
        "deal\\s+(?:it|them)\\s+(?<amount>\\d+)\\s+damage[.!]?$"
    );

    /**
     * The general form of {@link #FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_DAMAGE}: the same
     * "If you control N or more [Element] [Type]" gate in front of any target action rather than
     * only "deal it X damage" — e.g. Cocytus 8-031R's "choose up to 2 Forwards. If you control 4
     * or more Ice Characters, Freeze them."  The condition gates the <em>action</em>, not the
     * choosing: the targets are picked either way.
     * <p>{@code action} is handed to {@link #parseTargetAction}, so this only takes effect for
     * actions that machinery recognises; anything else falls through to the handlers below.
     */
    static final Pattern FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_ACTION = Pattern.compile(
        "(?i)^If\\s+you\\s+control\\s+(?<count>\\d+)\\s+or\\s+more\\s+" +
        "(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?|Summons?),?\\s+" +
        "(?<action>.+?)[.!]?$"
    );

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Matches the "If you paid the extra cost" conditional clause in effect text. {@code base}
     * may be empty when the condition is the entire ability (e.g. Summoner: "If you paid the
     * extra cost, your opponent selects …") rather than a suffix on an unconditional lead-in
     * (e.g. Samurai: "Choose 1 Forward … If you paid the extra cost, break it.").
     * Groups: {@code base}, {@code also}, {@code effect}, {@code instead}.
     */
    private static final java.util.regex.Pattern IF_PAID_EXTRA_COST = java.util.regex.Pattern.compile(
        "(?i)(?<base>.*?)\\s*\\b[Ii]f\\s+you\\s+paid\\s+the\\s+extra\\s+cost(?:\\s+and\\s+[^,]+)?,\\s+" +
        "(?<also>also\\s+)?" +
        "(?<effect>.+?)" +
        "(?<instead>\\s+instead)?" +
        "\\.?\\s*$",
        java.util.regex.Pattern.DOTALL
    );

    /**
     * Transforms summon effect text to apply the paid branch of an extra-cost conditional.
     * <ul>
     *   <li><b>Additive</b> ("also …"): appends the paid effect to the base.</li>
     *   <li><b>Replacement without "it"</b>: replaces the entire base text.</li>
     *   <li><b>Replacement with "it"</b>: keeps earlier base sentences, replaces the last.</li>
     *   <li><b>No conditional</b> (Titan): text is returned unchanged.</li>
     * </ul>
     */
    public static String applyExtraCostPaid(String text) {
        java.util.regex.Matcher m = IF_PAID_EXTRA_COST.matcher(text);
        if (!m.find()) return text;

        String base      = m.group("base").trim();
        boolean isAlso   = m.group("also") != null;
        String effect    = m.group("effect").trim();
        boolean isInstead = m.group("instead") != null;

        String cap = Character.toUpperCase(effect.charAt(0)) + effect.substring(1);
        if (!cap.endsWith(".")) cap += ".";

        if (isAlso) {
            return base.isEmpty() ? cap : base + " " + cap;
        }
        if (isInstead) {
            if (java.util.regex.Pattern.compile("(?i)\\bit\\b").matcher(effect).find()) {
                String withoutLast = removeLastSentence(base);
                return withoutLast.isEmpty() ? cap : withoutLast + " " + cap;
            }
            return cap;
        }
        // Additive without "also" keyword (Leviathan-style): append
        return base.isEmpty() ? cap : base + " " + cap;
    }

    /** Strips the "If you paid the extra cost, … ." clause (single- or multi-sentence) from the end of {@code text}. */
    public static String stripExtraCostClause(String text) {
        return text.replaceAll("(?i)\\s*If\\s+you\\s+paid\\s+the\\s+extra\\s+cost.*$", "").trim();
    }

    private static String removeLastSentence(String text) {
        int last = text.lastIndexOf('.');
        if (last <= 0) return "";
        int prev = text.lastIndexOf('.', last - 1);
        return prev < 0 ? "" : text.substring(0, prev + 1).trim();
    }

    /**
     * Attempts to parse {@code effectText} into a ready-to-execute
     * {@link Consumer}{@code <GameContext>}.
     *
     * @return the effect consumer, or {@code null} if the text is not yet supported
     */
    public static Consumer<GameContext> parse(String effectText) {
        return parse(effectText, null, 0);
    }

    /**
     * Attempts to parse {@code effectText} into a ready-to-execute
     * {@link Consumer}{@code <GameContext>}.
     *
     * @param source the card that owns this ability; required for standalone self-buff effects
     * @return the effect consumer, or {@code null} if the text is not yet supported
     */
    public static Consumer<GameContext> parse(String effectText, CardData source) {
        return parse(effectText, source, 0);
    }

    /**
     * @param xValue the CP amount paid into {@code 《X》}; {@code 0} when the ability has no X cost
     */
    public static Consumer<GameContext> parse(String effectText, CardData source, int xValue) {
        // Strip leading "EX BURST" / "[[ex]]EX BURST[[/]]" prefix present on summon field ability texts.
        effectText = effectText.replaceFirst("(?i)^(?:\\[\\[ex\\]\\])?\\s*EX\\s+BURST(?:\\[\\[/\\]\\])?\\s*", "").trim();
        // Strip leading "Then, " connector that appears when this text is a secondary clause.
        effectText = effectText.replaceFirst("(?i)^Then,?\\s+", "").trim();
        // Strip a leading "also" the same way — purely additive phrasing carried over from the
        // clause this text follows ("…, also draw 1 card." — Odin 21-084H), never a verb of its own.
        effectText = effectText.replaceFirst("(?i)^also\\s+", "").trim();
        Consumer<GameContext> result;

        // "Cast it as though you owned it" family — matched early because the highly specific
        // borrowed-cast phrasing would otherwise be intercepted by generic Choose/Remove matchers.
        result = tryParseOppRfpTopDeckCastable(effectText);
        if (result != null) return result;

        result = tryParseChooseFromOppBzCastable(effectText);
        if (result != null) return result;

        result = tryParseChooseSummonsFromBzCastable(effectText);
        if (result != null) return result;

        result = tryParseChooseSummonInBzMaxCostFreeCastRfg(effectText);
        if (result != null) return result;

        result = tryParseSelectFollowingActions(effectText, source);
        if (result != null) return result;

        // Must precede tryParseWhenYouDoSoSequence: Zidane-style text contains "If you do so"
        // which that parser would split, causing it to match first via OPPONENT_DRAW on the tail.
        result = tryParseRevealHandOptPickRfpOppDraw(effectText);
        if (result != null) return result;

        // Must precede tryParseWhenYouDoSoSequence: that parser resolves both halves independently,
        // and a bare "pay 《…》" is not an effect it can resolve, so the optional cost would be lost.
        result = tryParseMayPayCostThenEffect(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseWhenYouDoSoSequence(effectText, source, xValue);
        if (result != null) return result;

        // Must precede every consequence pattern: those match with find(), so left alone they would
        // claim the text after the gate and resolve the consequence unconditionally.
        result = tryParseIfNotPayOrElse(effectText, source, xValue);
        if (result != null) return result;

        // Same reasoning: the mass-break matcher would find "break all the Forwards opponent
        // controls" in the tail and apply it with no regard for the pile threshold in front of it.
        result = tryParseRemoveTopThenPileThreshold(effectText, source);
        if (result != null) return result;

        result = tryParseAddRemovedBySourceAbilityToHand(effectText, source);
        if (result != null) return result;

        result = tryParseIfOwnForwardFormedParty(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfControlAtMost(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfAllHaveElement(effectText, source, xValue);
        if (result != null) return result;

        // Must precede the generic damage/draw matchers: their leading ".+?" would otherwise
        // swallow the "if each player has no cards…" clause and drop the condition entirely.
        result = tryParseIfEachPlayerEmptyHand(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfNDiffElements(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfControlCondOtherThan(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseControlConditionGate(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseControlGatedInsteadUpgrade(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseOpponentControlsCardGate(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfOppControlsNOrMoreCondTypeGate(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseDiscardConditionalElement(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseDiscardConditionalElementSingle(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseDiscardConditionalTargetLoseAbilities(effectText);
        if (result != null) return result;

        result = tryParseDiscardConditionalSelfBoostInstead(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseDrawDiscardIfMultiElement(effectText);
        if (result != null) return result;

        result = tryParseIfCastAtLeast(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseSelectNumber(effectText, source);
        if (result != null) return result;

        result = tryParseAllMonstersTemporaryForward(effectText);
        if (result != null) return result;

        result = tryParseBecomeForwardUntilEot(effectText, source);
        if (result != null) return result;

        result = tryParseForEachJobAndNameDealDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDealNForEachJobOrNameToOppForwards(effectText);
        if (result != null) return result;

        result = tryParseDealBasePlusBzNameDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseSelfGainsWhenAttacksEOT(effectText, source);
        if (result != null) return result;

        result = tryParseDealDamageToForwardsForEach(effectText);
        if (result != null) return result;

        result = tryParseDealDamageToForwardsExceptElement(effectText);
        if (result != null) return result;

        result = tryParseRfpAllFwdExceptElementsThenTwiceDeck(effectText);
        if (result != null) return result;

        result = tryParseDealDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDivideDamageEquallyAmongAll(effectText);
        if (result != null) return result;

        result = tryParseNoForwardCostCannotAttack(effectText);
        if (result != null) return result;

        result = tryParseOwnForwardsCannotBeChosenByExBurst(effectText);
        if (result != null) return result;

        result = tryParseExBurstSuppression(effectText);
        if (result != null) return result;

        result = tryParseDealHalfPowerDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDealPowerMinusNDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDealHalfSourcePowerDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDamageToCombatBlocker(effectText);
        if (result != null) return result;

        result = tryParseChooseOneEach(effectText, source);
        if (result != null) return result;

        result = tryParseChooseForwardRedirectToNamed(effectText);
        if (result != null) return result;

        result = tryParseChooseFormerLatter(effectText, source);
        if (result != null) return result;

        result = tryParseChooseFwdPowerLeAndOptOppBzFwdRfp(effectText);
        if (result != null) return result;

        result = tryParseChooseThreeMixedTypes(effectText, source);
        if (result != null) return result;

        result = tryParseChooseTwoMixedTypes(effectText, source);
        if (result != null) return result;

        result = tryParseChooseForwardDealSelfDamageBreakIfCostLeDamage(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardSharedPowerLoss(effectText, source);
        if (result != null) return result;

        result = tryParseChooseOppFwdDynCostBreak(effectText);
        if (result != null) return result;

        result = tryParseChooseFwdPowerInferiorToSource(effectText, source);
        if (result != null) return result;

        result = tryParseChooseFwdBzCostInferiorToRemovedPlay(effectText);
        if (result != null) return result;

        result = tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(effectText, source);
        if (result != null) return result;

        result = tryParseUseSpecialAbilityUsedThisTurn(effectText, source);
        if (result != null) return result;

        result = tryParseChooseOppDamagedFwdIfHasAbilityBreak(effectText);
        if (result != null) return result;

        result = tryParseChooseAsManyAsFieldCount(effectText, source);
        if (result != null) return result;

        result = tryParseChooseAsManyAsBzRfgJobCount(effectText);
        if (result != null) return result;

        result = tryParseChooseCounterScaleCharsActivate(effectText, xValue);
        if (result != null) return result;

        result = tryParseChooseAnyNumberReturnToHand(effectText);
        if (result != null) return result;

        // Checked ahead of tryParseChooseCharacter: its "Summons?" target noun would otherwise
        // match bare "Choose 1 Summon. If your opponent doesn't pay..." text first, and its generic
        // followup dispatch's unanchored "Cancel its effect" substring match would misfire on the
        // conditional-pay clause as if it were a plain unconditional cancel.
        result = tryParseCancelStackEntryUnlessPay(effectText);
        if (result != null) return result;

        // Checked ahead of tryParseChooseCharacter: these "choose … Forward(s) …" compounds would
        // otherwise be claimed by ChooseCharacter's generic followup dispatch, which only partially
        // handles them (the reveal-cost-parity branch, and the ability-granting forms).
        result = tryParseChooseFwdRevealCostParity(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardsGainAbilityEot(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardPlacePetrification(effectText);
        if (result != null) return result;

        result = tryParseChooseOwnFwdBoostProtectionsOrAllIfDmg(effectText);
        if (result != null) return result;

        result = tryParseActivateAllOwnFwdsGainProtections(effectText);
        if (result != null) return result;

        result = tryParseRemoveAllCountersFromSelf(effectText, source);
        if (result != null) return result;

        result = tryParseChooseCharacter(effectText, source, xValue);
        if (result != null) return withAiTargetPreference(effectText, result);

        result = tryParseIfSelfFwdReceivedDamageDraw(effectText, source);
        if (result != null) return result;

        result = tryParseElementChange(effectText, source);
        if (result != null) return result;

        result = tryParseDelayedEffect(effectText);
        if (result != null) return result;

        result = tryParsePlayerCannotCastSummons(effectText);
        if (result != null) return result;

        result = tryParseCannotBeChosenStandalone(effectText, source);
        if (result != null) return result;

        result = tryParseCannotBecomeDullOpp(effectText, source);
        if (result != null) return result;

        result = tryParseCannotBeReturnedToHandOpp(effectText, source);
        if (result != null) return result;

        result = tryParseCharactersCannotBeReturnedToHandOpp(effectText);
        if (result != null) return result;

        result = tryParseCannotBePutIntoBzOpp(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneCannotAttackOrBlock(effectText, source);
        if (result != null) return result;

        result = tryParseNegateAllDamage(effectText);
        if (result != null) return result;

        result = tryParsePlayerNextDamageZeroRedirect(effectText);
        if (result != null) return result;

        result = tryParsePlayerNextDamageZero(effectText);
        if (result != null) return result;

        result = tryParseCancelAutoAbilityAndDamageIfForward(effectText);
        if (result != null) return result;

        result = tryParseRedirectAbilityTarget(effectText);
        if (result != null) return result;

        result = tryParseCancelAbilityOnStack(effectText);
        if (result != null) return result;

        result = tryParseCancelChosenTargetUnlessPay(effectText);
        if (result != null) return result;

        result = tryParseCancelChosenTargetUnlessDiscard(effectText);
        if (result != null) return result;

        result = tryParseCancelChosenTargetBare(effectText);
        if (result != null) return result;

        result = tryParseIfOppNotPayAction(effectText);
        if (result != null) return result;

        result = tryParseCancelChosenRevealTopIfType(effectText);
        if (result != null) return result;

        result = tryParseCancelChosenMillTopIfNotType(effectText);
        if (result != null) return result;

        result = tryParseCancelSummonTargetingMyCharacter(effectText);
        if (result != null) return result;

        result = tryParseCancelStackEntry(effectText);
        if (result != null) return result;

        result = tryParseDullAllOppFwdsPowerLeSource(effectText, source);
        if (result != null) return result;

        result = tryParseRevealTopBreakSameCostAddToHand(effectText);
        if (result != null) return result;

        result = tryParseAllFieldEffect(effectText);
        if (result != null) return result;

        result = tryParseFieldPowerGrantPassive(effectText);
        if (result != null) return result;

        result = tryParseAllForwardsSameElementAsNamedPowerBoost(effectText);
        if (result != null) return result;

        result = tryParsePartyForwardsPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseAllFieldPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseAllFieldJobCardNamePowerBoost(effectText);
        if (result != null) return result;

        result = tryParseTwoCardNamesPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseAllFieldJobPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseAllFieldJobKeywordGrant(effectText);
        if (result != null) return result;

        result = tryParseAllFieldKeywordGrant(effectText);
        if (result != null) return result;

        result = tryParseUntilEotDualPowerShift(effectText);
        if (result != null) return result;

        result = tryParseUntilEotAllFieldPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseReturnAllToHand(effectText);
        if (result != null) return result;

        result = tryParseStandalonePowerBoostAndAttackTrigger(effectText, source);
        if (result != null) return result;

        result = tryParseStandalonePowerBoostAndCannotBeChosen(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneGainsTraitsAndCannotBeBlocked(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneGainsTraitsAndCannotBeBlockedTrailing(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneGainsCannotBeBlocked(effectText, source);
        if (result != null) return result;

        result = tryParseSelfBasePowerBecomesUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandalonePowerBoostUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDoublePowerUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDoublesItsPowerUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDoublePowerMainPhaseNextTurn(effectText, source);
        if (result != null) return result;

        result = tryParseStandalonePowerReduceUntil(effectText, source);
        if (result != null) return result;

        result = tryParseFieldSelfPowerBoost(effectText, source);
        if (result != null) return result;

        result = tryParseDoubleOutgoingDamageThisTurn(effectText, source);
        if (result != null) return result;

        result = tryParseDoubleOutgoingDamageThisTurnAlt(effectText, source);
        if (result != null) return result;

        result = tryParseSelfOutgoingDmgBoostThisTurn(effectText, source);
        if (result != null) return result;

        result = tryParseGainOutgoingDmgBoostUntilEot(effectText, source);
        if (result != null) return result;

        result = tryParseGainsQuotedFieldAbilityUntilEot(effectText, source);
        if (result != null) return result;

        result = tryParseGainsQuotedAbilitiesPermanent(effectText, source);
        if (result != null) return result;

        result = tryParseUntilEotGainsPowerTraitsAndQuoted(effectText, source);
        if (result != null) return result;

        result = tryParseDoubleOpponentIncomingDamageThisTurn(effectText);
        if (result != null) return result;

        result = tryParseAllForwardIncomingDmgIncreaseThisTurn(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardDoubleIncomingThisTurn(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardDoubleNextOutgoing(effectText);
        if (result != null) return result;

        result = tryParseDoublePlayerAbilityOutgoingThisTurn(effectText);
        if (result != null) return result;

        result = tryParseStandaloneSelfBoostForEachCrystal(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneItPowerBoostUntil(effectText, source);
        if (result != null) return result;

        result = tryParseSelfPowerBoostAndActivate(effectText, source);
        if (result != null) return result;

        result = tryParseIfHandSizeSelfBoost(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneSelfBoost(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneSelfDullAndShield(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneSelfDull(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneShieldCannotBeBroken(effectText, source);
        if (result != null) return result;

        result = tryParseAllOwnForwardsNullifyAbilityDamage(effectText);
        if (result != null) return result;

        result = tryParseOwnJobOrNameNullifyAbilityDamage(effectText);
        if (result != null) return result;

        result = tryParseDoublecastFreeSummons(effectText);
        if (result != null) return result;

        result = tryParseCastRfgCostCardThisTurn(effectText);
        if (result != null) return result;

        result = tryParseChooseCardRemovedBySourceToBz(effectText, source);
        if (result != null) return result;

        result = tryParseAllForwardsCannotBlock(effectText);
        if (result != null) return result;

        result = tryParseForwardsOfCostCannotBlock(effectText);
        if (result != null) return result;

        result = tryParseEndOfNextTurnIfCardOnFieldOppLoses(effectText);
        if (result != null) return result;

        result = tryParseOppFwdsCannotBlockInferiorPower(effectText);
        if (result != null) return result;

        result = tryParseAllFwdsBlockedOnlyByLowerCostThisTurn(effectText);
        if (result != null) return result;

        result = tryParseOppFwdsLoseAllAbilitiesEot(effectText);
        if (result != null) return result;

        result = tryParseOppFwdPowerBoostSuppressedThisTurn(effectText);
        if (result != null) return result;

        result = tryParseOppFwdsLosePowerPerPlayCost(effectText);
        if (result != null) return result;

        result = tryParseStandaloneCannotBeBlocked(effectText, source);
        if (result != null) return result;

        result = tryParseRevealSelectHandRfp(effectText);
        if (result != null) return result;

        result = tryParseOpponentRandomHandRfp(effectText);
        if (result != null) return result;

        result = tryParseOpponentRandomHandToBottomDeck(effectText);
        if (result != null) return result;

        result = tryParseOpponentHandRfp(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNAddUpToExcludingNameRestBz(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNTypeToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNCategoryToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNJobOrNameToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNElementToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(effectText);
        if (result != null) return result;

        result = tryParseReturnNamedToHand(effectText);
        if (result != null) return result;

        result = tryParseYouMayRemoveNamedFromGame(effectText, source);
        if (result != null) return result;

        result = tryParseEndOfOppTurnPlayNamedOntoField(effectText);
        if (result != null) return result;

        result = tryParseRemoveAllOppBzFromGame(effectText);
        if (result != null) return result;

        result = tryParseRemoveNamedFromGame(effectText, source);
        if (result != null) return result;

        result = tryParseBreakSourceCard(effectText, source);
        if (result != null) return result;

        result = tryParsePutSourceIntoBreakZone(effectText, source);
        if (result != null) return result;

        result = tryParseBreaksAfterCombatNoDamage(effectText, source);
        if (result != null) return result;

        result = tryParseYouMayPutSelfToBZWhenDoSo(effectText, source);
        if (result != null) return result;

        result = tryParseIfOppNoForwardsPutToBreakZone(effectText, source);
        if (result != null) return result;

        result = tryParseIfEitherPlayerNoForwardsPutSourceToBz(effectText, source);
        if (result != null) return result;

        result = tryParseIfSelfDamagePointsPutToBreakZone(effectText, source);
        if (result != null) return result;

        result = tryParsePutSourceToBottomOfDeck(effectText, source);
        if (result != null) return result;

        result = tryParseBreakBlockingForward(effectText);
        if (result != null) return result;

        result = tryParseBreakForwardThatBlocksCard(effectText);
        if (result != null) return result;

        result = tryParseChooseExBurstFromDamageZone(effectText);
        if (result != null) return result;

        result = tryParseDamageZoneSwap(effectText);
        if (result != null) return result;

        result = tryParseOpponentDrawThenRandomDiscard(effectText);
        if (result != null) return result;

        result = tryParseOpponentDraw(effectText);
        if (result != null) return result;

        result = tryParseOpponentRandomDiscard(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerSelectForwardDamage(effectText);
        if (result != null) return result;

        result = tryParseBothPlayersSelectForwardToBreakZone(effectText);
        if (result != null) return result;

        result = tryParseSelectCharCostLeExclToBz(effectText);
        if (result != null) return result;

        result = tryParseSelectControlledCharacterToBz(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerSelectUpToNToBreakZone(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerDiscard(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerSalvageFromBreakZone(effectText);
        if (result != null) return result;

        result = tryParseSelectCharacterFromBzToHand(effectText);
        if (result != null) return result;

        result = tryParseChooseWarpCardFromBzToHand(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerDraw(effectText);
        if (result != null) return result;

        result = tryParseNameCardTypeOpponentDiscardDrawIfMatch(effectText);
        if (result != null) return result;

        result = tryParseOpponentDiscard(effectText);
        if (result != null) return result;

        result = tryParseDiscardHandThenDraw(effectText);
        if (result != null) return result;

        result = tryParseDrawThenPlaceHandToBottom(effectText);
        if (result != null) return result;

        result = tryParsePlaceUpToHandToBottomThenRedraw(effectText);
        if (result != null) return result;

        result = tryParsePayCpWhenDoSo(effectText, source);
        if (result != null) return result;

        result = tryParseDrawDiscardRetriggerIfCardName(effectText, source);
        if (result != null) return result;

        result = tryParseDrawOnePerForwardCapped(effectText);
        if (result != null) return result;

        result = tryParseDrawCards(effectText);
        if (result != null) return result;

        result = tryParseYouMayDiscardType(effectText);
        if (result != null) return result;

        result = tryParseDiscardElementFromHand(effectText);
        if (result != null) return result;

        result = tryParseMayRevealElementFromHand(effectText);
        if (result != null) return result;

        result = tryParseDiscardHand(effectText);
        if (result != null) return result;

        result = tryParseDiscardNCards(effectText);
        if (result != null) return result;

        result = tryParseDiscardJobFromHand(effectText);
        if (result != null) return result;

        result = tryParseDiscardThenDraw(effectText);
        if (result != null) return result;

        result = tryParseDealPlayerDamageToOpponent(effectText);
        if (result != null) return result;

        result = tryParseDealPlayerDamageToSelf(effectText);
        if (result != null) return result;

        result = tryParseRandomRevealHandCastIfSummonFree(effectText);
        if (result != null) return result;

        result = tryParseCastSummonFromHandDiscounted(effectText);
        if (result != null) return result;

        result = tryParseCastSummonFromHandFree(effectText, xValue);
        if (result != null) return result;

        result = tryParseSearchAndCastSummonFree(effectText);
        if (result != null) return result;

        result = tryParsePlayAnyNumberFromHand(effectText, source);
        if (result != null) return result;

        result = tryParsePlayFromHand(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfRfpCount(effectText, source);
        if (result != null) return result;

        result = tryParseOpponentSelects(effectText);
        if (result != null) return result;

        result = tryParseBzFwdToHandOppFwdToBzByDamage(effectText);
        if (result != null) return result;

        result = tryParseOpponentPutsForwardToBreakZone(effectText);
        if (result != null) return result;

        result = tryParseOpponentMillIfSameElementDraw(effectText);
        if (result != null) return result;

        result = tryParseOpponentMill(effectText);
        if (result != null) return result;

        result = tryParseSelfMill(effectText);
        if (result != null) return result;

        result = tryParseOpponentRevealHand(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerRevealCharacterMayPlay(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerMaySearchForwardMinPower(effectText);
        if (result != null) return result;

        result = tryParseRevealTopDeck(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDamageShields(effectText, source);
        if (result != null) return result;

        result = tryParseDualSearchJobAndTypeDontShareElements(effectText);
        if (result != null) return result;

        result = tryParseSearchElementOrCategoryCharsDiffCost(effectText);
        if (result != null) return result;

        result = tryParseSearchNElementSummonsDiffCost(effectText);
        if (result != null) return result;

        result = tryParseSearchDeck(effectText, source, xValue);
        if (result != null) return result;

        result = tryParsePlayAllByNameFromBreakZone(effectText);
        if (result != null) return result;

        result = tryParsePlaySourceFromBreakZone(effectText, source);
        if (result != null) return result;

        result = tryParsePlaySourceOntoField(effectText, source);
        if (result != null) return result;

        result = tryParseActivateNamedCard(effectText);
        if (result != null) return result;

        result = tryParseAttackOnceMore(effectText);
        if (result != null) return result;

        result = tryParseOpponentAttackOnceThisTurn(effectText);
        if (result != null) return result;

        result = tryParseOpponentCannotSearchThisTurn(effectText);
        if (result != null) return result;

        result = tryParseRemoveFromBattle(effectText);
        if (result != null) return result;

        result = tryParseChooseSummonFromBzToHandWithCostReduction(effectText);
        if (result != null) return result;

        result = tryParseChooseNSummonsBzPickOneHandRestRfg(effectText);
        if (result != null) return result;

        result = tryParseChooseWarpCardRemoveCounter(effectText);
        if (result != null) return result;

        result = tryParseChooseWarpCardMayRemoveCounter(effectText);
        if (result != null) return result;

        result = tryParseChooseSummonInBzCastable(effectText);
        if (result != null) return result;

        result = tryParseCostReductionThisTurn(effectText);
        if (result != null) return result;

        result = tryParsePlayCostReductionThisTurn(effectText);
        if (result != null) return result;

        result = tryParseExtraTurnThenLose(effectText);
        if (result != null) return result;

        result = tryParseGainCrystal(effectText);
        if (result != null) return result;

        result = tryParseGainCrystalIfOpponentHas(effectText);
        if (result != null) return result;

        result = tryParsePlaceCountersForEach(effectText, source);
        if (result != null) return result;

        result = tryParsePlaceCounters(effectText, source);
        if (result != null) return result;

        result = tryParseRemoveAllCounters(effectText, source);
        if (result != null) return result;

        result = tryParseLookTopDeckOptionallyBreak(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckBottomOrKeep(effectText);
        if (result != null) return result;

        result = tryParseCounterScaleLookAddToHand(effectText, xValue);
        if (result != null) return result;

        result = tryParseLookTopDeckAddToHandRestBottom(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckAddToHandOneToBreakRestBottom(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckAddToHandRestBreak(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckTopOrBottom(effectText, source);
        if (result != null) return result;

        result = tryParseLookTopDeckReturnTopOrdered(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckPickOneTopRestBottom(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckCastSummonFreeRestBottom(effectText, xValue);
        if (result != null) return result;

        result = tryParseLookTopDeckPeek(effectText);
        if (result != null) return result;

        result = tryParseRemoveTopOfDeckFromGame(effectText, source);
        if (result != null) return result;

        result = tryParseAddRemovedByPreviousEffectToHand(effectText, source);
        if (result != null) return result;

        result = tryParseRevealPlayNamedWithMaxCostRestBottom(effectText);
        if (result != null) return result;

        result = tryParseRevealPlayNamedOrJobMaxCostRestBottom(effectText);
        if (result != null) return result;

        result = tryParseFlipUntilTypeToHandRestShuffleBottom(effectText);
        if (result != null) return result;

        result = tryParseShuffleThenRevealPlayNamedRestBottom(effectText, source);
        if (result != null) return result;

        result = tryParseRevealPlayTypeOntoFieldRestBottom(effectText);
        if (result != null) return result;

        result = tryParseRevealElementCardFromHandIfSoDraw(effectText);
        if (result != null) return result;

        result = tryParseRevealPlayElementTypeCostOntoFieldRestBottom(effectText, xValue);
        if (result != null) return result;

        result = tryParseShuffleDeck(effectText);
        if (result != null) return result;

        result = tryParseBackupCpDraw(effectText);
        if (result != null) return result;

        result = tryParseNameElementOnlySelfBecomes(effectText, source);
        if (result != null) return result;

        result = tryParseNameElementAndJobSelfBecomes(effectText, source);
        if (result != null) return result;

        result = tryParseNameJobAndElementSelfGainsPermanent(effectText, source);
        if (result != null) return result;

        result = tryParseNameJobOrElementAllForwardsBoost(effectText);
        if (result != null) return result;

        result = tryParseNameJobOrCategoryRevealAddToHand(effectText);
        if (result != null) return result;

        result = tryParseNameJob(effectText);
        if (result != null) return result;

        result = tryParseGrantPartyAnyElementThisTurn(effectText);
        if (result != null) return result;

        result = tryParseSourcePowerBecomesRemovedForwardPower(effectText, source);
        if (result != null) return result;

        result = tryParseSourcePowerBecomesOpponentWeakestForward(effectText, source);
        if (result != null) return result;

        result = tryParseOpponentGainsControlOfSource(effectText, source);
        if (result != null) return result;

        // Compound-sentence fallback: split on ". " between sentences and compose effects.
        // Handles "Activate <cardName>. <cardName> gains +2000 power until the end of the turn." etc.
        // Sentences that don't parse are silently skipped so that implemented parts still fire.
        String[] sentences = effectText.split("(?<=\\.)\\s+(?=[A-Z])");
        if (sentences.length > 1) {
            List<Consumer<GameContext>> consumers = new ArrayList<>();
            for (String s : sentences) {
                String trimmed = s.trim().replaceAll("(?i)^Then\\s+", "");
                Consumer<GameContext> c = parse(trimmed, source, xValue);
                if (c != null) consumers.add(c);
            }
            if (!consumers.isEmpty()) return ctx -> consumers.forEach(c -> c.accept(ctx));
        }

        result = tryParseConditionalOpponentHand(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseConditionalOpponentHandMin(effectText, source, xValue);
        if (result != null) return result;

        if (CardData.HAS_ALL_ELEMENTS_PATTERN.matcher(effectText.trim()).matches()) return ctx -> {};

        result = tryParseMultiPlayGrant(effectText);
        if (result != null) return result;

        result = tryParseLightDarkDiscardCpGrant(effectText);
        if (result != null) return result;

        return null;
    }



    /** Returns the name of the first pattern that matches {@code effectText}, or {@code null}. */
    public static String matchedPatternName(String effectText, CardData source) {
        // Mirrors parse(): the pay-or-else gate is reported ahead of its consequence's own pattern.
        if (tryParseIfNotPayOrElse(effectText, source, 0)               != null) return "IfNotPayOrElse";
        if (tryParseRemoveTopThenPileThreshold(effectText, source)          != null) return "RemoveTopThenPileThreshold";
        if (tryParseAddRemovedBySourceAbilityToHand(effectText, source)     != null) return "AddRemovedBySourceAbilityToHand";
        if (tryParseOppRfpTopDeckCastable(effectText)                   != null) return "OppRfpTopDeckCastable";
        if (tryParseChooseFromOppBzCastable(effectText)                 != null) return "ChooseFromOppBzCastable";
        if (tryParseChooseSummonsFromBzCastable(effectText)             != null) return "ChooseSummonsFromBzCastable";
        if (tryParseChooseSummonInBzMaxCostFreeCastRfg(effectText)      != null) return "ChooseSummonInBzMaxCostFreeCastRfg";
        if (tryParseSelectNumber(effectText, source)                    != null) return "SelectNumber";
        if (tryParseForEachJobAndNameDealDamageToForwards(effectText)   != null) return "ForEachJobAndNameDealDamageToForwards";
        if (tryParseDealNForEachJobOrNameToOppForwards(effectText)      != null) return "DealNForEachJobOrNameToOppForwards";
        if (tryParseSelfGainsWhenAttacksEOT(effectText, source)        != null) return "SelfGainsWhenAttacksEOT";
        if (tryParseDealDamageToForwardsForEach(effectText)             != null) return "DealDamageToForwardsForEach";
        if (tryParseDealDamageToForwardsExceptElement(effectText)       != null) return "DealDamageToForwardsExceptElement";
        if (tryParseDealDamageToForwards(effectText)                    != null) return "DealDamageToForwards";
        if (tryParseDivideDamageEquallyAmongAll(effectText)             != null) return "DivideDamageEquallyAmongAll";
        if (tryParseNoForwardCostCannotAttack(effectText)               != null) return "NoForwardCostCannotAttack";
        if (tryParseOwnForwardsCannotBeChosenByExBurst(effectText)      != null) return "OwnForwardsCannotBeChosenByExBurst";
        if (tryParseExBurstSuppression(effectText)                      != null) return "ExBurstSuppression";
        if (tryParseDealHalfPowerDamageToForwards(effectText)           != null) return "DealHalfPowerDamageToForwards";
        if (tryParseDealPowerMinusNDamageToForwards(effectText)         != null) return "DealPowerMinusNDamageToForwards";
        if (tryParseDealHalfSourcePowerDamageToForwards(effectText)     != null) return "DealHalfSourcePowerDamageToForwards";
        if (tryParseDamageToCombatBlocker(effectText)                   != null) return "DamageToCombatBlocker";
        if (tryParseChooseOppFwdDynCostBreak(effectText)                   != null) return "ChooseOppFwdDynCostBreak";
        if (tryParseChooseFwdPowerInferiorToSource(effectText, source)     != null) return "ChooseFwdPowerInferiorToSource";
        if (tryParseChooseFwdBzCostInferiorToRemovedPlay(effectText)       != null) return "ChooseFwdBzCostInferiorToRemovedPlay";
        if (tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(effectText, source) != null) return "ChooseOppFwdGainsSpecialAbilityFreeOnce";
        if (tryParseUseSpecialAbilityUsedThisTurn(effectText, source) != null) return "UseSpecialAbilityUsedThisTurn";
        if (tryParseChooseOppDamagedFwdIfHasAbilityBreak(effectText)     != null) return "ChooseOppDamagedFwdIfHasAbilityBreak";
        if (tryParseChooseAsManyAsFieldCount(effectText, source)         != null) return "ChooseAsManyAsFieldCount";
        if (tryParseChooseAsManyAsBzRfgJobCount(effectText)             != null) return "ChooseAsManyAsBzRfgJobCount";
        if (tryParseChooseCounterScaleCharsActivate(effectText, 1)    != null) return "ChooseCounterScaleCharsActivate";
        if (tryParseChooseAnyNumberReturnToHand(effectText)    != null) return "ChooseAnyNumberReturnToHand";
        if (tryParseCancelStackEntryUnlessPay(effectText)      != null) return "CancelStackEntryUnlessPay";
        if (tryParseChooseFwdRevealCostParity(effectText)             != null) return "ChooseFwdRevealCostParity";
        if (tryParseChooseForwardsGainAbilityEot(effectText)          != null) return "ChooseForwardsGainAbilityEot";
        if (tryParseChooseForwardPlacePetrification(effectText)       != null) return "ChooseForwardPlacePetrification";
        if (tryParseRemoveAllCountersFromSelf(effectText, source)     != null) return "RemoveAllCountersFromSelf";
        if (tryParseChooseCharacter(effectText, source, 0)              != null) return "ChooseCharacter";
        if (tryParseIfSelfFwdReceivedDamageDraw(effectText, source)          != null) return "IfSelfFwdReceivedDamageDraw";
        if (tryParseIfRfpCount(effectText, source)               != null) return "IfRfpCount";
        if (tryParseElementChange(effectText, source) != null) return "ElementChange";
        if (tryParseDelayedEffect(effectText)                 != null) return "DelayedEffect";
        if (tryParsePlayerCannotCastSummons(effectText)                != null) return "PlayerCannotCastSummons";
        if (tryParseCannotBeChosenStandalone(effectText, source) != null) return "CannotBeChosen";
        if (tryParseCannotBecomeDullOpp(effectText, source) != null)     return "CannotBecomeDullOpp";
        if (tryParseCannotBeReturnedToHandOpp(effectText, source) != null) return "CannotBeReturnedToHandOpp";
        if (tryParseCharactersCannotBeReturnedToHandOpp(effectText) != null) return "CharactersCannotBeReturnedToHandOpp";
        if (tryParseCannotBePutIntoBzOpp(effectText, source) != null)    return "CannotBePutIntoBzOpp";
        if (tryParseChooseOwnFwdBoostProtectionsOrAllIfDmg(effectText) != null) return "ChooseOwnFwdBoostProtectionsOrAllIfDmg";
        if (tryParseActivateAllOwnFwdsGainProtections(effectText) != null) return "ActivateAllOwnFwdsGainProtections";
        if (tryParseStandaloneCannotAttackOrBlock(effectText, source) != null) return "CannotAttackOrBlock";
        if (tryParseNegateAllDamage(effectText)                != null) return "NegateDamage";
        if (tryParsePlayerNextDamageZeroRedirect(effectText)   != null) return "PlayerNextDamageZeroRedirect";
        if (tryParsePlayerNextDamageZero(effectText)           != null) return "PlayerNextDamageZero";
        if (tryParseCancelAutoAbilityAndDamageIfForward(effectText) != null) return "CancelAutoAbilityAndDamageIfForward";
        if (tryParseCancelStackEntry(effectText)               != null) return "CancelSummonOrAutoAbility";
        if (tryParseRedirectAbilityTarget(effectText)          != null) return "RedirectAbilityTarget";
        if (tryParseCancelAbilityOnStack(effectText)           != null) return "CancelAbilityOnStack";
        if (tryParseCancelChosenTargetUnlessPay(effectText)    != null) return "CancelChosenTargetUnlessPay";
        if (tryParseCancelChosenTargetUnlessDiscard(effectText) != null) return "CancelChosenTargetUnlessDiscard";
        if (tryParseCancelChosenTargetBare(effectText)         != null) return "CancelChosenTargetBare";
        if (tryParseIfOppNotPayAction(effectText)             != null) return "IfOppNotPayAction";
        if (tryParseCancelChosenRevealTopIfType(effectText)    != null) return "CancelChosenRevealTopIfType";
        if (tryParseCancelChosenMillTopIfNotType(effectText)   != null) return "CancelChosenMillTopIfNotType";
        if (tryParseCancelSummonTargetingMyCharacter(effectText) != null) return "CancelSummonTargetingMyCharacter";
        if (tryParseSelectNumber(effectText, source)          != null) return "SelectNumber";
        if (tryParseDullAllOppFwdsPowerLeSource(effectText, source)        != null) return "DullAllOppFwdsPowerLeSource";
        if (tryParseRevealTopBreakSameCostAddToHand(effectText)           != null) return "RevealTopBreakSameCostAddToHand";
        if (tryParseAllFieldEffect(effectText)                != null) return "AllFieldEffect";
        if (tryParseFieldPowerGrantPassive(effectText)        != null) {
            String trimmed = effectText.trim();
            return FIELD_OPPONENT_DEBUFF_PASSIVE.matcher(trimmed).matches()
                    ? "FieldOpponentPowerDebuff" : "FieldPowerGrant";
        }
        if (tryParseStandalonePowerBoostAndAttackTrigger(effectText, source) != null) return "StandalonePowerBoostAndAttackTrigger";
        if (tryParseStandalonePowerBoostAndCannotBeChosen(effectText, source) != null) return "StandalonePowerBoostAndCannotBeChosen";
        if (tryParseStandaloneGainsTraitsAndCannotBeBlocked(effectText, source) != null) return "StandaloneGainsTraitsAndCannotBeBlocked";
        if (tryParseStandaloneGainsTraitsAndCannotBeBlockedTrailing(effectText, source) != null) return "StandaloneGainsTraitsAndCannotBeBlockedTrailing";
        if (tryParseStandaloneGainsCannotBeBlocked(effectText, source) != null) return "StandaloneGainsCannotBeBlocked";
        if (tryParseSelfBasePowerBecomesUntil(effectText, source) != null) return "SelfBasePowerBecomesUntil";
        if (tryParseStandalonePowerBoostUntil(effectText, source) != null) return "StandalonePowerBoostUntil";
        if (tryParseStandaloneDoublePowerUntil(effectText, source) != null) return "StandaloneDoublePowerUntil";
        if (tryParseStandaloneDoublesItsPowerUntil(effectText, source) != null) return "StandaloneDoublesItsPowerUntil";
        if (tryParseStandaloneDoublePowerMainPhaseNextTurn(effectText, source) != null) return "StandaloneDoublePowerMainPhaseNextTurn";
        if (tryParseStandalonePowerReduceUntil(effectText, source) != null) return "StandalonePowerReduceUntil";
        if (tryParseFieldSelfPowerBoost(effectText, source)    != null) return "FieldSelfPowerBoost";
        if (tryParseDoubleOutgoingDamageThisTurn(effectText, source) != null)    return "DoubleOutgoingDamageThisTurn";
        if (tryParseDoubleOutgoingDamageThisTurnAlt(effectText, source) != null) return "DoubleOutgoingDamageThisTurnAlt";
        if (tryParseSelfOutgoingDmgBoostThisTurn(effectText, source) != null)   return "SelfOutgoingDmgBoostThisTurn";
        if (tryParseGainOutgoingDmgBoostUntilEot(effectText, source) != null)   return "GainOutgoingDmgBoostUntilEot";
        if (tryParseGainsQuotedFieldAbilityUntilEot(effectText, source) != null) return "GainsQuotedFieldAbilityUntilEot";
        if (tryParseGainsQuotedAbilitiesPermanent(effectText, source) != null)  return "GainsQuotedAbilitiesPermanent";
        if (tryParseUntilEotGainsPowerTraitsAndQuoted(effectText, source) != null) return "UntilEotGainsPowerTraitsAndQuoted";
        if (tryParseDoubleOpponentIncomingDamageThisTurn(effectText) != null)   return "DoubleOpponentIncomingDamageThisTurn";
        if (tryParseAllForwardIncomingDmgIncreaseThisTurn(effectText) != null)  return "AllForwardIncomingDmgIncreaseThisTurn";
        if (tryParseChooseForwardDoubleIncomingThisTurn(effectText) != null)    return "ChooseForwardDoubleIncomingThisTurn";
        if (tryParseChooseForwardDoubleNextOutgoing(effectText) != null)        return "ChooseForwardDoubleNextOutgoing";
        if (tryParseDoublePlayerAbilityOutgoingThisTurn(effectText) != null)   return "DoublePlayerAbilityOutgoingThisTurn";
        if (tryParseStandaloneSelfBoostForEachCrystal(effectText, source) != null) return "StandaloneSelfBoostForEachCrystal";
        if (tryParseIfHandSizeSelfBoost(effectText, source)               != null) return "IfHandSizeSelfBoost";
        if (tryParseStandaloneSelfBoost(effectText, source)   != null) return "StandaloneSelfBoost";
        if (tryParseStandaloneSelfDullAndShield(effectText, source) != null) return "StandaloneSelfDullAndShield";
        if (tryParseStandaloneSelfDull(effectText, source) != null)          return "StandaloneSelfDull";
        if (tryParseStandaloneShieldCannotBeBroken(effectText, source) != null) return "StandaloneShieldCannotBeBroken";
        if (tryParseAllOwnForwardsNullifyAbilityDamage(effectText)        != null) return "AllOwnForwardsNullifyAbilityDamage";
        if (tryParseOwnJobOrNameNullifyAbilityDamage(effectText)          != null) return "OwnJobOrNameNullifyAbilityDamage";
        if (tryParseDoublecastFreeSummons(effectText)                     != null) return "DoublecastFreeSummons";
        if (tryParseCastRfgCostCardThisTurn(effectText)                   != null) return "CastRfgCostCardThisTurn";
        if (tryParseChooseCardRemovedBySourceToBz(effectText, source)     != null) return "ChooseCardRemovedBySourceToBz";
        if (tryParseAllForwardsCannotBlock(effectText)                    != null) return "AllForwardsCannotBlock";
        if (tryParseForwardsOfCostCannotBlock(effectText)                 != null) return "ForwardsOfCostCannotBlock";
        if (tryParseEndOfNextTurnIfCardOnFieldOppLoses(effectText)        != null) return "EndOfNextTurnIfCardOnFieldOppLoses";
        if (tryParseOppFwdsCannotBlockInferiorPower(effectText)           != null) return "OppFwdsCannotBlockInferiorPower";
        if (tryParseAllFwdsBlockedOnlyByLowerCostThisTurn(effectText)    != null) return "AllFwdsBlockedOnlyByLowerCost";
        if (tryParseOppFwdsLoseAllAbilitiesEot(effectText)         != null) return "OppFwdsLoseAllAbilitiesEot";
        if (tryParseOppFwdPowerBoostSuppressedThisTurn(effectText) != null) return "OppFwdPowerBoostSuppressedThisTurn";
        if (tryParseOppFwdsLosePowerPerPlayCost(effectText)        != null) return "OppFwdsLosePowerPerPlayCost";
        if (tryParseStandaloneGainsCannotBeBlocked(effectText, source) != null) return "StandaloneGainsCannotBeBlocked";
        if (tryParseStandaloneCannotBeBlocked(effectText, source) != null) return "StandaloneCannotBeBlocked";
        if (tryParseRevealHandOptPickRfpOppDraw(effectText)    != null) return "RevealHandOptPickRfpOppDraw";
        if (tryParseRevealSelectHandRfp(effectText)            != null) return "RevealSelectHandRfp";
        if (tryParseOpponentRandomHandRfp(effectText)            != null) return "OpponentRandomHandRfp";
        if (tryParseOpponentRandomHandToBottomDeck(effectText)   != null) return "OpponentRandomHandToBottomDeck";
        if (tryParseOpponentHandRfp(effectText)               != null) return "OpponentHandRfp";
        if (tryParseYouMayRemoveNamedFromGame(effectText, source) != null) return "YouMayRemoveNamedFromGame";
        if (tryParseEndOfOppTurnPlayNamedOntoField(effectText) != null) return "EndOfOppTurnPlayNamedOntoField";
        if (tryParseRemoveAllOppBzFromGame(effectText)         != null) return "RemoveAllOppBzFromGame";
        if (tryParseRemoveNamedFromGame(effectText, source)   != null) return "RemoveNamedFromGame";
        if (tryParseBreakSourceCard(effectText, source)        != null) return "BreakSourceCard";
        if (tryParsePutSourceIntoBreakZone(effectText, source) != null) return "PutSourceIntoBreakZone";
        if (tryParseBreaksAfterCombatNoDamage(effectText, source) != null) return "BreaksAfterCombatNoDamage";
        if (tryParseYouMayPutSelfToBZWhenDoSo(effectText, source)    != null) return "YouMayPutSelfToBZWhenDoSo";
        if (tryParseIfOppNoForwardsPutToBreakZone(effectText, source)          != null) return "IfOppNoForwardsPutToBreakZone";
        if (tryParseIfEitherPlayerNoForwardsPutSourceToBz(effectText, source)  != null) return "IfEitherPlayerNoForwardsPutSourceToBz";
        if (tryParseIfSelfDamagePointsPutToBreakZone(effectText, source)      != null) return "IfSelfDamagePointsPutToBreakZone";
        if (tryParsePutSourceToBottomOfDeck(effectText, source) != null) return "PutSourceToBottomOfDeck";
        if (tryParseBreakBlockingForward(effectText)           != null) return "BreakBlockingForward";
        if (tryParseBreakForwardThatBlocksCard(effectText)     != null) return "BreakForwardThatBlocksCard";
        if (tryParseChooseExBurstFromDamageZone(effectText)    != null) return "ChooseExBurstFromDamageZone";
        if (tryParseExBurstSuppression(effectText)             != null) return "ExBurstSuppression";
        if (tryParseDamageZoneSwap(effectText)                 != null) {
            Matcher m = DAMAGE_ZONE_SWAP_PATTERN.matcher(effectText.trim());
            return m.matches() && m.group("draw") != null ? "DamageZoneSwap + DrawCards" : "DamageZoneSwap";
        }
        if (tryParseOpponentDrawThenRandomDiscard(effectText)  != null) return "OpponentDrawThenRandomDiscard";
        if (tryParseOpponentDraw(effectText)                   != null) return "OpponentDraw";
        if (tryParseOpponentRandomDiscard(effectText)         != null) return "OpponentRandomDiscard";
        if (tryParseEachPlayerSelectForwardDamage(effectText)  != null) return "EachPlayerSelectForwardDamage";
        if (tryParseBothPlayersSelectForwardToBreakZone(effectText) != null) return "BothPlayersSelectForwardToBreakZone";
        if (tryParseSelectCharCostLeExclToBz(effectText)             != null) return "SelectCharCostLeExclToBz";
        if (tryParseSelectControlledCharacterToBz(effectText)        != null) return "SelectControlledCharacterToBz";
        if (tryParseEachPlayerSelectUpToNToBreakZone(effectText)   != null) return "EachPlayerSelectUpToNToBreakZone";
        if (tryParseEachPlayerDiscard(effectText)              != null) return "EachPlayerDiscard";
        if (tryParseEachPlayerSalvageFromBreakZone(effectText) != null) return "EachPlayerSalvageFromBreakZone";
        if (tryParseSelectCharacterFromBzToHand(effectText)    != null) return "SelectCharacterFromBzToHand";
        if (tryParseChooseWarpCardFromBzToHand(effectText)     != null) return "ChooseWarpCardFromBzToHand";
        if (tryParseEachPlayerDraw(effectText)                 != null) return "EachPlayerDraw";
        if (tryParseNameCardTypeOpponentDiscardDrawIfMatch(effectText) != null) return "NameCardTypeOpponentDiscardDrawIfMatch";
        if (tryParseOpponentDiscard(effectText)               != null) return "OpponentDiscard";
        if (tryParseDiscardHandThenDraw(effectText)           != null) return "DiscardHandThenDraw";
        if (tryParseDrawThenPlaceHandToBottom(effectText)     != null) return "DrawThenPlaceHandToBottom";
        if (tryParsePlaceUpToHandToBottomThenRedraw(effectText) != null) return "PlaceUpToHandToBottomThenRedraw";
        if (tryParsePayCpWhenDoSo(effectText, source)         != null) return "PayCpWhenDoSo";
        if (tryParseDrawDiscardRetriggerIfCardName(effectText, source) != null) return "DrawDiscardRetriggerIfCardName";
        if (tryParseDrawCards(effectText)                     != null) return "DrawCards";
        if (tryParseYouMayDiscardType(effectText)             != null) return "YouMayDiscardType";
        if (tryParseMayRevealElementFromHand(effectText)      != null) return "MayRevealElementFromHand";
        if (tryParseDiscardHand(effectText)                   != null) return "DiscardHand";
        if (tryParseDiscardNCards(effectText)                 != null) return "DiscardNCards";
        if (tryParseDiscardJobFromHand(effectText)            != null) return "DiscardJobFromHand";
        if (tryParseDiscardThenDraw(effectText)               != null) return "DiscardThenDraw";
        if (tryParseIfEachPlayerEmptyHand(effectText, source, 0) != null) return "IfEachPlayerEmptyHand";
        if (tryParseDealPlayerDamageToOpponent(effectText)    != null) return "DealPlayerDamageToOpponent";
        if (tryParseDealPlayerDamageToSelf(effectText)        != null) return "DealPlayerDamageToSelf";
        if (tryParseRandomRevealHandCastIfSummonFree(effectText) != null) return "RandomRevealHandCastIfSummonFree";
        if (tryParseCastSummonFromHandDiscounted(effectText)     != null) return "CastSummonFromHandDiscounted";
        if (tryParseCastSummonFromHandFree(effectText, 0)     != null) return "CastSummonFromHandFree";
        if (tryParseSearchAndCastSummonFree(effectText)       != null) return "SearchAndCastSummonFree";
        if (tryParsePlayAnyNumberFromHand(effectText, source) != null) return "PlayAnyNumberFromHand";
        if (tryParsePlayFromHand(effectText, source, 0)       != null) return "PlayFromHand";
        // Checked ahead of OpponentSelects: an "…, X instead." upgrade wraps a base clause the
        // OpponentSelects matcher would otherwise claim on its own, dropping the replacement.
        if (tryParseControlGatedInsteadUpgrade(effectText, source, 0) != null) return "ControlGatedInsteadUpgrade";
        if (tryParseOpponentSelects(effectText)               != null) return "OpponentSelects";
        if (tryParseBzFwdToHandOppFwdToBzByDamage(effectText)  != null) return "BzFwdToHandOppFwdToBzByDamage";
        if (tryParseOpponentPutsForwardToBreakZone(effectText) != null) return "OpponentPutsForwardToBreakZone";
        if (tryParseOpponentMillIfSameElementDraw(effectText)  != null) return "OpponentMillIfSameElementDraw";
        if (tryParseOpponentMill(effectText)                  != null) return "OpponentMill";
        if (tryParseSelfMill(effectText)                      != null) return "SelfMill";
        if (tryParseOpponentRevealHand(effectText)            != null) return "OpponentRevealHand";
        if (tryParseEachPlayerRevealCharacterMayPlay(effectText)      != null) return "EachPlayerRevealMayPlay";
        if (tryParseEachPlayerMaySearchForwardMinPower(effectText)     != null) return "EachPlayerMaySearchForwardMinPower";
        if (tryParseRevealTopDeck(effectText, source)         != null) return "RevealTopDeck";
        if (tryParseStandaloneDamageShields(effectText, source) != null) return "StandaloneDamageShields";
        if (tryParseDualSearchJobAndTypeDontShareElements(effectText)      != null) return "DualSearchDontShareElements";
        if (tryParseSearchElementOrCategoryCharsDiffCost(effectText)       != null) return "SearchElementOrCategoryCharsDiffCost";
        if (tryParseSearchNElementSummonsDiffCost(effectText)              != null) return "SearchNElementSummonsDiffCost";
        if (tryParseSearchDeck(effectText, source, 0)                      != null) return "SearchDeck";
        if (tryParsePlayAllByNameFromBreakZone(effectText)      != null) return "PlayAllByNameFromBreakZone";
        if (tryParsePlaySourceFromBreakZone(effectText, source) != null) return "PlaySourceFromBreakZone";
        if (tryParseActivateNamedCard(effectText)               != null) return "ActivateNamedCard";
        if (tryParseAttackOnceMore(effectText)                  != null) return "AttackOnceMore";
        if (tryParseOpponentCannotSearchThisTurn(effectText)    != null) return "OpponentCannotSearch";
        if (tryParseRemoveFromBattle(effectText)                != null) return "RemoveFromBattle";
        if (tryParseChooseSummonFromBzToHandWithCostReduction(effectText) != null) return "ChooseSummonFromBzToHandWithCostReduction";
        if (tryParseChooseNSummonsBzPickOneHandRestRfg(effectText)        != null) return "ChooseNSummonsBzPickOneHandRestRfg";
        if (tryParseChooseWarpCardRemoveCounter(effectText)               != null) return "ChooseWarpCardRemoveCounter";
        if (tryParseChooseWarpCardMayRemoveCounter(effectText)            != null) return "ChooseWarpCardMayRemoveCounter";
        if (tryParseChooseSummonInBzCastable(effectText)              != null) return "ChooseSummonInBzCastable";
        if (tryParseChooseSummonInBzMaxCostFreeCastRfg(effectText)    != null) return "ChooseSummonInBzMaxCostFreeCastRfg";
        if (tryParseCostReductionThisTurn(effectText)                 != null) return "CostReductionThisTurn";
        if (tryParsePlayCostReductionThisTurn(effectText)        != null) return "PlayCostReductionThisTurn";
        if (CardData.isSelfCostModifierText(effectText))                  return "SelfCostModifier";
        if (CardData.FIELD_OPP_CAST_COST_INCREASE_PATTERN.matcher(effectText).find()) return "OppCastCostIncrease";
        if (AutoAbilityTriggers.FA_DISCARD_JOB_TO_CAST.matcher(effectText).find()) return "DiscardJobToCast";
        if (tryParseExtraTurnThenLose(effectText)               != null) return "ExtraTurnThenLose";
        if (tryParseGainCrystalPerX(effectText, 0)               != null) return "GainCrystalPerX";
        if (tryParseGainCrystal(effectText)                      != null) return "GainCrystal";
        if (tryParseGainCrystalIfOpponentHas(effectText)         != null) return "GainCrystalIfOpponentHas";
        if (tryParsePlaceCountersForEach(effectText, source)     != null) return "PlaceCountersForEach";
        if (tryParsePlaceCounters(effectText, source)            != null) return "PlaceCounters";
        if (tryParseRemoveAllCounters(effectText, source)         != null) return "RemoveAllCounters";
        if (tryParseLookTopDeckOptionallyBreak(effectText)        != null) return "LookTopDeckOptionallyBreak";
        if (tryParseLookTopDeckBottomOrKeep(effectText)           != null) return "LookTopDeckBottomOrKeep";
        if (tryParseCounterScaleLookAddToHand(effectText, 1)               != null) return "CounterScaleLookAddToHand";
        if (tryParseLookTopDeckAddToHandRestBottom(effectText)          != null) return lookAddToHandRestBottomPatternName(effectText);
        if (tryParseLookTopDeckAddToHandOneToBreakRestBottom(effectText) != null) return "LookTopDeckAddToHandOneToBreakRestBottom";
        if (tryParseLookTopDeckAddToHandRestBreak(effectText)           != null) return "LookTopDeckAddToHandRestBreak";
        if (tryParseLookTopDeckTopOrBottom(effectText, source)          != null) {
            String then = trailingThenText(effectText, LOOK_TOP_DECK_TOP_OR_BOTTOM);
            return then == null ? "LookTopDeckTopOrBottom"
                    : "LookTopDeckTopOrBottom + " + matchedPatternName(then, source);
        }
        if (tryParseLookTopDeckReturnTopOrdered(effectText)             != null) return "LookTopDeckReturnTopOrdered";
        if (tryParseLookTopDeckPickOneTopRestBottom(effectText)              != null) return "LookTopDeckPickOneTopRestBottom";
        if (tryParseLookTopDeckCastSummonFreeRestBottom(effectText, 0)       != null) return "LookTopDeckCastSummonFreeRestBottom";
        if (tryParseLookTopDeckPeek(effectText)                              != null) return "LookTopDeckPeek";
        if (tryParseAddRemovedByPreviousEffectToHand(effectText, source)    != null) return "AddRemovedByPreviousEffectToHand";
        if (tryParseRemoveTopOfDeckFromGame(effectText, source)             != null) return "RemoveTopOfDeckFromGame";
        if (tryParseRevealPlayNamedWithMaxCostRestBottom(effectText)         != null) return "RevealPlayNamedWithMaxCostRestBottom";
        if (tryParseRevealPlayNamedOrJobMaxCostRestBottom(effectText)        != null) return "RevealPlayNamedOrJobMaxCostRestBottom";
        if (tryParseFlipUntilTypeToHandRestShuffleBottom(effectText)         != null) return "FlipUntilTypeToHandRestShuffleBottom";
        if (tryParseShuffleDeck(effectText)                                  != null) return "ShuffleDeck";
        if (tryParseIfOwnForwardFormedParty(effectText, source, 0)       != null) return "IfOwnForwardFormedParty";
        if (tryParseIfControlAtMost(effectText, source, 0)             != null) return "IfControlAtMost";
        if (tryParseIfCastAtLeast(effectText, source, 0)               != null) return "IfCastAtLeast";
        if (tryParseIfControlCondOtherThan(effectText, source, 0)      != null) return "IfControlCondOtherThan";
        if (tryParseIfOppControlsNOrMoreCondTypeGate(effectText, source, 0) != null) return "IfOppControlsNOrMoreCondType";
        if (tryParseDiscardConditionalElement(effectText, source, 0)   != null) return "DiscardConditionalElement";
        if (tryParseDiscardConditionalElementSingle(effectText, source, 0) != null) return "DiscardConditionalElementSingle";
        if (tryParseDiscardConditionalTargetLoseAbilities(effectText) != null) return "DiscardConditionalTargetLoseAbilities";
        if (tryParseDiscardConditionalSelfBoostInstead(effectText, source, 0) != null) return "DiscardConditionalSelfBoostInstead";
        if (tryParseDrawDiscardIfMultiElement(effectText) != null) return "DrawDiscardIfMultiElement";
        if (tryParseConditionalOpponentHand(effectText, source, 0)     != null) return "ConditionalOpponentHand";
        if (tryParseConditionalOpponentHandMin(effectText, source, 0) != null) return "ConditionalOpponentHandMin";
        if (tryParseYouMayPutSelfToBZWhenDoSo(effectText, source)    != null) return "YouMayPutSelfToBZWhenDoSo";
        if (SELECT_FOLLOWING_ACTIONS_DETECT.matcher(effectText).find())        return "SelectFollowingActions";
        if (CardData.HAS_ALL_ELEMENTS_PATTERN.matcher(effectText.trim()).matches()) return "HasAllElements";
        if (tryParseMultiPlayGrant(effectText) != null)                         return "MultiPlayGrant";
        if (tryParseLightDarkDiscardCpGrant(effectText) != null)                return "LightDarkDiscardCpGrant";
        return null;
    }

    /**
     * Returns the name of the first followup pattern that matches {@code followupText}, or
     * {@code null} if no followup pattern recognises it.  The ordering mirrors the precedence
     * used inside {@link #tryParseChooseCharacter}.
     */
    public static String matchedFollowupName(String followupText, CardData source) {
        // Strip leading "You may " so optional-followup effects are identified correctly
        if (followupText.toLowerCase(java.util.Locale.ROOT).startsWith("you may "))
            followupText = followupText.substring("You may ".length()).trim();
        if (FOLLOWUP_TARGET_CONTROLLER_DISCARDS.matcher(followupText).matches()) return "TargetControllerDiscards";
        if (source != null) {
            Matcher mutM = FOLLOWUP_MUTUAL_POWER_DAMAGE.matcher(followupText);
            if (mutM.find() && mutM.group("srcname").trim().equalsIgnoreCase(source.name())) return "MutualPowerDamage";
        }
        if (FOLLOWUP_DAMAGE_FOR_EACH_COUNTER.matcher(followupText).find())             return "DamageForEachCounter";
        if (FOLLOWUP_DAMAGE_FOR_EACH.matcher(followupText).find())                    return "DamageForEach";
        if (FOLLOWUP_DULL_AND_DAMAGE.matcher(followupText).find())                   return "DullAndDamage";
        if (FOLLOWUP_FIRST_AND_OTHER.matcher(followupText).find())                    return "FirstAndOther";
        if (FOLLOWUP_DAMAGE_AND_CONTROLLER_DAMAGE.matcher(followupText).find())       return "DamageAndControllerDamage";
        if (FOLLOWUP_DAMAGE.matcher(followupText).find())                             return "Damage";
        if (FOLLOWUP_DAMAGE_EXPR.matcher(followupText).find())                        return "DamageExpr";
        if (FOLLOWUP_DIVIDE_DAMAGE_AMONG_CHOSEN.matcher(followupText).find())         return "DivideDamageAmongChosen";
        if (FOLLOWUP_ACTIVATE_AND_GAIN_CONTROL_EOT.matcher(followupText).find())        return "ActivateAndGainControlEOT";
        // The "If you control N or more …, <action>" gate comes before the plain action checks
        // below, which scan for their verb with find() and would otherwise claim the gated form as
        // unconditional. Guarded on parseTargetAction exactly as the dispatch is, so texts whose
        // action is not a recognised target action still fall through to their own handler.
        Matcher selfCondActionM = FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_ACTION.matcher(followupText);
        if (selfCondActionM.matches()
                && parseTargetAction(selfCondActionM.group("action").trim(), 0) != null)
            return "IfSelfControlsNElementTypeAction";
        if (FOLLOWUP_ACTIVATE_AND_NEGATE_DAMAGE.matcher(followupText).find())          return "ActivateAndNegateDamage";
        if (FOLLOWUP_NEGATE_DAMAGE.matcher(followupText).find())                      return "NegateDamage";
        if (FOLLOWUP_GAIN_CONTROL_WHILE_CARD.matcher(followupText).find())            return "GainControlWhileCard";
        if (FOLLOWUP_GAIN_CONTROL_EOT.matcher(followupText).find())                   return "GainControlEOT";
        if (FOLLOWUP_GAIN_CONTROL.matcher(followupText).find())                       return "GainControl";
        if (FOLLOWUP_SELF_AND_TARGET_GAIN_QUOTE_UNTIL_OPP_TURN.matcher(followupText).find()) return "SelfAndTargetGainUntilOppTurn";
        if (FOLLOWUP_TARGET_NEXT_SPECIAL_FREE.matcher(followupText).find())              return "TargetNextSpecialFree";
        if (FOLLOWUP_CAST_IT_FROM_BZ_ANYTIME_NO_HAND.matcher(followupText).find())      return "CastItFromBzAnytime";
        if (FOLLOWUP_GAINS_CANNOT_BE_CHOSEN.matcher(followupText).find())             return "GainsCannotBeChosen";
        if (FOLLOWUP_CANNOT_BE_BROKEN.matcher(followupText).find())                  return "CannotBeBroken";
        if (FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE.matcher(followupText).find())           return "CannotBeBrokenSimple";
        if (FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG.matcher(followupText).find())      return "CannotBeBrokenByNonDmg";
        if (FOLLOWUP_IF_PUT_TO_BZ_THIS_TURN_RFG_INSTEAD.matcher(followupText).find()) return "IfPutToBzThisTurnRfgInstead";
        if (FOLLOWUP_GAINS_BREAKTOUCH_BATTLE.matcher(followupText).find())           return "BreaktouchBattle";
        if (FOLLOWUP_CANNOT_BE_CHOSEN_BOTH.matcher(followupText).find())              return "CannotBeChosenBoth";
        if (FOLLOWUP_CANNOT_BE_CHOSEN_SUMMONS.matcher(followupText).find())           return "CannotBeChosenSummons";
        if (FOLLOWUP_CANNOT_BE_CHOSEN_ABILITIES.matcher(followupText).find())         return "CannotBeChosenAbilities";
        if (FOLLOWUP_CANNOT_BE_RETURNED_TO_HAND.matcher(followupText).find())         return "CannotBeReturnedToHand";
        if (FOLLOWUP_DULL_OR_ACTIVATE.matcher(followupText).find())                   return "DullOrActivate";
        if (FOLLOWUP_DULL_OR_FREEZE.matcher(followupText).find())                     return "DullOrFreeze";
        if (FOLLOWUP_ACTIVATE.matcher(followupText).find())                           return "Activate";
        if (FOLLOWUP_DULL.matcher(followupText).find()
                && !FOLLOWUP_DULL_AND_FREEZE.matcher(followupText).find()
                && !FOLLOWUP_DULL_OR_FREEZE.matcher(followupText).find())             return "Dull";
        if (FOLLOWUP_DULL_AND_FREEZE.matcher(followupText).find())                    return "DullAndFreeze";
        if (FOLLOWUP_FREEZE.matcher(followupText).find())                             return "Freeze";
        if (FOLLOWUP_BREAK.matcher(followupText).find())                              return "Break";
        if (FOLLOWUP_LOSE_ABILITIES_AND_POWER_BECOMES.matcher(followupText).find())    return "LoseAllAbilitiesAndPowerBecomes";
        if (FOLLOWUP_LOSE_ALL_ABILITIES_EOT.matcher(followupText).find())              return "LoseAllAbilitiesEot";
        if (FOLLOWUP_REMOVE_FROM_GAME_AND_NAMED.matcher(followupText).find())          return "RemoveFromGameAndNamed";
        if (FOLLOWUP_REMOVE_FROM_GAME.matcher(followupText).find())                   return "RemoveFromGame";
        if (SECONDARY_PLAY_REMOVED_ONTO_FIELD.matcher(followupText).find())           return "PlayRemovedOntoField";
        if (FOLLOWUP_PLAY_IF_COST_LE_JOB_COUNT.matcher(followupText).matches())       return "PlayIfCostLeJobCount";
        if (FOLLOWUP_RETURN_IF_COST_LE_HAND.matcher(followupText).matches())          return "ReturnIfCostLeHand";
        if (FOLLOWUP_PLAY_ONTO_FIELD.matcher(followupText).find())                    return "PlayOntoField";
        if (FOLLOWUP_ADD_TO_HAND.matcher(followupText).find())                        return "AddToHand";
        if (FOLLOWUP_RETURN_AND_NAMED_TO_OWNERS_HAND.matcher(followupText).find())    return "ReturnAndNamedToOwnersHand";
        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(followupText).find())              return "ReturnToOwnersHand";
        if (FOLLOWUP_RETURN_TO_YOUR_HAND.matcher(followupText).find())                return "ReturnToYourHand";
        if (FOLLOWUP_PUT_TOP_OR_BOTTOM_OF_DECK.matcher(followupText).find())          return "PutTopOrBottomOfDeck";
        if (FOLLOWUP_PUT_BOTTOM_OF_DECK.matcher(followupText).find())                 return "PutBottomOfDeck";
        if (FOLLOWUP_PUT_TOP_OF_DECK.matcher(followupText).find())                    return "PutTopOfDeck";
        if (FOLLOWUP_PUT_UNDER_TOP_OF_DECK.matcher(followupText).find())              return "PutUnderTopOfDeck";
        if (FOLLOWUP_CANNOT_BLOCK.matcher(followupText).find())                       return "CannotBlock";
        if (FOLLOWUP_ONLY_BLOCKED_BY_COST_LE_OWN.matcher(followupText).find())        return "OnlyBlockedByCostLeOwn";
        if (FOLLOWUP_CANNOT_BE_BLOCKED.matcher(followupText).find())                  return "CannotBeBlocked";
        if (FOLLOWUP_CANNOT_BE_BLOCKED_IF_ELEMENT_CP.matcher(followupText).find())   return "CannotBeBlockedIfElementCP";
        if (FOLLOWUP_MUST_BLOCK.matcher(followupText).find())                         return "MustBlock";
        if (FOLLOWUP_CANNOT_ATTACK.matcher(followupText).find())                      return "CannotAttack";
        if (FOLLOWUP_MUST_ATTACK.matcher(followupText).find())                        return "MustAttack";
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK.matcher(followupText).find())             return "CannotAttackOrBlock";
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_PERSISTENT.matcher(followupText).find())  return "CannotAttackOrBlockPersistent";
        if (FOLLOWUP_POWER_BECOMES.matcher(followupText).find())                      return "PowerBecomes";
        if (FOLLOWUP_POWER_BOOST.matcher(followupText).find())                        return "PowerBoost";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH.matcher(followupText).find())              return "PowerBoostUntilForEach";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_JOB.matcher(followupText).find())         return "PowerBoostUntilForEachJob";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_COUNTER.matcher(followupText).find())      return "PowerBoostUntilForEachCounter";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_SELF_DMG.matcher(followupText).find())    return "PowerBoostUntilForEachSelfDmg";
        if (FOLLOWUP_POWER_BOOST_UNTIL.matcher(followupText).find())                      return "PowerBoostUntil";
        if (FOLLOWUP_KEYWORD_GRANT.matcher(followupText).find())                      return "KeywordGrant";
        if (FOLLOWUP_KEYWORD_GRANT_UNTIL.matcher(followupText).find())               return "KeywordGrant";
        if (FOLLOWUP_POWER_REDUCE.matcher(followupText).find())                       return "PowerReduce";
        if (FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH_HAND.matcher(followupText).find())  return "PowerReduceUntilForEachHand";
        if (FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH.matcher(followupText).find())       return "PowerReduceUntilForEach";
        if (FOLLOWUP_POWER_REDUCE_UNTIL.matcher(followupText).find())                 return "PowerReduceUntil";
        if (OPPONENT_DISCARD.matcher(followupText).find())                            return "OpponentDiscard";
        if (source != null) {
            Matcher selfM = SELF_POWER_BOOST.matcher(followupText);
            if (selfM.find() && selfM.group("selfsubject").trim().equalsIgnoreCase(source.name()))
                return "SelfPowerBoost";
        }
        if (FOLLOWUP_PLACE_COUNTER_ON_IT.matcher(followupText).find())                 return "PlaceCounterOnIt";
        if (FOLLOWUP_REMOVE_ONE_COUNTER.matcher(followupText).find())                  return "RemoveOneCounter";
        if (BECOME_FORWARD_UNTIL_EOT_PATTERN.matcher(followupText).find())             return "BecomeForwardUntilEot";
        if (FOLLOWUP_CANCEL_EFFECT.matcher(followupText).find())                      return "CancelEffect";
        if (FOLLOWUP_SHIELD_NEXT_DMG_ZERO.matcher(followupText).find())               return "ShieldNextDmgZero";
        if (FOLLOWUP_SHIELD_NEXT_ABILITY_DMG_REDUCTION.matcher(followupText).find())   return "ShieldNextAbilityDmgReduction";
        if (FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION.matcher(followupText).find())          return "ShieldNextDmgReduction";
        if (FOLLOWUP_DEBUFF_INCOMING_DMG_INCREASE.matcher(followupText).find())       return "DebuffIncomingDmgIncrease";
        if (FOLLOWUP_SHIELD_NEXT_OUTGOING_ZERO.matcher(followupText).find())          return "ShieldNextOutgoingZero";
        if (FOLLOWUP_OUTGOING_DMG_BOOST_THIS_TURN.matcher(followupText).find())       return "OutgoingDmgBoostThisTurn";
        if (FOLLOWUP_SHIELD_NONLETHAL.matcher(followupText).find())                   return "ShieldNonLethal";
        if (FOLLOWUP_GAINS_SHIELD_ABILITY_ONLY.matcher(followupText).find())          return "GainsShieldAbilityOnly";
        if (FOLLOWUP_PUT_TO_BREAK_ZONE.matcher(followupText).find())                  return "PutToBreakZone";
        if (FOLLOWUP_SELECT_NUMBER_REVEAL_BREAK.matcher(followupText).find())         return "SelectNumberRevealBreak";
        if (FOLLOWUP_IF_OPPONENT_CONTROLS_FORWARDS_DAMAGE.matcher(followupText).matches()) return "IfOppControlsForwardsDamage";
        if (FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_DAMAGE.matcher(followupText).matches()) return "IfSelfControlsNElementTypeDamage";
        if (FOLLOWUP_REVEAL_TOP_N_DAMAGE_PER_CP_ADD_ALL_TO_HAND.matcher(followupText).find()) return "RevealTopNDamagePerCpAddAllToHand";
        if (FOLLOWUP_REVEAL_TOP_N_JOB_DEAL_DMG_PLACE_BOTTOM.matcher(followupText).find())    return "RevealTopNJobDealDmgPlaceBottom";
        return null;
    }

    /**
     * Returns a full description of which patterns cover {@code effectText}, including
     * primary, followup, and secondary layers.  A {@code "?"} in the result means that
     * layer has no matching pattern yet.  Returns {@code null} if no primary pattern matches.
     */
    public static String fullDescription(String effectText, CardData source) {
        effectText = effectText.replaceFirst("(?i)^(?:\\[\\[ex\\]\\])?\\s*EX\\s+BURST(?:\\[\\[/\\]\\])?\\s*", "").trim();
        effectText = effectText.replaceFirst("(?i)^Then,?\\s+", "").trim();
        effectText = effectText.replaceFirst("(?i)^also\\s+", "").trim();
        // Strip trailing use-restriction sentences so they don't short-circuit before effect patterns match
        String noRestriction = stripRestrictionSentences(effectText);
        if (!noRestriction.isEmpty()) effectText = noRestriction;
        if (tryParseChooseSummonInBzCastable(effectText)              != null) return "ChooseSummonInBzCastable";
        if (tryParseChooseSummonFromBzToHandWithCostReduction(effectText) != null) return "ChooseSummonFromBzToHandWithCostReduction";
        if (tryParseChooseNSummonsBzPickOneHandRestRfg(effectText)    != null) return "ChooseNSummonsBzPickOneHandRestRfg";
        if (tryParseOppRfpTopDeckCastable(effectText)                != null) return "OppRfpTopDeckCastable";
        if (tryParseChooseFromOppBzCastable(effectText)              != null) return "ChooseFromOppBzCastable";
        if (tryParseChooseSummonsFromBzCastable(effectText)          != null) return "ChooseSummonsFromBzCastable";
        if (tryParseChooseSummonInBzMaxCostFreeCastRfg(effectText)   != null) return "ChooseSummonInBzMaxCostFreeCastRfg";
        if (CardData.isSelfCostModifierText(effectText))                        return "SelfCostModifier";
        if (CardData.FIELD_OPP_CAST_COST_INCREASE_PATTERN.matcher(effectText).find()) return "OppCastCostIncrease";
        if (AutoAbilityTriggers.FA_DISCARD_JOB_TO_CAST.matcher(effectText).find()) return "DiscardJobToCast";
        if (CardData.YOUR_TURN_ONLY_PATTERN.matcher(effectText).matches())  return "YourTurnOnly";
        if (CardData.ONCE_PER_TURN_PATTERN.matcher(effectText).matches())   return "OncePerTurn";
        if (CardData.YOUR_TURN_ONLY_PATTERN.matcher(effectText).find()
                && CardData.ONCE_PER_TURN_PATTERN.matcher(effectText).find()) return "YourTurnOnly+OncePerTurn";
        if (CardData.MAIN_PHASE_ONLY_PATTERN.matcher(effectText).matches())        return "MainPhaseOnly";
        if (CardData.WHILE_PARTY_ATTACKING_PATTERN.matcher(effectText).matches()) return "WhilePartyAttacking";
        if (CardData.WHILE_CARD_ATTACKING_PATTERN.matcher(effectText).matches())  return "WhileCardAttacking";
        if (CardData.WHILE_CARD_BLOCKING_PATTERN.matcher(effectText).matches())   return "WhileCardBlocking";
        if (CardData.WHILE_CARD_IN_HAND_PATTERN.matcher(effectText).matches())   return "WhileCardInHand";
        if (CardData.CONTROL_IF_PATTERN.matcher(effectText).find())                  return "UseRestriction";
        if (CardData.YOUR_TURN_AND_CONTROL_IF_PATTERN.matcher(effectText).find())  return "UseRestriction";
        if (CardData.CONTROL_IF_NOT_ANY_PATTERN.matcher(effectText).find())        return "UseRestriction";
        if (CardData.OPPONENT_CONTROLS_N_OR_MORE_PATTERN.matcher(effectText).find()) return "UseRestriction";
        if (tryParseMayPayCostThenEffect(effectText, source, 0)         != null) return "MayPayCostThenEffect";
        if (tryParseWhenYouDoSoSequence(effectText, source, 0)          != null) return "WhenYouDoSo";
        if (tryParseIfNotPayOrElse(effectText, source, 0)               != null) return "IfNotPayOrElse";
        if (tryParseRemoveTopThenPileThreshold(effectText, source)          != null) return "RemoveTopThenPileThreshold";
        if (tryParseAddRemovedBySourceAbilityToHand(effectText, source)     != null) return "AddRemovedBySourceAbilityToHand";
        if (tryParseIfCastAtLeast(effectText, source, 0)                != null) return "IfCastAtLeast";
        if (tryParseIfControlCondOtherThan(effectText, source, 0)      != null) return "IfControlCondOtherThan";
        if (tryParseControlGatedInsteadUpgrade(effectText, source, 0)  != null) return "ControlGatedInsteadUpgrade";
        if (tryParseControlConditionGate(effectText, source, 0)        != null) {
            Matcher ccg = CONTROL_CONDITION_GATE.matcher(effectText.trim());
            if (!ccg.matches()) return "ControlConditionGate";
            String innerTxt  = ccg.group("effect").trim();
            String innerDesc = fullDescription(innerTxt, source);
            if (innerDesc == null) innerDesc = matchedPatternName(innerTxt, source);
            // Plain ASCII separator: "?" is this report's marker for an undescribed layer, and a
            // "→" degrades to "?" on a cp1252 console, which would read as exactly that.
            return "IfControl(" + (ccg.group("neg") != null ? "not " : "")
                    + CardData.parseControlCondition(ccg.group("cond").trim())
                    + ": " + (innerDesc != null ? innerDesc : "?") + ")";
        }
        if (tryParseIfOppControlsNOrMoreCondTypeGate(effectText, source, 0) != null) return "IfOppControlsNOrMoreCondTypeDraw";
        if (tryParseDiscardConditionalElement(effectText, source, 0)    != null) return "DiscardConditionalElement";
        if (tryParseDiscardConditionalElementSingle(effectText, source, 0) != null) return "DiscardConditionalElementSingle";
        if (tryParseDiscardConditionalTargetLoseAbilities(effectText) != null) return "DiscardConditionalTargetLoseAbilities";
        if (tryParseDiscardConditionalSelfBoostInstead(effectText, source, 0) != null) return "DiscardConditionalSelfBoostInstead";
        if (tryParseDrawDiscardIfMultiElement(effectText) != null) return "DrawDiscardIfMultiElement";
        if (tryParseSelectNumber(effectText, source)          != null) return "SelectNumber";
        if (tryParseForEachJobAndNameDealDamageToForwards(effectText)   != null) return "ForEachJobAndNameDealDamageToForwards";
        if (tryParseSelfGainsWhenAttacksEOT(effectText, source)        != null) return "SelfGainsWhenAttacksEOT";
        if (tryParseDealDamageToForwardsForEach(effectText)         != null) return "DealDamageToForwardsForEach";
        if (tryParseDealDamageToForwardsExceptElement(effectText)          != null) return "DealDamageToForwardsExceptElement";
        if (tryParseRfpAllFwdExceptElementsThenTwiceDeck(effectText)       != null) return "RfpAllFwdExceptElementsThenTwiceDeck";
        if (tryParseDealDamageToForwards(effectText)                       != null) return "DealDamageToForwards";
        if (tryParseDivideDamageEquallyAmongAll(effectText)                != null) return "DivideDamageEquallyAmongAll";
        if (tryParseNoForwardCostCannotAttack(effectText)           != null) return "NoForwardCostCannotAttack";
        if (tryParseOwnForwardsCannotBeChosenByExBurst(effectText)  != null) return "OwnForwardsCannotBeChosenByExBurst";
        if (tryParseExBurstSuppression(effectText)                  != null) return "ExBurstSuppression";
        if (tryParseDealHalfPowerDamageToForwards(effectText)       != null) return "DealHalfPowerDamageToForwards";
        if (tryParseDealPowerMinusNDamageToForwards(effectText)     != null) return "DealPowerMinusNDamageToForwards";
        if (tryParseDealHalfSourcePowerDamageToForwards(effectText) != null) return "DealHalfSourcePowerDamageToForwards";
        if (tryParseDamageToCombatBlocker(effectText)               != null) return "DamageToCombatBlocker";
        if (MAY_COST_REPLAY_ABILITY.matcher(effectText).find())               return "MayReplayAbility";

        String normalizedEffectText = ELEM_TYPE_OR_ELEM_TYPE.matcher(effectText).replaceAll("$1 or $3 $2");
        String escapedEffectText = escapePeriodInName(normalizedEffectText, source);
        Matcher oneEachM = CHOOSE_ONE_EACH_PATTERN.matcher(normalizedEffectText);
        if (oneEachM.find()) {
            String followupName = matchedFollowupName(oneEachM.group("followup").trim(), source);
            if (followupName != null) return "ChooseOneEach / " + followupName;
            // followup not describable by matchedFollowupName — fall through to tryParseChooseFormerLatter
        }
        if (tryParseChooseForwardRedirectToNamed(normalizedEffectText) != null) return "ChooseForwardRedirectToNamed";
        if (tryParseChooseFormerLatter(normalizedEffectText, source) != null) return "ChooseFormerLatter";
        if (tryParseChooseForwardDealSelfDamageBreakIfCostLeDamage(normalizedEffectText) != null)
            return "ChooseForwardDealSelfDamageBreakIfCostLeDamage";
        if (tryParseChooseForwardSharedPowerLoss(normalizedEffectText, source) != null)
            return "ChooseForwardSharedPowerLoss";
        if (tryParseChooseFwdPowerLeAndOptOppBzFwdRfp(normalizedEffectText) != null)
            return "ChooseFwdPowerLeAndOptOppBzFwdRfp";
        if (tryParseChooseAnyNumberReturnToHand(normalizedEffectText) != null)
            return "ChooseAnyNumberReturnToHand";
        Matcher threeMixedM = CHOOSE_THREE_MIXED_TYPES_PATTERN.matcher(normalizedEffectText);
        if (threeMixedM.find()) {
            String followupName = matchedFollowupName(threeMixedM.group("followup").trim(), source);
            return "ChooseThreeMixedTypes / " + (followupName != null ? followupName : "?");
        }
        Matcher mixedM = CHOOSE_TWO_MIXED_TYPES_PATTERN.matcher(normalizedEffectText);
        if (mixedM.find()) {
            String followupName = matchedFollowupName(mixedM.group("followup").trim(), source);
            return "ChooseTwoMixedTypes / " + (followupName != null ? followupName : "?");
        }
        // Checked ahead of the ChooseCharacter block: these "choose … Forward(s) …" compounds would
        // otherwise be described as "ChooseCharacter / ?" (their branches aren't recognised followups),
        // keeping the card stuck in "partially parsed" coverage.
        if (tryParseChooseFwdRevealCostParity(effectText) != null) return "ChooseFwdRevealCostParity";
        if (tryParseChooseForwardsGainAbilityEot(effectText) != null) return "ChooseForwardsGainAbilityEot";
        if (tryParseChooseForwardPlacePetrification(effectText) != null) return "ChooseForwardPlacePetrification";
        if (tryParseRemoveAllCountersFromSelf(effectText, source) != null) return "RemoveAllCountersFromSelf";
        Matcher chooseM = CHOOSE_CHARACTER_PATTERN.matcher(escapedEffectText);
        if (chooseM.find()) {
            String followup      = restorePeriodInName(chooseM.group("followup").trim(), source);
            // Check damage-instead on the full followup before the ". " split eats the condition clause.
            // This mirrors what tryParseChooseAndFollowup does.
            Matcher insteadM = FOLLOWUP_DAMAGE_INSTEAD.matcher(followup);
            if (insteadM.find() && parseDamageInsteadCondition(insteadM.group("cond").trim()) != null)
                return "ChooseCharacter / DamageInstead";
            if (FOLLOWUP_SELECT_JOB_GRANT.matcher(followup).find())
                return "ChooseCharacter / SelectJobGrant";
            if (FOLLOWUP_MAY_DISCARD_NAMED_DEAL_DAMAGE.matcher(followup).matches())
                return "ChooseCharacter / MayDiscardNamedDealDamage";
            if (FOLLOWUP_RFP_TOP_DECK_AND_DAMAGE_PER_CP.matcher(followup).find())
                return "ChooseCharacter / RfpTopDeckDamagePerCp";
            if (FOLLOWUP_REVEAL_TOP_N_DAMAGE_PER_CP_ADD_ALL_TO_HAND.matcher(followup).find())
                return "ChooseCharacter / RevealTopNDamagePerCpAddAllToHand";
            if (FOLLOWUP_RFP_IF_SAME_TYPE_DRAW.matcher(followup).find())
                return "ChooseCharacter / RfpIfSameTypeDraw";
            if (FOLLOWUP_REVEAL_TOP_N_JOB_DEAL_DMG_PLACE_BOTTOM.matcher(followup).find())
                return "ChooseCharacter / RevealTopNJobDealDmgPlaceBottom";
            {
                Matcher youMayPayM = FOLLOWUP_YOU_MAY_PAY_ELEMENT_IF_DO_SO.matcher(followup);
                if (youMayPayM.matches()) {
                    String innerEff  = youMayPayM.group("effect").trim();
                    String innerDesc = matchedFollowupName(innerEff, source);
                    return "ChooseCharacter / YouMayPayElement[" + (innerDesc != null ? innerDesc : "?") + "]";
                }
            }
            int    dotIdx        = followup.indexOf(". ");
            String primaryPart   = dotIdx >= 0 ? followup.substring(0, dotIdx).trim() : followup;
            String secondaryRaw  = dotIdx >= 0 ? followup.substring(dotIdx + 2).trim() : null;
            String secondaryTxt  = secondaryRaw != null ? stripRestrictionSentences(secondaryRaw) : null;
            if (secondaryTxt != null && secondaryTxt.isEmpty()) secondaryTxt = null;
            String followupName  = matchedFollowupName(primaryPart, source);
            String secondaryDesc = null;
            // For AddToHand primaries, prefer the conditional-on-added-card form
            // ("If (it|the added card) (is|has) X, Y") over the generic flat description,
            // because the inner effect would otherwise be reported as if it ran unconditionally.
            if ("AddToHand".equals(followupName) && secondaryTxt != null && !secondaryTxt.isEmpty()) {
                Matcher condM = FOLLOWUP_ADD_TO_HAND_CONDITIONAL_SECONDARY.matcher(secondaryTxt);
                if (condM.matches()
                        && parseRevealCondition(condM.group("cond").trim()) != null) {
                    String innerTxt  = condM.group("inner").trim();
                    String innerDesc = fullDescription(innerTxt, source);
                    if (innerDesc == null) innerDesc = matchedPatternName(innerTxt, source);
                    if (innerDesc == null) innerDesc = matchedFollowupName(innerTxt, source);
                    secondaryDesc = "IfAddedCard(" + (innerDesc != null ? innerDesc : "?") + ")";
                }
            }
            if ("PlayOntoField".equals(followupName) && secondaryTxt != null && !secondaryTxt.isEmpty()) {
                Matcher etfM = FOLLOWUP_PLAY_ONTO_FIELD_WHEN_ENTERS_CONDITIONAL.matcher(secondaryTxt);
                if (etfM.matches() && parseRevealCondition(etfM.group("cond").trim()) != null) {
                    String innerTxt  = etfM.group("inner").trim();
                    String innerDesc = fullDescription(innerTxt, source);
                    if (innerDesc == null) innerDesc = matchedPatternName(innerTxt, source);
                    if (innerDesc == null) innerDesc = matchedFollowupName(innerTxt, source);
                    secondaryDesc = "IfETF(" + (innerDesc != null ? innerDesc : "?") + ")";
                }
            }
            if (secondaryDesc == null && secondaryTxt != null && !secondaryTxt.isEmpty())
                secondaryDesc = fullDescription(secondaryTxt, source);
            if (secondaryDesc == null && secondaryTxt != null && !secondaryTxt.isEmpty())
                secondaryDesc = matchedFollowupName(secondaryTxt, source);
            // Compound-sentence fallback: split secondary on ". " and describe each sentence.
            if (secondaryDesc == null && secondaryTxt != null && !secondaryTxt.isEmpty()) {
                String[] secSentences = secondaryTxt.split("(?<=\\.)\\s+(?=[A-Z])");
                if (secSentences.length > 1) {
                    List<String> parts = new ArrayList<>();
                    for (String s : secSentences) {
                        String d = fullDescription(s.trim(), source);
                        if (d == null) d = matchedPatternName(s.trim(), source);
                        if (d == null) d = matchedFollowupName(s.trim(), source);
                        parts.add(d != null ? d : "?");
                    }
                    secondaryDesc = String.join("+", parts);
                }
            }
            StringBuilder sb = new StringBuilder("ChooseCharacter / ")
                    .append(followupName != null ? followupName : "?");
            if (secondaryDesc != null) sb.append(" + ").append(secondaryDesc);
            else if (secondaryTxt != null && !secondaryTxt.isEmpty()) sb.append(" + ?");
            return sb.toString();
        }

        if (tryParsePlayerCannotCastSummons(effectText)                != null) return "PlayerCannotCastSummons";
        if (tryParseCannotBeChosenStandalone(effectText, source) != null)       return "CannotBeChosen";
        if (tryParseCannotBecomeDullOpp(effectText, source) != null)            return "CannotBecomeDullOpp";
        if (tryParseCannotBeReturnedToHandOpp(effectText, source) != null)      return "CannotBeReturnedToHandOpp";
        if (tryParseCharactersCannotBeReturnedToHandOpp(effectText) != null)    return "CharactersCannotBeReturnedToHandOpp";
        if (tryParseCannotBePutIntoBzOpp(effectText, source) != null)           return "CannotBePutIntoBzOpp";
        if (tryParseChooseOwnFwdBoostProtectionsOrAllIfDmg(effectText) != null) return "ChooseOwnFwdBoostProtectionsOrAllIfDmg";
        if (tryParseActivateAllOwnFwdsGainProtections(effectText) != null)      return "ActivateAllOwnFwdsGainProtections";
        if (tryParseStandaloneCannotAttackOrBlock(effectText, source) != null) return "CannotAttackOrBlock";
        if (tryParseNegateAllDamage(effectText) != null)                       return "NegateDamage";
        if (tryParsePlayerNextDamageZeroRedirect(effectText) != null)          return "PlayerNextDamageZeroRedirect";
        if (tryParsePlayerNextDamageZero(effectText) != null)                  return "PlayerNextDamageZero";
        if (tryParseCancelAutoAbilityAndDamageIfForward(effectText) != null) return "CancelAutoAbilityAndDamageIfForward";
        if (tryParseCancelStackEntry(effectText)              != null) return "CancelSummonOrAutoAbility";
        if (tryParseRedirectAbilityTarget(effectText)         != null) return "RedirectAbilityTarget";
        if (tryParseCancelAbilityOnStack(effectText)          != null) return "CancelAbilityOnStack";
        if (tryParseCancelStackEntryUnlessPay(effectText)     != null) return "CancelStackEntryUnlessPay";
        if (tryParseCancelChosenTargetUnlessPay(effectText)   != null) return "CancelChosenTargetUnlessPay";
        if (tryParseCancelChosenTargetUnlessDiscard(effectText) != null) return "CancelChosenTargetUnlessDiscard";
        if (tryParseCancelChosenTargetBare(effectText)         != null) return "CancelChosenTargetBare";
        if (tryParseIfOppNotPayAction(effectText)             != null) return "IfOppNotPayAction";
        if (tryParseCancelChosenRevealTopIfType(effectText)    != null) return "CancelChosenRevealTopIfType";
        if (tryParseCancelChosenMillTopIfNotType(effectText)   != null) return "CancelChosenMillTopIfNotType";
        if (tryParseCancelSummonTargetingMyCharacter(effectText) != null) return "CancelSummonTargetingMyCharacter";
        if (tryParseSelectNumber(effectText, source) != null)               return "SelectNumber";
        if (tryParseChooseOppFwdDynCostBreak(effectText)               != null) return "ChooseOppFwdDynCostBreak";
        if (tryParseChooseFwdPowerInferiorToSource(effectText, source) != null) return "ChooseFwdPowerInferiorToSource";
        if (tryParseChooseFwdBzCostInferiorToRemovedPlay(effectText)   != null) return "ChooseFwdBzCostInferiorToRemovedPlay";
        if (tryParseDullAllOppFwdsPowerLeSource(effectText, source)    != null) return "DullAllOppFwdsPowerLeSource";
        if (tryParseRevealTopBreakSameCostAddToHand(effectText)       != null) return "RevealTopBreakSameCostAddToHand";
        if (tryParseIfSelfFwdReceivedDamageDraw(effectText, source)            != null) return "IfSelfFwdReceivedDamageDraw";
        if (tryParseIfRfpCount(effectText, source)                     != null) return "IfRfpCount";
        if (tryParseAllFieldEffect(effectText) != null)                     return "AllFieldEffect";
        if (tryParseFieldPowerGrantPassive(effectText) != null) {
            String trimmed = effectText.trim();
            return FIELD_OPPONENT_DEBUFF_PASSIVE.matcher(trimmed).matches()
                    ? "FieldOpponentPowerDebuff" : "FieldPowerGrant";
        }
        {
            Matcher bm = ALL_FIELD_POWER_BOOST_PATTERN.matcher(effectText);
            if (bm.find()) {
                String trailing = effectText.substring(bm.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
                if (!trailing.isEmpty()) {
                    String secDesc = fullDescription(trailing, source);
                    return "AllFieldPowerBoost + " + (secDesc != null ? secDesc : "?");
                }
                return "AllFieldPowerBoost";
            }
        }
        if (tryParseAllForwardsSameElementAsNamedPowerBoost(effectText) != null) return "AllForwardsSameElementAsNamedPowerBoost";
        if (tryParsePartyForwardsPowerBoost(effectText) != null)            return "PartyForwardsPowerBoost";
        if (tryParseAllFieldJobCardNamePowerBoost(effectText) != null)       return "AllFieldJobCardNamePowerBoost";
        if (tryParseTwoCardNamesPowerBoost(effectText) != null)             return "TwoCardNamesPowerBoost";
        if (tryParseAllFieldJobPowerBoost(effectText) != null)              return "AllFieldJobPowerBoost";
        if (tryParseAllFieldJobKeywordGrant(effectText) != null)            return "AllFieldJobKeywordGrant";
        if (tryParseAllFieldKeywordGrant(effectText) != null)               return "AllFieldKeywordGrant";
        if (tryParseUntilEotDualPowerShift(effectText) != null)            return "UntilEotDualPowerShift";
        if (tryParseUntilEotAllFieldPowerBoost(effectText) != null)        return "UntilEotAllFieldPowerBoost";
        if (tryParseStandalonePowerBoostAndAttackTrigger(effectText, source) != null) return "StandalonePowerBoostAndAttackTrigger";
        if (tryParseStandalonePowerBoostAndCannotBeChosen(effectText, source) != null) return "StandalonePowerBoostAndCannotBeChosen";
        if (tryParseStandaloneGainsTraitsAndCannotBeBlocked(effectText, source) != null) return "StandaloneGainsTraitsAndCannotBeBlocked";
        if (tryParseStandaloneGainsCannotBeBlocked(effectText, source) != null) return "StandaloneGainsCannotBeBlocked";
        if (tryParseSelfBasePowerBecomesUntil(effectText, source) != null)  return "SelfBasePowerBecomesUntil";
        if (tryParseStandalonePowerBoostUntil(effectText, source) != null)  return "StandalonePowerBoostUntil";
        if (tryParseStandaloneDoublePowerUntil(effectText, source) != null) return "StandaloneDoublePowerUntil";
        if (tryParseStandaloneDoublesItsPowerUntil(effectText, source) != null) return "StandaloneDoublesItsPowerUntil";
        if (tryParseStandaloneDoublePowerMainPhaseNextTurn(effectText, source) != null) return "StandaloneDoublePowerMainPhaseNextTurn";
        if (tryParseStandalonePowerReduceUntil(effectText, source) != null) return "StandalonePowerReduceUntil";
        if (tryParseDoubleOutgoingDamageThisTurn(effectText, source) != null)    return "DoubleOutgoingDamageThisTurn";
        if (tryParseDoubleOutgoingDamageThisTurnAlt(effectText, source) != null) return "DoubleOutgoingDamageThisTurnAlt";
        if (tryParseSelfOutgoingDmgBoostThisTurn(effectText, source) != null)   return "SelfOutgoingDmgBoostThisTurn";
        if (tryParseGainOutgoingDmgBoostUntilEot(effectText, source) != null)   return "GainOutgoingDmgBoostUntilEot";
        if (tryParseGainsQuotedFieldAbilityUntilEot(effectText, source) != null) return "GainsQuotedFieldAbilityUntilEot";
        if (tryParseGainsQuotedAbilitiesPermanent(effectText, source) != null)  return "GainsQuotedAbilitiesPermanent";
        if (tryParseUntilEotGainsPowerTraitsAndQuoted(effectText, source) != null) return "UntilEotGainsPowerTraitsAndQuoted";
        if (tryParseDoubleOpponentIncomingDamageThisTurn(effectText) != null)   return "DoubleOpponentIncomingDamageThisTurn";
        if (tryParseAllForwardIncomingDmgIncreaseThisTurn(effectText) != null)  return "AllForwardIncomingDmgIncreaseThisTurn";
        if (tryParseChooseForwardDoubleIncomingThisTurn(effectText) != null)    return "ChooseForwardDoubleIncomingThisTurn";
        if (tryParseChooseForwardDoubleNextOutgoing(effectText) != null)        return "ChooseForwardDoubleNextOutgoing";
        if (tryParseDoublePlayerAbilityOutgoingThisTurn(effectText) != null)   return "DoublePlayerAbilityOutgoingThisTurn";
        if (tryParseStandaloneSelfBoostForEachCrystal(effectText, source) != null) return "StandaloneSelfBoostForEachCrystal";
        if (tryParseIfHandSizeSelfBoost(effectText, source)               != null) return "IfHandSizeSelfBoost";
        if (tryParseIfHandSizeSelfBoost(effectText, source)               != null) return "IfHandSizeSelfBoost";
        if (tryParseStandaloneSelfBoost(effectText, source) != null)        return "StandaloneSelfBoost";
        if (tryParseStandaloneSelfDullAndShield(effectText, source) != null) return "StandaloneSelfDullAndShield";
        if (tryParseStandaloneSelfDull(effectText, source) != null)          return "StandaloneSelfDull";
        if (tryParseStandaloneShieldCannotBeBroken(effectText, source) != null) return "StandaloneShieldCannotBeBroken";
        if (tryParseAllOwnForwardsNullifyAbilityDamage(effectText)        != null) return "AllOwnForwardsNullifyAbilityDamage";
        if (tryParseOwnJobOrNameNullifyAbilityDamage(effectText)          != null) return "OwnJobOrNameNullifyAbilityDamage";
        if (tryParseDoublecastFreeSummons(effectText)                     != null) return "DoublecastFreeSummons";
        if (tryParseCastRfgCostCardThisTurn(effectText)                   != null) return "CastRfgCostCardThisTurn";
        if (tryParseChooseCardRemovedBySourceToBz(effectText, source)     != null) return "ChooseCardRemovedBySourceToBz";
        if (tryParseAllForwardsCannotBlock(effectText)                    != null) return "AllForwardsCannotBlock";
        if (tryParseForwardsOfCostCannotBlock(effectText)                 != null) return "ForwardsOfCostCannotBlock";
        if (tryParseEndOfNextTurnIfCardOnFieldOppLoses(effectText)        != null) return "EndOfNextTurnIfCardOnFieldOppLoses";
        if (tryParseOppFwdsCannotBlockInferiorPower(effectText)           != null) return "OppFwdsCannotBlockInferiorPower";
        if (tryParseAllFwdsBlockedOnlyByLowerCostThisTurn(effectText)    != null) return "AllFwdsBlockedOnlyByLowerCost";
        if (tryParseOppFwdsLoseAllAbilitiesEot(effectText)         != null) return "OppFwdsLoseAllAbilitiesEot";
        if (tryParseOppFwdPowerBoostSuppressedThisTurn(effectText) != null) return "OppFwdPowerBoostSuppressedThisTurn";
        if (tryParseOppFwdsLosePowerPerPlayCost(effectText)        != null) return "OppFwdsLosePowerPerPlayCost";
        if (tryParseStandaloneGainsCannotBeBlocked(effectText, source) != null) return "StandaloneGainsCannotBeBlocked";
        if (tryParseStandaloneCannotBeBlocked(effectText, source) != null) return "StandaloneCannotBeBlocked";
        if (tryParseRevealHandOptPickRfpOppDraw(effectText) != null)        return "RevealHandOptPickRfpOppDraw";
        if (tryParseRevealSelectHandRfp(effectText) != null)               return "RevealSelectHandRfp";
        if (tryParseOpponentRandomHandRfp(effectText) != null)              return "OpponentRandomHandRfp";
        if (tryParseOpponentRandomHandToBottomDeck(effectText) != null)     return "OpponentRandomHandToBottomDeck";
        if (tryParseOpponentHandRfp(effectText) != null)                   return "OpponentHandRfp";
        if (tryParseRevealTopNAddUpToExcludingNameRestBz(effectText) != null)  return "RevealTopNAddUpToExcludingNameRestBz";
        if (tryParseRevealTopNTypeToHand(effectText)       != null)           return "RevealTopNTypeToHand";
        if (tryParseRevealTopNCategoryToHand(effectText)   != null)          return "RevealTopNCategoryToHand";
        if (tryParseRevealTopNJobOrNameToHand(effectText)  != null)          return "RevealTopNJobOrNameToHand";
        if (tryParseRevealTopNElementToHand(effectText)    != null)           return "RevealTopNElementToHand";
        if (tryParseRevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(effectText) != null) return "RevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom";
        if (tryParseReturnNamedToHand(effectText) != null)                   return "ReturnNamedToHand";
        if (tryParseYouMayRemoveNamedFromGame(effectText, source) != null)   return "YouMayRemoveNamedFromGame";
        if (tryParseEndOfOppTurnPlayNamedOntoField(effectText) != null)     return "EndOfOppTurnPlayNamedOntoField";
        if (tryParseRemoveAllOppBzFromGame(effectText)       != null)      return "RemoveAllOppBzFromGame";
        if (tryParseRemoveNamedFromGame(effectText, source) != null)        return "RemoveNamedFromGame";
        if (tryParseBreakSourceCard(effectText, source)        != null)     return "BreakSourceCard";
        if (tryParsePutSourceIntoBreakZone(effectText, source) != null)     return "PutSourceIntoBreakZone";
        if (tryParseYouMayPutSelfToBZWhenDoSo(effectText, source)    != null) return "YouMayPutSelfToBZWhenDoSo";
        if (tryParseIfOppNoForwardsPutToBreakZone(effectText, source)          != null) return "IfOppNoForwardsPutToBreakZone";
        if (tryParseIfEitherPlayerNoForwardsPutSourceToBz(effectText, source)  != null) return "IfEitherPlayerNoForwardsPutSourceToBz";
        if (tryParseIfSelfDamagePointsPutToBreakZone(effectText, source) != null) return "IfSelfDamagePointsPutToBreakZone";
        if (tryParsePutSourceToBottomOfDeck(effectText, source) != null)   return "PutSourceToBottomOfDeck";
        if (tryParseBreakBlockingForward(effectText)           != null)     return "BreakBlockingForward";
        if (tryParseBreakForwardThatBlocksCard(effectText)     != null)     return "BreakForwardThatBlocksCard";
        if (tryParseChooseExBurstFromDamageZone(effectText)    != null)     return "ChooseExBurstFromDamageZone";
        if (tryParseExBurstSuppression(effectText)             != null)     return "ExBurstSuppression";
        if (tryParseDamageZoneSwap(effectText)              != null) {
            Matcher m = DAMAGE_ZONE_SWAP_PATTERN.matcher(effectText.trim());
            return m.matches() && m.group("draw") != null ? "DamageZoneSwap + DrawCards" : "DamageZoneSwap";
        }
        if (tryParseOpponentDrawThenRandomDiscard(effectText) != null)      return "OpponentDrawThenRandomDiscard";
        if (tryParseOpponentDraw(effectText) != null)                       return "OpponentDraw";
        if (tryParseOpponentRandomDiscard(effectText) != null)              return "OpponentRandomDiscard";
        if (tryParseEachPlayerSelectForwardDamage(effectText) != null)      return "EachPlayerSelectForwardDamage";
        if (tryParseBothPlayersSelectForwardToBreakZone(effectText) != null) return "BothPlayersSelectForwardToBreakZone";
        if (tryParseSelectCharCostLeExclToBz(effectText)             != null)  return "SelectCharCostLeExclToBz";
        if (tryParseSelectControlledCharacterToBz(effectText)        != null)  return "SelectControlledCharacterToBz";
        if (tryParseEachPlayerSelectUpToNToBreakZone(effectText) != null)   return "EachPlayerSelectUpToNToBreakZone";
        if (tryParseEachPlayerDiscard(effectText) != null)                  return "EachPlayerDiscard";
        if (tryParseEachPlayerSalvageFromBreakZone(effectText) != null)     return "EachPlayerSalvageFromBreakZone";
        if (tryParseEachPlayerDraw(effectText) != null)                     return "EachPlayerDraw";
        if (tryParseNameCardTypeOpponentDiscardDrawIfMatch(effectText) != null) return "NameCardTypeOpponentDiscardDrawIfMatch";
        if (tryParseOpponentDiscard(effectText) != null)                    return "OpponentDiscard";
        if (tryParseDiscardHandThenDraw(effectText) != null)                return "DiscardHandThenDraw";
        if (tryParseDrawDiscardRetriggerIfCardName(effectText, source) != null) return "DrawDiscardRetriggerIfCardName";
        if (tryParsePlaceUpToHandToBottomThenRedraw(effectText) != null)    return "PlaceUpToHandToBottomThenRedraw";
        if (tryParseDrawCards(effectText) != null)                          return "DrawCards";
        if (tryParseYouMayDiscardType(effectText) != null)                  return "YouMayDiscardType";
        if (tryParseMayRevealElementFromHand(effectText) != null)           return "MayRevealElementFromHand";
        if (tryParseDiscardHand(effectText) != null)                        return "DiscardHand";
        if (tryParseDiscardNCards(effectText) != null)                      return "DiscardNCards";
        if (tryParseDiscardJobFromHand(effectText) != null)                 return "DiscardJobFromHand";
        if (tryParseDiscardThenDraw(effectText) != null)                    return "DiscardThenDraw";
        if (tryParseIfEachPlayerEmptyHand(effectText, source, 0) != null)   return "IfEachPlayerEmptyHand";
        if (tryParseDealPlayerDamageToOpponent(effectText) != null)         return "DealPlayerDamageToOpponent";
        if (tryParseDealPlayerDamageToSelf(effectText) != null)             return "DealPlayerDamageToSelf";
        if (tryParseRandomRevealHandCastIfSummonFree(effectText) != null)   return "RandomRevealHandCastIfSummonFree";
        if (tryParseCastSummonFromHandDiscounted(effectText) != null)       return "CastSummonFromHandDiscounted";
        if (tryParseCastSummonFromHandFree(effectText, 0) != null)          return "CastSummonFromHandFree";
        if (tryParseSearchAndCastSummonFree(effectText) != null)            return "SearchAndCastSummonFree";
        if (tryParsePlayAnyNumberFromHand(effectText, source) != null)      return "PlayAnyNumberFromHand";
        if (tryParsePlayFromHand(effectText, source, 0) != null)            return "PlayFromHand";

        Matcher opSelM = OPPONENT_SELECTS_PATTERN.matcher(effectText);
        if (opSelM.find()) {
            String followup     = opSelM.group("followup").trim();
            String followupName = matchedFollowupName(followup, source);
            return "OpponentSelects / " + (followupName != null ? followupName : "?");
        }

        if (tryParseBzFwdToHandOppFwdToBzByDamage(effectText) != null)      return "BzFwdToHandOppFwdToBzByDamage";
        if (tryParseOpponentMillIfSameElementDraw(effectText) != null)      return "OpponentMillIfSameElementDraw";
        if (tryParseOpponentMill(effectText) != null)                       return "OpponentMill";
        if (tryParseSelfMill(effectText) != null)                           return "SelfMill";
        if (tryParseOpponentRevealHand(effectText) != null)                 return "OpponentRevealHand";
        if (tryParseEachPlayerRevealCharacterMayPlay(effectText) != null)   return "EachPlayerRevealMayPlay";
        if (tryParseEachPlayerMaySearchForwardMinPower(effectText) != null) return "EachPlayerMaySearchForwardMinPower";
        if (tryParseRevealTopDeck(effectText, source) != null)
            return revealTopDeckDescription(effectText, source) + restrictionDesc(effectText);
        if (tryParseStandaloneDamageShields(effectText, source) != null)    return "StandaloneDamageShields";
        if (tryParseDualSearchJobAndTypeDontShareElements(effectText) != null) return "DualSearchDontShareElements";
        if (tryParseSearchNElementSummonsDiffCost(effectText)         != null) return "SearchNElementSummonsDiffCost";
        if (tryParseSearchDeck(effectText, source, 0) != null)              return "SearchDeck";
        if (tryParsePlayAllByNameFromBreakZone(effectText) != null)         return "PlayAllByNameFromBreakZone";
        if (tryParsePlaySourceFromBreakZone(effectText, source) != null)    return "PlaySourceFromBreakZone";
        if (tryParseActivateNamedCard(effectText) != null)                  return "ActivateNamedCard";
        if (tryParseOpponentCannotSearchThisTurn(effectText) != null)       return "OpponentCannotSearch";
        if (tryParseExtraTurnThenLose(effectText) != null)                  return "ExtraTurnThenLose";
        if (tryParseGainCrystalPerX(effectText, 0) != null)                 return "GainCrystalPerX";
        if (tryParseGainCrystal(effectText)        != null)                  return "GainCrystal";
        if (tryParseGainCrystalIfOpponentHas(effectText) != null)            return "GainCrystalIfOpponentHas";
        if (tryParsePlaceCountersForEach(effectText, source) != null)        return "PlaceCountersForEach";
        if (tryParsePlaceCounters(effectText, source) != null)               return "PlaceCounters";
        if (tryParseRemoveAllCounters(effectText, source) != null)           return "RemoveAllCounters";
        if (tryParseLookTopDeckOptionallyBreak(effectText)        != null) return "LookTopDeckOptionallyBreak";
        if (tryParseLookTopDeckBottomOrKeep(effectText)           != null) return "LookTopDeckBottomOrKeep";
        if (tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(effectText, source) != null) return "ChooseOppFwdGainsSpecialAbilityFreeOnce";
        if (tryParseUseSpecialAbilityUsedThisTurn(effectText, source) != null) return "UseSpecialAbilityUsedThisTurn";
        if (tryParseChooseOppDamagedFwdIfHasAbilityBreak(effectText)       != null) return "ChooseOppDamagedFwdIfHasAbilityBreak";
        if (tryParseChooseAsManyAsFieldCount(effectText, source)           != null) return "ChooseAsManyAsFieldCount";
        if (tryParseChooseAsManyAsBzRfgJobCount(effectText)               != null) return "ChooseAsManyAsBzRfgJobCount";
        if (tryParseChooseCounterScaleCharsActivate(effectText, 1)         != null) return "ChooseCounterScaleCharsActivate";
        if (tryParseCounterScaleLookAddToHand(effectText, 1)               != null) return "CounterScaleLookAddToHand";
        if (tryParseLookTopDeckAddToHandRestBottom(effectText)          != null) return lookAddToHandRestBottomPatternName(effectText);
        if (tryParseLookTopDeckAddToHandOneToBreakRestBottom(effectText) != null) return "LookTopDeckAddToHandOneToBreakRestBottom";
        if (tryParseLookTopDeckAddToHandRestBreak(effectText)           != null) return "LookTopDeckAddToHandRestBreak";
        if (tryParseLookTopDeckTopOrBottom(effectText, source)          != null) {
            String then = trailingThenText(effectText, LOOK_TOP_DECK_TOP_OR_BOTTOM);
            return then == null ? "LookTopDeckTopOrBottom"
                    : "LookTopDeckTopOrBottom + " + fullDescription(then, source);
        }
        if (tryParseLookTopDeckReturnTopOrdered(effectText)             != null) return "LookTopDeckReturnTopOrdered";
        if (tryParseLookTopDeckPickOneTopRestBottom(effectText)              != null) return "LookTopDeckPickOneTopRestBottom";
        if (tryParseLookTopDeckCastSummonFreeRestBottom(effectText, 0)       != null) return "LookTopDeckCastSummonFreeRestBottom";
        if (tryParseLookTopDeckPeek(effectText)                              != null) return "LookTopDeckPeek";
        if (tryParseAddRemovedByPreviousEffectToHand(effectText, source)    != null) return "AddRemovedByPreviousEffectToHand";
        if (tryParseRemoveTopOfDeckFromGame(effectText, source)             != null) return "RemoveTopOfDeckFromGame";
        if (tryParseRevealPlayNamedWithMaxCostRestBottom(effectText)           != null) return "RevealPlayNamedWithMaxCostRestBottom";
        if (tryParseRevealPlayNamedOrJobMaxCostRestBottom(effectText)          != null) return "RevealPlayNamedOrJobMaxCostRestBottom";
        if (tryParseFlipUntilTypeToHandRestShuffleBottom(effectText)           != null) return "FlipUntilTypeToHandRestShuffleBottom";
        if (tryParseShuffleThenRevealPlayNamedRestBottom(effectText, source) != null) return "ShuffleThenRevealPlayNamedRestBottom";
        if (tryParseRevealPlayTypeOntoFieldRestBottom(effectText)                != null) return "RevealPlayTypeOntoFieldRestBottom";
        if (tryParseRevealElementCardFromHandIfSoDraw(effectText)                != null) return "RevealElementCardFromHandIfSoDraw";
        if (tryParseRevealPlayElementTypeCostOntoFieldRestBottom(effectText)     != null) return "RevealPlayElementTypeCostOntoFieldRestBottom";
        if (tryParseShuffleDeck(effectText)                              != null) return "ShuffleDeck";
        if (tryParseBackupCpDraw(effectText)                             != null) return "BackupCpDraw";
        if (tryParseAllMonstersTemporaryForward(effectText)            != null) return "AllMonstersTemporaryForward";
        if (tryParseBecomeForwardUntilEot(effectText, source)         != null) return "BecomeForwardUntilEot";
        if (tryParseNameElementOnlySelfBecomes(effectText, source)      != null) return "NameElementOnlySelfBecomes";
        if (tryParseNameElementAndJobSelfBecomes(effectText, source)   != null) return "NameElementAndJobSelfBecomes";
        if (tryParseNameJob(effectText)                                != null) return "NameJob";
        if (tryParseGrantPartyAnyElementThisTurn(effectText)           != null) return "GrantPartyAnyElementThisTurn";
        if (tryParseSourcePowerBecomesRemovedForwardPower(effectText, source) != null) return "SourcePowerBecomesRemovedPower";
        if (tryParseSourcePowerBecomesOpponentWeakestForward(effectText, source) != null) return "SourcePowerBecomesOpponentWeakestForward";
        if (tryParseOpponentGainsControlOfSource(effectText, source) != null) return "OpponentGainsControlOfSource";
        if (tryParseConditionalOpponentHand(effectText, source, 0)    != null) return "ConditionalOpponentHand";
        if (tryParseConditionalOpponentHandMin(effectText, source, 0) != null) return "ConditionalOpponentHandMin";
        if (tryParseYouMayPutSelfToBZWhenDoSo(effectText, source)    != null) return "YouMayPutSelfToBZWhenDoSo";
        if (SELECT_FOLLOWING_ACTIONS_DETECT.matcher(effectText).find())    return "SelectFollowingActions";
        if (CardData.HAS_ALL_ELEMENTS_PATTERN.matcher(effectText.trim()).matches()) return "HasAllElements";
        if (tryParseMultiPlayGrant(effectText) != null)                     return "MultiPlayGrant";
        if (tryParseLightDarkDiscardCpGrant(effectText) != null)            return "LightDarkDiscardCpGrant";
        return null;
    }

    private static String revealTopDeckDescription(String text, CardData source) {
        Matcher m = REVEAL_CLAUSE_PATTERN.matcher(text);
        List<String> clauseDescs = new ArrayList<>();
        while (m.find()) {
            String action = m.group("action").trim();
            String op = normalizeRevealOp(action);
            if (op != null) {
                clauseDescs.add(op);
            } else {
                String effName = matchedPatternName(action, source);
                clauseDescs.add(effName != null ? effName : "?");
            }
        }
        return clauseDescs.isEmpty() ? "RevealTopDeck"
                : "RevealTopDeck / " + String.join(", ", clauseDescs);
    }

    private static String restrictionDesc(String effectText) {
        List<String> parts = new ArrayList<>();
        if (CardData.YOUR_TURN_ONLY_PATTERN.matcher(effectText).find())        parts.add("yourTurnOnly");
        if (CardData.ONCE_PER_TURN_PATTERN.matcher(effectText).find())         parts.add("oncePerTurn");
        if (CardData.MAIN_PHASE_ONLY_PATTERN.matcher(effectText).find())       parts.add("mainPhaseOnly");
        if (CardData.WHILE_PARTY_ATTACKING_PATTERN.matcher(effectText).find()) {
            parts.add("whilePartyAttacking");
        } else {
            Matcher wAtkM = CardData.WHILE_CARD_ATTACKING_PATTERN.matcher(effectText);
            if (wAtkM.find()) parts.add("whileCardAttacking:" + wAtkM.group("card"));
        }
        Matcher wBlkM = CardData.WHILE_CARD_BLOCKING_PATTERN.matcher(effectText);
        if (wBlkM.find()) parts.add("whileCardBlocking:" + wBlkM.group("card"));
        if (CardData.WHILE_CARD_IN_HAND_PATTERN.matcher(effectText).find()) parts.add("whileCardInHand");
        Matcher elemFwdM = CardData.ELEMENT_FORWARD_ENTERED_THIS_TURN_PATTERN.matcher(effectText);
        if (elemFwdM.find()) parts.add("elemFwdEntered:" + elemFwdM.group("element"));
        return parts.isEmpty() ? "" : " [" + String.join(", ", parts) + "]";
    }

    /**
     * Resolves an activated Action Ability:
     * <ol>
     *   <li>Logs the ability being pushed to the stack.</li>
     *   <li>AI (P2) automatically passes priority (no response implemented yet).</li>
     *   <li>Pops and executes the effect; logs an info message if unparsed.</li>
     * </ol>
     *
     * @param ability   the ability being activated
     * @param source    the card that used the ability
     * @param gameState current game state
     * @param ctx       live context for applying effects to the field
     */
    public static void resolve(ActionAbility ability, CardData source,
            GameState gameState, GameContext ctx) {
        resolve(ability, source, gameState, ctx, 0);
    }

    public static void resolve(ActionAbility ability, CardData source,
            GameState gameState, GameContext ctx, int xValue) {
        ctx.logEntry("[Stack] \"" + source.name() + "\" → " + ability.effectText());
        ctx.logEntry("[Stack] P2 passes — resolving");

        Consumer<GameContext> effect = parse(ability.effectText(), source, xValue);
        if (effect != null) {
            effect.accept(ctx);
        } else {
            ctx.logEntry("[ActionResolver] Effect not yet implemented: " + ability.effectText());
        }
    }

    // -------------------------------------------------------------------------
    // Effect parsers
    // -------------------------------------------------------------------------

    /**
     * Parses "Deal X damage to all [condition] Forwards [your opponent controls]".
     *
     * <ul>
     *   <li>No condition — all Forwards (P1 and P2, or opponent only if stated)</li>
     *   <li>condition=dull — only Dulled Forwards</li>
     *   <li>condition=damaged — only Forwards that have already taken damage</li>
     * </ul>
     *
     * Targets are collected before damage is applied.  Forwards are damaged in
     * reverse-index order so that breaks (which shift the list) do not corrupt
     * subsequent indices.
     */










    private static Consumer<GameContext> tryParseDealNForEachJobOrNameToOppForwards(String text) {
        Matcher m = DEAL_N_FOR_EACH_JOB_OR_NAME_TO_OPP_FORWARDS.matcher(text.trim());
        if (!m.matches()) return null;
        String job      = m.group("job").trim();
        String cardName = m.group("cardname").trim();
        int    baseDmg  = Integer.parseInt(m.group("amount"));
        return ctx -> {
            int count = ctx.countSelfFieldCards(true, true, true, job, null)
                      + ctx.countSelfFieldCards(true, true, true, null, cardName);
            int damage = baseDmg * count;
            ctx.logEntry("Effect: Deal " + baseDmg + " × " + count
                    + " (Job " + job + "/Name " + cardName + ") = " + damage + " to all opponent Forwards");
            if (damage <= 0) return;
            if (ctx.isP1()) {
                for (int i = ctx.p2ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p2ForwardCount()) ctx.damageP2Forward(i, damage);
            } else {
                for (int i = ctx.p1ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p1ForwardCount()) ctx.damageP1Forward(i, damage);
            }
        };
    }


    private static Consumer<GameContext> tryParseSelfGainsWhenAttacksEOT(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_GAINS_WHEN_ATTACKS_EOT.matcher(text);
        if (!m.matches()) return null;
        String innerText = m.group("inner").trim();
        Consumer<GameContext> innerEffect = tryParseDealDamageToForwards(innerText);
        if (innerEffect == null) return null;
        return ctx -> {
            ctx.logEntry("Effect: " + source.name() + " gains 'When attacks, [effect]' until EOT");
            ctx.addTempAttackTrigger(source, innerEffect);
        };
    }





    static int halfPowerDamage(int power) {
        return (int)(Math.ceil(power / 2.0 / 1000) * 1000);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns {@code true} if {@code cardCost} satisfies the cost constraint, or if {@code costVal < 0} (no filter). */
    static boolean meetsCostFilter(int cardCost, int costVal, String costCmp) {
        if (costVal < 0) return true;
        if (costCmp == null) return cardCost == costVal;
        return costCmp.equalsIgnoreCase("less") ? cardCost <= costVal : cardCost >= costVal;
    }

    /**
     * Returns {@code true} if a forward satisfies {@code condition}.
     *
     * @param condition {@code "active"}, {@code "dull"}, {@code "damaged"},
     *                  {@code "attacking"}, {@code "blocking"}, or {@code null} (any)
     */
    static boolean meetsCondition(CardState state, int currentDamage,
            boolean isAttacking, boolean isBlocking, String condition) {
        if (condition == null) return true;
        return switch (condition.toLowerCase()) {
            case "active"         -> state == CardState.ACTIVE;
            case "dull"           -> state == CardState.DULL;
            case "damaged"        -> currentDamage > 0;
            case "attacking"      -> isAttacking;
            case "blocking"       -> isBlocking;
            default               -> true;
        };
    }

    // -------------------------------------------------------------------------
    // Damage-instead condition helpers
    // -------------------------------------------------------------------------

    static DamageInsteadCondition parseDamageInsteadCondition(String cond) {
        String s = cond.trim();

        // Target-state conditions
        if (s.equalsIgnoreCase("it is active"))
            return new DamageInsteadCondition.TargetIsActive();
        if (s.matches("(?i)it is a Multi-Element (?:Forward|Monster|Character|Backup)?\\s*"))
            return new DamageInsteadCondition.TargetIsMultiElement();

        // Self-state conditions
        if (s.equalsIgnoreCase("you have received a point of damage this turn"))
            return new DamageInsteadCondition.YouReceivedDamageThisTurn();
        if (s.equalsIgnoreCase("you have a Summon in your Break Zone"))
            return new DamageInsteadCondition.YouHaveSummonInBreakZone();

        // Self damage count: "you have received N points of damage or more"
        Matcher selfDmgM = java.util.regex.Pattern
                .compile("(?i)you have received (\\d+) points? of damage or more").matcher(s);
        if (selfDmgM.find())
            return new DamageInsteadCondition.YouReceivedDamageAtLeast(Integer.parseInt(selfDmgM.group(1)));

        // Opponent damage count: "your opponent has received N points of damage or more"
        Matcher oppDmgM = java.util.regex.Pattern
                .compile("(?i)your opponent has received (\\d+) points? of damage or more").matcher(s);
        if (oppDmgM.find())
            return new DamageInsteadCondition.OpponentDamageAtLeast(Integer.parseInt(oppDmgM.group(1)));

        // Opponent hand size: "your opponent has N cards or less in their hand"
        Matcher oppHandM = java.util.regex.Pattern
                .compile("(?i)your opponent has (\\d+) cards? or (?:less|fewer) in their hand").matcher(s);
        if (oppHandM.find())
            return new DamageInsteadCondition.OpponentHandAtMost(Integer.parseInt(oppHandM.group(1)));

        // Cards cast this turn: "you have cast N or more cards this turn"
        Matcher castM = java.util.regex.Pattern
                .compile("(?i)you have cast (\\d+) or more cards this turn").matcher(s);
        if (castM.find())
            return new DamageInsteadCondition.YouCastAtLeast(Integer.parseInt(castM.group(1)));

        // Forward count comparison
        if (s.equalsIgnoreCase("the number of Forwards your opponent controls is greater than the number of Forwards you control"))
            return new DamageInsteadCondition.OpponentHasMoreForwards();

        // EX Burst: "<name> results from an EX Burst"
        if (s.matches("(?i).+ results from an EX Burst"))
            return new DamageInsteadCondition.IsExBurst();

        // "If you control … [other than <name>]" — delegate to ControlCondition parser
        if (s.toLowerCase().startsWith("you control ")) {
            String rest = s.substring("you control ".length()).trim();
            String excludeName = null;
            Matcher otherThanM = java.util.regex.Pattern
                    .compile("(?i)^(?<cond>.+?)\\s+other\\s+than\\s+(?<name>.+)$").matcher(rest);
            if (otherThanM.matches()) {
                excludeName = otherThanM.group("name").trim();
                rest = otherThanM.group("cond").trim();
            }
            ControlCondition cc = CardData.parseControlCondition(rest);
            if (cc != null) return new DamageInsteadCondition.YouControl(cc, excludeName);
        }
        return null;
    }

    /**
     * Parses an action-text string (a followup without target-selection) into a
     * {@code BiConsumer} that applies the action to an already-selected target list.
     * Returns {@code null} if the text is not recognised.
     * Handles: Freeze, Dull+Freeze, Break, Return-to-hand (+draw), Reduce power,
     * and "Deal N damage for each [Category X] Type you control".
     */
    static BiConsumer<GameContext, List<ForwardTarget>>
            parseTargetAction(String text, int xValue) {
        String t = text.trim();

        // Dull+Freeze must precede plain Freeze (Freeze matches as a substring)
        if (FOLLOWUP_DULL_AND_FREEZE.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullAndFreezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullAndFreezeTarget);
            };

        if (FOLLOWUP_FREEZE.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::freezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::freezeTarget);
            };

        if (FOLLOWUP_BREAK.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::breakTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::breakTarget);
            };

        if (FOLLOWUP_ACTIVATE.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::activateTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::activateTarget);
            };

        // Return + draw must precede plain return (draw extends the return text)
        Matcher retDrawM = FOLLOWUP_RETURN_AND_DRAW.matcher(t);
        if (retDrawM.find()) {
            int draws = Integer.parseInt(retDrawM.group("draw"));
            return (ctx, ts) -> {
                for (ForwardTarget ft : ts) {
                    if (ft.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (ft.isP1()) ctx.returnP1ForwardToHand(ft.idx());
                    else           ctx.returnP2ForwardToHand(ft.idx());
                }
                ctx.drawCards(draws);
            };
        }

        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(t).find())
            return (ctx, ts) -> {
                for (ForwardTarget ft : ts) {
                    if (ft.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (ft.isP1()) ctx.returnP1ForwardToHand(ft.idx());
                    else           ctx.returnP2ForwardToHand(ft.idx());
                }
            };

        // Power reduce — both word orders
        Matcher reduceM = FOLLOWUP_POWER_REDUCE.matcher(t);
        if (reduceM.find()) {
            int reduction = reduceM.group(1) != null ? Integer.parseInt(reduceM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
            };
        }
        // Power reduce for each [element] [type] you control (must precede plain reduce-until)
        Matcher reduceForEachM = FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH.matcher(t);
        if (reduceForEachM.find()) {
            boolean untilPrefix = reduceForEachM.group(1) != null;
            int    perUnit = Integer.parseInt(untilPrefix ? reduceForEachM.group(1) : reduceForEachM.group(4));
            String srcElem = untilPrefix ? reduceForEachM.group("element") : reduceForEachM.group("element2");
            String srcType = (untilPrefix ? reduceForEachM.group("chartype") : reduceForEachM.group("chartype2")).toLowerCase();
            boolean cntFwd = srcType.startsWith("forward") || srcType.startsWith("character");
            boolean cntBkp = srcType.startsWith("backup")  || srcType.startsWith("character");
            boolean cntMon = srcType.startsWith("monster")  || srcType.startsWith("character");
            return (ctx, ts) -> {
                int n = ctx.countSelfFieldCards(cntFwd, cntBkp, cntMon, null, null, null, srcElem);
                int reduction = perUnit * n;
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, noTraits));
            };
        }
        Matcher reduceUntilM = FOLLOWUP_POWER_REDUCE_UNTIL.matcher(t);
        if (reduceUntilM.find()) {
            int reduction = reduceUntilM.group(1) != null ? Integer.parseInt(reduceUntilM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceUntilM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
            };
        }
        // Bare power reduce with no timing qualifier — used in former/latter splits (implied EOT)
        Matcher reduceBareM = FOLLOWUP_POWER_REDUCE_BARE.matcher(t);
        if (reduceBareM.find()) {
            int reduction = Integer.parseInt(reduceBareM.group(1));
            EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, noTraits));
            };
        }

        // Until EOT, it also becomes a Forward with N power
        Matcher becomeForwardM = BECOME_FORWARD_UNTIL_EOT_PATTERN.matcher(t);
        if (becomeForwardM.find()) {
            int power = Integer.parseInt(becomeForwardM.group("power"));
            return (ctx, ts) -> ts.forEach(ft -> ctx.makeTargetTemporaryForward(ft, power));
        }

        // Place N [Name] Counter(s) on it
        Matcher placeCounterM = FOLLOWUP_PLACE_COUNTER_ON_IT.matcher(t);
        if (placeCounterM.find()) {
            int    count       = Integer.parseInt(placeCounterM.group("count"));
            String counterName = placeCounterM.group("name").trim();
            return (ctx, ts) -> {
                for (ForwardTarget ft : ts) {
                    CardData card = ft.isP1() ? ctx.p1Forward(ft.idx()) : ctx.p2Forward(ft.idx());
                    ctx.placeCounters(card, counterName, count);
                }
            };
        }

        // Select and remove one counter from the chosen character (dialog if multiple types)
        if (FOLLOWUP_REMOVE_ONE_COUNTER.matcher(t).find()) {
            return (ctx, ts) -> ts.forEach(ctx::removeOneCounterFromTarget);
        }

        // Deal N damage [and/minus M [more] damage] for each [Category X] [Element] Type [of cost N] you control
        Matcher forEachM = FOLLOWUP_DAMAGE_FOR_EACH.matcher(t);
        if (forEachM.find() && forEachM.group("chartype") != null) {
            int    baseDmg  = Integer.parseInt(forEachM.group("base"));
            String perStr   = forEachM.group("per");
            int    perDmg   = perStr != null ? Integer.parseInt(perStr) : 0;
            boolean subtract = "minus".equalsIgnoreCase(forEachM.group("op"));
            String charType = forEachM.group("chartype");
            String category = forEachM.group("category") != null ? forEachM.group("category").trim() : null;
            String element  = forEachM.group("element") != null ? forEachM.group("element").toLowerCase(java.util.Locale.ROOT) : null;
            int    costFilter = forEachM.group("costfilter") != null ? Integer.parseInt(forEachM.group("costfilter")) : -1;
            boolean fwd = charType.matches("(?i)Forwards?|Characters?");
            boolean bkp = charType.matches("(?i)Backups?|Characters?");
            boolean mon = charType.matches("(?i)Monsters?|Characters?");
            return (ctx, ts) -> {
                int n = ctx.countSelfFieldCards(fwd, bkp, mon, null, null, category, element, costFilter);
                int damage = perDmg > 0
                        ? (subtract ? Math.max(0, baseDmg - perDmg * n) : baseDmg + perDmg * n)
                        : baseDmg * n;
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.damageTarget(ft, damage));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.damageTarget(ft, damage));
            };
        }

        return null;
    }

    static int resolveInsteadDamage(GameContext ctx, ForwardTarget t,
            DamageInsteadCondition cond, int base, int alt) {
        boolean condMet = switch (cond) {
            case DamageInsteadCondition.TargetIsActive() ->
                (t.isP1() ? ctx.p1ForwardState(t.idx()) : ctx.p2ForwardState(t.idx())) == CardState.ACTIVE;
            case DamageInsteadCondition.TargetIsMultiElement() ->
                (t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx())).containsElement("Multi-Element");
            default -> insteadConditionMet(ctx, cond);
        };
        return condMet ? alt : base;
    }

    /**
     * Evaluates a {@link DamageInsteadCondition} that does not depend on a specific target
     * (i.e. every variant except {@code TargetIsActive}/{@code TargetIsMultiElement}, which
     * require a {@link ForwardTarget} and must go through {@link #resolveInsteadDamage}).
     */
    static boolean insteadConditionMet(GameContext ctx, DamageInsteadCondition cond) {
        return switch (cond) {
            case DamageInsteadCondition.TargetIsActive() ->
                throw new IllegalArgumentException("TargetIsActive requires resolveInsteadDamage(ctx, target, ...)");
            case DamageInsteadCondition.TargetIsMultiElement() ->
                throw new IllegalArgumentException("TargetIsMultiElement requires resolveInsteadDamage(ctx, target, ...)");
            case DamageInsteadCondition.YouControl(ControlCondition cc, String excludeName) ->
                excludeName != null ? ctx.controlConditionMetExcluding(cc, excludeName) : ctx.controlConditionMet(cc);
            case DamageInsteadCondition.YouReceivedDamageThisTurn() ->
                ctx.selfReceivedDamageThisTurn();
            case DamageInsteadCondition.YouReceivedDamageAtLeast(int min) ->
                ctx.selfDamageCount() >= min;
            case DamageInsteadCondition.YouHaveSummonInBreakZone() ->
                ctx.selfHasSummonInBreakZone();
            case DamageInsteadCondition.OpponentDamageAtLeast(int min) ->
                ctx.opponentDamageCount() >= min;
            case DamageInsteadCondition.OpponentHandAtMost(int max) ->
                ctx.opponentHandSize() <= max;
            case DamageInsteadCondition.YouCastAtLeast(int min) ->
                ctx.selfCardsCastThisTurn() >= min;
            case DamageInsteadCondition.OpponentHasMoreForwards() ->
                ctx.opponentForwardCount() > ctx.selfForwardCount();
            case DamageInsteadCondition.IsExBurst() ->
                ctx.isExBurst();
        };
    }

    // -------------------------------------------------------------------------
    // Choose-character effect parser
    // -------------------------------------------------------------------------


    // -------------------------------------------------------------------------
    // Former/Latter dual-selection parser
    // -------------------------------------------------------------------------

    record TargetDesc(
            boolean fwd, boolean bkp, boolean mon,
            boolean opponentOnly, boolean selfOnly,
            String condition, String element,
            int costVal, String costCmp,
            String excludeName,
            boolean fromBreakZone, boolean opponentBz) {}

    static TargetDesc parseTargetDesc(String desc) {
        Matcher m = TARGET_DESC_PATTERN.matcher(desc.trim());
        if (!m.matches()) return null;

        String ct = m.group("cardtype").toLowerCase(java.util.Locale.ROOT);
        boolean fwd = ct.startsWith("forward") || ct.startsWith("character");
        boolean bkp = ct.startsWith("backup")  || ct.startsWith("character");
        boolean mon = ct.startsWith("monster") || ct.startsWith("character");

        String control      = m.group("control");
        boolean opponentOnly = control != null && control.toLowerCase(java.util.Locale.ROOT).contains("opponent");
        boolean selfOnly     = control != null && control.toLowerCase(java.util.Locale.ROOT).contains("you control");

        int    costVal = m.group("cost") != null ? Integer.parseInt(m.group("cost")) : -1;
        String costCmp = m.group("costcmp");

        String  zone       = m.group("zone");
        boolean fromBz     = zone != null;
        boolean opponentBz = fromBz && zone.toLowerCase(java.util.Locale.ROOT).contains("opponent");

        return new TargetDesc(fwd, bkp, mon, opponentOnly, selfOnly,
                m.group("condition"), m.group("element"),
                costVal, costCmp, m.group("excludename"),
                fromBz, opponentBz);
    }

    static BiConsumer<GameContext, List<ForwardTarget>>
            parseFormerLatterGroupAction(String text) {
        String t = text.trim();

        // "Play it onto the field dull" must precede plain "Play it onto the field"
        if (FOLLOWUP_PLAY_ONTO_FIELD_DULL.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::playTargetOntoFieldDull);
                sortedByIdxDesc(ts, false).forEach(ctx::playTargetOntoFieldDull);
            };

        if (FOLLOWUP_PLAY_ONTO_FIELD.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::playTargetOntoField);
                sortedByIdxDesc(ts, false).forEach(ctx::playTargetOntoField);
            };

        // "Dull or Freeze it" — compact form must precede plain FOLLOWUP_DULL
        if (FOLLOWUP_DULL_OR_FREEZE_COMPACT.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullOrFreezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullOrFreezeTarget);
            };

        if (FOLLOWUP_DULL.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            };

        // Power boost variants (UNTIL must precede plain BOOST since text may omit the trailing "until")
        Matcher boostUntilM = FOLLOWUP_POWER_BOOST_UNTIL.matcher(t);
        if (boostUntilM.find()) {
            int boost = Integer.parseInt(boostUntilM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostUntilM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.boostTarget(ft, boost, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.boostTarget(ft, boost, traits));
            };
        }

        Matcher boostM = FOLLOWUP_POWER_BOOST.matcher(t);
        if (boostM.find()) {
            int boost = Integer.parseInt(boostM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.boostTarget(ft, boost, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.boostTarget(ft, boost, traits));
            };
        }

        // "If its cost equals the cost of the card discarded by the extra cost, break it and draw N" (Fenrir)
        Matcher fenrirM = FOLLOWUP_IF_COST_EQUALS_DISCARD_BREAK_DRAW.matcher(t);
        if (fenrirM.find()) {
            int draw = Integer.parseInt(fenrirM.group("draw"));
            return (ctx, ts) -> {
                int discardCost = ctx.extraCostDiscardedCardCost();
                java.util.List<ForwardTarget> allTargets = new java.util.ArrayList<>(ts);
                for (ForwardTarget ft : allTargets) {
                    CardData fwd = ft.isP1() ? ctx.p1Forward(ft.idx()) : ctx.p2Forward(ft.idx());
                    if (fwd != null && fwd.cost() == discardCost) {
                        ctx.breakTarget(ft);
                        ctx.drawCards(draw);
                    }
                }
            };
        }

        // "Deal it damage equal to the power of the Forward removed by the extra cost" (Titan)
        if (FOLLOWUP_DAMAGE_EXTRA_COST_POWER.matcher(t).find()) {
            return (ctx, ts) -> {
                int power = ctx.extraCostRemovedCardPower();
                ctx.logEntry("Effect: Deal it " + power + " damage (Extra Cost removed Forward power)");
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.damageTarget(ft, power));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.damageTarget(ft, power));
            };
        }

        // "Deal it N damage" — check for a "If you have cast Card Name X other than X this turn" bonus
        Matcher dmgM = FOLLOWUP_DAMAGE.matcher(t);
        if (dmgM.find()) {
            int damage = Integer.parseInt(dmgM.group("amount"));
            Consumer<GameContext> bonus = parseCardNameCastOtherBonusEffect(t.substring(dmgM.end()));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.damageTarget(ft, damage));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.damageTarget(ft, damage));
                if (bonus != null) bonus.accept(ctx);
            };
        }

        return parseTargetAction(t, 0);
    }

    private static Consumer<GameContext> parseCardNameCastOtherBonusEffect(String suffix) {
        if (suffix == null || suffix.isBlank()) return null;
        Matcher m = CAST_CARD_NAME_OTHER_BONUS.matcher(suffix.trim());
        if (!m.find()) return null;
        String cardName  = m.group("name").trim();
        String bonusText = m.group("effect").trim().replaceAll("\\.$", "");
        Consumer<GameContext> bonusEffect = parse(bonusText, null);
        if (bonusEffect == null) return null;
        return ctx -> {
            if (ctx.countCardsNamedCastThisTurn(cardName) > 1)
                bonusEffect.accept(ctx);
        };
    }

    static String getTargetCardName(GameContext ctx, ForwardTarget t) {
        if (t.zone() == ForwardTarget.CardZone.FORWARD)
            return (t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx())).name();
        return null;
    }






    /**
     * Followup wordings that only help the chosen target — power and keyword grants, and
     * activation.  Dragoon 6-104C ("It gains First Strike until the end of the turn") is the
     * motivating case: an AI controller pointing that at the human's Forward is never right.
     */
    private static final Pattern CHOOSE_FOLLOWUP_BENEFITS_TARGET = Pattern.compile(
        "(?i)\\b(?:it|they)\\s+(?:gains?\\s+(?:\\+\\d+\\s+power|Haste|First\\s+Strike|Brave)"
        + "|becomes?\\s+active)\\b|\\bActivate\\s+(?:it|them)\\b");

    /**
     * Followup wordings that harm the chosen target.  Checked first so a mixed effect
     * ("Deal it 5000 damage … it gains …") is never treated as a pure buff.
     */
    private static final Pattern CHOOSE_FOLLOWUP_HARMS_TARGET = Pattern.compile(
        "(?i)\\b(?:deal|break|dull|freeze|discard|loses?|cannot|removes?\\s+it|return\\s+it"
        + "|power\\s+becomes?|put\\s+it\\s+into)\\b");

    /**
     * True when a "choose …" effect's followup only benefits the cards it picks, so an AI
     * controller should aim it at its own side.  A deliberately conservative heuristic: it must
     * match a known buff wording and contain no harmful verb, otherwise the existing
     * prefer-the-opponent behaviour stands.
     */
    static boolean chooseEffectBenefitsTarget(String effectText) {
        if (effectText == null) return false;
        if (CHOOSE_FOLLOWUP_HARMS_TARGET.matcher(effectText).find()) return false;
        return CHOOSE_FOLLOWUP_BENEFITS_TARGET.matcher(effectText).find();
    }

    /**
     * Wraps a "choose …" effect so an AI controller prefers its own cards when the effect is a
     * pure buff.  The flag is advisory and is read only by the AI's auto-selection branch; a
     * human player still picks freely.
     */
    private static Consumer<GameContext> withAiTargetPreference(String effectText, Consumer<GameContext> fn) {
        if (!chooseEffectBenefitsTarget(effectText)) return fn;
        return ctx -> {
            ctx.setAiPrefersOwnTargets(true);
            fn.accept(ctx);
        };
    }



    /** Returns targets belonging to {@code isP1} sorted by descending index (safe for list removal). */
    static java.util.stream.Stream<ForwardTarget> sortedByIdxDesc(
            List<ForwardTarget> targets, boolean isP1) {
        return targets.stream()
                .filter(t -> t.isP1() == isP1)
                .sorted((a, b) -> Integer.compare(b.idx(), a.idx()));
    }

    /**
     * Returns {@code t} to its owner's hand, dispatching by zone so a Monster or Backup that has
     * become a Forward this turn is returned from its actual zone rather than being silently skipped.
     */
    private static void returnTargetToOwnersHand(GameContext ctx, ForwardTarget t) {
        switch (t.zone()) {
            case FORWARD -> { if (t.isP1()) ctx.returnP1ForwardToHand(t.idx()); else ctx.returnP2ForwardToHand(t.idx()); }
            case MONSTER -> { if (t.isP1()) ctx.returnP1MonsterToHand(t.idx()); else ctx.returnP2MonsterToHand(t.idx()); }
            case BACKUP  -> { if (t.isP1()) ctx.returnP1BackupToHand(t.idx());  else ctx.returnP2BackupToHand(t.idx()); }
        }
    }

    /**
     * Returns every target in {@code ts} to its owner's hand. Cards are processed highest-index-first
     * within each side, because returning one card compacts its zone list and would otherwise
     * invalidate a later same-zone target's index (so a second same-controller card is missed).
     */
    static void returnTargetsToOwnersHand(GameContext ctx, List<ForwardTarget> ts) {
        sortedByIdxDesc(ts, true) .forEach(t -> returnTargetToOwnersHand(ctx, t));
        sortedByIdxDesc(ts, false).forEach(t -> returnTargetToOwnersHand(ctx, t));
    }

    /** Deals {@code amount} damage to {@code t}, bypassing reduction effects when {@code unreduced}. */
    static void damageTargetMaybeUnreduced(GameContext ctx, ForwardTarget t, int amount, boolean unreduced) {
        if (unreduced) ctx.damageTargetUnreduced(t, amount);
        else           ctx.damageTarget(t, amount);
    }

    /**
     * Splits {@code damage} evenly across {@code count} targets, rounding each target's share
     * up to the nearest 1000 (per official card rulings, e.g. "divide 12000 damage equally...
     * round up to the nearest 1000") — the total dealt may exceed {@code damage} when it doesn't
     * divide evenly.
     */
    static int roundUpToThousand(int damage, int count) {
        if (count <= 0) return 0;
        return ((damage + count * 1000 - 1) / (count * 1000)) * 1000;
    }

    /** Builds a log suffix like " — Gain +1000 power, Haste, and First Strike until end of turn". */
    static String boostLogSuffix(int amount, EnumSet<CardData.Trait> traits) {
        List<String> parts = new ArrayList<>();
        if (amount != 0)                                  parts.add("+" + amount + " power");
        if (traits.contains(CardData.Trait.HASTE))        parts.add("Haste");
        if (traits.contains(CardData.Trait.FIRST_STRIKE)) parts.add("First Strike");
        if (traits.contains(CardData.Trait.BRAVE))        parts.add("Brave");
        StringBuilder sb = new StringBuilder(" — Gain ");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                if (parts.size() == 2)            sb.append(" and ");
                else if (i == parts.size() - 1)   sb.append(", and ");
                else                              sb.append(", ");
            }
            sb.append(parts.get(i));
        }
        sb.append(" until end of turn");
        return sb.toString();
    }

    /** Returns a human-readable list of trait names, e.g. {@code "First Strike and Brave"}, or {@code ""}. */
    /**
     * Replaces literal periods in {@code source}'s name with the middle-dot character (·) so that
     * lazy regex quantifiers inside CHOOSE_CHARACTER_PATTERN do not mistake a mid-name period-space
     * sequence (e.g. "Dr. Mog") for the sentence delimiter ". ".  Restore with
     * {@link #restorePeriodInName}.
     */
    static String escapePeriodInName(String text, CardData source) {
        if (source == null || !source.name().contains(".")) return text;
        return text.replace(source.name(), source.name().replace('.', '·'));
    }

    /** Inverse of {@link #escapePeriodInName}: restores middle-dots back to periods. */
    static String restorePeriodInName(String text, CardData source) {
        if (source == null || !source.name().contains(".")) return text;
        return text.replace(source.name().replace('.', '·'), source.name());
    }

    /**
     * Removes any trailing/embedded restriction-only sentences already captured as boolean flags
     * (once-per-turn, main-phase-only, your-turn-only, while-attacking, etc.) from {@code text},
     * then strips leftover leading/trailing punctuation.  Returns an empty string if nothing
     * remains after stripping.
     */
    static String stripRestrictionSentences(String text) {
        if (text == null || text.isBlank()) return "";
        String s = text;
        s = CardData.ONCE_PER_TURN_PATTERN               .matcher(s).replaceAll("").trim();
        // Strip the combined "during your Main Phase and if X is in the Break Zone" form before
        // MAIN_PHASE_ONLY_PATTERN so the whole sentence is removed as a unit rather than leaving
        // "and if X is in the Break Zone." as an unparsed secondary fragment.
        s = CardData.OWN_BZ_NAME_REQUIRED_RESTRICTION  .matcher(s).replaceAll("").trim();
        // Same for the "during your turn and if X is in the Break Zone" combined form (Chaos),
        // which must go before YOUR_TURN_ONLY_PATTERN for the same reason.
        s = CardData.YOUR_TURN_AND_BZ_RESTRICTION      .matcher(s).replaceAll("").trim();
        s = CardData.MAIN_PHASE_ONLY_PATTERN              .matcher(s).replaceAll("").trim();
        s = CardData.YOUR_TURN_AND_CONTROL_IF_PATTERN    .matcher(s).replaceAll("").trim();
        // Strip "during your turn and if X is in your hand" before YOUR_TURN_ONLY_PATTERN so the
        // whole sentence is removed as a unit rather than leaving "and if X is in your hand." as a fragment.
        s = CardData.WHILE_CARD_IN_HAND_PATTERN           .matcher(s).replaceAll("").trim();
        s = CardData.YOUR_TURN_ONLY_PATTERN               .matcher(s).replaceAll("").trim();
        s = CardData.OPP_TURN_ONLY_PATTERN                .matcher(s).replaceAll("").trim();
        s = CardData.OPP_NO_CARDS_IN_HAND_RESTRICTION     .matcher(s).replaceAll("").trim();
        s = CardData.WHILE_PARTY_ATTACKING_PATTERN.matcher(s).replaceAll("").trim();
        s = CardData.WHILE_CARD_ATTACKING_PATTERN .matcher(s).replaceAll("").trim();
        s = CardData.WHILE_CARD_BLOCKING_PATTERN  .matcher(s).replaceAll("").trim();
        s = CardData.WHILE_CARD_IN_HAND_PATTERN   .matcher(s).replaceAll("").trim();
        s = CardData.SOURCE_IN_BATTLE_PATTERN     .matcher(s).replaceAll("").trim();
        s = CardData.OPP_DISCARD_THIS_TURN_PATTERN .matcher(s).replaceAll("").trim();
        s = CardData.CAST_SUMMON_THIS_TURN_PATTERN .matcher(s).replaceAll("").trim();
        s = CardData.OWN_DAMAGE_THRESHOLD_RESTRICTION.matcher(s).replaceAll("").trim();
        s = CardData.NAMED_CARD_TOOK_DAMAGE_THIS_TURN_RESTRICTION.matcher(s).replaceAll("").trim();
        s = CardData.SELF_RECEIVED_DAMAGE_THIS_TURN_RESTRICTION   .matcher(s).replaceAll("").trim();
        s = CardData.FORWARD_PUT_TO_BZ_THIS_TURN_RESTRICTION      .matcher(s).replaceAll("").trim();
        s = CardData.JOB_PUT_TO_BZ_THIS_TURN_RESTRICTION          .matcher(s).replaceAll("").trim();
        s = CardData.ELEMENT_FORWARD_ENTERED_THIS_TURN_PATTERN.matcher(s).replaceAll("").trim();
        s = CardData.COUNTER_MINIMUM_RESTRICTION              .matcher(s).replaceAll("").trim();
        s = CardData.OPP_HAND_AT_MOST_RESTRICTION             .matcher(s).replaceAll("").trim();
        s = CardData.SELF_NO_CARDS_IN_HAND_RESTRICTION        .matcher(s).replaceAll("").trim();
        s = CardData.CP_BACKUP_ONLY_ABILITY                   .matcher(s).replaceAll("").trim();
        s = CardData.CP_ELEMENTS_ONLY_ABILITY                 .matcher(s).replaceAll("").trim();
        s = CardData.CONTROL_IF_PATTERN                    .matcher(s).replaceAll("").trim();
        s = CardData.CONTROL_IF_NOT_ANY_PATTERN            .matcher(s).replaceAll("").trim();
        s = CardData.OPPONENT_CONTROLS_N_OR_MORE_PATTERN   .matcher(s).replaceAll("").trim();
        s = CardData.COUNTER_ZERO_RESTRICTION              .matcher(s).replaceAll("").trim();
        s = CardData.EACH_PLAYER_CAN_USE_PATTERN           .matcher(s).replaceAll("").trim();
        // Boilerplate divide-damage rounding clarification — restates a fixed game rule
        // (damage is always allocated in increments of 1000), carries no extra info to describe.
        s = DAMAGE_INCREMENT_CLARIFICATION.matcher(s).replaceAll("").trim();
        // Strip leftover leading/trailing ", and" / "," / "." artifacts
        s = s.replaceAll("^[,.;\\s]+|[,.;\\s]+$", "").trim();
        return s;
    }

    /**
     * Matches the boilerplate "(Units must be 1000.)" / "(damage must be in increments of 1000)"
     * clarification that appears after "divide/split damage among chosen targets as you like/wish"
     * effects (e.g. Yuffie, Faris) — purely restates the standard rounding rule.
     */
    private static final Pattern DAMAGE_INCREMENT_CLARIFICATION = Pattern.compile(
        "(?i)\\(\\s*(?:Units?\\s+must\\s+be\\s+\\d+\\.?|damage\\s+must\\s+be\\s+in\\s+increments\\s+of\\s+\\d+)\\s*\\)\\.?"
    );

    /**
     * Matches "Divide N damage among them as you like/equally" or "...split [it] as you wish/like
     * among the chosen ..." — a chosen-target damage allocation left to the controller's discretion
     * (e.g. Yuffie, Faris). The actual allocation is handled by {@link GameContext#divideDamageAmount};
     * this is used only to name the followup for description purposes.
     */
    private static final Pattern FOLLOWUP_DIVIDE_DAMAGE_AMONG_CHOSEN = Pattern.compile(
        "(?i)\\b(?:divide\\s+\\d+\\s+damage\\s+among\\s+them|split\\s+(?:it\\s+)?as\\s+you\\s+(?:like|wish))\\b"
    );

    static String traitNamesOnly(EnumSet<CardData.Trait> traits) {
        List<String> names = new ArrayList<>();
        if (traits.contains(CardData.Trait.HASTE))        names.add("Haste");
        if (traits.contains(CardData.Trait.FIRST_STRIKE)) names.add("First Strike");
        if (traits.contains(CardData.Trait.BRAVE))        names.add("Brave");
        return switch (names.size()) {
            case 0  -> "";
            case 1  -> names.get(0);
            case 2  -> names.get(0) + " and " + names.get(1);
            default -> names.get(0) + ", " + names.get(1) + ", and " + names.get(2);
        };
    }

    /** Parses a traits string (e.g. {@code ", Haste, and First Strike"}) into a set of traits. */
    static EnumSet<CardData.Trait> parseTraits(String traitStr) {
        EnumSet<CardData.Trait> traits = EnumSet.noneOf(CardData.Trait.class);
        if (traitStr == null || traitStr.isEmpty()) return traits;
        String s = traitStr.toLowerCase();
        if (s.contains("haste"))         traits.add(CardData.Trait.HASTE);
        if (s.contains("first strike"))  traits.add(CardData.Trait.FIRST_STRIKE);
        if (s.contains("brave"))         traits.add(CardData.Trait.BRAVE);
        return traits;
    }

    /**
     * Parses "Until the end of the turn, &lt;cardName&gt; gains +N power [and traits]" as a
     * standalone self-buff.  The subject must match {@code source.name()} (case-insensitive);
     * pronoun subjects ("it", "they") are ignored here — they are handled as Choose followups.
     */











    /**
     * Builds a log suffix like " — Lose 1000 power, Haste, and First Strike until end of turn".
     * Power and traits are listed in order; either may be absent.
     */
    static String reduceLogSuffix(int amount, EnumSet<CardData.Trait> traits) {
        List<String> parts = new ArrayList<>();
        if (amount > 0) parts.add(amount + " power");
        if (traits.contains(CardData.Trait.HASTE))        parts.add("Haste");
        if (traits.contains(CardData.Trait.FIRST_STRIKE)) parts.add("First Strike");
        if (traits.contains(CardData.Trait.BRAVE))        parts.add("Brave");
        StringBuilder sb = new StringBuilder(" — Lose ");
        if (parts.size() == 1) {
            sb.append(parts.get(0));
        } else if (parts.size() == 2) {
            sb.append(parts.get(0)).append(" and ").append(parts.get(1));
        } else if (parts.size() >= 3) {
            for (int i = 0; i < parts.size() - 1; i++) sb.append(parts.get(i)).append(", ");
            sb.append("and ").append(parts.get(parts.size() - 1));
        }
        return sb.append(" until end of turn").toString();
    }






    /**
     * Matches an action ability that temporarily grants the source card its own "deals damage to a
     * Forward → damage increases" field ability:
     * "[Self] gains \"If [Self] deals damage to a Forward, the damage increases by N instead.\"
     * until the end of the turn." (Delita 16-014R). Both the card that "gains" the ability and the
     * subject named inside the quoted ability must be the source card. The granted ability lasts the
     * turn, which is exactly a self outgoing-flat-boost this turn.
     */
    static final Pattern GAINS_OUTGOING_DMG_BOOST_UNTIL_EOT = Pattern.compile(
        "(?i)^(?<subject>.+?)\\s+gains\\s+\"If\\s+(?<inner>.+?)\\s+deals\\s+damage\\s+to\\s+a\\s+Forward" +
        "(?:\\s+opponent\\s+controls?)?,?\\s+the\\s+damage\\s+increases?\\s+by\\s+(?<amount>\\d+)(?:\\s+instead)?\\.\"\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$");


    // ---- Granted field abilities (self "gains \"…\" until the end of the turn") --------------------

    /** A quoted "[Self] can attack twice in the same turn." field ability being granted. */
    private static final Pattern GRANTED_CAN_ATTACK_TWICE = Pattern.compile(
        "(?i)^(?<subj>.+?)\\s+can\\s+attack\\s+twice\\s+in\\s+the\\s+same\\s+turn[.!]?$");

    /** A quoted "[Self] cannot be blocked by a Forward of cost N or more/less." field ability being granted. */
    private static final Pattern GRANTED_CANNOT_BE_BLOCKED_BY_COST = Pattern.compile(
        "(?i)^(?<subj>.+?)\\s+cannot\\s+be\\s+blocked\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+(?<cmp>more|less)[.!]?$");

    /**
     * Routes a quoted field-ability text (the contents of {@code "…"} in a "gains" grant) to the
     * primitive that applies it to {@code source} until end of turn. Returns {@code null} when the
     * quoted ability isn't a supported self-grant (letting other parsers try). The subject named
     * inside the quotes must be the source card.
     */
    static Consumer<GameContext> grantedSelfFieldAbilityEffect(String quoted, CardData source) {
        if (source == null) return null;
        Matcher at = GRANTED_CAN_ATTACK_TWICE.matcher(quoted);
        if (at.matches() && at.group("subj").trim().equalsIgnoreCase(source.name()))
            return ctx -> ctx.grantCanAttackTwiceUntilEndOfTurn(source);
        Matcher nb = GRANTED_CANNOT_BE_BLOCKED_BY_COST.matcher(quoted);
        if (nb.matches() && nb.group("subj").trim().equalsIgnoreCase(source.name())) {
            int cost = Integer.parseInt(nb.group("cost"));
            boolean more = "more".equalsIgnoreCase(nb.group("cmp"));
            return ctx -> ctx.grantSelfCannotBeBlockedByCost(source, cost, more);
        }
        if (exBurstSuppressionMaxCost(quoted, source.name()) != null)
            return ctx -> ctx.grantSelfExBurstSuppression(source);
        // "If [Self] deals damage to a Forward or your opponent, double the damage instead."
        // (Caius 18-108H). Granted verbatim — the damage paths already recognise this wording on a
        // printed field ability, and read granted ones through the same effective-abilities view.
        Matcher dd = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(quoted);
        if (dd.matches() && dd.group("card").trim().equalsIgnoreCase(source.name())) {
            final String granted = quoted;
            return ctx -> ctx.grantSelfFieldAbilityUntilEndOfTurn(source, granted);
        }
        // "If [Self] deals damage to your opponent, the damage becomes N instead."
        // (Ramada 17-125R, Cecil 15-073H, Fang 19-131S) — granted verbatim, same as the doubler.
        Matcher setTo = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_TO_OPPONENT_SETS_TO.matcher(quoted);
        if (setTo.matches() && setTo.group("card").trim().equalsIgnoreCase(source.name())) {
            final String granted = quoted;
            return ctx -> ctx.grantSelfFieldAbilityUntilEndOfTurn(source, granted);
        }
        return null;
    }

    /**
     * Matches both printed wordings of source-scoped EX Burst suppression:
     * <ul>
     *   <li>"Any card [of cost N or less] put in the Damage Zone due to [Name] cannot use its
     *       EX Burst." — Exdeath 1-122H, Arborous Simulacrum 2-118C</li>
     *   <li>"EX Bursts of cards [of cost N or less] put into the Damage Zone due to [Name] cannot
     *       be used." — Shadow Lord B-007 as a printed field ability, and the clause Shadow Lord
     *       12-071R grants itself until end of turn</li>
     * </ul>
     *
     * <p>Distinct from {@link #EX_BURST_SUPPRESSION_PATTERN}, whose "due to this ability" wording
     * spans only one resolution — here the suppression is keyed to the named card, so it applies
     * to that card's combat damage too.
     */
    private static final Pattern EX_BURST_SUPPRESSION_BY_SOURCE = Pattern.compile(
        "(?i)(?:" +
            "Any\\s+cards?(?:\\s+of\\s+cost\\s+(?<cost1>\\d+)\\s+or\\s+less)?\\s+put\\s+in(?:to)?\\s+" +
            "the\\s+Damage\\s+Zone\\s+due\\s+to\\s+(?<subj1>.+?)\\s+cannot\\s+use\\s+(?:its|their)\\s+EX\\s+Bursts?" +
        "|" +
            "EX\\s+Bursts?\\s+of\\s+cards?(?:\\s+of\\s+cost\\s+(?<cost2>\\d+)\\s+or\\s+less)?\\s+put\\s+in(?:to)?\\s+" +
            "the\\s+Damage\\s+Zone\\s+due\\s+to\\s+(?<subj2>.+?)\\s+cannot\\s+be\\s+used" +
        ")[.!]?");

    /**
     * Returns the highest card cost whose EX Burst {@code text} suppresses when the damage is
     * credited to {@code sourceName}, or {@code null} when {@code text} is not a source-scoped
     * EX Burst suppression naming that card.  {@link Integer#MAX_VALUE} means "any cost".
     */
    static Integer exBurstSuppressionMaxCost(String text, String sourceName) {
        if (text == null || sourceName == null) return null;
        Matcher m = EX_BURST_SUPPRESSION_BY_SOURCE.matcher(text.trim());
        if (!m.matches()) return null;
        String subj = m.group("subj1") != null ? m.group("subj1") : m.group("subj2");
        if (subj == null || !subj.trim().equalsIgnoreCase(sourceName)) return null;
        String cost = m.group("cost1") != null ? m.group("cost1") : m.group("cost2");
        return cost == null ? Integer.MAX_VALUE : Integer.parseInt(cost);
    }

    /**
     * "[Self] gains \"[quoted field ability]\" until the end of the turn." (e.g. Tsukinowa).
     *
     * <p>Either quote character is accepted: when this wording is itself nested inside a
     * "select 1 of the 2 following actions" option, the printed text uses single quotes for the
     * inner ability because the option already spent the double quotes (Caius 18-108H).
     */
    static final Pattern GAINS_QUOTED_FIELD_ABILITY_UNTIL_EOT = Pattern.compile(
        "(?i)^(?<subject>.+?)\\s+gains\\s+(?<q>[\"'])(?<quoted>.+?)\\k<q>\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$");


    /**
     * "[Self] gains \"[ability]\"[ and \"[ability]\"] (This effect does not end at the end of the
     * turn.)" — the priming payoff on Odin (XVI) 29-118L and 24-112L.
     *
     * <p>The parenthetical is what separates this from
     * {@link #GAINS_QUOTED_FIELD_ABILITY_UNTIL_EOT}: the grant outlasts the turn, so it routes to
     * the permanent grant primitives rather than the end-of-turn ones. Up to two quoted abilities
     * may be joined by "and" (24-112L grants an attack trigger and a second-attack permission).
     */
    static final Pattern GAINS_QUOTED_ABILITIES_PERMANENT = Pattern.compile(
        "(?i)^(?<subject>.+?)\\s+gains\\s+\"(?<q1>.+?)\"(?:\\s+and\\s+\"(?<q2>.+?)\")?\\s*" +
        "\\(This\\s+effect\\s+does\\s+not\\s+end\\s+at\\s+the\\s+end\\s+of\\s+the\\s+turn\\.?\\)[.!]?$");

    /**
     * Builds the permanent counterpart of {@link #grantedSelfFieldAbilityEffect} for one quoted
     * clause, or {@code null} when the clause is not a grant this engine can apply.
     *
     * <p>A clause is either a complete "When … , …" auto ability — granted by parsing it exactly as
     * the card's own text is parsed — or the "can attack twice in the same turn" permission.
     */
    static Consumer<GameContext> permanentGrantForClause(String quoted, CardData source) {
        Matcher at = GRANTED_CAN_ATTACK_TWICE.matcher(quoted);
        if (at.matches() && at.group("subj").trim().equalsIgnoreCase(source.name()))
            return ctx -> ctx.grantCanAttackTwicePermanently(source);
        // A trigger-bearing clause is granted whole; parseAutoAbilities is the authority on whether
        // it is one, so an unrecognised sentence declines here rather than being silently dropped.
        if (CardData.parseAutoAbilities(quoted).isEmpty()) return null;
        final String granted = quoted;
        return ctx -> ctx.grantSelfAutoAbilityPermanently(source, granted);
    }


    /**
     * "Until the end of the turn, [Self] gains [+N power][, traits] and \"[quoted field ability]\"."
     * (e.g. Ace, Tifa). Applies the power/trait boost via {@link GameContext#boostSourceForward} and
     * routes the quoted ability to its grant primitive; returns {@code null} when the quoted ability
     * isn't a supported self-grant.
     */
    static final Pattern UNTIL_EOT_GAINS_POWER_TRAITS_AND_QUOTED = Pattern.compile(
        "(?i)^Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,\\s+(?<subject>.+?)\\s+gains\\s+" +
        "(?<boosts>.+?)\\s+and\\s+\"(?<quoted>.+?)\"[.!]?$");

    static final Pattern POWER_AMOUNT_PLUS = Pattern.compile("(?i)\\+(\\d+)\\s+power");



















    /**
     * Parses "Your opponent gains control of [CardName]." — permanently transfers the source
     * card itself to its controller's opponent.
     */
    private static Consumer<GameContext> tryParseOpponentGainsControlOfSource(String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_OPPONENT_GAINS_CONTROL.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " — control given to opponent");
            ctx.giveSourceControlToOpponent(source);
        };
    }





    /**
     * "Remove it/them from the game for as long as [Name] is on the field." (Necron ETB) —
     * temporary exile that ends when the named watcher leaves the field.
     */
    static final Pattern FOLLOWUP_REMOVE_FROM_GAME_WHILE_ON_FIELD = Pattern.compile(
        "(?i)Remove\\s+(?:it|them)\\s+from\\s+the\\s+game\\s+for\\s+as\\s+long\\s+as\\s+" +
        "(?<name>.+?)\\s+is\\s+on\\s+the\\s+field\\.?"
    );

    /**
     * "Choose 1 card removed by [Name]'s ability. Put it into the Break Zone." (Necron action
     * ability) — only targets cards exiled by this specific source instance's ETB ability;
     * moving one to the Break Zone cancels its pending return to the field.
     */
    static final Pattern CHOOSE_CARD_REMOVED_BY_SOURCE_TO_BZ = Pattern.compile(
        "(?i)Choose\\s+1\\s+card\\s+removed\\s+by\\s+(?<name>.+?)'s\\s+ability\\.\\s*" +
        "Put\\s+it\\s+into\\s+the\\s+Break\\s+Zone\\.?"
    );


    /**
     * "Until the end of your turn, you can cast [CardName] removed by this ability's cost."
     * (Sephiroth) — registers the card instance(s) removed from the game while paying this
     * ability's costs as castable from the RFP zone for the rest of the turn.
     */
    static final Pattern CAST_RFG_COST_CARD_THIS_TURN = Pattern.compile(
        "(?i)Until\\s+the\\s+end\\s+of\\s+(?:your|the)\\s+turn,?\\s+you\\s+(?:can|may)\\s+cast\\s+" +
        "(?<name>.+?)\\s+removed\\s+by\\s+this\\s+ability(?:'s\\s+cost)?[.!]?"
    );


    /**
     * Returns {@code true} if {@code text} is the Doublecast free-Summons field effect.
     * Used by the AI to gate activation on actually having a Summon chain to exploit.
     */
    static boolean isDoublecastFreeSummonsEffect(String text) {
        return DOUBLECAST_FREE_SUMMONS_PATTERN.matcher(text.trim()).matches();
    }





    private static Consumer<GameContext> tryParseEndOfNextTurnIfCardOnFieldOppLoses(String text) {
        Matcher m = END_OF_NEXT_TURN_IF_CARD_ON_FIELD_OPP_LOSES.matcher(text);
        if (!m.matches()) return null;
        String cardName = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: Scheduled — at end of next turn, if " + cardName + " is on field, opponent loses");
            ctx.scheduleAtEndOfControllerNextTurn(innerCtx -> {
                if (innerCtx.isNamedCardOnField(cardName)) {
                    innerCtx.logEntry(cardName + " is on the field — opponent loses the game");
                    innerCtx.causeOpponentToLose();
                } else {
                    innerCtx.logEntry(cardName + " is NOT on the field — Sin condition not met");
                }
            });
        };
    }







    /** Parses "Draw N card(s)[, then discard M card(s)]" as a standalone effect. */
    private static final Pattern WHEN_YOU_DO_SO_SEQUENCE = Pattern.compile(
        "(?is)(?<primary>.+?)\\.\\s+(?:When|If)\\s+you\\s+do\\s+so,?\\s+(?<followup>.+)"
    );

    /**
     * Matches the optional-cost replay clause appended to Special abilities:
     * "You may [cost]. When/If you do so, use this (special) ability again without paying the cost."
     * Three cost variants:
     * <ul>
     *   <li>{@code payCost}     — element name from "pay 《Earth》"</li>
     *   <li>{@code dullName}    — card name from "dull active &lt;cardName&gt;"</li>
     *   <li>{@code discardName} — card name from "discard 1 Card Name &lt;cardName&gt;"</li>
     * </ul>
     */
    static final Pattern MAY_COST_REPLAY_ABILITY = Pattern.compile(
        "(?i)You\\s+may\\s+(?:" +
            "pay\\s+《(?<payCost>[^》]+)》" +
            "|dull\\s+active\\s+(?<dullName>[^.,]+)" +
            "|discard\\s+1\\s+Card\\s+Name\\s+(?<discardName>[^.,]+)" +
        ")\\s*[.,]?\\s+(?:When|If)\\s+you\\s+do\\s+so,?\\s+" +
        "use\\s+this\\s+(?:special\\s+)?ability\\s+again\\s+without\\s+paying\\s+the\\s+cost[.!]?"
    );

    /**
     * Matches "[You may] pay 《cost》[《cost》…]. If/When you do so, [effect]." as a whole effect —
     * an optional cost that unlocks something, with no target selection in front of it
     * (Jed 24-096R: "When Jed attacks, you may pay 《C》. If you do so, draw 1 card.").
     *
     * <p>The "you may" is optional because an auto ability's parser lifts it into
     * {@link AutoAbility#youMay()} and hands the effect over starting at "pay". Distinct from
     * {@link #FOLLOWUP_YOU_MAY_PAY_ELEMENT_IF_DO_SO}, which is the same wording appearing
     * <em>after</em> a "Choose 1 …" primary and so applies to the chosen targets.
     * Groups: {@code costs} — the run of 《…》 tokens; {@code effect} — what paying buys.
     */
    static final Pattern MAY_PAY_COST_THEN_EFFECT = Pattern.compile(
        "(?is)^(?:you\\s+may\\s+)?pay\\s+(?<costs>(?:《[^》]+》)+)\\s*[.!]?\\s+" +
        "(?:If|When)\\s+you\\s+do\\s+so[,.]?\\s+(?<effect>.+)$"
    );

    /** One 《…》 token of a cost run. */
    private static final Pattern COST_TOKEN = Pattern.compile("《([^》]+)》");

    /**
     * Tallies a run of 《…》 cost tokens into the {cp, crystals} pair plus a single element, the
     * shape {@link GameContext#mayPayCostToEffect} takes. Returns {@code null} for a run this
     * engine cannot price — an 《X》 variable, or more than one distinct element, neither of which
     * the payment primitive can express.
     */
    static Object[] tallyCostRun(String costs) {
        int cp = 0, crystals = 0;
        String element = null;
        Matcher t = COST_TOKEN.matcher(costs);
        while (t.find()) {
            String tok = t.group(1).trim();
            if (tok.equalsIgnoreCase("C"))      crystals++;
            else if (tok.matches("\\d+"))       cp += Integer.parseInt(tok);
            else if (tok.equalsIgnoreCase("X")) return null;
            else if (element == null)           element = tok;
            else if (element.equalsIgnoreCase(tok)) return null;  // 《Wind》《Wind》 — two of one element
            else                                return null;      // mixed elements
        }
        // Elements and generic CP together (《Fire》《1》) would need a compound payment the
        // primitive does not model, so decline rather than under-charge.
        if (element != null && (cp > 0 || crystals > 0)) return null;
        if (crystals > 0 && cp > 0) return null;
        if (cp == 0 && crystals == 0 && element == null) return null;
        return new Object[]{ cp, element, crystals };
    }


    /**
     * Parses "X. When/If you do so, Y." into a sequence: resolve X, then resolve Y only if
     * X made progress (see {@link GameContext#effectMadeProgress()}). Returns {@code null} if
     * either half cannot be parsed, so non-sequence text falls through to the regular matchers.
     */
    private static Consumer<GameContext> tryParseWhenYouDoSoSequence(String text, CardData source, int xValue) {
        Matcher m = WHEN_YOU_DO_SO_SEQUENCE.matcher(text);
        if (!m.find()) return null;
        Consumer<GameContext> primary  = parse(m.group("primary").trim(),  source, xValue);
        Consumer<GameContext> followup = parse(m.group("followup").trim(), source, xValue);
        if (primary == null || followup == null) return null;
        return ctx -> {
            ctx.resetEffectProgress();
            primary.accept(ctx);
            if (ctx.effectMadeProgress()) followup.accept(ctx);
        };
    }

    /** Matches "If a Forward you controlled formed a party this turn, &lt;effect&gt;." */
    private static final Pattern IF_OWN_FORWARD_FORMED_PARTY = Pattern.compile(
        "(?is)^if\\s+a\\s+Forward\\s+you\\s+controlled\\s+formed\\s+a\\s+party\\s+this\\s+turn,\\s+(?<effect>.+)$"
    );

    /**
     * Matches "if you control N or less/fewer [Forwards/Backups/Monsters/Characters], [effect]."
     * Groups: {@code max} — the maximum count; {@code type} — card type; {@code effect} — inner effect.
     */
    static final Pattern IF_CONTROL_AT_MOST = Pattern.compile(
        "(?is)^if\\s+you\\s+control\\s+(?<max>\\d+)\\s+or\\s+(?:less|fewer)\\s+" +
        "(?:Category\\s+(?<category>\\S+)\\s+)?" +
        "(?<type>Forwards?|Backups?|Monsters?|Characters?),\\s+(?<effect>.+)$"
    );

    /**
     * Matches "If all the [Type] you control have [Element] Element, [effect]."
     * Groups: {@code type}, {@code element}, {@code effect}.
     */
    static final Pattern IF_ALL_HAVE_ELEMENT_GATE = Pattern.compile(
        "(?is)^if\\s+all\\s+the\\s+(?<type>Forwards?|Backups?|Characters?|Monsters?)\\s+" +
        "you\\s+control\\s+have\\s+(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)(?:\\s+Element)?,\\s+" +
        "(?<effect>.+)$"
    );

    /** Matches a leading "If you [do not] control &lt;condition&gt;, &lt;effect&gt;" gate. */
    static final Pattern CONTROL_CONDITION_GATE = Pattern.compile(
        "(?is)^if\\s+you\\s+(?<neg>do\\s+not\\s+|don't\\s+)?control\\s+(?<cond>.+?),\\s+(?<effect>.+)$"
    );

    /**
     * Matches "&lt;base&gt;. If you control &lt;condition&gt;, &lt;alternative&gt; instead. [&lt;rest&gt;]" —
     * a replacement clause that swaps the whole base effect for a stronger one when the controller
     * meets a board condition (Black Mage 27-097C: the opponent breaks one of their Forwards of cost
     * 2 or less, or cost 4 or less while you control a Multi-Element Forward).
     *
     * <p>"instead" sits inside the alternative's first sentence, so any sentence after it
     * ({@code rest}) still belongs to the alternative and is re-joined before the branch is parsed.
     * <ul>
     *   <li>Group {@code base} — the effect that applies when the condition is not met</li>
     *   <li>Group {@code cond} — the "you control …" condition</li>
     *   <li>Group {@code alt}  — the replacement effect, up to the word "instead"</li>
     *   <li>Group {@code rest} — further sentences belonging to the replacement effect</li>
     * </ul>
     */
    static final Pattern CONTROL_GATED_INSTEAD_UPGRADE = Pattern.compile(
        "(?is)^(?<base>.+?[.!])\\s+If\\s+you\\s+control\\s+(?<cond>[^,]+?),\\s+" +
        "(?<alt>.+?)\\s+instead[.!]\\s*(?<rest>.*)$"
    );

    /**
     * Matches "if you control [cond] other than [name], [effect]."
     * Used for abilities like "if you control a Category FFCC Forward other than Bel Dat, draw 1 card."
     * Tried before {@link #CONTROL_CONDITION_GATE} because it is more specific.
     */
    static final Pattern IF_CONTROL_COND_OTHER_THAN = Pattern.compile(
        "(?is)^if\\s+you\\s+(?<neg>don't\\s+|do\\s+not\\s+)?control\\s+(?<cond>.+?)\\s+other\\s+than\\s+(?<exclude>[^,]+?),\\s+(?<effect>.+)$"
    );

    /** Matches "If your opponent controls a(n) [cond] [type], [effect]" — e.g. "a damaged Forward". */
    static final Pattern OPP_CONTROL_CARD_GATE = Pattern.compile(
        "(?is)^if\\s+your\\s+opponent\\s+controls\\s+a(?:n)?\\s+" +
        "(?<cond>damaged|dull|active|attacking|blocking)\\s+" +
        "(?<type>Forwards?|Monsters?|Backups?|Characters?),\\s+" +
        "(?<effect>.+)$"
    );

    /** Matches "If your opponent controls N or more [cond] [type], [effect]." */
    static final Pattern IF_OPP_CONTROLS_N_OR_MORE_COND_TYPE_GATE = Pattern.compile(
        "(?i)^[Ii]f\\s+your\\s+opponent\\s+controls\\s+(?<count>\\d+)\\s+or\\s+more\\s+" +
        "(?<cond>dull|damaged|active|attacking|blocking)\\s+" +
        "(?<type>Forwards?|Monsters?|Backups?|Characters?),\\s+" +
        "(?<effect>.+)$"
    );

    /** Matches "if each player has no cards in their hand(s), [effect]." — both hands must be empty. */
    static final Pattern IF_EACH_PLAYER_EMPTY_HAND_GATE = Pattern.compile(
        "(?i)^[Ii]f\\s+each\\s+player\\s+has\\s+no\\s+cards?\\s+in\\s+" +
        "(?:their|his/her|his\\s+or\\s+her)\\s+hands?,\\s*(?<effect>.+)$",
        Pattern.DOTALL
    );

    /** Matches "if there are N or more different Elements among [type] you control, [effect]." */
    static final Pattern IF_N_DIFF_ELEMENTS_AMONG = Pattern.compile(
        "(?is)^if\\s+there\\s+are\\s+(?<min>\\d+)\\s+or\\s+more\\s+different\\s+Elements?\\s+among\\s+" +
        "(?<type>Forwards?|Backups?|Characters?|Monsters?)\\s+you\\s+control[,.]?\\s+(?<effect>.+)$"
    );

    /** Matches "If you have cast N or more cards this turn, &lt;effect&gt;". */
    static final Pattern IF_CAST_AT_LEAST = Pattern.compile(
        "(?is)^if\\s+you\\s+have\\s+cast\\s+(?<min>\\d+)\\s+or\\s+more\\s+cards?\\s+this\\s+turn,\\s+(?<effect>.+)$"
    );

    /**
     * Matches the two-branch element conditional on a cost discard:
     * "If the discarded card is of Elem1 Element, [eff1]. If the discarded card is of Elem2 Element, [eff2]."
     * Groups: {@code elem1}, {@code eff1}, {@code elem2}, {@code eff2}.
     */
    static final Pattern DISCARD_CONDITIONAL_ELEMENT = Pattern.compile(
        "(?i)If\\s+the\\s+discarded\\s+card\\s+is\\s+of\\s+(?<elem1>\\w+)\\s+Element\\s*,\\s*" +
        "(?<eff1>.+?)\\s+" +
        "If\\s+the\\s+discarded\\s+card\\s+is\\s+of\\s+(?<elem2>\\w+)\\s+Element\\s*,\\s*" +
        "(?<eff2>.+)$",
        Pattern.DOTALL
    );

    /**
     * Matches the single-branch, additive-only variant of the discard-element conditional:
     * "If the discarded card is of Elem Element, also &lt;effect&gt;." Unlike
     * {@link #DISCARD_CONDITIONAL_ELEMENT} (two branches covering the whole ability), this
     * appears as a lone secondary clause tacked on after another cost effect (e.g.
     * "Choose 3 cards in your opponent's Break Zone. Remove them from the game. If the discarded
     * card is of Water Element, also draw 1 card, then discard 1 card.") and only ever grants a
     * bonus — there is no "otherwise" branch.
     */
    static final Pattern DISCARD_CONDITIONAL_ELEMENT_SINGLE = Pattern.compile(
        "(?i)^If\\s+the\\s+discarded\\s+card\\s+is\\s+of\\s+(?<elem>\\w+)\\s+Element\\s*,\\s*" +
        "also\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );


    /**
     * Returns {@code true} when a card whose elements are {@code discarded} counts as a card "of
     * {@code elem} Element". Every element of a multi-element card qualifies it independently.
     */
    static boolean discardedIsOfElement(List<String> discarded, String elem) {
        for (String e : discarded)
            if (e.trim().equalsIgnoreCase(elem)) return true;
        return false;
    }


    /**
     * Matches the target-additive discard conditional that tacks an extra effect onto the Forward
     * the primary already chose: "If the discarded card is of Elem Element, it also loses all its
     * abilities until the end of the turn." (The "it/they" pronoun refers back to the chosen target,
     * so the effect is applied to {@link GameContext#lastChosenTargets()} rather than re-selected.)
     */
    static final Pattern DISCARD_CONDITIONAL_TARGET_LOSE_ABILITIES = Pattern.compile(
        "(?i)^If\\s+the\\s+discarded\\s+card\\s+is\\s+of\\s+(?<elem>\\w+)\\s+Element\\s*,\\s*" +
        "(?:it|they)\\s+(?:also\\s+)?loses?\\s+all\\s+(?:its|their)\\s+abilities\\s+" +
        "until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn[.!]?$");


    /**
     * Matches the "instead" (replacement) discard conditional on a self power boost:
     * "[Self] gains +A power until the end of the turn. If the discarded card is a Card Name X,
     * [Self] gains +B power until the end of the turn instead." Applies the boosted (alt) branch
     * when the cost-discarded card is named X, otherwise the base branch — never both.
     */
    static final Pattern DISCARD_CONDITIONAL_SELF_BOOST_INSTEAD = Pattern.compile(
        "(?is)^(?<primary>.+?\\s+gains?\\s+\\+\\d+\\s+power\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn)\\.\\s+" +
        "If\\s+the\\s+discarded\\s+card\\s+is\\s+(?:a\\s+)?Card\\s+Name\\s+(?<name>.+?)\\s*,\\s*" +
        "(?<alt>.+?\\s+gains?\\s+\\+\\d+\\s+power\\s+until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn)\\s+instead[.!]?$");


    /**
     * Matches the no-target additive discard conditional gated on a Multi-Element discard:
     * "draw A card(s), then discard B card(s) from your hand. If the discarded card is a
     * Multi-Element card, draw C card(s), then discard D card(s) from your hand." (Corsair) —
     * repeats the draw/discard only when the first discard was a Multi-Element card.
     */
    static final Pattern DRAW_DISCARD_IF_MULTI_ELEMENT = Pattern.compile(
        "(?i)^draw\\s+(?<d1>\\d+)\\s+cards?,\\s+then\\s+discard\\s+(?<x1>\\d+)\\s+cards?\\s+from\\s+your\\s+hand\\.\\s+" +
        "If\\s+the\\s+discarded\\s+card\\s+is\\s+a\\s+Multi-Element\\s+card,\\s+" +
        "draw\\s+(?<d2>\\d+)\\s+cards?,\\s+then\\s+discard\\s+(?<x2>\\d+)\\s+cards?\\s+from\\s+your\\s+hand[.!]?$");


    /**
     * One branch of a "discard conditional element" ability, e.g. {@code element="Fire"},
     * {@code effectText="until the end of the turn, Firion gains +2000 power and First Strike."}.
     */
    record DiscardElementBranch(String element, String effectText) {}

    /**
     * Exposes the two branches of a "Discard 1 card: If the discarded card is of Elem1 Element,
     * eff1. If the discarded card is of Elem2 Element, eff2." ability for AI evaluation —
     * lets a caller check which element a discard needs to actually produce a benefit before
     * committing to the cost. Returns {@code null} if {@code effectText} isn't this shape.
     */
    static List<DiscardElementBranch> discardConditionalElementBranches(String effectText) {
        Matcher m = DISCARD_CONDITIONAL_ELEMENT.matcher(effectText.trim());
        if (!m.find()) return null;
        return List.of(
            new DiscardElementBranch(m.group("elem1").trim(), m.group("eff1").trim()),
            new DiscardElementBranch(m.group("elem2").trim(), m.group("eff2").trim())
        );
    }

    private static Consumer<GameContext> tryParseIfOwnForwardFormedParty(String text, CardData source, int xValue) {
        Matcher m = IF_OWN_FORWARD_FORMED_PARTY.matcher(text.trim());
        if (!m.matches()) return null;
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            if (ctx.ownForwardFormedPartyThisTurn()) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: no party formed this turn — skipped");
            }
        };
    }













    /**
     * Matches "[Name] breaks after the attack or the block and doesn't deal any damage."
     * (Vincent 2-078R) — the source deals no damage for the rest of the battle and is broken once
     * that battle ends. Group {@code name} is checked against the ability's own source.
     */
    static final Pattern SOURCE_BREAKS_AFTER_COMBAT_NO_DAMAGE = Pattern.compile(
        "(?i)^(?<name>.+?)\\s+breaks?\\s+after\\s+the\\s+attack(?:\\s+or\\s+the\\s+block)?\\s+and\\s+" +
        "doesn'?t\\s+deal\\s+any\\s+damage[.!]?$"
    );


    /**
     * A field ability that continuously grants a quoted ability to Forwards while its own card is
     * on the field (Vayne 9-022L).
     *
     * @param affectsOpponent {@code true} for "Forwards opponent controls", {@code false} for
     *                        "Forwards you control" — relative to the granting card's controller
     * @param abilityText     the granted ability, exactly as quoted on the card
     */
    record ForwardAbilityGrant(boolean affectsOpponent, String abilityText) {}

    /** Matches "All the Forwards [you control|opponent controls] gain "[ability]"." (Vayne 9-022L) */
    static final Pattern FIELD_GRANT_ABILITY_TO_FORWARDS = Pattern.compile(
        "(?i)^All\\s+the\\s+Forwards\\s+(?<who>opponent\\s+controls|you\\s+control)\\s+gains?\\s+" +
        "\"(?<ability>[^\"]+)\"[.!]?$"
    );

    /** Matches the granted ability's own trigger: "At the end of your turn, [effect]". */
    static final Pattern GRANTED_AT_END_OF_YOUR_TURN = Pattern.compile(
        "(?i)^At\\s+the\\s+end\\s+of\\s+your\\s+turn\\s*,\\s+(?<effect>.+)$",
        Pattern.DOTALL
    );



    /**
     * True when {@code effectText} is an "if you don't pay 《…》" gate. Such text carries its own
     * pay-or-decline choice, so callers must not also treat a printed "you may" as an offer to skip
     * the ability outright.
     */
    public static boolean isPayOrElseGate(String effectText) {
        return effectText != null && IF_NOT_PAY_OR_ELSE.matcher(effectText.trim()).matches();
    }




    /**
     * Matches "draw 1 card for each Forward you control. You can only draw up to N cards with this
     * ability." (Hilda 6-122H). Draws {@code min(Forwards you control, N)} — the cap is a hard limit
     * on the ability, not deck protection, so a too-small deck still mills the drawer out.
     */
    static final Pattern DRAW_ONE_PER_FORWARD_CAPPED = Pattern.compile(
        "(?i)^draw\\s+1\\s+card\\s+for\\s+each\\s+Forward\\s+you\\s+control\\.\\s+" +
        "You\\s+can\\s+only\\s+draw\\s+up\\s+to\\s+(?<cap>\\d+)\\s+cards?\\s+with\\s+this\\s+ability[.!]?$");










    /**
     * Returns the card type (e.g. "Summon") when the effect text begins with a
     * "discard 1 &lt;Type&gt;" clause, or {@code null} if no such clause is present.
     * Used by {@code executeAutoAbility} to skip offering the "you may?" dialog
     * when the player has no eligible cards in hand.
     */
    public static String youMayDiscardType(String effectText) {
        Matcher m = DISCARD_TYPE.matcher(effectText);
        if (!m.find()) return null;
        return m.group("type");
    }

    static final Pattern DISCARD_N_CARDS = Pattern.compile(
        "(?i)^discard\\s+(?<count>\\d+)\\s+cards?(?:\\s+from\\s+your\\s+hand)?[.!]?$"
    );


    /** Matches "discard N cards" at the start of an effect text (may have more text after). */
    private static final Pattern DISCARD_N_CARDS_PREFIX = Pattern.compile(
        "(?i)^discard\\s+(?<count>\\d+)\\s+cards?[.!]?(?:\\s|$)"
    );

    /**
     * Returns the discard count when the effect text begins with "discard N cards",
     * or -1 if it doesn't match.
     * Used by {@code executeAutoAbility} to skip offering the "you may?" dialog
     * when the player has fewer cards in hand than required.
     */
    public static int youMayDiscardCount(String effectText) {
        Matcher m = DISCARD_N_CARDS_PREFIX.matcher(effectText.trim());
        if (!m.find()) return -1;
        return Integer.parseInt(m.group("count"));
    }





















    /** Parses "Remove all the cards in your opponent's Break Zone from the game." */
    private static Consumer<GameContext> tryParseRemoveAllOppBzFromGame(String text) {
        if (!REMOVE_ALL_OPP_BZ_FROM_GAME.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Remove all cards in opponent's Break Zone from the game");
            ctx.removeAllOpponentBzFromGame();
        };
    }

    /** Parses "Remove [CardName] from the game." — removes a named card from the field. */
    private static Consumer<GameContext> tryParseRemoveNamedFromGame(String text, CardData source) {
        Matcher m = REMOVE_NAMED_FROM_GAME.matcher(text);
        if (!m.find()) return null;
        String named = m.group("named").trim();
        return ctx -> {
            ctx.logEntry("Effect: Remove " + named + " from the game");
            ctx.removeNamedCardFromGame(named);
        };
    }

    /**
     * Parses "You may remove [CardName] from the game." — shows a yes/no prompt; if accepted,
     * calls {@link GameContext#removeNamedCardFromGame}; if declined, calls
     * {@link GameContext#markEffectFizzled()} so any "If you do so" followup is suppressed.
     */
    private static Consumer<GameContext> tryParseYouMayRemoveNamedFromGame(String text, CardData source) {
        if (source == null) return null;
        Matcher m = YOU_MAY_REMOVE_NAMED_FROM_GAME.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (!name.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            if (!ctx.promptYouMay("Remove " + name + " from the game?")) {
                ctx.markEffectFizzled();
                return;
            }
            ctx.logEntry("Effect: Remove " + name + " from the game");
            ctx.removeNamedCardFromGame(name);
        };
    }


    /**
     * True for wording that points back at the card carrying the ability rather than naming it —
     * "this Forward", "this Character". Granted abilities are written this way, since the text is
     * printed on the granting card but resolves for whichever card received it.
     */
    static boolean isSelfReference(String name) {
        return name.matches("(?i)this\\s+(?:Forward|Backup|Monster|Character|card)");
    }





    private static Consumer<GameContext> tryParseYouMayPutSelfToBZWhenDoSo(String text, CardData source) {
        if (source == null) return null;
        Matcher m = YOU_MAY_PUT_SELF_TO_BZ_WHEN_DO_SO.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        String followupText = m.group("effect").trim();
        Consumer<GameContext> followup = parse(followupText, source);
        if (followup == null) return null;
        return ctx -> ctx.mayBreakSourceWhenDoSo(source, followup);
    }



















    // -------------------------------------------------------------------------
    // Delayed ("at the end of this turn") and recurring end-of-turn field parsers
    // -------------------------------------------------------------------------






    /**
     * Parses "At the end of this turn, &lt;effect&gt;" — wraps any supported mass-field
     * effect so it fires at the beginning of the end phase instead of immediately.
     */
    private static Consumer<GameContext> tryParseDelayedEffect(String text) {
        Matcher m = AT_END_OF_TURN_PATTERN.matcher(text);
        if (!m.find()) return null;
        String rest = m.group("rest");
        Consumer<GameContext> inner = tryParseAllFieldEffect(rest);
        if (inner == null) return null;
        return ctx -> {
            ctx.logEntry("End-of-turn effect queued: " + rest);
            ctx.addEndOfTurnEffect(inner);
        };
    }

    // -------------------------------------------------------------------------
    // All-field-cards effect parser
    // -------------------------------------------------------------------------






    /**
     * Parses "Choose 1 Summon or auto-ability. Cancel its effect." (Y'shtola).
     * The player selects a stack entry; its effect is suppressed when it resolves.
     */
    private static Consumer<GameContext> tryParseCancelStackEntry(String text) {
        if (!STANDALONE_CANCEL_STACK_ENTRY_PATTERN.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Summon or auto-ability — cancel its effect");
            ctx.cancelStackEntry();
        };
    }

    /**
     * Parses the general "Choose 1 [ability type(s)] [optional filter]. Cancel its effect." family.
     * Builds a {@link java.util.function.Predicate} over {@link StackEntry} from the parsed type string.
     */
    private static Consumer<GameContext> tryParseCancelAbilityOnStack(String text) {
        Matcher m = CANCEL_ABILITY_ON_STACK.matcher(text.trim());
        if (!m.find()) return null;
        String types = m.group("types").trim();
        String tgtFilterText = m.group("tgtFilter");
        boolean requiresControllerTarget = tgtFilterText != null
                && tgtFilterText.toLowerCase(java.util.Locale.ROOT).contains("you control");
        java.util.function.Predicate<StackEntry> filter = parseAbilityTypeFilter(types);
        String prompt = "Choose 1 " + types + " to cancel:";
        return ctx -> {
            ctx.logEntry("Effect: Cancel " + types + " on stack");
            ctx.cancelFilteredAbilityOnStack(filter, prompt, requiresControllerTarget);
        };
    }





    /**
     * True if {@code text} is a standalone "If your opponent doesn't pay 《N》, [target action]."
     * whose action is a recognised target action — the reactive "enters opponent's field not from
     * hand" watcher effects (e.g. Remedi) that {@code AutoAbilityTriggers} runs inline with the
     * entering card preloaded as the target.
     */
    static boolean isIfOppNotPayAction(String text) {
        return tryParseIfOppNotPayAction(text) != null;
    }




    /**
     * Returns {@code true} if {@code text} is one of the reactive "chosen by opponent's Summons or
     * abilities" cancel effects — the family that must run <em>inline</em> during the opponent's
     * target selection (so {@code selectCharacters} can drop the cancelled targets), rather than being
     * pushed onto the stack to resolve after the choosing effect has already acted. See
     * {@code AutoAbilityTriggers.executeAutoAbilityImpl}.
     */
    static boolean isChosenSelectionCancelEffect(String text) {
        return tryParseCancelChosenTargetUnlessPay(text)     != null
            || tryParseCancelChosenTargetUnlessDiscard(text)  != null
            || tryParseCancelChosenTargetBare(text)           != null
            || tryParseCancelChosenRevealTopIfType(text)      != null
            || tryParseCancelChosenMillTopIfNotType(text)     != null;
    }


    /**
     * Converts an ability-type string captured by {@link #CANCEL_ABILITY_ON_STACK} or
     * {@link #REDIRECT_ABILITY_TARGET} into a predicate over stack entries.
     * <ul>
     *   <li>"auto-ability" / "auto ability" → auto-abilities only</li>
     *   <li>"action ability" → action abilities (regular and special)</li>
     *   <li>"special ability" → special (named) action abilities only</li>
     *   <li>"ability" → any non-summon, non-EX-burst entry</li>
     *   <li>"summon" → Summons only</li>
     *   <li>Two types joined by " or " → union of the two individual predicates</li>
     * </ul>
     */
    static java.util.function.Predicate<StackEntry> parseAbilityTypeFilter(String types) {
        String t = types.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.equals("ability")) return e -> !e.isSummon() && !e.isExBurstEntry();
        boolean wantsSummon  = t.contains("summon");
        boolean wantsAuto    = t.contains("auto");
        boolean wantsSpecial = t.contains("special");
        boolean wantsAction  = t.contains("action");
        return e -> (wantsSummon  && e.isSummon())
                 || (wantsAuto    && e.isAutoAbility())
                 || (wantsSpecial && e.isSpecialAbility())
                 || (wantsAction  && e.isActionAbility());
    }

    /** "The [targets] you control gain +N power." — companion to CardData's bare-grant pattern. */
    static final Pattern FIELD_GRANT_BARE_PASSIVE = Pattern.compile(
        "(?i)^The\\s+(?:Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "you\\s+control\\s+gains?\\s+\\+\\d+\\s+power[.!]?$"
    );

    /** "The [Job (X)] / Job X / Category Y Forwards you control gain +N power." — bracket or plain form. */
    static final Pattern FIELD_GRANT_JOB_CAT_PASSIVE = Pattern.compile(
        "(?i)^The\\s+" +
        "(?:\\[Job\\s*\\([^)]+\\)\\]|Job\\s+[A-Za-z][A-Za-z\\s''\\-]+?|" +
        "\\[Category\\s*\\([^)]+\\)\\]|Category\\s+\\S+)\\s+" +
        "(?:Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "you\\s+control\\s+gains?\\s+\\+\\d+\\s+power[.!]?$"
    );

    /** "The [targets] opponent controls lose N power." — companion to CardData's opponent-debuff pattern. */
    static final Pattern FIELD_OPPONENT_DEBUFF_PASSIVE = Pattern.compile(
        "(?i)^The\\s+(?:Forwards?(?:\\s+and\\s+Monsters?)?|Backups?|Monsters?|Characters?)\\s+" +
        "(?:your\\s+)?opponent\\s+controls?\\s+loses?\\s+\\d+\\s+power[.!]?$"
    );

    /** "If there are N or more cards in your Break Zone, ..." or "If you have N or more Job X ... in your Break Zone, ..." */
    static final Pattern FIELD_GRANT_BZ_COND_PASSIVE = Pattern.compile(
        "(?i)^If\\s+(?:there\\s+are|you\\s+have)\\s+\\d+\\s+or\\s+more\\s+.+?\\s+in\\s+your\\s+Break\\s+Zone,"
    );

    /** "If there are N or more different Elements among [type] you control, [grant]." */
    static final Pattern FIELD_GRANT_DIFF_ELEM_COND_PASSIVE = Pattern.compile(
        "(?i)^If\\s+there\\s+are\\s+\\d+\\s+or\\s+more\\s+different\\s+Elements?\\s+among\\s+" +
        "(?:Forwards?|Backups?|Characters?|Monsters?)\\s+you\\s+control[,.]"
    );






















    /**
     * Parses "Your opponent puts the top N card(s) of his/her deck into the Break Zone
     * [. Draw M card(s)]".
     */
    private static Consumer<GameContext> tryParseOpponentMill(String text) {
        Matcher m = OPPONENT_MILL_PATTERN.matcher(text);
        if (!m.find()) return null;

        String countStr = m.group("count");
        if (countStr == null) countStr = m.group("count2");
        int    mill     = countStr != null ? Integer.parseInt(countStr) : 1;
        String drawStr  = m.group("draw");
        int    draw     = drawStr  != null ? Integer.parseInt(drawStr)  : 0;

        return ctx -> {
            ctx.logEntry("Effect: Opponent mills " + mill + " card(s)"
                    + (draw > 0 ? ", draw " + draw : ""));
            ctx.opponentMillCards(mill);
            if (draw > 0) ctx.drawCards(draw);
        };
    }


    /** Parses "Put the top N card(s) of your deck into the Break Zone." */
    private static Consumer<GameContext> tryParseSelfMill(String text) {
        Matcher m = SELF_MILL_PATTERN.matcher(text);
        if (!m.find()) return null;

        String countStr = m.group("count");
        int    mill     = countStr != null ? Integer.parseInt(countStr) : 1;

        return ctx -> {
            ctx.logEntry("Effect: Mill " + mill + " card(s) into own Break Zone");
            ctx.millCards(mill);
        };
    }



    /**
     * Matches "choose [up to] N Forwards. Until the end of the turn, they gain "&lt;ability&gt;"."
     * (Machinist) — grants the quoted action ability to each chosen Forward until end of turn.
     * Group {@code upto} present when "up to"; {@code count}; {@code ability} — the quoted grant text.
     */
    static final Pattern CHOOSE_FORWARDS_GAIN_ABILITY_EOT = Pattern.compile(
        "(?i)^choose\\s+(?<upto>up\\s+to\\s+)?(?<count>\\d+)\\s+Forwards?[.!]?\\s+" +
        "Until\\s+the\\s+end\\s+of\\s+the\\s+turn,?\\s+(?:they|it)\\s+gains?\\s+" +
        "\"(?<ability>[^\"]+)\"[.!]?\\s*$",
        Pattern.DOTALL
    );


    /**
     * Matches "choose 1 Forward. Place 1 Petrification Counter on it …" (Medusa). The chosen Forward
     * receives a Petrification Counter; the "cannot attack or block while petrified" restriction and
     * the "《5》: Remove all Petrification Counters" ability are driven off the counter's presence
     * (see {@code MainWindow#isFieldAbilityCannotAttackOrBlock} and {@code addAbilityMenuItems}).
     */
    static final Pattern CHOOSE_FORWARD_PLACE_PETRIFICATION = Pattern.compile(
        "(?i)^choose\\s+1\\s+Forward[.!]?\\s+Place\\s+1\\s+Petrification\\s+Counter\\s+on\\s+it\\b.*",
        Pattern.DOTALL
    );


    /**
     * Matches "Remove all &lt;Name&gt; Counters from this Forward." — removes every counter of the
     * named kind from the ability's own source card (used by Medusa's granted "《5》:" ability).
     */
    static final Pattern REMOVE_ALL_COUNTERS_FROM_SELF = Pattern.compile(
        "(?i)^Remove\\s+all\\s+(?<name>.+?)\\s+Counters\\s+from\\s+this\\s+Forward[.!]?\\s*$"
    );





    /**
     * Builds a single {@link RevealClause} from a parsed condition string and
     * action string.  Returns {@code null} if either the condition or the action
     * is not recognised.
     */
    static RevealClause buildRevealClause(String condText, String actionText, CardData source) {
        Predicate<CardData> condition = parseRevealCondition(condText);
        if (condition == null) return null;
        String cardOp = normalizeRevealOp(actionText);
        if (cardOp != null) return new RevealClause(condition, cardOp, null);
        Consumer<GameContext> effect = parse(actionText, source);
        if (effect != null) return new RevealClause(condition, null, effect);
        return null;
    }

    /**
     * Converts a raw condition string (captured from "If it is/has [cond],") into a
     * {@link Predicate} that tests a {@link CardData} against that condition.
     * Supported forms (article and negation handled first):
     * <ul>
     *   <li>"[not] a/an Forward|Backup|Character|Summon|Monster"</li>
     *   <li>"[not] a/an [Element] [type|card]" — element alone, element+type, element+card</li>
     *   <li>"[not] a/an Job X [or Card Name Y]"</li>
     *   <li>"[not] a/an Card Name X"</li>
     *   <li>"[not] a/an Category X [type]"</li>
     * </ul>
     * Returns {@code null} for unrecognised patterns.
     */
    static Predicate<CardData> parseRevealCondition(String cond) {
        cond = cond.trim();
        boolean negated = false;

        Matcher negM = Pattern.compile("(?i)^not\\s+an?\\s+(.+)$").matcher(cond);
        if (negM.matches()) {
            negated = true;
            cond = negM.group(1).trim();
        } else {
            Matcher artM = Pattern.compile("(?i)^an?\\s+(.+)$").matcher(cond);
            if (artM.matches()) cond = artM.group(1).trim();
        }

        Predicate<CardData> pred;

        // 1. "Job X [or [a/an] Card Name Y]"
        Matcher jobM = Pattern.compile(
            "(?i)^Job\\s+(.+?)(?:\\s+or\\s+(?:an?\\s+)?Card\\s+Name\\s+(.+))?$"
        ).matcher(cond);
        if (jobM.matches()) {
            String job  = jobM.group(1).trim();
            String name = jobM.group(2) != null ? jobM.group(2).trim() : null;
            pred = card -> card.hasJob(job)
                    || (name != null && card.name().equalsIgnoreCase(name));
            return negated ? pred.negate() : pred;
        }

        // 2. "Card Name X"
        Matcher nameM = Pattern.compile("(?i)^Card\\s+Name\\s+(.+)$").matcher(cond);
        if (nameM.matches()) {
            String name = nameM.group(1).trim();
            pred = card -> card.name().equalsIgnoreCase(name);
            return negated ? pred.negate() : pred;
        }

        // 3. "Category X [type|card]"
        Matcher catM = Pattern.compile(
            "(?i)^Category\\s+(\\S+)(?:\\s+(Forward|Character|Backup|Summon|Monster|card))?$"
        ).matcher(cond);
        if (catM.matches()) {
            String cat     = catM.group(1).trim();
            String catType = catM.group(2);
            pred = card -> {
                String cl = cat.toLowerCase(java.util.Locale.ROOT);
                if (!card.category1().toLowerCase(java.util.Locale.ROOT).contains(cl)
                        && !card.category2().toLowerCase(java.util.Locale.ROOT).contains(cl))
                    return false;
                return catType == null || catType.equalsIgnoreCase("card")
                        || meetsTypeCheck(card, catType);
            };
            return negated ? pred.negate() : pred;
        }

        // 4. "[Element] [type|card]" — element alone, element+type, or element+"card"
        Matcher elemM = Pattern.compile(
            "(?i)^(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+(Forward|Character|Backup|Summon|Monster|card))?$"
        ).matcher(cond);
        if (elemM.matches()) {
            String elem     = elemM.group(1);
            String elemType = elemM.group(2);
            pred = card -> {
                if (!card.containsElement(elem)) return false;
                return elemType == null || elemType.equalsIgnoreCase("card")
                        || meetsTypeCheck(card, elemType);
            };
            return negated ? pred.negate() : pred;
        }

        // 5. Simple type
        Matcher typeM = Pattern.compile(
            "(?i)^(Forward|Character|Backup|Summon|Monster)$"
        ).matcher(cond);
        if (typeM.matches()) {
            String type = typeM.group(1);
            pred = card -> meetsTypeCheck(card, type);
            return negated ? pred.negate() : pred;
        }

        return null;
    }

    static boolean meetsTypeCheck(CardData card, String type) {
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "forward"   -> card.isForward();
            case "backup"    -> card.isBackup();
            case "character" -> card.isForward() || card.isBackup() || card.isMonster();
            case "summon"    -> card.isSummon();
            case "monster"   -> card.isMonster();
            default          -> false;
        };
    }

    /**
     * Returns a card-op code if {@code raw} is an action that directly places the
     * revealed card ("play it onto the field [dull]", "add it to your hand",
     * "put it into the Break Zone").  Returns {@code null} for all other actions
     * (standalone effects like "draw N cards", "deal X damage …"), which are then
     * parsed by the main {@link #parse} chain.
     */
    private static String normalizeRevealOp(String raw) {
        if (raw == null) return null;
        String lo = raw.trim().toLowerCase(java.util.Locale.ROOT);
        // Compound actions that involve selecting another card first are handled by parse(),
        // not treated as simple "place revealed card" ops.
        if (lo.contains("select") || lo.contains("choose") || lo.startsWith("your opponent")) return null;
        if (lo.contains("field") && lo.contains("dull")) return "playOntoFieldDull";
        if (lo.contains("field"))  return "playOntoField";
        if (lo.contains("hand"))   return "addToHand";
        if (lo.contains("break"))  return "putToBreakZone";
        if (lo.contains("cast") && lo.contains("cost")) return "castSummonFree";
        return null;
    }



    /**
     * Returns {@code true} if the card has a permanent field ability of the form
     * "[CardName] cannot become dull by your opponent's Summons or abilities."
     */
    static boolean hasCannotBeDulledByOppFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BECOME_DULL_OPP.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }




    /**
     * Returns {@code true} if the card has a permanent field ability of the form
     * "[CardName] cannot be returned to its owner's hand by [your] opponent's Summons or abilities."
     */
    static boolean hasCannotBeReturnedToHandByOppFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BE_RETURNED_TO_HAND_OPP.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the card has the blanket field ability
     * "Characters you control cannot be returned to their owner's hand by your opponent's
     * Summons or abilities." (protects every character its controller controls).
     */
    static boolean hasCharactersCannotBeReturnedFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            if (STANDALONE_CHARACTERS_CANNOT_BE_RETURNED_TO_HAND_OPP.matcher(fa.effectText()).find()) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the card has a permanent field ability of the form
     * "[CardName] cannot be put into the Break Zone by [your] opponent's Summons or abilities."
     */
    static boolean hasCannotBePutIntoBzByOppFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BE_PUT_INTO_BZ_OPP.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }

    /**
     * Matches "Choose 1 Forward you control. Until the end of the turn, it gains +N power[,
     * keywords] and "&lt;quoted grant&gt;" [and "&lt;quoted grant&gt;"…]. If your opponent has
     * received M points of damage or more, all the Forwards you control gain all previous
     * effects instead."
     */
    static final Pattern CHOOSE_OWN_FWD_BOOST_PROTECTIONS_OR_ALL_IF_DMG = Pattern.compile(
        "(?i)^Choose\\s+1\\s+Forward\\s+you\\s+control\\.\\s+" +
        "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,\\s+it\\s+gains\\s+\\+(?<amount>\\d+)\\s+power" +
        "(?:,\\s*(?<traits>(?:Haste|First\\s+Strike|Brave)(?:\\s*,\\s*(?:Haste|First\\s+Strike|Brave))*))?" +
        "(?<quotes>(?:,?\\s+and\\s+\"[^\"]*\")+)\\s*\\.?\\s+" +
        "If\\s+your\\s+opponent\\s+has\\s+received\\s+(?<dmg>\\d+)\\s+points?\\s+of\\s+damage\\s+or\\s+more,\\s+" +
        "all\\s+the\\s+Forwards\\s+you\\s+control\\s+gain\\s+all\\s+(?:the\\s+)?previous\\s+effects\\s+instead\\.?\\s*$"
    );

    /**
     * Matches "Activate all the Forwards you control. Until the end of the turn, all the
     * Forwards you control gain "&lt;quoted grant&gt;" [and "&lt;quoted grant&gt;"…]."
     */
    static final Pattern ACTIVATE_ALL_OWN_FWDS_GAIN_PROTECTIONS = Pattern.compile(
        "(?i)^Activate\\s+all\\s+(?:the\\s+)?Forwards\\s+you\\s+control\\.\\s+" +
        "Until\\s+(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?turn,\\s+all\\s+(?:the\\s+)?Forwards\\s+you\\s+control\\s+gain\\s+" +
        "(?<quotes>\"[^\"]*\"(?:\\s+and\\s+\"[^\"]*\")*)\\s*\\.?\\s*$"
    );

    /** Extracts the contents of each "…" quote in a quoted-grant list. */
    private static final Pattern QUOTED_GRANT = Pattern.compile("\"([^\"]*)\"");

    /**
     * Maps a quoted granted-ability string to the trait that enforces it, or {@code null}
     * when the quote is not a recognized protection grant.
     */
    private static CardData.Trait quotedProtectionTrait(String quote) {
        String q = quote.toLowerCase(java.util.Locale.ROOT);
        if (q.contains("cannot become dull"))                 return CardData.Trait.CANNOT_BE_DULLED_BY_OPP;
        if (q.contains("cannot be returned to its owner"))    return CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP;
        if (q.contains("cannot be decreased"))                return CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP;
        return null;
    }

    /**
     * Parses each quote in {@code quotesRaw} into a protection trait and adds it to
     * {@code traits}. Returns {@code false} (leaving the text unparsed) when any quote
     * is not a recognized protection grant.
     */
    static boolean addQuotedProtectionTraits(String quotesRaw, EnumSet<CardData.Trait> traits) {
        Matcher qm = QUOTED_GRANT.matcher(quotesRaw);
        while (qm.find()) {
            CardData.Trait tr = quotedProtectionTrait(qm.group(1));
            if (tr == null) return false;
            traits.add(tr);
        }
        return true;
    }





    /**
     * Returns {@code true} if the given card has a "Players cannot cast Summons." field ability,
     * meaning all Summon casting (hand or break zone) is prohibited while it is on the field.
     */
    /** Returns {@code true} if the effect text matches a "cancel 1 auto-ability" summon effect. */
    public static boolean cancelsAutoAbility(String effectText) {
        return CANCEL_AUTO_ABILITY_DAMAGE_IF_FORWARD.matcher(effectText.trim()).find();
    }

    public static boolean hasPlayerCannotCastSummonsFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            if (PLAYERS_CANNOT_CAST_SUMMONS.matcher(fa.effectText().trim()).matches()) return true;
        }
        return false;
    }

    /** Returns {@code true} if the card has the "BZ Summons cannot be removed by opponent" field ability. */
    public static boolean hasBzSummonRfgProtection(CardData card) {
        for (FieldAbility fa : card.fieldAbilities())
            if (FA_BZ_SUMMONS_PROTECTED_FROM_OPP_RFG.matcher(fa.effectText()).find()) return true;
        return false;
    }

    /**
     * Returns {@code true} if the card has a field ability of the form
     * "[CardName] cannot be chosen by Summons." — i.e., a permanent self-targeting
     * immunity to any Summon while the card is on the field.
     */
    static boolean hasCannotBeChosenByAnySummonFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BE_CHOSEN_ANY_SUMMON.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the card has a field ability of the form
     * "[CardName] cannot be chosen by Summons or abilities that share its Element."
     * Immunity is evaluated dynamically against the resolving card's element.
     */
    static boolean hasCannotBeChosenByOwnElementFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_OWN_ELEMENT.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }
















    static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    /** Appends {@code term} ("Job X" or "Card Name X") to the appropriate pipe-separated list. */
    static void appendFilterTerm(StringBuilder jobs, StringBuilder names, String term) {
        if (term == null || term.isBlank()) return;
        String trimmed = term.trim();
        Matcher jm = Pattern.compile("(?i)^Job\\s+(?<val>.+)$").matcher(trimmed);
        Matcher nm = Pattern.compile("(?i)^Card\\s+Name\\s+(?<val>.+)$").matcher(trimmed);
        if (jm.matches()) {
            if (jobs.length() > 0)  jobs.append('|');
            jobs.append(jm.group("val").trim());
        } else if (nm.matches()) {
            if (names.length() > 0) names.append('|');
            names.append(nm.group("val").trim());
        }
    }

    private static Consumer<GameContext> tryParseNameJob(String text) {
        if (!NAME_JOB_STANDALONE.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Job");
            String job = ctx.selectJobFromDatabase();
            if (job != null && !job.isBlank()) ctx.logEntry("Named Job: " + job);
        };
    }




    static java.util.Set<String> parseExcludeElements(String excludeStr) {
        if (excludeStr == null || excludeStr.isBlank()) return java.util.Collections.emptySet();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : excludeStr.split("(?i)\\s+(?:or|and)\\s+|,\\s*"))
            out.add(part.trim());
        return out;
    }








    /**
     * Matches Gogo's "Mimic": "Use 1 special ability that a Character has used this turn
     * [other than Ability Name X] without paying the cost." Captures the excluded ability name.
     */
    static final Pattern USE_SPECIAL_ABILITY_USED_THIS_TURN = Pattern.compile(
            "(?i)^Use 1 special ability that a Character has used this turn"
            + "(?:\\s+other than Ability Name (?<excluded>.+?))?"
            + "\\s+without paying the cost\\.?$");

    /**
     * Parses Gogo's "Mimic" — delegates to {@link GameContext#useSpecialAbilityUsedThisTurn}, which
     * lets the acting player replay a special ability used this turn (name-substituted to the mimic).
     */
    private static Consumer<GameContext> tryParseUseSpecialAbilityUsedThisTurn(String text, CardData source) {
        Matcher m = USE_SPECIAL_ABILITY_USED_THIS_TURN.matcher(text.trim());
        if (!m.matches()) return null;
        String excluded = m.group("excluded");
        String excludedName = excluded == null ? null : excluded.trim();
        return ctx -> ctx.useSpecialAbilityUsedThisTurn(source, excludedName);
    }

    /** True when {@code text} is Gogo's "Mimic" effect (see {@link #USE_SPECIAL_ABILITY_USED_THIS_TURN}). */
    static boolean isUseSpecialAbilityUsedThisTurnEffect(String text) {
        return text != null && USE_SPECIAL_ABILITY_USED_THIS_TURN.matcher(text.trim()).matches();
    }













    /**
     * True when a captured {@code verb} group is the "Reveal" wording rather than "Look at".
     * Reveal shows the cards to both players; look at keeps them private to the controller.
     */
    static boolean isRevealWording(String verb) {
        return verb != null && verb.trim().toLowerCase(java.util.Locale.ROOT).startsWith("reveal");
    }

    /**
     * Names the add-1-to-hand look for the pattern-reporting helpers.  Callers check the parser
     * first, so any text left after the clause is the EX Burst rider it accepted.
     */
    private static String lookAddToHandRestBottomPatternName(String text) {
        Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM.matcher(text);
        return m.find() && !text.substring(m.end()).trim().isEmpty()
                ? "LookTopDeckAddToHandRestBottom + AddedCardExBurst"
                : "LookTopDeckAddToHandRestBottom";
    }




    /**
     * Chains a trailing "Then, [effect]" sentence onto an already-parsed primary effect.
     * The ordering dialogs this follows are modal, so the follow-on effect runs only once the
     * player has finished with the primary one.
     *
     * @param base   the parsed primary effect
     * @param tail   the text following the primary clause's match
     * @param source the card that owns the ability
     * @return {@code base} when {@code tail} holds no "Then," sentence; {@code base} followed by
     *         the parsed sentence when it is understood; {@code null} when it is not — a
     *         half-understood ability is reported as unparsed rather than silently dropping half
     *         of what the card does
     */
    static Consumer<GameContext> appendThenClause(
            Consumer<GameContext> base, String tail, CardData source) {
        Matcher m = TRAILING_THEN_CLAUSE.matcher(tail);
        if (!m.matches()) return base;
        Consumer<GameContext> then = parse(m.group("rest").trim(), source);
        return then == null ? null : base.andThen(then);
    }

    /**
     * Returns the effect text of a "Then, [effect]" sentence trailing {@code primary}'s match
     * within {@code text}, or {@code null} when {@code primary} does not match or nothing follows
     * it.  Used by the pattern-reporting helpers to name both halves of a chained ability.
     */
    private static String trailingThenText(String text, Pattern primary) {
        Matcher m = primary.matcher(text);
        if (!m.find()) return null;
        Matcher then = TRAILING_THEN_CLAUSE.matcher(text.substring(m.end()));
        return then.matches() ? then.group("rest").trim() : null;
    }






    /**
     * Matches Libroarian 8-084R's end-of-turn ability: "add N card(s) removed by the previous effect
     * to your hand." optionally followed by "Then, if there are no more cards removed by the previous
     * effect left, put [Self] into the Break Zone."
     * <ul>
     *   <li>Group {@code count} — how many removed cards to take back</li>
     *   <li>Group {@code name}  — the card broken once none are left; absent when there is no such clause</li>
     * </ul>
     */
    static final Pattern ADD_REMOVED_BY_PREVIOUS_EFFECT_TO_HAND = Pattern.compile(
        "(?i)^add\\s+(?<count>\\d+)\\s+cards?\\s+removed\\s+by\\s+the\\s+previous\\s+effect\\s+to\\s+your\\s+hand[.!]?" +
        "(?:\\s*Then[,.]?\\s+if\\s+there\\s+(?:are|is)\\s+no\\s+more\\s+cards?\\s+removed\\s+by\\s+the\\s+previous\\s+" +
        "effect\\s+left[,.]?\\s+put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.!]?)?$",
        Pattern.DOTALL
    );

    /**
     * Matches the "cards removed by [CardName]'s ability" family, which calls back the pile a card
     * built up with its own removal ability:
     * <ul>
     *   <li>"add all the cards removed by X's ability to your hand." — Gutsco 14-010H, Cloud of Darkness B-012</li>
     *   <li>"add 1 card removed by X's ability to your hand, and put the rest of the cards into the
     *       Break Zone." — Cloud of Darkness 10-140S</li>
     * </ul>
     * Group {@code all} is set for the "all the cards" form; {@code rest} for the "put the rest into
     * the Break Zone" tail; {@code name} is checked against the ability's own source.
     */
    static final Pattern ADD_REMOVED_BY_SOURCE_ABILITY_TO_HAND = Pattern.compile(
        "(?i)^add\\s+(?:(?<all>all\\s+the)|1)\\s+cards?\\s+removed\\s+by\\s+(?<name>.+?)'s?\\s+ability\\s+" +
        "to\\s+your\\s+hand(?<rest>\\s*,?\\s*and\\s+put\\s+the\\s+rest\\s+of\\s+the\\s+cards?\\s+into\\s+" +
        "the\\s+Break\\s+Zone)?[.!]?$",
        Pattern.DOTALL
    );

    /**
     * Matches Anima 19-123H's end-of-turn compound: "remove the top card of your deck from the game.
     * Then, if there are N or more cards removed by [Self]'s ability, add them to your hand and break
     * all the Forwards opponent controls."  Parsed as one unit because the threshold counts the pile
     * <em>after</em> this turn's removal, and the payoff is gated on it.
     * <ul>
     *   <li>Group {@code removed} — how many cards this turn's removal takes off the deck</li>
     *   <li>Group {@code threshold} — the pile size that triggers the payoff</li>
     * </ul>
     */
    private static final Pattern REMOVE_TOP_THEN_IF_PILE_AT_LEAST = Pattern.compile(
        "(?i)^remove\\s+the\\s+top\\s+(?:(?<removed>\\d+)\\s+cards?|card)\\s+of\\s+your\\s+deck\\s+from\\s+" +
        "(?:the\\s+)?game[.!]?\\s*Then[,.]?\\s+if\\s+there\\s+(?:are|is)\\s+(?<threshold>\\d+)\\s+or\\s+more\\s+" +
        "cards?\\s+removed\\s+by\\s+(?<name>.+?)'s?\\s+ability[,.]?\\s+add\\s+them\\s+to\\s+your\\s+hand\\s+and\\s+" +
        "break\\s+all\\s+the\\s+Forwards\\s+opponent\\s+controls[.!]?$",
        Pattern.DOTALL
    );


    /** Parses Anima 19-123H's "remove the top card… Then, if there are N or more removed…" compound. */
    private static Consumer<GameContext> tryParseRemoveTopThenPileThreshold(String text, CardData source) {
        if (source == null) return null;
        Matcher m = REMOVE_TOP_THEN_IF_PILE_AT_LEAST.matcher(text.trim());
        if (!m.matches()) return null;
        String named = m.group("name").trim();
        if (!named.equalsIgnoreCase(source.name()) && !isSelfReference(named)) return null;
        String removedStr = m.group("removed");
        int removeCount = removedStr != null ? Integer.parseInt(removedStr) : 1;
        int threshold   = Integer.parseInt(m.group("threshold"));
        return ctx -> {
            ctx.logEntry("Effect: Remove top " + removeCount + " card(s) of deck from game, then check for "
                    + threshold + "+ removed by " + source.name());
            ctx.removeTopCardsOfDeckFromGame(removeCount, source);
            int pile = ctx.cardsRemovedBySourceCount(source);
            if (pile < threshold) {
                ctx.logEntry("Effect: only " + pile + " card(s) removed by " + source.name()
                        + " (need " + threshold + ") — no payoff");
                return;
            }
            ctx.logEntry("Effect: " + pile + " cards removed by " + source.name()
                    + " — adding them to hand and breaking all opposing Forwards");
            ctx.addCardsRemovedBySourceToHand(source, Integer.MAX_VALUE);
            ctx.applyMassFieldEffect(GameContext.MassAction.BREAK, true, false, false,
                    true, false, null, -1, null, -1, null, null);
        };
    }




    private static Consumer<GameContext> tryParseAllMonstersTemporaryForward(String text) {
        Matcher m = ALL_MONSTERS_BECOME_FORWARDS_UNTIL_EOT_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        int power = Integer.parseInt(m.group("power"));
        return ctx -> {
            ctx.logEntry("Effect: All Monsters you control become Forwards with " + power + " power until end of turn");
            ctx.makeAllMonstersTemporaryForwards(power);
        };
    }

    private static Consumer<GameContext> tryParseBecomeForwardUntilEot(String text, CardData source) {
        if (source == null) return null;

        Matcher mAtk = BECOME_FORWARD_AND_ATTACK_TRIGGER.matcher(text);
        if (mAtk.find()) {
            int power = Integer.parseInt(mAtk.group("power"));
            String attackEffectText = mAtk.group("attackEffect").trim();
            Consumer<GameContext> attackEffect = parse(attackEffectText, source);
            if (attackEffect != null) {
                return ctx -> {
                    ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
                    ctx.makeMonsterTemporaryForward(source, power);
                    ctx.logEntry(source.name() + " gains 'When attacks: " + attackEffectText + "'");
                    ctx.addTempAttackTrigger(source, attackEffect);
                };
            }
        }

        Matcher mBlk = BECOME_FORWARD_AND_BLOCK_TRIGGER.matcher(text);
        if (mBlk.find()) {
            int power = Integer.parseInt(mBlk.group("power"));
            String blockEffectText = mBlk.group("blockEffect").trim();
            Consumer<GameContext> blockEffect = parse(blockEffectText, source);
            if (blockEffect != null) {
                return ctx -> {
                    ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
                    ctx.makeMonsterTemporaryForward(source, power);
                    ctx.logEntry(source.name() + " gains 'When blocks: " + blockEffectText + "'");
                    ctx.addTempBlockTrigger(source, blockEffect);
                };
            }
        }

        Matcher mBz = BECOME_FORWARD_AND_BZ_ACTION.matcher(text);
        if (mBz.find()) {
            int power = Integer.parseInt(mBz.group("power"));
            String bzName = mBz.group("bzName").trim();
            String bzEffectText = mBz.group("bzEffect").trim();
            if (parse(bzEffectText, source) != null) {
                return ctx -> {
                    ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
                    ctx.makeMonsterTemporaryForward(source, power);
                    ctx.grantTempBzActionAbility(source, bzName, bzEffectText);
                };
            }
        }

        Matcher m = BECOME_FORWARD_UNTIL_EOT_PATTERN.matcher(text);
        if (!m.find()) return null;
        int power = Integer.parseInt(m.group("power"));
        boolean breakAtEot = AT_END_OF_TURN_BREAK_SOURCE.matcher(text).find();
        return ctx -> {
            ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
            ctx.makeMonsterTemporaryForward(source, power);
            if (breakAtEot) ctx.breakSourceAtEndOfTurn(source);
        };
    }

    /** Returns {@code true} when {@code text} is an "until EOT, becomes a Forward" action-ability effect. */
    static boolean isBecomeForwardUntilEotEffect(String text, CardData source) {
        return tryParseBecomeForwardUntilEot(text, source) != null;
    }

    /**
     * Returns {@code true} when {@code text} is a standalone "source gains +N power until end of
     * turn" self-boost (named subject, not a pronoun like "it"/"they").  Used by the CPU to avoid
     * wasting hand cards on a power boost that provides no combat benefit.
     */
    static boolean isTempSelfPowerBoostEffect(String text, CardData source) {
        if (source == null) return false;
        Matcher m = SELF_POWER_BOOST.matcher(text);
        if (!m.find()) return false;
        String subject = m.group("selfsubject").trim();
        if (subject.equalsIgnoreCase("it") || subject.equalsIgnoreCase("they")) return false;
        return subject.equalsIgnoreCase(source.name());
    }

    /** Returns true when {@code text} is a "gain 《C》 for each CP paid as X" effect. */
    static boolean isGainCrystalPerX(String text) {
        return GAIN_CRYSTAL_PER_X.matcher(text).find();
    }

    /**
     * Returns {@code true} when {@code text} returns Forward(s) to their owner's hand — a bounce
     * such as "Choose 1 Forward. Return it to its owner's hand." — regardless of whether the target
     * is the controller's own or any Forward. Used by the CPU to gate self-sacrifice bounce abilities.
     */
    static boolean isReturnForwardToHandEffect(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        return t.contains("forward") && t.contains("return") && t.contains("hand");
    }

    /**
     * Returns {@code true} when {@code text} bounces only Forward(s) the controller controls — a
     * self-bounce such as "Choose 1 Forward you control. Return it to its owner's hand." Implies
     * {@link #isReturnForwardToHandEffect}. Such a play is never proactively useful (it costs a card
     * for no board gain), so the CPU only performs it reactively to save a Forward from removal.
     */
    static boolean isReturnOwnForwardToHandEffect(String text) {
        if (!isReturnForwardToHandEffect(text)) return false;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        return t.contains("forward you control") || t.contains("forwards you control");
    }

    /**
     * Returns {@code true} when every character {@code text} can choose is a Forward its controller
     * controls, so the whole effect no-ops while that player has none on the field.  Lets the CPU
     * avoid paying an activation cost for nothing.
     */
    static boolean targetsOnlyOwnForwards(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        if (!t.contains("forward you control") && !t.contains("forwards you control")) return false;
        if (t.contains("opponent controls") || t.contains("opponent's field")) return false;
        // Must act on the Forwards standing right now.  A turn-long conditional ("During this turn,
        // if a Forward you control is dealt damage…") can still pay off for a Forward played later
        // in the same turn, so an empty board is not proof it will do nothing.
        return t.contains("choose") || t.contains("all the forwards you control")
                || t.contains("all forwards you control");
    }

    /**
     * Matches a shield granted against the opponent's own effects — "cannot be returned to its
     * owner's hand / chosen / broken / dulled … by your opponent's Summons or abilities".
     */
    private static final Pattern OWN_FORWARD_PROTECTION = Pattern.compile(
        "(?i)cannot\\s+be\\s+(?:returned\\s+to\\s+its\\s+owner's\\s+hand|chosen|broken|dulled" +
        "|removed\\s+from\\s+the\\s+game)[^.]*?\\bby\\s+your\\s+opponent's\\b");

    /**
     * Returns {@code true} when {@code text}'s only benefit is shielding a Forward its controller
     * controls from the opponent's interaction (Krile (XIV) 6-071H: "Choose 1 Forward you control.
     * During this turn, it cannot be returned to its owner's hand by your opponent's Summons or
     * abilities.").
     *
     * <p>Such a shield gains nothing at the moment it resolves — it only pays off while an
     * opponent's effect is already on the stack.  The CPU passes priority rather than responding,
     * so activating one proactively is a wasted cost.  Same reasoning as
     * {@link #isReturnOwnForwardToHandEffect}.
     */
    /**
     * Wordings that pay off the moment the ability resolves, independently of anything the
     * opponent does — a power boost, a keyword grant, or an activation.
     */
    private static final Pattern IMMEDIATE_OWN_BENEFIT = Pattern.compile(
        "(?i)\\+\\d+\\s+power|\\bgains?\\s+(?:Haste|First\\s+Strike|Brave)\\b|\\bActivate\\b");

    static boolean isOwnForwardProtectionEffect(String text) {
        if (!targetsOnlyOwnForwards(text)) return false;
        // A shield bundled with an immediate benefit (20-109H's "+1000 power and …") is still
        // worth using proactively — only a pure shield is reactive-only.
        if (IMMEDIATE_OWN_BENEFIT.matcher(text).find()) return false;
        return OWN_FORWARD_PROTECTION.matcher(text).find();
    }











    private static Consumer<GameContext> tryParseExtraTurnThenLose(String text) {
        if (!EXTRA_TURN_THEN_LOSE.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Take 1 more turn — you lose at the end of that turn");
            ctx.takeExtraTurnThenLose();
        };
    }


    /** Parses "[name] can attack once more this turn." */
    private static Consumer<GameContext> tryParseAttackOnceMore(String text) {
        Matcher m = ATTACK_ONCE_MORE.matcher(text);
        if (!m.find()) return null;
        String name = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: " + name + " can attack once more this turn");
            ctx.grantAttackOnceMore(name);
        };
    }

    /** Parses "During this turn, your opponent may only declare attack once." */
    private static Consumer<GameContext> tryParseOpponentAttackOnceThisTurn(String text) {
        if (!OPPONENT_ATTACK_ONCE_THIS_TURN.matcher(text).find()) return null;
        return ctx -> ctx.limitOpponentAttackDeclarationsThisTurn(1);
    }


    /**
     * Parses "Remove &lt;cardName&gt; from [the] Battle." — removes the named card from the current
     * combat before damage resolves (Escape-type ability).
     */
    private static Consumer<GameContext> tryParseRemoveFromBattle(String text) {
        Matcher m = REMOVE_FROM_BATTLE.matcher(text);
        if (!m.find()) return null;
        String cardName = m.group("card").trim();
        return ctx -> {
            ctx.logEntry("Effect: " + cardName + " escapes from the Battle");
            ctx.removeFromBattle(cardName);
        };
    }




    /**
     * Parses "Search for 1 [filter] [elements] [type] [other than Card Name X] [of cost N] and [destination]".
     * Supported destinations: "add it to your hand", "play it onto the field",
     * "put it under the top card of your/its owner's deck".
     */
    /**
     * Turns a printed card-name list into the pipe-separated form the filters use downstream:
     * "Alisaie or Card Name Alphinaud" → {@code "Alisaie|Alphinaud"}. One separator covers every
     * printed joiner — ", ", ", or ", " or ", " and/or " — and a single name passes through unchanged.
     */
    static String splitCardNameList(String printedNames) {
        return String.join("|",
                printedNames.trim().split("(?i)\\s*,?\\s*(?:(?:and/)?or\\s+)?Card\\s+Name\\s+"));
    }


    /** Matches "play all the Card Name X from your Break Zone onto [the] field [dull]." */
    static final Pattern PLAY_ALL_FROM_BREAK_ZONE_PATTERN = Pattern.compile(
        "(?i)^play\\s+all\\s+the\\s+Card\\s+Name\\s+(?<cardname>.+?)\\s+from\\s+your\\s+Break\\s+Zone\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?$"
    );


    /** Matches "play [source card name] from [your/the] Break Zone onto [the] field [dull]." */
    static final Pattern PLAY_SOURCE_FROM_BREAK_ZONE = Pattern.compile(
        "(?i)^play\\s+(?<name>.+?)\\s+from\\s+(?:your\\s+|the\\s+)?Break\\s+Zone\\s+onto\\s+(?:the\\s+)?field(?:\\s+(?<dull>dull))?[.!]?$"
    );



    /**
     * Routes target selection to either the field or a Break Zone depending on
     * whether {@code zone} is non-null, and forwards all filter parameters.
     */
    /**
     * Returns the cost value that appears most frequently among P1's current Forwards.
     * Used by the opponent AI in dual-number selection to target the ability user's cards.
     * Returns 0 when P1 has no Forwards on the field.
     */
    static int aiMostCommonP1ForwardCost(GameContext ctx) {
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        for (int i = 0; i < ctx.p1ForwardCount(); i++)
            freq.merge(ctx.p1Forward(i).cost(), 1, Integer::sum);
        return freq.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(0);
    }



    /**
     * Prompts the activating player to choose targets for a "Choose N [targets]…" effect
     * <em>before</em> the ability is placed on the stack, so the selections can be stored in
     * {@link StackEntry#preSelectedTargets()} and later inspected (e.g. to enforce "that is
     * choosing a Forward you control" cancel filters).
     *
     * <p>Returns {@code null} when {@code effectText} does not match
     * {@link #CHOOSE_CHARACTER_PATTERN}, or when only break-zone selections would be needed
     * (those are deferred to resolution time since the zone state may change).
     */
    public static List<ForwardTarget> preSelectTargets(String effectText, CardData source, int xValue, GameContext ctx) {
        String text = ELEM_TYPE_OR_ELEM_TYPE.matcher(effectText).replaceAll("$1 or $3 $2");
        text = escapePeriodInName(text, source);
        Matcher m = CHOOSE_CHARACTER_PATTERN.matcher(text);
        if (!m.find()) return null;

        boolean any          = m.group("anycount") != null;
        boolean upTo         = any || m.group("upto") != null;
        int     maxCount     = any ? Integer.MAX_VALUE : Integer.parseInt(m.group("count"));
        String  rawElement   = m.group("element");
        String  element      = rawElement != null && rawElement.contains(" or ")
                ? rawElement.replaceAll("(?i)\\s+or\\s+", "|") : rawElement;
        String  rawCondition  = m.group("condition");
        String  postCondition = m.group("postcondition");
        String  blockingName  = m.group("blockingname");
        String  blockingJob   = m.group("blockingjob");
        String  condition     = blockingName  != null ? "blocking:"     + blockingName.trim()
                              : blockingJob   != null ? "blocking-job:" + blockingJob.trim()
                              : postCondition != null ? "entered the field this turn"
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
            int orJobIdx = tgtLower.indexOf(" or job ");
            String cardNamePart = targets.substring("Card Name ".length(), orJobIdx).trim();
            cardNameFilter = cardNamePart.replaceAll("(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
            String jobPart = targets.substring(orJobIdx + " or job ".length()).trim();
            jobFilter    = jobPart.replaceAll("(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
            inclForwards = tgtLower.contains("forward");
            inclBackups  = tgtLower.contains("backup");
            inclMonsters = tgtLower.contains("monster");
        } else if (tgtLower.startsWith("card name ")) {
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
        boolean inclSummons    = tgtLower.contains("summon")
                              || tgtLower.equals("card") || tgtLower.equals("cards");
        String  categoryFilter = m.group("category");
        String  excludeName    = restorePeriodInName(m.group("excludename") != null ? m.group("excludename").trim() : null, source);
        String  rawExcludeKw   = m.group("excludekw");
        boolean withoutMulticard = "Multicard".equalsIgnoreCase(rawExcludeKw != null ? rawExcludeKw.trim() : null);
        String  rawExcludeElem = m.group("excludeelem");
        String  excludeElem    = rawExcludeElem != null ? rawExcludeElem.trim() : null;
        String  costStr        = m.group("cost");
        String  costListStr    = m.group("costlist");
        String  rawCostCmp     = m.group("costcmp");
        int     costVal2       = costStr != null ? Integer.parseInt(costStr) : -1;
        String  costCmp;
        if (rawCostCmp != null && rawCostCmp.matches("\\d+")) {
            String tail = costListStr != null
                    ? costListStr.replaceAll("\\s+", "") + "," + rawCostCmp
                    : rawCostCmp;
            costCmp = "or_" + tail;
        } else {
            costCmp = rawCostCmp;
        }
        String  powerStr    = m.group("power");
        String  powerCmp    = m.group("powercmp");
        int     powerVal    = powerStr != null ? Integer.parseInt(powerStr) : -1;
        String  control     = m.group("control");
        boolean opponentOnly = control != null && !control.equalsIgnoreCase("you control");
        boolean selfOnly     = "you control".equalsIgnoreCase(control);
        String  zone        = m.group("zone");
        if (zone != null) return null; // break-zone targets deferred to resolution time

        return ctx.selectCharacters(maxCount, upTo, opponentOnly, selfOnly, condition, element,
                costVal2, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElem, withoutMulticard);
    }

    static List<ForwardTarget> selectTargets(GameContext ctx,
            int maxCount, boolean upTo, boolean opponentOnly, boolean selfOnly,
            String condition, String element, String zone, boolean opponentZone,
            int costVal, String costCmp, int powerVal, String powerCmp,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
            String excludeElement, boolean withoutMulticard) {
        return selectTargets(ctx, maxCount, upTo, opponentOnly, selfOnly, condition, element, zone, opponentZone, false,
                costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElement, withoutMulticard);
    }

    static List<ForwardTarget> selectTargets(GameContext ctx,
            int maxCount, boolean upTo, boolean opponentOnly, boolean selfOnly,
            String condition, String element, String zone, boolean opponentZone, boolean bothZones,
            int costVal, String costCmp, int powerVal, String powerCmp,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
            String excludeElement, boolean withoutMulticard) {
        List<ForwardTarget> preloaded = ctx.consumePreloadedTargets();
        if (preloaded != null) {
            ctx.recordChosenTargets(preloaded);
            return applyArmedMarks(ctx, preloaded);
        }
        List<ForwardTarget> result = zone != null
                ? ctx.selectCharactersFromBreakZone(maxCount, upTo, opponentZone, bothZones, condition, element,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElement, withoutMulticard)
                : ctx.selectCharacters(maxCount, upTo, opponentOnly, selfOnly, condition, element,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElement, withoutMulticard);
        ctx.recordChosenTargets(result);
        return applyArmedMarks(ctx, result);
    }

    /**
     * Applies any delayed-trigger mark armed for this selection to {@code targets}, then returns
     * them unchanged. Applying it here — between choosing the targets and the ability acting on
     * them — is what lets "When it is put from the field into the Break Zone this turn, …" survive
     * a primary that breaks the target outright.
     */
    private static List<ForwardTarget> applyArmedMarks(GameContext ctx, List<ForwardTarget> targets) {
        int bzDraw = ctx.consumeDrawOnFieldToBzMark();
        if (bzDraw > 0) targets.forEach(t -> ctx.markTargetDrawOnFieldToBzThisTurn(t, bzDraw));
        return targets;
    }
}
