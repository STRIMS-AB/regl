package dev.regl;

import java.util.Collections;
import java.util.List;

public record ValidationResult(
    boolean isValid,
    List<ValidationError> errors,
    List<ValidationError> warnings
) {
    public ValidationResult {
        if (errors == null) errors = Collections.emptyList();
        if (warnings == null) warnings = Collections.emptyList();
    }

    public static ValidationResult valid() {
        return new ValidationResult(true, Collections.emptyList(), Collections.emptyList());
    }

    public static ValidationResult invalid(List<ValidationError> errors) {
        return new ValidationResult(false, List.copyOf(errors), Collections.emptyList());
    }

    public static ValidationResult of(List<ValidationError> errors) {
        return errors.isEmpty() ? valid() : invalid(errors);
    }

    /**
     * Builds a result from the error (blocking) and warning (non-blocking) issues.
     * A result with only warnings is still valid.
     */
    public static ValidationResult of(List<ValidationError> errors, List<ValidationError> warnings) {
        return new ValidationResult(
                errors.isEmpty(),
                List.copyOf(errors),
                List.copyOf(warnings));
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}