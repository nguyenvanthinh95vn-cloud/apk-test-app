package com.example.apktest

import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

@OptIn(UnstableApi::class)
abstract class PcmProcessor : BaseAudioProcessor() {
    protected var framePos: Long = 0
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        framePos = 0
        onFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }
    open fun onFormat(sampleRate: Int, channels: Int) {}
    abstract fun processSample(sample: Int, channel: Int, frameIndex: Long): Int
    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size <= 0) return
        val format = inputAudioFormat
        val channels = max(1, format.channelCount)
        val bytesPerFrame = format.bytesPerFrame
        val out = replaceOutputBuffer(size)
        val inDup = inputBuffer.duplicate()
        inDup.order(ByteOrder.LITTLE_ENDIAN)
        val inShorts = inDup.asShortBuffer()
        val outShorts = out.asShortBuffer()
        val totalShorts = inShorts.remaining()
        var s = 0
        while (s < totalShorts) {
            val frameIndex = framePos + (s / channels)
            val v = processSample(inShorts.get(s).toInt(), s % channels, frameIndex)
            outShorts.put(s, v.coerceIn(-32768, 32767).toShort())
            s++
        }
        if (bytesPerFrame > 0) framePos += size / bytesPerFrame
        inputBuffer.position(inputBuffer.limit())
        out.position(size)
        out.flip()
    }
    override fun onFlush() { framePos = 0; onFormat(inputAudioFormat.sampleRate, max(1, inputAudioFormat.channelCount)) }
    override fun onReset() { framePos = 0 }
}

@OptIn(UnstableApi::class)
class GainFadeProcessor(
    private val gain: Float,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val totalDurationUs: Long,
    private val sampleRateHint: Int = 44100
) : PcmProcessor() {
    private var sampleRate: Int = sampleRateHint
    override fun onFormat(sr: Int, channels: Int) { sampleRate = sr }
    override fun processSample(sample: Int, channel: Int, frameIndex: Long): Int {
        val timeUs = if (sampleRate > 0) frameIndex * 1000000L / sampleRate else 0L
        var env = gain
        if (fadeInUs > 0 && timeUs < fadeInUs) env *= (timeUs.toFloat() / fadeInUs.toFloat())
        if (fadeOutUs > 0 && totalDurationUs > 0 && timeUs > totalDurationUs - fadeOutUs) {
            val remain = (totalDurationUs - timeUs).toFloat() / fadeOutUs.toFloat()
            env *= max(0f, min(1f, remain))
        }
        return (sample * env).toInt()
    }
}

@OptIn(UnstableApi::class)
class EchoProcessor : PcmProcessor() {
    private var delayLine: FloatArray = FloatArray(0)
    private var delayPos: Int = 0
    override fun onFormat(sr: Int, channels: Int) {
        val delaySamples = (sr * 180 / 1000) * max(1, channels)
        delayLine = FloatArray(max(1, delaySamples))
        delayPos = 0
    }
    override fun processSample(sample: Int, channel: Int, frameIndex: Long): Int {
        if (delayLine.isEmpty()) return sample
        val d = delayLine[delayPos]
        val out = sample + d * 0.45f
        delayLine[delayPos] = sample + d * 0.28f
        delayPos = (delayPos + 1) % delayLine.size
        return out.toInt()
    }
}

@OptIn(UnstableApi::class)
class ToneProcessor(private val mode: Int) : PcmProcessor() {
    private var lpState: FloatArray = floatArrayOf(0f, 0f)
    private var hpPrevIn: FloatArray = floatArrayOf(0f, 0f)
    private var hpPrevOut: FloatArray = floatArrayOf(0f, 0f)
    private var sampleRate: Int = 44100
    override fun onFormat(sr: Int, channels: Int) {
        sampleRate = sr
        lpState = FloatArray(max(2, channels))
        hpPrevIn = FloatArray(max(2, channels))
        hpPrevOut = FloatArray(max(2, channels))
    }
    override fun processSample(sample: Int, channel: Int, frameIndex: Long): Int {
        val ch = min(channel, lpState.size - 1)
        return when (mode) {
            1 -> { // lowpass: warmer / bass
                lpState[ch] += 0.22f * (sample - lpState[ch])
                (lpState[ch] * 1.25f).toInt()
            }
            2 -> { // highpass: brighter
                val alpha = 0.72f
                val out = alpha * (hpPrevOut[ch] + sample - hpPrevIn[ch])
                hpPrevIn[ch] = sample.toFloat()
                hpPrevOut[ch] = out
                (out * 1.3f).toInt()
            }
            else -> { // robot: ring modulation 28 Hz
                val t = frameIndex.toDouble() / max(1, sampleRate)
                val mod = sin(2.0 * Math.PI * 28.0 * t).toFloat()
                (sample * mod * 1.4f).toInt()
            }
        }
    }
}

