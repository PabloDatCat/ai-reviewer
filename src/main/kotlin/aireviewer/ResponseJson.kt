package aireviewer

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val unsignedJsonInteger = Regex("0|[1-9][0-9]*")

internal fun parseResponseObject(body: String, reason: FailureReason): JsonObject {
    val parsed = try {
        checkJsonTokens(body, reason)
        Json.parseToJsonElement(body)
    } catch (_: SerializationException) {
        throw ReviewFailure(reason)
    }
    return parsed.requireObject(reason)
}

private const val JSON_WHITESPACE = " \t\r\n"
private const val JSON_DELIMITERS = JSON_WHITESPACE + "{}[],:\""
private const val MAX_RESPONSE_JSON_DEPTH = 64
private val jsonNumber = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")

// The tree parser accepts bare literals and raw control characters in strings, and keeps the last duplicate key.
private fun checkJsonTokens(body: String, reason: FailureReason) {
    val objectKeys = ArrayDeque<MutableSet<String>>()
    var depth = 0
    var index = 0
    while (index < body.length) {
        when (val char = body[index]) {
            '{', '[' -> {
                if (++depth > MAX_RESPONSE_JSON_DEPTH) throw ReviewFailure(reason)
                if (char == '{') objectKeys.addLast(mutableSetOf())
                index++
            }
            '}', ']' -> {
                if (--depth < 0) throw ReviewFailure(reason)
                if (char == '}' && objectKeys.removeLastOrNull() == null) throw ReviewFailure(reason)
                index++
            }
            '"' -> {
                val start = index
                index = endOfJsonString(body, start, reason)
                var next = index
                while (next < body.length && body[next] in JSON_WHITESPACE) next++
                if (body.getOrNull(next) == ':') {
                    val rawKey = body.substring(start + 1, index - 1)
                    val key = if ('\\' in rawKey) Json.decodeFromString<String>(body.substring(start, index)) else rawKey
                    if (objectKeys.lastOrNull()?.add(key) != true) throw ReviewFailure(reason)
                }
            }
            ' ', '\t', '\r', '\n', ',', ':' -> index++
            else -> {
                val start = index++
                while (index < body.length && body[index] !in JSON_DELIMITERS) index++
                val literal = body.substring(start, index)
                if (literal != "true" && literal != "false" && literal != "null" && !jsonNumber.matches(literal)) {
                    throw ReviewFailure(reason)
                }
            }
        }
    }
}

private fun endOfJsonString(body: String, start: Int, reason: FailureReason): Int {
    var escaped = false
    for (index in start + 1 until body.length) {
        val char = body[index]
        if (char < ' ') throw ReviewFailure(reason)
        when {
            escaped -> escaped = false
            char == '\\' -> escaped = true
            char == '"' -> return index + 1
        }
    }
    throw ReviewFailure(reason)
}

internal fun JsonElement?.requireObject(reason: FailureReason): JsonObject =
    this as? JsonObject ?: throw ReviewFailure(reason)

internal fun JsonObject.requireObject(key: String, reason: FailureReason): JsonObject =
    this[key].requireObject(reason)

internal fun JsonObject.requireArray(key: String, reason: FailureReason): JsonArray =
    this[key] as? JsonArray ?: throw ReviewFailure(reason)

internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.requireString(key: String, reason: FailureReason): String =
    stringField(key) ?: throw ReviewFailure(reason)

internal fun JsonObject.requireNonnegativeInt(key: String, reason: FailureReason): Int =
    this[key].requireNonnegativeLong(reason).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: throw ReviewFailure(reason)

internal fun JsonElement?.requireNonnegativeLong(reason: FailureReason): Long =
    (this as? JsonPrimitive)?.takeUnless { it.isString }?.content
        ?.takeIf(unsignedJsonInteger::matches)?.toLongOrNull() ?: throw ReviewFailure(reason)
