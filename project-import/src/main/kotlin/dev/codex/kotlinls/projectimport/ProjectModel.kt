package dev.codex.kotlinls.projectimport

import java.nio.file.Path

data class ImportedProject(
    val root: Path,
    val modules: List<ImportedModule>,
) {
    val modulesByName: Map<String, ImportedModule> = modules.associateBy { it.name }
    val modulesByGradlePath: Map<String, ImportedModule> = modules.associateBy { it.gradlePath }

    fun moduleForPath(path: Path): ImportedModule? {
        val normalized = path.normalize()
        return modules
            .filter { normalized.startsWith(it.dir.normalize()) }
            .maxByOrNull { it.dir.normalize().nameCount }
    }

    fun moduleClosure(seedModules: Collection<ImportedModule>): List<ImportedModule> {
        if (seedModules.isEmpty()) return emptyList()
        val pending = ArrayDeque(seedModules.map { it.gradlePath })
        val visited = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val gradlePath = pending.removeFirst()
            if (!visited.add(gradlePath)) continue
            modulesByGradlePath[gradlePath]
                ?.projectDependencies
                .orEmpty()
                .forEach(pending::addLast)
        }
        return modules.filter { it.gradlePath in visited }
    }

    fun subsetForModules(seedModules: Collection<ImportedModule>): ImportedProject =
        copy(modules = moduleClosure(seedModules))
}

data class ImportedModule(
    val name: String,
    val gradlePath: String,
    val dir: Path,
    val buildFile: Path?,
    val sourceRoots: List<Path>,
    val javaSourceRoots: List<Path>,
    val testRoots: List<Path>,
    val compilerOptions: CompilerOptions,
    val externalDependencies: List<DependencyCoordinate>,
    val projectDependencies: List<String>,
    val classpathJars: List<Path>,
    val classpathSourceJars: List<Path> = emptyList(),
    val classpathJavadocJars: List<Path> = emptyList(),
)

data class CompilerOptions(
    val jvmTarget: String? = null,
    val languageVersion: String? = null,
    val apiVersion: String? = null,
    val freeCompilerArgs: List<String> = emptyList(),
)

data class DependencyCoordinate(
    val group: String,
    val artifact: String,
    val version: String,
) {
    val notation: String = "$group:$artifact:$version"
}
