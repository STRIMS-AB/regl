package dev.regl;

/**
 * Generic boolean rules inherited across all boolean-compatible field builders.
 */
public interface BooleanRules<SELF extends RuleContainer<SELF>> extends RuleContainer<SELF> {

    default SELF isTrue() {
        return addRule(new Rule(RuleType.IS_TRUE));
    }

    default SELF isFalse() {
        return addRule(new Rule(RuleType.IS_FALSE));
    }
}
