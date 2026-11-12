package dev.regl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static dev.regl.Rule.*;
import static dev.regl.Rules.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the terse rule-list form: {@code number().with(notNull(), integer(),
 * lessThan(65536))}.
 *
 * <p>Two claims are checked here, because both fail silently otherwise. The
 * factories must cover the catalog exactly — a missing one makes a rule
 * unspellable in the terse form, and an extra one is a rule the catalog does not
 * describe. And the factories must build what their names say, so a factory
 * cannot quietly return the wrong rule and still satisfy the coverage check.
 */
class RuleFactoriesTest {

    private static List<String> catalogIds() throws Exception {
        Path path = Path.of("..", "regl-spec", "catalog.json");
        String json = Files.readString(path);
        List<String> ids = new ArrayList<>();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([a-zA-Z]+)\"").matcher(json);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /** Static no-arg factory methods on {@link Rule} returning a {@link Rule}. */
    private static List<Method> factories() {
        List<Method> out = new ArrayList<>();
        for (Method method : Rule.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) continue;
            if (!Modifier.isPublic(method.getModifiers())) continue;
            if (!method.getReturnType().equals(Rule.class)) continue;
            if (method.getName().equals("custom")) continue;
            out.add(method);
        }
        return out;
    }

    private static String catalogName(String methodName) {
        return methodName.equals("enumOf") ? "enum" : methodName;
    }

    @Test
    @DisplayName("a factory exists for every catalog rule, and no extra factory exists")
    void factoriesMatchCatalog() throws Exception {
        Set<String> fromCatalog = new LinkedHashSet<>(catalogIds());
        Set<String> fromFactories = new LinkedHashSet<>();
        for (Method method : factories()) {
            fromFactories.add(catalogName(method.getName()));
        }
        assertEquals(fromCatalog, fromFactories,
                "Rule's static factories must be exactly the catalog's rule ids");
    }

    @Test
    @DisplayName("each factory builds the rule it is named after")
    void factoriesBuildTheirOwnRule() throws Exception {
        for (Method method : factories()) {
            String expected = catalogName(method.getName());
            // Flag rules take no argument; parameterized ones get a placeholder
            // chosen to satisfy the catalog's declared parameter type.
            Object arg = switch (expected) {
                case "range" -> new Range(1, 2);
                case "enum" -> new String[] { "a", "b" };
                default -> {
                    ParameterType declared = RuleType.fromCodeOrNull(expected).parameterType();
                    yield declared == ParameterType.NUMBER ? 1
                        : declared == ParameterType.STRING ? "x"
                        : null;
                }
            };
            Object[] args = arg == null ? new Object[0] : new Object[] { arg };
            Rule built = (Rule) method.invoke(null, args);
            assertEquals(expected, built.code(),
                    method.getName() + "() must build rule '" + expected + "'");
        }
    }

    @Test
    @DisplayName("with() appends in order, after any rule already on the builder")
    void withAppendsInOrder() {
        Field<?> field = string().minLength(2).with(notNull(), maxLength(8));
        assertEquals(List.of("minLength", "notNull", "maxLength"),
                field.rules().stream().map(Rule::code).toList());
    }

    @Test
    @DisplayName("with() gives the same validation as the formal constructor")
    void withRejectsTheWrongTargetType() {
        IllegalArgumentException fromWith = assertThrows(IllegalArgumentException.class,
                () -> number().with(email()));
        IllegalArgumentException fromOf = assertThrows(IllegalArgumentException.class,
                () -> Field.of(TargetType.NUMBER, List.of(email())));
        assertEquals(fromOf.getMessage(), fromWith.getMessage(),
                "with() and Field.of(...) must reject a mis-targeted rule identically");
    }

    @Test
    @DisplayName("with() requires at least one rule")
    void withRejectsAnEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> number().with());
    }

    @Test
    @DisplayName("with() rejects a null rule rather than appending one")
    void withRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> number().with((Rule) null));
    }

    @Test
    @DisplayName("with() keeps the builder's own type, so the field validates")
    void withKeepsTypeForValidation() {
        Field<?> field = integer().with(notNull(), lessThan(65536));
        assertTrue(field.requiresValue());
        assertEquals(TargetType.INTEGER, field.type());
    }
}
