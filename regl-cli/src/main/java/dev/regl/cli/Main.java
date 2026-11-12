package dev.regl.cli;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The {@code regl} command line (§14 of the document spec):
 *
 * <pre>
 *   regl &lt;schema&gt; &lt;document&gt;            validate a document against a schema file
 *   regl validate &lt;schema&gt; &lt;document&gt;   the same, explicit
 *   regl schema &lt;schema&gt;                load and lint a schema, print its fields
 * </pre>
 *
 * <p>{@code <schema>} and {@code <document>} are each a filesystem path or an
 * {@code http(s)} URL. Exit codes: 0 the document is valid, 1 it is invalid,
 * 2 nothing was validated (usage, load, decode, or schema errors — picocli's
 * usage errors are 2 as well).
 */
@Command(
        name = "regl",
        mixinStandardHelpOptions = true,
        version = "regl 0.1.0",
        description = "Validate JSON, YAML, and .properties documents against a schema file.",
        subcommands = {
                Main.ValidateCommand.class,
                Main.SchemaCommand.class
        }
)
public final class Main implements Callable<Integer> {

    /** Two bare arguments are the common case, so they are the default command. */
    @Parameters(index = "0", arity = "0..1", description = "The schema, as a path or an http(s) URL.")
    private String schema;

    @Parameters(index = "1", arity = "0..1", description = "The document, as a path or an http(s) URL.")
    private String document;

    @Option(names = "--format", description = "Document format override: json, yaml, or properties.")
    private String format;

    @Option(names = "--json", description = "Machine-readable output.")
    private boolean json;

    @Option(names = {"-q", "--quiet"}, description = "No summary line; findings still print.")
    private boolean quiet;

    @Option(names = "--no-color", description = "Plain output; color is used only on a terminal anyway.")
    private boolean noColor;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        if (schema != null && document != null) {
            return Regl.validate(schema, document, format, json, quiet, color(), System.out, System.err);
        }
        CommandLine.usage(this, System.out);
        return 0;
    }

    private boolean color() {
        return !noColor && System.console() != null;
    }

    @Command(name = "validate", description = "Validate a document against a schema file.")
    public static final class ValidateCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "The schema, as a path or an http(s) URL.")
        private String schema;

        @Parameters(index = "1", description = "The document, as a path or an http(s) URL.")
        private String document;

        @Option(names = "--format", description = "Document format override: json, yaml, or properties.")
        private String format;

        @Option(names = "--json", description = "Machine-readable output.")
        private boolean json;

        @Option(names = {"-q", "--quiet"}, description = "No summary line; findings still print.")
        private boolean quiet;

        @Option(names = "--no-color", description = "Plain output.")
        private boolean noColor;

        @Override
        public Integer call() {
            return Regl.validate(schema, document, format, json, quiet, color(), System.out, System.err);
        }

        private boolean color() {
            return !noColor && System.console() != null;
        }
    }

    @Command(name = "schema", description = "Load and lint a schema file, print its fields.")
    public static final class SchemaCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "The schema, as a path or an http(s) URL.")
        private String schema;

        @Option(names = "--json", description = "Print the normalized schema as JSON.")
        private boolean json;

        @Option(names = {"-q", "--quiet"}, description = "No summary line.")
        private boolean quiet;

        @Option(names = "--no-color", description = "Plain output.")
        private boolean noColor;

        @Override
        public Integer call() {
            return Regl.schema(schema, json, quiet, color(), System.out, System.err);
        }

        private boolean color() {
            return !noColor && System.console() != null;
        }
    }
}