package dev.codex.kotlinls.tests

fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: "all"
    runSuites(
        suites = listOf(
            importSuite(),
            analysisSuite(),
            featureSuite(),
            editorParitySuite(),
            incrementalServerSuite(),
            semanticPersistenceSuite(),
            formattingSuite(),
            vendoredSourcesSuite(),
            protocolSmokeSuite(),
        ),
        selectedMode = mode,
    )
}
