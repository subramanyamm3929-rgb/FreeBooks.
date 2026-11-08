package com.example.freebooks

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** flag: "" | "new" | "updated" */
data class Book(
    val id: Int, val title: String, val author: String, val url: String, val html: Boolean,
    val downloaded: Boolean = false, val hash: String = "", val flag: String = ""
)

/** Local catalog saved as JSON on the phone. Works with no internet. */
object Store {
    private var file: File? = null
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books

    @Synchronized fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "library.json").also { file = it }
        if (!f.exists()) return
        val a = JSONArray(f.readText())
        _books.value = (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                Book(it.getInt("id"), it.getString("title"), it.getString("author"), it.getString("url"),
                    it.getBoolean("html"), it.getBoolean("downloaded"), it.getString("hash"), it.getString("flag"))
            }
        }
    }

    @Synchronized fun update(fn: (List<Book>) -> List<Book>) {
        _books.value = fn(_books.value)
        val a = JSONArray()
        _books.value.forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("author", it.author)
                .put("url", it.url).put("html", it.html).put("downloaded", it.downloaded)
                .put("hash", it.hash).put("flag", it.flag))
        }
        file?.writeText(a.toString())
    }

    fun bookFile(ctx: Context, id: Int) = File(File(ctx.filesDir, "books"), "$id.html")
}

/** The only code that touches the network. Uses Gutendex (a free API for Project Gutenberg). */
object Net {
    private const val API = "https://gutendex.com/books"

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        return c.inputStream.use { it.readBytes() }
    }

    fun newest() = parse("$API?sort=descending&languages=en")
    fun search(q: String) = parse("$API?search=" + URLEncoder.encode(q, "UTF-8"))
    fun fetch(b: Book) = get(b.url)

    private fun parse(url: String): List<Book> {
        val r = JSONObject(String(get(url))).getJSONArray("results")
        return (0 until r.length()).mapNotNull { i ->
            val o = r.getJSONObject(i)
            val f = o.getJSONObject("formats")
            val keys = f.keys().asSequence().toList()
            val hk = keys.firstOrNull { it.startsWith("text/html") }
            val k = hk ?: keys.firstOrNull { it.startsWith("text/plain") } ?: return@mapNotNull null
            val au = o.getJSONArray("authors")
            val author = if (au.length() > 0) au.getJSONObject(0).getString("name") else "Unknown"
            Book(o.getInt("id"), o.getString("title"), author, f.getString(k), hk != null)
        }
    }
}

object Sync {
    private fun sha(d: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(d).joinToString("") { "%02x".format(it) }

    /** Download a book and save it on the phone for offline reading. */
    fun download(ctx: Context, b: Book, flag: String = "") {
        val data = Net.fetch(b)
        val text = String(data, Charsets.UTF_8)
        val page = if (b.html) text else
            "<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
            "<body style='margin:16px'><pre style='white-space:pre-wrap;font:1.1em serif'>" +
            text.replace("&", "&amp;").replace("<", "&lt;") + "</pre></body></html>"
        Store.bookFile(ctx, b.id).apply { parentFile?.mkdirs(); writeText(page) }
        val nb = b.copy(downloaded = true, hash = sha(data), flag = flag)
        Store.update { l -> if (l.any { it.id == b.id }) l.map { if (it.id == b.id) nb else it } else l + nb }
    }

    /** Add newly published books, and re-check downloaded books for changes. */
    fun run(ctx: Context): Boolean = try {
        Store.init(ctx)
        val known = Store.books.value.map { it.id }.toSet()
        val fresh = Net.newest().filter { it.id !in known }.map { it.copy(flag = "new") }
        Store.update { it + fresh }
        Store.books.value.filter { it.downloaded }.forEach { b ->
            if (sha(Net.fetch(b)) != b.hash) download(ctx, b, "updated")
        }
        true
    } catch (e: Exception) { false }
}

class SyncWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result = if (Sync.run(applicationContext)) Result.success() else Result.retry()
}
