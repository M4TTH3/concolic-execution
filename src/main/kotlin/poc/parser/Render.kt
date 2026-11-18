package poc.parser

/** Renders AST fragments back to POC source, used for branch-site descriptions and reports. */
fun Expr.render(): String = when (this) {
    is IntLit -> value.toString()
    is StrLit -> quotePoc(value)
    is Var -> name
    is Neg -> "-" + operand.renderOperand()
    is Binary -> "${left.renderOperand()} $op ${right.renderOperand()}"
    is Len -> "len(${operand.render()})"
    is Index -> "$target[${index.render()}]"
    is CallExpr -> "$name(${args.joinToString(", ") { it.render() }})"
}

private fun Expr.renderOperand(): String = if (this is Binary) "(${render()})" else render()

fun Cond.render(): String = when (this) {
    is Compare -> "${left.render()} $op ${right.render()}"
    is Not -> "not ${cond.renderOperand()}"
    is And -> "${left.renderOperand()} and ${right.renderOperand()}"
    is Or -> "${left.renderOperand()} or ${right.renderOperand()}"
}

private fun Cond.renderOperand(): String = if (this is Compare) render() else "(${render()})"

/** Quotes a byte string (one char per byte) as a POC string literal. */
fun quotePoc(s: String): String = buildString {
    append('"')
    for (c in s) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\t' -> append("\\t")
            c.code in 0x20..0x7e -> append(c)
            else -> append("\\x%02x".format(c.code))
        }
    }
    append('"')
}
