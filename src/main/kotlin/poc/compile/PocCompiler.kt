package poc.compile

import poc.bytecode.*
import poc.parser.*

const val DEFAULT_MAX_STR_LEN = 16

/** Front door: source text -> parse -> type-check -> [BytecodeGen] -> [BytecodeProgram]. Throws [CompileException]. */
object PocCompiler {
    fun compile(source: String, maxStrLen: Int = DEFAULT_MAX_STR_LEN): BytecodeProgram {
        require(maxStrLen >= 1) { "maxStrLen must be at least 1" }
        val program = try {
            PocParser.parse(source)
        } catch (e: PocSyntaxError) {
            throw CompileException(listOf(CompileError(e.pos, "syntax error: ${e.detail}")))
        }
        val errors = TypeChecker(maxStrLen).check(program)
        if (errors.isNotEmpty()) throw CompileException(errors)
        return BytecodeGen(program, maxStrLen).generate()
    }
}

/**
 * Generates bytecode for a type-checked program. It never reports errors: the [TypeChecker] has
 * already guaranteed every name, type and return it relies on. Functions keep declaration order
 * and MAIN comes last; branch-site ids are handed out in that same (source) order.
 */
private class BytecodeGen(private val program: Program, private val maxStrLen: Int) {
    private val sites = mutableListOf<BranchSite>()
    private val functions = program.functions
    private val functionIndex = functions.withIndex().associate { (i, f) -> f.name to i }

    fun generate(): BytecodeProgram {
        val generated = functions.mapIndexed { i, f -> FunctionGen(f.name, i).genFunction(f) }
        val main = FunctionGen("MAIN", functions.size).genMain(program.mains.single())
        return BytecodeProgram(generated + main, functions.size, maxStrLen, sites.toList())
    }

    private fun sizeOf(type: PocType) = when (type) {
        PocType.INT -> INT_SIZE
        PocType.STR -> STR_HEADER_SIZE + maxStrLen
    }

