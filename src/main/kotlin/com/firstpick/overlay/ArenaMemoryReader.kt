package com.firstpick.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
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
    val detail: String = "Checking Arena access.",
    val savedApproval: Boolean = false,
) {
    val canGrant: Boolean get() = status == LiveDeckAccessStatus.ACCESS_REQUIRED || status == LiveDeckAccessStatus.FAILED
}

internal data class ArenaCardQuantity(val grpId: Int, val count: Int)
internal data class ArenaMemoryDeck(
    val editorSession: String,
    val main: List<ArenaCardQuantity>,
    val pool: List<ArenaCardQuantity>,
    val total: Int,
    val geometry: ArenaCardGeometry? = null,
)
internal data class ArenaMemoryPack(val editorSession: String, val cards: List<Int>, val picked: List<ArenaCardQuantity>,
    val geometry: ArenaCardGeometry? = null)
internal enum class MemoryUnavailableKind { ACCESS_REQUIRED, UNSUPPORTED, WAITING }
internal data class ArenaMemoryDiagnostics(val elapsedMs: Int? = null, val geometryReason: String? = null)

internal sealed interface ArenaMemoryRead {
    data class Ready(val deck: ArenaMemoryDeck, val diagnostics: ArenaMemoryDiagnostics = ArenaMemoryDiagnostics()) : ArenaMemoryRead
    data class PackReady(val pack: ArenaMemoryPack, val diagnostics: ArenaMemoryDiagnostics = ArenaMemoryDiagnostics()) : ArenaMemoryRead
    data class Paused(val reason: String, val outsideBuilder: Boolean = false, val resetPresentation: Boolean = false) : ArenaMemoryRead
    data class Unavailable(val reason: String, val kind: MemoryUnavailableKind = MemoryUnavailableKind.WAITING, val resetPresentation: Boolean = false) : ArenaMemoryRead
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
    private var nextReconnectAt = 0L
    private var worker: Process? = null
    private var workerInput: java.io.BufferedWriter? = null
    private var workerOutput: java.io.BufferedReader? = null
    private val responses = Executors.newSingleThreadExecutor { Thread(it, "arena-memory-response").apply { isDaemon = true } }

    internal fun read(): ArenaMemoryRead {
        if (closed.get()) return ArenaMemoryRead.Unavailable("Live Arena access is closed.")
        val current = session
        val payload = try {
            if (current != null) rpc(current, "snapshot") else workerSnapshot()
        } catch (_: Exception) {
            if (current != null) {
                grantProcess?.destroy(); grantProcess = null
                session = null
                removeDirectory(current)
                synchronized(this) { stopWorker() }
                state.value = LiveDeckAccess(LiveDeckAccessStatus.ACCESS_REQUIRED, "The Arena connection ended. Grant access again to reconnect.")
            }
            return ArenaMemoryRead.Unavailable("Grant Arena access in FirstPick to reconnect the overlay.", MemoryUnavailableKind.ACCESS_REQUIRED)
        }
        if ((payload["status"] as? JsonPrimitive)?.contentOrNull == "saved_access" && !granting.get()) {
            val now = System.nanoTime()
            if (now >= nextReconnectAt) {
                nextReconnectAt = now + TimeUnit.SECONDS.toNanos(5)
                grantAccess(allowInstall = false)
            }
            if (session != null) { nextReconnectAt = 0L; return read() }
            return ArenaMemoryRead.Unavailable(state.value.detail, MemoryUnavailableKind.ACCESS_REQUIRED)
        }
        val savedApproval = payload["approval_saved"]?.jsonPrimitive?.booleanOrNull ?: state.value.savedApproval
        val result = parseArenaMemoryRead(payload)
        when ((payload["status"] as? JsonPrimitive)?.contentOrNull) {
            "ready", "draft_ready", "blocked", "outside_builder", "changing" -> if (!granting.get()) {
                state.value = LiveDeckAccess(LiveDeckAccessStatus.CONNECTED, "Live Arena access is connected. Approval is saved on this Mac.", savedApproval = true)
            }
            "access_required" -> if (!granting.get()) {
                state.value = LiveDeckAccess(LiveDeckAccessStatus.ACCESS_REQUIRED, "Grant read-only access to enable Arena's live overlay.")
            }
            "unsupported_build" -> if (!granting.get()) {
                state.value = LiveDeckAccess(LiveDeckAccessStatus.UNSUPPORTED, "This Arena build is not supported yet. The overlay is paused.")
            }
            "arena_closed" -> {
                if (current != null) {
                    grantProcess?.destroy(); grantProcess = null
                    session = null; removeDirectory(current); synchronized(this) { stopWorker() }
                }
                if (!granting.get()) state.value = LiveDeckAccess(LiveDeckAccessStatus.CHECKING, "Open Arena to connect live deck sync.")
            }
        }
        state.value = state.value.copy(savedApproval = savedApproval)
        return result
    }

