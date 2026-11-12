# regl document schema specification

**Status:** The validation library (§4–§11) is implemented in both engines and
enforced by shared conformance fixtures. The schema-file format (§12), the
decoders (§13), and the CLI (§14) are specified here and implemented first in
`regl-cli` (Java); `regl-ts` follows against the same fixtures. Any divergence
between engines is a bug in one of them.

Scalar validation (`Rule.validate(field, value)`) is already specified by
conformance and unchanged by this document. Everything below builds on it.

---

## 1. Scope

### Goals

- **regl is a schema.** The product is a declarative schema file that describes a
  configuration document — its shape, its types, and its constraints — using the
  existing rule catalog.
- **regl validates real configuration files**: JSON, YAML, and `.properties`
  documents, loaded from a path or an `http(s)` URL.
- **regl is a CLI**: `regl <schema> <document>` loads a schema file and a document,
  validates, and exits with a code a pipeline or CI job can branch on.
- The library beneath the CLI stays available: a decoded tree validated against a
  programmatically built schema, returning one aggregated `errors`/`warnings`
  list rather than throwing on the first problem.

### Non-goals

- **Coercion.** Values are never converted. A value of the wrong kind is reported
  (§8). This is why a `.properties` document, whose values are all strings, can
  only satisfy `STRING` rules.
- **Type inference from the schema.** regl validates; it does not convert a
  document into a typed object. There is no binding layer.
- **Schema generation.** regl does not infer a schema from a sample document.

### The one-sentence contract

> Given a schema and a document — both loaded from a file or URL — regl returns
> every rule violation it can reach, sorted deterministically, without throwing
> on any document content.

A missing field is `null` and a `notNull()` rule is what makes it mandatory
(§6). Every constraint a field carries — its type, its presence, its value — is a
catalog rule in a single chain (§4).

### The layering

| Layer | Where | Dependencies |
| :--- | :--- | :--- |
| Validation library | `regl-java`, `regl-ts` core | none |
| JSON + `.properties` decoding | `regl-java`, `regl-ts` core | none (JDK / built-ins) |
| YAML decoding, schema files, URL loading, the CLI | `regl-cli` (Java first) | the CLI layer may depend on proven libraries, exactly as the validation core may not |

The zero-dependency promise belongs to the validation core. The CLI is a product
built on top of it and may use proven parsers (`picocli`, `snakeyaml`) for the
parts that are not worth hand-rolling — the same trade `bbrun` makes.

---

## 2. Document model

A decoded document is a tree of these kinds. These are the kinds a parser
produces; regl requires no more.

| Kind | JSON | YAML | `.properties` | regl term |
| :--- | :--- | :--- | :--- | :--- |
| object | `{...}` | mapping | — | `OBJECT` |
| list | `[...]` | sequence | — | `LIST` |
| string | `"..."` | scalar | always | `STRING` |
| number | `123`, `1.5` | scalar | always | `NUMBER` |
| boolean | `true` | scalar | always | `BOOLEAN` |
| null | `null` | `~`, `null` | — | `null` |

`OBJECT` keys are strings. A document has exactly one root; the root may be any
kind, including a scalar.

**Non-finite numbers.** `NaN` and `±Infinity` are not `NUMBER` for any purpose in
this spec. They can reach regl from a hand-built tree even though JSON cannot
express them, so they are reported as type mismatches (§7).

---

## 3. Paths

A **path** locates values in a document. Paths are dot-separated segments.

```
path    := segment ( "." segment )*
segment := "*" | key
key     := ( escaped | plain )+
escaped := "\" ( "." | "\" | "*" )
```

- `*` as an entire segment is the **wildcard**, matching every element of a list.
- A segment that is not exactly `*` is a literal object key.
- To address a key that is literally `.`, `\`, or `*`, escape it with `\`.
  `a.b` addresses the key `b` inside `a`; `a\.b` addresses the single key `a.b`.
- Lookup is **case-sensitive and exact**. No normalization, no trimming, no
  locale-aware case folding.
- A wildcard on a non-list is a structural error (§6), not an empty match.

Examples: `server.port`, `servers.*.host`, `*`, `matrix.*.*`, `tags.*`.

---

## 4. Schema declaration

A **schema** is a set of **fields**. A field is a path plus a chain of rules
applied to whatever that path resolves to.

### Java

```java
import static dev.regl.Rules.*;

Schema schema = Schema.create()
    .field("name",         string().notNull().minLength(1).maxLength(64)
                                       .lowercase().pattern("^[a-z0-9-]+$"))
    .field("server.port",  integer().notNull()
                                       .greaterThan(0).lessThan(65536))
    .field("server.host",  string().notNull().email())
    .field("servers.*.host", string().email())
    .field("servers.*.port", integer())
    .field("tags",         list().notNull().unique())
    .field("debug",        boolean_().isFalse())
    .strict();
```

### TypeScript

```ts
import { Schema, string, number, list, boolean_, any } from './index';

const schema = Schema.create()
    .field('name',         string().notNull().minLength(1).maxLength(64)
                                       .lowercase().pattern('^[a-z0-9-]+$'))
    .field('server.port',  integer().notNull()
                                       .greaterThan(0).lessThan(65536))
    .field('tags',         list().notNull().unique())
    .field('debug',        boolean_().isFalse())
    .field('mode',         string().notNull().enumOf('fast', 'safe'))
    .strict();
```

In TypeScript the narrowing is structural rather than nominal: `StringField` and
`NumberField` are distinct classes, so `string().greaterThan(5)` fails to
compile for the same reason. Both engines spell the boolean head `boolean_()`,
because Java method names cannot be keywords; one spelling, so a schema reads
identically in either language.

### The chain narrows to a type-specific builder

`string()` returns a **string builder**. It is not a generic builder that happens
to accept a string rule — it exposes only the string rules, and nothing else
exists on it:

```java
Schema.create()
    .field("name", string().notNull().minLength(1).maxLength(64)
                                   .lowercase().pattern("^[a-z0-9-]+$"))
    .field("server.port", integer().notNull()
                                     .greaterThan(0).lessThan(65536))
    .field("tags",  list().notNull().unique().range(1, 10))
    .field("debug", boolean_().isFalse())
    .field("mode",  string().notNull().enumOf("fast", "safe"))
    .strict();
```

Each head returns a builder carrying exactly the rules that target its type:

| Head | Builder | Rules it offers |
| :--- | :--- | :--- |
| `string()` | `StringField` | `minLength`, `maxLength`, `length`, `pattern`, `contains`, `startsWith`, `endsWith`, `email`, `url`, `uuid`, `languageCode`, `lowercase`, `uppercase`, `trimmed`, `notEmpty`, `text`, `dateFormat`, `isoDate`, `isoDateTime`, `isoTime`, `after`, `before` |
| `number()` | `NumberField` | `greaterThan`, `greaterThanOrEqual`, `lessThan`, `lessThanOrEqual`, `positive`, `negative`, `positiveInteger`, `negativeInteger`, `multipleOf`, `precision` |
| `integer()` | `IntegerField` | `greaterThan`, `greaterThanOrEqual`, `lessThan`, `lessThanOrEqual`, `positive`, `negative`, `multipleOf` |
| `boolean_()` | `BooleanField` | `isTrue`, `isFalse` |
| `list()` | `ListField` | `range`, `unique` |
| `date()` | `DateField` | only the shared rules below |
| `dateTime()` | `DateTimeField` | `epoch`, `epochMillis` |
| `time()` | `TimeField` | only the shared rules below |

#### `INTEGER` is a whole number, and a `NUMBER` rule still applies to it

`INTEGER` is a **target type**, not a rule. There is no `integer` rule in the
catalog, because the type already asserts it: a field declared `INTEGER` is
rejected unless its value is a whole number, so `integer()` on that field would
be a tautology.

"Whole number" means **mathematically whole**, and the two engines define it
identically. It is *not* a class test, and that distinction is load-bearing:

| Input | `INTEGER`? | Why |
| :--- | :--- | :--- |
| `8080` | yes | |
| `1.0`, `1e3` | yes | `1.0` is the same number as `1`; the notation is not the value |
| `2147483648` | yes | above `Integer.MAX_VALUE`, so Java sees a `Long` and JS a plain double |
| `9007199254740993` | yes | above JS's safe range, but nothing about it is fractional |
| `80.5` | no | it has a fractional part |
| `"8080"` | no | a numeric string is a `STRING`, and nothing is coerced |
| `true` | no | |

The old `integer` rule answered "is this whole?" by inspecting the Java class or
asking `Number.isInteger`. Asking the **value** is what makes the two engines
agree, and it is why `2147483648` is not rejected on the way in — which is
exactly the class of bug that quietly truncates a port or an id.

`INTEGER` also **accepts every `NUMBER` rule**, even though no rule targets
`INTEGER`. An integer is a number with a whole-number assertion laid on top, so
the magnitude rules still speak about it, and a port is still bounded:

```
port  INTEGER  lessThan 65536   ->  70000 fails, 8080 passes
```

`IntegerField` offers `greaterThan`, `greaterThanOrEqual`, `lessThan`,
`lessThanOrEqual`, `positive`, `negative` and `multipleOf`. It withholds three
`NUMBER` rules, all deliberately:

- `integer` — the field type already asserts it, so the rule would be a no-op.
- `precision` — a whole number has no decimal places to count.
- `positiveInteger` and `negativeInteger` — `positive()` and `negative()` say
  the same thing here without the redundancy.

Withholding them is a builder choice, not a validation one. A hand-built
`Field.of(TargetType.INTEGER, ...)` may still carry them, and both engines accept
them; the fluent builder just declines to offer a rule that cannot add anything.

`positiveInteger` and `negativeInteger` remain in the catalog on `number()`,
where they still mean something a `positive()`-style check does not: they reject
`1.5`, which `positive()` accepts.
| `any()` | `AnyField` | only the shared rules below |

Two rules are shared by every builder, because the catalog assigns them
`targetType: ANY` — they are not about a particular kind of value:

- `notNull()`, because presence applies to every type (§6);
- `enumOf(...)`, which on a typed builder reads as "one of these strings".

Nothing else is shared, so nothing can be put in the wrong chain. `any()` is the
last resort: with no declared type it can only carry the two shared rules, and a
typed builder says everything it could have said and more.

The point is that an incoherent chain is **not expressible**:

```java
string().greaterThan(5)     // does not compile: NumberField.greaterThan is not on StringField
```

This is strictly better than validating the chain at runtime. A developer writing
a schema gets a compile error at the call site, not an exception from somewhere
else later. The list of rules per builder is derived from the catalog's
`targetType` values, so it cannot drift from the rule set: adding a `STRING` rule
adds a method to `StringField` and no other builder.

### The terse form trades one check for length

The chain buys exactly one thing: the compiler rejects a rule on the wrong type.
Everything else about it is sugar, so when the chain is longer than the constraint
it expresses, the same rules can be given as values:

```java
import static dev.regl.Rule.*;            // notNull(), lessThan(65536)

