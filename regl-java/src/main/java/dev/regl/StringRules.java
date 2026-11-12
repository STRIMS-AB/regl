package dev.regl;

/**
 * Generic string rules inherited across all string-compatible field builders.
 *
 * <p>Carries the temporal rules that are {@link TargetType#STRING}-targeted in the
 * catalog — {@code dateFormat}, {@code isoDate}, {@code isoDateTime},
 * {@code isoTime}, {@code after}, {@code before}. A temporal value in a document is
 * a string, and the field's target type is what says which format that string is
 * in, so these belong here rather than on the temporal builders.
 */
public interface StringRules<SELF extends RuleContainer<SELF>> extends RuleContainer<SELF> {

    default SELF minLength(Number min) {
        return addRule(new Rule(RuleType.MIN_LENGTH, min));
    }

    default SELF minLength(String ref) {
        return addRule(new Rule(RuleType.MIN_LENGTH, ref));
    }

    default SELF maxLength(Number max) {
        return addRule(new Rule(RuleType.MAX_LENGTH, max));
    }

    default SELF maxLength(String ref) {
        return addRule(new Rule(RuleType.MAX_LENGTH, ref));
    }

    default SELF length(Number exact) {
        return addRule(new Rule(RuleType.LENGTH, exact));
    }

    default SELF length(String ref) {
        return addRule(new Rule(RuleType.LENGTH, ref));
    }

    default SELF pattern(String regex) {
        return addRule(new Rule(RuleType.PATTERN, regex));
    }

    default SELF contains(String substring) {
        return addRule(new Rule(RuleType.CONTAINS, substring));
    }

    default SELF startsWith(String prefix) {
        return addRule(new Rule(RuleType.STARTS_WITH, prefix));
    }

    default SELF endsWith(String suffix) {
        return addRule(new Rule(RuleType.ENDS_WITH, suffix));
    }

    default SELF email() {
        return addRule(new Rule(RuleType.EMAIL));
    }

    default SELF url() {
        return addRule(new Rule(RuleType.URL));
    }

    default SELF uuid() {
        return addRule(new Rule(RuleType.UUID));
    }

    default SELF languageCode() {
        return addRule(new Rule(RuleType.LANGUAGE_CODE));
    }

    default SELF lowercase() {
        return addRule(new Rule(RuleType.LOWERCASE));
    }

    default SELF uppercase() {
        return addRule(new Rule(RuleType.UPPERCASE));
    }

    default SELF trimmed() {
        return addRule(new Rule(RuleType.TRIMMED));
    }

    default SELF notEmpty() {
        return addRule(new Rule(RuleType.NOT_EMPTY));
    }

    default SELF text() {
        return addRule(new Rule(RuleType.TEXT));
    }

    /** Requires date/time string to match format pattern (token pattern or Java pattern). */
    default SELF dateFormat(String pattern) {
        return addRule(new Rule(RuleType.DATE_FORMAT, pattern));
    }

    /** Requires value to be a valid ISO-8601 calendar date (YYYY-MM-DD). */
    default SELF isoDate() {
        return addRule(new Rule(RuleType.ISO_DATE));
    }

    /** Requires value to be a valid ISO-8601 date-time timestamp. */
    default SELF isoDateTime() {
        return addRule(new Rule(RuleType.ISO_DATE_TIME));
    }

    /** Requires value to be a valid ISO-8601 time (HH:mm:ss). */
    default SELF isoTime() {
        return addRule(new Rule(RuleType.ISO_TIME));
    }

    /** Requires date/time to be chronologically after {value}. */
    default SELF after(String target) {
        return addRule(new Rule(RuleType.AFTER, target));
    }

    /** Requires date/time to be chronologically before {value}. */
    default SELF before(String target) {
        return addRule(new Rule(RuleType.BEFORE, target));
    }
}
