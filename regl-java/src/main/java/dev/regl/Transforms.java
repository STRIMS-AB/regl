package dev.regl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Evaluates canonical Regl value transformations defined in
 * {@code regl-spec/transformations-catalog.json}.
 */
public final class Transforms {

    private Transforms() {
    }

    public static final String DEFAULT_ISO_DATETIME = "YYYY-MM-DDTHH:mm:ssZ";

    /**
     * Runs a transform chain left to right. Null passes through untouched except
     * through {@code default}, {@code constant}, and {@code custom}.
     */
    public static Object applyChain(
            Object value,
            List<Transform> transforms,
            Function<String, Function<Object, Object>> customResolver
    ) {
        return applyChain(value, transforms, customResolver, DEFAULT_ISO_DATETIME);
    }

    public static Object applyChain(
            Object value,
            List<Transform> transforms,
            Function<String, Function<Object, Object>> customResolver,
            String outputFormat
    ) {
        Object current = value;
        for (Transform transform : transforms) {
            if (current == null
                    && transform.type() != TransformType.DEFAULT
                    && transform.type() != TransformType.CONSTANT
                    && transform.type() != TransformType.CUSTOM) {
                continue;
            }
            current = apply(current, transform, customResolver, outputFormat);
        }
        return current;
    }

    public static Object apply(Object value, Transform transform) {
        return apply(value, transform, null, DEFAULT_ISO_DATETIME);
    }

    public static Object apply(
            Object value,
            Transform transform,
            Function<String, Function<Object, Object>> customResolver
    ) {
        return apply(value, transform, customResolver, DEFAULT_ISO_DATETIME);
    }

    public static Object apply(
            Object value,
            Transform transform,
            Function<String, Function<Object, Object>> customResolver,
            String outputFormat
    ) {
        TransformType type = transform.type();
        Object param = transform.param();

        if (value instanceof String str) {
            return switch (type) {
                case LOWER_CASE -> str.toLowerCase();
                case UPPER_CASE -> str.toUpperCase();
                case TRIM -> str.trim();
                case TITLE_CASE -> titleCase(str);
                case PREFIX -> param + str;
                case SUFFIX -> str + param;
                case STRIP_PREFIX -> str.startsWith((String) param) ? str.substring(((String) param).length()) : str;
                case STRIP_SUFFIX -> str.endsWith((String) param) ? str.substring(0, str.length() - ((String) param).length()) : str;
                case REPLACE -> replace(str, asMap(param));
                case SUBSTRING -> substring(str, asMap(param));
                default -> applyNonString(value, type, param, customResolver, str, outputFormat);
            };
        }

        return applyNonString(value, type, param, customResolver, value, outputFormat);
    }

    private static Object applyNonString(
            Object value,
            TransformType type,
            Object param,
            Function<String, Function<Object, Object>> customResolver,
            Object strValue,
            String outputFormat
    ) {
        return switch (type) {
            case DEFAULT -> defaultValue(value, param);
            case CONSTANT -> param;
            case CUSTOM -> custom(value, (String) param, customResolver);
            case JOIN -> joinList(value, param);
            case TO_STRING -> strValue == null ? value : String.valueOf(strValue);
            case TO_NUMBER -> toNumber(value);
            case TO_BOOLEAN -> toBoolean(value);
            case FORMAT_DATE -> formatDate(value, param, outputFormat);
            case ABS -> value instanceof Number n ? Math.abs(n.doubleValue()) : value;
            case ROUND -> value instanceof Number n ? (double) Math.round(n.doubleValue()) : value;
            case ROUND_TO -> value instanceof Number n ? roundTo(n.doubleValue(), asInt(param, 0)) : value;
            case MULTIPLY -> value instanceof Number n ? n.doubleValue() * asDouble(param, 1) : value;
            case DIVIDE -> value instanceof Number n && asDouble(param, 1) != 0 ? n.doubleValue() / asDouble(param, 1) : value;
            case LOWER_CASE, UPPER_CASE, TRIM, TITLE_CASE, PREFIX, SUFFIX, STRIP_PREFIX, STRIP_SUFFIX, REPLACE, SUBSTRING -> value;
        };
    }

