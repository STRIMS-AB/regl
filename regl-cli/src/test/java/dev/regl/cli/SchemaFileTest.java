package dev.regl.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import dev.regl.DocumentFinding;
import dev.regl.Documents;
import dev.regl.RuleType;
import dev.regl.TargetType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A schema file is data, so it is tested as data: JSON and YAML spellings of one
 * schema must load to the same schema and validate identically, and the
 * rejected schemas of §12 must fail at load with the same
 * {@code IllegalArgumentException} the code form raises at construction.
 */
class SchemaFileTest {

    private static final String JSON_SCHEMA = """
            {
              "strict": true,
              "maxDepth": 16,
              "fields": {
                "name": { "type": "STRING",
                           "rules": [ "notNull", { "minLength": 3 } ] },
                "server.port": { "type": "INTEGER",
                                 "rules": [ "notNull", { "lessThan": 65536 } ] },
                "tags": { "type": "LIST",
                          "rules": [ "notNull", { "range": { "min": 1, "max": 5 } } ] }
              }
            }
            """;

    private static final String YAML_SCHEMA = """
            strict: true
            maxDepth: 16
            fields:
              name: { type: STRING, rules: [notNull, { minLength: 3 }] }
              server.port: { type: INTEGER, rules: [notNull, { lessThan: 65536 }] }
              tags:
                type: LIST
                rules:
                  - notNull
                  - range: { min: 1, max: 5 }
            """;

    private static Object json(String text) {
        return Documents.fromJson(text);
    }

    /** The {@code --json} rendering of a schema validating a document — identical bytes mean identical schemas. */
    private static String jsonOf(SchemaFile.LoadedSchema schema, String document) {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        Reporter reporter = new Reporter(new java.io.PrintStream(buffer), true, false);
        return reporter.json(schema.schema().validate(Documents.fromJson(document)));
    }

    @Test
    @DisplayName("JSON and YAML spellings of one schema load to the same schema")
    void formatsAgree() {
        SchemaFile.LoadedSchema fromJson = SchemaFile.fromTree(json(JSON_SCHEMA));
        SchemaFile.LoadedSchema fromYaml = SchemaFile.fromTree(YamlDocuments.decodeSchema(Format.YAML, YAML_SCHEMA));

        assertEquals(fromJson.fields().size(), fromYaml.fields().size());
        assertEquals(fromJson.strict(), fromYaml.strict());
        assertEquals(fromJson.maxDepth(), fromYaml.maxDepth());
        assertEquals(fromJson.paths(), fromYaml.paths());

        String document = "{\"name\":\"ab\",\"server\":{\"port\":80},\"tags\":[]}";
        assertEquals(jsonOf(fromJson, document), jsonOf(fromYaml, document));
    }

    @Nested
    @DisplayName("rejected schemas")
    class Rejected {

