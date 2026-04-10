package dev.codex.kotlinls.formatting

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText

fun interface FormatterBridge {
    fun format(path: Path, text: String, timeoutMillis: Long): String?
}

class JetBrainsFormatterBridge private constructor(
    private val javaBinary: Path,
    private val vmOptions: List<String>,
    private val jvmArgs: List<String>,
    private val classpath: List<Path>,
    private val mainClass: String,
    private val ideaHome: Path,
    private val ideaPluginsPath: Path,
) : FormatterBridge {
    override fun format(path: Path, text: String, timeoutMillis: Long): String? {
        val sandboxRoot = Files.createTempDirectory("kotlin-neovim-lsp-idea-bridge")
        return runCatching {
            val command = buildList {
                add(javaBinary.toString())
                addAll(vmOptions)
                addAll(jvmArgs)
                add("-Didea.home.path=$ideaHome")
                add("-Didea.plugins.path=$ideaPluginsPath")
                add("-Didea.config.path=${sandboxRoot.resolve("config")}")
                add("-Didea.system.path=${sandboxRoot.resolve("system")}")
                add("-Didea.log.path=${sandboxRoot.resolve("log")}")
                add("-Djava.awt.headless=true")
                add("-cp")
                add(classpath.joinToString(":"))
                add(mainClass)
                add("format")
                add("-allowDefaults")
                add(path.toString())
            }
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            process.inputStream.readAllBytes()
            if (process.exitValue() != 0) {
                return@runCatching null
            }
            Files.readString(path)
        }.getOrNull()
            .also { sandboxRoot.deleteRecursivelyIfExists() }
    }

    companion object {
        private val mapper = jacksonObjectMapper()

        fun detect(): FormatterBridge? {
            val configured = System.getProperty("kotlinls.intellijHome")
                ?: System.getenv("KOTLINLS_INTELLIJ_HOME")
            if (!configured.isNullOrBlank()) {
                fromIdeaHome(Path.of(configured))?.let { return it }
            }
            if (!intellijBridgeEnabled()) {
                return null
            }
            if ((System.getProperty("kotlinls.disableIntellijBridge")
                    ?: System.getenv("KOTLINLS_DISABLE_INTELLIJ_BRIDGE")) == "true"
            ) {
                return null
            }
            commonIdeaHomes().firstNotNullOfOrNull(::fromIdeaHome)?.let { return it }
            return null
        }

        private fun intellijBridgeEnabled(): Boolean =
            (System.getProperty("kotlinls.enableIntellijBridge")
                ?: System.getenv("KOTLINLS_ENABLE_INTELLIJ_BRIDGE"))
                ?.equals("true", ignoreCase = true) == true

        fun fromIdeaHome(ideaHome: Path): FormatterBridge? {
            val normalizedHome = when (ideaHome.name) {
                "Contents" -> ideaHome
                else -> {
                    val nested = ideaHome.resolve("Contents")
                    if (Files.isDirectory(nested)) nested else ideaHome
                }
            }.normalize()
            val productInfoPath = normalizedHome.resolve("Resources/product-info.json")
            if (!Files.isRegularFile(productInfoPath)) return null
            val productInfo = runCatching { mapper.readValue<ProductInfo>(productInfoPath.toFile()) }.getOrNull() ?: return null
            val launch = productInfo.launch.firstOrNull { candidate ->
                candidate.os == currentOsName() && candidate.arch.equals(currentArchName(), ignoreCase = true)
            } ?: return null
            val javaBinary = normalizedHome.resolve("jbr/Contents/Home/bin/java")
            if (!Files.isRegularFile(javaBinary)) return null
            val vmOptionsPath = resolveResourcePath(productInfoPath, launch.vmOptionsFilePath)
            if (!Files.isRegularFile(vmOptionsPath)) return null
            val appPackage = if (normalizedHome.name == "Contents") normalizedHome.parent else normalizedHome
            val vmOptions = vmOptionsPath.readLines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
            val jvmArgs = launch.additionalJvmArguments
                .map { it.replace("\$APP_PACKAGE", appPackage.toString()) }
                .toMutableList()
                .apply {
                    if (none { it == "--enable-native-access=ALL-UNNAMED" }) {
                        add("--enable-native-access=ALL-UNNAMED")
                    }
                    if (none { it == "--add-modules=jdk.incubator.vector" }) {
                        add("--add-modules=jdk.incubator.vector")
                    }
                }
            val bootClasspath = launch.bootClassPathJarNames
                .map { normalizedHome.resolve("lib/$it") }
                .filter(Files::isRegularFile)
            if (bootClasspath.isEmpty()) return null
            val kotlinPluginLib = normalizedHome.resolve("plugins/Kotlin/lib")
            val kotlinPluginJars = if (Files.isDirectory(kotlinPluginLib)) {
                Files.list(kotlinPluginLib).use { stream ->
                    stream.filter(Files::isRegularFile).filter { it.fileName.toString().endsWith(".jar") }.toList()
                }
            } else {
                emptyList()
            }
            if (kotlinPluginJars.isEmpty()) return null
            return JetBrainsFormatterBridge(
                javaBinary = javaBinary,
                vmOptions = vmOptions,
                jvmArgs = jvmArgs,
                classpath = bootClasspath + kotlinPluginJars,
                mainClass = launch.mainClass,
                ideaHome = normalizedHome,
                ideaPluginsPath = normalizedHome.resolve("plugins"),
            )
        }

        private fun currentOsName(): String =
            when {
                System.getProperty("os.name").lowercase(Locale.US).contains("mac") -> "macOS"
                System.getProperty("os.name").lowercase(Locale.US).contains("win") -> "windows"
                else -> "linux"
            }

        private fun currentArchName(): String =
            when (val arch = System.getProperty("os.arch").lowercase(Locale.US)) {
                "arm64" -> "aarch64"
                else -> arch
            }

        private fun resolveResourcePath(productInfoPath: Path, relativePath: String): Path =
            productInfoPath.parent.resolve(relativePath).normalize()

        private fun commonIdeaHomes(): List<Path> =
            listOf(
                Path.of("/Applications/Android Studio.app/Contents"),
                Path.of("/Applications/IntelliJ IDEA.app/Contents"),
                Path.of("/Applications/IntelliJ IDEA CE.app/Contents"),
            )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ProductInfo(
    val launch: List<LaunchInfo>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class LaunchInfo(
    val os: String,
    val arch: String,
    val vmOptionsFilePath: String,
    val bootClassPathJarNames: List<String>,
    val additionalJvmArguments: List<String>,
    val mainClass: String,
)

private fun Path.deleteRecursivelyIfExists() {
    if (!Files.exists(this)) return
    Files.walk(this).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { entry ->
            Files.deleteIfExists(entry)
        }
    }
}
