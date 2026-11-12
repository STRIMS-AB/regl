# regl-java

JVM reference implementation of the [regl](..) universal validation specification.

`regl-java` is the zero-dependency validation core behind the [`regl` CLI](../regl-cli): it decodes JSON and `.properties` documents, validates them against a schema, and returns every problem it can reach as a deterministic findings list.

## Installation

### Maven

```xml
<dependency>
    <groupId>dev.regl</groupId>
    <artifactId>regl-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'dev.regl:regl-java:0.1.0'
```

## Core Concepts

- **`Schema`**: A set of path → field declarations. `schema.validate(document)` resolves every path against a decoded document and returns a `DocumentResult`.
- **`Field` builders** (`StringField`, `NumberField`, `BooleanField`, `ListField`, `AnyField`): A chain of rules narrowed to one target type, so an incoherent chain does not compile. Created through `Rules.string()`, `Rules.number()`, `Rules.boolean_()`, `Rules.list()`, `Rules.any()`.
- **`DocumentResult`**: `isValid`, `errors`, `warnings` — one aggregated list rather than a throw on the first problem.
- **`DocumentFinding`**: One problem in a whole document: `severity`, `kind` (`RULE_FAILED`, `TYPE_MISMATCH`, `UNREACHABLE`, `NOT_A_LIST`, `UNKNOWN_FIELD`), concrete `path`, `rule` (null for structural findings), `value`, and `message`.
- **`Documents`**: The zero-dependency document decoders: `Documents.fromJson(text)` and `Documents.fromProperties(text)`, the latter expanding dotted `.properties` keys into the nested tree a schema's paths address. YAML decoding needs a real parser and lives in [`regl-cli`](../regl-cli), keeping this core dependency-free.
- **`Rule` / `RuleEvaluators`**: The rule engine the schema runs on — one immutable rule per chain element, looked up in a global registry. This is the layer beneath the schema; see [The rule engine beneath](#the-rule-engine-beneath).
- **`RuleType` / `TargetType` / `ParameterType`**: Generated enums and category types describing what a rule evaluates. Metadata for the engine and for tooling, not something a schema author touches.

## Usage

### 1. Validate a document

Build a schema, decode a document, validate:

```java
import static dev.regl.Rules.*;

Schema schema = Schema.create()
    .field("name",           string().notNull().minLength(1).enumOf("api", "web"))
    .field("server.port",     number().notNull().integer().lessThan(65536))
    .field("servers.*.host",  string().notNull().email())   // * matches every list element
    .field("tags",            list().notNull().unique())
    .strict();                                               // reject unknown keys

Object document = Documents.fromJson(configJson);
DocumentResult result = schema.validate(document);

if (!result.isValid()) {
    for (DocumentFinding finding : result.errors()) {
        // finding.kind(): RULE_FAILED, TYPE_MISMATCH, UNREACHABLE,
        //                 NOT_A_LIST, UNKNOWN_FIELD
        // finding.path(): concrete, e.g. "servers.0.host"
        // finding.rule(): e.g. "email", or null for structural findings
    }
}
```

Conventions, specified in
[`regl-spec/document-schema.md`](../regl-spec/document-schema.md):

- **A missing field is `null`.** `notNull()` is what makes a field mandatory —
  an ordinary catalog rule, not schema metadata.
- **Nothing is coerced.** A value whose kind contradicts the field's declared
  type is a `TYPE_MISMATCH` finding, never a conversion.
- **Overlapping paths are rejected at construction** with
  `IllegalArgumentException`, so two fields can never apply to one value.
- **`validate` never throws on document content.** Every problem is a finding.

### 2. Decode documents

The formats that need no dependencies decode here:

```java
Object fromJson = Documents.fromJson(text);                    // any JSON root
Map<String, Object> props = Documents.fromProperties(text);    // dotted keys expand
```

`server.port=8080` in a `.properties` document decodes to
`{ "server": { "port": "8080" } }`, which is exactly what a schema's
`server.port` field addresses. YAML needs a real parser and is decoded in
[`regl-cli`](../regl-cli) — the layer that may depend on proven libraries.

### 3. Schemas from data

For a schema that arrives as data — a serialized definition, a test table —
`Field.of(TargetType, rules)` builds a field and validates target types at
runtime, throwing `IllegalArgumentException` on a mismatch, exactly as the
code form cannot even compile one:

```java
Field<?> port = Field.of(TargetType.NUMBER, List.of(
        new Rule(RuleType.NOT_NULL),
        new Rule(RuleType.INTEGER),
        new Rule(RuleType.LESS_THAN, 65536)));

Schema schema = Schema.create().field("server.port", port);
```

This is also the shape a schema *file* parses into; the CLI loads it.

### 4. Custom evaluators

Register domain rules the same way the built-ins register, and use them in
schema chains like any catalog rule:

```java
import dev.regl.RuleEvaluator;
import dev.regl.RuleEvaluators;
import dev.regl.TargetType;

RuleEvaluators.register(new RuleEvaluator<Integer>() {
    @Override public String code() { return "dayOfMonth"; }
    @Override public String name() { return "Day of Month"; }
    @Override public String description() { return "Requires a number between 1 and {value}."; }
    @Override public Class<Integer> parameterType() { return Integer.class; }
    @Override public TargetType targetType() { return TargetType.NUMBER; }
    @Override
    public boolean test(Object value, Integer maxDay) {
        return !(value instanceof Number n) || (n.intValue() >= 1 && n.intValue() <= maxDay);
    }
    @Override
    public String message(String field, Object value, Integer maxDay) {
        return "Field '" + field + "' must be a day between 1 and " + maxDay + ".";
    }
});

A custom evaluator gets the central type guard the built-ins get: a value of the
wrong kind is reported as a `TYPE_MISMATCH`, never passed to the predicate.

Custom rules enter a schema through `Field.of`, which checks the evaluator's
declared target type at runtime — the same check the typed builders make at
compile time for built-ins:

```java
Schema schema = Schema.create().field("schedule.day",
        Field.of(TargetType.NUMBER, List.of(new Rule("dayOfMonth", 31))));
```

### 5. Constraint annotations

`regl-java` bundles typed annotations under `dev.regl.rules.*` corresponding to
built-in rules:

```java
import dev.regl.rules.Email;
import dev.regl.rules.MinLength;
import dev.regl.rules.Positive;

public record UserRegistration(
    @MinLength(3) String username,
    @Email String email,
    @Positive int age
) {}
```

The annotations are currently declarative metadata only — they carry rule code
and parameter but no engine reads them reflectively. Until a reflective
validator exists, declare constraints in a schema instead.

### The rule engine beneath

A schema validates a document by resolving each path and running its chain,
rule by rule, on the value it finds. Those pieces are public API because
tooling builds on them — a UI rendering the catalog, a test asserting one
constraint — not because you should validate a document by hand:

- `Rule` is one chain element: a rule code, an optional parameter, a
  `warnOnly` flag. `Rule.validate(field, value)` evaluates it against one value
  and returns a `ValidationResult` with `errors` and `warnings`.
- `RuleEvaluators` is the global registry: `require(code)`, `register(evaluator)`,
  `test(evaluator, value, value)`.
- A `warnOnly` rule reports failures as warnings that never make a result
  invalid; severity in document findings mirrors it.

The default `message` on any error is the engine's own English text, not the
catalog's `i18n/en.json` template. Render your own localized message from
`field`/`path`, `rule`, and `value` when you need translations.

## Conformance

`regl-java` is the **reference implementation** of rule semantics. Rule
behaviour is defined by the hand-written predicates in
`RuleEvaluators.registerBuiltIns()`; where `regl-ts` disagrees, Java is
correct.

`RuleType.java` is **generated** from [`regl-spec/catalog.json`](../regl-spec/catalog.json),
which is the canonical rule catalog. Do not edit `RuleType.java` by hand — the
build regenerates it and any drift fails `mvn test`.

```bash
mvn test
```

The suite asserts that every catalog rule resolves to a registered evaluator,
that the engine registers nothing the catalog omits, that rule metadata matches
the catalog, that every rule has at least one conformance case, and that the
verdicts match the shared fixtures in
[`regl-spec/conformance/cases.json`](../regl-spec/conformance/cases.json).

Document behaviour is tested the same way: both engines assert against one
shared golden transcript,
[`regl-spec/conformance/document-expected.json`](../regl-spec/conformance/document-expected.json),
so their findings must match including order. `GenerateDocumentGolden`
regenerates that file from this engine — regenerate only to review a deliberate
behaviour change, and read the diff before committing it. `BuilderCoverageTest`
additionally checks that the type-narrowing builders expose exactly the catalog
rules for their target type, so a new rule without a builder method — or a
builder method for a rule that targets another type — fails the build.

## Building

```bash
mvn clean test
mvn clean install
```

Requires JDK 17+. Zero runtime dependencies.
