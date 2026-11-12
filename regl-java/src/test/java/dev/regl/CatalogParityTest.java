package dev.regl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the catalog against drift. {@code regl-spec/catalog.json} is the
 * canonical definition of the rule set, so every id in it must resolve to a
 * registered evaluator, and the engine must not invent rules the spec does
 * not define.
 */
class CatalogParityTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> catalogRules() {
        Object raw = Spec.parse("catalog.json");
        if (raw instanceof List) {
            return (List<Map<String, Object>>) raw;
        }
        if (raw instanceof Map<?, ?> map) {
            Object rules = ((Map<String, Object>) map).get("rules");
            if (rules instanceof List) {
                return (List<Map<String, Object>>) rules;
            }
        }
        throw new IllegalStateException("Unrecognised catalog.json layout.");
    }

    private static List<String> catalogIds() {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> rule : catalogRules()) {
            ids.add((String) rule.get("id"));
        }
        return ids;
    }

    @Test
    @DisplayName("catalog.json is well-formed and non-empty")
    void catalogParses() {
        List<String> ids = catalogIds();
        assertFalse(ids.isEmpty(), "catalog.json declared no rules.");
        assertEquals(ids.size(), new HashSet<>(ids).size(), "catalog.json contains duplicate rule ids.");
        for (String id : ids) {
            assertNotNull(id, "catalog.json contains a rule with a null id.");
        }
    }

    @Test
    @DisplayName("every rule in catalog.json resolves to a registered evaluator")
    void everyCatalogRuleIsRegistered() {
        List<String> unregistered = new ArrayList<>();
        for (String id : catalogIds()) {
            if (!RuleEvaluators.isRegistered(id)) {
                unregistered.add(id);
            }
        }
        assertEquals(List.of(), unregistered,
                "Catalog rules with no registered evaluator: " + unregistered);
    }

    @Test
    @DisplayName("every registered evaluator is declared in catalog.json")
    void noEvaluatorIsOutsideTheCatalog() {
        Set<String> catalog = new HashSet<>(catalogIds());
        List<String> extras = new ArrayList<>();
        for (RuleEvaluator<?> evaluator : RuleEvaluators.getAll()) {
            if (!catalog.contains(evaluator.code())) {
                extras.add(evaluator.code());
            }
        }
        assertEquals(List.of(), extras,
                "Evaluators registered in the engine but absent from catalog.json: " + extras);
    }

    @Test
    @DisplayName("RuleType covers exactly the catalog rule set")
    void ruleTypeMatchesCatalog() {
        Set<String> catalog = new HashSet<>(catalogIds());
        Set<String> enumCodes = new HashSet<>();
        for (RuleType type : RuleType.values()) {
            enumCodes.add(type.code());
        }
        assertEquals(catalog, enumCodes,
                "RuleType and catalog.json disagree; each is missing: " + symmetricDifference(catalog, enumCodes));
    }

    private static Set<String> symmetricDifference(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.removeAll(b);
        Set<String> other = new HashSet<>(b);
        other.removeAll(a);
        out.addAll(other);
        return out;
    }

    @Test
    @DisplayName("catalog target/parameter types match each registered evaluator")
    void catalogMetadataMatchesEvaluators() {
        for (Map<String, Object> rule : catalogRules()) {
            String id = (String) rule.get("id");
            RuleEvaluator<?> evaluator = RuleEvaluators.require(id);

            Object catalogTarget = rule.get("targetType");
            assertEquals(catalogTarget, evaluator.targetType().name(),
                    "Target type mismatch for '" + id + "'.");

            Object catalogParam = rule.get("parameterType");
            boolean catalogSaysParameterless = catalogParam == null;
            assertEquals(catalogSaysParameterless, evaluator.parameterType() == null || isVoid(evaluator.parameterType()),
                    "Parameter presence mismatch for '" + id + "'.");
        }
    }

    private static boolean isVoid(Class<?> type) {
        return type == Void.class || type == void.class;
    }

    @Test
    @DisplayName("catalog descriptions match each registered evaluator")
    void catalogDescriptionsMatchEvaluators() {
        for (Map<String, Object> rule : catalogRules()) {
            String id = (String) rule.get("id");
            RuleEvaluator<?> evaluator = RuleEvaluators.require(id);
            assertEquals(rule.get("name"), evaluator.name(), "Name mismatch for '" + id + "'.");
            assertEquals(rule.get("description"), evaluator.description(),
                    "Description mismatch for '" + id + "'.");
        }
    }

    @Test
    @DisplayName("every catalog rule has at least one conformance case")
    void everyCatalogRuleIsExercised() {
        Set<String> catalog = new HashSet<>(catalogIds());
        Set<String> covered = new HashSet<>();
        for (Map<String, Object> c : Spec.readCases("conformance/cases.json")) {
            covered.add((String) c.get("rule"));
        }

        List<String> untested = new ArrayList<>();
        for (String id : catalog) {
            if (!covered.contains(id)) {
                untested.add(id);
            }
        }
        assertEquals(List.of(), untested,
                "Catalog rules with no conformance case (their predicate is unverified): " + untested);
    }

    @Test
    @DisplayName("conformance cases only reference catalog rules")
    void conformanceCasesReferenceCatalog() {
        Set<String> catalog = new HashSet<>(catalogIds());
        List<String> unknown = new ArrayList<>();
        for (Map<String, Object> c : Spec.readCases("conformance/cases.json")) {
            String rule = (String) c.get("rule");
            if (!catalog.contains(rule)) {
                unknown.add(rule);
            }
        }
        assertEquals(List.of(), unknown,
                "Conformance cases referencing rules absent from the catalog: " + unknown);
    }

    @Test
    @DisplayName("conformance cases declare an expected verdict")
    void conformanceCasesDeclareExpectations() {
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> c : Spec.readCases("conformance/cases.json")) {
            if (!(c.get("expected") instanceof Boolean)) {
                missing.add((String) c.get("rule"));
            }
        }
        assertEquals(List.of(), missing,
                "Conformance cases without a boolean 'expected' verdict: " + missing);
    }

    @Test
    @DisplayName("i18n/en.json covers every catalog rule")
    void englishBundleCoversCatalog() {
        Map<String, Object> bundle = Spec.readObject("i18n/en.json");
        Object raw = bundle.get("rules");
        assertTrue(raw instanceof Map, "i18n/en.json is missing a 'rules' object.");

        @SuppressWarnings("unchecked")
        Map<String, Object> rules = (Map<String, Object>) raw;
        Set<String> catalog = new HashSet<>(catalogIds());

        List<String> missing = new ArrayList<>();
        for (String id : catalog) {
            if (!rules.containsKey(id)) {
                missing.add(id);
            }
        }
        assertEquals(List.of(), missing, "Catalog rules with no i18n entry: " + missing);

        List<String> extra = new ArrayList<>();
        for (String id : rules.keySet()) {
            if (!catalog.contains(id)) {
                extra.add(id);
            }
        }
        assertEquals(List.of(), extra, "i18n entries with no catalog rule: " + extra);
    }
}
