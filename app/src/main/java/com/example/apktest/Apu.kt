package com.example.apktest

import java.io.DataInputStream
import java.io.DataOutputStream

class Apu {
    private val regs = ByteArray(24)

    private class Pulse(val isP1: Boolean) {
        var enabled = false
        var duty = 0
        var halt = false
        var constantVol = false
        var volParam = 0
        var sweepEnabled = false
        var sweepPeriod = 0
        var sweepNegate = false
        var sweepShift = 0
        var sweepDivider = 0
        var sweepReload = false
        var period = 0
        var timer = 0
        var phase = 0
        var length = 0
        var envStart = false
        var envDivider = 0
        var envDecay = 0

        fun writeReg(idx: Int, v: Int) {
            when (idx) {
                0 -> {
                    duty = (v shr 6) and 0x03
                    halt = (v and 0x20) != 0
                    constantVol = (v and 0x10) != 0
                    volParam = v and 0x0F
                }
                1 -> {
                    sweepEnabled = (v and 0x80) != 0
                    sweepPeriod = (v shr 4) and 0x07
                    sweepNegate = (v and 0x08) != 0
                    sweepShift = v and 0x07
                    sweepReload = true
                }
                2 -> period = (period and 0x700) or (v and 0xFF)
                3 -> {
                    period = (period and 0xFF) or ((v and 0x07) shl 8)
                    if (enabled) length = LENGTH_TABLE[(v shr 3) and 0x1F]
                    envStart = true
                    phase = 0
                }
            }
        }

        fun clockTimer() {
            if (timer > 0) timer-- else {
                timer = period
                phase = (phase + 1) and 0x07
            }
        }

        fun clockEnvelope() {
            if (envStart) {
                envStart = false
                envDecay = 15
                envDivider = volParam
            } else if (envDivider > 0) {
                envDivider--
            } else {
                envDivider = volParam
                if (envDecay > 0) envDecay--
                else if (halt) envDecay = 15
            }
        }

        fun clockLength() {
            if (!halt && length > 0) length--
        }

        fun clockSweep() {
            if (sweepDivider > 0) {
                sweepDivider--
            } else {
                sweepDivider = sweepPeriod
                if (sweepEnabled && sweepShift > 0) {
                    val change = period shr sweepShift
                    val target = if (sweepNegate) period - change - (if (isP1) 1 else 0) else period + change
                    if (period >= 8 && target <= 0x7FF && target >= 0) period = target
                }
            }
            if (sweepReload) {
                sweepDivider = sweepPeriod
                sweepReload = false
            }
        }

        fun output(): Int {
            if (!enabled || length == 0) return 0
            if (period < 8) return 0
            val change = period shr sweepShift
            val target = if (sweepNegate) period - change else period + change
            if (target > 0x7FF) return 0
            if (DUTY[duty][phase] == 0) return 0
            return if (constantVol) volParam else envDecay
        }
    }

    private class Triangle {
        var enabled = false
        var control = false
        var linearLoad = 0
        var linear = 0
        var linearReload = false
        var period = 0
        var timer = 0
        var length = 0
        var stepIdx = 0

        fun clockTimer() {
            if (timer > 0) timer-- else {
                timer = period
                if (length > 0 && linear > 0) stepIdx = (stepIdx + 1) and 0x1F
            }
        }

        fun clockLinear() {
            if (linearReload) linear = linearLoad
            else if (linear > 0) linear--
            if (!control) linearReload = false
        }

        fun clockLength() {
            if (!control && length > 0) length--
        }

        fun output(): Int {
            if (!enabled || length == 0 || linear == 0) return 0
            if (period < 2) return 0
            return SEQ[stepIdx]
        }

        companion object {
            val SEQ = intArrayOf(
                15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
            )
        }
    }

    private class Noise {
        var enabled = false
        var halt = false
        var constantVol = false
        var volParam = 0
        var mode = false
        var periodIdx = 0
        var timer = 0
        var length = 0
        var shiftReg = 1
        var envStart = false
        var envDivider = 0
        var envDecay = 0

        fun clockTimer() {
            if (timer > 0) timer-- else {
                timer = PERIODS[periodIdx]
                val tap = if (mode) 6 else 1
                val feedback = (shiftReg and 0x01) xor ((shiftReg shr tap) and 0x01)
                shiftReg = (shiftReg ushr 1) or (feedback shl 14)
            }
        }

        fun clockEnvelope() {
            if (envStart) {
                envStart = false
                envDecay = 15
                envDivider = volParam
            } else if (envDivider > 0) {
                envDivider--
            } else {
                envDivider = volParam
                if (envDecay > 0) envDecay--
                else if (halt) envDecay = 15
            }
        }

        fun clockLength() {
            if (!halt && length > 0) length--
        }

        fun output(): Int {
            if (!enabled || length == 0) return 0
            if ((shiftReg and 0x01) != 0) return 0
            return if (constantVol) volParam else envDecay
        }

        companion object {
            val PERIODS = intArrayOf(4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068)
        }
    }

    private val p1 = Pulse(true)
    private val p2 = Pulse(false)
    private val tri = Triangle()
    private val noise = Noise()

    private var frameMode5 = false
    private var frameCycle = 0

