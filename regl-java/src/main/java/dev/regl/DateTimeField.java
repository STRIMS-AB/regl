package dev.regl;

import java.util.List;

/**
 * A field whose path must hold a date-time timestamp.
 *
 * <p>Exposes the epoch rules plus {@code notNull()} and {@code enumOf()} inherited from {@link Field}.
 */
public final class DateTimeField extends Field<DateTimeField> implements DateTimeRules<DateTimeField> {

    DateTimeField(List<Rule> rules) {
        super(TargetType.DATETIME, rules);
    }

    @Override
    protected DateTimeField copy(List<Rule> rules) {
        return new DateTimeField(rules);
    }
}
