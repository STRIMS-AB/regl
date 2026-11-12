package dev.regl;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static dev.regl.Rules.string;
import static dev.regl.Rules.number;

public class PerformanceTest {

    @Test
    public void testParallelValidationPerformance() {
        System.out.println("Generating large schema and document for performance testing...");
        Schema schema = Schema.create();
        Map<String, Object> document = new HashMap<>();

        int numFields = 20_000;
        
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < numFields; i++) {
            String path = "field_" + i;
            if (i % 2 == 0) {
                schema = schema.field(path, string().notNull().minLength(2).maxLength(50));
                document.put(path, "test_string_" + i);
            } else {
                schema = schema.field(path, number().notNull().positive().lessThan(1000000));
                document.put(path, i);
            }
        }
        long t1 = System.currentTimeMillis();
        System.out.println("Setup took " + (t1 - t0) + " ms");

        System.out.println("Validating...");
        long v0 = System.currentTimeMillis();
        DocumentResult result = schema.validate(document);
        long v1 = System.currentTimeMillis();

        System.out.println("Validation of " + numFields + " fields took " + (v1 - v0) + " ms");
        assert result.isValid();
    }
}
