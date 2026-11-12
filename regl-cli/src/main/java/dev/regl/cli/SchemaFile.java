package dev.regl.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.regl.Field;
import dev.regl.Range;
import dev.regl.Rule;
import dev.regl.RuleType;
import dev.regl.Schema;
import dev.regl.TargetType;

/**
 * Loads a schema file (§12 of the document spec): JSON or YAML, the terse form
 * of §4 serialized — a rule is its own name or a one-key object naming it, and
 * {@code fields} is keyed by path.
 *
 * <p>Loading validates the file the way the code form is validated at
 * construction — an unknown key, an unknown rule code, a target type that is
 * not one, an argument of the wrong shape, overlapping paths, a path deeper
 * than {@code maxDepth} — with the same {@link IllegalArgumentException}. A
 * schema file is just another way of building a schema, so a bad one is the
 * same class of fault as a badly built one.
 *
 * <p>Unknown keys are load errors on purpose: a schema file is authored by a
 * human, and a typo like {@code "feilds"} must fail loudly, not silently
 * produce a schema that validates everything. A repeated key is one: the
 * decoder rejects it, because a repeated path is a duplicate path and a
 * permissive decoder would silently keep the last.
 *
 * <p>Severity is read in two places, with one default. A field or type entry may
 * carry {@code severity: warn}, reaching every rule that entry contributes, a
 * field's overriding the named type's; a rule's name may carry {@code !},
 * marking that one rule a warning. A rule warns if marked or if its effective
 * entry says warn, and is an error otherwise. One direction is deliberately not
 * writable: a plain rule under a warn entry cannot be turned back into an error
 * by the rule alone — declare the entry without a severity and mark the warn
 * rules with {@code !} instead.
 */
public final class SchemaFile {

    /** A schema as loaded, kept in its normalized shape for {@code regl schema --json}. */
    public record LoadedSchema(
            Schema schema,
            boolean strict,
            int maxDepth,
            List<FieldSpec> fields
    ) {

        /** The declared paths, in declaration order — the report order of §9. */
        List<String> paths() {
            return fields.stream().map(FieldSpec::path).toList();
        }
    }

    record FieldSpec(String path, TargetType type, List<Rule> rules) {
    }

    /** A rule entry as read: the rule itself, validated, plus its {@code !} mark if it carries one. */
    private record RuleSpec(Rule rule, boolean marked) {
    }

    /** A {@code types} declaration, unresolved: the named base plus its own rules. */
    private record TypeDecl(String typeRef, List<RuleSpec> rules, Boolean warn) {
    }

    /** A {@code types} declaration, resolved: base type, base-merged rules, and the declared-or-inherited severity. */
    private record ResolvedType(TargetType type, List<RuleSpec> rules, Boolean warn) {
    }

    private SchemaFile() {
    }

    /**
     * Loads a schema from a path or URL.
     *
     * @throws IllegalArgumentException if the URL cannot be read, the format
     *                                  cannot be detected, the file cannot be
     *                                  decoded, or the schema is invalid
     */
    static LoadedSchema load(String url) {
        Urls.Loaded loaded = Urls.read(url);
        Format format = formatFor(url, loaded);
        Object tree = YamlDocuments.decodeSchema(format, loaded.text());
        return fromTree(tree);
    }

    private static Format formatFor(String url, Urls.Loaded loaded) {
        Format byExtension = Format.ofExtension(Urls.fileName(url));
        if (byExtension != null) return byExtension;
        Format byContentType = Format.ofContentType(loaded.contentType());
        if (byContentType != null) return byContentType;
        throw new IllegalArgumentException(
                "Cannot determine the schema's format from '" + url + "'.");
    }

    // --- model parsing -----------------------------------------------------

