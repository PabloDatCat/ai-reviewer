package aireviewer

import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject

private const val PROFILE_MODEL = "gpt-5.4-2026-03-05"
private val OLD_SHA = "c".repeat(40)

internal val profileProbeContext = RunContext(
    "fixture-owner/synthetic-review", 1, "fixture-actor",
    "a".repeat(40), "b".repeat(40), "main", "fixture", 1,
)

internal class ProfileCase(val name: String, private val changes: List<ProfileChange>) {
    val expectations = changes.mapNotNull { change -> change.category?.let { change.path to it } }.toMap()
    val snapshot = DiffSnapshot(
        profileProbeContext.baseSha, profileProbeContext.headSha, OLD_SHA, changes.map { it.patch() },
    )

    fun prepareVerified(): PreparedDiff {
        val byPath = changes.associateBy { it.path }
        val diff = prepareDiff(snapshot.files)
        verifyPatchText(diff, snapshot, object : GitHubApi {
            override fun sourceFile(commitSha: String, path: String): SourceFile {
                val change = byPath.getValue(path)
                val lines = when (commitSha) {
                    OLD_SHA -> change.before
                    profileProbeContext.headSha -> change.after
                    else -> error("Unexpected synthetic commit")
                }
                return SourceFile(lines.joinToString("\n", postfix = "\n").encodeToByteArray())
            }

            override fun permission(actor: String): String = error("Unexpected synthetic API call")
            override fun pullRequest(number: Int): PullRequestState = error("Unexpected synthetic API call")
            override fun comparison(baseSha: String, headSha: String): DiffSnapshot = error("Unexpected synthetic API call")
            override fun comment(number: Int, body: String) = error("Unexpected synthetic API call")
        })
        return diff
    }
}

internal data class ProfileChange(
    val path: String,
    val before: List<String>,
    val after: List<String>,
    val category: ReviewCategory? = null,
) {
    fun patch(): ChangedFile {
        val prefix = before.zip(after).takeWhile { (old, new) -> old == new }.size
        val suffix = before.drop(prefix).asReversed().zip(after.drop(prefix).asReversed())
            .takeWhile { (old, new) -> old == new }.size
        val removed = before.subList(prefix, before.size - suffix)
        val added = after.subList(prefix, after.size - suffix)
        val patch = buildString {
            appendLine("@@ -1,${before.size} +1,${after.size} @@")
            before.take(prefix).forEach { appendLine(" $it") }
            removed.forEach { appendLine("-$it") }
            added.forEach { appendLine("+$it") }
            before.takeLast(suffix).forEach { appendLine(" $it") }
        }
        return ChangedFile(path, patch, additions = added.size, deletions = removed.size)
    }
}

