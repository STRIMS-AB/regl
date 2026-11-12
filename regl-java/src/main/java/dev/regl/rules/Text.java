package dev.regl.rules;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the field as human-readable text — a marker for indexers and
 * consumers, with no value-level enforcement. The optional value is a
 * BCP-47 language tag (e.g. "en", "pt-BR") declaring the text's language.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
@Documented
public @interface Text {
    String value() default "";
    boolean warnOnly() default false;
}
