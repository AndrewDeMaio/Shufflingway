package shufflingway;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Runs a resolved ability against a {@link GameContext} that records every call and decides
 * nothing: every boolean gets the same answer, every {@code select…} returning a list gets one
 * {@link ForwardTarget}, the card in that slot is one plain Forward, every other {@link CardData} is
 * null and everything else is a zero or an empty value. What an ability calls under that board is what it does, as opposed to what its
 * description says it does.
 *
 * <p>The answers are crude on purpose — they reach every branch that does not need a real card —
 * so an exception in the log is often an artifact of the null cards rather than a bug. Confirm
 * on a real board before acting on one.
 */
final class RecordingContext {

	/** Prefix of the entry recorded when the ability throws. */
	static final String THREW = "!!";

	/** What every {@code select…} picks. */
	private static final ForwardTarget TARGET = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);

	/**
	 * Lookups by slot or by target, which answer {@link #SLOT_CARD} rather than null: a slot a
	 * {@code select…} just returned holds a card on any real board, and a null there threw in
	 * every ability that reads the chosen card's name or cost.
	 */
	private static final Set<String> SLOT_LOOKUPS =
			Set.of("p1Forward", "p2Forward", "p1BreakZoneCard", "p2BreakZoneCard", "targetCard");

	/** The card in every slot: a cost-2, 5000-power Forward. */
	private static final CardData SLOT_CARD = new CardData(null, "Recorded Forward", "Fire", 2, 5000, "Forward",
			false, 0, false, false, Set.of(), 0, List.of(), null, List.of(),
			List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
			false, false, null, false, false, false, false, false, 1,
			null, null, null, "");

	private RecordingContext() {}

	/** Every call {@code fn} makes, in order, as {@code name[args]}; a throw ends the list. */
	static List<String> record(Consumer<GameContext> fn, boolean boolAnswer) {
		List<String> calls = new ArrayList<>();
		if (fn == null) return calls;
		// Held and handed back as the real context does, so an ability that re-enters the choose
		// chain on cards already chosen shows up as choosing again if it does.
		Object[] preloaded = {null};
		GameContext rec = (GameContext) Proxy.newProxyInstance(
				GameContext.class.getClassLoader(), new Class<?>[]{GameContext.class},
				(proxy, method, a) -> {
					calls.add(method.getName() + Arrays.deepToString(a));
					if (method.getName().equals("preloadTargets")) { preloaded[0] = a[0]; return null; }
					if (method.getName().equals("consumePreloadedTargets")) {
						Object ts = preloaded[0];
						preloaded[0] = null;
						return ts;
					}
					if (method.getName().equals("lastChosenTargets")) return List.of(TARGET);
					if (method.getName().equals("selectOppForwardsForTieredDamage"))
						return List.of(new GameContext.TieredDamagePick(TARGET, ((int[]) a[0])[0]));
					if (method.getReturnType() == List.class && method.getName().startsWith("select"))
						return List.of(TARGET);
					if (SLOT_LOOKUPS.contains(method.getName())) return SLOT_CARD;
					// Named, or null when cancelled; never "".
					if (method.getName().equals("selectElement")) return "Fire";
					if (method.getReturnType() == CardData.class) return null;
					return method.getReturnType() == boolean.class ? boolAnswer : dflt(method.getReturnType());
				});
		try { fn.accept(rec); } catch (Exception | StackOverflowError e) { calls.add(THREW + e); }
		return calls;
	}

	private static Object dflt(Class<?> t) {
		if (!t.isPrimitive()) {
			if (t == String.class) return "";
			if (t == List.class) return List.of();
			if (t == Map.class) return Map.of();
			return null;
		}
		if (t == boolean.class) return false;
		if (t == char.class) return '\0';
		if (t == long.class) return 0L;
		if (t == float.class) return 0f;
		if (t == double.class) return 0d;
		if (t == void.class) return null;
		return 0;
	}
}
