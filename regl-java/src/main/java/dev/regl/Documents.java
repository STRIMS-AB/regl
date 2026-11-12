package dev.regl;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The document decoders that need no dependencies (§13 of the document spec):
 * JSON and {@code .properties}. YAML needs a real parser and lives in the CLI
 * layer, keeping this core dependency-free.
 *
 * <p>Both decoders produce exactly the document model of {@link Schema}: a tree
 * of {@link Map}s, {@link List}s, strings, numbers, booleans, and {@code null}.
 * Anything that cannot be decoded is an {@link IllegalArgumentException} — a
 * load error, never a crash and never a best-effort partial parse.
 */
public final class Documents {

    private Documents() {
    }

    /**
     * Decodes a JSON document.
     *
     * <p>The root may be any JSON kind — an object, an array, or a scalar —
     * because a {@link Schema} decides what the root must be, not the decoder.
     * An integral number decodes to an {@code Integer} or {@code Long}, a
     * fractional or exponent form to a {@code Double}.
     *
     * @throws IllegalArgumentException if the text is not a single well-formed
     *                                  JSON document
     */
    public static Object fromJson(String json) {
        return Json.parse(json);
    }

    /**
     * Decodes a {@code .properties} document into the nested tree a schema's
     * dotted paths address.
     *
     * <p>{@code .properties} is flat strings, so a key is read as a path (§3):
     * <strong>unescaped dots expand into nested objects</strong> —
     * {@code server.port=8080} decodes to {@code { "server": { "port": "8080" } }}.
     * To keep a dot inside one key, escape it as in any path; remember that
     * properties unescaping applies first, so the backslash itself is doubled in
     * the file: {@code example\\.com=up} decodes to one key, {@code example.com}.
     *
     * <p>Properties semantics otherwise: {@code #} and {@code !} comments,
     * backslash line continuations, {@code \\uXXXX} escapes, and duplicate keys
     * last-wins.
     *
     * @throws IllegalArgumentException if the text is not loadable, a key is not
     *                                  a valid path, or a key makes one node
     *                                  both a leaf and an object
     *                                  ({@code a=1} then {@code a.b=2})
     */
    public static Map<String, Object> fromProperties(String properties) {
        if (properties == null) throw new IllegalArgumentException("Properties text cannot be null.");
        Properties flat = new Properties();
        try {
            flat.load(new StringReader(properties));
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid properties document: " + e.getMessage(), e);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        for (String key : flat.stringPropertyNames()) {
            List<String> segments = Path.parse(key);
            insert(root, segments, flat.getProperty(key), key);
        }
        return root;
    }

    /**
     * Walks a key's segments into the tree, creating objects on the way, and
     * sets the leaf.
     *
     * <p>A conflict — a segment that is already a leaf, or a leaf whose parent
     * path is already an object — is an error rather than a choice: expanding
     * one of the two would silently discard the other.
     */
    private static void insert(Map<String, Object> root, List<String> segments, String value, String key) {
        Map<String, Object> at = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            String segment = segments.get(i);
            Object existing = at.get(segment);
            if (existing == null) {
                Map<String, Object> nested = new LinkedHashMap<>();
                at.put(segment, nested);
                at = nested;
            } else if (existing instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) nested;
                at = cast;
            } else {
                throw new IllegalArgumentException(
                        "Properties key '" + key + "' conflicts with a sibling key: '" + segment
                                + "' is already a value, not a section.");
            }
        }
        String leaf = segments.get(segments.size() - 1);
        Object existing = at.get(leaf);
        if (existing instanceof Map<?, ?>) {
            throw new IllegalArgumentException(
                    "Properties key '" + key + "' conflicts with a section: '" + leaf
                            + "' is already a parent of other keys.");
        }
        at.put(leaf, value);
    }
}