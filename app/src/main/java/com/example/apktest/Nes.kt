package com.example.apktest

import java.io.DataInputStream
import java.io.DataOutputStream

// ================= Controller =================
class Controller {
    // bit0 A, bit1 B, bit2 Select, bit3 Start, bit4 Up, bit5 Down, bit6 Left, bit7 Right
    var buttons = 0
    private var shift = 0
    private var strobe = false

    fun writeStrobe(v: Boolean) {
        strobe = v
        if (strobe) shift = buttons
    }

    fun read(): Int {
        if (strobe) return buttons and 1
        val r = shift and 1
        shift = (shift ushr 1) or 0x80
        return r
    }

    fun saveState(out: DataOutputStream) {
        out.writeInt(shift)
        out.writeBoolean(strobe)
    }

    fun loadState(inp: DataInputStream) {
        shift = inp.readInt()
        strobe = inp.readBoolean()
    }
}

// ================= Cartridge =================
class Cartridge(rom: ByteArray) {
    val prgRom: ByteArray
    val chrRom: ByteArray
    val chrRam = ByteArray(8192)
    val hasChrRam: Boolean
    val prgRam = ByteArray(8192)
    val mapperId: Int
    val battery: Boolean
    val fourScreen: Boolean
    // 0 = horizontal, 1 = vertical, 2 = single-screen 0, 3 = single-screen 1, 4 = four-screen
    var mirrorMode: Int

    init {
        require(rom.size >= 16 && rom[0] == 0x4E.toByte() && rom[1] == 0x45.toByte() &&
            rom[2] == 0x53.toByte() && rom[3] == 0x1A.toByte()) { "File khong phai ROM NES (thieu header iNES)" }
        val prgSize = (rom[4].toInt() and 0xFF) * 16384
        val chrSize = (rom[5].toInt() and 0xFF) * 8192
        val flags6 = rom[6].toInt() and 0xFF
        val flags7 = rom[7].toInt() and 0xFF
        mapperId = (flags6 shr 4) or (flags7 and 0xF0)
        battery = (flags6 and 0x02) != 0
        fourScreen = (flags6 and 0x08) != 0
        mirrorMode = when {
            fourScreen -> 4
            (flags6 and 0x01) != 0 -> 1
            else -> 0
        }
        var offset = 16
        if ((flags6 and 0x04) != 0) offset += 512 // trainer
        require(rom.size >= offset + prgSize) { "ROM bi thieu du lieu PRG" }
        prgRom = rom.copyOfRange(offset, offset + prgSize)
        offset += prgSize
        hasChrRam = chrSize == 0
        chrRom = if (hasChrRam) ByteArray(0)
            else rom.copyOfRange(offset, minOf(offset + chrSize, rom.size))
    }

    fun chrByte(index: Int): Int {
        return if (hasChrRam) chrRam[index % chrRam.size].toInt() and 0xFF
        else chrRom[index % chrRom.size].toInt() and 0xFF
    }

    fun chrSet(index: Int, v: Int) {
        if (hasChrRam) chrRam[index % chrRam.size] = v.toByte()
    }
}

// ================= Mappers =================
abstract class Mapper(val cart: Cartridge) {
    var irqLine = false
    abstract fun cpuRead(addr: Int): Int
    abstract fun cpuWrite(addr: Int, v: Int)
    open fun ppuRead(addr: Int): Int = cart.chrByte(addr)
    open fun ppuWrite(addr: Int, v: Int) { cart.chrSet(addr, v) }
    open fun scanline() {}
    open fun saveState(out: DataOutputStream) {}
    open fun loadState(inp: DataInputStream) {}

    protected fun ramRead(addr: Int): Int = cart.prgRam[addr - 0x6000].toInt() and 0xFF
    protected fun ramWrite(addr: Int, v: Int) { cart.prgRam[addr - 0x6000] = v.toByte() }
}

class Mapper0(cart: Cartridge) : Mapper(cart) {
    override fun cpuRead(addr: Int): Int = when {
        addr >= 0x8000 -> cart.prgRom[(addr - 0x8000) % cart.prgRom.size].toInt() and 0xFF
        addr >= 0x6000 -> ramRead(addr)
        else -> 0
    }
    override fun cpuWrite(addr: Int, v: Int) {
        if (addr in 0x6000..0x7FFF) ramWrite(addr, v)
    }
}

