plugins {
    alias(libs.plugins.kotlin.jvm)
}

// No Android dependencies here, ever. The simulator that measures KSPC and the IME that ships
// resolve candidates through the same code, otherwise a measured figure describes a keyboard
// nobody can install.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

/**
 * How often the decoder recovers what was meant, from presses somebody really made.
 *
 * The figure the project turns on, and the one KSPC cannot give: KSPC counts presses assuming
 * they land where they were aimed, and one press in eighteen does not. Reads the harness's
 * recording, so it measures against real mistakes rather than invented noise.
 */
tasks.register<JavaExec>("accuracy") {
    group = "verification"
    description = "Decodes bench/typing.tsv and reports how often the phrase comes back"
    mainClass.set("io.github.vagrant326.atvt9.core.bench.AccuracyKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    defaultCharacterEncoding = "UTF-8"
}

/**
 * KSPC over the query corpus, against the shipped dictionaries. Lives in the test source set so
 * the runner never reaches the APK.
 *
 * The figure this prints is the one that decides whether T9 is worth building at all. Published
 * T9 KSPC is 1.0072 and assumes every word is in the dictionary; the queries here are
 * deliberately not, because a TV search box is mostly proper nouns.
 */
/**
 * The keyboard on a PC keyboard, with the strip and the state on screen.
 *
 * A television is a slow place to discover that a word is missing, and the strip on the device
 * has room for what fits on a television. This shows every candidate with its score, so the
 * dictionary can be judged before an APK is built. Same source set as the benchmark, for the
 * same reason: it must never reach the APK.
 *
 *     ./gradlew :core:harness
 *     ./gradlew :core:harness --args="--keys 567 0 --language pl"
 */
tasks.register<JavaExec>("harness") {
    group = "verification"
    description = "Types into the keyboard from a PC keyboard, or runs a scripted sequence"
    mainClass.set("io.github.vagrant326.atvt9.core.harness.HarnessKt")
    classpath = sourceSets["test"].runtimeClasspath
    // The dictionary paths default to the assets relative to here, so --args can carry a script
    // without having to repeat them: Gradle replaces the whole argument list rather than adding.
    workingDir = rootProject.projectDir
    // Polish words on stdout, on a Windows console that is not UTF-8 by default. A scripted run
    // whose output is `ucho` but `tch?rzu` is worse than useless — it is wrong about the one
    // thing the diacritics folding exists to get right.
    defaultCharacterEncoding = "UTF-8"
}

tasks.register<JavaExec>("bench") {
    group = "verification"
    description = "Measures KSPC over bench/queries-v1.tsv"
    mainClass.set("io.github.vagrant326.atvt9.core.bench.BenchmarkKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootProject.projectDir
    args(
        "--queries", "bench/queries-v1.tsv",
        "--dictionary-pl", "app/src/main/assets/dictionary-pl.bin",
        "--dictionary-en", "app/src/main/assets/dictionary-en.bin",
    )
}

