package dev.regl;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Registry of {@link RuleEvaluator}s, keyed by rule code. The built-in
 * rules (all entries of {@link RuleType}) are pre-registered; custom
 * rules are added at runtime with {@link #register(RuleEvaluator)}.
 *
 * <p>Every registered evaluator must declare a unique {@link RuleEvaluator#code()}
 * and either a parameter type or {@code Void}. {@link Rule#validate(String, Object)}
 * resolves each {@link Rule} to its evaluator by code and rejects rules whose parameter
 * does not match the evaluator's declared parameter type.
 */
public final class RuleEvaluators {

    private static final Map<String, RuleEvaluator<?>> EVALUATORS = new LinkedHashMap<>();
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    /**
     * Simplified BCP-47 well-formedness: language[-extlang][-script][-region][-variant...](-x-private...).
     * Grandfathered and extension (singleton) subtags are not modelled.
     */
    private static final Pattern LANG_TAG_PATTERN = Pattern.compile(
            "^[a-zA-Z]{2,3}(-[a-zA-Z]{3}(-[a-zA-Z]{3}){0,2})?(-[a-zA-Z]{4})?(-[a-zA-Z]{2}|-[0-9]{3})?"
                    + "(-([a-zA-Z0-9]{5,8}|[0-9][a-zA-Z0-9]{3}))*(-x(-[a-zA-Z0-9]{1,8})+)?$");

    static {
        registerBuiltIns();
    }

    private RuleEvaluators() {
    }

    /**
     * Registers a custom rule evaluator. Fails when a rule with the same
     * {@link RuleEvaluator#code()} is already registered.
     */
    public static void register(RuleEvaluator<?> evaluator) {
        if (evaluator == null || evaluator.code() == null || evaluator.code().isBlank()) {
            throw new IllegalArgumentException("Evaluator and its non-blank code() must not be null.");
        }
        String code = evaluator.code();
        if (EVALUATORS.containsKey(code)) {
            throw new IllegalArgumentException("A rule evaluator is already registered for code: " + code);
        }
        EVALUATORS.put(code, evaluator);
    }

    /** Removes the evaluator registered for {@code code}; returns whether one was present. */
    public static boolean unregister(String code) {
        return EVALUATORS.remove(code) != null;
    }

    /** Returns the evaluator registered for {@code code}, if any. */
    public static Optional<RuleEvaluator<?>> find(String code) {
        return Optional.ofNullable(EVALUATORS.get(code));
    }

    /** Returns the evaluator registered for {@code code}, throwing when absent. */
    public static RuleEvaluator<?> require(String code) {
        RuleEvaluator<?> evaluator = EVALUATORS.get(code);
        if (evaluator == null) {
            throw new IllegalArgumentException("No rule evaluator registered for code: " + code
                    + " — register a custom evaluator via RuleEvaluators.register(…).");
        }
        return evaluator;
    }

    /** Whether an evaluator is registered for {@code code}. */
    public static boolean isRegistered(String code) {
        return EVALUATORS.containsKey(code);
    }

    /**
     * All registered evaluators, in registration order.
     *
     * <p>The returned list is unmodifiable; use {@link #register} and
     * {@link #unregister} to change the registry.
     */
    public static java.util.List<RuleEvaluator<?>> getAll() {
        return java.util.List.copyOf(EVALUATORS.values());
    }

    /** All registered evaluator codes, in registration order. */
    public static java.util.Set<String> codes() {
        return java.util.Collections.unmodifiableSet(EVALUATORS.keySet());
    }

    /**
     * Evaluates {@code input} against an {@code evaluator} resolved from a {@code ?}
     * wildcard. The parameter is passed as {@link Object}; {@link #checkParameter}
     * guarantees it matches the evaluator's declared parameter type.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean test(RuleEvaluator<?> evaluator, Object input, Object value) {
        RuleEvaluator raw = evaluator;
        return raw.test(input, value);
    }

    /**
     * Renders the failure message for an {@code evaluator} resolved from a {@code ?}
     * wildcard.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String message(RuleEvaluator<?> evaluator, String field, Object input, Object value) {
        RuleEvaluator raw = evaluator;
        return raw.message(field, input, value);
    }

    /**
     * Rejects a rule whose parameter does not match the evaluator's declared
     * {@link RuleEvaluator#parameterType()} (or that carries a parameter when the
     * evaluator is parameterless).
     */
    static void checkParameter(Rule rule, RuleEvaluator<?> evaluator) {
        Class<?> expected = evaluator.parameterType();
        Object value = rule.value();
        boolean parameterless = expected == Void.class || expected == void.class || expected == null;

        if (parameterless) {
            if (value != null) {
                throw new IllegalArgumentException("Rule '" + rule.code() + "' does not accept a parameter.");
            }
            return;
        }
        if (value == null) {
            throw new IllegalArgumentException("Rule '" + rule.code()
                    + "' requires a parameter of type " + expected.getSimpleName() + ".");
        }
        if (!expected.isInstance(value)) {
            throw new IllegalArgumentException("Rule '" + rule.code()
                    + "' requires a parameter of type " + expected.getSimpleName()
                    + ", got " + value.getClass().getSimpleName() + ".");
        }
    }

    /** Whether a number has no fractional part — the {@code INTEGER} test, shared with the evaluator table. */
    static boolean isWholeNumber(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.floor(d);
    }

    private interface Message<P> {
        String apply(String field, Object input, P value);
    }

    /**
     * Whether {@code input} is of the kind this rule's {@link TargetType}
     * declares, and therefore something the evaluator can meaningfully test.
     *
     * <p>A rule only speaks about values of its own target type: a
     * {@code minLength} rule is about strings, not numbers. When a value is of
     * another kind the rule cannot produce a meaningful verdict, so the
     * mismatch is <em>reported</em> as a validation error rather than coerced
     * or silently passed. This keeps a malformed document a reportable result
     * instead of a runtime error, and keeps the engines in step.
     */
    static boolean matchesTargetType(TargetType targetType, Object value) {
        if (targetType == null || targetType == TargetType.ANY) {
            return true;
        }
        return switch (targetType) {
            case STRING -> value instanceof String;
            case NUMBER -> value instanceof Number;
            // Mathematical integrality, not a Java class: a JSON integer above
            // 2^31 arrives as Long, and 1.0 as Double, both of which are whole
            // numbers. Only a value with a fractional part is not an INTEGER.
            case INTEGER -> value instanceof Number n
                    && isWholeNumber(n.doubleValue());
            case BOOLEAN -> value instanceof Boolean;
            case LIST -> value instanceof Collection<?>;
            case DATE -> value instanceof java.time.LocalDate
                    || value instanceof java.time.temporal.TemporalAccessor
                    || (value instanceof String s && TemporalFormats.isIsoDate(s));
            case DATETIME -> value instanceof java.time.LocalDateTime
                    || value instanceof java.time.Instant
                    || value instanceof java.time.temporal.TemporalAccessor
                    || (value instanceof String s && TemporalFormats.isIsoDateTime(s))
                    || (value instanceof Number n && TemporalFormats.isWholeNumber(n));
            case TIME -> value instanceof java.time.LocalTime
                    || value instanceof java.time.temporal.TemporalAccessor
                    || (value instanceof String s && TemporalFormats.isIsoTime(s));
            case ANY -> true;
        };
    }

    /**
     * A short, locale-neutral name for the runtime type of {@code input}, used
     * in the type-mismatch message. Deliberately coarse: it reports the kind the
     * engine can route on, not the concrete class, so the two engines word it
     * identically.
     */
    static String describeType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "STRING";
        }
        if (value instanceof Number) {
            return "NUMBER";
        }
        if (value instanceof Boolean) {
            return "BOOLEAN";
        }
        if (value instanceof Collection<?>) {
            return "LIST";
        }
        return value.getClass().getSimpleName();
    }

    /**
     * Counts the decimal places of {@code input} in its plain (non-exponent)
     * representation, discarding trailing zeros and never going below zero.
     *
     * <p>Working in plain decimal notation is what keeps this engine in step
     * with {@code regl-ts}, where every number is a double whose string form
     * switches to exponent notation at a different threshold than the JVM's
     * {@code Double.toString}. Counting scale directly off a
     * {@link BigDecimal} built from {@code toString()} would report {@code 5.0}
     * as having one decimal place, because the JVM renders it {@code "5.0"}.
     */
    private static int decimalPlaces(Number value) {
        if (value instanceof BigDecimal bd) {
            return Math.max(0, bd.stripTrailingZeros().scale());
        }
        if (value instanceof Double || value instanceof Float) {
            double d = value.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return 0;
            }
            return Math.max(0, new BigDecimal(Double.toString(d)).stripTrailingZeros().scale());
        }
        return Math.max(0, new BigDecimal(value.toString()).stripTrailingZeros().scale());
    }

    private static <P> void reg(String code, String name, String desc, Class<P> paramType, TargetType targetType,
                                BiPredicate<Object, P> test, Message<P> message) {
        EVALUATORS.put(code, new RuleEvaluator<P>() {
            public String code() {
                return code;
            }
            public String name() {
                return name;
            }
            public String description() {
                return desc;
            }

            public Class<P> parameterType() {
                return paramType;
            }

            public TargetType targetType() {
                return targetType;
            }

            public boolean test(Object input, P value) {
                // An input of the wrong kind cannot be tested by this rule, so it
                // counts as a failure rather than reaching a predicate that would
                // cast it and throw. Rule.validate reports it as a type mismatch.
                return matchesTargetType(targetType, input) && test.test(input, value);
            }

            public String message(String field, Object input, P value) {
                if (!matchesTargetType(targetType, input)) {
                    return "Value must be of type " + targetType + ", but got " + describeType(input) + ".";
                }
                return message.apply(field, input, value);
            }
        });
    }

    private static void regFlag(String code, String name, String desc, TargetType targetType, Predicate<Object> test, Message<Void> message) {
        reg(code, name, desc, Void.class, targetType, (input, value) -> test.test(input), message);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerBuiltIns() {
        // ----- STRING -----
        reg("minLength", "Minimum Length", "Requires text to be at least {value} characters.", Integer.class, TargetType.STRING,
                (value, p) -> !(value instanceof String s) || s.length() >= (p == null ? 0 : p),
                (field, value, p) -> {
                    String s = (String) value;
                    int min = p == null ? 0 : p;
                    return "Minimum length is " + min + ", but got " + s.length() + ".";
                });
        reg("maxLength", "Maximum Length", "Requires text to be no longer than {value} characters.", Integer.class, TargetType.STRING,
                (value, p) -> !(value instanceof String s) || s.length() <= (p == null ? Integer.MAX_VALUE : p),
                (field, value, p) -> {
                    String s = (String) value;
                    int max = p == null ? Integer.MAX_VALUE : p;
                    return "Maximum length is " + max + ", but got " + s.length() + ".";
                });
        reg("length", "Exact Length", "Requires text to be exactly {value} characters.", Integer.class, TargetType.STRING,
                (value, p) -> !(value instanceof String s) || p == null || s.length() == p,
                (field, value, p) -> {
                    String s = (String) value;
                    return "Exact length must be " + p + ", but got " + s.length() + ".";
                });
        reg("pattern", "Regex Pattern", "Requires text to match the regular expression: {value}.", String.class, TargetType.STRING,
                (value, p) -> !(value instanceof String s) || (p != null && Regex.find(p, s)),
                (field, value, p) -> "Value does not match required regex pattern: " + p);
        reg("contains", "Contains Text", "Requires text to contain the substring: {value}.", String.class, TargetType.STRING,
                (value, p) -> !(value instanceof String s) || (p != null && s.contains(p)),
                (field, value, p) -> "Value must contain '" + p + "'.");
        reg("startsWith", "Starts With", "Requires text to start with: {value}.", String.class, TargetType.STRING,
                (value, p) -> !(value instanceof String s) || (p != null && s.startsWith(p)),
                (field, value, p) -> "Value must start with '" + p + "'.");
        reg("endsWith", "Ends With", "Requires text to end with: {value}.", String.class, TargetType.STRING,
                (value, p) -> !(value instanceof String s) || (p != null && s.endsWith(p)),
                (field, value, p) -> "Value must end with '" + p + "'.");
        regFlag("email", "Email Address", "Requires a valid email address format.", TargetType.STRING, value -> {
            String s = (String) value;
            return EMAIL_PATTERN.matcher(s).matches();
        }, (field, value, p) -> "Value is not a valid email address.");
        regFlag("url", "URL", "Requires a valid HTTP or HTTPS URL.", TargetType.STRING, value -> {
            String s = (String) value;
            try {
                URI uri = URI.create(s);
                return uri.getScheme() != null
                        && (uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"));
            } catch (Exception e) {
                return false;
            }
        }, (field, value, p) -> "Value must be a valid HTTP or HTTPS URL.");
        regFlag("uuid", "UUID", "Requires a valid UUID format.", TargetType.STRING, value -> UUID_PATTERN.matcher((String) value).matches(),
                (field, value, p) -> "Value is not a valid UUID format.");
        regFlag("languageCode", "Language Code", "Requires a well-formed BCP-47 language tag.", TargetType.STRING, value -> LANG_TAG_PATTERN.matcher((String) value).matches(),
                (field, value, p) -> "Value is not a well-formed BCP-47 language tag (e.g. 'en', 'pt-BR').");
        regFlag("lowercase", "Lowercase", "Requires all characters to be lowercase.", TargetType.STRING, value -> ((String) value).equals(((String) value).toLowerCase()),
                (field, value, p) -> "Value must be all lowercase.");
        regFlag("uppercase", "Uppercase", "Requires all characters to be uppercase.", TargetType.STRING, value -> ((String) value).equals(((String) value).toUpperCase()),
                (field, value, p) -> "Value must be all uppercase.");
        regFlag("trimmed", "Trimmed", "Requires no leading or trailing whitespace.", TargetType.STRING, value -> ((String) value).equals(((String) value).trim()),
                (field, value, p) -> "Value must not have leading or trailing whitespace.");
        regFlag("notEmpty", "Not Empty", "Requires text to contain at least one non-whitespace character.", TargetType.STRING, value -> !((String) value).trim().isEmpty(),
                (field, value, p) -> "Value cannot be blank or empty.");
        regFlag("text", "Human-Readable Text", "Marks a field as human-readable text.", TargetType.STRING, value -> true,
                (field, value, p) -> "Field '" + field + "' is declared as human-readable text.");
        reg("dateFormat", "Date Format", "Requires date/time string to match format pattern: {value}.", String.class, TargetType.STRING,
                (value, p) -> !(value instanceof String s) || p == null || TemporalFormats.matchesPattern(p, s),
                (field, value, p) -> "Value '" + value + "' does not match date format '" + p + "'.");
        regFlag("isoDate", "ISO Date", "Requires a valid ISO-8601 calendar date (YYYY-MM-DD).", TargetType.STRING,
                TemporalFormats::isIsoDate,
                (field, value, p) -> "Value '" + value + "' must be a valid ISO-8601 date (YYYY-MM-DD).");
        regFlag("isoDateTime", "ISO Date-Time", "Requires a valid ISO-8601 date-time timestamp.", TargetType.STRING,
                TemporalFormats::isIsoDateTime,
                (field, value, p) -> "Value '" + value + "' must be a valid ISO-8601 date-time timestamp.");
        regFlag("isoTime", "ISO Time", "Requires a valid ISO-8601 time (HH:mm:ss).", TargetType.STRING,
                TemporalFormats::isIsoTime,
                (field, value, p) -> "Value '" + value + "' must be a valid ISO-8601 time (HH:mm:ss).");
        // TargetType.ANY, so it is also the one rule that can accept a null: the
        // type guard lets null through and this predicate is what rejects it.
        regFlag("notNull", "Not Null", "Requires the value to be present and not null.", TargetType.ANY, value -> value != null,
                (field, value, p) -> "Value is required and must not be null.");
        reg("enum", "Allowed Values", "Restricts the value to a predefined list of allowed strings: {value}.", (Class<List<String>>) (Class<?>) List.class, TargetType.ANY,
                (value, p) -> {
                    if (!(value instanceof String s)) {
                        return true;
                    }
                    return p == null || p.isEmpty() || p.contains(s);
                },
                (field, value, p) -> "Value '" + value + "' is not in allowed values: " + p);
        reg("after", "After Date", "Requires date/time to be chronologically after {value}.", String.class, TargetType.STRING,
                (value, p) -> {
                    if (value == null || p == null) return true;
                    try {
                        return TemporalFormats.compare(value, p) > 0;
                    } catch (Exception e) {
                        return false;
                    }
                },
                (field, value, p) -> "Value '" + value + "' must be chronologically after '" + p + "'.");
        reg("before", "Before Date", "Requires date/time to be chronologically before {value}.", String.class, TargetType.STRING,
                (value, p) -> {
                    if (value == null || p == null) return true;
                    try {
                        return TemporalFormats.compare(value, p) < 0;
                    } catch (Exception e) {
                        return false;
                    }
                },
                (field, value, p) -> "Value '" + value + "' must be chronologically before '" + p + "'.");

        // ----- NUMBER -----
        reg("greaterThan", "Greater Than", "Requires the number to be strictly greater than {value}.", Number.class, TargetType.NUMBER,
                (value, p) -> !(value instanceof Number n) || n.doubleValue() > (p == null ? 0 : p.doubleValue()),
                (field, value, p) -> "Value must be strictly greater than " + (p == null ? 0 : p.doubleValue()) + ".");
        reg("greaterThanOrEqual", "Greater Than or Equal", "Requires the number to be greater than or equal to {value}.", Number.class, TargetType.NUMBER,
                (value, p) -> !(value instanceof Number n) || n.doubleValue() >= (p == null ? 0 : p.doubleValue()),
                (field, value, p) -> "Value must be greater than or equal to " + (p == null ? 0 : p.doubleValue()) + ".");
        reg("lessThan", "Less Than", "Requires the number to be strictly less than {value}.", Number.class, TargetType.NUMBER,
                (value, p) -> !(value instanceof Number n) || n.doubleValue() < (p == null ? 0 : p.doubleValue()),
                (field, value, p) -> "Value must be strictly less than " + (p == null ? 0 : p.doubleValue()) + ".");
        reg("lessThanOrEqual", "Less Than or Equal", "Requires the number to be less than or equal to {value}.", Number.class, TargetType.NUMBER,
                (value, p) -> !(value instanceof Number n) || n.doubleValue() <= (p == null ? 0 : p.doubleValue()),
                (field, value, p) -> "Value must be less than or equal to " + (p == null ? 0 : p.doubleValue()) + ".");
        regFlag("positive", "Positive Number", "Requires a number strictly greater than zero.", TargetType.NUMBER, value -> ((Number) value).doubleValue() > 0,
                (field, value, p) -> "Value must be positive (> 0).");
        regFlag("negative", "Negative Number", "Requires a number strictly less than zero.", TargetType.NUMBER, value -> ((Number) value).doubleValue() < 0,
                (field, value, p) -> "Value must be negative (< 0).");
        regFlag("positiveInteger", "Positive Integer", "Requires a whole number strictly greater than zero.", TargetType.NUMBER, value -> {
            double d = ((Number) value).doubleValue();
            return d == Math.floor(d) && d > 0;
        }, (field, value, p) -> "Value must be an integer greater than zero.");
        regFlag("negativeInteger", "Negative Integer", "Requires a whole number strictly less than zero.", TargetType.NUMBER, value -> {
            double d = ((Number) value).doubleValue();
            return d == Math.floor(d) && d < 0;
        }, (field, value, p) -> "Value must be an integer less than zero.");
        reg("multipleOf", "Multiple Of", "Requires the number to be a multiple of {value}.", Number.class, TargetType.NUMBER,
                (value, p) -> {
                    if (!(value instanceof Number n)) {
                        return true;
                    }
                    double step = p == null ? 1 : p.doubleValue();
                    return step <= 0 || Math.abs(n.doubleValue() % step) <= 0.000001;
                },
                (field, value, p) -> "Value must be a multiple of " + (p == null ? 1 : p.doubleValue()) + ".");
        reg("precision", "Decimal Precision", "Restricts the maximum number of decimal places to {value}.", Integer.class, TargetType.NUMBER,
                (value, p) -> !(value instanceof Number n) || decimalPlaces(n) <= (p == null ? 0 : p),
                (field, value, p) -> {
                    int places = decimalPlaces(((Number) value).doubleValue());
                    int max = p == null ? 0 : p;
                    return "Value has " + places + " decimal places, maximum is " + max + ".";
                });
        regFlag("epoch", "Epoch Seconds", "Requires a whole-number Unix timestamp in seconds.", TargetType.DATETIME,
                TemporalFormats::isEpoch,
                (field, value, p) -> "Value must be a valid whole-number Unix timestamp in seconds.");
        regFlag("epochMillis", "Epoch Milliseconds", "Requires a whole-number Unix timestamp in milliseconds.", TargetType.DATETIME,
                TemporalFormats::isEpochMillis,
                (field, value, p) -> "Value must be a valid whole-number Unix timestamp in milliseconds.");

        // ----- LIST -----
        reg("range", "List Size Range", "Restricts the number of items in a list to the range {value}.", Range.class, TargetType.LIST,
                (value, p) -> !(value instanceof Collection<?> c) || p == null || p.contains(c.size()),
                (field, value, p) -> {
                    int size = ((Collection<?>) value).size();
                    if (p.min() != null && size < p.min()) {
                        return "List must have at least " + p.min() + " items, found " + size + ".";
                    }
                    return "List cannot exceed " + p.max() + " items, found " + size + ".";
                });
        reg("unique", "Unique Items", "Requires all items in the list to be distinct.", Void.class, TargetType.LIST,
                (value, p) -> {
                    Collection<?> c = (Collection<?>) value;
                    Set<Object> set = new HashSet<>(c);
                    return set.size() == c.size();
                },
                (field, value, p) -> "All elements in list '" + field + "' must be unique.");

        // ----- BOOLEAN -----
        regFlag("isTrue", "Is True", "Requires a boolean value to be true.", TargetType.BOOLEAN, value -> value instanceof Boolean b && b,
                (field, value, p) -> "Field must be true.");
        regFlag("isFalse", "Is False", "Requires a boolean value to be false.", TargetType.BOOLEAN, value -> value instanceof Boolean b && !b,
                (field, value, p) -> "Field must be false.");
    }
}