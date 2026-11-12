package dev.regl;

/**
 * Common contract for any builder or definition that accepts validation {@link Rule}s.
 *
 * @param <SELF> the returning type for chaining
 */
public interface RuleContainer<SELF> {
    SELF addRule(Rule rule);
}
