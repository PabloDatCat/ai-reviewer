package aireviewer

import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets.UTF_8
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

data class RunContext(
    val repo: String,
    val number: Int,
    val actor: String,
    val baseSha: String,
    val headSha: String,
    val baseRef: String,
    val headRef: String,
    val runAttempt: Int,
)

data class PullRequestState(
    val baseSha: String,
    val headSha: String,
    val baseRef: String,
    val headRef: String,
    val headRepo: String?,
    val open: Boolean,
    val draft: Boolean,
    val labeled: Boolean,
    val changedFiles: Int,
)

data class ChangedFile(
    val path: String,
    val patch: String?,
    val previousPath: String? = null,
    val additions: Int,
    val deletions: Int,
)
data class DiffSnapshot(val baseSha: String, val headSha: String, val oldSha: String, val files: List<ChangedFile>)

enum class DiffSide { OLD, NEW }

enum class ReviewCategory {
    BUSINESS_LOGIC, BACKWARD_COMPATIBILITY, RACE_CONDITION, CONTRACT_VIOLATION, ERROR_HANDLING,
}

data class FileLines(
    val oldPath: String,
    val oldText: Map<Int, String>,
    val newText: Map<Int, String>,
    val oldNoFinalLf: Set<Int> = emptySet(),
    val newNoFinalLf: Set<Int> = emptySet(),
)

data class PreparedDiff(val text: String, val files: Map<String, FileLines>)
data class SourceFile(val bytes: ByteArray)

data class Finding(
    val file: String,
    val severity: String,
    val title: String,
    val explanation: String,
    val category: ReviewCategory,
    val side: DiffSide,
    val startLine: Int,
    val endLine: Int,
)

data class ReviewResult(val findings: List<Finding>)

enum class Outcome { REVIEWED, IGNORED, SKIPPED, STALE, ERROR }

enum class FailureReason {
    LIMIT, PATCH_UNAVAILABLE, INVALID_PATCH, SOURCE_MISMATCH, SOURCE_UNAVAILABLE,
    HTTP, DEADLINE, OPENAI, INVALID_RESPONSE, CONFIG,
}

class ReviewFailure(
    val reason: FailureReason,
    val openAiError: OpenAiErrorDetails? = null,
) : RuntimeException(reason.name)

interface GitHubApi {
    fun permission(actor: String): String
    fun pullRequest(number: Int): PullRequestState
    fun comparison(baseSha: String, headSha: String): DiffSnapshot
    fun sourceFile(commitSha: String, path: String, maxBytes: Int = 256 * 1024): SourceFile
    fun comment(number: Int, body: String)
}

fun interface LlmReviewer {
    fun review(diff: PreparedDiff, rules: String): ReviewResult
}

fun interface HttpTransport {
    fun send(request: HttpRequest): HttpReply
}

data class HttpReply(val status: Int, val body: String, val headers: Map<String, List<String>> = emptyMap())

private val repoName = Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")
private val fullSha = Regex("[0-9a-fA-F]{40}")
private const val MAX_FILES = 50
private const val MAX_DIFF_LENGTH = 30_000
private const val MAX_COMMENT_LENGTH = 12_000

