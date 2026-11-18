package poc.bytecode

import io.github.cvc5.Kind
import io.github.cvc5.Term
import poc.parser.CmpOp
import poc.parser.quotePoc

/**
 * One bytecode instruction. [execute] carries both semantics: each instruction first reads its
 * concrete inputs, then updates the symbolic state (on the pre-state, so a destination aliasing a
 * source is harmless), then updates the concrete state. Terms are only built when an operand is
 * symbolic, so fully concrete branches are never recorded as path constraints.
 */
sealed interface Instruction {
    fun execute(conc: ConcreteState, sym: SymbolicState)

    /** The current-frame slot this instruction writes, if any (the replay recorder reports it even when
     *  the value does not change, e.g. writing 0 into a fresh slot). */
    val writes: Ref? get() = null
}

/** An instruction with a jump target; the compiler emits label ids and then retargets to pcs. */
sealed interface JumpInstruction : Instruction {
    val target: Int

    fun retarget(pc: Int): Instruction
}

// ---- data movement ----

data class ConstInt(val dst: Ref, val value: Int) : Instruction {
    override val writes: Ref get() = dst

    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        sym.setInt(dst, null)
        conc.writeInt(dst, value)
    }
}

/** Each char of [value] is one byte. */
data class ConstStr(val dst: Ref, val value: String) : Instruction {
    override val writes: Ref get() = dst

    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        sym.clear(dst)
        conc.writeStr(dst, value.toByteArray(Charsets.ISO_8859_1))
    }

    override fun toString() = "ConstStr(dst=$dst, value=${quotePoc(value)})"
}

/** Copies [src] to [dst] byte for byte (an int, or a whole str including its length). */
data class Copy(val dst: Ref, val src: Ref) : Instruction {
    override val writes: Ref get() = dst

    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        sym.copy(dst, src)
        conc.copy(dst, src)
    }
}

// ---- int arithmetic (32-bit wrapping, matching QF_BV) ----

data class IntAdd(val dst: Ref, val a: Ref, val b: Ref) : Instruction {
    override val writes: Ref get() = dst

    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        val x = conc.readInt(a)
        val y = conc.readInt(b)
        sym.setInt(dst, sym.intBinary(Kind.BITVECTOR_ADD, a, x, b, y))
        conc.writeInt(dst, x + y)
    }
}

data class IntSub(val dst: Ref, val a: Ref, val b: Ref) : Instruction {
    override val writes: Ref get() = dst

    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        val x = conc.readInt(a)
        val y = conc.readInt(b)
        sym.setInt(dst, sym.intBinary(Kind.BITVECTOR_SUB, a, x, b, y))
        conc.writeInt(dst, x - y)
    }
}

data class IntMul(val dst: Ref, val a: Ref, val b: Ref) : Instruction {
    override val writes: Ref get() = dst

    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        val x = conc.readInt(a)
        val y = conc.readInt(b)
        sym.setInt(dst, sym.intBinary(Kind.BITVECTOR_MULT, a, x, b, y))
        conc.writeInt(dst, x * y)
    }
}

/** Truncating signed division. The implicit `b != 0` check is a branch the engine can flip to find crashes. */
data class IntDiv(val dst: Ref, val a: Ref, val b: Ref, val site: BranchSite) : Instruction {
    override val writes: Ref get() = dst

    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        val x = conc.readInt(a)
        val y = conc.readInt(b)
        val nonZero = y != 0
        sym.intTerm(b)?.let { divisor -> sym.record(site, sym.mk(Kind.DISTINCT, divisor, sym.bv32(0)), nonZero) }
        conc.cover(site, nonZero)
        if (!nonZero) throw PocRuntimeError(site, "division by zero")

        // JVM Int division already wraps MIN_VALUE / -1 to MIN_VALUE, exactly like bvsdiv.
        sym.setInt(dst, sym.intBinary(Kind.BITVECTOR_SDIV, a, x, b, y))
        conc.writeInt(dst, x / y)
    }
}

data class IntNeg(val dst: Ref, val a: Ref) : Instruction {
    override val writes: Ref get() = dst

    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        val x = conc.readInt(a)
        sym.setInt(dst, sym.intTerm(a)?.let { sym.mk(Kind.BITVECTOR_NEG, it) })
        conc.writeInt(dst, -x)
    }
}

// ---- str operations ----

data class StrLen(val dst: Ref, val s: Ref) : Instruction {
    override val writes: Ref get() = dst

    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        sym.setInt(dst, sym.lenTerm(s))
        conc.writeInt(dst, conc.strLen(s))
    }
}

/** `dst = s[i]` as an int 0..255, after an implicit bounds check. */
data class LoadByte(val dst: Ref, val s: Ref, val i: Ref, val site: BranchSite) : Instruction {
    override val writes: Ref get() = dst

    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        val index = conc.readInt(i)
        checkBounds(conc, sym, s, i, index, site)

        val indexTerm = sym.intTerm(i)
        val byte: Term? = if (indexTerm == null) sym.byteTerm(s, index) else symbolicRead(conc, sym, s, indexTerm)
        sym.setInt(dst, byte?.let { sym.zeroExtend(it, 24) })
        conc.writeInt(dst, conc.strByte(s, index))
    }
}

