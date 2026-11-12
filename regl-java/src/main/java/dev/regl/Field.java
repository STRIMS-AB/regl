package dev.regl;

import java.util.ArrayList;
import java.util.List;

/**
 * A field: the type a path must hold, plus the rules applied to it.
 *
 * <p>Each target type has its own builder — {@link StringField},
 * {@link NumberField}, {@link BooleanField}, {@link ListField},
 * {@link AnyField} — and a builder exposes only the rules that target its type.
 * An incoherent chain such as {@code string().greaterThan(5)} therefore does not
 * compile, rather than being caught at runtime.
 *
 * <p>The method list per builder is derived from the catalog's
 * {@code targetType} values, so it cannot drift from the rule set: adding a
 * {@code STRING} rule adds one method to {@link StringField} and none to the
 * others. The two {@link TargetType#ANY}-target rules live here because they are
 * shared by every builder rather than owned by one.
 *
 * <p>For schemas that come from data rather than from source — a serialized
 * definition, a test, a configuration table — use {@link #of}, which declares
 * the type explicitly and validates it at runtime instead.
 *
 * @param <SELF> the concrete builder type, so chaining keeps its type
 */
public abstract class Field<SELF extends Field<SELF>> implements RuleContainer<SELF>, CommonRules<SELF> {

    private final TargetType type;
    private final List<Rule> rules;

    protected Field(TargetType type, List<Rule> rules) {
        this.type = type;
        this.rules = List.copyOf(rules);
    }

    @Override
    public final SELF addRule(Rule rule) {
        return append(rule);
    }

    /** The type this field's path must hold. */
    public final TargetType type() {
        return type;
    }

    /** The rules applied to the value, in declaration order. */
    public final List<Rule> rules() {
        return rules;
    }

    /**
     * Whether this field requires a value: it declares {@code notNull}.
     *
     * <p>Presence is a catalog rule rather than schema metadata, so it can be
     * localized and fixture-tested like any other constraint.
     */
    public final boolean requiresValue() {
        return rules.stream().anyMatch(r -> r.code().equals(RuleType.NOT_NULL.code()));
    }

    /**
     * Requires the value to be present and not null.
     *
     * <p>A missing field resolves to {@code null}, so this is what makes a field
     * mandatory. Every builder has it, because presence applies to every type.
     */
    public final SELF notNull() {
        return append(new Rule(RuleType.NOT_NULL));
    }

    /**
     * Restricts the value to a set of allowed values.
     *
     * <p>Usable on any builder, including a typed one: on a {@code string()} field
     * it reads as "one of these strings", which is both narrower and clearer than
     * reaching for {@code any()}.
     *
     * <p>Named {@code enumOf} rather than {@code enum} so it does not collide with
     * the {@code enum} keyword in Java or the global {@code enum} in TypeScript;
     * the catalog id is {@code enum}.
     */
    public final SELF enumOf(String... allowed) {
        return append(new Rule(RuleType.ENUM, List.of(allowed)));
    }

    public final SELF enumOf(List<String> allowed) {
        return append(new Rule(RuleType.ENUM, List.copyOf(allowed)));
    }

    /**
     * Appends several rules at once, given as values rather than as a chain.
     *
     * <pre>{@code
     * import static dev.regl.Rule.*;
     *
     * number().with(notNull(), integer(), lessThan(65536))
     * }</pre>
     *
     * <p>Rules are appended in the order given, after any already on the builder,
     * so finding order is unchanged from the equivalent chain. The head still fixes
     * the target type at compile time and each factory still type-checks its own
     * parameter; what this form gives up is the compile-time rejection of a rule
     * that targets a different type, which is then caught here by the same check
     * {@link #of} applies — the rule that was never expressible through the
     * builders alone.
     *
     * @throws IllegalArgumentException if a rule targets a type other than this
     *         field's, or if the argument list is empty
     */
    @SafeVarargs
    public final SELF with(Rule... additions) {
        if (additions == null || additions.length == 0) {
            throw new IllegalArgumentException("with(...) requires at least one rule.");
        }
        List<Rule> next = new ArrayList<>(rules);
        for (Rule rule : additions) {
            if (rule == null) {
                throw new IllegalArgumentException("Rule cannot be null.");
            }
            requireTarget(type, rule);
            next.add(rule);
        }
        return copy(next);
    }

