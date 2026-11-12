# Contributing to regl

Bug reports, proposals and pull requests are welcome.

## How regl is put together

There is one chain of authority, and the tests fail when it breaks
([Source of Truth](README.md#source-of-truth) in the README has the detail):

1. `regl-spec/catalog.json` is the rule catalog. A rule exists, and has its name, target
   type and parameter type, because the catalog says so.
2. `regl-java` implements what each rule means. When the two engines disagree, Java is
   right.
3. `regl-ts` follows the same catalog and the same fixtures.

So a new rule, or a change to one, is one change across the repository:

| Where | What |
| --- | --- |
| `regl-spec/catalog.json` | the rule: id, name, description, target type, parameter type |
| `regl-spec/conformance/` | at least one case for it, and its type-mismatch cases |
| `regl-spec/i18n/` | its message |
| `regl-java` | its evaluator in `RuleEvaluators`; `RuleType.java` is generated from the catalog, never edited |
| `regl-ts` | its evaluator and its place in the builders |
| `README.md` | the rules table: `node scripts/sync-readme.mjs` writes it from the catalog |

## Build and test

Java 17 or later, Maven, and Node.js for the TypeScript engine.

```bash
(cd regl-java && mvn verify)          # the engine, against the catalog and the fixtures
(cd regl-java && mvn install -DskipTests) && (cd regl-cli && mvn verify)   # the command
(cd regl-ts && npm ci && npm test)    # the TypeScript engine, against the same fixtures
node scripts/sync-readme.mjs --check  # the README's rules table matches the catalog
```

`.github/workflows/ci.yml` runs the same on every push.

## Pull requests

- One subject per pull request, with the tests that show it.
- A change to a rule touches the catalog, both engines and the fixtures together.
- Generated files (`RuleType.java`, the golden transcripts, the README's rules table) are
  regenerated, not hand-edited.

## Releasing

See [Releasing](README.md#releasing) in the README.

## Security

See [SECURITY.md](SECURITY.md).
