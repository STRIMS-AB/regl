package dev.regl;

/**
 * Generic whole-number rules, for builders that accept rules across data types.
 *
 * <p>The magnitude rules are the {@link NumberRules} ones, because an integer is
 * a number with a whole-number assertion on top; {@code integer()} is absent
 * because the type already asserts it.
 *
 * <p>Deliberately not part of {@link AllRules}: it is a strict subset of
 * {@code NumberRules}, and inheriting both would give a generic builder two
 * unrelated defaults for the same method.
 */
public interface IntegerRules<SELF extends RuleContainer<SELF>> extends RuleContainer<SELF> {

    default SELF greaterThan(Number bound) {
        return addRule(new Rule(RuleType.GREATER_THAN, bound));
    }

    default SELF greaterThan(String ref) {
        return addRule(new Rule(RuleType.GREATER_THAN, ref));
    }

    default SELF greaterThanOrEqual(Number bound) {
        return addRule(new Rule(RuleType.GREATER_THAN_OR_EQUAL, bound));
    }

    default SELF greaterThanOrEqual(String ref) {
        return addRule(new Rule(RuleType.GREATER_THAN_OR_EQUAL, ref));
    }

    default SELF lessThan(Number bound) {
        return addRule(new Rule(RuleType.LESS_THAN, bound));
    }

    default SELF lessThan(String ref) {
        return addRule(new Rule(RuleType.LESS_THAN, ref));
    }

    default SELF lessThanOrEqual(Number bound) {
        return addRule(new Rule(RuleType.LESS_THAN_OR_EQUAL, bound));
    }

    default SELF lessThanOrEqual(String ref) {
        return addRule(new Rule(RuleType.LESS_THAN_OR_EQUAL, ref));
    }

    default SELF positive() {
        return addRule(new Rule(RuleType.POSITIVE));
    }

    default SELF negative() {
        return addRule(new Rule(RuleType.NEGATIVE));
    }

    default SELF multipleOf(Number divisor) {
        return addRule(new Rule(RuleType.MULTIPLE_OF, divisor));
    }

    default SELF multipleOf(String ref) {
        return addRule(new Rule(RuleType.MULTIPLE_OF, ref));
    }
}