import { registerBuiltIns } from './builtins';

export * from './schema';
export * from './transforms';
export * from './sidecar';

export enum TargetType {
    STRING = "STRING",
    NUMBER = "NUMBER",
    INTEGER = "INTEGER",
    BOOLEAN = "BOOLEAN",
    LIST = "LIST",
    ANY = "ANY",
    DATE = "DATE",
    DATETIME = "DATETIME",
    TIME = "TIME"
}

export enum ParameterType {
    STRING = "STRING",
    NUMBER = "NUMBER",
    BOOLEAN = "BOOLEAN",
    OBJECT = "OBJECT",
    LIST = "LIST"
}

export interface ValidationError {
    field: string;
    rule: string;
    value?: any;
    message: string;
}

export interface ValidationResult {
    valid: boolean;
    errors: ValidationError[];
    warnings: ValidationError[];
}

export interface Range {
    min?: number;
    max?: number;
}

/**
 * Whether `value` is of the kind this rule's `TargetType` declares, and so
 * something the evaluator can meaningfully test.
 *
 * A rule only speaks about values of its own target type: a `minLength` rule is
 * about strings, not numbers. When a value is of another kind the rule cannot
 * produce a meaningful verdict, so the mismatch is *reported* as a validation
 * error rather than coerced or silently passed. This keeps a malformed document
 * a reportable result instead of a runtime error, and keeps the engines in step.
 */
export function matchesTargetType(targetType: TargetType, value: any): boolean {
    if (targetType === TargetType.ANY) return true;
    switch (targetType) {
        case TargetType.STRING: return typeof value === 'string';
        case TargetType.NUMBER: return typeof value === 'number' && Number.isFinite(value);
        // Mathematical integrality, not a JS class: 1.0 is the same number as 1
        // here, and only a fractional value is not an INTEGER.
        case TargetType.INTEGER: return typeof value === 'number' && Number.isInteger(value);
        case TargetType.BOOLEAN: return typeof value === 'boolean';
        case TargetType.LIST: return Array.isArray(value);
        case TargetType.DATE: return value instanceof Date || (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && !isNaN(Date.parse(value)));
        case TargetType.DATETIME: return value instanceof Date || (typeof value === 'string' && !isNaN(Date.parse(value))) || (typeof value === 'number' && Number.isInteger(value));
        case TargetType.TIME: return typeof value === 'string' && /^\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/.test(value);
        default: return true;
    }
}

/**
 * A short, locale-neutral name for the runtime kind of `value`, used in the
 * type-mismatch message. Deliberately coarse: it reports the kind the engine can
 * route on, so the two engines word it identically.
 */
export function describeType(value: any): string {
    if (value === null) return 'null';
    if (Array.isArray(value)) return 'LIST';
    const t = typeof value;
    return t === 'object' ? 'OBJECT' : t.toUpperCase();
}

export interface RuleEvaluator<P = any> {
    code: string;
    name?: string;
    description?: string;
    parameterType?: ParameterType | null;
    targetType: TargetType;
    test: (input: any, value?: P) => boolean;
    message: (field: string, input: any, value?: P) => string;
}

export class RuleEvaluators {
    private static EVALUATORS = new Map<string, RuleEvaluator>();
    /**
     * The catalog's parameter type per built-in code — regl-ts's equivalent of
     * regl-java's `RuleType` enum. A rule constructed for a built-in code
     * validates its argument against this at construction, exactly as the Java
     * engine does; a custom code is validated later, against its registered
     * evaluator, when the rule is validated.
     *
     * Unregistering a built-in evaluator does not withdraw the catalog entry —
     * the Java enum would not either — so the mark outlives the registry.
     */
    private static BUILT_IN_PARAMETER_TYPES = new Map<string, ParameterType | null>();

    static register(evaluator: RuleEvaluator) {
        this.EVALUATORS.set(evaluator.code, evaluator);
    }

