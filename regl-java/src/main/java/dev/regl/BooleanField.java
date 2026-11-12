package dev.regl;

import java.util.List;

/**
 * A field whose path must hold a boolean.
 *
 * <p>Exposes only the rules that target {@link TargetType#BOOLEAN}, plus
 * {@code notNull()} inherited from {@link Field}.
 */
public final class BooleanField extends Field<BooleanField> implements BooleanRules<BooleanField> {

    BooleanField(List<Rule> rules) {
        super(TargetType.BOOLEAN, rules);
    }

    @Override
    protected BooleanField copy(List<Rule> rules) {
        return new BooleanField(rules);
    }

    public BooleanField isTrue() {
        return append(new Rule(RuleType.IS_TRUE));
    }

    public BooleanField isFalse() {
        return append(new Rule(RuleType.IS_FALSE));
    }
}