    /** Installation requires an explicit grant. Saved access reconnects without an administrator prompt. */
    fun grantAccess(allowInstall: Boolean = true) {
        if (closed.get() || !granting.compareAndSet(false, true)) return
        var candidate: Session? = null
        try {
            val binary = helper ?: error("Native reader is unavailable")
            state.value = LiveDeckAccess(LiveDeckAccessStatus.GRANTING, if (allowInstall) "Approve the read-only Arena helper once to remember access on this Mac." else "Reconnecting with saved Arena access…")
            val directory = Files.createTempDirectory(Path.of("/tmp"), "fp-live-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val pendingSession = Session(directory, token)
            candidate = pendingSession
            val process = ProcessBuilder(binary.absolutePath, if (allowInstall) "--grant" else "--connect", pendingSession.socket.toString(), token,
                ProcessHandle.current().pid().toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            grantProcess = process
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
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
                    if (status in setOf("ready", "draft_ready", "blocked", "outside_builder", "changing")) {
                        session?.let { runCatching { rpc(it, "close") }; removeDirectory(it) }
                        session = pendingSession
                        candidate = null
                        state.value = LiveDeckAccess(LiveDeckAccessStatus.CONNECTED, "Live Arena access is connected. Approval is saved on this Mac.", savedApproval = true)
                        return
                    }
                    if (status == "unsupported_build") {
                        state.value = LiveDeckAccess(LiveDeckAccessStatus.UNSUPPORTED,
                            (answer["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(240)
                                .ifBlank { "This Arena layout could not be verified. The overlay is paused." })
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
                "Arena access could not be established. Open Arena and grant access again to enable the overlay.")
        } finally {
            candidate?.let { runCatching { rpc(it, "close") }; removeDirectory(it) }
            if (candidate != null) grantProcess?.destroy()
            if (candidate != null || session == null) grantProcess = null
            granting.set(false)
        }
    }

    fun revokeAccess() {
        if (closed.get() || !granting.compareAndSet(false, true)) return
        try {
            val binary = helper ?: error("Native reader is unavailable")
            val process = ProcessBuilder(binary.absolutePath, "--revoke-access")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Revoke timed out") }
            val answer = parseObject(process.inputStream.bufferedReader().readText().take(65536))
            check(answer["status"]?.jsonPrimitive?.contentOrNull == "revoked")
            grantProcess?.destroy(); grantProcess = null
            session?.let { removeDirectory(it) }; session = null
            synchronized(this) { stopWorker() }
            state.value = LiveDeckAccess(LiveDeckAccessStatus.ACCESS_REQUIRED, "Saved Arena access was removed from this Mac.")
        } catch (_: Exception) {
            state.value = LiveDeckAccess(LiveDeckAccessStatus.FAILED, "Saved Arena access could not be removed. Try again.", savedApproval = true)
        } finally { granting.set(false) }
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
    val diagnostics = ArenaMemoryDiagnostics(
        objectValue["elapsed_ms"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..2500 },
        objectValue["geometry_reason"]?.jsonPrimitive?.contentOrNull?.take(240),
    )
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
    when (status) {
        "ready" -> {
            val key = objectValue["editor_session"]?.jsonPrimitive?.contentOrNull ?: error("No editor identity")
            require(key.matches(Regex("[a-f0-9]{64}")))
            val main = pile("main", 300)
            pile("sideboard", 300)
            val pool = pile("pool", 10000)
            val total = objectValue["total"]?.jsonPrimitive?.intOrNull ?: error("Missing total")
            require(total == main.sumOf { it.count })
            val geometry = parseMemoryCardGeometry(objectValue["geometry"] as? JsonObject)?.takeIf { frame ->
                frame.cards.all { card ->
                    val source = when (card.zone) {
                        ArenaCardZone.DECK, ArenaCardZone.COLUMN -> main
                        ArenaCardZone.POOL -> pool
                        ArenaCardZone.PACK -> emptyList()
                    }
                    source.any { it.grpId == card.grpId && it.count >= card.count }
                }
            }
            ArenaMemoryRead.Ready(ArenaMemoryDeck(key, main, pool, total, geometry), diagnostics)
        }
        "draft_ready" -> {
            val key = objectValue["editor_session"]?.jsonPrimitive?.contentOrNull ?: error("No draft identity")
            require(key.matches(Regex("[a-f0-9]{64}")))
            val cards = (objectValue["cards"] as? JsonArray)?.map { it.jsonPrimitive.intOrNull ?: error("Invalid ID") }
                ?: error("Missing draft cards")
            require(cards.size in 1..30 && cards.all { it > 0 })
            val geometry = parseMemoryCardGeometry(objectValue["geometry"] as? JsonObject)?.takeIf { frame ->
                frame.cards.all { it.zone == ArenaCardZone.PACK && it.count == 1 } && frame.cards.map { it.grpId } == cards
            }
            ArenaMemoryRead.PackReady(ArenaMemoryPack(key, cards, pile("picked", 300), geometry), diagnostics)
        }
        "blocked", "changing" -> ArenaMemoryRead.Paused(reason.ifBlank { "Checking recent Arena changes." },
            resetPresentation = status == "blocked" || objectValue["presentation_reset"]?.jsonPrimitive?.booleanOrNull == true)
        "outside_builder" -> ArenaMemoryRead.Paused(reason, outsideBuilder = true)
        "access_required" -> ArenaMemoryRead.Unavailable("Grant Arena access in FirstPick to enable the overlay.", MemoryUnavailableKind.ACCESS_REQUIRED)
        "unsupported_build" -> ArenaMemoryRead.Unavailable("FirstPick could not verify this Arena build. Check FirstPick for an update.", MemoryUnavailableKind.UNSUPPORTED)
        "arena_closed" -> ArenaMemoryRead.Unavailable("Open Arena to enable the live overlay.", resetPresentation = true)
        else -> ArenaMemoryRead.Unavailable("Waiting for a fresh Arena reading.")
    }
}.getOrElse { ArenaMemoryRead.Unavailable("Arena returned an incomplete or inconsistent snapshot.") }

/** Geometry can be withheld independently while exact card quantities remain usable. */
internal fun parseMemoryCardGeometry(value: JsonObject?): ArenaCardGeometry? = runCatching {
    if (value == null) return null
    val screen = value["screen"] as? JsonArray ?: error("No viewport")
    require(screen.size == 2)
    val width = screen[0].jsonPrimitive.intOrNull ?: error("No width")
    val height = screen[1].jsonPrimitive.intOrNull ?: error("No height")
    require(width in 320..16384 && height in 200..16384)
    val rows = value["cards"] as? JsonArray ?: error("No positions")
    require(rows.size <= 128)
    val positions = rows.map { entry ->
        val row = entry as? JsonArray ?: error("Invalid position")
        require(row.size == 7)
        val id = row[0].jsonPrimitive.intOrNull ?: error("Invalid identity")
        val count = row[1].jsonPrimitive.intOrNull ?: error("Invalid count")
        val zone = ArenaCardZone.entries.firstOrNull { it.name.equals(row[2].jsonPrimitive.contentOrNull, ignoreCase = true) }
            ?: error("Invalid card area")
        val coordinates = (0..3).map { row[it + 3].jsonPrimitive.doubleOrNull ?: error("Invalid bounds") }
        val rect = NormalizedRect(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
        require(id > 0 && count in 1..300 && rect.isValid())
        ArenaVisibleCard(id, count, zone, rect)
    }
    ArenaCardGeometry(width, height, positions)
}.getOrNull()
