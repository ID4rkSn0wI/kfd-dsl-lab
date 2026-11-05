package lesson09

import lesson09.dsl.Mismatch
import lesson09.dsl.ResponseDsl
import lesson09.dsl.responseSpec
import lesson09.dsl.validate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpecificationTest {
    private fun response(status: Int, trace: List<String> = listOf("ok")) =
        HttpResponse(status, mapOf("X-Trace" to trace), null)

    @Test
    fun configurationRunsOnceAndResultsStayIndependent() {
        var configurations = 0
        var predicateCalls = 0

        val specification = responseSpec {
            configurations++
            status { equalTo(200) }
            header("X-Trace") {
                satisfies("non-blank") {
                    predicateCalls++
                    it.isNotBlank()
                }
            }
        }

        assertEquals(1, configurations)
        assertEquals(0, predicateCalls)

        val first = specification.validate(response(200))
        assertTrue(first.isValid)
        assertEquals(1, predicateCalls)

        val second = specification.validate(response(500, listOf("")))
        assertFalse(second.isValid)
        assertEquals(
            listOf(
                Mismatch("status", "200", "500"),
                Mismatch("headers.X-Trace", "non-blank", "[\"\"]"),
            ),
            second.mismatches,
        )
        assertEquals(2, predicateCalls)

        val third = specification.validate(response(200))
        assertTrue(third.isValid)
        assertEquals(3, predicateCalls)

        assertEquals(1, configurations)
        assertTrue(first.mismatches.isEmpty())
        assertEquals(2, second.mismatches.size)

        val withoutHeader = HttpResponse(200, emptyMap(), null)
        val withEmptyHeader = response(200, emptyList())
        assertFalse(specification.validate(withoutHeader).isValid)
        assertFalse(specification.validate(withEmptyHeader).isValid)
        assertEquals(3, predicateCalls)
    }

    @Test
    fun aStoredConfigurationLambdaCanBeUsedAcrossCalls() {
        val settings: ResponseDsl.() -> Unit = {
            status { equalTo(200) }
        }

        val first = validate(response(200), settings)
        val second = validate(response(500), settings)
        val third = validate(response(200), settings)

        assertTrue(first.isValid)
        assertEquals(listOf(Mismatch("status", "200", "500")), second.mismatches)
        assertTrue(third.isValid)
        assertTrue(first.mismatches.isEmpty())
    }

    @Test
    fun includedSpecificationCanBeSharedWithoutMovingErrorsAcrossResults() {
        val common = responseSpec { status { equalTo(200) } }
        val firstSpec = responseSpec {
            include(common)
            header("X-Trace") { equalTo("ok") }
        }
        val secondSpec = responseSpec {
            include(common)
            header("X-Trace") { startsWith("other") }
        }

        val first = firstSpec.validate(response(500, listOf("no")))
        val second = secondSpec.validate(response(200, listOf("other-value")))
        val third = firstSpec.validate(response(200))

        assertEquals(listOf("status", "headers.X-Trace"), first.mismatches.map { it.path })
        assertTrue(second.isValid)
        assertTrue(third.isValid)
        assertEquals(2, first.mismatches.size)
    }

    @Test
    fun includesPreservePositionAndRepeatedIncludesRepeatMismatches() {
        val common = responseSpec { status { equalTo(200) } }
        val specification = responseSpec {
            header("X-Trace") { equalTo("yes") }
            include(common)
            body { nonEmpty() }
            include(common)
        }

        val result = specification.validate(
            HttpResponse(500, mapOf("X-Trace" to listOf("no")), null)
        )
        assertEquals(
            listOf(
                Mismatch("headers.X-Trace", "value yes", "[\"no\"]"),
                Mismatch("status", "200", "500"),
                Mismatch("body", "non-empty string", "<null>"),
                Mismatch("status", "200", "500"),
            ),
            result.mismatches,
        )
    }
}
