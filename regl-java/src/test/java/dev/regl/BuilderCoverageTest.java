package dev.regl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the claim that the type-narrowing builders cannot drift from the catalog:
 * every rule must be reachable from exactly the builders its {@code targetType}
 * names, plus every builder when the target type is {@code ANY}.
 *
 * <p>Drift here is silent. A catalog rule with no builder method would be
 * unconstructible from a schema; a builder method for a rule the catalog assigns
 * elsewhere would let {@code string().greaterThan(5)} compile again. Both are
 * exactly what the builders exist to prevent, and neither is visible at runtime.
 */
class BuilderCoverageTest {

    private static final Map<TargetType, Class<?>> BUILDERS = Map.of(
            TargetType.STRING, StringField.class,
            TargetType.NUMBER, NumberField.class,
            TargetType.INTEGER, IntegerField.class,
            TargetType.BOOLEAN, BooleanField.class,
            TargetType.LIST, ListField.class,
            TargetType.ANY, AnyField.class,
            TargetType.DATE, DateField.class,
            TargetType.DATETIME, DateTimeField.class,
            TargetType.TIME, TimeField.class
    );

    /**
     * Chain methods renamed because the catalog id is a language keyword.
     *
     * <p>Only the id is renamed, and only for the actual collision. Guessing at a
     * suffix instead would let {@code multipleOf} and a hypothetical {@code multiple}
     * rule match each other's methods and hide a real drift.
     */
    private static final Map<String, String> RENAMED = Map.of(
            RuleType.ENUM.code(), "enumOf"
    );

    @Test
    @DisplayName("every catalog rule is reachable from exactly the builders its target type names")
    void buildersMatchCatalog() {
        for (RuleType type : RuleType.values()) {
            for (TargetType target : TargetType.values()) {
                boolean expected = offeredBy(type, target);
                assertEquals(expected, assigns(type, target),
                        type.code() + " targets " + type.targetType()
                                + " and should" + (expected ? "" : " not")
                                + " be reachable from a " + target + " builder");
                // The other direction, and the one that keeps the builders honest:
                // nothing may be offered that its own field type would reject.
                if (assigns(type, target)) {
                    assertTrue(permittedOn(target, type.targetType()),
                            type.code() + " is offered by the " + target
                                    + " builder but rejected on a " + target + " field");
                }
            }
        }
    }

    /**
     * The NUMBER rules an INTEGER builder deliberately does not offer. An
     * INTEGER field <em>accepts</em> them — {@link Field#requireTarget} permits
     * every NUMBER rule there — but the shorter spelling is always available, so
     * offering both would invite writing the longer one.
     */
    private static final Set<String> INTEGER_BUILDER_OMITS = Set.of(
            "positiveInteger",   // an INTEGER field that is positive: positive()
            "negativeInteger",   // ... and negative()
            "precision"          // a whole number has no decimal places
    );

    /**
     * Whether a builder offers a rule: the rule's own target type, a shared
     * {@code ANY} rule, or a NUMBER rule an INTEGER builder speaks — an integer
     * is a number with a whole-number assertion on top, so a port is still
     * {@code lessThan 65536}.
     */
    private static boolean offeredBy(RuleType type, TargetType target) {
        if (target == TargetType.INTEGER && type.targetType() == TargetType.NUMBER) {
            return !INTEGER_BUILDER_OMITS.contains(type.code());
        }
        return type.targetType() == target || type.targetType() == TargetType.ANY;
    }

    /**
     * Whether the engine would <em>accept</em> the rule on that field type, which
     * is a wider question than what the builder offers: an INTEGER field accepts
     * even the rules {@link #INTEGER_BUILDER_OMITS} holds back.
     */
    private static boolean permittedOn(TargetType type, TargetType ruleTarget) {
        return ruleTarget == type
                || ruleTarget == TargetType.ANY
                || (type == TargetType.INTEGER && ruleTarget == TargetType.NUMBER);
    }

    @Test
    @DisplayName("the ANY-target rules are declared once on Field, not repeated per builder")
    void sharedRulesLiveOnField() {
        Set<String> shared = new LinkedHashSet<>();
        for (Method method : Field.class.getDeclaredMethods()) {
            if (!method.getReturnType().equals(Field.class)) continue;
            shared.add(method.getName());
        }
        for (RuleType type : RuleType.values()) {
            if (type.targetType() != TargetType.ANY) continue;
            assertTrue(shared.contains(methodName(type)),
                    methodName(type) + "() should be declared on Field; found " + shared);
        }
    }

