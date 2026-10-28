package com.freeturn.app.domain.diagnostics

import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// A bounded queue keeps disk writes and archive compression off VPN/UI readers.
class DiagnosticRecorder(
    directory: File,
    private val exportDirectory: File,
    private val sessionInfo: String,
    capacityBytes: Long = 50L * 1024 * 1024,
    segmentBytes: Long = 5L * 1024 * 1024,
) {
    data class Status(val ready: Boolean = false, val bytes: Long = 0, val dropped: Long = 0, val writeError: Boolean = false)
    private sealed interface Command {
        data class Line(val text: String, val time: Long) : Command
        data class Export(val result: CompletableDeferred<File>) : Command
        data class Clear(val result: CompletableDeferred<Unit>) : Command
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = Channel<Command>(256)
    private val ready = CompletableDeferred<Unit>()
    private val pendingDrops = AtomicLong()
    private val totalDrops = AtomicLong()
    private val secrets = AtomicReference<List<String>>(emptyList())
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val worker = scope.launch {
        try {
            RotatingLogStore(directory, capacityBytes, segmentBytes).use { store ->
                store.append("[APPLICATION] ${DiagnosticRedactor.redact(sessionInfo)}")
                _status.value = Status(ready = true, bytes = store.sizeBytes())
                ready.complete(Unit)
                for (command in queue) {
                    try {
                        val lost = pendingDrops.getAndSet(0)
                        if (lost > 0) store.append("[LOGGER] Dropped $lost entries: writer queue was full")
                        when (command) {
                            is Command.Line -> store.append(command.text, command.time)
                            is Command.Export -> command.result.complete(store.exportArchive(
                                File(exportDirectory, "fturn-diagnostics.zip"),
                                DiagnosticRedactor.redact(sessionInfo) + "\nJournal limit: 50 MiB\nDropped entries: ${totalDrops.get()}\n"))
                            is Command.Clear -> {
                                store.clear()
                                for (name in listOf("fturn-diagnostics.zip", "fturn-diagnostics.zip.part")) {
                                    val cached = File(exportDirectory, name)
                                    check(!cached.exists() || cached.delete()) { "Cannot remove cached diagnostic archive" }
                                }
                                command.result.complete(Unit)
                            }
                        }
                        _status.value = Status(true, store.sizeBytes(), totalDrops.get())
                    } catch (e: Exception) {
                        _status.value = Status(true, store.sizeBytes(), totalDrops.get(), writeError = true)
                        when (command) {
                            is Command.Export -> command.result.completeExceptionally(e)
                            is Command.Clear -> command.result.completeExceptionally(e)
                            is Command.Line -> { pendingDrops.incrementAndGet(); totalDrops.incrementAndGet() }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            _status.value = Status(writeError = true, dropped = totalDrops.get())
            ready.completeExceptionally(e)
            queue.close(e)
            while (true) {
                when (val pending = queue.tryReceive().getOrNull() ?: break) {
                    is Command.Export -> pending.result.completeExceptionally(e)
                    is Command.Clear -> pending.result.completeExceptionally(e)
                    is Command.Line -> totalDrops.incrementAndGet()
                }
            }
        }
    }

    fun registerSecrets(values: Iterable<String>) {
        val added = values.filter { it.length >= 4 }
        secrets.updateAndGet { (it + added).distinct().takeLast(128) }
    }

    fun append(message: String) {
        val line = DiagnosticRedactor.redact(message, secrets.get())
        if (queue.trySend(Command.Line(line, System.currentTimeMillis())).isFailure) {
            pendingDrops.incrementAndGet()
            totalDrops.incrementAndGet()
        }
    }

    suspend fun exportArchive(): File {
        ready.await()
        val result = CompletableDeferred<File>()
        queue.send(Command.Export(result))
        return result.await()
    }

    suspend fun clear() {
        ready.await()
        val result = CompletableDeferred<Unit>()
        queue.send(Command.Clear(result))
        result.await()
    }

    suspend fun close() {
        queue.close()
        worker.join()
        scope.cancel()
    }
}

object DiagnosticLogs {
    @Volatile private var recorder: DiagnosticRecorder? = null
    private val unavailable = MutableStateFlow(DiagnosticRecorder.Status())
    val status: StateFlow<DiagnosticRecorder.Status> get() = recorder?.status ?: unavailable

    @Synchronized
    fun initialize(directory: File, exportDirectory: File, sessionInfo: String) {
        if (recorder == null) recorder = DiagnosticRecorder(directory, exportDirectory, sessionInfo)
    }

    fun append(message: String) { recorder?.append(message) }
    fun registerSecrets(values: Iterable<String>) { recorder?.registerSecrets(values) }
    suspend fun exportArchive(): File = checkNotNull(recorder) { "Diagnostic journal unavailable" }.exportArchive()
    suspend fun clear() { recorder?.clear() }
}
