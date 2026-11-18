package poc.core

import poc.bytecode.BranchSite
import poc.bytecode.BytecodeProgram

class ExplorationReport(
    val program: BytecodeProgram,
    val steps: List<Step>,
    val stats: SolverStats,
    /** True if every reachable path was explored, false if the run budget ran out first. */
    val exhausted: Boolean,
) {
    val runs: List<ExecutionResult> get() = steps.map { it.run }

    /** Runs that ended in a runtime error (division by zero, index out of bounds). */
    val errors: List<ExecutionResult> get() = runs.filter { it.outcome is Outcome.RuntimeError }

    /** Every printed line across all runs. */
    val allOutput: Set<String> get() = runs.flatMapTo(LinkedHashSet()) { it.output }

    /** For each branch site, the outcomes some run reached. */
    val coverage: Map<BranchSite, Set<Boolean>>
        get() = program.sites.associateWith { site ->
            runs.flatMapTo(LinkedHashSet()) { run -> run.covered.filter { it.siteId == site.id }.map { it.taken } }
        }

    fun render(): String = buildString {
        for (step in steps) {
            val run = step.run
            appendLine("run ${step.number}: input ${run.input} -> ${run.outcome}")
            run.output.forEach { appendLine("    | ${printable(it)}") }
            appendLine("    path: ${run.signature.ifEmpty { listOf("(no symbolic branches)") }.joinToString(" ")}")
            step.flips.forEach { appendLine("    $it") }
        }
        appendLine()
        appendLine("${runs.size} runs (${if (exhausted) "all reachable paths explored" else "run budget reached"}), ${errors.size} runtime errors")
        appendLine(stats.toString())
        appendLine("branch coverage:")
        for ((site, seen) in coverage) {
            fun mark(b: Boolean) = if (b in seen) "yes" else "NO "
            appendLine("  true:${mark(true)} false:${mark(false)}  ${site.describe()}")
        }
        if (errors.isNotEmpty()) {
            appendLine("crashing inputs:")
            errors.forEach { appendLine("  ${it.input}: ${it.outcome}") }
        }
    }
}

/** Escapes control and non-ASCII bytes in program output so the report stays readable. */
private fun printable(s: String): String = buildString {
    for (c in s) {
        when {
            c == '\\' -> append("\\\\")
            c.code in 0x20..0x7e -> append(c)
            c == '\n' -> append("\\n")
            c == '\t' -> append("\\t")
            else -> append("\\x%02x".format(c.code))
        }
    }
}
