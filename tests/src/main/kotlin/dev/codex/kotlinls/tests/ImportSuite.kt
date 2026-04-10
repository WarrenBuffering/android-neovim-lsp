package dev.codex.kotlinls.tests

import dev.codex.kotlinls.projectimport.GradleProjectImporter
import dev.codex.kotlinls.projectimport.LocalGradleCacheResolver
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.writeText

fun importSuite(): TestSuite {
    val importer = GradleProjectImporter()
    return TestSuite(
        name = "import",
        cases = listOf(
            TestCase("imports multi-module Gradle project") {
                val project = importer.importProject(FixtureSupport.fixture("multi-module"))
                assertEquals(3, project.modules.size)
                assertTrue(project.modules.any { it.gradlePath == ":app" }) { "Expected :app module" }
                assertTrue(project.modules.any { it.gradlePath == ":lib" }) { "Expected :lib module" }
            },
            TestCase("maps files to modules and includes project dependency closure") {
                val project = importer.importProject(FixtureSupport.fixture("multi-module"))
                val appFile = FixtureSupport.fixture("multi-module").resolve("app/src/main/kotlin/demo/app/Main.kt")
                val module = project.moduleForPath(appFile)
                assertEquals(":app", module?.gradlePath) { "Expected app file to resolve to :app, got $module" }
                val subset = project.subsetForModules(listOf(requireNotNull(module)))
                assertTrue(subset.modules.any { it.gradlePath == ":app" }) { "Expected subset to include :app" }
                assertTrue(subset.modules.any { it.gradlePath == ":lib" }) { "Expected subset to include dependent :lib" }
            },
            TestCase("resolves cached dependency jars") {
                val project = importer.importProject(FixtureSupport.fixture("dependency-heavy"))
                val rootModule = project.modules.first { it.gradlePath == ":" }
                assertTrue(rootModule.classpathJars.isNotEmpty()) { "Expected dependency cache resolution to find jars" }
            },
            TestCase("detects mixed Kotlin and Java roots") {
                val project = importer.importProject(FixtureSupport.fixture("mixed-kotlin-java"))
                val rootModule = project.modules.first { it.gradlePath == ":" }
                assertTrue(rootModule.sourceRoots.isNotEmpty()) { "Expected Kotlin source roots" }
                assertTrue(rootModule.javaSourceRoots.isNotEmpty()) { "Expected Java source roots" }
            },
            TestCase("resolves version-catalog library aliases") {
                val project = importer.importProject(FixtureSupport.fixture("version-catalog-gradle"))
                val appModule = project.modules.first { it.gradlePath == ":app" }
                assertTrue(appModule.externalDependencies.any { it.notation == "org.jetbrains.kotlin:kotlin-stdlib:2.3.20" }) {
                    "Expected kotlin-stdlib from version catalog, got ${appModule.externalDependencies}"
                }
                assertTrue(appModule.externalDependencies.any { it.notation == "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2" }) {
                    "Expected coroutines from version catalog, got ${appModule.externalDependencies}"
                }
                assertTrue(appModule.classpathJars.isNotEmpty()) { "Expected version catalog dependencies to resolve cached jars" }
            },
            TestCase("resolves BOM-managed aliases, AAR classes, and transitive cache deps") {
                val fixture = FakeGradleCacheFixture.create()
                val fixtureImporter = GradleProjectImporter(
                    cacheResolver = LocalGradleCacheResolver(fixture.gradleHome),
                )
                val project = fixtureImporter.importProject(fixture.projectRoot)
                val appModule = project.modules.first { it.gradlePath == ":app" }
                assertTrue(appModule.externalDependencies.any { it.notation == "my.ui:widget:2.0" }) {
                    "Expected BOM-managed widget dependency, got ${appModule.externalDependencies}"
                }
                assertTrue(appModule.classpathJars.any { it.name == "classes.jar" }) {
                    "Expected extracted classes.jar from AAR, got ${appModule.classpathJars}"
                }
                assertTrue(appModule.classpathJars.any { it.fileName.toString() == "core-3.0.jar" }) {
                    "Expected transitive core jar, got ${appModule.classpathJars}"
                }
            },
            TestCase("caches unchanged project imports") {
                val fixture = FakeGradleCacheFixture.create()
                val fixtureImporter = GradleProjectImporter(
                    cacheResolver = LocalGradleCacheResolver(fixture.gradleHome),
                )
                val first = fixtureImporter.importProject(fixture.projectRoot)
                val second = fixtureImporter.importProject(fixture.projectRoot)
                assertEquals(first, second) { "Expected importer to reuse cached project model contents" }
            },
            TestCase("resolves jvm-suffixed artifacts and ignores KSP version as Kotlin stdlib version") {
                val root = Files.createTempDirectory("kotlinls-kmp-import-fixture")
                val gradleHome = root.resolve("gradle-home")
                val projectRoot = root.resolve("project").also { it.createDirectories() }
                projectRoot.resolve("settings.gradle.kts").writeText("""include(":app")""")
                projectRoot.resolve("gradle").createDirectories()
                projectRoot.resolve("gradle/libs.versions.toml").writeText(
                    """
                    [versions]
                    kotlin = "2.3.20"
                    ksp = "2.3.6"
                    json = "1.10.0"

                    [libraries]
                    json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "json" }

                    [plugins]
                    jetbrains-kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
                    kotlin-ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
                    """.trimIndent(),
                )
                val appDir = projectRoot.resolve("app").also { it.createDirectories() }
                appDir.resolve("build.gradle.kts").writeText(
                    """
                    plugins {
                        alias(libs.plugins.kotlin.ksp)
                        alias(libs.plugins.jetbrains.kotlin.android)
                    }

                    dependencies {
                        implementation(libs.json)
                    }
                    """.trimIndent(),
                )
                appDir.resolve("src/main/kotlin/demo").createDirectories()
                appDir.resolve("src/main/kotlin/demo/App.kt").writeText("package demo\nclass App")

                FakeGradleCacheFixture.writePom(
                    gradleHome = gradleHome,
                    coordinate = "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.10.0",
                    fileName = "kotlinx-serialization-json-jvm-1.10.0.pom",
                    contents = """
                        <project xmlns="http://maven.apache.org/POM/4.0.0">
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>org.jetbrains.kotlinx</groupId>
                          <artifactId>kotlinx-serialization-json-jvm</artifactId>
                          <version>1.10.0</version>
                        </project>
                    """.trimIndent(),
                )
                FakeGradleCacheFixture.writeJar(
                    gradleHome = gradleHome,
                    coordinate = "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.10.0",
                    fileName = "kotlinx-serialization-json-jvm-1.10.0.jar",
                )

                FakeGradleCacheFixture.writePom(
                    gradleHome = gradleHome,
                    coordinate = "org.jetbrains.kotlin:kotlin-stdlib:2.3.20",
                    fileName = "kotlin-stdlib-2.3.20.pom",
                    contents = """
                        <project xmlns="http://maven.apache.org/POM/4.0.0">
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>org.jetbrains.kotlin</groupId>
                          <artifactId>kotlin-stdlib</artifactId>
                          <version>2.3.20</version>
                        </project>
                    """.trimIndent(),
                )
                FakeGradleCacheFixture.writeJar(
                    gradleHome = gradleHome,
                    coordinate = "org.jetbrains.kotlin:kotlin-stdlib:2.3.20",
                    fileName = "kotlin-stdlib-2.3.20.jar",
                )

                val fixtureImporter = GradleProjectImporter(
                    cacheResolver = LocalGradleCacheResolver(gradleHome),
                )
                val project = fixtureImporter.importProject(projectRoot)
                val appModule = project.modules.first { it.gradlePath == ":app" }
                assertTrue(appModule.externalDependencies.any { it.notation == "org.jetbrains.kotlin:kotlin-stdlib:2.3.20" }) {
                    "Expected Kotlin stdlib from Kotlin plugin version, got ${appModule.externalDependencies}"
                }
                assertTrue(appModule.externalDependencies.none { it.notation == "org.jetbrains.kotlin:kotlin-stdlib:2.3.6" }) {
                    "Expected KSP version not to leak into stdlib, got ${appModule.externalDependencies}"
                }
                assertTrue(appModule.classpathJars.any { it.fileName.toString() == "kotlinx-serialization-json-jvm-1.10.0.jar" }) {
                    "Expected JVM-suffixed serialization jar, got ${appModule.classpathJars}"
                }
            },
            TestCase("adds generated Android class directories for BuildConfig-style symbols") {
                val root = Files.createTempDirectory("kotlinls-android-generated-classes")
                val projectRoot = root.resolve("project").also { it.createDirectories() }
                val appDir = projectRoot.resolve("app").also { it.createDirectories() }
                projectRoot.resolve("settings.gradle.kts").writeText("""include(":app")""" + "\n")
                appDir.resolve("build.gradle.kts").writeText(
                    """
                    plugins {
                        id("com.android.application")
                    }

                    android {
                        namespace = "demo.app"
                        compileSdk = 36
                        buildFeatures {
                            buildConfig = true
                        }
                    }
                    """.trimIndent() + "\n",
                )
                appDir.resolve("src/main/kotlin/demo").createDirectories()
                appDir.resolve("src/main/kotlin/demo/App.kt").writeText("package demo\nclass App")
                val javacClasses = appDir.resolve("build/intermediates/javac/devDebug/compileDevDebugJavaWithJavac/classes/demo")
                javacClasses.createDirectories()
                javacClasses.resolve("BuildConfig.class").writeText("stub")
                val asmDirs = appDir.resolve("build/intermediates/classes/devDebug/transformDevDebugClassesWithAsm/dirs/demo")
                asmDirs.createDirectories()
                asmDirs.resolve("BuildConfig.class").writeText("stub")

                val project = importer.importProject(projectRoot)
                val appModule = project.modules.first { it.gradlePath == ":app" }
                assertTrue(appModule.classpathJars.any { it.toString().endsWith("/build/intermediates/javac/devDebug/compileDevDebugJavaWithJavac/classes") }) {
                    "Expected javac classes dir in classpath, got ${appModule.classpathJars}"
                }
                assertTrue(appModule.classpathJars.any { it.toString().endsWith("/build/intermediates/classes/devDebug/transformDevDebugClassesWithAsm/dirs") }) {
                    "Expected transformed classes dir in classpath, got ${appModule.classpathJars}"
                }
            },
        ),
    )
}

