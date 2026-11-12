package dev.regl;

import java.util.List;

/**
 * A single rule instance: the rule {@link #code()} (identical to the
 * {@code "type"} used in catalog documents), its parameter, a description, and the
 * warn-only severity flag.
 *
 * <p>Built-in rules are constructed from their {@link RuleType}; custom
 * rules use {@link #custom(String, Object)} together with a registered
 * {@link RuleEvaluator} (see {@link RuleEvaluators}).
 */
public record Rule(
    String code,
    Object value,
    String description,
    boolean warnOnly
) {
    public Rule {
        if (code == null) {
            throw new IllegalArgumentException("Rule code cannot be null.");
        }
        RuleType builtIn = RuleType.fromCodeOrNull(code);
        if (builtIn != null) {
            validateParameter(builtIn, value);
        }
    }

    public Rule(RuleType type) {
        this(type.code(), null, null, false);
    }

    public Rule(RuleType type, Object value) {
        this(type.code(), value, null, false);
    }

    public Rule(RuleType type, Object value, String description) {
        this(type.code(), value, description, false);
    }

    public Rule(RuleType type, Object value, boolean warnOnly) {
        this(type.code(), value, null, warnOnly);
    }

    /**
     * Creates a rule for a custom code backed by a registered
     * {@link RuleEvaluator}. The parameter type is validated at runtime against
     * the evaluator's declared parameter type.
     */
    public static Rule custom(String code, Object value) {
        return custom(code, value, null, false);
    }

    public static Rule custom(String code, Object value, String description) {
        return custom(code, value, description, false);
    }

    public static Rule custom(String code, Object value, String description, boolean warnOnly) {
        return new Rule(code, value, description, warnOnly);
    }

    /**
     * A copy of this rule with the warn-only flag set. A warn-only failing
     * rule is reported as a warning and never fails validation.
     */
    public Rule withWarnOnly(boolean warnOnly) {
        return new Rule(code, value, description, warnOnly);
    }

    public String typeCode() {
        return code;
    }

    /**
     * Alias for {@link #value()}.
     */
    public Object param() {
        return value;
    }

    /**
     * The built-in {@link RuleType} this rule maps to, or {@code null}
     * for custom rules.
     */
    public RuleType builtInType() {
        return RuleType.fromCodeOrNull(code);
    }

    /**
     * Evaluates {@code input} against this rule and returns the outcome.
     *
     * <p>Resolves the {@link RuleEvaluator} registered for this rule's code,
     * re-checks the parameter against the evaluator's declared type, and runs
     * the predicate. A passing rule yields {@link ValidationResult#valid()};
     * a failing rule yields a single {@link ValidationError} carrying the
     * field, rule code, parameter, and the evaluator's default message.
     *
     * <p>A {@link #warnOnly()} rule that fails is reported as a warning and
     * the result remains valid.
     *
     * @throws IllegalArgumentException if no evaluator is registered for this
     *         rule's code, or the parameter does not match the evaluator's
     *         declared parameter type
     */
    public ValidationResult validate(String field, Object input) {
        RuleEvaluator<?> evaluator = RuleEvaluators.require(code);
        RuleEvaluators.checkParameter(this, evaluator);
        if (RuleEvaluators.test(evaluator, input, value)) {
            return ValidationResult.valid();
        }
        String message = RuleEvaluators.message(evaluator, field, input, value);
        ValidationError error = new ValidationError(field, code, value, message);
        return warnOnly
                ? ValidationResult.of(java.util.List.of(), java.util.List.of(error))
                : ValidationResult.invalid(java.util.List.of(error));
    }

    /** Whether this rule is the given built-in type (by code). */
    public boolean isType(RuleType type) {
        return code.equals(type.code());
    }

    /**
     * The built-in {@link RuleType} of this rule. Returns {@code null}
     * when the code is not a built-in rule (may only be validated against a
     * registered custom {@link RuleEvaluator}).
     *
     * @deprecated prefer {@link #code()} / {@link #isType(RuleType)}; kept for
     * source compatibility with callers that only ever handle built-in rules
     */
    @Deprecated
    public RuleType type() {
        return builtInType();
    }

    /** Whether this rule's parameter is a cross-field reference ("$fieldName"). */
    static boolean isFieldRef(Object value) {
        return value instanceof String s && s.startsWith("$");
    }

    private static void validateParameter(RuleType type, Object value) {
        ParameterType expected = type.parameterType();
        if (isFieldRef(value)) {
            if (type.parameterType() == null
                    || type == RuleType.TEXT
                    || (expected != ParameterType.STRING && expected != ParameterType.NUMBER && expected != ParameterType.BOOLEAN)) {
                throw new IllegalArgumentException("Rule '" + type.code() + "' does not support field reference parameters.");
            }
            if (((String) value).length() < 2) {
                throw new IllegalArgumentException("Field reference parameter '" + value + "' must name a property ('$fieldName').");
            }
            return;
        }
        if (type.parameterType() == null) {
            if (value != null) {
                throw new IllegalArgumentException("Rule '" + type.code() + "' does not accept a parameter.");
            }
            return;
        }
        if (value == null) {
            throw new IllegalArgumentException("Rule '" + type.code() + "' requires a parameter of type " + expected + ".");
        }
        boolean matches = switch (expected) {
            case STRING -> value instanceof String;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case OBJECT -> value != null;
            case LIST -> value instanceof java.util.Collection;
                    };
        if (!matches) {
            throw new IllegalArgumentException("Rule '" + type.code() + "' requires a parameter of type " + expected + ".");
        }
    }
    // --- catalog rule factories ---------------------------------------------
    //
    // One factory per catalog rule, so a rule can be named as a value rather
    // than called as a builder method. With a static import of this class they
    // read as a plain list:
    //
    //     import static dev.regl.Rule.*;
    //
    //     schema.field("server.port", number().with(notNull(), integer(), lessThan(65536)));
    //
    // The builder chain stays the fully type-checked form; these exist so a terse
    // rule list need not fall back to raw constructor calls. Each parameter type
    // is the catalog's `parameterType`, and a test asserts these factories cover
    // every catalog id and nothing more, so the two cannot drift.
    //

    public static Rule minLength(Number value) {
        return new Rule(RuleType.MIN_LENGTH, value);
    }

    public static Rule maxLength(Number value) {
        return new Rule(RuleType.MAX_LENGTH, value);
    }

    public static Rule length(Number value) {
        return new Rule(RuleType.LENGTH, value);
    }

    public static Rule pattern(String value) {
        return new Rule(RuleType.PATTERN, value);
    }

    public static Rule contains(String value) {
        return new Rule(RuleType.CONTAINS, value);
    }

    public static Rule startsWith(String value) {
        return new Rule(RuleType.STARTS_WITH, value);
    }

    public static Rule endsWith(String value) {
        return new Rule(RuleType.ENDS_WITH, value);
    }

    public static Rule email() {
        return new Rule(RuleType.EMAIL);
    }

    public static Rule url() {
        return new Rule(RuleType.URL);
    }

    public static Rule uuid() {
        return new Rule(RuleType.UUID);
    }

    public static Rule languageCode() {
        return new Rule(RuleType.LANGUAGE_CODE);
    }

    public static Rule lowercase() {
        return new Rule(RuleType.LOWERCASE);
    }

    public static Rule uppercase() {
        return new Rule(RuleType.UPPERCASE);
    }

    public static Rule trimmed() {
        return new Rule(RuleType.TRIMMED);
    }

    public static Rule notEmpty() {
        return new Rule(RuleType.NOT_EMPTY);
    }

    public static Rule text() {
        return new Rule(RuleType.TEXT);
    }

    public static Rule dateFormat(String value) {
        return new Rule(RuleType.DATE_FORMAT, value);
    }

    public static Rule isoDate() {
        return new Rule(RuleType.ISO_DATE);
    }

    public static Rule isoDateTime() {
        return new Rule(RuleType.ISO_DATE_TIME);
    }

    public static Rule isoTime() {
        return new Rule(RuleType.ISO_TIME);
    }

    public static Rule notNull() {
        return new Rule(RuleType.NOT_NULL);
    }

    public static Rule enumOf(String... allowed) {
        return new Rule(RuleType.ENUM, List.of(allowed));
    }

    public static Rule after(String value) {
        return new Rule(RuleType.AFTER, value);
    }

    public static Rule before(String value) {
        return new Rule(RuleType.BEFORE, value);
    }

    public static Rule greaterThan(Number value) {
        return new Rule(RuleType.GREATER_THAN, value);
    }

    public static Rule greaterThanOrEqual(Number value) {
        return new Rule(RuleType.GREATER_THAN_OR_EQUAL, value);
    }

    public static Rule lessThan(Number value) {
        return new Rule(RuleType.LESS_THAN, value);
    }

    public static Rule lessThanOrEqual(Number value) {
        return new Rule(RuleType.LESS_THAN_OR_EQUAL, value);
    }

    public static Rule positive() {
        return new Rule(RuleType.POSITIVE);
    }

    public static Rule negative() {
        return new Rule(RuleType.NEGATIVE);
    }

    public static Rule positiveInteger() {
        return new Rule(RuleType.POSITIVE_INTEGER);
    }

    public static Rule negativeInteger() {
        return new Rule(RuleType.NEGATIVE_INTEGER);
    }

    public static Rule multipleOf(Number value) {
        return new Rule(RuleType.MULTIPLE_OF, value);
    }

    public static Rule precision(Number value) {
        return new Rule(RuleType.PRECISION, value);
    }

    public static Rule epoch() {
        return new Rule(RuleType.EPOCH);
    }

    public static Rule epochMillis() {
        return new Rule(RuleType.EPOCH_MILLIS);
    }

    public static Rule range(Range range) {
        return new Rule(RuleType.RANGE, range);
    }

    public static Rule unique() {
        return new Rule(RuleType.UNIQUE);
    }

    public static Rule isTrue() {
        return new Rule(RuleType.IS_TRUE);
    }

    public static Rule isFalse() {
        return new Rule(RuleType.IS_FALSE);
    }
}
