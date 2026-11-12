package dev.regl.cli;

/**
 * The three document formats (§13 of the document spec) and how they are
 * detected: extension first, then {@code Content-Type} for a remote URL, and
 * only then a load error asking for {@code --format}.
 */
enum Format {
    JSON, YAML, PROPERTIES;

    /** The format of a {@code --format} argument, named in the error if unknown. */
    static Format parse(String name) {
        for (Format f : values()) {
            if (f.name().equalsIgnoreCase(name)) return f;
        }
        throw new IllegalArgumentException(
                "Unknown format '" + name + "'; expected json, yaml, or properties.");
    }

    /** The format a file extension names, or {@code null} if the extension is unknown. */
    static Format ofExtension(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".json")) return JSON;
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return YAML;
        if (lower.endsWith(".properties")) return PROPERTIES;
        return null;
    }

    /**
     * The format a {@code Content-Type} header names, or {@code null} if it is
     * missing or unknown. Parameters like {@code ; charset=utf-8} are ignored.
     */
    static Format ofContentType(String header) {
        if (header == null) return null;
        String media = header.split(";", 2)[0].trim().toLowerCase();
        return switch (media) {
            case "application/json" -> JSON;
            case "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml" -> YAML;
            case "text/x-java-properties" -> PROPERTIES;
            default -> null;
        };
    }
}