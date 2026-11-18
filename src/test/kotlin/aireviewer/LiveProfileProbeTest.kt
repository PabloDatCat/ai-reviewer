package aireviewer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LiveProfileProbeTest {
    @Test
    fun eightSyntheticCasesHaveAllRequiredExpectationsAndVerifiableSources() {
        val cases = profileCases().associateBy { it.name }
        assertEquals(setOf(
            "style-only", "business-logic", "backward-compatibility", "race-condition",
            "contract-violation", "error-handling", "safe-guard", "near-limit",
        ), cases.keys)

        val singletonCategories = cases.values.filter { it.name != "near-limit" }
            .flatMap { it.expectations.values }
        assertEquals(ReviewCategory.entries.toSet(), singletonCategories.toSet())
        assertEquals(5, singletonCategories.size)
        for (name in listOf("style-only", "safe-guard")) {
            assertTrue(cases.getValue(name).expectations.isEmpty())
        }

        for (case in cases.values) {
            val diff = case.prepareVerified()
            assertEquals(case.snapshot.files.map { it.path }.toSet(), diff.files.keys)
            assertTrue(diff.files.values.all { file -> file.oldText.values.toList() != file.newText.values.toList() })
        }
    }

    @Test
    fun nearLimitFixtureSupportsFiveValidatedFindingsWithRealSourceAnchors() {
        val case = profileCases().single { it.name == "near-limit" }
        val diff = case.prepareVerified()
        val characters = diff.text.codePointCount(0, diff.text.length)
        assertTrue(characters in 29_000..30_000, "Prepared characters = $characters")
        assertTrue("🚚" in diff.text && characters < diff.text.length)
        assertEquals(5, case.expectations.size)
        assertEquals(ReviewCategory.entries.toSet(), case.expectations.values.toSet())

        val result = ReviewResult(case.expectations.map { (path, category) ->
            val file = diff.files.getValue(path)
            val start = file.newText.entries.first { (line, text) -> file.oldText[line] != text }.key
            Finding(path, "medium", "Синтетическое замечание", "Достижимый тестовый случай", category, DiffSide.NEW, start, start)
        })
        assertEquals(result, validateResult(result, diff))
        val comment = renderComment(profileProbeContext, Outcome.REVIEWED, result, diff, case.snapshot)
        assertNotEquals(renderComment(profileProbeContext, Outcome.ERROR, null, null, null), comment)
        for (finding in result.findings) {
            val link = "https://github.com/${profileProbeContext.repo}/blob/${profileProbeContext.headSha}/${finding.file}#L${finding.startLine}"
            assertTrue(link in comment)
        }
    }
}