.field("server.port", integer().with(notNull(), lessThan(65536)))
```

```ts
import { notNull, lessThan } from '@regl/core';

.field('server.port', integer().with(notNull, lessThan(65536)))
```

There is one factory per catalog rule, named after the rule, with the argument
type the catalog declares. The head still fixes the target type at compile time
and each factory still type-checks its own argument, so what is lost is only the
compile-time rejection of a mis-targeted rule — and that is then caught by
`with(...)` itself, which applies the same check `Field.of` does:

```
number().with(email())
  -> IllegalArgumentException: Field declares type NUMBER but rule 'email' targets STRING
```

The check is deliberately not duplicated logic: `with(...)` and `Field.of(...)`
call the same routine, and a test asserts they reject a mis-targeted rule with
the *same message*, so the two spellings cannot drift apart.

`with(...)` appends, in the order given, after whatever is already on the
builder, so it composes with the chain rather than replacing it:

```java
integer().greaterThan(0).with(notNull(), lessThan(65536))   // greaterThan, notNull, lessThan
```

The catalog's rule ids are all `camelCase`, so a factory is the rule's name with
no decoration; `enum` is the one that needs a rename, to `enumOf` in both
languages, because `enum` is reserved in each.

What the terse form cannot express is a rule on a path whose type is decided at
runtime — which is exactly the case `Field.of(...)` and the schema file already
cover. Between the three, no schema needs the raw constructor.

### The formal form throws

The narrowing builder is a convenience, not the only way to declare a field. A
caller may also construct one directly — from a data-driven schema, a test, or a
serialized definition — and that path is checked at runtime instead:

```java
Field.of(TargetType.STRING, List.of(     // formal: type and rules given as data
        new Rule(RuleType.MIN_LENGTH, 1),
        new Rule(RuleType.EMAIL)));

schema.field("server.port", Field.of(TargetType.STRING, List.of(
        new Rule(RuleType.NOT_NULL),
        new Rule(RuleType.MIN_LENGTH, 1))));
```

`Field.of(...)` throws **`IllegalArgumentException`** when a rule's target type
does not match the declared one:

```
Field.of(TargetType.STRING, List.of(new Rule(RuleType.GREATER_THAN, 0)))
  -> IllegalArgumentException: Field declares type STRING but rule
     'greaterThan' targets NUMBER
```

A rule whose target type is `ANY` — `notNull` and `enum` — is accepted on a field of
any declared type. That is what lets those two be shared by every builder rather
than duplicated once per type.

`IllegalArgumentException` rather than a dedicated exception type, because it is
the same class of fault the engines already raise for a bad rule argument
(`Rule`'s constructor, `RuleEvaluators.checkParameter`). One exception type for
"you built a rule wrongly", in both engines, and callers already handle it.

Both paths converge on the same internal representation, so a schema built
through the typed builder and one built through `Field.of` behave identically.

### Rules are appended in order

| Chain element | Adds |
| :--- | :--- |
| a head (`string()`, `number()`, …) | The type assertion. Must be first. |
| `notNull()` | The value must be present and not null (§6). A catalog rule. |
| a rule method | Appends that rule with its argument. |

Appending the same rule twice is allowed and produces two findings — some
constraints are genuinely duplicated by accident, and dropping one silently would
hide it. What is *not* allowed is two fields matching one concrete path; see
below.

Field declaration order is significant: it fixes the order of reported errors
(§9).

### Reusable field types

A chain that several fields share does not have to be written several times. A
schema may declare a **field type** — a named target type plus a chain of rules —
and attach it to any path by name. A field type is the *base* of a chain, never a
replacement for one: whatever rules the referencing field adds are appended
**after** the type's own, in declaration order.

```java
Schema schema = Schema.create()
    .define("Email",     string().notNull().email().maxLength(254))
    .define("WorkEmail", "Email", new Rule(RuleType.ENDS_WITH, "@example.com"))
    .field("contact.email",   "WorkEmail")
    .field("billing.address", "Email", new Rule(RuleType.TRIMMED))
    .strict();
```

`contact.email` therefore carries four rules in this order — `notNull`, `email`,
`maxLength`, `endsWith` — and `billing.address` carries `notNull`, `email`,
`maxLength`, `trimmed`. Because rule order fixes finding order (§9), base-first
is also what makes a field type's findings come before the field's own.

The name-taking `define` and `field` take `Rule...` — rule *data*, the formal form
described above — not a builder: the chain a type contributes is assembled by
concatenation, so there is no builder to hand over. A typed builder still builds
a whole field, and `define(name, field)` is the overload that takes one. Java
spells the three cases as three overloads (a complete `Field`, a `TargetType`
plus rules, or a base *name* plus rules); TypeScript takes the base as a
`string | TargetType | Field` and the rules as rest arguments, which is the same
three overloads in one signature:

```ts
const schema = Schema.create()
    .define('Email',     string().notNull().email().maxLength(254))
    .define('WorkEmail', 'Email', new Rule('endsWith', '@example.com'))
    .field('contact.email',   'WorkEmail')
    .field('billing.address', 'Email', new Rule('trimmed'))
    .strict();
