package dev.regl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Locates files in the sibling {@code regl-spec} directory so the test suite
 * can assert that both engines agree with the canonical catalog.
 */
final class Spec {

    private Spec() {
    }

    /**
     * Resolves a path under {@code regl-spec}, walking up from the working
     * directory so the tests pass regardless of where the build is invoked.
     */
    static Path path(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("regl-spec").resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate regl-spec/" + relative);
    }

    static String read(String relative) {
        try {
            return Files.readString(path(relative));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read regl-spec/" + relative, e);
        }
    }

    /** Parses a spec file whose root is an object (e.g. {@code i18n/en.json}). */
    static Map<String, Object> readObject(String relative) {
        Object parsed = Json.parse(read(relative));
        if (!(parsed instanceof Map)) {
            throw new IllegalStateException(
                    "Expected a JSON object at the root of regl-spec/" + relative);
        }
        return Json.parseObject(read(relative));
    }

    /** Parses any spec file, returning a {@code List} or {@code Map} as appropriate. */
    static Object parse(String relative) {
        return Json.parse(read(relative));
    }

    /** Parses a spec file whose root is an array (e.g. the golden transcript). */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> readList(String relative) {
        Object parsed = Json.parse(read(relative));
        if (!(parsed instanceof List)) {
            throw new IllegalStateException(
                    "Expected a JSON array at the root of regl-spec/" + relative);
        }
        return (List<Map<String, Object>>) parsed;
    }

    /** Parses a named top-level array that sits beside {@code cases}, such as {@code typeCases}. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> readArray(String relative, String key) {
        Object raw = readObject(relative).get(key);
        if (!(raw instanceof List)) {
            throw new IllegalStateException(
                    "Expected a '" + key + "' array in regl-spec/" + relative);
        }
        return (List<Map<String, Object>>) raw;
    }

    static List<Map<String, Object>> readCases(String relative) {
        Object raw = readObject(relative).get("cases");
        if (!(raw instanceof List)) {
            throw new IllegalStateException("Expected a 'cases' array in regl-spec/" + relative);
        }
        return (List<Map<String, Object>>) raw;
    }
}
