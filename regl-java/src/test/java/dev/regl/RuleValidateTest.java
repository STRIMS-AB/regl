package dev.regl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleValidateTest {

    @Nested
    @DisplayName("passing rules")
    class Passing {

        @Test
        @DisplayName("a passing rule yields a valid result with no errors or warnings")
        void validWhenRulePasses() {
            ValidationResult result = new Rule(RuleType.MIN_LENGTH, 8).validate("password", "longenough");

            assertTrue(result.isValid());
            assertTrue(result.errors().isEmpty());
            assertTrue(result.warnings().isEmpty());
            assertFalse(result.hasWarnings());
        }
    }

    @Nested
    @DisplayName("failing rules")
    class Failing {

        @Test
        @DisplayName("a failing rule yields one error carrying field, rule and value")
        void invalidWhenRuleFails() {
            Rule rule = new Rule(RuleType.MIN_LENGTH, 8);
            ValidationResult result = rule.validate("password", "secret");

            assertFalse(result.isValid());
            assertEquals(1, result.errors().size());

            ValidationError error = result.errors().get(0);
            assertEquals("password", error.field());
            assertEquals("minLength", error.rule());
            assertEquals(8, error.value());
            assertEquals("Minimum length is 8, but got 6.", error.message());
        }

        @Test
        @DisplayName("a failing warn-only rule is reported as a warning and stays valid")
        void warnOnlyDoesNotInvalidate() {
            Rule rule = new Rule(RuleType.MIN_LENGTH, 8).withWarnOnly(true);
            ValidationResult result = rule.validate("password", "secret");

            assertTrue(result.isValid(), "warn-only failure must not invalidate the result");
            assertTrue(result.errors().isEmpty());
            assertEquals(1, result.warnings().size());

            ValidationError warning = result.warnings().get(0);
            assertEquals("password", warning.field());
            assertEquals("minLength", warning.rule());
            assertTrue(result.hasWarnings());
        }
    }

    @Nested
    @DisplayName("parameter handling")
    class Parameters {

        @Test
        @DisplayName("a parameterless rule validates with no value")
        void parameterless() {
            assertTrue(new Rule(RuleType.EMAIL).validate("email", "a@b.co").isValid());
            assertFalse(new Rule(RuleType.EMAIL).validate("email", "nope").isValid());
        }

        @Test
        @DisplayName("a parameterized rule rejects a missing value at construction")
        void rejectsMissingParam() {
            assertThrows(IllegalArgumentException.class, () -> new Rule(RuleType.MIN_LENGTH, null));
        }

        @Test
        @DisplayName("a parameterless rule rejects a supplied value at construction")
        void rejectsUnexpectedParam() {
            assertThrows(IllegalArgumentException.class, () -> new Rule(RuleType.EMAIL, "irrelevant"));
        }

        @Test
        @DisplayName("a value of the wrong type is rejected")
        void rejectsWrongParamType() {
            assertThrows(IllegalArgumentException.class, () -> new Rule(RuleType.PATTERN, 42));
        }

        @Test
        @DisplayName("range accepts an unbounded side")
        void rangeAcceptsUnbounded() {
            Rule minOnly = new Rule(RuleType.RANGE, new Range(2, null));
            assertTrue(minOnly.validate("tags", List.of("a", "b")).isValid());
            assertFalse(minOnly.validate("tags", List.of("a")).isValid());
        }
    }

    @Nested
    @DisplayName("custom rules")
    class Custom {

        @Test
        @DisplayName("a custom rule validates through its registered evaluator")
        void customRuleValidates() {
            RuleEvaluators.register(new RuleEvaluator<Integer>() {
                public String code() { return "dayOfMonth"; }
                public String name() { return "Day of Month"; }
                public String description() { return "Requires a number between 1 and {value}."; }
                public Class<Integer> parameterType() { return Integer.class; }
                public TargetType targetType() { return TargetType.NUMBER; }
                public boolean test(Object value, Integer maxDay) {
                    return !(value instanceof Number n)
                            || (n.intValue() >= 1 && n.intValue() <= maxDay);
                }
                public String message(String field, Object value, Integer maxDay) {
                    return "Field '" + field + "' must be a day between 1 and " + maxDay + ".";
                }
            });
            try {
                Rule rule = Rule.custom("dayOfMonth", 31);

                assertTrue(rule.validate("day", 15).isValid());
                assertFalse(rule.validate("day", 32).isValid());
            } finally {
                RuleEvaluators.unregister("dayOfMonth");
            }
        }

        @Test
        @DisplayName("validating an unregistered code throws")
        void unknownCodeThrows() {
            Rule rule = Rule.custom("noSuchRule", null);
            assertThrows(IllegalArgumentException.class, () -> rule.validate("f", "v"));
        }
    }

    @Nested
    @DisplayName("ValidationResult")
    class Results {

        @Test
        @DisplayName("valid() is empty and valid")
        void validResult() {
            ValidationResult result = ValidationResult.valid();
            assertTrue(result.isValid());
            assertTrue(result.errors().isEmpty());
            assertTrue(result.warnings().isEmpty());
        }

        @Test
        @DisplayName("of(errors) is invalid when non-empty")
        void ofErrors() {
            ValidationError error = new ValidationError("f", "minLength", 3, "msg");
            assertFalse(ValidationResult.of(List.of(error)).isValid());
            assertTrue(ValidationResult.of(List.of()).isValid());
        }

        @Test
        @DisplayName("warnings alone keep the result valid")
        void warningsStayValid() {
            ValidationError warning = new ValidationError("f", "minLength", 3, "msg");
            ValidationResult result = ValidationResult.of(List.of(), List.of(warning));
            assertTrue(result.isValid());
            assertTrue(result.hasWarnings());
        }

        @Test
        @DisplayName("a null error or warning list is normalised to empty")
        void nullsNormalised() {
            ValidationResult result = new ValidationResult(true, null, null);
            assertTrue(result.errors().isEmpty());
            assertTrue(result.warnings().isEmpty());
        }
    }

    @Nested
    @DisplayName("ValidationError")
    class Errors {

        @Test
        @DisplayName("the three-argument form leaves value null")
        void paramDefaultsToNull() {
            ValidationError error = new ValidationError("f", "minLength", "msg");
            assertEquals("f", error.field());
            assertEquals("minLength", error.rule());
            assertNull(error.value());
            assertEquals("msg", error.message());
        }
    }
}
