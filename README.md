# Custom DSL

This project implements a Kotlin DSL for validating HTTP responses. It checks the status, headers, and response body. Instead of a single `Boolean`, we get a list of mismatches showing what was expected and what was actually received.

## Project Files

- `app/src/main/kotlin/lesson09/dsl/HttpValidationDsl.kt` - DSL classes, rule storage, and execution.
- `app/src/main/kotlin/lesson09/dsl/DslChecks.kt` - new `check01Dsl`–`check06Dsl` functions.
- `app/src/main/kotlin/lesson09/HttpResponse.kt` - response model.
- `app/src/main/kotlin/lesson09/legacy/Checks.kt` - original checks used to compare with the DSL.
- `app/src/main/kotlin/lesson09/support/json/Json.kt` - provided JSON parser.
- `app/src/test/kotlin/lesson09/` - our tests and the starter project's tests.

## Running the Project

**JDK 25** is required. Set it in `JAVA_HOME` and select the same JDK in the **Gradle JVM** setting in IntelliJ IDEA. You do not need to install Gradle separately - we use the Gradle Wrapper. The first run requires an internet connection to download dependencies.

The project uses Kotlin 2.4.10, Gradle 9.5.1, and JUnit 6.1.3. Run the commands from the repository root:

| Action | Bash | PowerShell |
| --- | --- | --- |
| Build and tests | `bash ./gradlew build` | `.\gradlew.bat build` |
| Tests | `bash ./gradlew :app:test` | `.\gradlew.bat :app:test` |

## Usage Example

```kotlin
import lesson09.HttpResponse
import lesson09.dsl.validate

fun main() {
    val response = HttpResponse(
        status = 500,
        headers = mapOf("Content-Type" to listOf("text/plain")),
        body = "{}",
    )

    val result = validate(response) {
        status { equalTo(200) }
        header("Content-Type") { startsWith("application/json") }
        body {
            json {
                field("id") { present() }
            }
        }
    }

    println(result.isValid)
    result.mismatches.forEach(::println)
}
```

Output:

```text
false
status: expected 200, actual 500
headers.Content-Type: expected prefix application/json, actual ["text/plain"]
body.json.id: expected present, actual <missing>
```

Here, three independent checks fail.

The DSL collects **all** errors in the order the rules are executed instead of stopping after the first one.

## How the DSL Works

### Validation Result

`ValidationResult` contains a `List<Mismatch>`. Each `Mismatch` stores three strings:

- `path` - the path to the error, such as `status`, `headers.Content-Type`, `body`, `body.json`, or `body.json.id`;
- `expected` - the expected value;
- `actual` - the actual value or the reason why it is unavailable.

`isValid` is `true` when the error list is empty. `Mismatch.toString()` is used for convenient output.

Repeated failed checks create separate entries.

A value mismatch does not throw an exception or stop other checks.

### DSL Levels

Each block has its own receiver class and a set of operations:

| Class | Operations |
| --- | --- |
| `ResponseDsl` | `status`, `header`, `body`, `include` |
| `StatusDsl` | `equalTo(Int)` |
| `HeaderDsl` | `equalTo(String)`, `startsWith(String)`, `satisfies(description, predicate)` |
| `BodyDsl` | `absentOrEmpty()`, `nonEmpty()`, `json` |
| `JsonDsl` | `field` |
| `JsonFieldDsl` | `present()`, `equalTo(String)`, `equalTo(Int)`, `equalTo(Boolean)` |

The blocks use **lambdas with receiver**:

`ResponseDsl.() -> Unit`, `StatusDsl.() -> Unit`, `HeaderDsl.() -> Unit`, `BodyDsl.() -> Unit`, `JsonDsl.() -> Unit`, and `JsonFieldDsl.() -> Unit`.

All receivers are marked with `@HttpValidationDsl` - an annotation with `@DslMarker`.

It prevents accidentally calling a method of an outer DSL block from a nested block.

