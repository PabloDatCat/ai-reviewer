# AI PR Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Собственный label-triggered reviewer PR из forks, с реалистичными findings и проверенными ссылками на строки, максимум 15 минут и один активный review одного PR.

**Architecture:** Один Actions job запускает доверенный Kotlin CLI. Единый Compare response задаёт DiffSnapshot; OLD/NEW текст сверяется с blobs тех же commit через upstream repository до одного OpenAI request. HTTP ограничен полным deadline с cancellation/close; модель и renderer не получают полномочий исполнения.

**Tech Stack:** Kotlin 2.2.21, JVM 21, Gradle Wrapper 8.14.3, kotlinx.serialization-json 1.9.0, JDK HttpClient, JUnit Jupiter 5.12.2, GitHub Actions, OpenAI Responses API; без Spring Boot.

**Spec:** [2026-11-18-ai-pr-review-design.md](../specs/2026-11-18-ai-pr-review-design.md). План обновлён после review; production code и тесты ещё не написаны.

## Global Constraints

- Kotlin 2.2.21 / JVM 21; Gradle Wrapper 8.14.3; Spring Boot не используется.
- JSON: kotlinx.serialization-json 1.9.0; HTTP: java.net.http.HttpClient; тесты: kotlin-test-junit5 и JUnit Jupiter 5.12.2.
- Один workflow, один job, одна CLI-программа; без сервера, БД, Actions cache, artifacts и агентных tools; временные maps metadata разрешены только в памяти одного запуска.
- Trigger: pull_request_target / labeled / ai-review; только открытые PR, не draft; GITHUB_RUN_ATTEMPT должен быть 1.
- Job permissions: contents: read и pull-requests: write; PAT не используется; все uses закреплены полным проверенным commit SHA; checkout ref: github.workflow_sha, path: reviewer, persist-credentials: false.
- Ни код, ни Gradle Wrapper, ни prompt, ни конфигурация из PR не исполняются и не используются как доверенные настройки; subprocess для содержимого PR запрещён.
- DiffSnapshot: merge-base и patches из одного ответа Compare API для зафиксированных base/head SHA; PR files API не используется; OLD/NEW текст всех представленных строк сверяется с Git blobs до вызова OpenAI. Обязательные per-file additions/deletions из того же ответа сверяются с числом операций patch; неизвестные totals не подменяются вычисленными.
- GitHub API origin: https://api.github.com; OpenAI endpoint: https://api.openai.com/v1/responses; redirects запрещены; URL из API-body, PR или модели не открываются.
- Deadline всего HTTP-обмена, включая body: GitHub 15 секунд, OpenAI 60 секунд; минимум с оставшимся CLI budget; каждый body не более 1 MiB; превышение body cap является HTTP/ERROR, а не input LIMIT/SKIPPED; автоматические повторы отключены. JVM запускается с -Djdk.httpclient.redirects.retrylimit=1 до инициализации HttpClient, чтобы ограничить и внутренние попытки JDK.
- При deadline отменяются задача и чтение, закрывается stream; исключены зависший reader и гонка timeout с получением stream; cleanup ограничен 1 секундой, при его неуспехе CLI аварийно завершается без следующих запросов.
- Job timeout-minutes: 15, включая checkout и сборку, без времени ожидания очереди; CLI deadline: 14 минут с начала процесса по System.nanoTime.
- Concurrency: job-группа ai-review-pr-${{ github.event.pull_request.number }}, cancel-in-progress: false, queue: max; максимум одно активное ревью на один PR/MR, разные PR могут проверяться параллельно.
- Максимум 50 файлов и 30 000 символов подготовленного diff; source blob не более 256 KiB, суммарно не более 2 MiB декодированных source bytes; один OpenAI-запрос, store: false.
- Профиль модели: gpt-5.4-2026-03-05, reasoning.effort: none, max_output_tokens: 6000; LLM_MODEL обязан совпадать с этим snapshot; другой профиль требует явного изменения trusted config и повторной приёмки.
- Максимум 5 замечаний; severity: high или medium; title: 1–120 символов, explanation: 1–1500 символов; file входит в snapshot; side: OLD или NEW, startLine/endLine — проверенный диапазон 1–5 строк соответствующей стороны patch.
- Каждое замечание имеет построенную reviewer ссылку через upstream repository: OLD — oldSha и старый путь, NEW — headSha и новый путь; Markdown открывается с ?plain=1 перед #L anchor; URL от модели не принимается.
- Review categories: BUSINESS_LOGIC, BACKWARD_COMPATIBILITY, RACE_CONDITION, CONTRACT_VIOLATION, ERROR_HANDLING; FORMAT/CODE_STYLE/NAMING/MISSING_TESTS/GENERAL_REFACTORING исключены.
- Каждый сценарий сбоя обязан быть реалистичным: достижимые предпосылки, конкретный путь выполнения и наблюдаемое последствие; без выдуманных контрактов, конфигураций и противоречащих коду условий. При недостатке оснований замечание не публикуется; редкий, но правдоподобный отказ не исключается только из-за редкости.
- Секрет OPENAI_API_KEY принадлежит корпоративному API-проекту; логи без credentials, diff, prompt и API-body; общий комментарий не длиннее 12 000 символов. Все пределы в символах считаются по Unicode code points, согласованно с JSON Schema; byte limits остаются байтовыми.

