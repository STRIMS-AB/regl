package dev.regl;

/**
 * Canonical catalog of value transformation functions defined in
 * {@code regl-spec/transformations-catalog.json}.
 */
public enum TransformType {
    // STRING
    LOWER_CASE("lowerCase", null),
    UPPER_CASE("upperCase", null),
    TRIM("trim", null),
    TITLE_CASE("titleCase", null),
    PREFIX("prefix", ParameterType.STRING),
    SUFFIX("suffix", ParameterType.STRING),
    STRIP_PREFIX("stripPrefix", ParameterType.STRING),
    STRIP_SUFFIX("stripSuffix", ParameterType.STRING),
    REPLACE("replace", ParameterType.OBJECT),
    SUBSTRING("substring", ParameterType.OBJECT),
    JOIN("join", ParameterType.STRING),

    // NUMBER
    MULTIPLY("multiply", ParameterType.NUMBER),
    DIVIDE("divide", ParameterType.NUMBER),
    ABS("abs", null),
    ROUND("round", null),
    ROUND_TO("roundTo", ParameterType.NUMBER),

    // COERCION
    TO_STRING("toString", null),
    TO_NUMBER("toNumber", null),
    TO_BOOLEAN("toBoolean", null),

    // TEMPORAL
    FORMAT_DATE("formatDate", ParameterType.STRING),

    // COMMON
    DEFAULT("default", ParameterType.OBJECT),
    CONSTANT("constant", ParameterType.OBJECT),
    CUSTOM("custom", ParameterType.STRING);

    private final String code;
    private final ParameterType parameterType;

    TransformType(String code, ParameterType parameterType) {
        this.code = code;
        this.parameterType = parameterType;
    }

    public String code() {
        return code;
    }

    public ParameterType parameterType() {
        return parameterType;
    }

    public boolean requiresParameter() {
        return parameterType != null;
    }

    public static TransformType fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Transform type cannot be null.");
        }
        for (TransformType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown transform type: " + code);
    }

    @Override
    public String toString() {
        return code;
    }
}
