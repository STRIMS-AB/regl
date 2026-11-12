package dev.regl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the shared conformance fixtures in {@code regl-spec/conformance/cases.json}
 * against the Java engine. The TypeScript suite runs the exact same file, so
 * any behavioural divergence between the two engines surfaces as a failing
 * test on one side.
 */
class ConformanceTest {

    @SuppressWarnings("unchecked")
    private static Stream<Object[]> cases() {
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> c : Spec.readCases("conformance/cases.json")) {
            String rule = (String) c.get("rule");
            Object input = c.get("input");     // the value under test
            Object value = c.get("value");     // the rule's own argument
            boolean expected = Boolean.TRUE.equals(c.get("expected"));
            rows.add(new Object[]{rule, input, value, expected});
        }
        return rows.stream();
    }

    private static Object coerceRuleValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Object min = map.get("min");
            Object max = map.get("max");
            return new Range(min == null ? null : Integer.valueOf(String.valueOf(min)),
                    max == null ? null : Integer.valueOf(String.valueOf(max)));
        }
        if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
            return (int) (double) d;
        }
        return value;
    }

    private static Object coerceValue(Object value) {
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object o : list) {
                out.add(o instanceof Double d && d == Math.rint(d) ? (int) (double) d : o);
            }
            return out;
        }
        if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
            return (int) (double) d;
        }
        return value;
    }

    /**
     * Target types are a shared concept too, so they are pinned in the same
     * fixture file. The rule cases cannot cover them: no rule targets
     * {@code INTEGER}, because {@code integer} became the type.
     */
    @SuppressWarnings("unchecked")
    private static Stream<Object[]> typeCases() {
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> c : Spec.readArray("conformance/cases.json", "typeCases")) {
            // Deliberately uncoerced: the point of several rows is which Java
            // class the number arrives as — 1.0 as Double, 2^31 as Long.
            rows.add(new Object[]{
                    String.valueOf(c.get("type")),
                    c.get("input"),
                    Boolean.TRUE.equals(c.get("expected"))});
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{0} accepts {1} -> {2}")
    @MethodSource("typeCases")
    @DisplayName("target-type verdict matches the shared conformance fixture")
    void typeMatchesFixture(String type, Object input, boolean expected) {
        assertEquals(expected, RuleEvaluators.matchesTargetType(TargetType.valueOf(type), input),
                "Type '" + type + "' on input " + input);
    }

    @ParameterizedTest(name = "{0}({1}) on {2} -> {3}")
    @MethodSource("cases")
    @DisplayName("rule verdict matches the shared conformance fixture")
    void verdictMatchesFixture(String rule, Object input, Object value, boolean expected) {
        RuleEvaluator<?> evaluator = RuleEvaluators.require(rule);
        boolean actual = RuleEvaluators.test(evaluator, coerceValue(input), coerceRuleValue(value));
        assertEquals(expected, actual,
                "Rule '" + rule + "' with value " + value + " on input " + input);
    }

    /**
     * Guards the semantic divergence the two engines previously disagreed on:
     * {@code precision} must count the decimal places of the numeric value,
     * not the characters of its string form.
     */
    @ParameterizedTest(name = "precision({0}) on {1} -> {2}")
    @MethodSource("precisionCases")
    @DisplayName("precision counts decimals, not string characters")
    void precisionCountsDecimals(String ignored, Object value, boolean expected) {
        boolean actual = RuleEvaluators.test(
                RuleEvaluators.require("precision"), value, 2);
        assertEquals(expected, actual, "precision on " + value);
    }

    static Stream<Object[]> precisionCases() {
        return Stream.of(
                new Object[]{"1.25", new BigDecimal("1.25"), true},
                new Object[]{"1.256", new BigDecimal("1.256"), false},
                new Object[]{"1.5", new BigDecimal("1.5"), true},
                new Object[]{"0.000001", new BigDecimal("0.000001"), false},
                // Exponential input must not be mistaken for zero decimals.
                new Object[]{"1e-7", new BigDecimal("1e-7"), false},
                new Object[]{"5", new BigDecimal("5"), true}
        );
    }

    /**
     * Confirms the value-shape the JSON fixtures use maps onto the collection
     * type the LIST rules expect.
     */
    @ParameterizedTest(name = "unique on {0} -> {1}")
    @MethodSource("uniqueCases")
    @DisplayName("unique works on decoded collections")
    void uniqueOnCollections(String ignored, Collection<?> value, boolean expected) {
        boolean actual = RuleEvaluators.test(RuleEvaluators.require("unique"), value, null);
        assertEquals(expected, actual, "unique on " + value);
    }

    static Stream<Object[]> uniqueCases() {
        return Stream.of(
                new Object[]{"distinct", List.of(1, 2, 3), true},
                new Object[]{"duplicate", List.of(1, 2, 1), false}
        );
    }
}
