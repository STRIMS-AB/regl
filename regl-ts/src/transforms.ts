/**
 * Canonical value transformation functions defined in
 * `regl-spec/transformations-catalog.json`.
 */

export type TransformType =
    | 'lowerCase'
    | 'upperCase'
    | 'trim'
    | 'titleCase'
    | 'prefix'
    | 'suffix'
    | 'stripPrefix'
    | 'stripSuffix'
    | 'replace'
    | 'substring'
    | 'join'
    | 'multiply'
    | 'divide'
    | 'abs'
    | 'round'
    | 'roundTo'
    | 'toNumber'
    | 'toString'
    | 'toBoolean'
    | 'formatDate'
    | 'default'
    | 'constant'
    | 'custom';

export interface Transform {
    type: TransformType | string;
    param?: any;
}

export type CustomTransformResolver = (reference: string) => ((value: any) => any) | undefined;

const DEFAULT_ISO_DATETIME = 'YYYY-MM-DDTHH:mm:ssZ';

const TOKEN_TO_JAVA: Record<string, string> = {
    YYYY: 'uuuu',
    MM: 'MM',
    DD: 'dd',
    HH: 'HH',
    mm: 'mm',
    ss: 'ss'
};

/**
 * Runs a transform chain left to right. Null passes through untouched except
 * through `default`, `constant`, and `custom`.
 */
export function applyTransformChain(
    value: any,
    transforms: Transform[],
    custom?: CustomTransformResolver,
    dateFormat?: string
): any {
    let current = value;
    for (const transform of transforms) {
        if (current === null || current === undefined) {
            if (transform.type !== 'default' && transform.type !== 'constant' && transform.type !== 'custom') {
                continue;
            }
        }
        current = applyTransform(current, transform, custom, dateFormat);
    }
    return current;
}

/**
 * Applies a single transform step to a value.
 */
export function applyTransform(
    value: any,
    transform: Transform,
    custom?: CustomTransformResolver,
    dateFormat?: string
): any {
    const { type, param } = transform;
    switch (type) {
        case 'lowerCase':
            return typeof value === 'string' ? value.toLowerCase() : value;
        case 'upperCase':
            return typeof value === 'string' ? value.toUpperCase() : value;
        case 'trim':
            return typeof value === 'string' ? value.trim() : value;
        case 'titleCase':
            return typeof value === 'string'
                ? value.replace(/\w\S*/g, txt => txt.charAt(0).toUpperCase() + txt.slice(1).toLowerCase())
                : value;
        case 'prefix':
            return typeof value === 'string' ? String(param) + value : value;
        case 'suffix':
            return typeof value === 'string' ? value + String(param) : value;
        case 'stripPrefix':
            return typeof value === 'string' && value.startsWith(String(param))
                ? value.slice(String(param).length)
                : value;
        case 'stripSuffix':
            return typeof value === 'string' && value.endsWith(String(param))
                ? value.slice(0, value.length - String(param).length)
                : value;
        case 'replace': {
            const pattern = String(param?.pattern ?? '');
            const replacement = String(param?.replacement ?? '');
            return typeof value === 'string' && pattern ? value.split(pattern).join(replacement) : value;
        }
        case 'substring': {
            if (typeof value !== 'string') return value;
            const start = Number(param?.start ?? 0);
            const end = param?.end;
            return end !== undefined ? value.substring(start, Math.min(Number(end), value.length)) : value.substring(start);
        }
        case 'join': {
            if (!Array.isArray(value)) return value;
            const separator = param === null || param === undefined ? '' : String(param);
            return value
                .filter((v) => v !== null && v !== undefined)
                .map((v) => String(v))
                .join(separator);
        }
        case 'multiply':
            return typeof value === 'number' ? value * Number(param ?? 1) : value;
        case 'divide':
            return typeof value === 'number' && Number(param ?? 1) !== 0 ? value / Number(param) : value;
        case 'abs':
            return typeof value === 'number' ? Math.abs(value) : value;
        case 'round':
            return typeof value === 'number' ? Math.round(value) : value;
        case 'roundTo': {
            if (typeof value !== 'number') return value;
            const factor = Math.pow(10, Number(param ?? 0));
            return Math.round(value * factor) / factor;
        }
        case 'toNumber': {
            if (typeof value === 'number') return value;
            if (typeof value === 'string' && value.trim() !== '') {
                const n = Number(value.trim());
                return Number.isNaN(n) ? value : n;
            }
            if (typeof value === 'boolean') return value ? 1 : 0;
            return value;
        }
        case 'toString':
            return typeof value === 'string' ? value : String(value);
        case 'toBoolean': {
            if (typeof value === 'boolean') return value;
            if (typeof value === 'number') return value !== 0;
            if (typeof value === 'string') {
                const t = value.trim().toLowerCase();
                if (['true', '1', 'yes'].includes(t)) return true;
                if (['false', '0', 'no'].includes(t)) return false;
            }
            return value;
        }
        case 'formatDate':
            return formatDate(value, param, dateFormat ?? DEFAULT_ISO_DATETIME);
        case 'default':
            if (value === null || value === undefined || (typeof value === 'string' && value.trim() === '')) {
                return param;
            }
            return value;
        case 'constant':
            return param;
        case 'custom': {
            if (!custom) {
                throw new Error(`Custom transform '${String(param)}' requires a custom resolver.`);
            }
            const fn = custom(String(param));
            if (!fn) {
                throw new Error(`No custom transform registered for: ${String(param)}`);
            }
            return fn(value);
        }
        default:
            return value;
    }
}

