package com.metrolist.innertube.models

import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object ArtistBlockCore {
    private val diacriticsRegex = Regex("\\p{Mn}+")
    private const val DEFAULT_SEED = "Beyonce"
    private const val FILE_NAME = "metrolist_blocked_artists.txt"

    @Volatile
    private var storageFile: File? = null

    @Volatile
    private var initialized = false

    @Volatile
    var displayArtists: List<String> = emptyList()
        private set

    @Volatile
    var normalizedTokens: List<String> = emptyList()
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init {
        ensureLoaded()
    }

    fun normalize(text: CharSequence?): String {
        if (text == null) return ""
        val raw = text.toString().trim()
        if (raw.isEmpty()) return ""
        val nfd = Normalizer.normalize(raw, Normalizer.Form.NFD)
        return diacriticsRegex.replace(nfd, "").lowercase(Locale.ROOT).trim()
    }

    private fun resolveStorageFile(): File? {
        storageFile?.let { return it }
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val app = atClass.getMethod("currentApplication").invoke(null)
            if (app != null) {
                val filesDir = app.javaClass.getMethod("getFilesDir").invoke(app) as? File
                if (filesDir != null) {
                    val f = File(filesDir, FILE_NAME)
                    storageFile = f
                    f
                } else null
            } else null
        }.getOrNull()
    }

    @Synchronized
    fun attachStorageDir(filesDir: File) {
        val f = File(filesDir, FILE_NAME)
        storageFile = f
        ensureLoaded()
    }

    @Synchronized
    fun ensureLoaded() {
        val file = resolveStorageFile()
        if (file != null) {
            if (file.exists()) {
                val content = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                applyRawInternal(content, persist = false)
                initialized = true
                return
            } else if (!initialized) {
                applyRawInternal(DEFAULT_SEED, persist = true)
                initialized = true
                return
            }
        } else if (!initialized) {
            applyRawInternal(DEFAULT_SEED, persist = false)
        }
    }

    private fun applyRawInternal(raw: String, persist: Boolean) {
        val seen = LinkedHashMap<String, String>()
        raw.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { item ->
                val norm = normalize(item)
                if (norm.isNotEmpty() && !seen.containsKey(norm)) {
                    seen[norm] = item
                }
            }
        displayArtists = seen.values.toList()
        normalizedTokens = seen.keys.toList()
        if (persist) {
            val file = resolveStorageFile()
            if (file != null) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(displayArtists.joinToString("\n"), Charsets.UTF_8)
                }
            }
        }
        listeners.forEach { runCatching { it.invoke() } }
    }

    @Synchronized
    fun setRawBlockedArtists(raw: String) {
        ensureLoaded()
        applyRawInternal(raw, persist = true)
        initialized = true
    }

    @Synchronized
    fun blockArtist(name: String) {
        ensureLoaded()
        val clean = name.trim()
        val norm = normalize(clean)
        if (norm.isEmpty()) return
        if (normalizedTokens.contains(norm)) return
        val updated = displayArtists + clean
        applyRawInternal(updated.joinToString("\n"), persist = true)
        initialized = true
    }

    @Synchronized
    fun unblockArtist(name: String) {
        ensureLoaded()
        val norm = normalize(name)
        if (norm.isEmpty()) return
        val updated = displayArtists.filterNot { normalize(it) == norm }
        applyRawInternal(updated.joinToString("\n"), persist = true)
        initialized = true
    }

    @Synchronized
    fun toggleArtist(name: String): Boolean {
        ensureLoaded()
        return if (isArtistNameBlocked(name)) {
            val norm = normalize(name)
            val updated = displayArtists.filterNot { token ->
                val tn = normalize(token)
                tn == norm || norm.contains(tn) || tn.contains(norm)
            }
            applyRawInternal(updated.joinToString("\n"), persist = true)
            initialized = true
            false
        } else {
            blockArtist(name)
            true
        }
    }

    fun isArtistNameBlocked(name: CharSequence?): Boolean {
        if (!initialized) ensureLoaded()
        val tokens = normalizedTokens
        if (tokens.isEmpty() || name.isNullOrBlank()) return false
        val norm = normalize(name)
        if (norm.isEmpty()) return false
        for (token in tokens) {
            if (norm == token || norm.contains(token)) return true
        }
        return false
    }

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }
}
