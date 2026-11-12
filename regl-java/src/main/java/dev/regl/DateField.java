package dev.regl;

import java.util.List;

/**
 * A field whose path must hold a date.
 *
 * <p>The catalog assigns {@code calendar-date} no rules, so this builder carries only
 * {@code notNull()} and {@code enumOf()} from {@link Field}. Stating a shape here
 * would need a rule the catalog does not have.
 */
public final class DateField extends Field<DateField> {

    DateField(List<Rule> rules) {
        super(TargetType.DATE, rules);
    }

    @Override
    protected DateField copy(List<Rule> rules) {
        return new DateField(rules);
    }
}
