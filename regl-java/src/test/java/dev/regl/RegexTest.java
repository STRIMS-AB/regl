package dev.regl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

class RegexTest {

    @Test
    @DisplayName("a catastrophic pattern fails closed within the time budget instead of hanging")
    void catastrophicBacktrackingTimesOut() {
        String input = "a".repeat(40) + "!";
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertFalse(Regex.find("^(a+)+$", input)));
    }

    @Test
    @DisplayName("a pattern over the length limit is rejected")
    void overlongPatternRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Regex.find("a".repeat(Regex.MAX_PATTERN_LENGTH + 1), "a"));
    }

    @Test
    @DisplayName("an unescaped $ outside a class anchors at the very end only")
    void dollarAnchorsAtEnd() {
        assertEquals("^a\\z", Regex.anchorEnd("^a$"));
        assertEquals("^[$]\\$\\z", Regex.anchorEnd("^[$]\\$$"));
        assertTrue(Regex.find("^[a-z]+$", "abc"));
        assertFalse(Regex.find("^[a-z]+$", "abc\n"));
    }
}
