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

class WarOverlayIntegrationTest {

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
    // Test: dev profile — WAR artifact full pipeline
    // -------------------------------------------------------------------------
    @Test
    fun testDevProfile_WarArtifact() {
        val fixtureWebApp = PROJECTS_BASE.resolve("multi-module/web-app")
        val fixtureParent = PROJECTS_BASE.resolve("multi-module")

        // Copy the web-app fixture to a temp dir so paths are stable
        val tempWebApp = tempFolder.newFolder("web-app").toPath()
        copyTree(fixtureWebApp, tempWebApp)
        val tempParent = tempFolder.newFolder("multi-module").toPath()
        copyTree(fixtureParent, tempParent)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        // Simulate IntelliJ's default assembly: copy webapp sources to artifact WEB-INF
        val srcWebInf = tempWebApp.resolve("src/main/webapp/WEB-INF")
        val artifactWebInf = artifactOutput.resolve("WEB-INF")
        Files.createDirectories(artifactWebInf)
        Files.copy(srcWebInf.resolve("web.xml"), artifactWebInf.resolve("web.xml"), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(srcWebInf.resolve("jboss-web.xml"), artifactWebInf.resolve("jboss-web.xml"), StandardCopyOption.REPLACE_EXISTING)

        val childPomPath = tempWebApp.resolve("pom.xml")
        val parentPomPath = tempParent.resolve("pom.xml")

        val rawConfig = MavenModelReader().buildConfigWithParent(
            childPomPath = childPomPath,
            parentPomPath = parentPomPath,
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.WAR
        )
        val config = resolveRelativePaths(rawConfig)
        val processor = ResourceProcessor(config)

        // Step 1: copy profiles/dev/WEB-INF/* to artifact output
        processor.processWebResources()

        // Step 2: filter deployment descriptors (web.xml, jboss-web.xml) in place
        processor.filterDeploymentDescriptors()

        // Verify output against expected
        val expectedDir = EXPECTED_BASE.resolve("multi-module/dev/web-app/WEB-INF")
        val actualDir = artifactOutput.resolve("WEB-INF")

        assertEquals(
            "keycloak.json mismatch",
            readText(expectedDir.resolve("keycloak.json")),
            readText(actualDir.resolve("keycloak.json"))
        )
        assertEquals(
            "oidc.json mismatch",
            readText(expectedDir.resolve("oidc.json")),
            readText(actualDir.resolve("oidc.json"))
        )
        assertEquals(
            "jboss-web.xml mismatch (\${jbossweb.context} -> myapp-dev)",
            readText(expectedDir.resolve("jboss-web.xml")),
            readText(actualDir.resolve("jboss-web.xml"))
        )
        assertEquals(
            "web.xml mismatch (\${app.display.name} → MyApp Dev, \${session.timeout} → 60)",
            readText(expectedDir.resolve("web.xml")),
            readText(actualDir.resolve("web.xml"))
        )
    }

    // -------------------------------------------------------------------------
    // Test: prod profile — WAR artifact full pipeline
    // -------------------------------------------------------------------------
    @Test
    fun testProdProfile_WarArtifact() {
        val fixtureWebApp = PROJECTS_BASE.resolve("multi-module/web-app")
        val fixtureParent = PROJECTS_BASE.resolve("multi-module")

        val tempWebApp = tempFolder.newFolder("web-app").toPath()
        copyTree(fixtureWebApp, tempWebApp)
        val tempParent = tempFolder.newFolder("multi-module").toPath()
        copyTree(fixtureParent, tempParent)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        // Simulate IntelliJ's default assembly: copy webapp sources to artifact WEB-INF
        val srcWebInf = tempWebApp.resolve("src/main/webapp/WEB-INF")
        val artifactWebInf = artifactOutput.resolve("WEB-INF")
        Files.createDirectories(artifactWebInf)
        Files.copy(srcWebInf.resolve("web.xml"), artifactWebInf.resolve("web.xml"), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(srcWebInf.resolve("jboss-web.xml"), artifactWebInf.resolve("jboss-web.xml"), StandardCopyOption.REPLACE_EXISTING)

        val childPomPath = tempWebApp.resolve("pom.xml")
        val parentPomPath = tempParent.resolve("pom.xml")

        val rawConfig = MavenModelReader().buildConfigWithParent(
            childPomPath = childPomPath,
            parentPomPath = parentPomPath,
            activeProfileIds = listOf("prod"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.WAR
        )
        val config = resolveRelativePaths(rawConfig)
        val processor = ResourceProcessor(config)

        processor.processWebResources()
        processor.filterDeploymentDescriptors()

        val expectedDir = EXPECTED_BASE.resolve("multi-module/prod/web-app/WEB-INF")
        val actualDir = artifactOutput.resolve("WEB-INF")

        compareTree(expectedDir, actualDir)
    }
}
