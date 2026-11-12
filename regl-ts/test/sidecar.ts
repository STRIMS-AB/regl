// Sidecar pipeline: `.regl.yaml` fields/transforms/ignore (regl spec §12).
// Verifies terse step parsing, row-hash stability, and the fixed
// ignore -> transforms -> validation order on a tabular document.
import {
    computeRowHash,
    parseTransformStep,
    parseTransformSteps,
    serializeTransformStep,
    parseRuleStep,
    resolveSidecarType,
    buildSchemaFromSidecar,
    runSidecarPipeline,
    parseFindingPath,
    findingsForRow,
    ReglSidecar,
    TargetType
} from '../src/index';

let failures = 0;

function fail(message: string): void {
    failures++;
    console.error(`FAIL  ${message}`);
}

function expectThrow(message: string, run: () => void): void {
    try {
        run();
        fail(`${message}: expected a throw`);
    } catch {
        // expected
    }
}

// --- row hashing ------------------------------------------------------------

{
    const row = { iban: 'DE89370400440532013000', bic: ' COBADEFF ', amount: 14500 };
    const reordered = { amount: 14500, bic: ' COBADEFF ', iban: 'DE89370400440532013000' };
    const h1 = computeRowHash(row);
    const h2 = computeRowHash(reordered);
    if (!h1.startsWith('h_') || h1.length !== 18) fail(`bad hash shape: ${h1}`);
    if (h1 !== h2) fail('hash must be independent of key order');
    if (h1 !== computeRowHash({ ...row, _raw: row })) fail('hash must prefer _raw when present');
    if (h1 === computeRowHash({ ...row, amount: 99 })) fail('hash must change with values');
}

// --- terse step parsing -----------------------------------------------------

{
    const t = parseTransformStep('trim');
    if (t.type !== 'trim' || t.param !== undefined) fail('bare step parse');
    const c = parseTransformStep({ constant: 'COBADEFFXXX' });
    if (c.type !== 'constant' || c.param !== 'COBADEFFXXX') fail('map step parse');
    const r = parseTransformStep({ replace: { pattern: 'INV-', replacement: 'SEPA-INV-' } });
    if (r.type !== 'replace' || r.param.pattern !== 'INV-') fail('object param parse');
    if (JSON.stringify(serializeTransformStep(r)) !== JSON.stringify({ replace: { pattern: 'INV-', replacement: 'SEPA-INV-' } })) {
        fail('serializeTransformStep must be the inverse of parseTransformStep');
    }
    if (JSON.stringify(serializeTransformStep({ type: 'upperCase' })) !== JSON.stringify('upperCase')) {
        fail('serializeTransformStep bare form');
    }
    expectThrow('multi-key step throws', () => parseTransformStep({ a: 1, b: 2 }));
    expectThrow('array step throws', () => parseTransformStep(['trim'] as any));
    if (parseTransformSteps(['trim', { upperCase: undefined }]).length !== 2) fail('parseTransformSteps length');
}

// --- rule steps and types ---------------------------------------------------

{
    const plain = parseRuleStep('notNull');
    if (plain.code !== 'notNull' || plain.warnOnly) fail('plain rule step');
    const warn = parseRuleStep('notNull!');
    if (warn.code !== 'notNull' || !warn.warnOnly) fail('! suffix must mean warning severity');
    const param = parseRuleStep({ minLength: 3 });
    if (param.code !== 'minLength' || param.value !== 3) fail('param map rule step');
    const warnParam = parseRuleStep({ 'greaterThan!': 0 });
    if (warnParam.code !== 'greaterThan' || warnParam.value !== 0 || !warnParam.warnOnly) {
        fail('param map with ! suffix');
    }
    if (resolveSidecarType('string') !== TargetType.STRING) fail('type resolution case-insensitive');
    if (resolveSidecarType(undefined) !== TargetType.ANY) fail('missing type means ANY');
    expectThrow('unknown type throws', () => resolveSidecarType('VARCHAR'));
}

// --- end-to-end pipeline (spec example) -------------------------------------