## Review Focus

1. Compare/PR basis расходятся либо oldSha правильного формата, но удалённый текст другой — SOURCE_MISMATCH без LLM; Tasks 1/3/4.
2. Headers уже получены, body завис/капает, timeout гоняется с созданием stream — deadline, interrupt, close и отсутствие reader leak; Task 2.
3. Markdown, rename/delete и private fork с individual upstream access — точные OLD/NEW ссылки через upstream и plain view; Tasks 1/3/5.
4. Совместимая JSON schema модель исчерпывает reasoning/output budget — supported ModelProfile, safe incomplete и near-limit live acceptance; Tasks 3/5.
5. Fork подменяет reviewer, права/PR state меняются, повторные label конкурируют или общий budget истёк — trusted checkout, zero unauthorized calls, один slot/PR и остановка; Tasks 4/5.

---

## File structure и интерфейсы

Исходный каталог не содержит backend/Gradle/.git. Создать файлы из spec. Небольшие функции domain/parser/renderer остаются в Review.kt; lifecycle транспорта вынесен в HttpTransport.kt, сверка исходного текста — в SnapshotVerifier.kt. Новые сервисы и Spring Boot не добавляются.

В `src/main/kotlin/aireviewer/Review.kt` определить:

```kotlin
data class RunContext(val repo: String, val number: Int, val actor: String,
    val baseSha: String, val headSha: String, val baseRef: String,
    val headRef: String, val runAttempt: Int)
data class PullRequestState(val baseSha: String, val headSha: String,
    val baseRef: String, val headRef: String, val headRepo: String?,
    val open: Boolean, val draft: Boolean, val labeled: Boolean, val changedFiles: Int)
data class ChangedFile(val path: String, val patch: String?, val previousPath: String? = null,
    val additions: Int, val deletions: Int)
data class DiffSnapshot(val baseSha: String, val headSha: String,
    val oldSha: String, val files: List<ChangedFile>) // source=COMPARE
enum class DiffSide { OLD, NEW }
enum class ReviewCategory { BUSINESS_LOGIC, BACKWARD_COMPATIBILITY,
    RACE_CONDITION, CONTRACT_VIOLATION, ERROR_HANDLING }
data class FileLines(val oldPath: String, val oldText: Map<Int, String>,
    val newText: Map<Int, String>, val oldNoFinalLf: Set<Int> = emptySet(),
    val newNoFinalLf: Set<Int> = emptySet())
data class PreparedDiff(val text: String, val files: Map<String, FileLines>)
data class SourceFile(val bytes: ByteArray)
data class Finding(val file: String, val severity: String, val title: String,
    val explanation: String, val category: ReviewCategory, val side: DiffSide,
    val startLine: Int, val endLine: Int)
data class ReviewResult(val findings: List<Finding>)
enum class Outcome { REVIEWED, IGNORED, SKIPPED, STALE, ERROR }
enum class FailureReason { LIMIT, PATCH_UNAVAILABLE, INVALID_PATCH, SOURCE_MISMATCH,
    SOURCE_UNAVAILABLE, HTTP, DEADLINE, OPENAI, INVALID_RESPONSE, CONFIG }
class ReviewFailure(val reason: FailureReason) : RuntimeException(reason.name)
interface GitHubApi {
    fun permission(actor: String): String
    fun pullRequest(number: Int): PullRequestState
    fun comparison(baseSha: String, headSha: String): DiffSnapshot
    fun sourceFile(commitSha: String, path: String): SourceFile
    fun comment(number: Int, body: String)
}
fun interface LlmReviewer {
    fun review(diff: PreparedDiff, rules: String): ReviewResult
}
fun interface HttpTransport {
    fun send(request: java.net.http.HttpRequest): HttpReply
}
data class HttpReply(val status: Int, val body: String,
    val headers: Map<String, List<String>> = emptyMap())
```

## Task 1: Проверяемые patches, исходный текст и ссылки

**Files:** Create `settings.gradle.kts`, `build.gradle.kts`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `src/main/kotlin/aireviewer/Review.kt`, `src/main/kotlin/aireviewer/SnapshotVerifier.kt`, `src/test/kotlin/aireviewer/ReviewTest.kt`, `src/test/kotlin/aireviewer/SnapshotVerifierTest.kt`.

**Interfaces:** Consumes: event JSON/trusted env и GitHubApi port. Produces: модели выше; `parseContext(event: String, repo: String, actor: String, attempt: Int): RunContext?`; `prepareDiff(files: List<ChangedFile>): PreparedDiff`; `validateResult(result: ReviewResult, diff: PreparedDiff): ReviewResult`; `verifyPatchText(diff: PreparedDiff, snapshot: DiffSnapshot, github: GitHubApi): Unit` в SnapshotVerifier.kt; `codeLink(ctx: RunContext, snapshot: DiffSnapshot, finding: Finding, diff: PreparedDiff): String`; `renderComment(ctx: RunContext, outcome: Outcome, result: ReviewResult?, diff: PreparedDiff?, snapshot: DiffSnapshot?): String`.

