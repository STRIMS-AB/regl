package dev.regl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shared document fixtures in
 * {@code regl-spec/conformance/document-cases.json} against the Java engine. The
 * TypeScript suite runs the exact same file, so any divergence between the two
 * engines surfaces as a failing test on one side.
 *
 * <p>Assertions are on the observable shape of a result — {@code kind},
 * {@code severity}, {@code path}, {@code rule} — rather than on message text.
 * Messages are for humans and are allowed to be worded per locale; the branchable
 * fields are the contract.
 */
class DocumentConformanceTest {

    private static Map<String, Object> spec() {
        return Spec.readObject("conformance/document-cases.json");
    }

    /** Builds a rule from the fixture's {@code code}/{@code value}/{@code severity}. */
    @SuppressWarnings("unchecked")
    private static Rule rule(Map<String, Object> r) {
        Object value = r.get("value");
        if (value instanceof Map<?, ?> map) {
            Object min = map.get("min");
            Object max = map.get("max");
            value = new Range(min == null ? null : Integer.valueOf(String.valueOf(min)),
                    max == null ? null : Integer.valueOf(String.valueOf(max)));
        } else if (value instanceof List<?> list) {
            value = list.stream().map(String::valueOf).toList();
        } else if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
            value = (int) (double) d;
        }
        return new Rule(String.valueOf(r.get("code")), value, null,
                "warn".equals(r.get("severity")));
    }

    @SuppressWarnings("unchecked")
    private static Field<?> field(Map<String, Object> f) {
        List<Rule> rules = new ArrayList<>();
        for (Object r : (List<Object>) f.get("rules")) {
            rules.add(rule((Map<String, Object>) r));
        }
        return Field.of(TargetType.valueOf(String.valueOf(f.get("type"))), rules);
    }

    private static Schema schema(Map<String, Object> spec, boolean strict) {
        Schema schema = Schema.create();
        for (Object f : (List<?>) spec.get("fields")) {
            schema = schema.field(String.valueOf(((Map<?, ?>) f).get("path")),
                    field((Map<String, Object>) f));
        }
        return strict ? schema.strict() : schema;
    }

    /** One row per (schema, document) pair, so every case is a separate assertion. */
    @SuppressWarnings("unchecked")
    private static Stream<Object[]> cases() {
        List<Object[]> rows = new ArrayList<>();
        addCases(rows, (List<Object>) spec().get("cases"), false, "case");
        addCases(rows, (List<Object>) spec().get("strictCases"), true, "strictCase");
        return rows.stream();
    }

    @SuppressWarnings("unchecked")
    private static void addCases(List<Object[]> rows, List<Object> group, boolean strict, String id) {
        for (Object c : group) {
            Map<String, Object> schemaSpec = (Map<String, Object>) c;
            String schemaName = String.valueOf(schemaSpec.get("name"));
            for (Object d : (List<Object>) schemaSpec.get("documents")) {
                Map<String, Object> doc = (Map<String, Object>) d;
                rows.add(new Object[]{
                        id + "/" + schemaName + "/" + doc.get("name"),
                        schemaSpec,
                        strict,
                        doc.get("doc")
                });
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("findings match the shared golden transcript")
    void findingsMatchGolden(String id, Map<String, Object> schemaSpec, boolean strict, Object document) {
        assertEquals(golden().get(id), transcript(schema(schemaSpec, strict).validate(document)),
                "transcript for '" + id + "'");
    }

    /**
     * The expected transcript for every case, keyed by case id.
     *
     * <p>Findings are asserted on their branchable fields — {@code severity},
     * {@code kind}, {@code path}, {@code rule} — and not on message text, so
     * wording can change per locale without a fixture edit. Because both engines
     * compare against this one file, a behavioural difference between them cannot
     * be absorbed by updating a snapshot: it fails on one side.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> golden() {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : Spec.readList("conformance/document-expected.json")) {
            out.put(String.valueOf(row.get("id")), String.valueOf(row.get("findings")));
        }
        return out;
    }

    /** The normalized, engine-neutral rendering of a result. */
    private static String transcript(DocumentResult result) {
        List<String> out = new ArrayList<>();
        out.add("valid=" + result.isValid());
        for (DocumentFinding f : result.findings()) {
            out.add(f.severity() + " " + f.kind() + " " + f.path() + " "
                    + (f.rule() == null ? "-" : f.rule()));
        }
        return String.join("\n", out);
    }

    @Nested
    @DisplayName("expected findings")
    class Expected {

        @Test
        @DisplayName("a mismatch names no rule and stops the chain")
        void mismatchNamesNoRule() {
            Schema schema = Schema.create().field("port", Rules.number().greaterThan(0).lessThan(100));

            DocumentResult result = schema.validate(Json.parse("{\"port\":\"8080\"}"));

            assertEquals(1, result.errors().size());
            DocumentFinding finding = result.errors().get(0);
            assertEquals(DocumentFinding.Kind.TYPE_MISMATCH, finding.kind());
            assertEquals(null, finding.rule());
            assertTrue(finding.message().contains("NUMBER"), finding.message());
        }

        @Test
        @DisplayName("a null runs notNull only")
        void nullRunsNotNullOnly() {
            Schema schema = Schema.create().field("port", Rules.integer().notNull().greaterThan(0));

            DocumentResult result = schema.validate(Json.parse("{\"port\":null}"));

            assertEquals(1, result.errors().size());
            assertEquals("notNull", result.errors().get(0).rule());
        }

        @Test
        @DisplayName("a wildcard finding carries the concrete index, not the wildcard")
        void wildcardUsesIndex() {
            Schema schema = Schema.create().field("ports.*", Rules.number().greaterThan(0));

            DocumentResult result = schema.validate(Json.parse("{\"ports\":[0,1,0]}"));

            assertEquals(List.of("ports.0", "ports.2"),
                    result.errors().stream().map(DocumentFinding::path).toList());
        }
    }

    @Nested
    @DisplayName("schema construction errors")
    class Construction {

        @SuppressWarnings("unchecked")
        private static Stream<Object[]> errors() {
            List<Object[]> rows = new ArrayList<>();
            for (Object c : (List<Object>) spec().get("constructionErrors")) {
                Map<String, Object> m = (Map<String, Object>) c;
                rows.add(new Object[]{String.valueOf(m.get("name")), (List<Object>) m.get("fields")});
            }
            return rows.stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("errors")
        @DisplayName("the fixture's illegal schema is rejected")
        void rejectsIllegalSchema(String name, List<Object> fields) {
            @SuppressWarnings("unchecked")
            Map<String, Object> spec = Map.of("fields", fields);

            assertThrows(IllegalArgumentException.class, () -> schema(spec, false),
                    "expected '" + name + "' to be rejected");
        }

        @SuppressWarnings("unchecked")
        private static Stream<Object[]> accepted() {
            List<Object[]> rows = new ArrayList<>();
            for (Object c : (List<Object>) spec().get("acceptedPathPairs")) {
                Map<String, Object> m = (Map<String, Object>) c;
                rows.add(new Object[]{String.valueOf(m.get("name")), (List<Object>) m.get("paths")});
            }
            return rows.stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("accepted")
        @DisplayName("the fixture's disjoint paths are accepted")
        void acceptsDisjointPaths(String name, List<Object> paths) {
            Schema schema = Schema.create();
            for (Object p : paths) {
                schema = schema.field(String.valueOf(p), Rules.string());
            }
            assertEquals(paths.size(), schema.paths().size(), "for '" + name + "'");
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("maxDepth rejects a deeper path and accepts a shallower one")
        void maxDepth() {
            Map<String, Object> limits = (Map<String, Object>) spec().get("maxDepth");
            int limit = Integer.parseInt(String.valueOf(limits.get("limit")));

            assertThrows(IllegalArgumentException.class,
                    () -> Schema.create().maxDepth(limit).field(String.valueOf(limits.get("rejected")), Rules.string()));
            assertEquals(1, Schema.create().maxDepth(limit)
                    .field(String.valueOf(limits.get("accepted")), Rules.string()).paths().size());
        }
    }

    @Nested
    @DisplayName("formal field construction")
    class Formal {

        @SuppressWarnings("unchecked")
        private static Stream<Object[]> errors() {
            List<Object[]> rows = new ArrayList<>();
            for (Object c : (List<Object>) spec().get("formalErrors")) {
                Map<String, Object> m = (Map<String, Object>) c;
                List<Rule> rules = new ArrayList<>();
                for (Object r : (List<Object>) m.get("rules")) {
                    rules.add(rule((Map<String, Object>) r));
                }
                rows.add(new Object[]{String.valueOf(m.get("name")), String.valueOf(m.get("type")), rules});
            }
            return rows.stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("errors")
        @DisplayName("a mismatched rule is rejected")
        void rejects(String name, String type, List<Rule> rules) {
            assertThrows(IllegalArgumentException.class,
                    () -> Field.of(TargetType.valueOf(type), rules), "for '" + name + "'");
        }

        @SuppressWarnings("unchecked")
        private static Stream<Object[]> accepted() {
            List<Object[]> rows = new ArrayList<>();
            for (Object c : (List<Object>) spec().get("formalAccepted")) {
                Map<String, Object> m = (Map<String, Object>) c;
                List<Rule> rules = new ArrayList<>();
                for (Object r : (List<Object>) m.get("rules")) {
                    rules.add(rule((Map<String, Object>) r));
                }
                rows.add(new Object[]{String.valueOf(m.get("name")), String.valueOf(m.get("type")), rules});
            }
            return rows.stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("accepted")
        @DisplayName("a coherent field is accepted and behaves like the fluent form")
        void accepts(String name, String type, List<Rule> rules) {
            Field<?> field = Field.of(TargetType.valueOf(type), rules);

            assertEquals(TargetType.valueOf(type), field.type(), "for '" + name + "'");
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        @DisplayName("no document shape throws")
        void noShapeThrows() {
            Schema schema = Schema.create()
                    .field("a.b", Rules.number().notNull())
                    .field("c.*.d", Rules.string().notEmpty())
                    .strict();

            List<Object> documents = java.util.Arrays.asList(
                    null,
                    "scalar",
                    42,
                    true,
                    List.of(),
                    Map.of(),
                    Json.parse("{\"a\":[1,2],\"c\":{\"x\":{}}}"),
                    Json.parse("{\"a\":{\"b\":[1]},\"c\":[null,5]}"));

            for (Object document : documents) {
                DocumentResult result = schema.validate(document);
                assertFalse(result.findings().stream()
                                .anyMatch(f -> f.rule() == null && f.kind() == null),
                        "a finding must always carry a kind for " + document);
            }
        }
    }
}