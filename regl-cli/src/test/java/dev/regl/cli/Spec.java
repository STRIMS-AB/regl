package dev.regl.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Locates files in the sibling {@code regl-spec} directory so the CLI's tests
 * run the exact fixtures both engines share, the same way the core suites do.
 */
final class Spec {

    private Spec() {
    }

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

    /** Parsed with the core's own JSON decoder, so the fixture format is not re-implemented. */
    static Map<String, Object> readObject(String relative) {
        return dev.regl.Documents.fromJson(read(relative)) instanceof Map<?, ?> map
                ? cast(map) : throwNotObject();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> throwNotObject() {
        throw new IllegalStateException("Expected a JSON object.");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> readCases(String relative) {
        Object raw = readObject(relative).get("cases");
        if (!(raw instanceof List)) {
            throw new IllegalStateException("Expected a 'cases' array in regl-spec/" + relative);
        }
        return (List<Map<String, Object>>) raw;
    }
}