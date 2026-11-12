package dev.regl.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import dev.regl.Documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runs every case in the shared decode fixtures — including the YAML cases the
 * zero-dependency core cannot run — against the CLI's decoders.
 *
 * <p>YAML is decoded here because it needs a real parser; JSON and
 * {@code .properties} are decoded by the core. The one fixture file keeps the
 * three decoders honest against each other, and against the TypeScript decoders
 * when that CLI arrives.
 */
class DecodeCasesTest {

    private static Stream<Object[]> cases() {
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> c : Spec.readCases("conformance/decode-cases.json")) {
            rows.add(new Object[]{
                    String.valueOf(c.get("name")),
                    String.valueOf(c.get("format")),
                    String.valueOf(c.get("text")),
                    c.containsKey("error") ? Boolean.TRUE : c.get("expected")
            });
        }
        return rows.stream();
    }

    @ParameterizedTest(name = "{1} / {0}")
    @MethodSource("cases")
    @DisplayName("the CLI's decoders match the shared fixtures")
    void matchesFixture(String name, String format, String text, Object expected) {
        Format f = Format.parse(format);
        if (expected == Boolean.TRUE) {
            assertThrows(IllegalArgumentException.class, () -> YamlDocuments.decode(f, text),
                    "expected '" + name + "' to fail decoding");
            return;
        }
        assertEquals(normalize(expected), normalize(YamlDocuments.decode(f, text)),
                "decoded tree for '" + name + "'");
    }

    /** Numbers compare by value: the model has one number kind, engines may hold it in several types. */
    private static Object normalize(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return (long) d;
            }
            return d;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DecodeCasesTest::normalize).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), normalize(v)));
            return out;
        }
        return value;
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a YAML stream with several documents is rejected, not guessed")
    void severalDocuments() {
        assertThrows(IllegalArgumentException.class,
                () -> YamlDocuments.decode(Format.YAML, "a: 1\n---\nb: 2\n"));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("an empty YAML stream is rejected, not treated as a null document")
    void emptyStream() {
        assertThrows(IllegalArgumentException.class, () -> YamlDocuments.decode(Format.YAML, ""));
        assertThrows(IllegalArgumentException.class, () -> YamlDocuments.decode(Format.YAML, "# only a comment\n"));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("a YAML document whose root is null decodes to null, like a JSON null root")
    void nullRoot() {
        assertEquals(null, YamlDocuments.decode(Format.YAML, "null\n"));
        assertEquals(null, Documents.fromJson("null"));
    }
}