class Mapper2(cart: Cartridge) : Mapper(cart) {
    private var bank = 0
    override fun cpuRead(addr: Int): Int = when {
        addr >= 0xC000 -> {
            val last = cart.prgRom.size - 16384
            cart.prgRom[last + (addr - 0xC000)].toInt() and 0xFF
        }
        addr >= 0x8000 -> cart.prgRom[(bank * 16384 + (addr - 0x8000)) % cart.prgRom.size].toInt() and 0xFF
        addr >= 0x6000 -> ramRead(addr)
        else -> 0
    }
    override fun cpuWrite(addr: Int, v: Int) {
        if (addr >= 0x8000) bank = v % (cart.prgRom.size / 16384)
        else if (addr in 0x6000..0x7FFF) ramWrite(addr, v)
    }
    override fun saveState(out: DataOutputStream) { out.writeInt(bank) }
    override fun loadState(inp: DataInputStream) { bank = inp.readInt() }
}

class Mapper3(cart: Cartridge) : Mapper(cart) {
    private var chrBank = 0
    override fun cpuRead(addr: Int): Int = when {
        addr >= 0x8000 -> cart.prgRom[(addr - 0x8000) % cart.prgRom.size].toInt() and 0xFF
        addr >= 0x6000 -> ramRead(addr)
        else -> 0
    }
    override fun cpuWrite(addr: Int, v: Int) {
        if (addr >= 0x8000) chrBank = v and 0x03
        else if (addr in 0x6000..0x7FFF) ramWrite(addr, v)
    }
    override fun ppuRead(addr: Int): Int = cart.chrByte(chrBank * 8192 + addr)
    override fun ppuWrite(addr: Int, v: Int) { cart.chrSet(chrBank * 8192 + addr, v) }
    override fun saveState(out: DataOutputStream) { out.writeInt(chrBank) }
    override fun loadState(inp: DataInputStream) { chrBank = inp.readInt() }
}

class Mapper1(cart: Cartridge) : Mapper(cart) {
    private var shiftReg = 0
    private var bitCount = 0
    private var control = 0x0C
    private var chrBank0 = 0
    private var chrBank1 = 0
    private var prgBank = 0

    private fun applyMirroring() {
        if (cart.fourScreen) { cart.mirrorMode = 4; return }
        cart.mirrorMode = when (control and 0x03) {
            0 -> 2
            1 -> 3
            2 -> 1
            else -> 0
        }
    }

    override fun cpuRead(addr: Int): Int = when {
        addr >= 0x8000 -> {
            val banks16 = cart.prgRom.size / 16384
            val idx = when ((control shr 2) and 0x03) {
                0, 1 -> {
                    val base = ((prgBank and 0x0E) * 16384) % cart.prgRom.size
                    base + (addr - 0x8000)
                }
                2 -> if (addr < 0xC000) addr - 0x8000
                    else ((prgBank and 0x0F) % banks16) * 16384 + (addr - 0xC000)
                else -> if (addr < 0xC000) ((prgBank and 0x0F) % banks16) * 16384 + (addr - 0x8000)
                    else (banks16 - 1) * 16384 + (addr - 0xC000)
            }
            cart.prgRom[idx].toInt() and 0xFF
        }
        addr >= 0x6000 -> ramRead(addr)
        else -> 0
    }

    override fun cpuWrite(addr: Int, v: Int) {
        if (addr in 0x6000..0x7FFF) { ramWrite(addr, v); return }
        if (addr < 0x8000) return
        if ((v and 0x80) != 0) {
            shiftReg = 0
            bitCount = 0
            control = control or 0x0C
            applyMirroring()
            return
        }
        shiftReg = shiftReg or ((v and 0x01) shl bitCount)
        bitCount++
        if (bitCount >= 5) {
            val value = shiftReg and 0x1F
            when (addr and 0x6000) {
                0x0000 -> { control = value; applyMirroring() }
                0x2000 -> chrBank0 = value
                0x4000 -> chrBank1 = value
                else -> prgBank = value and 0x0F
            }
            shiftReg = 0
            bitCount = 0
        }
    }

    override fun ppuRead(addr: Int): Int {
        val idx = if ((control and 0x10) != 0) {
            if (addr < 0x1000) chrBank0 * 4096 + addr else chrBank1 * 4096 + (addr - 0x1000)
        } else {
            (chrBank0 and 0x1E) * 1024 + addr
        }
        return cart.chrByte(idx)
    }