internal fun profileCases(): List<ProfileCase> {
    val styleBefore = """
        fun canShip(units: Int): Boolean {
            val positive = units > 0
            return positive
        }
    """.trimIndent()
    val styleAfter = """
        private fun hasStock(quantity: Int) = quantity > 0

        fun canShip(units: Int): Boolean =
            hasStock(units)
    """.trimIndent()
    val style = ProfileCase("style-only", listOf(ProfileChange("Stock.kt", styleBefore.lines(), styleAfter.lines())))

    val shipping = changed(
        "Shipping.kt", ReviewCategory.BUSINESS_LOGIC, """
            private const val FREE_SHIPPING_CENTS = 5_000

            // Orders of 5,000 cents or more ship free.
            fun shippingFeeCents(orderCents: Int): Int =
                if (orderCents >= FREE_SHIPPING_CENTS) 0 else 499
        """, "orderCents >= FREE_SHIPPING_CENTS", "orderCents > FREE_SHIPPING_CENTS",
    )
    val invoice = changed(
        "InvoiceArchive.kt", ReviewCategory.BACKWARD_COMPATIBILITY, """
            fun archivedV1Invoice(id: String): String = "v1|" + id
            fun newInvoice(id: String): String = "v2|" + id

            fun readArchivedInvoiceId(record: String): String? {
                val fields = record.split('|', limit = 2)
                if (fields.size != 2) return null
                val (version, id) = fields
                if (version !in setOf("v1", "v2")) return null
                return id
            }
        """, """version !in setOf("v1", "v2")""", "version != \"v2\"",
    )
    val claim = changed(
        "ShipmentClaims.kt", ReviewCategory.RACE_CONDITION, """
            import java.util.concurrent.ConcurrentHashMap

            class ShipmentClaims {
                private val claimed = ConcurrentHashMap<String, Boolean>()

                // Workers call this concurrently. Each successful claim starts a shipment.
                fun claim(id: String): Boolean {
                    return claimed.putIfAbsent(id, true) == null
                }
            }
        """, "return claimed.putIfAbsent(id, true) == null",
        "if (claimed.containsKey(id)) return false\n        claimed[id] = true\n        return true",
    )
    val retry = changed(
        "RetrySchedule.kt", ReviewCategory.CONTRACT_VIOLATION, """
            import java.time.Duration
            import java.time.Instant

            // The HTTP Retry-After delta value is expressed in seconds.
            fun nextAttempt(now: Instant, retryAfter: String): Instant {
                val deltaSeconds = retryAfter.toLong()
                return now.plus(Duration.ofSeconds(deltaSeconds))
            }
        """, "Duration.ofSeconds(deltaSeconds)", "Duration.ofMillis(deltaSeconds)",
    )
    val dispatch = changed(
        "PaymentDispatch.kt", ReviewCategory.ERROR_HANDLING, """
            import java.net.SocketTimeoutException

            interface BillingGateway {
                @Throws(SocketTimeoutException::class)
                fun send(id: String)
            }

            private fun sent(gateway: BillingGateway, id: String): Boolean =
                try {
                    gateway.send(id)
                    true
                } catch (_: SocketTimeoutException) {
                    false
                }

            fun dispatch(gateway: BillingGateway, queued: MutableSet<String>, id: String) {
                if (sent(gateway, id)) queued.remove(id)
            }
        """, "catch (_: SocketTimeoutException) {\n        false", "catch (_: SocketTimeoutException) {\n        true",
    )
    val safe = ProfileCase("safe-guard", listOf(changed(
        "InputDigit.kt", null, """
            fun firstDigit(input: String): Int? {
                val first = input.firstOrNull() ?: return null
                if (first !in '0'..'9') return null
                return first.digitToIntOrNull()
            }
        """, "digitToIntOrNull()", "digitToInt()",
    )))

    val context = List(331) { "// Demo delivery reference ${it + 1} · Зарегистрирован заказ «Летний» 🚚" }
    val nearLimit = ProfileCase("near-limit", listOf(
        shipping.copy(before = shipping.before + context, after = shipping.after + context),
        invoice, claim, retry, dispatch,
    ))
    return listOf(
        style, ProfileCase("business-logic", listOf(shipping)), ProfileCase("backward-compatibility", listOf(invoice)),
        ProfileCase("race-condition", listOf(claim)), ProfileCase("contract-violation", listOf(retry)),
        ProfileCase("error-handling", listOf(dispatch)), safe, nearLimit,
    )
}

private fun changed(
    path: String, category: ReviewCategory?, source: String, previous: String, replacement: String,
): ProfileChange {
    val before = source.trimIndent()
    check(previous in before && before.indexOf(previous) == before.lastIndexOf(previous))
    return ProfileChange(path, before.lines(), before.replace(previous, replacement).lines(), category)
}

object LiveProfileProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val exit = try {
            runProbe(args)
        } catch (_: TransportCleanupFailure) {
            println("status=ERROR reason=TRANSPORT_CLEANUP")
            1
        } catch (failure: ReviewFailure) {
            println("status=ERROR reason=${failure.reason.name}")
            1
        } catch (_: Exception) {
            println("status=ERROR reason=PROBE")
            1
        }
        if (exit != 0) exitProcess(exit)
    }
}

