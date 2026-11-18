package poc

import io.github.cvc5.Context
import io.github.cvc5.Kind
import io.github.cvc5.Solver
import io.github.cvc5.TermManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Proves the cvc5 JNI library loads from the Maven native jar and QF_BV works with wrapping semantics. */
class Cvc5SmokeTest {
    @Test
    fun `x plus 1 equals 0 over BV32 gives all ones`() {
        val tm = TermManager()
        val solver = Solver(tm)
        try {
            solver.setLogic("QF_BV")
            solver.setOption("produce-models", "true")
            val bv32 = tm.mkBitVectorSort(32)
            val x = tm.mkConst(bv32, "x")
            val one = tm.mkBitVector(32, 1)
            val zero = tm.mkBitVector(32, 0)
            solver.assertFormula(tm.mkTerm(Kind.EQUAL, tm.mkTerm(Kind.BITVECTOR_ADD, x, one), zero))
            assertTrue(solver.checkSat().isSat)
            assertEquals("4294967295", solver.getValue(x).getBitVectorValue(10))
        } finally {
            Context.deletePointers()
        }
    }
}
