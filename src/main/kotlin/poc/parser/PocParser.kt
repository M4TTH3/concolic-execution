package poc.parser

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.TerminalNode
import poc.parser.antlr.PocGrammarLexer
import poc.parser.antlr.PocGrammarParser
import poc.parser.antlr.PocGrammarParser.*

class PocSyntaxError(val pos: Pos, val detail: String) : Exception("$pos: $detail")

/** Parses POC source text into an AST. Throws [PocSyntaxError] on the first lexical or syntax error. */
object PocParser {
    fun parse(source: String): Program {
        val lexer = PocGrammarLexer(CharStreams.fromString(source))
        lexer.removeErrorListeners()
        lexer.addErrorListener(ThrowingErrorListener)
        val parser = PocGrammarParser(CommonTokenStream(lexer))
        parser.removeErrorListeners()
        parser.addErrorListener(ThrowingErrorListener)
        return AstBuilder.program(parser.program())
    }

    private object ThrowingErrorListener : BaseErrorListener() {
        override fun syntaxError(
            recognizer: Recognizer<*, *>?,
            offendingSymbol: Any?,
            line: Int,
            charPositionInLine: Int,
            msg: String?,
            e: RecognitionException?,
        ) {
            throw PocSyntaxError(Pos(line, charPositionInLine + 1), msg ?: "syntax error")
        }
    }
}

/** Converts ANTLR parse-tree contexts into [poc.parser] AST nodes. */
private object AstBuilder {
    fun program(ctx: ProgramContext): Program =
        Program(ctx.children.orEmpty().mapNotNull { child ->
            when (child) {
                is FunDeclContext -> funDecl(child)
                is MainDeclContext -> MainDecl(child.ID().text, pos(child.ID().symbol), block(child.block()), pos(child))
                else -> null // EOF
            }
        })

    private fun funDecl(ctx: FunDeclContext) = FunDecl(
        name = ctx.name.text,
        params = ctx.param().map { Param(type(it.type()), it.ID().text, pos(it.ID().symbol)) },
        returnType = ctx.retType?.let(::type),
        body = block(ctx.block()),
        pos = pos(ctx.name),
    )

    private fun type(ctx: TypeContext): PocType = when (ctx.text) {
        "int" -> PocType.INT
        "str" -> PocType.STR
        else -> error("unknown type ${ctx.text}")
    }

    private fun block(ctx: BlockContext) = Block(ctx.stmt().map(::stmt), pos(ctx), span(ctx))

    private fun stmt(ctx: StmtContext): Stmt = when (ctx) {
        is VarDeclStmtContext -> VarDecl(type(ctx.type()), ctx.ID().text, expr(ctx.expr()), pos(ctx.ID().symbol), stmtSpan(ctx))
        is AssignStmtContext -> Assign(ctx.ID().text, expr(ctx.expr()), pos(ctx), stmtSpan(ctx))
        is StoreStmtContext -> CharAssign(ctx.ID().text, expr(ctx.index), expr(ctx.value), pos(ctx), stmtSpan(ctx))
        is PrintStmtContext -> Print(expr(ctx.expr()), pos(ctx), stmtSpan(ctx))
        is CallStmtContext -> call(ctx.call()).let { CallStmt(it, it.pos, it.span) }
        is ReturnStmtContext -> ReturnStmt(ctx.expr()?.let(::expr), pos(ctx), stmtSpan(ctx))
        is IfStmtContext -> {
            val conds = ctx.cond()
            val blocks = ctx.block()
            If(
                branches = conds.indices.map { cond(conds[it]) to block(blocks[it]) },
                elseBlock = if (blocks.size > conds.size) block(blocks.last()) else null,
                pos = pos(ctx),
                span = span(ctx),
            )
        }
        else -> error("unhandled statement ${ctx.javaClass.simpleName}")
    }