    private static Object joinList(Object value, Object param) {
        if (!(value instanceof List<?> list)) {
            return value;
        }
        String separator = param == null ? "" : String.valueOf(param);
        StringBuilder joined = new StringBuilder();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(separator);
            }
            joined.append(item);
        }
        return joined.toString();
    }

    private static String replace(String value, Map<String, Object> param) {
        String pattern = asString(param.get("pattern"), "");
        String replacement = asString(param.get("replacement"), "");
        if (pattern.isEmpty()) return value;
        return value.replaceAll(Pattern.quote(pattern), replacement);
    }

    private static String substring(String value, Map<String, Object> param) {
        int start = asInt(param.get("start"), 0);
        int end = asInt(param.get("end"), -1);
        if (start < 0 || start > value.length()) return value;
        return end >= 0 ? value.substring(start, Math.min(end, value.length())) : value.substring(start);
    }

    /**
     * Mirrors {@code transforms.ts}'s {@code titleCase}, a port of the JS
     * word-run replacement idiom: each run that starts on an ASCII word
     * character and continues to the next whitespace is upper-cased on its
     * first character and lower-cased on the rest.
     */
    private static String titleCase(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        int n = value.length();
        while (i < n) {
            char c = value.charAt(i);
            if (isWordChar(c)) {
                out.append(Character.toUpperCase(c));
                i++;
                while (i < n && !Character.isWhitespace(value.charAt(i))) {
                    out.append(Character.toLowerCase(value.charAt(i)));
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static Object toNumber(Object value) {
        if (value instanceof Number) return value;
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        if (value instanceof Boolean b) return b ? 1 : 0;
        return value;
    }

    private static Object toBoolean(Object value) {
        if (value instanceof Boolean) return value;
        if (value instanceof Number n) return n.doubleValue() != 0;
        if (value instanceof String s) {
            String t = s.trim().toLowerCase();
            if (t.equals("true") || t.equals("1") || t.equals("yes")) return true;
            if (t.equals("false") || t.equals("0") || t.equals("no")) return false;
        }
        return value;
    }

    private static Object defaultValue(Object value, Object param) {
        if (value == null || (value instanceof String s && s.isBlank())) return param;
        return value;
    }

    private static Object custom(Object value, String reference, Function<String, Function<Object, Object>> resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException(
                    "Custom transform '" + reference + "' requires a custom resolver.");
        }
        Function<Object, Object> fn = resolver.apply(reference);
        if (fn == null) {
            throw new IllegalArgumentException("No custom transform registered for: " + reference);
        }
        return fn.apply(value);
    }

    private static Object formatDate(Object value, Object param, String outputFormat) {
        String source = asString(param, "").trim();
        boolean unix = source.equalsIgnoreCase("unix");

        if (value instanceof Number n) {
            return epochToFormat(n.longValue(), outputFormat);
        }

        if (value instanceof String s) {
            if (unix || source.isBlank()) {
                try {
                    return epochToFormat((long) Double.parseDouble(s.trim()), outputFormat);
                } catch (NumberFormatException ignored) {
                }
            }
            String fromPattern = source.isBlank() ? DEFAULT_ISO_DATETIME : source;
            try {
                DateTimeFormatter from = DateTimeFormatter.ofPattern(TemporalFormats.tokenToJavaPattern(fromPattern));
                Object parsed = from.parseBest(s.trim(), LocalDateTime::from, LocalDate::from);
                long epoch = parsed instanceof LocalDate ld
                        ? ld.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                        : ((LocalDateTime) parsed).toEpochSecond(ZoneOffset.UTC);
                return epochToFormat(epoch, outputFormat);
            } catch (Exception ignored) {
                return value;
            }
        }
        return value;
    }

    private static Object epochToFormat(long raw, String outputFormat) {
        long epoch = Math.abs(raw) > 10_000_000_000L ? raw / 1000L : raw;
        if ("epoch".equalsIgnoreCase(outputFormat)) {
            return epoch;
        }
        if ("epochMillis".equalsIgnoreCase(outputFormat)) {
            return epoch * 1000L;
        }
        String fmt = (outputFormat == null || outputFormat.isBlank() || "iso8601".equalsIgnoreCase(outputFormat))
                ? DEFAULT_ISO_DATETIME
                : outputFormat;
        DateTimeFormatter toFormat = DateTimeFormatter.ofPattern(TemporalFormats.tokenToJavaPattern(fmt));
        return Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).format(toFormat);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String asString(Object value, String fallback) {
        return value instanceof String s ? s : fallback;
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static double asDouble(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static double roundTo(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