    private val sampleBuf = ShortArray(16384)
    private var sampleLen = 0
    private var sampleAcc = 0.0

    companion object {
        val LENGTH_TABLE = intArrayOf(
            10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
        )
        val DUTY = arrayOf(
            intArrayOf(0, 1, 0, 0, 0, 0, 0, 0),
            intArrayOf(0, 1, 1, 0, 0, 0, 0, 0),
            intArrayOf(0, 1, 1, 1, 1, 0, 0, 0),
            intArrayOf(1, 0, 0, 1, 1, 1, 1, 1)
        )
    }

    fun reset() {
        regs.fill(0)
        p1.enabled = false; p2.enabled = false; tri.enabled = false; noise.enabled = false
        p1.length = 0; p2.length = 0; tri.length = 0; noise.length = 0
        frameMode5 = false
        frameCycle = 0
        sampleLen = 0
        sampleAcc = 0.0
    }

    fun writeReg(addr: Int, v: Int) {
        val idx = addr - 0x4000
        if (idx in 0..23) regs[idx] = v.toByte()
        when (addr) {
            in 0x4000..0x4003 -> p1.writeReg(addr - 0x4000, v)
            in 0x4004..0x4007 -> p2.writeReg(addr - 0x4004, v)
            0x4008 -> {
                tri.control = (v and 0x80) != 0
                tri.linearLoad = v and 0x7F
            }
            0x400A -> tri.period = (tri.period and 0x700) or (v and 0xFF)
            0x400B -> {
                tri.period = (tri.period and 0xFF) or ((v and 0x07) shl 8)
                if (tri.enabled) tri.length = LENGTH_TABLE[(v shr 3) and 0x1F]
                tri.linearReload = true
            }
            0x400C -> {
                noise.halt = (v and 0x20) != 0
                noise.constantVol = (v and 0x10) != 0
                noise.volParam = v and 0x0F
            }
            0x400E -> {
                noise.mode = (v and 0x80) != 0
                noise.periodIdx = v and 0x0F
            }
            0x400F -> {
                if (noise.enabled) noise.length = LENGTH_TABLE[(v shr 3) and 0x1F]
                noise.envStart = true
            }
            0x4015 -> {
                p1.enabled = (v and 0x01) != 0
                p2.enabled = (v and 0x02) != 0
                tri.enabled = (v and 0x04) != 0
                noise.enabled = (v and 0x08) != 0
                if (!p1.enabled) p1.length = 0
                if (!p2.enabled) p2.length = 0
                if (!tri.enabled) tri.length = 0
                if (!noise.enabled) noise.length = 0
            }
            0x4017 -> {
                frameMode5 = (v and 0x80) != 0
                frameCycle = 0
                if (frameMode5) {
                    clockQuarter()
                    clockHalf()
                }
            }
        }
    }

    fun readStatus(): Int {
        var r = 0
        if (p1.length > 0) r = r or 0x01
        if (p2.length > 0) r = r or 0x02
        if (tri.length > 0) r = r or 0x04
        if (noise.length > 0) r = r or 0x08
        return r
    }

    private fun clockQuarter() {
        p1.clockEnvelope()
        p2.clockEnvelope()
        noise.clockEnvelope()
        tri.clockLinear()
    }

    private fun clockHalf() {
        p1.clockLength(); p2.clockLength(); noise.clockLength(); tri.clockLength()
        p1.clockSweep(); p2.clockSweep()
    }

    fun step(cycles: Int) {
        var n = cycles
        while (n > 0) {
            n--
            // Pulse timers run at half CPU rate
            if ((frameCycle and 0x01) == 0) {
                p1.clockTimer()
                p2.clockTimer()
            }
            tri.clockTimer()
            noise.clockTimer()
            frameCycle++
            if (!frameMode5) {
                when (frameCycle) {
                    3729 -> clockQuarter()
                    7457 -> { clockQuarter(); clockHalf() }
                    11186 -> clockQuarter()
                    14915 -> { clockQuarter(); clockHalf(); frameCycle = 0 }
                }
            } else {
                when (frameCycle) {
                    3729 -> clockQuarter()
                    7457 -> { clockQuarter(); clockHalf() }
                    11186 -> clockQuarter()
                    18641 -> { clockQuarter(); clockHalf(); frameCycle = 0 }
                }
            }
            sampleAcc += 1.0
            if (sampleAcc >= 40.584) {
                sampleAcc -= 40.584
                pushSample()
            }
        }
    }

    private fun pushSample() {
        if (sampleLen >= sampleBuf.size) return
        var v = (p1.output() + p2.output()) * 500 + tri.output() * 600 + noise.output() * 400
        if (v > 32767) v = 32767
        sampleBuf[sampleLen] = v.toShort()
        sampleLen++
    }

    fun drainSamples(): ShortArray {
        val out = sampleBuf.copyOf(sampleLen)
        sampleLen = 0
        return out
    }

    fun saveState(out: DataOutputStream) {
        out.write(regs)
    }

    fun loadState(inp: DataInputStream) {
        inp.readFully(regs)
        for (i in regs.indices) {
            writeReg(0x4000 + i, regs[i].toInt() and 0xFF)
        }
    }
}
