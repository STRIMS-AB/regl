package dev.regl;

/**
 * A single transformation step applied to a value.
 */
public record Transform(
    TransformType type,
    Object param
) {
    public Transform {
        validateParameter(type, param);
    }

    public Transform(TransformType type) {
        this(type, null);
    }

    public String typeCode() {
        return type.code();
    }

    private static void validateParameter(TransformType type, Object param) {
        if (type == null) {
            throw new IllegalArgumentException("Transform type cannot be null.");
        }
        ParameterType expected = type.parameterType();
        if (type.requiresParameter() && param == null) {
            throw new IllegalArgumentException("Transform '" + type.code() + "' requires a parameter of type " + expected + ".");
        }
        if (!type.requiresParameter()) {
            if (param != null) {
                throw new IllegalArgumentException("Transform '" + type.code() + "' does not accept a parameter.");
            }
            return;
        }
        boolean matches = switch (expected) {
            case STRING -> param instanceof String;
            case NUMBER -> param instanceof Number;
            case BOOLEAN -> param instanceof Boolean;
            case OBJECT -> param != null;
            case LIST -> param instanceof java.util.Collection;
        };
        if (!matches) {
            throw new IllegalArgumentException("Transform '" + type.code() + "' requires a parameter of type " + expected + ".");
        }
    }
}
