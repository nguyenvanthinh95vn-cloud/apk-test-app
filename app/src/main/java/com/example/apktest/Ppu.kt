package com.example.apktest

import java.io.DataInputStream
import java.io.DataOutputStream

class Ppu(private val nes: Nes) {
    val frameBuffer = IntArray(256 * 240)
    val vram = ByteArray(4096)
    val palette = ByteArray(32)
    val oam = ByteArray(256)

    var ctrl = 0
        private set
    var mask = 0
        private set
    private var status = 0
    var oamAddr = 0
        private set
    private var v = 0
    private var t = 0
    private var fineX = 0
    private var w = 0
    private var dataBuffer = 0

    var scanline = 0
        private set
    var cycle = 0
        private set
    var frameDone = false

    companion object {
        val PALETTE = intArrayOf(
            0xFF666666.toInt(), 0xFF002A88.toInt(), 0xFF1412A7.toInt(), 0xFF3B00A4.toInt(),
            0xFF5C007E.toInt(), 0xFF6E0040.toInt(), 0xFF6C0600.toInt(), 0xFF561D00.toInt(),
            0xFF333500.toInt(), 0xFF0B4800.toInt(), 0xFF005200.toInt(), 0xFF004F08.toInt(),
            0xFF00404D.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFFADADAD.toInt(), 0xFF155FD9.toInt(), 0xFF4240FF.toInt(), 0xFF7527FE.toInt(),
            0xFFA01ACC.toInt(), 0xFFB71E7B.toInt(), 0xFFB53120.toInt(), 0xFF994E00.toInt(),
            0xFF6B6D00.toInt(), 0xFF388700.toInt(), 0xFF0C9300.toInt(), 0xFF008F32.toInt(),
            0xFF007C8D.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFFFFFEFF.toInt(), 0xFF64B0FF.toInt(), 0xFF9290FF.toInt(), 0xFFC676FF.toInt(),
            0xFFF36AFF.toInt(), 0xFFFE6ECC.toInt(), 0xFFFE8170.toInt(), 0xFFEA9E22.toInt(),
            0xFFBCBE00.toInt(), 0xFF88D800.toInt(), 0xFF5CE430.toInt(), 0xFF45E082.toInt(),
            0xFF48CDDE.toInt(), 0xFF4F4F4F.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFFFFFEFF.toInt(), 0xFFC0DFFF.toInt(), 0xFFD3D2FF.toInt(), 0xFFE8C8FF.toInt(),
            0xFFFBC2FF.toInt(), 0xFFFEC4EA.toInt(), 0xFFFECCC5.toInt(), 0xFFF7D8A5.toInt(),
            0xFFE4E594.toInt(), 0xFFCFEF96.toInt(), 0xFFBDF4AB.toInt(), 0xFFB3F3CC.toInt(),
            0xFFB5EBF2.toInt(), 0xFFB8B8B8.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt()
        )
    }

    fun reset() {
        ctrl = 0
        mask = 0
        status = 0
        oamAddr = 0
        v = 0
        t = 0
        fineX = 0
        w = 0
        dataBuffer = 0
        scanline = 0
        cycle = 0
        frameDone = false
        vram.fill(0)
        palette.fill(0)
        oam.fill(0)
        frameBuffer.fill(0xFF000000.toInt())
    }

    private fun renderingEnabled(): Boolean = (mask and 0x18) != 0

    // ---- PPU memory space ----
    private fun ntIndex(addr: Int): Int {
        val a = addr and 0x0FFF
        val nt = (a shr 10) and 0x03
        val off = a and 0x3FF
        val cart = nes.cart ?: return a
        return when (cart.mirrorMode) {
            0 -> (nt shr 1) * 0x400 + off        // horizontal
            1 -> (nt and 0x01) * 0x400 + off     // vertical
            2 -> off                             // single screen 0
            3 -> 0x400 + off                     // single screen 1
            else -> a                            // four screen
        }
    }

    private fun palRead(idx: Int): Int {
        var i = idx and 0x1F
        if (i >= 16 && (i and 0x03) == 0) i -= 16
        return palette[i].toInt() and 0x3F
    }

    private fun palWrite(idx: Int, value: Int) {
        var i = idx and 0x1F
        if (i >= 16 && (i and 0x03) == 0) i -= 16
        palette[i] = (value and 0x3F).toByte()
    }

    private fun memRead(addr: Int): Int {
        val a = addr and 0x3FFF
        return when {
            a < 0x2000 -> nes.mapper?.ppuRead(a) ?: 0
            a < 0x3F00 -> vram[ntIndex(a)].toInt() and 0xFF
            else -> palRead(a)
        }
    }

    private fun memWrite(addr: Int, value: Int) {
        val a = addr and 0x3FFF
        when {
            a < 0x2000 -> nes.mapper?.ppuWrite(a, value)
            a < 0x3F00 -> vram[ntIndex(a)] = value.toByte()
            else -> palWrite(a, value)
        }
    }

