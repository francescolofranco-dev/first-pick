package com.firstpick.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class LiveDeckAccessStatus { CHECKING, ACCESS_REQUIRED, GRANTING, CONNECTED, UNSUPPORTED, FAILED }

data class LiveDeckAccess(
    val status: LiveDeckAccessStatus = LiveDeckAccessStatus.CHECKING,
    val detail: String = "Checking live deck access.",
) {
    val canGrant: Boolean get() = status == LiveDeckAccessStatus.ACCESS_REQUIRED || status == LiveDeckAccessStatus.FAILED
}

internal data class ArenaCardQuantity(val grpId: Int, val count: Int)
internal data class ArenaMemoryDeck(
    val editorSession: String,
    val main: List<ArenaCardQuantity>,
    val pool: List<ArenaCardQuantity>,
    val total: Int,
)

internal sealed interface ArenaMemoryRead {
    data class Ready(val deck: ArenaMemoryDeck) : ArenaMemoryRead
    data class Paused(val reason: String, val outsideBuilder: Boolean = false) : ArenaMemoryRead
    data class Unavailable(val reason: String, val screenFallback: Boolean) : ArenaMemoryRead
}

/** Only typed deck snapshots cross the production socket. No raw-address API. */
class ArenaMemoryReader(
    private val helper: File? = NativeHelpers.extract("/native/macos/arena-memory", "arena-memory"),
) : AutoCloseable {
    private data class Session(val directory: Path, val token: String) {
        val socket: Path get() = directory.resolve("read.sock")
    }
    private val state = MutableStateFlow(LiveDeckAccess())
    val access = state.asStateFlow()
    private val granting = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var session: Session? = null
    @Volatile private var grantProcess: Process? = null
    private var worker: Process? = null
    private var workerInput: java.io.BufferedWriter? = null
    private var workerOutput: java.io.BufferedReader? = null
    private val responses = Executors.newSingleThreadExecutor { Thread(it, "arena-memory-response").apply { isDaemon = true } }

    internal fun read(): ArenaMemoryRead {
        if (closed.get()) return ArenaMemoryRead.Unavailable("Live deck sync is closed.", true)
        val current = session
        val payload = try {
            if (current != null) rpc(current, "snapshot") else workerSnapshot()
        } catch (_: Exception) {
            if (current != null) {
                session = null
                removeDirectory(current)
                state.value = LiveDeckAccess(LiveDeckAccessStatus.ACCESS_REQUIRED, "The Arena connection ended. Grant access again to reconnect.")
            }
            return ArenaMemoryRead.Unavailable("Waiting for a fresh Arena deck reading.", current == null)
        }
        val result = parseArenaMemoryRead(payload)
        when ((payload["status"] as? JsonPrimitive)?.contentOrNull) {
            "ready", "blocked", "outside_builder", "changing" -> if (!granting.get()) {
                state.value = LiveDeckAccess(LiveDeckAccessStatus.CONNECTED, "Live deck access is connected to Arena.")
            }
            "access_required" -> if (!granting.get() && state.value.status !in setOf(LiveDeckAccessStatus.FAILED, LiveDeckAccessStatus.UNSUPPORTED)) {
                state.value = LiveDeckAccess(LiveDeckAccessStatus.ACCESS_REQUIRED, "Read your complete deck without scrolling. Screen reading is available meanwhile.")
            }
            "unsupported_build" -> if (!granting.get()) {
                state.value = LiveDeckAccess(LiveDeckAccessStatus.UNSUPPORTED, "Live sync does not support this Arena build yet. Using screen reading.")
            }
            "arena_closed" -> {
                if (current != null) { session = null; removeDirectory(current) }
                if (!granting.get()) state.value = LiveDeckAccess(LiveDeckAccessStatus.CHECKING, "Open Arena to connect live deck sync.")
            }
        }
        return result
    }

    /** Called only by the explicit Grant Arena access button. macOS owns the password prompt. */
    fun grantAccess() {
        if (closed.get() || !granting.compareAndSet(false, true)) return
        var candidate: Session? = null
        try {
            val binary = helper ?: error("Native reader is unavailable")
            state.value = LiveDeckAccess(LiveDeckAccessStatus.GRANTING, "Complete the macOS permission prompt to connect Arena.")
            val directory = Files.createTempDirectory(Path.of("/tmp"), "fp-live-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val pendingSession = Session(directory, token)
            candidate = pendingSession
            val process = ProcessBuilder(binary.absolutePath, "--grant", pendingSession.socket.toString(), token,
                ProcessHandle.current().pid().toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            grantProcess = process
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (!closed.get() && System.nanoTime() < deadline) {
                val bootstrap = pendingSession.directory.resolve("bootstrap.json")
                if (Files.exists(bootstrap) && Files.size(bootstrap) <= 4096) {
                    val error = parseObject(Files.readString(bootstrap))
                    val reason = (error["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(240)
                    val unsupported = (error["status"] as? JsonPrimitive)?.contentOrNull == "unsupported_build"
                    state.value = LiveDeckAccess(if (unsupported) LiveDeckAccessStatus.UNSUPPORTED else LiveDeckAccessStatus.FAILED,
                        reason.ifBlank { "The live Arena reader could not start." })
                    return
                }
                if (Files.exists(pendingSession.socket)) {
                    val answer = runCatching { rpc(pendingSession, "snapshot") }.getOrNull()
                    val status = answer?.get("status")?.jsonPrimitive?.contentOrNull
                    if (status in setOf("ready", "blocked", "outside_builder", "changing")) {
                        session?.let { runCatching { rpc(it, "close") }; removeDirectory(it) }
                        session = pendingSession
                        candidate = null
                        state.value = LiveDeckAccess(LiveDeckAccessStatus.CONNECTED, "Live deck access is connected to Arena.")
                        return
                    }
                    if (status == "unsupported_build") {
                        state.value = LiveDeckAccess(LiveDeckAccessStatus.UNSUPPORTED,
                            (answer["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(240)
                                .ifBlank { "Live sync could not verify this Arena layout. Using screen reading." })
                        return
                    }
                }
                if (!process.isAlive) {
                    val response = parseObject(process.inputStream.bufferedReader().readText().take(65536))
                    val status = (response["status"] as? JsonPrimitive)?.contentOrNull
                    val reason = (response["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(240)
                    state.value = LiveDeckAccess(when (status) {
                        "unsupported_build" -> LiveDeckAccessStatus.UNSUPPORTED
                        "access_required" -> LiveDeckAccessStatus.ACCESS_REQUIRED
                        else -> LiveDeckAccessStatus.FAILED
                    }, reason.ifBlank { "The live Arena reader ended before connecting." })
                    return
                }
                Thread.sleep(150)
            }
            error("Arena session did not become ready")
        } catch (_: Exception) {
            if (!closed.get()) state.value = LiveDeckAccess(LiveDeckAccessStatus.FAILED,
                "Arena access could not be established. Open Arena and try again; screen reading remains available.")
        } finally {
            candidate?.let { runCatching { rpc(it, "close") }; removeDirectory(it) }
            if (candidate != null) grantProcess?.destroy()
            if (candidate != null || session == null) grantProcess = null
            granting.set(false)
        }
    }

    @Synchronized
    private fun workerSnapshot(): JsonObject {
        val binary = helper ?: return buildJsonObject { put("status", "unsupported_build") }
        if (worker?.isAlive != true) {
            stopWorker()
            worker = ProcessBuilder(binary.absolutePath, "--worker").redirectError(ProcessBuilder.Redirect.DISCARD).start()
            workerInput = worker!!.outputStream.bufferedWriter()
            workerOutput = worker!!.inputStream.bufferedReader()
        }
        workerInput!!.apply { write("snapshot\n"); flush() }
        val reader = workerOutput!!
        val pending = responses.submit<String?> { reader.readLine() }
        val line = try { pending.get(2500, TimeUnit.MILLISECONDS) } catch (error: Exception) {
            stopWorker(); pending.cancel(true); throw error
        } ?: error("Reader closed")
        require(line.length <= 65536)
        return parseObject(line)
    }

    private fun rpc(session: Session, op: String): JsonObject {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            Selector.open().use { selector ->
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500)
                val key = channel.register(selector, SelectionKey.OP_CONNECT)
                fun awaitReady(interest: Int) {
                    key.interestOps(interest)
                    val remaining = deadline - System.nanoTime()
                    check(remaining > 0) { "Live reader timed out" }
                    selector.select(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
                    selector.selectedKeys().clear()
                }
                channel.connect(UnixDomainSocketAddress.of(session.socket))
                while (!channel.finishConnect()) awaitReady(SelectionKey.OP_CONNECT)
                val request = ByteBuffer.wrap((buildJsonObject { put("op", op); put("token", session.token) }.toString() + "\n").toByteArray())
                while (request.hasRemaining()) if (channel.write(request) == 0) awaitReady(SelectionKey.OP_WRITE)
                val buffer = ByteBuffer.allocate(65536)
                while (buffer.hasRemaining()) {
                    val count = channel.read(buffer)
                    check(count >= 0) { "Arena connection closed" }
                    if (count == 0) { awaitReady(SelectionKey.OP_READ); continue }
                    if (buffer.get(buffer.position() - 1) == 10.toByte()) return parseObject(String(buffer.array(), 0, buffer.position()))
                }
                error("Arena response exceeds bounds")
            }
        }
    }

    private fun removeDirectory(session: Session) {
        runCatching { Files.deleteIfExists(session.socket) }
        runCatching { Files.deleteIfExists(session.directory.resolve("bootstrap.json")) }
        runCatching { Files.deleteIfExists(session.directory) }
    }
    private fun stopWorker() {
        worker?.let { process ->
            process.destroy()
            if (!process.waitFor(150, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            runCatching { process.inputStream.close() }; runCatching { process.outputStream.close() }
        }
        worker = null; workerInput = null; workerOutput = null
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        grantProcess?.destroy()
        session?.let { runCatching { rpc(it, "close") }; removeDirectory(it) }
        session = null
        synchronized(this) { stopWorker() }
        responses.shutdownNow()
    }
    private fun parseObject(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
}

internal fun parseArenaMemoryRead(objectValue: JsonObject): ArenaMemoryRead = runCatching {
    val status = objectValue["status"]?.jsonPrimitive?.contentOrNull
    val reason = objectValue["reason"]?.jsonPrimitive?.contentOrNull.orEmpty().take(240)
    when (status) {
        "ready" -> {
            val key = objectValue["editor_session"]?.jsonPrimitive?.contentOrNull ?: error("No editor identity")
            require(key.matches(Regex("[a-f0-9]{64}")))
            fun pile(field: String, maxCopies: Int): List<ArenaCardQuantity> {
                val rows = objectValue[field] as? JsonArray ?: error("Missing pile")
                require(rows.size <= 2048)
                val cards = rows.map {
                    val row = it as? JsonArray ?: error("Invalid card")
                    require(row.size == 2)
                    val id = row[0].jsonPrimitive.intOrNull ?: error("Invalid ID")
                    val count = row[1].jsonPrimitive.intOrNull ?: error("Invalid quantity")
                    require(id > 0 && count in 1..300)
                    ArenaCardQuantity(id, count)
                }
                require(cards.distinctBy { it.grpId }.size == cards.size && cards.sumOf { it.count } <= maxCopies)
                return cards
            }
            val main = pile("main", 300)
            pile("sideboard", 300)
            val pool = pile("pool", 10000)
            val total = objectValue["total"]?.jsonPrimitive?.intOrNull ?: error("Missing total")
            require(total == main.sumOf { it.count })
            ArenaMemoryRead.Ready(ArenaMemoryDeck(key, main, pool, total))
        }
        "blocked", "changing" -> ArenaMemoryRead.Paused(reason.ifBlank { "Checking recent Arena changes." })
        "outside_builder" -> ArenaMemoryRead.Paused(reason, outsideBuilder = true)
        "access_required", "unsupported_build", "arena_closed" -> ArenaMemoryRead.Unavailable(reason, screenFallback = true)
        else -> ArenaMemoryRead.Unavailable(reason.ifBlank { "Waiting for a fresh Arena deck reading." }, screenFallback = false)
    }
}.getOrElse { ArenaMemoryRead.Unavailable("Arena returned an incomplete or inconsistent deck snapshot.", screenFallback = false) }
