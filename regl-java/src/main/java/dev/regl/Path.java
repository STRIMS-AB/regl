package dev.regl;

import java.util.ArrayList;
import java.util.List;

/**
 * Dot-separated paths, with {@code *} matching every element of a list and
 * {@code \} escaping a literal dot, backslash, or asterisk.
 *
 * <p>Package-private: paths are an implementation detail of {@link Schema} and
 * are not part of the public API surface.
 */
final class Path {

    private Path() {
    }

    /**
     * Splits a path into segments, removing escapes.
     *
     * @throws IllegalArgumentException if the path ends with a dangling escape
     */
    static List<String> parse(String path) {
        if (path == null) throw new IllegalArgumentException("Path cannot be null.");
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean escaping = false;
        boolean any = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (escaping) {
                current.append(c);
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '.') {
                out.add(current.toString());
                current.setLength(0);
                any = true;
            } else {
                current.append(c);
            }
        }
        if (escaping) {
            throw new IllegalArgumentException("Path '" + path + "' ends with a dangling escape.");
        }
        out.add(current.toString());
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Path cannot be empty.");
        }
        // A trailing separator leaves one empty segment, which is never a real key.
        if (!any && path.isEmpty()) {
            throw new IllegalArgumentException("Path cannot be empty.");
        }
        return List.copyOf(out);
    }

    static boolean isWildcard(String segment) {
        return "*".equals(segment);
    }

    /**
     * Whether a literal segment could be what a wildcard matched.
     *
     * <p>A wildcard only ever matches list indices, so a wildcard overlaps a
     * literal segment exactly when that literal is a non-negative integer.
     */
    static boolean isIndexLiteral(String segment) {
        if (segment.isEmpty() || segment.length() > 9) return false;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        // Reject leading zeros so "0" and "00" cannot both name an index.
        return segment.length() == 1 || segment.charAt(0) != '0';
    }

    /**
     * Whether two declared paths could resolve to the same concrete path.
     *
     * <p>Only equal-length paths can: each wildcard substitutes one index for
     * one segment, so the segment count is fixed by the declaration. Two paths
     * of different lengths address different values and are allowed to coexist.
     *
     * <p>Overlap is a schema construction error, decided without looking at any
     * document.
     */
    static boolean overlaps(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            String x = a.get(i);
            String y = b.get(i);
            if (x.equals(y)) continue;
            // A wildcard can only ever stand for a list index, so it overlaps a
            // literal segment exactly when that literal is an index.
            boolean compatible = (isWildcard(x) && isIndexLiteral(y))
                    || (isWildcard(y) && isIndexLiteral(x));
            if (!compatible) return false;
        }
        return true;
    }

    /**
     * Joins segments back into path syntax, re-escaping each special character.
     *
     * <p>This is how a concrete path is rendered: the declared spelling with each
     * wildcard replaced by its index (§5 of the document spec), so a key that
     * contains a dot stays one segment rather than splitting on re-parse.
     */
    static String join(List<String> segments) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) out.append('.');
            escapeInto(out, segments.get(i));
        }
        return out.toString();
    }

    private static void escapeInto(StringBuilder out, String segment) {
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '\\' || c == '.' || c == '*') out.append('\\');
            out.append(c);
        }
    }

    /**
     * Compares two paths segment by segment, with two segments that are both
     * list indices compared numerically.
     *
     * <p>Plain string order would sort {@code ports.10} before {@code ports.2},
     * which is not the element order a document was read in. Both engines order
     * findings by element index, so both must compare indices as numbers.
     */
    static int comparePaths(String a, String b) {
        List<String> as = parse(a);
        List<String> bs = parse(b);
        int shared = Math.min(as.size(), bs.size());
        for (int i = 0; i < shared; i++) {
            String x = as.get(i);
            String y = bs.get(i);
            if (x.equals(y)) continue;
            Long xi = parseIndex(x);
            Long yi = parseIndex(y);
            if (xi != null && yi != null) return Long.compare(xi, yi);
            return x.compareTo(y);
        }
        return Integer.compare(as.size(), bs.size());
    }

    /** A segment of digits only, short enough to compare without overflow. */
    private static Long parseIndex(String segment) {
        if (segment.isEmpty() || segment.length() > 9) return null;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        return Long.parseLong(segment);
    }
}