    @Test
    @DisplayName("no builder repeats a rule method that Field already declares")
    void sharedRulesAreNotDuplicated() {
        Set<String> onField = new LinkedHashSet<>();
        for (Method method : Field.class.getDeclaredMethods()) {
            if (!method.getReturnType().equals(Field.class)) continue;
            onField.add(method.getName());
        }
        for (Map.Entry<TargetType, Class<?>> entry : BUILDERS.entrySet()) {
            if (entry.getValue() == AnyField.class) continue;
            for (Method method : entry.getValue().getDeclaredMethods()) {
                // copy() is the required per-builder implementation of the abstract
                // template, not a rule; everything else here would be a duplicate.
                if (method.getName().equals("copy")) continue;
                assertTrue(!onField.contains(method.getName()),
                        entry.getValue().getSimpleName() + "." + method.getName()
                                + " is declared on Field too; declare it once");
            }
        }
    }

    @Test
    @DisplayName("a builder method never returns a different builder, which would erase the narrowing")
    void chainsDoNotWiden() {
        for (Map.Entry<TargetType, Class<?>> entry : BUILDERS.entrySet()) {
            Class<?> builder = entry.getValue();
            for (Method method : chainMethods(builder)) {
                Class<?> returned = method.getReturnType();
                boolean inherited = method.getDeclaringClass() == Field.class;
                assertTrue(inherited || returned.equals(builder),
                        builder.getSimpleName() + "." + method.getName()
                                + " returns " + returned.getSimpleName()
                                + " rather than " + builder.getSimpleName());
            }
        }
    }

    @Test
    @DisplayName("every rule a builder emits is one the engine can evaluate")
    void emittedRulesExist() {
        for (TargetType target : TargetType.values()) {
            for (String code : exposedRules(target)) {
                assertNotNull(RuleEvaluators.find(code),
                        target + " builder emits unknown rule " + code);
            }
        }
    }

    @Test
    @DisplayName("the exposed set is non-empty, so the reflection above is actually looking at something")
    void reflectionSeesTheBuilders() {
        for (Map.Entry<TargetType, Class<?>> entry : BUILDERS.entrySet()) {
            Set<String> methods = exposedRules(entry.getKey());
            assertTrue(methods.size() >= 2,
                    entry.getValue().getSimpleName() + " exposes " + methods
                            + "; expected at least its own rules plus the shared ones");
        }
    }

    // --- helpers -----------------------------------------------------------

    /** Whether a rule's chain element exists on a builder. */
    private static boolean assigns(RuleType type, TargetType target) {
        String expected = methodName(type);
        for (Method method : reachableMethods(BUILDERS.get(target))) {
            if (method.getName().equals(expected)) return true;
        }
        return false;
    }

    /**
     * The chain-element methods of a builder: those that continue a chain.
     *
     * <p>A chain element returns the builder itself, so the next call is still
     * narrowed. Everything else is a query and cannot add a rule.
     */
    private static List<Method> chainMethods(Class<?> builder) {
        List<Method> out = new ArrayList<>();
        for (Method method : builder.getMethods()) {
            if (method.getDeclaringClass() == Object.class) continue;
            if (method.isSynthetic()) continue;
            Class<?> returned = method.getReturnType();
            boolean continuesChain = returned.equals(builder) || returned.equals(Field.class);
            if (!continuesChain) continue;
            out.add(method);
        }
        return out;
    }

    /**
     * Every rule method {@code builder} can reach, including the inherited interface
     * defaults that {@link #chainMethods} deliberately skips.
     *
     * <p>A default method inherited from a rule interface is seen with its
     * <em>erased</em> return type — {@code SELF} erases to {@code RuleContainer} —
     * and javac only synthesizes a covariant bridge in the implementing class for
     * some of them. So {@code StringField.isoDate()} appears as a bridge returning
     * {@code StringField}, while {@code StringRules.after()} appears only as
     * {@code RuleContainer}. Narrowing to bridges would therefore hide a whole
     * interface's worth of rules from {@link #assigns}, which is how {@code after}
     * and {@code before} came to sit on every builder while the catalog gave them a
     * target type of their own.
     *
     * <p>Reachability is deliberately looser than {@link #chainMethods}: a method
     * counts as reachable when the builder satisfies its declared return type. The
     * stricter "returns the builder or {@code Field}" rule stays where it belongs,
     * in the test that checks narrowing.
     */
    private static List<Method> reachableMethods(Class<?> builder) {
        List<Method> out = new ArrayList<>();
        for (Method method : builder.getMethods()) {
            if (method.getDeclaringClass() == Object.class) continue;
            if (method.isSynthetic()) continue;
            Class<?> returned = method.getReturnType();
            if (returned.equals(Object.class)) continue;
            if (!returned.isAssignableFrom(builder)) continue;
            out.add(method);
        }
        return out;
    }

    private static Set<String> exposedRules(TargetType target) {
        Set<String> out = new LinkedHashSet<>();
        for (Method method : chainMethods(BUILDERS.get(target))) {
            for (RuleType type : RuleType.values()) {
                if (method.getName().equals(methodName(type))) out.add(type.code());
            }
        }
        return out;
    }

    /** The chain method name for a rule, accounting for a keyword rename. */
    private static String methodName(RuleType type) {
        return RENAMED.getOrDefault(type.code(), type.code());
    }

    }