    /** Registers one of the 41 catalog rules, marking it built-in. */
    static registerBuiltIn(evaluator: RuleEvaluator) {
        this.register(evaluator);
        this.BUILT_IN_PARAMETER_TYPES.set(evaluator.code, evaluator.parameterType ?? null);
    }

    static unregister(code: string): boolean {
        return this.EVALUATORS.delete(code);
    }

    static require(code: string): RuleEvaluator {
        const ev = this.EVALUATORS.get(code);
        if (!ev) throw new Error(`Unknown rule code: ${code}`);
        return ev;
    }

    static isBuiltIn(code: string): boolean {
        return this.BUILT_IN_PARAMETER_TYPES.has(code);
    }

    /** The catalog's parameter type for a built-in code, or undefined for a custom one. */
    static builtInParameterType(code: string): ParameterType | null | undefined {
        return this.BUILT_IN_PARAMETER_TYPES.get(code);
    }

    static get(code: string): RuleEvaluator | undefined {
        return this.EVALUATORS.get(code);
    }

    static getAll(): RuleEvaluator[] {
        return Array.from(this.EVALUATORS.values());
    }

    static find(code: string): RuleEvaluator | undefined {
        return this.get(code);
    }

    static message(evaluator: RuleEvaluator, field: string, input: any, value?: any): string {
        return evaluator.message(field, input, value);
    }

    static test(evaluator: RuleEvaluator, input: any, value?: any): boolean {
        return evaluator.test(input, value);
    }

    /**
     * Checks a rule's argument against its evaluator's declared parameter type,
     * at validation time — the one place a custom rule's argument can be
     * checked, since its evaluator may be registered after the rule is built.
     */
    static checkParameter(rule: Rule, evaluator: RuleEvaluator): void {
        const expected = evaluator.parameterType ?? null;
        if (expected === null) {
            if (rule.value !== null && rule.value !== undefined) {
                throw new Error(`Rule '${rule.code}' does not accept a parameter.`);
            }
            return;
        }
        if (rule.value === null || rule.value === undefined) {
            throw new Error(`Rule '${rule.code}' requires a parameter of type ${expected}.`);
        }
        if (!parameterMatches(expected, rule.value)) {
            throw new Error(
                `Rule '${rule.code}' requires a parameter of type ${expected}, got ${describeType(rule.value)}.`
            );
        }
    }
}

/** Whether a rule argument fits a catalog parameter type. */
function parameterMatches(expected: ParameterType, value: unknown): boolean {
    switch (expected) {
        case ParameterType.STRING: return typeof value === 'string';
        case ParameterType.NUMBER: return typeof value === 'number';
        case ParameterType.BOOLEAN: return typeof value === 'boolean';
        case ParameterType.OBJECT: return typeof value === 'object' && value !== null;
        case ParameterType.LIST: return Array.isArray(value);
    }
}

/**
 * Validates a built-in rule's argument against the catalog at construction —
 * the same check `regl-java`'s `Rule` record performs, so a rule built with a
 * missing, extra, or wrongly typed argument is rejected in both engines
 * before any document is read.
 */
function validateBuiltInParameter(code: string, expected: ParameterType | null, value: unknown): void {
    if (expected === null) {
        if (value !== null && value !== undefined) {
            throw new Error(`Rule '${code}' does not accept a parameter.`);
        }
        return;
    }
    if (value === null || value === undefined) {
        throw new Error(`Rule '${code}' requires a parameter of type ${expected}.`);
    }
    if (!parameterMatches(expected, value)) {
        throw new Error(`Rule '${code}' requires a parameter of type ${expected}.`);
    }
}

export class Rule {
    constructor(
        public readonly code: string,
        public readonly value?: any,
        public readonly description?: string,
        public readonly warnOnly: boolean = false
    ) {
        if (this.code === null || this.code === undefined) {
            throw new Error('Rule code cannot be null.');
        }
        // A built-in rule's argument is checked here, exactly as in regl-java;
        // a custom code is checked at validation time, against its evaluator.
        const builtIn = RuleEvaluators.builtInParameterType(this.code);
        if (builtIn !== undefined) {
            validateBuiltInParameter(this.code, builtIn, this.value);
        }
    }

