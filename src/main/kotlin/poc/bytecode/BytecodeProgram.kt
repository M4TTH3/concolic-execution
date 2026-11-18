package poc.bytecode

import poc.parser.Pos
import poc.parser.Span

enum class SiteKind {
    /** An `if` comparison. */
    IF,

    /** The implicit `0 <= i < len(s)` check of `s[i]`. "Taken" means in bounds. */
    BOUNDS,

    /** The implicit `divisor != 0` check of `/`. "Taken" means non-zero. */
    DIV_ZERO,
}

/**
 * A place where execution can go two ways. Ids are assigned in source order and identify the site in path
 * signatures. [pos] is where it is reported; [span] is what the replay viewer highlights for it.
 */
data class BranchSite(
    val id: Int,
    val function: String,
    val pos: Pos,
    val kind: SiteKind,
    val description: String,
    val span: Span,
) {
    override fun toString() = "#$id"

    fun describe() = "#$id $function:$pos ${kind.name.lowercase()} `$description`"
}

class FunctionCode(
    val name: String,
    val index: Int,
    val params: List<Ref>,
    /** Size of the returned value, or null if the function returns nothing. */
    val returnSize: Int?,
    val frameSize: Int,
    val code: List<Instruction>,
    /** Slot names for the disassembler (variables by name, temporaries as t0, t1, ...). */
    val slotNames: Map<Ref, String>,
    /** Source span of each instruction, same index as [code]. */
    val spans: List<Span>,
    /** Slots holding compiler temporaries rather than named variables. */
    val temps: Set<Ref>,
)

class BytecodeProgram(
    val functions: List<FunctionCode>,
    val mainIndex: Int,
    val maxStrLen: Int,
    val sites: List<BranchSite>,
) {
    val main: FunctionCode get() = functions[mainIndex]

    fun disassemble(): String = buildString {
        for (fn in functions) {
            appendLine("fun ${fn.name} (frame ${fn.frameSize} bytes, returns ${fn.returnSize?.let { "$it bytes" } ?: "nothing"})")
            appendLine("  slots: " + fn.slotNames.entries.joinToString(", ") { (ref, name) -> "$ref:${ref.size}=$name" })
            fn.code.forEachIndexed { pc, ins -> appendLine("  %3d  %-60s ; %s".format(pc, ins, fn.spans[pc].start)) }
        }
        appendLine("sites:")
        sites.forEach { appendLine("  ${it.describe()}") }
    }
}
