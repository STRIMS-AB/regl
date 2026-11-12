# regl-spec

The canonical, implementation-independent half of regl. Nothing in this directory
executes; it is the contract both engines are tested against.

| File | What it defines |
| :--- | :--- |
| `catalog.json` | Every validation rule, with `id`, `name`, `description`, `targetType`, `parameterType`. The rule set itself. |
| `transformations-catalog.json` | Every value transformation function (`lowerCase`, `upperCase`, `trim`, `prefix`, `suffix`, `replace`, `multiply`, `roundTo`, `toNumber`, `formatDate`, `default`, `constant`, etc.) grouped by category (`STRING`, `NUMBER`, `COERCION`, `TEMPORAL`, `COMMON`). |
| `i18n/<locale>.json` | Localized `name` / `description` / `error` per rule, plus a `findings` section for document-level messages. |
| `conformance/cases.json` | Scalar fixtures: rule × parameter × value → expected verdict. |
| `conformance/document-cases.json` | Document fixtures: fields × documents, plus construction and formal-assembly pairs. |
| `conformance/document-expected.json` | The golden transcript every document case must produce, asserted by both engines. |
| `conformance/decode-cases.json` | Decoder fixtures: format × text → expected decoded tree, or a decode error. |
| `document-schema.md` | Normative spec for document-level validation, schema files, transformations, decoding, and the CLI. |

## Where a rule's `targetType` shows up

`targetType` is not only metadata — it drives three things at once, which is why
the typed builders cannot drift from the rule set:

1. The **scalar type guard** (§7 of `document-schema.md`): a value of the wrong
   kind fails instead of reaching a predicate that would cast it.
2. The **builder methods**: `string()` returns a builder exposing only `STRING`
   rules, `number()` only `NUMBER` rules, and so on. Adding a `STRING` rule adds
   one method to one builder.
3. `notNull`'s `ANY`, which is what lets it see a `null` at all — every other
   target type rejects `null` before the predicate runs.

## Adding a rule

1. Add it to `catalog.json`.
2. Implement the predicate in `regl-java`'s `RuleEvaluators.registerBuiltIns()`.
   That implementation is authoritative.
3. Mirror it in `regl-ts`'s `builtins.ts`.
4. Add i18n entries for all five locales, preserving `{value}` tokens.
5. Add fixtures to `conformance/cases.json`, including both sides and at least
   one `typeMismatch` case.
6. Run `mvn -Pci verify` in `regl-java` (regenerates `RuleType.java`, fails on
   staleness) and `npm test` in `regl-ts`.

## Not every rule is about a value's kind

`notNull` is the model for a rule that operates on presence rather than on a
type:

```json
{ "id": "notNull", "name": "Not Null", "targetType": "ANY" }
```

`ANY` means the type guard lets every kind through, including `null`, so the
predicate itself is what rejects the null. It is the only way a catalog rule can
operate on presence — which is why the document spec has no `required()` flag
that bypasses the catalog.

## Authority

`catalog.json` is the single source of truth for the rule set. `regl-java` is
authoritative for rule *semantics*; `regl-ts` must agree with it on every
fixture. See the root README for the full chain of authority.

A rule cannot be added here without a conformance case covering both its passing
and failing sides, or the predicate is unverified.

## Fixture format

`conformance/cases.json`:

```json
{
  "note": "...",
  "cases": [
    { "rule": "minLength", "value": 3, "value": "abc", "expected": true }
  ]
}
```

`expected: true` means the rule **passes**. Cases marked
`"typeMismatch": true` assert the stronger property from §7 of
`document-schema.md`: a value whose kind contradicts the rule's `targetType` must
**fail** in both engines — never coerced, never skipped, never thrown.

## Document fixtures

`conformance/document-cases.json` describes whole documents rather than single
values: each case declares fields (a path, a `type` head, and a `rules` chain)
and runs several documents through them, with `strictCases`, `constructionErrors`,
`acceptedPathPairs`, `formalErrors`, `formalAccepted`, and `maxDepth` groups
covering the schema-construction side.

The expected outcome of every case lives in
`conformance/document-expected.json` — one row per (schema, document) pair,
holding `severity`, `kind`, `path`, and `rule`, and deliberately not `message`.
Both engines assert against that one file, so a behavioural difference between
them cannot be absorbed by updating a snapshot on one side.

Each engine can also regenerate the file: `npm run golden` in `regl-ts`, and
`GenerateDocumentGolden` in `regl-java`. Regenerating with either engine must
produce a byte-identical file; regenerate only to review a deliberate behaviour
change, and read the diff before committing it.

## Decode fixtures

`conformance/decode-cases.json` pins the document decoders (§13 of
`document-schema.md`): each case is a `format`, a `text`, and either the
`expected` decoded tree — written as JSON, which the document model expresses
exactly — or `"error": true` for a text that must fail to decode rather than be
best-effort recovered. It covers JSON, `.properties` (including the
dotted-key expansion and its escaping), and YAML.

The decoders are asserted wherever they are implemented — the core suites for
JSON and `.properties`, the `regl-cli` suite for YAML, and the site's suite for
the decoders its playground runs in the browser — so when the TypeScript CLI
arrives, its decoders must agree with these fixtures byte for byte.
