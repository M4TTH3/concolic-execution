package poc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import poc.bytecode.Call
import poc.bytecode.ConstInt
import poc.bytecode.FunctionCode
import poc.bytecode.IntAdd
import poc.bytecode.IntCmpJump
import poc.bytecode.Return
import poc.bytecode.StoreByte
import poc.core.ConcolicTester
import poc.core.replay.ReplayWriter
import poc.core.replay.RunReplay
import poc.core.replay.TermPrinter
import poc.parser.Span
import java.io.File

/** The replay pipeline: source spans per instruction, the delta stream, readable terms, and the written files. */
class ReplayTest {
    /** The source text a single-line span covers. */
    private fun String.textOf(span: Span): String {
        assertEquals(span.start.line, span.end.line, "multi-line span $span")
        return lines()[span.start.line - 1].substring(span.start.col - 1, span.end.col - 1)
    }

    private fun FunctionCode.spanOf(predicate: (Any) -> Boolean): Span = spans[code.indexOfFirst(predicate)]

    @Test
    fun `instructions carry the exact span of the code that produced them`() {
        val source = """
            MAIN cmd {
              int a = 5
              int b = 3
              if (a + 1 > b) {
                a = a * 2;
              }
              cmd[0] = 66
            }
        """.trimIndent()
        val main = compile(source).main
        assertEquals("a + 1", source.textOf(main.spanOf { it is IntAdd }))
        assertEquals("a + 1 > b", source.textOf(main.spanOf { it is IntCmpJump }))
        assertEquals("1", source.textOf(main.spanOf { it is ConstInt && it.value == 1 }))
        assertEquals("a = a * 2", source.textOf(main.spans[main.code.indexOfFirst { it is IntCmpJump } + 4])) // the Copy, without `;`
        assertEquals("cmd[0] = 66", source.textOf(main.spanOf { it is StoreByte }))
        assertEquals("}", source.textOf(main.spans.last())) // implicit Return(null) at the closing brace
    }

    /** Replays a run's deltas like the viewer does and returns MAIN's slot values when it returned. */
    private fun applyDeltas(program: poc.bytecode.BytecodeProgram, replay: RunReplay): Map<String, Any?> {
        class Frame(val fn: FunctionCode) {
            val values = arrayOfNulls<Any?>(fn.slotNames.size)
        }

        fun names(f: Frame) = f.fn.slotNames.values.toList()
        val stack = ArrayDeque(listOf(Frame(program.main)))
        var mainAtReturn: Frame? = null

        fun apply(effects: List<Any?>) {
            for (e in effects) {
                val effect = e as List<*>
                when (effect[0]) {
                    "c" -> stack.addLast(Frame(program.functions[effect[1] as Int]))
                    "r" -> stack.removeLast().also { if (stack.isEmpty()) mainAtReturn = it }
                    else -> stack.last().values[effect[0] as Int] = effect[1]
                }
            }
        }
        apply(replay.init)
        for (step in replay.steps) if (step is List<*>) apply(step.drop(1))
        val main = mainAtReturn ?: error("MAIN never returned")
        return names(main).zip(main.values).toMap()
    }

    @Test
    fun `the delta stream reconstructs every variable, including writes of zero`() = concolic(
        """
        fun bump(int v) int {
          int w = v * 20
          return w
        }

        MAIN cmd {
          int c = 0
          str s = "abc"
          s[0] = 90
          int d = bump(c + 2)
          print(d)
        }
        """,
        replay = true,
    ) {
        val step = step()
        val replay = step.replay!!
        val values = applyDeltas(tester.program, replay)
        assertEquals(0, values["c"]) // a write of 0 into a zeroed slot is still reported
        assertEquals("Zbc", values["s"])
        assertEquals(40, values["d"])
        assertEquals("", values["cmd"])

        assertEquals(step.run.steps, replay.steps.size)
        val effects = replay.steps.filterIsInstance<List<*>>().flatMap { it.drop(1) }.map { (it as List<*>)[0] }
        assertEquals(1, effects.count { it == "c" })
        assertEquals(2, effects.count { it == "r" }) // bump's return, then MAIN's
        // A print writes no slot, so its step is a bare pc.
        val printPc = tester.program.main.code.indexOfFirst { it.toString().startsWith("PrintInt") }
        assertEquals(listOf(replay.steps.indexOfLast { it == printPc }), replay.outputSteps)
    }

