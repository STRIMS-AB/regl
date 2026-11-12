import { RuleEvaluators, ParameterType, TargetType, matchesTargetType, describeType } from './index';

const EMAIL_PATTERN = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;
const UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const LANG_TAG_PATTERN = /^[a-zA-Z]{2,3}(-[a-zA-Z]{3}(-[a-zA-Z]{3}){0,2})?(-[a-zA-Z]{4})?(-[a-zA-Z]{2}|-[0-9]{3})?(-([a-zA-Z0-9]{5,8}|[0-9][a-zA-Z0-9]{3}))*(-x(-[a-zA-Z0-9]{1,8})+)?$/;

/** Same limit as the Java engine's `Regex.MAX_PATTERN_LENGTH`. */
const MAX_PATTERN_LENGTH = 1000;
const REGEX_CACHE_SIZE = 256;
const regexCache = new Map<string, RegExp>();

/**
 * Compiles a `pattern` rule's regex once per distinct source. Schemas may come from a URL, so
 * the pattern is untrusted: over-long ones are rejected (as in Java). Unlike Java, a running
 * JavaScript match cannot be interrupted, so there is no match timeout here.
 */
function compileRegex(source: string): RegExp {
    if (source.length > MAX_PATTERN_LENGTH) {
        throw new Error(`Regex pattern is longer than ${MAX_PATTERN_LENGTH} characters.`);
    }
    let re = regexCache.get(source);
    if (re) {
        regexCache.delete(source);
    } else {
        re = new RegExp(source);
        if (regexCache.size >= REGEX_CACHE_SIZE) {
            regexCache.delete(regexCache.keys().next().value as string);
        }
    }
    regexCache.set(source, re);
    return re;
}

function reg<P>(code: string, name: string, desc: string, type: ParameterType | null, targetType: TargetType, test: (v: any, p?: P) => boolean, msg: (f: string, v: any, p?: P) => string) {
    RuleEvaluators.registerBuiltIn({
        code,
        name,
        description: desc,
        parameterType: type,
        targetType,
        // A value of the wrong kind cannot be tested by this rule, so it counts
        // as a failure rather than reaching a predicate that would coerce it and
        // report a misleading verdict. Rule.validate words it as a type mismatch.
        test: (v, p) => matchesTargetType(targetType, v) && test(v, p),
        message: (f, v, p) =>
            matchesTargetType(targetType, v)
                ? msg(f, v, p)
                : `Value must be of type ${targetType}, but got ${describeType(v)}.`
    });
}

function regFlag(code: string, name: string, desc: string, targetType: TargetType, test: (v: any) => boolean, msg: (f: string, v: any) => string) {
    reg(code, name, desc, null, targetType, test, msg);
}

/**
 * Counts the decimal places of `value` in plain (non-exponent) notation,
 * discarding trailing zeros and never returning a negative count.
 *
 * `String(1e-7)` is `"1e-7"`, which has no `.` and would naively count as
 * zero decimals, so exponent forms are expanded first. This mirrors
 * `decimalPlaces` in regl-java and keeps the two engines in step.
 */
function decimalPlaces(value: number): number {
    if (!Number.isFinite(value)) return 0;
    const s = String(Math.abs(value));
    const eIndex = s.search(/[eE]/);
    let mantissa = eIndex >= 0 ? s.slice(0, eIndex) : s;
    const exponent = eIndex >= 0 ? parseInt(s.slice(eIndex + 1), 10) : 0;

    let intPart = mantissa;
    let fracPart = '';
    const dot = mantissa.indexOf('.');
    if (dot >= 0) {
        intPart = mantissa.slice(0, dot);
        fracPart = mantissa.slice(dot + 1);
    }

    // Shift the decimal point by the exponent, keeping enough digits.
    let digits = intPart + fracPart;
    let pointPos = intPart.length + exponent;

    if (pointPos <= 0) {
        digits = '0'.repeat(-pointPos) + digits;
        pointPos = 0;
    }
    if (pointPos > digits.length) {
        digits = digits + '0'.repeat(pointPos - digits.length);
    }

    let frac = digits.slice(pointPos).replace(/0+$/, '');
    return frac.length;
}

