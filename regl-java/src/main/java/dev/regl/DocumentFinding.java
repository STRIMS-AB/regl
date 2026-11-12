package dev.regl;

import java.util.List;

/**
 * One problem found while validating a whole document.
 *
 * <p>A finding is deliberately <em>not</em> a {@link ValidationError}. The scalar
 * API reports a value against a rule; document validation additionally has to
 * report problems with the document's <em>shape</em> — a key read through a
 * scalar, a wildcard over something that is not a list, a value whose kind
 * contradicts the field's declared type. Those are not rule failures and
 * collapsing them into one shape would force every consumer to string-match the
 * message to tell a structural bug apart from ordinary bad input.
 *
 * <p>Keeping the two apart also keeps {@code ValidationError} frozen: no field is
 * added to it, so no existing consumer changes behaviour.
 *
 * @param severity {@link Severity#ERROR} or {@link Severity#WARNING}; a warning
 *                 never makes the result invalid
 * @param kind     what sort of problem this is, which is the field a consumer
 *                 should branch on
 * @param path     the concrete path the problem was found at, with wildcards
 *                 replaced by indices, e.g. {@code "servers.2.port"}
 * @param rule     the rule code, or {@code null} for a structural finding that
 *                 no rule is responsible for
 * @param value    the rule's own argument, or {@code null}
 * @param message  a convenience English message; localize from {@code kind},
 *                 {@code rule}, and {@code value} against the i18n bundles
 */
public record DocumentFinding(
    Severity severity,
    Kind kind,
    String path,
    String rule,
    Object value,
    String message
) {

    /** Whether a finding blocks validation. */
    public enum Severity { ERROR, WARNING }

    /**
     * The category of a finding.
     *
     * <p>There is no {@code MISSING}. A missing field resolves to {@code null}
     * (see the schema specification), so a missing mandatory field is an
     * ordinary {@link #RULE_FAILED} on {@code notNull}.
     */
    public enum Kind {
        /** A rule's predicate returned false for a value of the right kind. */
        RULE_FAILED,
        /**
         * A present, non-null value's kind does not match the type its field
         * declared. No rule is named: every rule in a chain shares the declared
         * type, so naming any one of them would be arbitrary.
         */
        TYPE_MISMATCH,
        /** A literal key was read through a value that is not an object. */
        UNREACHABLE,
        /** A wildcard was applied to a value that is not a list. */
        NOT_A_LIST,
        /** A strict schema found an object key that no field covers. */
        UNKNOWN_FIELD
    }

    public DocumentFinding {
        if (severity == null) throw new IllegalArgumentException("Finding severity cannot be null.");
        if (kind == null) throw new IllegalArgumentException("Finding kind cannot be null.");
        if (path == null) throw new IllegalArgumentException("Finding path cannot be null.");
    }

    public static DocumentFinding error(Kind kind, String path, String rule, Object value, String message) {
        return new DocumentFinding(Severity.ERROR, kind, path, rule, value, message);
    }

    /** An error that belongs to no single rule, such as a shape problem. */
    public static DocumentFinding error(Kind kind, String path, String message) {
        return new DocumentFinding(Severity.ERROR, kind, path, null, null, message);
    }

    public static DocumentFinding warning(Kind kind, String path, String rule, Object value, String message) {
        return new DocumentFinding(Severity.WARNING, kind, path, rule, value, message);
    }

    /** A rule failure at a known severity. */
    public static DocumentFinding of(Severity severity, Kind kind, String path, Rule rule, String message) {
        return new DocumentFinding(severity, kind, path, rule.code(), rule.value(), message);
    }

    /**
     * A finding that belongs to no single rule: a shape problem, or the type
     * mismatch against a field's declared type.
     */
    public static DocumentFinding of(Severity severity, Kind kind, String path, String message) {
        return new DocumentFinding(severity, kind, path, null, null, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /**
     * The path split into segments, with any escapes removed. Useful for a UI
     * that highlights a location, and for tests that assert on structure rather
     * than on a joined string.
     */
    public List<String> segments() {
        return Path.parse(path);
    }
}