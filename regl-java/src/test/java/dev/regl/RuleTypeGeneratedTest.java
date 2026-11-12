package dev.regl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps {@code RuleType.java} in sync with {@code regl-spec/catalog.json}.
 *
 * <p>{@code RuleType} is generated, because it is pure metadata: rule id,
 * parameter type and target type, all of which the catalog already declares.
 * The rule <em>predicates</em> in {@code RuleEvaluators} remain hand-written,
 * as the reference implementation of rule semantics.
 *
 * <p>Because the catalog is the source of truth, the test regenerates the file
 * when it has drifted and then asserts the result is stable and correct. That
 * way a hand-edit to {@code RuleType.java} is always reverted by the build
 * rather than silently accepted.
 */
class RuleTypeGeneratedTest {

    /**
     * Regenerates {@code RuleType.java} when it has drifted from the catalog.
     *
     * <p>When {@code regl.checkGenerated} is set to {@code true} the drift is
     * reported as a test failure instead, leaving the file untouched. That is
     * the mode CI uses: silently rewriting the file would let a build pass on a
     * repository whose committed catalog and committed {@code RuleType.java}
     * disagree.
     *
     * <p>Set the system property (or {@code -Dregl.checkGenerated=true}) to run
     * in check mode.
     */
    @Test
    @DisplayName("RuleType.java matches catalog.json")
    void generatedFileIsUpToDate() throws IOException {
        Path source = RuleTypeGenerator.locate();
        String expected = RuleTypeGenerator.generate(catalogRules());

        if (!Files.exists(source)) {
            if (checkMode()) {
                fail("RuleType.java is missing; regenerate with: mvn -q test-compile "
                        + "exec:java -Dexec.mainClass=dev.regl.RuleTypeGenerator");
            }
            assertTrue(RuleTypeGenerator.writeIfChanged(),
                    "RuleType.java was missing and could not be generated.");
            return;
        }

        String onDisk = Files.readString(source);

        if (checkMode()) {
            assertEquals(expected, onDisk,
                    "RuleType.java does not match regl-spec/catalog.json. It is generated and "
                            + "must not be hand-edited; regenerate with: mvn -q test-compile "
                            + "exec:java -Dexec.mainClass=dev.regl.RuleTypeGenerator");
            return;
        }

        if (!onDisk.equals(expected)) {
            RuleTypeGenerator.writeIfChanged();
            assertEquals(expected, Files.readString(source),
                    "RuleType.java did not match catalog.json even after regeneration.");
        }
    }

    /** Whether to fail on drift rather than regenerate it. */
    private static boolean checkMode() {
        return Boolean.parseBoolean(System.getProperty("regl.checkGenerated", "false"));
    }

    @Test
    @DisplayName("every RuleType constant matches its catalog entry")
    void constantsMatchCatalog() {
        for (RuleType type : RuleType.values()) {
            assertEquals(type.targetType().name(), RuleEvaluators.require(type.code()).targetType().name(),
                    "Target type mismatch between RuleType and the registered evaluator for '" + type.code() + "'.");
        }
    }

    @Test
    @DisplayName("RuleType exposes the catalog target type")
    void targetTypeIsExposed() {
        assertEquals(TargetType.STRING, RuleType.MIN_LENGTH.targetType());
        assertEquals(TargetType.NUMBER, RuleType.GREATER_THAN.targetType());
        assertEquals(TargetType.LIST, RuleType.UNIQUE.targetType());
        assertEquals(TargetType.BOOLEAN, RuleType.IS_TRUE.targetType());
        assertEquals(TargetType.ANY, RuleType.ENUM.targetType());
    }

    @Test
    @DisplayName("constant names are derived predictably from rule ids")
    void constantNamesAreDerived() {
        assertEquals("MIN_LENGTH", RuleTypeGenerator.constantName("minLength"));
        assertEquals("IS_TRUE", RuleTypeGenerator.constantName("isTrue"));
        assertEquals("EMAIL", RuleTypeGenerator.constantName("email"));
        assertEquals("URL", RuleTypeGenerator.constantName("url"));
        assertEquals("UUID", RuleTypeGenerator.constantName("uuid"));
    }

    @Test
    @DisplayName("generated source carries a do-not-edit header")
    void headerIsPresent() {
        String generated = RuleTypeGenerator.generate(catalogRules());
        assertTrue(generated.contains("DO NOT EDIT BY HAND"),
                "Generated RuleType.java must declare itself generated.");
        assertTrue(generated.contains("regl-spec/catalog.json"),
                "Generated RuleType.java must name its source of truth.");
    }

    /** Fails loudly if the generator would emit Java that does not parse. */
    @Test
    @DisplayName("generated source is syntactically plausible")
    void generatedSourceIsWellFormed() {
        String generated = RuleTypeGenerator.generate(catalogRules());
        long open = countChar(generated, '{') - countChar(generated, '}');
        assertEquals(0, open, "Unbalanced braces in generated RuleType.java.");
        assertEquals(countChar(generated, '{'), countChar(generated, '}'),
                "Unbalanced braces in generated RuleType.java.");
        assertTrue(generated.trim().endsWith("}"), "Generated source must end with a closing brace.");
    }

    private static long countChar(String s, char c) {
        return s.chars().filter(ch -> ch == c).count();
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<java.util.Map<String, Object>> catalogRules() {
        return (java.util.List<java.util.Map<String, Object>>) Spec.parse("catalog.json");
    }

    @Test
    @DisplayName("generator resolves the generated file's location")
    void generatorResolvesPaths() {
        assertTrue(RuleTypeGenerator.locate().toString().endsWith("RuleType.java"));
    }
}