```

All of it converges on the same internal representation, so a field reached
through a reusable type behaves exactly as one declared in full.

#### The two namespaces are disjoint

A **target type** is one of the catalog's: `STRING`, `NUMBER`, `INTEGER`, `BOOLEAN`,
`LIST`, `ANY`, `DATE`, `DATETIME`, `TIME`. A **field type** is an identifier the schema
declares. Both are spelled in a field's `type` position, so the rule that keeps
them apart has to be explicit:

- A field type **may not be named after a target type**, and is a construction
  error if it is. The two names must never be ambiguous, so there is no
  precedence rule to remember and no silent no-op to hit — a declaration named
  `STRING` is rejected outright rather than quietly ignored because the catalog
  already claims that name. §12 records why that ambiguity is removed rather than
  resolved.
- Both are **case-sensitive**. `STRING` is the target type; `String` is not the
  same name and is not a field type unless the schema declares it. One exact
  spelling each, in both engines and in schema files (§12).

#### A type must be resolvable before it is used

A field type resolves to a concrete target type and a concrete rule chain, and a
path's field must have a concrete target type to attach. So a definition has to be
complete before anything references it — that is structural, not a choice, and it
holds in every form.

What *is* a choice is what happens when a base is not declared yet, and here the
two forms differ deliberately:

| Form | A base declared later | A cycle |
| :--- | :--- | :--- |
| Code (`.define`, `.field`) | Construction error: unknown base type | Not expressible — ordered statements cannot close a loop |
| Schema file (`types`) | Fine; resolved on demand | Load error, cycle named in traversal order |

The code form is ordered because a program *is* ordered: `Email` is a value, and
using a value before it exists is the mistake a compiler exists to catch. A
JSON or YAML document has no order to violate, so the file form resolves in
dependency order instead and rejects only what is genuinely unresolvable — an
unknown base or a cycle. The same declaration is therefore legal in one form and
an error in the other, which is worth knowing and is not a divergence in the
*result*: both forms agree on what a fully-resolved type means.

#### A field type carries no path

A field type is a type, not a field, so it has no `path`, and it cannot be
`strict`, contribute to `maxDepth`, or overlap another declaration (§11). Every
path that wants these rules declares them, which is what makes the path — and so
the finding path (§9) — the thing the schema actually constrains.

### Schema options

| Option | Default | Meaning |
| :--- | :--- | :--- |
| `.strict()` | off | Reject object keys no field covers (§10). |
| `.maxDepth(n)` | 32 | Longest path a field may declare (§11). |

There are no per-field options. Everything a field can say, it says in its chain.

---

## 5. Resolution

Resolving a path yields zero or more **(path, value)** pairs, plus any structural
problems encountered. Start at the document root and consume segments:

- **Literal key.** The current value must be an object. If it is not, record
  `UNREACHABLE` at the path consumed so far and stop. If it is an object but
  lacks the key, the resolved value is **`null`** (§6) — stop, with no problem
  recorded.
- **Wildcard.** The current value must be a list. If it is not, record
  `NOT_A_LIST` at the path consumed so far and stop. If it is a list, continue
  once per element, and track the index as part of the concrete path
  (`servers.0.host`).

A `null` at any point is never a structural problem. It resolves to no further
values: a wildcard through `null` yields nothing, because a null list has no
elements to index. Only a **present** value of the wrong shape is a problem.

Resolution never produces a third "absent" state. A key that is not there
resolves to `null`, exactly as if the document had said `null`, and an ancestor
that is `null` makes every path beneath it `null`. That uniformity is what lets
the chain in §6 have a single rule about null instead of two rules about absence
and null.

Each concrete path is the declared path with every wildcard replaced by the
element index it matched. It is what appears in the `path` field of a result.

The declared spelling is otherwise preserved: a key escaped in the declaration
stays escaped in the concrete path, so `a\.b` still names one key and the path
does not split into two segments when it is read back.

Structural problems are reported once per offending declaration, not once per
element.

---

## 6. Absence is `null`, and `notNull()` demands otherwise

**There is no `required()`, and no `nullable()`.** Both concepts collapse into
one value and one rule.

- **A missing field is `null`.** Resolution never yields an "absent" value; it
  yields `null`. There is no third state.
- **`notNull()` is what makes a value mandatory.** It is a catalog rule with
  `targetType: ANY`, so it is the one rule whose whole purpose is to reject
  `null`.
- **`null` short-circuits the chain.** When the subject is `null`, no other rule
  in the chain runs. `notNull()` is evaluated *first*, before the short-circuit.

```
number()                             on missing  ->  valid       (optional)
number().notNull()                   on missing  ->  RULE_FAILED (mandatory)
number().notNull().greaterThan(123)  on null     ->  RULE_FAILED
number().greaterThan(123)            on null     ->  valid       (optional)
string()                             on 8080     ->  TYPE_MISMATCH
string().notNull().minLength(1)      on 8080     ->  TYPE_MISMATCH
```

So a chain reads as a statement about a value, and presence is just another
constraint on it:

| Chain | Means |
| :--- | :--- |
| `string()` | If present, must be a string. |
| `string().notNull()` | Required, and must be a string. |
| `string().notNull().email()` | Required, a string, and a valid address. |

This is why `nullable()` is unnecessary. Presence, null-tolerance, and type are
three questions about one value, and `notNull()` plus the type head answer all
three. A `nullable()` flag would have been a second, redundant way to say the
same thing — and worse, it would have been a *non-rule*: a piece of schema
metadata that no catalog entry describes, no `i18n` message covers, and no
conformance fixture can test. Keeping everything in the chain means every
constraint is a catalog rule with an id, a localized message, and fixtures.

### What this costs

Explicit `null` and a missing key are **indistinguishable**. A document cannot
express "this key must be present, with the value `null`", and regl will not
distinguish `{"port": null}` from `{}`. If that distinction is ever needed, it
belongs in a separate structural check, not in `notNull()`.

### Wildcards

A wildcard whose parent is `null` yields no elements, so its chain never runs.
To require at least one element, use `notNull()` **and** `range`:

```
list().notNull().range(1, 10)     // a present, non-empty list of at most 10
```

`notNull()` alone does not reject `[]` — an empty list is present and not null.
`range` with a `min` is the way to set a floor.

### `null` and the scalar API

The `null` short-circuit belongs to the **document** layer. The scalar API takes
a value you hand it, so `new Rule(RuleType.GREATER_THAN, 0).validate("port", null)`
still reports `TYPE_MISMATCH` — you passed a null, and the rule wants a number.
The scalar layer has no notion of a missing field, so it has no reason to forgive
one. This is why the `typeMismatch` fixtures that assert `greaterThan` on `null`
remain correct.

Note the asymmetry with the **target type** check, which does not forgive null at
all: `matchesTargetType(TargetType.INTEGER, null)` is false in both engines. The
document layer never asks that question about a null, because it stops at
`notNull` first, so the two facts never collide.

---

## 7. Type mismatches

This section restates the rule already enforced by the `typeMismatch` fixtures in
`conformance/cases.json`, and extends it to whole-document reporting.

A rule only speaks about values of its own `targetType`. Given a **present,
non-null** value whose kind does not match a rule's target type:

- the rule **fails**,
- the finding is reported as `TYPE_MISMATCH`, a distinct `kind` from an ordinary
  rule failure, and
- the rule's predicate is **never invoked**.

Nothing is coerced and nothing is silently skipped.

```
minLength  on 8080   ->  TYPE_MISMATCH   Value must be of type STRING, but got NUMBER.
greaterThan on "8080" ->  TYPE_MISMATCH   Value must be of type NUMBER, but got STRING.
unique     on 8080   ->  TYPE_MISMATCH   Value must be of type LIST,   but got NUMBER.
greaterThan on null  ->  TYPE_MISMATCH   Value must be of type NUMBER, but got null.
```

`TargetType.ANY` rules match every kind and never produce a `TYPE_MISMATCH` —
which is what makes `notNull()` and `enum` work.

In a chain, `null` never reaches a rule: §6 short-circuits first, so
`number()` on a missing field is valid, not a mismatch. The `greaterThan` line
above still reports a mismatch because that is the **scalar** API, where the
caller supplied a real `null` rather than a missing field.

### Document findings are a separate type

A document has both shape problems and value problems, and a caller usually wants
to treat them differently: a `TYPE_MISMATCH` or `UNREACHABLE` is a bug in the
producer's structure, whereas a `RULE_FAILED` on a well-typed `name` field is
ordinary input validation. Collapsing both into "the rule failed" would force
every consumer to string-match the message to tell them apart.

So document validation does **not** extend `ValidationError`. It reports a
separate `DocumentFinding`, and `ValidationError` stays exactly as it is — four
fields, no `kind`, no growth. The scalar API and the document API are different
reporters over the same catalog, not one reporter with an extra field.

```java
record DocumentFinding(
    Severity severity,     // ERROR or WARNING
    Kind kind,
    String path,           // concrete path, e.g. "servers.2.port"
    String rule,           // rule code, or null for structural findings
    Object value,          // the rule's own argument, or null
    String message
) {}
```

Two consequences of keeping them apart:

- **Scalar callers are untouched.** `ValidationError` is unchanged, so no
  existing consumer breaks and no existing fixture needs a `kind`.
- **Conversion is explicit.** A caller who wants to render findings through the
  scalar path must map them by hand. That is the intended friction: the shapes
  genuinely differ, and pretending otherwise with an optional field would make
  every consumer handle a `kind` that may or may not be there.

Note there is no `MISSING` kind. Under §6 a missing field is `null`, so a missing
mandatory field is an ordinary `RULE_FAILED` on `notNull`. The structural kinds
are only those describing a document's shape: `TYPE_MISMATCH`, `UNREACHABLE`,
`NOT_A_LIST`, `UNKNOWN_FIELD`.

---

## 8. No coercion

**There is no coercion, implicit or explicit.** A value of the wrong kind is
reported, never converted.

This is a deliberate reversal of an earlier draft, on the grounds that a
`coerce(TargetType)` switch is not extensible: each new pair of types means a new
branch, the switch cannot express conversions that compose with the chain, and it
invents a validation concern that no catalog rule describes. Adding rules to a
chain should never require touching a type-dispatch table.

The chain makes that workable. A conversion is naturally a *chain step that
narrows*, so if one is ever needed it can be added as an ordinary rule rather
than as new behaviour for the whole type system:

```
number().notNull().fromString()      // hypothetical: a rule, not a mode
```

### The consequence: `.properties`

`.properties` cannot express types — every value is a string. With no coercion,
`number()` on `server.port=8080` reports a `TYPE_MISMATCH`, and `.properties` can
only be validated for `STRING` rules.

That is an honest limitation, not an oversight, and there are three ways out:

1. Use a format that carries types. JSON and YAML both work fully.
2. Keep a `.properties` schema to strings and let the consumer that reads the
   properties do its own parsing.
3. Add a real `fromString` rule later, in the catalog, with fixtures and i18n —
   then `.properties` works and nothing else changes.

Option 3 is the point of the chain: the extension is one catalog rule, not a new
mode on the schema.

---

## 9. Results

Document validation reports `DocumentFinding`, a type distinct from the scalar
`ValidationError` (§7).

```java
record DocumentResult(
    boolean isValid,          // true when errors is empty; warnings never affect it
    List<DocumentFinding> errors,
    List<DocumentFinding> warnings
) {}
```

```ts
interface DocumentResult {
    valid: boolean;
    errors: DocumentFinding[];
    warnings: DocumentFinding[];
}
```

Each engine names validity the way its scalar result already does — `isValid()`
in Java, `valid` in TypeScript — so the two APIs read consistently within a
language before they read identically across languages.

The shape of a finding is specified in §7, where the decision to keep it separate
from `ValidationError` is explained.

### Kinds

| `kind` | Meaning |
| :--- | :--- |
| `RULE_FAILED` | A rule's predicate returned false. Includes a mandatory field that was missing — that is `notNull` failing on `null` (§6). |
| `TYPE_MISMATCH` | A present, non-null value's kind does not match a rule's `targetType`. |
| `UNREACHABLE` | A literal key was read through a non-object. |
| `NOT_A_LIST` | A wildcard was applied to a non-list. |
| `UNKNOWN_FIELD` | A `strict()` schema found an uncovered object key. |

`RULE_FAILED` now covers presence, which is the payoff of §6: there is no
separate kind for "the field was not there", because regl cannot tell that apart
from "the field was null". One kind, one code path.

### Severity

A finding is a `WARNING` when the rule carries `warnOnly` (`Rule.withWarnOnly(true)`).
A `WARNING` never makes `valid` false. Structural findings are always errors —
`warnOnly` applies to rule outcomes, not to a document's shape.

### Ordering

Findings are sorted by, in order:

1. the field's declaration index,
2. the rule's position within the field,
3. the finding's concrete path, compared segment by segment, with two segments
   that are both list indices compared **numerically** — this is what keeps
   element order intact past the tenth element, where plain string order would
   sort `ports.10` before `ports.2`,
4. `kind`, as a tiebreak so the order is total.

The order is therefore total and identical in both engines. It does **not**
depend on object key order, hash iteration, or which rule failed first.

### Messages

`message` is a convenience English default, exactly as in scalar validation.
Localize from `kind` + `rule` + `value` against `i18n/<locale>.json`.

`i18n/<locale>.json` gains one non-rule section alongside `rules`:

```json
{
  "rules": { "...": {} },
  "findings": {
    "TYPE_MISMATCH": "Field '{field}' must be of type {expected}, but got {actual}.",
    "UNREACHABLE":   "Field '{field}' cannot be read: the parent is not an object.",
    "NOT_A_LIST":    "Field '{field}' cannot be read: the value is not a list.",
    "UNKNOWN_FIELD": "Field '{field}' is not allowed."
  }
}
```

Token parity across locales is enforced by `site/scripts/verify-locales.mjs`
alongside the existing per-rule checks.

---

## 10. Strict mode

By default a schema is **open**: object keys that no field covers are ignored.
This is the right default because schemas are usually partial — a validation
schema for a config file rarely wants to forbid a key it does not validate.

`strict()` inverts that. Any object key not covered by a declared path becomes an
`UNKNOWN_FIELD` error.

Coverage is by path prefix, and a wildcard covers each element's subtree:

- declared `server.port` covers `server` and `server.port`, but not `server.host`
- declared `servers.*.host` covers `servers`, each `servers.<i>`, and
  `servers.<i>.host`
- declared `tags` does **not** cover anything inside `tags`
- declared `body` of type `ANY` covers `body` and **everything under it**
  (`body.id`, `body.items.0.sku`)

A wildcard does not by itself make an object subtree open. To leave part of the
document unconstrained under `strict()`, declare it explicitly, as `ANY`: a field
that accepts any value accepts whatever that value contains, so a free-form part
(a message body, a map of headers) sits inside a strict document without listing
its keys.

---

## 11. Duplicate and overlapping paths

**Two fields must never be able to match the same concrete path.** If they could,
validation would apply two chains to one value and the reported findings would
depend on declaration order in a way the author did not intend.

Declared paths must be **disjoint**. Two paths overlap when some concrete path
could match both, which is decidable without looking at any document.

Each wildcard substitutes exactly one segment for exactly one list index, so a
declared path's segment count is fixed and two paths of different lengths address
different values. **Overlap therefore requires equal segment counts**, and then
each segment pair must be able to name one value:

| Segment pair | Compatible | Why |
| :--- | :--- | :--- |
| `port` / `port` | yes | the same key |
| `port` / `host` | no | different keys |
| `*` / `0` | yes | a wildcard matches the index `0` |
| `*` / `primary` | no | `primary` is a key, never a list index |
| `*` / `*` | yes | both match any index |

Worked examples:

- `server.port` and `server.host` — disjoint.
- `server.*` and `server.host` — **disjoint**: equal length, but a wildcard matches
  a list index and `host` is not one.
- `server.*` and `server.0` — **overlapping**: both can match `server.0` when
  `server` is a list.
- `server.*` and `server.*.port` — **disjoint**: they have different lengths, so
  they address values at different depths. A list of objects is a legitimate
  thing to constrain at two levels at once.
- Any path declared twice — overlapping.

Overlap is a schema-construction error and throws `IllegalArgumentException`,
naming both paths. Deciding it at build time rather than skipping a duplicate at
validation time is deliberate: a schema that accidentally declares both
`servers.*.port` and `servers.0.port` is a mistake, and silently applying both
chains would make the schema's behaviour depend on which rule happened to be
declared first.

Note that `string()` and `number()` chains on the *same* path are also overlapping
— there is no "intersect the constraints" behaviour. One path, one chain.

### Depth limit

A field's path may not exceed `.maxDepth(n)` segments (default **32**). Exceeding
it throws `IllegalArgumentException` when the schema is built.

The limit is a property of the **schema**, not of the document, so it is checked
once at construction and costs nothing per validation. It exists because path
resolution recurses, and a 10,000-segment declared path is a typo rather than a
requirement. It also bounds the cost of resolution for any document, since a
concrete path can never be longer than the declaration that produced it.

---

## 12. Schema files

A schema is defined in a **file**. The file format is the terse form of §4,
serialized: a rule is its own name or a one-key object naming it, and `fields` is
keyed by path. YAML, which is often kinder to hand-authored configuration —
block style for one field, flow style for the other, mixed freely, because both
parse to one model:

```yaml
strict: true
maxDepth: 16
fields:
  server.port:
    type: INTEGER
    rules: [notNull, { lessThan: 65536 }]
  server.host: { type: STRING, rules: [notNull, email] }
