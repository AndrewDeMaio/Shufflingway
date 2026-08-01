package shufflingway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;

/**
 * Damage resolution: how much damage a source actually deals and a target actually
 * takes, after every shield, multiplier, reduction and redirect has been applied.
 *
 * <p>Extracted from {@link MainWindow}, which keeps a one-line delegator for each
 * method so existing call sites are unaffected.  None of this logic touches Swing;
 * it reads board and turn state through {@code mw}.
 */
class DamageResolver {

	private final MainWindow mw;

	DamageResolver(MainWindow mw) {
		this.mw = mw;
	}

	/**
	 * Applies all incoming-damage modifiers for {@code idx} and returns the final amount.
	 * One-time shields (next-damage-zero, next-damage-reduction) are consumed here.
	 * {@code fromAbility} is true when the damage source is an effect/summon, false for combat.
	 * {@code unreduced} bypasses all reductions: one-shot shields are still consumed but
	 * their reduction is not applied; persistent shields stay up and also do not reduce.
	 */
	int modifyIncomingDamage(boolean isP1, int idx, int rawAmount, boolean fromAbility, boolean unreduced) {
		return modifyIncomingDamage(isP1, ForwardTarget.CardZone.FORWARD, idx, rawAmount, fromAbility, unreduced);
	}

	/**
	 * Zone-aware variant: applies incoming-damage modifiers to a card acting as a Forward from any
	 * zone (a real Forward, or a Monster/Backup temporarily a Forward). A card acting as a Forward
	 * is a Forward for every eligible purpose, so the self- and field-wide protections apply to it.
	 */
	int modifyIncomingDamage(boolean isP1, ForwardTarget.CardZone zone, int idx, int rawAmount,
			boolean fromAbility, boolean unreduced) {
		CardData card = mw.fieldCombatant(isP1, zone, idx);
		if (card == null) return rawAmount;
		int amount = rawAmount * (mw.turn(isP1).forwardIncomingDmgMult)
		                       * mw.perCardIncomingDmgMultiplierMap.getOrDefault(card, 1);

		// Incoming damage increase (debuff) — applied regardless of reduction-disabled flag
		if (mw.incomingDmgIncreaseMap.containsKey(card))
			amount += mw.incomingDmgIncreaseMap.get(card);
		if (mw.globalForwardIncomingDmgIncrease > 0)
			amount += mw.globalForwardIncomingDmgIncrease;

		// Outgoing damage boost from caster's side field cards (e.g. Caetuna — Fire Summon +1000)
		if (fromAbility) amount = applyCasterSideElementSummonDamageBoosts(amount, isP1);
		// Outgoing damage boost from caster's side field cards when source is an Element Forward
		if (fromAbility) amount = applyCasterSideElementForwardDamageBoosts(amount, isP1);

		// Outgoing damage doubler from the source card's own field ability (ability damage to opponent's Forward)
		if (fromAbility && mw.currentAbilitySource != null && mw.currentAbilitySourceIsP1 != isP1
				&& !mw.lostAbilitiesCards.contains(mw.currentAbilitySource)) {
			for (FieldAbility fa : mw.effectiveFieldAbilities(mw.currentAbilitySource)) {
				Matcher fam = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(fa.effectText());
				if (!fam.find() || !fam.group("card").trim().equalsIgnoreCase(mw.currentAbilitySource.name())) continue;
				if (!fam.group("target").toLowerCase().contains("forward")) continue;
				int before = amount;
				amount *= 2;
				mw.logEntry(mw.currentAbilitySource.name() + " — outgoing damage doubled (" + before + " → " + amount + ")");
			}
		}

		// Self unconditional outgoing flat boost from the source card's own field ability
		// (ability damage to a Forward), e.g. Foulander's attack-trigger damage.
		if (fromAbility && card.isForward() && mw.currentAbilitySource != null
				&& mw.currentAbilitySourceIsP1 != isP1) {
			int boost = mw.selfOutgoingFlatBoostVsForward(mw.currentAbilitySource, mw.currentAbilitySourceIsP1);
			if (boost > 0) {
				int before = amount;
				amount += boost;
				mw.logEntry(mw.currentAbilitySource.name() + " — outgoing damage +" + boost
						+ " to Forward (" + before + " → " + amount + ")");
			}
		}

		// Source-based nullification (these block damage by type of source, not by reducing amount)
		if (fromAbility) {
			// Nullify all ability/summon damage
			if (mw.nullifyAbilityDmgSet.contains(card)) return 0;
			// Filter-based nullification: covers Forwards that entered the field after the shield resolved
			for (Predicate<CardData> f : (mw.turn(isP1).nullifyAbilityDmgFilters))
				if (f.test(card)) return 0;
			// Nullify ability-only damage (not Summons)
			if (!mw.currentResolutionIsSummon && mw.nullifyAbilityOnlyDmgSet.contains(card)) return 0;
			// Element-scoped nullification (Hein ability): covers both targeted and AoE damage
			String nullifyElem = mw.nullifyElementDamageMap.get(card);
			if (nullifyElem != null) {
				CardData resCard = mw.currentResolutionIsSummon ? mw.currentSummonSource : mw.currentAbilitySource;
				if (resCard != null && mw.effectiveElements(resCard).contains(nullifyElem)) return 0;
			}
			// Element-scoped, ability-only nullification (Rubicante ability): Summons are not covered
			if (!mw.currentResolutionIsSummon) {
				String nullifyAbilityElem = mw.nullifyElementDamageAbilityOnlyMap.get(card);
				if (nullifyAbilityElem != null && mw.currentAbilitySource != null
						&& mw.effectiveElements(mw.currentAbilitySource).contains(nullifyAbilityElem)) return 0;
			}
			// Passive field ability: nullify Summon-only damage
			if (mw.currentResolutionIsSummon) {
				for (FieldAbility fa : card.fieldAbilities()) {
					Matcher m = AutoAbilityTriggers.FA_NULLIFY_SUMMON_DAMAGE.matcher(fa.effectText());
					if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) return 0;
				}
			}

			// Passive field ability: nullify ability-source damage entirely (not Summons) — e.g. Philia
			if (!mw.currentResolutionIsSummon) {
				for (FieldAbility fa : card.fieldAbilities()) {
					Matcher m = AutoAbilityTriggers.FA_NULLIFY_ABILITY_DAMAGE.matcher(fa.effectText());
					if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) {
						mw.logEntry(card.name() + " — ability damage nullified by field ability (→ 0)");
						return 0;
					}
				}
			}

