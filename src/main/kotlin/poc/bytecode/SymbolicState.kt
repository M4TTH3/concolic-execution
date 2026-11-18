package poc.bytecode

import io.github.cvc5.Kind
import io.github.cvc5.Term
import io.github.cvc5.TermManager
import poc.parser.CmpOp

/** The free solver variables describing MAIN's `cmd` input. Created once per tester and shared by every run. */
class SymbolicInputs(tm: TermManager, maxStrLen: Int) {
    val len: Term = tm.mkConst(tm.mkBitVectorSort(32), "cmd_len")
    val bytes: List<Term> = List(maxStrLen) { tm.mkConst(tm.mkBitVectorSort(8), "cmd_$it") }

    /** Always assumed: the input length fits the str capacity. */
    val domain: Term = tm.mkTerm(Kind.BITVECTOR_ULE, len, tm.mkBitVector(32, maxStrLen.toLong()))
}

/**
 * The symbolic half of the VM: a shadow cell per byte of every frame.
 *
 * A null cell means "concrete, use the value in [ConcreteState]". Otherwise an int (or str length header)
 * holds a BV32 term at its base offset, and a str data byte holds a BV8 term at its own offset.
 * POC is type-safe, so ints are never read bytewise and the widths always match.
 */
class SymbolicState(val tm: TermManager, val program: BytecodeProgram) {
    private val frames = ArrayDeque<Array<Term?>>()
    private val cells: Array<Term?> get() = frames.last()

    val pathConstraints = mutableListOf<PathConstraint>()
    val maxStrLen: Int get() = program.maxStrLen

    fun enterMain(inputs: SymbolicInputs) {
        val main = program.main
        val shadow = arrayOfNulls<Term>(main.frameSize)
        val cmd = main.params.single()
        shadow[cmd.offset] = inputs.len
        inputs.bytes.forEachIndexed { k, b -> shadow[cmd.offset + STR_HEADER_SIZE + k] = b }
        frames.addLast(shadow)
    }

    // ---- shadow memory ----

    fun intTerm(ref: Ref): Term? = cells[ref.offset]

    fun intOr(ref: Ref, concrete: Int): Term = intTerm(ref) ?: bv32(concrete)

    fun setInt(ref: Ref, term: Term?) {
        cells[ref.offset] = term
    }

    fun lenTerm(s: Ref): Term? = cells[s.offset]

    fun lenOr(s: Ref, concrete: Int): Term = lenTerm(s) ?: bv32(concrete)

    fun byteTerm(s: Ref, k: Int): Term? = cells[s.offset + STR_HEADER_SIZE + k]

    fun byteOr(s: Ref, k: Int, concrete: Int): Term = byteTerm(s, k) ?: bv8(concrete)

    fun setByte(s: Ref, k: Int, term: Term?) {
        cells[s.offset + STR_HEADER_SIZE + k] = term
    }

    /** True if any cell of [ref] holds a term. */
    fun isSymbolic(ref: Ref): Boolean = (ref.offset until ref.offset + ref.size).any { cells[it] != null }

    fun clear(ref: Ref) = cells.fill(null, ref.offset, ref.offset + ref.size)

    fun copy(dst: Ref, src: Ref) = cells.copyInto(cells, dst.offset, src.offset, src.offset + src.size)

    fun call(fn: FunctionCode, args: List<Ref>) {
        val caller = cells
        val shadow = arrayOfNulls<Term>(fn.frameSize)
        args.forEachIndexed { i, arg -> caller.copyInto(shadow, fn.params[i].offset, arg.offset, arg.offset + arg.size) }
        frames.addLast(shadow)
    }

    fun ret(value: Ref?, retDst: Ref?) {
        val callee = frames.removeLast()
        if (value != null && retDst != null) {
            callee.copyInto(cells, retDst.offset, value.offset, value.offset + value.size)
        }
    }

    // ---- path constraints ----

    /** Records that [cond] evaluated to [taken] at [site]. */
    fun record(site: BranchSite, cond: Term, taken: Boolean) {
        pathConstraints += PathConstraint(site, taken, if (taken) cond else cond.notTerm())
    }

    // ---- term builders ----

    fun bv32(v: Int): Term = tm.mkBitVector(32, v.toLong() and 0xFFFFFFFFL)

    fun bv8(v: Int): Term = tm.mkBitVector(8, (v and 0xFF).toLong())

    fun bool(b: Boolean): Term = tm.mkBoolean(b)

    fun mk(kind: Kind, a: Term): Term = tm.mkTerm(kind, a)

    fun mk(kind: Kind, a: Term, b: Term): Term = tm.mkTerm(kind, a, b)

    fun ite(c: Term, a: Term, b: Term): Term = tm.mkTerm(Kind.ITE, c, a, b)

