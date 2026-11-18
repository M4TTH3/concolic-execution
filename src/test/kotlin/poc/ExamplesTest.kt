package poc

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import poc.core.ConcolicTester
import java.io.File

/** Every program in examples/ compiles and explores to completion (a divergence would throw [poc.core.DivergenceError]). */
class ExamplesTest {
    @TestFactory
    fun examples(): List<DynamicTest> {
        val files = File("examples").listFiles { f -> f.extension == "poc" }.orEmpty().sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no examples found")
        return files.map { file ->
            DynamicTest.dynamicTest(file.name) {
                ConcolicTester(file.readText()).use { tester ->
                    val report = tester.explore()
                    assertTrue(report.exhausted, "${file.name} did not finish")
                }
            }
        }
    }
}
