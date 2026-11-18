import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    kotlin("jvm") version "2.4.21"
    antlr
    application
}

group = "poc"
version = "0.1.0"

repositories {
    mavenCentral()
}

val antlrVersion = "4.13.2"
val cvc5Version = "1.4.2"

/** cvc5 publishes its JNI library as a per-platform classifier jar next to the pure-Java API. */
val cvc5NativeClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPart = when {
        "linux" in os -> "linux"
        "mac" in os || "darwin" in os -> "osx"
        "windows" in os -> "windows"
        else -> error("Unsupported OS for cvc5 native library: $os")
    }
    val archPart = when (arch) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch_64"
        else -> error("Unsupported architecture for cvc5 native library: $arch")
    }
    "$osPart-$archPart"
}

dependencies {
    antlr("org.antlr:antlr4:$antlrVersion")
    implementation("org.antlr:antlr4-runtime:$antlrVersion")

    implementation("io.github.cvc5:cvc5:$cvc5Version")
    runtimeOnly("io.github.cvc5:cvc5:$cvc5Version:$cvc5NativeClassifier")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The antlr plugin makes a compile configuration extend `antlr`, which drags the whole ANTLR tool
// onto the runtime classpath. Only antlr4-runtime is needed at runtime.
listOf("api", "implementation").forEach { name ->
    configurations.findByName(name)?.let { conf ->
        conf.setExtendsFrom(conf.extendsFrom.filterNot { it.name == "antlr" })
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.generateGrammarSource {
    packageName = "poc.parser.antlr"
    arguments = arguments + listOf("-visitor", "-no-listener")
}

tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}

tasks.compileTestKotlin {
    dependsOn(tasks.generateTestGrammarSource)
}

application {
    mainClass = "poc.MainKt"
    applicationName = "poc"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
