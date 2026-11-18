package aireviewer

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiClientsTest {
    @Test
    fun singleComparisonSnapshot() {
        val file = fileJson("src/a.kt", previous = "before/a.kt", additions = "2")
        val transport = RecordingTransport("GET $COMPARE" to HttpReply(200, compareJson(files = listOf(file))))
        val snapshot = github(transport).comparison(BASE, HEAD)

        assertEquals(
            DiffSnapshot(BASE, HEAD, OLD, listOf(ChangedFile("src/a.kt", PATCH, "before/a.kt", 2, 1))),
            snapshot,
        )
        assertEquals(listOf("GET $COMPARE"), transport.routes)
        assertGithubHeaders(transport.requests.single())
    }

    @Test
    fun compareCommitPaginationDoesNotTruncateOrPageTheFileList() {
        fun compare(count: Int): RecordingTransport {
            val files = (1..count).map { fileJson("$it.kt") }
            val headers = mapOf("Link" to listOf("<https://evil.example/next>; rel=\"next\""))
            return RecordingTransport("GET $COMPARE" to HttpReply(200, compareJson(files = files), headers))
        }

        val withinLimit = compare(50)
        assertEquals(50, github(withinLimit).comparison(BASE, HEAD).files.size)
        assertEquals(listOf("GET $COMPARE"), withinLimit.routes)
        for (count in listOf(0, 51, 300)) {
            val outsideLimit = compare(count)
            assertFailure(FailureReason.LIMIT) { github(outsideLimit).comparison(BASE, HEAD) }
            assertEquals(listOf("GET $COMPARE"), outsideLimit.routes)
        }
    }

    @Test
    fun mismatchedComparisonMetadata() {
        val validFile = fileJson("a.kt")
        val invalidReplies = buildList {
            add(compareJson(base = OLD))
            add(compareJson(base = "not-a-sha"))
            add(compareJson(old = "not-a-sha"))
            add(compareJson(old = null))
            add(compareJson(files = listOf(validFile, validFile)))
            add(compareJson().dropLast(1) + ""","diagnostic":bare_literal}""")
            for (badCount in listOf(null, "-1", "1.0", "\"1\"", "null", "true")) {
                add(compareJson(files = listOf(fileJson("a.kt", additions = badCount))))
                add(compareJson(files = listOf(fileJson("a.kt", deletions = badCount))))
            }
        }
        for (body in invalidReplies) {
            val transport = RecordingTransport("GET $COMPARE" to HttpReply(200, body))
            assertFailure(FailureReason.INVALID_PATCH) { github(transport).comparison(BASE, HEAD) }
            assertEquals(listOf("GET $COMPARE"), transport.routes)
        }
        val noRequest = RecordingTransport()
        assertFailure(FailureReason.INVALID_PATCH) { github(noRequest).comparison("short", HEAD) }
        assertTrue(noRequest.requests.isEmpty())
    }

    @Test
    fun permissionUsesEffectivePermissionAndFailsClosedOnApiErrors() {
        val route = "GET $ROOT/collaborators/dev%5Bbot%5D/permission"
        val maintainer = RecordingTransport(route to HttpReply(200, """{"permission":"write","role_name":"maintain"}"""))
        assertEquals("write", github(maintainer).permission("dev[bot]"))
        assertGithubHeaders(maintainer.requests.single())

        val triage = RecordingTransport(route to HttpReply(200, """{"permission":"read","role_name":"triage"}"""))
        assertEquals("read", github(triage).permission("dev[bot]"))
        val roleOnly = RecordingTransport(route to HttpReply(200, """{"role_name":"admin"}"""))
        assertFailure(FailureReason.HTTP) { github(roleOnly).permission("dev[bot]") }
        for (status in listOf(403, 404, 429, 502)) {
            val failed = RecordingTransport(route to HttpReply(status, RAW_MARKER))
            assertFailure(FailureReason.HTTP) { github(failed).permission("dev[bot]") }
            assertEquals(1, failed.requests.size)
        }
    }

    @Test
    fun pullRequestKeepsTheFullIdentityIncludingADeletedFork() {
        val path = "GET $ROOT/pulls/42"
        val privateFork = "fork-owner/private-project"
        val open = RecordingTransport(path to HttpReply(200, pullRequestJson(privateFork)))
        assertEquals(
            PullRequestState(BASE, HEAD, "main", "work/topic", privateFork, true, false, true, 7),
            github(open).pullRequest(42),
        )
        val closed = RecordingTransport(
            path to HttpReply(200, pullRequestJson(null, state = "closed", draft = true, labeled = false)),
        )
        assertEquals(
            PullRequestState(BASE, HEAD, "main", "work/topic", null, false, true, false, 7),
            github(closed).pullRequest(42),
        )
        assertGithubHeaders(open.requests.single())
    }

    @Test
    fun upstreamSourceResolutionUsesCommitTreesAndExactPathComponents() {
        val srcTree = "1".repeat(40)
        val oldBlob = "2".repeat(40)
        val newBlob = "3".repeat(40)
        val oldPath = "café #?.md"
        val newPath = "renamed.kt"
        val transport = RecordingTransport(
            "GET $ROOT/pulls/42" to HttpReply(200, pullRequestJson("fork-owner/private-project")),
            "GET $ROOT/git/commits/$OLD" to HttpReply(200, commitJson(OLD, TREE)),
            "GET $ROOT/git/trees/$TREE" to HttpReply(200, treeJson(TREE, listOf(entryJson("src", "040000", "tree", srcTree)))),
            "GET $ROOT/git/trees/$srcTree" to HttpReply(200, treeJson(srcTree, listOf(entryJson(oldPath, "100644", "blob", oldBlob)))),
            "GET $ROOT/git/blobs/$oldBlob" to HttpReply(200, blobJson("old\n".toByteArray(), sha = oldBlob)),
            "GET $ROOT/git/commits/$HEAD" to HttpReply(200, commitJson(HEAD, BASE)),
            "GET $ROOT/git/trees/$BASE" to HttpReply(200, treeJson(BASE, listOf(entryJson(newPath, "100755", "blob", newBlob)))),
            "GET $ROOT/git/blobs/$newBlob" to HttpReply(200, blobJson("new\n".toByteArray(), sha = newBlob)),
        )
        val github = github(transport)
        assertEquals("fork-owner/private-project", github.pullRequest(42).headRepo)
        assertEquals("old\n", github.sourceFile(OLD, "src/$oldPath").bytes.decodeToString())
        assertEquals("new\n", github.sourceFile(HEAD, newPath).bytes.decodeToString())
        assertEquals(listOf(
            "GET $ROOT/pulls/42",
            "GET $ROOT/git/commits/$OLD", "GET $ROOT/git/trees/$TREE", "GET $ROOT/git/trees/$srcTree",
            "GET $ROOT/git/blobs/$oldBlob",
            "GET $ROOT/git/commits/$HEAD", "GET $ROOT/git/trees/$BASE", "GET $ROOT/git/blobs/$newBlob",
        ), transport.routes)
        transport.requests.forEach(::assertGithubHeaders)
    }

    @Test
    fun sourceUrlsAreDataAndOnlyMetadataIsCachedWithinTheClient() {
        val transport = sourceFixture()
        val client = github(transport)
        repeat(2) {
            assertEquals("text\n", client.sourceFile(OLD, "a.kt").bytes.decodeToString())
        }
        assertEquals(listOf(
            "GET $ROOT/git/commits/$OLD",
            "GET $ROOT/git/trees/$TREE",
            "GET $ROOT/git/blobs/$BLOB",
            "GET $ROOT/git/blobs/$BLOB",
        ), transport.routes)
        assertTrue(transport.requests.all { it.uri().host == "api.github.com" && it.uri().rawQuery == null })
    }

    @Test
    fun sourceKindsAndTruncatedOrInconsistentMetadataAreUnavailable() {
        val disallowedTrees = listOf(
            treeJson(TREE, listOf(entryJson("a.kt", "120000", "blob"))),
            treeJson(TREE, listOf(entryJson("a.kt", "160000", "commit"))),
            treeJson(TREE, listOf(entryJson("a.kt", "040000", "tree"))),
            treeJson(TREE, listOf(entryJson("a.kt", "100644", "tree"))),
            treeJson(TREE, listOf(entryJson("a.KT", "100644", "blob"))),
            treeJson(TREE, listOf(entryJson("a.kt", "100644", "blob", "short"))),
            treeJson(TREE, List(2) { entryJson("a.kt", "100644", "blob") }),
            treeJson(TREE, listOf(entryJson("a.kt", "100644", "blob")), truncated = true),
            treeJson(HEAD, listOf(entryJson("a.kt", "100644", "blob"))),
            treeJson(TREE, listOf(entryJson("a.kt", "100644", "blob"))).dropLast(1) +
                ""","diagnostic":bare_literal}""",
        )
        for (tree in disallowedTrees) {
            val transport = sourceFixture(tree = HttpReply(200, tree))
            assertFailure(FailureReason.SOURCE_UNAVAILABLE) { github(transport).sourceFile(OLD, "a.kt") }
            assertEquals(listOf("GET $ROOT/git/commits/$OLD", "GET $ROOT/git/trees/$TREE"), transport.routes)
        }
        val wrongCommit = sourceFixture(commit = HttpReply(200, commitJson(HEAD, TREE)))
        assertFailure(FailureReason.SOURCE_UNAVAILABLE) { github(wrongCommit).sourceFile(OLD, "a.kt") }
        val regularFileAsDirectory = sourceFixture()
        assertFailure(FailureReason.SOURCE_UNAVAILABLE) { github(regularFileAsDirectory).sourceFile(OLD, "a.kt/nested") }
        assertEquals(2, regularFileAsDirectory.requests.size)
    }

    @Test
    fun source404IsUnavailableWhileRateLimitsAndServerFailuresAreHttpErrors() {
        for (status in listOf(404, 403, 429, 502)) {
            val expected = if (status == 404) FailureReason.SOURCE_UNAVAILABLE else FailureReason.HTTP
            val failed = HttpReply(status, RAW_MARKER)
            for (transport in listOf(sourceFixture(commit = failed), sourceFixture(tree = failed), sourceFixture(blob = failed))) {
                assertFailure(expected) { github(transport).sourceFile(OLD, "a.kt") }
            }
        }
    }

    @Test
    fun sourceKindsAndLimitsUseStrictDecodingAndDecodedByteCounts() {
        val exact = "é".repeat(131_072).toByteArray()
        val valid = sourceFixture(blob = HttpReply(200, blobJson(exact)))
        assertEquals(262_144, github(valid).sourceFile(OLD, "a.kt").bytes.size)
        val wrapped = sourceFixture(blob = HttpReply(200, blobJson("hello\n".toByteArray(), content = "aGVs\r\nbG8K\n")))
        assertEquals("hello\n", github(wrapped).sourceFile(OLD, "a.kt").bytes.decodeToString())

        val oversize = exact + 'x'.code.toByte()
        for (size in listOf(exact.size, oversize.size)) {
            val tooLarge = sourceFixture(blob = HttpReply(200, blobJson(oversize, declaredSize = size)))
            assertFailure(FailureReason.LIMIT) { github(tooLarge).sourceFile(OLD, "a.kt") }
        }
        val invalid = listOf(
            blobJson("x".toByteArray(), content = "e%=="),
            blobJson(byteArrayOf(0xc3.toByte(), 0x28)),
            blobJson("x".toByteArray(), declaredSize = 2),
            blobJson("x".toByteArray(), sha = HEAD),
            blobJson("x".toByteArray(), encoding = "utf-8"),
        )
        for (blob in invalid) {
            val transport = sourceFixture(blob = HttpReply(200, blob))
            assertFailure(FailureReason.SOURCE_UNAVAILABLE) { github(transport).sourceFile(OLD, "a.kt") }
        }
    }

    @Test
    fun commentUsesUpstreamIssueEndpointAndSerializesBody() {
        val route = "POST $ROOT/issues/42/comments"
        val transport = RecordingTransport(route to HttpReply(201, "{}"))
        val body = "fenced comment\nwith \"quotes\""
        github(transport).comment(42, body)
        val request = transport.requests.single()
        assertGithubHeaders(request)
        val payload = Json.parseToJsonElement(request.bodyText()).jsonObject
        assertEquals(setOf("body"), payload.keys)
        assertEquals(body, payload.getValue("body").jsonPrimitive.content)
    }

    @Test
    fun fixedProfileRejectsAliasesAndArbitraryConstructorValuesBeforeNetwork() {
        val supported = ModelProfile(MODEL, "none", 6_000)
        assertEquals(supported, supportedProfile(MODEL))
        for (unsupported in listOf("gpt-5.4", "gpt-5.4-2026-03-04", " $MODEL", "")) {
            assertFailure(FailureReason.CONFIG) { supportedProfile(unsupported) }
        }
        val transport = RecordingTransport()
        for (profile in listOf(
            supported.copy(model = "gpt-5.4"), supported.copy(reasoningEffort = "low"), supported.copy(maxOutputTokens = 2_000),
        )) {
            assertFailure(FailureReason.CONFIG) { OpenAiClient(OPENAI_KEY, profile, transport).review(diff(), RULES) }
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun oneModelRequestUsesFixedProfileStrictSchemaAndSeparateTrustedInstructions() {
        val transport = modelResponse(completed())
        val diff = diff()
        val rules = checkNotNull(javaClass.getResourceAsStream("/review-prompt.md")).bufferedReader().use { it.readText() }
        assertEquals(
            ReviewResult(listOf(Finding("a.kt", "high", "Переполнение", "Utf8 🚚", ReviewCategory.BUSINESS_LOGIC, DiffSide.OLD, 1, 1))),
            model(transport).review(diff, rules),
        )
        assertEquals(listOf("POST /v1/responses"), transport.routes)
        val request = transport.requests.single()
        assertEquals(URI("https://api.openai.com/v1/responses"), request.uri())
        assertEquals(Duration.ofSeconds(60), request.timeout().orElseThrow())
        assertEquals("Bearer $OPENAI_KEY", request.headers().firstValue("Authorization").orElseThrow())
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow())
        val body = request.bodyText()
        assertFalse(OPENAI_KEY in body || GITHUB_TOKEN in body)
        val payload = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf("model", "reasoning", "max_output_tokens", "store", "instructions", "input", "text"), payload.keys)
        assertEquals(JsonPrimitive(MODEL), payload.getValue("model"))
        assertEquals(JsonPrimitive("none"), payload.getValue("reasoning").jsonObject.getValue("effort"))
        assertEquals(JsonPrimitive(6_000), payload.getValue("max_output_tokens"))
        assertEquals(JsonPrimitive(false), payload.getValue("store"))
        assertEquals(JsonPrimitive(rules), payload.getValue("instructions"))
        assertEquals(JsonPrimitive(diff.text), payload.getValue("input"))

        val format = payload.getValue("text").jsonObject.getValue("format").jsonObject
        assertEquals(JsonPrimitive("json_schema"), format.getValue("type"))
        assertEquals(JsonPrimitive(true), format.getValue("strict"))
        val schema = format.getValue("schema").jsonObject
        assertEquals(JsonPrimitive(false), schema.getValue("additionalProperties"))
        assertEquals(listOf(JsonPrimitive("findings")), schema.getValue("required").jsonArray.toList())
        val findings = schema.getValue("properties").jsonObject.getValue("findings").jsonObject
        val item = findings.getValue("items").jsonObject
        assertEquals(JsonPrimitive(false), item.getValue("additionalProperties"))
        val expectedFields = setOf("file", "severity", "title", "explanation", "category", "side", "startLine", "endLine")
        assertEquals(expectedFields, item.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val properties = item.getValue("properties").jsonObject
        assertEquals(expectedFields, properties.keys)
        assertEquals(
            setOf("BUSINESS_LOGIC", "BACKWARD_COMPATIBILITY", "RACE_CONDITION", "CONTRACT_VIOLATION", "ERROR_HANDLING"),
            properties.getValue("category").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        val sides = properties.getValue("side").jsonObject.getValue("enum").jsonArray.toList()
        assertEquals(listOf(JsonPrimitive("OLD"), JsonPrimitive("NEW")), sides)
    }

    @Test
    fun safeIncompleteEvenWhenPartialOutputContainsValidFindings() {
        val incomplete = completed(status = "incomplete", metadata = """"incomplete_details":{"reason":"max_output_tokens"},""")
        val transport = modelResponse(incomplete)
        assertFailure(FailureReason.INVALID_RESPONSE) { model(transport).review(diff(), RULES) }
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun invalidOutputRejectsRefusalMalformedEnvelopeAndUnexpectedMessageKinds() {
        val validText = """{"type":"output_text","text":${JsonPrimitive(findingPayload())}}"""
        val refusal = """{"type":"refusal","refusal":"$RAW_MARKER"}"""
        val replies = listOf(
            "{",
            """{"status":"completed","output_text":${JsonPrimitive(findingPayload())}}""",
            completed(content = "[$validText,$refusal]"),
            completed(content = "[$refusal]"),
            completed(messageStatus = "incomplete"),
            completed(role = "developer"),
            completed(metadata = """"error":{"message":"$RAW_MARKER"},"""),
            completed(metadata = """"incomplete_details":{"reason":"max_output_tokens"},"""),
            """{"status":"completed","output":[{"type":"function_call"}]}""",
        )
        replies.forEach(::assertInvalidModelReply)
    }

    @Test
    fun invalidOutputRejectsUnknownFieldsAndCategoriesAndFakeLocations() {
        val invalid = listOf(
            "not JSON $RAW_MARKER",
            """{"findings":[],"diagnostic":"$RAW_MARKER"}""",
            findingPayload(extra = ""","url":"https://evil.example/" """),
            findingPayload(category = "STYLE"),
            findingPayload(file = "absent.kt"),
            findingPayload(start = "20", end = "20"),
            findingPayload(start = "\"1\""),
            findingPayload(start = "1.0"),
            findingPayload(start = "0"),
            findingPayload(end = null),
        )
        invalid.forEach { assertInvalidModelReply(completed(output = it)) }
    }

    @Test
    fun malformedJsonRejectsAnUnescapedNewlineInModelText() {
        val rawNewline = findingPayload().replace("Переполнение", "line one\nline two")
        assertInvalidModelReply(completed(output = rawNewline))
    }

    @Test
    fun malformedJsonRejectsAnEscapedDuplicateKeyThatWouldEraseFindings() {
        val repeatedFindings = findingPayload().dropLast(1) + """, "\u0066indings":[]}"""
        assertInvalidModelReply(completed(output = repeatedFindings))
    }

    @Test
    fun malformedJsonRejectsBareLiteralsEvenInUnusedEnvelopeFields() {
        assertInvalidModelReply(completed(metadata = """"diagnostic":bare_literal,"""))
    }

    @Test
    fun malformedJsonRejectsExcessiveResponseNesting() {
        assertInvalidModelReply(completed(metadata = nestedMetadata(2_000)))
    }

    @Test
    fun responseJsonAccepts64ContainersAndRejects65() {
        // The top-level response object accounts for the other container.
        val atLimit = modelResponse(completed(metadata = nestedMetadata(63)))
        assertEquals(1, model(atLimit).review(diff(), RULES).findings.size)
        assertInvalidModelReply(completed(metadata = nestedMetadata(64)))
    }

    @Test
    fun validJsonPreservesEscapedQuotesNewlinesAndUnicode() {
        val title = "Сбой \"на входе\" 🚚"
        val explanation = "Строка 1\nСтрока 2\\"
        val output = findingPayload(title = title, explanation = explanation).replace("🚚", """\uD83D\uDE9A""")
        val transport = modelResponse(completed(output = output))
        val finding = model(transport).review(diff(), RULES).findings.single()
        assertEquals(title, finding.title)
        assertEquals(explanation, finding.explanation)
    }

    @Test
    fun completedEmptyReviewIsAcceptedAndApiFailuresAreNeverRetried() {
        val empty = modelResponse(completed("""{"findings":[]}"""))
        assertEquals(ReviewResult(emptyList()), model(empty).review(diff(), RULES))
        assertEquals(1, empty.requests.size)

        for (status in listOf(429, 500, 502)) {
            val error = modelResponse(RAW_MARKER, status)
            assertFailure(FailureReason.OPENAI) { model(error).review(diff(), RULES) }
            assertEquals(1, error.requests.size)
        }
    }

    @Test
    fun invalidCredentialsAreRejectedWithoutEchoingTheirValues() {
        val invalid = "$RAW_MARKER\r\ninvalid"
        val transport = RecordingTransport()
        assertFailure(FailureReason.CONFIG) { GitHubClient(REPO, invalid, transport).permission("dev") }
        assertFailure(FailureReason.CONFIG) { OpenAiClient(invalid, supportedProfile(MODEL), transport).review(diff(), RULES) }
        assertTrue(transport.requests.isEmpty())
    }

    private fun sourceFixture(
        commit: HttpReply = HttpReply(200, commitJson(OLD, TREE)),
        tree: HttpReply = HttpReply(200, treeJson(TREE, listOf(entryJson("a.kt", "100644", "blob")))),
        blob: HttpReply = HttpReply(200, blobJson("text\n".toByteArray())),
    ) = RecordingTransport(
        "GET $ROOT/git/commits/$OLD" to commit,
        "GET $ROOT/git/trees/$TREE" to tree,
        "GET $ROOT/git/blobs/$BLOB" to blob,
    )

    private fun assertGithubHeaders(request: HttpRequest) {
        assertEquals("https", request.uri().scheme)
        assertEquals("api.github.com", request.uri().host)
        assertEquals(Duration.ofSeconds(15), request.timeout().orElseThrow())
        assertEquals("Bearer $GITHUB_TOKEN", request.headers().firstValue("Authorization").orElseThrow())
    }

    private fun assertInvalidModelReply(reply: String) {
        val transport = modelResponse(reply)
        assertFailure(FailureReason.INVALID_RESPONSE) { model(transport).review(diff(), RULES) }
        assertEquals(1, transport.requests.size)
    }

    private fun assertFailure(reason: FailureReason, action: () -> Unit) {
        val failure = assertFailsWith<ReviewFailure>(block = action)
        assertEquals(reason, failure.reason)
        assertEquals(reason.name, failure.message)
        assertNull(failure.cause)
    }

    private fun github(transport: RecordingTransport) = GitHubClient(REPO, GITHUB_TOKEN, transport)
    private fun model(transport: RecordingTransport) = OpenAiClient(OPENAI_KEY, supportedProfile(MODEL), transport)
    private fun modelResponse(body: String, status: Int = 200) =
        RecordingTransport("POST /v1/responses" to HttpReply(status, body))

    private fun diff() = prepareDiff(listOf(ChangedFile("a.kt", PATCH, additions = 1, deletions = 1)))

    private class RecordingTransport(vararg responses: Pair<String, HttpReply>) : HttpTransport {
        private val responses = responses.toMap()
        val requests = mutableListOf<HttpRequest>()
        val routes get() = requests.map(::route)

        override fun send(request: HttpRequest): HttpReply {
            requests += request
            return responses.getValue(route(request))
        }

        private fun route(request: HttpRequest): String {
            val query = request.uri().rawQuery?.let { "?$it" }.orEmpty()
            return "${request.method()} ${request.uri().rawPath}$query"
        }
    }

    private fun HttpRequest.bodyText(): String {
        val sink = ByteArrayOutputStream()
        val result = CompletableFuture<String>()
        bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)

            override fun onNext(item: ByteBuffer) {
                val bytes = ByteArray(item.remaining())
                item.get(bytes)
                sink.write(bytes)
            }

            override fun onComplete() {
                result.complete(sink.toString(Charsets.UTF_8))
            }

            override fun onError(throwable: Throwable) {
                result.completeExceptionally(throwable)
            }
        })
        return result.get(1, TimeUnit.SECONDS)
    }

    private companion object {
        const val REPO = "upstream/project"
        const val ROOT = "/repos/$REPO"
        const val GITHUB_TOKEN = "fake-github-token"
        const val OPENAI_KEY = "fake-openai-key"
        const val RAW_MARKER = "private-response-marker"
        const val RULES = "Trusted instructions from the reviewer"
        const val MODEL = "gpt-5.4-2026-03-05"
        const val PATCH = "@@ -1 +1 @@\n-old\n+new"
        val BASE = "a".repeat(40)
        val HEAD = "b".repeat(40)
        val OLD = "c".repeat(40)
        val TREE = "d".repeat(40)
        val BLOB = "e".repeat(40)
        val COMPARE = "$ROOT/compare/$BASE...$HEAD?per_page=1&page=1"

        fun fileJson(path: String, previous: String? = null, additions: String? = "1", deletions: String? = "1"): String =
            buildString {
                append("""{"filename":${JsonPrimitive(path)},"patch":${JsonPrimitive(PATCH)}""")
                previous?.let { append(""","previous_filename":${JsonPrimitive(it)}""") }
                additions?.let { append(""","additions":$it""") }
                deletions?.let { append(""","deletions":$it""") }
                append('}')
            }

        fun compareJson(base: String = BASE, old: String? = OLD, files: List<String> = listOf(fileJson("a.kt"))): String {
            val merge = old?.let { ""","merge_base_commit":{"sha":"$it"}""" }.orEmpty()
            return """{"base_commit":{"sha":"$base"}$merge,"files":[${files.joinToString()}],"commits":[]}"""
        }

        fun pullRequestJson(headRepo: String?, state: String = "open", draft: Boolean = false, labeled: Boolean = true): String {
            val repo = headRepo?.let { """{"full_name":"$it"}""" } ?: "null"
            val labels = if (labeled) """[{"name":"ai-review"}]""" else """[{"name":"unrelated"}]"""
            return """
                {"base":{"sha":"$BASE","ref":"main"},"head":{"sha":"$HEAD","ref":"work/topic","repo":$repo},
                 "state":"$state","draft":$draft,"labels":$labels,"changed_files":7}
            """.trimIndent()
        }

        fun commitJson(sha: String, root: String) =
            """{"sha":"$sha","tree":{"sha":"$root","url":"https://evil.example/tree"},"url":"https://evil.example/commit"}"""

        fun treeJson(sha: String, entries: List<String>, truncated: Boolean = false) =
            """{"sha":"$sha","truncated":$truncated,"tree":[${entries.joinToString()}],"url":"https://evil.example/tree"}"""

        fun entryJson(path: String, mode: String, type: String, sha: String = BLOB) =
            """{"path":${JsonPrimitive(path)},"mode":"$mode","type":"$type","sha":"$sha","url":"https://evil.example/blob"}"""

        fun blobJson(
            bytes: ByteArray,
            sha: String = BLOB,
            declaredSize: Int = bytes.size,
            content: String = Base64.getEncoder().encodeToString(bytes),
            encoding: String = "base64",
        ) = """
            {"sha":"$sha","size":$declaredSize,"encoding":"$encoding","content":${JsonPrimitive(content)},
             "git_url":"https://evil.example/git","download_url":"https://evil.example/download","raw_url":"https://evil.example/raw"}
        """.trimIndent()

        fun findingPayload(
            category: String = "BUSINESS_LOGIC", file: String = "a.kt", start: String = "1", end: String? = "1",
            extra: String = "",
            title: String = "Переполнение",
            explanation: String = "Utf8 🚚",
        ): String {
            val endField = end?.let { ""","endLine":$it""" }.orEmpty()
            return """
                {"findings":[{"file":${JsonPrimitive(file)},"severity":"high","title":${JsonPrimitive(title)},
                 "explanation":${JsonPrimitive(explanation)},
                 "category":"$category","side":"OLD","startLine":$start$endField$extra}]}
            """.trimIndent()
        }

        fun completed(
            output: String = findingPayload(),
            status: String = "completed",
            messageStatus: String = "completed",
            role: String = "assistant",
            metadata: String = "",
            content: String = """[{"type":"output_text","text":${JsonPrimitive(output)}}]""",
        ) = """{"status":"$status",$metadata"output":[{"type":"message","status":"$messageStatus","role":"$role","content":$content}]}"""

        fun nestedMetadata(arrayDepth: Int): String {
            val nested = "[".repeat(arrayDepth) + "null" + "]".repeat(arrayDepth)
            return """"nested":$nested,"""
        }
    }
}
