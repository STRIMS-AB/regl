package dev.regl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static dev.regl.Rules.any;
import static dev.regl.Rules.boolean_;
import static dev.regl.Rules.list;
import static dev.regl.Rules.integer;
import static dev.regl.Rules.number;
import static dev.regl.Rules.string;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaTest {

    private static Map<String, Object> doc(String json) {
        return Json.parseObject(json);
    }

    private static List<String> shapes(DocumentResult result) {
        return result.findings().stream().map(f -> f.kind() + ":" + f.path()).toList();
    }

    private static List<String> rules(DocumentResult result) {
        return rules(result.findings());
    }

    private static List<String> rules(List<DocumentFinding> findings) {
        return findings.stream().map(f -> f.path() + "/" + f.rule()).toList();
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("a missing field resolves to null, so notNull is what makes it mandatory")
        void missingIsNull() {
            Schema schema = Schema.create().field("name", string().notNull());

            DocumentResult result = schema.validate(doc("{}"));

            assertFalse(result.isValid());
            assertEquals(DocumentFinding.Kind.RULE_FAILED, result.errors().get(0).kind());
            assertEquals("notNull", result.errors().get(0).rule());
        }

        @Test
        @DisplayName("an explicit null is indistinguishable from a missing field")
        void explicitNullIsMissing() {
            Schema schema = Schema.create().field("name", string().notNull());

            assertEquals(shapes(schema.validate(doc("{}"))), shapes(schema.validate(doc("{\"name\":null}"))));
        }

        @Test
        @DisplayName("a present value is found through a nested path")
        void nestedPath() {
            Schema schema = Schema.create().field("server.port", integer().notNull());

            DocumentResult result = schema.validate(doc("{\"server\":{\"port\":8080}}"));

            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("a null ancestor makes everything beneath it null")
        void nullAncestor() {
            Schema schema = Schema.create().field("server.port", number().notNull());

            DocumentResult result = schema.validate(doc("{\"server\":null}"));

            assertEquals(List.of("RULE_FAILED:server.port"), shapes(result));
        }

        @Test
        @DisplayName("a wildcard resolves once per element, with indices in the path")
        void wildcardIndices() {
            Schema schema = Schema.create().field("servers.*.port", number().notNull().greaterThan(1024));

            DocumentResult result = schema.validate(doc("{\"servers\":[{\"port\":80},{\"port\":9999}]}"));

            assertEquals(List.of("RULE_FAILED:servers.0.port"), shapes(result));
        }

        @Test
        @DisplayName("a wildcard over a missing list yields nothing, so notNull on elements cannot fire")
        void wildcardOverMissingList() {
            Schema schema = Schema.create().field("servers.*.port", number().notNull());

            DocumentResult result = schema.validate(doc("{}"));

            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("cross-field reference resolves sibling value dynamically")
        void crossFieldReference() {
            Schema schema = Schema.create()
                    .field("startDate", number().notNull())
                    .field("endDate", number().notNull().greaterThan("$startDate"));

            DocumentResult valid = schema.validate(doc("{\"startDate\": 10, \"endDate\": 20}"));
            assertTrue(valid.isValid());

            DocumentResult invalid = schema.validate(doc("{\"startDate\": 20, \"endDate\": 10}"));
            assertFalse(invalid.isValid());
            assertEquals(List.of("endDate/greaterThan"), rules(invalid));
        }
    }

    @Nested
    @DisplayName("shape problems")
    class Shape {

        @Test
        @DisplayName("a wildcard over a non-list names the declared path once, not once per element")
        void wildcardOverNonList() {
            Schema schema = Schema.create().field("servers.*.port", number().notNull());

            DocumentResult result = schema.validate(doc("{\"servers\":{\"a\":{}}}"));

            assertEquals(List.of("NOT_A_LIST:servers.*.port"), shapes(result));
            assertEquals(1, result.errors().size());
            assertTrue(result.errors().get(0).message().contains("is not a list"),
                    result.errors().get(0).message());
        }

        @Test
        @DisplayName("reading a key through a scalar is unreachable, not missing")
        void keyThroughScalar() {
            Schema schema = Schema.create().field("server.port", number().notNull());

            DocumentResult result = schema.validate(doc("{\"server\":\"nope\"}"));

            assertEquals(List.of("UNREACHABLE:server.port"), shapes(result));
            assertTrue(result.errors().get(0).message().contains("'server' is not an object"));
        }

        @Test
        @DisplayName("reading a key through a list is unreachable")
        void keyThroughList() {
            Schema schema = Schema.create().field("server.port", number().notNull());

            DocumentResult result = schema.validate(doc("{\"server\":[1]}"));

            assertEquals(List.of("UNREACHABLE:server.port"), shapes(result));
        }
    }

    @Nested
    @DisplayName("type mismatches")
    class Mismatch {

        @Test
        @DisplayName("a wrong kind names no rule, because the whole chain shares the declared type")
        void noRuleNamed() {
            Schema schema = Schema.create().field("server.port", number().greaterThan(0));

            DocumentResult result = schema.validate(doc("{\"server\":{\"port\":\"8080\"}}"));

            DocumentFinding finding = result.errors().get(0);
            assertEquals(DocumentFinding.Kind.TYPE_MISMATCH, finding.kind());
            assertNull(finding.rule());
        }

        @Test
        @DisplayName("a mismatch short-circuits the rest of the chain")
        void shortCircuits() {
            Schema schema = Schema.create().field("port", number().greaterThan(0).lessThan(100));

            DocumentResult result = schema.validate(doc("{\"port\":\"8080\"}"));

            assertEquals(1, result.errors().size());
        }

        @Test
        @DisplayName("a null never produces a mismatch, since it short-circuits first")
        void nullIsNotAMismatch() {
            Schema schema = Schema.create().field("port", number().greaterThan(0));

            DocumentResult result = schema.validate(doc("{\"port\":null}"));

            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("the null short-circuit")
    class Nulls {

        @Test
        @DisplayName("on null, notNull runs and every other rule is skipped")
        void notNullThenSkip() {
            Schema schema = Schema.create().field("port", integer().notNull().greaterThan(0));

            DocumentResult result = schema.validate(doc("{\"port\":null}"));

            assertEquals(List.of("port/notNull"), rules(result));
        }

        @Test
        @DisplayName("notNull passes on a present value and the rest of the chain runs")
        void notNullPasses() {
            Schema schema = Schema.create().field("port", number().notNull().greaterThan(0));

            DocumentResult result = schema.validate(doc("{\"port\":8080}"));

            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("a null passes a chain with no rules beyond notNull")
        void optionalField() {
            Schema schema = Schema.create().field("nickname", string().minLength(2));

            DocumentResult result = schema.validate(doc("{}"));

            assertTrue(result.isValid());
        }
    }

    @Nested
    @DisplayName("warn-only rules")
    class WarnOnly {

        @Test
        @DisplayName("a warn-only failure is a warning and leaves the document valid")
        void warningNotError() {
            Schema schema = Schema.create().field("name", string().notNull().maxLength(3).warnOnly());

            DocumentResult result = schema.validate(doc("{\"name\":\"alexandra\"}"));

            assertTrue(result.isValid());
            assertTrue(result.hasWarnings());
            assertEquals(DocumentFinding.Severity.WARNING, result.warnings().get(0).severity());
        }

        @Test
        @DisplayName("warnOnly marks the most recent rule it follows")
        void attachesToPrevious() {
            Schema schema = Schema.create().field("name", string().maxLength(3).warnOnly().minLength(100));

            DocumentResult result = schema.validate(doc("{\"name\":\"alexandra\"}"));

            assertFalse(result.isValid());
            assertEquals(List.of("name/minLength"), rules(result.errors()));
            assertEquals(List.of("name/maxLength"), rules(result.warnings()));
        }

        @Test
        @DisplayName("warnOnly on an empty chain is a programming error")
        void emptyChain() {
            assertThrows(IllegalStateException.class, () -> string().warnOnly());
        }
    }

    @Nested
    @DisplayName("field construction")
    class Construction {

        @Test
        @DisplayName("overlapping exact paths are rejected")
        void exactOverlap() {
            assertThrows(IllegalArgumentException.class,
                    () -> Schema.create().field("port", number()).field("port", number()));
        }

        @Test
        @DisplayName("a wildcard and an index literal of the same length are rejected")
        void wildcardIndexOverlap() {
            assertThrows(IllegalArgumentException.class,
                    () -> Schema.create()
                            .field("servers.*.port", number())
                            .field("servers.0.port", number()));
        }

        @Test
        @DisplayName("a wildcard and an index literal of the same length are rejected")
        void wildcardOverlap() {
            assertThrows(IllegalArgumentException.class,
                    () -> Schema.create().field("servers.*.port", number()).field("servers.0.port", number()));
        }

        @Test
        @DisplayName("paths of different lengths are disjoint and allowed")
        void differentLengths() {
            Schema schema = Schema.create()
                    .field("servers.*", list().notNull())
                    .field("servers.*.port", number().notNull());

            assertEquals(List.of("servers.*", "servers.*.port"), schema.paths());
        }

        @Test
        @DisplayName("a wildcard does not overlap a literal that is not an index")
        void nonIndexLiteral() {
            Schema schema = Schema.create()
                    .field("servers.*.port", number())
                    .field("servers.primary.port", number());

            assertEquals(2, schema.paths().size());
        }

        @Test
        @DisplayName("an empty path is rejected")
        void emptyPath() {
            assertThrows(IllegalArgumentException.class, () -> Schema.create().field("", string()));
        }

        @Test
        @DisplayName("a path deeper than maxDepth is rejected")
        void maxDepth() {
            assertThrows(IllegalArgumentException.class,
                    () -> Schema.create().maxDepth(2).field("a.b.c", string()));
        }

        @Test
        @DisplayName("maxDepth defaults to 32 and can be raised")
        void maxDepthDefaults() {
            assertEquals(32, Schema.create().maxDepth());
            assertEquals(64, Schema.create().maxDepth(64).maxDepth());
        }

        @Test
        @DisplayName("escapes let a key contain a dot")
        void escaping() {
            Schema schema = Schema.create().field("a\\.b", string().notNull());

            assertTrue(schema.validate(doc("{\"a.b\":\"x\"}")).isValid());
            assertTrue(schema.validate(doc("{\"a\":{\"b\":\"x\"}}")).isValid() == false);
        }
    }

    @Nested
    @DisplayName("formal construction")
    class Formal {

        @Test
        @DisplayName("Field.of accepts a matching type and rules")
        void matches() {
            Field<?> field = Field.of(TargetType.STRING, List.of(new Rule(RuleType.MIN_LENGTH, 1)));

            assertEquals(TargetType.STRING, field.type());
        }

        @Test
        @DisplayName("Field.of rejects a rule that targets another type")
        void mismatchThrows() {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Field.of(TargetType.STRING, List.of(new Rule(RuleType.GREATER_THAN, 1))));

            assertTrue(error.getMessage().contains("greaterThan"));
            assertTrue(error.getMessage().contains("NUMBER"));
        }

        @Test
        @DisplayName("a field built formally validates like one built fluently")
        void equivalent() {
            Schema formal = Schema.create().field("name", Field.of(TargetType.STRING,
                    List.of(new Rule(RuleType.NOT_NULL), new Rule(RuleType.MIN_LENGTH, 3))));
            Schema fluent = Schema.create().field("name", string().notNull().minLength(3));

            assertEquals(shapes(formal.validate(doc("{\"name\":\"ab\"}"))),
                    shapes(fluent.validate(doc("{\"name\":\"ab\"}"))));
        }
    }

    @Nested
    @DisplayName("strict mode")
    class Strict {

        @Test
        @DisplayName("an uncovered key is reported")
        void unknownField() {
            Schema schema = Schema.create().field("name", string()).strict();

            DocumentResult result = schema.validate(doc("{\"name\":\"x\",\"extra\":1}"));

            assertEquals(List.of("UNKNOWN_FIELD:extra"), shapes(result));
        }

        @Test
        @DisplayName("a key on the way to a declared field is covered")
        void containerCovered() {
            Schema schema = Schema.create().field("server.port", number()).strict();

            assertTrue(schema.validate(doc("{\"server\":{\"port\":1}}")).isValid());
        }

        @Test
        @DisplayName("a sibling key under a covered object is still unknown")
        void siblingUnknown() {
            Schema schema = Schema.create().field("server.port", number()).strict();

            DocumentResult result = schema.validate(doc("{\"server\":{\"port\":1,\"host\":\"h\"}}"));

            assertEquals(List.of("UNKNOWN_FIELD:server.host"), shapes(result));
        }

        @Test
        @DisplayName("a wildcard covers its elements' subtrees at the declared depth")
        void wildcardCoversSubtree() {
            Schema schema = Schema.create().field("servers.*.host", string()).strict();

            assertTrue(schema.validate(doc("{\"servers\":[{\"host\":\"h\"}]}")).isValid());
            assertEquals(List.of("UNKNOWN_FIELD:servers.0.port"),
                    shapes(schema.validate(doc("{\"servers\":[{\"host\":\"h\",\"port\":1}]}"))));
        }

        @Test
        @DisplayName("without strict, extra keys are ignored")
        void openByDefault() {
            assertTrue(Schema.create().field("name", string()).validate(doc("{\"extra\":1}")).isValid());
        }

        @Test
        @DisplayName("unknown keys are reported in sorted order for cross-engine parity")
        void sorted() {
            Schema schema = Schema.create().field("name", string()).strict();

            DocumentResult result = schema.validate(doc("{\"z\":1,\"a\":1,\"m\":1}"));

            assertEquals(List.of("UNKNOWN_FIELD:a", "UNKNOWN_FIELD:m", "UNKNOWN_FIELD:z"), shapes(result));
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("findings come out in field declaration order, then rule position")
        void declarationThenRule() {
            Schema schema = Schema.create()
                    .field("b", number().greaterThan(100).greaterThan(200))
                    .field("a", string().minLength(5));

            DocumentResult result = schema.validate(doc("{\"a\":\"x\",\"b\":1}"));

            assertEquals(List.of("b/greaterThan", "b/greaterThan", "a/minLength"), rules(result));
        }

        @Test
        @DisplayName("list element findings follow list order")
        void listOrder() {
            Schema schema = Schema.create().field("ports.*", number().greaterThan(0));

            DocumentResult result = schema.validate(doc("{\"ports\":[0,0,0]}"));

            assertEquals(List.of("ports.0/greaterThan", "ports.1/greaterThan", "ports.2/greaterThan"),
                    rules(result));
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        @DisplayName("validating an empty schema accepts any document, including null")
        void emptySchema() {
            assertTrue(Schema.create().validate(doc("{}")).isValid());
            assertTrue(Schema.create().validate(null).isValid());
        }

        @Test
        @DisplayName("a scalar or list document makes every declared field unreachable, never a throw")
        void nonMapDocument() {
            Schema schema = Schema.create().field("name", string());

            assertEquals(List.of("UNREACHABLE:name"), shapes(schema.validate(List.of(1, 2))));
            assertEquals(List.of("UNREACHABLE:name"), shapes(schema.validate("just a string")));
        }

        @Test
        @DisplayName("strict mode on a non-map document has no keys to reject")
        void strictNonMap() {
            assertTrue(Schema.create().strict().validate("just a string").isValid());
        }

        @Test
        @DisplayName("a non-string map key is reported as a path segment, not thrown")
        void nonStringKey() {
            Schema schema = Schema.create().field("name", string()).strict();

            DocumentResult result = schema.validate(Map.of(1, "x"));

            assertEquals(List.of("UNKNOWN_FIELD:1"), shapes(result));
        }
    }
}