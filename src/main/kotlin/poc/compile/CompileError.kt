package poc.compile

import poc.parser.Pos

data class CompileError(val pos: Pos, val message: String) {
    override fun toString() = "$pos: $message"
}

class CompileException(val errors: List<CompileError>) : Exception(errors.joinToString("\n"))
