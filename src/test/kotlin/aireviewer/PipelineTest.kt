package aireviewer

import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineTest {
    @Test
    fun unauthorizedActorDoesNotReachPrOrModel() {
        for (permission in listOf("triage", "read", "none", "maintain")) {
            val fixture = Fixture(permission = permission)
            assertEquals(Outcome.IGNORED, fixture.run())
            assertEquals(listOf(PERMISSION), fixture.calls)
            assertTrue(fixture.github.comments.isEmpty())
        }
    }

    @Test
    fun permissionCheckFailsWithoutReadingPrOrPosting() {
        for (reason in listOf(FailureReason.HTTP, FailureReason.DEADLINE)) {
            val fixture = Fixture()
            fixture.github.onCall = { if (it == PERMISSION) throw ReviewFailure(reason) }
            assertEquals(Outcome.ERROR, fixture.run())
            assertEquals(listOf(PERMISSION), fixture.calls)
            assertTrue(fixture.github.comments.isEmpty())
        }
    }

    @Test
    fun rerunOrWrongEventIsIgnoredBeforeNetworkOrEventRead() {
        val fixture = Fixture()
        assertEquals(Outcome.IGNORED, fixture.run(context().copy(runAttempt = 2)))
        assertTrue(fixture.calls.isEmpty())

        for (env in listOf(
            mapOf("GITHUB_EVENT_NAME" to "pull_request", "GITHUB_RUN_ATTEMPT" to "1"),
            mapOf("GITHUB_EVENT_NAME" to "pull_request_target", "GITHUB_RUN_ATTEMPT" to "2"),
        )) {
            assertNull(loadRunContext(
                env = { env.getValue(it) },
                readEvent = { error("An ineligible trigger should not read the event file") },
            ))
        }
    }

    @Test
    fun eventPayloadIsCheckedUsingOnlyTriggerEnvironment() {
        val env = mapOf(
            "GITHUB_EVENT_NAME" to "pull_request_target",
            "GITHUB_RUN_ATTEMPT" to "1",
            "GITHUB_REPOSITORY" to REPO,
            "GITHUB_ACTOR" to ACTOR,
            "GITHUB_EVENT_PATH" to "/fake/github-event.json",
        )
        val reads = mutableListOf<String>()
        val load = { action: String ->
            loadRunContext(env = { reads += it; env.getValue(it) }, readEvent = {
                assertEquals("/fake/github-event.json", it)
                event(action)
            })
        }
        assertNull(load("opened"))
        assertEquals(context(), load("labeled"))
        assertEquals(env.keys, reads.toSet())
    }

    @Test
    fun initialPrFailureDoesNotPostADiagnostic() {
        val fixture = Fixture()
        fixture.github.onCall = { if (it == PR) throw ReviewFailure(FailureReason.HTTP) }
        assertEquals(Outcome.ERROR, fixture.run())
        assertEquals(listOf(PERMISSION, PR), fixture.calls)
        assertTrue(fixture.github.comments.isEmpty())
    }

    @Test
    fun initialPrMustStillMatchTheEventAndQualifyForReview() {
        for (pr in listOf(
            state().copy(baseRef = "release"),
            state().copy(headSha = DIFFERENT),
            state().copy(open = false),
            state().copy(draft = true),
            state().copy(labeled = false),
        )) {
            val fixture = Fixture(states = listOf(pr))
            assertEquals(Outcome.STALE, fixture.run())
            assertEquals(listOf(PERMISSION, PR), fixture.calls)
            assertTrue(fixture.github.comments.isEmpty())
        }
    }

    @Test
    fun forkHappyPathVerifiesSourcesAndPublishesOnlyUpstreamImmutableLinks() {
        val fixture = Fixture()
        assertEquals(Outcome.REVIEWED, fixture.run())
        assertEquals(happyCalls(), fixture.calls)
        assertEquals(1, fixture.llm.attempts)
        assertEquals(listOf(RULES), fixture.llm.rules)
        val input = fixture.llm.diffs.single()
        assertEquals(mapOf(1 to "old"), input.files.getValue(PATH).oldText)
        assertEquals(mapOf(1 to "new"), input.files.getValue(PATH).newText)

        val comment = fixture.github.comments.single()
        assertTrue("https://github.com/$REPO/blob/$OLD/$PREVIOUS?plain=1#L1" in comment)
        assertTrue("https://github.com/$REPO/blob/$HEAD/$PATH?plain=1#L1" in comment)
        assertTrue("source=COMPARE" in comment && BASE in comment && OLD in comment && HEAD in comment)
        assertFalse(FORK in comment)
    }

    @Test
    fun deletedForkButReachableSourceCanBeReviewed() {
        val fixture = Fixture(states = List(3) { state(headRepo = null) }, permission = "admin")
        assertEquals(Outcome.REVIEWED, fixture.run())
        assertEquals(happyCalls(), fixture.calls)
        assertTrue("https://github.com/$REPO/blob/$HEAD/" in fixture.github.comments.single())
    }

    @Test
    fun sourceMismatchBeforeModelSkipsBothOldAndNewMismatches() {
        val keys = listOf(OLD to PREVIOUS, HEAD to PATH)
        for (key in keys) {
            val fixture = Fixture(sources = sources() + (key to "unexpected\n".encodeToByteArray()))
            assertFixedDiagnostic(fixture, Outcome.SKIPPED)
            assertEquals(0, fixture.llm.attempts)
            assertTrue(fixture.calls.contains(sourceCall(key.first, key.second)))
        }
    }

    @Test
    fun inaccessibleUpstreamSourceSkipsWithoutAForkFallback() {
        val fixture = Fixture()
        fixture.github.onCall = { if (it == sourceCall(OLD, PREVIOUS)) throw ReviewFailure(FailureReason.SOURCE_UNAVAILABLE) }
        assertFixedDiagnostic(fixture, Outcome.SKIPPED)
        assertEquals(listOf(PERMISSION, PR, COMPARE, sourceCall(OLD, PREVIOUS), PR, COMMENT), fixture.calls)
        assertEquals(0, fixture.llm.attempts)
    }

    @Test
    fun limitsBeforeModelProduceFixedSkipWithoutAPaidCall() {
        val default = snapshot()
        val fixtures = listOf(
            Fixture(states = List(2) { state().copy(changedFiles = 51) }),
            Fixture(snapshot = default.copy(files = (1..51).map { file(path = "$it.kt") })),
            Fixture(snapshot = default.copy(files = listOf(file(patch = "@@ -1 +1 @@\n-old\n+" + "x".repeat(30_001))))),
            Fixture(snapshot = default.copy(files = listOf(file(patch = null)))),
            Fixture(sources = sources() + ((OLD to PREVIOUS) to ByteArray(256 * 1024 + 1))),
        )
        for (fixture in fixtures) {
            assertFixedDiagnostic(fixture, Outcome.SKIPPED)
            assertEquals(0, fixture.llm.attempts)
        }
        assertEquals(listOf(PERMISSION, PR, PR, COMMENT), fixtures.first().calls)
    }

    @Test
    fun aggregateSourceLimitIsAlsoASkipBeforeTheModel() {
        val files = (1..4).map { file(path = "$it.kt", oldPath = null) } +
            ChangedFile("extra.kt", "@@ -0,0 +1 @@\n+x\n\\ No newline at end of file", additions = 1, deletions = 0)
        val old = "old\n".encodeToByteArray().copyOf(256 * 1024).apply { fill('x'.code.toByte(), 4) }
        val new = "new\n".encodeToByteArray().copyOf(256 * 1024).apply { fill('x'.code.toByte(), 4) }
        val sourceData = files.take(4).flatMap { listOf((OLD to it.path) to old, (HEAD to it.path) to new) }.toMap() +
            ((HEAD to "extra.kt") to "x".encodeToByteArray())
        val fixture = Fixture(snapshot = snapshot().copy(files = files), sources = sourceData)
        assertFixedDiagnostic(fixture, Outcome.SKIPPED)
        assertEquals(0, fixture.llm.attempts)
        assertEquals(8, fixture.calls.count { it.startsWith("source:") })
        assertEquals(sourceCall(HEAD, "4.kt"), fixture.calls.dropLast(2).last())
    }

    @Test
    fun positiveRemainingSourceAllowanceReachesTheApiWrapper() {
        val files = (1..4).map { file(path = "$it.kt", oldPath = null) } +
            ChangedFile("extra.kt", "@@ -0,0 +1 @@\n+x\n\\ No newline at end of file", additions = 1, deletions = 0)
        val old = "old\n".encodeToByteArray().copyOf(256 * 1024).apply { fill('x'.code.toByte(), 4) }
        val new = "new\n".encodeToByteArray().copyOf(256 * 1024).apply { fill('x'.code.toByte(), 4) }
        val sourceData = files.take(4).flatMap { listOf((OLD to it.path) to old, (HEAD to it.path) to new) }.toMap() +
            ((HEAD to "4.kt") to new.copyOf(new.size - 1)) + ((HEAD to "extra.kt") to "x".encodeToByteArray())
        val fixture = Fixture(snapshot = snapshot().copy(files = files), sources = sourceData,
            respond = { ReviewResult(emptyList()) })
        assertEquals(Outcome.REVIEWED, fixture.run())
        assertEquals(List(8) { 256 * 1024 } + listOf(1), fixture.github.sourceAllowances)
        assertEquals(1, fixture.llm.attempts)
    }

    @Test
    fun comparisonMustDescribeTheRequestedBaseAndHeadBeforeSourceReads() {
        for (mismatched in listOf(snapshot().copy(baseSha = DIFFERENT), snapshot().copy(headSha = DIFFERENT))) {
            val fixture = Fixture(snapshot = mismatched)
            assertFixedDiagnostic(fixture, Outcome.SKIPPED)
            assertEquals(listOf(PERMISSION, PR, COMPARE, PR, COMMENT), fixture.calls)
            assertEquals(0, fixture.llm.attempts)
        }
    }

    @Test
    fun changedBeforeModelReturnsStaleForEachIdentityOrStateField() {
        for (changed in changedStates()) {
            val fixture = Fixture(states = listOf(state(), changed))
            assertEquals(Outcome.STALE, fixture.run())
            assertEquals(happyCalls().take(6), fixture.calls)
            assertEquals(0, fixture.llm.attempts)
            assertTrue(fixture.github.comments.isEmpty())
        }
    }

    @Test
    fun changedAfterModelReturnsStaleAndNeverPublishesFindings() {
        for (changed in changedStates()) {
            val fixture = Fixture(states = listOf(state(), state(), changed))
            assertEquals(Outcome.STALE, fixture.run())
            assertEquals(happyCalls().dropLast(1), fixture.calls)
            assertEquals(1, fixture.llm.attempts)
            assertTrue(fixture.github.comments.isEmpty())
        }
    }

    @Test
    fun staleInputFailureDoesNotPostASkipComment() {
        val fixture = Fixture(
            states = listOf(state(), state().copy(labeled = false)),
            snapshot = snapshot().copy(files = listOf(file(patch = null))),
        )
        assertEquals(Outcome.STALE, fixture.run())
        assertEquals(listOf(PERMISSION, PR, COMPARE, PR), fixture.calls)
        assertTrue(fixture.github.comments.isEmpty())
    }

    @Test
    fun invalidIncompleteOrLimitModelResponsesAreErrorsAfterOneInvocation() {
        val invalid = {
            ReviewResult(listOf(finding(DiffSide.NEW).copy(startLine = 2, endLine = 2)))
        }
        val failures = listOf<() -> ReviewResult>(
            invalid,
            { throw ReviewFailure(FailureReason.INVALID_RESPONSE) },
            { throw ReviewFailure(FailureReason.LIMIT) },
            { throw IllegalStateException("Provider failure details") },
        )
        for (respond in failures) {
            val fixture = Fixture(respond = respond)
            assertFixedDiagnostic(fixture, Outcome.ERROR)
            assertEquals(1, fixture.llm.attempts)
            assertEquals(happyCalls(), fixture.calls)
        }
    }

    @Test
    fun modelFailureStillRechecksStateAndSuppressesDiagnosticsIfStale() {
        val fixture = Fixture(
            states = listOf(state(), state(), state().copy(headRepo = null)),
            respond = { throw ReviewFailure(FailureReason.INVALID_RESPONSE) },
        )
        assertEquals(Outcome.STALE, fixture.run())
        assertEquals(happyCalls().dropLast(1), fixture.calls)
        assertEquals(1, fixture.llm.attempts)
        assertTrue(fixture.github.comments.isEmpty())
    }

    @Test
    fun rendererOverflowPostsOnlyTheCanonicalErrorAndReturnsError() {
        val longPath = "ë".repeat(700) + ".kt"
        val fixture = Fixture(
            snapshot = snapshot().copy(files = listOf(file(path = longPath, oldPath = null))),
            sources = sources(longPath, longPath),
            respond = { ReviewResult(List(5) {
                finding(DiffSide.NEW).copy(file = longPath, explanation = "detail ".repeat(200))
            }) },
        )
        assertFixedDiagnostic(fixture, Outcome.ERROR)
        assertEquals(1, fixture.llm.attempts)
        assertEquals(COMMENT, fixture.calls.last())
    }

    @Test
    fun expiredGlobalBudgetStopsBeforeEachRemainingRequest() {
        val alreadyExpired = Fixture()
        alreadyExpired.clock.expire()
        assertEquals(Outcome.ERROR, alreadyExpired.run())
        assertTrue(alreadyExpired.calls.isEmpty())

        val expectedStops = listOf(
            COMPARE to 3,
            sourceCall(OLD, PREVIOUS) to 4,
            PR to 6,
            LLM to 7,
            PR to 8,
        )
        for ((stopAt, length) in expectedStops) {
            lateinit var fixture: Fixture
            fixture = Fixture(respond = {
                if (stopAt == LLM) fixture.clock.expire()
                result()
            })
            fixture.github.onCall = { if (it == stopAt && fixture.calls.size == length) fixture.clock.expire() }
            assertEquals(Outcome.ERROR, fixture.run())
            assertEquals(happyCalls().take(length), fixture.calls)
            assertTrue(fixture.github.comments.isEmpty())
        }
    }

    @Test
    fun recheckFailureNeverAttemptsAnotherFetchOrComment() {
        for (read in listOf(2, 3)) {
            val fixture = Fixture()
            fixture.github.onCall = {
                if (it == PR && fixture.calls.count { call -> call == PR } == read) {
                    throw ReviewFailure(FailureReason.HTTP)
                }
            }
            assertEquals(Outcome.ERROR, fixture.run())
            assertEquals(happyCalls().take(if (read == 2) 6 else 8), fixture.calls)
            assertTrue(fixture.github.comments.isEmpty())
        }
    }

    @Test
    fun httpDeadlineOnModelCanPublishAFixedErrorAfterFreshRecheck() {
        val fixture = Fixture(respond = { throw ReviewFailure(FailureReason.DEADLINE) })
        assertFixedDiagnostic(fixture, Outcome.ERROR)
        assertEquals(1, fixture.llm.attempts)
        assertEquals(happyCalls(), fixture.calls)
    }

    @Test
    fun fatalCleanupAbortsWithoutAnotherCall() {
        val beforeModel = Fixture()
        beforeModel.github.onCall = { if (it == COMPARE) throw TransportCleanupFailure() }
        assertFailsWith<TransportCleanupFailure> { beforeModel.run() }
        assertEquals(listOf(PERMISSION, PR, COMPARE), beforeModel.calls)

        val afterModel = Fixture(respond = { throw TransportCleanupFailure() })
        assertFailsWith<TransportCleanupFailure> { afterModel.run() }
        assertEquals(happyCalls().take(7), afterModel.calls)
        assertTrue(beforeModel.github.comments.isEmpty() && afterModel.github.comments.isEmpty())
    }

    @Test
    fun interruptionDuringTheModelPreservesFlagAndPreventsFurtherCalls() {
        for (respond in listOf<() -> ReviewResult>(
            {
                Thread.currentThread().interrupt()
                throw ReviewFailure(FailureReason.DEADLINE)
            },
            { throw InterruptedException() },
            {
                Thread.currentThread().interrupt()
                result()
            },
        )) {
            val fixture = Fixture(respond = respond)
            try {
                assertEquals(Outcome.ERROR, fixture.run())
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(happyCalls().take(7), fixture.calls)
                assertTrue(fixture.github.comments.isEmpty())
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun canceledModelNeverRechecksOrPosts() {
        val fixture = Fixture(respond = { throw CancellationException() })
        assertEquals(Outcome.ERROR, fixture.run())
        assertEquals(happyCalls().take(7), fixture.calls)
        assertTrue(fixture.github.comments.isEmpty())
    }

    @Test
    fun commentFailureIsNeverRetriedIncludingFailureOnTheDiagnosticItself() {
        for (respond in listOf(::result, { throw ReviewFailure(FailureReason.INVALID_RESPONSE) })) {
            val fixture = Fixture(respond = respond)
            fixture.github.onCall = { if (it == COMMENT) throw ReviewFailure(FailureReason.HTTP) }
            assertEquals(Outcome.ERROR, fixture.run())
            assertEquals(happyCalls(), fixture.calls)
            assertEquals(1, fixture.github.comments.size)
        }
    }

    private fun assertFixedDiagnostic(fixture: Fixture, outcome: Outcome) {
        assertEquals(outcome, fixture.run())
        assertEquals(renderComment(context(), outcome, null, null, null), fixture.github.comments.single())
        assertEquals(listOf(PR, COMMENT), fixture.calls.takeLast(2))
    }

    private class Fixture(
        states: List<PullRequestState> = List(3) { state() },
        snapshot: DiffSnapshot = snapshot(),
        sources: Map<Pair<String, String>, ByteArray> = sources(),
        permission: String = "write",
        respond: () -> ReviewResult = ::result,
    ) {
        val clock = FakeClock()
        val calls = mutableListOf<String>()
        val github = FakeGitHub(calls, states, snapshot, sources, permission)
        val llm = CountingLlm(calls, respond)

        fun run(ctx: RunContext = context()) = runReview(ctx, github, llm, RULES, clock.budget)
    }

    private class FakeClock {
        private var now = 0L
        val budget = ReviewBudget(1_000L) { now }

        fun expire() {
            now = 1_000L
        }
    }

    private class FakeGitHub(
        private val calls: MutableList<String>,
        private val states: List<PullRequestState>,
        private val snapshot: DiffSnapshot,
        private val sources: Map<Pair<String, String>, ByteArray>,
        private val permission: String,
    ) : GitHubApi {
        private var read = 0
        val comments = mutableListOf<String>()
        val sourceAllowances = mutableListOf<Int>()
        var onCall: (String) -> Unit = {}

        override fun permission(actor: String): String {
            record("permission:$actor")
            return permission
        }

        override fun pullRequest(number: Int): PullRequestState {
            record("pull:$number")
            return states[read++]
        }

        override fun comparison(baseSha: String, headSha: String): DiffSnapshot {
            record("compare:$baseSha...$headSha")
            return snapshot
        }

        override fun sourceFile(commitSha: String, path: String, maxBytes: Int): SourceFile {
            sourceAllowances += maxBytes
            record(sourceCall(commitSha, path))
            return SourceFile(sources.getValue(commitSha to path))
        }

        override fun comment(number: Int, body: String) {
            comments += body
            record("comment:$number")
        }

        private fun record(call: String) {
            calls += call
            onCall(call)
        }
    }

    private class CountingLlm(private val calls: MutableList<String>, private val respond: () -> ReviewResult) : LlmReviewer {
        val diffs = mutableListOf<PreparedDiff>()
        val rules = mutableListOf<String>()
        val attempts get() = diffs.size

        override fun review(diff: PreparedDiff, rules: String): ReviewResult {
            calls += LLM
            diffs += diff
            this.rules += rules
            return respond()
        }
    }

    private companion object {
        const val REPO = "upstream/project"
        const val FORK = "contributor/private-fork"
        const val ACTOR = "reviewer"
        const val PATH = "src/current.md"
        const val PREVIOUS = "before/previous.md"
        const val RULES = "trusted test instructions"
        const val PERMISSION = "permission:$ACTOR"
        const val PR = "pull:42"
        const val LLM = "llm"
        const val COMMENT = "comment:42"
        val BASE = "a".repeat(40)
        val HEAD = "b".repeat(40)
        val OLD = "c".repeat(40)
        val DIFFERENT = "d".repeat(40)
        val COMPARE = "compare:$BASE...$HEAD"

        fun context() = RunContext(REPO, 42, ACTOR, BASE, HEAD, "main", "work/topic", 1)

        fun state(headRepo: String? = FORK) = PullRequestState(
            BASE, HEAD, "main", "work/topic", headRepo, open = true, draft = false, labeled = true, changedFiles = 1,
        )

        fun changedStates() = listOf(
            state().copy(baseSha = DIFFERENT),
            state().copy(headSha = DIFFERENT),
            state().copy(baseRef = "release"),
            state().copy(headRef = "work/changed"),
            state().copy(headRepo = "contributor/other-fork"),
            state().copy(headRepo = null),
            state().copy(open = false),
            state().copy(draft = true),
            state().copy(labeled = false),
        )

        fun file(path: String = PATH, oldPath: String? = PREVIOUS, patch: String? = "@@ -1 +1 @@\n-old\n+new") =
            ChangedFile(path, patch, oldPath, additions = 1, deletions = 1)

        fun snapshot() = DiffSnapshot(BASE, HEAD, OLD, listOf(file()))

        fun sources(oldPath: String = PREVIOUS, newPath: String = PATH) = mapOf(
            (OLD to oldPath) to "old\n".encodeToByteArray(),
            (HEAD to newPath) to "new\n".encodeToByteArray(),
        )

        fun finding(side: DiffSide) =
            Finding(PATH, "high", "Source regression", "A caller receives changed behavior", ReviewCategory.BUSINESS_LOGIC, side, 1, 1)

        fun result() = ReviewResult(listOf(finding(DiffSide.OLD), finding(DiffSide.NEW)))

        fun sourceCall(sha: String, path: String) = "source:$sha:$path"

        fun happyCalls() = listOf(
            PERMISSION, PR, COMPARE, sourceCall(OLD, PREVIOUS), sourceCall(HEAD, PATH), PR, LLM, PR, COMMENT,
        )

        fun event(action: String) = """
            {"action":"$action","label":{"name":"ai-review"},"number":42,"repository":{"full_name":"$REPO"},
             "sender":{"login":"$ACTOR"},"pull_request":{"state":"open","draft":false,
             "base":{"sha":"$BASE","ref":"main"},"head":{"sha":"$HEAD","ref":"work/topic"}}}
        """.trimIndent()
    }
}
