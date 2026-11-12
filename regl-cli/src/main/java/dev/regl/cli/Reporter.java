package dev.regl.cli;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.regl.DocumentFinding;
import dev.regl.DocumentResult;

/**
 * The CLI's two output shapes (§14 of the document spec).
 *
 * <p>Human output: one line per finding, errors before warnings, then a summary
 * — {@code ok} for a valid document, {@code N errors} otherwise. {@code --quiet}
 * drops the summary; the exit code carries the verdict.
 *
 * <p>{@code --json} output is the {@link DocumentResult} shape itself, which is
 * the cross-engine contract: the TypeScript CLI must emit the same bytes for the
 * same inputs.
 */
final class Reporter {

    private static final String RED = "\u001b[31m";
    private static final String GREEN = "\u001b[32m";
    private static final String YELLOW = "\u001b[33m";
    private static final String RESET = "\u001b[0m";

    private final PrintStream out;
    private final boolean quiet;
    private final boolean color;

    Reporter(PrintStream out, boolean quiet, boolean color) {
        this.out = out;
        this.quiet = quiet;
        this.color = color;
    }

    // --- human -------------------------------------------------------------

    void human(DocumentResult result) {
        for (DocumentFinding finding : result.findings()) {
            String severity = color
                    ? (finding.isError() ? RED + "ERROR" + RESET : YELLOW + "WARNING" + RESET)
                    : (finding.isError() ? "ERROR" : "WARNING");
            String rule = finding.rule() == null ? "" : " " + finding.rule();
            out.println(severity + " " + finding.path() + rule + ": " + finding.message());
        }
        if (quiet) return;
        if (result.isValid()) {
            String ok = color ? GREEN + "ok" + RESET : "ok";
            out.println(result.warnings().isEmpty() ? ok
                    : ok + " (" + result.warnings().size() + " warning"
                    + (result.warnings().size() == 1 ? "" : "s") + ")");
        } else {
            StringBuilder summary = new StringBuilder();
            summary.append(result.errors().size())
                    .append(result.errors().size() == 1 ? " error" : " errors");
            if (!result.warnings().isEmpty()) {
                summary.append(", ").append(result.warnings().size())
                        .append(result.warnings().size() == 1 ? " warning" : " warnings");
            }
            String line = color ? RED + summary + RESET : summary.toString();
            out.println(line);
        }
    }

    /** One line per declared field, for {@code regl schema}. */
    void schema(SchemaFile.LoadedSchema schema) {
        for (SchemaFile.FieldSpec field : schema.fields()) {
            List<String> codes = new ArrayList<>();
            field.rules().forEach(rule -> codes.add(rule.code()));
            String chain = String.join(" ", codes);
            String line = field.path() + "  " + field.type() + "  " + chain;
            out.println(color ? YELLOW + line + RESET : line);
        }
        if (quiet) return;
        String options = "strict " + (schema.strict() ? "on" : "off")
                + ", maxDepth " + schema.maxDepth()
                + ", " + schema.fields().size()
                + (schema.fields().size() == 1 ? " field" : " fields");
        out.println(color ? GREEN + options + RESET : options);
    }

    // --- json --------------------------------------------------------------

    /** The canonical {@code --json} rendering of a validation result. */
    String json(DocumentResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", result.isValid());
        out.put("errors", findings(result.errors()));
        out.put("warnings", findings(result.warnings()));
        return JsonWriter.write(out);
    }

    /** The normalized schema, in the §12 shape exactly as regl understood it. */
    String schemaJson(SchemaFile.LoadedSchema schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strict", schema.strict());
        out.put("maxDepth", schema.maxDepth());
        List<Object> fields = new ArrayList<>();
        for (SchemaFile.FieldSpec field : schema.fields()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("path", field.path());
            f.put("type", field.type().name());
            List<Object> rules = new ArrayList<>();
            for (dev.regl.Rule rule : field.rules()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("code", rule.code());
                Object value = normalizedValue(rule.value());
                if (value != null) r.put("value", value);
                if (rule.warnOnly()) r.put("severity", "warn");
                rules.add(r);
            }
            f.put("rules", rules);
            fields.add(f);
        }
        out.put("fields", fields);
        return JsonWriter.write(out);
    }

    private static List<Object> findings(List<DocumentFinding> findings) {
        List<Object> out = new ArrayList<>();
        for (DocumentFinding finding : findings) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("severity", finding.severity().name());
            f.put("kind", finding.kind().name());
            f.put("path", finding.path());
            f.put("rule", finding.rule());
            f.put("value", normalizedValue(finding.value()));
            f.put("message", finding.message());
            out.add(f);
        }
        return out;
    }

    /**
     * A value in its canonical JSON shape: a {@code range} value is the
     * {@code {"min", "max"}} object, with an unbounded side omitted — the shape
     * the schema file itself uses, and the shape the TypeScript CLI emits.
     */
    private static Object normalizedValue(Object value) {
        if (value instanceof dev.regl.Range range) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (range.min() != null) out.put("min", range.min());
            if (range.max() != null) out.put("max", range.max());
            return out;
        }
        return value;
    }
}