    private inner class FunctionGen(private val name: String, private val index: Int) {
        private var frameSize = 0
        private val vars = HashMap<String, Ref>()
        private val varTypes = HashMap<String, PocType>()
        private val slotNames = LinkedHashMap<Ref, String>()
        private val temps = HashSet<Ref>()
        private val code = mutableListOf<Instruction>()

        /** Source span of each instruction in [code] (same index), for the replay viewer. */
        private val spans = mutableListOf<Span>()
        private lateinit var currentSpan: Span

        /** Label id -> pc, or -1 while unplaced. Jump instructions hold label ids until [finish]. */
        private val labelPcs = mutableListOf<Int>()

        fun genFunction(f: FunDecl): FunctionCode {
            currentSpan = f.body.span
            val params = f.params.map { declareVar(it.name, it.type) }
            genBlock(f.body)
            return finish(f.body, params, f.returnType?.let(::sizeOf))
        }

        fun genMain(m: MainDecl): FunctionCode {
            currentSpan = m.body.span
            val cmd = declareVar(m.param, PocType.STR)
            genBlock(m.body)
            return finish(m.body, listOf(cmd), returnSize = null)
        }

        private fun finish(body: Block, params: List<Ref>, returnSize: Int?): FunctionCode {
            // Unreachable in functions with a return type (the checker guarantees every path returns).
            withSpan(body.closingBrace) { emit(Return(null)) }
            val resolved = code.map { if (it is JumpInstruction) it.retarget(labelPcs[it.target]) else it }
            return FunctionCode(name, index, params, returnSize, frameSize, resolved, slotNames, spans.toList(), temps.toSet())
        }

        // ---- frame layout ----

        private fun alloc(type: PocType, slotName: String): Ref {
            val ref = Ref(frameSize, sizeOf(type))
            frameSize += ref.size
            slotNames[ref] = slotName
            return ref
        }

        private fun temp(type: PocType): Ref = alloc(type, "t${temps.size}").also { temps += it }

        private fun declareVar(varName: String, type: PocType): Ref {
            val ref = alloc(type, varName)
            vars[varName] = ref
            varTypes[varName] = type
            return ref
        }

        // ---- emission ----

        private fun emit(ins: Instruction) {
            code += ins
            spans += currentSpan
        }

        /** Instructions emitted inside [body] are attributed to [span] (innermost wins). */
        private inline fun <T> withSpan(span: Span, body: () -> T): T {
            val outer = currentSpan
            currentSpan = span
            try {
                return body()
            } finally {
                currentSpan = outer
            }
        }

        private fun newLabel(): Int = labelPcs.size.also { labelPcs += -1 }

        private fun place(label: Int) {
            // Drop a jump to the very next instruction. Labels already placed right after it
            // (pointing past the end) must move back with it.
            val last = code.lastOrNull()
            if (last is Jump && last.target == label) {
                code.removeLast()
                spans.removeLast()
                labelPcs.replaceAll { pc -> if (pc == code.size + 1) code.size else pc }
            }
            labelPcs[label] = code.size
        }

        private fun site(kind: SiteKind, pos: Pos, span: Span, description: String): BranchSite =
            BranchSite(sites.size, name, pos, kind, description, span).also { sites += it }

        // ---- statements ----

        private fun genBlock(block: Block) = block.stmts.forEach(::genStmt)

        private fun genStmt(s: Stmt): Unit = withSpan(s.span) {
            when (s) {
                is VarDecl -> {
                    val value = genExpr(s.init)
                    if (value in temps) {
                        // A fresh temp is used by nothing else: let the variable take over its slot.
                        temps -= value
                        slotNames[value] = s.name
                        vars[s.name] = value
                        varTypes[s.name] = s.type
                    } else {
                        emit(Copy(declareVar(s.name, s.type), value))
                    }
                }
                is Assign -> emit(Copy(vars.getValue(s.name), genExpr(s.value)))
                is CharAssign -> {
                    val target = vars.getValue(s.target)
                    val index = genExpr(s.index)
                    val value = genExpr(s.value)
                    emit(StoreByte(target, index, value, site(SiteKind.BOUNDS, s.pos, s.span, "${s.target}[${s.index.render()}] in bounds")))
                }
                is Print -> {
                    val value = genExpr(s.value)
                    emit(if (typeOf(s.value) == PocType.INT) PrintInt(value) else PrintStr(value))
                }
                is CallStmt -> genCall(s.call, keepResult = false)
                is ReturnStmt -> emit(Return(s.value?.let(::genExpr)))
                is If -> genIf(s)
            }
        }

        private fun genIf(s: If) {
            val end = newLabel()
            for ((cond, block) in s.branches) {
                val then = newLabel()
                val next = newLabel()
                genCond(cond, then, next)
                place(then)
                genBlock(block)
                withSpan(block.closingBrace) { emit(Jump(end)) }
                place(next)
            }
            s.elseBlock?.let(::genBlock)
            place(end)
        }

        /** Short-circuit jumps: control reaches [ifTrue] or [ifFalse]. Every comparison is its own branch site. */
        private fun genCond(c: Cond, ifTrue: Int, ifFalse: Int) {
            when (c) {
                is Not -> genCond(c.cond, ifFalse, ifTrue)
                is And -> {
                    val mid = newLabel()
                    genCond(c.left, mid, ifFalse)
                    place(mid)
                    genCond(c.right, ifTrue, ifFalse)
                }
                is Or -> {
                    val mid = newLabel()
                    genCond(c.left, ifTrue, mid)
                    place(mid)
                    genCond(c.right, ifTrue, ifFalse)
                }
                is Compare -> withSpan(c.span) {
                    val a = genExpr(c.left)
                    val b = genExpr(c.right)
                    val site = site(SiteKind.IF, c.pos, c.span, c.render())
                    emit(
                        if (typeOf(c.left) == PocType.INT) IntCmpJump(c.op, a, b, ifTrue, site)
                        else StrCmpJump(c.op, a, b, ifTrue, site),
                    )
                    emit(Jump(ifFalse))
                }
            }
        }

        // ---- expressions ----

        /** Emits code for [e] and returns the Ref holding its value: a variable's own slot, or a fresh temp. */
        private fun genExpr(e: Expr): Ref = withSpan(e.span) { genExprIn(e) }

        private fun genExprIn(e: Expr): Ref = when (e) {
            is IntLit -> temp(PocType.INT).also { emit(ConstInt(it, e.value.toInt())) }
            is StrLit -> temp(PocType.STR).also { emit(ConstStr(it, e.value)) }
            is Var -> vars.getValue(e.name)
            is Neg -> {
                val a = genExpr(e.operand)
                temp(PocType.INT).also { emit(IntNeg(it, a)) }
            }
            is Binary -> {
                val a = genExpr(e.left)
                val b = genExpr(e.right)
                val dst = temp(PocType.INT)
                emit(
                    when (e.op) {
                        ArithOp.ADD -> IntAdd(dst, a, b)
                        ArithOp.SUB -> IntSub(dst, a, b)
                        ArithOp.MUL -> IntMul(dst, a, b)
                        ArithOp.DIV -> IntDiv(dst, a, b, site(SiteKind.DIV_ZERO, e.pos, e.right.span, "${e.right.render()} != 0"))
                    },
                )
                dst
            }
            is Len -> {
                val s = genExpr(e.operand)
                temp(PocType.INT).also { emit(StrLen(it, s)) }
            }
            is Index -> {
                val s = vars.getValue(e.target)
                val i = genExpr(e.index)
                val dst = temp(PocType.INT)
                emit(LoadByte(dst, s, i, site(SiteKind.BOUNDS, e.pos, e.span, "${e.target}[${e.index.render()}] in bounds")))
                dst
            }
            is CallExpr -> genCall(e, keepResult = true)!!
        }

        /** Emits the call; when [keepResult], allocates a temp in this frame that the callee's Return fills. */
        private fun genCall(c: CallExpr, keepResult: Boolean): Ref? {
            val fn = functionIndex.getValue(c.name)
            val args = c.args.map(::genExpr)
            val ret = if (keepResult) functions[fn].returnType?.let(::temp) else null
            emit(Call(fn, c.name, args, ret))
            return ret
        }

        private fun typeOf(e: Expr): PocType = when (e) {
            is IntLit, is Neg, is Binary, is Len, is Index -> PocType.INT
            is StrLit -> PocType.STR
            is Var -> varTypes.getValue(e.name)
            is CallExpr -> functions[functionIndex.getValue(e.name)].returnType!!
        }
    }
}
