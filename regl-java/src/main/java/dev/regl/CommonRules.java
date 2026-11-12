package dev.regl;

import java.util.List;

/**
 * Generic rules applicable across all field types (nullability and enumerated sets).
 */
public interface CommonRules<SELF extends RuleContainer<SELF>> extends RuleContainer<SELF> {

    default SELF notNull() {
        return addRule(new Rule(RuleType.NOT_NULL));
    }

    default SELF enumOf(String... allowed) {
        return addRule(new Rule(RuleType.ENUM, List.of(allowed)));
    }

    default SELF enumOf(List<String> allowed) {
        return addRule(new Rule(RuleType.ENUM, List.copyOf(allowed)));
    }

    default SELF allowedValues(List<?> allowed) {
        return addRule(new Rule(RuleType.ENUM, List.copyOf(allowed)));
    }


}
