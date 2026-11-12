package dev.regl.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.regl.Documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command surface end to end: a schema file and a document on disk, the
 * engine called exactly the way {@link Main} calls it, and the exit codes,
 * human output, and canonical {@code --json} output asserted (§14).
 */
class ReglTest {

    private static final String SCHEMA = """
            {
              "strict": false,
              "fields": {
                "name": { "type": "STRING", "rules": [ "notNull", { "minLength": 3 } ] },
                "server.port": { "type": "INTEGER", "rules": [ "notNull" ] },
                "nickname": { "type": "STRING", "severity": "warn", "rules": [ { "maxLength": 3 } ] }
              }
            }
            """;

    /** One run of {@code regl <schema> <document>}, capturing both streams. */
    private static class Run {
        final int exit;
        final String out;
        final String err;

        Run(String schema, String document, String format, boolean json, boolean quiet) {
            ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
            ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
            this.exit = Regl.validate(schema, document, format, json, quiet, false,
                    new PrintStream(outBytes), new PrintStream(errBytes));
            this.out = outBytes.toString().trim();
            this.err = errBytes.toString().trim();
        }
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Nested
    @DisplayName("exit codes")
    class ExitCodes {

        @Test
        @DisplayName("0 for a valid document, with the ok summary")
        void valid(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);
            Path document = write(dir, "doc.json",
                    "{\"name\":\"api\",\"server\":{\"port\":8080}}");

            Run run = new Run(schema.toString(), document.toString(), null, false, false);

            assertEquals(0, run.exit);
            assertEquals("ok", run.out);
        }

        @Test
        @DisplayName("1 for an invalid document, with one line per finding and a count")
        void invalid(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);
            Path document = write(dir, "doc.json", "{\"server\":{\"port\":\"8080\"}}");

            Run run = new Run(schema.toString(), document.toString(), null, false, false);

            assertEquals(1, run.exit);
            assertEquals(List.of(
                    "ERROR name notNull: Value is required and must not be null.",
                    "ERROR server.port: Value must be of type INTEGER, but got STRING.",
                    "2 errors"
            ), List.of(run.out.split("\n")));
        }

        @Test
        @DisplayName("0 with a warning summary when only a warn-only rule fails")
        void warnings(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);
            Path document = write(dir, "doc.json",
                    "{\"name\":\"api\",\"server\":{\"port\":8080},\"nickname\":\"alexandra\"}");

            Run run = new Run(schema.toString(), document.toString(), null, false, false);

