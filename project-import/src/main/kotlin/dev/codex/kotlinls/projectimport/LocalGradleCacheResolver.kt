package dev.codex.kotlinls.projectimport

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk
import org.w3c.dom.Element
import org.w3c.dom.Node

data class ResolvedClasspath(
    val binaries: List<Path>,
    val sources: List<Path>,
    val javadocs: List<Path>,
)

class LocalGradleCacheResolver(
    private val gradleHome: Path = Path(System.getProperty("user.home"), ".gradle"),
    private val extractionCacheRoot: Path = Path(System.getProperty("java.io.tmpdir"), "android-neovim-lsp", "artifact-cache"),
) {
    private val metadataCache = ConcurrentHashMap<String, ArtifactMetadata?>()
    private val managedVersionsCache = ConcurrentHashMap<String, Map<String, String>>()
    private val classpathCache = ConcurrentHashMap<String, ResolvedClasspath>()
    private val directArtifactsCache = ConcurrentHashMap<String, ResolvedClasspath>()

    fun resolveClasspath(directDependencies: List<DependencyCoordinate>): ResolvedClasspath {
        val normalizedDependencies = directDependencies.distinctBy { it.notation }.sortedBy { it.notation }
        val cacheKey = normalizedDependencies.joinToString(separator = "|") { it.notation }
        return classpathCache.computeIfAbsent(cacheKey) {
            val binaries = linkedSetOf<Path>()
            val sources = linkedSetOf<Path>()
            val javadocs = linkedSetOf<Path>()
            val queue = ArrayDeque<TraversalState>()
            val visited = linkedSetOf<String>()

            normalizedDependencies.forEach { dependency ->
                queue.addLast(
                    TraversalState(
                        coordinate = dependency,
                        managedVersions = resolveManagedVersions(dependency),
                    ),
                )
            }

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!visited.add(current.coordinate.notation)) continue
                val metadata = loadMetadata(current.coordinate) ?: continue
                metadata.binaryArtifact?.let { artifact ->
                    materializeBinaryArtifacts(artifact).forEach { binaries.add(it) }
                }
                metadata.sourceJar?.let { sources.add(it) }
                metadata.javadocJar?.let { javadocs.add(it) }

                val managedVersions = linkedMapOf<String, String>()
                managedVersions.putAll(current.managedVersions)
                managedVersions.putAll(metadata.managedVersions)

                metadata.dependencies
                    .filter { dependency -> dependency.includeInClasspath() }
                    .forEach { dependency ->
                        val version = dependency.version ?: managedVersions["${dependency.group}:${dependency.artifact}"] ?: return@forEach
                        queue.addLast(
                            TraversalState(
                                coordinate = DependencyCoordinate(dependency.group, dependency.artifact, version),
                                managedVersions = managedVersions,
                            ),
                        )
                    }
            }

            ResolvedClasspath(
                binaries = binaries.toList(),
                sources = sources.toList(),
                javadocs = javadocs.toList(),
            )
        }
    }

    fun resolveManagedVersions(coordinate: DependencyCoordinate): Map<String, String> =
        managedVersionsCache.computeIfAbsent(coordinate.notation) {
            loadMetadata(coordinate)?.managedVersions.orEmpty()
        }

    fun resolveDirectArtifacts(dependencies: List<DependencyCoordinate>): ResolvedClasspath {
        val normalizedDependencies = dependencies.distinctBy { it.notation }.sortedBy { it.notation }
        val cacheKey = normalizedDependencies.joinToString(separator = "|") { it.notation }
        return directArtifactsCache.computeIfAbsent(cacheKey) {
            val binaries = linkedSetOf<Path>()
            val sources = linkedSetOf<Path>()
            val javadocs = linkedSetOf<Path>()
            normalizedDependencies.forEach { dependency ->
                val metadata = loadMetadata(dependency) ?: return@forEach
                metadata.binaryArtifact?.let { artifact ->
                    materializeBinaryArtifacts(artifact).forEach { binaries.add(it) }
                }
                metadata.sourceJar?.let { sources.add(it) }
                metadata.javadocJar?.let { javadocs.add(it) }
            }
            ResolvedClasspath(
                binaries = binaries.toList(),
                sources = sources.toList(),
                javadocs = javadocs.toList(),
            )
        }
    }

    private fun loadMetadata(coordinate: DependencyCoordinate): ArtifactMetadata? =
        metadataCache.computeIfAbsent(coordinate.notation) {
            parseMetadata(coordinate)
        }

    private fun parseMetadata(coordinate: DependencyCoordinate): ArtifactMetadata? {
        val resolvedCoordinate = resolveArtifactCoordinate(coordinate) ?: return null
        val dependencyRoot = dependencyRoot(resolvedCoordinate) ?: return null
        val pom = dependencyRoot.findArtifactFile(resolvedCoordinate, "pom")
        val sourceJar = dependencyRoot.findArtifactFile(resolvedCoordinate, "jar") { name -> name.endsWith("-sources.jar") }
        val javadocJar = dependencyRoot.findArtifactFile(resolvedCoordinate, "jar") { name -> name.endsWith("-javadoc.jar") }
        val binaryArtifact = dependencyRoot.findArtifactFile(resolvedCoordinate, "jar") { name ->
            !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
        } ?: dependencyRoot.findArtifactFile(resolvedCoordinate, "aar")

        val pomModel = pom?.let(::parsePom) ?: PomModel()
        val managedVersions = linkedMapOf<String, String>()
        managedVersions.putAll(pomModel.managedVersions)
        pomModel.importedBoms.forEach { importedBom ->
            managedVersions.putAll(resolveManagedVersions(importedBom))
        }
        return ArtifactMetadata(
            coordinate = resolvedCoordinate,
            packaging = pomModel.packaging ?: binaryArtifact?.extension,
            binaryArtifact = binaryArtifact,
            sourceJar = sourceJar,
            javadocJar = javadocJar,
            dependencies = pomModel.dependencies,
            managedVersions = managedVersions,
        )
    }

    private fun parsePom(path: Path): PomModel {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = documentBuilderFactory.newDocumentBuilder().parse(path.toFile())
        val project = document.documentElement ?: return PomModel()

        val parentElement = project.child("parent")
        val inheritedGroup = parentElement?.childText("groupId")
        val inheritedVersion = parentElement?.childText("version")
        val artifactId = project.childText("artifactId")
        val groupId = project.childText("groupId") ?: inheritedGroup
        val version = project.childText("version") ?: inheritedVersion

        val rawProperties = linkedMapOf<String, String>()
        rawProperties["project.groupId"] = groupId.orEmpty()
        rawProperties["project.artifactId"] = artifactId.orEmpty()
        rawProperties["project.version"] = version.orEmpty()
        rawProperties["pom.groupId"] = groupId.orEmpty()
        rawProperties["pom.artifactId"] = artifactId.orEmpty()
        rawProperties["pom.version"] = version.orEmpty()
        rawProperties["groupId"] = groupId.orEmpty()
        rawProperties["artifactId"] = artifactId.orEmpty()
        rawProperties["version"] = version.orEmpty()
        parentElement?.let { parent ->
            rawProperties["project.parent.groupId"] = parent.childText("groupId").orEmpty()
            rawProperties["project.parent.artifactId"] = parent.childText("artifactId").orEmpty()
            rawProperties["project.parent.version"] = parent.childText("version").orEmpty()
        }
        project.child("properties")
            ?.children()
            ?.forEach { property ->
                rawProperties[property.nodeNameWithoutNamespace()] = property.textContent.orEmpty().trim()
            }
        val properties = rawProperties.mapValues { (_, value) -> value.resolvePropertyReferences(rawProperties) }

        val dependencyManagement = project.child("dependencyManagement")
            ?.child("dependencies")
            ?.children("dependency")
            .orEmpty()
            .mapNotNull { element -> parsePomDependency(element, properties) }
        val managedVersions = linkedMapOf<String, String>()
        val importedBoms = mutableListOf<DependencyCoordinate>()
        dependencyManagement.forEach { dependency ->
            if (dependency.scope == "import" && dependency.type == "pom" && dependency.version != null) {
                importedBoms += DependencyCoordinate(dependency.group, dependency.artifact, dependency.version)
            } else if (dependency.version != null) {
                managedVersions["${dependency.group}:${dependency.artifact}"] = dependency.version
            }
        }

        val dependencies = project.child("dependencies")
            ?.children("dependency")
            .orEmpty()
            .mapNotNull { element -> parsePomDependency(element, properties) }

        return PomModel(
            packaging = project.childText("packaging")?.resolvePropertyReferences(properties) ?: "jar",
            dependencies = dependencies,
            managedVersions = managedVersions,
            importedBoms = importedBoms,
        )
    }

    private fun parsePomDependency(
        element: Element,
        properties: Map<String, String>,
    ): MavenDependency? {
        val group = element.childText("groupId")?.resolvePropertyReferences(properties)?.takeIf { it.isNotBlank() } ?: return null
        val artifact = element.childText("artifactId")?.resolvePropertyReferences(properties)?.takeIf { it.isNotBlank() } ?: return null
        val version = element.childText("version")?.resolvePropertyReferences(properties)?.takeIf { it.isNotBlank() }
        val scope = element.childText("scope")?.resolvePropertyReferences(properties)?.takeIf { it.isNotBlank() }
        val optional = element.childText("optional")?.trim()?.equals("true", ignoreCase = true) == true
        val type = element.childText("type")?.resolvePropertyReferences(properties)?.takeIf { it.isNotBlank() }
        return MavenDependency(
            group = group,
            artifact = artifact,
            version = version,
            scope = scope,
            optional = optional,
            type = type,
        )
    }

    private fun materializeBinaryArtifacts(artifact: Path): List<Path> =
        when (artifact.extension.lowercase()) {
            "aar" -> extractAarArtifacts(artifact)
            else -> listOf(artifact)
        }

    private fun extractAarArtifacts(aar: Path): List<Path> {
        extractionCacheRoot.createDirectories()
        val fingerprint = buildString {
            append(aar.normalize())
            append(':')
            append(runCatching { aar.getLastModifiedTime().toMillis() }.getOrDefault(0L))
            append(':')
            append(runCatching { aar.fileSize() }.getOrDefault(0L))
        }.hashCode().toUInt().toString(16)
        val targetDir = extractionCacheRoot.resolve("${aar.fileName}-$fingerprint")
        val extracted = targetDir.resolve("extracted")
        val marker = targetDir.resolve(".ready")
        if (!marker.exists()) {
            targetDir.createDirectories()
            extracted.createDirectories()
            ZipFile(aar.toFile()).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val output = when {
                        entry.name == "classes.jar" -> extracted.resolve("classes.jar")
                        entry.name.startsWith("libs/") && entry.name.endsWith(".jar") -> extracted.resolve(entry.name.removePrefix("libs/"))
                        else -> null
                    } ?: return@forEach
                    output.parent?.createDirectories()
                    zip.getInputStream(entry).use { input ->
                        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            Files.writeString(marker, "ok")
        }
        return extracted.walk()
            .filter { it.isRegularFile() && it.extension == "jar" }
            .sortedBy { it.toString() }
            .toList()
    }

    private fun dependencyRoot(coordinate: DependencyCoordinate): Path? {
        val root = gradleHome
            .resolve("caches/modules-2/files-2.1")
            .resolve(coordinate.group)
            .resolve(coordinate.artifact)
            .resolve(coordinate.version)
        return root.takeIf { it.exists() }
    }

    private fun resolveArtifactCoordinate(coordinate: DependencyCoordinate): DependencyCoordinate? {
        val candidates = listOf(
            coordinate,
            coordinate.copy(artifact = "${coordinate.artifact}-jvm"),
            coordinate.copy(artifact = "${coordinate.artifact}-android"),
        ).distinctBy { it.notation }
        val candidatesWithRoots = candidates.mapNotNull { candidate ->
            dependencyRoot(candidate)?.let { root -> candidate to root }
        }
        candidatesWithRoots.firstOrNull { (candidate, root) ->
            root.findArtifactFile(candidate, "jar") { name ->
                !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar")
            } != null || root.findArtifactFile(candidate, "aar") != null
        }?.let { return it.first }
        return candidatesWithRoots.firstOrNull()?.first
    }

    private fun Path.findArtifactFile(
        coordinate: DependencyCoordinate,
        extension: String,
        namePredicate: (String) -> Boolean = { true },
    ): Path? = toFile()
        .walkTopDown()
        .filter { file ->
            file.isFile &&
                file.extension == extension &&
                namePredicate(file.name)
        }
        .sortedWith(compareByDescending<java.io.File> { candidate ->
            artifactFilePriority(candidate.name, coordinate)
        }.thenBy { it.name.length })
        .firstOrNull { candidate -> artifactFilePriority(candidate.name, coordinate) > 0 }
        ?.toPath()

    private fun artifactFilePriority(fileName: String, coordinate: DependencyCoordinate): Int {
        val artifact = coordinate.artifact
        val baseArtifact = artifact.removeSuffix("-android").removeSuffix("-jvm")
        val stem = fileName.substringBeforeLast('.')
        return when {
            stem == "$artifact-${coordinate.version}" -> 6
            stem == "$baseArtifact-${coordinate.version}" -> 5
            stem == artifact -> 4
            stem == baseArtifact -> 3
            fileName.startsWith("$artifact-${coordinate.version}") -> 2
            fileName.startsWith("$baseArtifact-${coordinate.version}") -> 1
            else -> 0
        }
    }

    private fun String.resolvePropertyReferences(properties: Map<String, String>): String {
        var current = this
        repeat(8) {
            val next = PROPERTY_REFERENCE.replace(current) { match ->
                properties[match.groupValues[1]] ?: match.value
            }
            if (next == current) return next
            current = next
        }
        return current
    }

    private fun Element.child(name: String): Element? =
        childNodes.asSequence()
            .filterIsInstance<Element>()
            .firstOrNull { child -> child.nodeNameWithoutNamespace() == name }

    private fun Element.children(name: String? = null): List<Element> =
        childNodes.asSequence()
            .filterIsInstance<Element>()
            .filter { child -> name == null || child.nodeNameWithoutNamespace() == name }
            .toList()

    private fun Element.childText(name: String): String? =
        child(name)?.textContent?.trim()?.takeIf { it.isNotBlank() }

    private fun Node.nodeNameWithoutNamespace(): String = localName ?: nodeName.substringAfter(':')

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<Node> =
        sequence {
            for (index in 0 until length) {
                val node = item(index) ?: continue
                yield(node)
            }
        }

    companion object {
        private val PROPERTY_REFERENCE = Regex("""\$\{([^}]+)\}""")
    }
}

private data class TraversalState(
    val coordinate: DependencyCoordinate,
    val managedVersions: Map<String, String>,
)

private data class ArtifactMetadata(
    val coordinate: DependencyCoordinate,
    val packaging: String?,
    val binaryArtifact: Path?,
    val sourceJar: Path?,
    val javadocJar: Path?,
    val dependencies: List<MavenDependency>,
    val managedVersions: Map<String, String>,
)

private data class PomModel(
    val packaging: String? = null,
    val dependencies: List<MavenDependency> = emptyList(),
    val managedVersions: Map<String, String> = emptyMap(),
    val importedBoms: List<DependencyCoordinate> = emptyList(),
)

private data class MavenDependency(
    val group: String,
    val artifact: String,
    val version: String?,
    val scope: String?,
    val optional: Boolean,
    val type: String?,
) {
    fun includeInClasspath(): Boolean =
        !optional && scope !in setOf("test", "provided", "system")
}
