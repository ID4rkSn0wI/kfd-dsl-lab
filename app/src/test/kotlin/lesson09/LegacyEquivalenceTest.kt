package lesson09

import lesson09.dsl.Mismatch
import lesson09.dsl.ValidationResult
import lesson09.dsl.check01Dsl
import lesson09.dsl.check02Dsl
import lesson09.dsl.check03Dsl
import lesson09.dsl.check04Dsl
import lesson09.dsl.check05Dsl
import lesson09.dsl.check06Dsl
import lesson09.legacy.check01
import lesson09.legacy.check02
import lesson09.legacy.check03
import lesson09.legacy.check04
import lesson09.legacy.check05
import lesson09.legacy.check06
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LegacyEquivalenceTest {
    private fun compare(
        original: (HttpResponse) -> Boolean,
        replacement: (HttpResponse) -> ValidationResult,
        responses: List<HttpResponse>,
    ) {
        for (response in responses) {
            assertEquals(
                original(response),
                replacement(response).isValid,
                "Response: $response",
            )
        }
    }

    @Test
    fun check01KeepsAllOriginalConditions() {
        compare(
            ::check01,
            ::check01Dsl,
            listOf(
                HttpResponse(204, emptyMap(), null),
                HttpResponse(204, emptyMap(), ""),
                HttpResponse(204, emptyMap(), " "),
                HttpResponse(204, emptyMap(), "value"),
                HttpResponse(200, emptyMap(), null),
                HttpResponse(200, emptyMap(), ""),
            ),
        )
        assertEquals(
            listOf(Mismatch("status", "204", "200")),
            check01Dsl(HttpResponse(200, emptyMap(), null)).mismatches,
        )
    }

    @Test
    fun check02KeepsAllOriginalConditions() {
        val valid = HttpResponse(
            200,
            mapOf("content-type" to listOf("application/json; charset=UTF-8")),
            "not json",
        )
        compare(
            ::check02,
            ::check02Dsl,
            listOf(
                valid,
                valid.copy(status = 404),
                valid.copy(headers = emptyMap()),
                valid.copy(headers = mapOf("Content-Type" to emptyList())),
                valid.copy(headers = mapOf("Content-Type" to listOf("text/json"))),
                valid.copy(headers = mapOf("Content-Type" to listOf("application/jsonp"))),
                valid.copy(body = null),
                valid.copy(body = ""),
                valid.copy(body = " "),
                valid.copy(headers = mapOf("Content-Type" to listOf(" application/json"))),
            ),
        )
        assertEquals(
            listOf(Mismatch("headers.Content-Type", "prefix application/json", "<missing>")),
            check02Dsl(valid.copy(headers = emptyMap())).mismatches,
        )
    }

    @Test
    fun check03KeepsAllOriginalConditions() {
        val valid = HttpResponse(
            200,
            linkedMapOf(
                "Accept" to listOf("application/json"),
                "accept" to listOf("text/html"),
            ),
            null,
        )
        compare(
            ::check03,
            ::check03Dsl,
            listOf(
                valid,
                valid.copy(status = 400),
                valid.copy(headers = emptyMap()),
                valid.copy(headers = mapOf("Accept" to listOf("application/json"))),
                valid.copy(headers = mapOf("Accept" to listOf("text/html"))),
                valid.copy(headers = mapOf("Accept" to listOf("application/json,text/html"))),
                valid.copy(headers = mapOf("Accept" to listOf("application/json", "text/html"))),
                valid.copy(headers = mapOf("Accept" to listOf("application/json", "text/html", "other"))),
            ),
        )
        assertEquals(
            listOf(
                Mismatch("headers.Accept", "value text/html", "[\"application/json\"]"),
            ),
            check03Dsl(
                valid.copy(headers = mapOf("Accept" to listOf("application/json")))
            ).mismatches,
        )
    }

    @Test
    fun check04KeepsAllOriginalConditions() {
        val valid = HttpResponse(
            200,
            emptyMap(),
            """{"id":null,"name":"Ivanov","extra":true}""",
        )
        compare(
            ::check04,
            ::check04Dsl,
            listOf(
                valid,
                valid.copy(status = 500),
                valid.copy(body = """{"name":"Ivanov"}"""),
                valid.copy(body = """{"id":42,"name":"Ivanov"}"""),
                valid.copy(body = """{"id":null,"name":"ivanov"}"""),
                valid.copy(body = """{"id":null,"name":42}"""),
                valid.copy(body = "{}"),
                valid.copy(body = "invalid"),
                valid.copy(body = null),
                valid.copy(body = ""),
            ),
        )
        assertEquals(
            listOf(Mismatch("body.json.id", "present", "<missing>")),
            check04Dsl(valid.copy(body = """{"name":"Ivanov"}""")).mismatches,
        )
    }

    @Test
    fun check05KeepsAllOriginalConditions() {
        val valid = HttpResponse(200, emptyMap(), """{"id":42,"active":true}""")
        compare(
            ::check05,
            ::check05Dsl,
            listOf(
                valid,
                valid.copy(status = 404),
                valid.copy(body = """{"id":"42","active":true}"""),
                valid.copy(body = """{"id":42,"active":false}"""),
                valid.copy(body = """{"id":null,"active":true}"""),
                valid.copy(body = """{"active":true}"""),
                valid.copy(body = """{"id":42}"""),
                valid.copy(body = """{"id":42,"active":true,"extra":123}"""),
                valid.copy(body = "[]"),
                valid.copy(body = null),
            ),
        )
        assertEquals(
            listOf(Mismatch("body.json.active", "true", "false")),
            check05Dsl(valid.copy(body = """{"id":42,"active":false}""")).mismatches,
        )
    }

    @Test
    fun check06KeepsAllOriginalConditions() {
        val valid = HttpResponse(
            422,
            mapOf("CONTENT-type" to listOf("application/json")),
            """{"error":"invalid","retry":false}""",
        )
        compare(
            ::check06,
            ::check06Dsl,
            listOf(
                valid,
                valid.copy(status = 200),
                valid.copy(headers = emptyMap()),
                valid.copy(headers = mapOf("Content-Type" to emptyList())),
                valid.copy(headers = mapOf("Content-Type" to listOf("text/plain"))),
                valid.copy(body = """{"error":"invalid","retry":true}"""),
                valid.copy(body = """{"error":"other","retry":false}"""),
                valid.copy(body = """{"error":"invalid"}"""),
                valid.copy(body = """{"retry":false}"""),
                valid.copy(body = "invalid"),
                valid.copy(body = null),
                valid.copy(
                    headers = valid.headers + ("X-Extra" to listOf("ok")),
                    body = """{"error":"invalid","retry":false,"extra":"ok"}""",
                ),
            ),
        )
        assertEquals(
            listOf(Mismatch("body.json.error", "invalid", "other")),
            check06Dsl(
                valid.copy(body = """{"error":"other","retry":false}""")
            ).mismatches,
        )
    }
}
