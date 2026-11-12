package dev.regl;

/**
 * Evaluates a single regl rule.
 *
 * @param <P> the typed rule parameter
 */
public interface RuleEvaluator<P> {

    /**
     * Rule code, e.g. {@code "startsWith"}. Must be unique across registered
     * evaluators and is the identifier used in catalog documents and validation results.
     */
    String code();
    default String name() { return code(); }
    default String description() { return ""; }

    /**
     * The expected {@link Rule#value()} type, e.g. {@code Integer.class},
     * {@code String.class}, {@code Range.class}, or {@code Void.class} for
     * parameterless rules. Runtimes reject rules whose parameter does not
     * match at validation time.
     */
    Class<P> parameterType();

    /**
     * The target data type this rule applies to.
     */
    TargetType targetType();

    /**
     * Returns {@code true} when {@code input} satisfies this rule for the given
     * rule {@code value}. Constraints ignore inputs of a non-matching type by
     * returning {@code true} (a String rule does not evaluate numbers).
     */
    boolean test(Object input, P value);

    /**
     * Human-readable failure message rendered on the {@link ValidationError}.
     *
     * @param field the validated property name
     * @param input the value that failed (never a passing value)
     * @param value the rule's own argument
     */
    default String message(String field, Object input, P value) {
        return "Field '" + field + "' does not satisfy rule '" + code() + "'.";
    }
}
