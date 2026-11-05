package lesson09.dsl

import lesson09.HttpResponse
import lesson09.support.json.JsonParseResult
import lesson09.support.json.parseJsonObject

/*
* Actually, all the DSL classes could be made internal for safety purposes, but for current task it is overthinking.
*/


@DslMarker
annotation class HttpValidationDsl

/*
* Describes failed check with its path, expected value, and actual value.
*/
data class Mismatch(
    val path: String,
    val expected: String,
    val actual: String,
) {
    override fun toString(): String =
        "$path: expected $expected, actual $actual"
}

/*
* Holds the errors from validation run. An empty list means success.
*/
data class ValidationResult(val mismatches: List<Mismatch>) {
    val isValid: Boolean
        get() = mismatches.isEmpty()
}
/*
* Collects checks for the HTTP status code.
*/
@HttpValidationDsl
class StatusDsl() {
    private val rules = mutableListOf<(Int, MutableList<Mismatch>) -> Unit>()

    fun equalTo(expected: Int) {
        rules.add { actual, errors ->
            if (actual != expected) {
                errors.add(Mismatch("status", expected.toString(), actual.toString()))
            }
        }
    }

    // made internal for safety purposes. This method mustn't be emitted outside of module 'app'
    internal fun build(): List<(Int, MutableList<Mismatch>) -> Unit> = rules.toList()
}

/*
* Collects checks for a header. Each check passes when any value matches.
*/
@HttpValidationDsl
class HeaderDsl(private val name: String) {
    private val rules =
        mutableListOf<(List<String>?, MutableList<Mismatch>) -> Unit>()

    fun equalTo(expected: String) {
        satisfies("value $expected") { it == expected }
    }

    fun startsWith(prefix: String) {
        satisfies("prefix $prefix") { it.startsWith(prefix) }
    }

    // Store the predicate for validation.
    fun satisfies(description: String, predicate: (String) -> Boolean) {
        rules.add { values, errors ->
            if (values?.any(predicate) != true) {
                val actual = values?.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
                    ?: "<missing>"
                errors.add(Mismatch("headers.$name", description, actual))
            }
        }
    }

    // made internal for safety purposes. This method mustn't be emitted outside of module 'app'
    internal fun build(): List<(List<String>?, MutableList<Mismatch>) -> Unit> =
        rules.toList()
}

/*
* Collects checks for the response body, including JSON checks.
*/
@HttpValidationDsl
class BodyDsl() {
    private val rules =
        mutableListOf<(String?, MutableList<Mismatch>) -> Unit>()

    fun absentOrEmpty() {
        rules.add { actual, errors ->
            if (!actual.isNullOrEmpty()) {
                errors.add(Mismatch("body", "null or empty", actual))
            }
        }
    }

    fun nonEmpty() {
        rules.add { actual, errors ->
            if (actual.isNullOrEmpty()) {
                errors.add(
                    Mismatch(
                        "body",
                        "non-empty string",
                        actual ?: "<null>",
                    )
                )
            }
        }
    }

    /*
    * Save the field checks now, but parse JSON only during validation.
    * If parsing fails, report one error and skip the field checks.
    */
    fun json(block: JsonDsl.() -> Unit) {
        val jsonRules = JsonDsl().apply(block).build()

        rules.add { actual, errors ->
            if (actual == null) {
                errors.add(Mismatch("body.json", "valid JSON object", "<missing body>"))
            } else {
                when (val parsed = parseJsonObject(actual)) {
                    is JsonParseResult.Success -> {
                        for (rule in jsonRules) {
                            rule(parsed.fields, errors)
                        }
                    }
                    is JsonParseResult.Failure -> {
                        errors.add(Mismatch("body.json", "valid JSON object", parsed.reason))
                    }
                }
            }
        }
    }

    // made internal for safety purposes. This method mustn't be emitted outside of module 'app'
    internal fun build(): List<(String?, MutableList<Mismatch>) -> Unit> = rules.toList()
}

/*
* Collects checks for fields in a JSON object.
*/
@HttpValidationDsl
class JsonDsl() {
    // named this rules 'jsonRules' to differentiate this json rules from main validation rules
    private val jsonRules =
        mutableListOf<(Map<String, Any?>, MutableList<Mismatch>) -> Unit>()

