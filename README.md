# regl

**A schema file, a CLI, and isomorphic rule engines for validating JSON, YAML, and `.properties`**

Documentation, the rule reference and a playground: **[regl.dev](https://regl.dev)**

`regl` validates real configuration files. You write a schema — in a JSON or YAML file — that describes a document's shape, types, and constraints, and then:

```bash
regl schema.json document.yaml        # validate: exit 0 valid, 1 invalid, 2 regl failed
```

A schema or a document is a **filesystem path or an `http(s)` URL**. Under the CLI sit two zero-dependency rule engines — Java and TypeScript — that must agree on every fixture, which is what makes a schema mean the same thing everywhere.

---

## Why regl?

Traditional validation libraries couple rule logic to one language (Hibernate Validator for Java, Zod for TypeScript) and leave you to hand-roll the parts around the rules: loading, decoding, exit codes.

`regl` decouples the layers:

- **A schema is a file**, not code: the same schema validates the same document in any engine, checked out of a repo or fetched from a URL.
- **A CLI is the product**: `regl <schema> <document>` loads, decodes, validates, and exits with a code a pipeline or CI job can branch on.
- **Canonical specification (`regl-spec`)**: a single rule catalog, conformance fixtures, and localization templates.
- **Isomorphic engines (`regl-java`, `regl-ts`)**: zero-dependency reference implementations that evaluate identical rules with identical semantics, pinned by shared golden transcripts.
- **Localization-first (`i18n`)**: parameterized templates (`{field}`, `{value}`) let UIs and tools localize messages consistently.

---

## Quick start

```bash
cd regl-java && mvn install          # once: publishes the core the CLI builds on
cd ../regl-cli && mvn package        # builds target/regl/bin/regl

target/regl/bin/regl schema.json app.yaml
echo $?                              # 0 valid, 1 invalid, 2 nothing validated
```

The same schema in YAML — kinder to hand-authored configuration:

```yaml
strict: true
fields:
  server.port: { type: INTEGER, rules: [notNull, { lessThan: 65536 }] }
  servers.*.host: { type: STRING, severity: warn, rules: [email] }
```

`regl schema schema.yaml` lints the schema and prints what regl understood; `--json` prints it normalized. See [`regl-cli/README.md`](regl-cli/README.md) for the full command surface.

---

## Repository Structure

```
regl/
├── regl-spec/     # Canonical spec, rule catalog, transformations catalog & i18n reference files
│   ├── catalog.json                   # Machine-readable validation rule definitions
│   ├── transformations-catalog.json   # Machine-readable value transformation functions
│   ├── i18n/                          # Localized message templates (en.json, ...)
│   ├── conformance/                   # Shared fixtures: scalar, document, decode, golden transcripts
│   ├── document-schema.md             # Normative spec: schema files, decoding, the CLI
│   └── README.md                      # What lives here and how to change it
├── regl-java/     # JVM validation & transformation core (Java 17+, zero dependencies)
│   └── README.md          # Java-specific API documentation & examples
├── regl-cli/      # The regl command: schema files, YAML, URLs, exit codes
│   └── README.md          # CLI usage, installation, examples
├── regl-ts/       # TypeScript/JavaScript validation & transformation core
│   └── README.md          # npm package documentation
└── scripts/       # Repo tooling (sync-readme.mjs keeps the rules table in step with the catalog)
```

---

## Implementations

- **[Java core (`regl-java`)](regl-java/README.md)**: Maven/Gradle dependency (`dev.regl:regl-java`), the zero-dependency validation core — schema validation with deterministic `DocumentFinding`s, document decoding for JSON and `.properties`, and the rule engine the schema runs on. Also carries constraint annotations (`@MinLength`, `@Email`, etc. — currently declarative metadata, with no reflective engine behind them yet).
- **[The CLI (`regl-cli`)](regl-cli/README.md)**: `regl <schema> <document>` — schema files in JSON or YAML, document decoding in all three formats, URL loading, canonical `--json` output, and the 0/1/2 exit codes.
- **[TypeScript core (`regl-ts`)](regl-ts/README.md)**: npm package (`@regl/core`) with the same schema API for Node.js, browsers, and dashboard UIs; its decoders and CLI follow the Java ones against the same fixtures.

---

## Core Architecture

### Target Types (`TargetType`)
Every rule declares what category of data it evaluates:
- `STRING`: Operates on text (e.g. `minLength`, `pattern`, `email`).
- `NUMBER`: Operates on numeric values (e.g. `greaterThan`, `precision`, `positive`).
- `INTEGER`: Whole number target type; rejects fractional numbers and accepts all `NUMBER` rules.
- `BOOLEAN`: Operates on boolean flags (`isTrue`, `isFalse`).
- `LIST`: Evaluates a collection as a single unit (e.g. `range`, `unique`).
- `DATE` / `DATETIME` / `TIME`: Temporal values (e.g. `epoch`, `epochMillis`).
- `ANY`: Universal rules applicable across types (e.g. `enum`, `notNull`).

The validation engine uses `TargetType` to automatically route data (e.g. iterating elements of a list for `STRING` rules while passing the whole collection to `LIST` rules).

`ANY` has a second role: because it matches every kind, `notNull` is the one rule that can accept a `null` and is therefore the rule that rejects it.

### Parameter Types (`ParameterType`)
Parameters map directly to standard JSON primitives:
- `STRING`: Text parameter (e.g. regex pattern, substring).
- `NUMBER`: Numeric parameter (e.g. min length, boundary value, step).
- `BOOLEAN`: Boolean configuration parameter.
- `LIST`: Array of values (e.g. allowed values for `enum`).
- `OBJECT`: Structured configuration map (e.g. range boundaries).
- `null`: Parameterless flag rules (e.g. `email`, `lowercase`, `positive`).

---

## Built-in Rules Catalog

Generated from `regl-spec/catalog.json` (`node scripts/sync-readme.mjs`); CI fails if it drifts.

<!-- rules-table:begin (generated from regl-spec/catalog.json by scripts/sync-readme.mjs; do not edit) -->
| Rule | Target Type | Parameter Type | Description |
| :--- | :--- | :--- | :--- |
| `minLength` | `STRING` | `NUMBER` | Requires text to be at least {value} characters. |
| `maxLength` | `STRING` | `NUMBER` | Requires text to be no longer than {value} characters. |
| `length` | `STRING` | `NUMBER` | Requires text to be exactly {value} characters. |
| `pattern` | `STRING` | `STRING` | Requires text to match the regular expression: {value}. |
| `contains` | `STRING` | `STRING` | Requires text to contain the substring: {value}. |
| `startsWith` | `STRING` | `STRING` | Requires text to start with: {value}. |
| `endsWith` | `STRING` | `STRING` | Requires text to end with: {value}. |
| `email` | `STRING` | *none* | Requires a valid email address format. |
| `url` | `STRING` | *none* | Requires a valid HTTP or HTTPS URL. |
| `uuid` | `STRING` | *none* | Requires a valid UUID format. |
| `languageCode` | `STRING` | *none* | Requires a well-formed BCP-47 language tag. |
| `lowercase` | `STRING` | *none* | Requires all characters to be lowercase. |
| `uppercase` | `STRING` | *none* | Requires all characters to be uppercase. |
| `trimmed` | `STRING` | *none* | Requires no leading or trailing whitespace. |
| `notEmpty` | `STRING` | *none* | Requires text to contain at least one non-whitespace character. |
| `text` | `STRING` | *none* | Marks a field as human-readable text. |
| `dateFormat` | `STRING` | `STRING` | Requires date/time string to match format pattern: {value}. |
| `isoDate` | `STRING` | *none* | Requires a valid ISO-8601 calendar date (YYYY-MM-DD). |
| `isoDateTime` | `STRING` | *none* | Requires a valid ISO-8601 date-time timestamp. |
| `isoTime` | `STRING` | *none* | Requires a valid ISO-8601 time (HH:mm:ss). |
| `notNull` | `ANY` | *none* | Requires the value to be present and not null. |
| `enum` | `ANY` | `LIST` | Restricts the value to a predefined list of allowed strings: {value}. |
| `after` | `STRING` | `STRING` | Requires date/time to be chronologically after {value}. |
| `before` | `STRING` | `STRING` | Requires date/time to be chronologically before {value}. |
| `greaterThan` | `NUMBER` | `NUMBER` | Requires the number to be strictly greater than {value}. |
| `greaterThanOrEqual` | `NUMBER` | `NUMBER` | Requires the number to be greater than or equal to {value}. |
| `lessThan` | `NUMBER` | `NUMBER` | Requires the number to be strictly less than {value}. |
| `lessThanOrEqual` | `NUMBER` | `NUMBER` | Requires the number to be less than or equal to {value}. |
| `positive` | `NUMBER` | *none* | Requires a number strictly greater than zero. |
| `negative` | `NUMBER` | *none* | Requires a number strictly less than zero. |
| `positiveInteger` | `NUMBER` | *none* | Requires a whole number strictly greater than zero. |
| `negativeInteger` | `NUMBER` | *none* | Requires a whole number strictly less than zero. |
| `multipleOf` | `NUMBER` | `NUMBER` | Requires the number to be a multiple of {value}. |
| `precision` | `NUMBER` | `NUMBER` | Restricts the maximum number of decimal places to {value}. |
| `epoch` | `DATETIME` | *none* | Requires a whole-number Unix timestamp in seconds. |
| `epochMillis` | `DATETIME` | *none* | Requires a whole-number Unix timestamp in milliseconds. |
| `range` | `LIST` | `OBJECT` | Restricts the number of items in a list to the range {value}. |
| `unique` | `LIST` | *none* | Requires all items in the list to be distinct. |
| `isTrue` | `BOOLEAN` | *none* | Requires a boolean value to be true. |
| `isFalse` | `BOOLEAN` | *none* | Requires a boolean value to be false. |
<!-- rules-table:end -->

### `pattern` semantics

`pattern` is a **partial match** (the regex may match anywhere in the text; anchor with `^…$` for a full match), like JavaScript's `RegExp.test`. Because schemas may be fetched from a URL, the pattern is treated as untrusted input:

- A pattern longer than 1000 characters is rejected (`IllegalArgumentException` / `Error`).
- Compiled patterns are cached per distinct source.
- Java aborts a match that runs longer than one second and treats it as *not matching* (fails closed). JavaScript cannot interrupt a running match, so `regl-ts` has the length limit but no timeout; avoid nested quantifiers such as `(a+)+` in schemas you do not control.
- The engines use their platform's regex dialect, so portable patterns stick to the common subset: character classes, `\d \w \s \b`, quantifiers (including lazy), groups, `(?:…)`, backreferences, lookahead and lookbehind. Java-only syntax (`(?i)` inline flags, possessive quantifiers, `\p{L}` without the `u` flag in JS) will not agree. One difference is normalized for you: Java's `$` is rewritten to match only at the very end of the text, as in JavaScript. The shared fixtures in `cases.json` pin this subset.

---

## Built-in Transformations Catalog

Defined in `regl-spec/transformations-catalog.json` and executed uniformly by `dev.regl.Transforms` (`regl-java`) and `applyTransform` / `applyTransformChain` (`regl-ts`). Schema files (`.regl.yaml` / `.regl.json`) and domain mappings use these functions to normalize, coerce, or reshape values prior to validation:

| Transformation | Category | Parameter | Description |
| :--- | :--- | :--- | :--- |
| `lowerCase` | `STRING` | *none* | Lowercases the string. |
| `upperCase` | `STRING` | *none* | Uppercases the string. |
| `trim` | `STRING` | *none* | Strips leading and trailing whitespace. |
| `prefix` | `STRING` | `string` | Prepends the parameter to the value. |
| `suffix` | `STRING` | `string` | Appends the parameter to the value. |
| `stripPrefix` | `STRING` | `string` | Removes the leading occurrence of the parameter when present. |
| `stripSuffix` | `STRING` | `string` | Removes the trailing occurrence of the parameter when present. |
| `replace` | `STRING` | `object` | Replaces all occurrences: `{ pattern, replacement }`. |
| `substring` | `STRING` | `object` | Extracts a range: `{ start, end? }`. |
| `join` | `STRING` | `string` | Joins an array into a string separated by the parameter. |
| `multiply` | `NUMBER` | `number` | Multiplies the number by the parameter. |
| `divide` | `NUMBER` | `number` | Divides the number by the parameter. |
| `abs` | `NUMBER` | *none* | Absolute value. |
| `round` | `NUMBER` | *none* | Rounds to the nearest integer. |
| `roundTo` | `NUMBER` | `integer` | Rounds to the given number of decimals. |
| `toNumber` | `COERCION` | *none* | Coerces to a number when possible. |
| `toString` | `COERCION` | *none* | Coerces to a string. |
| `toBoolean` | `COERCION` | *none* | Coerces to a boolean (`true`/`1`/`yes`, `false`/`0`/`no`). |
| `formatDate` | `TEMPORAL` | `string` | Formats a date/time from `'unix'` or a token pattern into the target format. |
| `default` | `COMMON` | `any` | Returns the parameter when the value is null or blank. |
| `constant` | `COMMON` | `any` | Always returns the parameter, ignoring the source value. |
| `custom` | `COMMON` | `string` | Free-text reference resolved at runtime. |

---

## Internationalization & Tooling Integration

`regl-spec/i18n/` contains language reference files structured for third-party tools and client libraries:

```json
{
  "rules": {
    "minLength": {
      "name": "Minimum Length",
      "description": "Requires text to be at least {value} characters.",
      "error": "Field '{field}' must be at least {value} characters."
    }
  }
}
```

When validation fails, a finding carries its `kind`, concrete `path`, the `rule` and `value`, and a default `message`. Client applications and UIs can interpolate the `{field}` and `{value}` tokens into their own localized translations.

Note that the built-in evaluators render their own English message rather than the `error` template above (e.g. `Minimum length is 8, but got 6.`). Treat `en.json` as the reference catalog for clients that localize from `field`/`rule`/`value`; the engines' `message` field is a convenience default, not a rendering of the bundle.

### Type mismatches are reported, never coerced or thrown

A rule only speaks about values of its own `targetType`: a `minLength` rule is about strings, not numbers. When a value is of another kind the rule cannot produce a meaningful verdict, so both engines **report it as a type-mismatch finding** rather than coercing the value, skipping the rule, or throwing:

```json
{ "server": { "port": "8080" } }
```

against a schema field `server.port` of type `NUMBER` yields:

```
TYPE_MISMATCH  server.port  Value must be of type NUMBER, but got STRING.
```

This is what makes the engines safe to point at a real document: a malformed value becomes an entry in the findings list instead of an uncaught `ClassCastException` halfway through a traversal. There is no implicit coercion — a schema that wants `"8080"` to satisfy a `NUMBER` rule must ask for it explicitly. This is also why a `.properties` document, whose values are all strings, can only satisfy `STRING` rules.

The type check is applied centrally when an evaluator is registered (`RuleEvaluators.reg` / the `reg()` helper in `builtins.ts`), so custom evaluators get it too and no built-in predicate needs its own guard. `TargetType.ANY` rules (`enum`) match every kind.

## Source of Truth

There is one ordered chain of authority. Each link is derived from the one above it, and the test suites fail if the chain breaks.

1. **`regl-spec/catalog.json`** — the canonical rule catalog. It defines the complete rule set and each rule's `id`, `name`, `description`, `targetType`, and `parameterType`. Nothing downstream may invent a rule, rename one, or disagree about its metadata.
2. **`regl-java`** — the reference implementation of rule *semantics*. The predicates in `RuleEvaluators.registerBuiltIns()` are hand-written and authoritative: when the two engines disagree on behaviour, Java is right and `regl-ts` has a bug. `RuleType.java` is **generated** from the catalog, since it is pure metadata — never edit it by hand.
3. **`regl-ts`** — a portable consumer of the same catalog and fixtures. It holds no independent authority.

`regl-spec/i18n/` holds message templates for clients that localize from `field`/`rule`/`value`. The engines' `message` field is a convenience English default, not a rendering of that bundle.

### Document validation

`regl-spec/document-schema.md` specifies validating a whole document — a decoded tree of objects, lists, and scalars — against a schema built from these same rules. It covers the path grammar (with `*` for list elements and `\` escaping), null semantics, strict mode, result ordering, and the conformance format. Both engines implement it, and both are tested against one shared golden transcript, so their findings are identical including order.

A field is a **type head plus a chain of rules**, e.g. `number().notNull().greaterThan(0)`, or `integer().notNull().lessThan(65536)` — an integer is a whole number, not a class, so `2147483648` passes and `80.5` does not. The head returns a builder carrying only that type's rules, so an incoherent chain such as `string().greaterThan(5)` **does not compile**. A data-driven field can also be built formally with `Field.of(type, rules)`, which validates target types and throws `IllegalArgumentException` — the same exception the engines already raise for a bad rule parameter. Three conventions are worth flagging, because all three simplify the API:

- **A missing field is `null`, and `notNull()` is what makes it mandatory.** There is no `required()` and no `nullable()`. Presence is an ordinary catalog rule, so it has an id, localized messages, and fixtures like everything else.
- **No coercion.** A value of the wrong kind is reported, never converted. This means `.properties`, whose values are all strings, can only be validated with `STRING` rules.
- **`DocumentFinding` is separate from `ValidationError`.** Document results distinguish a structural problem from a value problem, which the scalar error shape has no field for. `ValidationError` is left unchanged, so no scalar consumer is affected.

```java
import static dev.regl.Rule.*;   // notNull(), lessThan(...)
import static dev.regl.Rules.*;

Schema schema = Schema.create()
    .field("name",        string().notNull().minLength(1).enumOf("api", "web"))
    .field("server.port", integer().with(notNull(), lessThan(65536)))
    .field("tags",        list().notNull().unique())
    .strict();

// The chain is the fully type-checked form: string().greaterThan(5) does not
// compile. with(...) is the same rules as values, for when the chain is longer
// than the constraint; the head still fixes the type and lessThan(65536) still
// type-checks, and a mis-targeted rule is caught by with(...) itself.


DocumentResult result = schema.validate(decodedDocument);
for (DocumentFinding finding : result.errors()) {
    // branch on finding.kind(): RULE_FAILED, TYPE_MISMATCH,
    // UNREACHABLE, NOT_A_LIST, UNKNOWN_FIELD
}
```

### What is enforced

| Check | Where |
| :--- | :--- |
| `RuleType.java` matches `catalog.json` (regenerated if hand-edited) | `RuleTypeGeneratedTest` |
| Every catalog rule has a registered evaluator | `CatalogParityTest` |
| The engine registers no rule the catalog omits | `CatalogParityTest` |
| Names, descriptions, and target/parameter types match the catalog | `CatalogParityTest` |
| Every catalog rule has at least one conformance case | `CatalogParityTest` |
| Every catalog rule has an i18n entry in `en.json` | `CatalogParityTest` |
| Java verdicts match the shared fixtures | `ConformanceTest` |
| TypeScript verdicts and metadata match the same fixtures | `regl-ts`: `npm test` |
| Every rule × every mismatched value kind fails identically in both engines (no coercion, no skip, no throw) | `cases.json` `typeMismatch` cases |
| Document findings match the shared golden transcript, in order | `DocumentConformanceTest` / `regl-ts`: `npm test` |
| Regenerating the golden transcript in either engine yields a byte-identical file | `GenerateDocumentGolden` / `regl-ts`: `npm run golden` |
| Every builder exposes exactly the catalog rules for its target type — no more, no less | `BuilderCoverageTest` / `regl-ts`: `npm test` |
| Decoders match the shared decode fixtures (JSON, `.properties` in core; YAML in the CLI) | `DecodeTest` / `regl-cli`: `mvn test` |
| JSON and YAML spellings of one schema load to the same schema | `regl-cli`: `SchemaFileTest` |
| URL loading, format detection, and the 0/1/2 exit codes behave as §13–§14 specify | `regl-cli`: `UrlsTest`, `ReglTest` |

### Changing the rule set

1. Edit `regl-spec/catalog.json`.
2. Add or update the predicate in `RuleEvaluators.registerBuiltIns()` (Java) and in `regl-ts/src/builtins.ts`.
3. Add i18n entries for all five locales, preserving the `{value}` tokens.
4. Add conformance cases covering both the passing and failing sides of the rule, plus at least one `typeMismatch` case.
5. Run `mvn test` (regl-java) and `npm test` (regl-ts).

`RuleType.java` is regenerated automatically by the build; you do not edit it. To regenerate deliberately:

```bash
mvn -q test-compile exec:java -Dexec.mainClass=dev.regl.RuleTypeGenerator
```

---

## Releasing

regl is published to Maven Central under the group `dev.regl` (`regl-java`, then
`regl-cli`, which depends on it), through the Sonatype Central Portal.

1. **Once**: the namespace `dev.regl` is verified in the
   [Central Portal](https://central.sonatype.com/) with a TXT record on `regl.dev`, and
   the repository has the secrets `CENTRAL_TOKEN_USER`, `CENTRAL_TOKEN_PASSWORD` (a user
   token of the Portal), `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE` (the signing
   key).
2. Set the version in `regl-java/pom.xml` and `regl-cli/pom.xml` (and the version of
   `regl-java` that `regl-cli` depends on), commit, and push a tag: `git tag v0.1.0 && git
   push origin v0.1.0`. The workflow `.github/workflows/publish.yml` runs
   `mvn -Prelease clean deploy` in both: sources, javadoc, signatures, upload.

By hand, with a `central` server in `~/.m2/settings.xml` holding the Portal token and a GPG
key in your keyring:

```bash
(cd regl-java && mvn -Prelease -DskipTests clean deploy)
(cd regl-cli && mvn -Prelease -DskipTests clean deploy)
```

`regl-ts` is the npm package `@regl/core`: `npm publish` in `regl-ts` (its `prepack`
builds it).

See [CONTRIBUTING.md](CONTRIBUTING.md) to work on regl and [SECURITY.md](SECURITY.md) to
report a vulnerability.

## License

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
