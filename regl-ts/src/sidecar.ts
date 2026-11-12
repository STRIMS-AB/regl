/**
 * The `.regl.yaml` sidecar pipeline (regl spec §12).
 *
 * A sidecar document accompanies one raw data file and carries three optional
 * sections alongside the existing schema-file keys:
 *
 * 1. `fields`    — per-column type & validation rules (`Sheet.*.column`).
 * 2. `transforms` — ordered value transformations, scoped to a whole column,
 *    to rows matching a value (`when.equals`), or to one row (`rowHash`).
 * 3. `ignore`    — row hashes per sheet that are excluded from the dataset.
 *
 * `runSidecarPipeline` executes them in the canonical order:
 *
 *     ignore → transforms → validation
 *
 * so validation always judges post-transform values and never reports on
 * ignored rows.
 */
import { TargetType, Rule } from './index';
import { Schema, fieldOf, DEFAULT_MAX_DEPTH } from './schema';
import { Transform, TransformType, applyTransformChain } from './transforms';

// ---------------------------------------------------------------------------
// Sidecar document model
// ---------------------------------------------------------------------------

/**
 * A terse transform step exactly as written in YAML:
 *
 * ```yaml
 * - trim                      # no parameter
 * - constant: COBADEFFXXX     # single-key map: id -> parameter
 * - replace: { pattern: INV-, replacement: SEPA-INV- }
 * ```
 */
export type TransformStep = string | Record<string, any>;

/**
 * One ordered transform rule. `path` uses the §12 wildcard form
 * `SheetName.*.column`. The rule applies to every matching cell unless
 * narrowed by `rowHash` (a deterministic raw row hash) and/or `when.equals`
 * (the cell's current value).
 */
export interface TransformRule {
    path: string;
    rowHash?: string;
    when?: { equals?: any };
    apply: TransformStep[];
}

/** A `fields` entry: declared type plus terse rule steps. */
export interface SidecarFieldSpec {
    type?: string;
    rules?: TransformStep[];
}

/** The parsed `.regl.yaml` sidecar document. */
export interface ReglSidecar {
    strict?: boolean;
    maxDepth?: number;
    fields?: Record<string, SidecarFieldSpec>;
    transforms?: TransformRule[];
    /** Sheet name -> row hashes excluded from ingestion and validation. */
    ignore?: Record<string, string[]>;
}

/** A fresh, empty sidecar. */
export function defaultSidecar(): ReglSidecar {
    return { strict: false };
}

// ---------------------------------------------------------------------------
// Row hashing — must stay byte-compatible with popfile's AdjustmentEngine
// ---------------------------------------------------------------------------

const HASH_EXCLUDED_KEYS = [
    '_source_file', '_source_file_id', '_source_table_id', '_is_ignored',
    '_zone', '_row_hash', '_line_num', '_raw', '_adjusted_cells', 'id'
];

/**
 * Deterministic row hash over the row's *raw* values (`_raw` when present),
 * so a re-import of identical raw content matches the same hash regardless
 * of line order or prior cell adjustments. Rendered as `h_<16 hex>`.
 */
export function computeRowHash(row: Record<string, any>): string {
    const source = row && typeof row === 'object' && row._raw && typeof row._raw === 'object'
        ? row._raw
        : (row || {});
    const keys = Object.keys(source)
        .filter(k => !k.startsWith('_') && !HASH_EXCLUDED_KEYS.includes(k))
        .sort();
    const targetKeys = keys.length > 0
        ? keys
        : Object.keys(source).filter(k => !k.startsWith('_')).sort();
    const canonicalString = targetKeys
        .map(k => {
            const val = source[k];
            const strVal = val === undefined || val === null ? '' : String(val).trim();
            return `${k}:${strVal}`;
        })
        .join('|');

    let hash1 = 0x811c9dc5;
    let hash2 = 0x1000193;
    for (let i = 0; i < canonicalString.length; i++) {
        const code = canonicalString.charCodeAt(i);
        hash1 ^= code;
        hash1 = Math.imul(hash1, 0x01000193);
        hash2 ^= code;
        hash2 = Math.imul(hash2, 0x5bd1e995);
    }
    const h1 = (hash1 >>> 0).toString(16).padStart(8, '0');
    const h2 = (hash2 >>> 0).toString(16).padStart(8, '0');
    return `h_${h1}${h2}`;
}

// ---------------------------------------------------------------------------
// Terse step parsing / serialization
// ---------------------------------------------------------------------------

/**
 * Parses one terse transform step into a canonical `{ type, param }`.
 *
 * - `"trim"` -> `{ type: 'trim' }`
 * - `{ constant: 'X' }` -> `{ type: 'constant', param: 'X' }`
 * - `{ replace: { pattern, replacement } }` -> `{ type: 'replace', param: {...} }`
 *
 * @throws if the step is not a known single-key map or bare id.
 */
