package com.abd.wordbook

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

// New question packs are published here; the app picks them up without an app update.
const val PACK_URL = "https://raw.githubusercontent.com/$REPO/main/packs/pack.json"

@Composable
fun Root(store: Store) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Edit, null) }, label = { Text("Words") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.PlayArrow, null) }, label = { Text("Practice") })
        }
    }) { pad ->
        // Words stays composed underneath so its state (and the one-time update check) survives tab switches.
        // only the bottom bar's space: Words' own Scaffold already handles the status bar
        Box(Modifier.padding(bottom = pad.calculateBottomPadding()).fillMaxSize()) {
            App(store)
            if (tab == 1) {
                val ctx = LocalContext.current
                val w = web ?: practiceWebView(ctx).also { web = it }
                AndroidView({ w }, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding())
            }
        }
    }
    web?.let { w -> LaunchedEffect(w) { refreshPack(w) } }
}

@SuppressLint("SetJavaScriptEnabled")
private fun practiceWebView(ctx: Context) = WebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true // answers, timings and flags live in the page's localStorage
    setBackgroundColor(0)
    show(this, File(ctx.filesDir, "pack.json").takeIf { it.exists() }?.readText()
        ?: ctx.assets.open("pack.json").bufferedReader().use { it.readText() })
}

private fun show(w: WebView, pack: String) {
    val o = JSONObject(pack)
    val html = w.context.assets.open("viewer.html").bufferedReader().use { it.readText() }
        .replace("/*PACKID*/'sample'", JSONObject.quote(o.getString("id")))
        .replace("/*PACK*/[]", o.getJSONArray("questions").toString())
    // fixed origin so localStorage (progress) persists between launches
    w.loadDataWithBaseURL("https://wordbook.app/", html, "text/html", "utf-8", null)
}

/** Downloads the latest pack; if it differs from the cached one, saves it and reloads the page. Offline = no-op. */
private suspend fun refreshPack(w: WebView) {
    val cache = File(w.context.filesDir, "pack.json")
    val fresh = withContext(Dispatchers.IO) {
        runCatching { URL(PACK_URL).readText().also { JSONObject(it).getJSONArray("questions") } }.getOrNull()
    } ?: return
    val current = cache.takeIf { it.exists() }?.readText()
        ?: w.context.assets.open("pack.json").bufferedReader().use { it.readText() }
    if (fresh != current) {
        cache.writeText(fresh)
        show(w, fresh)
    }
}