- [ ] **Step 1: Создать Gradle CLI scaffold и failing boundary tests.** rootProject.name=ai-reviewer, Kotlin JVM+serialization plugins 2.2.21, toolchain 21, application.mainClass=aireviewer.MainKt (реализуется в Task 4). Wrapper 8.14.3 с проверенным distributionSha256Sum. Dependencies фиксированы: serialization-json 1.9.0, kotlin-test-junit5, JUnit BOM 5.12.2 + Jupiter engine + platform launcher, useJUnitPlatform().

  В ReviewTest локальные fixture helpers создают валидные hunks. Зафиксировать:

  ```kotlin
  @Test fun tracksBothSides() {
      val diff = prepareDiff(listOf(ChangedFile("a.kt",
          "@@ -10,2 +10,2 @@\n-old\n+new\n context", additions = 1, deletions = 1)))
      assertEquals(mapOf(10 to "old", 11 to "context"), diff.files.getValue("a.kt").oldText)
      assertEquals(mapOf(10 to "new", 11 to "context"), diff.files.getValue("a.kt").newText)
  }
  ```

  `contextGate`: неправильные action/label/repo/sender, closed/draft и attempt=2 → null; корректное fork event → RunContext. `diffBoundaries`: 50/51 файлов, 30 000/30 001 символов с разметкой, пустой/отсутствующий patch, duplicate path, неравные OLD/NEW gaps перед первым/между hunks, пропущенный целый hunk с несовпадением обязательных additions/deletions, отрицательные totals, zero-count hunks, pure add/delete, несколько hunks, no-final-LF marker, CRLF, пересечение/несогласованные counts. `findingLocations`: OLD/NEW, контекстная строка, start=0, end<start, >5 строк, пропуск в диапазоне, неизвестный file/category, >5 findings и превышения title/explanation; Unicode supplementary characters ровно на пределе 120/1500 code points и за ним. Размеры numbered diff и comment также считаются по code points.
- [ ] **Step 2: Добавить failing SnapshotVerifierTest.** Helpers `fixtureSnapshot()` и `FakeSources` определить локально; fake GitHubApi sourceFile выдаёт bytes по паре (SHA,path), записывает calls; остальные методы не вызываются.

  ```kotlin
  @Test fun rejectsWrongOldTextAtValidLineNumber() {
      val diff = PreparedDiff("numbered patch", mapOf("a.kt" to
          FileLines("a.kt", mapOf(1 to "deleted"), mapOf(1 to "new"))))
      val sources = FakeSources(oldBytes = "different\n".toByteArray(),
          newBytes = "new\n".toByteArray())
      assertEquals(FailureReason.SOURCE_MISMATCH, assertFailsWith<ReviewFailure> {
          verifyPatchText(diff, fixtureSnapshot(), sources)
      }.reason)
  }
  ```

  `coherentSnapshot`: matching OLD/NEW text проходит; oldSha != baseSha. `cachedPatchAfterBaseChange`: численные координаты валидны, но patch от старого основания при новом oldSha → SOURCE_MISMATCH. `renamedAndDeletedSources`: oldPath/newPath и SHA запросов точны; pure add/delete не читает отсутствующую сторону. `sourceBoundaries`: decoded blob 256 KiB проходит, +1 byte отвергается; total 2 MiB проходит, +1 отвергается; UTF-8 invalid, отсутствующая строка, пробелы/CR и final-LF mismatch не нормализуются и дают безопасный отказ.
- [ ] **Step 3: Добавить failing link/renderer tests.** `upstreamLinks`: оба URL начинаются с ctx.repo, даже если fork private/недоступен; OLD oldSha, NEW headSha, previousPath при rename. `markdownSourceLinks`: .md/.MD/.markdown, OLD/NEW, rename и single/range → ?plain=1 перед #L; Kotlin/JSON URL без этого query. `escapedPaths`: Unicode, пробел, '#', '?', скобки и Markdown syntax кодируются как path segments. `safeComment`: каждому finding соответствует собственная кликабельная ссылка снаружи code block; модельный Markdown/backticks/@ не активны; >12 000 символов → фиксированный ERROR; STALE/ERROR не показывают findings.
- [ ] **Step 4: Запустить `./gradlew test --tests aireviewer.ReviewTest --tests aireviewer.SnapshotVerifierTest`.** Ожидается FAIL compilation по отсутствующим production symbols.
- [ ] **Step 5: Реализовать перечисленные интерфейсы.** Сверка text включает все строки hunks, не только будущие findings; до LLM. Не trim/не менять CR, no-final-LF проверяется отдельно. Range валидируется по keys OLD/NEW maps. Renderer строит ссылки только из verified snapshot/path/координат; URL модели не принимается. Source limits считаются по декодированным bytes.
- [ ] **Step 6: Повторить команду Step 4.** Ожидается BUILD SUCCESSFUL.
- [ ] **Step 7: Commit файлов этой задачи**, если Git уже настроен: message `feat: verify diff source text and immutable line links`. Автоматически создавать .git/remote не требуется.

