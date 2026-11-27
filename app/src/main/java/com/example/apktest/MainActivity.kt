package com.example.apktest

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
        const val BTN_A = 0x01
        const val BTN_B = 0x02
        const val BTN_SELECT = 0x04
        const val BTN_START = 0x08
        const val BTN_UP = 0x10
        const val BTN_DOWN = 0x20
        const val BTN_LEFT = 0x40
        const val BTN_RIGHT = 0x80
        private const val REQ_ROM = 1001
        // Cac nut co the keo tha + luu vi tri
        private val CTL_KEYS = listOf("dpad", "a", "b", "sel", "start")
    }

    private val nes = Nes()
    private lateinit var gameView: GameView
    private var audioTrack: AudioTrack? = null
    private var muteButton: Button? = null

    @Volatile private var muted = false
    @Volatile private var paused = false
    @Volatile private var threadRun = false
    private var loopThread: Thread? = null

    // Trang thai dieu khien (OR vao nhau moi khung hinh)
    @Volatile var touchMask = 0
    @Volatile var padMask = 0
    @Volatile var motionMask = 0

    // ----- Cai dat hien thi (luu SharedPreferences) -----
    private lateinit var uiPrefs: SharedPreferences
    @Volatile var uiScale = 1.0f           // 0.6x - 1.6x
    @Volatile var moveMode = false         // che do keo tha nut
    val ctlOffsets = HashMap<String, FloatArray>()  // key -> [dx, dy] px

    private var romKey = "game"

    // ----- Rung haptic khi bam nut cam ung -----
    @Volatile var hapticEnabled = true
    private val vibrator: Vibrator? by lazy {
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // Rung nhe 1 cai ~20ms, dut khoat (chi khi dang bat trong Cai dat hien thi)
    fun buzz() {
        if (!hapticEnabled) return
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(20L)
            }
        } catch (e: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        loadUiPrefs()

        val root = FrameLayout(this)
        gameView = GameView(this)
        root.addView(gameView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(8, 8, 8, 8)
        val romBtn = Button(this).apply { text = "ROM"; setOnClickListener { pickRom() } }
        val saveBtn = Button(this).apply { text = "Lưu nhanh"; setOnClickListener { saveStateQuick() } }
        val loadBtn = Button(this).apply { text = "Tải nhanh"; setOnClickListener { loadStateQuick() } }
        muteButton = Button(this).apply {
            text = "Tắt tiếng"
            setOnClickListener {
                muted = !muted
                text = if (muted) "Bật tiếng" else "Tắt tiếng"
            }
        }
        val settingsBtn = Button(this).apply { text = "Cài đặt"; setOnClickListener { showDisplaySettings() } }
        bar.addView(romBtn)
        bar.addView(saveBtn)
        bar.addView(loadBtn)
        bar.addView(muteButton)
        bar.addView(settingsBtn)
        val barParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        barParams.gravity = Gravity.TOP or Gravity.START
        root.addView(bar, barParams)

        setContentView(root)
        startAudio()
        threadRun = true
        loopThread = Thread { gameLoop() }.apply { isDaemon = true; start() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onResume() {
        super.onResume()
        paused = false
    }

    override fun onPause() {
        super.onPause()
        paused = true
        saveBattery()
    }

    override fun onDestroy() {
        threadRun = false
        try { audioTrack?.stop() } catch (e: Exception) { }
        audioTrack?.release()
        audioTrack = null
        super.onDestroy()
    }

    // ---------- Cai dat hien thi ----------
    private fun loadUiPrefs() {
        uiPrefs = getSharedPreferences("nes_display", Context.MODE_PRIVATE)
        uiScale = uiPrefs.getFloat("scale", 1.0f).coerceIn(0.6f, 1.6f)
        hapticEnabled = uiPrefs.getBoolean("haptic", true)
        for (key in CTL_KEYS) {
            ctlOffsets[key] = floatArrayOf(
                uiPrefs.getFloat("off_${key}_x", 0f),
                uiPrefs.getFloat("off_${key}_y", 0f))
        }
    }

    private fun saveUiPrefs() {
        val e = uiPrefs.edit()
        e.putFloat("scale", uiScale)
        e.putBoolean("haptic", hapticEnabled)
        for (key in CTL_KEYS) {
            val o = ctlOffsets[key] ?: floatArrayOf(0f, 0f)
            e.putFloat("off_${key}_x", o[0])
            e.putFloat("off_${key}_y", o[1])
        }
        e.apply()
    }

    private fun showDisplaySettings() {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scaleLabel = TextView(this).apply {
            text = "Kích cỡ nút: ${"%.1f".format(uiScale)}x"
            textSize = 15f
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = ((uiScale - 0.6f) * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    uiScale = 0.6f + progress / 100f
                    scaleLabel.text = "Kích cỡ nút: ${"%.1f".format(uiScale)}x"
                    gameView.invalidate()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { }
                override fun onStopTrackingTouch(sb: SeekBar?) { saveUiPrefs() }
            })
        }
        val moveChk = CheckBox(this).apply {
            text = "Di chuyển nút (kéo thả các nút trên màn hình tới vị trí tùy ý)"
            textSize = 15f
            isChecked = moveMode
            setOnCheckedChangeListener { _, checked ->
                moveMode = checked
                if (checked) touchMask = 0
                gameView.invalidate()
            }
        }
        val hapticChk = CheckBox(this).apply {
            text = "Rung khi bấm"
            textSize = 15f
            isChecked = hapticEnabled
            setOnCheckedChangeListener { _, checked ->
                hapticEnabled = checked
                saveUiPrefs()
            }
        }
        val resetBtn = Button(this).apply {
            text = "Đặt lại vị trí gốc"
            setOnClickListener {
                for (key in CTL_KEYS) ctlOffsets[key] = floatArrayOf(0f, 0f)
                uiScale = 1.0f
                seek.progress = 40
                scaleLabel.text = "Kích cỡ nút: 1.0x"
                saveUiPrefs()
                gameView.invalidate()
                toast("Đã đặt lại vị trí + kích cỡ gốc")
            }
        }
        layout.addView(scaleLabel)
        layout.addView(seek)
        layout.addView(moveChk)
        layout.addView(hapticChk)
        layout.addView(resetBtn)
        AlertDialog.Builder(this)
            .setTitle("Cài đặt hiển thị")
            .setView(layout)
            .setPositiveButton("Đóng", null)
            .show()
    }

    // ---------- Audio ----------
    @Suppress("DEPRECATION")
    private fun startAudio() {
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val size = if (minBuf > 0) minBuf * 2 else 8192
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC, 44100,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                size, AudioTrack.MODE_STREAM)
            audioTrack?.play()
        } catch (e: Exception) {
            audioTrack = null
        }
    }

    // ---------- Game loop (nhip 60fps chot theo deadline nanoTime) ----------
    private fun gameLoop() {
        val frameNs = 16_666_667L
        var nextDeadline = System.nanoTime()
        while (threadRun) {
            if (paused || !nes.romLoaded) {
                try { Thread.sleep(30) } catch (e: InterruptedException) { }
                nextDeadline = System.nanoTime()
                continue
            }
            // Chot trang thai dieu khien o dau khung hinh
            nes.pad1.buttons = touchMask or padMask or motionMask
            nes.runFrame()
            val samples = nes.apu.drainSamples()
            if (!muted && samples.isNotEmpty()) {
                try { audioTrack?.write(samples, 0, samples.size) } catch (e: Exception) { }
            }
            gameView.postInvalidate()
            nextDeadline += frameNs
            var remaining = nextDeadline - System.nanoTime()
            if (remaining < -frameNs * 4) {
                // Tre qua xa (may bi dung): dong bo lai deadline, tranh chay bu xoắn
                nextDeadline = System.nanoTime()
                remaining = 0
            }
            if (remaining > 0) {
                try {
                    Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                } catch (e: InterruptedException) { }
            }
        }
    }

    // ---------- ROM / luu tru ----------
    private fun pickRom() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_ROM)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ROM || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        try {
            saveBattery()
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Khong doc duoc file")
            romKey = "rom_" + bytes.contentHashCode().toString(16)
            val savFile = File(filesDir, "$romKey.sav")
            val battery = if (savFile.exists()) savFile.readBytes() else null
            val err = nes.loadRom(bytes, battery)
            if (err == null) {
                toast("Đã nạp game (${bytes.size / 1024} KB) — mapper ${nes.cart?.mapperId}")
            } else {
                toast(err)
            }
        } catch (e: Exception) {
            toast("Lỗi đọc ROM: ${e.message}")
        }
    }

    private fun saveBattery() {
        try {
            val data = nes.batteryData() ?: return
            File(filesDir, "$romKey.sav").writeBytes(data)
        } catch (e: Exception) { }
    }

    private fun saveStateQuick() {
        if (!nes.romLoaded) { toast("Chưa nạp game"); return }
        try {
            DataOutputStream(FileOutputStream(File(filesDir, "$romKey.state"))).use { out ->
                nes.saveState(out)
            }
            toast("Đã lưu nhanh")
        } catch (e: Exception) {
            toast("Lưu lỗi: ${e.message}")
        }
    }

    private fun loadStateQuick() {
        if (!nes.romLoaded) { toast("Chưa nạp game"); return }
        val f = File(filesDir, "$romKey.state")
        if (!f.exists()) { toast("Chưa có bản lưu nhanh cho game này"); return }
        try {
            DataInputStream(FileInputStream(f)).use { inp -> nes.loadState(inp) }
            toast("Đã tải bản lưu")
        } catch (e: Exception) {
            toast("Tải lỗi: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- Tay cam roi: phim cung ----------
    // A = nhay, B = ban; Y = A, X = B (nut thuong, khong turbo)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val bit = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> BTN_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> BTN_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> BTN_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> BTN_RIGHT
            KeyEvent.KEYCODE_BUTTON_A -> BTN_A
            KeyEvent.KEYCODE_BUTTON_Y -> BTN_A
            KeyEvent.KEYCODE_BUTTON_B -> BTN_B
            KeyEvent.KEYCODE_BUTTON_X -> BTN_B
            KeyEvent.KEYCODE_BUTTON_START -> BTN_START
            KeyEvent.KEYCODE_BUTTON_SELECT -> BTN_SELECT
            else -> 0
        }
        if (bit == 0) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> padMask = padMask or bit
            KeyEvent.ACTION_UP -> padMask = padMask and bit.inv()
        }
        return true
    }

    // ---------- Tay cam roi: can analog + hat ----------
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE) {
            var m = 0
            val ax = axisValue(event, MotionEvent.AXIS_X)
            val ay = axisValue(event, MotionEvent.AXIS_Y)
            val hx = axisValue(event, MotionEvent.AXIS_HAT_X)
            val hy = axisValue(event, MotionEvent.AXIS_HAT_Y)
            if (ax < -0.4f || hx < -0.5f) m = m or BTN_LEFT
            if (ax > 0.4f || hx > 0.5f) m = m or BTN_RIGHT
            if (ay < -0.4f || hy < -0.5f) m = m or BTN_UP
            if (ay > 0.4f || hy > 0.5f) m = m or BTN_DOWN
            motionMask = m
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun axisValue(event: MotionEvent, axis: Int): Float {
        val device = event.device ?: return event.getAxisValue(axis)
        val range = device.getMotionRange(axis, event.source) ?: return event.getAxisValue(axis)
        val v = event.getAxisValue(axis)
        return if (abs(v) < range.flat) 0f else v
    }

    // ---------- Man hinh game + tay cam cam ung ----------
    inner class GameView(ctx: Context) : View(ctx) {
        private val bmp = Bitmap.createBitmap(256, 240, Bitmap.Config.ARGB_8888)
        private val fbPaint = Paint().apply { isFilterBitmap = false }
        private val srcRect = Rect(0, 0, 256, 240)
        private val dstRect = Rect()
        private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(230, 255, 210, 60)
            strokeWidth = 2f
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }
        // Paint cho hieu ung nut noi 3D (vien sang / vien toi)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val crossPath = Path()
        private val tmpRect = RectF()

        private var d = 1f

        // Keo tha trong che do di chuyen nut
        private var dragKey: String? = null
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var dragOrigX = 0f
        private var dragOrigY = 0f

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            d = resources.displayMetrics.density
            strokePaint.strokeWidth = 2f * d
        }

        // ----- Bo cuc: vi tri goc + offset nguoi dung, kich co theo uiScale -----
        private fun off(key: String): FloatArray = ctlOffsets[key] ?: floatArrayOf(0f, 0f)
        private fun dpadCx(): Float = 108f * d + off("dpad")[0]
        private fun dpadCy(): Float = height - 108f * d + off("dpad")[1]
        private fun arm(): Float = 60f * d * uiScale
        private fun armW(): Float = 22f * d * uiScale
        private fun aCx(): Float = width - 62f * d + off("a")[0]
        private fun aCy(): Float = height - 92f * d + off("a")[1]
        private fun bCx(): Float = width - 152f * d + off("b")[0]
        private fun bCy(): Float = height - 62f * d + off("b")[1]
        private fun btnR(): Float = 33f * d * uiScale
        private fun selRect(): RectF {
            val cx = width / 2f - 70f * d + off("sel")[0]
            val cy = height - 29f * d + off("sel")[1]
            val w = 112f * d * uiScale
            val h = 26f * d * uiScale
            return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        }
        private fun startRect(): RectF {
            val cx = width / 2f + 70f * d + off("start")[0]
            val cy = height - 29f * d + off("start")[1]
            val w = 112f * d * uiScale
            val h = 26f * d * uiScale
            return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            if (nes.romLoaded) {
                bmp.setPixels(nes.ppu.frameBuffer, 0, 256, 0, 0, 256, 240)
                val scale = minOf(width / 256f, height / 240f)
                val w = (256 * scale).toInt()
                val h = (240 * scale).toInt()
                dstRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                canvas.drawBitmap(bmp, srcRect, dstRect, fbPaint)
            } else {
                labelPaint.textSize = 15f * d
                canvas.drawText("Bấm nút ROM ở góc trên để chọn game .nes", width / 2f, height / 2f, labelPaint)
            }
            drawControls(canvas)
            if (moveMode) drawMoveHints(canvas)
        }

        private fun drawControls(canvas: Canvas) {
            drawDpad(canvas)
            // Chi 2 nut hanh dong: A (nhay), B (ban)
            drawButton(canvas, aCx(), aCy(), "A", (touchMask and BTN_A) != 0)
            drawButton(canvas, bCx(), bCy(), "B", (touchMask and BTN_B) != 0)
            // Select / Start
            drawPill(canvas, selRect(), "SELECT", (touchMask and BTN_SELECT) != 0)
            drawPill(canvas, startRect(), "START", (touchMask and BTN_START) != 0)
        }

        // Duong bao hinh chu thap (+) cua D-pad kieu NES co dien
        private fun buildCrossPath(cx: Float, cy: Float, a: Float, aw: Float) {
            crossPath.reset()
            crossPath.moveTo(cx - aw, cy - a)
            crossPath.lineTo(cx + aw, cy - a)
            crossPath.lineTo(cx + aw, cy - aw)
            crossPath.lineTo(cx + a, cy - aw)
            crossPath.lineTo(cx + a, cy + aw)
            crossPath.lineTo(cx + aw, cy + aw)
            crossPath.lineTo(cx + aw, cy + a)
            crossPath.lineTo(cx - aw, cy + a)
            crossPath.lineTo(cx - aw, cy + aw)
            crossPath.lineTo(cx - a, cy + aw)
            crossPath.lineTo(cx - a, cy - aw)
            crossPath.lineTo(cx - aw, cy - aw)
            crossPath.close()
        }

        // D-pad thap tu noi 3D: vien sang tren/trai, vien toi + bong do duoi/phai;
        // canh dang cham thi lun xuong theo thoi gian thuc. Khong con num tron.
        private fun drawDpad(canvas: Canvas) {
            val cx = dpadCx(); val cy = dpadCy()
            val a = arm(); val aw = armW()
            rimPaint.strokeWidth = 2.2f * d
            // Bong do
            buildCrossPath(cx + 2.2f * d, cy + 3f * d, a, aw)
            shapePaint.color = Color.argb(110, 0, 0, 0)
            canvas.drawPath(crossPath, shapePaint)
            // Vien toi (duoi/phai) roi vien sang (tren/trai): phan vien ngoai duong bao se lo ra sau khi to than
            buildCrossPath(cx + 1.3f * d, cy + 1.3f * d, a, aw)
            rimPaint.color = Color.argb(200, 16, 16, 20)
            canvas.drawPath(crossPath, rimPaint)
            buildCrossPath(cx - 1.3f * d, cy - 1.3f * d, a, aw)
            rimPaint.color = Color.argb(215, 208, 208, 218)
            canvas.drawPath(crossPath, rimPaint)
            // Than thap tu
            buildCrossPath(cx, cy, a, aw)
            shapePaint.color = Color.argb(228, 66, 66, 72)
            canvas.drawPath(crossPath, shapePaint)
            // O vuong tam hoi toi
            val cs = aw * 0.92f
            shapePaint.color = Color.argb(90, 0, 0, 0)
            canvas.drawRect(cx - cs, cy - cs, cx + cs, cy + cs, shapePaint)
            // Canh dang lun (theo dung vung dang cham)
            val mask = touchMask
            if ((mask and BTN_UP) != 0) drawArmPressed(canvas, BTN_UP, cx, cy, a, aw)
            if ((mask and BTN_DOWN) != 0) drawArmPressed(canvas, BTN_DOWN, cx, cy, a, aw)
            if ((mask and BTN_LEFT) != 0) drawArmPressed(canvas, BTN_LEFT, cx, cy, a, aw)
            if ((mask and BTN_RIGHT) != 0) drawArmPressed(canvas, BTN_RIGHT, cx, cy, a, aw)
            // Mui ten 4 huong
            labelPaint.color = Color.argb(235, 238, 238, 240)
            labelPaint.textSize = 12f * d * uiScale
            val mid = (a + aw) / 2f
            val base = labelPaint.textSize * 0.36f
            val sh = 1.6f * d
            canvas.drawText("▲", cx, cy - mid + base + if ((mask and BTN_UP) != 0) sh else 0f, labelPaint)
            canvas.drawText("▼", cx, cy + mid + base + if ((mask and BTN_DOWN) != 0) sh else 0f, labelPaint)
            canvas.drawText("◀", cx - mid, cy + base + if ((mask and BTN_LEFT) != 0) sh else 0f, labelPaint)
            canvas.drawText("▶", cx + mid, cy + base + if ((mask and BTN_RIGHT) != 0) sh else 0f, labelPaint)
            labelPaint.color = Color.WHITE
        }

        // 1 canh thap tu dang lun: nen toi hon dich xuong 1.6dp, bong trong o canh
        // ngoai (dau canh), vet sang mo o canh sat tam — cam giac nut that bi an xuong
        private fun drawArmPressed(canvas: Canvas, dir: Int, cx: Float, cy: Float, a: Float, aw: Float) {
            val sh = 1.6f * d
            val strip = 3f * d
            shapePaint.color = Color.argb(235, 40, 40, 46)
            when (dir) {
                BTN_UP -> canvas.drawRect(cx - aw, cy - a + sh, cx + aw, cy - aw + sh, shapePaint)
                BTN_DOWN -> canvas.drawRect(cx - aw, cy + aw + sh, cx + aw, cy + a + sh, shapePaint)
                BTN_LEFT -> canvas.drawRect(cx - a, cy - aw + sh, cx - aw, cy + aw + sh, shapePaint)
                BTN_RIGHT -> canvas.drawRect(cx + aw, cy - aw + sh, cx + a, cy + aw + sh, shapePaint)
            }
            shapePaint.color = Color.argb(130, 0, 0, 0)
            when (dir) {
                BTN_UP -> canvas.drawRect(cx - aw, cy - a + sh, cx + aw, cy - a + sh + strip, shapePaint)
                BTN_DOWN -> canvas.drawRect(cx - aw, cy + a + sh - strip, cx + aw, cy + a + sh, shapePaint)
                BTN_LEFT -> canvas.drawRect(cx - a, cy - aw + sh, cx - a + strip, cy + aw + sh, shapePaint)
                BTN_RIGHT -> canvas.drawRect(cx + a - strip, cy - aw + sh, cx + a, cy + aw + sh, shapePaint)
            }
            shapePaint.color = Color.argb(55, 255, 255, 255)
            when (dir) {
                BTN_UP -> canvas.drawRect(cx - aw, cy - aw + sh - strip, cx + aw, cy - aw + sh, shapePaint)
                BTN_DOWN -> canvas.drawRect(cx - aw, cy + aw + sh, cx + aw, cy + aw + sh + strip, shapePaint)
                BTN_LEFT -> canvas.drawRect(cx - aw - strip, cy - aw + sh, cx - aw, cy + aw + sh, shapePaint)
                BTN_RIGHT -> canvas.drawRect(cx + aw, cy - aw + sh, cx + aw + strip, cy + aw + sh, shapePaint)
            }
        }

        // Nut tron A/B noi 3D nhu nut that; dang bam thi lun xuong + toi hon + dao bong
        private fun drawButton(canvas: Canvas, cx: Float, cy: Float, label: String, pressed: Boolean) {
            val r = btnR()
            val sh = if (pressed) 2f * d else 0f
            rimPaint.strokeWidth = 2.2f * d
            if (!pressed) {
                shapePaint.color = Color.argb(110, 0, 0, 0)
                canvas.drawCircle(cx + 2.2f * d, cy + 3f * d, r, shapePaint)
                rimPaint.color = Color.argb(200, 16, 16, 20)
                canvas.drawCircle(cx + 1.2f * d, cy + 1.2f * d, r, rimPaint)
                rimPaint.color = Color.argb(215, 208, 208, 218)
                canvas.drawCircle(cx - 1.2f * d, cy - 1.2f * d, r, rimPaint)
            } else {
                rimPaint.color = Color.argb(170, 10, 10, 14)
                canvas.drawCircle(cx - 1.2f * d, cy - 1.2f * d + sh, r, rimPaint)
                rimPaint.color = Color.argb(90, 255, 255, 255)
                canvas.drawCircle(cx + 1.2f * d, cy + 1.2f * d + sh, r, rimPaint)
            }
            shapePaint.color = if (pressed) Color.argb(230, 126, 26, 38) else Color.argb(222, 172, 40, 54)
            canvas.drawCircle(cx, cy + sh, r, shapePaint)
            labelPaint.color = Color.WHITE
            labelPaint.textSize = 19f * d * uiScale
            canvas.drawText(label, cx, cy + sh + 6f * d * uiScale, labelPaint)
        }

        // Nut SELECT/START dang vien thuoc, noi 3D, lun khi bam
        private fun drawPill(canvas: Canvas, rect: RectF, label: String, pressed: Boolean) {
            val rad = 10f * d
            val sh = if (pressed) 1.6f * d else 0f
            rimPaint.strokeWidth = 2f * d
            if (!pressed) {
                tmpRect.set(rect); tmpRect.offset(2f * d, 2.6f * d)
                shapePaint.color = Color.argb(110, 0, 0, 0)
                canvas.drawRoundRect(tmpRect, rad, rad, shapePaint)
                tmpRect.set(rect); tmpRect.offset(1.1f * d, 1.1f * d)
                rimPaint.color = Color.argb(200, 16, 16, 20)
                canvas.drawRoundRect(tmpRect, rad, rad, rimPaint)
                tmpRect.set(rect); tmpRect.offset(-1.1f * d, -1.1f * d)
                rimPaint.color = Color.argb(215, 208, 208, 218)
                canvas.drawRoundRect(tmpRect, rad, rad, rimPaint)
            } else {
                tmpRect.set(rect); tmpRect.offset(-1.1f * d, -1.1f * d + sh)
                rimPaint.color = Color.argb(170, 10, 10, 14)
                canvas.drawRoundRect(tmpRect, rad, rad, rimPaint)
                tmpRect.set(rect); tmpRect.offset(1.1f * d, 1.1f * d + sh)
                rimPaint.color = Color.argb(90, 255, 255, 255)
                canvas.drawRoundRect(tmpRect, rad, rad, rimPaint)
            }
            tmpRect.set(rect); tmpRect.offset(0f, sh)
            shapePaint.color = if (pressed) Color.argb(232, 56, 56, 62) else Color.argb(222, 96, 96, 102)
            canvas.drawRoundRect(tmpRect, rad, rad, shapePaint)
            labelPaint.color = Color.WHITE
            labelPaint.textSize = 10f * d * uiScale
            canvas.drawText(label, rect.centerX(), rect.centerY() + sh + 4f * d, labelPaint)
        }

        private fun drawMoveHints(canvas: Canvas) {
            val cx = dpadCx(); val cy = dpadCy(); val a = arm()
            canvas.drawRect(cx - a, cy - a, cx + a, cy + a, strokePaint)
            canvas.drawCircle(aCx(), aCy(), btnR(), strokePaint)
            canvas.drawCircle(bCx(), bCy(), btnR(), strokePaint)
            canvas.drawRoundRect(selRect(), 10f * d, 10f * d, strokePaint)
            canvas.drawRoundRect(startRect(), 10f * d, 10f * d, strokePaint)
            labelPaint.textSize = 13f * d
            canvas.drawText("Đang di chuyển nút: kéo thả từng nút tới vị trí mong muốn",
                width / 2f, 30f * d, labelPaint)
        }

        // D-pad co dien: hop bao thap tu chia 4 vung boi 2 duong cheo qua tam,
        // moi diem cham = dung 1 huong doc lap (khong cheo). Vung cham noi +10%
        // ngoai hinh ve cho de truot; tam chet ban kinh ~12% canh.
        private fun dpadDirection(px: Float, py: Float): Int {
            val dx = px - dpadCx()
            val dy = py - dpadCy()
            val a = arm()
            val lim = a * 1.1f
            if (abs(dx) > lim || abs(dy) > lim) return 0
            val dead = a * 0.24f
            if (dx * dx + dy * dy < dead * dead) return 0
            return if (abs(dx) > abs(dy)) {
                if (dx < 0) BTN_LEFT else BTN_RIGHT
            } else {
                if (dy < 0) BTN_UP else BTN_DOWN
            }
        }

        private fun hitControl(px: Float, py: Float): String? {
            val m = 10f * d
            var dx = px - aCx(); var dy = py - aCy()
            if (dx * dx + dy * dy <= (btnR() + m) * (btnR() + m)) return "a"
            dx = px - bCx(); dy = py - bCy()
            if (dx * dx + dy * dy <= (btnR() + m) * (btnR() + m)) return "b"
            if (selRect().contains(px, py)) return "sel"
            if (startRect().contains(px, py)) return "start"
            if (abs(px - dpadCx()) <= arm() && abs(py - dpadCy()) <= arm()) return "dpad"
            return null
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (moveMode) return onTouchMoveMode(event)
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchMask = 0
                invalidate()
                return true
            }
            val upPid = when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                    event.getPointerId(event.actionIndex)
                else -> -1
            }
            // Cap nhat ngay trong su kien cham: quet toan bo pointer dang cham
            var m = 0
            val sr = selRect(); val tr = startRect()
            val rr = btnR() + 8f * d
            for (i in 0 until event.pointerCount) {
                val pid = event.getPointerId(i)
                if (pid == upPid) continue
                val px = event.getX(i)
                val py = event.getY(i)
                m = m or dpadDirection(px, py)
                var dx = px - aCx(); var dy = py - aCy()
                if (dx * dx + dy * dy <= rr * rr) m = m or BTN_A
                dx = px - bCx(); dy = py - bCy()
                if (dx * dx + dy * dy <= rr * rr) m = m or BTN_B
                if (sr.contains(px, py)) m = m or BTN_SELECT
                if (tr.contains(px, py)) m = m or BTN_START
            }
            // Rung haptic: nut hanh dong rung khi vua nhan (0 -> 1);
            // D-pad rung khi bat dau huong moi hoac truot sang huong khac,
            // giu yen 1 huong thi khong rung lai
            val prev = touchMask
            val actionBits = BTN_A or BTN_B or BTN_SELECT or BTN_START
            if (((m and actionBits) and prev.inv()) != 0) buzz()
            val dirBits = BTN_UP or BTN_DOWN or BTN_LEFT or BTN_RIGHT
            val newDir = m and dirBits
            if (newDir != 0 && newDir != (prev and dirBits)) buzz()
            touchMask = m
            invalidate()
            return true
        }

        private fun onTouchMoveMode(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragKey = hitControl(event.x, event.y)
                    if (dragKey != null) {
                        dragStartX = event.x
                        dragStartY = event.y
                        val o = off(dragKey!!)
                        dragOrigX = o[0]
                        dragOrigY = o[1]
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val key = dragKey
                    if (key != null) {
                        ctlOffsets[key] = floatArrayOf(
                            dragOrigX + (event.x - dragStartX),
                            dragOrigY + (event.y - dragStartY))
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragKey != null) {
                        dragKey = null
                        saveUiPrefs()
                    }
                }
            }
            return true
        }
    }
}