			// Passive field ability: nullify opponent's non-Summon ability damage
			if (!mw.currentResolutionIsSummon && mw.currentAbilitySourceIsP1 != isP1) {
				for (FieldAbility fa : card.fieldAbilities()) {
					Matcher m = AutoAbilityTriggers.FA_NULLIFY_OPPONENT_ABILITY_DAMAGE.matcher(fa.effectText());
					if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) {
						mw.logEntry(card.name() + " — opponent ability damage nullified by field ability (→ 0)");
						return 0;
					}
				}
			}

			// Passive field ability: reduce ability-source damage by N (gated on damage threshold)
			if (!mw.currentResolutionIsSummon) {
				int dmgInZone = isP1 ? mw.gameState.getP1DamageZone().size() : mw.gameState.getP2DamageZone().size();
				for (FieldAbility fa : card.fieldAbilities()) {
					if (fa.damageThreshold() > 0 && dmgInZone < fa.damageThreshold()) continue;
					Matcher m = AutoAbilityTriggers.FA_REDUCE_ABILITY_DAMAGE.matcher(fa.effectText());
					if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) {
						int reduction = Integer.parseInt(m.group("reduction"));
						int before = amount;
						amount = Math.max(0, amount - reduction);
						mw.logEntry(card.name() + " — ability damage reduced by " + reduction + " (" + before + " → " + amount + ")");
					}
				}
			}
		}

		// Passive field ability: nullify battle damage from a Forward with specific traits (e.g. Haste, First Strike)
		if (!fromAbility && mw.currentBattleAttacker != null) {
			for (FieldAbility fa : card.fieldAbilities()) {
				Matcher fam = AutoAbilityTriggers.FA_NULLIFY_TRAIT_FORWARD_DAMAGE.matcher(fa.effectText());
				if (!fam.find() || !fam.group("card").trim().equalsIgnoreCase(card.name())) continue;
				String t1 = fam.group("trait1").trim();
				String t2raw = fam.group("trait2");
				String t2 = t2raw != null ? t2raw.trim() : null;
				CardData.Trait trait1 = mw.traitFromName(t1);
				CardData.Trait trait2 = t2 != null ? mw.traitFromName(t2) : null;
				boolean t1Match = trait1 != null && mw.fieldForwardTrait(mw.currentBattleAttackerIsP1, mw.currentBattleAttackerZone, mw.currentBattleAttackerIdx, trait1);
				boolean t2Match = trait2 != null && mw.fieldForwardTrait(mw.currentBattleAttackerIsP1, mw.currentBattleAttackerZone, mw.currentBattleAttackerIdx, trait2);
				if (t1Match || t2Match) {
					mw.logEntry(card.name() + " — battle damage nullified (attacker has " + (t1Match ? t1 : t2) + ")");
					return 0;
				}
			}
		}

		if (unreduced) {
			// Consume one-shot shields so they are spent, but do not apply any reduction.
			// Persistent shields ("until end of turn") remain in place unchanged.
			mw.nextIncomingDmgZeroSet.remove(card);
			mw.nextIncomingDmgReduceMap.remove(card);
			if (fromAbility) mw.nextAbilityDmgReduceMap.remove(card);
			return amount;
		}

		// If damage reductions are disabled for this side, skip all target-side protections
		if (mw.turn(isP1).dmgReductionDisabled) return amount;

		// One-time: next incoming damage = 0
		if (mw.nextIncomingDmgZeroSet.remove(card)) return 0;

		// One-time: next incoming damage reduced by N
		if (mw.nextIncomingDmgReduceMap.containsKey(card))
			amount = Math.max(0, amount - mw.nextIncomingDmgReduceMap.remove(card));

		// One-time: next ability/summon damage reduced by N
		if (fromAbility && mw.nextAbilityDmgReduceMap.containsKey(card))
			amount = Math.max(0, amount - mw.nextAbilityDmgReduceMap.remove(card));

		// Passive field ability: self-targeted incoming damage modifier
		// ("by a Forward / by Summon or ability / other than battle damage / less than power / any source")
		for (FieldAbility fa : card.fieldAbilities()) {
			Matcher fam = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(fa.effectText());
			if (!fam.find() || !fam.group("card").trim().equalsIgnoreCase(card.name())) continue;
			amount = applyDamageModifierMatch(fam, amount, isP1, zone, idx, fromAbility, card.name());
		}

		// Incoming damage modifier granted to this Forward by a counter grant (e.g. Kimahri's
		// Ronso Counter, Tidus's Guardian Counter). "If this Forward is dealt damage …" — the
		// subject is implicit, so no card-name match is required.
		for (String granted : mw.counterGrantedAbilities(card, isP1)) {
			Matcher fam = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(granted);
			if (fam.find()) amount = applyDamageModifierMatch(fam, amount, isP1, zone, idx, fromAbility, card.name());
		}

		// Passive field ability: self-targeted incoming damage reduction while dull
		for (FieldAbility fa : card.fieldAbilities()) {
			Matcher m = AutoAbilityTriggers.FA_DAMAGE_WHILE_DULL_REDUCTION.matcher(fa.effectText());
			if (!m.find() || !m.group("card").trim().equalsIgnoreCase(card.name())) continue;
			CardState state = mw.fieldTargetState(new ForwardTarget(isP1, idx, zone));
			if (state == CardState.DULL) {
				int reduction = Integer.parseInt(m.group("amount"));
				int before = amount;
				amount = Math.max(0, amount - reduction);
				mw.logEntry(card.name() + " — damage while dull reduced by " + reduction + " (" + before + " → " + amount + ")");
			}
		}

		// Passive field ability on other friendly cards: field-wide incoming damage modifier
		amount = applyFieldWideDamageModifiers(amount, card, isP1, zone, idx, fromAbility);

		// Global per-player damage reduction
		int globalRed = mw.turn(isP1).globalDmgReduction;
		if (globalRed > 0) amount = Math.max(0, amount - globalRed);

		// Per-card non-lethal protection: damage < this card's effective power → becomes 0
		if (mw.perCardNonLethalDmgSet.contains(card)) {
			int power = mw.fieldForwardPower(isP1, zone, idx);
			if (amount < power) return 0;
		}

		// Global non-lethal protection: damage < forward's effective power → becomes 0
		boolean nonLethal = mw.turn(isP1).nonLethalProtection;
		if (nonLethal) {
			int power = mw.fieldForwardPower(isP1, zone, idx);
			if (amount < power) return 0;
		}

		return amount;
	}

	/**
	 * Applies one matched {@link AutoAbilityTriggers#FA_DAMAGE_MODIFIER} effect (reduce/set/increase/
	 * double) to {@code amount}, honoring its optional damage threshold and source clause. Shared by
	 * a Forward's own field ability and abilities granted to it via a counter grant. {@code subjectName}
	 * is used only for the log line. Returns the (possibly modified) amount.
	 */
	int applyDamageModifierMatch(Matcher fam, int amount, boolean isP1,
			ForwardTarget.CardZone zone, int idx, boolean fromAbility, String subjectName) {
		String threshStr = fam.group("threshold");
		if (threshStr != null && amount < Integer.parseInt(threshStr)) return amount;
		String src = fam.group("sourceclause");
		boolean applies;
		if (src == null || src.isBlank()) {
			applies = true;
		} else {
			String srcN = src.trim().toLowerCase();
			if (srcN.startsWith("less than") && srcN.endsWith("power")) {
				int power = mw.fieldForwardPower(isP1, zone, idx);
				applies = amount < power;
			} else if (srcN.startsWith("by a forward")) {
				applies = !fromAbility;
			} else if (srcN.contains("summon") && !srcN.contains("abilit")) {
				applies = fromAbility && mw.currentResolutionIsSummon;
			} else if (!srcN.contains("summon") && !srcN.startsWith("other")) {
				applies = fromAbility && !mw.currentResolutionIsSummon;
			} else {
				applies = fromAbility;
			}
		}
		if (!applies) return amount;
		String reduceStr   = fam.group("reduceby");
		String setstoStr   = fam.group("setsto");
		String increaseStr = fam.group("increaseby");
		if (reduceStr != null) {
			int before = amount;
			amount = Math.max(0, amount - Integer.parseInt(reduceStr));
			mw.logEntry(subjectName + " — damage reduced by " + reduceStr + " (" + before + " → " + amount + ")");
		} else if (setstoStr != null) {
			int fixed = Integer.parseInt(setstoStr);
			mw.logEntry(subjectName + " — damage set to " + fixed + " instead");
			amount = fixed;
		} else if (increaseStr != null) {
			int before = amount;
			amount = amount + Integer.parseInt(increaseStr);
			mw.logEntry(subjectName + " — damage increased by " + increaseStr + " (" + before + " → " + amount + ")");
		} else if (fam.group("double") != null) {
			int before = amount;
			amount = amount * 2;
			mw.logEntry(subjectName + " — damage doubled (" + before + " → " + amount + ")");
		}
		return amount;
	}

	/**
	 * Scans all friendly Forwards and Backups for {@link AutoAbilityTriggers#FA_FIELD_DAMAGE_MODIFIER}
	 * abilities and applies any that target the damaged Forward.
	 * Returns the (possibly modified) damage amount.
	 */
	int applyFieldWideDamageModifiers(int amount, CardData damaged, boolean isP1,
			ForwardTarget.CardZone zone, int idx, boolean fromAbility) {
		int effectivePower = mw.fieldForwardPower(isP1, zone, idx);
		boolean attackerIsBackup = !fromAbility && (isP1 ? mw.pendingP2AttackerIsBackup : mw.p1BackupAttackIdx >= 0);

		List<CardData> sources = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
		for (CardData bkp : isP1 ? mw.p1BackupCards : mw.p2BackupCards)
			if (bkp != null) sources.add(bkp);

		for (CardData protector : sources) {
			for (FieldAbility fa : protector.fieldAbilities()) {
				Matcher m = AutoAbilityTriggers.FA_FIELD_DAMAGE_MODIFIER.matcher(fa.effectText());
				if (!m.find()) continue;

				// Target filter
				String category = m.group("category");
				String job      = m.group("job");
				String element  = m.group("element");
				String costStr  = m.group("cost");
				String costcmp  = m.group("costcmp");
				String except   = m.group("except1") != null ? m.group("except1").trim()
				                                             : (m.group("except2") != null ? m.group("except2").trim() : null);

				if (category != null && !CardFilters.meetsCategoryFilter(damaged, category)) continue;
				if (job      != null && !CardFilters.meetsJobFilter(damaged, job))            continue;
				if (element  != null && !mw.effectiveElements(damaged).contains(element))        continue;
				if (costStr  != null) {
					int costVal = Integer.parseInt(costStr);
					boolean orMore = "more".equalsIgnoreCase(costcmp);
					if (orMore ? damaged.cost() < costVal : damaged.cost() > costVal) continue;
				}
				if (except != null && except.equalsIgnoreCase(damaged.name())) continue;

				// Source clause
				String src = m.group("sourceclause");
				if (src != null && !src.isBlank()) {
					String srcN = src.trim().toLowerCase();
					if (srcN.contains("less than its power") && amount >= effectivePower) continue;
					if (srcN.contains("by a backup") && !attackerIsBackup) continue;
					if (srcN.contains("abilit") || srcN.contains("summon")) {
						if (!fromAbility) continue;
						boolean namesSummon  = srcN.contains("summon");
						boolean namesAbility = srcN.contains("abilit");
						if (namesSummon && !namesAbility && !mw.currentResolutionIsSummon) continue;
						if (namesAbility && !namesSummon &&  mw.currentResolutionIsSummon) continue;
						// "by your opponent's …" — the damage has to originate on the other side of
						// the board, so a friendly Summon or ability is not covered.
						if (srcN.contains("opponent")) {
							CardData resCard = mw.currentResolutionIsSummon ? mw.currentSummonSource : mw.currentAbilitySource;
							boolean  resIsP1 = mw.currentResolutionIsSummon ? mw.currentSummonSourceIsP1 : mw.currentAbilitySourceIsP1;
							if (resCard == null || resIsP1 == isP1) continue;
						}
					}
				}

				// Apply effect
				String reduceStr = m.group("reduceby");
				String setstoStr = m.group("setsto");
				if (reduceStr != null) {
					int before = amount;
					amount = Math.max(0, amount - Integer.parseInt(reduceStr));
					mw.logEntry(damaged.name() + " — damage reduced by " + reduceStr
							+ " (" + before + " → " + amount + ") [" + protector.name() + "]");
				} else if (setstoStr != null) {
					int fixed = Integer.parseInt(setstoStr);
					mw.logEntry(damaged.name() + " — damage set to " + fixed + " instead [" + protector.name() + "]");
					amount = fixed;
				}
			}

			// Exact-amount nullification: "If a Forward you control receives N damage, the damage becomes 0 instead."
			if (!mw.lostAbilitiesCards.contains(protector)) {
				for (FieldAbility fa : protector.fieldAbilities()) {
					Matcher m = AutoAbilityTriggers.FA_FIELD_DAMAGE_EXACT_NULLIFY.matcher(fa.effectText());
					if (!m.find()) continue;
					int exactAmt = Integer.parseInt(m.group("amount"));
					if (amount == exactAmt) {
						mw.logEntry(damaged.name() + " — " + exactAmt + " damage becomes 0 [" + protector.name() + "]");
						amount = 0;
					}
				}
			}
		}
		return amount;
	}

	/**
	 * Scans the CASTER's side field cards for {@link AutoAbilityTriggers#FA_ELEMENT_SUMMON_DAMAGE_BOOST}
	 * abilities (e.g. Caetuna: "Fire Summon damage +1000") and applies any that match the current
	 * resolving Summon's element.  {@code targetIsP1} is the owner of the Forward being damaged.
	 */
	int applyCasterSideElementSummonDamageBoosts(int amount, boolean targetIsP1) {
		if (!mw.currentResolutionIsSummon || mw.currentSummonSource == null) return amount;
		boolean casterIsP1 = mw.currentSummonSourceIsP1;
		if (casterIsP1 == targetIsP1) return amount;  // Only boosts damage to the opposing side
		List<CardData> casterField = new ArrayList<>(casterIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
		for (CardData bkp : casterIsP1 ? mw.p1BackupCards : mw.p2BackupCards)
			if (bkp != null) casterField.add(bkp);
		for (CardData booster : casterField) {
			for (FieldAbility fa : booster.fieldAbilities()) {
				java.util.regex.Matcher m = AutoAbilityTriggers.FA_ELEMENT_SUMMON_DAMAGE_BOOST.matcher(fa.effectText());
				if (!m.find()) continue;
				String elem = m.group("element");
				if (!mw.currentSummonSource.containsElement(elem)) continue;
				int boost = Integer.parseInt(m.group("amount"));
				int before = amount;
				amount += boost;
				mw.logEntry(booster.name() + " — " + elem + " Summon damage increased by " + boost
						+ " (" + before + " → " + amount + ")");
			}
		}
		return amount;
	}

	/**
	 * Scans the caster's side field cards for {@link AutoAbilityTriggers#FA_ELEMENT_FORWARD_DAMAGE_BOOST}
	 * abilities and applies any that match when the resolving ability source is an Element Forward
	 * dealing damage to a Forward on the opposing side.
	 */
	int applyCasterSideElementForwardDamageBoosts(int amount, boolean targetIsP1) {
		if (mw.currentAbilitySource == null || !mw.currentAbilitySource.isForward()) return amount;
		if (mw.currentAbilitySourceIsP1 == targetIsP1) return amount;
		boolean casterIsP1 = mw.currentAbilitySourceIsP1;
		List<CardData> casterField = new ArrayList<>(casterIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
		for (CardData bkp : casterIsP1 ? mw.p1BackupCards : mw.p2BackupCards)
			if (bkp != null) casterField.add(bkp);
		for (CardData booster : casterField) {
			for (FieldAbility fa : booster.fieldAbilities()) {
				Matcher m = AutoAbilityTriggers.FA_ELEMENT_FORWARD_DAMAGE_BOOST.matcher(fa.effectText());
				if (!m.find()) continue;
				if (!mw.currentAbilitySource.containsElement(m.group("element"))) continue;
				int boost = Integer.parseInt(m.group("amount"));
				int before = amount;
				amount += boost;
				mw.logEntry(booster.name() + " — " + m.group("element") + " Forward ability damage increased by "
						+ boost + " (" + before + " → " + amount + ")");
			}
		}
		return amount;
	}

	/** Forward-zone overload — see {@link #modifyOutgoingCombatDamage(boolean, ForwardTarget.CardZone, int, int, CardData)}. */
	int modifyOutgoingCombatDamage(boolean isP1, int idx, int rawAmount, CardData target) {
		return modifyOutgoingCombatDamage(isP1, ForwardTarget.CardZone.FORWARD, idx, rawAmount, target);
	}

	/**
	 * Applies outgoing-damage modifiers for a card that is about to deal combat damage while
	 * acting as a Forward — from any zone (a real Forward, or a Monster/Backup temporarily a
	 * Forward). Checks and consumes the one-time "next outgoing damage = 0" shield.
	 *
	 * <p>The dealing card and its {@code target} are both combatants and so are Forwards for
	 * every eligible purpose; the friendly-element, cost-based, and self "deals damage to a
	 * Forward" boosts therefore apply regardless of the cards' printed card types.
	 */
	int modifyOutgoingCombatDamage(boolean isP1, ForwardTarget.CardZone zone, int idx, int rawAmount, CardData target) {
		CardData card = mw.fieldCombatant(isP1, zone, idx);
		if (card == null) return rawAmount;
		if (mw.nextOutgoingDmgZeroSet.remove(card)) return 0;
		if (mw.dealsNoCombatDamageSet.contains(card)) return 0;   // deals no damage for the whole battle
		int mult = mw.outgoingDmgMultiplierMap.getOrDefault(card, 1);
		if (mw.nextOutgoingDmgDoublerSet.remove(card)) mult *= 2;
		if (target != null) mult *= mw.fieldAbilityCombatOutgoingMult(card, target);
		int flat = (target != null) ? mw.outgoingDmgFlatBoostMap.getOrDefault(card, 0) : 0;

		if (target != null) {
			flat += mw.friendlyElementForwardCombatBoost(card, isP1);
			flat += mw.costBasedCombatFlatAdjustments(card, target);
			flat += mw.selfOutgoingFlatBoostVsForward(card, isP1);
		}
		return rawAmount * mult + flat;
	}

	boolean sourceHasOutgoingDmgToOpponentDoubler(CardData attacker) {
		if (attacker == null || mw.lostAbilitiesCards.contains(attacker)) return false;
		for (FieldAbility fa : mw.effectiveFieldAbilities(attacker)) {
			Matcher m = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(fa.effectText());
			if (m.find() && m.group("card").trim().equalsIgnoreCase(attacker.name())
					&& m.group("target").toLowerCase().contains("opponent"))
				return true;
		}
		return false;
	}

	/**
	 * The fixed number of damage points {@code attacker}'s "If [card] deals damage to your opponent,
	 * the damage becomes N instead" ability replaces its damage with, or {@code null} when it has no
	 * such ability. Reads granted abilities too, so an until-end-of-turn grant counts.
	 */
	Integer outgoingDamageToOpponentOverride(CardData attacker) {
		if (attacker == null || mw.lostAbilitiesCards.contains(attacker)) return null;
		for (FieldAbility fa : mw.effectiveFieldAbilities(attacker)) {
			Matcher m = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_TO_OPPONENT_SETS_TO.matcher(fa.effectText());
			if (m.find() && m.group("card").trim().equalsIgnoreCase(attacker.name()))
				return Integer.valueOf(m.group("amount"));
		}
		return null;
	}

	/** The points of combat damage {@code attacker} deals to the opposing player. */
	int combatDamagePointsToOpponent(CardData attacker) {
		Integer override = outgoingDamageToOpponentOverride(attacker);
		if (override != null) return override;
		return sourceHasOutgoingDmgToOpponentDoubler(attacker) ? 2 : 1;
	}

	/** Deals combat damage to the opponent — normally 1 point, but an outgoing-damage doubler or
	 *  "the damage becomes N instead" ability on the attacker changes the count (N may be 0) —
	 *  calling {@code afterDamage} after all damage points and any EX bursts have resolved. */
	void dealCombatDamageToOpponent(CardData attacker, boolean attackerIsP1, Runnable afterDamage) {
		if (mw.dealsNoCombatDamageSet.contains(attacker)) {
			mw.logEntry((attackerIsP1 ? "" : "[P2] ") + attacker.name() + " deals no damage this battle");
			afterDamage.run();
			return;
		}
		int points = combatDamagePointsToOpponent(attacker);
		if (points != 1)
			mw.logEntry((attackerIsP1 ? "" : "[P2] ") + attacker.name()
					+ " — combat damage to opponent is " + points + " instead of 1");
		dealOpponentDamagePoints(attacker, attackerIsP1, points, afterDamage);
	}

	/**
	 * Deals {@code remaining} points of damage to the opposing player one at a time, each point
	 * re-crediting {@code attacker} as the damage source (it is consumed per call) and the next
	 * point dealt from the previous one's completion callback so EX Bursts resolve in order.
	 */
	void dealOpponentDamagePoints(CardData attacker, boolean attackerIsP1, int remaining,
			Runnable afterDamage) {
		if (remaining <= 0) { afterDamage.run(); return; }
		mw.setPlayerDamageSource(attacker);
		Runnable next = () -> dealOpponentDamagePoints(attacker, attackerIsP1, remaining - 1, afterDamage);
		if (attackerIsP1) mw.p2TakeDamage(next); else mw.p1TakeDamage(next);
	}

	/**
	 * Applies incoming-damage modifiers, writes the result to the damage accumulator,
	 * and breaks the forward if accumulated damage reaches its effective power.
	 */
	void applyDamageToMonster(boolean isP1, int idx, int amount) {
		List<CardData> mons    = isP1 ? mw.p1MonsterCards  : mw.p2MonsterCards;
		List<Integer>  dmgList = isP1 ? mw.p1MonsterDamage : mw.p2MonsterDamage;
		if (idx >= mons.size() || amount <= 0) return;
		int accum  = dmgList.get(idx) + amount;
		dmgList.set(idx, accum);
		boolean asFwd = isP1 ? mw.isP1MonsterTemporarilyForward(idx) : mw.isP2MonsterTemporarilyForward(idx);
		int effPow = asFwd ? (isP1 ? mw.p1MonsterForwardPower(idx) : mw.p2MonsterForwardPower(idx))
		                   : (isP1 ? mw.effectiveP1MonsterPower(idx) : mw.effectiveP2MonsterPower(idx));
		mw.logEntry((isP1 ? "" : "[P2] ") + mons.get(idx).name() + " takes " + amount + " damage"
				+ (effPow > 0 ? " (" + (effPow - accum) + " remaining)" : ""));
		if (effPow > 0 && accum >= effPow) {
			if (isP1) mw.autoAbilityTriggers.breakP1MonsterSlot(idx); else mw.breakP2MonsterSlot(idx);
		} else {
			if (isP1) mw.refreshP1MonsterSlot(idx); else mw.refreshP2MonsterSlot(idx);
		}
	}

	void applyDamageToForward(boolean isP1, int idx, int rawAmount, boolean fromAbility, boolean unreduced) {
		List<CardData>  fwds   = isP1 ? mw.p1ForwardCards   : mw.p2ForwardCards;
		List<Integer>   dmgList = isP1 ? mw.p1ForwardDamage  : mw.p2ForwardDamage;
		if (idx >= fwds.size()) return;
		// One-time damage redirect: "the next damage dealt to A is received by B instead"
		CardData redirectTarget = mw.nextIncomingDmgRedirectMap.remove(fwds.get(idx));
		if (redirectTarget != null) {
			int p1RedirIdx = mw.p1ForwardCards.indexOf(redirectTarget);
			int p2RedirIdx = mw.p2ForwardCards.indexOf(redirectTarget);
			if (p1RedirIdx >= 0) {
				mw.logEntry(fwds.get(idx).name() + " — damage redirected to " + redirectTarget.name());
				applyDamageToForward(true, p1RedirIdx, rawAmount, fromAbility, unreduced);
				return;
			} else if (p2RedirIdx >= 0) {
				mw.logEntry(fwds.get(idx).name() + " — damage redirected to " + redirectTarget.name());
				applyDamageToForward(false, p2RedirIdx, rawAmount, fromAbility, unreduced);
				return;
			}
			// redirect target no longer on field — fall through to normal damage
		}
		int amount = modifyIncomingDamage(isP1, idx, rawAmount, fromAbility, unreduced);
		if (amount <= 0) {
			mw.logEntry((isP1 ? "" : "[P2] ") + fwds.get(idx).name() + " — damage blocked");
			return;
		}
		int accum  = dmgList.get(idx) + amount;
		dmgList.set(idx, accum);
		(mw.turn(isP1).cardsTookDamageThisTurn).add(fwds.get(idx).name());
		int effPow = isP1 ? mw.effectiveP1ForwardPower(idx) : mw.effectiveP2ForwardPower(idx);
		mw.logEntry((isP1 ? "" : "[P2] ") + fwds.get(idx).name() + " takes " + amount + " damage"
				+ (effPow > 0 ? " (" + (effPow - accum) + " remaining)" : ""));
		// Fires on being dealt damage, so before the break check below — 28-043R Gi Nattak's
		// trigger still resolves when the damage is lethal.
		mw.autoAbilityTriggers.fireIsDealtDamageTriggers(fwds.get(idx), isP1);
		if (effPow > 0 && accum >= effPow) {
			CardData fwd = fwds.get(idx);
			if (isP1 ? mw.effectiveP1HasTrait(idx, CardData.Trait.CANNOT_BE_BROKEN)
			         : mw.effectiveP2HasTrait(idx, CardData.Trait.CANNOT_BE_BROKEN)) {
				mw.logEntry((isP1 ? "" : "[P2] ") + fwd.name() + " survives lethal damage (cannot be broken — damage clears at end of turn)");
				if (isP1) mw.refreshP1ForwardSlot(idx); else mw.refreshP2ForwardSlot(idx);
				if (mw.currentSummonSource != null)
					fireBreaktouchForDamage(mw.currentSummonSource, mw.currentSummonSourceIsP1, isP1, idx);
			} else {
				if (isP1) mw.breakP1Forward(idx); else mw.breakP2Forward(idx);
			}
		} else {
			if (isP1) mw.refreshP1ForwardSlot(idx); else mw.refreshP2ForwardSlot(idx);
			// Fire "deals damage to forward" triggers from tracked ability source (e.g. Ramuh + Lightning Summon)
			if (mw.currentSummonSource != null)
				fireBreaktouchForDamage(mw.currentSummonSource, mw.currentSummonSourceIsP1, isP1, idx);
		}
	}

	/** Forward-zone overload — see {@link #fireBreaktouchForDamage(CardData, boolean, boolean, ForwardTarget.CardZone, int)}. */
	boolean fireBreaktouchForDamage(CardData source, boolean sourceIsP1,
			boolean damagedIsP1, int damagedIdx) {
		return fireBreaktouchForDamage(source, sourceIsP1, damagedIsP1, ForwardTarget.CardZone.FORWARD, damagedIdx);
	}

	boolean fireBreaktouchForDamage(CardData source, boolean sourceIsP1,
			boolean damagedIsP1, ForwardTarget.CardZone damagedZone, int damagedIdx) {
		CardData damaged = mw.fieldCombatant(damagedIsP1, damagedZone, damagedIdx);
		if (damaged == null) return false;

		// Case 1: source card itself has "deals damage to forward" auto-ability
		for (AutoAbility fa : source.autoAbilities()) {
			if (!fa.trigger().equals("deals damage to forward")) continue;
			if (!fa.triggerCard().equalsIgnoreCase(source.name())) continue;
			// Not every trigger of this shape is Breaktouch. 4-039R Rogue dulls and Freezes the
			// damaged Forward instead, and breaking it here would be plainly wrong rather than
			// merely incomplete. Such effects name no target of their own — "it" is the card just
			// damaged — so they resolve with it preloaded.
			if (ActionResolver.isTriggeredTargetAction(fa.effectText())) {
				runOnDamagedCard(fa, source, sourceIsP1, damagedIsP1, damagedZone, damagedIdx);
				return false;
			}
			mw.logEntry((sourceIsP1 ? "" : "[P2] ") + source.name() + " — Breaktouch! "
					+ (damagedIsP1 ? "" : "[P2] ") + damaged.name() + " is broken.");
			mw.breakFieldCard(damagedIsP1, damagedZone, damagedIdx);
			return true;
		}

		// Case 2: source is a Summon of matching element; check caster's field for the Summon trigger
		if (source.isSummon()) {
			String[] sourceElems = source.elements();
			List<CardData> casterFwds = new ArrayList<>(sourceIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			for (CardData fieldCard : casterFwds) {
				for (AutoAbility fa : fieldCard.autoAbilities()) {
					String trig = fa.trigger();
					if (!trig.endsWith(" summon deals damage to forward")) continue;
					String elemPrefix = trig.substring(0, trig.indexOf(" summon")).toLowerCase(java.util.Locale.ROOT);
					boolean elemMatch = false;
					for (String e : sourceElems) {
						if (e.toLowerCase(java.util.Locale.ROOT).equals(elemPrefix)) { elemMatch = true; break; }
					}
					if (!elemMatch) continue;
					mw.logEntry((sourceIsP1 ? "" : "[P2] ") + fieldCard.name() + " — Breaktouch (Summon)! "
							+ (damagedIsP1 ? "" : "[P2] ") + damaged.name() + " is broken.");
					mw.breakFieldCard(damagedIsP1, damagedZone, damagedIdx);
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Resolves a "deals damage to a Forward" ability whose target is the card just damaged, with
	 * that card preloaded — the same route {@code AutoAbilityTriggers} uses for the watchers whose
	 * effects say "it" rather than naming a target.
	 */
	private void runOnDamagedCard(AutoAbility fa, CardData source, boolean sourceIsP1,
			boolean damagedIsP1, ForwardTarget.CardZone damagedZone, int damagedIdx) {
		Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), source);
		if (effect == null) return;

		GameContext ctx = mw.buildGameContext(sourceIsP1);
		ctx.preloadTargets(List.of(new ForwardTarget(damagedIsP1, damagedIdx, damagedZone)));
		CardData prevSource = mw.currentAbilitySource;
		mw.currentAbilitySource = source;
		try {
			mw.logEntry((sourceIsP1 ? "" : "[P2] ") + source.name() + " — " + fa.effectText());
			effect.accept(ctx);
		} finally {
			mw.currentAbilitySource = prevSource;
		}
	}

	/** Applies ability/combat damage to a backup that is currently acting as a Forward. */
	void applyDamageToBackup(boolean isP1, int idx, int amount) {
		CardData[] backs = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
		if (idx < 0 || idx >= backs.length || backs[idx] == null || amount <= 0) return;
		boolean asFwd = isP1 ? mw.isP1BackupTemporarilyForward(idx) : mw.isP2BackupTemporarilyForward(idx);
		if (!asFwd) return;
		CardData c = backs[idx];
		Map<CardData, Integer> dmgMap = isP1 ? mw.p1BackupForwardDamage : mw.p2BackupForwardDamage;
		int accum = dmgMap.getOrDefault(c, 0) + amount;
		dmgMap.put(c, accum);
		int effPow = isP1 ? mw.p1BackupForwardPower(idx) : mw.p2BackupForwardPower(idx);
		mw.logEntry((isP1 ? "" : "[P2] ") + c.name() + " takes " + amount + " damage"
				+ (effPow > 0 ? " (" + (effPow - accum) + " remaining)" : ""));
		if (effPow > 0 && accum >= effPow) {
			if (isP1) mw.autoAbilityTriggers.breakP1BackupSlot(idx); else mw.breakP2BackupSlot(idx);
		} else {
			if (isP1) mw.refreshP1BackupSlot(idx); else mw.refreshP2BackupSlot(idx);
		}
	}
}
