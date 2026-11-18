package aireviewer

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.InetAddress
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HttpTransportTest {
    @Test
    fun headersWithoutBody() {
        abortsStalledResponse(byteArrayOf())
    }

    @Test
    fun stalledMidBody() {
        abortsStalledResponse("{".toByteArray())
    }

    private fun abortsStalledResponse(prefix: ByteArray) {
        val disconnected = CountDownLatch(1)
        RawServer { socket, _ ->
            respond(socket, length = 100, body = prefix)
            try {
                assertEquals(-1, socket.getInputStream().read())
            } catch (_: SocketException) {
                // The OS can signal cancellation with either EOF or a reset.
            }
            disconnected.countDown()
        }.use { server ->
            val probe = BodyProbe()
            val client = realClient(probe)
            transport(client, server.origin, budgetMillis = 450).use { transport ->
                val elapsed = elapsedMillis { assertFailure(FailureReason.DEADLINE) { transport.send(request(server.origin)) } }
                assertWithinDeadlineAndGrace(elapsed, 450)
                assertReaderStopped(probe)
                disconnected.awaitEvent()
            }
        }
    }

    @Test
    fun tricklingBody() {
        val bytesWritten = AtomicInteger()
        RawServer { socket, _ ->
            respond(socket, length = 100)
            val output = socket.getOutputStream()
            while (bytesWritten.get() < 20) {
                try {
                    output.write('x'.code)
                    output.flush()
                    bytesWritten.incrementAndGet()
                    Thread.sleep(50)
                } catch (_: SocketException) {
                    break
                }
            }
        }.use { server ->
            val probe = BodyProbe()
            val client = realClient(probe)
            transport(client, server.origin, budgetMillis = 200).use { transport ->
                val elapsed = elapsedMillis { assertFailure(FailureReason.DEADLINE) { transport.send(request(server.origin)) } }
                assertWithinDeadlineAndGrace(elapsed, 200)
                assertTrue(bytesWritten.get() in 1..19)
                assertReaderStopped(probe)
            }
        }
    }

    @Test
    fun lateStreamAfterTimeout() {
        val permitHeaders = CountDownLatch(1)
        val noticedInterrupt = CountDownLatch(1)
        val probe = BodyProbe()
        val client = ControlledHttpClient { request ->
            try {
                permitHeaders.await()
            } catch (_: InterruptedException) {
                noticedInterrupt.countDown()
                permitHeaders.awaitIgnoringInterrupts()
            }
            stubResponse(request, probe.wrap(ByteArrayInputStream("late".toByteArray())))
        }

        transport(client).use { transport ->
            RunningCall { assertFailure(FailureReason.DEADLINE) { transport.send(request(timeoutMillis = 200)) } }
                .use { caller ->
                    try {
                        noticedInterrupt.awaitEvent()
                        permitHeaders.countDown()
                        caller.result()
                        assertEquals(0, probe.readCalls.get())
                        assertEquals(0, probe.activeReads.get())
                        assertEquals(1, probe.closedCount.get())
                    } finally {
                        permitHeaders.countDown()
                    }
                }
        }
    }

    @Test
    fun completionRacesDeadline() {
        val allowEof = CountDownLatch(1)
        val noticedInterrupt = CountDownLatch(1)
        val probe = BodyProbe()
        val client = ControlledHttpClient { request ->
            val body = if (request.uri().path == "/race") {
                probe.wrap(controlledBody(read = {
                    try {
                        allowEof.await()
                    } catch (_: InterruptedException) {
                        noticedInterrupt.countDown()
                        allowEof.awaitIgnoringInterrupts()
                    }
                    -1
                }))
            } else {
                ByteArrayInputStream("next".toByteArray())
            }
            stubResponse(request, body)
        }

        transport(client).use { transport ->
            RunningCall {
                assertFailure(FailureReason.DEADLINE) {
                    transport.send(request(path = "/race", timeoutMillis = 200))
                }
            }.use { caller ->
                try {
                    noticedInterrupt.awaitEvent()
                    allowEof.countDown()
                    caller.result()
                    assertReaderStopped(probe)
                    assertEquals("next", transport.send(request()).body)
                    assertEquals(2, client.sendCalls.get())
                } finally {
                    allowEof.countDown()
                }
            }
        }
    }

    @Test
    fun boundedBody() {
        val payload = ByteArray(1_048_577) { 'x'.code.toByte() }
        RawServer { socket, headers ->
            val length = if (headers.startsWith("GET /at-limit ")) payload.size - 1 else payload.size
            respond(socket, length, payload, bodyLength = length)
        }.use { server ->
            val probe = BodyProbe()
            transport(realClient(probe), server.origin).use { transport ->
                assertEquals(1_048_576, transport.send(request(server.origin, "/at-limit")).body.length)
                assertEquals(1, probe.closedCount.get())
                assertFailure(FailureReason.LIMIT) { transport.send(request(server.origin, "/above-limit")) }
                assertEquals(2, probe.closedCount.get())
                assertEquals(0, probe.activeReads.get())
            }
        }
    }

    @Test
    fun redirectsBlocked() {
        val targetRequests = AtomicInteger()
        RawServer { socket, _ ->
            targetRequests.incrementAndGet()
            respond(socket, 0)
        }.use { target ->
            val originHeaders = AtomicReference<String>()
            RawServer { socket, headers ->
                originHeaders.set(headers)
                respond(socket, 0, status = "302 Found", extraHeaders = "Location: ${target.origin}/target\r\n")
            }.use { origin ->
                transport(realClient(BodyProbe()), origin.origin).use { transport ->
                    assertEquals(302, transport.send(request(origin.origin)).status)
                    assertTrue(assertNotNull(originHeaders.get()).contains("Authorization: Bearer fake-test-token", true))
                    assertEquals(0, targetRequests.get())
                    assertFailure(FailureReason.CONFIG) { transport.send(request(target.origin)) }
                    assertEquals(0, targetRequests.get())
                }
            }
        }
    }

    @Test
    fun droppedResponseDoesNotRetry() {
        val received = AtomicInteger()
        val warmupPort = AtomicInteger()
        val firstDropPort = AtomicInteger()
        RawServer(keepAlive = true) { socket, headers ->
            if (headers.startsWith("GET /warmup ")) {
                warmupPort.set(socket.port)
                respond(socket, 0, connection = "keep-alive")
            } else {
                firstDropPort.compareAndSet(0, socket.port)
                received.incrementAndGet()
                // JDK can retry a GET whose pooled connection drops before response headers.
                socket.close()
            }
        }.use { server ->
            transport(realClient(BodyProbe()), server.origin).use { transport ->
                assertEquals(200, transport.send(request(server.origin, "/warmup")).status)
                assertFailure(FailureReason.HTTP) { transport.send(request(server.origin)) }
                assertEquals(warmupPort.get(), firstDropPort.get())
                assertEquals(1, received.get())
            }
        }
    }

    @Test
    fun interruptedCaller() {
        val probe = BodyProbe()
        val allowReadToEnd = CountDownLatch(1)
        val client = ControlledHttpClient { request ->
            stubResponse(request, probe.wrap(controlledBody(
                read = { allowReadToEnd.awaitIgnoringInterrupts(); -1 },
                close = { allowReadToEnd.countDown() },
            )))
        }
        transport(client).use { transport ->
            RunningCall {
                assertFailure(FailureReason.DEADLINE) { transport.send(request()) }
                Thread.currentThread().isInterrupted
            }.use { caller ->
                try {
                    probe.readStarted.awaitEvent()
                    caller.interrupt()
                    assertTrue(caller.result())
                    assertReaderStopped(probe)
                    assertFailure(FailureReason.HTTP) { transport.send(request()) }
                    assertEquals(1, client.sendCalls.get())
                } finally {
                    allowReadToEnd.countDown()
                }
            }
        }
    }

    @Test
    fun alreadyInterruptedCallerDoesNotStartAnExchange() {
        val clockReads = AtomicInteger()
        val budget = ReviewBudget(System.nanoTime() + Duration.ofSeconds(5).toNanos()) {
            clockReads.incrementAndGet()
            System.nanoTime()
        }
        val client = ControlledHttpClient { error("No exchange expected") }
        DeadlineHttpTransport(client, budget, setOf(FAKE_ORIGIN)).use { transport ->
            RunningCall {
                Thread.currentThread().interrupt()
                assertFailure(FailureReason.DEADLINE) { transport.send(request()) }
                Thread.currentThread().isInterrupted
            }.use { caller ->
                assertTrue(caller.result())
            }
            assertEquals(0, clockReads.get())
            assertEquals(0, client.sendCalls.get())
            assertFailure(FailureReason.HTTP) { transport.send(request()) }
        }
    }

    @Test
    fun remainingReviewBudget() {
        val probe = BodyProbe()
        val allowReadToEnd = CountDownLatch(1)
        val client = ControlledHttpClient { request ->
            stubResponse(request, probe.wrap(controlledBody(
                read = { allowReadToEnd.awaitIgnoringInterrupts(); -1 },
                close = { allowReadToEnd.countDown() },
            )))
        }
        try {
            transport(client, budgetMillis = 100).use { transport ->
                val elapsed = elapsedMillis { assertFailure(FailureReason.DEADLINE) { transport.send(request()) } }
                assertWithinDeadlineAndGrace(elapsed, 100)
                assertTrue(assertNotNull(client.lastRequest.get()).timeout().orElseThrow() <= Duration.ofMillis(100))
                assertReaderStopped(probe)
                assertFailure(FailureReason.DEADLINE) { transport.send(request()) }
                assertEquals(1, client.sendCalls.get())
            }
        } finally {
            allowReadToEnd.countDown()
        }
    }

    @Test
    fun remainingBudgetUsesTheOriginalMonotonicDeadline() {
        var now = 100L
        val budget = ReviewBudget(1_000L) { now }
        assertEquals(Duration.ofNanos(900), budget.remaining())
        now = 950
        assertEquals(Duration.ofNanos(50), budget.remaining())
        now = 1_000
        assertFailure(FailureReason.DEADLINE) { budget.remaining() }
    }

    @Test
    fun cleanupFailureStopsClient() {
        val releaseRead = CountDownLatch(1)
        val probe = BodyProbe()
        val client = ControlledHttpClient { request ->
            stubResponse(request, probe.wrap(controlledBody(read = { releaseRead.awaitIgnoringInterrupts(); -1 })))
        }
        val transport = transport(client)
        try {
            RunningCall {
                elapsedMillis { assertFailsWith<TransportCleanupFailure> { transport.send(request(timeoutMillis = 200)) } }
            }.use { caller ->
                try {
                    assertWithinDeadlineAndGrace(caller.result(), 200)
                    assertEquals(1, probe.activeReads.get())
                    assertEquals(1, probe.closedCount.get())
                    assertFailsWith<TransportCleanupFailure> { transport.send(request()) }
                    assertEquals(1, client.sendCalls.get())
                } finally {
                    releaseRead.countDown()
                }
            }
            probe.firstReadEnded.awaitEvent()
        } finally {
            releaseRead.countDown()
            transport.close()
        }
    }

    @Test
    fun blockingCloseIsBounded() {
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val probe = BodyProbe()
        val client = ControlledHttpClient { request ->
            val body = controlledBody(
                read = { closeStarted.awaitIgnoringInterrupts(); -1 },
                close = { closeStarted.countDown(); releaseClose.awaitIgnoringInterrupts() },
            )
            stubResponse(request, probe.wrap(body))
        }
        val transport = transport(client)
        try {
            RunningCall {
                elapsedMillis { assertFailsWith<TransportCleanupFailure> { transport.send(request(timeoutMillis = 200)) } }
            }.use { caller ->
                try {
                    assertWithinDeadlineAndGrace(caller.result(), 200)
                    probe.firstReadEnded.awaitEvent()
                    assertEquals(0, probe.closedCount.get())
                    assertFailsWith<TransportCleanupFailure> { transport.send(request()) }
                    assertEquals(1, client.sendCalls.get())
                } finally {
                    closeStarted.countDown()
                    releaseClose.countDown()
                }
            }
            probe.firstCloseEnded.awaitEvent()
        } finally {
            closeStarted.countDown()
            releaseClose.countDown()
            transport.close()
        }
    }

    @Test
    fun blockingClientShutdownIsBounded() {
        val shutdownStarted = CountDownLatch(1)
        val releaseShutdown = CountDownLatch(1)
        val shutdownExited = CountDownLatch(1)
        val client = ControlledHttpClient(shutdown = {
            shutdownStarted.countDown()
            try {
                releaseShutdown.awaitIgnoringInterrupts()
            } finally {
                shutdownExited.countDown()
            }
        }) { error("No exchange expected") }
        val transport = transport(client)
        try {
            RunningCall { elapsedMillis { assertFailsWith<TransportCleanupFailure> { transport.close() } } }.use { caller ->
                try {
                    assertWithinDeadlineAndGrace(caller.result(), 0)
                    shutdownStarted.awaitEvent()
                    assertFailsWith<TransportCleanupFailure> { transport.send(request()) }
                    assertEquals(0, client.sendCalls.get())
                } finally {
                    releaseShutdown.countDown()
                }
            }
            shutdownExited.awaitEvent()
        } finally {
            releaseShutdown.countDown()
            transport.close()
        }
    }

    private fun realClient(probe: BodyProbe): ControlledHttpClient {
        val real = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build()
        return ControlledHttpClient(real = real) { request ->
            val response = real.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val stream = probe.wrap(response.body())
            object : HttpResponse<InputStream> by response {
                override fun body(): InputStream = stream
            }
        }
    }

    private fun assertReaderStopped(probe: BodyProbe) {
        assertTrue(probe.readCalls.get() > 0, "The exchange did not reach the body reader")
        assertEquals(0, probe.activeReads.get(), "A body read is still active")
        assertEquals(1, probe.closedCount.get(), "The response stream did not finish closing")
    }

    private fun assertFailure(reason: FailureReason, action: () -> Unit) {
        assertEquals(reason, assertFailsWith<ReviewFailure>(block = action).reason)
    }

    private fun assertWithinDeadlineAndGrace(elapsed: Long, deadlineMillis: Long) {
        assertTrue(elapsed in 0..(deadlineMillis + 1_000 + 250), "Exchange took $elapsed ms")
    }

    private fun transport(client: HttpClient, origin: URI = FAKE_ORIGIN, budgetMillis: Long = 5_000) =
        DeadlineHttpTransport(client, ReviewBudget(System.nanoTime() + Duration.ofMillis(budgetMillis).toNanos()), setOf(origin))

    private fun request(origin: URI = FAKE_ORIGIN, path: String = "/", timeoutMillis: Long = 15_000) =
        HttpRequest.newBuilder(origin.resolve(path))
            .timeout(Duration.ofMillis(timeoutMillis))
            .header("Authorization", "Bearer fake-test-token")
            .GET()
            .build()

    private fun stubResponse(request: HttpRequest, stream: InputStream): HttpResponse<InputStream> =
        object : HttpResponse<InputStream> {
            override fun body() = stream
            override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
            override fun request() = request
            override fun statusCode() = 200
            override fun uri(): URI = request.uri()
            override fun version() = HttpClient.Version.HTTP_1_1
            override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()
            override fun sslSession(): Optional<SSLSession> = Optional.empty()
        }

    private fun controlledBody(read: () -> Int, close: () -> Unit = {}): InputStream =
        object : InputStream() {
            override fun read(): Int = read.invoke()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read.invoke()
            override fun close() = close.invoke()
        }

    private class BodyProbe {
        val readCalls = AtomicInteger()
        val activeReads = AtomicInteger()
        val closedCount = AtomicInteger()
        val readStarted = CountDownLatch(1)
        val firstReadEnded = CountDownLatch(1)
        val firstCloseEnded = CountDownLatch(1)

        fun wrap(stream: InputStream): InputStream = object : FilterInputStream(stream) {
            override fun read(): Int = observeRead { super.read() }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                observeRead { super.read(bytes, offset, length) }

            override fun close() {
                super.close()
                closedCount.incrementAndGet()
                firstCloseEnded.countDown()
            }
        }

        private inline fun observeRead(action: () -> Int): Int {
            readCalls.incrementAndGet()
            activeReads.incrementAndGet()
            readStarted.countDown()
            try {
                return action()
            } finally {
                activeReads.decrementAndGet()
                firstReadEnded.countDown()
            }
        }
    }

    private class ControlledHttpClient(
        private val real: HttpClient? = null,
        private val shutdown: () -> Unit = {},
        private val response: (HttpRequest) -> HttpResponse<InputStream>,
    ) : HttpClient() {
        val sendCalls = AtomicInteger()
        val lastRequest = AtomicReference<HttpRequest>()

        @Suppress("UNCHECKED_CAST")
        override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            sendCalls.incrementAndGet()
            lastRequest.set(request)
            return response(request) as HttpResponse<T>
        }

        override fun shutdownNow() {
            shutdown.invoke()
            real?.shutdownNow()
        }

        override fun awaitTermination(duration: Duration): Boolean = real?.awaitTermination(duration) ?: true
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.empty()
        override fun followRedirects() = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters() = SSLParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version() = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()
        override fun close(): Unit = error("Unbounded HttpClient.close")
        override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>):
            CompletableFuture<HttpResponse<T>> = error("Unexpected async exchange")

        override fun <T> sendAsync(
            request: HttpRequest, handler: HttpResponse.BodyHandler<T>, push: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = error("Unexpected async exchange")
    }

    private class RunningCall<T>(action: () -> T) : AutoCloseable {
        private val task = FutureTask(action)
        private val thread = Thread.ofPlatform().daemon().name("test-http-caller").start(task)

        fun result(): T = task.get(4, TimeUnit.SECONDS)
        fun interrupt() = thread.interrupt()

        override fun close() {
            thread.join(1_000)
            if (thread.isAlive) {
                thread.interrupt()
                thread.join(1_000)
            }
            assertFalse(thread.isAlive, "The caller thread did not exit")
        }
    }

    private class RawServer(keepAlive: Boolean = false, handler: (Socket, String) -> Unit) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val active = AtomicReference<Socket>()
        val origin = URI("http://127.0.0.1:${listener.localPort}")
        private val executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("test-http-server").factory())
        private val serving = executor.submit {
            while (!listener.isClosed) {
                val socket = try {
                    listener.accept()
                } catch (failure: SocketException) {
                    if (listener.isClosed) break
                    throw failure
                }
                active.set(socket)
                socket.use {
                    it.soTimeout = 3_000
                    val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                    do {
                        val requestLine = reader.readLine() ?: break
                        val headers = buildString {
                            appendLine(requestLine)
                            while (true) {
                                val line = reader.readLine()
                                if (line.isNullOrEmpty()) break
                                appendLine(line)
                            }
                        }
                        handler(it, headers)
                    } while (keepAlive && !it.isClosed)
                }
                active.set(null)
            }
        }

        override fun close() {
            listener.close()
            val socket = active.get()
            // Give any handler that already observed cancellation time to finish.
            executor.shutdown()
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                socket?.close()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS), "The loopback server did not stop")
            }
            serving.get()
        }
    }

    private companion object {
        val FAKE_ORIGIN = URI("http://127.0.0.1:1")

        fun respond(
            socket: Socket,
            length: Int,
            body: ByteArray = byteArrayOf(),
            bodyLength: Int = body.size,
            status: String = "200 OK",
            extraHeaders: String = "",
            connection: String = "close",
        ) {
            val output = socket.getOutputStream()
            val headers = "HTTP/1.1 $status\r\nContent-Length: $length\r\nConnection: $connection\r\n$extraHeaders\r\n"
            output.write(headers.toByteArray())
            output.write(body, 0, bodyLength)
            output.flush()
        }

        fun CountDownLatch.awaitEvent() = assertTrue(await(3, TimeUnit.SECONDS), "Timed out waiting for test event")

        fun CountDownLatch.awaitIgnoringInterrupts() {
            while (true) {
                try {
                    await()
                    return
                } catch (_: InterruptedException) {
                    // Deliberately exercise a reader or closer that ignores worker interruption.
                }
            }
        }

        inline fun elapsedMillis(action: () -> Unit): Long {
            val start = System.nanoTime()
            action()
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        }
    }
}
