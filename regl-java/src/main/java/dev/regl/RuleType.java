package dev.regl;

// GENERATED FILE — DO NOT EDIT BY HAND.
// Source of truth: regl-spec/catalog.json
// Regenerate with: mvn -q test-compile exec:java -Dexec.mainClass=dev.regl.RuleTypeGenerator -Dexec.args=--write

/**
 * The built-in rule set, derived from {@code regl-spec/catalog.json}.
 *
 * <p>Every enum constant corresponds to one catalog entry. Rule semantics
 * are implemented by the evaluators in {@link RuleEvaluators}.
 */
public enum RuleType {
    // STRING
    MIN_LENGTH("minLength", ParameterType.NUMBER, TargetType.STRING),
    MAX_LENGTH("maxLength", ParameterType.NUMBER, TargetType.STRING),
    LENGTH("length", ParameterType.NUMBER, TargetType.STRING),
    PATTERN("pattern", ParameterType.STRING, TargetType.STRING),
    CONTAINS("contains", ParameterType.STRING, TargetType.STRING),
    STARTS_WITH("startsWith", ParameterType.STRING, TargetType.STRING),
    ENDS_WITH("endsWith", ParameterType.STRING, TargetType.STRING),
    EMAIL("email", null, TargetType.STRING),
    URL("url", null, TargetType.STRING),
    UUID("uuid", null, TargetType.STRING),
    LANGUAGE_CODE("languageCode", null, TargetType.STRING),
    LOWERCASE("lowercase", null, TargetType.STRING),
    UPPERCASE("uppercase", null, TargetType.STRING),
    TRIMMED("trimmed", null, TargetType.STRING),
    NOT_EMPTY("notEmpty", null, TargetType.STRING),
    TEXT("text", null, TargetType.STRING),
    DATE_FORMAT("dateFormat", ParameterType.STRING, TargetType.STRING),
    ISO_DATE("isoDate", null, TargetType.STRING),
    ISO_DATE_TIME("isoDateTime", null, TargetType.STRING),
    ISO_TIME("isoTime", null, TargetType.STRING),
    AFTER("after", ParameterType.STRING, TargetType.STRING),
    BEFORE("before", ParameterType.STRING, TargetType.STRING),

    // NUMBER
    GREATER_THAN("greaterThan", ParameterType.NUMBER, TargetType.NUMBER),
    GREATER_THAN_OR_EQUAL("greaterThanOrEqual", ParameterType.NUMBER, TargetType.NUMBER),
    LESS_THAN("lessThan", ParameterType.NUMBER, TargetType.NUMBER),
    LESS_THAN_OR_EQUAL("lessThanOrEqual", ParameterType.NUMBER, TargetType.NUMBER),
    POSITIVE("positive", null, TargetType.NUMBER),
    NEGATIVE("negative", null, TargetType.NUMBER),
    POSITIVE_INTEGER("positiveInteger", null, TargetType.NUMBER),
    NEGATIVE_INTEGER("negativeInteger", null, TargetType.NUMBER),
    MULTIPLE_OF("multipleOf", ParameterType.NUMBER, TargetType.NUMBER),
    PRECISION("precision", ParameterType.NUMBER, TargetType.NUMBER),

    // BOOLEAN
    IS_TRUE("isTrue", null, TargetType.BOOLEAN),
    IS_FALSE("isFalse", null, TargetType.BOOLEAN),

    // LIST
    RANGE("range", ParameterType.OBJECT, TargetType.LIST),
    UNIQUE("unique", null, TargetType.LIST),

    // ANY
    NOT_NULL("notNull", null, TargetType.ANY),
    ENUM("enum", ParameterType.LIST, TargetType.ANY),

    // DATETIME
    EPOCH("epoch", null, TargetType.DATETIME),
    EPOCH_MILLIS("epochMillis", null, TargetType.DATETIME);

    private final String code;
    private final ParameterType parameterType;
    private final TargetType targetType;

    RuleType(String code, ParameterType parameterType, TargetType targetType) {
        this.code = code;
        this.parameterType = parameterType;
        this.targetType = targetType;
    }

    /** The catalog rule id, e.g. {@code "startsWith"}. */
    public String code() {
        return code;
    }

    /** The catalog parameter type, or {@code null} for parameterless rules. */
    public ParameterType parameterType() {
        return parameterType;
    }

    /** The catalog target type this rule applies to. */
    public TargetType targetType() {
        return targetType;
    }

    /**
     * Resolves {@code code} to its built-in {@link RuleType}.
     *
     * @throws IllegalArgumentException if the code is null or unknown
     */
    public static RuleType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Rule type cannot be null.");
        }
        for (RuleType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown rule type: " + code);
    }

    /**
     * Resolves {@code code} to its built-in {@link RuleType}, or {@code null}
     * when the code is not a built-in rule (i.e. a custom rule backed by a
     * registered {@link RuleEvaluator}).
     */
    public static RuleType fromCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        for (RuleType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return code;
    }
}