private data class FakeGradleCacheFixture(
    val root: Path,
    val projectRoot: Path,
    val gradleHome: Path,
) {
    companion object {
        fun create(): FakeGradleCacheFixture {
            val root = Files.createTempDirectory("kotlinls-gradle-cache-fixture")
            val gradleHome = root.resolve("gradle-home")
            val projectRoot = root.resolve("project").also { it.createDirectories() }
            projectRoot.resolve("settings.gradle.kts").writeText("""include(":app")""")
            projectRoot.resolve("gradle").createDirectories()
            projectRoot.resolve("gradle/libs.versions.toml").writeText(
                """
                [versions]
                fake-bom = "1.0"

                [libraries]
                fake-bom = { group = "my.compose", name = "compose-bom", version.ref = "fake-bom" }
                ui-widget = { group = "my.ui", name = "widget" }
                """.trimIndent(),
            )
            val appDir = projectRoot.resolve("app").also { it.createDirectories() }
            appDir.resolve("build.gradle.kts").writeText(
                """
                plugins {}

                dependencies {
                    val uiBom = platform(libs.fake.bom)
                    implementation(uiBom)
                    implementation(libs.ui.widget)
                }
                """.trimIndent(),
            )
            appDir.resolve("src/main/kotlin/demo").createDirectories()
            appDir.resolve("src/main/kotlin/demo/App.kt").writeText("package demo\nclass App")

            writePom(
                gradleHome = gradleHome,
                coordinate = "my.compose:compose-bom:1.0",
                fileName = "compose-bom-1.0.pom",
                contents = """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>my.compose</groupId>
                      <artifactId>compose-bom</artifactId>
                      <version>1.0</version>
                      <packaging>pom</packaging>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>my.ui</groupId>
                            <artifactId>widget</artifactId>
                            <version>2.0</version>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                    </project>
                """.trimIndent(),
            )
            writePom(
                gradleHome = gradleHome,
                coordinate = "my.ui:widget:2.0",
                fileName = "widget-2.0.pom",
                contents = """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>my.ui</groupId>
                      <artifactId>widget</artifactId>
                      <version>2.0</version>
                      <packaging>aar</packaging>
                      <dependencies>
                        <dependency>
                          <groupId>my.dep</groupId>
                          <artifactId>core</artifactId>
                          <version>3.0</version>
                          <scope>runtime</scope>
                        </dependency>
                      </dependencies>
                    </project>
                """.trimIndent(),
            )
            writeAar(
                gradleHome = gradleHome,
                coordinate = "my.ui:widget:2.0",
                fileName = "widget-2.0.aar",
            )
            writePom(
                gradleHome = gradleHome,
                coordinate = "my.dep:core:3.0",
                fileName = "core-3.0.pom",
                contents = """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>my.dep</groupId>
                      <artifactId>core</artifactId>
                      <version>3.0</version>
                    </project>
                """.trimIndent(),
            )
            writeJar(
                gradleHome = gradleHome,
                coordinate = "my.dep:core:3.0",
                fileName = "core-3.0.jar",
            )
            return FakeGradleCacheFixture(
                root = root,
                projectRoot = projectRoot,
                gradleHome = gradleHome,
            )
        }

        fun writePom(
            gradleHome: Path,
            coordinate: String,
            fileName: String,
            contents: String,
        ) {
            artifactDir(gradleHome, coordinate, fileName.removeSuffix(".pom")).resolve(fileName).writeText(contents)
        }

        fun writeJar(
            gradleHome: Path,
            coordinate: String,
            fileName: String,
        ) {
            val jarPath = artifactDir(gradleHome, coordinate, fileName.removeSuffix(".jar")).resolve(fileName)
            jarPath.parent.createDirectories()
            ZipOutputStream(Files.newOutputStream(jarPath)).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zip.write("Manifest-Version: 1.0\n".toByteArray())
                zip.closeEntry()
            }
        }

        fun writeAar(
            gradleHome: Path,
            coordinate: String,
            fileName: String,
        ) {
            val aarPath = artifactDir(gradleHome, coordinate, fileName.removeSuffix(".aar")).resolve(fileName)
            aarPath.parent.createDirectories()
            ZipOutputStream(Files.newOutputStream(aarPath)).use { zip ->
                zip.putNextEntry(ZipEntry("classes.jar"))
                zip.write(emptyJarBytes())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("libs/helper.jar"))
                zip.write(emptyJarBytes())
                zip.closeEntry()
            }
        }

        private fun artifactDir(
            gradleHome: Path,
            coordinate: String,
            hashSeed: String,
        ): Path {
            val (group, artifact, version) = coordinate.split(':')
            val hash = hashSeed.hashCode().toUInt().toString(16)
            return gradleHome.resolve("caches/modules-2/files-2.1/$group/$artifact/$version/$hash").also { it.createDirectories() }
        }

        private fun emptyJarBytes(): ByteArray {
            val tempJar = Files.createTempFile("kotlinls-empty", ".jar")
            ZipOutputStream(Files.newOutputStream(tempJar)).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                zip.write("Manifest-Version: 1.0\n".toByteArray())
                zip.closeEntry()
            }
            return Files.readAllBytes(tempJar)
        }
    }
}