    override fun ppuWrite(addr: Int, v: Int) {
        val idx = if ((control and 0x10) != 0) {
            if (addr < 0x1000) chrBank0 * 4096 + addr else chrBank1 * 4096 + (addr - 0x1000)
        } else {
            (chrBank0 and 0x1E) * 1024 + addr
        }
        cart.chrSet(idx, v)
    }

    override fun saveState(out: DataOutputStream) {
        out.writeInt(shiftReg); out.writeInt(bitCount); out.writeInt(control)
        out.writeInt(chrBank0); out.writeInt(chrBank1); out.writeInt(prgBank)
    }
    override fun loadState(inp: DataInputStream) {
        shiftReg = inp.readInt(); bitCount = inp.readInt(); control = inp.readInt()
        chrBank0 = inp.readInt(); chrBank1 = inp.readInt(); prgBank = inp.readInt()
        applyMirroring()
    }
}

class Mapper4(cart: Cartridge) : Mapper(cart) {
    private var bankSelect = 0
    private val bankData = IntArray(8)
    private var irqLatch = 0
    private var irqCounter = 0
    private var irqReload = false
    private var irqEnabled = false

    private fun prgBankOffset(bank: Int): Int {
        val banks8 = cart.prgRom.size / 8192
        var b = bank % banks8
        if (b < 0) b += banks8
        return b * 8192
    }

    override fun cpuRead(addr: Int): Int = when {
        addr >= 0x8000 -> {
            val mode = (bankSelect shr 6) and 0x01
            val banks8 = cart.prgRom.size / 8192
            val off = when {
                addr < 0xA000 -> prgBankOffset(if (mode == 0) bankData[6] else banks8 - 2) + (addr - 0x8000)
                addr < 0xC000 -> prgBankOffset(bankData[7]) + (addr - 0xA000)
                addr < 0xE000 -> prgBankOffset(if (mode == 0) banks8 - 2 else bankData[6]) + (addr - 0xC000)
                else -> prgBankOffset(banks8 - 1) + (addr - 0xE000)
            }
            cart.prgRom[off].toInt() and 0xFF
        }
        addr >= 0x6000 -> ramRead(addr)
        else -> 0
    }

    override fun cpuWrite(addr: Int, v: Int) {
        if (addr in 0x6000..0x7FFF) { ramWrite(addr, v); return }
        if (addr < 0x8000) return
        val even = (addr and 0x01) == 0
        when (addr and 0x6000) {
            0x0000 -> if (even) {
                bankSelect = v
            } else {
                bankData[bankSelect and 0x07] = v
            }
            0x2000 -> if (even) {
                if (!cart.fourScreen) cart.mirrorMode = if ((v and 0x01) != 0) 0 else 1
            }
            0x4000 -> if (even) irqLatch = v else irqReload = true
            else -> if (even) { irqEnabled = false; irqLine = false } else irqEnabled = true
        }
    }

    private fun chrIndex(addr: Int): Int {
        val inv = (bankSelect and 0x80) != 0
        val region = (addr shr 10) and 0x07
        val within = addr and 0x3FF
        val bank1k = if (!inv) {
            when (region) {
                0 -> (bankData[0] and 0xFE)
                1 -> (bankData[0] and 0xFE) + 1
                2 -> (bankData[1] and 0xFE)
                3 -> (bankData[1] and 0xFE) + 1
                4 -> bankData[2]
                5 -> bankData[3]
                6 -> bankData[4]
                else -> bankData[5]
            }
        } else {
            when (region) {
                0 -> bankData[2]
                1 -> bankData[3]
                2 -> bankData[4]
                3 -> bankData[5]
                4 -> (bankData[0] and 0xFE)
                5 -> (bankData[0] and 0xFE) + 1
                6 -> (bankData[1] and 0xFE)
                else -> (bankData[1] and 0xFE) + 1
            }
        }
        return bank1k * 1024 + within
    }

    override fun ppuRead(addr: Int): Int = cart.chrByte(chrIndex(addr))
    override fun ppuWrite(addr: Int, v: Int) { cart.chrSet(chrIndex(addr), v) }

    override fun scanline() {
        if (irqCounter == 0 || irqReload) {
            irqCounter = irqLatch
            irqReload = false
        } else {
            irqCounter--
        }
        if (irqCounter == 0 && irqEnabled) irqLine = true
    }

