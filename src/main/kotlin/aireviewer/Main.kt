package aireviewer

import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CancellationException
import kotlin.system.exitProcess

private val CLI_BUDGET = Duration.ofMinutes(14)
private const val MAX_PR_FILES = 50
private val writePermissions = setOf("write", "admin")
private val skippableInputFailures = setOf(
    FailureReason.LIMIT,
    FailureReason.PATCH_UNAVAILABLE,
    FailureReason.INVALID_PATCH,
    FailureReason.SOURCE_MISMATCH,
    FailureReason.SOURCE_UNAVAILABLE,
)

fun runReview(ctx: RunContext, github: GitHubApi, llm: LlmReviewer, rules: String, budget: ReviewBudget): Outcome =
    ReviewRun(ctx, github, llm, rules, budget).run().outcome

private data class ReviewExecution(
    val outcome: Outcome,
    val reason: FailureReason? = null,
    val openAiError: OpenAiErrorDetails? = null,
)

private class ReviewRun(
    private var ctx: RunContext,
    private val github: GitHubApi,
    private val llm: LlmReviewer,
    private val rules: String,
    private val budget: ReviewBudget,
) {
    private var openAiError: OpenAiErrorDetails? = null

    fun run(): ReviewExecution {
        if (ctx.runAttempt < 1) return ReviewExecution(Outcome.IGNORED)
        val execution = try {
            reviewAuthorizedPullRequest()
        } catch (failure: TransportCleanupFailure) {
            throw failure
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ReviewExecution(Outcome.ERROR, FailureReason.DEADLINE)
        } catch (_: CancellationException) {
            ReviewExecution(Outcome.ERROR, FailureReason.DEADLINE)
        } catch (failure: ReviewFailure) {
            ReviewExecution(Outcome.ERROR, failure.reason, failure.openAiError)
        } catch (_: Exception) {
            ReviewExecution(Outcome.ERROR)
        }
        // Preserve the provider diagnosis even if the final PR check or comment POST fails.
        return execution.copy(openAiError = openAiError ?: execution.openAiError)
    }

    private fun reviewAuthorizedPullRequest(): ReviewExecution {
        if (request { github.permission(ctx.actor) } !in writePermissions) return ReviewExecution(Outcome.IGNORED)
        val original = request { github.pullRequest(ctx.number) }
        if (ctx.runAttempt > 1) {
            ctx = ctx.copy(baseSha = original.baseSha, headSha = original.headSha,
                baseRef = original.baseRef, headRef = original.headRef)
        }
        if (!original.matchesEvent()) return ReviewExecution(Outcome.STALE)

        val snapshot: DiffSnapshot
        val diff: PreparedDiff
        try {
            if (original.changedFiles > MAX_PR_FILES) throw ReviewFailure(FailureReason.LIMIT)
            snapshot = request { github.comparison(ctx.baseSha, ctx.headSha) }
            if (snapshot.baseSha != ctx.baseSha || snapshot.headSha != ctx.headSha) {
                throw ReviewFailure(FailureReason.INVALID_PATCH)
            }
            diff = prepareDiff(snapshot.files)
            verifyPatchText(diff, snapshot, object : GitHubApi by github {
                override fun sourceFile(commitSha: String, path: String, maxBytes: Int): SourceFile =
                    request { github.sourceFile(commitSha, path, maxBytes) }
            })
        } catch (failure: ReviewFailure) {
            val outcome = if (failure.reason in skippableInputFailures) Outcome.SKIPPED else Outcome.ERROR
            return diagnostic(original, ReviewExecution(outcome, failure.reason))
        }

        if (!stillCurrent(original)) return ReviewExecution(Outcome.STALE)
        requireActive(budget)
        val result = try {
            val reply = request { llm.review(diff, rules) }
            validateResult(reply, diff)
        } catch (failure: Exception) {
            if (failure is TransportCleanupFailure || failure is InterruptedException || failure is CancellationException) {
                throw failure
            }
            openAiError = (failure as? ReviewFailure)?.openAiError
            val reason = (failure as? ReviewFailure)?.reason ?: FailureReason.OPENAI
            return diagnostic(original, ReviewExecution(Outcome.ERROR, reason))
        }

        return publish(original, ReviewExecution(Outcome.REVIEWED)) {
            renderComment(ctx, Outcome.REVIEWED, result, diff, snapshot)
        }
    }

    private fun diagnostic(original: PullRequestState, execution: ReviewExecution): ReviewExecution =
        publish(original, execution) { renderComment(ctx, execution.outcome, null, null, null) }

    private fun publish(
        original: PullRequestState,
        execution: ReviewExecution,
        render: () -> String,
    ): ReviewExecution {
        if (!stillCurrent(original)) return ReviewExecution(Outcome.STALE)
        val body = render()
        val published = if (execution.outcome == Outcome.REVIEWED &&
            body == renderComment(ctx, Outcome.ERROR, null, null, null)
        ) {
            ReviewExecution(Outcome.ERROR, FailureReason.INVALID_RESPONSE)
        } else {
            execution
        }

        // A failed response can follow an accepted POST. This is the only comment attempt.
        request { github.comment(ctx.number, body) }
        return published
    }

    private fun stillCurrent(original: PullRequestState): Boolean {
        val latest = request { github.pullRequest(ctx.number) }
        return latest.matchesEvent() && latest.headRepo == original.headRepo
    }

    private fun PullRequestState.matchesEvent(): Boolean =
        open && !draft && labeled &&
            baseSha == ctx.baseSha && headSha == ctx.headSha &&
            baseRef == ctx.baseRef && headRef == ctx.headRef

    private inline fun <T> request(action: () -> T): T {
        requireActive(budget)
        val result = action()
        requireActive(budget)
        return result
    }
}

