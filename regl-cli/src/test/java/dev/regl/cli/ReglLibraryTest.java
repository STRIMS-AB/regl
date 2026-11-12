package dev.regl.cli;

import org.junit.jupiter.api.Test;

import java.util.Map;

import dev.regl.DocumentResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-memory entry point embedders use instead of the file-based command. */
class ReglLibraryTest {

    private static final String SCHEMA = """
            fields:
              payments.timeout: { type: INTEGER, rules: [notNull, { lessThan: 60000 }] }
            """;

    @Test
    void validatesADecodedTreeAgainstSchemaText() {
        SchemaFile.LoadedSchema schema = Regl.loadSchemaText(SCHEMA);

        DocumentResult ok = schema.schema().validate(Map.of("payments", Map.of("timeout", 8000)));
        assertTrue(ok.isValid());

        DocumentResult bad = schema.schema().validate(Map.of("payments", Map.of("timeout", 90000)));
        assertFalse(bad.isValid());
        assertEquals("payments.timeout", bad.errors().get(0).path());
    }

    @Test
    void anInvalidSchemaIsAnIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> Regl.loadSchemaText("- not\n- a schema\n"));
        assertThrows(IllegalArgumentException.class, () -> Regl.loadSchemaText(null));
    }
}
