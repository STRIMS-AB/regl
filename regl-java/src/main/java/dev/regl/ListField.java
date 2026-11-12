package dev.regl;

import java.util.List;

/**
 * A field whose path must hold a list.
 *
 * <p>A {@code LIST} rule evaluates the collection as a single unit — the size
 * bounds in {@code range}, not the elements. To constrain the elements, address
 * them through the path: {@code .field("tags.*", string().notEmpty())}.
 *
 * <p>Exposes only the rules that target {@link TargetType#LIST}, plus
 * {@code notNull()} inherited from {@link Field}.
 */
public final class ListField extends Field<ListField> implements ListRules<ListField> {

    ListField(List<Rule> rules) {
        super(TargetType.LIST, rules);
    }

    @Override
    protected ListField copy(List<Rule> rules) {
        return new ListField(rules);
    }

    /**
     * Bounds the number of items. Either bound may be {@code null} to leave that
     * side unbounded.
     *
     * <p>Use {@code range} with a {@code min} to require a non-empty list:
     * {@code notNull()} alone does not reject {@code []}.
     */
    public ListField range(Integer min, Integer max) {
        return append(new Rule(RuleType.RANGE, new Range(min, max)));
    }

    public ListField range(Range range) {
        return append(new Rule(RuleType.RANGE, range));
    }

    public ListField unique() {
        return append(new Rule(RuleType.UNIQUE));
    }
}