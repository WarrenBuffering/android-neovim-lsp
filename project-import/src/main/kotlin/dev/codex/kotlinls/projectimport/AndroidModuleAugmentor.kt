package dev.codex.kotlinls.projectimport

import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk
import org.w3c.dom.Element
import org.w3c.dom.Node

class AndroidModuleAugmentor {
    internal fun augment(moduleDir: Path, buildFile: Path?): AndroidModuleAugmentation {
        val buildText = buildFile?.takeIf { it.exists() }?.readText().orEmpty()
        if (!isAndroidModule(buildText)) return AndroidModuleAugmentation()

        val classpathJars = linkedSetOf<Path>()
        val sourceRoots = linkedSetOf<Path>()
        val javaSourceRoots = linkedSetOf<Path>()
        val resolvedDependencies = linkedSetOf<DependencyCoordinate>()

        resolveAndroidSdkJar(moduleDir, buildText)?.let(classpathJars::add)
        resolveGeneratedSourceRoots(moduleDir).forEach { root ->
            when (root.kind) {
                GeneratedSourceKind.KOTLIN -> sourceRoots.add(root.path)
                GeneratedSourceKind.JAVA -> javaSourceRoots.add(root.path)
            }
        }
        resolveGeneratedClasspaths(moduleDir).forEach(classpathJars::add)
        parseResolvedLibraries(moduleDir).forEach { library ->
            classpathJars.addAll(library.jars)
            library.coordinate?.let(resolvedDependencies::add)
        }

        return AndroidModuleAugmentation(
            sourceRoots = sourceRoots.toList(),
            javaSourceRoots = javaSourceRoots.toList(),
            classpathJars = classpathJars.toList(),
            resolvedDependencies = resolvedDependencies.toList(),
        )
    }

    private fun isAndroidModule(buildText: String): Boolean =
        listOf(
            Regex("""id\(["']com\.android\.(application|library|test)["']\)"""),
            Regex("""alias\(\s*libs\.plugins\.android\.[A-Za-z0-9_.-]+\s*\)"""),
            Regex("""\bandroid\s*\{"""),
        ).any { regex -> regex.containsMatchIn(buildText) }

    private fun resolveAndroidSdkJar(moduleDir: Path, buildText: String): Path? {
        val compileSdk = Regex("""compileSdk\s*=\s*(\d+)""").find(buildText)?.groupValues?.get(1) ?: return null
        val sdkDir = locateAndroidSdkDir(moduleDir) ?: return null
        val androidJar = sdkDir.resolve("platforms/android-$compileSdk/android.jar")
        return androidJar.takeIf { it.exists() }
    }

    private fun locateAndroidSdkDir(moduleDir: Path): Path? {
        generateSequence(moduleDir.normalize()) { current -> current.parent }
            .map { it.resolve("local.properties") }
            .firstOrNull { it.exists() }
            ?.readText()
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { line -> line.startsWith("sdk.dir=") }
            ?.substringAfter('=')
            ?.replace("\\:", ":")
            ?.let(Path::of)
            ?.takeIf { it.exists() }
            ?.let { return it }
        sequenceOf(
            System.getenv("ANDROID_SDK_ROOT"),
            System.getenv("ANDROID_HOME"),
            Path.of(System.getProperty("user.home"), "Library/Android/sdk").toString(),
        ).filterNotNull()
            .map(Path::of)
            .firstOrNull { it.exists() }
            ?.let { return it }
        return null
    }

    private fun resolveGeneratedSourceRoots(moduleDir: Path): List<GeneratedSourceRoot> {
        val generatedRoot = moduleDir.resolve("build/generated")
        if (!generatedRoot.exists()) return emptyList()

        val roots = linkedSetOf<GeneratedSourceRoot>()
        fun addIfDirectory(path: Path, kind: GeneratedSourceKind) {
            if (path.exists() && path.isDirectory()) {
                roots += GeneratedSourceRoot(path.normalize(), kind)
            }
        }

        generatedRoot.resolve("ksp").takeIf { it.exists() }?.walk()?.forEach { path ->
            when (path.name) {
                "java" -> addIfDirectory(path, GeneratedSourceKind.JAVA)
                "kotlin" -> addIfDirectory(path, GeneratedSourceKind.KOTLIN)
            }
        }

        generatedRoot.resolve("hilt/component_sources").takeIf { it.exists() }?.walk()?.forEach { path ->
            if (!path.isDirectory()) return@forEach
            val parentName = path.parent?.name ?: return@forEach
            if (parentName == "component_sources") {
                addIfDirectory(path, GeneratedSourceKind.JAVA)
            }
        }

        generatedRoot.resolve("source/buildConfig").takeIf { it.exists() }?.let { buildConfigRoot ->
            buildConfigRoot.walk().forEach { path ->
                if (!path.isRegularFile() || path.fileName.toString() != "BuildConfig.java") return@forEach
                buildConfigVariantRoot(buildConfigRoot, path)?.let { root ->
                    addIfDirectory(root, GeneratedSourceKind.JAVA)
                }
            }
        }

        generatedRoot.resolve("ap_generated_sources").takeIf { it.exists() }?.walk()?.forEach { path ->
            if (path.isDirectory() && path.name == "out") {
                addIfDirectory(path, GeneratedSourceKind.JAVA)
            }
        }

        generatedRoot.resolve("source/kapt").takeIf { it.exists() }?.walk()?.forEach { path ->
            if (path.isDirectory() && path.parent?.name == "kapt") {
                addIfDirectory(path, GeneratedSourceKind.JAVA)
            }
        }

        generatedRoot.resolve("source/kaptKotlin").takeIf { it.exists() }?.walk()?.forEach { path ->
            if (path.isDirectory() && path.parent?.name == "kaptKotlin") {
                addIfDirectory(path, GeneratedSourceKind.KOTLIN)
            }
        }

        return roots.toList()
    }

