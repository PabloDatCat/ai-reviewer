package aireviewer

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val GITHUB_API = "https://api.github.com"
private val GITHUB_TIMEOUT = Duration.ofSeconds(15)
private const val MAX_COMPARE_FILES = 50
private const val MAX_BLOB_BYTES = 256 * 1024
private val repositoryName = Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")
private val githubLogin = Regex("""[A-Za-z0-9_-]+(?:\[bot])?""")
private val fullGitSha = Regex("[0-9a-fA-F]{40}")

class GitHubClient(repo: String, token: String, private val transport: HttpTransport) : GitHubApi {
    private val root = "/repos/$repo"
    private val authorization = bearerValue(token)
    private val rootTrees = mutableMapOf<String, String>()
    private val treeEntries = mutableMapOf<String, Map<String, TreeEntry>>()

    init {
        if (!repositoryName.matches(repo) || repo.substringAfter('/') in setOf(".", "..")) {
            throw ReviewFailure(FailureReason.CONFIG)
        }
    }

    override fun permission(actor: String): String {
        if (!githubLogin.matches(actor)) throw ReviewFailure(FailureReason.CONFIG)
        val login = URLEncoder.encode(actor, UTF_8)
        return getObject("/collaborators/$login/permission", FailureReason.HTTP)
            .requireString("permission", FailureReason.HTTP)
    }

    override fun pullRequest(number: Int): PullRequestState {
        requirePullNumber(number)
        val reason = FailureReason.HTTP
        val pr = getObject("/pulls/$number", reason)
        val base = pr.requireObject("base", reason)
        val head = pr.requireObject("head", reason)
        val headRepo = when (val repo = head["repo"]) {
            JsonNull -> null
            is JsonObject -> repo.requireString("full_name", reason)
            else -> throw ReviewFailure(reason)
        }
        val open = when (pr.stringField("state")) {
            "open" -> true
            "closed" -> false
            else -> throw ReviewFailure(reason)
        }
        val labeled = pr.requireArray("labels", reason)
            .map { it.requireObject(reason).requireString("name", reason) }
            .contains("ai-review")

        return PullRequestState(
            baseSha = base.requireSha("sha", reason),
            headSha = head.requireSha("sha", reason),
            baseRef = base.requireString("ref", reason),
            headRef = head.requireString("ref", reason),
            headRepo = headRepo,
            open = open,
            draft = pr.requireBoolean("draft", reason),
            labeled = labeled,
            changedFiles = pr.requireNonnegativeInt("changed_files", reason),
        )
    }

    override fun comparison(baseSha: String, headSha: String): DiffSnapshot {
        val reason = FailureReason.INVALID_PATCH
        if (!fullGitSha.matches(baseSha) || !fullGitSha.matches(headSha)) throw ReviewFailure(reason)
        val comparison = getObject("/compare/$baseSha...$headSha?per_page=1&page=1", reason)
        comparison.requireObject("base_commit", reason).requireMatchingSha(baseSha, reason)
        val oldSha = comparison.requireObject("merge_base_commit", reason).requireSha("sha", reason)
        val files = comparison.requireArray("files", reason)
        if (files.size !in 1..MAX_COMPARE_FILES) throw ReviewFailure(FailureReason.LIMIT)

        val seen = mutableSetOf<String>()
        val changedFiles = files.map { changed ->
            val file = changed.requireObject(reason)
            val path = file.requireString("filename", reason)
            if (!seen.add(path)) throw ReviewFailure(reason)
            ChangedFile(
                path = path,
                patch = file.optionalString("patch", reason),
                previousPath = file.optionalString("previous_filename", reason),
                additions = file.requireNonnegativeInt("additions", reason),
                deletions = file.requireNonnegativeInt("deletions", reason),
            )
        }
        return DiffSnapshot(baseSha, headSha, oldSha, changedFiles)
    }

    override fun sourceFile(commitSha: String, path: String, maxBytes: Int): SourceFile {
        val reason = FailureReason.SOURCE_UNAVAILABLE
        if (maxBytes <= 0) throw ReviewFailure(FailureReason.LIMIT)
        val allowance = minOf(MAX_BLOB_BYTES, maxBytes)
        val components = path.split('/')
        if (!fullGitSha.matches(commitSha) || components.any { !validComponent(it) }) throw ReviewFailure(reason)

        var tree = rootTrees.getOrPut(commitSha) {
            val commit = getObject("/git/commits/$commitSha", reason, source = true)
            commit.requireMatchingSha(commitSha, reason)
            commit.requireObject("tree", reason).requireSha("sha", reason)
        }
        for (component in components.dropLast(1)) {
            val entry = readTree(tree)[component] ?: throw ReviewFailure(reason)
            if (entry.mode != "040000" || entry.type != "tree") throw ReviewFailure(reason)
            tree = entry.sha
        }

        val file = readTree(tree)[components.last()] ?: throw ReviewFailure(reason)
        if (file.mode !in setOf("100644", "100755") || file.type != "blob") throw ReviewFailure(reason)
        // The base64 representation of a large file can exceed the transport limit.
        if (file.advertisedSize != null && file.advertisedSize.requireNonnegativeLong(reason) > allowance) {
            throw ReviewFailure(FailureReason.LIMIT)
        }
        return readBlob(file.sha, allowance)
    }