    public get param(): any {
        return this.value;
    }

    static custom(code: string, value?: any, description?: string, warnOnly: boolean = false): Rule {
        return new Rule(code, value, description, warnOnly);
    }

    withWarnOnly(warnOnly: boolean): Rule {
        return new Rule(this.code, this.value, this.description, warnOnly);
    }

    validate(field: string, value: any): ValidationResult {
        const evaluator = RuleEvaluators.require(this.code);
        RuleEvaluators.checkParameter(this, evaluator);
        if (RuleEvaluators.test(evaluator, value, this.value)) {
            return { valid: true, errors: [], warnings: [] };
        }
        const message = RuleEvaluators.message(evaluator, field, value, this.value);
        const error: ValidationError = {
            field,
            rule: this.code,
            value: this.value,
            message
        };
        return this.warnOnly
            ? { valid: true, errors: [], warnings: [error] }
            : { valid: false, errors: [error], warnings: [] };
    }
}

registerBuiltIns();

/**
 * One factory per catalog rule, so a rule can be named as a value rather than
 * called as a builder method. They read as a plain list:
 *
 * ```ts
 * schema.field('server.port', integer().with(notNull, lessThan(65536)));
 * ```
 *
 * The builder chain stays the fully type-checked form; these exist so a terse
 * rule list need not fall back to raw `new Rule('lessThan', 65536)` calls. Each
 * parameter type is the catalog's `parameterType`, and a test asserts these
 * factories cover every catalog id and nothing more, so the two cannot drift.
 */



export const minLength = (value: number) => new Rule('minLength', value);

export const maxLength = (value: number) => new Rule('maxLength', value);

export const length = (value: number) => new Rule('length', value);

export const pattern = (value: string) => new Rule('pattern', value);

export const contains = (value: string) => new Rule('contains', value);

export const startsWith = (value: string) => new Rule('startsWith', value);

export const endsWith = (value: string) => new Rule('endsWith', value);

export const email = new Rule('email');

export const url = new Rule('url');

export const uuid = new Rule('uuid');

export const languageCode = new Rule('languageCode');

export const lowercase = new Rule('lowercase');

export const uppercase = new Rule('uppercase');

export const trimmed = new Rule('trimmed');

export const notEmpty = new Rule('notEmpty');

export const text = new Rule('text');

export const dateFormat = (value: string) => new Rule('dateFormat', value);

export const isoDate = new Rule('isoDate');

export const isoDateTime = new Rule('isoDateTime');

export const isoTime = new Rule('isoTime');

export const notNull = new Rule('notNull');

export const enumOf = (...allowed: string[]) => new Rule('enum', allowed);

export const after = (value: string) => new Rule('after', value);

export const before = (value: string) => new Rule('before', value);

export const greaterThan = (value: number) => new Rule('greaterThan', value);

export const greaterThanOrEqual = (value: number) => new Rule('greaterThanOrEqual', value);

export const lessThan = (value: number) => new Rule('lessThan', value);

export const lessThanOrEqual = (value: number) => new Rule('lessThanOrEqual', value);

export const positive = new Rule('positive');

export const negative = new Rule('negative');

export const positiveInteger = new Rule('positiveInteger');

export const negativeInteger = new Rule('negativeInteger');

export const multipleOf = (value: number) => new Rule('multipleOf', value);

export const precision = (value: number) => new Rule('precision', value);

export const epoch = new Rule('epoch');

export const epochMillis = new Rule('epochMillis');

export const range = (value: Range) => new Rule('range', value);

export const unique = new Rule('unique');

export const isTrue = new Rule('isTrue');

export const isFalse = new Rule('isFalse');