            assertEquals(0, run.exit);
            assertEquals(List.of(
                    "WARNING nickname maxLength: Maximum length is 3, but got 9.",
                    "ok (1 warning)"
            ), List.of(run.out.split("\n")));
        }

        @Test
        @DisplayName("--quiet drops the summary; the exit code carries the verdict")
        void quiet(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);
            Path document = write(dir, "doc.json",
                    "{\"name\":\"api\",\"server\":{\"port\":8080},\"nickname\":\"alexandra\"}");

            Run run = new Run(schema.toString(), document.toString(), null, false, true);

            assertEquals(0, run.exit);
            assertEquals(List.of(
                    "WARNING nickname maxLength: Maximum length is 3, but got 9."
            ), List.of(run.out.split("\n")));
        }

        @Test
        @DisplayName("2 when the schema is unreadable")
        void missingSchema(@TempDir Path dir) throws Exception {
            Path document = write(dir, "doc.json", "{}");

            Run run = new Run(dir.resolve("nope.json").toString(), document.toString(),
                    null, false, false);

            assertEquals(2, run.exit);
            assertTrue(run.out.isEmpty());
            assertTrue(run.err.startsWith("regl: "), run.err);
            assertTrue(run.err.contains("not found"), run.err);
        }

        @Test
        @DisplayName("2 when the document is unreadable")
        void missingDocument(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);

            Run run = new Run(schema.toString(), dir.resolve("nope.json").toString(),
                    null, false, false);

            assertEquals(2, run.exit);
            assertTrue(run.err.contains("not found"), run.err);
        }

        @Test
        @DisplayName("2 when the document cannot be decoded — a load error, not findings")
        void undecodableDocument(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);
            Path document = write(dir, "doc.json", "{\"name\": ");

            Run run = new Run(schema.toString(), document.toString(), null, false, false);

            assertEquals(2, run.exit);
            assertTrue(run.err.contains("regl: "), run.err);
            assertFalse(run.out.contains("ERROR"), "a decode error must not look like findings");
        }

        @Test
        @DisplayName("2 when the schema is invalid, before any document is read")
        void invalidSchema(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", """
                    { "fields": { "servers.*.port": "NUMBER",
                                  "servers.0.port": "NUMBER" } }
                    """);
            Path document = write(dir, "doc.json", "{}");

            Run run = new Run(schema.toString(), document.toString(), null, false, false);

            assertEquals(2, run.exit);
            assertTrue(run.err.contains("overlaps"), run.err);
        }

        @Test
        @DisplayName("2 when --format names no known format")
        void badFormatFlag(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);
            Path document = write(dir, "doc.json", "{}");

            Run run = new Run(schema.toString(), document.toString(), "xml", false, false);

            assertEquals(2, run.exit);
            assertTrue(run.err.contains("xml"), run.err);
        }
    }

    @Nested
    @DisplayName("formats")
    class Formats {

        @Test
        @DisplayName("a YAML document against a YAML schema")
        void yaml(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.yaml", """
                    fields:
                      name: { type: STRING, rules: [notNull, { minLength: 3 }] }
                    """);
            Path document = write(dir, "doc.yaml", "name: ab\n");

            Run run = new Run(schema.toString(), document.toString(), null, false, false);

            assertEquals(1, run.exit);
            assertTrue(run.out.contains("name minLength"), run.out);
        }

        @Test
        @DisplayName("a .properties document expands dotted keys, and its strings mismatch NUMBER")
        void properties(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);
            Path document = write(dir, "doc.properties", "name=api\nserver.port=8080\n");

            Run run = new Run(schema.toString(), document.toString(), null, false, false);

            assertEquals(1, run.exit);
            assertTrue(run.out.contains("server.port: Value must be of type INTEGER, but got STRING."),
                    run.out);
            assertFalse(run.out.contains("name"), "the STRING rules pass on the string value");
        }

        @Test
        @DisplayName("--format decides an extensionless document")
        void formatOverride(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);
            Path document = write(dir, "document", "{\"name\":\"api\",\"server\":{\"port\":8080}}");

            assertEquals(0, new Run(schema.toString(), document.toString(), "json", false, false).exit);
            assertEquals(2, new Run(schema.toString(), document.toString(), null, false, false).exit);
        }

        @Test
        @DisplayName("without --format, an undetectable format is a load error that says so")
        void undetectable(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);
            Path document = write(dir, "document", "{\"name\":\"api\"}");

            Run run = new Run(schema.toString(), document.toString(), null, false, false);

            assertEquals(2, run.exit);
            assertTrue(run.err.contains("--format"), run.err);
        }
    }

    @Nested
    @DisplayName("--json output")
    class JsonOutput {

        @Test
        @DisplayName("is the DocumentResult shape, in a fixed key order")
        void shape(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);
            Path document = write(dir, "doc.json", "{\"server\":{\"port\":\"8080\"}}");

            Run run = new Run(schema.toString(), document.toString(), null, true, false);

            assertEquals(1, run.exit);
            Map<String, Object> parsed = (Map<String, Object>) Documents.fromJson(run.out);
            assertEquals(List.of("valid", "errors", "warnings"),
                    new ArrayList<>(parsed.keySet()));
            assertEquals(false, parsed.get("valid"));

            Map<String, Object> finding = (Map<String, Object>) ((List<?>) parsed.get("errors")).get(0);
            assertEquals(List.of("severity", "kind", "path", "rule", "value", "message"),
                    new ArrayList<>(finding.keySet()));
            assertEquals("RULE_FAILED", finding.get("kind"));
            assertEquals("notNull", finding.get("rule"));
            assertEquals(null, finding.get("value"));
        }

        @Test
        @DisplayName("keeps params, with an integral number written without a trailing .0")
        void params(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);
            Path document = write(dir, "doc.json", "{\"name\":\"ab\"}");

            Run run = new Run(schema.toString(), document.toString(), null, true, false);

            Map<String, Object> parsed = (Map<String, Object>) Documents.fromJson(run.out);
            List<?> errors = (List<?>) parsed.get("errors");
            Map<String, Object> finding = (Map<String, Object>) errors.get(0);
            assertEquals("RULE_FAILED", finding.get("kind"));
            assertEquals(3, finding.get("value"));
        }
    }

    @Nested
    @DisplayName("regl schema")
    class SchemaInspection {

        @Test
        @DisplayName("prints one line per field and the schema's options")
        void human(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", SCHEMA);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int exit = Regl.schema(schema.toString(), false, false, false,
                    new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));

            assertEquals(0, exit);
            assertEquals(List.of(
                    "name  STRING  notNull minLength",
                    "server.port  INTEGER  notNull",
                    "nickname  STRING  maxLength",
                    "strict off, maxDepth 32, 3 fields"
            ), List.of(out.toString().trim().split("\n")));
        }

        @Test
        @DisplayName("--json prints the normalized schema in the §12 shape")
        void json(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", """
                    { "fields": { "tags": { "type": "LIST", "rules": [ { "range": { "min": 1 } } ] } } }
                    """);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int exit = Regl.schema(schema.toString(), true, false, false,
                    new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));

            assertEquals(0, exit);
            Map<String, Object> parsed = (Map<String, Object>) Documents.fromJson(out.toString());
            assertEquals(List.of("strict", "maxDepth", "fields"), new ArrayList<>(parsed.keySet()));
            List<?> fields = (List<?>) parsed.get("fields");
            Map<String, Object> field = (Map<String, Object>) fields.get(0);
            Map<String, Object> rule = (Map<String, Object>) ((List<?>) field.get("rules")).get(0);
            assertEquals(Map.of("min", 1), rule.get("value"));
        }

        @Test
        @DisplayName("a `!`-marked rule normalizes to `severity: warn`, the one data spelling")
        void jsonWarnSeverity(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.yaml", """
                    fields:
                      tags:
                        type: LIST
                        rules:
                          - range!: { min: 1, max: 5 }
                          - unique
                    """);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int exit = Regl.schema(schema.toString(), true, false, false,
                    new PrintStream(out), new PrintStream(new ByteArrayOutputStream()));

            assertEquals(0, exit);
            List<?> fields = (List<?>) ((Map<String, Object>) Documents.fromJson(out.toString()))
                    .get("fields");
            List<?> rules = (List<?>) ((Map<String, Object>) fields.get(0)).get("rules");
            Map<String, Object> soft = (Map<String, Object>) rules.get(0);
            Map<String, Object> hard = (Map<String, Object>) rules.get(1);
            assertEquals("warn", soft.get("severity"));
            assertEquals(List.of("code", "value", "severity"), new ArrayList<>(soft.keySet()));
            assertEquals(List.of("code"), new ArrayList<>(hard.keySet()));
            assertFalse(out.toString().contains("warnOnly"), out.toString());
        }

        @Test
        @DisplayName("an invalid schema exits 2, the same error validation would raise")
        void invalid(@TempDir Path dir) throws Exception {
            Path schema = write(dir, "schema.json", "{ \"fields\": { \"a\": \"TEXT\" } }");

            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exit = Regl.schema(schema.toString(), false, false, false,
                    new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));

            assertEquals(2, exit);
            assertTrue(err.toString().contains("TEXT"), err.toString());
        }
    }
}