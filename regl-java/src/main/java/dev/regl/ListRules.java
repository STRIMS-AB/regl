package dev.regl;

/**
 * Generic list / collection rules inherited across all collection-compatible field builders.
 */
public interface ListRules<SELF extends RuleContainer<SELF>> extends RuleContainer<SELF> {

    default SELF range(Integer min, Integer max) {
        return addRule(new Rule(RuleType.RANGE, new Range(min, max)));
    }

    default SELF unique() {
        return addRule(new Rule(RuleType.UNIQUE));
    }
}
