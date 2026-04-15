package dev.codex.kotlinls.protocol

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

object IntellijHomeLocator {
    private const val INTELLIJ_HOME_PROPERTY = "kotlinls.intellijHome"
    private const val INTELLIJ_HOME_ENV = "KOTLINLS_INTELLIJ_HOME"
    private const val ANDROID_SDK_ROOT_ENV = "ANDROID_SDK_ROOT"
    private const val ANDROID_HOME_ENV = "ANDROID_HOME"
    private val localPropertyKeys = listOf(
        INTELLIJ_HOME_PROPERTY,
        "androidStudio.dir",
    )
    private val androidSdkPropertyKeys = listOf("sdk.dir")

    fun configuredIdeaHome(projectRoot: Path? = null): Path? {
        configuredIdeaHomeFromProcess()?.let { return it }
        configuredIdeaHomeFromLocalProperties(projectRoot)?.let { return it }
        return null
    }

    fun configuredAndroidSdk(projectRoot: Path? = null): Path? {
        configuredAndroidSdkFromProcess()?.let { return it }
        configuredAndroidSdkFromLocalProperties(projectRoot)?.let { return it }
        return null
    }

    private fun configuredIdeaHomeFromProcess(): Path? {
        val configured = System.getProperty(INTELLIJ_HOME_PROPERTY)
            ?: System.getenv(INTELLIJ_HOME_ENV)
        return configured?.takeIf { it.isNotBlank() }?.let(Path::of)?.normalize()
    }

    private fun configuredIdeaHomeFromLocalProperties(projectRoot: Path?): Path? =
        localPropertiesCandidates(projectRoot)
            .mapNotNull(::readIdeaHome)
            .firstOrNull()

    private fun configuredAndroidSdkFromProcess(): Path? =
        sequenceOf(
            System.getenv(ANDROID_SDK_ROOT_ENV),
            System.getenv(ANDROID_HOME_ENV),
        )
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .map(Path::of)
            .firstOrNull()

    private fun configuredAndroidSdkFromLocalProperties(projectRoot: Path?): Path? =
        localPropertiesCandidates(projectRoot)
            .mapNotNull(::readAndroidSdk)
            .firstOrNull()

    private fun localPropertiesCandidates(projectRoot: Path?): Sequence<Path> {
        val start = projectRoot
            ?.toAbsolutePath()
            ?.normalize()
            ?: System.getProperty("user.dir")
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of)
                ?.toAbsolutePath()
                ?.normalize()
            ?: return emptySequence()
        return ancestorDirectories(start)
            .map { it.resolve("local.properties") }
            .filter(Files::isRegularFile)
    }

    private fun ancestorDirectories(start: Path): Sequence<Path> =
        generateSequence(start) { current -> current.parent }

    private fun readIdeaHome(localPropertiesPath: Path): Path? {
        val properties = Properties()
        if (
            runCatching {
                Files.newBufferedReader(localPropertiesPath).use(properties::load)
            }.isFailure
        ) {
            return null
        }
        return localPropertyKeys.asSequence()
            .mapNotNull { key -> properties.getProperty(key)?.trim() }
            .firstOrNull { value -> value.isNotBlank() }
            ?.let(Path::of)
            ?.normalize()
    }

    private fun readAndroidSdk(localPropertiesPath: Path): Path? {
        val properties = Properties()
        if (
            runCatching {
                Files.newBufferedReader(localPropertiesPath).use(properties::load)
            }.isFailure
        ) {
            return null
        }
        return androidSdkPropertyKeys.asSequence()
            .mapNotNull { key -> properties.getProperty(key)?.trim() }
            .firstOrNull { value -> value.isNotBlank() }
            ?.let(Path::of)
            ?.normalize()
    }
}
