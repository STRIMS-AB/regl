package dev.regl;

/**
 * Generic numeric rules inherited across all numeric-compatible field builders.
 */
public interface NumberRules<SELF extends RuleContainer<SELF>> extends RuleContainer<SELF> {

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

    default SELF positiveInteger() {
        return addRule(new Rule(RuleType.POSITIVE_INTEGER));
    }

    default SELF negativeInteger() {
        return addRule(new Rule(RuleType.NEGATIVE_INTEGER));
    }

    default SELF multipleOf(Number divisor) {
        return addRule(new Rule(RuleType.MULTIPLE_OF, divisor));
    }

    default SELF multipleOf(String ref) {
        return addRule(new Rule(RuleType.MULTIPLE_OF, ref));
    }

    default SELF precision(Number decimalPlaces) {
        return addRule(new Rule(RuleType.PRECISION, decimalPlaces));
    }

    default SELF precision(String ref) {
        return addRule(new Rule(RuleType.PRECISION, ref));
    }
}
