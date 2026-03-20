package ro.gs1s.mvnresfilter.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.gs1s.mvnresfilter.ArtifactType
import ro.gs1s.mvnresfilter.MavenModelReader
import ro.gs1s.mvnresfilter.OverlayCache
import ro.gs1s.mvnresfilter.OverlayCacheInputs
import ro.gs1s.mvnresfilter.OverlayConfig
import ro.gs1s.mvnresfilter.ResourceProcessor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class IncrementalBuildTest {

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

        /**
         * Resolves relative resource directory paths in the config against the projectBasedir.
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

        /**
         * Builds an [OverlayCacheInputs] from a resolved [OverlayConfig].
         * Collects last-modified timestamps for all source files under resource directories.
         */
        private fun buildCacheInputs(config: OverlayConfig): OverlayCacheInputs {
            val sourceFiles = mutableMapOf<String, Long>()
            for (resource in config.resources) {
                val dir = Path.of(resource.directory)
                if (Files.exists(dir) && Files.isDirectory(dir)) {
                    Files.walk(dir).use { stream ->
                        stream.filter { Files.isRegularFile(it) }.forEach { file ->
                            sourceFiles[file.toString()] = Files.getLastModifiedTime(file).toMillis()
                        }
                    }
                }
            }
            for (webResource in config.webResources) {
                val dir = Path.of(webResource.directory)
                if (Files.exists(dir) && Files.isDirectory(dir)) {
                    Files.walk(dir).use { stream ->
                        stream.filter { Files.isRegularFile(it) }.forEach { file ->
                            sourceFiles[file.toString()] = Files.getLastModifiedTime(file).toMillis()
                        }
                    }
                }
            }
            return OverlayCacheInputs(
                profiles = config.activeProfiles,
                properties = config.mergedProperties,
                sourceFiles = sourceFiles
            )
        }
    }

    // -------------------------------------------------------------------------
    // Test: second build with same profile and unchanged files → cache hit
    // -------------------------------------------------------------------------
    @Test
    fun testSecondBuild_SameProfile_Skipped() {
        val fixtureMinimalJar = PROJECTS_BASE.resolve("minimal-jar")

        val tempProject = tempFolder.newFolder("minimal-jar").toPath()
        copyTree(fixtureMinimalJar, tempProject)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val childPomPath = tempProject.resolve("pom.xml")

        // First build
        val rawConfig1 = MavenModelReader().buildConfig(
            pomPath = childPomPath,
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        val config1 = resolveRelativePaths(rawConfig1)
        ResourceProcessor(config1).processResources()

        val cacheInputs1 = buildCacheInputs(config1)
        val cache = OverlayCache(artifactOutput)
        cache.writeCache(cacheInputs1)

        // Second build — same profile, same files
        val rawConfig2 = MavenModelReader().buildConfig(
            pomPath = childPomPath,
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        val config2 = resolveRelativePaths(rawConfig2)
        val cacheInputs2 = buildCacheInputs(config2)

        assertTrue(
            "Cache should be up to date for same profile and unchanged source files",
            cache.isUpToDate(cacheInputs2)
        )
    }

    // -------------------------------------------------------------------------
    // Test: profile switch from dev to prod → cache miss → reprocess
    // -------------------------------------------------------------------------
    @Test
    fun testProfileSwitch_Reprocessed() {
        val fixtureMinimalJar = PROJECTS_BASE.resolve("minimal-jar")

        val tempProject = tempFolder.newFolder("minimal-jar").toPath()
        copyTree(fixtureMinimalJar, tempProject)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val childPomPath = tempProject.resolve("pom.xml")

        // First build with dev profile
        val rawConfigDev = MavenModelReader().buildConfig(
            pomPath = childPomPath,
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        val configDev = resolveRelativePaths(rawConfigDev)
        ResourceProcessor(configDev).processResources()

        val cacheInputsDev = buildCacheInputs(configDev)
        val cache = OverlayCache(artifactOutput)
        cache.writeCache(cacheInputsDev)

        // Switch to prod profile — build new config and inputs
        val rawConfigProd = MavenModelReader().buildConfig(
            pomPath = childPomPath,
            activeProfileIds = listOf("prod"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        val configProd = resolveRelativePaths(rawConfigProd)
        val cacheInputsProd = buildCacheInputs(configProd)

        assertFalse(
            "Cache should be stale after profile switch from dev to prod",
            cache.isUpToDate(cacheInputsProd)
        )

        // Reprocess with prod profile
        ResourceProcessor(configProd).processResources()

        // Verify output matches expected/minimal-jar/prod/
        val expectedDir = EXPECTED_BASE.resolve("minimal-jar/prod")
        val actualConfigFile = artifactOutput.resolve("config.properties")
        assertTrue("config.properties should exist in artifact output", Files.exists(actualConfigFile))

        val expectedConfigFile = expectedDir.resolve("config.properties")
        val actualContent = Files.readString(actualConfigFile).replace("\r\n", "\n")
        val expectedContent = Files.readString(expectedConfigFile).replace("\r\n", "\n")
        assertTrue(
            "config.properties should contain prod app.url after reprocessing",
            actualContent.contains("https://prod.example.com")
        )
        assertTrue(
            "Prod config.properties content should match expected",
            actualContent == expectedContent
        )
    }
}
