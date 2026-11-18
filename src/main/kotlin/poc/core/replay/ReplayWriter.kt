package poc.core.replay

import poc.bytecode.INT_SIZE
import poc.core.ConcolicTester
import poc.core.Outcome
import poc.parser.Span
import java.io.File

/**
 * Writes the replay viewer for a tester's runs: the static template (`index.html`, `app.js`,
 * `style.css`, copied from the jar) plus the generated `replay-data.js`. See [RunReplay] for the
 * step delta format. Call it before the tester is closed: it still needs the cvc5 terms.
 */
object ReplayWriter {
    const val DATA_FILE = "replay-data.js"
    private val TEMPLATE = listOf("index.html", "app.js", "style.css")
    private const val MAX_SMT_LENGTH = 4000

    /** Writes everything into [dir] and returns its `index.html`. */
    fun write(tester: ConcolicTester, name: String, dir: File): File {
        dir.mkdirs()
        for (file in TEMPLATE) {
            val resource = ReplayWriter::class.java.getResourceAsStream("/replay/$file")
                ?: error("replay template resource /replay/$file is missing")
            resource.use { input -> File(dir, file).outputStream().use { input.copyTo(it) } }
        }
        File(dir, DATA_FILE).writeText("window.POC_REPLAY = ${Json.encode(data(tester, name))};\n")
        return File(dir, "index.html")
    }

    /** The data as plain maps and lists (exposed for tests). */
    fun data(tester: ConcolicTester, name: String): Map<String, Any?> {
        val program = tester.program
        val terms = tester.termTable
        val spans = LinkedHashMap<Span, Int>()
        fun span(s: Span) = spans.getOrPut(s) { spans.size }
        val functionIndex = program.functions.associate { it.name to it.index }

        val functions = program.functions.map { fn ->
            mapOf(
                "name" to fn.name,
                "slots" to slotsOf(fn).map { ref ->
                    listOf(ref.offset, ref.size, fn.slotNames.getValue(ref), if (ref.size == INT_SIZE) "int" else "str", ref in fn.temps)
                },
                "pcSpan" to fn.spans.map(::span),
                // Instruction text per pc: the status line shows it, and jumps are recognised by name.
                "code" to fn.code.map { it.toString() },
            )
        }
        val sites = program.sites.map { listOf(functionIndex.getValue(it.function), span(it.span), it.kind.name.lowercase(), it.description) }

        val runs = tester.steps.map { step ->
            val run = step.run
            val replay = step.replay ?: error("run ${step.number} was not recorded; create the tester with recordReplay = true")
            mapOf(
                "input" to run.input.cmd,
                "outcome" to when (val o = run.outcome) {
                    Outcome.Completed -> listOf("completed")
                    is Outcome.RuntimeError -> listOf("error", o.site.id, o.message)
                    is Outcome.LimitExceeded -> listOf("limit", o.message)
                },
                "from" to step.producedBy?.let { listOf(it.parentRun, it.index) },
                "constraints" to run.constraints.mapIndexed { i, c ->
                    listOf(replay.constraintSteps[i], c.site.id, c.taken, terms.id(c.term), c.term.toString().take(MAX_SMT_LENGTH))
                },
                "output" to run.output.mapIndexed { i, text -> listOf(replay.outputSteps[i], text) },
                "covered" to run.covered.map { listOf(it.siteId, it.taken) },
                "init" to replay.init,
                "steps" to replay.steps,
            )
        }

        val main = program.main
        return linkedMapOf(
            "version" to 1,
            "program" to linkedMapOf(
                "name" to name,
                "source" to tester.source,
                "maxStrLen" to program.maxStrLen,
                "inputName" to main.slotNames.getValue(main.params.single()),
                "mainIndex" to program.mainIndex,
                "spans" to spans.keys.map { listOf(it.start.line, it.start.col, it.end.line, it.end.col) },
                "functions" to functions,
                "sites" to sites,
            ),
            // Built last: interning the constraint terms above may add entries.
            "terms" to terms.texts,
            "runs" to runs,
            "stats" to with(tester.stats) { linkedMapOf("queries" to queries, "sat" to sat, "unsat" to unsat, "unknown" to unknown) },
        )
    }
}