    /**
     * Marks the most recently added rule warn-only, so it is reported as a
     * warning and never makes the document invalid.
     */
    @SuppressWarnings("unchecked")
    public final SELF warnOnly() {
        if (rules.isEmpty()) {
            throw new IllegalStateException("warnOnly() must follow a rule; the chain is empty.");
        }
        List<Rule> next = new ArrayList<>(rules);
        next.set(next.size() - 1, next.get(next.size() - 1).withWarnOnly(true));
        return (SELF) copy(next);
    }

    /** Appends a rule, returning a new builder. The receiver is unchanged. */
    @SuppressWarnings("unchecked")
    protected final SELF append(Rule rule) {
        List<Rule> next = new ArrayList<>(rules);
        next.add(rule);
        return (SELF) copy(next);
    }

    /** Returns a builder of this concrete type carrying {@code rules}. */
    protected abstract SELF copy(List<Rule> rules);

    // --- formal construction ----------------------------------------------

    /**
     * Builds a field for a type given explicitly, validating that every rule
     * matches the declared type.
     *
     * <p>This is the non-builder path, for schemas that arrive as data. It throws
     * {@link IllegalArgumentException} on a mismatch — the same exception the
     * engines already raise for a bad rule parameter, because it is the same
     * class of fault: a rule built wrongly.
     *
     * <pre>{@code
     * Field.of(TargetType.STRING, List.of(new Rule(RuleType.MIN_LENGTH, 1)));
     * Field.of(TargetType.STRING, List.of(new Rule(RuleType.GREATER_THAN, 0)));
     *   // IllegalArgumentException: field declares type STRING but rule
     *   // 'greaterThan' targets NUMBER
     * }</pre>
     */
    public static Field<?> of(TargetType type, List<Rule> rules) {
        if (type == null) throw new IllegalArgumentException("Field type cannot be null.");
        if (rules == null) throw new IllegalArgumentException("Field rules cannot be null.");
        for (Rule rule : rules) {
            requireTarget(type, rule);
        }
        return builderFor(type, rules);
    }

    /**
     * Throws unless {@code rule} may sit on a field of {@code type}.
     *
     * <p>Shared by the formal constructor {@link #of} and the {@link #with}
     * terminal so the two cannot disagree about which rules belong on a type.
     */
    static void requireTarget(TargetType type, Rule rule) {
        TargetType actual = targetTypeOf(rule);
        // notNull and any other TargetType.ANY rule belongs on every field,
        // which is exactly what lets it be shared rather than duplicated.
        if (actual == type || actual == TargetType.ANY) {
            return;
        }
        // An INTEGER field is a NUMBER field with a whole-number assertion on
        // top, so every NUMBER rule speaks about it too — a port is still
        // 'lessThan 65536'. Anything else is a genuine mismatch.
        if (type == TargetType.INTEGER && actual == TargetType.NUMBER) {
            return;
        }
        throw new IllegalArgumentException("Field declares type " + type
                + " but rule '" + rule.code() + "' targets " + actual + ".");
    }

    /** The target type of a built-in or custom rule, or {@code null} if unknown. */
    static TargetType targetTypeOf(Rule rule) {
        return RuleEvaluators.require(rule.code()).targetType();
    }

    static Field<?> builderFor(TargetType type, List<Rule> rules) {
        return switch (type) {
            case STRING -> new StringField(rules);
            case NUMBER -> new NumberField(rules);
            case INTEGER -> new IntegerField(rules);
            case BOOLEAN -> new BooleanField(rules);
            case LIST -> new ListField(rules);
            case ANY -> new AnyField(rules);
            case DATE -> new DateField(rules);
            case DATETIME -> new DateTimeField(rules);
            case TIME -> new TimeField(rules);
        };
    }
}