```

The same schema in JSON — the spellings parse to one model, round-trip
losslessly, and load to the same schema:

```json
{
  "strict": true,
  "maxDepth": 16,
  "fields": {
    "server.port": { "type": "INTEGER",
                     "rules": [ "notNull", { "lessThan": 65536 } ] },
    "server.host": { "type": "STRING", "rules": [ "notNull", "email" ] }
  }
}
```

Two choices carry almost all of the shape.

**A rule is its own name, or a one-key object naming it.** This is how JSON
Schema, OpenAPI and SQL DDL write a keyword with a value, and it removes the
`{code, value}` envelope:

```yaml
# the withdrawn envelope — now a load error, a rule map with two entries
rules:
  - code: notNull
  - code: lessThan
    value: 65536

# the spelling
rules: [notNull, { lessThan: 65536 }]
```

**`fields` is keyed by path.** A path is already a string and any string is a
legal path, so there is nothing to check the key against — which is what removes
`"path":` from every entry. This is the same move as JSON Schema's `properties`.
A field with no rules of its own is then just its type name, mirroring how a rule
with no argument is just its name.

A larger one, with a reusable type (§ "Reusable field types" below), is seven
lines:

```yaml
strict: true
types:
  Email: { type: STRING, rules: [notNull, email, { maxLength: 254 }] }
  WorkEmail: { type: Email, rules: [{ endsWith: '@example.com' }] }
fields:
  contact.email: WorkEmail
  billing.address: { type: Email, rules: [trimmed] }
```

The check on the loader is the normalized schema, which is what `regl schema`
prints for the file above:

```
contact.email  STRING  notNull email maxLength endsWith
billing.address  STRING  notNull email maxLength trimmed
strict on, maxDepth 32, 2 fields
```

The two rule lists are the base merged first and then the referring field's
own — the ordering §12 already specifies, unchanged.

### Grammar

```
schema    = { strict?: bool, maxDepth?: int, types?: typedefs, fields?: fields }
typedefs  = { name: typedef }
fields    = { path: field }            # a duplicate key is a load error
typedef   = typeRef | { type: typeRef, rules?: rules, severity?: severity }
field     = typeRef | { type: typeRef, rules?: rules, severity?: severity }
typeRef   = TARGET_TYPE | NAME
rules     = [ rule, ... ]              # order is the finding order of §4
rule      = code                        # no argument; error unless the entry says warn
          | code!                       # no argument; warn
          | { code: value }            # with its argument; error unless the entry says warn
          | { code!: value }           # with its argument; warn
