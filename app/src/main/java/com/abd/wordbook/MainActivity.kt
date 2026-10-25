package com.abd.wordbook

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = Store(this)
        setContent { AppTheme { App(store) } }
    }

    // Leaving the app = backup moment.
    override fun onStop() {
        super.onStop()
        if (store.words.isEmpty()) return
        val json = wordsToJson(store.words.toList())
        Thread { runCatching { store.autoBackup(json) } }.start()
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private fun ComponentActivity.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(store: Store) {
    val ctx = LocalContext.current as ComponentActivity
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) } // 0 newest, 1 A-Z, 2 weakest
    var editing by remember { mutableStateOf<Word?>(null) }
    var deck by remember { mutableStateOf<List<Word>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(Unit) {
        update = withContext(Dispatchers.IO) { latestRelease() }
            ?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
    }

    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { store.merge(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }) }
            .onSuccess { ctx.toast("Restored $it new words") }
            .onFailure { ctx.toast("That file is not a Word Book backup") }
    }

    val current = deck
    if (current != null) {
        Revise(current, store) { deck = null }
        return
    }

    val shown = store.words
        .filter { query.isBlank() || "${it.w} ${it.m} ${it.ex}".contains(query.trim(), ignoreCase = true) }
        .let { l ->
            when (sort) {
                1 -> l.sortedBy { it.w.lowercase() }
                2 -> l.sortedBy { it.score }
                else -> l.sortedByDescending { it.added }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Word Book")
                        Text("${store.words.size} words", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    FilledTonalButton(onClick = {
                        if (store.words.isEmpty()) ctx.toast("Add some words first")
                        // weakest first, shuffled among similar scores
                        else deck = store.words.sortedBy { it.score + Random.nextDouble() * 0.3 }.take(20)
                    }) { Text("Revise") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Restore from backup file") }, onClick = {
                            menu = false; restore.launch(arrayOf("*/*"))
                        })
                        DropdownMenuItem(text = { Text("Send backup (WhatsApp / Drive)") }, onClick = {
                            menu = false
                            val f = File(ctx.cacheDir, "wordbook-${LocalDate.now()}.json")
                            f.writeText(wordsToJson(store.words))
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                            ctx.startActivity(Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("application/json")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Save backup to…"))
                        })
                        DropdownMenuItem(text = { Text("Check for updates") }, onClick = {
                            menu = false
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { latestRelease() }
                                when {
                                    r == null -> ctx.toast("Couldn't check. Are you online?")
                                    isNewer(r.version, BuildConfig.VERSION_NAME) -> update = r
                                    else -> ctx.toast("You have the latest version")
                                }
                            }
                        })
                        Text(
                            "v${BuildConfig.VERSION_NAME} · auto-backup: Download/WordBook",
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                val now = System.currentTimeMillis()
                editing = Word(now, "", "", "", now)
            }) { Icon(Icons.Default.Add, "Add word") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                query, { query = it },
                Modifier.fillMaxWidth(),
                placeholder = { Text("Search words or meanings") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
            )
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Newest", "A–Z", "Weakest").forEachIndexed { i, label ->
                    FilterChip(selected = sort == i, onClick = { sort = i }, label = { Text(label) })
                }
            }
            if (shown.isEmpty()) {
                Text(
                    if (store.words.isEmpty()) "No words yet.\nTap + to add your first word." else "No match.",
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 88.dp), // room for the + button
            ) {
                items(shown, key = { it.id }) { x -> WordCard(x) { editing = x } }
            }
        }
    }

    editing?.let { x ->
        val isNew = store.words.none { it.id == x.id }
        EditDialog(
            x, isNew,
            isDup = { w -> isNew && store.words.any { it.w.equals(w.trim(), ignoreCase = true) } },
            onSave = { store.upsert(it); editing = null },
            onDelete = { store.delete(x); editing = null },
            onDismiss = { editing = null },
        )
    }

    update?.let { r ->
        AlertDialog(
            onDismissRequest = { if (progress == null) update = null },
            title = { Text("Update available: v${r.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.notes.isNotBlank()) Text(r.notes)
                    Text("Your saved words stay safe.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    progress?.let { p -> LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {
                TextButton(enabled = progress == null, onClick = {
                    progress = 0f
                    scope.launch {
                        val f = runCatching { withContext(Dispatchers.IO) { downloadApk(ctx, r.apkUrl) { progress = it } } }
                        progress = null
                        f.onSuccess { update = null; installApk(ctx, it) }
                            .onFailure { ctx.toast("Download failed. Check internet and try again.") }
                    }
                }) { Text("Update") }
            },
            dismissButton = {
                TextButton(enabled = progress == null, onClick = { update = null }) { Text("Later") }
            },
        )
    }
}

@Composable
fun WordCard(x: Word, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text(x.w, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(x.m)
            if (x.ex.isNotBlank()) {
                Text(x.ex, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (x.seen == 0) "not revised yet" else "known ${x.known}/${x.seen}",
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
fun EditDialog(
    x: Word,
    isNew: Boolean,
    isDup: (String) -> Boolean,
    onSave: (Word) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var w by remember { mutableStateOf(x.w) }
    var m by remember { mutableStateOf(x.m) }
    var ex by remember { mutableStateOf(x.ex) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isNew) focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New word" else "Edit word") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val dup = isDup(w)
                OutlinedTextField(
                    w, { w = it }, Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text("Word") }, singleLine = true, isError = dup,
                    supportingText = if (dup) ({ Text("Already in your list") }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
                OutlinedTextField(m, { m = it }, Modifier.fillMaxWidth(), label = { Text("Meaning") })
                OutlinedTextField(ex, { ex = it }, Modifier.fillMaxWidth(),
                    label = { Text("Example / synonyms (optional)") })
            }
        },
        confirmButton = {
            TextButton(enabled = w.isNotBlank() && m.isNotBlank(), onClick = {
                onSave(x.copy(w = w.trim(), m = m.trim(), ex = ex.trim()))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(if (confirmDelete) "Sure? Delete" else "Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun Revise(deck: List<Word>, store: Store, done: () -> Unit) {
    val ctx = LocalContext.current as ComponentActivity
    var i by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    BackHandler(onBack = done)
    val x = deck[i]

    fun answer(knew: Boolean) {
        val cur = store.words.firstOrNull { it.id == x.id }
        if (cur != null) store.upsert(cur.copy(seen = cur.seen + 1, known = cur.known + if (knew) 1 else 0))
        if (i + 1 >= deck.size) { ctx.toast("Round done!"); done() } else { i++; revealed = false }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            Text("${i + 1} / ${deck.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp).clickable { revealed = true }) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(x.w, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center)
                    if (revealed) {
                        Text(x.m, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        if (x.ex.isNotBlank()) Text(x.ex, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("Tap to see meaning", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = done) { Text("Stop") }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = revealed, onClick = { answer(false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Didn't know") }
                Button(enabled = revealed, onClick = { answer(true) }) { Text("Knew it") }
            }
        }
    }
}