export function registerBuiltIns() {
    // --- STRING ---
    reg<number>('minLength', 'Minimum Length', 'Requires text to be at least {value} characters.', ParameterType.NUMBER, TargetType.STRING,
        (v, p) => typeof v !== 'string' || v.length >= (p ?? 0),
        (f, v, p) => `Minimum length is ${p}, but got ${v.length}.`
    );
    reg<number>('maxLength', 'Maximum Length', 'Requires text to be no longer than {value} characters.', ParameterType.NUMBER, TargetType.STRING,
        (v, p) => typeof v !== 'string' || v.length <= (p ?? Number.MAX_SAFE_INTEGER),
        (f, v, p) => `Maximum length is ${p}, but got ${v.length}.`
    );
    reg<number>('length', 'Exact Length', 'Requires text to be exactly {value} characters.', ParameterType.NUMBER, TargetType.STRING,
        (v, p) => typeof v !== 'string' || p === undefined || v.length === p,
        (f, v, p) => `Exact length must be ${p}, but got ${v.length}.`
    );
    reg<string>('pattern', 'Regex Pattern', 'Requires text to match the regular expression: {value}.', ParameterType.STRING, TargetType.STRING,
        (v, p) => typeof v !== 'string' || (p !== undefined && compileRegex(p).test(v)),
        (f, v, p) => `Value does not match required regex pattern: ${p}`
    );
    reg<string>('contains', 'Contains Text', 'Requires text to contain the substring: {value}.', ParameterType.STRING, TargetType.STRING,
        (v, p) => typeof v !== 'string' || (p !== undefined && v.includes(p)),
        (f, v, p) => `Value must contain '${p}'.`
    );
    reg<string>('startsWith', 'Starts With', 'Requires text to start with: {value}.', ParameterType.STRING, TargetType.STRING,
        (v, p) => typeof v !== 'string' || (p !== undefined && v.startsWith(p)),
        (f, v, p) => `Value must start with '${p}'.`
    );
    reg<string>('endsWith', 'Ends With', 'Requires text to end with: {value}.', ParameterType.STRING, TargetType.STRING,
        (v, p) => typeof v !== 'string' || (p !== undefined && v.endsWith(p)),
        (f, v, p) => `Value must end with '${p}'.`
    );
    regFlag('email', 'Email Address', 'Requires a valid email address format.', TargetType.STRING,
        (v) => EMAIL_PATTERN.test(String(v)),
        () => `Value is not a valid email address.`
    );
    regFlag('url', 'URL', 'Requires a valid HTTP or HTTPS URL.', TargetType.STRING,
        (v) => {
            try {
                const u = new URL(String(v));
                return u.protocol === 'http:' || u.protocol === 'https:';
            } catch { return false; }
        },
        () => `Value must be a valid HTTP or HTTPS URL.`
    );
    regFlag('uuid', 'UUID', 'Requires a valid UUID format.', TargetType.STRING,
        (v) => UUID_PATTERN.test(String(v)),
        () => `Value is not a valid UUID format.`
    );
    regFlag('languageCode', 'Language Code', 'Requires a well-formed BCP-47 language tag.', TargetType.STRING,
        (v) => LANG_TAG_PATTERN.test(String(v)),
        () => `Value is not a well-formed BCP-47 language tag (e.g. 'en', 'pt-BR').`
    );
    regFlag('lowercase', 'Lowercase', 'Requires all characters to be lowercase.', TargetType.STRING,
        (v) => String(v) === String(v).toLowerCase(),
        () => `Value must be all lowercase.`
    );
    regFlag('uppercase', 'Uppercase', 'Requires all characters to be uppercase.', TargetType.STRING,
        (v) => String(v) === String(v).toUpperCase(),
        () => `Value must be all uppercase.`
    );
    regFlag('trimmed', 'Trimmed', 'Requires no leading or trailing whitespace.', TargetType.STRING,
        (v) => String(v) === String(v).trim(),
        () => `Value must not have leading or trailing whitespace.`
    );
    regFlag('notEmpty', 'Not Empty', 'Requires text to contain at least one non-whitespace character.', TargetType.STRING,
        (v) => String(v).trim().length > 0,
        () => `Value cannot be blank or empty.`
    );
    regFlag('text', 'Human-Readable Text', 'Marks a field as human-readable text.', TargetType.STRING,
        (v) => true,
        (f) => `Field '${f}' is declared as human-readable text.`
    );
    reg<string>('dateFormat', 'Date Format', 'Requires date/time string to match format pattern: {value}.', ParameterType.STRING, TargetType.STRING,
        (v, p) => {
            if (typeof v !== 'string' || !p) return true;
            if (p === 'YYYY-MM-DD') return /^\d{4}-\d{2}-\d{2}$/.test(v) && !isNaN(Date.parse(v));
            if (p === 'HH:mm:ss') return /^\d{2}:\d{2}:\d{2}$/.test(v);
            if (p === 'iso' || p === 'iso8601') return !isNaN(Date.parse(v));
            const regexStr = '^' + p
                .replace(/YYYY/g, '\\d{4}')
                .replace(/MM/g, '\\d{2}')
                .replace(/DD/g, '\\d{2}')
                .replace(/HH/g, '\\d{2}')
                .replace(/mm/g, '\\d{2}')
                .replace(/ss/g, '\\d{2}') + '$';
            return new RegExp(regexStr).test(v);
        },
        (f, v, p) => `Value '${v}' does not match date format '${p}'.`
    );
    regFlag('isoDate', 'ISO Date', 'Requires a valid ISO-8601 calendar date (YYYY-MM-DD).', TargetType.STRING,
        (v) => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) && !isNaN(Date.parse(v)),
        () => `Value must be a valid ISO-8601 date (YYYY-MM-DD).`
    );
    regFlag('isoDateTime', 'ISO Date-Time', 'Requires a valid ISO-8601 date-time timestamp.', TargetType.STRING,
        (v) => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}/.test(v) && !isNaN(Date.parse(v)),
        () => `Value must be a valid ISO-8601 date-time timestamp.`
    );
    regFlag('isoTime', 'ISO Time', 'Requires a valid ISO-8601 time (HH:mm:ss).', TargetType.STRING,
        (v) => typeof v === 'string' && /^\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/.test(v),
        () => `Value must be a valid ISO-8601 time (HH:mm:ss).`
    );
    // TargetType.ANY, so it is also the one rule that can accept a null: the type
    // guard lets null through and this predicate is what rejects it.
    regFlag('notNull', 'Not Null', 'Requires the value to be present and not null.', TargetType.ANY,
        (v) => v !== null && v !== undefined,
        () => `Value is required and must not be null.`
    );
    reg<string[]>('enum', 'Allowed Values', 'Restricts the value to a predefined list of allowed strings: {value}.', ParameterType.LIST, TargetType.ANY,
        (v, p) => {
            if (typeof v !== 'string') return true;
            return p === undefined || p.length === 0 || p.includes(v);
        },
        (f, v, p) => `Value '${v}' is not in allowed values: ${p}`
    );
    reg<string>('after', 'After Date', 'Requires date/time to be chronologically after {value}.', ParameterType.STRING, TargetType.STRING,
        (v, p) => {
            if (!v || !p) return true;
            const parseMillis = (val: any) => typeof val === 'number' ? (val < 100000000000 ? val * 1000 : val) : Date.parse(val);
            const mA = parseMillis(v);
            const mB = parseMillis(p);
            return !isNaN(mA) && !isNaN(mB) && mA > mB;
        },
        (f, v, p) => `Value '${v}' must be chronologically after '${p}'.`
    );
    reg<string>('before', 'Before Date', 'Requires date/time to be chronologically before {value}.', ParameterType.STRING, TargetType.STRING,
        (v, p) => {
            if (!v || !p) return true;
            const parseMillis = (val: any) => typeof val === 'number' ? (val < 100000000000 ? val * 1000 : val) : Date.parse(val);
            const mA = parseMillis(v);
            const mB = parseMillis(p);
            return !isNaN(mA) && !isNaN(mB) && mA < mB;
        },
        (f, v, p) => `Value '${v}' must be chronologically before '${p}'.`
    );

    // --- NUMBER ---
    reg<number>('greaterThan', 'Greater Than', 'Requires the number to be strictly greater than {value}.', ParameterType.NUMBER, TargetType.NUMBER,
        (v, p) => typeof v !== 'number' || v > (p ?? 0),
        (f, v, p) => `Value must be strictly greater than ${p ?? 0}.`
    );
    reg<number>('greaterThanOrEqual', 'Greater Than or Equal', 'Requires the number to be greater than or equal to {value}.', ParameterType.NUMBER, TargetType.NUMBER,
        (v, p) => typeof v !== 'number' || v >= (p ?? 0),
        (f, v, p) => `Value must be greater than or equal to ${p ?? 0}.`
    );
    reg<number>('lessThan', 'Less Than', 'Requires the number to be strictly less than {value}.', ParameterType.NUMBER, TargetType.NUMBER,
        (v, p) => typeof v !== 'number' || v < (p ?? 0),
        (f, v, p) => `Value must be strictly less than ${p ?? 0}.`
    );
    reg<number>('lessThanOrEqual', 'Less Than or Equal', 'Requires the number to be less than or equal to {value}.', ParameterType.NUMBER, TargetType.NUMBER,
        (v, p) => typeof v !== 'number' || v <= (p ?? 0),
        (f, v, p) => `Value must be less than or equal to ${p ?? 0}.`
    );
    regFlag('positive', 'Positive Number', 'Requires a number strictly greater than zero.', TargetType.NUMBER,
        (v) => v > 0,
        () => `Value must be positive (> 0).`
    );
    regFlag('negative', 'Negative Number', 'Requires a number strictly less than zero.', TargetType.NUMBER,
        (v) => v < 0,
        () => `Value must be negative (< 0).`
    );
    regFlag('positiveInteger', 'Positive Integer', 'Requires a whole number strictly greater than zero.', TargetType.NUMBER,
        (v) => Number.isInteger(v) && v > 0,
        () => `Value must be an integer greater than zero.`
    );
    regFlag('negativeInteger', 'Negative Integer', 'Requires a whole number strictly less than zero.', TargetType.NUMBER,
        (v) => Number.isInteger(v) && v < 0,
        () => `Value must be an integer less than zero.`
    );
    reg<number>('multipleOf', 'Multiple Of', 'Requires the number to be a multiple of {value}.', ParameterType.NUMBER, TargetType.NUMBER,
        (v, p) => {
            if (typeof v !== 'number') return true;
            const step = p ?? 1;
            return step <= 0 || Math.abs(v % step) <= 0.000001;
        },
        (f, v, p) => `Value must be a multiple of ${p ?? 1}.`
    );
    reg<number>('precision', 'Decimal Precision', 'Restricts the maximum number of decimal places to {value}.', ParameterType.NUMBER, TargetType.NUMBER,
        (v, p) => {
            if (typeof v !== 'number') return true;
            return decimalPlaces(v) <= (p ?? 0);
        },
        (f, v, p) => {
            return `Value has ${decimalPlaces(v)} decimal places, maximum is ${p ?? 0}.`;
        }
    );
    regFlag('epoch', 'Epoch Seconds', 'Requires a whole-number Unix timestamp in seconds.', TargetType.DATETIME,
        (v) => typeof v === 'number' && Number.isInteger(v) && v >= 0,
        () => `Value must be a valid whole-number Unix timestamp in seconds.`
    );
    regFlag('epochMillis', 'Epoch Milliseconds', 'Requires a whole-number Unix timestamp in milliseconds.', TargetType.DATETIME,
        (v) => typeof v === 'number' && Number.isInteger(v) && v >= 0,
        () => `Value must be a valid whole-number Unix timestamp in milliseconds.`
    );

    // --- LIST ---
    reg<{min?: number, max?: number}>('range', 'List Size Range', 'Restricts the number of items in a list to the range {value}.', ParameterType.OBJECT, TargetType.LIST,
        (v, p) => !Array.isArray(v) || p === undefined || (v.length >= (p.min ?? 0) && v.length <= (p.max ?? Number.MAX_SAFE_INTEGER)),
        (f, v, p) => {
            const size = (v as any[]).length;
            if (p?.min !== undefined && size < p.min) {
                return `List must have at least ${p.min} items, found ${size}.`;
            }
            return `List cannot exceed ${p?.max} items, found ${size}.`;
        }
    );
    regFlag('unique', 'Unique Items', 'Requires all items in the list to be distinct.', TargetType.LIST,
        (v) => {
            if (!Array.isArray(v)) return true;
            return new Set(v).size === v.length;
        },
        (f) => `All elements in list '${f}' must be unique.`
    );

    // --- BOOLEAN ---
    regFlag('isTrue', 'Is True', 'Requires a boolean value to be true.', TargetType.BOOLEAN,
        (v) => typeof v === 'boolean' && v,
        () => `Field must be true.`
    );
    regFlag('isFalse', 'Is False', 'Requires a boolean value to be false.', TargetType.BOOLEAN,
        (v) => typeof v === 'boolean' && !v,
        () => `Field must be false.`
    );
}
