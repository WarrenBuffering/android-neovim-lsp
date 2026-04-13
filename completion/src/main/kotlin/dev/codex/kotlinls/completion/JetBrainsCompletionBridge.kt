package dev.codex.kotlinls.completion

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.readValue
import dev.codex.kotlinls.protocol.Json
import dev.codex.kotlinls.protocol.Hover
import dev.codex.kotlinls.protocol.Location
import dev.codex.kotlinls.protocol.MarkupContent
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.Range
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.io.path.name
import kotlin.io.path.readLines

class JetBrainsCompletionBridge private constructor(
    private val javaBinary: Path,
    private val vmOptions: List<String>,
    private val jvmArgs: List<String>,
    private val classpath: List<Path>,
    private val mainClass: String,
    private val ideaHome: Path,
    private val pluginJar: Path,
    private val extraPluginJars: List<Path>,
) : AutoCloseable {
    private val requestIds = AtomicLong(1)
    private val sandboxRoot = Files.createTempDirectory("android-neovim-lsp-jb-completion")
    private val process: Process
    private val socket: Socket
    private val reader: BufferedReader
    private val writer: BufferedWriter

    init {
        val pluginDir = preparePluginDir()
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        serverSocket.soTimeout = 15_000
        process = ProcessBuilder(
            buildList {
                add(javaBinary.toString())
                addAll(vmOptions)
                addAll(jvmArgs)
                add("-Didea.home.path=$ideaHome")
                add("-Didea.plugins.path=$pluginDir")
                add("-Didea.config.path=${sandboxRoot.resolve("config")}")
                add("-Didea.system.path=${sandboxRoot.resolve("system")}")
                add("-Didea.log.path=${sandboxRoot.resolve("log")}")
                add("-Dapple.awt.UIElement=true")
                add("-Didea.kotlin.plugin.use.k2=true")
                add("-Didea.load.plugins.id=org.jetbrains.kotlin,com.intellij.java,dev.codex.kotlinls.jetbrains-bridge")
                add("-Didea.required.plugins.id=org.jetbrains.kotlin,com.intellij.java,dev.codex.kotlinls.jetbrains-bridge")
                add("-cp")
                add(classpath.joinToString(":"))
                add(mainClass)
                add("kotlinls-bridge")
                add(serverSocket.localPort.toString())
            },
        )
            .redirectErrorStream(true)
            .start()
        drainProcessOutput(process.inputStream)
        socket = try {
            serverSocket.accept().also {
                it.soTimeout = 120_000
            }
        } finally {
            serverSocket.close()
        }
        reader = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
        writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        val ready = readResponse()
        require(ready.success && ready.message == "ready") {
            "JetBrains completion bridge failed to start: ${ready.error ?: ready.message}"
        }
        Runtime.getRuntime().addShutdownHook(
            thread(start = false, name = "kotlinls-jb-completion-shutdown") {
                close()
            },
        )
    }

    val detectedIdeaHome: Path
        get() = ideaHome

    @Synchronized
    fun syncDocument(
        projectRoot: Path,
        filePath: Path? = null,
        text: String? = null,
    ): Boolean {
        return sendRequest(
            method = "sync",
            payload = payloadFor(projectRoot, filePath, text, offset = 0, limit = 0),
        ).success
    }

    @Synchronized
    fun prime(
        projectRoot: Path,
        filePath: Path? = null,
        text: String? = null,
    ): Boolean = syncDocument(projectRoot, filePath, text)

    @Synchronized
    fun ensureProject(projectRoot: Path): Boolean =
        sendRequest(
            method = "sync",
            payload = payloadFor(projectRoot, filePath = null, text = null, offset = 0, limit = 0),
        ).success

    @Synchronized
    fun complete(
        projectRoot: Path,
        filePath: Path,
        text: String,
        offset: Int,
        limit: Int = 100,
    ): List<JetBrainsBridgeCompletion>? {
        val response = sendRequest(
            method = "complete",
            payload = payloadFor(projectRoot, filePath, text, offset, limit),
        )
        if (!response.success) return null
        return response.items
    }

    @Synchronized
    fun hover(
        projectRoot: Path,
        filePath: Path,
        text: String,
        offset: Int,
    ): Hover? {
        val response = sendRequest(
            method = "hover",
            payload = payloadFor(projectRoot, filePath, text, offset, limit = 0),
        )
        if (!response.success) return null
        val markdown = response.hoverMarkdown?.takeIf { it.isNotBlank() } ?: return null
        return Hover(MarkupContent(kind = "markdown", value = markdown))
    }

    @Synchronized
    fun definition(
        projectRoot: Path,
        filePath: Path,
        text: String,
        offset: Int,
    ): List<Location>? {
        val response = sendRequest(
            method = "definition",
            payload = payloadFor(projectRoot, filePath, text, offset, limit = 0),
        )
        if (!response.success) return null
        return response.locations.map { it.toProtocol() }
    }

    @Synchronized
    fun formatDocument(
        projectRoot: Path,
        filePath: Path,
        text: String,
    ): String? {
        val response = sendRequest(
            method = "format",
            payload = payloadFor(
                projectRoot = projectRoot,
                filePath = filePath,
                text = text,
                offset = 0,
                limit = 0,
                rangeStart = -1,
                rangeEnd = -1,
            ),
        )
        if (!response.success) return null
        return response.formattedText
    }

    @Synchronized
    fun formatRange(
        projectRoot: Path,
        filePath: Path,
        text: String,
        startOffset: Int,
        endOffset: Int,
    ): String? {
        val response = sendRequest(
            method = "format",
            payload = payloadFor(
                projectRoot = projectRoot,
                filePath = filePath,
                text = text,
                offset = 0,
                limit = 0,
                rangeStart = startOffset,
                rangeEnd = endOffset,
            ),
        )
        if (!response.success) return null
        return response.formattedText
    }

    @Synchronized
    override fun close() {
        runCatching { writer.close() }
        runCatching { reader.close() }
        runCatching { socket.close() }
        runCatching {
            if (process.isAlive) {
                process.destroy()
                process.waitFor()
            }
        }
        sandboxRoot.deleteRecursivelyIfExists()
    }

    private fun payloadFor(
        projectRoot: Path,
        filePath: Path?,
        text: String?,
        offset: Int,
        limit: Int,
        rangeStart: Int = -1,
        rangeEnd: Int = -1,
    ): CompletionPayload =
        if (filePath != null && text != null) {
            CompletionPayload(
                projectRoot = projectRoot.toString(),
                filePath = filePath.toString(),
                text = text,
                offset = offset,
                limit = limit,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
            )
        } else {
            CompletionPayload(
                projectRoot = projectRoot.toString(),
                filePath = "",
                text = "",
                offset = offset,
                limit = limit,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
            )
        }

    private fun sendRequest(
        method: String,
        payload: CompletionPayload,
    ): BridgeResponse {
        val request = BridgeRequest(
            id = requestIds.getAndIncrement(),
            method = method,
            payload = payload,
        )
        writer.write(Json.mapper.writeValueAsString(request))
        writer.write('\n'.code)
        writer.flush()
        return readResponse()
    }

    private fun preparePluginDir(): Path {
        val pluginsRoot = sandboxRoot.resolve("plugins")
        val pluginLibDir = pluginsRoot.resolve("kotlinls-bridge/lib")
        Files.createDirectories(pluginLibDir)
        copyIfNeeded(pluginJar, pluginLibDir.resolve(pluginJar.fileName))
        extraPluginJars.forEach { jar ->
            copyIfNeeded(jar, pluginLibDir.resolve(jar.fileName))
        }
        return pluginsRoot
    }

    private fun readResponse(): BridgeResponse = Json.mapper.readValue(requireNotNull(reader.readLine()) { "Bridge disconnected" })

    private fun drainProcessOutput(stream: InputStream) {
        val verbose = (System.getProperty("kotlinls.debugIntellijCompletionBridge")
            ?: System.getenv("KOTLINLS_DEBUG_INTELLIJ_COMPLETION_BRIDGE")) == "true"
        thread(isDaemon = true, name = "kotlinls-jb-completion-stdout") {
            stream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (verbose) {
                        System.err.println("[android-neovim-lsp][jetbrains-completion] $line")
                    }
                }
            }
        }
    }

    companion object {
        fun detect(forceEnable: Boolean = false): JetBrainsCompletionBridge? {
            val configured = System.getProperty("kotlinls.intellijHome")
                ?: System.getenv("KOTLINLS_INTELLIJ_HOME")
            if (!configured.isNullOrBlank()) {
                val pluginJar = locatePluginJar() ?: return null
                val extraPluginJars = locateSupplementalPluginJars()
                return fromIdeaHome(Path.of(configured), pluginJar, extraPluginJars)
            }
            if (!forceEnable && !intellijBridgeEnabled()) {
                return null
            }
            if ((System.getProperty("kotlinls.disableIntellijCompletionBridge")
                    ?: System.getenv("KOTLINLS_DISABLE_INTELLIJ_COMPLETION_BRIDGE")) == "true"
            ) {
                return null
            }
            val pluginJar = locatePluginJar() ?: return null
            val extraPluginJars = locateSupplementalPluginJars()
            val homes = buildList {
                addAll(commonIdeaHomes())
            }
            return homes.firstNotNullOfOrNull { fromIdeaHome(it, pluginJar, extraPluginJars) }
        }

        private fun intellijBridgeEnabled(): Boolean =
            (System.getProperty("kotlinls.enableIntellijBridge")
                ?: System.getenv("KOTLINLS_ENABLE_INTELLIJ_BRIDGE"))
                ?.equals("true", ignoreCase = true) == true

        private fun fromIdeaHome(
            ideaHome: Path,
            pluginJar: Path,
            extraPluginJars: List<Path>,
        ): JetBrainsCompletionBridge? {
            val normalizedHome = when (ideaHome.name) {
                "Contents" -> ideaHome
                else -> ideaHome.resolve("Contents").takeIf(Files::isDirectory) ?: ideaHome
            }.normalize()
            val productInfoPath = normalizedHome.resolve("Resources/product-info.json")
            if (!Files.isRegularFile(productInfoPath)) return null
            val productInfo = runCatching { Json.mapper.readValue<ProductInfo>(productInfoPath.toFile()) }.getOrNull() ?: return null
            val launch = productInfo.launch.firstOrNull { candidate ->
                candidate.os == currentOsName() && candidate.arch.equals(currentArchName(), ignoreCase = true)
            } ?: return null
            val javaBinary = normalizedHome.resolve("jbr/Contents/Home/bin/java")
            if (!Files.isRegularFile(javaBinary)) return null
            val vmOptionsPath = productInfoPath.parent.resolve(launch.vmOptionsFilePath).normalize()
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
                }
            val bootClasspath = launch.bootClassPathJarNames
                .map { normalizedHome.resolve("lib/$it") }
                .filter(Files::isRegularFile)
            val kotlinPluginLib = normalizedHome.resolve("plugins/Kotlin/lib")
            val kotlinPluginCompilerLib = normalizedHome.resolve("plugins/Kotlin/kotlinc/lib")
            val kotlinPluginJars = mutableListOf<Path>()
            if (Files.isDirectory(kotlinPluginLib)) {
                Files.list(kotlinPluginLib).use { stream ->
                    kotlinPluginJars += stream.filter(Files::isRegularFile)
                        .filter { it.fileName.toString().endsWith(".jar") }
                        .toList()
                }
            }
            if (Files.isDirectory(kotlinPluginCompilerLib)) {
                Files.list(kotlinPluginCompilerLib).use { stream ->
                    kotlinPluginJars += stream.filter(Files::isRegularFile)
                        .filter { it.fileName.toString().endsWith(".jar") }
                        .toList()
                }
            }
            if (bootClasspath.isEmpty() || kotlinPluginJars.isEmpty()) return null
            return runCatching {
                JetBrainsCompletionBridge(
                    javaBinary = javaBinary,
                    vmOptions = vmOptions,
                    jvmArgs = jvmArgs,
                    classpath = (bootClasspath + kotlinPluginJars).distinct(),
                    mainClass = launch.mainClass,
                    ideaHome = normalizedHome,
                    pluginJar = pluginJar,
                    extraPluginJars = extraPluginJars,
                )
            }.getOrNull()
        }

        private fun locatePluginJar(): Path? {
            val marker = "META-INF/kotlinls-bridge-plugin.marker"
            val resources = Thread.currentThread().contextClassLoader.getResources(marker)
            while (resources.hasMoreElements()) {
                val url = resources.nextElement()
                val jarUrl = url.toString()
                if (!jarUrl.startsWith("jar:file:")) continue
                val rawPath = jarUrl.removePrefix("jar:file:").substringBefore("!/$marker")
                return Path.of(URLDecoder.decode(rawPath, StandardCharsets.UTF_8))
            }
            return System.getProperty("java.class.path")
                .split(java.io.File.pathSeparatorChar)
                .map { Path.of(it) }
                .firstOrNull { it.fileName.toString().contains("jetbrains-bridge-plugin") && Files.isRegularFile(it) }
        }

        private fun locateSupplementalPluginJars(): List<Path> =
            System.getProperty("java.class.path")
                .split(java.io.File.pathSeparatorChar)
                .map { Path.of(it) }
                .filter { path ->
                    val name = path.fileName.toString()
                    Files.isRegularFile(path) && (
                        name.startsWith("jackson-annotations") ||
                            name.startsWith("jackson-core") ||
                            name.startsWith("jackson-databind")
                        )
                }

        private fun commonIdeaHomes(): List<Path> =
            listOf(
                Path.of("/Applications/Android Studio.app/Contents"),
                Path.of("/Applications/IntelliJ IDEA.app/Contents"),
                Path.of("/Applications/IntelliJ IDEA CE.app/Contents"),
            )

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
    }
}

