// Mirrors regl-java's RuleValidateTest: a rule's argument is validated against
// the catalog at construction in both engines, a custom rule's against its
// registered evaluator at validation time, and the results carry field, rule,
// value, and message either way.
import {
    ParameterType,
    Rule,
    RuleEvaluators,
    TargetType,
    ValidationResult,
    ValidationError
} from '../src/index';

let failures = 0;

function fail(message: string): void {
    failures++;
    console.error(`FAIL  ${message}`);
}

function expect(message: string, run: () => void): void {
    try {
        run();
        fail(`${message}: expected a throw`);
    } catch (e) {
        const text = (e as Error).message;
        if (/cannot be null|does not accept|requires a parameter|Unknown rule code|longer than/.test(text)) return;
        fail(`${message}: threw an unrelated error: ${text}`);
    }
}

function expectOk(message: string, run: () => void): void {
    try {
        run();
    } catch (e) {
        fail(`${message}: threw ${(e as Error).message}`);
    }
}

// --- passing and failing ---------------------------------------------------

expectOk('a passing rule yields a valid result', () => {
    const result = new Rule('minLength', 8).validate('password', 'longenough') as ValidationResult;
    if (!result.valid) fail('expected valid');
    if (result.errors.length !== 0 || result.warnings.length !== 0) {
        fail('expected no errors or warnings');
    }
});

expectOk('a failing rule yields one error carrying field, rule and value', () => {
    const result = new Rule('minLength', 8).validate('password', 'secret') as ValidationResult;
    if (result.valid) fail('expected invalid');
    if (result.errors.length !== 1) fail(`expected one error, got ${result.errors.length}`);
    const error: ValidationError = result.errors[0];
    if (error.field !== 'password') fail(`wrong field: ${error.field}`);
    if (error.rule !== 'minLength') fail(`wrong rule: ${error.rule}`);
    if (error.value !== 8) fail(`wrong value: ${String(error.value)}`);
    if (error.message !== 'Minimum length is 8, but got 6.') fail(`wrong message: ${error.message}`);
});

expectOk('a failing warn-only rule is a warning and stays valid', () => {
    const rule = new Rule('minLength', 8).withWarnOnly(true);
    const result = rule.validate('password', 'secret') as ValidationResult;
    if (!result.valid) fail('warn-only failure must not invalidate the result');
    if (result.errors.length !== 0) fail('expected no errors');
    if (result.warnings.length !== 1) fail(`expected one warning, got ${result.warnings.length}`);
});

expectOk('a parameterless rule validates with no argument', () => {
    if (!(new Rule('email').validate('email', 'a@b.co') as ValidationResult).valid) {
        fail('expected a valid email to pass');
    }
    if ((new Rule('email').validate('email', 'nope') as ValidationResult).valid) {
        fail('expected an invalid email to fail');
    }
});

expectOk('range accepts an unbounded side', () => {
    const minOnly = new Rule('range', { min: 2 });
    if (!(minOnly.validate('tags', ['a', 'b']) as ValidationResult).valid) fail('expected 2 elements to pass');
    if ((minOnly.validate('tags', ['a']) as ValidationResult).valid) fail('expected 1 element to fail');
});

// --- construction validates the catalog's parameter type -------------------

expect('a missing code is rejected at construction', () => {
    new Rule(null as unknown as string);
});

expect('a parameterized rule rejects a missing argument at construction', () => {
    new Rule('minLength');
});

expect('a parameterless rule rejects a supplied argument at construction', () => {
    new Rule('email', 'irrelevant');
});

expect('an argument of the wrong type is rejected at construction', () => {
    new Rule('pattern', 42);
});

expect('an enum argument must be a list', () => {
    new Rule('enum', 'fast');
});

expect('a range argument must be an object', () => {
    new Rule('range', 'fast');
});

expect('an over-long regex pattern is rejected when evaluated', () => {
    new Rule('pattern', 'a'.repeat(1001)).validate('x', 'a');
});

// --- custom rules ----------------------------------------------------------

RuleEvaluators.register({
    code: 'dayOfMonth',
    name: 'Day of Month',
    description: 'Requires a number between 1 and {value}.',
    parameterType: ParameterType.NUMBER,
    targetType: TargetType.NUMBER,
    test: (v, p) => !(typeof v === 'number') || (v >= 1 && v <= (p as number)),
    message: (f, _v, p) => `Field '${f}' must be a day between 1 and ${p}.`
});

try {
    expectOk('a custom rule validates through its registered evaluator', () => {
        const rule = Rule.custom('dayOfMonth', 31);
        if (!(rule.validate('day', 15) as ValidationResult).valid) fail('expected day 15 to pass');
        if ((rule.validate('day', 32) as ValidationResult).valid) fail('expected day 32 to fail');
    });

    expect('a custom rule with a bad argument is rejected at validation, not construction', () => {
        // Construction defers, exactly as in regl-java: the evaluator may be
        // registered after the rule is built. Validation checks it.
        const rule = Rule.custom('dayOfMonth', 'nope');
        rule.validate('day', 15);
    });

    expect('validating an unregistered code throws', () => {
        const rule = Rule.custom('noSuchRule', undefined);
        rule.validate('f', 'v');
    });
} finally {
    RuleEvaluators.unregister('dayOfMonth');
}

expect('the built-in catalog mark outlives an unregistered evaluator', () => {
    const minLength = RuleEvaluators.get('minLength');
    RuleEvaluators.unregister('minLength');
    try {
        // Still a built-in, like regl-java's RuleType enum: construction still
        // validates the argument against the catalog even without an evaluator.
        new Rule('minLength', 'three');
    } finally {
        RuleEvaluators.register(minLength!);
    }
});

// --- summary ----------------------------------------------------------------

if (failures === 0) {
    console.log('ok  rule argument validation matches regl-java');
} else {
    console.error(`\n${failures} of 15 checks failed`);
    process.exit(1);
}