severity  = error | warn                # the field/type entry key; error is the default
```

A rule's `value` may be `null`, so `{ email: null }` is accepted for a rule the
catalog gives no argument, but the bare `email` is the canonical spelling and
the one the examples use. `value` is the rule's own argument; the value being checked is the
document under test, which the conformance cases call `input`.

### How a rule entry is read

A rule entry is a bare name, or a map with exactly one entry — the rule's
name and its argument — and a name ending in `!` is the same rule at warn.
That is the whole grammar, and it is total:

| Entry | Read as |
| :--- | :--- |
| `notNull` | the `notNull` rule, no argument |
| `email!` | the `email` rule, no argument, a warning |
| `{ lessThan: 65536 }` | the `lessThan` rule with argument `65536` |
| `{ maxLength!: 20 }` | the `maxLength` rule with argument `20`, a warning |
| `{ email: null }` | the `email` rule; a no-argument rule written longhand |
| `{ range: { min: 1, max: 2 } }` | the `range` rule with an object argument |

`range` is the case worth checking, because its argument is itself an object. It
is unambiguous: `{ min, max }` is the *value* of the single key, not a second
entry, so `{ range: {...} }` has exactly one entry and is a rule map.

The mark rides the end of the name for two reasons. A catalog id cannot contain
`!`, so no rule name can collide with the mark. And on the name the argument
stays a first-class value, so `{ range!: { min: 1, max: 2 } }` simply works.

A call-shaped `maxLength(20)!` was rejected on the same ground as the envelope it
would replace: the argument would live inside a string, so `pattern('^[a-z]+$')!`
needs quoting rules, `contains(')')!` breaks them, and `range`'s object argument
has no home — an ad-hoc encoding with a grammar of its own. On the name there is
nothing to parse.

`!` is chosen over the other one-character marks because of where it sits in
YAML. As a **suffix** it is a plain scalar character, so a marked rule needs no
quoting anywhere:

```yaml
rules: [notNull, email!, { maxLength!: 20 }]        # flow style, unquoted
```

Every prefix mark is worse, and `?` worst of all: `?` is a YAML flow indicator, so
`[email?]` is a **syntax error** and only the quoted `'email?'` parses, while
`+maxLength` parses but reads as arithmetic. A mark that is legal only when
quoted is a papercut in exactly the compact one-liners the terse format is for.

Note the polarity is the reverse of TypeScript's `!`, which asserts that a value
is present. In a schema `maxLength!` means the opposite — the rule advises
instead of rejecting — because the mark's job is to soften, not to insist. A
reader coming from TypeScript should be told this once, and then the mark is
unambiguous: severity is the only thing it can ever mean here.

The `{code, value, severity}` envelope is withdrawn with the mark; it is now a
load error, a rule map with more than one entry.

### Severity replaces `warnOnly` in schema files

`warnOnly` is a code-level flag carried by `Rule` and set by
`Rule.withWarnOnly(true)`. It is the right shape in code, where the rule is an
object you can ask. It is the wrong shape in a data file, for two reasons: the
flag has no level to express, so a field that is a warning *in one place* and an
error *everywhere else* cannot be written; and it puts a rule's outcome on the
rule entry while the same outcome is usually a property of the field.

So schema files mark severity in two places, with one default:

- A field or type entry may carry `severity: warn`, which applies to every rule
  the entry contributes — a field's, including the rules inherited from the
  type it names.
- A rule's name may carry `!`, which applies to that rule alone.
- A rule warns if its name carries `!` or its entry says warn; otherwise it is
  an `error`.

```yaml
# the shape rules are soft, the presence rules are not
billing.address: { type: Email, rules: [email!, { maxLength!: 254 }, trimmed, notNull] }
```

The common case is the other direction: no `severity` on the entry, so every
rule is an error, and one rule carries `!` — a soft limit that advises rather
than rejects, in one line:

```yaml
fields:
  nickname:
    type: STRING
    rules:
      - notNull                    # an error: the default
      - maxLength!: 20             # one soft rule — a warning that never fails
```

A 29-character nickname against it, exactly as the CLI prints it — the warning
is reported, the document is still valid, and the exit code is 0:

```
WARNING nickname maxLength: Maximum length is 20, but got 29.
ok (1 warning)
```

And a `null` nickname still fails, because `notNull` kept the default and a
warning on one rule does not soften another:

```
ERROR nickname notNull: Value is required and must not be null.
1 error
```

A `severity` on the field overrides one on the type it names, because that is
what the merge order already does to `rules`: base first, then the referring
entry's own. A type's `severity` is itself the nearest declared one up its own
base chain, so `Email: { severity: warn }` reached through `WorkEmail` is still
`warn` unless `WorkEmail` or the field says otherwise.

One direction is deliberately not writable: a plain rule under a warn entry
cannot be turned back into an error by the rule alone. Declare the entry
without `severity` and mark the warn rules with `!` instead — the same schema,
written the other way round, and the reading is the same: the `!` rules advise,
the rest fail.

This is a spelling change only. A `!` or an entry-level `warn` loads to the
same `Rule.withWarnOnly(true)` the flag already produced, so §6's finding
severities, §4's ordering and the normalized `regl schema` output are
unchanged, and a cross-language schema still describes the same validation in
both engines.

### One schema, three spellings

YAML block style is what a hand author writes; YAML flow style is the compact
spelling; JSON is the machine spelling. The three are one schema: the loader
reads one model and `regl schema` prints one output for each.

Every rule spelling, in the block style a hand author writes:

```yaml
fields:
  name:
    type: STRING
    rules:
      - notNull                    # a bare name: no argument
      - notNull!                   # the same rule, marked warn
      - minLength: 3               # a one-key map: rule + argument
      - minLength!: 3              # the same rule map, marked warn
      - pattern: '^[a-z]+$'        # a one-key map, quoted argument
  tags:
    type: LIST
    rules:
      - range: { min: 1, max: 5 }  # a one-key map, object argument
      - range:                      # the same rule map, written in block form
          min: 1
          max: 5
```

The reading is the same in block style as in flow style: a bare name or exactly
one name-and-argument pair, with `!` on the name marking the rule warn. The
withdrawn `{code, value, severity}` envelope is now a load error — two entries
in a rule map.

One schema — a reusable type, soft shape rules marked `!`, and hard presence
rules — in all three spellings:

```yaml
strict: true
types:
  Email:
    type: STRING
    rules:
      - email!                     # soft: a warning
      - maxLength!: 254            # soft: a warning
fields:
  server.port:
    type: INTEGER
    rules:
      - notNull
      - lessThan: 65536
  contact.email: Email             # inherits Email's soft shape rules
  billing.address:
    type: Email
    rules:
      - trimmed                    # hard: an error, the default
      - notNull                    # hard: an error, the default
  debug: BOOLEAN
```

```yaml
strict: true
types:
  Email: { type: STRING, rules: [email!, { maxLength!: 254 }] }
fields:
  server.port: { type: INTEGER, rules: [notNull, { lessThan: 65536 }] }
  contact.email: Email
  billing.address: { type: Email, rules: [trimmed, notNull] }
  debug: BOOLEAN
```

```json
{
  "strict": true,
  "types": {
    "Email": { "type": "STRING", "rules": [ "email!", { "maxLength!": 254 } ] }
  },
  "fields": {
    "server.port": { "type": "INTEGER",
                     "rules": [ "notNull", { "lessThan": 65536 } ] },
    "contact.email": "Email",
    "billing.address": { "type": "Email", "rules": [ "trimmed", "notNull" ] },
    "debug": "BOOLEAN"
  }
}
```

All three print the same normalized schema, byte for byte:

```
server.port  INTEGER  notNull lessThan
contact.email  STRING  email maxLength
billing.address  STRING  email maxLength trimmed notNull
debug  BOOLEAN
strict on, maxDepth 32, 4 fields
```

The worked example is the severity story in one type: `Email` marks its shape
rules `!`, so a malformed address advises, while `billing.address` adds hard
presence rules — `trimmed` and `notNull` keep the default and fail the
document. `contact.email`, which adds nothing, inherits the soft shape rules
alone.

### Shorthand equivalences

These are the same schema, not different ones:

| Shorthand | Long form |
| :--- | :--- |
| `debug: BOOLEAN` | `debug: { type: BOOLEAN }` |
| `contact.email: WorkEmail` | `contact.email: { type: WorkEmail }` |
| `Email: STRING` | `Email: { type: STRING }` |
| `notNull` | `{ notNull: null }` |
| `email!` | `{ "email!": null }` |
| `{ maxLength!: 20 }` | `{ maxLength: 20 }` on a `severity: warn` entry |
| `severity: warn` omitted | `severity: error` |

A bare `typeRef` is unambiguous because the two namespaces are disjoint and
case-sensitive (§17): a string there is always a type name, never a rule.

### What deliberately does not change

- **`rules` stays an ordered array, and is not inlined into the field object.**
  JSON Schema writes its keywords flat in one object
  (`{type: NUMBER, notNull: true, lessThan: 65536}`), which is the largest
  remaining saving in characters and is not proposed here. Two things would be
  given up: rule order, which §4 makes observable in finding order and which a
  JSON object does not guarantee; and the ability to distinguish a misspelled
  field key from a rule name, since with flat keywords every unknown key is
  indistinguishable from a rule.
- **Unknown keys and unknown rules are still load errors.** `strict`, `maxDepth`,
  `types` and `fields` remain a closed set at the top level, `type`, `rules`, and
  `severity` remain a closed set inside a field or type entry, and every rule
  name is resolved against the catalog, so `"feilds"`, a misspelled `ruels`, and
  `lessThn` each fail loudly.
- **Order of resolution, merge order, and the load-error table are untouched.**
  A `types` entry still resolves its base on demand, still merges base-first, and
  still fails on a cycle, a shadowing name, a duplicate name or an unresolvable
  base. `WorkEmail` may still be written before `Email`.

### Repeated keys are load errors

Keying `fields` by path moves duplicate-path detection into the decoder: two
entries with the same path are a repeated object key, and a permissive decoder —
`JSON.parse`, a default Jackson or SnakeYAML — silently keeps the last. The
decoder must reject a repeated key anywhere in a schema as a load error, not
only in `fields`: a repeated `types` name or rule key is the same fault. (The
overlap check in §11 still catches two *different* paths that match one value,
like `servers.*.port` and `servers.0.port`.)

### Keys

| Key | Type | Default | Meaning |
| :--- | :--- | :--- | :--- |
| `fields` | object | required (may be empty) | Keyed by path: one entry per declared path, in report order. A repeated key is a load error. |
| `types` | object | none | Keyed by name: named reusable field types, referenced by name from a field's or type's `type`. |
| `transforms` | array | none | Ordered transformation rules applied prior to validation; each rule targets `SheetName.*.column` and may be scoped by `rowHash` or `when` (see below). |
| `ignore` | object | none | Keyed by collection/sheet name: array of row hashes excluded from transforms, validation, and ingestion (see below). |
| `strict` | boolean | `false` | §10. |
| `maxDepth` | integer | `32` | §11. |

A field or type entry is a bare type name — a target type or a declared type
name — or an object carrying `type`, an optional `rules`, and an optional
`severity`. A rule entry is a bare rule name or a one-key map of rule name to
argument, either carrying `!` on the name to mark the rule warn. The argument is
the catalog's: a number for `minLength`-style rules, `{ "min", "max" }` for
`range`, an array of strings for `enum`.

`types`, `transforms`, `ignore`, `strict`, `maxDepth`, `rules`, and `severity`
are the only optional keys, and an unknown key anywhere in a schema file is a **load error**. A schema file is authored by a human; a typo like
`"feilds"` must fail loudly, not silently produce a schema that validates
everything.

### Reusable field types

`types` is §4's field type in file form: a name mapped to a target type and a
chain of rules, which any field may then name in its `type` position.

```yaml
strict: true
types:
  Email: { type: STRING, rules: [notNull, email, { maxLength: 254 }] }
  WorkEmail: { type: Email, rules: [{ endsWith: '@example.com' }] }
