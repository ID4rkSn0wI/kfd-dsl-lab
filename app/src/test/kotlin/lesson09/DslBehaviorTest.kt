package lesson09

import lesson09.dsl.Mismatch
import lesson09.dsl.responseSpec
import lesson09.dsl.validate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun nonBlankValue(value: String): Boolean = value.isNotBlank()

class DslBehaviorTest {
    @Test
    fun allIndependentFailuresHaveStructuredOrderedMismatches() {
        val response = HttpResponse(
            500,
            mapOf("Content-Type" to listOf("text/plain")),
            "{}",
        )
        val result = validate(response) {
            status { equalTo(200) }
            header("Content-Type") { startsWith("application/json") }
            body { json { field("id") { present() } } }
        }

        assertFalse(result.isValid)
        assertEquals(
            listOf(
                Mismatch("status", "200", "500"),
                Mismatch("headers.Content-Type", "prefix application/json", "[\"text/plain\"]"),
                Mismatch("body.json.id", "present", "<missing>"),
            ),
            result.mismatches,
        )
        assertEquals(
            "status: expected 200, actual 500",
            result.mismatches.first().toString(),
        )
    }

    @Test
    fun emptyBodyChecksUseLengthNotBlankness() {
        val absentOrEmpty = responseSpec { body { absentOrEmpty() } }
        val nonEmpty = responseSpec { body { nonEmpty() } }

        fun response(body: String?) = HttpResponse(200, emptyMap(), body)

        assertTrue(absentOrEmpty.validate(response(null)).isValid)
        assertTrue(absentOrEmpty.validate(response("")).isValid)
        assertFalse(absentOrEmpty.validate(response(" ")).isValid)
        assertFalse(absentOrEmpty.validate(response("data")).isValid)

        assertFalse(nonEmpty.validate(response(null)).isValid)
        assertFalse(nonEmpty.validate(response("")).isValid)
        assertTrue(nonEmpty.validate(response(" ")).isValid)
        assertTrue(nonEmpty.validate(response("data")).isValid)
    }

    @Test
    fun headerChecksHandleCaseValuesAndMissingHeaders() {
        val acceptSpec = responseSpec {
            header("ACCEPT") {
                equalTo("application/json")
                equalTo("text/html")
            }
        }
        val response = HttpResponse(
            200,
            linkedMapOf(
                "accept" to listOf("application/json"),
                "Accept" to listOf("text/html"),
                "X-Extra" to listOf("irrelevant"),
            ),
            null,
        )
        assertTrue(acceptSpec.validate(response).isValid)
        assertFalse(
            acceptSpec.validate(
                response.copy(headers = mapOf("Accept" to listOf("application/json,text/html")))
            ).isValid
        )

        val incorrectValues = acceptSpec.validate(
            response.copy(
                headers = linkedMapOf(
                    "accept" to listOf("APPLICATION/JSON"),
                    "Accept" to listOf(" text/html"),
                )
            )
        )
        assertEquals(
            listOf(
                Mismatch(
                    "headers.ACCEPT",
                    "value application/json",
                    "[\"APPLICATION/JSON\", \" text/html\"]",
                ),
                Mismatch(
                    "headers.ACCEPT",
                    "value text/html",
                    "[\"APPLICATION/JSON\", \" text/html\"]",
                ),
            ),
            incorrectValues.mismatches,
        )

        val traceSpec = responseSpec {
            header("X-Trace") {
                satisfies("non-blank value", ::nonBlankValue)
            }
        }
        fun traceResult(headers: Map<String, List<String>>) =
            traceSpec.validate(HttpResponse(200, headers, null))

        assertEquals("<missing>", traceResult(emptyMap()).mismatches.single().actual)
        assertEquals("[]", traceResult(mapOf("X-Trace" to emptyList())).mismatches.single().actual)
        assertEquals("[\"\"]", traceResult(mapOf("X-Trace" to listOf(""))).mismatches.single().actual)
        assertTrue(traceResult(mapOf("x-trace" to listOf("", "abc"))).isValid)

        val prefixSpec = responseSpec {
            header("Content-Type") { startsWith("application/json") }
        }
        fun prefixValid(value: String): Boolean =
            prefixSpec.validate(
                HttpResponse(200, mapOf("Content-Type" to listOf(value)), null)
            ).isValid

        assertTrue(prefixValid("application/jsonp"))
        assertTrue(prefixValid("application/json; charset=utf-8"))
        assertFalse(prefixValid(" application/json"))
        assertFalse(prefixValid("Application/JSON"))
    }

