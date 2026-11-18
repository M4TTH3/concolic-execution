package poc.core

import io.github.cvc5.TermManager
import poc.bytecode.*
import poc.parser.quotePoc

/** MAIN's `cmd` input. Each char is one byte (0..255). */
data class Input(val cmd: String) {
    init {
        require(cmd.all { it.code <= 0xFF }) { "input chars must be bytes 0..255" }
    }

    fun bytes(): ByteArray = cmd.toByteArray(Charsets.ISO_8859_1)

    override fun toString() = quotePoc(cmd)

    companion object {
        fun of(bytes: ByteArray) = Input(String(bytes, Charsets.ISO_8859_1))
    }
}

sealed interface Outcome {
    data object Completed : Outcome {
        override fun toString() = "completed"
    }

    data class RuntimeError(val site: BranchSite, val message: String) : Outcome {
        override fun toString() = "runtime error at ${site.function}:${site.pos}: $message"
    }

    data class LimitExceeded(val message: String) : Outcome {
        override fun toString() = "limit exceeded: $message"
    }
}

class ExecutionResult(
    val input: Input,
    val output: List<String>,
    val outcome: Outcome,
    /** Symbolic branch decisions in execution order. Only these can be flipped. */
    val constraints: List<PathConstraint>,
    /** Every (site, outcome) reached, concrete or symbolic. */
    val covered: Set<Branch>,
    val steps: Int,
) {
    val signature: List<Branch> get() = constraints.map { it.branch }
}

/** Runs one input through the program, concretely and symbolically at once. */
class ConcolicEngine(
    val program: BytecodeProgram,
    private val tm: TermManager,
    private val inputs: SymbolicInputs,
    private val maxSteps: Int = 10_000,
    private val maxCallDepth: Int = 200,
) {
    fun run(input: Input): ExecutionResult {
        val bytes = input.bytes()
        require(bytes.size <= program.maxStrLen) { "input $input is longer than the max str length ${program.maxStrLen}" }

        val conc = ConcreteState(program, maxCallDepth)
        val sym = SymbolicState(tm, program)
        conc.enterMain(bytes)
        sym.enterMain(inputs)

        var steps = 0
        val outcome = try {
            while (!conc.halted) {
                if (steps >= maxSteps) throw LimitExceeded("step limit of $maxSteps exceeded")
                val frame = conc.frame
                val instruction = frame.fn.code[frame.pc]
                frame.pc++
                steps++
                instruction.execute(conc, sym)
            }
            Outcome.Completed
        } catch (e: PocRuntimeError) {
            Outcome.RuntimeError(e.site, e.message ?: "runtime error")
        } catch (e: LimitExceeded) {
            Outcome.LimitExceeded(e.message ?: "limit exceeded")
        }
        return ExecutionResult(input, conc.output.toList(), outcome, sym.pathConstraints.toList(), conc.covered.toSet(), steps)
    }
}
