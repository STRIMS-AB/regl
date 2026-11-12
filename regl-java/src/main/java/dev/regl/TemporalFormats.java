package dev.regl;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Universal date, time, and timestamp handling for Regl rules.
 *
 * <p>Supports standard ISO-8601 formats, DRef token patterns
 * ({@code YYYY}, {@code MM}, {@code DD}, {@code HH}, {@code mm}, {@code ss}),
 * Unix epoch timestamps (seconds and milliseconds), and Java temporal objects.
 */
public final class TemporalFormats {

    private TemporalFormats() {
    }

    public static final Pattern ISO_DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    public static final Pattern ISO_TIME_PATTERN = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?$");
    public static final Pattern ISO_DATETIME_PATTERN = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}(:?\\d{2})?)?$");

    private static final Map<String, String> TOKEN_TO_JAVA = Map.of(
            "YYYY", "uuuu",
            "MM", "MM",
            "DD", "dd",
            "HH", "HH",
            "mm", "mm",
            "ss", "ss"
    );

    private static final Map<String, DateTimeFormatter> FORMATTER_CACHE = new ConcurrentHashMap<>();

    /**
     * Translates a token pattern ({@code YYYY-MM-DD}, {@code HH:mm:ss}, etc.) into a Java
     * {@link DateTimeFormatter} pattern string. Non-token alphabetic characters are enclosed in single quotes.
     */
    public static String tokenToJavaPattern(String tokenPattern) {
        if (tokenPattern == null || tokenPattern.isBlank()) {
            return "uuuu-MM-dd'T'HH:mm:ss'Z'";
        }
        int i = 0;
        StringBuilder sb = new StringBuilder();
        while (i < tokenPattern.length()) {
            boolean matched = false;
            for (Map.Entry<String, String> token : TOKEN_TO_JAVA.entrySet()) {
                if (tokenPattern.startsWith(token.getKey(), i)) {
                    sb.append(token.getValue());
                    i += token.getKey().length();
                    matched = true;
                    break;
                }
            }
            if (matched) continue;
            char c = tokenPattern.charAt(i);
            sb.append(Character.isLetter(c) ? "'" + c + "'" : c);
            i++;
        }
        return sb.toString();
    }

    /**
     * Returns a cached {@link DateTimeFormatter} for either a token pattern
     * or standard Java format pattern.
     */
    public static DateTimeFormatter formatterFor(String pattern) {
        return FORMATTER_CACHE.computeIfAbsent(pattern, p -> {
            if ("iso".equalsIgnoreCase(p) || "iso8601".equalsIgnoreCase(p)) {
                return DateTimeFormatter.ISO_DATE_TIME;
            }
            return DateTimeFormatter.ofPattern(tokenToJavaPattern(p));
        });
    }

    /** Whether the given string value is a valid ISO-8601 calendar date (YYYY-MM-DD). */
    public static boolean isIsoDate(Object value) {
        if (value instanceof LocalDate) return true;
        if (!(value instanceof String s) || !ISO_DATE_PATTERN.matcher(s).matches()) return false;
        try {
            LocalDate.parse(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether the given string value is a valid ISO-8601 time (HH:mm:ss). */
    public static boolean isIsoTime(Object value) {
        if (value instanceof LocalTime) return true;
        if (!(value instanceof String s) || !ISO_TIME_PATTERN.matcher(s).matches()) return false;
        try {
            LocalTime.parse(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether the given string value is a valid ISO-8601 timestamp. */
    public static boolean isIsoDateTime(Object value) {
        if (value instanceof LocalDateTime || value instanceof Instant || value instanceof OffsetDateTime) return true;
        if (!(value instanceof String s) || !ISO_DATETIME_PATTERN.matcher(s).matches()) return false;
        try {
            parseToEpochMillis(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Checks whether a numeric value is a whole integer. */
    public static boolean isWholeNumber(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger) {
            return true;
        }
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d) && !Double.isNaN(d);
        }
        return false;
    }

    /** Checks whether a value is an integer Unix timestamp in seconds (between year 1970 and 3000). */
    public static boolean isEpoch(Object value) {
        if (!isWholeNumber(value)) return false;
        long seconds = ((Number) value).longValue();
        return seconds >= 0 && seconds <= 32503680000L;
    }

    /** Checks whether a value is an integer Unix timestamp in milliseconds. */
    public static boolean isEpochMillis(Object value) {
        if (!isWholeNumber(value)) return false;
        long millis = ((Number) value).longValue();
        return millis >= 0 && millis <= 32503680000000L;
    }

    /** Checks whether a string value matches the specified token pattern or format. */
    public static boolean matchesPattern(String pattern, Object value) {
        if (value == null || pattern == null || pattern.isBlank()) return false;
        if ("iso".equalsIgnoreCase(pattern) || "iso8601".equalsIgnoreCase(pattern)) {
            return isIsoDate(value) || isIsoDateTime(value) || isIsoTime(value);
        }
        String str = value.toString();
        try {
            DateTimeFormatter formatter = formatterFor(pattern);
            formatter.parse(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Converts a temporal value (String, Number, Instant, LocalDate, LocalDateTime, etc.)
     * to Unix epoch milliseconds for comparison.
     */
    public static long parseToEpochMillis(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot parse null temporal value.");
        }
        if (value instanceof Instant inst) {
            return inst.toEpochMilli();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant().toEpochMilli();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        if (value instanceof LocalDate ld) {
            return ld.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        }
        if (value instanceof Number n) {
            long val = n.longValue();
            // If < 10^11, assume epoch seconds; otherwise epoch milliseconds
            if (val < 100_000_000_000L) {
                return val * 1000L;
            }
            return val;
        }
        if (value instanceof String s) {
            s = s.trim();
            if (ISO_DATE_PATTERN.matcher(s).matches()) {
                return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
            try {
                return Instant.parse(s).toEpochMilli();
            } catch (Exception ignored) {
            }
            try {
                return OffsetDateTime.parse(s).toInstant().toEpochMilli();
            } catch (Exception ignored) {
            }
            try {
                return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (Exception ignored) {
            }
            // If numeric string
            try {
                long num = Long.parseLong(s);
                return num < 100_000_000_000L ? num * 1000L : num;
            } catch (Exception ignored) {
            }
        }
        throw new IllegalArgumentException("Cannot convert value '" + value + "' to a timestamp.");
    }

    /**
     * Compares two temporal representations chronologically.
     * Returns negative if a &lt; b, zero if a == b, positive if a &gt; b.
     */
    public static int compare(Object a, Object b) {
        long tA = parseToEpochMillis(a);
        long tB = parseToEpochMillis(b);
        return Long.compare(tA, tB);
    }
}