private fun requireActive(budget: ReviewBudget) {
    if (Thread.currentThread().isInterrupted) throw ReviewFailure(FailureReason.DEADLINE)
    budget.remaining()
}

internal fun loadRunContext(env: (String) -> String?, readEvent: (String) -> String): RunContext? {
    if (env("GITHUB_EVENT_NAME") != "pull_request_target") return null
    val attempt = env("GITHUB_RUN_ATTEMPT")?.toIntOrNull()
    if (attempt == null || attempt < 1) return null

    val repo = requiredEnv(env, "GITHUB_REPOSITORY")
    val actor = requiredEnv(env, "GITHUB_ACTOR")
    val event = readEvent(requiredEnv(env, "GITHUB_EVENT_PATH"))
    return parseContext(event, repo, actor, attempt)
}

private fun requiredEnv(env: (String) -> String?, name: String): String =
    env(name)?.takeIf(String::isNotBlank) ?: throw ReviewFailure(FailureReason.CONFIG)

private fun readEventFile(path: String): String = try {
    Files.readString(Path.of(path))
} catch (_: IOException) {
    throw ReviewFailure(FailureReason.CONFIG)
} catch (_: InvalidPathException) {
    throw ReviewFailure(FailureReason.CONFIG)
}

private fun loadTrustedRules(): String = try {
    RunContext::class.java.getResourceAsStream("/review-prompt.md")
        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        ?.takeIf(String::isNotBlank) ?: throw ReviewFailure(FailureReason.CONFIG)
} catch (_: IOException) {
    throw ReviewFailure(FailureReason.CONFIG)
}

private fun runCli(budget: ReviewBudget): ReviewExecution {
    val env: (String) -> String? = System::getenv
    val ctx = loadRunContext(env, ::readEventFile) ?: return ReviewExecution(Outcome.IGNORED)
    requireActive(budget)
    val profile = configuredProfile(requiredEnv(env, "LLM_MODEL"))
    val token = requiredEnv(env, "GITHUB_TOKEN")
    val apiKey = requiredEnv(env, "OPENAI_API_KEY")
    val rules = loadTrustedRules()
    requireActive(budget)

    val transport = defaultTransport(budget)
    var canClose = true
    return try {
        val github = GitHubClient(ctx.repo, token, transport)
        val llm = OpenAiClient(apiKey, profile, transport)
        ReviewRun(ctx, github, llm, rules, budget).run()
    } catch (failure: TransportCleanupFailure) {
        canClose = false
        throw failure
    } finally {
        if (canClose) transport.close()
    }
}

fun main() {
    val startedAt = System.nanoTime()
    val budget = ReviewBudget(startedAt + CLI_BUDGET.toNanos())
    val execution = try {
        runCli(budget)
    } catch (_: TransportCleanupFailure) {
        System.err.println("ERROR TRANSPORT_CLEANUP")
        exitProcess(1)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        ReviewExecution(Outcome.ERROR, FailureReason.DEADLINE)
    } catch (failure: ReviewFailure) {
        ReviewExecution(Outcome.ERROR, failure.reason, failure.openAiError)
    } catch (_: Exception) {
        ReviewExecution(Outcome.ERROR)
    }

    execution.openAiError?.let { System.err.println(it.logLine()) }
    val interrupted = Thread.currentThread().isInterrupted
    if (execution.outcome == Outcome.ERROR || interrupted) {
        val reason = if (interrupted) FailureReason.DEADLINE else execution.reason
        System.err.println(reason?.let { "ERROR ${it.name}" } ?: "ERROR")
        exitProcess(1)
    }
    println(execution.outcome.name)
}