function formatDate(value: any, param: any, output: string): any {
    if (value === null || value === undefined) return value;
    const source = String(param ?? '').trim();
    const unix = source.toLowerCase() === 'unix';

    if (typeof value === 'number') {
        return formatEpoch(value, output);
    }

    if (typeof value === 'string') {
        if (unix || source === '') {
            const n = Number(value.trim());
            if (!Number.isNaN(n)) return formatEpoch(n, output);
        }
        const from = source === '' ? DEFAULT_ISO_DATETIME : source;
        const parsed = parseTokenDate(value.trim(), from);
        if (parsed) {
            return formatWithPattern(parsed, javaPattern(output));
        }
        return value;
    }
    return value;
}

function formatEpoch(epoch: number, output: string): any {
    let e = epoch;
    if (Math.abs(e) > 10_000_000_000) {
        e = e / 1000;
    }
    if (output === 'epoch') return e;
    if (output === 'epochMillis') return e * 1000;
    if (output === 'iso8601') output = DEFAULT_ISO_DATETIME;
    return formatWithPattern(new Date(e * 1000), javaPattern(output));
}

function parseTokenDate(input: string, pattern: string): Date | null {
    const tokens = ['YYYY', 'MM', 'DD', 'HH', 'mm', 'ss'];
    let regex = '';
    const order: { token: string; group: number }[] = [];
    let i = 0;
    let group = 1;
    while (i < pattern.length) {
        let matched = false;
        for (const token of tokens) {
            if (pattern.startsWith(token, i)) {
                order.push({ token, group: group++ });
                regex += token === 'YYYY' ? '(\\d{4})' : '(\\d{1,2})';
                i += token.length;
                matched = true;
                break;
            }
        }
        if (matched) continue;
        regex += pattern[i].replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        i++;
    }
    const m = new RegExp(`^${regex}$`).exec(input);
    if (!m) return null;
    const value = (token: string, fallback: number): number => {
        const entry = order.find((o) => o.token === token);
        return entry ? Number(m[entry.group]) : fallback;
    };
    const date = new Date(Date.UTC(
        value('YYYY', 1970),
        value('MM', 1) - 1,
        value('DD', 1),
        value('HH', 0),
        value('mm', 0),
        value('ss', 0)
    ));
    return Number.isNaN(date.getTime()) ? null : date;
}

function javaPattern(tokenPattern: string): string {
    let i = 0;
    let out = '';
    while (i < tokenPattern.length) {
        let matched = false;
        for (const token of Object.keys(TOKEN_TO_JAVA).sort((a, b) => b.length - a.length)) {
            if (tokenPattern.startsWith(token, i)) {
                out += TOKEN_TO_JAVA[token];
                i += token.length;
                matched = true;
                break;
            }
        }
        if (matched) continue;
        out += tokenPattern[i];
        i++;
    }
    return out;
}

function formatWithPattern(date: Date, javaPatternStr: string): string {
    const pad = (n: number) => String(n).padStart(2, '0');
    const parts: Record<string, string> = {
        uuuu: String(date.getUTCFullYear()),
        MM: pad(date.getUTCMonth() + 1),
        dd: pad(date.getUTCDate()),
        HH: pad(date.getUTCHours()),
        mm: pad(date.getUTCMinutes()),
        ss: pad(date.getUTCSeconds())
    };
    return javaPatternStr.replace(/uuuu|MM|dd|HH|mm|ss/g, (token) => parts[token] ?? token);
}
