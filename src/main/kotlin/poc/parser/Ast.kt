package poc.parser

/** 1-based line, 1-based column. */
data class Pos(val line: Int, val col: Int) {
    override fun toString() = "$line:$col"
}

/** A source range: [start] inclusive, [end] exclusive (both 1-based). */
data class Span(val start: Pos, val end: Pos) {
    override fun toString() = "$start-$end"
}

enum class PocType(val keyword: String, val article: String) {
    INT("int", "an int"),
    STR("str", "a str");

    override fun toString() = keyword
}

data class Program(val decls: List<TopDecl>) {
    val functions: List<FunDecl> get() = decls.filterIsInstance<FunDecl>()
    val mains: List<MainDecl> get() = decls.filterIsInstance<MainDecl>()
}

sealed interface TopDecl {
    val pos: Pos
}

data class FunDecl(
    val name: String,
    val params: List<Param>,
    val returnType: PocType?,
    val body: Block,
    override val pos: Pos,
) : TopDecl

data class Param(val type: PocType, val name: String, val pos: Pos)

/** `MAIN cmd { ... }`: [param] is the name of the str input. */
data class MainDecl(val param: String, val paramPos: Pos, val body: Block, override val pos: Pos) : TopDecl

data class Block(val stmts: List<Stmt>, val pos: Pos, val span: Span) {
    /** The closing `}`. */
    val closingBrace: Span get() = Span(Pos(span.end.line, span.end.col - 1), span.end)
}

// ---- Statements ----

sealed interface Stmt {
    /** Where errors are reported (may be an operator or a name, not the start). */
    val pos: Pos

    /** The node's full source extent, for highlighting. */
    val span: Span
}

data class VarDecl(val type: PocType, val name: String, val init: Expr, override val pos: Pos, override val span: Span) : Stmt

/** `x = e`; only legal for int variables. */
data class Assign(val name: String, val value: Expr, override val pos: Pos, override val span: Span) : Stmt

/** `s[i] = e`: char replacement, stores the low 8 bits of `e`. */
data class CharAssign(val target: String, val index: Expr, val value: Expr, override val pos: Pos, override val span: Span) : Stmt

data class Print(val value: Expr, override val pos: Pos, override val span: Span) : Stmt

data class CallStmt(val call: CallExpr, override val pos: Pos, override val span: Span) : Stmt

data class ReturnStmt(val value: Expr?, override val pos: Pos, override val span: Span) : Stmt

/** `if c0 {b0} else if c1 {b1} ... else {elseBlock}`. */
data class If(val branches: List<Pair<Cond, Block>>, val elseBlock: Block?, override val pos: Pos, override val span: Span) : Stmt

// ---- Conditions (only inside `if`; there is no bool type) ----

sealed interface Cond {
    /** Where errors are reported (may be an operator or a name, not the start). */
    val pos: Pos

    /** The node's full source extent, for highlighting. */
    val span: Span
}

data class Not(val cond: Cond, override val pos: Pos, override val span: Span) : Cond

data class And(val left: Cond, val right: Cond, override val pos: Pos, override val span: Span) : Cond

data class Or(val left: Cond, val right: Cond, override val pos: Pos, override val span: Span) : Cond

data class Compare(val op: CmpOp, val left: Expr, val right: Expr, override val pos: Pos, override val span: Span) : Cond

enum class CmpOp(val symbol: String) {
    EQ("=="),
    NE("!="),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">=");

    /** The operator that holds exactly when this one does not. */
    fun negate(): CmpOp = when (this) {
        EQ -> NE
        NE -> EQ
        LT -> GE
        LE -> GT
        GT -> LE
        GE -> LT
    }

    fun evalInt(a: Int, b: Int): Boolean = when (this) {
        EQ -> a == b
        NE -> a != b
        LT -> a < b
        LE -> a <= b
        GT -> a > b
        GE -> a >= b
    }

    /** Applies this operator to the result of a three-way comparison. */
    fun fromCompare(c: Int): Boolean = evalInt(c, 0)

    override fun toString() = symbol

    companion object {
        fun fromSymbol(s: String): CmpOp = entries.first { it.symbol == s }
    }
}

// ---- Expressions ----

sealed interface Expr {
    /** Where errors are reported (may be an operator or a name, not the start). */
    val pos: Pos

    /** The node's full source extent, for highlighting. */
    val span: Span
}

/** Held as Long so the parser can accept `-2147483648`; the checker rejects out-of-range values. */
data class IntLit(val value: Long, override val pos: Pos, override val span: Span) : Expr

/** Each char of [value] is one byte (0..255). */
data class StrLit(val value: String, override val pos: Pos, override val span: Span) : Expr {
    fun bytes(): ByteArray = value.toByteArray(Charsets.ISO_8859_1)
}

data class Var(val name: String, override val pos: Pos, override val span: Span) : Expr

data class Neg(val operand: Expr, override val pos: Pos, override val span: Span) : Expr

data class Binary(val op: ArithOp, val left: Expr, val right: Expr, override val pos: Pos, override val span: Span) : Expr

data class Len(val operand: Expr, override val pos: Pos, override val span: Span) : Expr

/** `s[i]`: reads one byte of str variable [target] as an int 0..255. */
data class Index(val target: String, val index: Expr, override val pos: Pos, override val span: Span) : Expr

data class CallExpr(val name: String, val args: List<Expr>, override val pos: Pos, override val span: Span) : Expr

enum class ArithOp(val symbol: String) {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/");

    override fun toString() = symbol

    companion object {
        fun fromSymbol(s: String): ArithOp = entries.first { it.symbol == s }
    }
}
