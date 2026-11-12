import { RuleEvaluators, ParameterType, TargetType, Rule, matchesTargetType, describeType } from './index';
import { overlaps, parsePath, joinPath, comparePaths } from './path';

export type Severity = 'ERROR' | 'WARNING';

/**
 * The category of a finding, which is the field a consumer should branch on.
 *
 * There is no `MISSING`: a missing field resolves to `null`, so a missing
 * mandatory field is an ordinary `RULE_FAILED` on `notNull`.
 */
export type Kind =
    | 'RULE_FAILED'
    | 'TYPE_MISMATCH'
    | 'UNREACHABLE'
    | 'NOT_A_LIST'
    | 'UNKNOWN_FIELD';

/**
 * One problem found while validating a whole document.
 *
 * A finding is deliberately *not* a `ValidationError`. The scalar API reports a
 * value against a rule; document validation additionally has to report problems
 * with the document's *shape* — a key read through a scalar, a wildcard over
 * something that is not a list, a value whose kind contradicts the field's
 * declared type. Those are not rule failures, and collapsing them into one shape
 * would force every consumer to string-match the message to tell a structural bug
 * apart from ordinary bad input.
 */
export interface DocumentFinding {
    severity: Severity;
    kind: Kind;
    path: string;
    /** The rule code, or null for a structural finding no rule is responsible for. */
    rule: string | null;
    value?: any;
    /** A convenience English message; localize from kind, rule, and value. */
    message: string;
}

export interface DocumentResult {
    valid: boolean;
    errors: DocumentFinding[];
    warnings: DocumentFinding[];
}

/**
 * Every finding, errors first then warnings, in the order they were produced.
 *
 * A companion to the `errors`/`warnings` fields rather than a method on the
 * result, because this is an interface: consumers construct results from
 * engines and from tests, and a bare object is the friendliest shape for both.
 */
export function findings(result: DocumentResult): DocumentFinding[] {
    return [...result.errors, ...result.warnings];
}

/** Whether any finding is of the given kind. */
export function hasKind(result: DocumentResult, kind: Kind): boolean {
    return findings(result).some(f => f.kind === kind);
}

export interface Field {
    readonly type: TargetType;
    readonly rules: Rule[];
}

/**
 * The shared base for every field builder.
 *
 * It holds the rules whose catalog target type is `ANY` — `notNull` and `enum` —
 * which are available on every builder precisely because they are not about a
 * particular kind of value. Everything else lives on the type-specific subclass,
 * so a chain can only name rules that make sense for its declared type.
 */
abstract class TypedField<SELF extends TypedField<SELF>> {
    protected constructor(readonly type: TargetType, readonly rules: Rule[]) {}

    /** Requires the value to be present and not null. */
    notNull(): SELF {
        return this.append(new Rule('notNull'));
    }

    /**
     * Restricts the value to a set of allowed values.
     *
     * Named `enumOf` rather than `enum` so it does not collide with the global
     * `enum`; the catalog id is `enum`.
     *
     * <p>Usable on any builder, including a typed one: on a `StringField` it reads
     * as "one of these strings", which is both narrower and clearer than reaching
     * for `any()`.
     */
    enumOf(...allowed: string[]): SELF {
        return this.append(new Rule('enum', allowed));
    }

    /**
     * Appends several rules at once, given as values rather than as a chain.
     *
     * ```ts
     * number().with(notNull, integer, lessThan(65536))
     * ```
     *
     * Rules are appended in the order given, after any already on the builder,
     * so finding order is unchanged from the equivalent chain. The head still
     * fixes the target type at compile time and each factory still type-checks
     * its own parameter; what this form gives up is the compile-time rejection
     * of a rule that targets a different type, which is then caught here by the
     * same check `fieldOf` applies.
     *
     * @throws if a rule targets a type other than this field's, or if no rule
     *         is given
     */
    with(...additions: Rule[]): SELF {
        if (additions.length === 0) {
            throw new Error('with(...) requires at least one rule.');
        }
        const next = this.rules.concat(additions);
        next.forEach(rule => requireTarget(this.type, rule));
        return this.copy(next);
    }

    /**
     * Marks the most recently added rule warn-only, so it is reported as a
     * warning and never makes the document invalid.
     */
    warnOnly(): SELF {
        if (this.rules.length === 0) {
            throw new Error('warnOnly() must follow a rule; the chain is empty.');
        }
        const next = this.rules.slice();
        next[next.length - 1] = next[next.length - 1].withWarnOnly(true);
        return this.copy(next);
    }

