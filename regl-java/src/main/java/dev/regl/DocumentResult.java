package dev.regl;

import java.util.Collections;
import java.util.List;

/**
 * The aggregated outcome of validating a whole document against a {@link Schema}.
 *
 * <p>Mirrors {@link ValidationResult}'s shape — {@code valid}, {@code errors},
 * {@code warnings} — so the two APIs are familiar to use side by side, while
 * carrying {@link DocumentFinding} rather than {@link ValidationError}.
 *
 * <p>Findings arrive in a total order (field declaration index, then rule
 * position, then list index, then kind), identical in every engine.
 */
public record DocumentResult(
    boolean isValid,
    List<DocumentFinding> errors,
    List<DocumentFinding> warnings
) {
    public DocumentResult {
        errors = errors == null ? Collections.emptyList() : List.copyOf(errors);
        warnings = warnings == null ? Collections.emptyList() : List.copyOf(warnings);
    }

    /** Builds a result; a document with only warnings is still valid. */
    public static DocumentResult of(List<DocumentFinding> errors, List<DocumentFinding> warnings) {
        return new DocumentResult(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }

    /** Every finding, errors first then warnings, in the order they were produced. */
    public List<DocumentFinding> findings() {
        List<DocumentFinding> all = new java.util.ArrayList<>(errors);
        all.addAll(warnings);
        return List.copyOf(all);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /** Whether any error is of the given kind. */
    public boolean has(DocumentFinding.Kind kind) {
        return errors.stream().anyMatch(f -> f.kind() == kind)
                || warnings.stream().anyMatch(f -> f.kind() == kind);
    }
}