    override fun comment(number: Int, body: String) {
        requirePullNumber(number)
        val payload = buildJsonObject { put("body", body) }.toString()
        val request = request("/issues/$number/comments")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        if (transport.send(request).status != 201) throw ReviewFailure(FailureReason.HTTP)
    }

    private fun requirePullNumber(number: Int) {
        if (number <= 0) throw ReviewFailure(FailureReason.CONFIG)
    }

    private fun readTree(sha: String): Map<String, TreeEntry> = treeEntries.getOrPut(sha) {
        val reason = FailureReason.SOURCE_UNAVAILABLE
        val response = getObject("/git/trees/$sha", reason, source = true)
        response.requireMatchingSha(sha, reason)
        if (response.requireBoolean("truncated", reason)) throw ReviewFailure(reason)
        buildMap {
            for (value in response.requireArray("tree", reason)) {
                val entry = value.requireObject(reason)
                val path = entry.requireString("path", reason)
                if (!validComponent(path)) throw ReviewFailure(reason)
                val metadata = TreeEntry(
                    mode = entry.requireString("mode", reason),
                    type = entry.requireString("type", reason),
                    sha = entry.requireSha("sha", reason),
                    advertisedSize = entry["size"],
                )
                if (put(path, metadata) != null) throw ReviewFailure(reason)
            }
        }
    }

    private fun readBlob(sha: String, allowance: Int): SourceFile {
        val reason = FailureReason.SOURCE_UNAVAILABLE
        val blob = getObject("/git/blobs/$sha", reason, source = true)
        blob.requireMatchingSha(sha, reason)
        if (blob.stringField("encoding") != "base64") throw ReviewFailure(reason)
        val size = blob.requireNonnegativeInt("size", reason)
        if (size > allowance) throw ReviewFailure(FailureReason.LIMIT)
        val content = blob.requireString("content", reason).replace("\r", "").replace("\n", "")
        val decodedSize = decodedBase64Size(content, reason)
        if (decodedSize > allowance) throw ReviewFailure(FailureReason.LIMIT)
        if (decodedSize != size.toLong()) throw ReviewFailure(reason)
        val bytes = try {
            Base64.getDecoder().decode(content)
        } catch (_: IllegalArgumentException) {
            throw ReviewFailure(reason)
        }
        if (bytes.size > allowance) throw ReviewFailure(FailureReason.LIMIT)
        if (bytes.size != size) throw ReviewFailure(reason)
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw ReviewFailure(reason)
        }
        return SourceFile(bytes)
    }

    private fun getObject(path: String, reason: FailureReason, source: Boolean = false): JsonObject {
        val reply = transport.send(request(path).GET().build())
        if (source && reply.status == 404) throw ReviewFailure(FailureReason.SOURCE_UNAVAILABLE)
        if (reply.status != 200) throw ReviewFailure(FailureReason.HTTP)
        return parseResponseObject(reply.body, reason)
    }

    private fun request(path: String): HttpRequest.Builder = HttpRequest.newBuilder(URI.create("$GITHUB_API$root$path"))
        .timeout(GITHUB_TIMEOUT)
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", authorization)
        .header("X-GitHub-Api-Version", "2022-11-28")

    private data class TreeEntry(val mode: String, val type: String, val sha: String, val advertisedSize: JsonElement?)
}

// Validate the basic Base64 shape before allocating any decoded source bytes.
// The JDK decoder accepts unpadded final groups of two or three characters.
private fun decodedBase64Size(content: String, reason: FailureReason): Long {
    val padding = when {
        content.endsWith("==") -> 2
        content.endsWith("=") -> 1
        else -> 0
    }
    val dataLength = content.length - padding
    if (dataLength % 4 == 1 || (padding > 0 && content.length % 4 != 0)) {
        throw ReviewFailure(reason)
    }
    for (index in 0 until dataLength) {
        val character = content[index]
        if (character !in 'A'..'Z' && character !in 'a'..'z' &&
            character !in '0'..'9' && character != '+' && character != '/') {
            throw ReviewFailure(reason)
        }
    }
    return dataLength.toLong() * 6 / 8
}

private fun validComponent(path: String): Boolean =
    path.isNotEmpty() && path != "." && path != ".." && '/' !in path && '\u0000' !in path

private fun JsonObject.requireBoolean(key: String, reason: FailureReason): Boolean =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw ReviewFailure(reason)

private fun JsonObject.requireSha(key: String, reason: FailureReason): String =
    requireString(key, reason).takeIf(fullGitSha::matches) ?: throw ReviewFailure(reason)

private fun JsonObject.requireMatchingSha(expected: String, reason: FailureReason) {
    if (!requireSha("sha", reason).equals(expected, ignoreCase = true)) throw ReviewFailure(reason)
}

private fun JsonObject.optionalString(key: String, reason: FailureReason): String? =
    if (this[key] == null || this[key] == JsonNull) null else requireString(key, reason)

internal fun bearerValue(token: String): String {
    if (token.isEmpty() || token.any { it !in '!'..'~' }) throw ReviewFailure(FailureReason.CONFIG)
    return "Bearer $token"
}