    public static LoadedSchema fromTree(Object tree) {
        Map<String, Object> root = expectObject(tree, "the schema");
        rejectUnknownKeys(root, ALLOWED_TOP_LEVEL, "the schema");

        Boolean strict = optionalBoolean(root, "strict");
        Integer maxDepth = optionalInteger(root, "maxDepth");

        Map<String, TypeDecl> decls = parseTypes(root.get("types"));
        Map<String, ResolvedType> resolvedTypes = new LinkedHashMap<>();
        for (String name : decls.keySet()) {
            resolveType(name, decls, resolvedTypes, new LinkedHashSet<>());
        }

        List<FieldSpec> fields = new ArrayList<>();
        Object rawFields = requireKey(root, "fields", "the schema");
        if (!(rawFields instanceof Map<?, ?> fieldMap)) {
            throw new IllegalArgumentException("Schema key 'fields' must be an object keyed by path.");
        }
        for (Map.Entry<?, ?> entry : fieldMap.entrySet()) {
            fields.add(fieldSpec(String.valueOf(entry.getKey()), entry.getValue(), decls, resolvedTypes));
        }

        int depth = maxDepth == null ? Schema.DEFAULT_MAX_DEPTH : maxDepth;
        boolean isStrict = Boolean.TRUE.equals(strict);
        Schema schema = Schema.create().maxDepth(depth);
        for (FieldSpec field : fields) {
            schema = schema.field(field.path(), Field.of(field.type(), field.rules()));
        }
        return new LoadedSchema(isStrict ? schema.strict() : schema, isStrict, depth, fields);
    }

    // --- types ---------------------------------------------------------------

