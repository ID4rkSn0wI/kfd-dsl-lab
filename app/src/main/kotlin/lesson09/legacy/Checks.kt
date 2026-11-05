package lesson09.legacy

import lesson09.HttpResponse
import lesson09.support.json.JsonParseResult
import lesson09.support.json.parseJsonObject

fun check01(response: HttpResponse): Boolean {
    if (response.status != 204) return false
    if (response.body != null && response.body != "") return false
    return true
}

fun check02(response: HttpResponse): Boolean {
    if (response.status != 200) return false
    val values = headerValues(response, "Content-Type")
    if (values.none { it.startsWith("application/json") }) return false
    if (response.body.isNullOrEmpty()) return false
    return true
}

fun check03(response: HttpResponse): Boolean {
    if (response.status != 200) return false
    val values = headerValues(response, "Accept")
    if (values.none { it == "application/json" }) return false
    if (values.none { it == "text/html" }) return false
    return true
}

fun check04(response: HttpResponse): Boolean {
    if (response.status != 200) return false
    val fields = flatObject(response.body) ?: return false
    if (!fields.containsKey("id")) return false
    if (fields["name"] != "Ivanov") return false
    return true
}

fun check05(response: HttpResponse): Boolean {
    if (response.status != 200) return false
    val fields = flatObject(response.body) ?: return false
    if (fields["id"] != 42L) return false
    if (fields["active"] != true) return false
    return true
}

fun check06(response: HttpResponse): Boolean {
    if (response.status != 422) return false
    val values = headerValues(response, "Content-Type")
    if (values.none { it.startsWith("application/json") }) return false
    val fields = flatObject(response.body) ?: return false
    if (fields["error"] != "invalid") return false
    if (fields["retry"] != false) return false
    return true
}

private fun headerValues(response: HttpResponse, name: String): List<String> =
    response.headers.entries
        .filter { it.key.equals(name, ignoreCase = true) }
        .flatMap { it.value }

private fun flatObject(source: String?): Map<String, Any?>? {
    if (source == null) return null
    return when (val result = parseJsonObject(source)) {
        is JsonParseResult.Success -> result.fields
        is JsonParseResult.Failure -> null
    }
}