    override fun saveState(out: DataOutputStream) {
        out.writeInt(bankSelect)
        for (b in bankData) out.writeInt(b)
        out.writeInt(irqLatch); out.writeInt(irqCounter)
        out.writeBoolean(irqReload); out.writeBoolean(irqEnabled); out.writeBoolean(irqLine)
        out.writeInt(cart.mirrorMode)
    }
    override fun loadState(inp: DataInputStream) {
        bankSelect = inp.readInt()
        for (i in bankData.indices) bankData[i] = inp.readInt()
        irqLatch = inp.readInt(); irqCounter = inp.readInt()
        irqReload = inp.readBoolean(); irqEnabled = inp.readBoolean(); irqLine = inp.readBoolean()
        cart.mirrorMode = inp.readInt()
    }
}

// ================= CPU 6502 =================
class Cpu(private val bus: Nes) {
    var a = 0
    var x = 0
    var y = 0
    var sp = 0xFD
    var pc = 0
    var p = 0x24
    var stall = 0
    var nmiPending = false
    private var pageCrossed = false

    companion object {
        const val FC = 0x01
        const val FZ = 0x02
        const val FI = 0x04
        const val FD = 0x08
        const val FB = 0x10
        const val FU = 0x20
        const val FV = 0x40
        const val FN = 0x80

        val OPS = arrayOf(
            "BRK","ORA","NOP","NOP","NOP","ORA","ASL","NOP","PHP","ORA","ASL","NOP","NOP","ORA","ASL","NOP",
            "BPL","ORA","NOP","NOP","NOP","ORA","ASL","NOP","CLC","ORA","NOP","NOP","NOP","ORA","ASL","NOP",
            "JSR","AND","NOP","NOP","BIT","AND","ROL","NOP","PLP","AND","ROL","NOP","BIT","AND","ROL","NOP",
            "BMI","AND","NOP","NOP","NOP","AND","ROL","NOP","SEC","AND","NOP","NOP","NOP","AND","ROL","NOP",
            "RTI","EOR","NOP","NOP","NOP","EOR","LSR","NOP","PHA","EOR","LSR","NOP","JMP","EOR","LSR","NOP",
            "BVC","EOR","NOP","NOP","NOP","EOR","LSR","NOP","CLI","EOR","NOP","NOP","NOP","EOR","LSR","NOP",
            "RTS","ADC","NOP","NOP","NOP","ADC","ROR","NOP","PLA","ADC","ROR","NOP","JMP","ADC","ROR","NOP",
            "BVS","ADC","NOP","NOP","NOP","ADC","ROR","NOP","SEI","ADC","NOP","NOP","NOP","ADC","ROR","NOP",
            "NOP","STA","NOP","NOP","STY","STA","STX","NOP","DEY","NOP","TXA","NOP","STY","STA","STX","NOP",
            "BCC","STA","NOP","NOP","STY","STA","STX","NOP","TYA","STA","TXS","NOP","NOP","STA","NOP","NOP",
            "LDY","LDA","LDX","NOP","LDY","LDA","LDX","NOP","TAY","LDA","TAX","NOP","LDY","LDA","LDX","NOP",
            "BCS","LDA","NOP","NOP","LDY","LDA","LDX","NOP","CLV","LDA","TSX","NOP","LDY","LDA","LDX","NOP",
            "CPY","CMP","NOP","NOP","CPY","CMP","DEC","NOP","INY","CMP","DEX","NOP","CPY","CMP","DEC","NOP",
            "BNE","CMP","NOP","NOP","NOP","CMP","DEC","NOP","CLD","CMP","NOP","NOP","NOP","CMP","DEC","NOP",
            "CPX","SBC","NOP","NOP","CPX","SBC","INC","NOP","INX","SBC","NOP","NOP","CPX","SBC","INC","NOP",
            "BEQ","SBC","NOP","NOP","NOP","SBC","INC","NOP","SED","SBC","NOP","NOP","NOP","SBC","INC","NOP"
        )
        // 0 IMP, 1 IMM, 2 ZP, 3 ZPX, 4 ZPY, 5 ABS, 6 ABX, 7 ABY, 8 IZX, 9 IZY, 10 REL, 11 IND
        val MODES = intArrayOf(
            0,8,0,0,2,2,2,0,0,1,0,0,5,5,5,0,
            10,9,0,0,3,3,3,0,0,7,0,0,6,6,6,0,
            5,8,0,0,2,2,2,0,0,1,0,0,5,5,5,0,
            10,9,0,0,3,3,3,0,0,7,0,0,6,6,6,0,
            0,8,0,0,2,2,2,0,0,1,0,0,5,5,5,0,
            10,9,0,0,3,3,3,0,0,7,0,0,6,6,6,0,
            0,8,0,0,2,2,2,0,0,1,0,0,11,5,5,0,
            10,9,0,0,3,3,3,0,0,7,0,0,6,6,6,0,
            1,8,0,0,2,2,2,0,0,0,0,0,5,5,5,0,
            10,9,0,0,3,3,4,0,0,7,0,0,0,6,0,0,
            1,8,1,0,2,2,2,0,0,1,0,0,5,5,5,0,
            10,9,0,0,3,3,4,0,0,7,0,0,6,6,7,0,
            1,8,0,0,2,2,2,0,0,1,0,0,5,5,5,0,
            10,9,0,0,3,3,3,0,0,7,0,0,6,6,6,0,
            1,8,0,0,2,2,2,0,0,1,0,0,5,5,5,0,
            10,9,0,0,3,3,3,0,0,7,0,0,6,6,6,0
        )
        val CYCLES = intArrayOf(
            7,6,2,2,3,3,5,2,3,2,2,2,4,4,6,2,
            2,5,2,2,4,4,6,2,2,4,2,2,4,4,7,2,
            6,6,2,2,3,3,5,2,4,2,2,2,4,4,6,2,
            2,5,2,2,4,4,6,2,2,4,2,2,4,4,7,2,
            6,6,2,2,3,3,5,2,3,2,2,2,3,4,6,2,
            2,5,2,2,4,4,6,2,2,4,2,2,4,4,7,2,
            6,6,2,2,3,3,5,2,4,2,2,2,5,4,6,2,
            2,5,2,2,4,4,6,2,2,4,2,2,4,4,7,2,
            2,6,2,2,3,3,3,2,2,2,2,2,4,4,4,2,
            2,6,2,2,4,4,4,2,2,5,2,2,2,5,2,2,
            2,6,2,2,3,3,3,2,2,2,2,2,4,4,4,2,
            2,5,2,2,4,4,4,2,2,4,2,2,4,4,4,2,
            2,6,2,2,3,3,5,2,2,2,2,2,4,4,6,2,
            2,5,2,2,4,4,6,2,2,4,2,2,4,4,7,2,
            2,6,2,2,3,3,5,2,2,2,2,2,4,4,6,2,
            2,5,2,2,4,4,6,2,2,4,2,2,4,4,7,2
        )
        val CROSS_OPS = setOf("ADC","AND","CMP","CPX","CPY","EOR","LDA","LDX","LDY","ORA","SBC","NOP")
    }

