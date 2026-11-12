package dev.regl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates {@code RuleType.java} from the canonical catalog at
 * {@code regl-spec/catalog.json}.
 *
 * <p>{@code RuleType} is pure metadata — code, parameter type, and nothing
 * behavioural — so it is derived from the catalog rather than hand-maintained.
 * The hand-written rule <em>predicates</em> stay in
 * {@code RuleEvaluators.registerBuiltIns()}, which is the reference
 * implementation of the rule semantics; only the enum is generated.
 *
 * <p>Run with {@code --write} to regenerate the file in place.
 */
final class RuleTypeGenerator {

    private RuleTypeGenerator() {
    }

    static final String GENERATED_HEADER =
            "// GENERATED FILE — DO NOT EDIT BY HAND.\n"
                    + "// Source of truth: regl-spec/catalog.json\n"
                    + "// Regenerate with: mvn -q test-compile exec:java "
                    + "-Dexec.mainClass=dev.regl.RuleTypeGenerator -Dexec.args=--write\n";

    /** Renders the complete {@code RuleType.java} source. */
    static String generate(List<Map<String, Object>> rules) {
        StringBuilder sb = new StringBuilder();
        sb.append("package dev.regl;\n\n");
        sb.append(GENERATED_HEADER);
        sb.append("\n/**\n");
        sb.append(" * The built-in rule set, derived from {@code regl-spec/catalog.json}.\n");
        sb.append(" *\n");
        sb.append(" * <p>Every enum constant corresponds to one catalog entry. Rule semantics\n");
        sb.append(" * are implemented by the evaluators in {@link RuleEvaluators}.\n");
        sb.append(" */\n");
        sb.append("public enum RuleType {\n");

        // Group by target type, preserving catalog order within each group.
        // The group order comes from the enum rather than a literal list, so a
        // target type added to TargetType cannot silently go missing here.
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (TargetType target : TargetType.values()) {
            grouped.put(target.name(), new ArrayList<>());
        }
        for (Map<String, Object> rule : rules) {
            String target = (String) rule.get("targetType");
            List<Map<String, Object>> bucket = grouped.get(target);
            if (bucket == null) {
                throw new IllegalStateException(
                        "catalog.json rule '" + rule.get("id") + "' has unknown targetType '" + target + "'.");
            }
            bucket.add(rule);
        }

        boolean firstGroup = true;
        for (Map.Entry<String, List<Map<String, Object>>> entry : grouped.entrySet()) {
            List<Map<String, Object>> bucket = entry.getValue();
            if (bucket.isEmpty()) {
                continue;
            }
            if (!firstGroup) {
                sb.append('\n');
            }
            firstGroup = false;
            sb.append("    // ").append(entry.getKey()).append('\n');

            for (int i = 0; i < bucket.size(); i++) {
                Map<String, Object> rule = bucket.get(i);
                String id = (String) rule.get("id");
                Object parameterType = rule.get("parameterType");
                String parameter = parameterType == null ? "null" : "ParameterType." + parameterType;
                String terminator = i == bucket.size() - 1 && isLastGroup(grouped, entry.getKey()) ? ";" : ",";

                sb.append("    ").append(constantName(id)).append("(\"")
                        .append(escape(id)).append("\", ")
                        .append(parameter).append(", ")
                        .append("TargetType.").append(rule.get("targetType")).append(")")
                        .append(terminator).append('\n');
            }
        }

        sb.append('\n');
        sb.append("    private final String code;\n");
        sb.append("    private final ParameterType parameterType;\n");
        sb.append("    private final TargetType targetType;\n\n");
        sb.append("    RuleType(String code, ParameterType parameterType, TargetType targetType) {\n");
        sb.append("        this.code = code;\n");
        sb.append("        this.parameterType = parameterType;\n");
        sb.append("        this.targetType = targetType;\n");
        sb.append("    }\n\n");
        sb.append("    /** The catalog rule id, e.g. {@code \"startsWith\"}. */\n");
        sb.append("    public String code() {\n");
        sb.append("        return code;\n");
        sb.append("    }\n\n");
        sb.append("    /** The catalog parameter type, or {@code null} for parameterless rules. */\n");
        sb.append("    public ParameterType parameterType() {\n");
        sb.append("        return parameterType;\n");
        sb.append("    }\n\n");
        sb.append("    /** The catalog target type this rule applies to. */\n");
        sb.append("    public TargetType targetType() {\n");
        sb.append("        return targetType;\n");
        sb.append("    }\n\n");
        sb.append("    /**\n");
        sb.append("     * Resolves {@code code} to its built-in {@link RuleType}.\n");
        sb.append("     *\n");
        sb.append("     * @throws IllegalArgumentException if the code is null or unknown\n");
        sb.append("     */\n");
        sb.append("    public static RuleType fromCode(String code) {\n");
        sb.append("        if (code == null) {\n");
        sb.append("            throw new IllegalArgumentException(\"Rule type cannot be null.\");\n");
        sb.append("        }\n");
        sb.append("        for (RuleType type : values()) {\n");
        sb.append("            if (type.code.equals(code)) {\n");
        sb.append("                return type;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        throw new IllegalArgumentException(\"Unknown rule type: \" + code);\n");
        sb.append("    }\n\n");
        sb.append("    /**\n");
        sb.append("     * Resolves {@code code} to its built-in {@link RuleType}, or {@code null}\n");
        sb.append("     * when the code is not a built-in rule (i.e. a custom rule backed by a\n");
        sb.append("     * registered {@link RuleEvaluator}).\n");
        sb.append("     */\n");
        sb.append("    public static RuleType fromCodeOrNull(String code) {\n");
        sb.append("        if (code == null) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        for (RuleType type : values()) {\n");
        sb.append("            if (type.code.equals(code)) {\n");
        sb.append("                return type;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return null;\n");
        sb.append("    }\n\n");
        sb.append("    @Override\n");
        sb.append("    public String toString() {\n");
        sb.append("        return code;\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static boolean isLastGroup(Map<String, List<Map<String, Object>>> grouped, String key) {
        String last = null;
        for (Map.Entry<String, List<Map<String, Object>>> e : grouped.entrySet()) {
            if (!e.getValue().isEmpty()) {
                last = e.getKey();
            }
        }
        return key.equals(last);
    }

    /** {@code minLength} to {@code MIN_LENGTH}, {@code isTrue} to {@code IS_TRUE}. */
    static String constantName(String id) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Writes {@code RuleType.java} if it differs from what the catalog implies.
     *
     * <p>Normally invoked by {@code CatalogParityTest} so a stale generated
     * file is rewritten automatically on the next build. The catalog is the
     * source of truth, so regenerating is always safe.
     */
    @SuppressWarnings("unchecked")
    static synchronized boolean writeIfChanged() throws java.io.IOException {
        List<Map<String, Object>> rules = (List<Map<String, Object>>) Spec.parse("catalog.json");
        java.nio.file.Path target = locate();
        String generated = generate(rules);
        if (java.nio.file.Files.exists(target)
                && java.nio.file.Files.readString(target).equals(generated)) {
            return false;
        }
        java.nio.file.Files.createDirectories(target.getParent());
        java.nio.file.Files.writeString(target, generated);
        System.out.println("[regl] Regenerated " + target + " from catalog.json ("
                + rules.size() + " rules).");
        return true;
    }

    /**
     * Regenerates {@code RuleType.java} in place. Run after editing
     * {@code regl-spec/catalog.json}:
     *
     * <pre>{@code mvn -q test-compile exec:java \
     *   -Dexec.mainClass=dev.regl.RuleTypeGenerator -Dexec.args=--write}</pre>
     */
    public static void main(String[] args) throws java.io.IOException {
        System.out.println(writeIfChanged()
                ? "Wrote RuleType.java."
                : "RuleType.java is already up to date.");
    }

    /** The on-disk location of the generated enum, resolved from the module root. */
    static java.nio.file.Path locate() {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            java.nio.file.Path candidate = dir.resolve("src/main/java/dev/regl/RuleType.java");
            if (java.nio.file.Files.isDirectory(candidate.getParent())) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate src/main/java/dev/regl/RuleType.java");
    }
}
