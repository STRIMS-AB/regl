package dev.regl;

import java.util.List;

/**
 * A field whose path must hold a number.
 *
 * <p>Exposes only the rules that target {@link TargetType#NUMBER}, plus
 * {@code notNull()} inherited from {@link Field}.
 */
public final class NumberField extends Field<NumberField> implements NumberRules<NumberField> {

    NumberField(List<Rule> rules) {
        super(TargetType.NUMBER, rules);
    }

    @Override
    protected NumberField copy(List<Rule> rules) {
        return new NumberField(rules);
    }

    public NumberField greaterThan(Number bound) {
        return append(new Rule(RuleType.GREATER_THAN, bound));
    }

    public NumberField greaterThanOrEqual(Number bound) {
        return append(new Rule(RuleType.GREATER_THAN_OR_EQUAL, bound));
    }

    public NumberField lessThan(Number bound) {
        return append(new Rule(RuleType.LESS_THAN, bound));
    }

    public NumberField lessThanOrEqual(Number bound) {
        return append(new Rule(RuleType.LESS_THAN_OR_EQUAL, bound));
    }

    public NumberField positive() {
        return append(new Rule(RuleType.POSITIVE));
    }

    public NumberField negative() {
        return append(new Rule(RuleType.NEGATIVE));
    }

    public NumberField positiveInteger() {
        return append(new Rule(RuleType.POSITIVE_INTEGER));
    }

    public NumberField negativeInteger() {
        return append(new Rule(RuleType.NEGATIVE_INTEGER));
    }

    public NumberField multipleOf(Number divisor) {
        return append(new Rule(RuleType.MULTIPLE_OF, divisor));
    }

    public NumberField precision(Number decimalPlaces) {
        return append(new Rule(RuleType.PRECISION, decimalPlaces));
    }
}