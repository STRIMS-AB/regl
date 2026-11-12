package dev.regl;

import java.util.List;

/**
 * A field that declares no type: whatever is present is accepted.
 *
 * <p>This is the only builder whose rules can operate on a {@code null}, because
 * {@link TargetType#ANY} lets every kind through the type guard — including
 * {@code null}, which every other target type rejects before the predicate runs.
 * That is what makes {@code notNull} a catalog rule rather than schema metadata.
 *
 * <p>Reaching for {@code any()} is a last resort. A field with no declared type
 * can only be constrained by the two {@link TargetType#ANY}-target rules inherited
 * from {@link Field}; if you know the type, say so with {@link StringField},
 * {@link NumberField}, {@link BooleanField}, or {@link ListField} and get the
 * whole rule set for that type.
 */
public final class AnyField extends Field<AnyField> {

    AnyField(List<Rule> rules) {
        super(TargetType.ANY, rules);
    }

    @Override
    protected AnyField copy(List<Rule> rules) {
        return new AnyField(rules);
    }
}