package aireviewer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewTest {
    @Test
    fun contextGateAcceptsAnOpenLabeledForkPullRequest() {
        assertEquals(
            RunContext(REPO, 42, "reviewer", BASE, HEAD, "main", "fork/work", 1),
            parseContext(event(), REPO, "reviewer", 1),
        )
        assertNotNull(parseContext(event(headRepo = "null"), REPO, "reviewer", 1))
    }

    @Test
    fun contextGateRejectsIneligibleOrMalformedEvents() {
        val invalid = listOf(
            event(action = "opened"),
            event(label = "bug"),
            event(repo = "another/project"),
            event(sender = "someone-else"),
            event(state = "closed"),
            event(draft = "true"),
            event(draft = "\"false\""),
            event(number = "0"),
            event(number = "\"42\""),
            event(headSha = "abcdef"),
            """{"action":"labeled"}""",
            "[]",
            "{",
        )
        for (ineligible in invalid) {
            assertNull(parseContext(ineligible, REPO, "reviewer", 1), ineligible)
        }
        assertNull(parseContext(event(), REPO, "reviewer", 0))
        assertNull(parseContext(event(), REPO, "reviewer", -1))
    }

    @Test
    fun contextGateAcceptsManualReruns() {
        for (attempt in listOf(2, 3)) {
            assertEquals(
                RunContext(REPO, 42, "reviewer", BASE, HEAD, "main", "fork/work", attempt),
                parseContext(event(), REPO, "reviewer", attempt),
            )
        }
    }

    @Test
    fun tracksBothSides() {
        val file = ChangedFile("a.kt", "@@ -10,2 +10,2 @@\n-old\n+new\n context", additions = 1, deletions = 1)
        val diff = prepareDiff(listOf(file))
        assertEquals(mapOf(10 to "old", 11 to "context"), diff.files.getValue("a.kt").oldText)
        assertEquals(mapOf(10 to "new", 11 to "context"), diff.files.getValue("a.kt").newText)
        assertTrue("OLD:10" in diff.text)
        assertTrue("NEW:10" in diff.text)
        assertTrue("OLD:11 NEW:11" in diff.text)
    }

    @Test
    fun boundsFileCountAndRejectsDuplicatePathsEvenForDistinctSources() {
        val files = (1..50).map { ChangedFile("$it.kt", replacement(), additions = 1, deletions = 1) }
        assertEquals(50, prepareDiff(files).files.size)
        assertPreparationFailure(FailureReason.LIMIT, files + ChangedFile("51.kt", replacement(), additions = 1, deletions = 1))
        assertPreparationFailure(FailureReason.LIMIT, emptyList())
        assertPreparationFailure(
            FailureReason.INVALID_PATCH,
            files.dropLast(1) + files.first().copy(previousPath = "other.kt"),
        )
    }

    @Test
    fun boundsPreparedTextIncludingFileAndLineMarkup() {
        val file = ChangedFile("a.kt", replacement(old = "", new = ""), additions = 1, deletions = 1)
        val markupLength = prepareDiff(listOf(file)).text.length
        for (character in listOf("x", "🚚")) {
            val contentAtLimit = character.repeat(30_000 - markupLength)
            val atLimit = prepareDiff(listOf(file.copy(patch = replacement(old = "", new = contentAtLimit))))
            assertEquals(30_000, atLimit.text.codePointCount(0, atLimit.text.length))
            assertEquals(contentAtLimit, atLimit.files.getValue("a.kt").newText.getValue(1))
            assertPreparationFailure(
                FailureReason.LIMIT,
                listOf(file.copy(patch = replacement(old = "", new = contentAtLimit + character))),
            )
        }
    }

    @Test
    fun refusesMissingOrEmptyPatches() {
        for (patch in listOf(null, "")) {
            val file = ChangedFile("a.kt", patch, additions = 1, deletions = 1)
            assertPreparationFailure(FailureReason.PATCH_UNAVAILABLE, listOf(file))
        }
    }

    @Test
    fun rejectsInconsistentOrNegativeCompareTotals() {
        val file = ChangedFile("a.kt", replacement(), additions = 1, deletions = 1)
        for (invalid in listOf(
            file.copy(additions = 0), file.copy(deletions = 0),
            file.copy(additions = -1), file.copy(deletions = -1),
        )) {
            assertPreparationFailure(FailureReason.INVALID_PATCH, listOf(invalid))
        }
    }

    @Test
    fun acceptsZeroCountHunksAndPureAdditionOrDeletion() {
        val diff = prepareDiff(listOf(
            ChangedFile("inserted.kt", "@@ -0,0 +1,2 @@\n+one\n+two\n", additions = 2, deletions = 0),
            ChangedFile("removed.kt", "@@ -1 +0,0 @@\n-one", additions = 0, deletions = 1),
            ChangedFile("split.kt", "@@ -1 +1 @@\n-one\n+two\n@@ -2,0 +3 @@\n+three", additions = 2, deletions = 1),
        ))
        assertEquals(emptyMap(), diff.files.getValue("inserted.kt").oldText)
        assertEquals(mapOf(1 to "one", 2 to "two"), diff.files.getValue("inserted.kt").newText)
        assertEquals(mapOf(1 to "one"), diff.files.getValue("removed.kt").oldText)
        assertEquals(emptyMap(), diff.files.getValue("removed.kt").newText)
        assertEquals(mapOf(1 to "two", 3 to "three"), diff.files.getValue("split.kt").newText)
    }

    @Test
    fun keepsCarriageReturnsAndMarksTheSideWithoutFinalLf() {
        val patch = "@@ -4,2 +4,2 @@\r\n context\r\n-old\r\n\\ No newline at end of file\r\n+new\r\n"
        val file = ChangedFile("a.kt", patch, "previous.kt", additions = 1, deletions = 1)
        val hunk = prepareDiff(listOf(file)).files.getValue("a.kt")
        assertEquals("previous.kt", hunk.oldPath)
        assertEquals(mapOf(4 to "context\r", 5 to "old\r"), hunk.oldText)
        assertEquals(mapOf(4 to "context\r", 5 to "new\r"), hunk.newText)
        assertEquals(setOf(5), hunk.oldNoFinalLf)
        assertEquals(emptySet(), hunk.newNoFinalLf)
    }

    @Test
    fun marksUnterminatedContextOnBothSides() {
        val patch = "@@ -1,2 +1,2 @@\n-old\n+new\n same\n\\ No newline at end of file"
        val hunk = prepareDiff(listOf(ChangedFile("a.kt", patch, additions = 1, deletions = 1))).files.getValue("a.kt")
        assertEquals(setOf(2), hunk.oldNoFinalLf)
        assertEquals(setOf(2), hunk.newNoFinalLf)
    }

    @Test
    fun rejectsTruncatedOverlappingOrMalformedHunks() {
        val invalid = listOf(
            "@@ -1,2 +1,2 @@\n-old\n+new",
            "@@ -1 +1 @@\n-old\n+new\n+extra",
            "@@ -1 +1 @@\n-old\n+new\n@@ -1 +2 @@\n-one\n+two",
            "@@ -1 +1 @@\n-old\n+new\n@@ -2 +1 @@\n-one\n+two",
            "@@ -1 +1 @@\n-old\n+new\n@@ -0,0 +2 @@\n+before-old-first-line",
            "@@ -0 +1 @@\n-old\n+new",
            "@@ -2147483647,2 +1 @@\n-old\n-extra\n+new",
            "@@ -0,0 +0,0 @@",
            "@@ -1 +1 @@\n unchanged",
            "@@ -1 +1 @@\n\\ No newline at end of file\n-old\n+new",
            "@@ -1 +1 @@\n-old\n\\ No newline at end of file\n\\ No newline at end of file\n+new",
            "@@ -1,2 +1,2 @@\n-old\n\\ No newline at end of file\n+new\n context",
            "binary files differ",
        )
        for (patch in invalid) {
            val file = ChangedFile("a.kt", patch, additions = 1, deletions = 1)
            assertPreparationFailure(FailureReason.INVALID_PATCH, listOf(file))
        }
    }

    @Test
    fun acceptsFindingsOnBothSidesIncludingContext() {
        val diff = findingDiff()
        val findings = listOf(
            finding(side = DiffSide.OLD, start = 1, end = 2),
            finding(side = DiffSide.NEW, start = 2, end = 2),
        )
        assertEquals(ReviewResult(findings), validateResult(ReviewResult(findings), diff))
        assertEquals(ReviewResult(emptyList()), validateResult(ReviewResult(emptyList()), diff))
    }

    @Test
    fun rejectsInvalidFindingCoordinatesAndRangesCrossingHunkGaps() {
        val diff = findingDiff()
        val invalid = listOf(
            finding(start = 0, end = 1),
            finding(start = 2, end = 1),
            finding(start = 2, end = 5),
            finding(start = 5, end = 10),
            finding(start = Int.MAX_VALUE, end = Int.MAX_VALUE),
            finding(file = "unknown.kt"),
        )
        for (badFinding in invalid) {
            assertInvalidResult(ReviewResult(listOf(badFinding)), diff)
        }
        assertEquals(
            ReviewResult(listOf(finding(start = 5, end = 9))),
            validateResult(ReviewResult(listOf(finding(start = 5, end = 9))), diff),
        )
    }

    @Test
    fun boundsFindingCountAndTextSizesAndRejectsUnknownSeverity() {
        val diff = findingDiff()
        val largest = finding(title = "t".repeat(120), explanation = "e".repeat(1_500))
        val allowed = ReviewResult(List(5) { largest })
        assertEquals(allowed, validateResult(allowed, diff))
        assertInvalidResult(ReviewResult(List(6) { largest }), diff)
        for (badFinding in listOf(
            largest.copy(title = ""),
            largest.copy(title = "t".repeat(121)),
            largest.copy(explanation = ""),
            largest.copy(explanation = "e".repeat(1_501)),
            largest.copy(severity = "low"),
        )) {
            assertInvalidResult(ReviewResult(listOf(badFinding)), diff)
        }
        assertEquals(
            setOf("BUSINESS_LOGIC", "BACKWARD_COMPATIBILITY", "RACE_CONDITION", "CONTRACT_VIOLATION", "ERROR_HANDLING"),
            ReviewCategory.entries.map { it.name }.toSet(),
        )
    }

    @Test
    fun countsSupplementaryCodePointsAtFindingFieldLimits() {
        val diff = findingDiff()
        val largest = finding(title = "t".repeat(119) + "🚚", explanation = "e".repeat(1_499) + "🚚")
        val result = ReviewResult(listOf(largest))
        assertEquals(result, validateResult(result, diff))
        assertInvalidResult(ReviewResult(listOf(largest.copy(title = largest.title + "🚚"))), diff)
        assertInvalidResult(ReviewResult(listOf(largest.copy(explanation = largest.explanation + "🚚"))), diff)
    }

    @Test
    fun upstreamLinksUseTheOldAndHeadShasAndFollowRenames() {
        val file = ChangedFile("src/new.kt", replacement(), "previous/old.json", additions = 1, deletions = 1)
        val deleted = ChangedFile("deleted.kt", "@@ -1 +0,0 @@\n-gone", additions = 0, deletions = 1)
        val diff = prepareDiff(listOf(file, deleted))
        val snapshot = DiffSnapshot(BASE, HEAD, OLD, listOf(file, deleted))
        val forkContext = assertNotNull(parseContext(event(), REPO, "reviewer", 1))
        assertEquals(
            "https://github.com/$REPO/blob/$OLD/previous/old.json#L1",
            codeLink(forkContext, snapshot, finding(file = file.path, side = DiffSide.OLD), diff),
        )
        assertEquals(
            "https://github.com/$REPO/blob/$HEAD/src/new.kt#L1",
            codeLink(forkContext, snapshot, finding(file = file.path), diff),
        )
        assertEquals(
            "https://github.com/$REPO/blob/$OLD/deleted.kt#L1",
            codeLink(forkContext, snapshot, finding(file = "deleted.kt", side = DiffSide.OLD), diff),
        )
    }

    @Test
    fun markdownSourceLinksUsePlainViewBeforeLineAnchors() {
        for (extension in listOf("md", "MD", "markdown", "mdown", "mkd")) {
            val file = ChangedFile(
                "docs/new.$extension", "@@ -1,2 +1,2 @@\n-old\n+new\n context", "old/intro.MD",
                additions = 1, deletions = 1,
            )
            val diff = prepareDiff(listOf(file))
            val snapshot = DiffSnapshot(BASE, HEAD, OLD, listOf(file))
            assertEquals(
                "https://github.com/$REPO/blob/$OLD/old/intro.MD?plain=1#L1-L2",
                codeLink(context(), snapshot, finding(file = file.path, side = DiffSide.OLD, end = 2), diff),
            )
            assertEquals(
                "https://github.com/$REPO/blob/$HEAD/docs/new.$extension?plain=1#L2",
                codeLink(context(), snapshot, finding(file = file.path, start = 2, end = 2), diff),
            )
        }
    }

    @Test
    fun escapesEachPathSegmentWithoutChangingDirectorySeparators() {
        val file = ChangedFile("docs/é #? (guide)[1]*~.md", replacement(), additions = 1, deletions = 1)
        val diff = prepareDiff(listOf(file))
        assertEquals(
            "https://github.com/$REPO/blob/$HEAD/docs/%C3%A9%20%23%3F%20%28guide%29%5B1%5D%2A~.md?plain=1#L1",
            codeLink(context(), DiffSnapshot(BASE, HEAD, OLD, listOf(file)), finding(file = file.path), diff),
        )
    }

    @Test
    fun placesEveryVerifiedSourceLinkOutsideModelTextFences() {
        val diff = findingDiff()
        val snapshot = findingSnapshot()
        val findings = listOf(
            finding(side = DiffSide.OLD, title = "attack \u0060\u0060\u0060", explanation = "@team [bad](https://evil.test)\u0000"),
            finding(side = DiffSide.NEW, title = "other finding", start = 2, end = 2),
        )
        val body = renderComment(context(), Outcome.REVIEWED, ReviewResult(findings), diff, snapshot)
        val modelText = body.split("\u0060\u0060\u0060")
        assertEquals(5, modelText.size)
        assertTrue("attack" in modelText[1] && "https://evil.test" in modelText[1])
        assertTrue("other finding" in modelText[3])
        assertFalse('@' in body || '\u0000' in body)
        assertTrue("](https://github.com/$REPO/blob/$OLD/a.kt#L1)" in modelText[2])
        assertTrue("](https://github.com/$REPO/blob/$HEAD/a.kt#L2)" in modelText[4])
        assertTrue("source=COMPARE" in body && BASE in body && HEAD in body && OLD in body)
        assertTrue("Файлов: 1." in body && "тесты не запускались" in body)
    }

    @Test
    fun emptyReviewReportsCoverageAndAQualifiedResult() {
        val body = renderComment(
            context(), Outcome.REVIEWED, ReviewResult(emptyList()), findingDiff(),
            findingSnapshot(),
        )
        assertTrue("В переданном diff существенных проблем не обнаружено" in body)
        assertTrue("source=COMPARE" in body && "тесты не запускались" in body)
    }

    @Test
    fun staleAndErrorCommentsSuppressFindingsAndLinks() {
        val result = ReviewResult(listOf(finding(title = "withheld-marker")))
        val snapshot = findingSnapshot()
        val diff = findingDiff()
        for (outcome in listOf(Outcome.STALE, Outcome.ERROR)) {
            val body = renderComment(context(), outcome, result, diff, snapshot)
            assertEquals(renderComment(context(), outcome, null, null, null), body)
            assertFalse("withheld-marker" in body || "github.com" in body || "не обнаружено" in body)
        }
    }

    @Test
    fun oversizedCommentEqualsCanonicalErrorAndLeaksNoFindings() {
        val path = "ë".repeat(700) + ".kt"
        val file = ChangedFile(path, replacement(), additions = 1, deletions = 1)
        val diff = prepareDiff(listOf(file))
        val result = ReviewResult(List(5) {
            finding(file = path, title = "withheld-marker", explanation = "a".repeat(1_500))
        })
        val snapshot = DiffSnapshot(BASE, HEAD, OLD, listOf(file))
        val body = renderComment(context(), Outcome.REVIEWED, result, diff, snapshot)
        assertEquals(renderComment(context(), Outcome.ERROR, null, null, null), body)
        assertFalse("withheld-marker" in body || "github.com" in body)
    }

    @Test
    fun commentLengthCountsSupplementaryCodePoints() {
        val explanation = "🚚".repeat(1_500)
        val result = ReviewResult(List(5) { finding(explanation = explanation) })
        val body = renderComment(context(), Outcome.REVIEWED, result, findingDiff(), findingSnapshot())
        assertTrue(explanation in body)
        assertTrue(body.length > 12_000 && body.codePointCount(0, body.length) <= 12_000)
    }

    private fun assertPreparationFailure(reason: FailureReason, files: List<ChangedFile>) {
        assertEquals(reason, assertFailsWith<ReviewFailure> { prepareDiff(files) }.reason)
    }

    private fun assertInvalidResult(result: ReviewResult, diff: PreparedDiff) {
        assertEquals(FailureReason.INVALID_RESPONSE, assertFailsWith<ReviewFailure> {
            validateResult(result, diff)
        }.reason)
    }

    private fun findingSnapshot() = DiffSnapshot(BASE, HEAD, OLD, listOf(ChangedFile(
        "a.kt",
        "@@ -1,2 +1,2 @@\n-old\n+new\n context\n@@ -5,6 +5,6 @@\n-previous\n+next\n a\n b\n c\n d\n e",
        additions = 2,
        deletions = 2,
    )))

    private fun findingDiff() = prepareDiff(findingSnapshot().files)

    private fun finding(
        file: String = "a.kt",
        side: DiffSide = DiffSide.NEW,
        start: Int = 1,
        end: Int = 1,
        title: String = "title",
        explanation: String = "explanation",
    ) = Finding(file, "high", title, explanation, ReviewCategory.BUSINESS_LOGIC, side, start, end)

    private fun replacement(old: String = "old", new: String = "new") = "@@ -1 +1 @@\n-$old\n+$new"
    private fun context() = RunContext(REPO, 42, "reviewer", BASE, HEAD, "main", "fork/work", 1)

    private fun event(
        action: String = "labeled",
        label: String = "ai-review",
        repo: String = REPO,
        sender: String = "reviewer",
        state: String = "open",
        draft: String = "false",
        number: String = "42",
        headSha: String = HEAD,
        headRepo: String = """{"full_name":"private-fork/project"}""",
    ) = """
        {
          "action": "$action",
          "label": {"name": "$label"},
          "repository": {"full_name": "$repo"},
          "sender": {"login": "$sender"},
          "number": $number,
          "pull_request": {
            "state": "$state",
            "draft": $draft,
            "base": {"sha": "$BASE", "ref": "main", "repo": {"full_name": "$REPO"}},
            "head": {"sha": "$headSha", "ref": "fork/work", "repo": $headRepo}
          }
        }
    """.trimIndent()

    private companion object {
        const val REPO = "upstream/project"
        val BASE = "a".repeat(40)
        val HEAD = "b".repeat(40)
        val OLD = "c".repeat(40)
    }
}