    private fun resolveGeneratedClasspaths(moduleDir: Path): List<Path> {
        val buildRoot = moduleDir.resolve("build")
        if (!buildRoot.exists()) return emptyList()
        val generatedArtifacts = linkedSetOf<Path>()
        buildRoot.walk()
            .filter(::isNonTestBuildArtifact)
            .forEach { path ->
                val normalized = path.normalize()
                when {
                    path.isRegularFile() && path.fileName.toString() == "R.jar" -> generatedArtifacts.add(normalized)
                    path.isRegularFile() && path.fileName.toString() == "BuildConfig.class" ->
                        generatedClassRoot(normalized)?.let(generatedArtifacts::add)
                }
            }
        return generatedArtifacts.toList()
    }

    private fun generatedClassRoot(path: Path): Path? {
        var current: Path? = path.parent
        while (current != null) {
            val normalized = current.normalize().toString()
            when {
                current.name == "classes" && "/build/intermediates/javac/" in normalized -> return current.normalize()
                current.name == "dirs" && "/build/intermediates/classes/" in normalized -> return current.normalize()
            }
            current = current.parent
        }
        return null
    }

    private fun parseResolvedLibraries(moduleDir: Path): List<ResolvedLibraryEntry> {
        val modelFile = selectArtifactLibrariesFile(moduleDir) ?: return emptyList()
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = runCatching { documentBuilderFactory.newDocumentBuilder().parse(modelFile.toFile()) }.getOrNull() ?: return emptyList()
        return document.documentElement
            ?.children("library")
            .orEmpty()
            .mapNotNull { element ->
                val resolved = element.getAttribute("resolved").takeIf { it.isNotBlank() }
                val jars = element.getAttribute("jars")
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .map(Path::of)
                    .filter { it.exists() }
                    .map(Path::normalize)
                if (jars.isEmpty() && resolved == null) return@mapNotNull null
                ResolvedLibraryEntry(
                    coordinate = resolved?.let(::parseCoordinate),
                    jars = jars,
                )
            }
    }

    private fun selectArtifactLibrariesFile(moduleDir: Path): Path? {
        val intermediates = moduleDir.resolve("build/intermediates")
        if (!intermediates.exists()) return null
        return intermediates.walk()
            .filter { path -> path.isRegularFile() && path.fileName.toString().endsWith("artifact-libraries.xml") }
            .filter(::isNonTestBuildArtifact)
            .maxWithOrNull(
                compareBy<Path>({ artifactLibrariesPriority(it) }, { runCatching { it.getLastModifiedTime().toMillis() }.getOrDefault(0L) }),
            )
    }

    private fun artifactLibrariesPriority(path: Path): Int {
        val normalized = path.normalize().toString()
        return when {
            "lint_report_lint_model" in normalized -> 3
            "incremental/lintAnalyze" in normalized -> 2
            else -> 1
        }
    }

    private fun isNonTestBuildArtifact(path: Path): Boolean {
        val normalized = path.normalize().toString()
        return listOf(
            "AndroidTest",
            "UnitTest",
            "android_test_",
            "unit_test_",
            "/androidTest/",
            "/test/",
        ).none { marker -> marker in normalized }
    }

    private fun parseCoordinate(notation: String): DependencyCoordinate? {
        val parts = notation.split(':', limit = 3)
        if (parts.size != 3) return null
        val group = parts[0].trim()
        val artifact = parts[1].trim()
        val version = parts[2].trim()
        if (group.isBlank() || artifact.isBlank() || version.isBlank()) return null
        return DependencyCoordinate(group = group, artifact = artifact, version = version)
    }

    private fun buildConfigVariantRoot(buildConfigRoot: Path, buildConfigFile: Path): Path? {
        val relative = runCatching { buildConfigRoot.relativize(buildConfigFile.normalize()) }.getOrNull() ?: return null
        val packageSegments = runCatching {
            buildConfigFile.readText()
                .lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("package ") }
                ?.removePrefix("package ")
                ?.removeSuffix(";")
                ?.trim()
                ?.split('.')
                ?.filter(String::isNotBlank)
                ?.size
        }.getOrNull() ?: 0
        val variantSegmentCount = relative.nameCount - 1 - packageSegments
        if (variantSegmentCount <= 0) return null
        return buildConfigRoot.resolve(relative.subpath(0, variantSegmentCount)).normalize()
    }

    private fun Element.children(name: String? = null): List<Element> =
        childNodes.asSequence()
            .filterIsInstance<Element>()
            .filter { child -> name == null || child.nodeNameWithoutNamespace() == name }
            .toList()

    private fun Node.nodeNameWithoutNamespace(): String = localName ?: nodeName.substringAfter(':')

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<Node> =
        sequence {
            for (index in 0 until length) {
                val node = item(index) ?: continue
                yield(node)
            }
        }
}

data class AndroidModuleAugmentation(
    val sourceRoots: List<Path> = emptyList(),
    val javaSourceRoots: List<Path> = emptyList(),
    val classpathJars: List<Path> = emptyList(),
    val resolvedDependencies: List<DependencyCoordinate> = emptyList(),
)

private data class ResolvedLibraryEntry(
    val coordinate: DependencyCoordinate?,
    val jars: List<Path>,
)

private data class GeneratedSourceRoot(
    val path: Path,
    val kind: GeneratedSourceKind,
)

private enum class GeneratedSourceKind {
    KOTLIN,
    JAVA,
}
