package com.yml.browser

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

data class Item(val label: String, val url: String, val size: Long, val ext: String)

object Lib {
    lateinit var root: File
    fun init(ctx: Context) {
        // filesDir is private to this app: never indexed by the gallery
        root = File(ctx.filesDir, "library")
        File(root, "Unsorted").mkdirs()
    }
    fun folders(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    fun files(folder: String): List<File> =
        File(root, folder).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    fun clean(s: String) =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifEmpty { "video" }
    fun mkdir(name: String): String { val n = clean(name); File(root, n).mkdirs(); return n }
    fun fmt(b: Long): String = when {
        b <= 0 -> "size unknown"
        b >= (1L shl 30) -> "%.2f GB".format(b / 1073741824.0)
        b >= (1L shl 20) -> "%.1f MB".format(b / 1048576.0)
        else -> "${b / 1024} KB"
    }

    fun download(cap: String, item: Item, folder: String, cancel: AtomicBoolean,
                 onProgress: (Long, Long) -> Unit) {
        val dir = File(root, folder).apply { mkdirs() }
        val base = clean(cap)
        var f = File(dir, "$base.${item.ext}")
        var n = 1
        while (f.exists()) { f = File(dir, "$base ($n).${item.ext}"); n++ }
        val part = File(dir, f.name + ".part")
        try {
            val c = URL(item.url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", Extractor.UA)
            c.connectTimeout = 15000; c.readTimeout = 30000
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else item.size
            var done = 0L; var last = 0L
            c.inputStream.use { i ->
                part.outputStream().use { o ->
                    val buf = ByteArray(65536)
                    while (true) {
                        if (cancel.get()) throw Exception("cancelled")
                        val r = i.read(buf)
                        if (r < 0) break
                        o.write(buf, 0, r); done += r
                        val now = System.currentTimeMillis()
                        if (now - last > 300) { last = now; onProgress(done, total) }
                    }
                }
            }
            part.renameTo(f)
        } catch (e: Exception) { part.delete(); throw e }
    }
}

object Extractor {
    const val UA = "Mozilla/5.0 (Linux; Android 13) YMLBrowser/1.0"
    // Open-source / openly licensed sources only
    private val allowed = listOf("archive.org", "wikimedia.org", "blender.org")
    private val vidExt = setOf("mp4", "webm", "ogv", "mkv", "mov", "avi", "mpeg", "m4v")

    private fun ok(h: String?) = h != null && allowed.any { h == it || h.endsWith(".$it") }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 15000; c.readTimeout = 20000
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    private fun head(u: String): Long = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.setRequestProperty("User-Agent", UA)
        c.contentLengthLong
    } catch (e: Exception) { -1 }

    fun resolve(page: String): Pair<String, List<Item>> {
        val uri = Uri.parse(page)
        val host = uri.host
        if (!ok(host)) throw Exception("Only open-source sites are supported (Internet Archive, Wikimedia Commons, Blender).")
        val seg = uri.pathSegments
        if (host!!.endsWith("archive.org") && seg.size >= 2 && seg[0] == "details") return archive(seg[1])
        if (host.endsWith("wikimedia.org") && seg.size >= 2 && seg[0] == "wiki" && seg[1].startsWith("File:")) return commons(seg[1])
        val last = seg.lastOrNull() ?: ""
        val ext = last.substringAfterLast('.', "").lowercase()
        if (ext in vidExt) return Pair(last.substringBeforeLast('.'), listOf(Item("Original", page, head(page), ext)))
        throw Exception("Open a video page first (for example an archive.org /details/ page).")
    }

    private fun archive(id: String): Pair<String, List<Item>> {
        val j = JSONObject(get("https://archive.org/metadata/" + Uri.encode(id)))
        val t = j.optJSONObject("metadata")?.opt("title")
        val title = (if (t is JSONArray) t.optString(0) else t?.toString()) ?: id
        val fs = j.optJSONArray("files") ?: JSONArray()
        val out = mutableListOf<Item>()
        for (i in 0 until fs.length()) {
            val f = fs.getJSONObject(i)
            val name = f.optString("name")
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in vidExt) continue
            val fmt = f.optString("format", ext)
            val h = f.optString("height")
            val label = if (h.isNotEmpty()) "${h}p · $fmt" else fmt
            out.add(Item(label, "https://archive.org/download/" + Uri.encode(id) + "/" + Uri.encode(name, "/"),
                f.optString("size", "0").toLongOrNull() ?: -1, ext))
        }
        return Pair(title, out.sortedByDescending { it.size })
    }

    private fun commons(title: String): Pair<String, List<Item>> {
        val j = JSONObject(get("https://commons.wikimedia.org/w/api.php?action=query&format=json&prop=imageinfo&iiprop=url%7Csize%7Cmime&titles=" + Uri.encode(title)))
        val pages = j.getJSONObject("query").getJSONObject("pages")
        val p = pages.getJSONObject(pages.keys().next())
        val ii = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: return Pair(title, emptyList())
        val mime = ii.optString("mime")
        if (!(mime.startsWith("video") || mime.contains("ogg"))) return Pair(title, emptyList())
        val u = ii.getString("url")
        val label = "${ii.optInt("width")}×${ii.optInt("height")} · original"
        return Pair(title.removePrefix("File:").substringBeforeLast('.'),
            listOf(Item(label, u, ii.optLong("size", -1), u.substringAfterLast('.').lowercase())))
    }
}
