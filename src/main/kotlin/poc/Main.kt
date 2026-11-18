package poc

import poc.compile.CompileException
import poc.compile.DEFAULT_MAX_STR_LEN
import poc.core.ConcolicTester
import poc.core.DivergenceError
import poc.core.ExplorationReport
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """usage: poc <file.poc> [options]
  --max-len N        capacity of every str, and the max input length (default $DEFAULT_MAX_STR_LEN)
  --max-runs N       stop after N runs (default 1000)
  --seed STR         initial input for cmd (default "")
  --dump-bytecode    print the compiled bytecode before exploring
  --dump-queries     print every solver query
exit status: 0 explored, 1 compile errors, 2 bad usage, 3 internal error (a run diverged)"""

fun main(args: Array<String>) {
    var file: String? = null
    var maxLen = DEFAULT_MAX_STR_LEN
    var maxRuns = 1000
    var seed = ""
    var dumpBytecode = false
    var dumpQueries = false

    val it = args.iterator()
    fun value(flag: String): String = if (it.hasNext()) it.next() else fail("$flag needs a value")
    while (it.hasNext()) {
        when (val arg = it.next()) {
            "--max-len" -> maxLen = value(arg).toIntOrNull() ?: fail("--max-len needs a number")
            "--max-runs" -> maxRuns = value(arg).toIntOrNull() ?: fail("--max-runs needs a number")
            "--seed" -> seed = value(arg)
            "--dump-bytecode" -> dumpBytecode = true
            "--dump-queries" -> dumpQueries = true
            "-h", "--help" -> {
                println(USAGE)
                return
            }
            else -> if (file == null && !arg.startsWith("--")) file = arg else fail("unexpected argument '$arg'")
        }
    }
    val path = file ?: fail("missing <file.poc>")
    val source = File(path).takeIf { it.isFile }?.readText() ?: fail("cannot read $path")

    val tester = try {
        ConcolicTester(source, maxLen, seed, queryLog = if (dumpQueries) ::println else null)
    } catch (e: CompileException) {
        e.errors.forEach { System.err.println("$path:$it") }
        exitProcess(1)
    }
    val status = tester.use {
        if (dumpBytecode) println(it.program.disassemble())
        try {
            print(it.explore(maxRuns).render())
            0
        } catch (e: DivergenceError) {
            // Show the runs that completed, then the diagnostic: this is a tester bug, not a program bug.
            print(ExplorationReport(it.program, it.steps.toList(), it.stats, exhausted = false).render())
            System.err.println("poc: internal error: ${e.message}")
            3
        }
    }
    if (status != 0) exitProcess(status)
}

private fun fail(message: String): Nothing {
    System.err.println("poc: $message")
    System.err.println(USAGE)
    exitProcess(2)
}
