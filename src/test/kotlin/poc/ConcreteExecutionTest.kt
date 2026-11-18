package poc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import poc.bytecode.SiteKind
import poc.core.Outcome

/** System tests for the VM's concrete semantics: source + one input in, printed output and outcome out. */
class ConcreteExecutionTest {
    private fun output(source: String, cmd: String = ""): List<String> {
        val run = runConcrete(source, cmd)
        assertEquals(Outcome.Completed, run.outcome)
        return run.output
    }

    @Test
    fun `int arithmetic wraps at 32 bits and division truncates`() {
        assertEquals(
            listOf("-2147483648", "-3", "3", "-2147483648", "-6", "2"),
            output(
                """
                MAIN cmd {
                  print(2147483647 + 1)
                  print(-7 / 2)
                  print(7 / 2)
                  print(-2147483648 / -1)
                  print(-(2 * 3))
                  print(10 - 2 * 4)
                }
                """,
            ),
        )
    }

    @Test
    fun `len, indexing and char replacement`() {
        assertEquals(
            listOf("3", "98", "Abc", "aBc"),
            output(
                """
                MAIN cmd {
                  print(len(cmd))
                  print(cmd[1])
                  str s = "abc"
                  s[0] = 65 + 256       // stores the low 8 bits
                  print(s)
                  cmd[1] = 66
                  print(cmd)
                }
                """,
                cmd = "abc",
            ),
        )
    }

    @Test
    fun `strs are copied into variables, arguments and return values`() {
        assertEquals(
            listOf("Zbc", "abc", "aXc", "abc", "Qbc", "abc"),
            output(
                """
                fun mutate(str s) {
                  s[0] = 90
                  print(s)
                }

                fun edited(str e) str {
                  e[0] = 81
                  return e
                }

                MAIN cmd {
                  str t = "abc"
                  mutate(t)
                  print(t)
                  str u = t
                  u[1] = 88
                  print(u)
                  print(t)
                  print(edited(t))
                  print(t)
                }
                """,
            ),
        )
    }

    @Test
    fun `return values, early return and calls inside expressions`() {
        assertEquals(
            listOf("0", "40", "42", "3628800"),
            output(
                """
                fun scale(int v) int {
                  if v > 100 {
                    return 0
                  }
                  return v * 20
                }

                fun inc(int w) int {
                  return w + 1
                }

                fun fact(int n) int {
                  if n <= 1 {
                    return 1
                  }
                  return n * fact(n - 1)
                }

                MAIN cmd {
                  print(scale(101))
                  print(scale(2))
                  print(inc(20) + inc(20))
                  print(fact(10))
                }
                """,
            ),
        )
    }

    @Test
    fun `unbounded recursion stops at the limit`() {
        val run = runConcrete(
            """
            fun forever(int n) int {
              return forever(n + 1)
            }

            MAIN cmd {
              print(forever(0))
            }
            """,
        )
        val outcome = run.outcome
        assertTrue(outcome is Outcome.LimitExceeded, outcome.toString())
        assertEquals("call depth limit of 200 exceeded", (outcome as Outcome.LimitExceeded).message)
    }

    @Test
    fun `str comparisons are lexicographic over unsigned bytes`() {
        assertEquals(
            listOf("ab < abc", "abd > abc", "ab == ab", "empty < a", "xff > a", "abc != abd", "abc <= abc", "b >= abc"),
            output(
                """
                MAIN cmd {
                  if "ab" < "abc" { print("ab < abc") }
                  if "abd" > "abc" { print("abd > abc") }
                  if "ab" == "ab" { print("ab == ab") }
                  if "" < "a" { print("empty < a") }
                  if "\xff" > "a" { print("xff > a") }
                  if "abc" != "abd" { print("abc != abd") }
                  if "abc" <= "abc" { print("abc <= abc") }
                  if "b" >= "abc" { print("b >= abc") }
                  if "ab" == "abc" { print("wrong") }
                }
                """,
            ),
        )
    }

    @Test
    fun `and, or and not short-circuit`() {
        assertEquals(
            listOf("or skipped it", "and skipped it", "not", "else-if 2"),
            output(
                """
                MAIN cmd {
                  int zero = 0
                  if 1 == 1 or 10 / zero == 1 { print("or skipped it") }
                  if 1 == 2 and 10 / zero == 1 { print("wrong") } else { print("and skipped it") }
                  if not (1 == 2) { print("not") }
                  if len(cmd) == 1 {
                    print("else-if 1")
                  } else if len(cmd) == 0 and 2 > 1 {
                    print("else-if 2")
                  } else {
                    print("else")
                  }
                }
                """,
            ),
        )
    }

    @Test
    fun `runtime errors report kind and site`() {
        val oob = runConcrete(
            """
            MAIN cmd {
              print("before")
              print(cmd[0])
            }
            """,
        )
        assertEquals(listOf("before"), oob.output)
        val oobError = oob.outcome as Outcome.RuntimeError
        assertEquals(SiteKind.BOUNDS, oobError.site.kind)
        assertEquals(3, oobError.site.pos.line)
        assertEquals("index 0 out of bounds for str of length 0", oobError.message)

        val div = runConcrete(
            """
            MAIN cmd {
              print(10 / len(cmd))
            }
            """,
        )
        val divError = div.outcome as Outcome.RuntimeError
        assertEquals(SiteKind.DIV_ZERO, divError.site.kind)
        assertEquals("division by zero", divError.message)

        val negative = runConcrete(
            """
            MAIN cmd {
              cmd[0 - 1] = 1
            }
            """,
            cmd = "abc",
        )
        assertEquals("index -1 out of bounds for str of length 3", (negative.outcome as Outcome.RuntimeError).message)
    }
}
