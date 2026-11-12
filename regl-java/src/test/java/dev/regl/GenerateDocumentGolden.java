package dev.regl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every case in {@code regl-spec/conformance/document-cases.json} and prints
 * the transcript as JSON in the golden file's format.
 *
 * <p>Both engines have this generator and both write the same shape, so the
 * golden file is generated once and then asserted by both suites. Regenerate it
 * with {@code mvn -q compile exec:java -Dexec.mainClass=dev.regl.GenerateDocumentGolden}
 * (or {@code npm run golden} in regl-ts) and read the diff before committing: a
 * change here is a behaviour change in the public API.
 *
 * <p>Message text is deliberately excluded from the transcript. Messages are for
 * humans and are allowed to be worded per locale; {@code severity}, {@code kind},
 * {@code path}, and {@code rule} are what consumers branch on.
 */
public final class GenerateDocumentGolden {

    private GenerateDocumentGolden() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> spec() {
        return Spec.readObject("conformance/document-cases.json");
    }

    @SuppressWarnings("unchecked")
    static Rule rule(Map<String, Object> r) {
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
    static Field<?> field(Map<String, Object> f) {
        List<Rule> rules = new ArrayList<>();
        for (Object r : (List<Object>) f.get("rules")) {
            rules.add(rule((Map<String, Object>) r));
        }
        return Field.of(TargetType.valueOf(String.valueOf(f.get("type"))), rules);
    }

    @SuppressWarnings("unchecked")
    static Schema schema(Map<String, Object> schemaSpec, boolean strict) {
        Schema schema = Schema.create();
        for (Object f : (List<Object>) schemaSpec.get("fields")) {
            schema = schema.field(String.valueOf(((Map<?, ?>) f).get("path")),
                    field((Map<String, Object>) f));
        }
        return strict ? schema.strict() : schema;
    }

    @SuppressWarnings("unchecked")
    static void addCases(List<Map<String, Object>> rows, List<Object> group, boolean strict, String id) {
        for (Object c : group) {
            Map<String, Object> schemaSpec = (Map<String, Object>) c;
            String schemaName = String.valueOf(schemaSpec.get("name"));
            for (Object d : (List<Object>) schemaSpec.get("documents")) {
                Map<String, Object> doc = (Map<String, Object>) d;
                String caseId = id + "/" + schemaName + "/" + doc.get("name");
                DocumentResult result = schema(schemaSpec, strict).validate(doc.get("doc"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", caseId);
                row.put("findings", transcript(result));
                rows.add(row);
            }
        }
    }

    static String transcript(DocumentResult result) {
        List<String> out = new ArrayList<>();
        out.add("valid=" + result.isValid());
        for (DocumentFinding f : result.findings()) {
            out.add(f.severity() + " " + f.kind() + " " + f.path() + " "
                    + (f.rule() == null ? "-" : f.rule()));
        }
        return String.join("\n", out);
    }

    public static void main(String[] args) {
        Map<String, Object> spec = spec();
        List<Map<String, Object>> rows = new ArrayList<>();
        addCases(rows, (List<Object>) spec.get("cases"), false, "case");
        addCases(rows, (List<Object>) spec.get("strictCases"), true, "strictCase");

        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            json.append("  {\n");
            json.append("    \"id\": \"").append(row.get("id")).append("\",\n");
            json.append("    \"findings\": ").append(quote(String.valueOf(row.get("findings")))).append("\n");
            json.append("  }").append(i == rows.size() - 1 ? "\n" : ",\n");
        }
        json.append("]\n");
        System.out.print(json);
    }

    /** Minimal JSON string escaping, so the generator stays dependency-free. */
    static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}