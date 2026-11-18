package poc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import poc.bytecode.*
import poc.parser.CmpOp
import poc.parser.Pos

/** System tests for the front end: source text in, exact bytecode or exact compile errors out. */
class CompilationTest {
    // ---- valid programs: bytecode shape ----

    @Test
    fun `a call allocates a return temp in the caller that the callee's Return fills`() {
        val program = compile(
            """
            fun add(int a, int b) int {
              return a + b
            }

            MAIN cmd {
              int z = add(1, 2)
              print(z)
            }
            """,
        )
        val add = program.functions[0]
        assertEquals(listOf(Ref(0, 4), Ref(4, 4)), add.params)
        assertEquals(4, add.returnSize)
        assertEquals(
            listOf(IntAdd(Ref(8, 4), Ref(0, 4), Ref(4, 4)), Return(Ref(8, 4)), Return(null)),
            add.code,
        )

        val main = program.main
        assertEquals(listOf(Ref(0, 20)), main.params) // a str is a 4-byte length + 16 bytes
        assertEquals(
            listOf(
                ConstInt(Ref(20, 4), 1),
                ConstInt(Ref(24, 4), 2),
                Call(0, "add", listOf(Ref(20, 4), Ref(24, 4)), ret = Ref(28, 4)),
                PrintInt(Ref(28, 4)), // z took over the return temp: no extra Copy
                Return(null),
            ),
            main.code,
        )
        assertEquals("z", main.slotNames[Ref(28, 4)])
    }

    @Test
    fun `str size follows the max length`() {
        val program = compile(
            """
            MAIN cmd {
              str s = "hi"
              print(s)
            }
            """,
            maxLen = 8,
        )
        assertEquals(listOf(Ref(0, 12)), program.main.params)
        assertEquals(listOf(ConstStr(Ref(12, 12), "hi"), PrintStr(Ref(12, 12)), Return(null)), program.main.code)
    }

    @Test
    fun `early return compiles to correct jump targets`() {
        val program = compile(
            """
            fun f(int v) int {
              if v > 100 {
                return 0
              }
              return v
            }

            MAIN cmd {
            }
            """,
        )
        val site = BranchSite(0, "f", Pos(2, 6), SiteKind.IF, "v > 100")
        assertEquals(
            listOf(
                ConstInt(Ref(4, 4), 100),
                IntCmpJump(CmpOp.GT, Ref(0, 4), Ref(4, 4), target = 3, site = site),
                Jump(5),
                ConstInt(Ref(8, 4), 0),
                Return(Ref(8, 4)),
                Return(Ref(0, 4)),
                Return(null),
            ),
            program.functions[0].code,
        )
    }

    @Test
    fun `else-if chains and short-circuit conditions get one site per comparison`() {
        val program = compile(
            """
            MAIN cmd {
              if len(cmd) == 1 or len(cmd) == 3 {
                print(1)
              } else if not (len(cmd) == 2) and len(cmd) < 9 {
                print(2)
              } else {
                print(3)
              }
            }
            """,
        )
        assertEquals(
            listOf("len(cmd) == 1", "len(cmd) == 3", "len(cmd) == 2", "len(cmd) < 9"),
            program.sites.map { it.description },
        )
        assertEquals(listOf(2, 2, 4, 4), program.sites.map { it.pos.line })
        val jumps = program.main.code.filterIsInstance<JumpInstruction>()
        assertTrue(jumps.all { it.target in program.main.code.indices }, program.disassemble())
    }

    @Test
    fun `implicit bounds and division sites are created in source order`() {
        val program = compile(
            """
            MAIN cmd {
              int a = cmd[0]
              cmd[1] = a / 2
            }
            """,
        )
        assertEquals(
            listOf(
                "#0 MAIN:2:11 bounds `cmd[0] in bounds`",
                "#1 MAIN:3:14 div_zero `2 != 0`",
                "#2 MAIN:3:3 bounds `cmd[1] in bounds`",
            ),
            program.sites.map { it.describe() },
        )
    }

    @Test
    fun `negative int literal at the 32-bit minimum compiles`() {
        val program = compile(
            """
            MAIN cmd {
              int m = -2147483648
            }
            """,
        )
        assertEquals(ConstInt(Ref(20, 4), Int.MIN_VALUE), program.main.code[0])
    }

    // ---- compile errors ----

