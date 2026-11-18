package aireviewer

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class ReviewBudget(private val deadlineNanos: Long, private val nanoTime: () -> Long = System::nanoTime) {
    fun remaining(): Duration = Duration.ofNanos(positiveNanos(deadlineNanos - nanoTime()))
}

class TransportCleanupFailure : RuntimeException("HTTP transport cleanup failed")

private const val MAX_BODY_BYTES = 1024 * 1024
private const val CLEANUP_GRACE_NANOS = 1_000_000_000L

class DeadlineHttpTransport internal constructor(
    private val client: HttpClient,
    private val budget: ReviewBudget,
    allowedOrigins: Set<URI>,
) : HttpTransport, AutoCloseable {
    private val origins = allowedOrigins.map { origin(it) ?: throw ReviewFailure(FailureReason.CONFIG) }.toSet()
    private val executor = Executors.newThreadPerTaskExecutor(
        Thread.ofPlatform().daemon().name("ai-review-http-", 1).factory(),
    )
    private val lifecycle = Any()
    private val active = mutableSetOf<Exchange>()
    private var shutdown: Shutdown? = null
    private var poisoned = false

    init {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) throw ReviewFailure(FailureReason.CONFIG)
    }

    override fun send(request: HttpRequest): HttpReply {
        if (Thread.currentThread().isInterrupted) stopAfterInterrupt()
        val startedAt = System.nanoTime()
        val exchange = synchronized(lifecycle) {
            if (poisoned) throw TransportCleanupFailure()
            if (shutdown != null) throw ReviewFailure(FailureReason.HTTP)
            if (origin(request.uri()) !in origins) throw ReviewFailure(FailureReason.CONFIG)
            val timeout = request.timeout().orElseThrow { ReviewFailure(FailureReason.CONFIG) }
            val limit = minOf(timeout, budget.remaining())
            Exchange(client, executor, request, startedAt + limit.toNanos()).also {
                it.start()
                active.add(it)
            }
        }

        try {
            val reply = exchange.awaitReply()
            budget.remaining()
            return reply
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            stopAfterInterrupt()
        } catch (_: TransportCleanupFailure) {
            failCleanup()
        } catch (failure: ReviewFailure) {
            if (failure.reason == FailureReason.DEADLINE) {
                val cleanupDeadline = System.nanoTime() + CLEANUP_GRACE_NANOS
                exchange.cancel()
                if (!exchange.stoppedBy(cleanupDeadline)) failCleanup()
                if (Thread.currentThread().isInterrupted) closeBy(cleanupDeadline)
            }
            throw failure
        } finally {
            synchronized(lifecycle) { active.remove(exchange) }
        }
    }

    override fun close() = closeBy(System.nanoTime() + CLEANUP_GRACE_NANOS)

    private fun stopAfterInterrupt(): Nothing {
        close()
        throw ReviewFailure(FailureReason.DEADLINE)
    }

    private fun closeBy(deadline: Long) {
        val stopping = synchronized(lifecycle) {
            if (poisoned) return
            beginShutdown(deadline)
        }
        val executorStopped = awaitCleanup(deadline) { executor.awaitTermination(it, NANOSECONDS) }
        if (!executorStopped || !stopping.client.finishedBy(deadline) ||
            !stopping.exchanges.all { it.stoppedBy(deadline) }
        ) {
            failCleanup()
        }
    }

    // Both the body and the client can block in cleanup, so only daemon workers invoke them.
    private fun beginShutdown(deadline: Long): Shutdown {
        shutdown?.let { return it }
        val exchanges = active.toList()
        exchanges.forEach { it.cancel() }
        val clientShutdown = executor.submit {
            client.shutdownNow()
            val remaining = Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0))
            if (!client.awaitTermination(remaining)) throw TransportCleanupFailure()
        }
        executor.shutdown()
        return Shutdown(clientShutdown, exchanges).also { shutdown = it }
    }

    private fun failCleanup(): Nothing {
        synchronized(lifecycle) {
            poisoned = true
            beginShutdown(System.nanoTime() + CLEANUP_GRACE_NANOS)
        }
        throw TransportCleanupFailure()
    }

    private data class Shutdown(val client: Future<*>, val exchanges: List<Exchange>)
}

fun defaultTransport(budget: ReviewBudget): DeadlineHttpTransport {
    // The packaged CLI also passes this at JVM startup, before JDK HTTP classes initialize.
    System.setProperty("jdk.httpclient.redirects.retrylimit", "1")
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    return DeadlineHttpTransport(
        client,
        budget,
        setOf(URI("https://api.github.com"), URI("https://api.openai.com")),
    )
}