    fun reset() {
        a = 0; x = 0; y = 0
        sp = 0xFD
        p = 0x24
        stall = 0
        nmiPending = false
        pc = read16(0xFFFC)
    }

    private fun read(addr: Int): Int = bus.read(addr and 0xFFFF)
    private fun write(addr: Int, v: Int) = bus.write(addr and 0xFFFF, v and 0xFF)
    private fun read16(addr: Int): Int = read(addr) or (read((addr + 1) and 0xFFFF) shl 8)

    private fun push(v: Int) {
        write(0x100 + sp, v)
        sp = (sp - 1) and 0xFF
    }

    private fun pull(): Int {
        sp = (sp + 1) and 0xFF
        return read(0x100 + sp)
    }

    private fun setFlag(bit: Int, on: Boolean) {
        p = if (on) p or bit else p and bit.inv()
    }

    private fun setZN(v: Int) {
        p = (p and (FZ or FN).inv()) or (if (v == 0) FZ else 0) or (v and FN)
    }

    private fun interrupt(vector: Int) {
        push((pc shr 8) and 0xFF)
        push(pc and 0xFF)
        push((p and FB.inv()) or FU)
        setFlag(FI, true)
        pc = read16(vector)
    }

    private fun address(mode: Int): Int {
        pageCrossed = false
        return when (mode) {
            1 -> { val r = pc; pc = (pc + 1) and 0xFFFF; r } // IMM
            2 -> { val r = read(pc); pc = (pc + 1) and 0xFFFF; r } // ZP
            3 -> { val r = (read(pc) + x) and 0xFF; pc = (pc + 1) and 0xFFFF; r } // ZPX
            4 -> { val r = (read(pc) + y) and 0xFF; pc = (pc + 1) and 0xFFFF; r } // ZPY
            5 -> { val r = read16(pc); pc = (pc + 2) and 0xFFFF; r } // ABS
            6 -> { // ABX
                val base = read16(pc); pc = (pc + 2) and 0xFFFF
                val r = (base + x) and 0xFFFF
                pageCrossed = (base and 0xFF00) != (r and 0xFF00)
                r
            }
            7 -> { // ABY
                val base = read16(pc); pc = (pc + 2) and 0xFFFF
                val r = (base + y) and 0xFFFF
                pageCrossed = (base and 0xFF00) != (r and 0xFF00)
                r
            }
            8 -> { // IZX
                val ptr = (read(pc) + x) and 0xFF; pc = (pc + 1) and 0xFFFF
                read(ptr) or (read((ptr + 1) and 0xFF) shl 8)
            }
            9 -> { // IZY
                val ptr = read(pc); pc = (pc + 1) and 0xFFFF
                val base = read(ptr) or (read((ptr + 1) and 0xFF) shl 8)
                val r = (base + y) and 0xFFFF
                pageCrossed = (base and 0xFF00) != (r and 0xFF00)
                r
            }
            10 -> { // REL
                var off = read(pc); pc = (pc + 1) and 0xFFFF
                if (off >= 0x80) off -= 0x100
                (pc + off) and 0xFFFF
            }
            11 -> { // IND (with page-wrap bug)
                val ptr = read16(pc); pc = (pc + 2) and 0xFFFF
                read(ptr) or (read((ptr and 0xFF00) or ((ptr + 1) and 0xFF)) shl 8)
            }
            else -> 0 // IMP
        }
    }

