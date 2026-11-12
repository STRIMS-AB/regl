package dev.regl;

/**
 * Fluent builder methods for rules that target {@link TargetType#DATETIME}.
 *
 * <p>Only {@code DateTimeField} implements this. A {@code DATE}- or
 * {@code TIME}-typed field cannot carry these rules, because the catalog assigns
 * them to {@code DATETIME} alone — a Unix timestamp in seconds or milliseconds
 * is an instant, not a calendar day and not a time of day.
 *
 * @param <SELF> the concrete builder type
 */
public interface DateTimeRules<SELF extends RuleContainer<SELF>> extends RuleContainer<SELF> {

    /** Requires value to be a whole-number Unix timestamp in seconds. */
    default SELF epoch() {
        return addRule(new Rule(RuleType.EPOCH));
    }

    /** Requires value to be a whole-number Unix timestamp in milliseconds. */
    default SELF epochMillis() {
        return addRule(new Rule(RuleType.EPOCH_MILLIS));
    }
}