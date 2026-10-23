package com.example.apktest

import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import com.google.common.collect.ImmutableList
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class GainFadeProcessor(
    private val gain: Float,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val totalDurationUs: Long
) : BaseAudioProcessor() {

    private var bytesProcessed: Long = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return
        val format = inputAudioFormat
        val bytesPerFrame = format.bytesPerFrame
        val sampleRate = format.sampleRate
        val out = replaceOutputBuffer(size)

        val inDup = inputBuffer.duplicate()
        inDup.order(ByteOrder.LITTLE_ENDIAN)
        val inShorts = inDup.asShortBuffer()
        val outShorts = out.asShortBuffer()
        val totalShorts = inShorts.remaining()
        val channels = max(1, format.channelCount)
        val startFrame = if (bytesPerFrame > 0) bytesProcessed / bytesPerFrame else 0L

        var s = 0
        while (s < totalShorts) {
            val frameIndex = s / channels
            val timeUs = if (sampleRate > 0) (startFrame + frameIndex) * 1000000L / sampleRate else 0L
            var env = gain
            if (fadeInUs > 0 && timeUs < fadeInUs) {
                env *= (timeUs.toFloat() / fadeInUs.toFloat())
            }
            if (fadeOutUs > 0 && totalDurationUs > 0 && timeUs > totalDurationUs - fadeOutUs) {
                val remain = (totalDurationUs - timeUs).toFloat() / fadeOutUs.toFloat()
                env *= max(0f, min(1f, remain))
            }
            val sample = inShorts.get(s).toInt()
            var v = (sample * env).toInt()
            if (v > 32767) v = 32767
            if (v < -32768) v = -32768
            outShorts.put(s, v.toShort())
            s++
        }
        inputBuffer.position(inputBuffer.limit())
        bytesProcessed += size
        out.position(size)
        out.flip()
    }

    override fun onFlush() { bytesProcessed = 0 }
    override fun onReset() { bytesProcessed = 0 }
}

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private val accent = Color.rgb(255, 214, 10)
    private val bgDark = Color.rgb(16, 16, 20)
    private val bgPanel = Color.rgb(26, 26, 32)
    private val bgBtn = Color.rgb(44, 44, 54)

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var trimStartBar: SeekBar
    private lateinit var trimEndBar: SeekBar
    private lateinit var trimLabel: TextView
    private lateinit var hookInput: EditText
    private lateinit var audioLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var flipCheck: CheckBox
    private lateinit var muteCheck: CheckBox
    private lateinit var blurCheck: CheckBox
    private lateinit var zoomCheck: CheckBox
    private lateinit var fadeInCheck: CheckBox
    private lateinit var fadeOutCheck: CheckBox
    private lateinit var brightBar: SeekBar
    private lateinit var contrastBar: SeekBar
    private lateinit var saturBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var brightLabel: TextView
    private lateinit var contrastLabel: TextView
    private lateinit var saturLabel: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var colorLabel: TextView
    private lateinit var pitchLabel: TextView

    private val panels = mutableListOf<View>()
    private val tabButtons = mutableListOf<Button>()
    private lateinit var panelHost: FrameLayout

    private var videoUri: Uri? = null
    private var audioUri: Uri? = null
    private var durationMs: Long = 0
    private var trimStartMs: Long = 0
    private var trimEndMs: Long = 0
    private var speed: Float = 1.0f
    private var pitchVal: Float = 1.0f
    private var gainVal: Float = 1.0f
    private var rotationDeg: Int = 0
    private var ratioMode: Int = 0
    private var colorPreset: Int = 0
    private var brightnessVal: Float = 0f
    private var contrastVal: Float = 0f
    private var saturationVal: Float = 0f
    private var textOnTop: Boolean = false
    private var textYellow: Boolean = true

    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var seeking = false

    private val pickVideo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadVideo(uri)
    }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            audioUri = uri
            audioLabel.text = "Âm thanh thay thế: đã chọn 1 file"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%d:%02d", s / 60, s % 60)
    }
    private fun label(text: String, size: Float = 14f): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = size
        tv.setTextColor(Color.rgb(210, 210, 215))
        tv.setPadding(0, dp(8), 0, dp(2))
        return tv
    }
    private fun btn(text: String, onClick: (View) -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 13f
        b.setTextColor(Color.WHITE)
        b.setBackgroundColor(bgBtn)
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
    private fun styledSeek(sb: SeekBar) {
        sb.progressTintList = ColorStateList.valueOf(accent)
        sb.thumbTintList = ColorStateList.valueOf(accent)
    }
    private fun check(text: String): CheckBox {
        val c = CheckBox(this)
        c.text = text
        c.setTextColor(Color.WHITE)
        c.textSize = 13f
        return c
    }
    private fun newPanel(): LinearLayout {
        val p = LinearLayout(this)
        p.orientation = LinearLayout.VERTICAL
        p.setPadding(dp(12), dp(8), dp(12), dp(8))
        val wrapper = ScrollView(this)
        wrapper.setBackgroundColor(bgPanel)
        wrapper.addView(p)
        wrapper.visibility = View.GONE
        panels.add(wrapper)
        if (::panelHost.isInitialized) panelHost.addView(wrapper)
        return p
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bgDark)

        // Top bar
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.setPadding(dp(12), dp(8), dp(12), dp(8))
        val title = TextView(this)
        title.text = "LiteCut"
        title.textSize = 19f
        title.setTextColor(Color.WHITE)
        title.setTypeface(null, Typeface.BOLD)
        topBar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val exportBtn = Button(this)
        exportBtn.text = "XUẤT VIDEO"
        exportBtn.setTextColor(Color.BLACK)
        exportBtn.setTypeface(null, Typeface.BOLD)
        exportBtn.setBackgroundColor(accent)
        exportBtn.setOnClickListener { exportVideo() }
        topBar.addView(exportBtn)
        val pickBtn = Button(this)
        pickBtn.text = "+ Video"
        pickBtn.setTextColor(Color.WHITE)
        pickBtn.setBackgroundColor(bgBtn)
        pickBtn.setOnClickListener {
            pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        topBar.addView(pickBtn)
        root.addView(topBar)

        // Video preview (center, flexible height)
        playerView = PlayerView(this)
        playerView.setBackgroundColor(Color.BLACK)
        root.addView(playerView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        playerView.player = player

        // Transport
        val transport = LinearLayout(this)
        transport.orientation = LinearLayout.HORIZONTAL
        transport.setPadding(dp(10), dp(2), dp(10), dp(2))
        val playBtn = btn("▶") { if (player.isPlaying) player.pause() else player.play() }
        transport.addView(playBtn, LinearLayout.LayoutParams(dp(52), LinearLayout.LayoutParams.WRAP_CONTENT))
        seekBar = SeekBar(this)
        styledSeek(seekBar)
        transport.addView(seekBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        timeLabel = TextView(this)
        timeLabel.text = "0:00/0:00"
        timeLabel.setTextColor(Color.WHITE)
        timeLabel.textSize = 12f
        transport.addView(timeLabel)
        root.addView(transport)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player.seekTo(progress.toLong())
                    timeLabel.text = "${fmt(progress.toLong())}/${fmt(durationMs)}"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) { seeking = false }
        })

        // Progress + status (thin)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.progressTintList = ColorStateList.valueOf(accent)
        val pp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
        pp.setMargins(dp(10), 0, dp(10), 0)
        root.addView(progressBar, pp)
        statusLabel = TextView(this)
        statusLabel.text = "Bấm + Video để bắt đầu. Video xuất ra lưu ở Phim/LiteCut."
        statusLabel.setTextColor(Color.rgb(170, 170, 175))
        statusLabel.textSize = 12f
        statusLabel.setPadding(dp(12), dp(2), dp(12), dp(4))
        root.addView(statusLabel)

        // ===== Panels =====
        panelHost = FrameLayout(this)

        // Panel 1: Trim
        val p1 = newPanel()
        p1.addView(label("Cắt đoạn giữ lại:"))
        trimLabel = label("Giữ từ 0:00 đến 0:00")
        p1.addView(trimLabel)
        trimStartBar = SeekBar(this); styledSeek(trimStartBar)
        trimEndBar = SeekBar(this); styledSeek(trimEndBar)
        p1.addView(label("Điểm đầu"))
        p1.addView(trimStartBar)
        p1.addView(label("Điểm cuối"))
        p1.addView(trimEndBar)
        val trimListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                trimStartMs = trimStartBar.progress.toLong()
                trimEndMs = trimEndBar.progress.toLong()
                if (trimEndMs - trimStartMs < 500) {
                    if (sb === trimStartBar) { trimEndMs = trimStartMs + 500; trimEndBar.progress = trimEndMs.toInt() }
                    else { trimStartMs = trimEndMs - 500; trimStartBar.progress = trimStartMs.toInt() }
                }
                trimLabel.text = "Giữ từ ${fmt(trimStartMs)} đến ${fmt(trimEndMs)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { applyTrimToPreview() }
        }
        trimStartBar.setOnSeekBarChangeListener(trimListener)
        trimEndBar.setOnSeekBarChangeListener(trimListener)

        // Panel 2: Speed
        val p2 = newPanel()
        p2.addView(label("Tốc độ phát (áp dụng cả hình và tiếng):"))
        p2.addView(row(
            btn("0.5x") { setSpeed(0.5f) },
            btn("1x") { setSpeed(1.0f) },
            btn("1.2x") { setSpeed(1.2f) },
            btn("1.5x") { setSpeed(1.5f) },
            btn("2x") { setSpeed(2.0f) }
        ))
        p2.addView(label("Bản xem trước chạy đúng tốc độ đã chọn."))

        // Panel 3: Video effects
        val p3 = newPanel()
        colorLabel = label("Màu sẵn: Gốc")
        p3.addView(colorLabel)
        p3.addView(row(
            btn("Gốc") { setColorPreset(0, "Gốc") },
            btn("Ấm") { setColorPreset(1, "Ấm") },
            btn("Lạnh") { setColorPreset(2, "Lạnh") },
            btn("Đen trắng") { setColorPreset(3, "Đen trắng") },
            btn("Cổ điển") { setColorPreset(4, "Cổ điển") },
            btn("Sống động") { setColorPreset(5, "Sống động") }
        ))
        brightLabel = label("Độ sáng: 0")
        p3.addView(brightLabel)
        brightBar = SeekBar(this); styledSeek(brightBar); brightBar.max = 60; brightBar.progress = 30
        p3.addView(brightBar)
        contrastLabel = label("Tương phản: 0")
        p3.addView(contrastLabel)
        contrastBar = SeekBar(this); styledSeek(contrastBar); contrastBar.max = 60; contrastBar.progress = 30
        p3.addView(contrastBar)
        saturLabel = label("Độ bão hoà màu: 0")
        p3.addView(saturLabel)
        saturBar = SeekBar(this); styledSeek(saturBar); saturBar.max = 200; saturBar.progress = 100
        p3.addView(saturBar)
        val adjListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (sb) {
                    brightBar -> { brightnessVal = (progress - 30) / 100f; brightLabel.text = "Độ sáng: ${progress - 30}" }
                    contrastBar -> { contrastVal = (progress - 30) / 100f; contrastLabel.text = "Tương phản: ${progress - 30}" }
                    saturBar -> { saturationVal = (progress - 100).toFloat(); saturLabel.text = "Độ bão hoà màu: ${progress - 100}" }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { applyEffectsToPreview() }
        }
        brightBar.setOnSeekBarChangeListener(adjListener)
        contrastBar.setOnSeekBarChangeListener(adjListener)
        saturBar.setOnSeekBarChangeListener(adjListener)
        flipCheck = check("Lật gương")
        flipCheck.setOnCheckedChangeListener { _, _ -> applyEffectsToPreview() }
        blurCheck = check("Làm mờ")
        blurCheck.setOnCheckedChangeListener { _, _ -> applyEffectsToPreview() }
        zoomCheck = check("Zoom 1.3x")
        zoomCheck.setOnCheckedChangeListener { _, _ -> applyEffectsToPreview() }
        p3.addView(row(flipCheck, blurCheck, zoomCheck))

        // Panel 4: Text
        val p4 = newPanel()
        p4.addView(label("Chữ chạy suốt video (hook):"))
        hookInput = EditText(this)
        hookInput.hint = "Nhập dòng chữ, ví dụ: Xem hết mới hiểu!"
        hookInput.setTextColor(Color.WHITE)
        hookInput.setHintTextColor(Color.rgb(150, 150, 155))
        hookInput.setBackgroundColor(bgBtn)
        hookInput.setPadding(dp(10), dp(8), dp(10), dp(8))
        p4.addView(hookInput)
        p4.addView(row(
            btn("Chữ: Dưới ↑") { v ->
                textOnTop = !textOnTop
                (v as Button).text = if (textOnTop) "Chữ: Trên ↓" else "Chữ: Dưới ↑"
                applyEffectsToPreview()
            },
            btn("Màu: Vàng") { v ->
                textYellow = !textYellow
                (v as Button).text = if (textYellow) "Màu: Vàng" else "Màu: Trắng"
                applyEffectsToPreview()
            },
            btn("Áp chữ") { applyEffectsToPreview() }
        ))

        // Panel 5: Audio
        val p5 = newPanel()
        volumeLabel = label("Âm lượng gốc: 100%")
        p5.addView(volumeLabel)
        volumeBar = SeekBar(this); styledSeek(volumeBar); volumeBar.max = 200; volumeBar.progress = 100
        p5.addView(volumeBar)
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                gainVal = progress / 100f
                volumeLabel.text = "Âm lượng gốc: ${progress}%"
                player.volume = min(gainVal, 1f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        pitchLabel = label("Hiệu ứng giọng: Gốc")
        p5.addView(pitchLabel)
        p5.addView(row(
            btn("Giọng gốc") { setPitch(1.0f, "Gốc") },
            btn("Giọng nhí") { setPitch(1.5f, "Nhí (cao)") },
            btn("Giọng trầm") { setPitch(0.65f, "Trầm (thấp)") }
        ))
        fadeInCheck = check("Mở dần (fade in)")
        fadeOutCheck = check("Nhỏ dần cuối (fade out)")
        muteCheck = check("Tắt tiếng gốc")
        p5.addView(row(fadeInCheck, fadeOutCheck, muteCheck))
        audioLabel = label("Âm thanh thay thế: chưa chọn")
        p5.addView(audioLabel)
        p5.addView(row(
            btn("Chọn file nhạc/giọng") { pickAudio.launch("audio/*") },
            btn("Bỏ chọn") { audioUri = null; audioLabel.text = "Âm thanh thay thế: chưa chọn" }
        ))

        // Panel 6: Frame / ratio
        val p6 = newPanel()
        p6.addView(label("Tỉ lệ khung hình xuất ra:"))
        p6.addView(row(
            btn("Gốc") { setRatio(0) },
            btn("9:16") { setRatio(1) },
            btn("1:1") { setRatio(2) },
            btn("16:9") { setRatio(3) }
        ))
        p6.addView(btn("Xoay video 90°") { v ->
            rotationDeg = (rotationDeg + 90) % 360
            (v as Button).text = "Xoay video 90° (đang ${rotationDeg}°)"
            applyEffectsToPreview()
        })
        p6.addView(label("Độ phân giải giữ theo video gốc cho nhẹ và nhanh."))

        root.addView(panelHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(215)))

        // Bottom tab bar
        val tabBar = LinearLayout(this)
        tabBar.orientation = LinearLayout.HORIZONTAL
        tabBar.setBackgroundColor(bgPanel)
        val tabNames = listOf("✂\nCắt", "⚡\nTốc độ", "🎨\nHiệu ứng", "🔤\nChữ", "🔊\nÂm thanh", "🖼\nKhung")
        for (i in tabNames.indices) {
            val b = Button(this)
            b.text = tabNames[i]
            b.textSize = 11f
            b.setTextColor(Color.WHITE)
            b.setBackgroundColor(bgPanel)
            val idx = i
            b.setOnClickListener { selectTab(idx) }
            tabButtons.add(b)
            tabBar.addView(b, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        root.addView(tabBar)
        selectTab(0)

        setContentView(root)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && videoUri != null) {
                    val d = player.duration
                    if (d > 0) {
                        durationMs = d
                        seekBar.max = d.toInt()
                        trimStartBar.max = d.toInt()
                        trimEndBar.max = d.toInt()
                        trimEndBar.progress = d.toInt()
                        trimStartMs = 0
                        trimEndMs = d
                        trimLabel.text = "Giữ từ 0:00 đến ${fmt(d)}"
                        timeLabel.text = "0:00/${fmt(d)}"
                    }
                }
            }
        })
        handler.post(object : Runnable {
            override fun run() {
                if (!seeking && player.duration > 0) {
                    seekBar.progress = player.currentPosition.toInt()
                    timeLabel.text = "${fmt(player.currentPosition)}/${fmt(durationMs)}"
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun selectTab(index: Int) {
        for (i in panels.indices) {
            panels[i].visibility = if (i == index) View.VISIBLE else View.GONE
        }
        for (i in tabButtons.indices) {
            if (i == index) {
                tabButtons[i].setBackgroundColor(bgBtn)
                tabButtons[i].setTextColor(accent)
            } else {
                tabButtons[i].setBackgroundColor(bgPanel)
                tabButtons[i].setTextColor(Color.WHITE)
            }
        }
    }

    private fun loadVideo(uri: Uri) {
        videoUri = uri
        val item = MediaItem.Builder().setUri(uri).build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
        statusLabel.text = "Đã tải video. Chỉnh ở các tab bên dưới rồi bấm XUẤT VIDEO."
    }

    private fun buildPreviewItem(): MediaItem {
        val builder = MediaItem.Builder().setUri(videoUri!!)
        if (durationMs > 0 && (trimStartMs > 0 || trimEndMs < durationMs)) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs)
                    .setEndPositionMs(trimEndMs)
                    .build()
            )
        }
        return builder.build()
    }

    private fun applyTrimToPreview() {
        if (videoUri == null) return
        player.setMediaItem(buildPreviewItem())
        player.prepare()
        player.play()
    }

    private fun applyAudioPreview() {
        player.setPlaybackParameters(PlaybackParameters(speed, pitchVal))
        player.volume = min(gainVal, 1f)
    }

    private fun setSpeed(v: Float) {
        speed = v
        applyAudioPreview()
        applyEffectsToPreview()
        Toast.makeText(this, "Tốc độ: ${v}x", Toast.LENGTH_SHORT).show()
    }

    private fun setPitch(v: Float, name: String) {
        pitchVal = v
        pitchLabel.text = "Hiệu ứng giọng: $name"
        applyAudioPreview()
    }

    private fun setColorPreset(v: Int, name: String) {
        colorPreset = v
        colorLabel.text = "Màu sẵn: $name"
        applyEffectsToPreview()
    }

    private fun setRatio(mode: Int) {
        ratioMode = mode
        applyEffectsToPreview()
        val name = when (mode) { 1 -> "9:16"; 2 -> "1:1"; 3 -> "16:9"; else -> "Gốc" }
        Toast.makeText(this, "Tỉ lệ: $name", Toast.LENGTH_SHORT).show()
    }

    private fun buildVideoEffects(): MutableList<Effect> {
        val effects = mutableListOf<Effect>()
        val zoom = if (zoomCheck.isChecked) 1.3f else 1f
        if (flipCheck.isChecked || rotationDeg != 0 || zoomCheck.isChecked) {
            val sx = (if (flipCheck.isChecked) -1f else 1f) * zoom
            effects.add(
                ScaleAndRotateTransformation.Builder()
                    .setScale(sx, zoom)
                    .setRotationDegrees(rotationDeg.toFloat())
                    .build()
            )
        }
        when (ratioMode) {
            1 -> effects.add(Presentation.createForAspectRatio(9f / 16f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            2 -> effects.add(Presentation.createForAspectRatio(1f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            3 -> effects.add(Presentation.createForAspectRatio(16f / 9f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        }
        var bri = brightnessVal
        var con = contrastVal
        var sat = saturationVal
        var rgb: RgbAdjustment? = null
        when (colorPreset) {
            1 -> { rgb = RgbAdjustment.Builder().setRedScale(1.18f).setGreenScale(1.02f).setBlueScale(0.82f).build(); sat += 8f }
            2 -> { rgb = RgbAdjustment.Builder().setRedScale(0.88f).setGreenScale(1.0f).setBlueScale(1.18f).build() }
            3 -> { sat = -100f }
            4 -> { rgb = RgbAdjustment.Builder().setRedScale(1.12f).setGreenScale(1.0f).setBlueScale(0.85f).build(); sat -= 45f; bri += 0.05f }
            5 -> { sat += 40f; con += 0.12f }
        }
        if (bri != 0f) effects.add(Brightness(bri.coerceIn(-1f, 1f)))
        if (con != 0f) effects.add(Contrast(con.coerceIn(-1f, 1f)))
        sat = sat.coerceIn(-100f, 100f)
        if (sat != 0f) effects.add(HslAdjustment.Builder().adjustSaturation(sat).build())
        rgb?.let { effects.add(it) }
        if (blurCheck.isChecked) effects.add(GaussianBlur(4f))
        if (speed != 1.0f) effects.add(SpeedChangeEffect(speed))
        val text = hookInput.text.toString().trim()
        if (text.isNotEmpty()) {
            val spannable = SpannableString(text)
            val color = if (textYellow) Color.YELLOW else Color.WHITE
            spannable.setSpan(ForegroundColorSpan(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(BackgroundColorSpan(Color.argb(150, 0, 0, 0)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(AbsoluteSizeSpan(130, false), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val anchorY = if (textOnTop) 0.78f else -0.78f
            val settings = OverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, anchorY)
                .setOverlayFrameAnchor(0f, 0f)
                .build()
            val overlay = TextOverlay.createStaticTextOverlay(spannable, settings)
            effects.add(OverlayEffect(ImmutableList.of(overlay)))
        }
        return effects
    }

    private fun buildAudioProcessors(): MutableList<AudioProcessor> {
        val list = mutableListOf<AudioProcessor>()
        if (speed != 1.0f || pitchVal != 1.0f) {
            val sonic = SonicAudioProcessor()
            sonic.setSpeed(speed)
            sonic.setPitch(pitchVal)
            list.add(sonic)
        }
        if (gainVal != 1.0f || fadeInCheck.isChecked || fadeOutCheck.isChecked) {
            val totalOutUs = if (durationMs > 0) ((trimEndMs - trimStartMs) * 1000L / speed).toLong() else 0L
            list.add(
                GainFadeProcessor(
                    gainVal,
                    if (fadeInCheck.isChecked) 800000L else 0L,
                    if (fadeOutCheck.isChecked) 1200000L else 0L,
                    totalOutUs
                )
            )
        }
        return list
    }

    private fun applyEffectsToPreview() {
        if (videoUri == null) return
        try {
            player.setVideoEffects(buildVideoEffects())
        } catch (e: Exception) {
            statusLabel.text = "Xem trước hiệu ứng lỗi: ${e.message} (xuất video vẫn thử bình thường)"
        }
    }

    private fun exportVideo() {
        val uri = videoUri
        if (uri == null) {
            Toast.makeText(this, "Chưa chọn video", Toast.LENGTH_SHORT).show()
            return
        }
        val outFile = File(cacheDir, "litecut_out.mp4")
        if (outFile.exists()) outFile.delete()

        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        if (durationMs > 0 && (trimStartMs > 0 || trimEndMs < durationMs)) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs)
                    .setEndPositionMs(trimEndMs)
                    .build()
            )
        }
        val videoItem = mediaItemBuilder.build()

        val hasReplacementAudio = audioUri != null
        val editedBuilder = EditedMediaItem.Builder(videoItem)
            .setEffects(Effects(buildAudioProcessors(), buildVideoEffects()))
            .setRemoveAudio(muteCheck.isChecked || hasReplacementAudio)

        val t = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    progressBar.progress = 100
                    val saved = saveToGallery(outFile)
                    statusLabel.text = if (saved != null) "Xong! Đã lưu video vào: $saved"
                        else "Xuất xong nhưng lưu vào thư viện lỗi, file tạm: ${outFile.absolutePath}"
                    Toast.makeText(this@MainActivity, "Xuất video thành công", Toast.LENGTH_LONG).show()
                }
                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    statusLabel.text = "Lỗi xuất video: ${exception.message}"
                    Toast.makeText(this@MainActivity, "Lỗi: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            })
            .build()
        transformer = t

        statusLabel.text = "Đang xuất video…"
        progressBar.progress = 0
        if (hasReplacementAudio) {
            val clipLen = if (durationMs > 0) trimEndMs - trimStartMs else 0
            val audioItemBuilder = MediaItem.Builder().setUri(audioUri!!)
            if (clipLen > 0) {
                audioItemBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(clipLen).build()
                )
            }
            val audioEdited = EditedMediaItem.Builder(audioItemBuilder.build())
                .setEffects(Effects(buildAudioProcessors(), mutableListOf<Effect>()))
                .build()
            val composition = Composition.Builder(
                EditedMediaItemSequence(editedBuilder.build()),
                EditedMediaItemSequence(audioEdited)
            ).build()
            t.start(composition, outFile.absolutePath)
        } else {
            t.start(editedBuilder.build(), outFile.absolutePath)
        }
        pollProgress(t)
    }

    private fun pollProgress(t: Transformer) {
        val holder = ProgressHolder()
        handler.post(object : Runnable {
            override fun run() {
                if (transformer !== t) return
                val state = t.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    progressBar.progress = holder.progress
                    handler.postDelayed(this, 400)
                } else if (state == Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY) {
                    handler.postDelayed(this, 400)
                }
            }
        })
    }

    private fun saveToGallery(file: File): String? {
        val name = "LiteCut_${System.currentTimeMillis()}.mp4"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LiteCut")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val destUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                contentResolver.openOutputStream(destUri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(destUri, values, null, null)
                "Phim/LiteCut/$name"
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "LiteCut")
                dir.mkdirs()
                val dest = File(dir, name)
                file.copyTo(dest, overwrite = true)
                dest.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        transformer?.cancel()
        player.release()
        super.onDestroy()
    }
}