// ---------- Synthesized sound effects (no asset files needed) ----------
object SfxSynth {
    const val SAMPLE_RATE = 44100
    val NAMES = listOf("Tiếng bíp", "Bấm bàn phím", "Tiếng pop", "Chuông ding", "Tiếng vút", "Chụp ảnh")

    fun synth(type: Int): ShortArray {
        val rnd = Random(1234 + type)
        return when (type) {
            0 -> { // beep: 880 Hz sine, 0.18s decay
                val n = (SAMPLE_RATE * 0.18).toInt()
                ShortArray(n) { i ->
                    val t = i.toDouble() / SAMPLE_RATE
                    val env = max(0.0, 1.0 - i.toDouble() / n)
                    (sin(2.0 * Math.PI * 880.0 * t) * 0.55 * env * 32767).toInt().toShort()
                }
            }
            1 -> { // keyboard click: short noise burst + low thock
                val n = (SAMPLE_RATE * 0.07).toInt()
                ShortArray(n) { i ->
                    val t = i.toDouble() / SAMPLE_RATE
                    val env = max(0.0, 1.0 - i.toDouble() / n)
                    val noise = rnd.nextDouble() * 2.0 - 1.0
                    val thock = sin(2.0 * Math.PI * 190.0 * t) * 0.5
                    ((noise * 0.35 + thock) * env * 32767).toInt().toShort()
                }
            }
            2 -> { // pop: sine sweep 480 -> 110 Hz, 0.09s
                val n = (SAMPLE_RATE * 0.09).toInt()
                ShortArray(n) { i ->
                    val f = 480.0 - 370.0 * (i.toDouble() / n)
                    val t = i.toDouble() / SAMPLE_RATE
                    val env = max(0.0, 1.0 - i.toDouble() / n)
                    (sin(2.0 * Math.PI * f * t) * 0.7 * env * 32767).toInt().toShort()
                }
            }
            3 -> { // ding: 1318 Hz + harmonic, 0.5s exp decay
                val n = (SAMPLE_RATE * 0.5).toInt()
                ShortArray(n) { i ->
                    val t = i.toDouble() / SAMPLE_RATE
                    val env = Math.exp(-6.0 * i.toDouble() / n)
                    val v = sin(2.0 * Math.PI * 1318.0 * t) * 0.5 + sin(2.0 * Math.PI * 1976.0 * t) * 0.22
                    (v * env * 32767).toInt().toShort()
                }
            }
            4 -> { // whoosh: noise 0.45s, attack-decay envelope
                val n = (SAMPLE_RATE * 0.45).toInt()
                ShortArray(n) { i ->
                    val x = i.toDouble() / n
                    val env = sin(Math.PI * x)
                    val noise = rnd.nextDouble() * 2.0 - 1.0
                    (noise * 0.4 * env * 32767).toInt().toShort()
                }
            }
            else -> { // camera shutter: two noise bursts
                val n = (SAMPLE_RATE * 0.22).toInt()
                ShortArray(n) { i ->
                    val inBurst = (i < SAMPLE_RATE * 0.03) || (i > SAMPLE_RATE * 0.10 && i < SAMPLE_RATE * 0.14)
                    if (!inBurst) 0.toShort()
                    else {
                        val noise = rnd.nextDouble() * 2.0 - 1.0
                        (noise * 0.6 * 32767).toInt().toShort()
                    }
                }
            }
        }
    }

    fun writeWav(file: File, samples: ShortArray) {
        val dataSize = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataSize)
        FileOutputStream(file).use { out ->
            out.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            val sb = body.asShortBuffer()
            sb.put(samples)
            out.write(body.array())
        }
    }

    fun sfxFile(cacheDir: File, type: Int): File {
        val f = File(cacheDir, "sfx_$type.wav")
        if (!f.exists()) writeWav(f, synth(type))
        return f
    }

    fun silenceFile(cacheDir: File, durationMs: Long): File {
        val f = File(cacheDir, "silence_$durationMs.wav")
        if (!f.exists()) {
            val n = (SAMPLE_RATE * max(0L, durationMs) / 1000).toInt()
            writeWav(f, ShortArray(n))
        }
        return f
    }
}

