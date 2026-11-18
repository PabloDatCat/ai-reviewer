package aireviewer

import kotlinx.serialization.json.JsonObject

data class OpenAiErrorDetails internal constructor(
    val httpStatus: Int? = null,
    val code: String? = null,
    val type: String? = null,
    val parameter: String? = null,
    val requestId: String? = null,
    val responseStatus: String? = null,
    val incompleteReason: String? = null,
    val explanation: String,
) {
    fun logLine(): String = buildString {
        append("OPENAI_ERROR")
        httpStatus?.let { append(" http_status=$it") }
        code?.let { append(" code=$it") }
        type?.let { append(" type=$it") }
        parameter?.let { append(" param=$it") }
        requestId?.let { append(" request_id=$it") }
        responseStatus?.let { append(" response_status=$it") }
        incompleteReason?.let { append(" incomplete_reason=$it") }
        append(": ").append(explanation)
    }
}

private val errorIdentifier = Regex("[a-z][a-z0-9_]{0,79}")
private val errorParameter = Regex("[a-z][A-Za-z0-9_.\\[\\]]{0,119}")
private val requestIdentifier = Regex("[A-Za-z0-9_-]{1,150}")
private val credentialPrefix = Regex("sk-|gh[pousr]_|github_pat_")

internal fun openAiErrorDetails(
    reply: HttpReply,
    apiKey: String,
    parsedResponse: JsonObject? = null,
    fallback: String? = null,
): OpenAiErrorDetails {
    // Provider messages may echo credentials, schema values or input. Never log them.
    val response = parsedResponse ?: try {
        parseResponseObject(reply.body, FailureReason.INVALID_RESPONSE)
    } catch (_: ReviewFailure) {
        null
    }
    val error = response?.get("error") as? JsonObject
    fun metadata(value: String?, pattern: Regex): String? = value?.takeIf {
        pattern.matches(it) && !it.contains(apiKey) && !credentialPrefix.containsMatchIn(it)
    }
    val code = metadata(error?.stringField("code"), errorIdentifier)
    val type = metadata(error?.stringField("type"), errorIdentifier)
    val parameter = metadata(error?.stringField("param"), errorParameter)
    val requestId = metadata(reply.headers.entries.firstOrNull {
        it.key.equals("x-request-id", ignoreCase = true)
    }?.value?.firstOrNull(), requestIdentifier)
    val status = metadata(response?.stringField("status"), errorIdentifier)
    val incomplete = response?.get("incomplete_details") as? JsonObject
    val incompleteReason = metadata(incomplete?.stringField("reason"), errorIdentifier)
    val explanation = when {
        code == "insufficient_quota" || type == "insufficient_quota" || code == "billing_hard_limit_reached" ->
            "API project quota is exhausted. Check billing, credits and project spending limits."
        code == "rate_limit_exceeded" || type == "rate_limit_exceeded" ->
            "API rate limit exceeded. Check project request/token limits and try Re-run later."
        code == "invalid_api_key" || reply.status == 401 ->
            "Authentication failed. Check OPENAI_API_KEY and the API project it belongs to."
        code == "model_not_found" ->
            "The model is unavailable or inaccessible. Check LLM_MODEL and project model access."
        reply.status == 403 ->
            "Access denied. Check API project permissions, model access and organization policy."
        code == "context_length_exceeded" ->
            "Input exceeds the model context window. Use a larger-context model or a smaller PR."
        code == "invalid_json_schema" ->
            "The API rejected text.format.schema. Check Structured Outputs support and the request schema."
        parameter == "reasoning.effort" ->
            "The model rejected reasoning.effort. The reviewer sends none; check model support for this value."
        code == "unsupported_parameter" || code == "unsupported_value" ->
            "The model rejected a request parameter or value. Check the named parameter and model capabilities."
        status == "incomplete" && incompleteReason == "max_output_tokens" ->
            "The response exhausted max_output_tokens=6000. No partial findings are published."
        status == "incomplete" && incompleteReason == "content_filter" ->
            "The response was stopped by a content filter. No partial findings are published."
        status == "incomplete" ->
            "The model response is incomplete. No partial findings are published."
        reply.status == 429 ->
            "API rate limit or quota rejected the request. Check API project limits; no specific cause was returned."
        reply.status == 400 || reply.status == 422 ->
            "Request rejected. Check the named parameter and support for reasoning.effort=none, max_output_tokens=6000 and strict JSON schema."
        reply.status == 404 ->
            "Requested API resource was not found. Check LLM_MODEL and project model access."
        reply.status in 500..599 || code == "server_error" || type == "server_error" ->
            "OpenAI service error. Try Re-run later; use request_id when contacting support."
        fallback != null -> fallback
        else -> "OpenAI rejected the request. Use the HTTP status, error metadata and request_id to investigate."
    }
    return OpenAiErrorDetails(reply.status, code, type, parameter, requestId, status, incompleteReason, explanation)
}

internal fun openAiTransportError(reason: FailureReason): OpenAiErrorDetails = OpenAiErrorDetails(
    explanation = if (reason == FailureReason.DEADLINE) {
        "OpenAI exchange timed out or was interrupted. The full exchange limit is 60 seconds, capped by the remaining review budget."
    } else {
        "OpenAI transport failed before a usable response was received. Check network/TLS and response-size limits."
    },
)