export function parseTransformStep(step: TransformStep): Transform {
    if (typeof step === 'string') {
        return { type: step };
    }
    if (step && typeof step === 'object' && !Array.isArray(step)) {
        const keys = Object.keys(step);
        if (keys.length !== 1) {
            throw new Error(
                `Transform step must be a bare id or a single-key map, got keys: [${keys.join(', ')}]`
            );
        }
        return { type: keys[0], param: step[keys[0]] };
    }
    throw new Error(`Invalid transform step: ${JSON.stringify(step)}`);
}

/** Parses a whole terse `apply` list. */
export function parseTransformSteps(steps: TransformStep[]): Transform[] {
    return (steps || []).map(parseTransformStep);
}

/**
 * Renders a canonical `{ type, param }` back to the terse YAML form, the
 * inverse of `parseTransformStep`, so callers can edit and re-serialize a
 * sidecar without losing the terse syntax.
 */
export function serializeTransformStep(transform: Transform): TransformStep {
    if (transform.param === undefined) return transform.type;
    return { [transform.type]: transform.param };
}

// ---------------------------------------------------------------------------
// `fields` -> Schema
// ---------------------------------------------------------------------------

/** Resolves a declared `type` string (case-insensitive) to a `TargetType`. */
export function resolveSidecarType(type: string | undefined): TargetType {
    if (type === undefined || type === null || type === '') return TargetType.ANY;
    const upper = String(type).toUpperCase();
    const target = (TargetType as any)[upper];
    if (target === undefined) {
        throw new Error(`Unknown field type '${type}'.`);
    }
    return target as TargetType;
}

/**
 * Parses one terse validation rule step, honouring the `!` warning-severity
 * suffix (`notNull!` -> warn-only `notNull`).
 */
export function parseRuleStep(step: TransformStep): Rule {
    if (typeof step === 'string') {
        const warn = step.endsWith('!');
        const code = warn ? step.slice(0, -1) : step;
        return new Rule(code, undefined, undefined, warn);
    }
    if (step && typeof step === 'object' && !Array.isArray(step)) {
        const keys = Object.keys(step);
        if (keys.length !== 1) {
            throw new Error(
                `Rule step must be a bare id or a single-key map, got keys: [${keys.join(', ')}]`
            );
        }
        let code = keys[0];
        let warn = false;
        if (code.endsWith('!')) {
            code = code.slice(0, -1);
            warn = true;
        }
        return new Rule(code, step[keys[0]], undefined, warn);
    }
    throw new Error(`Invalid rule step: ${JSON.stringify(step)}`);
}

/**
 * Builds a validating `Schema` from a sidecar's `fields` section.
 *
 * Paths keep their §12 form (`Sheet.*.column`); the document handed to
 * `Schema.validate` must be `{ SheetName: [ rowObjects ] }`.
 */
export function buildSchemaFromSidecar(sidecar: ReglSidecar): Schema {
    let schema = Schema.create();
    if (sidecar.strict) schema = schema.strict();
    const maxDepth = sidecar.maxDepth;
    if (maxDepth !== undefined && maxDepth !== null) {
        if (maxDepth < 1) throw new Error('maxDepth must be at least 1.');
        schema = schema.maxDepth(maxDepth);
    }
    const fields = sidecar.fields || {};
    for (const path of Object.keys(fields)) {
        const spec = fields[path] || {};
        const type = resolveSidecarType(spec.type);
        const rules = (spec.rules || []).map(parseRuleStep);
        schema = schema.field(path, fieldOf(type, rules));
    }
    return schema;
}

// ---------------------------------------------------------------------------
// Pipeline
// ---------------------------------------------------------------------------

/** A row excluded by the `ignore` section. */
export interface IgnoredRow {
    sheet: string;
    index: number;
    rowHash: string;
}

/** One cell actually rewritten by a transform rule. */
export interface AppliedTransform {
    sheet: string;
    index: number;
    rowHash: string;
    column: string;
    from: any;
    to: any;
    ruleIndex: number;
}

/** Everything the pipeline learned about one tabular document. */
export interface SidecarPipelineResult {
    /** Effective rows: same sheets, same order, transformed values. */
    document: Record<string, any[]>;
    ignored: IgnoredRow[];
    transformsApplied: AppliedTransform[];
    /** Validation over post-transform values; paths are `Sheet.index.column`. */
    validation: { valid: boolean; errors: any[]; warnings: any[] };
}

/** A tabular document: sheet name -> array of row objects. */
export type TabularDocument = Record<string, any[]>;

/** The row hash a pipeline row carries, preferring its stamped `_row_hash`. */
function rowHashOf(row: Record<string, any>): string {
    if (row && typeof row._row_hash === 'string' && row._row_hash) return row._row_hash;
    return computeRowHash(row || {});
}

/** The non-metadata snapshot of a row, used as the transform base. */
function rawOf(row: Record<string, any>): Record<string, any> {
    if (row && row._raw && typeof row._raw === 'object') return { ...row._raw };
    return Object.fromEntries(Object.entries(row || {}).filter(([k]) => !k.startsWith('_')));
}