## Task 2: Полный HTTP deadline и lifecycle

**Files:** Create `src/main/kotlin/aireviewer/HttpTransport.kt`, `src/test/kotlin/aireviewer/HttpTransportTest.kt`; Modify `build.gradle.kts` (startup flag полного запрета повторных попыток JDK).

**Interfaces:** Consumes: HttpTransport/HttpReply/ReviewFailure из Task 1. Produces: `class ReviewBudget(deadlineNanos: Long, nanoTime: () -> Long = System::nanoTime)` с `fun remaining(): java.time.Duration` (DEADLINE при исчерпании); `class DeadlineHttpTransport internal constructor(client: java.net.http.HttpClient, budget: ReviewBudget, allowedOrigins: Set<java.net.URI>) : HttpTransport, AutoCloseable`; `fun defaultTransport(budget: ReviewBudget): DeadlineHttpTransport` с production origins только api.github.com/api.openai.com и Redirect.NEVER. `class TransportCleanupFailure : RuntimeException` сигнализирует Main, что продолжать запросы нельзя.

- [ ] **Step 1: Написать failing HttpTransportTest с loopback server и fake credentials.** В test helper создать origin на свободном локальном порту, inject через internal constructor; production factory остаётся неизменяемой. Каждый сервер/executor закрывается в finally. Основные проверки:

  | Test | Сценарий | Обязательные assertions |
  | --- | --- | --- |
  | headersWithoutBody | Сервер отдал headers, EOF/body не присылает | send выбрасывает DEADLINE по общему budget; server видит закрытие; worker завершён |
  | stalledMidBody | Несколько bytes пришли, дальше тишина | чтение отменено, stream закрыт, частичный JSON не возвращается |
  | tricklingBody | Один byte каждые 50 ms | при budget 200 ms обмен завершается по deadline, а не после 1 MiB/EOF |
  | lateStreamAfterTimeout | Barrier удерживает регистрацию stream до timeout | поздний stream закрыт, body не читается, открытого ресурса не осталось |
  | completionRacesDeadline | Barrier управляет EOF/timeout | один итог; нет опубликованного успеха после deadline и оставшегося timer/reader |
  | boundedBody | 1 MiB и 1 MiB+1 | первый проходит, второй безопасно отклонён и закрыт |
  | redirectsBlocked | 302 на другой loopback endpoint | target получает ноль запросов, credentials не пересылаются |
  | clientAttemptsBounded | fresh JVM с startup flag, сервер принял и оборвал запрос | фактически не более одной попытки HTTP, не только один вызов transport.send |
  | interruptedCaller | caller interrupt при read | interrupt не теряется, exchange отменён, новых запросов нет |
  | remainingReviewBudget | общий budget 100 ms, request timeout 15 s | обмен ограничен остатком 100 ms, а не 15 s |
  | cleanupFailureStopsClient | controlled worker не завершился в grace | TransportCleanupFailure; следующий send не выполняется |
  | blockingCloseIsBounded | контролируемый stream блокирует close | caller не блокируется на close; bounded cleanup заканчивается fatal, а не зависанием |

  Размерные/дедлайновые тесты используют управляемые barriers/latches и малые durations, не реальные 15/60 секунд. Реальный сетевой тест deadline допускает scheduling tolerance до cleanup grace 1 секунды. Для late-stream/close races helper ControlledHttpClient реализует JDK HttpClient с управляемыми response/stream и передаётся в существующий constructor; production алгоритм registration тот же, нового runtime API для PR не появляется. Проверять фактическое завершение reader tasks после cleanup, а не только isDone отменённого Future. Helpers отпускают тестовые barriers в finally, включая intentionally blocked cleanup.
- [ ] **Step 2: Запустить `./gradlew test --tests aireviewer.HttpTransportTest`.** Ожидается FAIL до появления transport.
- [ ] **Step 3: Реализовать ReviewBudget и transport.** Создавать абсолютный deadline до submit; min(request.timeout, budget.remaining). Worker охватывает send, чтение максимум 1 MiB+1 и finally-close. Согласовать cancellation flag и публикацию stream так, чтобы late stream закрывался и не читался. Timeout отменяет worker с interrupt и закрывает stream; не ограничиваться Future.get timeout/orTimeout без отмены чтения. До возврата результата проверить, что deadline не истёк. Не сбрасывать таймер на chunk. Таймеры удалять после completion; close transport завершает executor. Cleanup >1 секунды → fatal TransportCleanupFailure; Main завершает процесс, не пытаясь отправить ERROR-комментарий. Ошибки содержат только safe reason/status. В applicationDefaultJvmArgs и test JVM добавить -Djdk.httpclient.redirects.retrylimit=1 до старта процесса; defensive установка в defaultTransport не заменяет startup flag. Проверить реальное число сетевых попыток, а не только значение property.

  Shutdown/close тоже bounded: не выполнять потенциально блокирующий close на caller и не использовать бесконечные ExecutorService.close/HttpClient.close/join. Cancellation и shutdownNow + конечный await должны укладываться в cleanup grace; незавершённый worker/close → fatal. Main после fatal не повторяет неблокирующий по предположению, но фактически бесконечный cleanup в finally.