If necessary, the outer receiver can be accessed explicitly: `this@validate.status { equalTo(200) }`.

### Comparison Rules

**Headers.** Header names are compared case-insensitively, while values are compared case-sensitively.

If keys differ only in case, their values are combined in the order of `response.headers.entries`.

The order within the lists is preserved.

A check passes when at least one header value matches.

Different values can satisfy two different checks. `startsWith` uses the standard `String.startsWith`: for example, `application/jsonp` starts with `application/json`.

A missing header is displayed as `<missing>`, an empty list as `[]`, and a list containing one empty string as `[""]`.

Header values in the list are enclosed in quotation marks without additional escaping.

**Body.** `absentOrEmpty()` accepts `null` and `""`. `nonEmpty()` accepts any non-empty string, including `" "`.

**JSON.** The `parseJsonObject` parser is called only when `json { ... }` is executed.

If the body is missing or the JSON is invalid, this block adds one `body.json` error and skips its field checks.

Field presence is checked using `containsKey`. This is important because a missing key and a field with the JSON value `null` are different cases. Comparison checks both the type and the value.

The parser returns integers as `Long`, so `equalTo(Int)` converts the number to `Long` during the check.

**Custom Predicate.** `satisfies(description, predicate: (String) -> Boolean)` accepts a description and a function for checking a header value.

The function can be passed as a lambda, a variable of a function type, or a function reference.

The predicate is called only during validation when the header has values.

## Reusable Specifications (Optional Part)

In addition to the regular `validate(response) { ... }`, there is `responseSpec { ... }`.

It creates the rules once so they can later be applied to multiple responses. The `include` method allows an existing specification to be included in another one.

```kotlin
import lesson09.HttpResponse
import lesson09.dsl.responseSpec

fun main() {
    val common = responseSpec {
        status { equalTo(200) }
    }

    val specification = responseSpec {
        include(common)
        header("X-Trace") {
            satisfies("non-blank value") { value -> value.isNotBlank() }
        }
    }

    val firstResponse = HttpResponse(
        status = 200,
        headers = mapOf("X-Trace" to listOf("request-1")),
        body = null,
    )
    val secondResponse = HttpResponse(
        status = 500,
        headers = mapOf("X-Trace" to listOf("")),
        body = null,
    )

    val firstResult = specification.validate(firstResponse)
    val secondResult = specification.validate(secondResponse)

    println(firstResult.isValid)
    println(secondResult.isValid)
}
```

There are two stages:

1. **Configuration.** `responseSpec(block: ResponseDsl.() -> Unit)` executes the block once and stores the rules in `ResponseSpecification`. There is no HTTP response yet, and custom predicates are not called.
2. **Validation.** `specification.validate(response)` executes the stored rules. A separate error list is created for each call, so new validations do not change previous results.

The rules are stored as functions with the following types:

- response: `(HttpResponse, MutableList<Mismatch>) -> Unit`;
- status: `(Int, MutableList<Mismatch>) -> Unit`;
- header: `(List<String>?, MutableList<Mismatch>) -> Unit`;
- body: `(String?, MutableList<Mismatch>) -> Unit`;
- JSON object: `(Map<String, Any?>, MutableList<Mismatch>) -> Unit`;
- JSON field: `(Boolean, Any?, MutableList<Mismatch>) -> Unit`.

When a specification is created, the list of rules is fixed using `toList()`.

`include(common)` adds the shared rules at the position where it is written. During validation, they work with the same response and the same error collector.

The regular `validate(response) { ... }` creates such a specification and immediately applies it once.

## Tests and Submission

- `LegacyEquivalenceTest` compares the six original functions with the new `check01Dsl`–`check06Dsl` functions.
- `DslBehaviorTest` checks edge cases, error structure and order, headers, JSON, and `@DslMarker`.
- `SpecificationTest` checks repeated executions, result independence, and `include`.