    // ---- CPU-facing registers ----
    fun readReg(addr: Int): Int = when (addr) {
        0x2002 -> {
            val r = status
            status = status and 0x7F
            w = 0
            r
        }
        0x2004 -> oam[oamAddr].toInt() and 0xFF
        0x2007 -> {
            val a = v and 0x3FFF
            val result: Int
            if (a >= 0x3F00) {
                result = palRead(a)
                dataBuffer = memRead(a - 0x1000)
            } else {
                result = dataBuffer
                dataBuffer = memRead(a)
            }
            v = (v + if ((ctrl and 0x04) != 0) 32 else 1) and 0x7FFF
            result
        }
        else -> 0
    }

    fun writeReg(addr: Int, value: Int) {
        when (addr) {
            0x2000 -> {
                val wasOff = (ctrl and 0x80) == 0
                ctrl = value
                t = (t and 0x73FF) or ((value and 0x03) shl 10)
                if (wasOff && (ctrl and 0x80) != 0 && (status and 0x80) != 0) {
                    nes.cpu.nmiPending = true
                }
            }
            0x2001 -> mask = value
            0x2003 -> oamAddr = value and 0xFF
            0x2004 -> {
                oam[oamAddr] = value.toByte()
                oamAddr = (oamAddr + 1) and 0xFF
            }
            0x2005 -> {
                if (w == 0) {
                    t = (t and 0x7FE0) or (value shr 3)
                    fineX = value and 0x07
                    w = 1
                } else {
                    t = (t and 0x0C1F) or ((value and 0x07) shl 12) or ((value and 0xF8) shl 2)
                    w = 0
                }
            }
            0x2006 -> {
                if (w == 0) {
                    t = (t and 0x00FF) or ((value and 0x3F) shl 8)
                    w = 1
                } else {
                    t = (t and 0x7F00) or value
                    v = t
                    w = 0
                }
            }
            0x2007 -> {
                memWrite(v and 0x3FFF, value)
                v = (v + if ((ctrl and 0x04) != 0) 32 else 1) and 0x7FFF
            }
        }
    }

    // ---- Timing ----
    fun tick() {
        if (scanline in 0..239) {
            if (cycle == 256) renderScanline(scanline)
            if (cycle == 257 && renderingEnabled()) {
                incrementY()
                v = (v and 0x7BE0) or (t and 0x041F)
            }
            if (cycle == 260 && renderingEnabled()) {
                nes.mapper?.scanline()
            }
        }
        if (scanline == 241 && cycle == 1) {
            status = status or 0x80
            if ((ctrl and 0x80) != 0) nes.cpu.nmiPending = true
        }
        if (scanline == 261) {
            if (cycle == 1) status = 0
            if (cycle in 280..304 && renderingEnabled()) {
                v = (v and 0x041F) or (t and 0x7BE0)
            }
        }
        cycle++
        if (cycle > 340) {
            cycle = 0
            scanline++
            if (scanline > 261) {
                scanline = 0
                frameDone = true
            }
        }
    }

    private fun incrementY() {
        if ((v and 0x7000) != 0x7000) {
            v += 0x1000
        } else {
            v = v and 0x0FFF
            var cy = (v shr 5) and 0x1F
            if (cy == 29) {
                cy = 0
                v = v xor 0x0800
            } else if (cy == 31) {
                cy = 0
            } else {
                cy++
            }
            v = (v and 0x7C1F) or (cy shl 5)
        }
    }