fields:
  contact.email: WorkEmail         # notNull, email, maxLength, endsWith
  billing.address:
    type: Email
    rules: [trimmed]               # notNull, email, maxLength, trimmed
```

The whole format reads with one sentence: **a type is a type plus rules, and a
field is a path plus a type plus rules.** That is why the container is `types`
rather than `fields` — an entry's own key is `type`, and a `types` entry whose
`type` names another `types` entry is the same recursion one level down.

The rules are merged base-first exactly as in the code form (§4): a `types`
entry's chain, then the referencing field's. A `types` entry takes `type`,
`rules`, and `severity`, and nothing else — no `strict`, no `maxDepth` —
because a field type is a type, not a field.

Order in the file is irrelevant. `WorkEmail` above may be written before `Email`;
the loader resolves each definition's base on demand and fails only on an
unresolvable one. These are **load errors** (the CLI reports each as exit 2,
§14):

| Fault | Named in the message as |
| :--- | :--- |
| A base naming no target type and no declared type | the unknown type name |
| A field naming no target type and no declared type | the field's path and the unknown type name |
| A cycle among `types` entries | the cycle, in traversal order |
| A `types` key equal to a target type name | the shadowing name |
| The same name declared twice | the duplicated name |

Message wording is the loader's, as with every other finding (§9); the
conformance obligation is that each fault is *detected*, not that it reads a
particular way.

The shadowing row is the point of the list. A definition named after a catalog
type is ambiguous, and the two engines and the loader do not resolve that
ambiguity the same way — a file loader that consults the catalog first ignores
the definition, while a code form that consults its own registry first uses it.
Two spellings of one name meaning opposite things is precisely the failure the
unknown-key rule above exists to prevent, so the ambiguity is removed instead of
resolved: the namespaces are disjoint, and a `types` key that collides is a load
error. Both target type names and field type names are case-sensitive, for the
same reason — one exact spelling each, so no lookup can be a guess.

### Loading validates

A schema file is checked when it is loaded, before any document is read, with
the same errors the code form raises at construction: an unknown rule code, a
`type` that is not a target type or a declared type, an argument of the wrong
shape, overlapping paths, a path deeper than `maxDepth` (§11) — and the two the
terse shape adds: an unknown key, and a repeated key (above). The exception is
the same `IllegalArgumentException` — a schema file is just another way of
building a schema, so a bad one is the same class of fault as a badly built one.

A schema file cannot express anything the code form cannot. If a format feature
would need new semantics, that is a spec change, not a schema-file feature.

### Formats

A schema file is JSON (`.json`) or YAML (`.yaml`, `.yml`), detected exactly like
a document (`.13`). The two formats parse to one schema model — there are no
format-specific semantics, and a schema round-trips between them losslessly.

### Tabular sidecars: transforms and ignore

A schema file that accompanies a tabular dataset — one raw data file paired
with a `<fileName>.regl.yaml` companion — may additionally carry `transforms`
and `ignore`. The two sections are the hygiene half of the sidecar: `fields`
says what a correct value *is*, `transforms` says how a raw value *becomes*
correct, and `ignore` says which rows are not part of the dataset at all.

A dataset is a map of sheet name to a list of row objects, so `SheetName.*.col`
resolves through the §3 list wildcard exactly as `servers.*.host` does.

**`transforms`** is an array of rules, applied in declaration order. Each rule
is:

| Key | Type | Meaning |
| :--- | :--- | :--- |
| `path` | string | Required. `SheetName.*.column` — the cell the rule rewrites. |
| `rowHash` | string | Optional. Only rows whose deterministic raw-row hash equals this value match. The hash is computed over the row's raw values, so it survives line-order changes on re-import. |
| `when` | object | Optional. `{ equals: <value> }` — only cells whose current value equals this match. |
| `apply` | array | Required. Non-empty list of transformation steps from `transformations-catalog.json`, applied left to right. |

A transformation step is written tersely: a bare catalog id (`trim`,
`upperCase`) or a one-key map of id to parameter
(`{ constant: COBADEFFXXX }`, `{ replace: { pattern: INV-, replacement: SEPA-INV- } }`).

**`ignore`** is an object keyed by sheet name, each value an array of row
hashes. Ignored rows are excluded from the transform pass and from validation
findings; the host application flags them (for display) but never ingests
them.

**Pipeline order is fixed.** Whenever a sidecar is evaluated against a
dataset, the three passes run in this order, and later passes never see what
an earlier pass excluded or rewrote:

1. **Ignore** — rows listed in `ignore.<SheetName>` are excluded.
2. **Transforms** — remaining rows have their raw values rewritten by each
   matching rule, in order.
3. **Validation** — `fields` rules run against post-transform values.

A complete example:

```yaml
strict: false
fields:
  SepaTransactions.*.iban:
    type: STRING
    rules: [notNull, { pattern: '^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$' }]
  SepaTransactions.*.currency:
    type: STRING
    rules: [notNull, { enum: [EUR, USD, GBP, CHF] }]
  SepaTransactions.*.reference:
    type: STRING
    rules: ['notNull!']          # warning severity
transforms:
  - path: SepaTransactions.*.currency
    apply: [trim, upperCase]
  - path: SepaTransactions.*.bic
    when: { equals: COBADEFF }
    apply: [{ constant: COBADEFFXXX }]
  - path: SepaTransactions.*.reference
    rowHash: h_9776364cafef6cac
    apply: [{ replace: { pattern: 'INV-', replacement: 'SEPA-INV-' } }]
ignore:
  SepaTransactions:
    - h_decaffd601fdd26a
```

`transforms` and `ignore` are load-checked like the rest of the file: an
unknown catalog id in `apply`, a `path` not of the form `SheetName.*.column`,
an `apply` that is empty, or an `ignore` value that is not an array of strings
are each load errors. The two engines and any host pipeline must agree on the
row-hash function; it is defined as the canonical concatenation of the row's
non-metadata keys in sorted order (`key:trimmedValue` joined by `|`), hashed
with the dual 32-bit accumulator published alongside the reference
implementations, rendered `h_<16 hex>`.

---

## 13. Document decoding

The CLI decodes the document itself. The three formats decode to exactly the
document model of §2 — a tree of objects, lists, strings, numbers, booleans, and
`null` — and nothing else.

| Format | Where decoded | Notes |
| :--- | :--- | :--- |
| JSON | core (zero-dep) | Standard JSON. Numbers decode as IEEE-754 doubles; the root may be any kind. |
| `.properties` | core (zero-dep) | Flat strings; dotted keys expand (below). |
| YAML | CLI layer (real parser) | Full YAML, not a subset — block and flow styles, anchors and aliases. |

### Format detection

1. `--format json|yaml|properties` wins.
2. Otherwise the file extension: `.json` → JSON, `.yaml`/`.yml` → YAML,
   `.properties` → properties.
3. For an `http(s)` URL without a usable extension, the `Content-Type` header:
   `application/json`, `application/yaml` or `text/yaml`,
   `text/x-java-properties`.
4. Otherwise: a load error, with the `--format` hint in the message.

### `.properties` keys are paths

`.properties` files are flat strings, so a key is read as a §3 path and
**unescaped dots expand into nested objects**: `server.port=8080` decodes to
`{ "server": { "port": "8080" } }`, which is exactly what a schema's
`server.port` field addresses. The two grammars are one grammar.

To keep a dot inside one key, escape it as in any path — and remember that
properties unescaping applies first, so the backslash itself is doubled in the
file:

```properties
server.port=8080          # { "server": { "port": "8080" } }
example\\.com=up          # { "example.com": "up" } — one key, one literal dot
```

Properties semantics otherwise: `#` and `!` comments, backslash line
continuations, `\uXXXX` escapes, duplicate keys last-wins, whitespace around the
separator skipped, and **trailing whitespace in a value preserved** — a
properties quirk both engines must share. A key that is
not a valid path (empty, or a dangling escape), and a key that makes one node
both a leaf and an object (`a=1` then `a.b=2`), are **decode errors**.

Every `.properties` value is a string — so, per §8, only `STRING` rules can
pass on a properties document, and any `NUMBER` or `BOOLEAN` field reports a
`TYPE_MISMATCH`. That is the no-coercion rule, not a decoder limitation.

### Decode failure is not a finding

A document that cannot be decoded — malformed JSON, a YAML error, an invalid
properties key — is a **load error** with exit code 2 (§14). It is distinct from
a document that decodes and then violates the schema, which is a finding with
exit code 1. A decoder must never guess: no partial parses, no best-effort
recovery.

---

## 14. The CLI

