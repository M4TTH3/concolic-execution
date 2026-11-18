package poc.core

import io.github.cvc5.Context
import io.github.cvc5.Solver
import io.github.cvc5.Term
import io.github.cvc5.TermManager
import poc.bytecode.Branch
import poc.bytecode.BytecodeProgram
import poc.bytecode.PathConstraint
import poc.bytecode.SymbolicInputs
import poc.compile.DEFAULT_MAX_STR_LEN
import poc.compile.PocCompiler

/** A negated path constraint that the solver turned into a new input. */
class Flip(
    /** Index of the negated constraint in the parent run's path. */
    val index: Int,
    val constraint: PathConstraint,
    val input: Input,
    /** The path prefix the new input should follow: the parent's first [index] branches, then the flipped one. */
    val target: List<Branch>,
) {
    val site get() = constraint.site

    /** Which way the parent run went at this site. The new input should go the other way. */
    val from: Boolean get() = constraint.taken

    override fun toString() = "flip[$index] ${site.describe()} from ${if (from) "true" else "false"} -> $input"
}

/** One BFS step: the run of the oldest pending seed, and the new seeds (flips) it produced. */
class Step(
    /** 1-based run number. */
    val number: Int,
    val run: ExecutionResult,
    val flips: List<Flip>,
    /** The flip that produced this run's input, or null for the initial seed. */
    val producedBy: Flip?,
)

class SolverStats {
    var queries = 0
    var sat = 0
    var unsat = 0
    var unknown = 0

    override fun toString() = "$queries solver queries ($sat sat, $unsat unsat, $unknown unknown)"
}

/**
 * A run did not follow the path prefix its input was solved for. POC's symbolic model is meant to
 * be exact (no concretization), so this always means some instruction's concrete and symbolic
 * semantics disagree: a bug in the tester, not in the program under test.
 */
class DivergenceError(val flip: Flip, val run: ExecutionResult, message: String) : IllegalStateException(message)

/**
 * Compiles a POC program and explores its paths concolically, breadth first.
 *
 * Every run's path constraints are flipped shallowest first, and each satisfiable flip is
 * appended to a FIFO queue of seeds, so the next input to run is always the oldest pending one.
 * Path prefixes that were already explored or already attempted are never solved again.
 * The order is deterministic: same source, options and cvc5 version give the same runs and inputs.
 *
 * Owns native cvc5 objects: [close] it (or use `use {}`). [Context.deletePointers] frees all cvc5
 * objects in the process, so testers must not be used concurrently.
 */