    protected append(rule: Rule): SELF {
        return this.copy(this.rules.concat([rule]));
    }

    protected abstract copy(rules: Rule[]): SELF;
}

export class StringField extends TypedField<StringField> {
    constructor(rules: Rule[] = []) {
        super(TargetType.STRING, rules);
    }
    protected copy(rules: Rule[]): StringField {
        return new StringField(rules);
    }
    minLength(min: number) {
        return this.append(new Rule('minLength', min));
    }
    maxLength(max: number) {
        return this.append(new Rule('maxLength', max));
    }
    length(exact: number) {
        return this.append(new Rule('length', exact));
    }
    pattern(regex: string) {
        return this.append(new Rule('pattern', regex));
    }
    contains(substring: string) {
        return this.append(new Rule('contains', substring));
    }
    startsWith(prefix: string) {
        return this.append(new Rule('startsWith', prefix));
    }
    endsWith(suffix: string) {
        return this.append(new Rule('endsWith', suffix));
    }
    email() {
        return this.append(new Rule('email'));
    }
    url() {
        return this.append(new Rule('url'));
    }
    uuid() {
        return this.append(new Rule('uuid'));
    }
    languageCode() {
        return this.append(new Rule('languageCode'));
    }
    lowercase() {
        return this.append(new Rule('lowercase'));
    }
    uppercase() {
        return this.append(new Rule('uppercase'));
    }
    trimmed() {
        return this.append(new Rule('trimmed'));
    }
    notEmpty() {
        return this.append(new Rule('notEmpty'));
    }
    text() {
        return this.append(new Rule('text'));
    }
    dateFormat(pattern: string) {
        return this.append(new Rule('dateFormat', pattern));
    }
    isoDate() {
        return this.append(new Rule('isoDate'));
    }
    isoDateTime() {
        return this.append(new Rule('isoDateTime'));
    }
    isoTime() {
        return this.append(new Rule('isoTime'));
    }

    /** Requires date/time to be chronologically after parameter or sibling reference ($sibling). */
    after(target: string): StringField {
        return this.append(new Rule('after', target));
    }

    /** Requires date/time to be chronologically before parameter or sibling reference ($sibling). */
    before(target: string): StringField {
        return this.append(new Rule('before', target));
    }
}

export class NumberField extends TypedField<NumberField> {
    constructor(rules: Rule[] = []) {
        super(TargetType.NUMBER, rules);
    }
    protected copy(rules: Rule[]): NumberField {
        return new NumberField(rules);
    }
    greaterThan(bound: number) {
        return this.append(new Rule('greaterThan', bound));
    }
    greaterThanOrEqual(bound: number) {
        return this.append(new Rule('greaterThanOrEqual', bound));
    }
    lessThan(bound: number) {
        return this.append(new Rule('lessThan', bound));
    }
    lessThanOrEqual(bound: number) {
        return this.append(new Rule('lessThanOrEqual', bound));
    }
    positive() {
        return this.append(new Rule('positive'));
    }
    negative() {
        return this.append(new Rule('negative'));
    }
    positiveInteger() {
        return this.append(new Rule('positiveInteger'));
    }
    negativeInteger() {
        return this.append(new Rule('negativeInteger'));
    }
    multipleOf(divisor: number) {
        return this.append(new Rule('multipleOf', divisor));
    }
    precision(decimalPlaces: number) {
        return this.append(new Rule('precision', decimalPlaces));
    }
}

/**
 * A field whose path must hold a whole number.
 *
 * The NUMBER magnitude rules are here because an integer is a number with a
 * whole-number assertion on top — a port is still `lessThan 65536`. The three
 * omissions mirror the Java builder: `integer()` is what this type already
 * asserts, `precision()` is meaningless for a whole number, and
 * `positiveInteger()`/`negativeInteger()` are `positive()`/`negative()` here.
 */
