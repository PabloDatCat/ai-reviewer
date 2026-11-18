package aireviewer

import java.nio.charset.CharacterCodingException

private const val MAX_SOURCE_BYTES = 256 * 1024
private const val MAX_TOTAL_SOURCE_BYTES = 2 * 1024 * 1024

fun verifyPatchText(diff: PreparedDiff, snapshot: DiffSnapshot, github: GitHubApi) {
    var total = 0

    fun verifySide(sha: String, path: String, expected: Map<Int, String>, noFinalLf: Set<Int>) {
        if (expected.isEmpty()) return

        val bytes = github.sourceFile(sha, path).bytes
        if (bytes.size > MAX_SOURCE_BYTES || bytes.size > MAX_TOTAL_SOURCE_BYTES - total) {
            throw ReviewFailure(FailureReason.LIMIT)
        }
        total += bytes.size

        val source = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw ReviewFailure(FailureReason.SOURCE_MISMATCH)
        }
        val hasFinalLf = source.endsWith('\n')
        val lines = if (source.isEmpty()) emptyList() else source.removeSuffix("\n").split('\n')
        for ((line, patchText) in expected) {
            val sourceNoFinalLf = line == lines.size && !hasFinalLf
            if (line <= 0 || lines.getOrNull(line - 1) != patchText || sourceNoFinalLf != (line in noFinalLf)) {
                throw ReviewFailure(FailureReason.SOURCE_MISMATCH)
            }
        }
    }

    for ((path, lines) in diff.files) {
        verifySide(snapshot.oldSha, lines.oldPath, lines.oldText, lines.oldNoFinalLf)
        verifySide(snapshot.headSha, path, lines.newText, lines.newNoFinalLf)
    }
}