- [ ] **Step 4: Повторить тесты Task 2.** Ожидается BUILD SUCCESSFUL, server/thread resources завершены.
- [ ] **Step 5: Commit:** HttpTransport.kt и HttpTransportTest.kt; message `feat: bound complete HTTP exchanges and cancel body reads` (если Git настроен).

## Task 3: Coherent GitHub API и фиксированный ModelProfile

**Files:** Create `src/main/kotlin/aireviewer/GitHubClient.kt`, `src/main/kotlin/aireviewer/OpenAiClient.kt`, `src/main/resources/review-prompt.md`, `src/test/kotlin/aireviewer/ApiClientsTest.kt`.

**Interfaces:** Consumes: модели/порты Task 1 и transport Task 2. Produces: `GitHubClient(repo: String, token: String, transport: HttpTransport) : GitHubApi`; `data class ModelProfile(val model: String, val reasoningEffort: String, val maxOutputTokens: Int)` и `fun supportedProfile(model: String): ModelProfile`; `OpenAiClient(apiKey: String, profile: ModelProfile, transport: HttpTransport) : LlmReviewer`. ModelProfile и supportedProfile располагаются в OpenAiClient.kt.

- [ ] **Step 1: Добавить failing ApiClientsTest с RecordingTransport.** Fake transport записывает HttpRequest и выдаёт responses по method/path; helpers разбирают body publisher. Assertions:

  | Test | Проверяемое поведение |
  | --- | --- |
  | singleComparisonSnapshot | Один compare/base...head?per_page=1&page=1; oldSha и files из того же JSON; ноль запросов pulls/{n}/files |
  | compareCommitPagination | Link rel=next на commits и полный files<=50 не приводят к skip/пагинации; >50 и ответ из 300 files дают LIMIT; zero files → SKIPPED через LIMIT |
  | mismatchedComparisonMetadata | base_commit.sha не совпал, SHA malformed, duplicate filename, нет merge-base либо отсутствуют/отрицательны/нецелочисленны additions/deletions → безопасный отказ до LLM |
  | upstreamSourceResolution | OLD/NEW читаются только через repo из constructor, Git commit→trees→blob; path и SHA точны; никакого headRepo HTTP |
  | sourceKindsAndLimits | mode 100644/100755 принимается; symlink/submodule/directory/truncated tree отвергаются; 404→SOURCE_UNAVAILABLE, неверный base64/UTF-8 и >256 KiB → safe skip |
  | sourceUrlsAreData | git_url/download_url/raw_url на evil.example не открываются; origin и URL path формирует client |
  | permissions | permission field, не role_name; 403/404 не превращаются в write; triage не получает платный запрос через pipeline |
  | fixedProfile | supportedProfile принимает только gpt-5.4-2026-03-05, effort=none, max=6000; alias/другой model → CONFIG |
  | oneModelRequest | ровно один POST responses, model snapshot, reasoning.effort=none, max_output_tokens=6000, store=false, strict schema, без tools/background/SSE |
  | safeIncomplete | incomplete с reason=max_output_tokens и даже частичным output_text → INVALID_RESPONSE, без retry и частичного findings |
  | invalidOutput | refusal, malformed JSON, unknown properties/category, fake file/line, 429/5xx → фиксированная ошибка без raw payload/ключей |

  Дополнительно проверить request timeout 15/60 секунд, раздельные Authorization credentials и отсутствие fake API-key в prompt. По source kinds tests fixtures отражают Git modes, не поведение Contents API, которое может следовать symlink.