```
regl <schema> <document>            validate a document against a schema file
regl validate <schema> <document>   the same, explicit
regl schema <schema>                load and lint a schema, print its fields
```

`<schema>` and `<document>` are each a **filesystem path or an `http(s)` URL**.
Remote documents are fetched once, follow redirects, and a non-2xx status is a
load error.

| Option | Meaning |
| :--- | :--- |
| `--format json\|yaml\|properties` | Document format override (§13). |
| `--json` | Machine-readable output, shaped as below. |
| `--quiet` | No summary line; findings still print. Exit codes carry the verdict. |
| `--no-color` | Plain output; color is used only on a terminal anyway. |
| `-h`, `--help` / `-V`, `--version` | Standard. |

### Exit codes

| Code | Meaning |
| :--- | :--- |
| 0 | The document is valid. Warnings may be present. |
| 1 | The document is invalid: at least one error-level finding. |
| 2 | Nothing was validated: a usage error, an unreadable schema or document, an undecodable document (§13), or an invalid schema (§12). |

The distinction is for pipelines: 1 means "the document is wrong", 2 means
"regl could not do the job" — a CI step that regl itself failed to run should
not look like a red test.

### Output

Human output lists one finding per line, errors before warnings, in library
order (§9), then a summary:

```
ERROR server.port notNull: Value is required and must not be null.
ERROR servers.0.host email: Value is not a valid email address.
2 errors
```

A structural finding names no rule, so its line is `ERROR server.port: Value at
'server' is not an object, ...`. A valid document prints `ok` (plus any
warnings).

`--json` output is the `DocumentResult` shape itself — the cross-engine
contract, so the TypeScript CLI must emit the same bytes for the same inputs:

```json
{
  "valid": false,
  "errors": [
    { "severity": "ERROR", "kind": "RULE_FAILED", "path": "server.port",
      "rule": "notNull", "value": null,
      "message": "Value is required and must not be null." }
  ],
  "warnings": []
}
```

A rule `value` is written in its canonical shape: a `range` value as
`{ "min", "max" }` with an unbounded side omitted, and an integral number
without a trailing `.0` — `65536`, not `65536.0`, which is what the TypeScript
CLI writes. A load error (exit 2) prints its one-line reason to **stderr** as
plain text regardless of `--json`: nothing was validated, so there is no
result to encode.

`regl schema` loads and lints a schema file — the same errors as loading it for
validation, exit 2 on any — and prints the declared fields, one per line, with
`strict` and `maxDepth`; `--json` prints the normalized schema in the §12 shape
exactly as regl understood it, which is the fastest way to see what a YAML
schema really says. Normalized rules are the formal `{code, value?, severity?}`
of §16, so the output reads `severity: "warn"` where the file said `!` and never
spells severity any other way — `warnOnly` is code-level only.

---

## 15. Worked example

A JSON document, so types survive decoding:

```json
{
  "server":  { "port": 8080,      "host": "example.com" },
  "servers": [ { "host": "bad_host", "port": 999999 } ],
  "name":    "My Service",
  "debug":   true,
  "retries": null
}
```

Schema, in declaration order:

```java
Schema.create()
    .field("name",          string().notNull().lowercase())
    .field("server.port",   number().notNull().integer()
                                        .greaterThan(0).lessThan(65536))
    .field("server.host",   string().notNull().email())
    .field("servers.*.host", string().email())
    .field("servers.*.port", number().integer().lessThan(65536))
    .field("debug",         boolean_().isFalse())
    .field("retries",       number().notNull().greaterThan(0));
```

Expected findings, sorted by declaration index:

| # | kind | path | rule | message |
| :-- | :--- | :--- | :--- | :--- |
| 1 | `RULE_FAILED` | `name` | `lowercase` | Value must be all lowercase. |
| 2 | `RULE_FAILED` | `server.host` | `email` | Value is not a valid email address. |
| 3 | `RULE_FAILED` | `servers.0.host` | `email` | Value is not a valid email address. |
| 4 | `RULE_FAILED` | `servers.0.port` | `lessThan` | Value must be strictly less than 65536.0. |
| 5 | `RULE_FAILED` | `debug` | `isFalse` | Field must be false. |
| 6 | `RULE_FAILED` | `retries` | `notNull` | Value is required and must not be null. |

The `message` column is what the Java engine renders for convenience; the same
finding in TypeScript words it the TypeScript way. That is why `message` is
excluded from the conformance transcript (§14): it is for humans, and the
branchable fields above are the contract.

Reading the six:

1. `"My Service"` is present and a `STRING`, so `lowercase` runs and fails. An
   ordinary `RULE_FAILED`, **not** a `TYPE_MISMATCH` — the kinds distinguish
   "wrong shape" from "wrong value", and only the former is a structural bug.
2. `"example.com"` has no `@`, so the built-in email pattern fails.
3. Same under the wildcard. The concrete path is `servers.0.host`: the wildcard
   is replaced by the index it matched.
4. `999999` is whole, so it satisfies the `INTEGER` field type, and `lessThan`
   fails. Note that `lessThan` is a `NUMBER` rule on an `INTEGER` field — the
   widening that lets a port be bounded — so the chain's type head admitted it.
   Had the head been `string()`, this schema would have failed to build.
5. `true` is a `BOOLEAN`, so `isFalse` runs and fails.
6. `"retries": null` — `notNull()` is evaluated *before* the null short-circuit,
   so it rejects the null and `greaterThan` never runs.

And the near-misses, which matter as much as the hits:

- **`server.port` passes.** `8080` is a whole number in range.
- **`servers.1` is silent.** It does not exist, so the wildcard yields no element
  and its chain never runs. Absence is not an error for an optional field.
- **A second `servers` element is not required.** Neither `servers.*` field calls
  `notNull()`, and per §6 that is what makes them optional.
- **`servers.*.port` is optional per element.** A hole in the list — an element
  with no `port` — resolves to `null` and passes.
- **No `UNKNOWN_FIELD`.** The schema is open by default, so undeclared keys are
  ignored. Adding `.strict()` would report any.

### The same document, as `.properties`

Since `.properties` values are all strings, the numeric and boolean fields
cannot be validated at all under §8:

```properties
server.port=8080
server.host=example.com
name=My Service
debug=true
```

With the identical schema this yields `TYPE_MISMATCH` on `server.port` (a string
where a number is required) and on `debug`. The `string()` chains are unaffected.

That is §8's cost, shown concretely rather than asserted.

---

## 16. Conformance

Document fixtures live in a **new file**, `conformance/document-cases.json`, so
that scalar fixtures keep validating scalar behaviour:

```json
{
  "cases": [
    {
      "name": "nested path",
      "fields": [
        { "path": "server.port", "type": "INTEGER",
          "rules": [ { "code": "notNull" },
                     { "code": "greaterThan", "value": 1024 } ] }
      ],
      "documents": [
        { "name": "valid", "doc": { "server": { "port": 8080 } } },
        { "name": "missing", "doc": {} },
        { "name": "scalar ancestor", "doc": { "server": "nope" } }
      ]
    }
  ],
  "strictCases":   [ /* same shape, validated with .strict() */ ],
  "constructionErrors": [ /* schemas that must be rejected at build time */ ],
  "acceptedPathPairs":  [ /* disjoint path pairs that must be accepted */ ],
  "formalErrors":       [ /* Field.of combinations that must throw */ ],
  "formalAccepted":     [ /* Field.of combinations that must build */ ],
  "maxDepth":           { "limit": 2, "rejected": "a.b.c", "accepted": "a.b" }
}
```

A case declares its fields once and runs several documents through them, so each
schema is described one time rather than repeated per document. A fixture's rule
is `{ "code", "value"?, "severity"? }` — the `value` types are the catalog's, with
`range` as `{ "min", "max" }`, and `severity` absent meaning the rule is an error.
This is the **formal** shape of §4, kept so a fixture maps straight onto
`Field.of(...)`; a schema *file* uses §12's terse spelling for the same rules, and
`regl schema --json` normalizes back to this one. The severity reading is the same
in all three: absent is an error, `warn` advises.

`type` is the chain head — the assertion that both checks the field's type and
narrows the rules that follow. There is no `required` or `coerce` key, because
there is no such concept (§6, §8).

A fixture's schema is by definition the **formal** form: it declares `type`
explicitly, so it maps onto `Field.of(...)`, which validates target types and
throws `IllegalArgumentException` on a mismatch. That gives a bad fixture the
same treatment as a bad hand-written schema, rather than a silent pass.

### Expected results

The expected outcome of every case lives in a second file,
`conformance/document-expected.json`: one row per (schema, document) pair, keyed
`case/<case>/<document>`, whose `findings` value is a normalized transcript:

```
valid=false
ERROR RULE_FAILED server.port notNull
```

The transcript carries `severity`, `kind`, `path`, and `rule` — and deliberately
not `message`, which may be reworded per locale without a fixture edit, and not
`value`, which the rule already carries. Because **both** engines assert against
this one file, a behavioural difference between them cannot be absorbed by
updating a snapshot on one side: it fails on one engine or the other.

Each engine also has a generator for the file — `npm run golden` in `regl-ts`,
`GenerateDocumentGolden` in `regl-java` — and regenerating with either must
produce a byte-identical file. The generators are for reviewing a deliberate
behaviour change; an accidental one shows up as a failing assertion first.

### Coverage

- [x] each `kind` above has at least one fixture
- [x] missing field with and without `notNull()`
- [x] explicit `null` with and without `notNull()`, proving the short-circuit
- [x] present value of the wrong type for each `type` head
      (`ANY` is except by definition: it accepts every kind)