export class IntegerField extends TypedField<IntegerField> {
    constructor(rules: Rule[] = []) {
        super(TargetType.INTEGER, rules);
    }
    protected copy(rules: Rule[]): IntegerField {
        return new IntegerField(rules);
    }
    greaterThan(bound: number) {
        return this.append(new Rule('greaterThan', bound));
    }
    greaterThanOrEqual(bound: number) {
        return this.append(new Rule('greaterThanOrEqual', bound));
    }
    lessThan(bound: number) {
        return this.append(new Rule('lessThan', bound));
    }
    lessThanOrEqual(bound: number) {
        return this.append(new Rule('lessThanOrEqual', bound));
    }
    positive() {
        return this.append(new Rule('positive'));
    }
    negative() {
        return this.append(new Rule('negative'));
    }
    multipleOf(divisor: number) {
        return this.append(new Rule('multipleOf', divisor));
    }
}

export class BooleanField extends TypedField<BooleanField> {
    constructor(rules: Rule[] = []) {
        super(TargetType.BOOLEAN, rules);
    }
    protected copy(rules: Rule[]): BooleanField {
        return new BooleanField(rules);
    }
    isTrue() {
        return this.append(new Rule('isTrue'));
    }
    isFalse() {
        return this.append(new Rule('isFalse'));
    }
}

export class ListField extends TypedField<ListField> {
    constructor(rules: Rule[] = []) {
        super(TargetType.LIST, rules);
    }
    protected copy(rules: Rule[]): ListField {
        return new ListField(rules);
    }
    /**
     * Bounds the number of items; either bound may be omitted to leave that side
     * unbounded. Use a min to require a non-empty list: `notNull()` alone does not
     * reject `[]`.
     */
    range(range: { min?: number; max?: number }) {
        return this.append(new Rule('range', range));
    }
    unique() {
        return this.append(new Rule('unique'));
    }
}

/**
 * A field that declares no type: whatever is present is accepted.
 *
 * This is the only builder whose rules can operate on a `null`, because `ANY`
 * lets every kind through the type guard — including `null`, which every other
 * target type rejects before the predicate runs.
 *
 * Reaching for `any()` is a last resort. A field with no declared type can only
 * be constrained by the two ANY-target rules; if you know the type, say so with
 * `StringField`, `NumberField`, `BooleanField`, or `ListField` and get the whole
 * rule set for that type.
 */
export class AnyField extends TypedField<AnyField> {
    constructor(rules: Rule[] = []) {
        super(TargetType.ANY, rules);
    }
    protected copy(rules: Rule[]): AnyField {
        return new AnyField(rules);
    }
}

/**
 * A field whose path must hold a calendar date.
 *
 * The catalog assigns `DATE` no rules, so this builder carries only `notNull()`
 * and `enumOf()` from the shared base. Stating a shape here would need a rule the
 * catalog does not have.
 */
export class DateField extends TypedField<DateField> {
    constructor(rules: Rule[] = []) {
        super(TargetType.DATE, rules);
    }
    protected copy(rules: Rule[]): DateField {
        return new DateField(rules);
    }
}

/**
 * A field whose path must hold a date-time timestamp.
 *
 * Carries the epoch rules, which the catalog targets at `DATETIME` alone: a Unix
 * timestamp in seconds or milliseconds is an instant, not a calendar day and not a
 * time of day.
 */
export class DateTimeField extends TypedField<DateTimeField> {
    constructor(rules: Rule[] = []) {
        super(TargetType.DATETIME, rules);
    }
    protected copy(rules: Rule[]): DateTimeField {
        return new DateTimeField(rules);
    }
    /** Requires a whole-number Unix timestamp in seconds. */
    epoch() {
        return this.append(new Rule('epoch'));
    }
    /** Requires a whole-number Unix timestamp in milliseconds. */
    epochMillis() {
        return this.append(new Rule('epochMillis'));
    }
}

/**
 * A field whose path must hold a time of day.
 *
 * The catalog assigns `TIME` no rules, so this builder carries only `notNull()`
 * and `enumOf()` from the shared base.
 */
export class TimeField extends TypedField<TimeField> {
    constructor(rules: Rule[] = []) {
        super(TargetType.TIME, rules);
    }
    protected copy(rules: Rule[]): TimeField {
        return new TimeField(rules);
    }
}

export const string = () => new StringField();
export const number = () => new NumberField();

/** A path that must hold a whole number. `1.0`, `1e3` and `2**31` all qualify. */
export const integer = () => new IntegerField();
export const boolean_ = () => new BooleanField();
export const list = () => new ListField();
export const any = () => new AnyField();
export const date = () => new DateField();
export const dateTime = () => new DateTimeField();
export const time = () => new TimeField();

