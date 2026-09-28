package shufflingway;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs a resolved ability against a {@link GameContext} that records every call and decides
 * nothing: every boolean gets the same answer, every {@code select…} returning a list gets one
 * {@link ForwardTarget}, every {@link CardData} is null and everything else is a zero or an empty
 * value. What an ability calls under that board is what it does, as opposed to what its
 * description says it does.
 *
 * <p>The answers are crude on purpose — they reach every branch that does not need a real card —
 * so an exception in the log is often an artifact of the null cards rather than a bug. Confirm
 * on a real board before acting on one.
 */
final class RecordingContext {

	/** Prefix of the entry recorded when the ability throws. */
	static final String THREW = "!!";

	private RecordingContext() {}

	/** Every call {@code fn} makes, in order, as {@code name[args]}; a throw ends the list. */
	static List<String> record(Consumer<GameContext> fn, boolean boolAnswer) {
		List<String> calls = new ArrayList<>();
		if (fn == null) return calls;
		GameContext rec = (GameContext) Proxy.newProxyInstance(
				GameContext.class.getClassLoader(), new Class<?>[]{GameContext.class},
				(proxy, method, a) -> {
					calls.add(method.getName() + Arrays.deepToString(a));
					if (method.getName().equals("consumePreloadedTargets")) return null;
					if (method.getReturnType() == List.class && method.getName().startsWith("select"))
						return List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));
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
