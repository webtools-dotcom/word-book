package com.abd.wordbook

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate

data class Word(
    val id: Long,
    val w: String,
    val m: String,
    val ex: String,
    val added: Long,
    val seen: Int = 0,
    val known: Int = 0,
) {
    // unrevised words count as weakest
    val score get() = if (seen == 0) -1.0 else known.toDouble() / seen
}

// Same JSON shape as the old web version, so its backups import too.
fun parseWords(s: String): List<Word> {
    val a = JSONArray(s)
    return List(a.length()) { i ->
        a.getJSONObject(i).run {
            Word(getLong("id"), getString("w"), getString("m"), optString("ex"),
                optLong("added", getLong("id")), optInt("seen"), optInt("known"))
        }
    }
}

fun wordsToJson(ws: List<Word>): String = JSONArray(ws.map {
    JSONObject().put("id", it.id).put("w", it.w).put("m", it.m).put("ex", it.ex)
        .put("added", it.added).put("seen", it.seen).put("known", it.known)
}).toString(1)

class Store(private val ctx: Context) {
    // AtomicFile: a crash mid-save can never corrupt the word list
    private val file = AtomicFile(File(ctx.filesDir, "words.json"))
    val words = mutableStateListOf<Word>().apply { addAll(load()) }

    // Any error other than "no file yet" crashes instead of silently starting empty and overwriting data.
    private fun load(): List<Word> =
        try { parseWords(String(file.readFully())) } catch (e: FileNotFoundException) { emptyList() }

    private fun save() {
        val out = file.startWrite()
        try {
            out.write(wordsToJson(words).toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out); throw e
        }
    }

    fun upsert(x: Word) {
        val i = words.indexOfFirst { it.id == x.id }
        if (i >= 0) words[i] = x else words += x
        save()
    }

    fun delete(x: Word) { words.removeAll { it.id == x.id }; save() }

    /** Adds words from a backup that aren't already here. Returns how many were added. */
    fun merge(json: String): Int {
        val have = words.map { it.id }.toSet()
        val fresh = parseWords(json).filter { it.id !in have }
        words += fresh
        save()
        return fresh.size
    }

    /**
     * One file per day in phone's Download/WordBook/, overwritten through the day.
     * Survives uninstall. Call off the main thread.
     */
    fun autoBackup(json: String) {
        val name = "wordbook-${LocalDate.now()}.json"
        val prefs = ctx.getSharedPreferences("backup", Context.MODE_PRIVATE)
        val r = ctx.contentResolver
        fun write(u: Uri) = r.openOutputStream(u, "wt")!!.use { it.write(json.toByteArray()) }

        if (prefs.getString("name", null) == name) {
            try { write(Uri.parse(prefs.getString("uri", null))); return } catch (_: Exception) { /* user deleted it: make a new one */ }
        }
        val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/WordBook")
        }) ?: return
        write(uri)
        prefs.edit().putString("name", name).putString("uri", uri.toString()).apply()
    }
}