{
    const sepa: ReglSidecar = {
        strict: false,
        fields: {
            'SepaTransactions.*.iban': { type: 'STRING', rules: ['notNull', { pattern: '^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$' }] },
            'SepaTransactions.*.bic': { type: 'STRING', rules: ['notNull'] },
            'SepaTransactions.*.amount': { type: 'NUMBER', rules: ['notNull', 'positive'] },
            'SepaTransactions.*.currency': { type: 'STRING', rules: ['notNull', { enum: ['EUR', 'USD'] }] },
            'SepaTransactions.*.reference': { type: 'STRING', rules: ['notNull!'] }
        },
        transforms: [
            { path: 'SepaTransactions.*.iban', apply: ['trim'] },
            { path: 'SepaTransactions.*.currency', apply: ['trim', 'upperCase'] },
            { path: 'SepaTransactions.*.bic', when: { equals: 'COBADEFF' }, apply: [{ constant: 'COBADEFFXXX' }] },
            { path: 'SepaTransactions.*.reference', rowHash: 'h_replace_me', apply: [{ replace: { pattern: 'INV-', replacement: 'SEPA-INV-' } }] }
        ],
        ignore: {}
    };

    const rows = [
        { iban: ' DE89370400440532013000 ', bic: 'COBADEFF', amount: 14500, currency: ' eur ', reference: 'INV-2026-1001' },
        { iban: 'FR7630006000011234567890189', bic: 'BNPAFRPPXXX', amount: -5, currency: 'EUR', reference: 'INV-2026-1002' },
        { iban: 'NOT_AN_IBAN', bic: 'ABNANL2AXXX', amount: 3120, currency: 'JPY', reference: null }
    ];
    // rowHash for row 0, stamped the way the pipeline will read it
    const rowHash0 = computeRowHash(rows[0]);
    sepa.transforms![3].rowHash = rowHash0;
    // ignore row 2 by its hash
    sepa.ignore = { SepaTransactions: [computeRowHash(rows[2])] };

    const result = runSidecarPipeline({ SepaTransactions: rows }, sepa);

    // ignore pass
    if (result.ignored.length !== 1) fail(`expected 1 ignored row, got ${result.ignored.length}`);
    if (result.ignored[0].index !== 2) fail('ignored row index');
    if (!result.document.SepaTransactions[2]._is_ignored) fail('ignored row must be flagged');
    if (result.document.SepaTransactions[0]._is_ignored) fail('kept rows must not be flagged');

    // transform pass
    const effective = result.document.SepaTransactions;
    if (effective[0].currency !== 'EUR') fail(`currency trim+upperCase, got ${effective[0].currency}`);
    if (effective[0].bic !== 'COBADEFFXXX') fail(`conditional constant, got ${effective[0].bic}`);
    if (effective[0].reference !== 'SEPA-INV-2026-1001') fail(`rowHash-scoped replace, got ${effective[0].reference}`);
    if (effective[1].bic !== 'BNPAFRPPXXX') fail('non-matching when.equals must not apply');
    if (effective[1].reference !== 'INV-2026-1002') fail('other rows must not receive rowHash transform');
    if (result.transformsApplied.length !== 4) fail(`expected 4 applied transforms, got ${result.transformsApplied.length}`);

    // validation pass — post-transform values, indices aligned to the document
    if (result.validation.valid) fail('document with an ignored bad row still has live errors');
    const row0 = findingsForRow(result.validation, 'SepaTransactions', 0);
    if (row0.errors.length !== 0) fail(`row 0 should be clean after transforms, got ${JSON.stringify(row0.errors)}`);
    const row1 = findingsForRow(result.validation, 'SepaTransactions', 1);
    if (row1.errors.length !== 1 || row1.errors[0].rule !== 'positive') {
        fail(`row 1 should fail positive, got ${JSON.stringify(row1.errors)}`);
    }
    // row 2 is ignored: the pipeline still reports findings at its index, and
    // the caller suppresses them because the row is flagged.
    if (!result.document.SepaTransactions[2]._is_ignored) fail('row 2 ignore flag');
    const row2 = findingsForRow(result.validation, 'SepaTransactions', 2);
    if (row2.errors.length === 0) fail('row 2 findings should exist but be suppressed by the caller');
    const parsed = parseFindingPath(result.validation.errors[0].path);
    if (!parsed || parsed.sheet !== 'SepaTransactions' || typeof parsed.index !== 'number') {
        fail('finding path must be Sheet.index.column');
    }
    // null reference is a warning only (notNull!), never an error
    if (result.validation.warnings.length !== 1) {
        fail(`expected 1 warning for null reference, got ${result.validation.warnings.length}`);
    }
    // input document is never mutated
    if (rows[0].currency !== ' eur ') fail('pipeline must not mutate the input rows');
}

// --- schema construction ----------------------------------------------------

{
    const schema = buildSchemaFromSidecar({
        fields: {
            'Sheet.*.n': { type: 'INTEGER', rules: ['notNull', { 'greaterThan!': 0 }] }
        }
    });
    const bad = schema.validate({ Sheet: [{ n: -1 }] });
    // A `!` (warn-only) rule never makes the document invalid on its own.
    if (!bad.valid) fail('warn-only greaterThan must not invalidate the document');
    if (bad.errors.length !== 0 || bad.warnings.length !== 1) {
        fail('! rule must surface as a warning, not an error');
    }
    expectThrow('empty transform path throws in pipeline', () =>
        runSidecarPipeline({ Sheet: [{ n: 1 }] }, { transforms: [{ path: 'Sheet.n', apply: ['trim'] }] })
    );
    expectThrow('overlapping fields throw', () =>
        buildSchemaFromSidecar({
            fields: {
                'Sheet.*.n': { type: 'INTEGER' },
                'Sheet.0.n': { type: 'NUMBER' }
            }
        })
    );
}

if (failures > 0) {
    console.error(`\n${failures} failure(s)`);
    process.exit(1);
}
console.log('sidecar: all checks passed');
