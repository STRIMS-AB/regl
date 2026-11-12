package dev.regl;

import java.util.List;

/**
 * A field whose path must hold a whole number.
 *
 * <p>Exposes the {@link TargetType#NUMBER} magnitude rules, since an integer is
 * a number with a whole-number assertion on top — a port is still
 * {@code lessThan(65536)} — plus {@code notNull()} inherited from {@link Field}.
 *
 * <p>Three {@link NumberField} methods are deliberately absent. {@code integer()}
 * is what this type already asserts. {@code precision()} is meaningless here: a
 * whole number has no decimal places. {@code positiveInteger()} and
 * {@code negativeInteger()} are {@code positive()} and {@code negative()} on a
 * field that is known to be whole, so the builder offers the shorter pair.
 */
public final class IntegerField extends Field<IntegerField> implements IntegerRules<IntegerField> {

    IntegerField(List<Rule> rules) {
        super(TargetType.INTEGER, rules);
    }

    @Override
    protected IntegerField copy(List<Rule> rules) {
        return new IntegerField(rules);
    }

    public IntegerField greaterThan(Number bound) {
        return append(new Rule(RuleType.GREATER_THAN, bound));
    }

    public IntegerField greaterThanOrEqual(Number bound) {
        return append(new Rule(RuleType.GREATER_THAN_OR_EQUAL, bound));
    }

    public IntegerField lessThan(Number bound) {
        return append(new Rule(RuleType.LESS_THAN, bound));
    }

    public IntegerField lessThanOrEqual(Number bound) {
        return append(new Rule(RuleType.LESS_THAN_OR_EQUAL, bound));
    }

    public IntegerField positive() {
        return append(new Rule(RuleType.POSITIVE));
    }

    public IntegerField negative() {
        return append(new Rule(RuleType.NEGATIVE));
    }

    public IntegerField multipleOf(Number divisor) {
        return append(new Rule(RuleType.MULTIPLE_OF, divisor));
    }
}