private class Exchange(
    private val client: HttpClient,
    private val executor: ExecutorService,
    private val request: HttpRequest,
    private val deadline: Long,
) {
    private val registration = Any()
    private var publishedBody: Body? = null
    private var cancellation: CompletableFuture<Void>? = null
    private val workerExited = CountDownLatch(1)
    private val closeFailed = AtomicBoolean()
    private val result = FutureTask(::run)

    fun start() {
        executor.execute {
            try {
                result.run()
            } finally {
                // A cancelled Future can be done while this worker is still running.
                workerExited.countDown()
            }
        }
    }

    fun awaitReply(): HttpReply {
        try {
            val reply = result.get(remainingNanos(), NANOSECONDS)
            if (Thread.interrupted()) throw InterruptedException()
            synchronized(registration) {
                if (cancellation != null) throw ReviewFailure(FailureReason.HTTP)
                remainingNanos()
            }
            return reply
        } catch (_: TimeoutException) {
            throw ReviewFailure(FailureReason.DEADLINE)
        } catch (_: CancellationException) {
            throw ReviewFailure(FailureReason.HTTP)
        } catch (failure: ExecutionException) {
            when (val cause = failure.cause) {
                is ReviewFailure -> throw cause
                is TransportCleanupFailure -> throw cause
                is HttpTimeoutException -> throw ReviewFailure(FailureReason.DEADLINE)
                else -> throw ReviewFailure(FailureReason.HTTP)
            }
        }
    }

    fun cancel() = synchronized(registration) {
        if (cancellation == null) {
            val interrupt = CompletableFuture.runAsync({ result.cancel(true) }, executor)
            val close = publishedBody?.let { body -> CompletableFuture.runAsync(body::close, executor) }
            cancellation = if (close == null) interrupt else CompletableFuture.allOf(interrupt, close)
        }
    }

    fun stoppedBy(deadline: Long): Boolean {
        val stopping = synchronized(registration) { checkNotNull(cancellation) }
        return awaitCleanup(deadline) { workerExited.await(it, NANOSECONDS) } &&
            stopping.finishedBy(deadline) && !closeFailed.get()
    }

    private fun run(): HttpReply {
        val boundedRequest = HttpRequest.newBuilder(request) { _, _ -> true }
            .timeout(Duration.ofNanos(remainingNanos()))
            .build()
        val response = client.send(boundedRequest, HttpResponse.BodyHandlers.ofInputStream())
        val body = Body(response.body())
        try {
            synchronized(registration) {
                if (cancellation != null) throw ReviewFailure(FailureReason.DEADLINE)
                remainingNanos()
                publishedBody = body
            }
            val bytes = body.stream.readNBytes(MAX_BODY_BYTES + 1)
            if (bytes.size > MAX_BODY_BYTES) throw ReviewFailure(FailureReason.LIMIT)
            return HttpReply(response.statusCode(), bytes.decodeToString(throwOnInvalidSequence = true), response.headers().map())
        } finally {
            body.close()
        }
    }

    private fun remainingNanos(): Long = positiveNanos(deadline - System.nanoTime())

    private inner class Body(val stream: InputStream) {
        private val closing = AtomicBoolean()

        fun close() {
            if (!closing.compareAndSet(false, true)) return
            try {
                stream.close()
            } catch (_: Exception) {
                closeFailed.set(true)
                throw TransportCleanupFailure()
            }
        }
    }
}

private fun origin(uri: URI): URI? {
    val scheme = uri.scheme?.lowercase()
    val port = when (scheme) {
        "http" -> if (uri.port == 80) -1 else uri.port
        "https" -> if (uri.port == 443) -1 else uri.port
        else -> return null
    }
    if (uri.host == null || uri.userInfo != null || uri.fragment != null || port > 65_535) return null
    return URI(scheme, null, uri.host, port, null, null, null)
}

private fun positiveNanos(remaining: Long): Long {
    if (remaining <= 0) throw ReviewFailure(FailureReason.DEADLINE)
    return remaining
}

private fun Future<*>.finishedBy(deadline: Long): Boolean = awaitCleanup(deadline) {
    try {
        get(it, NANOSECONDS)
        true
    } catch (_: TimeoutException) {
        false
    } catch (_: ExecutionException) {
        false
    } catch (_: CancellationException) {
        false
    }
}

private inline fun awaitCleanup(deadline: Long, await: (Long) -> Boolean): Boolean {
    var interrupted = Thread.interrupted()
    try {
        while (true) {
            val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
            try {
                return await(remaining)
            } catch (_: InterruptedException) {
                interrupted = true
                if (remaining == 0L) return false
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}