    private static Map<String, TypeDecl> parseTypes(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("Schema key 'types' must be an object.");
        }
        Map<String, TypeDecl> decls = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (targetOrNull(name) != null) {
                throw new IllegalArgumentException(
                        "Type '" + name + "' shadows the target type '" + name
                                + "'; the two namespaces are disjoint.");
            }
            decls.put(name, typeDecl(entry.getValue(), "type '" + name + "'"));
        }
        return decls;
    }

    private static TypeDecl typeDecl(Object entry, String where) {
        if (entry instanceof String typeRef) {
            return new TypeDecl(typeRef, List.of(), null);
        }
        Map<String, Object> map = expectObject(entry, where);
        rejectUnknownKeys(map, ALLOWED_ENTRY, where);
        String typeRef = requireString(map, "type", where);
        List<RuleSpec> rules = parseRules(map.get("rules"), where);
        Boolean warn = severity(map, where);
        return new TypeDecl(typeRef, rules, warn);
    }

    private static ResolvedType resolveType(String name, Map<String, TypeDecl> decls,
                                            Map<String, ResolvedType> resolved, Set<String> stack) {
        ResolvedType existing = resolved.get(name);
        if (existing != null) return existing;
        if (!stack.add(name)) {
            List<String> cycle = new ArrayList<>(stack);
            cycle.add(name);
            throw new IllegalArgumentException("Custom type cycle detected: " + String.join(" -> ", cycle) + ".");
        }
        TypeDecl decl = decls.get(name);
        ResolvedType base = resolveTypeRef(decl.typeRef(), decls, resolved, stack, "type '" + name + "'");
        List<RuleSpec> merged = new ArrayList<>(base.rules());
        merged.addAll(decl.rules());
        // A type is checked when resolved, referenced or not — the same errors
        // the code form raises, for every types entry at load.
        Field.of(base.type(), merged.stream().map(RuleSpec::rule).toList());
        Boolean warn = decl.warn() != null ? decl.warn() : base.warn();
        stack.remove(name);
        ResolvedType result = new ResolvedType(base.type(), List.copyOf(merged), warn);
        resolved.put(name, result);
        return result;
    }

    private static ResolvedType resolveTypeRef(String typeRef, Map<String, TypeDecl> decls,
                                               Map<String, ResolvedType> resolved, Set<String> stack, String where) {
        TargetType target = targetOrNull(typeRef);
        if (target != null) return new ResolvedType(target, List.of(), null);
        if (!decls.containsKey(typeRef)) {
            throw new IllegalArgumentException("Unknown type '" + typeRef + "' in " + where
                    + "; expected a target type or a declared type.");
        }
        return resolveType(typeRef, decls, resolved, stack);
    }

    // --- fields ----------------------------------------------------------------

    private static FieldSpec fieldSpec(String path, Object entry,
                                       Map<String, TypeDecl> decls, Map<String, ResolvedType> resolvedTypes) {
        String typeRef;
        List<RuleSpec> ownRules;
        Boolean fieldWarn;
        if (entry instanceof String type) {
            typeRef = type;
            ownRules = List.of();
            fieldWarn = null;
        } else {
            Map<String, Object> map = expectObject(entry, "field '" + path + "'");
            rejectUnknownKeys(map, ALLOWED_ENTRY, "field '" + path + "'");
            typeRef = requireString(map, "type", "field '" + path + "'");
            ownRules = parseRules(map.get("rules"), "field '" + path + "'");
            fieldWarn = severity(map, "field '" + path + "'");
        }

        TargetType targetType;
        List<RuleSpec> inheritedRules = List.of();
        Boolean typeWarn = null;
        if (targetOrNull(typeRef) != null) {
            targetType = targetOrNull(typeRef);
        } else if (decls.containsKey(typeRef)) {
            ResolvedType type = resolveType(typeRef, decls, resolvedTypes, new LinkedHashSet<>());
            targetType = type.type();
            inheritedRules = type.rules();
            typeWarn = type.warn();
        } else {
            throw new IllegalArgumentException("Field '" + path + "' declares unknown type '" + typeRef
                    + "'; expected a target type or a declared type.");
        }

        List<Rule> rules = new ArrayList<>();
        for (RuleSpec spec : inheritedRules) rules.add(stamp(spec, fieldWarn, typeWarn));
        for (RuleSpec spec : ownRules) rules.add(stamp(spec, fieldWarn, typeWarn));
        return new FieldSpec(path, targetType, rules);
    }

    /**
     * Resolves one rule's severity: a rule warns if its name carries {@code !},
     * or if the entry that carries it says warn — and the field's severity
     * overrides the named type's, exactly as merge order overrides rules.
     */
    private static Rule stamp(RuleSpec spec, Boolean fieldWarn, Boolean typeWarn) {
        Boolean entry = fieldWarn != null ? fieldWarn : typeWarn;
        boolean warn = spec.marked() || Boolean.TRUE.equals(entry);
        return warn ? spec.rule().withWarnOnly(true) : spec.rule();
    }

    // --- rules ----------------------------------------------------------------

    private static List<RuleSpec> parseRules(Object raw, String where) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("Key 'rules' in " + where + " must be an array.");
        }
        List<RuleSpec> rules = new ArrayList<>();
        for (Object entry : list) {
            rules.add(ruleSpec(entry, where));
        }
        return rules;
    }

    /**
     * Reads one rule entry (§12): a bare name — {@code notNull}, or
     * {@code email!} for a warning — or a one-key map naming the rule and
     * carrying its argument, the name carrying {@code !} the same way. The
     * {@code !} mark is the rule-level severity. Putting it on the name keeps
     * the argument a first-class value, so {@code { range!: { min: 1 } }}
     * needs no parser for quotes, parens or nested objects; as a suffix it is
     * also the one position YAML accepts unquoted in flow style. Note the
     * polarity is the reverse of TypeScript's {@code !}, which asserts.
     */
    private static RuleSpec ruleSpec(Object entry, String where) {
        if (entry instanceof String name) {
            boolean marked = name.endsWith("!");
            String code = marked ? name.substring(0, name.length() - 1) : name;
            if (code.isEmpty()) {
                throw new IllegalArgumentException("A rule entry in " + where + " must be a non-empty rule name.");
            }
            return new RuleSpec(new Rule(code, null, null, false), marked);
        }
        Map<String, Object> map = expectObject(entry, "a rule in " + where);
        if (map.size() != 1) {
            throw new IllegalArgumentException("A rule map in " + where + " must have exactly one entry"
                    + " — a rule name and its value; found " + map.size() + "."
                    + " The withdrawn {code, value, severity} envelope is among them.");
        }
        Map.Entry<String, Object> only = map.entrySet().iterator().next();
        String name = only.getKey();
        boolean marked = name.endsWith("!");
        String code = marked ? name.substring(0, name.length() - 1) : name;
        if (code.isEmpty()) {
            throw new IllegalArgumentException("A rule name in " + where + " must be non-empty.");
        }
        return new RuleSpec(new Rule(code, coerceRuleValue(code, only.getValue(), where), null, false), marked);
    }

    /**
     * Fits the two structured arguments to their Java shapes — a {@link Range}
     * for {@code range}, a list of strings for {@code enum} — and leaves the
     * rest for {@link Rule}'s own constructor, which validates them against the
     * catalog.
     */
    private static Object coerceRuleValue(String code, Object value, String where) {
        if (value == null) return null;
        if (code.equals(RuleType.RANGE.code())) {
            Map<String, Object> map = expectObject(value, "the 'range' value in " + where);
            return new Range(optionalInteger(map, "min"), optionalInteger(map, "max"));
        }
        if (code.equals(RuleType.ENUM.code())) {
            if (!(value instanceof List<?> list)) {
                throw new IllegalArgumentException(
                        "The 'enum' value in " + where + " must be an array of strings.");
            }
            List<String> allowed = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof String s)) {
                    throw new IllegalArgumentException(
                            "The 'enum' value in " + where + " must contain only strings.");
                }
                allowed.add(s);
            }
            return allowed;
        }
        return value;
    }

    // --- strict map access --------------------------------------------------

    private static final Set<String> ALLOWED_TOP_LEVEL = Set.of(
            "fields", "types", "strict", "maxDepth", "transforms", "ignore");
    private static final Set<String> ALLOWED_ENTRY = Set.of(
            "type", "rules", "severity");

    private static Map<String, Object> expectObject(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected " + what + " to be an object.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /**
     * Asserts that every key of {@code map} is one of {@code allowed}, rejecting
     * typos before they can silently change what the schema says.
     */
    private static void rejectUnknownKeys(Map<String, Object> map, Set<String> allowed, String where) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(
                        "Unknown key '" + key + "' in " + where + "; expected one of " + allowed + ".");
            }
        }
    }

    private static Object requireKey(Map<String, Object> map, String key, String where) {
        if (!map.containsKey(key)) {
            throw new IllegalArgumentException("Missing key '" + key + "' in " + where + ".");
        }
        return map.get(key);
    }

    private static String requireString(Map<String, Object> map, String key, String where) {
        Object value = requireKey(map, key, where);
        if (!(value instanceof String s) || s.isEmpty()) {
            throw new IllegalArgumentException("Key '" + key + "' in " + where + " must be a non-empty string.");
        }
        return s;
    }

    /** Reads a {@code severity} key: {@code warn} or {@code error}, absent meaning unset. */
    private static Boolean severity(Map<String, Object> map, String where) {
        if (!map.containsKey("severity") || map.get("severity") == null) return null;
        Object value = map.get("severity");
        if ("warn".equals(value)) return Boolean.TRUE;
        if ("error".equals(value)) return Boolean.FALSE;
        throw new IllegalArgumentException(
                "Key 'severity' in " + where + " must be 'error' or 'warn'.");
    }

    private static TargetType targetOrNull(String name) {
        try {
            return TargetType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Boolean optionalBoolean(Map<String, Object> map, String key) {
        if (!map.containsKey(key) || map.get(key) == null) return null;
        if (!(map.get(key) instanceof Boolean b)) {
            throw new IllegalArgumentException("Key '" + key + "' must be a boolean.");
        }
        return b;
    }

    private static Integer optionalInteger(Map<String, Object> map, String key) {
        if (!map.containsKey(key) || map.get(key) == null) return null;
        Object value = map.get(key);
        if (value instanceof Integer i) return i;
        if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return (int) n.doubleValue();
        }
        throw new IllegalArgumentException("Key '" + key + "' must be an integer.");
    }
}
