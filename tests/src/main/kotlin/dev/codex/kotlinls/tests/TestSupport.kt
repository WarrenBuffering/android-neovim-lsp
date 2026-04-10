package dev.codex.kotlinls.tests

import kotlin.system.exitProcess

data class TestCase(
    val name: String,
    val body: () -> Unit,
)

data class TestSuite(
    val name: String,
    val cases: List<TestCase>,
)

fun runSuites(suites: List<TestSuite>, selectedMode: String) {
    val filtered = when (selectedMode) {
        "smoke" -> suites.filter { it.name in setOf("protocol-smoke", "import") }
        else -> suites
    }
    var failures = 0
    filtered.forEach { suite ->
        println("[suite] ${suite.name}")
        suite.cases.forEach { test ->
            try {
                test.body()
                println("  [ok] ${test.name}")
            } catch (t: Throwable) {
                failures++
                println("  [fail] ${test.name}: ${t.message}")
                t.printStackTrace(System.out)
            }
        }
    }
    if (failures > 0) {
        error("$failures test(s) failed")
    }
}

fun assertTrue(condition: Boolean, lazyMessage: () -> String = { "Expected condition to be true" }) {
    if (!condition) error(lazyMessage())
}

fun <T> assertEquals(expected: T, actual: T, lazyMessage: () -> String = { "Expected <$expected> but was <$actual>" }) {
    if (expected != actual) error(lazyMessage())
}

fun assertContains(haystack: String, needle: String) {
    assertTrue(haystack.contains(needle)) { "Expected <$haystack> to contain <$needle>" }
}

