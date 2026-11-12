import {
    Schema,
    TargetType,
    RuleEvaluators,
    Rule,
    fieldOf,
    string,
    number,
    boolean_,
    list,
    any,
    date,
    dateTime,
    time,
    findings,
    DocumentResult,
    notNull,
    integer,
    lessThan,
    email
} from '../src/index';
import * as ruleFactories from '../src/index';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Resolves the sibling regl-spec directory by walking up from this file, so the
 * suite works whether it runs from source or from the compiled test output.
 */
function specPath(relative: string): string {
    let dir = __dirname;
    for (let i = 0; i < 6 && dir; i++, dir = join(dir, '..')) {
        try {
            readFileSync(join(dir, 'regl-spec', relative));
            return join(dir, 'regl-spec', relative);
        } catch {
            // keep walking up
        }
    }
    throw new Error(`Could not locate regl-spec/${relative}`);
}

interface RuleSpec {
    code: string;
    value?: any;
    severity?: 'error' | 'warn';
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

interface Spec {
    cases: CaseSpec[];
    strictCases: CaseSpec[];
    constructionErrors: Array<{ name: string; fields: FieldSpec[] }>;
    acceptedPathPairs: Array<{ name: string; paths: string[] }>;
    formalErrors: Array<{ name: string; type: string; rules: RuleSpec[] }>;
    formalAccepted: Array<{ name: string; type: string; rules: RuleSpec[] }>;
    maxDepth: { limit: number; rejected: string; accepted: string };
}

const spec = JSON.parse(
    readFileSync(specPath('conformance/document-cases.json'), 'utf8')
) as Spec;

const golden = new Map(
    (JSON.parse(readFileSync(specPath('conformance/document-expected.json'), 'utf8')) as Array<{
        id: string;
        findings: string;
    }>).map(row => [row.id, row.findings] as [string, string])
);

let failures = 0;

function fail(message: string): void {
    failures++;
    console.error(`FAIL  ${message}`);
}

function toRule(spec: RuleSpec): Rule {
    return new Rule(spec.code, spec.value, undefined, spec.severity === 'warn');
}

function toField(spec: FieldSpec) {
    return fieldOf(TargetType[spec.type as keyof typeof TargetType], spec.rules.map(toRule));
}

function build(fields: FieldSpec[], strict: boolean): Schema {
    let schema = Schema.create();
    for (const f of fields) {
        schema = schema.field(f.path, toField(f));
    }
    return strict ? schema.strict() : schema;
}

/**
 * The normalized rendering of a result, identical in shape to the Java
 * generator's. Message text is excluded: wording may change per locale, while
 * severity, kind, path, and rule are what consumers branch on.
 */
function transcript(result: DocumentResult): string {
    const lines = [`valid=${result.valid}`];
    for (const f of findings(result)) {
        lines.push(`${f.severity} ${f.kind} ${f.path} ${f.rule ?? '-'}`);
    }
    return lines.join('\n');
}

function checkGroup(group: CaseSpec[], strict: boolean, id: string): void {
    for (const c of group) {
        let schema: Schema;
        try {
            schema = build(c.fields, strict);
        } catch (e) {
            fail(`${id}/${c.name}: schema failed to build: ${(e as Error).message}`);
            continue;
        }
        for (const d of c.documents) {
            const caseId = `${id}/${c.name}/${d.name}`;
            const expected = golden.get(caseId);
            if (expected === undefined) {
                fail(`${caseId}: no golden entry`);
                continue;
            }
            let actual: string;
            try {
                actual = transcript(schema.validate(d.doc));
            } catch (e) {
                fail(`${caseId}: validate threw ${(e as Error).message}`);
                continue;
            }
            if (actual !== expected) {
                fail(`${caseId}:\n  got:\n${indent(actual)}\n  want:\n${indent(expected)}`);
            }
        }
    }
}

function indent(s: string): string {
    return s
        .split('\n')
        .map(line => `    ${line}`)
        .join('\n');
}

checkGroup(spec.cases, false, 'case');
checkGroup(spec.strictCases, true, 'strictCase');

// Every golden row must be exercised, so a stale snapshot cannot hide.
const exercised = new Set<string>();
for (const c of spec.cases) {
    for (const d of c.documents) exercised.add(`case/${c.name}/${d.name}`);
}
for (const c of spec.strictCases) {
    for (const d of c.documents) exercised.add(`strictCase/${c.name}/${d.name}`);
}
for (const id of golden.keys()) {
    if (!exercised.has(id)) fail(`golden entry '${id}' is not exercised by any case`);
}

for (const c of spec.constructionErrors) {
    try {
        build(c.fields, false);
        fail(`construction error '${c.name}' was accepted`);
    } catch {
        // expected
    }
}

for (const c of spec.acceptedPathPairs) {
    try {
        let schema = Schema.create();
        for (const p of c.paths) schema = schema.field(p, fieldOf(TargetType.STRING, []));
        if (schema.paths().length !== c.paths.length) {
            fail(`accepted pair '${c.name}' produced ${schema.paths().length} paths`);
        }
    } catch (e) {
        fail(`accepted pair '${c.name}' was rejected: ${(e as Error).message}`);
    }
}

for (const c of spec.formalErrors) {
    try {
        fieldOf(TargetType[c.type as keyof typeof TargetType], c.rules.map(toRule));
        fail(`formal error '${c.name}' was accepted`);
    } catch {
        // expected
    }
}

for (const c of spec.formalAccepted) {
    try {
        fieldOf(TargetType[c.type as keyof typeof TargetType], c.rules.map(toRule));
    } catch (e) {
        fail(`formal accepted '${c.name}' was rejected: ${(e as Error).message}`);
    }
}

try {
    Schema.create().maxDepth(spec.maxDepth.limit).field(spec.maxDepth.rejected, string());
    fail(`maxDepth accepted '${spec.maxDepth.rejected}' at limit ${spec.maxDepth.limit}`);
} catch {
    // expected
}

try {
    const schema = Schema.create()
        .maxDepth(spec.maxDepth.limit)
        .field(spec.maxDepth.accepted, string());
    if (schema.paths().length !== 1) fail(`maxDepth rejected '${spec.maxDepth.accepted}'`);
} catch (e) {
    fail(`maxDepth rejected '${spec.maxDepth.accepted}': ${(e as Error).message}`);
}

// The fluent builders must expose the same rules the catalog declares.
// Every rule the catalog assigns to a target type, including the ANY-target
// rules that every builder inherits (`notNull` and `enum`). If this drifts from
// the builders, a rule exists that no schema can reach.
const builderRules: Record<string, (f: any) => any[]> = {
    STRING: f => [
        f.minLength(1), f.maxLength(1), f.length(1), f.pattern('.'), f.contains('x'),
        f.startsWith('x'), f.endsWith('x'), f.email(), f.url(), f.uuid(), f.languageCode(),
        f.lowercase(), f.uppercase(), f.trimmed(), f.notEmpty(), f.text(), f.notNull(),
        f.enumOf('x'), f.dateFormat('YYYY-MM-DD'), f.isoDate(), f.isoDateTime(), f.isoTime(),
        f.after('2026-01-01'), f.before('2026-01-02')
    ],
    NUMBER: f => [
        f.greaterThan(1), f.greaterThanOrEqual(1), f.lessThan(1), f.lessThanOrEqual(1),
        f.positive(), f.negative(), f.positiveInteger(), f.negativeInteger(),
        f.multipleOf(1), f.precision(1), f.notNull(), f.enumOf('x')
    ],
    // Magnitude rules only: `integer` is the type, `precision` is meaningless on
    // a whole number, and positiveInteger/negativeInteger are positive/negative.
    INTEGER: f => [
        f.greaterThan(1), f.greaterThanOrEqual(1), f.lessThan(1), f.lessThanOrEqual(1),
        f.positive(), f.negative(), f.multipleOf(1), f.notNull(), f.enumOf('x')
    ],
    BOOLEAN: f => [f.isTrue(), f.isFalse(), f.notNull(), f.enumOf('x')],
    LIST: f => [f.range({ min: 1 }), f.unique(), f.notNull(), f.enumOf('x')],
    ANY: f => [f.notNull(), f.enumOf('x')],
    DATE: f => [f.notNull(), f.enumOf('x')],
    DATETIME: f => [f.epoch(), f.epochMillis(), f.notNull(), f.enumOf('x')],
    TIME: f => [f.notNull(), f.enumOf('x')]
};

const builders: Record<string, () => any> = {
    STRING: string,
    NUMBER: number,
    INTEGER: integer,
    BOOLEAN: boolean_,
    LIST: list,
    ANY: any,
    DATE: date,
    DATETIME: dateTime,
    TIME: time
};

const catalog = JSON.parse(readFileSync(specPath('catalog.json'), 'utf8')) as Array<{
    id: string;
    targetType: string;
}>;

/**
 * The NUMBER rules an INTEGER builder deliberately holds back: an INTEGER field
 * *accepts* all of them, but `integer()` is the type, `precision()` is
 * meaningless for a whole number, and positiveInteger/negativeInteger are
 * positive/negative here. Mirrors INTEGER_BUILDER_OMITS in the Java suite.
 */
const INTEGER_BUILDER_OMITS = new Set(['positiveInteger', 'negativeInteger', 'precision']);

for (const type of Object.keys(builders)) {
    const expectedRules = catalog
        .filter(r => {
            if (r.targetType === 'ANY' || r.targetType === type) return true;
            // The one widening: an INTEGER field is a NUMBER field with a
            // whole-number assertion on top, so magnitude rules speak about it.
            return type === 'INTEGER' && r.targetType === 'NUMBER'
                && !INTEGER_BUILDER_OMITS.has(r.id);
        })
        .map(r => r.id)
        .sort();
    const chain = builderRules[type](builders[type]())
        .map((f: any) => f.rules.at(-1).code)
        .sort();
    if (chain.join(',') !== expectedRules.join(',')) {
        fail(`builder for ${type} exposes [${chain}] but the catalog has [${expectedRules}]`);
    }
}

// Every builder chain must produce a rule the engine can evaluate.
for (const type of Object.keys(builders)) {
    for (const f of builderRules[type](builders[type]())) {
        const rule = f.rules.at(-1);
        if (!RuleEvaluators.get(rule.code)) fail(`${type} builder emitted unknown rule ${rule.code}`);
    }
}
// Custom type definition and usage
const customSchema = Schema.create()
    .define('ShortString', TargetType.STRING, new Rule('minLength', 2), new Rule('maxLength', 10))
    .define('Code', 'ShortString', new Rule('pattern', '^[A-Z]+$'))
    .field('code', 'Code', new Rule('notNull'));

const validDoc = customSchema.validate({ code: 'ABC' });
if (!validDoc.valid) fail('Expected validDoc to be valid');
const invalidDoc1 = customSchema.validate({ code: 'a' });
if (invalidDoc1.valid) fail('Expected invalidDoc1 to fail minLength and pattern');
const invalidDoc2 = customSchema.validate({ code: 'ABCDEFGHIJK' });
if (invalidDoc2.valid) fail('Expected invalidDoc2 to fail maxLength');

// The terse rule-list form: integer().with(notNull, lessThan(65536)).
// A factory must exist for every catalog rule, must build the rule it is named
// after, and must not exist for anything the catalog does not describe. Both
// directions fail silently otherwise.

const sampleArg = (id: string): any =>
    id === 'range' ? { min: 1, max: 2 }
    : id === 'enum' ? ['a', 'b']
    : ['greaterThan', 'greaterThanOrEqual', 'lessThan', 'lessThanOrEqual', 'multipleOf',
       'precision', 'minLength', 'maxLength', 'length'].includes(id) ? 1
    : ['pattern', 'contains', 'startsWith', 'endsWith', 'dateFormat', 'after', 'before'].includes(id) ? 'x'
    : null;

const nonFactoryExports = new Set([
    'Schema', 'Rule', 'RuleEvaluators', 'TargetType', 'ParameterType', 'Kind', 'Severity',
    'StringField', 'NumberField', 'IntegerField', 'BooleanField', 'ListField', 'AnyField', 'DateField',
    'DateTimeField', 'TimeField', 'fieldOf', 'matchesTargetType', 'findings', 'describeType',
    'hasKind', 'DEFAULT_MAX_DEPTH', 'string', 'number', 'integer', 'boolean_', 'list', 'any',
    'date', 'dateTime', 'time', 'ValidationResult', 'Range',
    'applyTransform', 'applyTransformChain', 'Transform', 'TransformType', 'CustomTransformResolver',
    'defaultSidecar', 'computeRowHash', 'parseTransformStep', 'parseTransformSteps',
    'serializeTransformStep', 'resolveSidecarType', 'parseRuleStep', 'buildSchemaFromSidecar',
    'runSidecarPipeline', 'parseFindingPath', 'findingsForRow',
    'TransformStep', 'TransformRule', 'SidecarFieldSpec', 'ReglSidecar', 'IgnoredRow',
    'AppliedTransform', 'SidecarPipelineResult', 'TabularDocument'
]);

const factoryExports = Object.keys(ruleFactories).filter(
    k => !nonFactoryExports.has(k) && !(k.startsWith('Validation') || k === 'DocumentResult')
);
const catalogIds = catalog.map(r => r.id).sort();
const factoryIds = factoryExports.map(k => (k === 'enumOf' ? 'enum' : k)).sort();
if (factoryIds.join(',') !== catalogIds.join(',')) {
    const missing = catalogIds.filter(id => !factoryIds.includes(id));
    const extra = factoryIds.filter(id => !catalogIds.includes(id));
    fail(`rule factories [${factoryIds}] do not match catalog ids; missing [${missing}], extra [${extra}]`);
}

for (const id of catalogIds) {
    const factory = (ruleFactories as any)[id === 'enum' ? 'enumOf' : id];
    const arg = sampleArg(id);
    const built: Rule = typeof factory === 'function' ? factory(arg) : factory;
    if (!(built instanceof Rule)) {
        fail(`factory for ${id} did not return a Rule`);
        continue;
    }
    if (built.code !== id) {
        fail(`factory for ${id} built rule '${built.code}'`);
    }
}

// with() appends in order and rejects a mis-targeted rule with the same message
// fieldOf gives.
const appended = integer().greaterThan(0).with(notNull, lessThan(65536));
const appendedCodes = appended.rules.map(r => r.code);
if (appendedCodes.join(',') !== 'greaterThan,notNull,lessThan') {
    fail(`with() appended [${appendedCodes}], expected greaterThan,notNull,lessThan`);
}
let withMessage = '';
try {
    number().with(email);
    fail('with() accepted a STRING rule on a NUMBER field');
} catch (e) {
    withMessage = (e as Error).message;
}
let ofMessage = '';
try {
    fieldOf(TargetType.NUMBER, [email]);
    fail('fieldOf accepted a STRING rule on a NUMBER field');
} catch (e) {
    ofMessage = (e as Error).message;
}
if (withMessage !== ofMessage) {
    fail(`with() and fieldOf disagree on a mis-targeted rule: "${withMessage}" vs "${ofMessage}"`);
}
try {
    number().with();
    fail('with() accepted an empty rule list');
} catch {
    // expected
}

const documentCount = spec.cases.reduce((n, c) => n + c.documents.length, 0)
    + spec.strictCases.reduce((n, c) => n + c.documents.length, 0);

if (failures === 0) {
    console.log(
        `ok  ${catalog.length} catalog rules, ${documentCount} document cases, ` +
        `${spec.constructionErrors.length + spec.acceptedPathPairs.length} path pairs, ` +
        `${spec.formalErrors.length + spec.formalAccepted.length} formal cases`
    );
} else {
    console.error(`\n${failures} checks failed`);
    process.exit(1);
}