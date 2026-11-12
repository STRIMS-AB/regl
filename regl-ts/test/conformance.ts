import { RuleEvaluators, TargetType, matchesTargetType } from '../src/index';
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

const scalarFixtures = JSON.parse(readFileSync(specPath('conformance/cases.json'), 'utf8'));
const cases = scalarFixtures.cases as Array<{ rule: string; value?: any; input: any; expected: boolean }>;
// Target types are shared too. The rule cases cannot cover them: no rule targets
// INTEGER, because `integer` became the type.
const typeCases = (scalarFixtures.typeCases ?? []) as Array<{ type: string; input: any; expected: boolean }>;

const catalog = JSON.parse(readFileSync(specPath('catalog.json'), 'utf8')) as Array<{
    id: string;
    name: string;
    description: string;
    targetType: string;
    parameterType?: string;
}>;

let failures = 0;

function fail(message: string): void {
    failures++;
    console.error(`FAIL  ${message}`);
}

// Catalog parity: every catalog rule must be registered, and nothing extra.
const catalogIds = new Set(catalog.map((r) => r.id));
for (const id of catalogIds) {
    const evaluator = RuleEvaluators.get(id);
    if (!evaluator) {
        fail(`catalog rule '${id}' has no registered evaluator`);
        continue;
    }
    const entry = catalog.find((r) => r.id === id)!;
    if (evaluator.name !== entry.name) {
        fail(`'${id}' name: got ${JSON.stringify(evaluator.name)}, want ${JSON.stringify(entry.name)}`);
    }
    if (evaluator.description !== entry.description) {
        fail(`'${id}' description: got ${JSON.stringify(evaluator.description)}, want ${JSON.stringify(entry.description)}`);
    }
    if (evaluator.targetType !== entry.targetType) {
        fail(`'${id}' targetType: got ${evaluator.targetType}, want ${entry.targetType}`);
    }
    const parameterless = entry.parameterType === undefined;
    const evaluatorParameterless = evaluator.parameterType === null || evaluator.parameterType === undefined;
    if (parameterless !== evaluatorParameterless) {
        fail(`'${id}' parameterType presence: catalog=${entry.parameterType ?? 'none'}, evaluator=${evaluator.parameterType ?? 'none'}`);
    }
}

for (const evaluator of RuleEvaluators.getAll()) {
    if (!catalogIds.has(evaluator.code)) {
        fail(`evaluator '${evaluator.code}' is registered but absent from catalog.json`);
    }
}

// Shared conformance fixtures: identical verdicts to the Java reference engine.
const covered = new Set<string>();
for (const c of cases) {
    covered.add(c.rule);
    let evaluator;
    try {
        evaluator = RuleEvaluators.require(c.rule);
    } catch {
        fail(`conformance case references unknown rule '${c.rule}'`);
        continue;
    }
    const actual = evaluator.test(c.input, c.value);
    if (actual !== c.expected) {
        fail(`${c.rule}(${JSON.stringify(c.value)}) on ${JSON.stringify(c.input)}: got ${actual}, want ${c.expected}`);
    }
}

for (const c of typeCases) {
    const target = TargetType[c.type as keyof typeof TargetType];
    if (target === undefined) {
        fail(`type case references unknown target type '${c.type}'`);
        continue;
    }
    const actual = matchesTargetType(target, c.input);
    if (actual !== c.expected) {
        fail(`${c.type} on ${JSON.stringify(c.input)}: got ${actual}, want ${c.expected}`);
    }
}

// Every catalog rule needs at least one case, or its predicate is unverified.
for (const id of catalogIds) {
    if (!covered.has(id)) {
        fail(`catalog rule '${id}' has no conformance case`);
    }
}

const total = cases.length + typeCases.length + catalogIds.size;
if (failures === 0) {
    console.log(`ok  ${catalogIds.size} catalog rules, ${cases.length} conformance cases, `
        + `${typeCases.length} type cases`);
} else {
    console.error(`\n${failures} of ${total} checks failed`);
    process.exit(1);
}
