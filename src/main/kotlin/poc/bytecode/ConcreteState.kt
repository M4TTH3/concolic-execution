package poc.bytecode

class Frame(val fn: FunctionCode, val mem: ByteArray, val retDst: Ref?) {
    var pc = 0
}

/** The concrete half of the VM: real byte memory per call frame, plus program output and coverage. */
class ConcreteState(val program: BytecodeProgram, private val maxCallDepth: Int) {
    private val frames = ArrayDeque<Frame>()

    val output = mutableListOf<String>()

    /** Every (site, outcome) reached in this run, whether or not its condition was symbolic. */
    val covered = LinkedHashSet<Branch>()

    val frame: Frame get() = frames.last()
    val halted: Boolean get() = frames.isEmpty()

    /** Number of frames on the call stack (0 once MAIN has returned). */
    val depth: Int get() = frames.size

    /** Frame [i] from the bottom (0 is MAIN). Read-only view for observers such as the replay recorder. */
    fun frameAt(i: Int): Frame = frames[i]

    fun enterMain(cmd: ByteArray) {
        val main = program.main
        frames.addLast(Frame(main, ByteArray(main.frameSize), retDst = null))
        writeStr(main.params.single(), cmd)
    }

    // ---- ints ----

    fun readInt(ref: Ref): Int = readIntAt(frame.mem, ref.offset)

    fun writeInt(ref: Ref, value: Int) = writeIntAt(frame.mem, ref.offset, value)

    // ---- strs ----

    fun strLen(s: Ref): Int = readIntAt(frame.mem, s.offset)

    /** Byte [i] of [s] as 0..255. Valid for any i < capacity, including bytes past the length. */
    fun strByte(s: Ref, i: Int): Int = frame.mem[s.offset + STR_HEADER_SIZE + i].toInt() and 0xFF

    fun setStrByte(s: Ref, i: Int, value: Int) {
        frame.mem[s.offset + STR_HEADER_SIZE + i] = value.toByte()
    }

    /** The first len(s) bytes. */
    fun strBytes(s: Ref): ByteArray = frame.mem.copyOfRange(s.offset + STR_HEADER_SIZE, s.offset + STR_HEADER_SIZE + strLen(s))

    fun writeStr(s: Ref, bytes: ByteArray) {
        val capacity = s.size - STR_HEADER_SIZE
        require(bytes.size <= capacity) { "str of ${bytes.size} bytes exceeds capacity $capacity" }
        writeIntAt(frame.mem, s.offset, bytes.size)
        bytes.copyInto(frame.mem, s.offset + STR_HEADER_SIZE)
        frame.mem.fill(0, s.offset + STR_HEADER_SIZE + bytes.size, s.offset + s.size)
    }

    // ---- control ----

    fun copy(dst: Ref, src: Ref) = frame.mem.copyInto(frame.mem, dst.offset, src.offset, src.offset + src.size)

    fun jump(pc: Int) {
        frame.pc = pc
    }

    fun cover(site: BranchSite, outcome: Boolean) {
        covered += Branch(site.id, outcome)
    }

    /** Pushes a frame for [fn] with each argument copied into its parameter slot; [ret] receives the result. */
    fun call(fn: FunctionCode, args: List<Ref>, ret: Ref?) {
        if (frames.size >= maxCallDepth) throw LimitExceeded("call depth limit of $maxCallDepth exceeded")
        val caller = frame
        val mem = ByteArray(fn.frameSize)
        args.forEachIndexed { i, arg -> caller.mem.copyInto(mem, fn.params[i].offset, arg.offset, arg.offset + arg.size) }
        frames.addLast(Frame(fn, mem, ret))
    }

    /** Pops the current frame, copying [value] into the caller's return slot if both exist. */
    fun ret(value: Ref?) {
        val callee = frames.removeLast()
        val dst = callee.retDst
        if (value != null && dst != null) {
            callee.mem.copyInto(frame.mem, dst.offset, value.offset, value.offset + value.size)
        }
    }
}

internal fun readIntAt(mem: ByteArray, offset: Int): Int =
    (mem[offset].toInt() and 0xFF) or
        ((mem[offset + 1].toInt() and 0xFF) shl 8) or
        ((mem[offset + 2].toInt() and 0xFF) shl 16) or
        ((mem[offset + 3].toInt() and 0xFF) shl 24)

internal fun writeIntAt(mem: ByteArray, offset: Int, value: Int) {
    mem[offset] = value.toByte()
    mem[offset + 1] = (value ushr 8).toByte()
    mem[offset + 2] = (value ushr 16).toByte()
    mem[offset + 3] = (value ushr 24).toByte()
}
