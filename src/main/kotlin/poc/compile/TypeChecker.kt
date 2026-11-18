package poc.compile

import poc.parser.*

/**
 * Validates a parsed [Program]: MAIN placement, program-wide unique names, block scoping,
 * types, calls and returns. Collects every error it finds (sorted by position) instead of stopping at the first.
 */
class TypeChecker(private val maxStrLen: Int) {
    private val errors = mutableListOf<CompileError>()
    private val functions = LinkedHashMap<String, FunDecl>()

    /** Every name declared anywhere so far, with where it was first declared. */
    private val declared = HashMap<String, Pos>()

    /** Innermost scope last. */
    private val scopes = ArrayDeque<MutableMap<String, PocType>>()

    private var currentFunction = ""
    private var currentReturn: PocType? = null

    fun check(program: Program): List<CompileError> {
        checkMainPlacement(program)
        for (f in program.functions) {
            declare(f.name, f.pos)
            functions.putIfAbsent(f.name, f)
        }
        program.decls.forEach {
            when (it) {
                is FunDecl -> checkFunction(it)
                is MainDecl -> checkMain(it)
            }
        }
        return errors.sortedWith(compareBy({ it.pos.line }, { it.pos.col }))
    }

    private fun checkMainPlacement(program: Program) {
        val mains = program.mains
        if (mains.isEmpty()) {
            error(Pos(1, 1), "program must define MAIN")
            return
        }
        mains.drop(1).forEach { error(it.pos, "MAIN is defined more than once") }
        val firstMain = program.decls.indexOf(mains.first())
        program.decls.drop(firstMain + 1).firstOrNull { it is FunDecl }?.let {
            error(it.pos, "MAIN must be the last declaration in the program")
        }
    }

    private fun checkFunction(f: FunDecl) {
        currentFunction = f.name
        currentReturn = f.returnType
        withScope {
            f.params.forEach { declareVar(it.name, it.type, it.pos) }
            val returns = checkBlock(f.body)
            if (f.returnType != null && !returns) {
                error(f.pos, "function '${f.name}' must return ${f.returnType.article} on every path")
            }
        }
    }

    private fun checkMain(m: MainDecl) {
        currentFunction = "MAIN"
        currentReturn = null
        withScope {
            declareVar(m.param, PocType.STR, m.paramPos)
            checkBlock(m.body)
        }
    }

    /** Returns true if the block definitely executes a `return`. */
    private fun checkBlock(block: Block): Boolean = withScope {
        var returns = false
        for (stmt in block.stmts) {
            if (returns) {
                error(stmt.pos, "unreachable statement after return")
                break
            }
            returns = checkStmt(stmt)
        }
        returns
    }

    /** Returns true if the statement definitely executes a `return`. */
    private fun checkStmt(s: Stmt): Boolean {
        when (s) {
            is VarDecl -> {
                expect(s.init, s.type) { "cannot initialise ${s.type} '${s.name}' with ${it.article}" }
                declareVar(s.name, s.type, s.pos)
            }
            is Assign -> when (lookupVar(s.name, s.pos)) {
                PocType.STR -> error(
                    s.pos,
                    "cannot reassign str '${s.name}': a str's size is fixed, replace characters with ${s.name}[i] = ... instead",
                )
                PocType.INT -> expect(s.value, PocType.INT) { "cannot assign ${it.article} to int '${s.name}'" }
                null -> valueType(s.value)
            }
            is CharAssign -> {
                if (lookupVar(s.target, s.pos) == PocType.INT) {
                    error(s.pos, "'${s.target}' is an int; only a str can be indexed")
                }
                expect(s.index, PocType.INT) { "a str index must be an int, found $it" }
                expect(s.value, PocType.INT) { "a character value must be an int, found $it" }
            }
            is Print -> valueType(s.value)
            is CallStmt -> checkCall(s.call)
            is ReturnStmt -> {
                checkReturn(s)
                return true
            }
            is If -> {
                s.branches.forEach { checkCond(it.first) }
                val branchesReturn = s.branches.map { checkBlock(it.second) }
                val elseReturns = s.elseBlock?.let(::checkBlock) ?: false
                return elseReturns && branchesReturn.all { it }
            }
        }
        return false
    }

