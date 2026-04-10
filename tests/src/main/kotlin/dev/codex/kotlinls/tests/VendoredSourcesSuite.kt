package dev.codex.kotlinls.tests

import com.fasterxml.jackson.databind.JsonNode
import dev.codex.kotlinls.protocol.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

fun vendoredSourcesSuite(): TestSuite =
    TestSuite(
        name = "vendored-sources",
        cases = listOf(
            TestCase("JetBrains vendored metadata matches committed files") {
                val repoRoot = findRepoRoot()
                val manifestPath = repoRoot.resolve("third_party/jetbrains/vendor-manifest.json")
                val metadataPath = repoRoot.resolve("third_party/jetbrains/VENDORED_SOURCES.json")
                assertTrue(Files.isRegularFile(manifestPath)) { "Missing vendor manifest" }
                assertTrue(Files.isRegularFile(metadataPath)) { "Missing vendored sources metadata" }

                val manifest = Json.mapper.readTree(manifestPath.toFile())
                val metadata = Json.mapper.readTree(metadataPath.toFile())
                val metadataSources = metadata["sources"].associateBy { it["name"].asText() }

                manifest["sources"].forEach { source ->
                    val name = source["name"].asText()
                    val metadataSource = metadataSources[name] ?: error("Missing metadata for $name")
                    assertEquals(source["repo"].asText(), metadataSource["repo"].asText())
                    assertEquals(source["ref"].asText(), metadataSource["ref"].asText())
                    assertTrue(source["ref"].asText().matches(Regex("[0-9a-f]{40}"))) {
                        "Expected pinned commit SHA for $name"
                    }
                    val metadataFiles = metadataSource["files"].associateBy { it["path"].asText() }
                    source["files"].forEach { fileNode ->
                        val upstreamPath = fileNode.asText()
                        val metadataFile = metadataFiles[upstreamPath] ?: error("Missing file metadata for $upstreamPath")
                        val destination = repoRoot.resolve(metadataFile["destination"].asText())
                        assertTrue(Files.isRegularFile(destination)) { "Missing vendored file $destination" }
                        assertEquals(metadataFile["sha256"].asText(), sha256(destination))
                    }
                }
            },
        ),
    )

private fun JsonNode.associateBy(keySelector: (JsonNode) -> String): Map<String, JsonNode> =
    this.asSequence().associateBy(keySelector)

private fun JsonNode.asSequence(): Sequence<JsonNode> =
    buildList {
        val iterator = elements()
        while (iterator.hasNext()) {
            add(iterator.next())
        }
    }.asSequence()

private fun sha256(path: Path): String =
    MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path))
        .joinToString("") { byte -> "%02x".format(byte) }

private fun findRepoRoot(): Path {
    val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    return generateSequence(cwd) { current -> current.parent }
        .firstOrNull { candidate ->
            Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
                Files.isDirectory(candidate.resolve("third_party/jetbrains"))
        }
        ?: error("Unable to locate repository root from $cwd")
}
