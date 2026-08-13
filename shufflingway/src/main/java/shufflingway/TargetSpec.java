package shufflingway;

/**
 * The target constraints a "Choose N …" effect imposes, decoded from its card text — the exact
 * argument list {@link GameContext#selectCharacters} takes.
 *
 * <p>Produced by {@link ActionResolver#targetSpec}. Two callers need it and must agree:
 * {@link ActionResolver#preSelectTargets} passes it straight to {@code selectCharacters} when the
 * effect first chooses, and the redirect path replays it to decide whether a replacement target
 * would have been a legal choice for that effect — "The newly chosen target must be a valid
 * choice", printed on every effect that offers a free pick.
 *
 * <p>{@code null} rather than an all-permissive instance is what a caller gets for text this
 * cannot decode, so an unrecognised effect imposes no constraint instead of silently imposing an
 * empty one.
 */
public record TargetSpec(
        int     maxCount,
        boolean upTo,
        boolean opponentOnly,
        boolean selfOnly,
        String  condition,
        String  element,
        int     costVal,
        String  costCmp,
        int     powerVal,
        String  powerCmp,
        boolean inclForwards,
        boolean inclBackups,
        boolean inclMonsters,
        String  jobFilter,
        String  cardNameFilter,
        String  categoryFilter,
        String  excludeName,
        boolean inclSummons,
        String  excludeElement,
        boolean withoutMulticard
) {}