        @Test
        @DisplayName("a typo'd top-level key is rejected, not treated as absent")
        void unknownTopLevelKey() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "feilds": {}, "strict": true }
                            """)));
            assertTrue(e.getMessage().contains("feilds"), e.getMessage());
        }

        @Test
        @DisplayName("an unknown field-entry key is rejected")
        void unknownFieldKey() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "a": { "type": "STRING", "rules": [], "required": true } } }
                            """)));
            assertTrue(e.getMessage().contains("required"), e.getMessage());
        }

        @Test
        @DisplayName("the withdrawn envelope is rejected — a rule map has exactly one entry")
        void envelopeIsWithdrawn() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "a": { "type": "STRING",
                                                "rules": [ { "code": "notNull", "value": 3 } ] } } }
                            """)));
            assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "a": { "type": "STRING",
                                                "rules": [ { "code": "maxLength", "value": 3, "warnOnly": true } ] } } }
                            """)));
        }

        @Test
        @DisplayName("a rule that takes no argument rejects one, in either spelling")
        void parameterlessRuleRejectsAValue() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "a": { "type": "STRING", "rules": [ { "notNull": 3 } ] } } }
                            """)));
            assertTrue(e.getMessage().contains("notNull"), e.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "a": { "type": "STRING", "rules": [ { "notNull!": 3 } ] } } }
                            """)));
        }

        @Test
        @DisplayName("a rule map with two entries is rejected")
        void ruleMapWithTwoEntries() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "a": { "type": "STRING",
                                                "rules": [ { "minLength": 3, "maxLength": 10 } ] } } }
                            """)));
            assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
        }

        @Test
        @DisplayName("an unknown rule code is rejected, bare or in a map")
        void unknownRuleCode() {
            assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "a": { "type": "STRING", "rules": [ "nope" ] } } }
                            """)));
            assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "a": { "type": "STRING", "rules": [ { "nope": 1 } ] } } }
                            """)));
        }

        @Test
        @DisplayName("a missing fields key is rejected")
        void missingFields() {
            assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("{ \"strict\": true }")));
        }

        @Test
        @DisplayName("fields as an array is the withdrawn form and is rejected")
        void fieldsAsArray() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": [ { "path": "a", "type": "STRING", "rules": [] } ] }
                            """)));
            assertTrue(e.getMessage().contains("keyed by path"), e.getMessage());
        }

        @Test
        @DisplayName("a duplicate path, as a repeated object key, is rejected in JSON and YAML")
        void duplicatePath() {
            IllegalArgumentException fromJson = assertThrows(IllegalArgumentException.class,
                    () -> YamlDocuments.decodeSchema(Format.JSON, """
                            { "fields": { "a": { "type": "STRING" },
                                          "a": { "type": "NUMBER" } } }
                            """));
            assertTrue(fromJson.getMessage().toLowerCase().contains("duplicate"), fromJson.getMessage());
            IllegalArgumentException fromYaml = assertThrows(IllegalArgumentException.class,
                    () -> YamlDocuments.decodeSchema(Format.YAML, """
                            fields:
                              a: { type: STRING }
                              a: { type: NUMBER }
                            """));
            assertTrue(fromYaml.getMessage().toLowerCase().contains("duplicate"), fromYaml.getMessage());
        }

        @Test
        @DisplayName("a severity that is not 'error' or 'warn' is rejected")
        void badSeverity() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "a": { "type": "STRING", "severity": "loud",
                                                "rules": [] } } }
                            """)));
            assertTrue(e.getMessage().contains("severity"), e.getMessage());
        }

        @Test
        @DisplayName("a target type that is not one is rejected")
        void unknownTargetType() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "a": "TEXT" } }
                            """)));
            assertTrue(e.getMessage().contains("TEXT"), e.getMessage());
        }

        @Test
        @DisplayName("an argument of the wrong shape is rejected, exactly as the code form rejects it")
        void badParameterShape() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "a": { "type": "STRING",
                                                "rules": [ { "minLength": "three" } ] } } }
                            """)));
            assertTrue(e.getMessage().contains("minLength"), e.getMessage());
        }

        @Test
        @DisplayName("an enum value must be an array of strings")
        void badEnumValue() {
            assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "a": { "type": "ANY",
                                                "rules": [ { "enum": "fast" } ] } } }
                            """)));
        }

        @Test
        @DisplayName("overlapping paths are rejected, as they are in code")
        void overlappingPaths() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "fields": { "servers.*.port": "NUMBER",
                                          "servers.0.port": "NUMBER" } }
                            """)));
            assertTrue(e.getMessage().contains("overlaps"), e.getMessage());
        }

        @Test
        @DisplayName("a path deeper than maxDepth is rejected")
        void overDeepPath() {
            assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "maxDepth": 2, "fields": { "a.b.c": "STRING" } }
                            """)));
        }

        @Test
        @DisplayName("a non-object root is rejected")
        void nonObjectRoot() {
            assertThrows(IllegalArgumentException.class, () -> SchemaFile.fromTree(json("[]")));
            assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(YamlDocuments.decodeSchema(Format.YAML, "- a\n- b\n")));
        }

        @Test
        @DisplayName("a type that shadows a target type is rejected")
        void shadowingType() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "types": { "STRING": "NUMBER" }, "fields": {} }
                            """)));
            assertTrue(e.getMessage().contains("shadows"), e.getMessage());
        }

        @Test
        @DisplayName("a type cycle is rejected, named in traversal order")
        void typeCycle() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "types": { "A": "B", "B": "A" }, "fields": {} }
                            """)));
            assertTrue(e.getMessage().contains("cycle"), e.getMessage());
        }

        @Test
        @DisplayName("a type no field references is still checked: an unknown rule code is rejected")
        void unreferencedTypeUnknownRuleCode() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "types": { "A": { "type": "STRING", "rules": [ "nope" ] } }, "fields": {} }
                            """)));
            assertTrue(e.getMessage().contains("nope"), e.getMessage());
        }

        @Test
        @DisplayName("a type no field references is still checked: a bad argument is rejected")
        void unreferencedTypeBadArgument() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "types": { "A": { "type": "STRING", "rules": [ { "minLength": "three" } ] } }, "fields": {} }
                            """)));
            assertTrue(e.getMessage().contains("minLength"), e.getMessage());
        }

        @Test
        @DisplayName("a type no field references is still checked: a mis-targeted rule is rejected")
        void unreferencedTypeMisTargetedRule() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "types": { "A": { "type": "NUMBER", "rules": [ "email" ] } }, "fields": {} }
                            """)));
            assertTrue(e.getMessage().contains("email"), e.getMessage());
        }

        @Test
        @DisplayName("a base that names no target type and no declared type is rejected")
        void unknownBase() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.fromTree(json("""
                            { "types": { "A": "Missing" }, "fields": {} }
                            """)));
            assertTrue(e.getMessage().contains("Missing"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("accepted schemas")
    class Accepted {

        @Test
        @DisplayName("fields may be empty: an empty schema validates any document")
        void emptyFields() {
            SchemaFile.LoadedSchema schema = SchemaFile.fromTree(json("{ \"fields\": {} }"));
            assertEquals(0, schema.fields().size());
            assertTrue(schema.schema().validate(Map.of()).isValid());
        }

        @Test
        @DisplayName("INTEGER is a whole number, not a Java class, and NUMBER rules still apply to it")
        void integerIsMathematical() {
            SchemaFile.LoadedSchema schema = SchemaFile.fromTree(json("""
                    { "fields": { "port": { "type": "INTEGER", "rules": [ { "lessThan": 65536 } ] } } }
                    """));
            assertEquals(TargetType.INTEGER, schema.fields().get(0).type());
            var loaded = schema.schema();

            // A type mismatch names no rule, a rule failure names its code. That
            // difference is how we tell "the type rejected it" from "lessThan did".
            Function<Object, List<String>> rules = input -> loaded.validate(Map.of("port", input))
                    .findings().stream().map(DocumentFinding::rule).toList();
            String messages = String.valueOf(loaded.validate(Map.of("port", 80.5)).findings());

            assertTrue(loaded.validate(Map.of("port", 8080)).isValid(), "8080 is whole");
            assertTrue(loaded.validate(Map.of("port", 1.0)).isValid(), "1.0 is whole");
            assertEquals(List.of("lessThan"), rules.apply(2147483648L),
                    "above Integer.MAX_VALUE, so it arrives as Long: the type accepts it and only "
                            + "the magnitude rule objects. An instanceof Integer check would have "
                            + "called this a type mismatch instead.");
            assertEquals(List.of("lessThan"), rules.apply(70000), "same, on an Integer");
            assertTrue(messages.contains("of type INTEGER"), "80.5 has a fractional part");
            assertTrue(loaded.validate(Map.of("port", "8080")).findings().toString()
                    .contains("of type INTEGER"), "a numeric string stays a STRING");
        }

        @Test
        @DisplayName("strict and maxDepth are optional, with the spec's defaults")
        void defaults() {
            SchemaFile.LoadedSchema schema = SchemaFile.fromTree(json(
                    "{ \"fields\": { \"a\": { \"type\": \"STRING\" } } }"));
            assertEquals(false, schema.strict());
            assertEquals(32, schema.maxDepth());
        }

        @Test
        @DisplayName("the shorthands load to the same schema as their long forms")
        void shorthands() {
            SchemaFile.LoadedSchema shorthand = SchemaFile.fromTree(json("""
                    { "fields": { "debug": "BOOLEAN",
                                  "a": { "type": "STRING", "rules": [ "notNull", { "email": null } ] } } }
                    """));
            SchemaFile.LoadedSchema longhand = SchemaFile.fromTree(json("""
                    { "fields": { "debug": { "type": "BOOLEAN" },
                                  "a": { "type": "STRING",
                                         "rules": [ { "notNull": null }, { "email": null } ] } } }
                    """));
            assertEquals(longhand.paths(), shorthand.paths());
            String document = "{\"debug\": true, \"a\": \"x@y.co\"}";
            assertEquals(jsonOf(longhand, document), jsonOf(shorthand, document));
        }

        @Test
        @DisplayName("a range value fits either bound")
        void halfOpenRange() {
            SchemaFile.LoadedSchema schema = SchemaFile.fromTree(json("""
                    { "fields": { "tags": { "type": "LIST", "rules": [ { "range": { "min": 1 } } ] } } }
                    """));
            assertEquals(1, schema.fields().get(0).rules().size());
        }

        @Test
        @DisplayName("custom types define reusable field definitions that paths can reference")
        void customTypesSupport() {
            SchemaFile.LoadedSchema loaded = SchemaFile.fromTree(json("""
                    {
                      "types": {
                        "Email": { "type": "STRING", "rules": [ "email", { "maxLength": 255 } ] },
                        "WorkEmail": { "type": "Email", "rules": [ { "endsWith": "@company.com" } ] }
                      },
                      "fields": { "contact.email": { "type": "WorkEmail", "rules": [ "notNull" ] } }
                    }
                    """));
            assertEquals(1, loaded.fields().size());
            assertEquals(TargetType.STRING, loaded.fields().get(0).type());
            // Inherited: email, maxLength, endsWith + path rule: notNull = 4 rules
            assertEquals(4, loaded.fields().get(0).rules().size());

            var validResult = loaded.schema().validate(Map.of("contact", Map.of("email", "dev@company.com")));
            assertTrue(validResult.isValid(), "Expected valid email to pass: " + validResult.findings());

            var invalidResult = loaded.schema().validate(Map.of("contact", Map.of("email", "dev@other.com")));
            assertTrue(!invalidResult.isValid(), "Expected non-company email to fail");
        }

        @Test
        @DisplayName("a severity on a field applies to every rule it carries, inherited included")
        void fieldSeverity() {
            SchemaFile.LoadedSchema loaded = SchemaFile.fromTree(json("""
                    {
                      "types": { "Email": { "type": "STRING", "rules": [ "email" ] } },
                      "fields": { "contact.email": { "type": "Email", "severity": "warn",
                                                     "rules": [ "notNull" ] } }
                    }
                    """));
            var result = loaded.schema().validate(Map.of("contact", Map.of("email", "not-an-email")));
            assertTrue(result.isValid(), "A warning never fails validation: " + result.findings());
            assertEquals(1, result.warnings().size(), "email fails as a warning; notNull passes on a present value");
            assertTrue(result.errors().isEmpty());
        }

        @Test
        @DisplayName("a severity on a type is inherited and overridden by the field's own")
        void typeSeverity() {
            SchemaFile.LoadedSchema inherited = SchemaFile.fromTree(json("""
                    {
                      "types": { "Email": { "type": "STRING", "severity": "warn", "rules": [ "email" ] } },
                      "fields": { "contact.email": { "type": "Email" } }
                    }
                    """));
            var result = inherited.schema().validate(Map.of("contact", Map.of("email", "not-an-email")));
            assertTrue(result.isValid(), "A warning never fails validation: " + result.findings());
            assertEquals(1, result.warnings().size());

            SchemaFile.LoadedSchema overridden = SchemaFile.fromTree(json("""
                    {
                      "types": { "Email": { "type": "STRING", "severity": "warn", "rules": [ "email" ] } },
                      "fields": { "contact.email": { "type": "Email", "severity": "error" } }
                    }
                    """));
            var failed = overridden.schema().validate(Map.of("contact", Map.of("email", "not-an-email")));
            assertFalse(failed.isValid(), "The field's severity overrides the type's");
            assertEquals(1, failed.errors().size());
        }

        @Test
        @DisplayName("a `!` mark warns under a plain entry, and a plain rule warns under a warn entry")
        void ruleLevelAndEntryLevelWarn() {
            SchemaFile.LoadedSchema loaded = SchemaFile.fromTree(json("""
                    { "fields": { "a": { "type": "STRING", "rules": [ "email!" ] },
                                  "b": { "type": "STRING", "severity": "warn",
                                         "rules": [ "email" ] } } }
                    """));
            var a = loaded.schema().validate(Map.of("a", "not-an-email"));
            assertTrue(a.isValid(), "A `!` rule warns and never fails");
            assertEquals(1, a.warnings().size());

            var b = loaded.schema().validate(Map.of("b", "not-an-email"));
            assertTrue(b.isValid(), "An entry-level severity warns for every rule it carries");
            assertEquals(1, b.warnings().size());
        }

        @Test
        @DisplayName("a `!` mark loads to the warnOnly the code form already carries")
        void bangIsWarnOnly() {
            SchemaFile.LoadedSchema loaded = SchemaFile.fromTree(json("""
                    { "fields": { "a": { "type": "STRING", "rules": [ { "maxLength!": 10 } ] } } }
                    """));
            assertEquals(1, loaded.fields().get(0).rules().size());
            assertEquals(RuleType.MAX_LENGTH.code(), loaded.fields().get(0).rules().get(0).code());
            assertEquals(true, loaded.fields().get(0).rules().get(0).warnOnly());
        }
    }

    @Nested
    @DisplayName("loading from a file")
    class FromFile {

        @Test
        @DisplayName("a schema file loads by extension, JSON or YAML")
        void byExtension(@TempDir Path dir) throws Exception {
            Path json = dir.resolve("schema.json");
            Files.writeString(json, JSON_SCHEMA);
            Path yaml = dir.resolve("schema.yaml");
            Files.writeString(yaml, YAML_SCHEMA);

            assertEquals(jsonOf(SchemaFile.load(json.toString()), "{}"),
                    jsonOf(SchemaFile.load(yaml.toString()), "{}"));
        }

        @Test
        @DisplayName("a missing schema file is a load error naming the file")
        void missing(@TempDir Path dir) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SchemaFile.load(dir.resolve("nope.json").toString()));
            assertTrue(e.getMessage().contains("not found"), e.getMessage());
        }
    }
}
