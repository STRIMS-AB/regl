package dev.regl.cli;

import java.io.PrintStream;

import dev.regl.DocumentResult;
import dev.regl.Schema;

/**
 * The CLI's engine, separate from {@link Main}'s argument parsing so the whole
 * command surface is callable and testable as a function.
 *
 * <p>Exit codes (§14 of the document spec): 0 the document is valid, 1 it is
 * invalid, 2 nothing was validated — a usage error, an unreadable schema or
 * document, an undecodable document, or an invalid schema. The distinction is
 * for pipelines: 1 means "the document is wrong", 2 means "regl could not do
 * the job".
 */
public final class Regl {

    private Regl() {
    }

    /**
     * Validates a document against a schema file.
     *
     * @param schemaUrl   the schema, as a filesystem path or an {@code http(s)} URL
     * @param documentUrl the document, as a filesystem path or an {@code http(s)} URL
     * @param format      the document format override from {@code --format}, or null
     * @param json        machine output via {@code --json}
     * @param quiet       no summary line via {@code --quiet}
     * @param color       ANSI colors, already resolved against the terminal
     * @param out         where findings print
     * @param err         where load errors print
     * @return the exit code: 0 valid, 1 invalid, 2 nothing validated
     */
    public static int validate(String schemaUrl, String documentUrl, String format,
                                boolean json, boolean quiet, boolean color,
                                PrintStream out, PrintStream err) {
        try {
            SchemaFile.LoadedSchema schema = SchemaFile.load(schemaUrl);
            DocumentResult result = validateDocument(schema, documentUrl, format);
            report(result, json, quiet, color, out);
            return result.isValid() ? 0 : 1;
        } catch (IllegalArgumentException e) {
            err.println("regl: " + e.getMessage());
            return 2;
        }
    }

    /**
     * Loads, lints, and prints a schema file — the same errors loading it for
     * validation would raise.
     *
     * @return 0 loaded, 2 the schema could not be read or is invalid
     */
    public static int schema(String schemaUrl, boolean json, boolean quiet, boolean color,
                             PrintStream out, PrintStream err) {
        try {
            SchemaFile.LoadedSchema schema = SchemaFile.load(schemaUrl);
            Reporter reporter = new Reporter(out, quiet, color);
            if (json) {
                out.println(reporter.schemaJson(schema));
            } else {
                reporter.schema(schema);
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println("regl: " + e.getMessage());
            return 2;
        }
    }

    /**
     * Loads a schema from its YAML or JSON text, for embedders that hold the schema
     * in memory rather than at a path or URL. Validate a decoded document with
     * {@code loaded.schema().validate(tree)}.
     *
     * @throws IllegalArgumentException if the text cannot be decoded or the schema is invalid
     */
    public static SchemaFile.LoadedSchema loadSchemaText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("The schema text cannot be null.");
        }
        return SchemaFile.fromTree(YamlDocuments.decodeSchema(Format.YAML, text));
    }

    private static DocumentResult validateDocument(SchemaFile.LoadedSchema schema,
                                                   String documentUrl, String format) {
        Urls.Loaded loaded = Urls.read(documentUrl);
        Format resolved = resolveFormat(documentUrl, loaded, format);
        Object document = YamlDocuments.decode(resolved, loaded.text());
        Schema built = schema.schema();
        return built.validate(document);
    }

    /**
     * Format detection (§13): the {@code --format} override, then the extension,
     * then the {@code Content-Type} of a remote response, then a load error with
     * the {@code --format} hint in the message.
     */
    private static Format resolveFormat(String documentUrl, Urls.Loaded loaded, String format) {
        if (format != null) {
            return Format.parse(format);
        }
        Format byExtension = Format.ofExtension(Urls.fileName(documentUrl));
        if (byExtension != null) return byExtension;
        Format byContentType = Format.ofContentType(loaded.contentType());
        if (byContentType != null) return byContentType;
        throw new IllegalArgumentException("Cannot determine the document's format from '"
                + documentUrl + "'; pass --format json, yaml, or properties.");
    }

    private static void report(DocumentResult result, boolean json, boolean quiet, boolean color,
                               PrintStream out) {
        if (json) {
            out.println(new Reporter(out, true, false).json(result));
            return;
        }
        new Reporter(out, quiet, color).human(result);
    }
}