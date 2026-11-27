package com.example.apktest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
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
    @Volatile var touchTurboA = false
    @Volatile var touchTurboB = false
    @Volatile var padMask = 0
    @Volatile var motionMask = 0
    @Volatile var padTurboA = false
    @Volatile var padTurboB = false

    private var romKey = "game"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        bar.addView(romBtn)
        bar.addView(saveBtn)
        bar.addView(loadBtn)
        bar.addView(muteButton)
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

    // ---------- Game loop ----------
    private fun gameLoop() {
        while (threadRun) {
            if (paused || !nes.romLoaded) {
                try { Thread.sleep(30) } catch (e: InterruptedException) { }
                continue
            }
            val t0 = System.nanoTime()
            var mask = touchMask or padMask or motionMask
            // Turbo ~12 lan/giay: nua chu ky ~42ms
            val phaseOn = (System.currentTimeMillis() / 42L) % 2L == 0L
            if (touchTurboA || padTurboA) {
                mask = if (phaseOn) mask or BTN_A else mask and BTN_A.inv()
            }
            if (touchTurboB || padTurboB) {
                mask = if (phaseOn) mask or BTN_B else mask and BTN_B.inv()
            }
            nes.pad1.buttons = mask
            nes.runFrame()
            val samples = nes.apu.drainSamples()
            if (!muted && samples.isNotEmpty()) {
                try { audioTrack?.write(samples, 0, samples.size) } catch (e: Exception) { }
            }
            gameView.postInvalidate()
            val usedMs = (System.nanoTime() - t0) / 1_000_000L
            val rest = 16L - usedMs
            if (rest > 0) {
                try { Thread.sleep(rest) } catch (e: InterruptedException) { }
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
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val bit = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> BTN_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> BTN_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> BTN_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> BTN_RIGHT
            KeyEvent.KEYCODE_BUTTON_A -> BTN_A
            KeyEvent.KEYCODE_BUTTON_B -> BTN_B
            KeyEvent.KEYCODE_BUTTON_START -> BTN_START
            KeyEvent.KEYCODE_BUTTON_SELECT -> BTN_SELECT
            else -> 0
        }
        val turbo = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_Y -> 1 // turbo A
            KeyEvent.KEYCODE_BUTTON_X -> 2 // turbo B
            else -> 0
        }
        if (bit == 0 && turbo == 0) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (bit != 0) padMask = padMask or bit
                if (turbo == 1) padTurboA = true
                if (turbo == 2) padTurboB = true
            }
            KeyEvent.ACTION_UP -> {
                if (bit != 0) padMask = padMask and bit.inv()
                if (turbo == 1) padTurboA = false
                if (turbo == 2) padTurboB = false
            }
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
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
        }

        private var d = 1f
        private var dpadCx = 0f
        private var dpadCy = 0f
        private var arm = 0f
        private var armW = 0f
        // key = bit (hoac bit or 0x100 cho turbo) -> [cx, cy, r, loai] loai: 0 thuong, 1 turbo
        private val circles = HashMap<Int, FloatArray>()
        private var selectRect = RectF()
        private var startRect = RectF()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            d = resources.displayMetrics.density
            dpadCx = 108f * d
            dpadCy = h - 108f * d
            arm = 60f * d
            armW = 22f * d
            circles.clear()
            circles[BTN_A] = floatArrayOf(w - 62f * d, h - 92f * d, 33f * d, 0f)
            circles[BTN_B] = floatArrayOf(w - 152f * d, h - 62f * d, 33f * d, 0f)
            circles[BTN_A or 0x100] = floatArrayOf(w - 62f * d, h - 188f * d, 29f * d, 1f)
            circles[BTN_B or 0x100] = floatArrayOf(w - 152f * d, h - 158f * d, 29f * d, 1f)
            selectRect = RectF(w / 2f - 126f * d, h - 42f * d, w / 2f - 14f * d, h - 16f * d)
            startRect = RectF(w / 2f + 14f * d, h - 42f * d, w / 2f + 126f * d, h - 16f * d)
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
        }

        private fun drawControls(canvas: Canvas) {
            // D-pad
            shapePaint.color = Color.argb(70, 255, 255, 255)
            canvas.drawRect(dpadCx - armW, dpadCy - arm, dpadCx + armW, dpadCy + arm, shapePaint)
            canvas.drawRect(dpadCx - arm, dpadCy - armW, dpadCx + arm, dpadCy + armW, shapePaint)
            shapePaint.color = Color.argb(150, 255, 255, 255)
            val dz = arm * 0.55f
            if ((touchMask and BTN_UP) != 0) canvas.drawCircle(dpadCx, dpadCy - dz, armW * 0.85f, shapePaint)
            if ((touchMask and BTN_DOWN) != 0) canvas.drawCircle(dpadCx, dpadCy + dz, armW * 0.85f, shapePaint)
            if ((touchMask and BTN_LEFT) != 0) canvas.drawCircle(dpadCx - dz, dpadCy, armW * 0.85f, shapePaint)
            if ((touchMask and BTN_RIGHT) != 0) canvas.drawCircle(dpadCx + dz, dpadCy, armW * 0.85f, shapePaint)

            // 4 nut hanh dong: A, B, TA (turbo A), TB (turbo B)
            drawButton(canvas, BTN_A, "A", (touchMask and BTN_A) != 0)
            drawButton(canvas, BTN_B, "B", (touchMask and BTN_B) != 0)
            drawButton(canvas, BTN_A or 0x100, "TA", touchTurboA)
            drawButton(canvas, BTN_B or 0x100, "TB", touchTurboB)

            // Select / Start
            shapePaint.color = Color.argb(if ((touchMask and BTN_SELECT) != 0) 170 else 80, 255, 255, 255)
            canvas.drawRoundRect(selectRect, 10f * d, 10f * d, shapePaint)
            shapePaint.color = Color.argb(if ((touchMask and BTN_START) != 0) 170 else 80, 255, 255, 255)
            canvas.drawRoundRect(startRect, 10f * d, 10f * d, shapePaint)
            labelPaint.textSize = 10f * d
            canvas.drawText("SELECT", selectRect.centerX(), selectRect.centerY() + 4f * d, labelPaint)
            canvas.drawText("START", startRect.centerX(), startRect.centerY() + 4f * d, labelPaint)
        }

        private fun drawButton(canvas: Canvas, key: Int, label: String, pressed: Boolean) {
            val c = circles[key] ?: return
            val turbo = c[3] > 0.5f
            shapePaint.color = when {
                pressed -> Color.argb(200, 255, 210, 60)
                turbo -> Color.argb(90, 120, 200, 255)
                else -> Color.argb(90, 255, 90, 90)
            }
            canvas.drawCircle(c[0], c[1], c[2], shapePaint)
            labelPaint.textSize = (if (turbo) 15f else 19f) * d
            canvas.drawText(label, c[0], c[1] + 6f * d, labelPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchMask = 0
                touchTurboA = false
                touchTurboB = false
                invalidate()
                return true
            }
            val upPid = when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                    event.getPointerId(event.actionIndex)
                else -> -1
            }
            var m = 0
            var ta = false
            var tb = false
            for (i in 0 until event.pointerCount) {
                val pid = event.getPointerId(i)
                if (pid == upPid) continue
                val px = event.getX(i)
                val py = event.getY(i)
                // D-pad
                val dx = px - dpadCx
                val dy = py - dpadCy
                val inCross = (abs(dx) <= arm && abs(dy) <= armW) || (abs(dy) <= arm && abs(dx) <= armW)
                if (inCross) {
                    val dead = 9f * d
                    if (dy < -dead) m = m or BTN_UP
                    if (dy > dead) m = m or BTN_DOWN
                    if (dx < -dead) m = m or BTN_LEFT
                    if (dx > dead) m = m or BTN_RIGHT
                }
                // Cac nut tron (A, B, TA, TB)
                for ((key, c) in circles) {
                    val ddx = px - c[0]
                    val ddy = py - c[1]
                    val rr = c[2] + 8f * d
                    if (ddx * ddx + ddy * ddy <= rr * rr) {
                        if (c[3] > 0.5f) {
                            if ((key and BTN_A) != 0) ta = true else tb = true
                        } else {
                            m = m or key
                        }
                    }
                }
                if (selectRect.contains(px, py)) m = m or BTN_SELECT
                if (startRect.contains(px, py)) m = m or BTN_START
            }
            touchMask = m
            touchTurboA = ta
            touchTurboB = tb
            invalidate()
            return true
        }
    }
}