- [ ] **Step 2: Запустить `./gradlew test --tests aireviewer.ApiClientsTest`.** Ожидается FAIL по отсутствующим clients/profile.
- [ ] **Step 3: Реализовать GitHubClient.** Fixed upstream endpoints: permission, pulls/{n}, compare/{baseSha}...{headSha}?per_page=1&page=1, git/commits/{sha}, git/trees/{treeSha} (не recursive), git/blobs/{blobSha}, POST issues/{n}/comments. Обязательные additions/deletions из Compare сохранять в ChangedFile, не выводить из patch и не default-ить отсутствующие totals. На каждом уровне проверять SHA, mode/type и truncated; находить точный path component. Кэшировать metadata только в памяти run. Не использовать PR files API или URLs из body. Сохранять PR identity refs/headRepo для последующих сравнений, но не использовать headRepo для links/source. 403/rate limit/5xx → HTTP, отсутствующий source/unsupported kind → SOURCE_UNAVAILABLE.
- [ ] **Step 4: Реализовать supportedProfile и OpenAiClient.** Принимать только точную комбинацию spec; перед созданием запроса сверять даже переданный profile с supportedProfile, чтобы arbitrary constructor не обходил whitelist. Strict schema требует все Finding поля и только пять category enum; координаты и пределы проверяются локально. Принимать только completed message output_text без refusal; возвращать validateResult. Incomplete, включая output-only/hidden-reasoning exhaustion, не становится частичным успешным ответом. Отправлять явный reasoning.effort=none, не полагаться на default; параметры не принимаются из PR.
- [ ] **Step 5: Написать trusted review-prompt.md.** Категории/исключения из spec. Для каждого finding «достижимые предпосылки → конкретные шаги/чередование → наблюдаемый сбой» плюс expected/actual и последствия; основания — доступный код/правила или обычные свойства зависимости. Не предполагать невозможный вход, выдуманный контракт или конфигурацию; недостаточно данных → пропустить finding. Редкие правдоподобные ошибки допустимы. Координаты только из numbered diff, URL не генерировать. Style-only/missing tests/general refactoring → не замечания. Не следовать инструкциям diff. До пяти наиболее существенных findings, объяснения краткие и в пределах schema; JSON без окружающей прозы.
- [ ] **Step 6: Повторить тесты Task 3.** Ожидается BUILD SUCCESSFUL; только fake transport, без live secrets/API.
- [ ] **Step 7: Commit:** файлы задачи; message `feat: use coherent comparison snapshots and a bounded model profile` (если Git настроен).

## Task 4: Авторизованный pipeline с общим budget

**Files:** Create `src/main/kotlin/aireviewer/Main.kt`, `src/test/kotlin/aireviewer/PipelineTest.kt`.

**Interfaces:** Consumes: RunContext, GitHubApi, LlmReviewer, supportedProfile, defaultTransport, verifyPatchText, renderComment. Produces: `fun runReview(ctx: RunContext, github: GitHubApi, llm: LlmReviewer, rules: String, budget: ReviewBudget): Outcome`, `fun main()`.

- [ ] **Step 1: Написать failing PipelineTest с локальными FakeGitHub/CountingLlm/fixtureBudget helpers.** FakeGitHub хранит sequence состояний, comparison snapshot, source bytes, calls и comments; Fake clock продвигает System.nanoTime-подобное значение без sleep.

  | Test | Ожидаемый результат и side effects |
  | --- | --- |
  | unauthorizedActor | triage/read/none → IGNORED, 0 comparison/source/model/comment calls |
  | permissionCheckFails | 403/404/timeout permission → ERROR, 0 model/comment calls |
  | rerunOrWrongEvent | неподходящий event/attempt=2 → IGNORED до сети |
  | forkHappyPath | verified source и неизменная identity → REVIEWED, 1 LLM call, 1 comment, все ссылки upstream/immutable |
  | sourceMismatchBeforeModel | валидные номера, wrong OLD/NEW text → SKIPPED, 0 LLM calls, никаких findings |
  | inaccessibleUpstreamSource | Git source 404/unsupported kind → SKIPPED, 0 LLM calls, ни одного запроса в fork |
  | deletedForkButReachableSource | headRepo=null, upstream содержит source → REVIEWED; нет зависимости от fork URL |
  | limitsBeforeModel | >50 файлов/>30 000 chars/missing patch/source byte limits → SKIPPED, 0 LLM calls |
  | changedBeforeModel | SHA/ref/headRepo/state/label изменились после source verification → STALE, 0 LLM calls |
  | changedAfterModel | те же изменения на final recheck → STALE, 0 опубликованных findings |
  | invalidOrIncompleteModel | refusal/invalid coordinates/incomplete с partial JSON или LIMIT от fake LLM → ERROR, 1 call без retry, ни одного partial finding |
  | expiredGlobalBudget | expiry до source/LLM/comment → новых HTTP calls нет, ERROR; не пытаться сообщить timeout новым запросом |
  | httpDeadlineOnModel | обмен остановлен и очищен → ERROR; fixed comment только при живом budget и неизменном PR |
  | fatalCleanupOrCancellation | TransportCleanupFailure/interrupt → abort, 0 следующих запросов/диагностических comments |
  | commentFailure | ERROR, не повторять POST и не отправлять второй диагностический comment |

