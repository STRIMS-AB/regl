# @regl/core

The TypeScript validation core of [regl](https://github.com/STRIMS-AB/regl) — a schema, document decoding, and a rule engine, sharing one specification with the Java engine.

## Install

The first public npm release is being prepared. Once published:

```sh
npm install @regl/core
```

## Use

A schema validates a whole decoded document — a tree of objects, arrays, and scalars — and returns every problem it can reach, instead of throwing on the first one:

```ts
import { Schema, string, integer, boolean_, list, notNull, lessThan } from '@regl/core';

const schema = Schema.create()
    .field('name',           string().notNull().minLength(1).enumOf('api', 'web'))
    .field('server.port',     integer().with(notNull, lessThan(65536)))
    .field('servers.*.host',  string().notNull().email())   // * matches every array element
    .field('debug',           boolean_().isFalse())
    .field('tags',            list().notNull().unique())
    .strict();                                               // reject unknown keys

const result = schema.validate(decodedDocument);

if (!result.valid) {
    for (const finding of result.errors) {
        finding.kind;    // 'RULE_FAILED' | 'TYPE_MISMATCH' | 'UNREACHABLE'
                         // | 'NOT_A_LIST' | 'UNKNOWN_FIELD'
        finding.path;    // concrete, e.g. 'servers.0.host'
        finding.rule;   // e.g. 'email', or null for structural findings
    }
}
```

Each builder exposes only the rules its type accepts, so
`string().greaterThan(5)` does not compile. A missing field resolves to `null`,
`notNull()` is what makes it mandatory, and nothing is ever coerced — a
wrong-typed value is reported, not converted.

Includes 33 built-in rules for strings, numbers, booleans, and lists, with no runtime dependencies. TypeScript declarations are included. Use it in Node.js or bundle it for a browser.

## The rule engine beneath

A schema validates a document by resolving each path and running its chain, rule by rule, on the value it finds. Those pieces are public API because tooling builds on them — not because you should validate a document by hand: `new Rule('minLength', 8)` is one chain element, and `RuleEvaluators.register(evaluator)` adds domain rules that schema chains then use, validated against their declared target type at runtime.

## What follows the Java side

The TypeScript decoders and CLI follow the same specification as the Java ones
(§13–§14 of the document spec), and are pinned by the shared fixtures in
[`regl-spec/conformance/decode-cases.json`](../regl-spec/conformance/decode-cases.json)
— when they arrive, they must agree with the Java CLI byte for byte.

## Build and package

```sh
npm ci
npm test
npm pack --dry-run
```

Packing runs the TypeScript build automatically and includes only compiled code, declarations, and package documentation. When ready to release, a maintainer with access to the `@regl` npm scope can run `npm publish --access public` from this directory.

See the [rule catalog](https://github.com/STRIMS-AB/regl/blob/main/regl-spec/catalog.json) and [documentation](https://regl.dev/docs/).

Licensed under Apache-2.0.