    private fun checkReturn(s: ReturnStmt) {
        val expected = currentReturn
        when {
            s.value == null && expected != null -> error(s.pos, "function '$currentFunction' must return ${expected.article}")
            s.value != null && expected == null -> {
                val what = if (currentFunction == "MAIN") "MAIN" else "function '$currentFunction' has no return type, so it"
                error(s.pos, "$what cannot return a value")
                valueType(s.value)
            }
            s.value != null && expected != null ->
                expect(s.value, expected) { "function '$currentFunction' returns $expected, not $it" }
        }
    }

    private fun checkCond(c: Cond) {
        when (c) {
            is Not -> checkCond(c.cond)
            is And -> { checkCond(c.left); checkCond(c.right) }
            is Or -> { checkCond(c.left); checkCond(c.right) }
            is Compare -> {
                val l = valueType(c.left)
                val r = valueType(c.right)
                if (l != null && r != null && l != r) {
                    error(c.pos, "cannot compare $l with $r: both sides of ${c.op} must have the same type")
                }
            }
        }
    }

    /** The type of a value-producing expression, or null if it is ill-typed (an error has been reported). */
    private fun valueType(e: Expr): PocType? = when (e) {
        is IntLit -> {
            if (e.value !in Int.MIN_VALUE..Int.MAX_VALUE) error(e.pos, "integer literal ${e.value} does not fit in 32 bits")
            PocType.INT
        }
        is StrLit -> {
            val size = e.bytes().size
            if (size > maxStrLen) error(e.pos, "string literal is $size bytes but the max str length is $maxStrLen")
            PocType.STR
        }
        is Var -> lookupVar(e.name, e.pos)
        is Neg -> {
            expect(e.operand, PocType.INT) { "unary - needs an int, found $it" }
            PocType.INT
        }
        is Binary -> {
            expect(e.left, PocType.INT) { "operator ${e.op} needs int operands, found $it" }
            expect(e.right, PocType.INT) { "operator ${e.op} needs int operands, found $it" }
            PocType.INT
        }
        is Len -> {
            expect(e.operand, PocType.STR) { "len() needs a str, found $it" }
            PocType.INT
        }
        is Index -> {
            if (lookupVar(e.target, e.pos) == PocType.INT) error(e.pos, "'${e.target}' is an int; only a str can be indexed")
            expect(e.index, PocType.INT) { "a str index must be an int, found $it" }
            PocType.INT
        }
        is CallExpr -> checkCall(e)?.let { f ->
            if (f.returnType == null) error(e.pos, "function '${f.name}' does not return a value")
            f.returnType
        }
    }

    private fun checkCall(c: CallExpr): FunDecl? {
        val f = functions[c.name]
        if (f == null) {
            error(c.pos, if (c.name in declared) "'${c.name}' is not a function" else "unknown function '${c.name}'")
            c.args.forEach { valueType(it) }
            return null
        }
        if (c.args.size != f.params.size) {
            error(c.pos, "function '${f.name}' takes ${f.params.size} argument(s) but was given ${c.args.size}")
        }
        c.args.forEachIndexed { i, arg ->
            val p = f.params.getOrNull(i)
            if (p == null) valueType(arg)
            else expect(arg, p.type) { "argument '${p.name}' of '${f.name}' must be ${p.type.article}, found ${it.article}" }
        }
        return f
    }

    private inline fun expect(e: Expr, type: PocType, message: (PocType) -> String) {
        val actual = valueType(e)
        if (actual != null && actual != type) error(e.pos, message(actual))
    }

    private fun lookupVar(name: String, pos: Pos): PocType? {
        scopes.asReversed().firstNotNullOfOrNull { it[name] }?.let { return it }
        error(
            pos,
            when (name) {
                in functions -> "'$name' is a function, not a variable"
                in declared -> "'$name' is not in scope here (declared at ${declared[name]})"
                else -> "unknown variable '$name'"
            },
        )
        return null
    }

    private fun declareVar(name: String, type: PocType, pos: Pos) {
        declare(name, pos)
        scopes.last()[name] = type
    }

    private fun declare(name: String, pos: Pos) {
        val previous = declared.putIfAbsent(name, pos)
        if (previous != null) error(pos, "'$name' is already declared at $previous (names must be unique in the program)")
    }

    private inline fun <T> withScope(body: () -> T): T {
        scopes.addLast(mutableMapOf())
        try {
            return body()
        } finally {
            scopes.removeLast()
        }
    }

    private fun error(pos: Pos, message: String) {
        errors += CompileError(pos, message)
    }
}