/**
 * Runs the sidecar pipeline over a tabular document.
 *
 * Order is fixed (spec §12):
 * 1. **Ignore** — rows whose hash is listed for their sheet are flagged
 *    `_is_ignored` and excluded from transforms and validation.
 * 2. **Transforms** — rules run in declaration order against the raw snapshot
 *    of each row, producing effective cell values.
 * 3. **Validation** — `fields` rules run via the regl `Schema` on
 *    post-transform values.
 *
 * The input document is not mutated; effective rows are returned copies that
 * keep `_raw` and `_row_hash` so downstream consumers can re-run the pipeline.
 */
export function runSidecarPipeline(
    document: TabularDocument,
    sidecar: ReglSidecar,
    options: { custom?: (reference: string) => ((value: any) => any) | undefined; dateFormat?: string } = {}
): SidecarPipelineResult {
    const effective: Record<string, any[]> = {};
    const ignored: IgnoredRow[] = [];
    const transformsApplied: AppliedTransform[] = [];
    const ignoreIndex = sidecar.ignore || {};

    // 1. Ignore pass
    for (const sheet of Object.keys(document)) {
        const rows = document[sheet] || [];
        const ignoreHashes = new Set(ignoreIndex[sheet] || []);
        const kept: any[] = [];
        for (let i = 0; i < rows.length; i++) {
            const row = rows[i] || {};
            const rowHash = rowHashOf(row);
            if (ignoreHashes.size > 0 && ignoreHashes.has(rowHash)) {
                ignored.push({ sheet, index: i, rowHash });
                kept.push({ ...row, _row_hash: rowHash, _is_ignored: true });
                continue;
            }
            kept.push({ ...row, _row_hash: rowHash, _is_ignored: false });
        }
        effective[sheet] = kept;
    }

    // 2. Transform pass
    const rules = sidecar.transforms || [];
    for (const rule of rules) {
        const parsed = parseTransformSteps(rule.apply || []);
        if (parsed.length === 0) continue;
        const segments = String(rule.path || '').split('.');
        if (segments.length !== 3 || segments[1] !== '*') {
            throw new Error(
                `Transform path '${rule.path}' must have the form 'SheetName.*.column'.`
            );
        }
        const [sheet, , column] = segments;
        const rows = effective[sheet];
        if (!rows) continue;
        for (let i = 0; i < rows.length; i++) {
            const row = rows[i];
            if (row._is_ignored) continue;
            if (rule.rowHash && row._row_hash !== rule.rowHash) continue;
            if (!(column in row) && !rawOf(row).hasOwnProperty(column)) continue;
            if (rule.when && rule.when.equals !== undefined) {
                const current = row[column];
                const match = rule.when.equals;
                const equal = current === match || String(current ?? '').trim() === String(match ?? '').trim();
                if (!equal) continue;
            }
            const from = row[column];
            const to = applyTransformChain(from, parsed, options.custom, options.dateFormat);
            if (to !== from) {
                rows[i] = { ...row, [column]: to };
                transformsApplied.push({
                    sheet, index: i, rowHash: row._row_hash, column, from, to, ruleIndex: rules.indexOf(rule)
                });
            }
        }
    }

    // 3. Validation pass
    //
    // Every row is validated — including ignored ones — so a finding's
    // `Sheet.index.column` path lines up with the document's row indices.
    // Callers suppress findings for rows the ignore pass flagged.
    // `_`-prefixed metadata is stripped so strict mode does not report it.
    let validation = { valid: true, errors: [] as any[], warnings: [] as any[] };
    const hasFields = sidecar.fields && Object.keys(sidecar.fields).length > 0;
    if (hasFields) {
        const docForValidation: TabularDocument = {};
        for (const sheet of Object.keys(effective)) {
            docForValidation[sheet] = effective[sheet].map(r =>
                Object.fromEntries(Object.entries(r || {}).filter(([k]) => !k.startsWith('_')))
            );
        }
        const schema = buildSchemaFromSidecar(sidecar);
        validation = schema.validate(docForValidation);
    }

    return { document: effective, ignored, transformsApplied, validation };
}

/**
 * Splits a finding path (`Sheet.index.column`) into its parts, or `undefined`
 * when the path does not have that shape.
 */
export function parseFindingPath(path: string): { sheet: string; index: number; column: string } | undefined {
    const segments = String(path || '').split('.');
    if (segments.length < 3) return undefined;
    const column = segments.slice(2).join('.');
    const index = Number(segments[1]);
    if (!Number.isInteger(index)) return undefined;
    return { sheet: segments[0], index, column };
}

/** Errors and warnings for one row; `index` is the document's row index. */
export function findingsForRow(
    validation: { errors: any[]; warnings: any[] },
    sheet: string,
    index: number
): { errors: any[]; warnings: any[] } {
    const match = (f: any) => {
        const parsed = parseFindingPath(f.path);
        return parsed !== undefined && parsed.sheet === sheet && parsed.index === index;
    };
    return {
        errors: (validation.errors || []).filter(match),
        warnings: (validation.warnings || []).filter(match)
    };
}

export type { Transform, TransformType };
