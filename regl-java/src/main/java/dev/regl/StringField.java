package dev.regl;

import java.util.List;

/**
 * A field whose path must hold a string.
 *
 * <p>Exposes only the rules that target {@link TargetType#STRING}, plus
 * {@code notNull()} inherited from {@link Field}.
 */
public final class StringField extends Field<StringField> implements StringRules<StringField> {

    StringField(List<Rule> rules) {
        super(TargetType.STRING, rules);
    }

    @Override
    protected StringField copy(List<Rule> rules) {
        return new StringField(rules);
    }

    public StringField minLength(Number min) {
        return append(new Rule(RuleType.MIN_LENGTH, min));
    }

    public StringField maxLength(Number max) {
        return append(new Rule(RuleType.MAX_LENGTH, max));
    }

    public StringField length(Number exact) {
        return append(new Rule(RuleType.LENGTH, exact));
    }

    public StringField pattern(String regex) {
        return append(new Rule(RuleType.PATTERN, regex));
    }

    public StringField contains(String substring) {
        return append(new Rule(RuleType.CONTAINS, substring));
    }

    public StringField startsWith(String prefix) {
        return append(new Rule(RuleType.STARTS_WITH, prefix));
    }

    public StringField endsWith(String suffix) {
        return append(new Rule(RuleType.ENDS_WITH, suffix));
    }

    public StringField email() {
        return append(new Rule(RuleType.EMAIL));
    }

    public StringField url() {
        return append(new Rule(RuleType.URL));
    }

    public StringField uuid() {
        return append(new Rule(RuleType.UUID));
    }

    public StringField languageCode() {
        return append(new Rule(RuleType.LANGUAGE_CODE));
    }

    public StringField lowercase() {
        return append(new Rule(RuleType.LOWERCASE));
    }

    public StringField uppercase() {
        return append(new Rule(RuleType.UPPERCASE));
    }

    public StringField trimmed() {
        return append(new Rule(RuleType.TRIMMED));
    }

    public StringField notEmpty() {
        return append(new Rule(RuleType.NOT_EMPTY));
    }

    public StringField text() {
        return append(new Rule(RuleType.TEXT));
    }

    public StringField dateFormat(String pattern) {
        return append(new Rule(RuleType.DATE_FORMAT, pattern));
    }

    public StringField isoDate() {
        return append(new Rule(RuleType.ISO_DATE));
    }

    public StringField isoDateTime() {
        return append(new Rule(RuleType.ISO_DATE_TIME));
    }

    public StringField isoTime() {
        return append(new Rule(RuleType.ISO_TIME));
    }
}