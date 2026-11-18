package poc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import poc.bytecode.SiteKind
import poc.core.ConcolicTester
import poc.core.ExplorationReport
import poc.core.Outcome
import java.io.File

/** Whole-search system tests: explore until exhausted and check what was found. */
class ExplorationTest {
    private fun example(name: String) = File("examples/$name").readText()

    private fun ExplorationReport.assertFinished() {
        assertTrue(exhausted, "search did not finish")
    }

    @Test
    fun `demo reaches every print and finds the division by zero`() = concolic(example("demo.poc")) {
        val report = explore()
        report.assertFinished()
        assertEquals(7, report.runs.size, report.render())
        assertTrue(report.allOutput.containsAll(listOf("usage", "0", "hello", "1540", "6")), report.render())

        val crash = report.errors.single()
        assertEquals("\u000f\u000f", crash.input.cmd) // x == 15 and cmd[1] == 15
        assertEquals(SiteKind.DIV_ZERO, (crash.outcome as Outcome.RuntimeError).site.kind)

        // Both sides of every `if` are covered; only the unreachable out-of-bounds sides are not.
        val uncovered = report.coverage.filterValues { it.size < 2 }.keys
        assertTrue(uncovered.all { it.kind == SiteKind.BOUNDS }, report.render())
    }

    @Test
    fun `magic bytes are found one flip at a time`() = concolic(example("magic.poc")) {
        val report = explore()
        report.assertFinished()
        assertTrue("jackpot" in report.allOutput, report.render())
        val crash = report.errors.single()
        assertEquals("bug!", crash.input.cmd)
        assertEquals("division by zero", (crash.outcome as Outcome.RuntimeError).message)
    }

    @Test
    fun `out-of-bounds reads are found from both sides`() = concolic(
        """
        MAIN cmd {
          int c = cmd[3]
          print(c)
        }
        """,
    ) {
        val report = explore()
        report.assertFinished()
        assertEquals(2, report.runs.size)
        assertEquals("index 3 out of bounds for str of length 0", (report.runs[0].outcome as Outcome.RuntimeError).message)
        assertEquals(Outcome.Completed, report.runs[1].outcome)
        assertTrue(report.runs[1].input.cmd.length > 3)
    }

    @Test
    fun `an overflow-only branch is reachable`() = concolic(
        """
        fun check(int x) {
          if x > 0 and x * 2 < 0 {
            print("overflow")
          }
        }

        MAIN cmd {
          if len(cmd) == 4 {
            check(cmd[0] + cmd[1] * 256 + cmd[2] * 65536 + cmd[3] * 16777216)
          }
        }
        """,
    ) {
        val report = explore()
        report.assertFinished()
        val hit = report.runs.single { "overflow" in it.output }
        val b = hit.input.bytes().map { it.toInt() and 0xFF }
        val x = b[0] + b[1] * 256 + b[2] * 65536 + b[3] * 16777216
        assertTrue(x > 0 && x * 2 < 0, "x = $x")
    }

    @Test
    fun `a finite program explores exactly its paths`() = concolic(
        """
        MAIN cmd {
          if len(cmd) == 1 {
            int x = cmd[0]
            if x < 10 or x > 20 { print("outside") } else { print("inside") }
          }
        }
        """,
    ) {
        val report = explore()
        report.assertFinished()
        // Wrong length; x = 0 (x < 10); flipping `x < 10` gives some x >= 10 that is also > 20;
        // only flipping `x > 20` after that lands inside.
        assertEquals(4, report.runs.size, report.render())
        assertEquals(listOf(emptyList(), listOf("outside"), listOf("outside"), listOf("inside")), report.runs.map { it.output })
    }

    @Test
    fun `symbolic str comparison, symbolic index and str returns`() = concolic(example("strings.poc")) {
        val report = explore()
        report.assertFinished()
        assertTrue(
            report.allOutput.containsAll(listOf("greeting", "first half", "second half", "found Z")),
            report.render(),
        )
        val greeting = report.runs.first { "greeting" in it.output }.input.cmd
        assertTrue(greeting == "HELLO" || greeting == "hELLO", greeting)
    }

    @Test
    fun `a symbolic store is modelled exactly`() = concolic(
        """
        MAIN cmd {
          if len(cmd) == 1 {
            str s = "aaaa"
            s[cmd[0]] = 66
            if s == "aaBa" { print("stored at 2") }
          }
        }
        """,
    ) {
        val report = explore()
        report.assertFinished()
        val hit = report.runs.single { "stored at 2" in it.output }
        assertEquals("\u0002", hit.input.cmd)
    }

    @Test
    fun `limit-exceeded runs do not stop the search`() = concolic(
        """
        fun spin(int n) int {
          return spin(n + 1)
        }

        MAIN cmd {
          if len(cmd) == 1 {
            print(spin(0))
          } else {
            print("fine")
          }
        }
        """,
    ) {
        val report = explore()
        report.assertFinished()
        assertEquals(listOf(listOf("fine"), emptyList()), report.runs.map { it.output })
        assertTrue(report.runs[1].outcome is Outcome.LimitExceeded)
    }

    @Test
    fun `exploration is deterministic`() {
        fun trace(): List<String> = ConcolicTester(example("demo.poc")).use { tester ->
            tester.explore().steps.map { "${it.run.input} ${it.run.output} ${it.run.outcome} ${it.flips.map { f -> f.input }}" }
        }
        assertEquals(trace(), trace())
    }
}