    // ---- Rendering ----
    private fun renderScanline(sl: Int) {
        val backdrop = PALETTE[palRead(0)]
        val showBg = (mask and 0x08) != 0
        val showSpr = (mask and 0x10) != 0
        val base = sl * 256
        if (!showBg && !showSpr) {
            for (px in 0 until 256) frameBuffer[base + px] = backdrop
            return
        }
        val bgOpaque = BooleanArray(256)
        val bgColor = IntArray(256)
        // Background
        if (showBg) {
            val bgTable = if ((ctrl and 0x10) != 0) 0x1000 else 0x0000
            val coarseX = v and 0x1F
            val coarseY = (v shr 5) and 0x1F
            val fineY = (v shr 12) and 0x07
            val baseNtX = (v shr 10) and 0x01
            val baseNtY = (v shr 11) and 0x01
            val totalY0 = coarseY * 8 + fineY
            for (px in 0 until 256) {
                if (px < 8 && (mask and 0x02) == 0) {
                    bgColor[px] = backdrop
                    continue
                }
                val totalX = coarseX * 8 + fineX + px
                val ntX = baseNtX xor ((totalX shr 8) and 0x01)
                val tileX = (totalX shr 3) and 0x1F
                val bitX = 7 - (totalX and 0x07)
                var totalY = totalY0
                var ntY = baseNtY
                if (totalY >= 240) {
                    totalY -= 240
                    ntY = ntY xor 1
                }
                val tileY = (totalY shr 3) and 0x1F
                val rowY = totalY and 0x07
                val nt = ntY * 2 + ntX
                val tileIndex = vram[ntIndex(0x2000 + nt * 0x400 + tileY * 32 + tileX)].toInt() and 0xFF
                val pAddr = bgTable + tileIndex * 16 + rowY
                val lo = nes.mapper?.ppuRead(pAddr) ?: 0
                val hi = nes.mapper?.ppuRead(pAddr + 8) ?: 0
                val c2 = (((hi shr bitX) and 0x01) shl 1) or ((lo shr bitX) and 0x01)
                if (c2 == 0) {
                    bgColor[px] = backdrop
                } else {
                    val attr = vram[ntIndex(0x2000 + nt * 0x400 + 0x3C0 + (tileY shr 2) * 8 + (tileX shr 2))].toInt() and 0xFF
                    val shift = ((tileY and 0x02) shl 1) or (tileX and 0x02)
                    val palHigh = (attr shr shift) and 0x03
                    bgColor[px] = PALETTE[palRead(palHigh * 4 + c2)]
                    bgOpaque[px] = true
                }
            }
        } else {
            for (px in 0 until 256) bgColor[px] = backdrop
        }
        for (px in 0 until 256) frameBuffer[base + px] = bgColor[px]

        // Sprites
        if (showSpr) {
            val sprHeight = if ((ctrl and 0x20) != 0) 16 else 8
            val selected = IntArray(8)
            var selCount = 0
            var overflow = false
            for (i in 0 until 64) {
                val sy = oam[i * 4].toInt() and 0xFF
                val row = sl - sy - 1
                if (row in 0 until sprHeight) {
                    if (selCount < 8) {
                        selected[selCount] = i
                        selCount++
                    } else {
                        overflow = true
                        break
                    }
                }
            }
            if (overflow) status = status or 0x20
            val sprPixel = IntArray(256) { -1 }
            val sprBehind = BooleanArray(256)
            for (s in selCount - 1 downTo 0) {
                val i = selected[s]
                val sy = oam[i * 4].toInt() and 0xFF
                var tile = oam[i * 4 + 1].toInt() and 0xFF
                val attr = oam[i * 4 + 2].toInt() and 0xFF
                val sx = oam[i * 4 + 3].toInt() and 0xFF
                var row = sl - sy - 1
                if ((attr and 0x80) != 0) row = sprHeight - 1 - row
                val pAddr: Int
                if (sprHeight == 16) {
                    val table = (tile and 0x01) * 0x1000
                    var tt = tile and 0xFE
                    var rr = row
                    if (rr >= 8) { tt++; rr -= 8 }
                    pAddr = table + tt * 16 + rr
                } else {
                    val table = if ((ctrl and 0x08) != 0) 0x1000 else 0x0000
                    pAddr = table + tile * 16 + row
                }
                val lo = nes.mapper?.ppuRead(pAddr) ?: 0
                val hi = nes.mapper?.ppuRead(pAddr + 8) ?: 0
                val flipH = (attr and 0x40) != 0
                for (col in 0 until 8) {
                    val px = sx + col
                    if (px > 255) break
                    if (px < 8 && (mask and 0x04) == 0) continue
                    val bitX = if (flipH) col else 7 - col
                    val c2 = (((hi shr bitX) and 0x01) shl 1) or ((lo shr bitX) and 0x01)
                    if (c2 == 0) continue
                    if (i == 0 && bgOpaque[px] && px < 255 &&
                        !((px < 8) && ((mask and 0x02) == 0 || (mask and 0x04) == 0))) {
                        status = status or 0x40
                    }
                    sprPixel[px] = PALETTE[palRead(0x10 + (attr and 0x03) * 4 + c2)]
                    sprBehind[px] = (attr and 0x20) != 0
                }
            }
            for (px in 0 until 256) {
                val sc = sprPixel[px]
                if (sc != -1 && (!sprBehind[px] || !bgOpaque[px])) {
                    frameBuffer[base + px] = sc
                }
            }
        }
    }

    fun saveState(out: DataOutputStream) {
        out.write(vram)
        out.write(palette)
        out.write(oam)
        out.writeInt(ctrl); out.writeInt(mask); out.writeInt(status); out.writeInt(oamAddr)
        out.writeInt(v); out.writeInt(t); out.writeInt(fineX); out.writeInt(w)
        out.writeInt(dataBuffer); out.writeInt(scanline); out.writeInt(cycle)
    }

    fun loadState(inp: DataInputStream) {
        inp.readFully(vram)
        inp.readFully(palette)
        inp.readFully(oam)
        ctrl = inp.readInt(); mask = inp.readInt(); status = inp.readInt(); oamAddr = inp.readInt()
        v = inp.readInt(); t = inp.readInt(); fineX = inp.readInt(); w = inp.readInt()
        dataBuffer = inp.readInt(); scanline = inp.readInt(); cycle = inp.readInt()
    }
}