- [ ] **Step 2: Запустить `./gradlew test --tests aireviewer.PipelineTest`.** Ожидается FAIL до реализации pipeline.
- [ ] **Step 3: Реализовать runReview.** Порядок: attempt → permission → PR identity/state/changed_files fast limit → comparison → prepareDiff → verifyPatchText → PR recheck → LLM/validateResult → PR recheck → renderComment → POST. Проверять budget перед этапами; transport дополнительно ограничивает каждый полный exchange. Сравнение snapshot requested base/head SHA обязательно; сравнивать baseRef/headRef/headRepo/state с первоначальным PR чтением. Состояние после LLM нужно проверить и для error-path; после expiry/cancellation/fatal cleanup новых запросов нет. LIMIT/PATCH_UNAVAILABLE/INVALID_PATCH/SOURCE_MISMATCH/SOURCE_UNAVAILABLE до model attempt → SKIPPED; любой отказ после model attempt → ERROR, если state не требует STALE; error semantics остальные из spec. Permission/initial PR loading вне ветки отправки диагностических comments. Отказ POST не сообщать вторым POST: первый мог быть принят сервером, даже если response потерялся.
- [ ] **Step 4: Реализовать main.** Читать JSON из GITHUB_EVENT_PATH; repo/actor/attempt из runtime env, credentials только из env launch step, LLM_MODEL через supportedProfile до создания клиентов и любых запросов. Неподходящий event → exit 0 до чтения ключей. Создать ReviewBudget 14 минут по System.nanoTime и defaultTransport; prompt из classpath /review-prompt.md. На успехе закрыть transport; ERROR/CONFIG → exit 1 с safe reason, остальные outcomes exit 0. TransportCleanupFailure и interrupt завершают процесс с освобождением ресурсов, без stack trace/API-body и последующих запросов. Hard job timeout 15 минут остаётся внешней границей, даже если checkout/build заняли время.
- [ ] **Step 5: Запустить `./gradlew test installDist`.** Ожидается BUILD SUCCESSFUL, все тесты используют fake API/loopback, executable `build/install/ai-reviewer/bin/ai-reviewer` создан.
- [ ] **Step 6: Commit:** Main.kt/PipelineTest.kt; message `feat: enforce authorization snapshot and review deadlines` (если Git настроен).

## Task 5: Workflow и live acceptance

**Files:** Create `.github/workflows/ai-review.yml`, `README.md`.

**Interfaces:** Consumes: installDist CLI и runtime env Task 4. Produces: рабочий label workflow и инструкция с проверяемыми deployment gates; live acceptance не выдаётся за уже выполненную.

- [ ] **Step 1: Создать workflow.** Trigger/if/permissions/concurrency и timeout из Global Constraints. Checkout/setup-java закрепить SHA из официальных repositories; Temurin 21 без cache. Checkout github.workflow_sha в reviewer; сборка `./gradlew --no-daemon --console=plain installDist` с working-directory reviewer. Отдельный launch step `build/install/ai-reviewer/bin/ai-reviewer`, тот же working-directory; только ему env GITHUB_TOKEN, OPENAI_API_KEY, LLM_MODEL. Runtime env GitHub не интерполировать в shell через untrusted title/body/path. Не checkout fork, не загружать artifacts fork, не запускать его wrapper/dependencies/tests.
- [ ] **Step 2: Написать README setup/operations.** API project key и LLM_MODEL=gpt-5.4-2026-03-05; label; event policy; публикация trusted files; повтор через label вместо Re-run. Описать source=COMPARE (не обещает идентичность PR UI), source verification, исходный час/увеличение объёма, пределы 50/30 000/256 KiB/2 MiB, HTTP 15/60 секунд, CLI 14 и job 15 минут, pending queue, outcomes и возможное отсутствие timeout-comment. Документировать profile acceptance и private-fork human-role gate; до их прохождения production rollout не считать завершённым. Тесты кода PR остаются отдельным непривилегированным CI.
- [ ] **Step 3: Запустить `actionlint .github/workflows/ai-review.yml` и `./gradlew test installDist`.** Ожидаются exit 0/BUILD SUCCESSFUL. Ручная проверка: SHA pins, workflow_sha checkout, минимальные permissions, секрет только launch step, slot на PR и timeout=15. Не писать тесты, повторяющие YAML строковыми сравнениями.
- [ ] **Step 4: Live fork/security/navigation smoke.** В подготовленном repo открыть fork PR с маленьким дефектом; одновременно изменить reviewer/prompt/wrapper/workflow в PR и добавить harmless sentinel, который создал бы файл при исполнении. Label от write/admin: выполняется только trusted workflow_sha, sentinel не срабатывает, опубликован один comment. Открыть ссылки NEW/OLD: точный head/old commit, нужные текст и выделение. Повторить rename/delete и Markdown OLD/NEW source view с ?plain=1; обычный Kotlin/JSON также должен открываться корректно. Действительный API-key не помещать в fork или логи.
- [ ] **Step 5: Live coherent-diff smoke.** Создать историю, где head/base разошлись и base затем включает часть head ancestry; после повторного label фиксируются новый oldSha и patches из одного Compare response. Проверить совпадение OLD текста по ссылке. Если Compare отличается от cached PR UI, комментарий честно указывает snapshot/источник. Unit fixture из Task 1 уже проверяет принудительно несогласованный patch независимо от того, воспроизводится ли кэширование GitHub. Force-push/retarget во время run → STALE либо явно старый immutable snapshot, не review новых изменений; push без label не создаёт run.
- [ ] **Step 6: Live private-fork acceptance.** Reviewer-человеку выдать individual upstream write, без team membership/прав на private fork; fork принадлежит другому участнику. Этот reviewer ставит label и открывает все NEW/OLD upstream URLs в собственной сессии: source виден, строки выделены. Проверить ситуацию, где direct fork URL недоступен. Если upstream source API=404 → SKIPPED без LLM; если bot API работает, а человеческий upstream URL нет, private-repo rollout остановить и исправить маршрут. Права owner/admin не использовать вместо заявленной роли: они скрывают этот corner case. Не добавлять fallback на headRepo.
- [ ] **Step 7: Live concurrency/timeout.** Во время review A снять/снова поставить label A: второй pending, первый не отменяется, одновременно <=1 in_progress для A. Label другого PR B не блокируется группой A. Подтвердить timeout-minutes=15 в effective job config. В отдельном временном trusted smoke-workflow без credentials/model проверить timeout-minutes=1 на sleep 90, затем удалить его; production workflow не загрязнять. Полные HTTP дедлайны уже проверяет loopback suite, один job-timeout smoke их не заменяет.
- [ ] **Step 8: Live profile и semantic evaluation.** Выполнить семь коротких cases: style-only (format/style/naming/missing tests/general refactoring) → []; по одному реальному дефекту пяти разрешённых категорий; безопасный case, где кажущийся сбой исключён явно показанным контрактом/валидацией → []. В реальных cases присутствует правдоподобный редкий timeout или гонка. Дополнительно near-limit diff ~30 000 символов, Unicode и пять реалистичных дефектов: exact model snapshot + effort=none + max=6000 должны дать completed schema-valid JSON в 60 секунд, до пяти findings со всеми координатами/ссылками. Записать profile, latency, output/reasoning usage и результаты, без исходного кода/credentials. Это acceptance candidate profile, не гарантия всех будущих ответов. Провал/нехватка API-доступа/невалидный JSON/incomplete → rollout не завершать; profile корректируется trusted change с повторением acceptance, не runtime retry или молчаливой сменой модели.
- [ ] **Step 9: Зафиксировать фактические результаты.** README содержит выполненные команды/краткие результаты live cases; невыполненные проверки явно отмечены, не считаются PASS. Commit `.github/workflows/ai-review.yml`/README.md: `feat: run verified AI review from trusted label events`, если Git настроен. Repository/remote/push не выбирать без пользовательского контекста; подготовка плана ничего не публикует.

