package com.yml.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private class Tab(val web: WebView, var title: String = "New tab")

    private val HOME = "https://archive.org/details/movies"
    private val tabs = mutableListOf<Tab>()
    private var cur = -1
    private lateinit var tabBar: LinearLayout
    private lateinit var holder: FrameLayout
    private lateinit var addr: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Lib.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        addr = EditText(this).apply {
            hint = "Search or enter address"; isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        val dl = Button(this).apply { text = "⬇"; minWidth = dp(48); setOnClickListener { onDownload() } }
        val lib = Button(this).apply {
            text = "📁"; minWidth = dp(48)
            setOnClickListener { startActivity(Intent(this@MainActivity, LibraryActivity::class.java)) }
        }
        val bar = LinearLayout(this).apply {
            addView(addr, LinearLayout.LayoutParams(0, -2, 1f))
            addView(dl); addView(lib)
        }
        tabBar = LinearLayout(this)
        val scroll = HorizontalScrollView(this).apply { addView(tabBar) }
        holder = FrameLayout(this)
        root.addView(bar); root.addView(scroll)
        root.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        newTab(HOME)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String?) {
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        val t = Tab(w)
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                if (tabs.getOrNull(cur)?.web === v) addr.setText(u)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(v: WebView, title: String) { t.title = title; refreshTabs() }
        }
        tabs.add(t)
        holder.addView(w, FrameLayout.LayoutParams(-1, -1))
        select(tabs.size - 1)
        if (url != null) w.loadUrl(url)
    }

    private fun select(i: Int) {
        cur = i
        tabs.forEachIndexed { k, t -> t.web.visibility = if (k == i) View.VISIBLE else View.GONE }
        addr.setText(tabs[i].web.url ?: "")
        refreshTabs()
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        holder.removeView(t.web); t.web.destroy()
        if (tabs.isEmpty()) { cur = -1; newTab(HOME) }
        else select(if (i < cur) cur - 1 else minOf(cur, tabs.size - 1))
    }

    private fun refreshTabs() {
        tabBar.removeAllViews()
        tabs.forEachIndexed { i, t ->
            val tv = TextView(this).apply {
                text = t.title.take(14)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(Color.BLACK)
                setBackgroundColor(if (i == cur) 0xFFBBDEFB.toInt() else 0xFFEEEEEE.toInt())
                setOnClickListener { select(i) }
                setOnLongClickListener { closeTab(i); true } // long-press = close tab
            }
            tabBar.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        tabBar.addView(Button(this).apply { text = "＋"; minWidth = dp(48); setOnClickListener { newTab(HOME) } })
    }

    private fun go(s: String) {
        val t = s.trim()
        if (t.isEmpty()) return
        val u = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(" ") || !t.contains(".") -> "https://duckduckgo.com/?q=" + Uri.encode(t)
            else -> "https://$t"
        }
        tabs[cur].web.loadUrl(u)
    }

    private fun onDownload() {
        val u = tabs[cur].web.url ?: return
        toast("Checking this page…")
        Thread {
            try {
                val (cap, items) = Extractor.resolve(u)
                runOnUiThread { if (items.isEmpty()) toast("No downloadable video found on this page") else pick(cap, items) }
            } catch (e: Exception) { runOnUiThread { toast(e.message ?: "Failed") } }
        }.start()
    }

    private fun pick(cap: String, items: List<Item>) {
        AlertDialog.Builder(this).setTitle(cap)
            .setItems(items.map { "${it.label}  •  ${Lib.fmt(it.size)}" }.toTypedArray()) { _, i ->
                chooseFolder(cap, items[i])
            }.show()
    }

    private fun chooseFolder(cap: String, item: Item) {
        val fs = Lib.folders()
        val opts = (fs + "＋ New folder…").toTypedArray()
        AlertDialog.Builder(this).setTitle("Save to folder").setItems(opts) { _, i ->
            if (i < fs.size) startDownload(cap, item, fs[i])
            else {
                val et = EditText(this)
                AlertDialog.Builder(this).setTitle("Folder name").setView(et)
                    .setPositiveButton("Create") { _, _ -> startDownload(cap, item, Lib.mkdir(et.text.toString())) }
                    .setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun startDownload(cap: String, item: Item, folder: String) {
        val tv = TextView(this).apply { setPadding(dp(24), dp(16), dp(24), dp(8)); text = "Starting…" }
        val cancel = AtomicBoolean(false)
        val dlg = AlertDialog.Builder(this).setTitle("Downloading").setView(tv).setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancel.set(true) }.create()
        dlg.show()
        Thread {
            val res = try {
                Lib.download(cap, item, folder, cancel) { d, t ->
                    runOnUiThread {
                        val pct = if (t > 0) " (${d * 100 / t}%)" else ""
                        tv.text = "${Lib.fmt(d)} / ${Lib.fmt(t)}$pct"
                    }
                }
                "Saved to private library › $folder"
            } catch (e: Exception) { if (cancel.get()) "Cancelled" else "Failed: ${e.message}" }
            runOnUiThread { dlg.dismiss(); toast(res) }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = tabs[cur].web
        if (w.canGoBack()) w.goBack() else if (tabs.size > 1) closeTab(cur) else super.onBackPressed()
    }
}