data class ClipItem(val uri: Uri, var durationMs: Long = 0, var trimStart: Long = 0, var trimEnd: Long = 0)
data class SfxEntry(val type: Int, val posPercent: Int)

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private val accent = Color.rgb(255, 214, 10)
    private val bgPanel = Color.rgb(26, 26, 32)
    private val bgBtn = Color.rgb(48, 48, 60)

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var hookPreview: TextView
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
    private lateinit var echoCheck: CheckBox
    private lateinit var robotCheck: CheckBox
    private lateinit var bassCheck: CheckBox
    private lateinit var brightAudioCheck: CheckBox
    private lateinit var brightBar: SeekBar
    private lateinit var contrastBar: SeekBar
    private lateinit var saturBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var sfxPosBar: SeekBar
    private lateinit var brightLabel: TextView
    private lateinit var contrastLabel: TextView
    private lateinit var saturLabel: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var colorLabel: TextView
    private lateinit var pitchLabel: TextView
    private lateinit var sfxLabel: TextView
    private lateinit var sfxListLabel: TextView
    private lateinit var clipChipsBox: LinearLayout
    private lateinit var projectLabel: TextView
    private lateinit var dupLabel: TextView

    private val panels = mutableListOf<View>()
    private val tabButtons = mutableListOf<Button>()
    private lateinit var panelHost: FrameLayout

    private val clips = mutableListOf<ClipItem>()
    private var selectedClip: Int = -1
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
    private var fontMode: Int = 0
    private var textSizeMode: Int = 1
    private var textStyleMode: Int = 0
    private var sfxType: Int = 0
    private val sfxEntries = mutableListOf<SfxEntry>()

    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var seeking = false

    private val pickVideos = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) addClips(uris)
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
    private fun btn(text: String, onClick: (View) -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 13f
        b.setTextColor(Color.WHITE)
        b.background = roundedBg(bgBtn)
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
    private fun styledSeek(sb: SeekBar) {
        sb.progressTintList = ColorStateList.valueOf(accent)
        sb.thumbTintList = ColorStateList.valueOf(accent)
    }
    private fun check(text: String): CheckBox {
        val c = CheckBox(this)
        c.text = text
        c.setTextColor(Color.WHITE)
        c.textSize = 13f
        c.buttonTintList = ColorStateList.valueOf(accent)
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
        val rootBg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(27, 27, 34), Color.rgb(10, 10, 14)))
        root.background = rootBg

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
        val pickBtn = Button(this)
        pickBtn.text = "+ Video"
        pickBtn.setTextColor(Color.WHITE)
        pickBtn.background = roundedBg(bgBtn, 14)
        pickBtn.elevation = dp(3).toFloat()
        pickBtn.setOnClickListener { launchPicker() }
        topBar.addView(pickBtn)
        val exportBtn = Button(this)
        exportBtn.text = "XUẤT VIDEO"
        exportBtn.setTextColor(Color.BLACK)
        exportBtn.setTypeface(null, Typeface.BOLD)
        exportBtn.background = roundedBg(accent, 14)
        exportBtn.elevation = dp(4).toFloat()
        exportBtn.setOnClickListener { exportVideo() }
        val ebLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        ebLp.setMargins(dp(8), 0, 0, 0)
        topBar.addView(exportBtn, ebLp)
        root.addView(topBar)

        // Preview: video + live text overlay (always visible while typing)
        val previewBox = FrameLayout(this)
        playerView = PlayerView(this)
        playerView.setBackgroundColor(Color.BLACK)
        previewBox.addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        hookPreview = TextView(this)
        hookPreview.setTextColor(Color.YELLOW)
        hookPreview.setTypeface(null, Typeface.BOLD)
        hookPreview.gravity = Gravity.CENTER
        hookPreview.visibility = View.GONE
        val hpLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        hpLp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        hpLp.setMargins(dp(16), dp(16), dp(16), dp(20))
        previewBox.addView(hookPreview, hpLp)
        root.addView(previewBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
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

        // Progress + status
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.progressTintList = ColorStateList.valueOf(accent)
        val pp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
        pp.setMargins(dp(10), 0, dp(10), 0)
        root.addView(progressBar, pp)
        statusLabel = TextView(this)
        statusLabel.text = "Bấm + Video để bắt đầu (chọn được nhiều clip). Video xuất ra lưu ở Phim/LiteCut."
        statusLabel.setTextColor(Color.rgb(170, 170, 175))
        statusLabel.textSize = 12f
        statusLabel.setPadding(dp(12), dp(2), dp(12), dp(4))
        root.addView(statusLabel)

        // ===== Panels =====
        panelHost = FrameLayout(this)

        // Panel: Ghép (project clips)
        val pGhep = newPanel()
        projectLabel = label("Dự án: chưa có clip nào")
        pGhep.addView(projectLabel)
        val chipsScroll = HorizontalScrollView(this)
        clipChipsBox = LinearLayout(this)
        clipChipsBox.orientation = LinearLayout.HORIZONTAL
        chipsScroll.addView(clipChipsBox)
        pGhep.addView(chipsScroll)
        pGhep.addView(row(
            btn("＋ Thêm video") { launchPicker() },
            btn("🗑 Xoá clip chọn") { deleteSelectedClip() }
        ))
        pGhep.addView(label("Bấm vào 1 clip để chọn và xem trước. Cắt/tốc độ/hiệu ứng áp cho mọi clip khi xuất, riêng đoạn cắt thì theo từng clip đang chọn."))

        // Panel: Nhân đôi
        val pDup = newPanel()
        dupLabel = label("Nhân đôi video")
        pDup.addView(dupLabel)
        pDup.addView(btn("📑 Nhân đôi clip đang chọn") { duplicateSelectedClip() })
        pDup.addView(btn("📑📑 Nhân đôi toàn bộ dự án") { duplicateAllClips() })
        pDup.addView(label("Clip nhân đôi được chèn ngay sau clip gốc, xuất video sẽ dài gấp đôi phần đó."))

        // Panel: Cắt
        val p1 = newPanel()
        p1.addView(label("Cắt đoạn giữ lại (cho clip đang chọn):"))
        trimLabel = label("Giữ từ 0:00 đến 0:00")
        p1.addView(trimLabel)
        p1.addView(label("Điểm đầu"))
        trimStartBar = SeekBar(this); styledSeek(trimStartBar)
        p1.addView(trimStartBar)
        p1.addView(label("Điểm cuối"))
        trimEndBar = SeekBar(this); styledSeek(trimEndBar)
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
                saveTrimToClip()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { applyTrimToPreview() }
        }
        trimStartBar.setOnSeekBarChangeListener(trimListener)
        trimEndBar.setOnSeekBarChangeListener(trimListener)

        // Panel: Tốc độ
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

        // Panel: Hiệu ứng video
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
                applyEffectsToPreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
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

        // Panel: Chữ
        val p4 = newPanel()
        p4.addView(label("Chữ trên video — gõ là hiện ngay trên khung xem trước:"))
        hookInput = EditText(this)
        hookInput.hint = "Nhập dòng chữ, ví dụ: Xem hết mới hiểu!"
        hookInput.setTextColor(Color.WHITE)
        hookInput.setHintTextColor(Color.rgb(150, 150, 155))
        hookInput.background = roundedBg(bgBtn, 10)
        hookInput.setPadding(dp(10), dp(8), dp(10), dp(8))
        p4.addView(hookInput)
        hookInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateHookPreview() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        p4.addView(label("Phông chữ:"))
        p4.addView(row(
            btn("Đậm") { setFont(0) },
            btn("Có chân") { setFont(1) },
            btn("Máy chữ") { setFont(2) },
            btn("Bay bướm") { setFont(3) }
        ))
        p4.addView(label("Cỡ và kiểu chữ:"))
        p4.addView(row(
            btn("Cỡ nhỏ") { textSizeMode = 0; updateHookPreview() },
            btn("Cỡ vừa") { textSizeMode = 1; updateHookPreview() },
            btn("Cỡ lớn") { textSizeMode = 2; updateHookPreview() },
            btn("Nền mờ") { textStyleMode = 0; updateHookPreview() },
            btn("Gạch chân") { textStyleMode = 1; updateHookPreview() },
            btn("Nghiêng") { textStyleMode = 2; updateHookPreview() }
        ))
        p4.addView(row(
            btn("Chữ: Dưới ↑") { v ->
                textOnTop = !textOnTop
                (v as Button).text = if (textOnTop) "Chữ: Trên ↓" else "Chữ: Dưới ↑"
                updateHookPreview()
            },
            btn("Màu: Vàng") { v ->
                textYellow = !textYellow
                (v as Button).text = if (textYellow) "Màu: Vàng" else "Màu: Trắng"
                updateHookPreview()
            }
        ))

        // Panel: Âm thanh
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
        echoCheck = check("Tiếng vang (echo)")
        robotCheck = check("Giọng robot")
        bassCheck = check("Âm trầm ấm")
        brightAudioCheck = check("Âm sáng rõ")
        p5.addView(row(echoCheck, robotCheck))
        p5.addView(row(bassCheck, brightAudioCheck))
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
        p5.addView(label("Chèn hiệu ứng âm thanh vào video:"))
        sfxLabel = label("Loại tiếng: Tiếng bíp · Vị trí: 50%")
        p5.addView(sfxLabel)
        p5.addView(row(
            btn("Bíp") { setSfxType(0) },
            btn("Bấm phím") { setSfxType(1) },
            btn("Pop") { setSfxType(2) },
            btn("Ding") { setSfxType(3) },
            btn("Vút") { setSfxType(4) },
            btn("Chụp ảnh") { setSfxType(5) }
        ))
        sfxPosBar = SeekBar(this); styledSeek(sfxPosBar); sfxPosBar.max = 100; sfxPosBar.progress = 50
        p5.addView(sfxPosBar)
        sfxPosBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) updateSfxLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        p5.addView(row(
            btn("▶ Nghe thử") { previewSfx() },
            btn("＋ Chèn vào video") { addSfxEntry() },
            btn("Xoá hết tiếng chèn") { sfxEntries.clear(); updateSfxListLabel() }
        ))
        sfxListLabel = label("Chưa chèn tiếng nào")
        p5.addView(sfxListLabel)

        // Panel: Khung
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

        // Bottom tab bar (scrollable, CapCut style)
        val tabScroll = HorizontalScrollView(this)
        tabScroll.setBackgroundColor(bgPanel)
        val tabBar = LinearLayout(this)
        tabBar.orientation = LinearLayout.HORIZONTAL
        val tabNames = listOf("📂\nGhép", "📑\nNhân đôi", "✂\nCắt", "⚡\nTốc độ", "🎨\nHiệu ứng", "🔤\nChữ", "🔊\nÂm thanh", "🖼\nKhung")
        for (i in tabNames.indices) {
            val b = Button(this)
            b.text = tabNames[i]
            b.textSize = 11f
            b.setTextColor(Color.WHITE)
            b.background = roundedBg(bgPanel, 10)
            val idx = i
            b.setOnClickListener { selectTab(idx) }
            tabButtons.add(b)
            tabBar.addView(b, LinearLayout.LayoutParams(dp(78), dp(58)))
        }
        tabScroll.addView(tabBar)
        root.addView(tabScroll)
        selectTab(0)

        setContentView(root)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && videoUri != null) {
                    val d = player.duration
                    if (d > 0) {
                        val c = if (selectedClip in clips.indices) clips[selectedClip] else null
                        if (c != null && c.durationMs == 0L) {
                            c.durationMs = d
                            c.trimStart = 0
                            c.trimEnd = d
                            trimStartMs = 0
                            trimEndMs = d
                        }
                        val fullDur = if (c != null && c.durationMs > 0) c.durationMs else d
                        val isNew = (durationMs == 0L || durationMs != fullDur)
                        durationMs = fullDur
                        seekBar.max = d.toInt()
                        trimStartBar.max = fullDur.toInt()
                        trimEndBar.max = fullDur.toInt()
                        if (isNew) {
                            trimStartBar.progress = trimStartMs.toInt()
                            trimEndBar.progress = trimEndMs.toInt()
                            trimLabel.text = "Giữ từ ${fmt(trimStartMs)} đến ${fmt(trimEndMs)}"
                        }
                        timeLabel.text = "0:00/${fmt(d)}"
                        refreshClipChips()
                    }
                }
            }
        })
        handler.post(object : Runnable {
            override fun run() {
                if (!seeking && player.duration > 0) {
                    seekBar.progress = player.currentPosition.toInt()
                    timeLabel.text = "${fmt(player.currentPosition)}/${fmt(player.duration)}"
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
            val b = tabButtons[i]
            if (i == index) {
                b.background = roundedBg(accent, 10)
                b.setTextColor(Color.BLACK)
            } else {
                b.background = roundedBg(bgPanel, 10)
                b.setTextColor(Color.WHITE)
            }
        }
    }

    private fun launchPicker() {
        pickVideos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    private fun saveTrimToClip() {
        if (selectedClip in clips.indices) {
            clips[selectedClip].trimStart = trimStartMs
            clips[selectedClip].trimEnd = trimEndMs
        }
    }

    private fun addClips(uris: List<Uri>) {
        val wasEmpty = clips.isEmpty()
        for (u in uris) clips.add(ClipItem(u))
        if (wasEmpty) {
            selectClip(0)
        } else {
            refreshClipChips()
        }
        statusLabel.text = "Dự án có ${clips.size} clip. Xuất video sẽ ghép nối tiếp theo thứ tự."
        Toast.makeText(this, "Đã thêm ${uris.size} video vào dự án", Toast.LENGTH_SHORT).show()
    }

    private fun selectClip(index: Int) {
        if (index !in clips.indices) return
        saveTrimToClip()
        selectedClip = index
        val c = clips[index]
        videoUri = c.uri
        durationMs = c.durationMs
        trimStartMs = c.trimStart
        trimEndMs = c.trimEnd
        player.setMediaItem(buildPreviewItem())
        player.prepare()
        player.play()
        if (c.durationMs > 0) {
            trimStartBar.max = c.durationMs.toInt()
            trimEndBar.max = c.durationMs.toInt()
            trimStartBar.progress = trimStartMs.toInt()
            trimEndBar.progress = trimEndMs.toInt()
            trimLabel.text = "Giữ từ ${fmt(trimStartMs)} đến ${fmt(trimEndMs)}"
        } else {
            trimLabel.text = "Đang đọc độ dài clip…"
        }
        refreshClipChips()
    }

    private fun refreshClipChips() {
        if (!::clipChipsBox.isInitialized) return
        clipChipsBox.removeAllViews()
        for (i in clips.indices) {
            val c = clips[i]
            val b = Button(this)
            b.text = if (c.durationMs > 0) "Clip ${i + 1} · ${fmt(c.durationMs)}" else "Clip ${i + 1}"
            b.textSize = 12f
            if (i == selectedClip) {
                b.setTextColor(Color.BLACK)
                b.background = roundedBg(accent, 10)
            } else {
                b.setTextColor(Color.WHITE)
                b.background = roundedBg(bgBtn, 10)
            }
            val idx = i
            b.setOnClickListener { selectClip(idx) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
            lp.setMargins(dp(2), dp(4), dp(2), dp(4))
            clipChipsBox.addView(b, lp)
        }
        projectLabel.text = "Dự án: ${clips.size} clip · tổng ${fmt(projectDurationMs())}"
        dupLabel.text = "Nhân đôi video — dự án đang có ${clips.size} clip, đang chọn clip ${if (selectedClip >= 0) selectedClip + 1 else 0}"
    }

    private fun deleteSelectedClip() {
        if (selectedClip !in clips.indices) return
        if (clips.size <= 1) {
            Toast.makeText(this, "Dự án phải có ít nhất 1 clip", Toast.LENGTH_SHORT).show()
            return
        }
        clips.removeAt(selectedClip)
        val next = min(selectedClip, clips.size - 1)
        selectedClip = -1
        selectClip(next)
        Toast.makeText(this, "Đã xoá clip", Toast.LENGTH_SHORT).show()
    }

    private fun duplicateSelectedClip() {
        if (selectedClip !in clips.indices) {
            Toast.makeText(this, "Chưa chọn clip nào", Toast.LENGTH_SHORT).show()
            return
        }
        saveTrimToClip()
        val c = clips[selectedClip]
        clips.add(selectedClip + 1, ClipItem(c.uri, c.durationMs, c.trimStart, c.trimEnd))
        refreshClipChips()
        Toast.makeText(this, "Đã nhân đôi clip ${selectedClip + 1} → dự án có ${clips.size} clip", Toast.LENGTH_SHORT).show()
    }

    private fun duplicateAllClips() {
        if (clips.isEmpty()) {
            Toast.makeText(this, "Chưa có clip nào", Toast.LENGTH_SHORT).show()
            return
        }
        saveTrimToClip()
        val copies = clips.map { ClipItem(it.uri, it.durationMs, it.trimStart, it.trimEnd) }
        clips.addAll(copies)
        refreshClipChips()
        Toast.makeText(this, "Đã nhân đôi toàn bộ → dự án có ${clips.size} clip", Toast.LENGTH_SHORT).show()
    }

    private fun projectDurationMs(): Long {
        var total = 0L
        for (c in clips) {
            if (c.durationMs > 0) {
                val end = if (c.trimEnd in 1..c.durationMs) c.trimEnd else c.durationMs
                total += (end - c.trimStart).coerceAtLeast(0)
            }
        }
        return total
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
        saveTrimToClip()
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

    private fun fontFamily(): String = when (fontMode) {
        1 -> "serif"
        2 -> "monospace"
        3 -> "cursive"
        else -> "sans-serif"
    }

    private fun setFont(mode: Int) {
        fontMode = mode
        updateHookPreview()
        applyEffectsToPreview()
    }

    private fun updateHookPreview() {
        if (!::hookPreview.isInitialized || !::hookInput.isInitialized) return
        val text = hookInput.text.toString()
        if (text.isBlank()) {
            hookPreview.visibility = View.GONE
            return
        }
        hookPreview.visibility = View.VISIBLE
        hookPreview.text = text
        hookPreview.setTextColor(if (textYellow) Color.YELLOW else Color.WHITE)
        val style = if (textStyleMode == 2) Typeface.BOLD_ITALIC else Typeface.BOLD
        hookPreview.typeface = Typeface.create(fontFamily(), style)
        val sp = when (textSizeMode) { 0 -> 16f; 2 -> 30f; else -> 22f }
        hookPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        hookPreview.paint.isUnderlineText = (textStyleMode == 1)
        if (textStyleMode == 0) {
            hookPreview.background = roundedBg(Color.argb(150, 0, 0, 0), 8)
            hookPreview.setPadding(dp(10), dp(4), dp(10), dp(4))
        } else {
            hookPreview.background = null
            hookPreview.setPadding(dp(2), dp(2), dp(2), dp(2))
            hookPreview.setShadowLayer(6f, 2f, 2f, Color.BLACK)
        }
        val lp = hookPreview.layoutParams as FrameLayout.LayoutParams
        lp.gravity = (if (textOnTop) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        hookPreview.layoutParams = lp
        applyEffectsToPreview()
    }

    private fun setSfxType(t: Int) {
        sfxType = t
        updateSfxLabel()
    }

    private fun updateSfxLabel() {
        if (!::sfxLabel.isInitialized) return
        val pos = if (::sfxPosBar.isInitialized) sfxPosBar.progress else 50
        sfxLabel.text = "Loại tiếng: ${SfxSynth.NAMES[sfxType]} · Vị trí: ${pos}%"
    }

    private fun previewSfx() {
        try {
            val f = SfxSynth.sfxFile(cacheDir, sfxType)
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { it.release() }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            Toast.makeText(this, "Nghe thử lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addSfxEntry() {
        if (clips.isEmpty()) {
            Toast.makeText(this, "Chưa có video nào trong dự án", Toast.LENGTH_SHORT).show()
            return
        }
        if (sfxEntries.size >= 6) {
            Toast.makeText(this, "Tối đa 6 tiếng chèn mỗi video", Toast.LENGTH_SHORT).show()
            return
        }
        sfxEntries.add(SfxEntry(sfxType, sfxPosBar.progress))
        updateSfxListLabel()
        Toast.makeText(this, "Đã chèn ${SfxSynth.NAMES[sfxType]} ở ${sfxPosBar.progress}%", Toast.LENGTH_SHORT).show()
    }

    private fun updateSfxListLabel() {
        if (!::sfxListLabel.isInitialized) return
        if (sfxEntries.isEmpty()) {
            sfxListLabel.text = "Chưa chèn tiếng nào"
        } else {
            sfxListLabel.text = "Đã chèn: " + sfxEntries.joinToString(" · ") { "${SfxSynth.NAMES[it.type]}@${it.posPercent}%" }
        }
    }

    private fun buildVideoEffects(forPreview: Boolean): MutableList<Effect> {
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
        if (!forPreview && speed != 1.0f) effects.add(SpeedChangeEffect(speed))
        if (!forPreview) {
            val text = hookInput.text.toString().trim()
            if (text.isNotEmpty()) {
                val spannable = SpannableString(text)
                val color = if (textYellow) Color.YELLOW else Color.WHITE
                spannable.setSpan(ForegroundColorSpan(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (textStyleMode == 0) {
                    spannable.setSpan(BackgroundColorSpan(Color.argb(150, 0, 0, 0)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (textStyleMode == 1) {
                    spannable.setSpan(UnderlineSpan(), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                spannable.setSpan(StyleSpan(if (textStyleMode == 2) Typeface.BOLD_ITALIC else Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(TypefaceSpan(fontFamily()), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val px = when (textSizeMode) { 0 -> 95; 2 -> 185; else -> 135 }
                spannable.setSpan(AbsoluteSizeSpan(px, false), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val anchorY = if (textOnTop) 0.78f else -0.78f
                val settings = OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0f, anchorY)
                    .setOverlayFrameAnchor(0f, 0f)
                    .build()
                val overlay = TextOverlay.createStaticTextOverlay(spannable, settings)
                effects.add(OverlayEffect(ImmutableList.of(overlay)))
            }
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
            val totalOutUs = if (projectDurationMs() > 0) (projectDurationMs() * 1000L / speed).toLong() else 0L
            list.add(
                GainFadeProcessor(
                    gainVal,
                    if (fadeInCheck.isChecked) 800000L else 0L,
                    if (fadeOutCheck.isChecked) 1200000L else 0L,
                    totalOutUs
                )
            )
        }
        if (echoCheck.isChecked) list.add(EchoProcessor())
        if (bassCheck.isChecked) list.add(ToneProcessor(1))
        if (brightAudioCheck.isChecked) list.add(ToneProcessor(2))
        if (robotCheck.isChecked) list.add(ToneProcessor(3))
        return list
    }

    private fun applyEffectsToPreview() {
        if (videoUri == null) return
        val full = buildVideoEffects(forPreview = true)
        try {
            player.setVideoEffects(full)
            return
        } catch (e: Exception) {
            // fall through to reduced set
        }
        val reduced = full.filter { it !is OverlayEffect && it !is GaussianBlur }
        try {
            player.setVideoEffects(reduced)
            statusLabel.text = "Có hiệu ứng nặng chỉ hiện khi xuất video, các hiệu ứng còn lại đã hiện ở xem trước."
            return
        } catch (e: Exception) {
            // fall through
        }
        try { player.setVideoEffects(emptyList()) } catch (e: Exception) { }
        statusLabel.text = "Xem trước hiệu ứng bị lỗi trên máy này — hiệu ứng vẫn được áp khi xuất video."
    }

    private fun exportVideo() {
        if (clips.isEmpty()) {
            Toast.makeText(this, "Chưa có video nào trong dự án", Toast.LENGTH_SHORT).show()
            return
        }
        saveTrimToClip()
        val outFile = File(cacheDir, "litecut_out.mp4")
        if (outFile.exists()) outFile.delete()

        val hasReplacementAudio = audioUri != null
        val items = clips.map { c ->
            val mb = MediaItem.Builder().setUri(c.uri)
            if (c.durationMs > 0 && (c.trimStart > 0 || (c.trimEnd in 1..c.durationMs && c.trimEnd < c.durationMs))) {
                mb.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.trimStart)
                        .setEndPositionMs(if (c.trimEnd in 1..c.durationMs) c.trimEnd else c.durationMs)
                        .build()
                )
            }
            EditedMediaItem.Builder(mb.build())
                .setEffects(Effects(buildAudioProcessors(), buildVideoEffects(forPreview = false)))
                .setRemoveAudio(muteCheck.isChecked || hasReplacementAudio)
                .build()
        }
        val sequences = mutableListOf<EditedMediaItemSequence>()
        sequences.add(EditedMediaItemSequence(items))

        if (hasReplacementAudio) {
            val total = projectDurationMs()
            val audioItemBuilder = MediaItem.Builder().setUri(audioUri!!)
            if (total > 0) {
                audioItemBuilder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(total).build()
                )
            }
            val audioEdited = EditedMediaItem.Builder(audioItemBuilder.build())
                .setEffects(Effects(buildAudioProcessors(), mutableListOf<Effect>()))
                .build()
            sequences.add(EditedMediaItemSequence(audioEdited))
        }

        val projDur = projectDurationMs()
        for (entry in sfxEntries) {
            val posOutMs = if (projDur > 0) (entry.posPercent / 100.0 * projDur / speed).toLong() else 0L
            val silence = SfxSynth.silenceFile(cacheDir, posOutMs)
            val sfx = SfxSynth.sfxFile(cacheDir, entry.type)
            val silenceItem = EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(silence)).build()).build()
            val sfxItem = EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(sfx)).build()).build()
            sequences.add(EditedMediaItemSequence(silenceItem, sfxItem))
        }

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
        statusLabel.text = "Đang xuất video (${clips.size} clip)…"
        progressBar.progress = 0
        val composition = Composition.Builder(sequences).build()
        t.start(composition, outFile.absolutePath)
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
