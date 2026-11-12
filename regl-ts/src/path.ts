/**
 * Dot-separated paths, with `*` matching every element of a list and `\`
 * escaping a literal dot, backslash, or asterisk.
 *
 * Internal: paths are an implementation detail of `Schema` and are not part of
 * the public API surface.
 */

/** Splits a path into segments, removing escapes. */
export function parsePath(path: string): string[] {
    const out: string[] = [];
    let current = '';
    let escaping = false;
    for (const c of path) {
        if (escaping) {
            current += c;
            escaping = false;
        } else if (c === '\\') {
            escaping = true;
        } else if (c === '.') {
            out.push(current);
            current = '';
        } else {
            current += c;
        }
    }
    if (escaping) throw new Error(`Path '${path}' ends with a dangling escape.`);
    out.push(current);
    if (path === '') throw new Error('Path cannot be empty.');
    return out;
}

export function isWildcard(segment: string): boolean {
    return segment === '*';
}

/**
 * Whether a literal segment could be what a wildcard matched.
 *
 * A wildcard only ever matches list indices, so it overlaps a literal segment
 * exactly when that literal is a non-negative integer.
 */
export function isIndexLiteral(segment: string): boolean {
    if (segment.length === 0 || segment.length > 9) return false;
    if (!/^[0-9]+$/.test(segment)) return false;
    // Reject leading zeros so "0" and "00" cannot both name an index.
    return segment.length === 1 || segment[0] !== '0';
}

/**
 * Whether two declared paths could resolve to the same concrete path.
 *
 * Each wildcard substitutes one index for one segment, so the segment count is
 * fixed by the declaration and two paths of different lengths address different
 * values. Overlap is decided without looking at any document.
 */
export function overlaps(a: string[], b: string[]): boolean {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
        const x = a[i];
        const y = b[i];
        if (x === y) continue;
        const compatible =
            (isWildcard(x) && isIndexLiteral(y)) || (isWildcard(y) && isIndexLiteral(x));
        if (!compatible) return false;
    }
    return true;
}

/**
 * Joins segments back into path syntax, re-escaping each special character.
 *
 * This is how a concrete path is rendered: the declared spelling with each
 * wildcard replaced by its index (§5 of the document spec), so a key that
 * contains a dot stays one segment rather than splitting on re-parse.
 */
export function joinPath(segments: string[]): string {
    return segments
        .map(segment => segment.replace(/[\\.*]/g, c => '\\' + c))
        .join('.');
}

/**
 * Compares two paths segment by segment, with two segments that are both list
 * indices compared numerically.
 *
 * Plain string order would sort `ports.10` before `ports.2`, which is not the
 * element order a document was read in. Both engines order findings by element
 * index, so both must compare indices as numbers.
 */
export function comparePaths(a: string, b: string): number {
    const as = parsePath(a);
    const bs = parsePath(b);
    const shared = Math.min(as.length, bs.length);
    for (let i = 0; i < shared; i++) {
        const x = as[i];
        const y = bs[i];
        if (x === y) continue;
        const xi = parseIndex(x);
        const yi = parseIndex(y);
        if (xi !== null && yi !== null) return xi - yi;
        return x < y ? -1 : 1;
    }
    return as.length - bs.length;
}

/** A segment of digits only, short enough to compare without overflow. */
function parseIndex(segment: string): number | null {
    if (segment.length === 0 || segment.length > 9) return null;
    if (!/^[0-9]+$/.test(segment)) return null;
    return Number(segment);
}