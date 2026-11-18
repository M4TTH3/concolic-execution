package poc.core.replay

import io.github.cvc5.Kind
import io.github.cvc5.Term

/**
 * Renders cvc5 terms as POC-like infix for the replay viewer, e.g. `cmd[0] * 2 == 130` instead of
 * `(= (bvmul ((_ zero_extend 24) cmd_0) #b0...010) #b0...10000010)`.
 *
 * Input variables print as POC source (`cmd_len` as `len(cmd)`, `cmd_3` as `cmd[3]`), byte zero-extension
 * is invisible, unsigned comparisons are marked `<ᵤ`, and `not` of a comparison flips the operator.
 */
class TermPrinter(private val inputName: String, private val maxLength: Int = 300) {
    fun print(t: Term): String {
        val s = render(t, 0)
        return if (s.length <= maxLength) s else s.take(maxLength - 1) + "…"
    }

    private fun render(t: Term, parentPrec: Int): String {
        val (text, prec) = node(t)
        return if (prec < parentPrec) "($text)" else text
    }

    private fun node(t: Term): Pair<String, Int> = when (t.kind) {
        Kind.CONSTANT -> symbol(t) to ATOM
        Kind.CONST_BITVECTOR -> bvValue(t) to ATOM
        Kind.CONST_BOOLEAN -> t.booleanValue.toString() to ATOM
        Kind.BITVECTOR_ZERO_EXTEND -> node(t.getChild(0))
        Kind.BITVECTOR_EXTRACT -> "byte(${render(t.getChild(0), 0)})" to ATOM
        Kind.BITVECTOR_ADD -> chain(t, "+", ADD)
        Kind.BITVECTOR_MULT -> chain(t, "*", MUL)
        Kind.BITVECTOR_SUB -> leftAssoc(t, "-", ADD)
        Kind.BITVECTOR_SDIV -> leftAssoc(t, "/", MUL)
        Kind.BITVECTOR_NEG -> "-" + render(t.getChild(0), UNARY) to UNARY
        Kind.AND -> chain(t, "&&", AND)
        Kind.OR -> chain(t, "||", OR)
        Kind.IMPLIES -> "${render(t.getChild(0), IMPLIES + 1)} => ${render(t.getChild(1), IMPLIES)}" to IMPLIES
        Kind.ITE -> "${render(t.getChild(0), ITE + 1)} ? ${render(t.getChild(1), ITE + 1)} : ${render(t.getChild(2), ITE)}" to ITE
        Kind.NOT -> not(t.getChild(0))
        else -> comparison(t.kind)?.let { cmp(t, it) } ?: (t.toString() to ATOM)
    }

    private fun not(inner: Term): Pair<String, Int> {
        if (inner.kind == Kind.NOT) return node(inner.getChild(0))
        val negated = comparison(inner.kind)?.let(NEGATED::get)
        return if (negated != null) cmp(inner, negated) else "!" + render(inner, NOT) to NOT
    }

    private fun cmp(t: Term, op: String) = "${render(t.getChild(0), CMP + 1)} $op ${render(t.getChild(1), CMP + 1)}" to CMP

    private fun chain(t: Term, op: String, prec: Int) =
        (0 until t.numChildren).joinToString(" $op ") { render(t.getChild(it), prec) } to prec

    /** Left-associative: the right operand needs parentheses at equal precedence, `a - (b - c)`. */
    private fun leftAssoc(t: Term, op: String, prec: Int) =
        "${render(t.getChild(0), prec)} $op ${render(t.getChild(1), prec + 1)}" to prec

    private fun symbol(t: Term): String {
        val name = if (t.hasSymbol()) t.symbol else return t.toString()
        if (name == "cmd_len") return "len($inputName)"
        val byte = BYTE_VAR.matchEntire(name) ?: return name
        return "$inputName[${byte.groupValues[1]}]"
    }

    /** 32-bit values print signed (they are POC ints); bytes print 0..255. */
    private fun bvValue(t: Term): String {
        val unsigned = t.getBitVectorValue(10).toLong()
        return if (t.sort.bitVectorSize == 32) unsigned.toInt().toString() else unsigned.toString()
    }

    private companion object {
        const val ITE = 1
        const val IMPLIES = 2
        const val OR = 3
        const val AND = 4
        const val NOT = 5
        const val CMP = 6
        const val ADD = 7
        const val MUL = 8
        const val UNARY = 9
        const val ATOM = 10

        val BYTE_VAR = Regex("cmd_(\\d+)")

        fun comparison(kind: Kind): String? = when (kind) {
            Kind.EQUAL -> "=="
            Kind.DISTINCT -> "!="
            Kind.BITVECTOR_SLT -> "<"
            Kind.BITVECTOR_SLE -> "<="
            Kind.BITVECTOR_SGT -> ">"
            Kind.BITVECTOR_SGE -> ">="
            Kind.BITVECTOR_ULT -> "<ᵤ"
            Kind.BITVECTOR_ULE -> "<=ᵤ"
            Kind.BITVECTOR_UGT -> ">ᵤ"
            Kind.BITVECTOR_UGE -> ">=ᵤ"
            else -> null
        }

        val NEGATED = mapOf(
            "==" to "!=", "!=" to "==",
            "<" to ">=", "<=" to ">", ">" to "<=", ">=" to "<",
            "<ᵤ" to ">=ᵤ", "<=ᵤ" to ">ᵤ", ">ᵤ" to "<=ᵤ", ">=ᵤ" to "<ᵤ",
        )
    }
}

/** Interns printed terms so the data file stores each distinct expression once. */
class TermTable(private val printer: TermPrinter) {
    private val byNode = HashMap<Long, Int>()
    private val byText = LinkedHashMap<String, Int>()

    /** Every interned text, indexed by id. */
    val texts: List<String> get() = byText.keys.toList()

    fun id(t: Term): Int = byNode.getOrPut(t.id) {
        val text = printer.print(t)
        byText.getOrPut(text) { byText.size }
    }
}
