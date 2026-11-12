package dev.regl.cli;

import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer for the CLI's machine output. Dependency-free on top of
 * the values a schema or a finding can carry: maps, lists, strings, numbers,
 * booleans, and {@code null}.
 *
 * <p>An integral floating-point number is written without a trailing
 * {@code .0} — {@code 65536.0} and {@code 65536} are the same value to a
 * consumer, and the TypeScript CLI writes the latter — so the canonical bytes
 * match across engines.
 */
final class JsonWriter {

    private final StringBuilder out = new StringBuilder();

    private JsonWriter() {
    }

    static String write(Object value) {
        JsonWriter w = new JsonWriter();
        w.value(value);
        return w.out.toString();
    }

    private void value(Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            string(s);
        } else if (value instanceof Boolean b) {
            out.append(b);
        } else if (value instanceof Double d) {
            if (Double.isFinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
                out.append((long) (double) d);
            } else if (Double.isFinite(d)) {
                out.append(d);
            } else {
                throw new IllegalArgumentException("Cannot write the non-finite number " + d + ".");
            }
        } else if (value instanceof Float f) {
            value((double) f);
        } else if (value instanceof Number n) {
            out.append(n);
        } else if (value instanceof Map<?, ?> map) {
            object(map);
        } else if (value instanceof List<?> list) {
            array(list);
        } else {
            throw new IllegalArgumentException("Cannot write a " + value.getClass().getSimpleName()
                    + " as JSON.");
        }
    }

    private void object(Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) out.append(", ");
            first = false;
            string(String.valueOf(entry.getKey()));
            out.append(": ");
            value(entry.getValue());
        }
        out.append('}');
    }

    private void array(List<?> list) {
        out.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) out.append(", ");
            first = false;
            value(item);
        }
        out.append(']');
    }

    private void string(String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
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
        out.append('"');
    }
}