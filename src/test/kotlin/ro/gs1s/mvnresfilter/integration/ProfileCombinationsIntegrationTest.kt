package ro.gs1s.mvnresfilter.integration

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.gs1s.mvnresfilter.ArtifactType
import ro.gs1s.mvnresfilter.MavenModelReader
import ro.gs1s.mvnresfilter.ResourceProcessor
import java.nio.file.Files

class ProfileCombinationsIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val reader = MavenModelReader()

    @Test
    fun testMultipleActiveProfiles_WebResourcesCombined() {
        val tempProject = tempFolder.newFolder("multi-profile").toPath()
        TestUtils.copyTree(TestUtils.PROJECTS_BASE.resolve("multi-profile"), tempProject)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val rawConfig = reader.buildConfig(
            pomPath = tempProject.resolve("pom.xml"),
            activeProfileIds = listOf("alpha", "beta"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.WAR
        )
        val config = TestUtils.resolveRelativePaths(rawConfig)

        assertEquals("Both profiles should contribute webResources", 2, config.webResources.size)

        val processor = ResourceProcessor(config)
        processor.processWebResources()

        // beta's extra.json should be present (unique to beta)
        assertTrue(
            "extra.json from beta profile should exist",
            Files.exists(artifactOutput.resolve("WEB-INF/extra.json"))
        )
        // config.json should exist (both profiles have it)
        assertTrue(
            "config.json should exist",
            Files.exists(artifactOutput.resolve("WEB-INF/config.json"))
        )
    }

    @Test
    fun testOverlappingWebResources_LastProfileWins() {
        val tempProject = tempFolder.newFolder("multi-profile").toPath()
        TestUtils.copyTree(TestUtils.PROJECTS_BASE.resolve("multi-profile"), tempProject)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        // alpha is declared first in pom, beta second
        // Both write WEB-INF/config.json — beta (processed second) should win
        val rawConfig = reader.buildConfig(
            pomPath = tempProject.resolve("pom.xml"),
            activeProfileIds = listOf("alpha", "beta"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.WAR
        )
        val config = TestUtils.resolveRelativePaths(rawConfig)

        val processor = ResourceProcessor(config)
        processor.processWebResources()

        val configJson = TestUtils.readText(artifactOutput.resolve("WEB-INF/config.json"))
        assertTrue(
            "config.json should contain beta content (last profile wins)",
            configJson.contains("beta")
        )
    }
}