## Review findings → проверки

| Замечание review | Изменение архитектуры | Доказательство при исполнении |
| --- | --- | --- |
| merge-base и patches могли иметь разные основания | Один DiffSnapshot из Compare, текст сверяется с pinned blobs | Task 1 cachedPatchAfterBaseChange/coherentSnapshot; Task 3 singleComparisonSnapshot; Task 4 sourceMismatchBeforeModel; Task 5 ancestry smoke |
| request timeout не ограничивал чтение body | Полный deadline, interrupt/close и late-stream handshake | Task 2 headersWithoutBody/stalledMidBody/tricklingBody/lateStreamAfterTimeout/completionRacesDeadline/cleanupFailureStopsClient |
| Markdown URL не выделял исходные строки | ?plain=1 перед fragment для Markdown | Task 1 markdownSourceLinks; Task 5 Markdown browser smoke |
| произвольная reasoning model + 2000 tokens | Один supported ModelProfile, effort=none, 6000; safe incomplete | Task 3 fixedProfile/oneModelRequest/safeIncomplete; Task 5 near-limit model acceptance |
| private fork URL мог быть недоступен individual collaborator | Обе ссылки через upstream + API source verification + human-role gate | Task 3 upstreamSourceResolution; Task 4 inaccessibleUpstreamSource; Task 5 private-fork acceptance |

## Self-review и handoff

- Global Constraints скопированы из spec без расхождений; пять Review Focus и все пять remarks review имеют задачи/проверки в таблице выше. Полные source bytes не передаются модели: она получает только bounded numbered diff.
- Типы и функции едины между задачами; прежние отдельные mergeBase/files методы и ReviewSnapshot исключены. Новые Verify/Transport файлы имеют конкретную ответственность, Spring Boot не добавляется.
- Вся прежняя приёмка сохранена: trust boundary forks, authorization, SHA/state, реалистичность, категории, обязательные ссылки, 15 минут и один review на PR. Добавленные tests проверяют внешние эффекты и lifecycle, а не строки implementation.
- Из-за source verification, сетевого lifecycle и live role/profile checks прежняя оценка четырёх этапов по сумме 60 минут больше не используется. Первоначальный час — ограничение исходной задачи, а не доказанный срок полного набора новой приёмки. Безопасность и обязательные ссылки не сокращаются молча.
- Это план, а не готовый reviewer. Для исполнения связанного CLI рекомендуется работа в этой сессии через executing-plans; можно выбрать subagent-driven-development. Review предыдущей редакции проведён независимым саб-агентом; текущий документ проверяется отдельно на согласованность, без утверждения о выполненных unit/live tests. Commit/push, изменение GitHub settings и платные API-вызовы при обновлении документов не выполняются.