- [x] `Field.of(...)` rejecting a mismatched rule in both engines
- [x] every builder method present on its own builder and absent from the
      others, in both engines (`BuilderCoverageTest`, the TS document suite)
- [x] wildcard over a list, over an empty list, and over a non-list
- [x] literal key through a scalar, through a null, and through a missing parent
- [x] overlapping paths rejected: `server.*` + `server.0`, a duplicate path, an
      empty path, a dangling escape
- [x] disjoint paths accepted: different lengths, wildcard + non-index literal,
      siblings, an escaped key beside its two-segment spelling
- [x] `maxDepth` rejecting an over-deep path and accepting one at the limit
- [x] ordering: a document whose findings would differ if unordered, including a
      list with more than ten elements, so element indices are proven to compare
      as numbers
- [x] `severity: warn` producing a warning that leaves `valid` true
- [x] `strict()` covering, prefix, wildcard-subtree, and an escaped key
- [x] every catalog rule reachable from a document chain, in both engines.
      Verdict coverage stays in the scalar fixtures: a chain that exercised all
      seventeen string rules against one value is not constructible (`email`,
      `url`, and `uuid` cannot all pass one string), so duplicating the scalar
      verdicts here would add machinery, not coverage.

### Decode fixtures

`conformance/decode-cases.json` pins the decoders of §13: each case is a
`format`, a `text`, and either the `expected` decoded tree (as JSON, which the
document model can express exactly) or `"error": true` for a text that must fail
to decode. It covers, at minimum:

- [x] JSON: object, list, scalar root, nested trees, string escapes, `null`
- [x] JSON: malformed text rejected
- [x] `.properties`: a flat key, dotted keys expanding, an escaped dot staying
      one key, comments, line continuations, `\uXXXX` escapes, duplicate keys
      last-wins
- [x] `.properties`: an invalid path key and a leaf-vs-object conflict rejected
- [x] YAML: block and flow styles, anchors and aliases, typed scalars
- [x] YAML: malformed text, several documents, and an empty stream rejected
- [x] every decoded tree matches `Schema`'s expectations — a decode fixture's
      `expected` is usable directly as a `doc` in `document-cases.json`

The decoders' shared fixtures are what keep a YAML value from meaning one thing
in Java and another in TypeScript when the TS CLI arrives.

### Schema-file fixtures

A schema file is data, so it is tested as data: the CLI suite loads JSON and
YAML spellings of one schema and asserts they validate identically, plus
rejected schemas (unknown key, unknown rule code, bad target type, bad
argument shape, overlapping paths) exiting 2. These live in the CLI's own
tests, not a shared fixture file, until the TS CLI exists to share them.

A `types` block is covered the same way and in the same suite: one schema whose
`types` entries are referenced by fields and whose rules merge base-first; the
same schema spelled twice (forward base, reversed base) asserted to load
identically; and one rejected schema per fault in §12's table — unknown base,
unknown field reference, cycle, shadowing, duplicate.

---

## 17. Settled decisions

Recorded here so the reasoning survives, not just the outcome.

| Question | Decision | Where |
| :--- | :--- | :--- |
| Missing field vs `null` | Identical. A missing field resolves to `null`. | §5, §6 |
| Is `required()` needed | No. `notNull()` is a catalog rule with `targetType: ANY`. | §6 |
| Is `nullable()` needed | No. It would be schema metadata no catalog entry describes, no `i18n` covers, and no fixture can test. | §6 |
| Coercion | None, implicit or explicit. A wrong-typed value is reported. | §8 |
| Document result type | `DocumentFinding`, separate from `ValidationError`. `ValidationError` stays frozen. | §7, §9 |
| Chain narrowing | The head returns a builder exposing only that type's rules, so an incoherent chain does not compile. | §4 |
| Temporal strings | `dateFormat`, `isoDate`, `isoDateTime`, `isoTime`, `after`, and `before` all target `STRING`, and so live on `StringField` only. A document holds temporal *values* as strings, so `STRING` is where they are observable; `DATE`, `DATETIME`, and `TIME` describe the kind of temporal a schema author means, and carry no rules of their own. | §4 |
| Epoch rules target `DATETIME` | A Unix timestamp in seconds or milliseconds is an instant, not a calendar day and not a time of day. Keeping them on `NumberField` would put `epoch` next to `positiveInteger` and `positive`, implying a bare number is a timestamp. | §4 |
| Formal field construction | `Field.of(...)` validates target types and throws `IllegalArgumentException`. | §4 |
| Terse rule lists | `with(...)` takes rule factories as values, so a rule can be named rather than called. The head still fixes the type at compile time and each factory still type-checks its argument; only the compile-time rejection of a mis-targeted rule is traded for length, and `with(...)` applies the same check as `Field.of(...)` through one shared routine, so the spellings cannot disagree. | §4 |
| Shared rules | The two `ANY`-target rules — `notNull`, `enum` — are declared once, on the shared builder base, so every builder carries them and no builder repeats them. | §4 |
| Two fields matching one path | Not allowed. Overlap is a construction-time `IllegalArgumentException`. | §11 |
| Depth limit | A schema property, `.maxDepth(n)`, default 32. Checked once at construction. | §11 |
| Strict wildcard subtrees | A wildcard covers its elements' subtrees at the declared depth only: `servers.*.host` covers `servers.0.host` and not `servers.0.port`. | §10 |
| Schema serialization | A file: the terse form of §4 serialized, JSON or YAML, validated at load with the same `IllegalArgumentException` as the code form. | §12 |
| Terse schema files | A rule is its own name or a one-key map of name to argument, and `fields` is keyed by path — the keyword-with-value and `properties` spellings JSON Schema and OpenAPI use. The `{code, value, severity}` envelope is withdrawn entirely: a `!` on the rule's name carries the one thing it existed for. | §12 |
| Schema-file severity | A `!` on a rule's name at the rule level, `severity: warn` on the field/type entry at the entry level, `error` the default. A rule warns if marked or its entry says warn, and the field's severity overrides the named type's; a plain rule cannot un-warn itself, so an error among warnings is declared by marking the warn rules instead. Both load to the same `Rule.withWarnOnly(true)`, so findings, ordering, and the normalized `regl schema` output are unchanged. | §12 |
| Repeated schema keys | A repeated object key anywhere in a schema is a load error: `fields` is keyed by path, so a repeated key is a duplicate path a permissive decoder would silently keep-last. | §11, §12 |
| Reusable field types | A named target type plus rules, attached to a path by name. A field type is a chain *base*: the referencing field's rules append after it, so finding order is base-first. | §4, §12 |
| File key is `types` | A type is a type plus rules; a field is a path plus a type plus rules. The entry's own key is `type`, so `types.X.type` nesting another entry is the same recursion one level down. `customFields` is withdrawn — it had no documented or tested user. | §12 |
| Shadowing a target type | Target types and field types are disjoint namespaces, so there is no precedence rule and no silent no-op. A definition named after a catalog type is a load error. | §4, §12 |
| Name case | Both namespaces are case-sensitive, in both engines and in schema files. `STRING` is the target type; `String` is a different name. | §4, §12 |
| Resolution order | A type must be complete before use, always. Whether a *later* base is legal is a property of the form: ordered in code, irrelevant in a file, and a named error either way. | §4 |
| `metadata` | Withdrawn. It is schema metadata no catalog entry describes, no `i18n` covers, and no fixture can test — the same ground on which `nullable()` was rejected. | §6, §12 |
| YAML | A real parser, in the CLI layer; the validation core stays zero-dependency and decodes only JSON and `.properties`. | §1, §13 |
| `.properties` keys | Dotted keys expand into nested objects using the §3 grammar; `\.` keeps a literal dot, with the properties backslash escaping applied first. | §13 |
| CLI exit codes | 0 valid, 1 invalid document, 2 nothing validated (usage, load, decode, schema errors). | §14 |

## 18. Open questions

Remaining, and none blocking a first implementation:

1. **Whether `list()` should take an element type.** `list(STRING)` would assert
   a list *of strings*, which is what `tags.*` plus a `string()` chain already
   says. Offering both means two spellings for one constraint; offering only the
   path form keeps the grammar minimal.
2. **Message wording for structural kinds.** The engines currently render their
   own English for `TYPE_MISMATCH`, `UNREACHABLE`, `NOT_A_LIST`, and
   `UNKNOWN_FIELD`. Whether those reuse the `i18n` `findings` templates (§9) or
   stay hardcoded like the scalar messages. The transcript excludes messages, so
   the choice is not observable in conformance fixtures.
3. **Target type casing in schema files.** The examples keep `NUMBER`,
   matching the Java enums and the existing fixtures, so the shape change was
   reviewed alone. Every data format spells these lowercase — JSON Schema's are
   `string`, `number`, `integer`, `boolean`, `array` — and lowering them would
   align regl with that vocabulary. §17 settled that the namespace is
   case-sensitive and disjoint, which lowercase does not affect, so this is
   available; it should be decided separately from the shape.
4. **Whether `targetType` should admit a set.** `after` and `before` compare
   temporally in any of the three string encodings, but a catalog entry declares a
   single `targetType`, so they can name only one and are attached to `STRING`.
   That is why `DATE`, `DATETIME`, and `TIME` have no rules: reaching `after` from
   a `date()` head would need either a multi-valued `targetType` or a per-kind
   duplicate of the same rule. Both are worse than one honest `STRING` target
   today, but a set is the natural fix if the temporal kinds ever diverge.