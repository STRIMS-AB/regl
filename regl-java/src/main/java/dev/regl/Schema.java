package dev.regl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates a whole document — a decoded tree of objects, lists, and scalars —
 * against a set of fields, and reports every problem it can reach instead of
 * throwing on the first one.
 *
 * <pre>{@code
 * Schema schema = Schema.create()
 *     .field("name", string().notNull().minLength(1))
 *     .field("server.port", integer().notNull().lessThan(65536))
 *     .field("servers.*.host", string().email())
 *     .strict();
 *
 * DocumentResult result = schema.validate(decodedDocument);
 * }</pre>
 *
 * <h2>Conventions</h2>
 * <ul>
 *   <li><b>A missing field is {@code null}.</b> Resolution has no third state,
 *       and {@code notNull()} is what makes a field mandatory.</li>
 *   <li><b>Nothing is coerced.</b> A value whose kind contradicts the declared
 *       type is a {@code TYPE_MISMATCH} finding, never a conversion.</li>
 *   <li><b>Fields must be disjoint.</b> Two fields that could match one concrete
 *       path are a construction error, so behaviour never depends on which was
 *       declared first.</li>
 * </ul>
 *
 * <p>Instances are immutable: {@link #field} and the options each return a new
 * schema.
 */
public final class Schema {

    /** The default cap on a declared path's segment count. */
    public static final int DEFAULT_MAX_DEPTH = 32;

    private record Entry(int order, String path, List<String> segments, Field<?> field) {
    }

    private final List<Entry> entries;
    private final boolean strict;
    private final int maxDepth;
    private final Map<String, Field<?>> customTypes;

    private Schema(List<Entry> entries, boolean strict, int maxDepth, Map<String, Field<?>> customTypes) {
        this.entries = List.copyOf(entries);
        this.strict = strict;
        this.maxDepth = maxDepth;
        this.customTypes = Map.copyOf(customTypes);
    }

    private Schema(List<Entry> entries, boolean strict, int maxDepth) {
        this(entries, strict, maxDepth, Map.of());
    }

    /** An empty schema. Every document validates. */
    public static Schema create() {
        return new Schema(List.of(), false, DEFAULT_MAX_DEPTH, Map.of());
    }

    /**
     * Returns a schema with {@code field} added for {@code path}.
     *
     * <p>Field order is the order findings are reported in, so declare fields in
     * the order they should be reported rather than alphabetically.
     *
     * @throws IllegalArgumentException if {@code path} is empty, deeper than
     *         {@link #maxDepth(int)}, or overlaps an already-declared path
     */
    public Schema field(String path, Field<?> field) {
        Objects.requireNonNull(field, "field");
        List<String> segments = Path.parse(path);
        if (segments.size() > maxDepth) {
            throw new IllegalArgumentException("Path '" + path + "' has " + segments.size()
                    + " segments, which exceeds the schema's maxDepth of " + maxDepth + ".");
        }
        for (Entry existing : entries) {
            if (Path.overlaps(existing.segments(), segments)) {
                throw new IllegalArgumentException("Path '" + path + "' overlaps the already-declared path '"
                        + existing.path() + "'; two fields must not match the same value.");
            }
        }
        List<Entry> next = new ArrayList<>(entries);
        next.add(new Entry(entries.size(), path, segments, field));
        return new Schema(next, strict, maxDepth, customTypes);
    }

    /**
     * Registers a reusable custom field definition by name.
     */
    public Schema define(String name, Field<?> field) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(field, "field");
        Map<String, Field<?>> next = new LinkedHashMap<>(customTypes);
        next.put(name, field);
        return new Schema(entries, strict, maxDepth, next);
    }

    /**
     * Registers a custom field definition with explicit target type and rules.
     */
    public Schema define(String name, TargetType targetType, Rule... rules) {
        return define(name, Field.of(targetType, rules != null ? List.of(rules) : List.of()));
    }

    /**
     * Registers a custom field definition extending an existing custom field or base type.
     */
    public Schema define(String name, String baseTypeName, Rule... additionalRules) {
        Field<?> base = customTypes.get(baseTypeName);
        if (base == null) {
            try {
                TargetType target = TargetType.valueOf(baseTypeName.toUpperCase());
                return define(name, target, additionalRules);
            } catch (Exception e) {
                throw new IllegalArgumentException("Unknown base type '" + baseTypeName + "' for custom field '" + name + "'.");
            }
        }
        List<Rule> merged = new ArrayList<>(base.rules());
        if (additionalRules != null) {
            merged.addAll(List.of(additionalRules));
        }
        return define(name, Field.of(base.type(), merged));
    }

    /**
     * Attaches a registered custom field to a path, optionally adding additional rules.
     */
    public Schema field(String path, String customTypeName, Rule... additionalRules) {
        Field<?> custom = customTypes.get(customTypeName);
        if (custom == null) {
            try {
                TargetType target = TargetType.valueOf(customTypeName.toUpperCase());
                return field(path, Field.of(target, additionalRules != null ? List.of(additionalRules) : List.of()));
            } catch (Exception e) {
                throw new IllegalArgumentException("Unknown field type '" + customTypeName + "' for path '" + path + "'.");
            }
        }
        List<Rule> merged = new ArrayList<>(custom.rules());
        if (additionalRules != null) {
            merged.addAll(List.of(additionalRules));
        }
        return field(path, Field.of(custom.type(), merged));
    }

    /** Returns the custom field registered for {@code name}, or {@code null}. */
    public Field<?> customType(String name) {
        return customTypes.get(name);
    }

    /** All registered custom field types. */
    public Map<String, Field<?>> customTypes() {
        return customTypes;
    }

    /**
     * Whether every builder is used, so nothing is unvalidated by accident.
     */
    public Schema strict() {
        return new Schema(entries, true, maxDepth, customTypes);
    }

    /** Caps the segment count any declared path may have. */
    public Schema maxDepth(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1.");
        }
        return new Schema(entries, strict, maxDepth, customTypes);
    }

    public boolean isStrict() {
        return strict;
    }

    public int maxDepth() {
        return maxDepth;
    }

    /** The declared paths, in declaration order. */
    public List<String> paths() {
        return entries.stream().map(Entry::path).toList();
    }

    /**
     * The field declared for a path, or {@code null} if there is none.
     *
     * <p>Useful for introspection — building a UI of the schema, or asserting
     * coverage in a test — without a consumer having to keep its own copy of what
     * it declared.
     */
    public Field<?> field(String path) {
        for (Entry entry : entries) {
            if (entry.path().equals(path)) return entry.field();
        }
        return null;
    }

    /**
     * Validates a decoded document and reports every problem found.
     *
     * <p>Never throws on document content. Findings come out in a total order:
     * field declaration index, then rule position, then list index, then kind.
     */
    public DocumentResult validate(Object document) {
        java.util.List<Finding> found = entries.parallelStream()
                .flatMap(entry -> {
                    java.util.List<Finding> entryFindings = new java.util.ArrayList<>();
                    Resolution resolution = resolve(document, entry.segments());
                    if (resolution.kind != null) {
                        entryFindings.add(new Finding(entry.order, -1,
                                DocumentFinding.error(resolution.kind, entry.path(), resolution.message)));
                    } else {
                        for (Hit hit : resolution.hits) {
                            entryFindings.addAll(check(entry, hit, document));
                        }
                    }
                    return entryFindings.stream();
                })
                .collect(java.util.stream.Collectors.toCollection(java.util.concurrent.CopyOnWriteArrayList::new));

        if (strict) {
            found.addAll(unknownFields(document));
        }
        
        java.util.List<Finding> sorted = new java.util.ArrayList<>(found);
        sorted.sort(ORDER);
        return toResult(sorted);
    }

    // --- chain evaluation --------------------------------------------------

    private List<Finding> check(Entry entry, Hit hit, Object document) {
        List<Rule> chain = entry.field().rules();
        List<Finding> out = new ArrayList<>();

        // notNull is evaluated before the null short-circuit, so a mandatory field
        // reports itself even though nothing else will run.
        for (int i = 0; i < chain.size(); i++) {
            Rule rule = chain.get(i);
            if (!rule.code().equals(RuleType.NOT_NULL.code())) continue;
            if (hit.value == null) {
                out.add(failure(entry.order, i, hit.path, rule, "Value is required and must not be null."));
            }
        }
        if (hit.value == null) {
            return out;
        }

        // The declared type is checked once for the whole chain. Every rule in a
        // chain shares it, so naming one rule would be arbitrary.
        if (!RuleEvaluators.matchesTargetType(entry.field().type(), hit.value)) {
            out.add(new Finding(entry.order, -1, DocumentFinding.error(
                    DocumentFinding.Kind.TYPE_MISMATCH, hit.path,
                    "Value must be of type " + entry.field().type()
                            + ", but got " + RuleEvaluators.describeType(hit.value) + ".")));
            return out;
        }

        for (int i = 0; i < chain.size(); i++) {
            Rule rule = chain.get(i);
            if (rule.code().equals(RuleType.NOT_NULL.code())) continue;
            Object value = rule.value();
            if (Rule.isFieldRef(value)) {
                String targetPath = ((String) value).substring(1);
                Resolution refRes = resolve(document, Path.parse(targetPath));
                if (refRes.kind != null || refRes.hits.isEmpty()) continue;
                Object siblingVal = refRes.hits.get(0).value;
                if (siblingVal == null || (siblingVal instanceof String s && s.isEmpty())) {
                    continue; // Skip if unset sibling
                }
                value = normalizeParamNumber(siblingVal);
            }
            if (RuleEvaluators.test(RuleEvaluators.require(rule.code()), hit.value, value)) continue;
            out.add(failure(entry.order, i, hit.path, rule,
                    RuleEvaluators.message(RuleEvaluators.require(rule.code()), hit.path, hit.value, value)));
        }
        return out;
    }

    private static Object normalizeParamNumber(Object val) {
        if (val instanceof Double d && d == Math.rint(d) && !d.isInfinite() && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
            return d.intValue();
        }
        if (val instanceof Float f && f == Math.rint(f) && !f.isInfinite() && f >= Integer.MIN_VALUE && f <= Integer.MAX_VALUE) {
            return f.intValue();
        }
        return val;
    }

    private static Finding failure(int order, int ruleIndex, String path, Rule rule, String message) {
        DocumentFinding.Severity severity = rule.warnOnly()
                ? DocumentFinding.Severity.WARNING
                : DocumentFinding.Severity.ERROR;
        return new Finding(order, ruleIndex,
                DocumentFinding.of(severity, DocumentFinding.Kind.RULE_FAILED, path, rule, message));
    }

    // --- resolution --------------------------------------------------------

    private record Hit(String path, Object value) {
    }

    

    private record Resolution(List<Hit> hits, DocumentFinding.Kind kind, String message) {
    }

    /**
     * Resolves a declared path against a document.
     *
     * <p>A shape problem is reported once for the whole declaration rather than
     * once per element it would have applied to, so an unreachable wildcard is one
     * finding and not a page of them.
     */
    private Resolution resolve(Object root, List<String> segments) {
        List<Hit> hits = new ArrayList<>();
        Failure failure = walk(root, segments, 0, new ArrayList<>(), hits);
        if (failure != null) {
            return new Resolution(List.of(), failure.kind,
                    "Value at '" + failure.at + "' is " + failure.explanation + ", so '"
                            + Path.join(segments) + "' cannot be read.");
        }
        return new Resolution(hits, null, null);
    }

    private record Failure(DocumentFinding.Kind kind, String at, String explanation) {
    }

    /**
     * Collects the values a path resolves to, and stops at the first shape problem.
     *
     * <p>A missing key resolves to {@code null}, and a {@code null} ancestor makes
     * everything beneath it {@code null}. A wildcard through a {@code null} yields
     * nothing, because a null list has no elements to index — which is why a null
     * is never a shape problem, only a missing value.
     */
    private Failure walk(Object current, List<String> segments, int index, List<String> consumed, List<Hit> out) {
        if (index == segments.size()) {
            out.add(new Hit(Path.join(consumed), current));
            return null;
        }
        String segment = segments.get(index);
        if (Path.isWildcard(segment)) {
            if (current == null) return null;
            if (!(current instanceof List<?> list)) {
                return new Failure(DocumentFinding.Kind.NOT_A_LIST, pathOf(consumed, segment), "not a list");
            }
            for (int i = 0; i < list.size(); i++) {
                consumed.add(String.valueOf(i));
                Failure nested = walk(list.get(i), segments, index + 1, consumed, out);
                consumed.remove(consumed.size() - 1);
                if (nested != null) return nested;
            }
            return null;
        }
        if (current != null && !(current instanceof Map<?, ?>)) {
            // A present scalar or list read as an object: the value exists but the
            // path does not, so this is a shape problem rather than a missing field.
            // The offending value is the one already reached, not the key we failed
            // to read from it.
            return new Failure(DocumentFinding.Kind.UNREACHABLE, pathOf(consumed), "not an object");
        }
        Object next = null;
        if (current instanceof Map<?, ?> map && map.containsKey(segment)) {
            next = map.get(segment);
        }
        consumed.add(segment);
        Failure nested = walk(next, segments, index + 1, consumed, out);
        consumed.remove(consumed.size() - 1);
        return nested;
    }

    /** Joins what has been consumed so far, naming the root rather than nothing. */
    private static String pathOf(List<String> consumed) {
        String joined = Path.join(consumed);
        return joined.isEmpty() ? "(document root)" : joined;
    }

    /** Joins consumed plus a failing segment (for shape problems). */
    private static String pathOf(List<String> consumed, String failingSegment) {
        List<String> parts = new ArrayList<>(consumed);
        parts.add(failingSegment);
        String joined = Path.join(parts);
        return joined.isEmpty() ? "(document root)" : joined;
    }

    // --- strict mode -------------------------------------------------------

    /**
     * Finds object keys that no declared path covers.
     *
     * <p>A key is covered when it either matches a declared leaf or sits on the
     * way to one: {@code server} is covered because {@code server.port} descends
     * through it, while {@code server.host} is not. A wildcard covers each
     * element's subtree at its own depth, so {@code servers.*.host} covers
     * {@code servers.0.host} but not {@code servers.0.port}. A field of type ANY
     * covers everything under it: {@code body: ANY} covers {@code body.id}.
     */
    private List<Finding> unknownFields(Object document) {
        List<Finding> out = new ArrayList<>();
        collectUnknown(document, new ArrayList<>(), out);
        return out;
    }

    private void collectUnknown(Object node, List<String> at, List<Finding> out) {
        // Sorted so both engines walk keys in the same order.
        if (node instanceof Map<?, ?> map) {
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet()) keys.add(String.valueOf(key));
            keys.sort(Comparator.naturalOrder());
            for (String key : keys) {
                if (!isCovered(at, key)) {
                    List<String> concrete = new ArrayList<>(at);
                    concrete.add(key);
                    out.add(new Finding(-1, -1, DocumentFinding.error(DocumentFinding.Kind.UNKNOWN_FIELD,
                            Path.join(concrete), "Field '" + Path.join(concrete) + "' is not allowed.")));
                }
                List<String> child = new ArrayList<>(at);
                child.add(key);
                collectUnknown(map.get(key), child, out);
            }
            return;
        }
        // Descend into lists so that a wildcard path's elements get their keys
        // checked too. A list index is never itself a field, so nothing is
        // reported for the list itself.
        if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                List<String> child = new ArrayList<>(at);
                child.add(String.valueOf(i));
                collectUnknown(list.get(i), child, out);
            }
        }
    }

    /** Whether some declared path covers the key {@code key} sitting at {@code at}. */
    private boolean isCovered(List<String> at, String key) {
        List<String> concrete = new ArrayList<>(at);
        concrete.add(key);
        for (Entry entry : entries) {
            if (coversLeaf(entry.segments(), concrete)) return true;
            if (coversContainer(entry.segments(), concrete)) return true;
            if (entry.field().type() == TargetType.ANY && coversSubtree(entry.segments(), concrete)) return true;
        }
        return false;
    }

    /**
     * The declared path lies above this concrete path. Only a field of type
     * {@link TargetType#ANY} covers what lies under it: it accepts any value, and so
     * whatever that value contains.
     */
    private static boolean coversSubtree(List<String> declared, List<String> concrete) {
        if (declared.size() >= concrete.size()) return false;
        for (int i = 0; i < declared.size(); i++) {
            if (!segmentMatches(declared.get(i), concrete.get(i))) return false;
        }
        return true;
    }

    /** The declared path is exactly this concrete path. */
    private static boolean coversLeaf(List<String> declared, List<String> concrete) {
        if (declared.size() != concrete.size()) return false;
        return matchesPrefix(declared, concrete);
    }

    /**
     * The declared path continues past this concrete path, so the concrete path is
     * a container rather than a leaf.
     */
    private static boolean coversContainer(List<String> declared, List<String> concrete) {
        if (declared.size() <= concrete.size()) return false;
        return matchesPrefix(declared, concrete);
    }

    /** Whether the first {@code concrete.size()} declared segments match. */
    private static boolean matchesPrefix(List<String> declared, List<String> concrete) {
        for (int i = 0; i < concrete.size(); i++) {
            if (i >= declared.size()) return false;
            if (!segmentMatches(declared.get(i), concrete.get(i))) return false;
        }
        return true;
    }

    private static boolean segmentMatches(String declared, String concrete) {
        return Path.isWildcard(declared) || declared.equals(concrete);
    }

    // --- ordering and conversion -------------------------------------------

    /**
     * A finding plus the two declaration coordinates that order it. Structural
     * findings carry {@code -1} for the rule position, since no rule is
     * responsible for them.
     */
    private record Finding(int order, int ruleIndex, DocumentFinding finding) {
    }

    /**
     * Total order, so both engines emit identical sequences: field declaration
     * index, then rule position, then path (element indices compared as numbers),
     * then kind.
     */
    private static final Comparator<Finding> ORDER = Comparator
            .comparingInt((Finding f) -> f.order() < 0 ? Integer.MAX_VALUE : f.order())
            .thenComparingInt(f -> f.ruleIndex() < 0 ? Integer.MAX_VALUE : f.ruleIndex())
            .thenComparing(f -> f.finding().path(), Path::comparePaths)
            .thenComparingInt(f -> f.finding().kind().ordinal());

    private static DocumentResult toResult(List<Finding> found) {
        List<DocumentFinding> errors = new ArrayList<>();
        List<DocumentFinding> warnings = new ArrayList<>();
        for (Finding f : found) {
            if (f.finding().isError()) errors.add(f.finding());
            else warnings.add(f.finding());
        }
        return new DocumentResult(errors.isEmpty(), errors, warnings);
    }

    }