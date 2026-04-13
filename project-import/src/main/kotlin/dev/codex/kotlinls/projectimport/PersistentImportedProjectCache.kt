package dev.codex.kotlinls.projectimport

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PersistentImportedProjectCache(
    private val cacheRoot: Path = defaultProjectCacheRoot(),
) {
    private val mapper = jacksonObjectMapper()
        .registerKotlinModule()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    fun load(root: Path, fingerprint: String): ImportedProject? {
        val cacheFile = cacheFile(root)
        if (!cacheFile.exists()) return null
        return runCatching {
            val persisted = mapper.readValue(cacheFile.toFile(), PersistedImportedProjectCache::class.java)
            if (persisted.schemaVersion != SCHEMA_VERSION) return null
            if (persisted.projectRoot != root.normalize().toString()) return null
            if (persisted.fingerprint != fingerprint) return null
            persisted.toImportedProject()
        }.getOrNull()
    }

    fun save(root: Path, fingerprint: String, project: ImportedProject) {
        val cacheFile = cacheFile(root)
        runCatching {
            cacheFile.parent?.createDirectories()
            val tempFile = cacheFile.resolveSibling("${cacheFile.fileName}.tmp")
            val payload = PersistedImportedProjectCache(
                schemaVersion = SCHEMA_VERSION,
                fingerprint = fingerprint,
                projectRoot = project.root.normalize().toString(),
                modules = project.modules.map { module ->
                    PersistedImportedModule(
                        name = module.name,
                        gradlePath = module.gradlePath,
                        dir = module.dir.normalize().toString(),
                        buildFile = module.buildFile?.normalize()?.toString(),
                        sourceRoots = module.sourceRoots.map { it.normalize().toString() },
                        javaSourceRoots = module.javaSourceRoots.map { it.normalize().toString() },
                        testRoots = module.testRoots.map { it.normalize().toString() },
                        compilerOptions = module.compilerOptions,
                        externalDependencies = module.externalDependencies,
                        projectDependencies = module.projectDependencies,
                        classpathJars = module.classpathJars.map { it.normalize().toString() },
                        classpathSourceJars = module.classpathSourceJars.map { it.normalize().toString() },
                        classpathJavadocJars = module.classpathJavadocJars.map { it.normalize().toString() },
                    )
                },
            )
            mapper.writeValue(tempFile.toFile(), payload)
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun cacheFile(root: Path): Path =
        cacheRoot.resolve(projectKey(root)).resolve("imported-project.json")

    private fun projectKey(root: Path): String = sha256(root.normalize().toString()).take(24)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val SCHEMA_VERSION = 2

        private fun defaultProjectCacheRoot(): Path {
            val userHome = Path.of(System.getProperty("user.home"))
            val osName = System.getProperty("os.name").lowercase()
            return when {
                "mac" in osName -> userHome.resolve("Library/Caches/android-neovim-lsp")
                else -> userHome.resolve(".cache/android-neovim-lsp")
            }.resolve("project-model")
        }
    }
}

private data class PersistedImportedProjectCache(
    val schemaVersion: Int,
    val fingerprint: String,
    val projectRoot: String,
    val modules: List<PersistedImportedModule>,
) {
    fun toImportedProject(): ImportedProject =
        ImportedProject(
            root = Path.of(projectRoot),
            modules = modules.map { module ->
                ImportedModule(
                    name = module.name,
                    gradlePath = module.gradlePath,
                    dir = Path.of(module.dir),
                    buildFile = module.buildFile?.let(Path::of),
                    sourceRoots = module.sourceRoots.map(Path::of),
                    javaSourceRoots = module.javaSourceRoots.map(Path::of),
                    testRoots = module.testRoots.map(Path::of),
                    compilerOptions = module.compilerOptions,
                    externalDependencies = module.externalDependencies,
                    projectDependencies = module.projectDependencies,
                    classpathJars = module.classpathJars.map(Path::of),
                    classpathSourceJars = module.classpathSourceJars.map(Path::of),
                    classpathJavadocJars = module.classpathJavadocJars.map(Path::of),
                )
            },
        )
}

private data class PersistedImportedModule(
    val name: String,
    val gradlePath: String,
    val dir: String,
    val buildFile: String?,
    val sourceRoots: List<String>,
    val javaSourceRoots: List<String>,
    val testRoots: List<String>,
    val compilerOptions: CompilerOptions,
    val externalDependencies: List<DependencyCoordinate>,
    val projectDependencies: List<String>,
    val classpathJars: List<String>,
    val classpathSourceJars: List<String>,
    val classpathJavadocJars: List<String>,
)
