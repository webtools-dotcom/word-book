package com.abd.wordbook

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Public GitHub repo whose Releases carry the APK. Tag each release "v<versionName>", e.g. v1.1
const val REPO = "webtools-dotcom/word-book"

data class Release(val version: String, val notes: String, val apkUrl: String)

/** Latest release on GitHub, or null when offline / no release / no APK attached. Blocking. */
fun latestRelease(): Release? = try {
    val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
    c.connectTimeout = 8000; c.readTimeout = 8000
    val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    val assets = j.getJSONArray("assets")
    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
        .first { it.getString("name").endsWith(".apk") }
    Release(j.getString("tag_name").removePrefix("v"), j.optString("body"), apk.getString("browser_download_url"))
} catch (e: Exception) {
    null
}

/** "1.10" > "1.9" > "1.2.1" > "1.2" */
fun isNewer(remote: String, local: String): Boolean {
    val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
    val l = local.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(r.size, l.size)) {
        val d = r.getOrElse(i) { 0 } - l.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/** Blocking download into cache. GitHub redirects https->https, which HttpURLConnection follows. */
fun downloadApk(ctx: Activity, url: String, onProgress: (Float) -> Unit): File {
    val f = File(ctx.cacheDir, "update.apk")
    val c = URL(url).openConnection() as HttpURLConnection
    val total = c.contentLengthLong
    c.inputStream.use { inp ->
        f.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var done = 0L
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (total > 0) onProgress(done.toFloat() / total)
            }
        }
    }
    return f
}

/**
 * Hands the APK to Android's installer, which shows "Update this app?".
 * First time only, Android asks to allow installs from Word Book, then continues.
 * Same package + same signing key = app data (saved words) is kept.
 */
fun installApk(ctx: Activity, f: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    ctx.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    )
}
