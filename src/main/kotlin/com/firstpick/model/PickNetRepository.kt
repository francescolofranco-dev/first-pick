package com.firstpick.model

import com.firstpick.core.AppPaths
import com.firstpick.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the learned pick model for a set+format. Models are trained offline
 * (training/train_picker.py) and never change at runtime. Lookup order:
 * user-overridable cache file, then the bundled resource. No model for a
 * set is a valid state: the advisor then ranks heuristically as before.
 */
class PickNetRepository(private val cacheDir: Path = AppPaths.cacheDir) {
    private val mutex = Mutex()
    private val bundledCache = ConcurrentHashMap<String, Boolean>()

    @Volatile
    var net: PickNet? = null
        private set

    @Volatile
    var loadedKey: String? = null
        private set

    suspend fun load(setCode: String, format: String) {
        val key = key(setCode, format)
        if (key == loadedKey) return
        mutex.withLock {
            if (key == loadedKey) return
            net = withContext(Dispatchers.IO) { loadNet(key) }
            loadedKey = key
            Log.info(TAG, "pick model for $key: ${net?.let { "${it.cards.size} cards" } ?: "none"}")
        }
    }

    /** The loaded model, but only if it is the one for [setCode]+[format]. */
    fun netFor(setCode: String?, format: String): PickNet? =
        net?.takeIf { setCode != null && loadedKey == key(setCode, format) }

    /** Which of [candidates] ship with a bundled model for [format]. */
    fun bundledSets(candidates: Collection<String>, format: String): List<String> =
        candidates.map { it.uppercase() }.sorted().filter { set ->
            bundledCache.getOrPut("${set}_$format") {
                javaClass.getResource("/picknet/${set}_$format.fpnet") != null
            }
        }

    private fun loadNet(key: String): PickNet? {
        val override = cacheDir.resolve("picknet_$key.fpnet")
        if (Files.exists(override)) {
            runCatching { PickNet.load(override) }
                .onSuccess { return it }
                .onFailure { Log.warn(TAG, "override picknet_$key.fpnet unusable — falling back to bundled: $it") }
        }
        val resource = "/picknet/$key.fpnet"
        val bytes = javaClass.getResourceAsStream(resource)?.use { it.readBytes() } ?: return null
        return runCatching { PickNet.parse(bytes, resource) }
            .onFailure { Log.warn(TAG, "bad bundled pick model $resource: $it") }
            .getOrNull()
    }

    private fun key(setCode: String, format: String) = "${setCode.uppercase()}_$format"

    companion object {
        private const val TAG = "PickNet"
    }
}