/**
 * Builds a field for a type given explicitly, validating that every rule matches
 * the declared type.
 *
 * This is the non-builder path, for schemas that arrive as data. It throws an
 * `Error` on a mismatch — the same class of fault the engines already raise for
 * a bad rule parameter.
 */
/**
 * Throws unless `rule` may sit on a field of `type`.
 *
 * Shared by the formal constructor `fieldOf` and the builder's `with` terminal so
 * the two cannot disagree about which rules belong on a type.
 */
function requireTarget(type: TargetType, rule: Rule): void {
    const evaluator = RuleEvaluators.get(rule.code);
    if (!evaluator) throw new Error(`Unknown rule code: ${rule.code}`);
    // A rule whose target type is ANY belongs on every field, which is what
    // lets notNull and enum be shared rather than duplicated.
    if (evaluator.targetType === type || evaluator.targetType === TargetType.ANY) return;
    // An INTEGER field is a NUMBER field with a whole-number assertion on top,
    // so every NUMBER rule speaks about it too — a port is still bounded.
    if (type === TargetType.INTEGER && evaluator.targetType === TargetType.NUMBER) return;
    throw new Error(
        `Field declares type ${type} but rule '${rule.code}' targets ${evaluator.targetType}.`
    );
}

export function fieldOf(type: TargetType, rules: Rule[]): Field {
    if (type === undefined || type === null) throw new Error('Field type cannot be null.');
    if (!Array.isArray(rules)) throw new Error('Field rules cannot be an array of rules.');
    for (const rule of rules) {
        requireTarget(type, rule);
    }
    switch (type) {
        case TargetType.STRING:
            return new StringField(rules);
        case TargetType.NUMBER:
            return new NumberField(rules);
        case TargetType.INTEGER:
            return new IntegerField(rules);
        case TargetType.BOOLEAN:
            return new BooleanField(rules);
        case TargetType.LIST:
            return new ListField(rules);
        case TargetType.DATE:
            return new DateField(rules);
        case TargetType.DATETIME:
            return new DateTimeField(rules);
        case TargetType.TIME:
            return new TimeField(rules);
        case TargetType.ANY:
            return new AnyField(rules);
        default:
            throw new Error(`No builder for target type ${type}.`);
    }
}

interface Entry {
    order: number;
    path: string;
    segments: string[];
    field: Field;
}

interface Hit {
    path: string;
    value: any;
}

interface Failure {
    kind: Kind;
    at: string;
    explanation: string;
}

interface Finding {
    order: number;
    ruleIndex: number;
    finding: DocumentFinding;
}

/** The default cap on a declared path's segment count. */
export const DEFAULT_MAX_DEPTH = 32;

