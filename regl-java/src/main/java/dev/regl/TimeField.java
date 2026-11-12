package dev.regl;

import java.util.List;

/**
 * A field whose path must hold a time value.
 *
 * <p>The catalog assigns {@code time-of-day} no rules, so this builder carries only
 * {@code notNull()} and {@code enumOf()} from {@link Field}. Stating a shape here
 * would need a rule the catalog does not have.
 */
public final class TimeField extends Field<TimeField> {

    TimeField(List<Rule> rules) {
        super(TargetType.TIME, rules);
    }

    @Override
    protected TimeField copy(List<Rule> rules) {
        return new TimeField(rules);
    }
}
