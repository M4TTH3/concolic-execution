# POC concolic tester

A small concolic testing tool for **POC** (Proof of Concept), a tiny single-file language with two types.
POC source is parsed with ANTLR, compiled to a custom bytecode, and run by a VM that executes every
instruction concretely and symbolically at the same time. The tester negates path constraints with
[cvc5](https://cvc5.github.io/) (bitvector theory, QF_BV) to generate new inputs, breadth first, until
every feasible path has been explored. Division by zero and out-of-bounds indexing count as branches too,
so the tester also finds crashing inputs.

## Running

```bash
./gradlew test                                        # all E2E tests
./gradlew run --args="examples/demo.poc"              # explore a program
./gradlew run --args="examples/demo.poc --dump-bytecode --dump-queries"
```

```
poc <file.poc> [--max-len 16] [--max-runs 1000] [--seed STR] [--dump-bytecode] [--dump-queries]
               [--results DIR] [--no-replay]
```

To run without Gradle, build the self-contained jar once and use it from anywhere (Java 21+):

```bash
./gradlew fatJar
java -jar build/libs/poc.jar examples/demo.poc
```

`poc.jar` (about 14 MB) bundles the Kotlin runtime, ANTLR runtime, cvc5 and the replay viewer. The cvc5 native library inside is the one for the machine that built the jar, so build it on each OS/architecture you run it on. Replays go to `results/` under the current directory. `./gradlew installDist` builds a launcher script at `build/install/poc/bin/poc` instead.

## Replay viewer

Every run also writes an HTML replay to `results/<file name>/index.html` (change the directory with `--results`, turn it off with `--no-replay`). Open it in a browser to watch each generated test execute:

- **Test bar:** switch between tests. A dot marks each test's outcome, and the card shows the input and the flip that produced it, linked back to its parent test.
- **Source:** the exact sub-expression just executed is highlighted. A ghost after it shows its value (`= 15`), a branch decision (`false`), or the crash. Gutter marks show the lines this test has run.
- **Variables:** the call stack and each frame's variables, with concrete values in blue and, for values that depend on the input, their symbolic term in violet (`cmd[0] * 20`).
- **Path constraints:** listed as each branch records one, in readable form. A constraint whose flip produced another test links to it.
- **Output:** what `print` emitted so far, then the outcome.
- **Playback:** play/pause, a speed slider and a timeline marked with branches, prints and crashes. You can step by instruction or by line. With **Autoplay tests** on, a finished test holds briefly on its outcome, then playback continues with the next test and stops after the last one. Keys: Space plays, ←/→ step, Shift+←/→ switch test, Home/End jump to the ends, A toggles autoplay.

The page is a static template (`index.html`, `app.js`, `style.css`, from `src/main/resources/replay/`) plus a generated `replay-data.js`, loaded with a `<script>` tag so the page works when opened straight from disk. The data is a compact delta format: one entry per executed instruction, holding only the slots that instruction wrote. `RunReplay` in `core/replay/ReplayRecorder.kt` documents it.

Requires JDK 21. The Gradle wrapper fetches Gradle 9.9.1, and the cvc5 Java API plus its native library come
from Maven Central (`io.github.cvc5:cvc5:1.4.2`).

## The language

```
fun scale(int v) int {        // return type after the parameters; omit it for no return value
  if v > 100 {
    return 0
  }
  return v * 20
}

MAIN cmd {                    // last in the file; cmd is the str input
  if len(cmd) == 2 {
    int x = cmd[0]            // s[i] reads one byte as an int 0..255
    if x < 10 or x > 20 {
      print(scale(x))
    } else {
      cmd[1] = 65             // char replacement stores the low 8 bits
      print(cmd)
    }
  }
}
```

**Types**
- `int` is 32-bit two's complement. Arithmetic wraps, `/` truncates, and comparisons are signed.
- `str` is a fixed-capacity byte array with a length. Every str has capacity `--max-len` (default 16). A str's length never changes, but its bytes can.
- There is no bool type.

**Expressions**
- Integer literals, `"strings"` (escapes `\n \t \" \\ \xNN`), variables, unary `-`, `+ - * /`, `len(s)`, `s[i]` and calls to functions with a return type.

**Conditions**
- Conditions appear only in `if` / `else if` / `else`.
- A comparison `== != < <= > >=` needs the same type on both sides. Strs compare lexicographically over unsigned bytes.
- Comparisons combine with `and`, `or` (both short-circuit), `not` and parentheses.

**Statements**
- `int x = e`, `str s = e`, `x = e` (int only; reassigning a whole str is an error), `s[i] = e`, `print(e)`, `f(args)` and `return e?`.

**Functions**
- Top level only, in any order, with recursion allowed.
- Arguments and return values are copied.
- A function with a return type must return on every path, and nothing may follow a `return` in its block.

**Names**
- Every variable, parameter and function name must be unique in the whole program.
- Variables are block-scoped and must be initialised.

**Syntax**
- `//` starts a comment.
- `;` is optional. Since newlines aren't significant, a line starting with `-` continues the previous expression.
- There are no loops.

## How it works

```
source ──PocParser──▶ AST ──TypeChecker──▶ ──PocCompiler──▶ bytecode ──ConcolicEngine──▶ path constraints
  (parser/, ANTLR)          (compile/)                       (bytecode/)    (core/)           │
                                       ▲                                                      │
                                       └──── new input ◀── cvc5 ◀── negate one ◀── ConcolicTester (BFS)
```

### Memory: `bytecode/Ref.kt`
Every value lives in its call frame's byte memory at a `Ref(offset, size)`:
- an int is 4 bytes, little-endian;
- a str is a 4-byte length header followed by `maxLen` data bytes.

The symbolic state shadows each frame with one cvc5 term per cell: a BV32 term at an int's or header's offset, and a BV8 term per data byte. A null cell means the value is concrete.

### Instructions: `bytecode/Instructions.kt`
Each instruction is a data class whose `execute(conc, sym)` does the symbolic update first (on the pre-state) and then the concrete one. Terms are only built when an operand is symbolic, so concrete branches never become constraints.

### Calls
The caller allocates a temp `Ref` for the result and passes it in `Call`. The callee's `Return` copies its value, together with that value's symbolic cells, into the temp.

### Inputs
`cmd` is `cmd_len` (BV32, at most `maxLen`) plus `cmd_0 .. cmd_{maxLen-1}` (BV8), so the solver can choose the length as well as the bytes.

### Search: `core/ConcolicTester.kt`
1. Take the oldest seed from a FIFO queue and run it.
2. For each constraint `cj`, shallowest first, solve `c0 ∧ … ∧ c(j-1) ∧ ¬cj` with `checkSatAssuming`.
3. Enqueue each model as a new seed.

Prefixes that were already explored or attempted are skipped. The search is deterministic, so tests can pin exact generated inputs. For bytes and lengths that nothing constrains, cvc5 picks `0` and, often, the maximum length.

Every run is checked against the prefix its input was solved for. If it strays ("diverges"), `step()` throws a `DivergenceError` naming the expected prefix, the path that actually ran, and the first branch where they differ. Tools that concretize treat divergence as normal, but POC's symbolic model is exact, so a divergence always means some instruction's concrete and symbolic halves disagree: a bug in the tester.

## Tests

All tests are end to end and start from inline POC source. `TestSupport.kt` provides the helpers:

```kotlin
@Test fun `len flip from empty seed`() = concolic("""
    MAIN cmd {
      if len(cmd) == 2 { print("two") } else { print("other") }
    }
""") {
    val s0 = step()                                     // runs seed ""
    assertFlips(s0, flip(index = 0, line = 2, from = false, input = "\u0000\u0000"))
    assertEquals(listOf("two"), step().run.output)      // BFS: the flipped input runs next
    assertExhausted()
}
```

The test classes:

| Class | Covers |
|---|---|
| `CompilationTest` | exact bytecode for representative programs, and exact compile errors |
| `ConcreteExecutionTest` | the VM's concrete semantics: wrapping, copies, returns, recursion limits, str ordering, short-circuiting, runtime errors |
| `NextFlipTest` | step-by-step BFS order and exact flips and inputs |
| `ExplorationTest` | whole searches: crashes, magic bytes, overflow, symbolic str ops, determinism |
| `ExamplesTest` | every file in `examples/` explores to completion |
| `ReplayTest` | exact source spans per instruction; the delta stream reconstructs every variable; readable terms; deterministic replay files |

A divergence fails any test that hits it, because `step()` and `explore()` throw.

`Context.deletePointers()`, called by `ConcolicTester.close()`, frees every cvc5 object in the process, so testers must not run concurrently. JUnit runs tests sequentially by default.
