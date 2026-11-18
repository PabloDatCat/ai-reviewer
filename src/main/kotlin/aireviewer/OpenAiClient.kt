package aireviewer

import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ModelProfile(val model: String, val reasoningEffort: String, val maxOutputTokens: Int)

private val REVIEW_MODEL = ModelProfile("gpt-5.4-2026-03-05", "none", 6_000)
private val RESPONSES_API = URI("https://api.openai.com/v1/responses")
private val OPENAI_TIMEOUT = Duration.ofSeconds(60)
private val INVALID_RESPONSE = FailureReason.INVALID_RESPONSE

fun supportedProfile(model: String): ModelProfile {
    if (model != REVIEW_MODEL.model) throw ReviewFailure(FailureReason.CONFIG)
    return REVIEW_MODEL
}

class OpenAiClient(
    apiKey: String,
    private val profile: ModelProfile,
    private val transport: HttpTransport,
) : LlmReviewer {
    private val authorization = bearerValue(apiKey)

    init {
        if (profile != supportedProfile(profile.model)) throw ReviewFailure(FailureReason.CONFIG)
    }

    override fun review(diff: PreparedDiff, rules: String): ReviewResult {
        val payload = buildJsonObject {
            put("model", profile.model)
            put("reasoning", buildJsonObject { put("effort", profile.reasoningEffort) })
            put("max_output_tokens", profile.maxOutputTokens)
            put("store", false)
            put("instructions", rules)
            put("input", diff.text)
            put("text", REVIEW_FORMAT)
        }
        val request = HttpRequest.newBuilder(RESPONSES_API)
            .timeout(OPENAI_TIMEOUT)
            .header("Authorization", authorization)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()

        val reply = transport.send(request)
        if (reply.status != 200) throw ReviewFailure(FailureReason.OPENAI)
        val response = parseResponseObject(reply.body, INVALID_RESPONSE)
        return validateResult(parseResult(completedOutput(response)), diff)
    }
}

private fun completedOutput(response: JsonObject): String {
    if (response.stringField("status") != "completed" ||
        response["error"]?.let { it != JsonNull } == true ||
        response["incomplete_details"]?.let { it != JsonNull } == true
    ) {
        invalidResponse()
    }

    var finalText: String? = null
    for (value in response.requireArray("output", INVALID_RESPONSE)) {
        val item = value.requireObject(INVALID_RESPONSE)
        when (item.stringField("type")) {
            "reasoning" -> {
                if ("status" in item && item.stringField("status") != "completed") invalidResponse()
            }
            "message" -> {
                if (item.stringField("role") != "assistant" || item.stringField("status") != "completed") {
                    invalidResponse()
                }
                val content = item.requireArray("content", INVALID_RESPONSE)
                when (item["phase"]) {
                    JsonPrimitive("commentary") -> {
                        if (finalText != null) invalidResponse()
                        content.forEach { outputText(it) }
                    }
                    null, JsonNull, JsonPrimitive("final_answer") -> {
                        if (finalText != null) invalidResponse()
                        finalText = outputText(content.singleOrNull())
                    }
                    else -> invalidResponse()
                }
            }
            else -> invalidResponse()
        }
    }
    return finalText ?: invalidResponse()
}

private fun outputText(value: JsonElement?): String {
    val text = value.requireObject(INVALID_RESPONSE)
    if (text.stringField("type") != "output_text") invalidResponse()
    return text.requireString("text", INVALID_RESPONSE)
}

private fun parseResult(text: String): ReviewResult {
    val result = parseResponseObject(text, INVALID_RESPONSE)
    if (result.keys != RESULT_PROPERTIES.keys) invalidResponse()
    val findings = result.requireArray("findings", INVALID_RESPONSE)
    if (findings.size > 5) invalidResponse()
    return ReviewResult(findings.map { json ->
        val finding = json.requireObject(INVALID_RESPONSE)
        if (finding.keys != FINDING_PROPERTIES.keys) invalidResponse()
        val category = finding.requireString("category", INVALID_RESPONSE)
        val side = finding.requireString("side", INVALID_RESPONSE)
        Finding(
            file = finding.requireString("file", INVALID_RESPONSE),
            severity = finding.requireString("severity", INVALID_RESPONSE),
            title = finding.requireString("title", INVALID_RESPONSE),
            explanation = finding.requireString("explanation", INVALID_RESPONSE),
            category = ReviewCategory.entries.firstOrNull { it.name == category } ?: invalidResponse(),
            side = DiffSide.entries.firstOrNull { it.name == side } ?: invalidResponse(),
            startLine = finding.requireNonnegativeInt("startLine", INVALID_RESPONSE),
            endLine = finding.requireNonnegativeInt("endLine", INVALID_RESPONSE),
        )
    })
}

private fun invalidResponse(): Nothing = throw ReviewFailure(INVALID_RESPONSE)

private val STRING_SCHEMA = buildJsonObject { put("type", "string") }
private val INTEGER_SCHEMA = buildJsonObject { put("type", "integer") }

private fun enumSchema(values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("enum", JsonArray(values.map(::JsonPrimitive)))
}

private fun objectSchema(properties: JsonObject): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", properties)
    put("required", JsonArray(properties.keys.map(::JsonPrimitive)))
    put("additionalProperties", false)
}

private val FINDING_PROPERTIES = buildJsonObject {
    put("file", STRING_SCHEMA)
    put("severity", enumSchema(listOf("high", "medium")))
    put("title", STRING_SCHEMA)
    put("explanation", STRING_SCHEMA)
    put("category", enumSchema(ReviewCategory.entries.map { it.name }))
    put("side", enumSchema(DiffSide.entries.map { it.name }))
    put("startLine", INTEGER_SCHEMA)
    put("endLine", INTEGER_SCHEMA)
}

private val RESULT_PROPERTIES = buildJsonObject {
    put("findings", buildJsonObject {
        put("type", "array")
        put("items", objectSchema(FINDING_PROPERTIES))
    })
}

private val REVIEW_FORMAT = buildJsonObject {
    put("format", buildJsonObject {
        put("type", "json_schema")
        put("name", "pull_request_review")
        put("strict", true)
        put("schema", objectSchema(RESULT_PROPERTIES))
    })
}
