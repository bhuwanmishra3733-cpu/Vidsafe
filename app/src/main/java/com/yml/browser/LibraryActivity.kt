package com.yml.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import java.io.File

class LibraryActivity : Activity() {
    private var folder: String? = null
    private var rows: List<Any> = emptyList()
    private lateinit var list: ListView
    private lateinit var head: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        Lib.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        head = TextView(this).apply { textSize = 20f; setPadding(dp(16), dp(12), dp(16), dp(8)) }
        val add = Button(this).apply { text = "＋ New folder"; setOnClickListener { newFolder() } }
        list = ListView(this)
        root.addView(head); root.addView(add)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        list.setOnItemClickListener { _, _, i, _ ->
            val r = rows[i]
            if (r is String) { folder = r; refresh() }
            else startActivity(Intent(this, PlayerActivity::class.java).putExtra("p", (r as File).path))
        }
        list.setOnItemLongClickListener { _, _, i, _ -> menu(rows[i]); true }
        refresh()
    }

    private fun refresh() {
        val f = folder
        val labels: List<String>
        if (f == null) {
            val fs = Lib.folders(); rows = fs
            head.text = "🔒 Private library"
            labels = fs.map { "📁 $it  (${Lib.files(it).size})" }
        } else {
            val fl = Lib.files(f); rows = fl
            head.text = "📁 $f"
            labels = fl.map { it.name.substringBeforeLast('.') + "\n" + Lib.fmt(it.length()) }
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
    }

    private fun newFolder() {
        val et = EditText(this)
        AlertDialog.Builder(this).setTitle("Folder name").setView(et)
            .setPositiveButton("Create") { _, _ -> Lib.mkdir(et.text.toString()); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun menu(r: Any) {
        if (r is File) {
            AlertDialog.Builder(this).setItems(arrayOf("Move to folder", "Delete")) { _, i ->
                if (i == 0) {
                    val dests = Lib.folders().filter { it != folder }
                    AlertDialog.Builder(this).setTitle("Move to").setItems(dests.toTypedArray()) { _, k ->
                        r.renameTo(File(File(Lib.root, dests[k]), r.name)); refresh()
                    }.show()
                } else confirm("Delete this video?") { r.delete(); refresh() }
            }.show()
        } else {
            confirm("Delete folder \"$r\" and all videos inside?") { File(Lib.root, r as String).deleteRecursively(); Lib.init(this); refresh() }
        }
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Delete") { _, _ -> ok() }
            .setNegativeButton("Cancel", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (folder != null) { folder = null; refresh() } else super.onBackPressed() }
}
