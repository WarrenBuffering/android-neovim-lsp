package dev.codex.kotlinls.projectimport

import dev.codex.kotlinls.workspace.ProjectRootDetector
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

class GradleProjectImporter(
    private val rootDetector: ProjectRootDetector = ProjectRootDetector(),
    private val cacheResolver: LocalGradleCacheResolver = LocalGradleCacheResolver(),
    private val persistentCache: PersistentImportedProjectCache = PersistentImportedProjectCache(),
    private val androidAugmentor: AndroidModuleAugmentor = AndroidModuleAugmentor(),
) {
    private val importCache = ConcurrentHashMap<Path, CachedImportedProject>()

    fun loadPersistedProject(start: Path): ImportedProject? {
        val root = rootDetector.detect(start).normalize()
        val settingsFile = sequenceOf(root.resolve("settings.gradle.kts"), root.resolve("settings.gradle"))
            .firstOrNull { it.exists() }
        val moduleLayouts = moduleLayouts(root, settingsFile)
        val fingerprint = buildImportFingerprint(
            root = root,
            settingsFile = settingsFile,
            versionCatalogPath = root.resolve("gradle/libs.versions.toml").takeIf { it.exists() },
            moduleBuildFiles = moduleLayouts.mapNotNull { it.buildFile },
        )
        importCache[root]?.takeIf { it.fingerprint == fingerprint }?.let { return it.project }
        return persistentCache.load(root, fingerprint)?.also { cached ->
            importCache[root] = CachedImportedProject(fingerprint = fingerprint, project = cached)
        }
    }

    fun importProject(start: Path, progress: ((String, Int, Int) -> Unit)? = null): ImportedProject {
        val root = rootDetector.detect(start).normalize()
        val settingsFile = sequenceOf(root.resolve("settings.gradle.kts"), root.resolve("settings.gradle"))
            .firstOrNull { it.exists() }
        val versionCatalogPath = root.resolve("gradle/libs.versions.toml")
        val versionCatalog = parseVersionCatalog(versionCatalogPath)
        val moduleLayouts = moduleLayouts(root, settingsFile)
        val fingerprint = buildImportFingerprint(
            root = root,
            settingsFile = settingsFile,
            versionCatalogPath = versionCatalogPath.takeIf { it.exists() },
            moduleBuildFiles = moduleLayouts.mapNotNull { it.buildFile },
        )
        importCache[root]?.takeIf { it.fingerprint == fingerprint }?.let {
            progress?.invoke("Loaded cached project model", 1, 1)
            return augmentImportedProject(it.project)
        }
        persistentCache.load(root, fingerprint)?.let { cached ->
            importCache[root] = CachedImportedProject(fingerprint = fingerprint, project = cached)
            progress?.invoke("Loaded persisted project model", 1, 1)
            return augmentImportedProject(cached)
        }
        val modules = buildList {
            val totalModules = moduleLayouts.size.coerceAtLeast(1)
            moduleLayouts.forEachIndexed { index, layout ->
            val gradlePath = layout.gradlePath
            val dir = layout.dir
            val buildFile = layout.buildFile
            val sourceRoots = conventionalRoots(dir, listOf("src/main/kotlin", "src/commonMain/kotlin"))
            val javaSourceRoots = conventionalRoots(dir, listOf("src/main/java", "src/commonMain/java"))
            val testRoots = conventionalRoots(
                dir,
                listOf("src/test/kotlin", "src/test/java", "src/jvmTest/kotlin", "src/commonTest/kotlin"),
            )
            val parsedBuild = parseBuildFile(buildFile, versionCatalog)
            val baseDependencies = (parsedBuild.externalDependencies + implicitKotlinJvmDependencies(parsedBuild.kotlinPluginVersion)).distinctBy { it.notation }
            val resolvedClasspath = cacheResolver.resolveClasspath(baseDependencies)
                add(
                    ImportedModule(
                name = if (gradlePath == ":") root.name else gradlePath.substringAfterLast(':'),
                gradlePath = gradlePath,
                dir = dir,
                buildFile = buildFile,
                sourceRoots = sourceRoots,
                javaSourceRoots = javaSourceRoots,
                testRoots = testRoots,
                compilerOptions = parsedBuild.compilerOptions,
                externalDependencies = baseDependencies,
                projectDependencies = parsedBuild.projectDependencies,
                classpathJars = resolvedClasspath.binaries,
                classpathSourceJars = resolvedClasspath.sources,
                classpathJavadocJars = resolvedClasspath.javadocs,
            )
                )
                val moduleLabel = if (gradlePath == ":") root.name else gradlePath.removePrefix(":")
                progress?.invoke("Resolved module $moduleLabel", index + 1, totalModules)
            }
        }
        return augmentImportedProject(ImportedProject(root = root, modules = modules))
            .also { project ->
                importCache[root] = CachedImportedProject(fingerprint = fingerprint, project = project)
                persistentCache.save(root, fingerprint, project)
            }
    }

    private fun conventionalRoots(dir: Path, relativePaths: List<String>): List<Path> =
        relativePaths.map { dir.resolve(it) }.filter { it.exists() && it.isDirectory() }

    private fun moduleLayouts(root: Path, settingsFile: Path?): List<ModuleLayout> {
        val includes = parseModuleIncludes(settingsFile)
        val explicitDirs = parseProjectDirOverrides(settingsFile)
        val modulePaths = if (includes.isEmpty()) listOf(":") else listOf(":") + includes.sorted()
        return modulePaths.distinct().map { gradlePath ->
            val dir = when (gradlePath) {
                ":" -> root
                else -> explicitDirs[gradlePath] ?: root.resolve(gradlePath.removePrefix(":").replace(':', '/'))
            }
            val buildFile = sequenceOf(dir.resolve("build.gradle.kts"), dir.resolve("build.gradle")).firstOrNull { it.exists() }
            ModuleLayout(
                gradlePath = gradlePath,
                dir = dir,
                buildFile = buildFile,
            )
        }
    }

    private fun augmentImportedProject(project: ImportedProject): ImportedProject =
        project.copy(
            modules = project.modules.map { module ->
                val augmentation = androidAugmentor.augment(module.dir, module.buildFile)
                val supplementalArtifacts = cacheResolver.resolveDirectArtifacts(augmentation.resolvedDependencies)
                module.copy(
                    sourceRoots = (module.sourceRoots + augmentation.sourceRoots).distinct(),
                    javaSourceRoots = (module.javaSourceRoots + augmentation.javaSourceRoots).distinct(),
                    externalDependencies = (module.externalDependencies + augmentation.resolvedDependencies).distinctBy { it.notation },
                    classpathJars = (module.classpathJars + augmentation.classpathJars).distinct(),
                    classpathSourceJars = (module.classpathSourceJars + supplementalArtifacts.sources).distinct(),
                    classpathJavadocJars = (module.classpathJavadocJars + supplementalArtifacts.javadocs).distinct(),
                )
            },
        )

    private fun parseModuleIncludes(settingsFile: Path?): List<String> {
        if (settingsFile == null || !settingsFile.exists()) return emptyList()
        val text = settingsFile.readText()
        val includes = mutableSetOf<String>()
        Regex("""include(?:Flat)?\s*\((.*?)\)""", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(text)
            .forEach { match ->
                Regex("""["'](:[^"']+)["']""").findAll(match.groupValues[1]).forEach { include ->
                    includes += include.groupValues[1]
                }
            }
        return includes.toList()
    }

    private fun parseProjectDirOverrides(settingsFile: Path?): Map<String, Path> {
        if (settingsFile == null || !settingsFile.exists()) return emptyMap()
        val text = settingsFile.readText()
        val overrides = linkedMapOf<String, Path>()
        Regex("""project\(["'](:[^"']+)["']\)\.projectDir\s*=\s*file\(["']([^"']+)["']\)""")
            .findAll(text)
            .forEach { match ->
                overrides[match.groupValues[1]] = settingsFile.parent.resolve(match.groupValues[2]).normalize()
            }
        return overrides
    }

    private fun parseVersionCatalog(path: Path): VersionCatalog? {
        if (!path.exists()) return null
        val versions = linkedMapOf<String, String>()
        val libraries = linkedMapOf<String, VersionCatalogLibrary>()
        val plugins = linkedMapOf<String, VersionCatalogPlugin>()
        var section = ""
        path.readText().lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isBlank()) return@forEach
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line.removePrefix("[").removeSuffix("]")
                return@forEach
            }
            when (section) {
                "versions" -> {
                    Regex("""^([A-Za-z0-9_.-]+)\s*=\s*["']([^"']+)["']$""")
                        .find(line)
                        ?.let { match -> versions[match.groupValues[1]] = match.groupValues[2] }
                }

                "libraries" -> {
                    val match = Regex("""^([A-Za-z0-9_.-]+)\s*=\s*\{(.*)\}\s*$""").find(line) ?: return@forEach
                    val key = normalizeCatalogAlias(match.groupValues[1])
                    val body = match.groupValues[2]
                    val module = Regex("""module\s*=\s*["']([^:"']+):([^"']+)["']""").find(body)
                    val group = module?.groupValues?.get(1) ?: Regex("""group\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
                    val artifact = module?.groupValues?.get(2) ?: Regex("""name\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
                    val version = Regex("""version\.ref\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)?.let(versions::get)
                        ?: Regex("""version\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
                    if (group != null && artifact != null) {
                        libraries[key] = VersionCatalogLibrary(group = group, artifact = artifact, version = version)
                    }
                }

                "plugins" -> {
                    val match = Regex("""^([A-Za-z0-9_.-]+)\s*=\s*\{(.*)\}\s*$""").find(line) ?: return@forEach
                    val key = normalizeCatalogAlias(match.groupValues[1])
                    val body = match.groupValues[2]
                    val id = Regex("""id\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1) ?: return@forEach
                    val version = Regex("""version\.ref\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)?.let(versions::get)
                        ?: Regex("""version\s*=\s*["']([^"']+)["']""").find(body)?.groupValues?.get(1)
                    plugins[key] = VersionCatalogPlugin(alias = key, id = id, version = version)
                }
            }
        }
        if (libraries.isEmpty() && plugins.isEmpty()) return null
        return VersionCatalog(libraries = libraries, plugins = plugins)
    }

    private fun normalizeCatalogAlias(alias: String): String = alias.replace('.', '-')

    private fun parseBuildFile(buildFile: Path?, versionCatalog: VersionCatalog?): ParsedBuildFile {
        if (buildFile == null || !buildFile.exists()) return ParsedBuildFile()
        val text = buildFile.readText()
        val platformDependencies = buildList {
            Regex("""platform\(\s*libs\.([A-Za-z0-9_.-]+)\s*\)""")
                .findAll(text)
                .mapNotNullTo(this) { match -> versionCatalog?.resolveLibrary(match.groupValues[1]) }
            Regex("""platform\(\s*["']([^:"']+):([^:"']+):([^"']+)["']\s*\)""")
                .findAll(text)
                .mapTo(this) { match -> DependencyCoordinate(match.groupValues[1], match.groupValues[2], match.groupValues[3]) }
        }.distinctBy { it.notation }
        val managedVersions = platformDependencies
            .flatMap { coordinate -> cacheResolver.resolveManagedVersions(coordinate).entries }
            .associate { entry -> entry.toPair() }
        val externalDependencies = Regex("""(?:implementation|api|compileOnly|runtimeOnly|testImplementation|debugImplementation|androidTestImplementation|ksp|kspTest|coreLibraryDesugaring)\s*\(\s*["']([^:"']+):([^:"']+):([^"']+)["']\s*\)""")
            .findAll(text)
            .map { DependencyCoordinate(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            .toMutableList()
        Regex("""(?:implementation|api|compileOnly|runtimeOnly|testImplementation|debugImplementation|androidTestImplementation|ksp|kspTest|coreLibraryDesugaring)\s*\(\s*libs\.([A-Za-z0-9_.-]+)\s*\)""")
            .findAll(text)
            .mapNotNullTo(externalDependencies) { match ->
                versionCatalog?.resolveLibrary(match.groupValues[1], managedVersions)
            }
        val projectDependencies = Regex("""(?:implementation|api|testImplementation|debugImplementation|androidTestImplementation)\s*\(\s*project\(["'](:[^"']+)["']\)\s*\)""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()
        val freeCompilerArgs = Regex("""freeCompilerArgs(?:\.addAll)?\((.*?)\)""", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(text)
            .flatMap { match -> Regex("""["']([^"']+)["']""").findAll(match.groupValues[1]).map { it.groupValues[1] } }
            .toList()
        val jvmToolchain = Regex("""jvmToolchain\((\d+)\)""").find(text)?.groupValues?.get(1)
        val jvmTarget = Regex("""jvmTarget\s*=\s*JvmTarget\.JVM_(\d+)""").find(text)?.groupValues?.get(1)
            ?: jvmToolchain
        val languageVersion = Regex("""languageVersion\s*=\s*KotlinVersion\.KOTLIN_([0-9_]+)""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.replace('_', '.')
        val apiVersion = Regex("""apiVersion\s*=\s*KotlinVersion\.KOTLIN_([0-9_]+)""")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.replace('_', '.')
        val kotlinPluginVersion = sequenceOf(
            Regex("""kotlin\(["'][^"']+["']\)\s+version\s+["']([^"']+)["']"""),
            Regex("""id\(["']org\.jetbrains\.kotlin\.[^"']+["']\)\s+version\s+["']([^"']+)["']"""),
        ).firstNotNullOfOrNull { regex ->
            regex.find(text)?.groupValues?.get(1)
        } ?: Regex("""alias\(\s*libs\.plugins\.([A-Za-z0-9_.-]+)\s*\)""")
            .findAll(text)
            .mapNotNull { match ->
                versionCatalog?.resolvePlugin(match.groupValues[1])
            }
            .firstOrNull { plugin ->
                plugin.id.startsWith("org.jetbrains.kotlin")
            }
            ?.version
        return ParsedBuildFile(
            compilerOptions = CompilerOptions(
                jvmTarget = jvmTarget,
                languageVersion = languageVersion,
                apiVersion = apiVersion,
                freeCompilerArgs = freeCompilerArgs,
            ),
            kotlinPluginVersion = kotlinPluginVersion,
            externalDependencies = (externalDependencies + platformDependencies).distinctBy { it.notation },
            projectDependencies = projectDependencies,
        )
    }

    private fun implicitKotlinJvmDependencies(version: String?): List<DependencyCoordinate> {
        if (version.isNullOrBlank()) return emptyList()
        return listOf(
            DependencyCoordinate("org.jetbrains.kotlin", "kotlin-stdlib", version),
        )
    }

    private fun buildImportFingerprint(
        root: Path,
        settingsFile: Path?,
        versionCatalogPath: Path?,
        moduleBuildFiles: List<Path>,
    ): String {
        val inputs = buildList {
            add(root.resolve("build.gradle.kts"))
            add(root.resolve("build.gradle"))
            settingsFile?.let(::add)
            versionCatalogPath?.let(::add)
            addAll(moduleBuildFiles)
        }.filter { it.exists() && it.isRegularFile() }
            .distinct()
            .sortedBy { it.toString() }
        return inputs.joinToString(separator = "|") { path ->
            "${path.normalize()}:${runCatching { path.getLastModifiedTime().toMillis() }.getOrDefault(0L)}:${runCatching { path.fileSize() }.getOrDefault(0L)}"
        }
    }
}

private data class ParsedBuildFile(
    val compilerOptions: CompilerOptions = CompilerOptions(),
    val kotlinPluginVersion: String? = null,
    val externalDependencies: List<DependencyCoordinate> = emptyList(),
    val projectDependencies: List<String> = emptyList(),
)

private data class VersionCatalog(
    val libraries: Map<String, VersionCatalogLibrary>,
    val plugins: Map<String, VersionCatalogPlugin>,
) {
    fun resolveLibrary(alias: String, managedVersions: Map<String, String> = emptyMap()): DependencyCoordinate? =
        libraries[alias.replace('.', '-')]
            ?.let { library ->
                val version = library.version ?: managedVersions["${library.group}:${library.artifact}"] ?: return null
                DependencyCoordinate(library.group, library.artifact, version)
            }

    fun resolvePlugin(alias: String): VersionCatalogPlugin? = plugins[alias.replace('.', '-')]
}

private data class VersionCatalogLibrary(
    val group: String,
    val artifact: String,
    val version: String?,
)

private data class VersionCatalogPlugin(
    val alias: String,
    val id: String,
    val version: String?,
)

private data class ModuleLayout(
    val gradlePath: String,
    val dir: Path,
    val buildFile: Path?,
)

private data class CachedImportedProject(
    val fingerprint: String,
    val project: ImportedProject,
)