/** `s[i] = v`: stores the low 8 bits of v, after an implicit bounds check. */
data class StoreByte(val s: Ref, val i: Ref, val v: Ref, val site: BranchSite) : Instruction {
    override val writes: Ref get() = s

    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        val index = conc.readInt(i)
        val value = conc.readInt(v)
        checkBounds(conc, sym, s, i, index, site)

        val newByte: Term? = sym.intTerm(v)?.let { sym.lowByte(it) }
        val indexTerm = sym.intTerm(i)
        if (indexTerm == null) {
            sym.setByte(s, index, newByte)
        } else {
            // Symbolic index: every byte becomes `ite(i == k, v, old_k)`.
            val stored = newByte ?: sym.bv8(value)
            for (k in 0 until sym.maxStrLen) {
                val old = sym.byteOr(s, k, conc.strByte(s, k))
                sym.setByte(s, k, sym.ite(sym.eq(indexTerm, sym.bv32(k)), stored, old))
            }
        }
        conc.setStrByte(s, index, value)
    }
}

/** Records `i <u len(s)` (which also rules out negative i) and throws if it is false. */
private fun checkBounds(conc: ConcreteState, sym: SymbolicState, s: Ref, i: Ref, index: Int, site: BranchSite) {
    val len = conc.strLen(s)
    val inBounds = index in 0 until len
    if (sym.intTerm(i) != null || sym.lenTerm(s) != null) {
        sym.record(site, sym.ult(sym.intOr(i, index), sym.lenOr(s, len)), inBounds)
    }
    conc.cover(site, inBounds)
    if (!inBounds) throw PocRuntimeError(site, "index $index out of bounds for str of length $len")
}

/** Exact read at a symbolic index: `ite(i == 0, s[0], ite(i == 1, s[1], ...))` over the whole capacity. */
private fun symbolicRead(conc: ConcreteState, sym: SymbolicState, s: Ref, index: Term): Term {
    val last = sym.maxStrLen - 1
    var result = sym.byteOr(s, last, conc.strByte(s, last))
    for (k in last - 1 downTo 0) {
        result = sym.ite(sym.eq(index, sym.bv32(k)), sym.byteOr(s, k, conc.strByte(s, k)), result)
    }
    return result
}

// ---- control flow ----

data class Jump(override val target: Int) : JumpInstruction {
    override fun execute(conc: ConcreteState, sym: SymbolicState) = conc.jump(target)

    override fun retarget(pc: Int) = copy(target = pc)
}

/** Jumps to [target] if `a op b` (signed), otherwise falls through. */
data class IntCmpJump(val op: CmpOp, val a: Ref, val b: Ref, override val target: Int, val site: BranchSite) : JumpInstruction {
    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        val x = conc.readInt(a)
        val y = conc.readInt(b)
        val taken = op.evalInt(x, y)
        sym.intCompare(op, a, x, b, y)?.let { sym.record(site, it, taken) }
        conc.cover(site, taken)
        if (taken) conc.jump(target)
    }

    override fun retarget(pc: Int) = copy(target = pc)
}

/** Jumps to [target] if `a op b`, comparing strs lexicographically over unsigned bytes. */
data class StrCmpJump(val op: CmpOp, val a: Ref, val b: Ref, override val target: Int, val site: BranchSite) : JumpInstruction {
    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        val taken = op.fromCompare(compareBytes(conc.strBytes(a), conc.strBytes(b)))
        sym.strCompare(op, strValue(conc, sym, a), strValue(conc, sym, b))?.let { sym.record(site, it, taken) }
        conc.cover(site, taken)
        if (taken) conc.jump(target)
    }

    override fun retarget(pc: Int) = copy(target = pc)
}

private fun strValue(conc: ConcreteState, sym: SymbolicState, s: Ref) =
    SymbolicState.StrValue(s, conc.strLen(s), IntArray(sym.maxStrLen) { conc.strByte(s, it) })

/** Lexicographic comparison over unsigned bytes; a proper prefix sorts first. */
internal fun compareBytes(x: ByteArray, y: ByteArray): Int {
    for (k in 0 until minOf(x.size, y.size)) {
        val d = (x[k].toInt() and 0xFF) - (y[k].toInt() and 0xFF)
        if (d != 0) return d
    }
    return x.size - y.size
}

/** Calls function [fn] (named [name] for the disassembly). [ret] is the caller's temp for the result, if used. */
data class Call(val fn: Int, val name: String, val args: List<Ref>, val ret: Ref?) : Instruction {
    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        val callee = conc.program.functions[fn]
        sym.call(callee, args)
        conc.call(callee, args, ret)
    }
}

/** Returns from the current function, copying [value] (if any) into the caller's return temp. */
data class Return(val value: Ref?) : Instruction {
    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        sym.ret(value, conc.frame.retDst)
        conc.ret(value)
    }
}

// ---- output (concrete only) ----

data class PrintInt(val src: Ref) : Instruction {
    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        conc.output += conc.readInt(src).toString()
    }
}

data class PrintStr(val src: Ref) : Instruction {
    override fun execute(conc: ConcreteState, sym: SymbolicState) {
        conc.output += String(conc.strBytes(src), Charsets.ISO_8859_1)
    }
}