class ConcolicTester(
    source: String,
    val maxLen: Int = DEFAULT_MAX_STR_LEN,
    seed: String = "",
    maxSteps: Int = 10_000,
    maxCallDepth: Int = 200,
    /** Receives a readable dump of every solver query (for `--dump-queries`). */
    private val queryLog: ((String) -> Unit)? = null,
) : AutoCloseable {
    // Compile first: a compile error must not leave native objects behind.
    val program: BytecodeProgram = PocCompiler.compile(source, maxLen)

    private val tm = TermManager()
    private val solver = Solver(tm).apply {
        setLogic("QF_BV")
        setOption("produce-models", "true")
        setOption("incremental", "true")
        setOption("seed", "0")
    }
    private val inputs = SymbolicInputs(tm, maxLen)
    private val engine = ConcolicEngine(program, tm, inputs, maxSteps, maxCallDepth)

    private val queue = ArrayDeque<Pair<Input, Flip?>>()

    /** Every prefix of every path that has actually run. */
    private val explored = HashSet<List<Branch>>()

    /** Every flip target that has been handed to the solver. */
    private val attempted = HashSet<List<Branch>>()

    val steps = mutableListOf<Step>()
    val stats = SolverStats()

    init {
        solver.assertFormula(inputs.domain)
        queue.addLast(Input(seed) to null)
    }

    /** Inputs waiting to run, oldest first. */
    val pending: List<Input> get() = queue.map { it.first }

    /** Runs [input] once without touching the search state. */
    fun execute(input: Input): ExecutionResult = engine.run(input)

    /**
     * Runs the oldest pending seed and enqueues its flips; null when every reachable path has been explored.
     * Throws [DivergenceError] if the run does not follow the prefix its input was solved for.
     */
    fun step(): Step? {
        val (input, producedBy) = queue.removeFirstOrNull() ?: return null
        val run = engine.run(input)
        val signature = run.signature

        if (producedBy != null && !signature.startsWith(producedBy.target)) {
            throw DivergenceError(producedBy, run, divergenceMessage(producedBy, run))
        }
        for (k in 1..signature.size) explored += signature.subList(0, k).toList()

        val flips = mutableListOf<Flip>()
        for (j in run.constraints.indices) {
            val target = signature.subList(0, j) + signature[j].flipped()
            if (target in explored || !attempted.add(target)) continue
            solve(run, j, target)?.let { flip ->
                flips += flip
                queue.addLast(flip.input to flip)
            }
        }
        return Step(steps.size + 1, run, flips, producedBy).also { steps += it }
    }

    private fun divergenceMessage(flip: Flip, run: ExecutionResult): String = buildString {
        val actual = run.signature
        val k = flip.target.indices.first { it >= actual.size || actual[it] != flip.target[it] }
        fun describe(b: Branch?) = b?.let { "$it (${program.sites[it.siteId].describe()})" } ?: "nothing (the path ended)"

        appendLine("run ${steps.size + 1} diverged: input ${run.input} was solved by $flip")
        appendLine("  expected prefix: ${flip.target.joinToString(" ")}")
        appendLine("  actual path:     ${actual.joinToString(" ")}  -> ${run.outcome}")
        appendLine("  first difference at index $k: expected ${describe(flip.target[k])}, got ${describe(actual.getOrNull(k))}")
        appendLine("The concrete and symbolic semantics of some instruction disagree; this is a tester bug.")
        appendLine("Path constraints of the run:")
        run.constraints.forEach { appendLine("  $it") }
    }

    /** Steps until exhausted or [maxRuns] runs have happened in total. */
    fun explore(maxRuns: Int = 1000): ExplorationReport {
        while (steps.size < maxRuns) step() ?: break
        return ExplorationReport(program, steps.toList(), stats, exhausted = queue.isEmpty())
    }

    /** Solves `c0 ∧ ... ∧ c(j-1) ∧ ¬cj` and turns a model into a [Flip]. */
    private fun solve(run: ExecutionResult, j: Int, target: List<Branch>): Flip? {
        val constraint = run.constraints[j]
        // Not `list + term`: cvc5's Term is Iterable over its children, so `+` would append the children.
        val assumptions: List<Term> = buildList {
            run.constraints.take(j).forEach { add(it.term) }
            add(constraint.term.notTerm())
        }
        stats.queries++
        val result = solver.checkSatAssuming(assumptions.toTypedArray())

        val flip = when {
            result.isSat -> {
                stats.sat++
                Flip(j, constraint, modelInput(), target)
            }
            result.isUnsat -> {
                stats.unsat++
                null
            }
            else -> {
                stats.unknown++
                null
            }
        }
        queryLog?.invoke(
            buildString {
                appendLine("query #${stats.queries}: run ${steps.size + 1}, flip constraint $j ${constraint.site.describe()}")
                assumptions.forEachIndexed { i, t -> appendLine("  ${if (i == j) "negate" else "assume"} $t") }
                append("  => ${if (flip != null) "sat, new input ${flip.input}" else result.toString()}")
            },
        )
        return flip
    }

    private fun modelInput(): Input {
        val len = solver.getValue(inputs.len).getBitVectorValue(10).toLong().toInt()
        return Input.of(ByteArray(len) { solver.getValue(inputs.bytes[it]).getBitVectorValue(10).toInt().toByte() })
    }

    override fun close() {
        Context.deletePointers()
    }
}

private fun <T> List<T>.startsWith(prefix: List<T>) = size >= prefix.size && subList(0, prefix.size) == prefix
