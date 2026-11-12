package dev.regl;

public record ValidationError(
    String field,
    String rule,
    Object value,
    String message
) {
    public ValidationError(String field, String rule, String message) {
        this(field, rule, null, message);
    }
}
