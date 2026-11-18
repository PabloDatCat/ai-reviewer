package aireviewer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SnapshotVerifierTest {
    @Test
    fun coherentSnapshotReadsEveryOldAndNewLineFromItsActualCommit() {
        val snapshot = fixtureSnapshot()
        val sources = FakeSources(oldBytes = "old\nsame\n".toByteArray(), newBytes = "new\nsame\n".toByteArray())
        verifyPatchText(prepareDiff(snapshot.files), snapshot, sources)
        assertEquals(listOf(OLD to "a.kt", HEAD to "a.kt"), sources.calls)
    }

    @Test
    fun rejectsWrongOldTextAtValidLineNumber() {
        val diff = PreparedDiff("numbered patch", mapOf("a.kt" to
            FileLines("a.kt", mapOf(1 to "deleted"), mapOf(1 to "new"))))
        val sources = FakeSources(oldBytes = "different\n".toByteArray(), newBytes = "new\n".toByteArray())
        assertFailure(FailureReason.SOURCE_MISMATCH, diff, fixtureSnapshot(), sources)
    }

    @Test
    fun cachedPatchAfterBaseChangeCannotUseOldTextAtStillValidCoordinates() {
        val snapshot = fixtureSnapshot()
        val sources = FakeSources(mapOf(
            BASE to "a.kt" to "old\nsame\n".toByteArray(),
            OLD to "a.kt" to "new merge-base text\nsame\n".toByteArray(),
            HEAD to "a.kt" to "new\nsame\n".toByteArray(),
        ))
        assertFailure(FailureReason.SOURCE_MISMATCH, prepareDiff(snapshot.files), snapshot, sources)
        assertEquals(listOf(OLD to "a.kt"), sources.calls)
    }

    @Test
    fun wrongContextLineFailsEvenWhenTheChangedLinesMatch() {
        checkSources("old\nsame\n".toByteArray(), "new\ndifferent\n".toByteArray(), FailureReason.SOURCE_MISMATCH)
    }

    @Test
    fun rejectsPatchMissingACompleteSecondHunkEvenWhenPinnedSourcesMatch() {
        val file = ChangedFile("a.kt", "@@ -1 +1 @@\n-first-old\n+first-new", additions = 2, deletions = 2)
        val snapshot = fixtureSnapshot(listOf(file))
        val sources = FakeSources(
            "first-old\nsame\nlater-old\n".toByteArray(),
            "first-new\nsame\nlater-new\n".toByteArray(),
        )
        assertEquals(FailureReason.INVALID_PATCH, assertFailsWith<ReviewFailure> {
            verifyPatchText(prepareDiff(snapshot.files), snapshot, sources)
        }.reason)
        assertEquals(emptyList(), sources.calls)
    }

    @Test
    fun rejectsAMissingInsertionBeforeTheFirstRepresentedHunk() {
        assertInconsistentHunkGap(
            "@@ -3 +4 @@\n-before\n+after",
            "top\nsame\nbefore\n",
            "inserted\ntop\nsame\nafter\n",
            representedChanges = 1,
        )
    }

    @Test
    fun rejectsAMissingInsertionBetweenRepresentedHunks() {
        assertInconsistentHunkGap(
            "@@ -1 +1 @@\n-original\n+updated\n@@ -3 +4 @@\n-before\n+after",
            "original\nsame\nbefore\n",
            "updated\ninserted\nsame\nafter\n",
            representedChanges = 2,
        )
    }

    @Test
    fun renamedAndDeletedSourcesUseExactPathsAndSkipAbsentSides() {
        val files = listOf(
            ChangedFile("src/renamed.kt", "@@ -1 +1 @@\n-old\n+new", "old/path.kt", additions = 1, deletions = 1),
            ChangedFile("src/deleted.kt", "@@ -1 +0,0 @@\n-gone", additions = 0, deletions = 1),
            ChangedFile("new.kt", "@@ -0,0 +1 @@\n+added", additions = 1, deletions = 0),
        )
        val snapshot = fixtureSnapshot(files)
        val sources = FakeSources(mapOf(
            OLD to "old/path.kt" to "old\n".toByteArray(),
            HEAD to "src/renamed.kt" to "new\n".toByteArray(),
            OLD to "src/deleted.kt" to "gone\n".toByteArray(),
            HEAD to "new.kt" to "added\n".toByteArray(),
        ))
        verifyPatchText(prepareDiff(files), snapshot, sources)
        assertEquals(
            listOf(OLD to "old/path.kt", HEAD to "src/renamed.kt", OLD to "src/deleted.kt", HEAD to "new.kt"),
            sources.calls,
        )
    }

    @Test
    fun boundsEachDecodedSourceByBytes() {
        val old = ("old\nsame\nx" + "é".repeat((256 * 1024 - 10) / 2)).toByteArray()
        val new = "new\nsame\n".toByteArray()
        checkSources(old, new)
        checkSources(old + 'x'.code.toByte(), new, FailureReason.LIMIT)
    }

    @Test
    fun boundsTheTotalDecodedSourceBytes() {
        val files = (1..4).map { ChangedFile("$it.kt", "@@ -1 +1 @@\n-old\n+new", additions = 1, deletions = 1) }
        val old = ("old\n" + "x".repeat(256 * 1024 - 4)).toByteArray()
        val new = ("new\n" + "x".repeat(256 * 1024 - 4)).toByteArray()
        val available = files.flatMap { file -> listOf(OLD to file.path to old, HEAD to file.path to new) }.toMap()
        val exactlyAtLimit = FakeSources(available)
        verifyPatchText(prepareDiff(files), fixtureSnapshot(files), exactlyAtLimit)
        assertEquals(8, exactlyAtLimit.calls.size)

        val additional = files + ChangedFile(
            "over.txt", "@@ -0,0 +1 @@\n+x\n\\ No newline at end of file", additions = 1, deletions = 0,
        )
        val exceeded = FakeSources(available + (HEAD to "over.txt" to "x".toByteArray()))
        assertFailure(
            FailureReason.LIMIT,
            prepareDiff(additional),
            fixtureSnapshot(additional),
            exceeded,
        )
        assertEquals(exactlyAtLimit.calls, exceeded.calls)
    }

    @Test
    fun rejectsTheEntireBlobIfItContainsInvalidUtf8OutsideTheHunk() {
        val invalid = "old\nsame\n".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)
        checkSources(invalid, "new\nsame\n".toByteArray(), FailureReason.SOURCE_MISMATCH)
    }

    @Test
    fun missingSourceIsReportedAsUnavailable() {
        val snapshot = fixtureSnapshot()
        assertFailure(
            FailureReason.SOURCE_UNAVAILABLE, prepareDiff(snapshot.files), snapshot,
            FakeSources(mapOf(HEAD to "a.kt" to "new\nsame\n".toByteArray())),
        )
    }

    @Test
    fun doesNotNormalizeWhitespaceCarriageReturnsOrAPhantomFinalLine() {
        for (source in listOf("old\n", "old \nsame\n", "old\r\nsame\n", "old\nsame")) {
            checkSources(source.toByteArray(), "new\nsame\n".toByteArray(), FailureReason.SOURCE_MISMATCH)
        }
        val file = ChangedFile("empty-line.kt", "@@ -2 +2 @@\n-\n+new", additions = 1, deletions = 1)
        assertFailure(
            FailureReason.SOURCE_MISMATCH, prepareDiff(listOf(file)), fixtureSnapshot(listOf(file)),
            FakeSources(mapOf(OLD to file.path to "one\n".toByteArray(), HEAD to file.path to "one\nnew\n".toByteArray())),
        )
    }

    @Test
    fun requiresTheFinalLfMarkerToMatchTheVerifiedSourceExactly() {
        val file = ChangedFile(
            "a.kt", "@@ -1 +1 @@\n-old\r\n\\ No newline at end of file\n+new\r", additions = 1, deletions = 1,
        )
        val snapshot = fixtureSnapshot(listOf(file))
        val diff = prepareDiff(snapshot.files)
        verifyPatchText(diff, snapshot, FakeSources("old\r".toByteArray(), "new\r\n".toByteArray()))
        assertFailure(FailureReason.SOURCE_MISMATCH, diff, snapshot,
            FakeSources("old\r\n".toByteArray(), "new\r\n".toByteArray()))
        assertFailure(FailureReason.SOURCE_MISMATCH, diff, snapshot,
            FakeSources("old\r".toByteArray(), "new\r".toByteArray()))
    }

    private fun checkSources(old: ByteArray, new: ByteArray, reason: FailureReason? = null) {
        val snapshot = fixtureSnapshot()
        val diff = prepareDiff(snapshot.files)
        val sources = FakeSources(old, new)
        if (reason == null) {
            verifyPatchText(diff, snapshot, sources)
        } else {
            assertFailure(reason, diff, snapshot, sources)
        }
    }

    private fun assertInconsistentHunkGap(patch: String, old: String, new: String, representedChanges: Int) {
        // Match the reported totals to the visible operations so the inconsistent coordinates alone reject the patch.
        val file = ChangedFile("a.kt", patch, additions = representedChanges, deletions = representedChanges)
        val snapshot = fixtureSnapshot(listOf(file))
        val sources = FakeSources(old.toByteArray(), new.toByteArray())
        assertEquals(FailureReason.INVALID_PATCH, assertFailsWith<ReviewFailure> {
            verifyPatchText(prepareDiff(snapshot.files), snapshot, sources)
        }.reason)
        assertEquals(emptyList(), sources.calls)
    }

    private fun assertFailure(reason: FailureReason, diff: PreparedDiff, snapshot: DiffSnapshot, sources: FakeSources) {
        assertEquals(reason, assertFailsWith<ReviewFailure> { verifyPatchText(diff, snapshot, sources) }.reason)
    }

    private fun fixtureSnapshot(files: List<ChangedFile> = listOf(
        ChangedFile("a.kt", "@@ -1,2 +1,2 @@\n-old\n+new\n same", additions = 1, deletions = 1),
    )) = DiffSnapshot(BASE, HEAD, OLD, files)

    private class FakeSources(private val available: Map<Pair<String, String>, ByteArray>) : GitHubApi {
        constructor(oldBytes: ByteArray, newBytes: ByteArray) : this(mapOf(
            OLD to "a.kt" to oldBytes,
            HEAD to "a.kt" to newBytes,
        ))

        val calls = mutableListOf<Pair<String, String>>()

        override fun sourceFile(commitSha: String, path: String, maxBytes: Int): SourceFile {
            val key = commitSha to path
            calls += key
            return SourceFile(available[key] ?: throw ReviewFailure(FailureReason.SOURCE_UNAVAILABLE))
        }

        override fun permission(actor: String): String = error("Unexpected permission API call")
        override fun pullRequest(number: Int): PullRequestState = error("Unexpected PR API call")
        override fun comparison(baseSha: String, headSha: String): DiffSnapshot = error("Unexpected compare API call")
        override fun comment(number: Int, body: String): Unit = error("Unexpected comment API call")
    }

    private companion object {
        val BASE = "a".repeat(40)
        val HEAD = "b".repeat(40)
        val OLD = "c".repeat(40)
    }
}
