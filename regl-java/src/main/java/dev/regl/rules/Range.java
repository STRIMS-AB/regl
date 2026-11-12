package dev.regl.rules;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inclusive item-count bounds for a list (or, at model level, instance count).
 * A bound of {@code -1} means that side of the range is unbounded.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
@Documented
public @interface Range {
    int min() default -1;

    int max() default -1;
    boolean warnOnly() default false;
}