    // containsKey separates a missing field from a field whose value is null.
    fun field(name: String, block: JsonFieldDsl.() -> Unit) {
        val fieldRules = JsonFieldDsl(name).apply(block).build()

        jsonRules.add { fields, errors ->
            val exists = fields.containsKey(name)
            val value = fields[name]
            for (rule in fieldRules) {
                rule(exists, value, errors)
            }
        }
    }

    // made internal for safety purposes. This method mustn't be emitted outside of module 'app'
    internal fun build(): List<(Map<String, Any?>, MutableList<Mismatch>) -> Unit> =
        jsonRules.toList()
}

/*
* Collects presence and equality checks for one JSON field.
*/
@HttpValidationDsl
class JsonFieldDsl(private val name: String) {
    // named this rules 'fieldRules' to differentiate this field rules from main validation rules
    private val fieldRules =
        mutableListOf<(Boolean, Any?, MutableList<Mismatch>) -> Unit>()

    fun present() {
        fieldRules.add { exists, _, errors ->
            if (!exists) {
                errors.add(Mismatch("body.json.$name", "present", "<missing>"))
            }
        }
    }

    fun equalTo(expected: String) = addEquality(expected)
    fun equalTo(expected: Int) = addEquality(expected.toLong())
    fun equalTo(expected: Boolean) = addEquality(expected)

    // Add one error if the field is missing or its type or value is different.
    private fun addEquality(expected: Any) {
        fieldRules.add { exists, actual, errors ->
            if (!exists || actual != expected) {
                errors.add(
                    Mismatch(
                        "body.json.$name",
                        expected.toString(),
                        if (exists) actual.toString() else "<missing>",
                    )
                )
            }
        }
    }

    // made internal for safety purposes. This method mustn't be emitted outside of module 'app'
    internal fun build(): List<(Boolean, Any?, MutableList<Mismatch>) -> Unit> =
        fieldRules.toList()
}

/*
* Stores rules in a fixed order without keeping an HTTP response.
*/
class ResponseSpecification(
    private val rules: List<(HttpResponse, MutableList<Mismatch>) -> Unit>,
) {
    // Run saved rules with the given error list, including rules added through include().
    internal fun evaluateInto(response: HttpResponse, errors: MutableList<Mismatch>) {
        for (rule in rules) {
            rule(response, errors)
        }
    }

    fun validate(response: HttpResponse): ValidationResult {
        val errors = mutableListOf<Mismatch>()
        evaluateInto(response, errors)
        return ValidationResult(errors.toList())
    }
}

/*
* Collects checks for the whole HTTP response which run during validation.
*/
@HttpValidationDsl
class ResponseDsl() {
    private val rules =
        mutableListOf<(HttpResponse, MutableList<Mismatch>) -> Unit>()

    fun status(block: StatusDsl.() -> Unit) {
        val statusRules = StatusDsl().apply(block).build()

        rules.add { response, errors ->
            for (rule in statusRules) {
                rule(response.status, errors)
            }
        }
    }

    // Match header names without case sensitivity and keep all values in map order.
    fun header(name: String, block: HeaderDsl.() -> Unit) {
        val headerRules = HeaderDsl(name).apply(block).build()

        rules.add { response, errors ->
            val matching = response.headers.entries.filter {
                it.key.equals(name, ignoreCase = true)
            }
            val values = if (matching.isEmpty()) {
                null
            } else {
                matching.flatMap { it.value }
            }

            for (rule in headerRules) {
                rule(values, errors)
            }
        }
    }

    fun body(block: BodyDsl.() -> Unit) {
        val bodyRules = BodyDsl().apply(block).build()

        rules.add { response, errors ->
            for (rule in bodyRules) {
                rule(response.body, errors)
            }
        }
    }

    // Add shared rules here. They run later on the same response.
    fun include(specification: ResponseSpecification) {
        rules.add { response, errors ->
            specification.evaluateInto(response, errors)
        }
    }

    // made internal for safety purposes. This method mustn't be emitted outside of module 'app'
    internal fun build(): ResponseSpecification = ResponseSpecification(rules.toList())
}

/*
* Builds reusable rules.
*/
fun responseSpec(block: ResponseDsl.() -> Unit): ResponseSpecification =
    ResponseDsl().apply(block).build()

/*
* Builds rules and immediately checks one response.
*/
fun validate(response: HttpResponse, block: ResponseDsl.() -> Unit): ValidationResult =
    responseSpec(block).validate(response)
