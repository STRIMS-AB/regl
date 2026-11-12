package dev.regl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader behind {@link Documents#fromJson}. Dependency-free so the
 * zero-dependency guarantee of the validation core extends to decoding.
 *
 * <p>Supports the JSON the document model can express: objects, arrays, strings,
 * numbers, booleans, and {@code null}. Anything else — trailing content, a bad
 * escape, a malformed number, an unexpected end — is an
 * {@link IllegalArgumentException} with the offset, so a decode failure is a
 * load error (§13) rather than a crash.
 */
final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON text cannot be null.");
        Json p = new Json(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos != p.src.length()) {
            throw p.error("unexpected trailing content");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object.");
        }
        return (Map<String, Object>) value;
    }

    private Object readValue() {
        char c = peek();
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> readNumber();
            default -> throw error("expected a value");
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char esc = next();
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) {
                            throw error("truncated unicode escape");
                        }
                        String hex = src.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("bad unicode escape \\u" + hex);
                        }
                        pos += 4;
                    }
                    default -> throw error("bad escape \\" + esc);
                }
            } else if (c < 0x20) {
                throw error("raw control character in string");
            } else {
                sb.append(c);
            }
        }
    }

    /**
     * A JSON number: {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}.
     *
     * <p>An integral number decodes to an {@code Integer} or {@code Long}, a
     * fractional or exponent form to a {@code Double} — which is how the
     * conformance fixtures arrive, and matches what a YAML decoder produces for
     * the same value.
     */
    private Object readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (pos < src.length() && src.charAt(pos) == '0') {
            pos++;
        } else {
            requireDigits();
        }
        if (pos < src.length() && src.charAt(pos) == '.') {
            pos++;
            requireDigits();
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                pos++;
            }
            requireDigits();
        }
        String raw = src.substring(start, pos);
        if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
            try {
                return Double.valueOf(raw);
            } catch (NumberFormatException e) {
                throw error("malformed number '" + raw + "'");
            }
        }
        try {
            long asLong = Long.parseLong(raw);
            if (asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE) {
                return (int) asLong;
            }
            return asLong;
        } catch (NumberFormatException e) {
            throw error("malformed number '" + raw + "'");
        }
    }

    private void requireDigits() {
        int at = pos;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
            pos++;
        }
        if (pos == at) {
            throw error("expected a digit");
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!src.startsWith(literal, pos)) {
            throw error("expected '" + literal + "'");
        }
        pos += literal.length();
        return value;
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw error("expected '" + expected + "'");
        }
    }

    private char next() {
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        return src.charAt(pos++);
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at offset " + pos + ": " + snippet());
    }

    private String snippet() {
        if (src.isEmpty()) return "(empty)";
        int from = Math.max(0, pos - 10);
        int to = Math.min(src.length(), pos + 10);
        String around = src.substring(from, to).replace("\n", "\\n");
        return "'" + around + "'";
    }
}