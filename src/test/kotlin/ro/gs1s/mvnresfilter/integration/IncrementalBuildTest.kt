package ro.gs1s.mvnresfilter.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.gs1s.mvnresfilter.ArtifactType
import ro.gs1s.mvnresfilter.MavenModelReader
import ro.gs1s.mvnresfilter.OverlayCache
import ro.gs1s.mvnresfilter.ResourceProcessor
import java.nio.file.Files

class IncrementalBuildTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // Test: second build with same profile and unchanged files → cache hit
    // -------------------------------------------------------------------------
    @Test
    fun testSecondBuild_SameProfile_Skipped() {
        val fixtureMinimalJar = TestUtils.PROJECTS_BASE.resolve("minimal-jar")

        val tempProject = tempFolder.newFolder("minimal-jar").toPath()
        TestUtils.copyTree(fixtureMinimalJar, tempProject)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val childPomPath = tempProject.resolve("pom.xml")

        // First build
        val rawConfig1 = MavenModelReader().buildConfig(
            pomPath = childPomPath,
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        val config1 = TestUtils.resolveRelativePaths(rawConfig1)
        ResourceProcessor(config1).processResources()

        val cacheInputs1 = TestUtils.buildCacheInputs(config1)
        val cache = OverlayCache(artifactOutput, "test")
        cache.writeCache(cacheInputs1)

        // Second build — same profile, same files
        val rawConfig2 = MavenModelReader().buildConfig(
            pomPath = childPomPath,
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        val config2 = TestUtils.resolveRelativePaths(rawConfig2)
        val cacheInputs2 = TestUtils.buildCacheInputs(config2)

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
        val fixtureMinimalJar = TestUtils.PROJECTS_BASE.resolve("minimal-jar")

        val tempProject = tempFolder.newFolder("minimal-jar").toPath()
        TestUtils.copyTree(fixtureMinimalJar, tempProject)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val childPomPath = tempProject.resolve("pom.xml")

        // First build with dev profile
        val rawConfigDev = MavenModelReader().buildConfig(
            pomPath = childPomPath,
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        val configDev = TestUtils.resolveRelativePaths(rawConfigDev)
        ResourceProcessor(configDev).processResources()

        val cacheInputsDev = TestUtils.buildCacheInputs(configDev)
        val cache = OverlayCache(artifactOutput, "test")
        cache.writeCache(cacheInputsDev)

        // Switch to prod profile — build new config and inputs
        val rawConfigProd = MavenModelReader().buildConfig(
            pomPath = childPomPath,
            activeProfileIds = listOf("prod"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        val configProd = TestUtils.resolveRelativePaths(rawConfigProd)
        val cacheInputsProd = TestUtils.buildCacheInputs(configProd)

        assertFalse(
            "Cache should be stale after profile switch from dev to prod",
            cache.isUpToDate(cacheInputsProd)
        )

        // Reprocess with prod profile
        ResourceProcessor(configProd).processResources()

        // Verify output matches expected/minimal-jar/prod/
        val expectedDir = TestUtils.EXPECTED_BASE.resolve("minimal-jar/prod")
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
