package dev.regl;

/**
 * Entry points for the type-specific field builders.
 *
 * <pre>{@code
 * import static dev.regl.Rules.*;
 *
 * Schema schema = Schema.create()
 *     .field("name",        string().notNull().minLength(1).maxLength(64).lowercase())
 *     .field("server.port", integer().notNull().greaterThan(0).lessThan(65536))
 *     .field("tags",        list().notNull().unique())
 *     .field("debug",       boolean_().isFalse())
 *     .field("mode",        string().notNull().enumOf("fast", "safe"))
 *     .strict();
 * }</pre>
 *
 * <p>Each factory returns a builder exposing only that type's rules, so
 * {@code string().greaterThan(5)} does not compile.
 */
public final class Rules {

    private Rules() {
    }

    /** A path that must hold a string. */
    public static StringField string() {
        return new StringField(java.util.List.of());
    }

    /** A path that must hold a number. */
    public static NumberField number() {
        return new NumberField(java.util.List.of());
    }

    /**
     * A path that must hold a whole number.
     *
     * <p>A JSON integer is a whole number whatever Java class it arrives in, so
     * {@code 1.0}, {@code 1e3} and {@code 2147483648} all qualify; only a
     * fractional value is a type mismatch.
     */
    public static IntegerField integer() {
        return new IntegerField(java.util.List.of());
    }

    /**
     * A path that must hold a boolean.
     *
     * <p>Java method names cannot be keywords, so this is {@code boolean_()}. Both
     * engines use the same name, so a schema reads identically in Java and in
     * TypeScript.
     */
    public static BooleanField boolean_() {
        return new BooleanField(java.util.List.of());
    }

    /** A path that must hold a list. */
    public static ListField list() {
        return new ListField(java.util.List.of());
    }

    /** A path whose type is not declared. */
    public static AnyField any() {
        return new AnyField(java.util.List.of());
    }

    /** A path that must hold a date. */
    public static DateField date() {
        return new DateField(java.util.List.of());
    }

    /** A path that must hold a date-time timestamp. */
    public static DateTimeField dateTime() {
        return new DateTimeField(java.util.List.of());
    }

    /** A path that must hold a time value. */
    public static TimeField time() {
        return new TimeField(java.util.List.of());
    }

    /**
     * Converts a declarative rule annotation from {@code dev.regl.rules.*} into a {@link Rule}.
     * Returns {@code null} if the annotation is not a recognised regl rule annotation.
     */
    public static Rule fromAnnotation(java.lang.annotation.Annotation a) {
        if (a instanceof dev.regl.rules.MinLength v) return new Rule(RuleType.MIN_LENGTH, v.value());
        if (a instanceof dev.regl.rules.MaxLength v) return new Rule(RuleType.MAX_LENGTH, v.value());
        if (a instanceof dev.regl.rules.Length v) return new Rule(RuleType.LENGTH, v.value());
        if (a instanceof dev.regl.rules.Pattern v) return new Rule(RuleType.PATTERN, v.value());
        if (a instanceof dev.regl.rules.Contains v) return new Rule(RuleType.CONTAINS, v.value());
        if (a instanceof dev.regl.rules.StartsWith v) return new Rule(RuleType.STARTS_WITH, v.value());
        if (a instanceof dev.regl.rules.EndsWith v) return new Rule(RuleType.ENDS_WITH, v.value());
        if (a instanceof dev.regl.rules.Email) return new Rule(RuleType.EMAIL);
        if (a instanceof dev.regl.rules.Url) return new Rule(RuleType.URL);
        if (a instanceof dev.regl.rules.Uuid) return new Rule(RuleType.UUID);
        if (a instanceof dev.regl.rules.LanguageCode) return new Rule(RuleType.LANGUAGE_CODE);
        if (a instanceof dev.regl.rules.Text) return new Rule(RuleType.TEXT);
        if (a instanceof dev.regl.rules.Lowercase) return new Rule(RuleType.LOWERCASE);
        if (a instanceof dev.regl.rules.Uppercase) return new Rule(RuleType.UPPERCASE);
        if (a instanceof dev.regl.rules.Trimmed) return new Rule(RuleType.TRIMMED);
        if (a instanceof dev.regl.rules.NotEmpty) return new Rule(RuleType.NOT_EMPTY);
        if (a instanceof dev.regl.rules.AllowedValues v) return new Rule(RuleType.ENUM, java.util.List.of(v.value()));
        if (a instanceof dev.regl.rules.GreaterThan v) return new Rule(RuleType.GREATER_THAN, v.value());
        if (a instanceof dev.regl.rules.GreaterThanOrEqual v) return new Rule(RuleType.GREATER_THAN_OR_EQUAL, v.value());
        if (a instanceof dev.regl.rules.LessThan v) return new Rule(RuleType.LESS_THAN, v.value());
        if (a instanceof dev.regl.rules.LessThanOrEqual v) return new Rule(RuleType.LESS_THAN_OR_EQUAL, v.value());
        if (a instanceof dev.regl.rules.MultipleOf v) return new Rule(RuleType.MULTIPLE_OF, v.value());
        if (a instanceof dev.regl.rules.Precision v) return new Rule(RuleType.PRECISION, v.value());
        if (a instanceof dev.regl.rules.Positive) return new Rule(RuleType.POSITIVE);
        if (a instanceof dev.regl.rules.Negative) return new Rule(RuleType.NEGATIVE);
        if (a instanceof dev.regl.rules.PositiveInteger) return new Rule(RuleType.POSITIVE_INTEGER);
        if (a instanceof dev.regl.rules.NegativeInteger) return new Rule(RuleType.NEGATIVE_INTEGER);
        if (a instanceof dev.regl.rules.Range v) {
            Integer min = v.min() >= 0 ? v.min() : null;
            Integer max = v.max() >= 0 ? v.max() : null;
            return new Rule(RuleType.RANGE, new Range(min, max));
        }
        if (a instanceof dev.regl.rules.Unique) return new Rule(RuleType.UNIQUE);
        if (a instanceof dev.regl.rules.IsTrue) return new Rule(RuleType.IS_TRUE);
        if (a instanceof dev.regl.rules.IsFalse) return new Rule(RuleType.IS_FALSE);
        if (a instanceof dev.regl.rules.DateFormat v) return new Rule(RuleType.DATE_FORMAT, v.value());
        if (a instanceof dev.regl.rules.IsoDate) return new Rule(RuleType.ISO_DATE);
        if (a instanceof dev.regl.rules.IsoDateTime) return new Rule(RuleType.ISO_DATE_TIME);
        if (a instanceof dev.regl.rules.IsoTime) return new Rule(RuleType.ISO_TIME);
        if (a instanceof dev.regl.rules.Epoch) return new Rule(RuleType.EPOCH);
        if (a instanceof dev.regl.rules.EpochMillis) return new Rule(RuleType.EPOCH_MILLIS);
        if (a instanceof dev.regl.rules.After v) return new Rule(RuleType.AFTER, v.value());
        if (a instanceof dev.regl.rules.Before v) return new Rule(RuleType.BEFORE, v.value());
        return null;
    }
}