    private fun branch(cond: Boolean, target: Int, baseCycles: Int): Int {
        if (!cond) return baseCycles
        var c = baseCycles + 1
        if ((pc and 0xFF00) != (target and 0xFF00)) c++
        pc = target
        return c
    }

    fun step(): Int {
        if (stall > 0) { stall--; return 1 }
        if (nmiPending) {
            nmiPending = false
            interrupt(0xFFFA)
            return 7
        }
        val m = bus.mapper
        if (m != null && m.irqLine && (p and FI) == 0) {
            m.irqLine = false
            interrupt(0xFFFE)
            return 7
        }
        val opcode = read(pc)
        pc = (pc + 1) and 0xFFFF
        val name = OPS[opcode]
        val mode = MODES[opcode]
        var cycles = CYCLES[opcode]
        val isAcc = opcode == 0x0A || opcode == 0x2A || opcode == 0x4A || opcode == 0x6A
        val addr = if (isAcc) 0 else address(mode)
        if (pageCrossed && name in CROSS_OPS) cycles++

        when (name) {
            "ADC" -> {
                val v = read(addr)
                val t = a + v + (if ((p and FC) != 0) 1 else 0)
                setFlag(FC, t > 0xFF)
                setFlag(FV, ((a xor t) and (v xor t) and 0x80) != 0)
                a = t and 0xFF
                setZN(a)
            }
            "SBC" -> {
                val v = read(addr)
                val t = a - v - (if ((p and FC) != 0) 0 else 1)
                setFlag(FC, t >= 0)
                setFlag(FV, ((a xor t) and (a xor v) and 0x80) != 0)
                a = t and 0xFF
                setZN(a)
            }
            "AND" -> { a = a and read(addr); setZN(a) }
            "ORA" -> { a = a or read(addr); setZN(a) }
            "EOR" -> { a = a xor read(addr); setZN(a) }
            "LDA" -> { a = read(addr); setZN(a) }
            "LDX" -> { x = read(addr); setZN(x) }
            "LDY" -> { y = read(addr); setZN(y) }
            "STA" -> write(addr, a)
            "STX" -> write(addr, x)
            "STY" -> write(addr, y)
            "CMP" -> { val t = a - read(addr); setFlag(FC, t >= 0); setZN(t and 0xFF) }
            "CPX" -> { val t = x - read(addr); setFlag(FC, t >= 0); setZN(t and 0xFF) }
            "CPY" -> { val t = y - read(addr); setFlag(FC, t >= 0); setZN(t and 0xFF) }
            "BIT" -> {
                val v = read(addr)
                setFlag(FZ, (a and v) == 0)
                setFlag(FN, (v and 0x80) != 0)
                setFlag(FV, (v and 0x40) != 0)
            }
            "ASL" -> {
                if (isAcc) {
                    setFlag(FC, (a and 0x80) != 0)
                    a = (a shl 1) and 0xFF
                    setZN(a)
                } else {
                    val v = read(addr)
                    setFlag(FC, (v and 0x80) != 0)
                    val r = (v shl 1) and 0xFF
                    write(addr, r)
                    setZN(r)
                }
            }
            "LSR" -> {
                if (isAcc) {
                    setFlag(FC, (a and 0x01) != 0)
                    a = a ushr 1
                    setZN(a)
                } else {
                    val v = read(addr)
                    setFlag(FC, (v and 0x01) != 0)
                    val r = v ushr 1
                    write(addr, r)
                    setZN(r)
                }
            }
            "ROL" -> {
                val ci = if ((p and FC) != 0) 1 else 0
                if (isAcc) {
                    setFlag(FC, (a and 0x80) != 0)
                    a = ((a shl 1) or ci) and 0xFF
                    setZN(a)
                } else {
                    val v = read(addr)
                    setFlag(FC, (v and 0x80) != 0)
                    val r = ((v shl 1) or ci) and 0xFF
                    write(addr, r)
                    setZN(r)
                }
            }
            "ROR" -> {
                val ci = if ((p and FC) != 0) 0x80 else 0
                if (isAcc) {
                    setFlag(FC, (a and 0x01) != 0)
                    a = (a ushr 1) or ci
                    setZN(a)
                } else {
                    val v = read(addr)
                    setFlag(FC, (v and 0x01) != 0)
                    val r = (v ushr 1) or ci
                    write(addr, r)
                    setZN(r)
                }
            }
            "INC" -> { val r = (read(addr) + 1) and 0xFF; write(addr, r); setZN(r) }
            "DEC" -> { val r = (read(addr) - 1) and 0xFF; write(addr, r); setZN(r) }
            "INX" -> { x = (x + 1) and 0xFF; setZN(x) }
            "INY" -> { y = (y + 1) and 0xFF; setZN(y) }
            "DEX" -> { x = (x - 1) and 0xFF; setZN(x) }
            "DEY" -> { y = (y - 1) and 0xFF; setZN(y) }
            "TAX" -> { x = a; setZN(x) }
            "TAY" -> { y = a; setZN(y) }
            "TXA" -> { a = x; setZN(a) }
            "TYA" -> { a = y; setZN(a) }
            "TSX" -> { x = sp; setZN(x) }
            "TXS" -> { sp = x }
            "PHA" -> push(a)
            "PHP" -> push(p or 0x30)
            "PLA" -> { a = pull(); setZN(a) }
            "PLP" -> { p = (pull() and FB.inv()) or FU }
            "JMP" -> pc = addr
            "JSR" -> {
                val ret = (pc - 1) and 0xFFFF
                push((ret shr 8) and 0xFF)
                push(ret and 0xFF)
                pc = addr
            }
            "RTS" -> {
                val lo = pull()
                val hi = pull()
                pc = (((hi shl 8) or lo) + 1) and 0xFFFF
            }
            "RTI" -> {
                p = (pull() and FB.inv()) or FU
                val lo = pull()
                val hi = pull()
                pc = (hi shl 8) or lo
            }
            "BRK" -> {
                pc = (pc + 1) and 0xFFFF
                push((pc shr 8) and 0xFF)
                push(pc and 0xFF)
                push(p or 0x30)
                setFlag(FI, true)
                pc = read16(0xFFFE)
            }
            "BPL" -> return branch((p and FN) == 0, addr, cycles)
            "BMI" -> return branch((p and FN) != 0, addr, cycles)
            "BVC" -> return branch((p and FV) == 0, addr, cycles)
            "BVS" -> return branch((p and FV) != 0, addr, cycles)
            "BCC" -> return branch((p and FC) == 0, addr, cycles)
            "BCS" -> return branch((p and FC) != 0, addr, cycles)
            "BNE" -> return branch((p and FZ) == 0, addr, cycles)
            "BEQ" -> return branch((p and FZ) != 0, addr, cycles)
            "CLC" -> setFlag(FC, false)
            "SEC" -> setFlag(FC, true)
            "CLI" -> setFlag(FI, false)
            "SEI" -> setFlag(FI, true)
            "CLV" -> setFlag(FV, false)
            "CLD" -> setFlag(FD, false)
            "SED" -> setFlag(FD, true)
            else -> { } // NOP and unofficial opcodes: no operation
        }
        return cycles
    }

