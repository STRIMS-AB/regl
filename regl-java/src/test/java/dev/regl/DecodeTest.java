package dev.regl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shared decode fixtures in {@code regl-spec/conformance/decode-cases.json}
 * against the zero-dependency decoders of the core: JSON and {@code .properties}.
 *
 * <p>The YAML cases in the same file are asserted by {@code regl-cli}, which owns
 * the real YAML parser; the file is shared precisely so the two engines' decoders
 * cannot disagree when the TypeScript CLI arrives.
 */
class DecodeTest {

    @SuppressWarnings("unchecked")
    private static Stream<Object[]> cases() {
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> c : Spec.readCases("conformance/decode-cases.json")) {
            String format = String.valueOf(c.get("format"));
            if (!format.equals("json") && !format.equals("properties")) continue;
            rows.add(new Object[]{
                    String.valueOf(c.get("name")),
                    format,
                    String.valueOf(c.get("text")),
                    c.containsKey("error") ? Boolean.TRUE : c.get("expected")
            });
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{1} / {0}")
    @MethodSource("cases")
    @DisplayName("the zero-dependency decoders match the shared fixtures")
    void matchesFixture(String name, String format, String text, Object expected) {
        if (expected == Boolean.TRUE) {
            assertThrows(IllegalArgumentException.class, () -> decode(format, text),
                    "expected '" + name + "' to fail decoding");
            return;
        }
        assertEquals(normalize(expected), normalize(decode(format, text)),
                "decoded tree for '" + name + "'");
    }

    private static Object decode(String format, String text) {
        return format.equals("json") ? Documents.fromJson(text) : Documents.fromProperties(text);
    }

    /**
     * Canonicalizes numbers so a fixture's {@code 8080} equals the decoder's
     * {@code Integer 8080}, {@code Long}, or {@code Double} form of the same
     * value. The document model has one number kind; the engines may hold it in
     * more than one Java type.
     */
    private static Object normalize(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return (long) d;
            }
            return d;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DecodeTest::normalize).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), normalize(v)));
            return out;
        }
        return value;
    }

    @Nested
    @DisplayName("properties key expansion")
    class PropertiesKeys {

        @Test
        @DisplayName("a flat key does not expand")
        void flat() {
            assertEquals(Map.of("name", "regl"), Documents.fromProperties("name=regl\n"));
        }

        @Test
        @DisplayName("dotted keys expand to nested objects, so a schema addresses them directly")
        void expands() {
            assertEquals(Map.of("server", Map.of("port", "8080")),
                    Documents.fromProperties("server.port=8080\n"));
        }

        @Test
        @DisplayName("an escaped dot stays one key, with the properties escape applied first")
        void escapedDot() {
            assertEquals(Map.of("example.com", "up"),
                    Documents.fromProperties("example\\\\.com=up\n"));
        }

        @Test
        @DisplayName("a leaf cannot become a section later")
        void leafThenSection() {
            assertThrows(IllegalArgumentException.class,
                    () -> Documents.fromProperties("a=1\na.b=2\n"));
        }

        @Test
        @DisplayName("a section cannot become a leaf later")
        void sectionThenLeaf() {
            assertThrows(IllegalArgumentException.class,
                    () -> Documents.fromProperties("a.b=1\na=2\n"));
        }

        @Test
        @DisplayName("conflicts are detected in any key order")
        void orderIndependent() {
            assertThrows(IllegalArgumentException.class,
                    () -> Documents.fromProperties("z.a=1\nz=2\n"));
        }

        @Test
        @DisplayName("an empty key is an invalid path")
        void emptyKey() {
            assertThrows(IllegalArgumentException.class,
                    () -> Documents.fromProperties("=value\n"));
        }

        @Test
        @DisplayName("every value is a string, including empty ones")
        void valuesAreStrings() {
            Map<String, Object> decoded = Documents.fromProperties("port=8080\nempty=\n");
            assertTrue(decoded.get("port") instanceof String);
            assertEquals("", decoded.get("empty"));
        }
    }

    @Nested
    @DisplayName("json robustness")
    class JsonRobustness {

        @Test
        @DisplayName("trailing content is rejected, not ignored")
        void trailing() {
            assertThrows(IllegalArgumentException.class, () -> Documents.fromJson("{} trailing"));
        }

        @Test
        @DisplayName("a truncated document names where it stopped")
        void truncated() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Documents.fromJson("{\"name\": "));
            assertTrue(e.getMessage().contains("offset"), e.getMessage());
        }

        @Test
        @DisplayName("a scalar root is a document, not an error")
        void scalarRoot() {
            assertEquals(8080, Documents.fromJson("8080"));
        }

        @Test
        @DisplayName("NaN and Infinity are not JSON numbers")
        void nonFinite() {
            assertThrows(IllegalArgumentException.class, () -> Documents.fromJson("{\"a\":NaN}"));
            assertThrows(IllegalArgumentException.class, () -> Documents.fromJson("{\"a\":Infinity}"));
        }
    }
}