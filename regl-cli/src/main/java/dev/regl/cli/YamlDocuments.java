package dev.regl.cli;

import java.util.ArrayList;
import java.util.List;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import dev.regl.Documents;

final class YamlDocuments {

    private YamlDocuments() {
    }

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** Schema decoding rejects a repeated object key (§12), which a permissive decoder would silently keep-last. */
    private static final ObjectMapper STRICT_JSON_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    static Object decode(String text) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        List<Object> documents = new ArrayList<>();
        try {
            for (Object document : yaml.loadAll(text)) {
                documents.add(document);
                if (documents.size() > 1) break;
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid YAML document: " + e.getMessage(), e);
        }
        if (documents.size() != 1) {
            throw new IllegalArgumentException(
                    "Expected exactly one YAML document, found " + documents.size() + ".");
        }
        return documents.get(0);
    }

    static Object decode(Format format, String text) {
        try {
            return switch (format) {
                case JSON -> JSON_MAPPER.readValue(text, new TypeReference<Object>() {});
                case YAML -> decode(text);
                case PROPERTIES -> Documents.fromProperties(text);
            };
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid document: " + e.getMessage(), e);
        }
    }

    /**
     * Decodes a schema file: like a document, but a repeated object key is a load
     * error rather than a silent keep-last, because {@code fields} is keyed by
     * path and a duplicate key is a duplicate path (§12).
     */
    static Object decodeSchema(Format format, String text) {
        try {
            return switch (format) {
                case JSON -> STRICT_JSON_MAPPER.readValue(text, new TypeReference<Object>() {});
                case YAML -> decodeStrictYaml(text);
                case PROPERTIES -> Documents.fromProperties(text);
            };
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid schema: " + e.getMessage(), e);
        }
    }

    private static Object decodeStrictYaml(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        List<Object> documents = new ArrayList<>();
        try {
            for (Object document : yaml.loadAll(text)) {
                documents.add(document);
                if (documents.size() > 1) break;
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid YAML schema: " + e.getMessage(), e);
        }
        if (documents.size() != 1) {
            throw new IllegalArgumentException(
                    "Expected exactly one YAML document, found " + documents.size() + ".");
        }
        return documents.get(0);
    }
}