data class JetBrainsBridgeCompletion(
    val label: String,
    val lookupString: String,
    val allLookupStrings: List<String> = emptyList(),
    val detail: String? = null,
    val kind: String? = null,
    val fqName: String? = null,
    val packageName: String? = null,
    val importable: Boolean = false,
    val receiverType: String? = null,
    val smart: Boolean = false,
)

private data class BridgeRequest(
    val id: Long,
    val method: String,
    val payload: CompletionPayload? = null,
)

private data class CompletionPayload(
    val projectRoot: String,
    val filePath: String,
    val text: String,
    val offset: Int,
    val limit: Int,
    val rangeStart: Int = -1,
    val rangeEnd: Int = -1,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BridgeResponse(
    val id: Long = 0,
    val success: Boolean = false,
    val message: String? = null,
    val items: List<JetBrainsBridgeCompletion> = emptyList(),
    val locations: List<BridgeLocation> = emptyList(),
    val hoverMarkdown: String? = null,
    val formattedText: String? = null,
    val error: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BridgeLocation(
    val uri: String = "",
    val range: BridgeRange = BridgeRange(),
) {
    fun toProtocol(): Location =
        Location(uri = uri, range = range.toProtocol())
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BridgeRange(
    val start: BridgePosition = BridgePosition(),
    val end: BridgePosition = BridgePosition(),
) {
    fun toProtocol(): Range =
        Range(start = start.toProtocol(), end = end.toProtocol())
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BridgePosition(
    val line: Int = 0,
    val character: Int = 0,
) {
    fun toProtocol(): Position =
        Position(line = line, character = character)
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

private fun copyIfNeeded(source: Path, target: Path) {
    if (Files.exists(target) && Files.size(target) == Files.size(source)) return
    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
}

private fun Path.deleteRecursivelyIfExists() {
    if (!Files.exists(this)) return
    Files.walk(this).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { entry ->
            Files.deleteIfExists(entry)
        }
    }
}
