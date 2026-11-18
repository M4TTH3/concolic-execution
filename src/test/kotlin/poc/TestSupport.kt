package poc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import poc.bytecode.BytecodeProgram
import poc.bytecode.SiteKind
import poc.compile.CompileException
import poc.compile.DEFAULT_MAX_STR_LEN
import poc.compile.PocCompiler
import poc.core.ConcolicTester
import poc.core.ExecutionResult
import poc.core.ExplorationReport
import poc.core.Input
import poc.core.Step
import poc.parser.quotePoc

/**
 * Builds a [ConcolicTester] from inline POC source (`trimIndent`ed, so line 1 is the first source line),
 * runs [block] against it and closes it.
 */
fun concolic(
    source: String,
    maxLen: Int = DEFAULT_MAX_STR_LEN,
    seed: String = "",
    block: ConcolicScope.() -> Unit,
) {
    ConcolicTester(source.trimIndent(), maxLen, seed).use { ConcolicScope(it).block() }
}

class ConcolicScope(val tester: ConcolicTester) {
    /** Runs the next seed in BFS order. Fails if the queue is empty. */
    fun step(): Step = tester.step() ?: fail("expected another seed to run, but the queue is empty\n${debug()}")

    /** Asserts exactly which flips (in order) [step] produced. */
    fun assertFlips(step: Step, vararg expected: ExpectedFlip) {
        val actual = step.flips.map { ExpectedFlip(it.index, it.site.pos.line, it.from, it.input.cmd, it.site.kind) }
        assertEquals(expected.toList(), actual) {
            "flips of run ${step.number} (input ${step.run.input}) differ\n" +
                "path constraints:\n" + step.run.constraints.joinToString("\n") { "  $it" } + "\n" + debug()
        }
    }

    /** Asserts that every reachable path has been explored. */
    fun assertExhausted() {
        val next = tester.step() ?: return
        fail<Unit>("expected no more seeds, but run ${next.number} ran input ${next.run.input}\n${debug()}")
    }

    fun explore(maxRuns: Int = 1000): ExplorationReport = tester.explore(maxRuns)

    /** Inputs of every run so far, in run order. */
    val inputs: List<String> get() = tester.steps.map { it.run.input.cmd }

    fun debug(): String = tester.program.disassemble() +
        tester.steps.joinToString("\n") { s -> "run ${s.number} ${s.run.input}: ${s.run.signature} -> ${s.run.outcome}" }
}

/** Expected flip: [index] of the negated constraint, the source [line] of its site, the parent's direction, and the new input. */
data class ExpectedFlip(val index: Int, val line: Int, val from: Boolean, val input: String, val kind: SiteKind) {
    override fun toString() = "flip(index=$index, line=$line, from=$from, input=${quotePoc(input)}, kind=$kind)"
}

fun flip(index: Int, line: Int, from: Boolean, input: String, kind: SiteKind = SiteKind.IF) =
    ExpectedFlip(index, line, from, input, kind)

/** [n] zero bytes, the value cvc5 picks for input bytes nothing constrains. */
fun zeros(n: Int) = "\u0000".repeat(n)

fun compile(source: String, maxLen: Int = DEFAULT_MAX_STR_LEN): BytecodeProgram =
    PocCompiler.compile(source.trimIndent(), maxLen)

/** The compile errors of [source] as `line:col: message`. Fails if it compiles. */
fun compileErrors(source: String, maxLen: Int = DEFAULT_MAX_STR_LEN): List<String> = try {
    val program = PocCompiler.compile(source.trimIndent(), maxLen)
    fail("expected compile errors, but it compiled:\n${program.disassemble()}")
} catch (e: CompileException) {
    e.errors.map { it.toString() }
}

/** Runs [source] once on [cmd] (no exploration). */
fun runConcrete(source: String, cmd: String = "", maxLen: Int = DEFAULT_MAX_STR_LEN, maxSteps: Int = 10_000): ExecutionResult =
    ConcolicTester(source.trimIndent(), maxLen, maxSteps = maxSteps).use { it.execute(Input(cmd)) }
