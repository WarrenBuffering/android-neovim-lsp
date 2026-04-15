package dev.codex.kotlinls.tests

import dev.codex.kotlinls.analysis.KotlinWorkspaceAnalyzer
import dev.codex.kotlinls.formatting.FormatterBridge
import dev.codex.kotlinls.formatting.FormattingService
import dev.codex.kotlinls.formatting.JetBrainsFormatterBridge
import dev.codex.kotlinls.projectimport.GradleProjectImporter
import dev.codex.kotlinls.protocol.DocumentFormattingParams
import dev.codex.kotlinls.protocol.DocumentRangeFormattingParams
import dev.codex.kotlinls.protocol.FormattingOptions
import dev.codex.kotlinls.protocol.Position
import dev.codex.kotlinls.protocol.Range
import dev.codex.kotlinls.protocol.TextDocumentIdentifier
import dev.codex.kotlinls.workspace.LineIndex
import dev.codex.kotlinls.workspace.TextDocumentStore
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

fun formattingSuite(): TestSuite {
    val importer = GradleProjectImporter()
    val analyzer = KotlinWorkspaceAnalyzer()
    val formattingService = FormattingService()
    return TestSuite(
        name = "formatting",
        cases = listOf(
            TestCase("formats Kotlin code with Kotlin-aware layout rules") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    import kotlin.collections.listOf

                    fun greet( name:String)=listOf(
                    "a",
                    "b"
                    ).joinToString(separator =",")
                """.trimIndent()
                val bridgeFormattingService = FormattingService(
                    intellijFormatterBridge = FormatterBridge { _, _, _ ->
                        """
                        package demo

                        import kotlin.collections.listOf

                        fun greet(name: String) = listOf(
                            "a",
                            "b",
                        ).joinToString(separator = ",")
                        """.trimIndent()
                    },
                )
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 8,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val edits = bridgeFormattingService.formatDocument(
                    snapshot,
                    DocumentFormattingParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        options = FormattingOptions(tabSize = 4, insertSpaces = true),
                    ),
                )
                val formatted = applyEdits(content, edits)
                assertContains(formatted, "fun greet(name: String) =")
                assertContains(formatted, "    \"a\",")
                assertContains(formatted, "    \"b\",")
                assertContains(formatted, "separator = \",\"")
            },
            TestCase("bridge-backed document formatting also organizes imports") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    import kotlin.collections.setOf
                    import kotlin.collections.listOf

                    fun greet( name:String)=listOf(
                    "a",
                    "b"
                    ).joinToString(separator =",")
                """.trimIndent()
                val bridgeFormattingService = FormattingService(
                    intellijFormatterBridge = FormatterBridge { _, _, _ ->
                        """
                        package demo

                        import kotlin.collections.setOf
                        import kotlin.collections.listOf

                        fun greet(name: String) = listOf(
                            "a",
                            "b",
                        ).joinToString(separator = ",")
                        """.trimIndent()
                    },
                )
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 8,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val edits = bridgeFormattingService.formatDocument(
                    snapshot,
                    DocumentFormattingParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        options = FormattingOptions(tabSize = 4, insertSpaces = true),
                    ),
                )
                val formatted = applyEdits(content, edits)
                assertContains(formatted, "import kotlin.collections.listOf")
                assertTrue(
                    formatted.indexOf("import kotlin.collections.listOf") <
                        formatted.indexOf("import kotlin.collections.setOf"),
                ) {
                    "Expected imports to be reorganized after bridge formatting: $formatted"
                }
                assertContains(formatted, "fun greet(name: String) =")
            },
            TestCase("range formatting only edits overlapping formatted changes") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun keep() = 1

                    fun messy( name:String)=name.trim()
                """.trimIndent()
                val bridgeFormattingService = FormattingService(
                    intellijFormatterBridge = FormatterBridge { _, _, _ ->
                        """
                        package demo

                        fun keep() = 1

                        fun messy(name: String) = name.trim()
                        """.trimIndent()
                    },
                )
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 9,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val messyLine = content.lines().indexOfFirst { it.contains("fun messy") }
                val edits = bridgeFormattingService.formatRange(
                    snapshot,
                    DocumentRangeFormattingParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        range = Range(
                            start = Position(messyLine, 0),
                            end = Position(messyLine, content.lines()[messyLine].length),
                        ),
                        options = FormattingOptions(tabSize = 4, insertSpaces = true),
                    ),
                )
                val formatted = applyEdits(content, edits)
                assertContains(formatted, "fun messy(name: String) = name.trim()")
            },
            TestCase("range formatting stays inside enclosing PSI scope") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun first( name:String)=name.trim()

                    fun second( name:String)=name.trim()
                """.trimIndent()
                val bridgeFormattingService = FormattingService(
                    intellijFormatterBridge = FormatterBridge { _, _, _ ->
                        """
                        package demo

                        fun first(name: String) = name.trim()

                        fun second(name: String) = name.trim()
                        """.trimIndent()
                    },
                )
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 11,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val secondLine = content.lines().indexOfFirst { it.contains("fun second") }
                val edits = bridgeFormattingService.formatRange(
                    snapshot,
                    DocumentRangeFormattingParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        range = Range(
                            start = Position(secondLine, 4),
                            end = Position(secondLine, content.lines()[secondLine].length),
                        ),
                        options = FormattingOptions(tabSize = 4, insertSpaces = true),
                    ),
                )
                val formatted = applyEdits(content, edits)
                assertContains(formatted, "fun first( name:String)=name.trim()")
                assertContains(formatted, "fun second(name: String) = name.trim()")
            },
            TestCase("IntelliJ project style enables formatting defaults but not style diagnostics") {
                val root = Files.createTempDirectory("kotlinls-format-style-gate")
                root.resolve(".idea/codeStyles").createDirectories()
                root.resolve("src/main/kotlin/demo").createDirectories()
                root.resolve(".idea/codeStyles/Project.xml").writeText(
                    """
                    <component name="ProjectCodeStyleConfiguration">
                      <code_scheme name="Project" version="173">
                        <JetCodeStyleSettings>
                          <option name="CODE_STYLE_DEFAULTS" value="KOTLIN_OFFICIAL" />
                        </JetCodeStyleSettings>
                      </code_scheme>
                    </component>
                    """.trimIndent() + "\n",
                )
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                assertEquals(false, formattingService.shouldPublishStyleDiagnostics(appFile))
            },
            TestCase("editorconfig does not enable standalone style diagnostics") {
                val root = Files.createTempDirectory("kotlinls-format-style-lint")
                root.resolve("src/main/kotlin/demo").createDirectories()
                root.resolve(".editorconfig").writeText(
                    """
                    root = true

                    [*.{kt,kts}]
                    indent_size = 4
                    """.trimIndent() + "\n",
                )
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                assertEquals(false, formattingService.shouldPublishStyleDiagnostics(appFile))
                assertEquals(emptyList<dev.codex.kotlinls.protocol.Diagnostic>(), formattingService.lintDocument(appFile, "fun demo( )=1"))
            },
            TestCase("fallback formatting does not rewrite indentation based on editor tab size") {
                withSystemProperty("kotlinls.disableIntellijBridge", "true") {
                    val localFormattingService = FormattingService()
                    val root = Files.createTempDirectory("kotlinls-format-kotlin-official")
                    root.resolve("src/main/kotlin/demo").createDirectories()
                    root.resolve("settings.gradle.kts").writeText("""rootProject.name = "tmp"""" + "\n")
                    root.resolve("build.gradle.kts").writeText(
                        """
                        plugins {
                            kotlin("jvm") version "2.3.20"
                        }

                        repositories {
                            mavenCentral()
                        }
                        """.trimIndent() + "\n",
                    )
                    val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                    val content = """
                        package demo

                        fun greet() {
                            if (true) {
                                println("hi")
                            }
                        }
                    """.trimIndent()
                    appFile.writeText(content)
                    val project = importer.importProject(root)
                    val store = TextDocumentStore()
                    store.open(
                        dev.codex.kotlinls.protocol.TextDocumentItem(
                            uri = appFile.toUri().toString(),
                            languageId = "kotlin",
                            version = 10,
                            text = content,
                        ),
                    )
                    val snapshot = analyzer.analyze(project, store)
                    val edits = localFormattingService.formatDocument(
                        snapshot,
                        DocumentFormattingParams(
                            textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                            options = FormattingOptions(tabSize = 2, insertSpaces = true),
                        ),
                    )
                    val formatted = applyEdits(content, edits)
                    assertEquals(content + "\n", formatted)
                }
            },
            TestCase("fallback formatting is stable across editor tab options") {
                withSystemProperty("kotlinls.disableIntellijBridge", "true") {
                    val localFormattingService = FormattingService()
                    val root = Files.createTempDirectory("kotlinls-format-default-official")
                    root.resolve("src/main/kotlin/demo").createDirectories()
                    root.resolve("settings.gradle.kts").writeText("""rootProject.name = "tmp"""" + "\n")
                    root.resolve("build.gradle.kts").writeText(
                        """
                        plugins {
                            kotlin("jvm") version "2.3.20"
                        }

                        repositories {
                            mavenCentral()
                        }
                        """.trimIndent() + "\n",
                    )
                    val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                    val content = """
                        package demo

                        fun greet() = "hi"
                    """.trimIndent()
                    appFile.writeText(content)
                    val project = importer.importProject(root)
                    val store = TextDocumentStore()
                    store.open(
                        dev.codex.kotlinls.protocol.TextDocumentItem(
                            uri = appFile.toUri().toString(),
                            languageId = "kotlin",
                            version = 10,
                            text = content,
                        ),
                    )
                    val snapshot = analyzer.analyze(project, store)
                    val edits = localFormattingService.formatDocument(
                        snapshot,
                        DocumentFormattingParams(
                            textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                            options = FormattingOptions(tabSize = 2, insertSpaces = false),
                        ),
                    )
                    val formatted = applyEdits(content, edits)
                    assertEquals(content + "\n", formatted)
                }
            },
            TestCase("reads IntelliJ Kotlin project code style options used by fallback formatting") {
                val root = Files.createTempDirectory("kotlinls-format-intellij-style")
                root.resolve(".idea/codeStyles").createDirectories()
                root.resolve("src/main/kotlin/demo").createDirectories()
                root.resolve(".idea/codeStyles/Project.xml").writeText(
                    """
                    <project version="4">
                      <component name="ProjectCodeStyleConfiguration">
                        <code_scheme name="Project" version="173">
                          <JetCodeStyleSettings>
                            <option name="CODE_STYLE_DEFAULTS" value="KOTLIN_OFFICIAL" />
                            <option name="NAME_COUNT_TO_USE_STAR_IMPORT" value="2" />
                            <option name="NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS" value="3" />
                            <option name="IMPORT_NESTED_CLASSES" value="true" />
                          </JetCodeStyleSettings>
                          <codeStyleSettings language="kotlin">
                            <indentOptions>
                              <option name="USE_TAB_CHARACTER" value="true" />
                              <option name="INDENT_SIZE" value="2" />
                              <option name="TAB_SIZE" value="2" />
                              <option name="CONTINUATION_INDENT_SIZE" value="6" />
                            </indentOptions>
                          </codeStyleSettings>
                        </code_scheme>
                      </component>
                    </project>
                    """.trimIndent() + "\n",
                )
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val method = FormattingService::class.java.getDeclaredMethod("findIntellijKotlinStyle", java.nio.file.Path::class.java)
                method.isAccessible = true
                val settings = method.invoke(formattingService, appFile) ?: error("Expected IntelliJ style settings")
                val codeStyleDefaults = settings.javaClass.getDeclaredMethod("getCodeStyleDefaults").apply { isAccessible = true }
                val nameCountToUseStarImport = settings.javaClass.getDeclaredMethod("getNameCountToUseStarImport").apply { isAccessible = true }
                val nameCountToUseStarImportForMembers = settings.javaClass.getDeclaredMethod("getNameCountToUseStarImportForMembers").apply { isAccessible = true }
                val importNestedClasses = settings.javaClass.getDeclaredMethod("getImportNestedClasses").apply { isAccessible = true }
                val useTabCharacter = settings.javaClass.getDeclaredMethod("getUseTabCharacter").apply { isAccessible = true }
                val indentSize = settings.javaClass.getDeclaredMethod("getIndentSize").apply { isAccessible = true }
                val tabSize = settings.javaClass.getDeclaredMethod("getTabSize").apply { isAccessible = true }
                val continuationIndentSize = settings.javaClass.getDeclaredMethod("getContinuationIndentSize").apply { isAccessible = true }
                assertEquals("KOTLIN_OFFICIAL", codeStyleDefaults.invoke(settings))
                assertEquals(2, nameCountToUseStarImport.invoke(settings))
                assertEquals(3, nameCountToUseStarImportForMembers.invoke(settings))
                assertEquals(true, importNestedClasses.invoke(settings))
                assertEquals(true, useTabCharacter.invoke(settings))
                assertEquals(2, indentSize.invoke(settings))
                assertEquals(2, tabSize.invoke(settings))
                assertEquals(6, continuationIndentSize.invoke(settings))
            },
            TestCase("organizes imports with IntelliJ star-import thresholds while preserving aliases") {
                val root = Files.createTempDirectory("kotlinls-organize-imports")
                root.resolve(".idea/codeStyles").createDirectories()
                root.resolve("src/main/kotlin/demo/lib").createDirectories()
                root.resolve("src/main/kotlin/demo/app").createDirectories()
                root.resolve("settings.gradle.kts").writeText("""rootProject.name = "tmp"""" + "\n")
                root.resolve("build.gradle.kts").writeText(
                    """
                    plugins {
                        kotlin("jvm") version "2.3.20"
                    }

                    repositories {
                        mavenCentral()
                    }
                    """.trimIndent() + "\n",
                )
                root.resolve(".idea/codeStyles/Project.xml").writeText(
                    """
                    <project version="4">
                      <component name="ProjectCodeStyleConfiguration">
                        <code_scheme name="Project" version="173">
                          <JetCodeStyleSettings>
                            <option name="NAME_COUNT_TO_USE_STAR_IMPORT" value="2" />
                            <option name="NAME_COUNT_TO_USE_STAR_IMPORT_FOR_MEMBERS" value="2" />
                            <option name="IMPORT_NESTED_CLASSES" value="true" />
                          </JetCodeStyleSettings>
                        </code_scheme>
                      </component>
                    </project>
                    """.trimIndent() + "\n",
                )
                root.resolve("src/main/kotlin/demo/lib/Lib.kt").writeText(
                    """
                    package demo.lib

                    fun alpha(): String = "a"
                    fun beta(): String = "b"
                    fun gamma(): String = "g"
                    fun unused(): String = "u"
                    """.trimIndent() + "\n",
                )
                val appFile = root.resolve("src/main/kotlin/demo/app/App.kt")
                val content = """
                    package demo.app

                    import demo.lib.alpha
                    import demo.lib.beta
                    import demo.lib.gamma as gm
                    import demo.lib.unused

                    fun demo(): String = alpha() + beta() + gm()
                """.trimIndent()
                appFile.writeText(content)
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 14,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val organized = formattingService.organizeImportsText(snapshot, appFile.toUri().toString()) ?: error("Expected organized imports")
                assertContains(organized, "import demo.lib.*")
                assertContains(organized, "import demo.lib.gamma as gm")
                assertTrue("import demo.lib.alpha" !in organized) { "Expected explicit alpha import to collapse into star import: $organized" }
                assertTrue("import demo.lib.beta" !in organized) { "Expected explicit beta import to collapse into star import: $organized" }
                assertTrue("import demo.lib.unused" !in organized) { "Expected unused import to be removed: $organized" }
            },
            TestCase("uses external formatter command when configured") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun  cli( )= 1
                """.trimIndent()
                val script = Files.createTempFile("kotlinls-external-formatter", ".sh")
                script.writeText(
                    """
                    #!/bin/sh
                    for last; do true; done
                    cat <<'EOF' > "${'$'}last"
                    package demo

                    fun cli() = 1
                    //external
                    EOF
                    """.trimIndent() + "\n",
                )
                script.setPosixFilePermissions(setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                ))
                val externalFormattingService = FormattingService(intellijFormatterCommand = listOf(script.toString()))
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 12,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val edits = externalFormattingService.formatDocument(
                    snapshot,
                    DocumentFormattingParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        options = FormattingOptions(tabSize = 4, insertSpaces = true),
                    ),
                )
                val formatted = applyEdits(content, edits)
                assertContains(formatted, "fun cli() = 1")
                assertContains(formatted, "//external")
            },
            TestCase("uses direct JetBrains bridge when available") {
                val root = FixtureSupport.fixture("simple-jvm-app")
                val project = importer.importProject(root)
                val store = TextDocumentStore()
                val appFile = root.resolve("src/main/kotlin/demo/App.kt")
                val content = """
                    package demo

                    fun  bridge( )= 1
                """.trimIndent()
                val bridgedService = FormattingService(
                    intellijFormatterBridge = FormatterBridge { _, _, _ ->
                        """
                        package demo

                        fun bridge() = 99
                        //bridge
                        """.trimIndent() + "\n"
                    },
                    intellijFormatterCommand = null,
                )
                store.open(
                    dev.codex.kotlinls.protocol.TextDocumentItem(
                        uri = appFile.toUri().toString(),
                        languageId = "kotlin",
                        version = 13,
                        text = content,
                    ),
                )
                val snapshot = analyzer.analyze(project, store)
                val edits = bridgedService.formatDocument(
                    snapshot,
                    DocumentFormattingParams(
                        textDocument = TextDocumentIdentifier(appFile.toUri().toString()),
                        options = FormattingOptions(tabSize = 4, insertSpaces = true),
                    ),
                )
                val formatted = applyEdits(content, edits)
                assertContains(formatted, "fun bridge() = 99")
                assertContains(formatted, "//bridge")
            },
            TestCase("detects JetBrains bridge from product bundle metadata") {
                val root = Files.createTempDirectory("kotlinls-idea-home")
                val contents = createFakeIdeaHome(root.resolve("Android Studio.app/Contents"))
                val bridge = JetBrainsFormatterBridge.fromIdeaHome(contents)
                assertTrue(bridge != null) { "Expected JetBrains bridge to be detected from fake bundle" }
            },
            TestCase("detects JetBrains bridge from local.properties project config") {
                val root = Files.createTempDirectory("kotlinls-local-properties-idea-home")
                val contents = createFakeIdeaHome(root.resolve("Android Studio.app/Contents"))
                val projectRoot = Files.createTempDirectory("kotlinls-local-properties-project")
                projectRoot.resolve("local.properties").writeText(
                    "kotlinls.intellijHome=${contents}\n",
                )
                withSystemProperty("kotlinls.enableIntellijBridge", null) {
                    withSystemProperty("kotlinls.intellijHome", null) {
                        withSystemProperty("kotlinls.disableIntellijBridge", null) {
                            val bridge = JetBrainsFormatterBridge.detect(projectRoot)
                            assertTrue(bridge != null) {
                                "Expected JetBrains bridge to be detected from local.properties project config"
                            }
                        }
                    }
                }
            },
            TestCase("JetBrains bridge only opts into incubator vector when runtime provides it") {
                val root = Files.createTempDirectory("kotlinls-idea-home-no-vector")
                val contents = root.resolve("Android Studio.app/Contents")
                contents.resolve("Resources").createDirectories()
                contents.resolve("bin").createDirectories()
                contents.resolve("lib").createDirectories()
                contents.resolve("plugins/Kotlin/lib").createDirectories()
                contents.resolve("jbr/Contents/Home/bin").createDirectories()
                contents.resolve("jbr/Contents/Home/jmods").createDirectories()
                contents.resolve("Resources/product-info.json").writeText(
                    """
                    {
                      "launch": [
                        {
                          "os": "macOS",
                          "arch": "aarch64",
                          "vmOptionsFilePath": "../bin/studio.vmoptions",
                          "bootClassPathJarNames": ["app.jar"],
                          "additionalJvmArguments": [
                            "-Didea.platform.prefix=AndroidStudio",
                            "--add-opens=java.base/java.lang=ALL-UNNAMED"
                          ],
                          "mainClass": "com.android.tools.idea.MainWrapper"
                        }
                      ]
                    }
                    """.trimIndent() + "\n",
                )
                contents.resolve("bin/studio.vmoptions").writeText("-Xmx512m\n")
                contents.resolve("lib/app.jar").writeText("")
                contents.resolve("plugins/Kotlin/lib/kotlin-plugin.jar").writeText("")
                contents.resolve("jbr/Contents/Home/bin/java").writeText("")
                val bridge = JetBrainsFormatterBridge.fromIdeaHome(contents) ?: error("Expected bridge")
                val jvmArgsField = bridge.javaClass.getDeclaredField("jvmArgs").apply { isAccessible = true }
                val jvmArgs = jvmArgsField.get(bridge) as List<*>
                assertTrue("--add-modules=jdk.incubator.vector" !in jvmArgs) {
                    "Expected bridge to skip incubator vector when jmod is missing, got $jvmArgs"
                }
            },
            TestCase("real Android Studio bridge formats malformed Kotlin when available") {
                val ideaHome = Path("/Applications/Android Studio.app/Contents")
                if (!Files.isDirectory(ideaHome)) {
                    return@TestCase
                }
                val bridge = JetBrainsFormatterBridge.fromIdeaHome(ideaHome) ?: return@TestCase
                val tempFile = Files.createTempFile("kotlinls-real-formatter", ".kt")
                val content = """
                    package demo

                    fun  probe( )= 1
                """.trimIndent() + "\n"
                tempFile.writeText(content)
                val formatted = bridge.format(tempFile, content, 30_000) ?: error("Expected JetBrains bridge to format Kotlin source")
                assertContains(formatted, "fun probe() = 1")
            },
            TestCase("formatter temp file keeps visible Kotlin filename") {
                val root = Files.createTempDirectory("kotlinls-format-temp-file")
                val appFile = root.resolve("IgnisMapView.kt")
                val method = FormattingService::class.java.getDeclaredMethod("createFormatterTempFile", java.nio.file.Path::class.java)
                method.isAccessible = true
                val tempPath = method.invoke(formattingService, appFile) as java.nio.file.Path
                val fileName = tempPath.fileName.toString()
                assertTrue(!fileName.startsWith(".")) { "Expected formatter temp file to stay visible, got $fileName" }
                assertTrue(fileName.endsWith(".kt")) { "Expected formatter temp file to keep Kotlin extension, got $fileName" }
                assertContains(fileName, "IgnisMapView")
            },
            TestCase("keeps JetBrains formatter bridge off by default") {
                val projectRoot = Files.createTempDirectory("kotlinls-no-idea-home")
                withSystemProperty("kotlinls.enableIntellijBridge", null) {
                    withSystemProperty("kotlinls.intellijHome", null) {
                        withSystemProperty("kotlinls.disableIntellijBridge", null) {
                            val bridge = JetBrainsFormatterBridge.detect(projectRoot)
                            assertTrue(bridge == null) { "Expected JetBrains bridge to stay off without explicit opt-in" }
                        }
                    }
                }
            },
            TestCase("detects Linux-style JetBrains runtime layout") {
                val ideaHome = createFakeLinuxIdeaHome(Files.createTempDirectory("kotlinls-linux-idea-home"))
                withSystemProperty("os.name", "Linux") {
                    withSystemProperty("os.arch", "x86_64") {
                        val bridge = JetBrainsFormatterBridge.fromIdeaHome(ideaHome)
                        assertTrue(bridge != null) { "Expected Linux-style JetBrains home to be recognized" }
                    }
                }
            },
        ),
    )
}

private fun createFakeIdeaHome(contents: java.nio.file.Path): java.nio.file.Path {
    contents.resolve("Resources").createDirectories()
    contents.resolve("bin").createDirectories()
    contents.resolve("lib").createDirectories()
    contents.resolve("plugins/Kotlin/lib").createDirectories()
    contents.resolve("jbr/Contents/Home/bin").createDirectories()
    contents.resolve("Resources/product-info.json").writeText(
        """
        {
          "launch": [
            {
              "os": "macOS",
              "arch": "aarch64",
              "vmOptionsFilePath": "../bin/studio.vmoptions",
              "bootClassPathJarNames": ["app.jar"],
              "additionalJvmArguments": [
                "-Didea.platform.prefix=AndroidStudio",
                "--add-opens=java.base/java.lang=ALL-UNNAMED"
              ],
              "mainClass": "com.android.tools.idea.MainWrapper"
            }
          ]
        }
        """.trimIndent() + "\n",
    )
    contents.resolve("bin/studio.vmoptions").writeText("-Xmx512m\n")
    contents.resolve("lib/app.jar").writeText("")
    contents.resolve("plugins/Kotlin/lib/kotlin-plugin.jar").writeText("")
    contents.resolve("jbr/Contents/Home/bin/java").writeText("")
    return contents
}

private fun createFakeLinuxIdeaHome(root: java.nio.file.Path): java.nio.file.Path {
    root.resolve("bin").createDirectories()
    root.resolve("lib").createDirectories()
    root.resolve("plugins/Kotlin/lib").createDirectories()
    root.resolve("jbr/bin").createDirectories()
    root.resolve("product-info.json").writeText(
        """
        {
          "launch": [
            {
              "os": "linux",
              "arch": "x86_64",
              "vmOptionsFilePath": "bin/studio64.vmoptions",
              "bootClassPathJarNames": ["app.jar"],
              "additionalJvmArguments": [
                "-Didea.platform.prefix=AndroidStudio",
                "--add-opens=java.base/java.lang=ALL-UNNAMED"
              ],
              "mainClass": "com.android.tools.idea.MainWrapper"
            }
          ]
        }
        """.trimIndent() + "\n",
    )
    root.resolve("bin/studio64.vmoptions").writeText("-Xmx512m\n")
    root.resolve("lib/app.jar").writeText("")
    root.resolve("plugins/Kotlin/lib/kotlin-plugin.jar").writeText("")
    root.resolve("jbr/bin/java").writeText("")
    return root
}

private fun applyEdits(
    text: String,
    edits: List<dev.codex.kotlinls.protocol.TextEdit>,
): String {
    if (edits.isEmpty()) return text
    val lineIndex = LineIndex.build(text)
    val builder = StringBuilder(text)
    edits.sortedByDescending { lineIndex.offset(it.range.start) }.forEach { edit ->
        val start = lineIndex.offset(edit.range.start)
        val end = lineIndex.offset(edit.range.end)
        builder.replace(start, end, edit.newText)
    }
    return builder.toString()
}

private fun <T> withSystemProperty(name: String, value: String?, block: () -> T): T {
    val original = System.getProperty(name)
    if (value == null) {
        System.clearProperty(name)
    } else {
        System.setProperty(name, value)
    }
    return try {
        block()
    } finally {
        if (original == null) {
            System.clearProperty(name)
        } else {
            System.setProperty(name, original)
        }
    }
}
