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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.webkit.WebViewClient
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.JavascriptInterface
import android.view.ViewGroup
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
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class Variant(val quality: String, val url: String, val size: Long, val isAudio: Boolean)
data class DownloadJob(
    val id: Long,
    val title: String,
    var variant: Variant,
    var state: String = "Đang chờ…",
    var done: Long = 0,
    var total: Long = -1,
    var lastPct: Int = -1
)
class JobRow(val bar: ProgressBar, val status: TextView, val retry: Button)

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
    private lateinit var downloadsBox: LinearLayout
    private lateinit var downloadsLabel: TextView
    private lateinit var mainPanel: View
    private lateinit var historyPanel: View
    private lateinit var tabDownloadBtn: Button
    private lateinit var tabHistoryBtn: Button

    private var variants: List<Variant> = emptyList()
    private var currentTitle: String = ""
    private var accelOn = false
    private var workerUrl = ""
    private var apiBase = "https://demo.tube5s.com/wp-json/aio-dl/api/?url="
    private lateinit var accelBtn: Button
    private var nextJobId = 1L
    private val jobQueue: ArrayDeque<DownloadJob> = ArrayDeque()
    private val jobRows = HashMap<Long, JobRow>()
    private val queueLock = Any()
    private var activeJobs = 0
    private val maxConcurrent = 3

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
    private fun pressable(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(60).start()
                    view.alpha = 0.85f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    view.alpha = 1f
                    if (ev.action == MotionEvent.ACTION_UP) {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            }
            false
        }
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
        pressable(b)
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
        // One-button acceleration toggle: OFF = direct download, ON = via free Cloudflare Worker
        accelBtn = btn("⚡ Tăng tốc (Cloudflare): TẮT") { toggleAccel() }
        val accelLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        accelLp.setMargins(0, dp(4), 0, 0)
        mainBox.addView(accelBtn, accelLp)
        mainBox.addView(label("Chỉ bật khi tải chậm (buổi tối). Tắt khi tải nhanh để đỡ qua trung gian."))
        statusLabel = label("Sẵn sàng. Mẹo: trong app Douyin bấm Chia sẻ → Sao chép link, rồi qua đây bấm Dán. Tải được nhiều video cùng lúc.")
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
        downloadsLabel = label("Đang tải:")
        downloadsLabel.visibility = View.GONE
        mainBox.addView(downloadsLabel)
        downloadsBox = LinearLayout(this)
        downloadsBox.orientation = LinearLayout.VERTICAL
        mainBox.addView(downloadsBox)
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
        fetchRemoteConfig()
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

    // Remote config (savego-config.json in the repo): worker_url + api_url, read on launch
    private fun fetchRemoteConfig() {
        val prefs = getSharedPreferences("savego", Context.MODE_PRIVATE)
        accelOn = prefs.getBoolean("accel_on", false)
        workerUrl = prefs.getString("worker_url", "") ?: ""
        apiBase = prefs.getString("api_url", apiBase) ?: apiBase
        runOnUiThread { updateAccelBtn() }
        Thread {
            try {
                val json = JSONObject(httpGetText("https://raw.githubusercontent.com/nguyenvanthinh95vn-cloud/apk-test-app/main/savego-config.json"))
                val w = json.optString("worker_url", "")
                val a = json.optString("api_url", "")
                prefs.edit().apply {
                    if (w.isNotEmpty() || json.has("worker_url")) putString("worker_url", w)
                    if (a.isNotEmpty()) putString("api_url", a)
                }.apply()
                workerUrl = w
                if (a.isNotEmpty()) apiBase = a
                runOnUiThread { updateAccelBtn() }
            } catch (e: Exception) { }
        }.start()
    }

    private fun updateAccelBtn() {
        if (!::accelBtn.isInitialized) return
        if (accelOn) {
            accelBtn.text = "⚡ Tăng tốc (Cloudflare): BẬT"
            accelBtn.background = roundedBg(accent)
            accelBtn.setTextColor(Color.BLACK)
            accelBtn.setTypeface(null, Typeface.BOLD)
        } else {
            accelBtn.text = "⚡ Tăng tốc (Cloudflare): TẮT"
            accelBtn.background = roundedBg(bgBtn)
            accelBtn.setTextColor(Color.WHITE)
            accelBtn.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun toggleAccel() {
        accelOn = !accelOn
        getSharedPreferences("savego", Context.MODE_PRIVATE).edit().putBoolean("accel_on", accelOn).apply()
        updateAccelBtn()
        if (accelOn) {
            if (workerUrl.isEmpty()) {
                Toast.makeText(this, "Đã bật tăng tốc, nhưng chưa có địa chỉ máy chủ — cần triển khai Worker 1 lần trước", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Đã bật tăng tốc qua Cloudflare", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Đã tắt tăng tốc — tải trực tiếp", Toast.LENGTH_SHORT).show()
        }
    }

    private fun proxied(url: String): String {
        return if (accelOn && workerUrl.isNotEmpty()) {
            workerUrl.trimEnd('/') + "?u=" + URLEncoder.encode(url, "UTF-8")
        } else {
            url
        }
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
            s.contains("1080") -> 350
            s.contains("hd") || s.contains("720") -> 300
            s.contains("540") || s.contains("480") || s.contains("sd") -> 200
            s.contains("low") || s.contains("360") || s.contains("240") -> 100
            else -> 150
        }
    }

    // ---------- Quét thẳng từ Douyin bằng WebView (giả làm trình duyệt thật) ----------
    // Douyin chặn kiểu tải thô (link rút gọn còn bị đá về trang chủ), nhưng WebView
    // chạy JavaScript như Chrome thật nên vượt được màn chống bot của nó.
    private class ScanState {
        @Volatile var webView: WebView? = null
        @Volatile var html: String? = null
        @Volatile var pageUrl: String = ""
        @Volatile var error: String? = null
    }

    private val scanJs = "(function(){try{var h=document.documentElement.outerHTML;" +
        "if(h&&h.indexOf('bit_rate')>=0){HtmlHook.pushHtml(h,location.href)}}catch(e){}})()"

    private fun setupScanWebView(shareUrl: String, state: ScanState) {
        val wv = WebView(this)
        state.webView = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = uaString
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun pushHtml(h: String, u: String) {
                if (state.html == null && h.contains("bit_rate")) {
                    state.html = h
                    state.pageUrl = u
                }
            }
        }, "HtmlHook")
        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && state.error == null) {
                    state.error = "Không mở được trang Douyin (${error.description})"
                }
            }
        }
        (linkInput.parent as? ViewGroup)?.addView(wv, 1, 1)
        wv.loadUrl(shareUrl)
    }

    private fun fetchDouyinHtmlViaWebView(shareUrl: String): Pair<String, String> {
        val state = ScanState()
        runOnUiThread { setupScanWebView(shareUrl, state) }
        val deadline = System.currentTimeMillis() + 30000
        try {
            while (System.currentTimeMillis() < deadline) {
                if (state.html != null || state.error != null) break
                Thread.sleep(1500)
                runOnUiThread {
                    try { state.webView?.evaluateJavascript(scanJs, null) } catch (ignored: Exception) { }
                }
            }
        } catch (ignored: InterruptedException) { }
        runOnUiThread {
            try {
                (state.webView?.parent as? ViewGroup)?.removeView(state.webView)
                state.webView?.destroy()
            } catch (ignored: Exception) { }
            state.webView = null
        }
        state.error?.let { throw RuntimeException(it) }
        val html = state.html
            ?: throw RuntimeException("Douyin không trả dữ liệu video (link có thể đã hết hạn)")
        return Pair(state.pageUrl, html)
    }


    // Cat mang JSON theo key, vd extractJsonArray(html, "bit_rate") -> "[{...},{...}]"
    private fun extractJsonArray(html: String, key: String): String? {
        val startKey = html.indexOf("\"" + key + "\":")
        if (startKey < 0) return null
        var i = html.indexOf('[', startKey)
        if (i < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        val sb = StringBuilder()
        while (i < html.length) {
            val c = html[i]
            sb.append(c)
            if (esc) esc = false
            else if (inStr && c == '\\') esc = true
            else if (c == '"') inStr = !inStr
            else if (!inStr) {
                if (c == '[') depth++
                else if (c == ']') {
                    depth--
                    if (depth == 0) return sb.toString()
                }
            }
            i++
            if (sb.length > 2000000) return null
        }
        return null
    }

    private fun unescapeJson(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                val n = raw[i + 1]
                when (n) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < raw.length) {
                            sb.append(raw.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: '?')
                            i += 4
                        }
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun extractDouyinDesc(html: String): String {
        val key = "\"desc\":\""
        val s = html.indexOf(key)
        if (s < 0) return ""
        var i = s + key.length
        val sb = StringBuilder()
        while (i < html.length) {
            val c = html[i]
            if (c == '\\' && i + 1 < html.length) {
                sb.append(c); sb.append(html[i + 1]); i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c); i++
            }
            if (sb.length > 500) break
        }
        return unescapeJson(sb.toString()).trim().take(120)
    }

    // Trả về (tiêu đề, danh sách bản). Ném exception nếu không quét được.
    private fun scanDouyinDirect(shareUrl: String): Pair<String, List<Variant>> {
        setStatus("Đang mở Douyin để quét (giả làm trình duyệt, mất khoảng 10-20 giây)…")
        val (pageUrl, html) = fetchDouyinHtmlViaWebView(shareUrl)
        val videoId = Regex("/video/(\\d+)").find(pageUrl)?.groupValues?.get(1) ?: "video"
        val arrText = extractJsonArray(html, "bit_rate")
            ?: throw RuntimeException("Không tìm thấy các bản video trong trang Douyin")
        val arr = JSONArray(arrText)
        val list = mutableListOf<Variant>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val gear = e.optString("gear_name", "")
            val pa = e.optJSONObject("play_addr") ?: continue
            val urls = pa.optJSONArray("url_list") ?: continue
            val u = urls.optString(0, "")
            if (u.isEmpty() || !seen.add(u)) continue
            val label = when {
                gear.contains("1080") -> "1080p"
                gear.contains("720") -> "720p"
                gear.contains("540") || gear.contains("480") -> "540p"
                gear.contains("360") || gear.contains("240") -> "360p"
                else -> gear.ifEmpty { "video" }
            }
            list.add(Variant(label, u, 0L, false))
        }
        if (list.isEmpty()) throw RuntimeException("Douyin không trả về bản video nào")
        val title = extractDouyinDesc(html).ifEmpty { "douyin_$videoId" }
        return Pair(title, list)
    }


    // Quet qua API trung gian (du phong khi quet thang that bai)
    private fun scanViaApi(url: String): Triple<String, String, List<Variant>> {
        val apiUrl = apiBase + URLEncoder.encode(url, "UTF-8")
        val json = JSONObject(httpGetText(apiUrl))
        val medias = json.optJSONArray("medias")
        if (medias == null || medias.length() == 0) {
            val err = json.optString("error", "")
            throw RuntimeException(if (err.isNotEmpty()) err else "API không trả về video")
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
        if (list.isEmpty()) throw RuntimeException("Kết quả API trống")
        return Triple(json.optString("title", "video"), json.optString("thumbnail", ""), list)
    }

    private fun showVariants(title: String, thumb: String, list: List<Variant>) {
        variants = list
        currentTitle = title
        runOnUiThread {
            statusLabel.text = "Tìm thấy ${list.size} bản. Bản ⭐ là nét nhất — bấm Tải là xong. Tải được nhiều video cùng lúc, tối đa 3 file chạy song song."
            titleLabel.text = title
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
                // Cach 1: quet thang tu Douyin, khong can may chu trung gian
                var directErr = ""
                if (url.contains("douyin.com")) {
                    try {
                        val (t, l) = scanDouyinDirect(url)
                        showVariants(t, "", l)
                        return@Thread
                    } catch (e: Exception) {
                        directErr = e.message ?: "không rõ lỗi"
                    }
                }
                // Cach 2 (du phong): API trung gian
                try {
                    val (t, th, l) = scanViaApi(url)
                    showVariants(t, th, l)
                } catch (e: Exception) {
                    val msg = e.message ?: "khong ro loi"
                    setStatus(if (msg.contains("523"))
                        "Máy chủ quét link trung gian đang sập (lỗi 523) — không phải lỗi mạng nhà anh. Quét thẳng cũng lỗi: $directErr. Anh thử lại sau ít phút nhé."
                    else "Lỗi khi quét video: $msg. Kiểm tra mạng rồi thử lại.")
                }
            } catch (e: Exception) {
                setStatus("Lỗi khi quét video: ${e.message}.")
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

    // ---------- Download queue: up to 3 files at once ----------
    private fun download(v: Variant) {
        if (accelOn && workerUrl.isEmpty()) {
            Toast.makeText(this, "Nút tăng tốc đang bật nhưng chưa có địa chỉ máy chủ — tắt nút hoặc triển khai Worker trước nhé", Toast.LENGTH_LONG).show()
            return
        }
        val job = DownloadJob(nextJobId++, currentTitle.ifEmpty { "video" }, v)
        runOnUiThread { addJobRow(job) }
        synchronized(queueLock) { jobQueue.addLast(job) }
        processQueue()
    }

    private fun addJobRow(job: DownloadJob) {
        downloadsLabel.visibility = View.VISIBLE
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(6), 0, dp(6))
        val titleTv = label("${job.title.take(60)} · bản ${job.variant.quality}", 13f)
        titleTv.setTextColor(Color.WHITE)
        box.addView(titleTv)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.progress = 0
        bar.progressTintList = ColorStateList.valueOf(accent)
        box.addView(bar)
        val statusTv = label(job.state, 12f)
        box.addView(statusTv)
        val retryBtn = btn("↻ Tải lại") { retryJob(job) }
        retryBtn.visibility = View.GONE
        box.addView(retryBtn)
        jobRows[job.id] = JobRow(bar, statusTv, retryBtn)
        downloadsBox.addView(box)
    }

    private fun retryJob(job: DownloadJob) {
        job.done = 0
        job.total = -1
        job.lastPct = -1
        job.state = "Đang chờ…"
        runOnUiThread {
            jobRows[job.id]?.let {
                it.bar.progress = 0
                it.status.text = job.state
                it.status.setTextColor(Color.rgb(215, 215, 220))
                it.retry.visibility = View.GONE
            }
        }
        synchronized(queueLock) { jobQueue.addLast(job) }
        processQueue()
    }

    private fun processQueue() {
        synchronized(queueLock) {
            while (activeJobs < maxConcurrent && jobQueue.isNotEmpty()) {
                val job = jobQueue.removeFirst()
                activeJobs++
                Thread {
                    try { runJob(job) } finally {
                        synchronized(queueLock) { activeJobs-- }
                        processQueue()
                    }
                }.start()
            }
        }
    }

    private fun setJobState(job: DownloadJob, state: String) {
        job.state = state
        runOnUiThread {
            jobRows[job.id]?.let {
                it.status.text = state
                when {
                    state.startsWith("✅") -> it.status.setTextColor(Color.rgb(120, 220, 130))
                    state.startsWith("❌") -> {
                        it.status.setTextColor(Color.rgb(255, 120, 120))
                        it.retry.visibility = View.VISIBLE
                    }
                    else -> it.status.setTextColor(Color.rgb(215, 215, 220))
                }
            }
        }
    }

    private fun updateJobProgress(job: DownloadJob) {
        if (job.total <= 0) return
        val pct = (job.done * 100 / job.total).toInt().coerceIn(0, 100)
        if (pct == job.lastPct) return
        job.lastPct = pct
        runOnUiThread {
            jobRows[job.id]?.let {
                it.bar.progress = pct
                it.status.text = "${job.state} $pct% (${fmtSize(job.done)}/${fmtSize(job.total)})"
            }
        }
    }

    private fun runJob(job: DownloadJob) {
        val others = variants
            .filter { it.url != job.variant.url && !it.isAudio && labelScore(it.quality) >= 0 }
            .sortedWith(compareByDescending<Variant> { labelScore(it.quality) }.thenByDescending { it.size })
        val candidates = listOf(job.variant) + others
        var ok = false
        for (cand in candidates) {
            setJobState(job, "Đang tải bản ${cand.quality}…")
            val tmp = File(cacheDir, "dl_${job.id}.${if (cand.isAudio) "mp3" else "mp4"}")
            if (tmp.exists()) tmp.delete()
            val success = segmentedDownload(proxied(cand.url), tmp, job)
            if (success) {
                val saved = saveToMedia(tmp, cand)
                tmp.delete()
                addHistory(job.title, cand, saved)
                runOnUiThread { jobRows[job.id]?.bar?.progress = 100 }
                setJobState(job, "✅ Xong — $saved")
                ok = true
                break
            } else {
                tmp.delete()
                if (cand.url != candidates.last().url) {
                    setJobState(job, "Máy chủ này chậm/lỗi, đang đổi máy chủ khác…")
                }
            }
        }
        if (!ok) {
            setJobState(job, "❌ Tải lỗi. Bấm Tải lại hoặc quét lại link mới.")
        }
    }

    // ---------- Parallel segmented download (multi-connection) ----------
    private fun openConn(url: String, range: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", uaString)
        conn.setRequestProperty("Referer", "https://www.douyin.com/")
        if (range != null) conn.setRequestProperty("Range", range)
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        return conn
    }

    private fun downloadRange(url: String, start: Long, end: Long, dest: File, doneAtomic: AtomicLong, job: DownloadJob): Boolean {
        return try {
            val conn = openConn(url, "bytes=$start-$end")
            if (conn.responseCode != 206) return false
            conn.inputStream.use { input ->
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(start)
                    val buf = ByteArray(256 * 1024)
                    var pos = start
                    while (pos <= end) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), end - pos + 1).toInt())
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        pos += n
                        val d = doneAtomic.addAndGet(n.toLong())
                        job.done = d
                        updateJobProgress(job)
                    }
                    pos > end
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun streamDownload(url: String, dest: File, job: DownloadJob): Boolean {
        return try {
            val conn = openConn(url)
            if (conn.responseCode !in 200..299) return false
            job.total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        job.done = done
                        updateJobProgress(job)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun segmentedDownload(url: String, dest: File, job: DownloadJob): Boolean {
        var total = -1L
        try {
            val probe = openConn(url, "bytes=0-0")
            if (probe.responseCode == 206) {
                val cr = probe.getHeaderField("Content-Range")
                total = cr?.substringAfter('/')?.toLongOrNull() ?: -1
            }
            probe.disconnect()
        } catch (e: Exception) { }
        if (total <= 0) return streamDownload(url, dest, job)
        job.total = total
        job.done = 0
        job.lastPct = -1
        val segments = when {
            total < 2_000_000 -> 2
            total < 10_000_000 -> 6
            else -> 8
        }
        try {
            RandomAccessFile(dest, "rw").use { it.setLength(total) }
        } catch (e: Exception) { return false }
        val doneAtomic = AtomicLong(0)
        val errors = AtomicInteger(0)
        val threads = mutableListOf<Thread>()
        for (s in 0 until segments) {
            val start = total * s / segments
            val end = if (s == segments - 1) total - 1 else total * (s + 1) / segments - 1
            threads += Thread {
                var ok = false
                for (attempt in 0 until 3) {
                    if (downloadRange(url, start, end, dest, doneAtomic, job)) { ok = true; break }
                }
                if (!ok) errors.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        return errors.get() == 0 && dest.exists() && dest.length() == total
    }

    // ---------- Save to gallery / history ----------
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