    fun saveState(out: DataOutputStream) {
        out.writeInt(a); out.writeInt(x); out.writeInt(y)
        out.writeInt(sp); out.writeInt(pc); out.writeInt(p)
    }

    fun loadState(inp: DataInputStream) {
        a = inp.readInt(); x = inp.readInt(); y = inp.readInt()
        sp = inp.readInt(); pc = inp.readInt(); p = inp.readInt()
        stall = 0
        nmiPending = false
    }
}

// ================= NES bus / system =================
class Nes {
    val ram = ByteArray(2048)
    var cart: Cartridge? = null
        private set
    var mapper: Mapper? = null
        private set
    val cpu = Cpu(this)
    val ppu = Ppu(this)
    val apu = Apu()
    val pad1 = Controller()
    var romLoaded = false
        private set

    /** Nap ROM. batteryData = du lieu PRG RAM da luu truoc do (neu co). Tra ve null neu OK, hoac chuoi loi. */
    fun loadRom(rom: ByteArray, batteryData: ByteArray?): String? {
        val c: Cartridge
        try {
            c = Cartridge(rom)
        } catch (e: Exception) {
            return "ROM loi: ${e.message}"
        }
        val m: Mapper = when (c.mapperId) {
            0 -> Mapper0(c)
            1 -> Mapper1(c)
            2 -> Mapper2(c)
            3 -> Mapper3(c)
            4 -> Mapper4(c)
            else -> return "Mapper ${c.mapperId} chua duoc ho tro (chi ho tro 0, 1, 2, 3, 4)"
        }
        cart = c
        mapper = m
        if (c.battery && batteryData != null && batteryData.size == c.prgRam.size) {
            batteryData.copyInto(c.prgRam)
        }
        reset()
        romLoaded = true
        return null
    }

