package dev.regl;

/**
 * Union of all generic rules for builders (such as domain properties or dynamic schema models)
 * that accept rules across any data type.
 */
public interface AllRules<SELF extends RuleContainer<SELF>> extends
        CommonRules<SELF>,
        StringRules<SELF>,
        NumberRules<SELF>,
        BooleanRules<SELF>,
        ListRules<SELF>,
        DateTimeRules<SELF> {
}
