package dev.regl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Safe execution of the {@code pattern} rule's user-supplied regular expression.
 *
 * <p>Schemas may come from a URL, so a pattern is untrusted input. Three guards apply:
 * <ul>
 *   <li>patterns longer than {@link #MAX_PATTERN_LENGTH} are rejected with
 *       {@link IllegalArgumentException} (the TypeScript engine applies the same limit);</li>
 *   <li>compiled patterns are cached, so a rule evaluated against a long list compiles once;</li>
 *   <li>matching is bounded by {@link #TIMEOUT_NANOS}; a match that exceeds it (catastrophic
 *       backtracking) is treated as <em>not matching</em>, failing closed.</li>
 * </ul>
 * Semantics are a partial match ({@code find}), as in JavaScript's {@code RegExp.test}, with
 * {@code $} anchoring only at the very end of the input (see {@link #anchorEnd}).
 */
final class Regex {

    static final int MAX_PATTERN_LENGTH = 1000;
    static final long TIMEOUT_NANOS = 1_000_000_000L;
    private static final int CACHE_SIZE = 256;

    private static final Map<String, Pattern> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    private Regex() {
    }

    /** True when {@code regex} matches anywhere in {@code input}; false on timeout. */
    static boolean find(String regex, String input) {
        Pattern pattern = compile(regex);
        try {
            return pattern.matcher(new Deadline(input, System.nanoTime() + TIMEOUT_NANOS)).find();
        } catch (TimeoutException e) {
            return false;
        }
    }

    private static Pattern compile(String regex) {
        if (regex.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException(
                    "Regex pattern is longer than " + MAX_PATTERN_LENGTH + " characters.");
        }
        return CACHE.computeIfAbsent(regex, r -> Pattern.compile(anchorEnd(r)));
    }

    /**
     * Rewrites an unescaped {@code $} outside a character class to {@code \z}. In Java a bare
     * {@code $} also matches before a trailing line terminator, so {@code ^[a-z]+$} would accept
     * {@code "abc\n"}; in JavaScript it does not. This keeps the two engines in agreement.
     */
    static String anchorEnd(String regex) {
        StringBuilder out = new StringBuilder(regex.length() + 8);
        boolean inClass = false;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\' && i + 1 < regex.length()) {
                out.append(c).append(regex.charAt(++i));
            } else if (c == '[') {
                inClass = true;
                out.append(c);
            } else if (c == ']' && inClass) {
                inClass = false;
                out.append(c);
            } else if (c == '$' && !inClass) {
                out.append("\\z");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static final class TimeoutException extends RuntimeException {
        TimeoutException() {
            super(null, null, false, false);
        }
    }

    /** A CharSequence that aborts the match once its deadline passes. */
    private static final class Deadline implements CharSequence {
        private final CharSequence text;
        private final long deadline;
        private int reads;

        Deadline(CharSequence text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFFFF) == 0 && System.nanoTime() > deadline) {
                throw new TimeoutException();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