    private fun cond(ctx: CondContext): Cond = when (ctx) {
        is NotCondContext -> Not(cond(ctx.cond()), pos(ctx), span(ctx))
        is AndCondContext -> And(cond(ctx.cond(0)), cond(ctx.cond(1)), pos(ctx), span(ctx))
        is OrCondContext -> Or(cond(ctx.cond(0)), cond(ctx.cond(1)), pos(ctx), span(ctx))
        is ParenCondContext -> cond(ctx.cond())
        is CmpCondContext -> Compare(CmpOp.fromSymbol(ctx.op.text), expr(ctx.expr(0)), expr(ctx.expr(1)), pos(ctx), span(ctx))
        else -> error("unhandled condition ${ctx.javaClass.simpleName}")
    }

    private fun expr(ctx: ExprContext): Expr = when (ctx) {
        is NegExprContext -> when (val operand = expr(ctx.expr())) {
            // Fold so that -2147483648 is representable.
            is IntLit -> IntLit(-operand.value, pos(ctx), span(ctx))
            else -> Neg(operand, pos(ctx), span(ctx))
        }
        is MulExprContext -> Binary(ArithOp.fromSymbol(ctx.op.text), expr(ctx.expr(0)), expr(ctx.expr(1)), pos(ctx.op), span(ctx))
        is AddExprContext -> Binary(ArithOp.fromSymbol(ctx.op.text), expr(ctx.expr(0)), expr(ctx.expr(1)), pos(ctx.op), span(ctx))
        is LenExprContext -> Len(expr(ctx.expr()), pos(ctx), span(ctx))
        is CallExprContext -> call(ctx.call())
        is IndexExprContext -> Index(ctx.ID().text, expr(ctx.expr()), pos(ctx), span(ctx))
        is VarExprContext -> Var(ctx.ID().text, pos(ctx), span(ctx))
        is IntExprContext -> IntLit(
            ctx.INT().text.toLongOrNull() ?: throw PocSyntaxError(pos(ctx), "integer literal too large: ${ctx.INT().text}"),
            pos(ctx),
            span(ctx),
        )
        is StrExprContext -> StrLit(unescape(ctx.STRING().symbol), pos(ctx), span(ctx))
        is ParenExprContext -> expr(ctx.expr())
        else -> error("unhandled expression ${ctx.javaClass.simpleName}")
    }

    private fun call(ctx: CallContext) = CallExpr(ctx.ID().text, ctx.expr().map(::expr), pos(ctx), span(ctx))

    private fun unescape(token: Token): String {
        val raw = token.text.substring(1, token.text.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\') {
                when (val e = raw[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    'x' -> {
                        out.append(raw.substring(i + 2, i + 4).toInt(16).toChar())
                        i += 2
                    }
                    else -> error("lexer accepted unknown escape \\$e")
                }
                i += 2
            } else {
                if (c.code > 0xFF) {
                    throw PocSyntaxError(pos(token), "string literals may only contain bytes 0..255 (found '$c')")
                }
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun pos(token: Token) = Pos(token.line, token.charPositionInLine + 1)

    private fun pos(ctx: ParserRuleContext) = pos(ctx.start)

    /** Exclusive end position of [token] (single-line tokens only, which is all POC has). */
    private fun end(token: Token) = Pos(token.line, token.charPositionInLine + token.text.length + 1)

    private fun span(ctx: ParserRuleContext) = Span(pos(ctx.start), end(ctx.stop))

    /** A statement's span without its optional trailing `;`. */
    private fun stmtSpan(ctx: ParserRuleContext): Span {
        val last = ctx.getChild(ctx.childCount - 1)
        if (last !is TerminalNode || last.symbol.text != ";") return span(ctx)
        val beforeSemi = ctx.getChild(ctx.childCount - 2)
        val stop = if (beforeSemi is TerminalNode) beforeSemi.symbol else (beforeSemi as ParserRuleContext).stop
        return Span(pos(ctx.start), end(stop))
    }
}
