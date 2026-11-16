package app.radiotube

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Saves tracks' audio on the phone, one at a time, so they play without a connection. */
object Downloads {
    const val QUEUED = -1

    /** Tracks being downloaded: id to percent done, or [QUEUED] while waiting their turn. */
    val progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeId: String? = null
    @Volatile private var activeProcess: Process? = null
    private var finished = 0
    private var failed = 0
    private var lastError = ""

    fun start(ids: List<String>) {
        val fresh = ids.distinct().filter { id ->
            val track = Library.get(id)
            track != null && !track.live && track.file == null && id !in progress.value
        }
        if (fresh.isEmpty()) return
        cancelled.removeAll(fresh.toSet())
        progress.update { it + fresh.associateWith { QUEUED } }
        for (id in fresh) worker.execute { download(id) }
    }

    fun cancel(ids: Collection<String>) {
        cancelled.addAll(ids)
        if (activeId in ids) activeProcess?.destroyForcibly()
        progress.update { it - ids.toSet() }
    }

    private fun download(id: String) {
        if (id in cancelled || Library.get(id) == null) {
            done(id)
            return
        }
        activeId = id
        try {
            val file = Yt.download(
                id, Library.audioDir,
                onProgress = { percent -> progress.update { if (id in it) it + (id to percent) else it } },
                onProcess = { activeProcess = it },
                cancelled = { id in cancelled },
            )
            if (id in cancelled || Library.get(id) == null) file.delete() else {
                Library.setFile(id, file.name)
                finished++
            }
        } catch (e: Exception) {
            if (id !in cancelled) {
                failed++
                lastError = e.message.orEmpty()
            }
            // Whatever was half written is useless without the rest.
            Library.audioDir.listFiles { f -> f.name.startsWith("$id.") }?.forEach { it.delete() }
        } finally {
            activeId = null
            activeProcess = null
            done(id)
        }
    }

    private fun done(id: String) {
        cancelled.remove(id)
        progress.update { it - id }
        if (progress.value.isNotEmpty() || finished + failed == 0) return
        Bus.notice.value = when {
            failed == 0 -> if (finished == 1) "Descarga completada" else "$finished descargas completadas"
            finished == 0 && failed == 1 -> "No se pudo descargar: $lastError"
            else -> "$finished descargadas, $failed fallidas"
        }
        finished = 0
        failed = 0
    }
}