    @Test
    fun `constraint steps point at the instruction that recorded them`() = concolic(
        """
        MAIN cmd {
          if len(cmd) == 1 {
            if cmd[0] == 65 { print("A") }
          }
        }
        """,
        seed = "A",
        replay = true,
    ) {
        val step = step()
        val replay = step.replay!!
        val program = tester.program
        assertEquals(3, replay.constraintSteps.size)
        assertEquals(replay.constraintSteps.sorted(), replay.constraintSteps)
        // Every recording step ran a branching instruction in MAIN (no calls here).
        for (s in replay.constraintSteps) {
            val pc = replay.steps[s].let { if (it is Int) it else (it as List<*>)[0] as Int }
            val text = program.main.code[pc].toString()
            assertTrue(text.startsWith("IntCmpJump") || text.startsWith("LoadByte"), text)
        }
    }

    @Test
    fun `terms print as POC expressions`() = concolic(
        """
        fun dbl(int v) int {
          return v * 2
        }

        MAIN cmd {
          if len(cmd) == 1 {
            int x = cmd[0]
            if x < 10 { print("small") }
            if dbl(cmd[0]) == 130 { print("hit") }
          }
          if cmd == "hi" { print("hi") }
        }
        """,
        seed = " ",
    ) {
        val printer = TermPrinter("cmd")
        val constraints = step().run.constraints.map { printer.print(it.term) }
        assertEquals(
            listOf(
                "len(cmd) == 1",
                "0 <ᵤ len(cmd)",
                "cmd[0] >= 10", // `x < 10` was false: not(<) prints as >=
                "0 <ᵤ len(cmd)", // dbl(cmd[0]) reads cmd[0] again
                "cmd[0] * 2 != 130",
                "!(len(cmd) == 2 && cmd[0] == 104 && cmd[1] == 105)", // only the literal's 2 bytes matter
            ),
            constraints,
        )
    }

    @Test
    fun `the writer emits the template and deterministic data`(@TempDir dir: File) {
        fun write(into: File): String {
            ConcolicTester(File("examples/demo.poc").readText(), recordReplay = true).use { tester ->
                tester.explore()
                val index = ReplayWriter.write(tester, "demo", into)
                assertEquals(File(into, "index.html"), index)
            }
            return File(into, ReplayWriter.DATA_FILE).readText()
        }

        val first = write(File(dir, "a"))
        for (name in listOf("index.html", "app.js", "style.css", ReplayWriter.DATA_FILE)) {
            assertTrue(File(dir, "a/$name").length() > 0, "missing $name")
        }
        assertTrue(first.startsWith("window.POC_REPLAY = {\"version\":1,"), first.take(60))
        assertTrue(first.trimEnd().endsWith("};"))
        assertEquals(first, write(File(dir, "b")))
    }

    @Test
    fun `calls and returns are recorded around the callee's writes`() = concolic(
        """
        fun inc(int v) int {
          return v + 1
        }

        MAIN cmd {
          int r = inc(41)
          print(r)
        }
        """,
        replay = true,
    ) {
        val replay = step().replay!!
        val program = tester.program
        val callStep = replay.steps.first { it is List<*> && (it[1] as List<*>)[0] == "c" } as List<*>
        assertTrue(program.main.code[callStep[0] as Int] is Call)
        assertEquals(listOf(0, 41), callStep[2]) // the argument copied into inc's slot 0 (v)
        val retStep = replay.steps.first { it is List<*> && (it[1] as List<*>)[0] == "r" } as List<*>
        assertTrue(program.functions[0].code[retStep[0] as Int] is Return)
        assertEquals(42, (retStep[2] as List<*>)[1]) // then the caller's return temp
    }
}
