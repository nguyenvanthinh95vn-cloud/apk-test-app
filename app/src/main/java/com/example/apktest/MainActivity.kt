package com.example.apktest

import android.content.ContentValues
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
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.SonicAudioProcessor
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

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

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

    private var videoUri: Uri? = null
    private var audioUri: Uri? = null
    private var durationMs: Long = 0
    private var trimStartMs: Long = 0
    private var trimEndMs: Long = 0
    private var speed: Float = 1.0f
    private var rotationDeg: Int = 0
    private var ratioMode: Int = 0 // 0 original, 1 = 9:16, 2 = 1:1, 3 = 16:9
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
            audioLabel.text = "Âm thanh thay thế: đã chọn 1 file (bấm Xuất video để áp dụng)"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun fmt(ms: Long): String {
        val s = (ms / 1000).toInt()
        return String.format("%d:%02d", s / 60, s % 60)
    }
    private fun label(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 16f
        tv.setPadding(0, dp(14), 0, dp(4))
        return tv
    }
    private fun btn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setOnClickListener { onClick() }
        return b
    }
    private fun row(vararg views: View): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            l.addView(v, lp)
        }
        return l
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(10), dp(14), dp(20))

        val title = TextView(this)
        title.text = "LiteCut — edit video nhẹ"
        title.textSize = 20f
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        root.addView(btn("1. Chọn video từ thư viện") {
            pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        })

        playerView = PlayerView(this)
        root.addView(playerView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)))
        playerView.player = player

        seekBar = SeekBar(this)
        root.addView(seekBar)
        timeLabel = TextView(this)
        timeLabel.text = "0:00 / 0:00"
        root.addView(timeLabel)
        root.addView(row(
            btn("▶ Phát") { player.play() },
            btn("⏸ Dừng") { player.pause() }
        ))
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player.seekTo(progress.toLong())
                    timeLabel.text = "${fmt(progress.toLong())} / ${fmt(durationMs)}"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { seeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) { seeking = false }
        })

        root.addView(label("2. Cắt đầu / cuối (kéo 2 thanh)"))
        trimLabel = TextView(this)
        trimLabel.text = "Giữ từ 0:00 đến 0:00"
        root.addView(trimLabel)
        trimStartBar = SeekBar(this)
        trimEndBar = SeekBar(this)
        root.addView(trimStartBar)
        root.addView(trimEndBar)
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

        root.addView(label("3. Tốc độ"))
        root.addView(row(
            btn("0.5x") { setSpeed(0.5f) },
            btn("1x") { setSpeed(1.0f) },
            btn("1.2x") { setSpeed(1.2f) },
            btn("1.5x") { setSpeed(1.5f) },
            btn("2x") { setSpeed(2.0f) }
        ))

        root.addView(label("4. Hình ảnh"))
        flipCheck = CheckBox(this)
        flipCheck.text = "Lật gương (mirror)"
        flipCheck.setOnCheckedChangeListener { _, _ -> applyEffectsToPreview() }
        muteCheck = CheckBox(this)
        muteCheck.text = "Tắt tiếng gốc"
        root.addView(row(flipCheck, muteCheck))
        root.addView(btn("Xoay 90° (đang: 0°)") { v ->
            rotationDeg = (rotationDeg + 90) % 360
            (v as Button).text = "Xoay 90° (đang: ${rotationDeg}°)"
            applyEffectsToPreview()
        })
        root.addView(row(
            btn("Gốc") { setRatio(0) },
            btn("9:16") { setRatio(1) },
            btn("1:1") { setRatio(2) },
            btn("16:9") { setRatio(3) }
        ))

        root.addView(label("5. Chữ trên video (hook)"))
        hookInput = EditText(this)
        hookInput.hint = "Nhập dòng chữ, ví dụ: Xem hết mới hiểu!"
        root.addView(hookInput)
        root.addView(row(
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

        root.addView(label("6. Âm thanh"))
        audioLabel = TextView(this)
        audioLabel.text = "Âm thanh thay thế: chưa chọn"
        root.addView(audioLabel)
        root.addView(row(
            btn("Chọn file nhạc / giọng") { pickAudio.launch("audio/*") },
            btn("Bỏ chọn") { audioUri = null; audioLabel.text = "Âm thanh thay thế: chưa chọn" }
        ))

        root.addView(label("7. Xuất video"))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 100
        progressBar.progress = 0
        root.addView(progressBar)
        statusLabel = TextView(this)
        statusLabel.text = "Chưa xuất. Video xuất ra sẽ lưu vào thư mục Phim/LiteCut trong máy."
        root.addView(statusLabel)
        root.addView(row(
            btn("⬇ XUẤT VIDEO") { exportVideo() },
            btn("Huỷ xuất") { transformer?.cancel() }
        ))

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

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
                        timeLabel.text = "0:00 / ${fmt(d)}"
                    }
                }
            }
        })
        handler.post(object : Runnable {
            override fun run() {
                if (!seeking && player.duration > 0) {
                    seekBar.progress = player.currentPosition.toInt()
                    timeLabel.text = "${fmt(player.currentPosition)} / ${fmt(durationMs)}"
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    private fun loadVideo(uri: Uri) {
        videoUri = uri
        val item = MediaItem.Builder().setUri(uri).build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
        statusLabel.text = "Đã tải video. Chỉnh xong bấm XUẤT VIDEO."
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

    private fun setSpeed(v: Float) {
        speed = v
        player.setPlaybackParameters(PlaybackParameters(v))
        applyEffectsToPreview()
        Toast.makeText(this, "Tốc độ: ${v}x", Toast.LENGTH_SHORT).show()
    }

    private fun setRatio(mode: Int) {
        ratioMode = mode
        applyEffectsToPreview()
        val name = when (mode) { 1 -> "9:16"; 2 -> "1:1"; 3 -> "16:9"; else -> "Gốc" }
        Toast.makeText(this, "Tỉ lệ: $name", Toast.LENGTH_SHORT).show()
    }

    private fun buildVideoEffects(): MutableList<Effect> {
        val effects = mutableListOf<Effect>()
        if (flipCheck.isChecked || rotationDeg != 0) {
            effects.add(
                ScaleAndRotateTransformation.Builder()
                    .setScale(if (flipCheck.isChecked) -1f else 1f, 1f)
                    .setRotationDegrees(rotationDeg.toFloat())
                    .build()
            )
        }
        when (ratioMode) {
            1 -> effects.add(Presentation.createForAspectRatio(9, 16, Presentation.SCALE_TO_FIT_WITH_CROP))
            2 -> effects.add(Presentation.createForAspectRatio(1, 1, Presentation.SCALE_TO_FIT_WITH_CROP))
            3 -> effects.add(Presentation.createForAspectRatio(16, 9, Presentation.SCALE_TO_FIT_WITH_CROP))
        }
        if (speed != 1.0f) {
            effects.add(SpeedChangeEffect(speed))
        }
        val text = hookInput.text.toString().trim()
        if (text.isNotEmpty()) {
            val spannable = SpannableString(text)
            val color = if (textYellow) Color.YELLOW else Color.WHITE
            spannable.setSpan(ForegroundColorSpan(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(BackgroundColorSpan(Color.argb(150, 0, 0, 0)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(AbsoluteSizeSpan(130, false), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val anchorY = if (textOnTop) 0.78f else -0.78f
            val settings = StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, anchorY)
                .setOverlayFrameAnchor(0f, 0f)
                .build()
            val overlay = TextOverlay.createStaticTextOverlay(spannable, settings)
            effects.add(OverlayEffect(ImmutableList.of(overlay)))
        }
        return effects
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

        val audioProcessors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
        if (speed != 1.0f) {
            val sonic = SonicAudioProcessor()
            sonic.setSpeed(speed)
            audioProcessors.add(sonic)
        }
        val effects = Effects(audioProcessors, buildVideoEffects())

        val hasReplacementAudio = audioUri != null
        val editedBuilder = EditedMediaItem.Builder(videoItem)
            .setEffects(effects)
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
            val audioEdited = EditedMediaItem.Builder(audioItemBuilder.build()).build()
            val composition = Composition.Builder()
                .addSequence(EditedMediaItemSequence(editedBuilder.build()))
                .addSequence(EditedMediaItemSequence(audioEdited))
                .build()
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
