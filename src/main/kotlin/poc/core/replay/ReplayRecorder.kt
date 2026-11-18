package poc.core.replay

import io.github.cvc5.Term
import poc.bytecode.Call
import poc.bytecode.ConcreteState
import poc.bytecode.FunctionCode
import poc.bytecode.INT_SIZE
import poc.bytecode.Ref
import poc.bytecode.STR_HEADER_SIZE
import poc.bytecode.SymbolicState
import poc.bytecode.Return
import poc.bytecode.readIntAt
import poc.core.StepObserver

/**
 * One run's replay in the delta format of `replay-data.js`.
 *
 * [init] holds the effects that set up MAIN's frame (the input) before step 0. Each entry of [steps] is a
 * bare pc, or `[pc, effect...]` where an effect is `[slot, value]`, `[slot, value, sym]`, `["c", fnIndex]`
 * (call: push a frame) or `["r"]` (return: pop it). Effects after `c` or `r` target the new top frame.
 */
class RunReplay(
    val init: List<Any?>,
    val steps: List<Any>,
    /** Step index at which each path constraint was recorded. */
    val constraintSteps: List<Int>,
    /** Step index at which each output line was printed. */
    val outputSteps: List<Int>,
)

/** The slot order shared by the recorder and the data file: the function's slot table, in allocation order. */
internal fun slotsOf(fn: FunctionCode): List<Ref> = fn.slotNames.keys.toList()

/**
 * Records a run as deltas. It keeps a snapshot of every frame (bytes and shadow-cell references) and,
 * after each instruction, emits the slots of the top frame whose bytes or terms changed, plus the slots
 * the instruction wrote even if their value did not change (so `int c = 0` still counts as assigned).
 */
class ReplayRecorder(private val terms: TermTable) : StepObserver {
    private class Snapshot(val fn: FunctionCode, val slots: List<Ref>, val mem: ByteArray, val cells: Array<Term?>) {
        fun slotOf(ref: Ref) = slots.indexOf(ref)
    }

    private val stack = ArrayDeque<Snapshot>()
    private var init: List<Any?> = emptyList()
    private val steps = mutableListOf<Any>()
    private val constraintSteps = mutableListOf<Int>()
    private val outputSteps = mutableListOf<Int>()

    fun result() = RunReplay(init, steps.toList(), constraintSteps.toList(), outputSteps.toList())

    override fun onStart(conc: ConcreteState, sym: SymbolicState) {
        val main = conc.frameAt(0).fn
        push(main)
        init = diffTop(conc, sym, forced = main.params.mapTo(HashSet()) { stack.last().slotOf(it) })
    }

    override fun onStep(fn: FunctionCode, pc: Int, conc: ConcreteState, sym: SymbolicState) {
        // add(), not +=: on a MutableList<Any?>, `+= list` would splice the list's elements in.
        val effects = mutableListOf<Any?>()
        val instruction = fn.code[pc]
        val forced = HashSet<Int>()
        while (stack.size > conc.depth) {
            stack.removeLast()
            effects.add(listOf("r"))
        }
        if (conc.depth > stack.size) {
            val callee = conc.frameAt(conc.depth - 1).fn
            push(callee)
            effects.add(listOf("c", callee.index))
            callee.params.mapTo(forced) { stack.last().slotOf(it) } // arguments were copied in
        } else if (instruction is Return && stack.isNotEmpty()) {
            // The caller resumes right after its Call, whose `ret` temp received the value.
            val caller = conc.frameAt(conc.depth - 1)
            (caller.fn.code[caller.pc - 1] as? Call)?.ret?.let { forced += stack.last().slotOf(it) }
        } else if (instruction !is Return) {
            instruction.writes?.let { forced += stack.last().slotOf(it) }
        }
        if (stack.isNotEmpty()) effects.addAll(diffTop(conc, sym, forced))

        val step = steps.size
        repeat(sym.pathConstraints.size - constraintSteps.size) { constraintSteps.add(step) }
        repeat(conc.output.size - outputSteps.size) { outputSteps.add(step) }
        steps.add(if (effects.isEmpty()) pc else listOf<Any?>(pc) + effects)
    }

    private fun push(fn: FunctionCode) {
        // A new frame starts zeroed and fully concrete, exactly like ConcreteState/SymbolicState.call.
        stack.addLast(Snapshot(fn, slotsOf(fn), ByteArray(fn.frameSize), arrayOfNulls(fn.frameSize)))
    }

    private fun diffTop(conc: ConcreteState, sym: SymbolicState, forced: Set<Int>): List<Any?> {
        val top = conc.depth - 1
        val mem = conc.frameAt(top).mem
        val cells = sym.shadowAt(top)
        val snap = stack.last()
        val effects = mutableListOf<Any?>()
        snap.slots.forEachIndexed { slot, ref ->
            val end = ref.offset + ref.size
            val changed = slot in forced || (ref.offset until end).any { mem[it] != snap.mem[it] || cells[it] !== snap.cells[it] }
            if (changed) {
                effects.add(encode(slot, ref, mem, cells))
                mem.copyInto(snap.mem, ref.offset, ref.offset, end)
                cells.copyInto(snap.cells, ref.offset, ref.offset, end)
            }
        }
        return effects
    }

    private fun encode(slot: Int, ref: Ref, mem: ByteArray, cells: Array<Term?>): List<Any?> {
        if (ref.size == INT_SIZE) {
            val value = readIntAt(mem, ref.offset)
            val term = cells[ref.offset] ?: return listOf(slot, value)
            return listOf(slot, value, terms.id(term))
        }
        val len = readIntAt(mem, ref.offset).coerceIn(0, ref.size - STR_HEADER_SIZE)
        val data = ref.offset + STR_HEADER_SIZE
        val text = String(mem, data, len, Charsets.ISO_8859_1)
        val sym = listOf(cells[ref.offset]?.let(terms::id)) + (0 until len).map { k -> cells[data + k]?.let(terms::id) }
        return if (sym.all { it == null }) listOf(slot, text) else listOf(slot, text, sym)
    }
}