    @Test
    fun threeFormsOfUserPredicateGiveIdenticalResults() {
        val functionValue: (String) -> Boolean = ::nonBlankValue
        val direct = responseSpec {
            header("X-Trace") { satisfies("non-blank", { value -> value.isNotBlank() }) }
        }
        val stored = responseSpec {
            header("X-Trace") { satisfies("non-blank", functionValue) }
        }
        val reference = responseSpec {
            header("X-Trace") { satisfies("non-blank", ::nonBlankValue) }
        }

        for (values in listOf(emptyList(), listOf("  "), listOf("", "abc"))) {
            val response = HttpResponse(200, mapOf("X-Trace" to values), null)
            val results = listOf(direct, stored, reference).map { it.validate(response) }
            assertEquals(results[0], results[1])
            assertEquals(results[1], results[2])
        }
    }

    @Test
    fun jsonPresenceIsDifferentFromNullAndEqualityChecksType() {
        val specification = responseSpec {
            body {
                json {
                    field("id") {
                        present()
                        equalTo(42)
                    }
                    field("active") { equalTo(true) }
                }
            }
        }
        fun check(body: String) =
            specification.validate(HttpResponse(200, emptyMap(), body))

        assertEquals(
            listOf(
                Mismatch("body.json.id", "present", "<missing>"),
                Mismatch("body.json.id", "42", "<missing>"),
                Mismatch("body.json.active", "true", "<missing>"),
            ),
            check("{}").mismatches,
        )
        assertEquals(
            Mismatch("body.json.id", "42", "null"),
            check("""{"id":null,"active":true}""").mismatches.single(),
        )
        assertEquals(
            Mismatch("body.json.id", "42", "42"),
            check("""{"id":"42","active":true}""").mismatches.single(),
        )
        assertTrue(check("""{"id":42,"active":true,"extra":null}""").isValid)
        assertEquals(
            Mismatch("body.json.active", "true", "false"),
            check("""{"id":42,"active":false}""").mismatches.single(),
        )
    }

    @Test
    fun invalidJsonOnlyReportsParseErrorForEachJsonBlock() {
        val specification = responseSpec {
            status { equalTo(200) }
            body {
                json {
                    field("id") {
                        present()
                        equalTo(42)
                    }
                }
            }
            header("X-Trace") { equalTo("abc") }
        }
        for (body in listOf(null, "", "bad", """{"id":1.5}""")) {
            val result = specification.validate(HttpResponse(500, emptyMap(), body))
            assertEquals(
                listOf("status", "body.json", "headers.X-Trace"),
                result.mismatches.map { it.path },
            )
        }
        assertEquals(
            "<missing body>",
            specification.validate(HttpResponse(500, emptyMap(), null))
                .mismatches[1].actual,
        )
    }

    @Test
    fun repeatedRulesKeepEveryMismatch() {
        val result = validate(HttpResponse(500, emptyMap(), null)) {
            status {
                equalTo(200)
                equalTo(200)
            }
        }
        assertEquals(
            listOf(
                Mismatch("status", "200", "500"),
                Mismatch("status", "200", "500"),
            ),
            result.mismatches,
        )
    }

    @Test
    fun inputResponseAndItsCollectionsAreNeverModified() {
        val headers = linkedMapOf(
            "Accept" to mutableListOf("text/plain"),
            "accept" to mutableListOf("application/json"),
        )
        val response = HttpResponse(200, headers, """{"id":null}""")
        val before = response.copy(headers = headers.mapValues { it.value.toList() })

        validate(response) {
            header("Accept") { equalTo("application/json") }
            body { json { field("id") { present() } } }
        }

        assertEquals(before, response)
        assertEquals(mutableListOf("text/plain"), headers["Accept"])
        assertEquals(mutableListOf("application/json"), headers["accept"])
    }

    @Test
    fun explicitlyLabelledOuterReceiverIsAvailableWithDslMarker() {
        val specification = responseSpec {
            body {
                nonEmpty()
                this@responseSpec.status { equalTo(200) }
            }
        }
        val result = specification.validate(HttpResponse(500, emptyMap(), null))
        assertEquals(setOf("body", "status"), result.mismatches.map { it.path }.toSet())
        assertEquals(2, result.mismatches.size)
    }
}
