package com.miniide.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.miniide.app.project.ProjectManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modern dosya yöneticisi: klasör gezintisi, arama filtresi, Java/XML şablonlarıyla dosya üretimi,
 * kopyalama, yeniden adlandırma, silme ve tek tuşla derleme başlatma.
 */
class ProjectActivity : AppCompatActivity() {

    private lateinit var root: File
    private lateinit var cur: File
    private lateinit var tvProjectTitle: TextView
    private lateinit var tvPath: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var etFilter: EditText
    private lateinit var lv: ListView
    private lateinit var adapter: FileAdapter
    private var filterQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_project)

        val p = intent.getStringExtra(EXTRA_PROJECT)
        if (p == null) { finish(); return }
        root = File(p)
        cur = root

        tvProjectTitle = findViewById(R.id.tvProjectTitle)
        tvPath = findViewById(R.id.tvPath)
        tvEmpty = findViewById(R.id.tvEmpty)
        etFilter = findViewById(R.id.etFilter)
        lv = findViewById(R.id.lvFiles)

        tvProjectTitle.text = root.name
        adapter = FileAdapter(this)
        lv.adapter = adapter

        lv.setOnItemClickListener { _, _, pos, _ ->
            val f = adapter.getItem(pos) ?: return@setOnItemClickListener
            if (f.isDirectory) {
                cur = f
                etFilter.setText("")
                refresh()
            } else {
                openFile(f)
            }
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            adapter.getItem(pos)?.let { showActions(it) }
            true
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            if (cur.canonicalPath != root.canonicalPath) goUp() else finish()
        }
        findViewById<Button>(R.id.btnQuickBuild).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_AUTO_BUILD, true)
            startActivity(intent)
        }
        findViewById<Button>(R.id.btnUp).setOnClickListener { goUp() }
        findViewById<Button>(R.id.btnNewJava).setOnClickListener {
            askName("Yeni Java Sınıfı (ör. Yardimci.java)", "YeniSinif.java") { name ->
                val finalName = if (name.endsWith(".java", ignoreCase = true)) name else "$name.java"
                createFile(finalName)
            }
        }
        findViewById<Button>(R.id.btnNewFile).setOnClickListener {
            askName("Yeni Dosya Adı (ör. layout.xml)", "") { createFile(it) }
        }
        findViewById<Button>(R.id.btnNewDir).setOnClickListener {
            askName("Yeni Klasör Adı", "") { createDir(it) }
        }

        etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterQuery = s?.toString()?.trim() ?: ""
                refresh()
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (cur.canonicalPath != root.canonicalPath) goUp() else finish()
            }
        })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun goUp() {
        if (cur.canonicalPath == root.canonicalPath) return
        cur = cur.parentFile ?: root
        refresh()
    }

    private fun refresh() {
        if (!cur.isDirectory) cur = root
        val allItems = cur.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        val items = if (filterQuery.isEmpty()) {
            allItems
        } else {
            allItems.filter { it.name.contains(filterQuery, ignoreCase = true) }
        }
        adapter.clear()
        adapter.addAll(items)
        adapter.notifyDataSetChanged()
        val rel = cur.canonicalPath.removePrefix(root.canonicalPath).replace('\\', '/').ifEmpty { "/" }
        tvPath.text = root.name + rel
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openFile(f: File) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PATH, f.absolutePath))
    }

    private fun showActions(f: File) {
        val options = if (f.isFile) {
            arrayOf("Editörde Aç", "Yeniden Adlandır", "Kopyasını Oluştur", "Sil")
        } else {
            arrayOf("Klasörü Aç", "Yeniden Adlandır", "Sil")
        }
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(options) { _, which ->
                if (f.isFile) {
                    when (which) {
                        0 -> openFile(f)
                        1 -> askName("Yeni Ad", f.name) { rename(f, it) }
                        2 -> duplicateFile(f)
                        3 -> confirmDelete(f)
                    }
                } else {
                    when (which) {
                        0 -> { cur = f; refresh() }
                        1 -> askName("Yeni Ad", f.name) { rename(f, it) }
                        2 -> confirmDelete(f)
                    }
                }
            }
            .show()
    }

    private fun duplicateFile(f: File) {
        try {
            val base = f.nameWithoutExtension
            val ext = f.extension.let { if (it.isNotEmpty()) ".$it" else "" }
            var copy = File(f.parentFile, "${base}_kopya$ext")
            var n = 2
            while (copy.exists()) {
                copy = File(f.parentFile, "${base}_kopya$n$ext")
                n++
            }
            f.copyTo(copy)
            refresh()
            toast("Kopyalandı: ${copy.name}")
        } catch (e: Exception) {
            toast("Kopyalanamadı: ${e.message}")
        }
    }

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this)
        input.setText(initial)
        input.setSingleLine()
        if (initial.isNotEmpty()) {
            val dot = initial.lastIndexOf('.')
            input.setSelection(0, if (dot > 0) dot else initial.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Tamam") { _, _ -> onOk(input.text.toString().trim()) }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun createFile(name: String) {
        if (!ProjectManager.isSafeName(name)) { toast("Geçersiz dosya adı"); return }
        val f = File(cur, name)
        if (f.exists()) { toast("Bu adda bir öğe zaten var"); return }
        try {
            val content = when {
                name.endsWith(".java", ignoreCase = true) -> ProjectManager.javaTemplate(root, f)
                name.endsWith(".xml", ignoreCase = true) -> ProjectManager.xmlTemplate(f)
                else -> ""
            }
            f.writeText(content)
            refresh()
            openFile(f)
        } catch (e: Exception) {
            toast("Oluşturulamadı: ${e.message}")
        }
    }

    private fun createDir(name: String) {
        if (!ProjectManager.isSafeName(name)) { toast("Geçersiz klasör adı"); return }
        val f = File(cur, name)
        if (f.exists()) { toast("Bu adda bir öğe zaten var"); return }
        if (!f.mkdirs()) toast("Klasör oluşturulamadı")
        refresh()
    }

    private fun rename(f: File, name: String) {
        if (!ProjectManager.isSafeName(name)) { toast("Geçersiz ad"); return }
        val target = File(f.parentFile, name)
        if (target.exists()) { toast("Bu adda bir öğe zaten var"); return }
        if (!f.renameTo(target)) toast("Yeniden adlandırılamadı")
        refresh()
    }

    private fun confirmDelete(f: File) {
        val what = if (f.isDirectory) "klasörü ve içindeki tüm dosyalar" else "dosyası"
        AlertDialog.Builder(this)
            .setTitle("Silinsin mi?")
            .setMessage("\"${f.name}\" $what kalıcı olarak silinecek. Bu işlem geri alınamaz.")
            .setPositiveButton("Sil") { _, _ ->
                if (!f.deleteRecursively()) toast("Silinemedi")
                refresh()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private class FileAdapter(ctx: Context) : ArrayAdapter<File>(ctx, R.layout.item_file) {
        private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        private val dp = ctx.resources.displayMetrics.density

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_file, parent, false)
            val f = getItem(position)!!
            val tvBadge = v.findViewById<TextView>(R.id.tvBadge)
            val tvName = v.findViewById<TextView>(R.id.tvFileName)
            val tvMeta = v.findViewById<TextView>(R.id.tvFileMeta)

            tvName.text = f.name
            val (badgeText, badgeColor) = badgeFor(f)
            tvBadge.text = badgeText
            tvBadge.background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(badgeColor)
            }

            val dateStr = dateFormat.format(Date(f.lastModified()))
            tvMeta.text = if (f.isDirectory) {
                "${f.list()?.size ?: 0} öğe  •  $dateStr"
            } else {
                "${humanSize(f.length())}  •  $dateStr"
            }
            return v
        }

        private fun badgeFor(f: File): Pair<String, Int> {
            if (f.isDirectory) return "KLSR" to Color.rgb(0x3B, 0x82, 0xF6)
            return when (f.extension.lowercase()) {
                "java" -> "JAVA" to Color.rgb(0xEA, 0x58, 0x0C)
                "kt", "kts" -> "KT" to Color.rgb(0x7C, 0x3A, 0xED)
                "xml" -> "XML" to Color.rgb(0x0D, 0x94, 0x88)
                "png", "jpg", "jpeg", "webp" -> "IMG" to Color.rgb(0xDB, 0x27, 0x77)
                "jar" -> "JAR" to Color.rgb(0xCA, 0x8A, 0x04)
                "apk" -> "APK" to Color.rgb(0x16, 0xA3, 0x4A)
                "json" -> "JSON" to Color.rgb(0x25, 0x63, 0xEB)
                else -> "DOSYA" to Color.rgb(0x64, 0x74, 0x8B)
            }
        }

        private fun humanSize(n: Long): String = when {
            n < 1024 -> "$n B"
            n < 1024 * 1024 -> "${n / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", n / 1024.0 / 1024.0)
        }
    }

    companion object {
        const val EXTRA_PROJECT = "project"
    }
}
