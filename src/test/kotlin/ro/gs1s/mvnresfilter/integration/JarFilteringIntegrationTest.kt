package ro.gs1s.mvnresfilter.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.gs1s.mvnresfilter.ArtifactType
import ro.gs1s.mvnresfilter.MavenModelReader
import ro.gs1s.mvnresfilter.OverlayConfig
import ro.gs1s.mvnresfilter.ResourceDef
import ro.gs1s.mvnresfilter.ResourceProcessor
import ro.gs1s.mvnresfilter.WebResourceDef
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class JarFilteringIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    companion object {
        private val PROJECTS_BASE = Path.of("src/test/resources/projects")
        private val EXPECTED_BASE = Path.of("src/test/resources/expected")

        private fun copyTree(source: Path, target: Path) {
            Files.walk(source).use { stream ->
                stream.forEach { src ->
                    val dest = target.resolve(source.relativize(src))
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest)
                    } else {
                        Files.createDirectories(dest.parent)
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        private fun readText(path: Path): String =
            Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n")

        private fun compareTree(expectedDir: Path, actualDir: Path) {
            Files.walk(expectedDir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { expectedFile ->
                    val relative = expectedDir.relativize(expectedFile)
                    val actualFile = actualDir.resolve(relative)
                    assertTrue("Expected file missing in output: $relative", Files.exists(actualFile))
                    assertEquals(
                        "Content mismatch for: $relative",
                        readText(expectedFile),
                        readText(actualFile)
                    )
                }
            }
        }

        /**
         * Resolves relative resource directory paths in the config against the projectBasedir.
         * MavenModelReader returns directory values from the pom as-is (relative), but
         * ResourceProcessor uses Path.of() directly. This ensures all paths are absolute.
         */
        private fun resolveRelativePaths(config: OverlayConfig): OverlayConfig {
            val basedir = config.projectBasedir
            val resolvedWebResources = config.webResources.map { wr ->
                val dir = Path.of(wr.directory)
                val absDir = if (dir.isAbsolute) dir else basedir.resolve(dir)
                wr.copy(directory = absDir.toString())
            }
            val resolvedResources = config.resources.map { r ->
                val dir = Path.of(r.directory)
                val absDir = if (dir.isAbsolute) dir else basedir.resolve(dir)
                r.copy(directory = absDir.toString())
            }
            return config.copy(
                webResources = resolvedWebResources,
                resources = resolvedResources
            )
        }
    }

    // -------------------------------------------------------------------------
    // Test: dev profile — JAR artifact full pipeline
    // -------------------------------------------------------------------------
    @Test
    fun testDevProfile_JarArtifact() {
        val fixtureCoreLib = PROJECTS_BASE.resolve("multi-module/core-lib")
        val fixtureParent = PROJECTS_BASE.resolve("multi-module")

        val tempCoreLib = tempFolder.newFolder("core-lib").toPath()
        copyTree(fixtureCoreLib, tempCoreLib)
        val tempParent = tempFolder.newFolder("multi-module").toPath()
        copyTree(fixtureParent, tempParent)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val childPomPath = tempCoreLib.resolve("pom.xml")
        val parentPomPath = tempParent.resolve("pom.xml")

        val rawConfig = MavenModelReader().buildConfigWithParent(
            childPomPath = childPomPath,
            parentPomPath = parentPomPath,
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        val config = resolveRelativePaths(rawConfig)
        ResourceProcessor(config).processResources()

        val expectedDir = EXPECTED_BASE.resolve("multi-module/dev/core-lib")
        compareTree(expectedDir, artifactOutput)

        // Verify UTF-8 content with Romanian characters is preserved
        val persistenceXml = artifactOutput.resolve("META-INF/persistence.xml")
        val content = readText(persistenceXml)
        assertTrue(
            "UTF-8 content 'Configuratie speciala' (Romanian) should be preserved",
            content.contains("Configurație specială")
        )
    }

    // -------------------------------------------------------------------------
    // Test: prod profile — JAR artifact full pipeline
    // -------------------------------------------------------------------------
    @Test
    fun testProdProfile_JarArtifact() {
        val fixtureCoreLib = PROJECTS_BASE.resolve("multi-module/core-lib")
        val fixtureParent = PROJECTS_BASE.resolve("multi-module")

        val tempCoreLib = tempFolder.newFolder("core-lib").toPath()
        copyTree(fixtureCoreLib, tempCoreLib)
        val tempParent = tempFolder.newFolder("multi-module").toPath()
        copyTree(fixtureParent, tempParent)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val childPomPath = tempCoreLib.resolve("pom.xml")
        val parentPomPath = tempParent.resolve("pom.xml")

        val rawConfig = MavenModelReader().buildConfigWithParent(
            childPomPath = childPomPath,
            parentPomPath = parentPomPath,
            activeProfileIds = listOf("prod"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        val config = resolveRelativePaths(rawConfig)
        ResourceProcessor(config).processResources()

        val expectedDir = EXPECTED_BASE.resolve("multi-module/prod/core-lib")
        compareTree(expectedDir, artifactOutput)
    }
}
