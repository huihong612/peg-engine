# PEG Pattern Recognition & Structured Extraction Engine

Java 17+ implementation of the v1.0 design specification.

## API

```java
Pattern pattern = Pattern.compile(template);
Matcher matcher = pattern.matcher(source);

if (matcher.matches()) {
    Object result = matcher.slot("result");
}
```

Explicit entry rule:

```java
Pattern.compile(template, "value");
```

## Implemented

- Literal, character class, sequence, ordered choice
- Optional `?`, repetition `*`, `+`
- Positive/negative lookahead `&` / `!`
- Rule references and recursive rules
- Capture `(<%=name%> expression)`
- Dynamic scopes with `var`
- `dict()` / `list()`
- Map get/set and List add/delete/insert/set
- `return` and rule value propagation
- Matcher-level `slot[...]`
- Undo log + checkpoints for PEG backtracking
- Farthest parse-error position with line/column/expected/actual
- `parseInt()`

## Example

```text
value = object / array / string / number

object =
    <% var obj = dict() %>
    "{"
    (pair ("," pair)*)?
    "}"
    <% return obj %>

pair =
    (<%=key%>string)
    ":"
    (<%=value%>value)
    <% obj[key] = value %>

array =
    <% var arr = list() %>
    "["
    (
        (<%=value%>value) <% arr.add(value) %>
        (
            ","
            (<%=value%>value) <% arr.add(value) %>
        )*
    )?
    "]"
    <% return arr %>

string = "\"" [^\"]* "\""

number =
    (<%=num%>[0-9]+)
    <% return parseInt(num) %>
```

For a JSON-oriented grammar where string values should be unquoted, add a semantic rule that captures the interior and returns it; the core engine intentionally does not add a full scripting language.

## Build

With Maven installed:

```bash
mvn test
```

The environment used for this delivery did not contain Maven, so the implementation was also verified with JDK 21 via direct `javac` compilation and smoke tests.