private fun runProbe(args: Array<String>): Int {
    val budget = ReviewBudget(System.nanoTime() + Duration.ofMinutes(14).toNanos())
    val arguments = args.toList()
    val names = arguments.filter { it.startsWith("--case=") }
    val showFindings = "--show-synthetic-findings" in arguments
    if (names.size > 1 || arguments.any { it != "--show-synthetic-findings" && it !in names }) {
        throw ReviewFailure(FailureReason.CONFIG)
    }
    val name = names.singleOrNull()?.substringAfter('=')
    val cases = profileCases().filter { name == null || it.name == name }
    if (cases.isEmpty()) throw ReviewFailure(FailureReason.CONFIG)
    val apiKey = System.getenv("OPENAI_API_KEY")?.takeIf(String::isNotBlank) ?: throw ReviewFailure(FailureReason.CONFIG)
    val profile = supportedProfile(PROFILE_MODEL)
    val rules = RunContext::class.java.getResourceAsStream("/review-prompt.md")
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: throw ReviewFailure(FailureReason.CONFIG)
    val prepared = cases.map { it to it.prepareVerified() }

    val transport = defaultTransport(budget)
    var canClose = true
    try {
        val observed = UsageTransport(transport)
        val client = OpenAiClient(apiKey, profile, observed)
        val redaction = "*".repeat(apiKey.length)
        println("model=${profile.model} reasoning_effort=${profile.reasoningEffort} max_output_tokens=${profile.maxOutputTokens}")
        var accepted = true
        for ((case, diff) in prepared) {
            observed.usage = ProbeUsage()
            val started = System.nanoTime()
            val result = try {
                client.review(diff, rules)
            } catch (failure: ReviewFailure) {
                reportCase(case.name, "ERROR", started, observed.usage, reason = failure.reason.name)
                return 1
            } catch (failure: TransportCleanupFailure) {
                canClose = false
                reportCase(case.name, "ERROR", started, observed.usage, reason = "TRANSPORT_CLEANUP")
                return 1
            }
            val safe = ReviewResult(result.findings.map {
                it.copy(title = it.title.replace(apiKey, redaction), explanation = it.explanation.replace(apiKey, redaction))
            })
            val comment = renderComment(profileProbeContext, Outcome.REVIEWED, safe, diff, case.snapshot)
            if (comment == renderComment(profileProbeContext, Outcome.ERROR, null, null, null)) {
                reportCase(case.name, "ERROR", started, observed.usage, reason = FailureReason.INVALID_RESPONSE.name)
                return 1
            }
            val actual = result.findings.map { it.file to it.category }
            val matches = actual.size == case.expectations.size && actual.toMap() == case.expectations
            if (!matches) accepted = false
            val status = if (matches) "COMPLETED" else "EXPECTATION_MISMATCH"
            reportCase(case.name, status, started, observed.usage, findings = actual.size)
            if (showFindings) println(comment.trimEnd())
        }
        return if (accepted) 0 else 1
    } finally {
        if (canClose) transport.close()
    }
}

private data class ProbeUsage(val http: Int? = null, val output: Int? = null, val reasoning: Int? = null)

private class UsageTransport(private val delegate: HttpTransport) : HttpTransport {
    var usage = ProbeUsage()

    override fun send(request: HttpRequest): HttpReply {
        val reply = delegate.send(request)
        usage = ProbeUsage(http = reply.status)
        if (reply.status == 200) {
            val parsed = try {
                parseResponseObject(reply.body, FailureReason.INVALID_RESPONSE)
            } catch (_: ReviewFailure) {
                return reply
            }
            val details = parsed["usage"] as? JsonObject
            val reasoning = details?.get("output_tokens_details") as? JsonObject
            usage = ProbeUsage(reply.status, details?.tokenCount("output_tokens"), reasoning?.tokenCount("reasoning_tokens"))
        }
        return reply
    }
}

private fun JsonObject.tokenCount(field: String): Int? = try {
    requireNonnegativeInt(field, FailureReason.INVALID_RESPONSE)
} catch (_: ReviewFailure) {
    null
}

private fun reportCase(
    case: String, status: String, started: Long, usage: ProbeUsage, findings: Int? = null, reason: String? = null,
) {
    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    println(
        "case=$case status=$status http=${usage.http ?: "unavailable"} latency_ms=$elapsed " +
            "output_tokens=${usage.output ?: "unavailable"} reasoning_tokens=${usage.reasoning ?: "unavailable"} " +
            "findings=${findings ?: "unavailable"} reason=${reason ?: "none"}",
    )
}