    @Test
    fun `names must be unique across the whole program`() {
        assertEquals(
            listOf("4:11: 'a' is already declared at 1:11 (names must be unique in the program)"),
            compileErrors(
                """
                fun f(int a) {
                }

                fun g(int a) {
                }

                MAIN cmd {
                }
                """,
            ),
        )
    }

    @Test
    fun `unknown and out-of-scope variables`() {
        assertEquals(
            listOf("2:9: unknown variable 'y'"),
            compileErrors(
                """
                MAIN cmd {
                  print(y)
                }
                """,
            ),
        )
        assertEquals(
            listOf("5:9: 'y' is not in scope here (declared at 3:9)"),
            compileErrors(
                """
                MAIN cmd {
                  if len(cmd) == 0 {
                    int y = 1
                  }
                  print(y)
                }
                """,
            ),
        )
    }

    @Test
    fun `type mismatches`() {
        assertEquals(
            listOf("2:6: cannot compare str with int: both sides of == must have the same type"),
            compileErrors(
                """
                MAIN cmd {
                  if cmd == 1 {
                  }
                }
                """,
            ),
        )
        assertEquals(
            listOf("2:11: operator + needs int operands, found str"),
            compileErrors(
                """
                MAIN cmd {
                  int x = cmd + 1
                }
                """,
            ),
        )
        assertEquals(
            listOf("3:3: 'n' is an int; only a str can be indexed"),
            compileErrors(
                """
                MAIN cmd {
                  int n = 1
                  n[0] = 2
                }
                """,
            ),
        )
    }

    @Test
    fun `a str cannot be reassigned`() {
        assertEquals(
            listOf("3:3: cannot reassign str 's': a str's size is fixed, replace characters with s[i] = ... instead"),
            compileErrors(
                """
                MAIN cmd {
                  str s = "a"
                  s = "b"
                }
                """,
            ),
        )
    }

    @Test
    fun `calls are checked for arity, argument types and existence`() {
        assertEquals(
            listOf(
                "5:3: function 'f' takes 1 argument(s) but was given 2",
                "6:5: argument 'a' of 'f' must be an int, found a str",
                "7:3: unknown function 'g'",
            ),
            compileErrors(
                """
                fun f(int a) {
                }

                MAIN cmd {
                  f(1, 2)
                  f(cmd)
                  g()
                }
                """,
            ),
        )
    }

    @Test
    fun `MAIN must exist once and come last`() {
        assertEquals(
            listOf("1:1: program must define MAIN"),
            compileErrors(
                """
                fun f() {
                }
                """,
            ),
        )
        assertEquals(
            listOf("4:5: MAIN must be the last declaration in the program"),
            compileErrors(
                """
                MAIN cmd {
                }

                fun f() {
                }
                """,
            ),
        )
    }

    @Test
    fun `literals must fit`() {
        assertEquals(
            listOf("2:9: string literal is 5 bytes but the max str length is 4"),
            compileErrors(
                """
                MAIN cmd {
                  print("hello")
                }
                """,
                maxLen = 4,
            ),
        )
        assertEquals(
            listOf("2:11: integer literal 2147483648 does not fit in 32 bits"),
            compileErrors(
                """
                MAIN cmd {
                  int x = 2147483648
                }
                """,
            ),
        )
    }

    @Test
    fun `syntax errors report a position`() {
        val errors = compileErrors(
            """
            MAIN cmd {
              int = 3
            }
            """,
        )
        assertEquals(1, errors.size)
        assertTrue(errors[0].startsWith("2:7: syntax error:"), errors[0])
    }

    @Test
    fun `return rules`() {
        assertEquals(
            listOf("1:5: function 'f' must return an int on every path"),
            compileErrors(
                """
                fun f(int v) int {
                  if v > 0 {
                    return 1
                  }
                }

                MAIN cmd {
                }
                """,
            ),
        )
        assertEquals(
            listOf(
                "2:10: function 'f' returns int, not str",
                "6:3: function 'g' has no return type, so it cannot return a value",
                "10:3: function 'h' must return an int",
                "14:3: MAIN cannot return a value",
            ),
            compileErrors(
                """
                fun f() int {
                  return "x"
                }

                fun g() {
                  return 1
                }

                fun h() int {
                  return
                }

                MAIN cmd {
                  return 1
                }
                """,
            ),
        )
        assertEquals(
            listOf("5:11: function 'g' does not return a value", "8:3: unreachable statement after return"),
            compileErrors(
                """
                fun g() {
                }

                MAIN cmd {
                  int x = g()
                  print(x)
                  return
                  print(1)
                }
                """,
            ),
        )
    }
}
