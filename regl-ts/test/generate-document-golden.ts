import { Schema, TargetType, Rule, fieldOf, findings } from '../src/index';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Writes the golden document transcript that both engines assert against.
 *
 * Run with `npm run golden`. Read the diff before committing: every line here is
 * observable behaviour of the public API in both engines.
 *
 * Message text is deliberately excluded. Messages are for humans and may be worded
 * per locale; `severity`, `kind`, `path`, and `rule` are what consumers branch on.
 */

function specDir(): string {
    let dir = __dirname;
    for (let i = 0; i < 6 && dir; i++, dir = join(dir, '..')) {
        try {
            readFileSync(join(dir, 'regl-spec', 'catalog.json'));
            return join(dir, 'regl-spec');
        } catch {
            // keep walking up
        }
    }
    throw new Error('Could not locate regl-spec');
}

const OUT = join(specDir(), 'conformance', 'document-expected.json');

interface RuleSpec {
    code: string;
    value?: any;
    warnOnly?: boolean;
}
interface FieldSpec {
    path: string;
    type: string;
    rules: RuleSpec[];
}
interface CaseSpec {
    name: string;
    fields: FieldSpec[];
    documents: Array<{ name: string; doc: any }>;
}

const spec = JSON.parse(
    readFileSync(join(specDir(), 'conformance', 'document-cases.json'), 'utf8')
) as {
    cases: CaseSpec[];
    strictCases: CaseSpec[];
};

function toRule(r: RuleSpec): Rule {
    return new Rule(r.code, r.value, undefined, r.warnOnly === true);
}

function build(fields: FieldSpec[], strict: boolean): Schema {
    let schema = Schema.create();
    for (const f of fields) {
        schema = schema.field(
            f.path,
            fieldOf(TargetType[f.type as keyof typeof TargetType], f.rules.map(toRule))
        );
    }
    return strict ? schema.strict() : schema;
}

function transcript(result: ReturnType<Schema['validate']>): string {
    const lines = [`valid=${result.valid}`];
    for (const f of findings(result)) {
        lines.push(`${f.severity} ${f.kind} ${f.path} ${f.rule ?? '-'}`);
    }
    return lines.join('\n');
}

const rows: Array<{ id: string; findings: string }> = [];

function addGroup(group: CaseSpec[], strict: boolean, id: string): void {
    for (const c of group) {
        const schema = build(c.fields, strict);
        for (const d of c.documents) {
            rows.push({
                id: `${id}/${c.name}/${d.name}`,
                findings: transcript(schema.validate(d.doc))
            });
        }
    }
}

addGroup(spec.cases, false, 'case');
addGroup(spec.strictCases, true, 'strictCase');

writeFileSync(OUT, JSON.stringify(rows, null, 2) + '\n');
console.log(`wrote ${rows.length} rows to ${OUT}`);