function isPlainObject(value: any): boolean {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Validates a whole document — a decoded tree of objects, lists, and scalars —
 * against a set of fields, and reports every problem it can reach instead of
 * throwing on the first one.
 *
 * Conventions:
 * - A missing field is `null`; `notNull()` is what makes a field mandatory.
 * - Nothing is coerced. A value whose kind contradicts the declared type is a
 *   `TYPE_MISMATCH` finding, never a conversion.
 * - Fields must be disjoint. Two fields that could match one concrete path are a
 *   construction error, so behaviour never depends on declaration order.
 */
function resolveTargetType(name: string): TargetType | undefined {
    const upper = name.toUpperCase();
    if (upper in TargetType) {
        return (TargetType as any)[upper];
    }
    return undefined;
}

export class Schema {
    private constructor(
        private readonly entries: Entry[],
        private readonly strictMode: boolean,
        private readonly depthLimit: number,
        private readonly customTypes: ReadonlyMap<string, Field> = new Map()
    ) {}

    static create(): Schema {
        return new Schema([], false, DEFAULT_MAX_DEPTH, new Map());
    }

    /**
     * Registers a reusable custom field definition by name.
     */
    define(name: string, fieldOrBase: Field | string | TargetType, ...rules: Rule[]): Schema {
        if (!name) throw new Error('name cannot be null or empty.');
        let resolvedField: Field;
        if (typeof fieldOrBase === 'string') {
            const base = this.customTypes.get(fieldOrBase);
            if (base) {
                resolvedField = fieldOf(base.type, [...base.rules, ...rules]);
            } else {
                const target = resolveTargetType(fieldOrBase);
                if (target !== undefined) {
                    resolvedField = fieldOf(target, rules);
                } else {
                    throw new Error(`Unknown base type '${fieldOrBase}' for custom field '${name}'.`);
                }
            }
        } else if (typeof fieldOrBase === 'object' && fieldOrBase !== null && 'type' in fieldOrBase && 'rules' in fieldOrBase) {
            resolvedField = fieldOrBase as Field;
        } else if (typeof fieldOrBase === 'string' || (fieldOrBase in TargetType)) {
            resolvedField = fieldOf(fieldOrBase as TargetType, rules);
        } else {
            throw new Error(`Invalid custom field definition for '${name}'.`);
        }
        const next = new Map(this.customTypes);
        next.set(name, resolvedField);
        return new Schema(this.entries, this.strictMode, this.depthLimit, next);
    }

    /** Returns the custom field registered for `name`, or `undefined`. */
    customType(name: string): Field | undefined {
        return this.customTypes.get(name);
    }

    /** All registered custom field types. */
    getCustomTypes(): ReadonlyMap<string, Field> {
        return this.customTypes;
    }

    /**
     * Returns a schema with `field` added for `path`.
     *
     * Field order is the order findings are reported in, so declare fields in the
     * order they should be reported rather than alphabetically.
     */
    field(path: string, fieldOrCustomType: Field | string, ...rules: Rule[]): Schema {
        let field: Field;
        if (typeof fieldOrCustomType === 'string') {
            const custom = this.customTypes.get(fieldOrCustomType);
            if (custom) {
                field = fieldOf(custom.type, [...custom.rules, ...rules]);
            } else {
                const target = resolveTargetType(fieldOrCustomType);
                if (target !== undefined) {
                    field = fieldOf(target, rules);
                } else {
                    throw new Error(`Unknown field type '${fieldOrCustomType}' for path '${path}'.`);
                }
            }
        } else {
            field = fieldOrCustomType;
        }
        if (!field) throw new Error('field');
        const segments = parsePath(path);
        if (segments.length > this.depthLimit) {
            throw new Error(
                `Path '${path}' has ${segments.length} segments, ` +
                    `which exceeds the schema's maxDepth of ${this.depthLimit}.`
            );
        }
        for (const existing of this.entries) {
            if (overlaps(existing.segments, segments)) {
                throw new Error(
                    `Path '${path}' overlaps the already-declared path '${existing.path}'; ` +
                        'two fields must not match the same value.'
                );
            }
        }
        const next = this.entries.concat([
            { order: this.entries.length, path, segments, field }
        ]);
        return new Schema(next, this.strictMode, this.depthLimit, this.customTypes);
    }

    /** Whether every builder is used, so nothing is unvalidated by accident. */
    strict(): Schema {
        return new Schema(this.entries, true, this.depthLimit, this.customTypes);
    }

    /** Caps the segment count any declared path may have. */
    maxDepth(limit: number): Schema {
        if (limit < 1) throw new Error('maxDepth must be at least 1.');
        return new Schema(this.entries, this.strictMode, limit, this.customTypes);
    }

    isStrict(): boolean {
        return this.strictMode;
    }

    depth(): number {
        return this.depthLimit;
    }

    /** The declared paths, in declaration order. */
    paths(): string[] {
        return this.entries.map(e => e.path);
    }

    /** The field declared for a path, or undefined if there is none. */
    fieldAt(path: string): Field | undefined {
        const found = this.entries.find(e => e.path === path);
        return found ? found.field : undefined;
    }

    /**
     * Validates a decoded document and reports every problem found.
     *
     * Never throws on document content. Findings come out in a total order:
     * field declaration index, then rule position, then path, then kind.
     */
    validate(document: any): DocumentResult {
        const found: Finding[] = [];
        for (const entry of this.entries) {
            const resolution = this.resolve(document, entry.segments);
            if (resolution.kind) {
                found.push({
                    order: entry.order,
                    ruleIndex: -1,
                    finding: {
                        severity: 'ERROR',
                        kind: resolution.kind,
                        path: entry.path,
                        rule: null,
                        message: resolution.message!
                    }
                });
                continue;
            }
            for (const hit of resolution.hits) {
                found.push(...this.check(entry, hit));
            }
        }
        if (this.strictMode) {
            found.push(...this.unknownFields(document));
        }
        found.sort(compareFindings);
        const errors = found.filter(f => f.finding.severity === 'ERROR').map(f => f.finding);
        const warnings = found.filter(f => f.finding.severity === 'WARNING').map(f => f.finding);
        return { valid: errors.length === 0, errors, warnings };
    }

    // --- chain evaluation --------------------------------------------------

    private check(entry: Entry, hit: Hit): Finding[] {
        const chain = entry.field.rules;
        const out: Finding[] = [];

        // notNull is evaluated before the null short-circuit, so a mandatory field
        // reports itself even though nothing else will run.
        for (let i = 0; i < chain.length; i++) {
            const rule = chain[i];
            if (rule.code !== 'notNull') continue;
            if (hit.value === null) {
                out.push(failure(entry.order, i, hit.path, rule,
                    'Value is required and must not be null.'));
            }
        }
        if (hit.value === null) return out;

        // The declared type is checked once for the whole chain. Every rule in a
        // chain shares it, so naming one rule would be arbitrary.
        if (!matchesTargetType(entry.field.type, hit.value)) {
            out.push({
                order: entry.order,
                ruleIndex: -1,
                finding: {
                    severity: 'ERROR',
                    kind: 'TYPE_MISMATCH',
                    path: hit.path,
                    rule: null,
                    message:
                        `Value must be of type ${entry.field.type}, ` +
                        `but got ${describeType(hit.value)}.`
                }
            });
            return out;
        }

        for (let i = 0; i < chain.length; i++) {
            const rule = chain[i];
            if (rule.code === 'notNull') continue;
            const evaluator = RuleEvaluators.require(rule.code);
            if (RuleEvaluators.test(evaluator, hit.value, rule.value)) continue;
            out.push(failure(entry.order, i, hit.path, rule,
                RuleEvaluators.message(evaluator, hit.path, hit.value, rule.value)));
        }
        return out;
    }

    // --- resolution --------------------------------------------------------

    private resolve(root: any, segments: string[]): { hits: Hit[]; kind?: Kind; message?: string } {
        const hits: Hit[] = [];
        const failure = this.walk(root, segments, 0, [], hits);
        if (failure) {
            return {
                hits: [],
                kind: failure.kind,
                message:
                    `Value at '${failure.at}' is ${failure.explanation}, ` +
                    `so '${joinPath(segments)}' cannot be read.`
            };
        }
        return { hits };
    }

    /**
     * Collects the values a path resolves to, and stops at the first shape problem.
     *
     * A missing key resolves to `null`, and a `null` ancestor makes everything
     * beneath it `null`. A wildcard through a `null` yields nothing, because a
     * null list has no elements to index — which is why a null is never a shape
     * problem, only a missing value.
     */
    private walk(
        current: any,
        segments: string[],
        index: number,
        consumed: string[],
        out: Hit[]
    ): Failure | null {
        if (index === segments.length) {
            out.push({ path: joinPath(consumed), value: current });
            return null;
        }
        const segment = segments[index];
        if (segment === '*') {
            if (current === null || current === undefined) return null;
            if (!Array.isArray(current)) {
                return { kind: 'NOT_A_LIST', at: joinPath([...consumed, segment]), explanation: 'not a list' };
            }
            for (let i = 0; i < current.length; i++) {
                consumed.push(String(i));
                const nested = this.walk(current[i], segments, index + 1, consumed, out);
                consumed.pop();
                if (nested) return nested;
            }
            return null;
        }
        if (current !== null && current !== undefined && !isPlainObject(current)) {
            // A present scalar or list read as an object: the value exists but the
            // path does not, so this is a shape problem rather than a missing field.
            // The offending value is the one already reached, not the key we failed
            // to read from it.
            return {
                kind: 'UNREACHABLE',
                at: consumed.length === 0 ? '(document root)' : joinPath(consumed),
                explanation: 'not an object'
            };
        }
        let next: any = null;
        if (isPlainObject(current) && Object.prototype.hasOwnProperty.call(current, segment)) {
            next = current[segment];
        }
        consumed.push(segment);
        const nested = this.walk(next, segments, index + 1, consumed, out);
        consumed.pop();
        return nested;
    }

    // --- strict mode -------------------------------------------------------

    /**
     * Finds object keys that no declared path covers.
     *
     * A key is covered when it either matches a declared leaf or sits on the way
     * to one: `server` is covered because `server.port` descends through it, while
     * `server.host` is not. A wildcard covers each element's subtree at its own
     * depth, so `servers.*.host` covers `servers.0.host` but not `servers.0.port`.
     * A field of type ANY covers everything under it: `body: ANY` covers `body.id`.
     */
    private unknownFields(document: any): Finding[] {
        const out: Finding[] = [];
        this.collectUnknown(document, [], out);
        return out;
    }

    private collectUnknown(node: any, at: string[], out: Finding[]): void {
        // Sorted so both engines walk keys in the same order.
        if (isPlainObject(node)) {
            for (const key of Object.keys(node).sort()) {
                if (!this.isCovered(at, key)) {
                    const concrete = [...at, key];
                    const path = joinPath(concrete);
                    out.push({
                        order: -1,
                        ruleIndex: -1,
                        finding: {
                            severity: 'ERROR',
                            kind: 'UNKNOWN_FIELD',
                            path,
                            rule: null,
                            message: `Field '${path}' is not allowed.`
                        }
                    });
                }
                this.collectUnknown(node[key], [...at, key], out);
            }
            return;
        }
        // Descend into lists so that a wildcard path's elements get their keys
        // checked too. A list index is never itself a field, so nothing is
        // reported for the list itself.
        if (Array.isArray(node)) {
            for (let i = 0; i < node.length; i++) {
                this.collectUnknown(node[i], [...at, String(i)], out);
            }
        }
    }

    /** Whether some declared path covers the key `key` sitting at `at`. */
    private isCovered(at: string[], key: string): boolean {
        const concrete = [...at, key];
        return this.entries.some(
            entry =>
                coversLeaf(entry.segments, concrete) ||
                coversContainer(entry.segments, concrete) ||
                (entry.field.type === TargetType.ANY && coversSubtree(entry.segments, concrete))
        );
    }
}

/**
 * The declared path lies above this concrete path. Only a field of type ANY covers
 * what lies under it: it accepts any value, and so whatever that value contains.
 */
function coversSubtree(declared: string[], concrete: string[]): boolean {
    if (declared.length >= concrete.length) return false;
    for (let i = 0; i < declared.length; i++) {
        const d = declared[i];
        if (d !== '*' && d !== concrete[i]) return false;
    }
    return true;
}

/** The declared path is exactly this concrete path. */
function coversLeaf(declared: string[], concrete: string[]): boolean {
    if (declared.length !== concrete.length) return false;
    return matchesPrefix(declared, concrete);
}

/**
 * The declared path continues past this concrete path, so the concrete path is a
 * container rather than a leaf.
 */
function coversContainer(declared: string[], concrete: string[]): boolean {
    if (declared.length <= concrete.length) return false;
    return matchesPrefix(declared, concrete);
}

function matchesPrefix(declared: string[], concrete: string[]): boolean {
    for (let i = 0; i < concrete.length; i++) {
        if (i >= declared.length) return false;
        const d = declared[i];
        const c = concrete[i];
        if (d !== '*' && d !== c) return false;
    }
    return true;
}

function failure(
    order: number,
    ruleIndex: number,
    path: string,
    rule: Rule,
    message: string
): Finding {
    return {
        order,
        ruleIndex,
        finding: {
            severity: rule.warnOnly ? 'WARNING' : 'ERROR',
            kind: 'RULE_FAILED',
            path,
            rule: rule.code,
            value: rule.value,
            message
        }
    };
}

/**
 * Total order, so both engines emit identical sequences: field declaration index,
 * then rule position, then path (element indices compared as numbers), then kind.
 */
const KIND_ORDER: Kind[] = ['RULE_FAILED', 'TYPE_MISMATCH', 'UNREACHABLE', 'NOT_A_LIST', 'UNKNOWN_FIELD'];

function compareFindings(a: Finding, b: Finding): number {
    const order = (a.order < 0 ? Number.MAX_SAFE_INTEGER : a.order) -
        (b.order < 0 ? Number.MAX_SAFE_INTEGER : b.order);
    if (order !== 0) return order;
    const ruleIndex = (a.ruleIndex < 0 ? Number.MAX_SAFE_INTEGER : a.ruleIndex) -
        (b.ruleIndex < 0 ? Number.MAX_SAFE_INTEGER : b.ruleIndex);
    if (ruleIndex !== 0) return ruleIndex;
    if (a.finding.path !== b.finding.path) {
        return comparePaths(a.finding.path, b.finding.path);
    }
    return KIND_ORDER.indexOf(a.finding.kind) - KIND_ORDER.indexOf(b.finding.kind);
}