    fun and(terms: List<Term>): Term = when (terms.size) {
        0 -> bool(true)
        1 -> terms[0]
        else -> tm.mkTerm(Kind.AND, terms.toTypedArray())
    }

    fun eq(a: Term, b: Term): Term = mk(Kind.EQUAL, a, b)

    fun ult(a: Term, b: Term): Term = mk(Kind.BITVECTOR_ULT, a, b)

    fun zeroExtend(t: Term, by: Int): Term = tm.mkTerm(tm.mkOp(Kind.BITVECTOR_ZERO_EXTEND, by), t)

    fun lowByte(t: Term): Term = tm.mkTerm(tm.mkOp(Kind.BITVECTOR_EXTRACT, 7, 0), t)

    /** `kind(a, b)` over two int operands, or null when both are concrete (the result is then concrete too). */
    fun intBinary(kind: Kind, a: Ref, x: Int, b: Ref, y: Int): Term? {
        if (intTerm(a) == null && intTerm(b) == null) return null
        return mk(kind, intOr(a, x), intOr(b, y))
    }

    /** Signed comparison of two int operands, or null when both are concrete. */
    fun intCompare(op: CmpOp, a: Ref, x: Int, b: Ref, y: Int): Term? {
        if (intTerm(a) == null && intTerm(b) == null) return null
        val ta = intOr(a, x)
        val tb = intOr(b, y)
        return when (op) {
            CmpOp.EQ -> eq(ta, tb)
            CmpOp.NE -> mk(Kind.DISTINCT, ta, tb)
            CmpOp.LT -> mk(Kind.BITVECTOR_SLT, ta, tb)
            CmpOp.LE -> mk(Kind.BITVECTOR_SLE, ta, tb)
            CmpOp.GT -> mk(Kind.BITVECTOR_SGT, ta, tb)
            CmpOp.GE -> mk(Kind.BITVECTOR_SGE, ta, tb)
        }
    }

    /** A str operand's concrete length and full-capacity data, used where its shadow cells are null. */
    class StrValue(val ref: Ref, val len: Int, val data: IntArray)

    /** Exact comparison of two strs over the whole capacity, or null when both are fully concrete. */
    fun strCompare(op: CmpOp, a: StrValue, b: StrValue): Term? {
        if (!isSymbolic(a.ref) && !isSymbolic(b.ref)) return null
        return when (op) {
            CmpOp.EQ -> strEq(a, b)
            CmpOp.NE -> strEq(a, b).notTerm()
            CmpOp.LT -> strLt(a, b)
            CmpOp.GT -> strLt(b, a)
            CmpOp.LE -> strLt(b, a).notTerm()
            CmpOp.GE -> strLt(a, b).notTerm()
        }
    }

    /** `len(a) == len(b) ∧ ⋀ₖ (k < len(a) → a[k] == b[k])`. */
    private fun strEq(a: StrValue, b: StrValue): Term {
        val la = lenOr(a.ref, a.len)
        val parts = mutableListOf(eq(la, lenOr(b.ref, b.len)))
        val lenConcrete = lenTerm(a.ref) == null
        for (k in 0 until maxStrLen) {
            if (lenConcrete && k >= a.len) break // k < len(a) is false, the conjunct is trivially true
            val ta = byteTerm(a.ref, k)
            val tb = byteTerm(b.ref, k)
            if (ta == null && tb == null && a.data[k] == b.data[k]) continue
            val same = eq(ta ?: bv8(a.data[k]), tb ?: bv8(b.data[k]))
            parts.add(if (lenConcrete) same else mk(Kind.IMPLIES, ult(bv32(k), la), same))
        }
        return and(parts)
    }

    /** Lexicographic `a < b` over unsigned bytes: a right-to-left ITE fold that ends in `len(a) < len(b)`. */
    private fun strLt(a: StrValue, b: StrValue): Term {
        val la = lenOr(a.ref, a.len)
        val lb = lenOr(b.ref, b.len)
        val shorter = ult(la, lb)
        val lensConcrete = lenTerm(a.ref) == null && lenTerm(b.ref) == null
        var result = shorter
        val positions = if (lensConcrete) minOf(a.len, b.len) else maxStrLen
        for (k in positions - 1 downTo 0) {
            val ta = byteTerm(a.ref, k)
            val tb = byteTerm(b.ref, k)
            val decided = if (ta == null && tb == null) {
                // Both bytes concrete: equal bytes defer to the suffix, unequal bytes decide.
                if (a.data[k] == b.data[k]) result else bool(a.data[k] < b.data[k])
            } else {
                val ba = ta ?: bv8(a.data[k])
                val bb = tb ?: bv8(b.data[k])
                ite(eq(ba, bb), result, ult(ba, bb))
            }
            result = if (lensConcrete) decided else ite(mk(Kind.AND, ult(bv32(k), la), ult(bv32(k), lb)), decided, shorter)
        }
        return result
    }
}
