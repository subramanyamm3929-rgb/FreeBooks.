package com.example.freebooks

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        // Automatic background check once a day, only when internet happens to be available.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
        setContent { MaterialTheme { App() } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by Store.books.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var reading by remember { mutableStateOf<Book?>(null) }
    var status by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Book>>(emptyList()) }

    fun online(block: () -> Unit) = scope.launch(Dispatchers.IO) {
        try { block() } catch (e: Exception) { status = "Couldn't reach the internet" }
    }

    val open = reading
    if (open != null) {
        BackHandler { reading = null }
        Reader(open)
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Free Books", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = {
                    status = "Syncing…"
                    online { status = if (Sync.run(ctx)) "Up to date" else "Sync failed (offline?)" }
                }) { Text("Sync") }
            }
            if (status.isNotEmpty()) Text(status, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("My books") })
                val newCount = books.count { !it.downloaded && it.flag == "new" }
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(if (newCount > 0) "Discover ($newCount new)" else "Discover") })
            }
            if (tab == 1) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, label = { Text("Search (needs internet)") })
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { status = "Searching…"; online { results = Net.search(query); status = "" } }) { Text("Go") }
                }
            }
            val list = (if (tab == 0) books.filter { it.downloaded }
            else if (results.isNotEmpty()) results.map { r -> books.find { it.id == r.id } ?: r }
            else books.filter { !it.downloaded }).sortedBy { if (it.flag.isEmpty()) 1 else 0 }

            if (list.isEmpty()) Text(
                if (tab == 0) "No books saved yet. Open Discover, then Download a book to read it offline."
                else "Tap Sync to fetch the newest books, or search above.",
                Modifier.padding(16.dp)
            )
            LazyColumn {
                items(list, key = { it.id }) { b ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.title, style = MaterialTheme.typography.titleSmall)
                            Text(b.author + when (b.flag) { "new" -> "  •  NEW"; "updated" -> "  •  UPDATED"; else -> "" },
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            if (b.downloaded) {
                                Store.update { l -> l.map { if (it.id == b.id) it.copy(flag = "") else it } }
                                reading = b
                            } else {
                                status = "Downloading…"
                                online { Sync.download(ctx, b); status = "Saved for offline reading" }
                            }
                        }) { Text(if (b.downloaded) "Read" else "Download") }
                    }
                }
            }
        }
    }
}

@Composable
fun Reader(b: Book) {
    val ctx = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = true
                settings.blockNetworkLoads = true // reading never uses the internet
                loadUrl("file://" + Store.bookFile(ctx, b.id).absolutePath)
            }
        }
    )
}