fun parseContext(event: String, repo: String, actor: String, attempt: Int): RunContext? {
    if (attempt < 1 || !validRepo(repo) || actor.isBlank()) return null
    val body = try {
        Json.parseToJsonElement(event) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return null

    if (body.string("action") != "labeled" || body.obj("label")?.string("name") != "ai-review") return null
    if (body.obj("repository")?.string("full_name") != repo || body.obj("sender")?.string("login") != actor) {
        return null
    }

    val number = body.literal("number")?.intOrNull?.takeIf { it > 0 } ?: return null
    val pr = body.obj("pull_request") ?: return null
    if (pr.string("state") != "open" || pr.literal("draft")?.booleanOrNull != false) return null

    val base = pr.obj("base") ?: return null
    val head = pr.obj("head") ?: return null
    val baseSha = base.string("sha")?.takeIf(fullSha::matches) ?: return null
    val headSha = head.string("sha")?.takeIf(fullSha::matches) ?: return null
    val baseRef = base.string("ref")?.takeIf(String::isNotBlank) ?: return null
    val headRef = head.string("ref")?.takeIf(String::isNotBlank) ?: return null
    return RunContext(repo, number, actor, baseSha, headSha, baseRef, headRef, attempt)
}

private fun JsonObject.obj(key: String) = this[key] as? JsonObject

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.literal(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }

fun prepareDiff(files: List<ChangedFile>): PreparedDiff {
    if (files.size !in 1..MAX_FILES) throw ReviewFailure(FailureReason.LIMIT)

    val paths = linkedMapOf<String, FileLines>()
    val numberedPatch = StringBuilder()
    for (file in files) {
        if (file.additions < 0 || file.deletions < 0) invalidPatch()
        val oldPath = file.previousPath ?: file.path
        if (file.path in paths || !validPath(file.path) || !validPath(oldPath)) invalidPatch()
        val patch = file.patch?.takeUnless(String::isEmpty) ?: throw ReviewFailure(FailureReason.PATCH_UNAVAILABLE)
        numberedPatch.append("File: ").append(JsonPrimitive(file.path))
        file.previousPath?.let { numberedPatch.append(" (previous: ").append(JsonPrimitive(it)).append(')') }
        numberedPatch.append('\n')
        numberedPatch.checkDiffBufferSize()
        paths[file.path] = parsePatch(file, oldPath, patch, numberedPatch)
    }
    val text = numberedPatch.toString()
    if (text.characterCount() > MAX_DIFF_LENGTH) throw ReviewFailure(FailureReason.LIMIT)
    return PreparedDiff(text, paths)
}

private val hunkHeader = Regex("""@@ -([0-9]+)(?:,([0-9]+))? \+([0-9]+)(?:,([0-9]+))? @@(?: [\s\S]*)?""")
private const val NO_FINAL_LF = "\\ No newline at end of file"

private fun parsePatch(file: ChangedFile, oldPath: String, patch: String, numberedPatch: StringBuilder): FileLines {
    val old = PatchSide()
    val new = PatchSide()
    var additions = 0
    var deletions = 0
    var hunkHasChange = false
    var previousOld: Int? = null
    var previousNew: Int? = null

    for (row in patch.removeSuffix("\n").splitToSequence('\n')) {
        when {
            row.startsWith("@@") -> {
                if (old.started && !hunkHasChange) invalidPatch()
                val header = hunkHeader.matchEntire(row.removeSuffix("\r")) ?: invalidPatch()
                val (oldStart, oldCount, newStart, newCount) = header.destructured
                val oldGap = old.startHunk(oldStart, oldCount)
                val newGap = new.startHunk(newStart, newCount)
                if (oldGap != newGap) invalidPatch()
                hunkHasChange = false
                previousOld = null
                previousNew = null
                numberedPatch.append("@@\n")
            }
            row.removeSuffix("\r") == NO_FINAL_LF -> {
                if (previousOld == null && previousNew == null) invalidPatch()
                previousOld?.let(old.noFinalLf::add)
                previousNew?.let(new.noFinalLf::add)
                previousOld = null
                previousNew = null
                numberedPatch.append(NO_FINAL_LF).append('\n')
            }
            else -> {
                val operation = row.firstOrNull()
                if (operation == null || operation !in " +-") invalidPatch()
                val line = row.substring(1)
                previousOld = if (operation != '+') old.consume(line) else null
                previousNew = if (operation != '-') new.consume(line) else null
                if (operation != ' ') hunkHasChange = true
                when (operation) {
                    '+' -> additions++
                    '-' -> deletions++
                }

                previousOld?.let { numberedPatch.append("OLD:").append(it).append(' ') }
                previousNew?.let { numberedPatch.append("NEW:").append(it).append(' ') }
                numberedPatch.append(operation).append(line).append('\n')
            }
        }
        numberedPatch.checkDiffBufferSize()
    }

    if (!hunkHasChange) invalidPatch()
    old.checkComplete()
    new.checkComplete()
    if (additions != file.additions || deletions != file.deletions) invalidPatch()
    return FileLines(oldPath, old.text, new.text, old.noFinalLf, new.noFinalLf)
}

private class PatchSide {
    val text = linkedMapOf<Int, String>()
    val noFinalLf = mutableSetOf<Int>()
    val started: Boolean get() = endExclusive != 0L
    private var nextLine = 0L
    private var endExclusive = 0L

    fun startHunk(startField: String, countField: String): Long {
        checkComplete()
        val start = startField.toIntOrNull()?.toLong() ?: invalidPatch()
        val count = if (countField.isEmpty()) 1 else countField.toIntOrNull() ?: invalidPatch()
        // An empty range's header names the line before the insertion point.
        val first = if (count == 0) start + 1 else start
        val afterLast = first + count
        if (first == 0L || first < endExclusive || afterLast > Int.MAX_VALUE.toLong() + 1) invalidPatch()
        // At the first hunk, the preceding unchanged interval begins at line 1.
        val gap = first - maxOf(1L, endExclusive)
        nextLine = first
        endExclusive = afterLast
        return gap
    }

    fun consume(line: String): Int {
        if (nextLine >= endExclusive || noFinalLf.isNotEmpty()) invalidPatch()
        val number = (nextLine++).toInt()
        text[number] = line
        return number
    }

    fun checkComplete() {
        if (nextLine != endExclusive) invalidPatch()
    }
}

private fun StringBuilder.checkDiffBufferSize() {
    // Each code point uses at most two UTF-16 units. The exact count is checked once after preparation.
    if (length > MAX_DIFF_LENGTH * 2) throw ReviewFailure(FailureReason.LIMIT)
}

private fun String.characterCount(): Int = codePointCount(0, length)

private fun validRepo(repo: String): Boolean =
    repoName.matches(repo) && repo.substringAfter('/') !in setOf(".", "..")

private fun validPath(path: String): Boolean =
    '\u0000' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
        UTF_8.newEncoder().canEncode(path)

private fun invalidPatch(): Nothing = throw ReviewFailure(FailureReason.INVALID_PATCH)

fun validateResult(result: ReviewResult, diff: PreparedDiff): ReviewResult {
    if (result.findings.size > 5) invalidResponse()
    for (finding in result.findings) {
        if (finding.severity != "high" && finding.severity != "medium") invalidResponse()
        if (finding.title.characterCount() !in 1..120 || finding.explanation.characterCount() !in 1..1_500) {
            invalidResponse()
        }
        validatedLocation(finding, diff)
    }
    return result
}

private fun validatedLocation(finding: Finding, diff: PreparedDiff): FileLines {
    val file = diff.files[finding.file] ?: invalidResponse()
    val start = finding.startLine
    val end = finding.endLine
    if (start <= 0 || end < start || end - start >= 5) invalidResponse()
    val text = when (finding.side) {
        DiffSide.OLD -> file.oldText
        DiffSide.NEW -> file.newText
    }
    if ((start..end).any { it !in text }) invalidResponse()
    return file
}

private fun invalidResponse(): Nothing = throw ReviewFailure(FailureReason.INVALID_RESPONSE)

private val markdownExtensions = setOf("md", "markdown", "mdown", "mkd")

fun codeLink(ctx: RunContext, snapshot: DiffSnapshot, finding: Finding, diff: PreparedDiff): String {
    val file = validatedLocation(finding, diff)
    val (commit, path) = when (finding.side) {
        DiffSide.OLD -> snapshot.oldSha to file.oldPath
        DiffSide.NEW -> snapshot.headSha to finding.file
    }
    if (!validRepo(ctx.repo) || !fullSha.matches(commit) || !validPath(path)) invalidResponse()
    val encodedPath = path.split('/').joinToString("/") {
        URLEncoder.encode(it, UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    }
    val query = if (path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in markdownExtensions) {
        "?plain=1"
    } else {
        ""
    }
    val rangeEnd = if (finding.startLine == finding.endLine) "" else "-L${finding.endLine}"
    return "https://github.com/${ctx.repo}/blob/$commit/$encodedPath$query#L${finding.startLine}$rangeEnd"
}

private const val ERROR_COMMENT = "AI-ревью завершилось ошибкой."

fun renderComment(
    ctx: RunContext,
    outcome: Outcome,
    result: ReviewResult?,
    diff: PreparedDiff?,
    snapshot: DiffSnapshot?,
): String = when (outcome) {
    Outcome.IGNORED -> ""
    Outcome.SKIPPED -> "AI-ревью пропущено. Не удалось подтвердить доступные patches в пределах заданных ограничений."
    Outcome.STALE -> "AI-ревью прервано из-за изменения состояния pull request."
    Outcome.ERROR -> ERROR_COMMENT
    Outcome.REVIEWED -> {
        if (result == null || diff == null || snapshot == null) {
            ERROR_COMMENT
        } else {
            try {
                renderReviewed(ctx, validateResult(result, diff), diff, snapshot)
            } catch (_: ReviewFailure) {
                ERROR_COMMENT
            }
        }
    }
}

private fun renderReviewed(
    ctx: RunContext,
    result: ReviewResult,
    diff: PreparedDiff,
    snapshot: DiffSnapshot,
): String {
    if (!fullSha.matches(snapshot.baseSha) || !fullSha.matches(snapshot.headSha) || !fullSha.matches(snapshot.oldSha)) {
        return ERROR_COMMENT
    }
    val comment = buildString {
        appendLine("### AI-ревью")
        appendLine()
        appendLine(
            "source=COMPARE · base `${snapshot.baseSha}` · head `${snapshot.headSha}` · old `${snapshot.oldSha}`",
        )
        appendLine("Файлов: ${diff.files.size}. Проверены доступные patches; тесты не запускались.")
        if (result.findings.isEmpty()) {
            appendLine()
            appendLine("В переданном diff существенных проблем не обнаружено.")
        }
        for (finding in result.findings) {
            appendLine()
            appendLine("**${finding.severity} · ${finding.category}**")
            appendLine()
            appendLine("```text")
            appendLine(safeModelText(finding.title))
            appendLine(safeModelText(finding.explanation))
            appendLine("```")
            appendLine()
            appendLine("[К строкам кода](${codeLink(ctx, snapshot, finding, diff)})")
        }
    }
    return if (comment.characterCount() > MAX_COMMENT_LENGTH) ERROR_COMMENT else comment
}

private fun safeModelText(value: String): String = buildString(value.length) {
    for (char in value) {
        append(when {
            char == '`' -> '\u02CB'
            char == '@' -> '\uFF20'
            char.isISOControl() || char.category == CharCategory.FORMAT -> ' '
            else -> char
        })
    }
}
