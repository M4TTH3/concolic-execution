package poc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import poc.bytecode.SiteKind.BOUNDS
import poc.core.Outcome

/**
 * "Test the next flip": each test drives the BFS search one run at a time and asserts exactly which
 * constraints were negated and which inputs the solver produced. Inputs are deterministic (fixed
 * cvc5 version, seed and query order); each pinned value is also checked against the constraint it solves.
 */
class NextFlipTest {
    @Test
    fun `len flip from empty seed`() = concolic(
        """
        MAIN cmd {
          if len(cmd) == 2 { print("two") } else { print("other") }
        }
        """,
    ) {
        val s0 = step() // runs seed ""
        assertEquals(listOf("other"), s0.run.output)
        assertFlips(s0, flip(index = 0, line = 2, from = false, input = "\u0000\u0000"))

        val s1 = step() // BFS: the flipped input runs next
        assertEquals(listOf("two"), s1.run.output)
        assertFlips(s1) // nothing new to flip
        assertExhausted()
    }

    @Test
    fun `BFS runs a whole generation before its children, shallowest flip first`() = concolic(
        """
        MAIN cmd {
          int a = cmd[0]
          int b = cmd[1]
          if a == 65 {
            print("a")
          }
          if b == 66 {
            print("b")
          }
        }
        """,
        seed = "zz",
    ) {
        // Generation 0: four constraints (two bounds checks, two ifs), all flippable, shallowest first.
        // Only `1 < len <= 16` constrains the length of the last two, and cvc5 picks 16.
        val s0 = step()
        assertEquals(emptyList<String>(), s0.run.output)
        assertFlips(
            s0,
            flip(index = 0, line = 2, from = true, input = "", kind = BOUNDS), // cmd[0] out of bounds
            flip(index = 1, line = 3, from = true, input = "\u0000", kind = BOUNDS), // cmd[1] out of bounds
            flip(index = 2, line = 4, from = false, input = "A" + zeros(15)), // a == 65
            flip(index = 3, line = 7, from = false, input = "@B" + zeros(14)), // b == 66 with a != 65
        )

        // Generation 1, in enqueue order.
        val s1 = step()
        assertEquals("index 0 out of bounds for str of length 0", (s1.run.outcome as Outcome.RuntimeError).message)
        assertFlips(s1)
        val s2 = step()
        assertEquals("index 1 out of bounds for str of length 1", (s2.run.outcome as Outcome.RuntimeError).message)
        assertFlips(s2)
        val s3 = step()
        assertEquals(listOf("a"), s3.run.output)
        assertFlips(s3, flip(index = 3, line = 7, from = false, input = "AB" + zeros(14))) // generation 2: a == 65 and b == 66
        val s4 = step()
        assertEquals(listOf("b"), s4.run.output)
        assertFlips(s4) // its only new prefix (a == 65) was explored by s3

        // Generation 2.
        val s5 = step()
        assertEquals(listOf("a", "b"), s5.run.output)
        assertFlips(s5)
        assertExhausted()
    }

    @Test
    fun `an infeasible flip produces no seed`() = concolic(
        """
        MAIN cmd {
          if len(cmd) == 1 {
            if cmd[0] == 65 {
              print("A")
            } else {
              print("not A")
            }
          } else {
            print("wrong length")
          }
        }
        """,
    ) {
        val s0 = step()
        assertEquals(listOf("wrong length"), s0.run.output)
        assertFlips(s0, flip(index = 0, line = 2, from = false, input = "\u0000"))

        // Path: len == 1, cmd[0] in bounds, cmd[0] != 65. Flipping the bounds check needs
        // len == 1 and not (0 < len): UNSAT, so only the cmd[0] == 65 flip is produced.
        val s1 = step()
        assertEquals(listOf("not A"), s1.run.output)
        assertEquals(3, s1.run.constraints.size)
        val unsatBefore = tester.stats.unsat
        assertFlips(s1, flip(index = 2, line = 3, from = false, input = "A"))
        assertEquals(1, unsatBefore)

        val s2 = step()
        assertEquals(listOf("A"), s2.run.output)
        assertFlips(s2)
        assertExhausted()
    }

    @Test
    fun `or gives each comparison its own flippable site`() = concolic(
        """
        MAIN cmd {
          if len(cmd) == 1 {
            int x = cmd[0]
            if x < 10 or x > 20 { print("outside") } else { print("inside") }
          }
        }
        """,
        seed = "\u000f", // x = 15
    ) {
        val s0 = step()
        assertEquals(listOf("inside"), s0.run.output)
        // len == 1, cmd[0] in bounds, x < 10 false, x > 20 false: two separate sites on line 4.
        assertEquals(listOf(4, 4), s0.run.constraints.drop(2).map { it.site.pos.line })
        assertTrue(s0.run.constraints[2].site != s0.run.constraints[3].site)
        assertFlips(
            s0,
            flip(index = 0, line = 2, from = true, input = zeros(3)), // len != 1
            flip(index = 2, line = 4, from = false, input = "\u0008"), // x < 10
            flip(index = 3, line = 4, from = false, input = "\u0015"), // x > 20 (21), given x >= 10
        )
        assertEquals(listOf(emptyList(), listOf("outside"), listOf("outside")), List(3) { step().run.output })
        assertExhausted()
    }

    @Test
    fun `a symbolic value flows through a return`() = concolic(
        """
        fun dbl(int v) int {
          return v * 2
        }

        MAIN cmd {
          if len(cmd) == 1 {
            if dbl(cmd[0]) == 130 { print("hit") }
          }
        }
        """,
        seed = "\u0000",
    ) {
        val s0 = step()
        assertFlips(
            s0,
            flip(index = 0, line = 6, from = true, input = zeros(3)), // len != 1
            flip(index = 2, line = 7, from = false, input = "A"), // zext(cmd[0]) * 2 == 130 only for 65
        )
    }

    @Test
    fun `a branch inside the callee is flipped from MAIN`() = concolic(
        """
        fun classify(int v) int {
          if v > 100 {
            return 1
          }
          return 0
        }

        MAIN cmd {
          if len(cmd) == 1 {
            if classify(cmd[0]) == 1 { print("big") } else { print("small") }
          }
        }
        """,
        seed = "\u0000",
    ) {
        val s0 = step()
        assertEquals(listOf("small"), s0.run.output)
        // classify returns a constant, so `classify(cmd[0]) == 1` is concrete and not recorded;
        // the flippable decision is the callee's own `v > 100` (line 2).
        assertEquals(listOf(9, 10, 2), s0.run.constraints.map { it.site.pos.line })
        assertFlips(
            s0,
            flip(index = 0, line = 9, from = true, input = zeros(3)), // len != 1
            flip(index = 2, line = 2, from = false, input = "e"), // v > 100 (101)
        )
        step()
        assertEquals(listOf("big"), step().run.output)
        assertExhausted()
    }

    @Test
    fun `a target is solved at most once across the whole search`() = concolic(
        """
        MAIN cmd {
          if len(cmd) == 3 {
            if cmd[0] == 1 { print("a") }
            if cmd[1] == 2 { print("b") }
            if cmd[2] == 3 { print("c") }
          }
        }
        """,
    ) {
        val report = explore()
        val targets = report.steps.flatMap { s -> s.flips.map { it.target } }
        assertEquals(targets.size, targets.toSet().size, "duplicate flip targets: $targets")
        assertEquals(tester.stats.sat, targets.size)
        // 1 wrong-length path + 2^3 combinations of the three independent ifs.
        assertEquals(9, report.runs.size)
        assertTrue(report.exhausted)
    }
}