    fun reset() {
        ram.fill(0)
        ppu.reset()
        apu.reset()
        cpu.reset()
    }

    fun batteryData(): ByteArray? {
        val c = cart ?: return null
        return if (c.battery) c.prgRam.copyOf() else null
    }

    fun read(addr: Int): Int = when {
        addr < 0x2000 -> ram[addr and 0x7FF].toInt() and 0xFF
        addr < 0x4000 -> ppu.readReg(0x2000 + (addr and 0x07))
        addr == 0x4015 -> apu.readStatus()
        addr == 0x4016 -> pad1.read()
        addr == 0x4017 -> 0
        addr >= 0x4020 -> mapper?.cpuRead(addr) ?: 0
        else -> 0
    }

    fun write(addr: Int, v: Int) {
        when {
            addr < 0x2000 -> ram[addr and 0x7FF] = v.toByte()
            addr < 0x4000 -> ppu.writeReg(0x2000 + (addr and 0x07), v)
            addr == 0x4014 -> dma(v)
            addr == 0x4016 -> pad1.writeStrobe((v and 0x01) != 0)
            addr == 0x4017 -> apu.writeReg(addr, v)
            addr in 0x4000..0x4013 || addr == 0x4015 -> apu.writeReg(addr, v)
            addr >= 0x4020 -> mapper?.cpuWrite(addr, v)
        }
    }

    private fun dma(page: Int) {
        val base = page * 256
        for (i in 0 until 256) {
            ppu.oam[(ppu.oamAddr + i) and 0xFF] = read(base + i).toByte()
        }
        cpu.stall += 513
    }

    fun runFrame() {
        if (!romLoaded) return
        ppu.frameDone = false
        while (!ppu.frameDone) {
            val c = cpu.step()
            var n = c * 3
            while (n > 0) {
                ppu.tick()
                n--
            }
            apu.step(c)
        }
    }

    fun saveState(out: DataOutputStream) {
        val m = mapper ?: throw IllegalStateException("Chua nap ROM")
        out.writeInt(0x4E4553) // "NES"
        out.writeInt(cart!!.mapperId)
        out.write(ram)
        cpu.saveState(out)
        ppu.saveState(out)
        out.write(cart!!.prgRam)
        out.write(cart!!.chrRam)
        m.saveState(out)
        pad1.saveState(out)
        apu.saveState(out)
    }

    fun loadState(inp: DataInputStream) {
        val m = mapper ?: throw IllegalStateException("Chua nap ROM")
        val magic = inp.readInt()
        if (magic != 0x4E4553) throw IllegalStateException("File state khong hop le")
        val mid = inp.readInt()
        if (mid != cart!!.mapperId) throw IllegalStateException("State khong cung mapper voi ROM hien tai")
        inp.readFully(ram)
        cpu.loadState(inp)
        ppu.loadState(inp)
        inp.readFully(cart!!.prgRam)
        inp.readFully(cart!!.chrRam)
        m.loadState(inp)
        pad1.loadState(inp)
        apu.loadState(inp)
    }
}
