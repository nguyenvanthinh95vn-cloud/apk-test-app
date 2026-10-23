package com.example.apktest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Variant(val quality: String, val url: String, val size: Long, val isAudio: Boolean)

class MainActivity : AppCompatActivity() {

    private val accent = Color.rgb(255, 214, 10)
    private val bgPanel = Color.rgb(26, 26, 32)
    private val bgBtn = Color.rgb(48, 48, 60)
    private val uaString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private lateinit var linkInput: EditText
    private lateinit var statusLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var thumbView: ImageView
    private lateinit var variantsBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var mainPanel: View
    private lateinit var historyPanel: View
    private lateinit var tabDownloadBtn: Button
    private lateinit var tabHistoryBtn: Button

    private var variants: List<Variant> = emptyList()
    private var currentTitle: String = ""
    private var downloading = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun roundedBg(color: Int, radiusDp: Int = 12): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }
    private fun label(text: String, size: Float = 14f): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = size
        tv.setTextColor(Color.rgb(215, 215, 220))
        tv.setPadding(0, dp(8), 0, dp(2))
        return tv
    }
    private fun btn(text: String, bg: Int = -1, onClick: (View) -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 13f
        if (bg == accent) {
            b.setTextColor(Color.BLACK)
            b.setTypeface(null, Typeface.BOLD)
        } else {
            b.setTextColor(Color.WHITE)
        }
        b.background = roundedBg(if (bg == -1) bgBtn else bg)
        b.elevation = dp(3).toFloat()
        b.setOnClickListener(onClick)
        return b
    }
    private fun row(vararg views: View): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            l.addView(v, lp)
        }
        return l
    }
    private fun fmtSize(bytes: Long): String {
        if (bytes <= 0) return "?"
        val mb = bytes / 1048576.0
        return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", bytes / 1024.0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(27, 27, 34), Color.rgb(10, 10, 14))
        )

        // Header
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(14), dp(12), dp(14), dp(4))
        val title = TextView(this)
        title.text = "SaveGo"
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        title.setTypeface(null, Typeface.BOLD)
        header.addView(title)
        val sub = TextView(this)
        sub.text = "Tải video Douyin / TikTok / Facebook… — tự tìm bản NÉT NHẤT"
        sub.textSize = 12.5f
        sub.setTextColor(Color.rgb(170, 170, 175))
        header.addView(sub)
        root.addView(header)

        // Tabs
        tabDownloadBtn = btn("⬇  Tải video", accent) { showTab(true) }
        tabHistoryBtn = btn("🕘  Lịch sử") { showTab(false) }
        val tabRow = row(tabDownloadBtn, tabHistoryBtn)
        tabRow.setPadding(dp(8), dp(2), dp(8), dp(2))
        root.addView(tabRow)

        // Main panel
        val mainBox = LinearLayout(this)
        mainBox.orientation = LinearLayout.VERTICAL
        mainBox.setPadding(dp(12), dp(6), dp(12), dp(16))
        mainBox.addView(label("Dán link video vào đây:"))
        linkInput = EditText(this)
        linkInput.hint = "https://www.douyin.com/video/…"
        linkInput.setTextColor(Color.WHITE)
        linkInput.setHintTextColor(Color.rgb(150, 150, 155))
        linkInput.background = roundedBg(bgBtn, 10)
        linkInput.setPadding(dp(10), dp(10), dp(10), dp(10))
        linkInput.maxLines = 3
        mainBox.addView(linkInput)
        mainBox.addView(row(
            btn("📋 Dán từ khay nhớ") { pasteFromClipboard() },
            btn("🔍 Tìm bản nét nhất", accent) { analyze() }
        ))
        statusLabel = label("Sẵn sàng. Mẹo: trong app Douyin bấm Chia sẻ → Sao chép link, rồi qua đây bấm Dán.")
        mainBox.addView(statusLabel)
        thumbView = ImageView(this)
        thumbView.scaleType = ImageView.ScaleType.CENTER_CROP
        thumbView.visibility = View.GONE
        val thLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190))
        thLp.setMargins(0, dp(8), 0, dp(2))
        mainBox.addView(thumbView, thLp)
        titleLabel = label("")
        titleLabel.setTextColor(Color.WHITE)
        mainBox.addView(titleLabel)
        variantsBox = LinearLayout(this)
        variantsBox.orientation = LinearLayout.VERTICAL
        mainBox.addView(variantsBox)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.progressTintList = ColorStateList.valueOf(accent)
        progressBar.visibility = View.GONE
        mainBox.addView(progressBar)
        progressLabel = label("")
        progressLabel.visibility = View.GONE
        mainBox.addView(progressLabel)
        val mainScroll = ScrollView(this)
        mainScroll.addView(mainBox)
        mainPanel = mainScroll
        root.addView(mainScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // History panel
        val histBox = LinearLayout(this)
        histBox.orientation = LinearLayout.VERTICAL
        histBox.setPadding(dp(12), dp(6), dp(12), dp(16))
        historyBox = LinearLayout(this)
        historyBox.orientation = LinearLayout.VERTICAL
        histBox.addView(historyBox)
        histBox.addView(btn("🗑 Xoá hết lịch sử") {
            getSharedPreferences("savego", Context.MODE_PRIVATE).edit().remove("history_json").apply()
            renderHistory()
        })
        val histScroll = ScrollView(this)
        histScroll.addView(histBox)
        histScroll.visibility = View.GONE
        historyPanel = histScroll
        root.addView(histScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        renderHistory()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = extractUrl(text)
        if (url != null) {
            linkInput.setText(url)
            showTab(true)
            analyze()
        }
    }

    private fun extractUrl(text: String): String? {
        val m = Regex("https?://[^\\s]+").find(text.trim())
        return m?.value?.trimEnd('.', ',', ')', ']', '"', '\'')
    }

    private fun showTab(download: Boolean) {
        mainPanel.visibility = if (download) View.VISIBLE else View.GONE
        historyPanel.visibility = if (download) View.GONE else View.VISIBLE
        tabDownloadBtn.background = roundedBg(if (download) accent else bgBtn)
        tabDownloadBtn.setTextColor(if (download) Color.BLACK else Color.WHITE)
        tabHistoryBtn.background = roundedBg(if (download) bgBtn else accent)
        tabHistoryBtn.setTextColor(if (download) Color.WHITE else Color.BLACK)
        if (!download) renderHistory()
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = cm.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val t = clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
            val url = extractUrl(t)
            if (url != null) {
                linkInput.setText(url)
                statusLabel.text = "Đã dán link. Bấm Tìm bản nét nhất."
            } else {
                statusLabel.text = "Trong khay nhớ không có link nào."
            }
        } else {
            statusLabel.text = "Khay nhớ đang trống."
        }
    }

    private fun setStatus(msg: String) {
        runOnUiThread { statusLabel.text = msg }
    }

    private fun httpGetText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", uaString)
        conn.connectTimeout = 20000
        conn.readTimeout = 40000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("Máy chủ trả lỗi $code")
        return text
    }

    private fun labelScore(q: String): Int {
        val s = q.lowercase()
        return when {
            s.contains("watermark") -> -1
            s.contains("original") -> 400
            s.contains("hd") -> 300
            s.contains("sd") -> 200
            s.contains("low") -> 100
            else -> 150
        }
    }

    private fun analyze() {
        val raw = linkInput.text.toString()
        val url = extractUrl(raw)
        if (url == null) {
            statusLabel.text = "Chưa có link hợp lệ. Anh dán link video vào trước nhé."
            return
        }
        statusLabel.text = "Đang quét các bản của video này…"
        variantsBox.removeAllViews()
        titleLabel.text = ""
        thumbView.visibility = View.GONE
        Thread {
            try {
                val apiUrl = "https://demo.tube5s.com/wp-json/aio-dl/api/?url=" + URLEncoder.encode(url, "UTF-8")
                val json = JSONObject(httpGetText(apiUrl))
                val medias = json.optJSONArray("medias")
                if (medias == null || medias.length() == 0) {
                    val err = json.optString("error", "")
                    setStatus(if (err.isNotEmpty()) "Không tải được: $err" else "Không tìm thấy video nào từ link này. Thử link khác nhé anh.")
                    return@Thread
                }
                val list = mutableListOf<Variant>()
                for (i in 0 until medias.length()) {
                    val m = medias.optJSONObject(i) ?: continue
                    val u = m.optString("url", "")
                    if (u.isEmpty()) continue
                    val q = m.optString("quality", "video")
                    val size = m.optString("size", "0").toLongOrNull() ?: 0L
                    val isAudio = q.lowercase().contains("kbps") || u.contains(".mp3") || q.lowercase().contains("audio")
                    list.add(Variant(q, u, size, isAudio))
                }
                if (list.isEmpty()) {
                    setStatus("Kết quả trống. Thử lại hoặc đổi link khác.")
                    return@Thread
                }
                variants = list
                currentTitle = json.optString("title", "video")
                val thumb = json.optString("thumbnail", "")
                runOnUiThread {
                    statusLabel.text = "Tìm thấy ${list.size} bản. Bản ⭐ là nét nhất — đã tự chọn sẵn, bấm Tải là xong."
                    titleLabel.text = currentTitle
                    renderVariants()
                }
                if (thumb.isNotEmpty()) {
                    try {
                        val conn = URL(thumb).openConnection() as HttpURLConnection
                        conn.setRequestProperty("User-Agent", uaString)
                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000
                        val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                        if (bmp != null) runOnUiThread {
                            thumbView.setImageBitmap(bmp)
                            thumbView.visibility = View.VISIBLE
                        }
                    } catch (e: Exception) { }
                }
            } catch (e: Exception) {
                setStatus("Lỗi khi quét video: ${e.message}. Kiểm tra mạng rồi thử lại.")
            }
        }.start()
    }

    private fun bestVariant(): Variant? {
        val videos = variants.filter { !it.isAudio && labelScore(it.quality) >= 0 }
        if (videos.isEmpty()) return null
        return videos.maxWithOrNull(compareBy<Variant> { labelScore(it.quality) }.thenBy { it.size })
    }

    private fun renderVariants() {
        variantsBox.removeAllViews()
        val best = bestVariant()
        val sorted = variants.sortedWith(
            compareByDescending<Variant> { if (it.isAudio) -2 else labelScore(it.quality) }.thenByDescending { it.size }
        )
        variantsBox.addView(label("Các bản tìm được (bấm Tải ở bản muốn lấy):"))
        for (v in sorted) {
            val isBest = best != null && v.url == best.url
            val name = when {
                v.isAudio -> "🎵 Chỉ tiếng · ${v.quality}"
                v.quality.lowercase().contains("watermark") -> "💧 Bản có logo · ${fmtSize(v.size)}"
                isBest -> "⭐ ${v.quality.uppercase()} · ${fmtSize(v.size)} — NÉT NHẤT"
                else -> "🎬 ${v.quality.uppercase()} · ${fmtSize(v.size)}"
            }
            val b = btn("$name — Tải", if (isBest) accent else -1) { download(v) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(3), 0, dp(3))
            variantsBox.addView(b, lp)
        }
    }

    private fun download(v: Variant) {
        if (downloading) {
            Toast.makeText(this, "Đang tải 1 file rồi, đợi xong đã anh", Toast.LENGTH_SHORT).show()
            return
        }
        downloading = true
        progressBar.visibility = View.VISIBLE
        progressLabel.visibility = View.VISIBLE
        progressBar.progress = 0
        progressLabel.text = "Đang tải bản ${v.quality}…"
        Thread {
            var tmp: File? = null
            try {
                val conn = URL(v.url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", uaString)
                conn.setRequestProperty("Referer", "https://www.douyin.com/")
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.instanceFollowRedirects = true
                val total = conn.contentLengthLong
                tmp = File(cacheDir, if (v.isAudio) "dl_audio.mp3" else "dl_video.mp4")
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    runOnUiThread {
                                        progressBar.progress = pct
                                        progressLabel.text = "Đang tải bản ${v.quality}: $pct% (${fmtSize(done)}/${fmtSize(total)})"
                                    }
                                }
                            } else {
                                runOnUiThread { progressLabel.text = "Đang tải bản ${v.quality}: ${fmtSize(done)}" }
                            }
                        }
                    }
                }
                val savedName = saveToMedia(tmp!!, v)
                addHistory(currentTitle, v, savedName)
                runOnUiThread {
                    progressBar.progress = 100
                    progressLabel.text = "✅ Xong! Đã lưu: $savedName"
                    statusLabel.text = "Video nằm trong thư viện máy (mục Phim/SaveGo${if (v.isAudio) " — file tiếng ở Nhạc/SaveGo" else ""})."
                    Toast.makeText(this, "Đã tải xong bản ${v.quality}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressLabel.text = "❌ Tải lỗi: ${e.message}"
                    statusLabel.text = "Tải lỗi. Link tải có thể đã hết hạn — bấm Tìm bản nét nhất để quét lại link mới."
                }
            } finally {
                tmp?.delete()
                downloading = false
            }
        }.start()
    }

    private fun saveToMedia(file: File, v: Variant): String {
        val stamp = System.currentTimeMillis()
        return if (Build.VERSION.SDK_INT >= 29) {
            if (v.isAudio) {
                val name = "SaveGo_$stamp.mp3"
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/SaveGo")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw RuntimeException("Không tạo được file trong thư viện")
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                "Nhạc/SaveGo/$name"
            } else {
                val name = "SaveGo_$stamp.mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SaveGo")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw RuntimeException("Không tạo được file trong thư viện")
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                "Phim/SaveGo/$name"
            }
        } else {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "SaveGo")
            dir.mkdirs()
            val dest = File(dir, if (v.isAudio) "SaveGo_$stamp.mp3" else "SaveGo_$stamp.mp4")
            file.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }

    private fun addHistory(title: String, v: Variant, savedName: String) {
        try {
            val prefs = getSharedPreferences("savego", Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString("history_json", "[]"))
            val entry = JSONObject()
            entry.put("title", title)
            entry.put("quality", v.quality)
            entry.put("size", v.size)
            entry.put("file", savedName)
            entry.put("time", System.currentTimeMillis())
            val newArr = JSONArray()
            newArr.put(entry)
            var i = 0
            while (i < arr.length() && newArr.length() < 50) {
                newArr.put(arr.get(i))
                i++
            }
            prefs.edit().putString("history_json", newArr.toString()).apply()
        } catch (e: Exception) { }
    }

    private fun renderHistory() {
        if (!::historyBox.isInitialized) return
        historyBox.removeAllViews()
        val prefs = getSharedPreferences("savego", Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("history_json", "[]")) } catch (e: Exception) { JSONArray() }
        if (arr.length() == 0) {
            historyBox.addView(label("Chưa tải video nào."))
            return
        }
        historyBox.addView(label("Đã tải ${arr.length()} mục gần nhất:"))
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val when_ = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(e.optLong("time", 0)))
            val tv = label("• ${e.optString("title", "video").take(70)}\n   Bản ${e.optString("quality")} · ${fmtSize(e.optLong("size", 0))} · $when_", 13f)
            tv.setPadding(0, dp(5), 0, dp(5))
            historyBox.addView(tv)
        }
    }
}
