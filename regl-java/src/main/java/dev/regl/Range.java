package dev.regl;

/**
 * An inclusive cardinality range between a min and max bound. Either bound may be
 * absent (null), in which case that side of the range is unbounded.
 */
public record Range(
    Integer min,
    Integer max
) {
    public Range {
        if (min != null && min < 0) {
            throw new IllegalArgumentException("Range min cannot be negative.");
        }
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("Range min (" + min + ") cannot exceed max (" + max + ").");
        }
    }

    /**
     * True when at least one bound is set (the range is not fully open-ended).
     */
    public boolean isBounded() {
        return min != null || max != null;
    }

    public boolean contains(int value) {
        if (min != null && value < min) return false;
        if (max != null && value > max) return false;
        return true;
    }
}
