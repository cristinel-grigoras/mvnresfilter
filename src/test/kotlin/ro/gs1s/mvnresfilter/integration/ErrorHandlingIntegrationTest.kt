package ro.gs1s.mvnresfilter.integration

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.gs1s.mvnresfilter.ArtifactType
import ro.gs1s.mvnresfilter.MavenModelReader
import ro.gs1s.mvnresfilter.ResourceProcessor
import java.nio.file.Path

class ErrorHandlingIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val reader = MavenModelReader()

    @Test(expected = Exception::class)
    fun testMissingPomXml_Throws() {
        val nonExistent = Path.of("/tmp/does-not-exist-${System.nanoTime()}/pom.xml")
        reader.buildConfig(nonExistent, listOf("dev"), tempFolder.root.toPath(), ArtifactType.WAR)
    }

    @Test(expected = Exception::class)
    fun testMalformedPomXml_ThrowsParseError() {
        val malformedPom = TestUtils.PROJECTS_BASE.resolve("malformed-pom/pom.xml")
        reader.buildConfig(malformedPom, listOf("dev"), tempFolder.root.toPath(), ArtifactType.WAR)
    }

    @Test
    fun testEmptyProfile_NoWebResources() {
        val multiProfilePom = TestUtils.PROJECTS_BASE.resolve("multi-profile/pom.xml")
        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        // "empty" profile has properties but no war-plugin config
        val rawConfig = reader.buildConfig(
            pomPath = multiProfilePom,
            activeProfileIds = listOf("empty"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.WAR
        )
        val config = TestUtils.resolveRelativePaths(rawConfig)

        assertTrue("Should have no webResources", config.webResources.isEmpty())
        assertEquals("EmptyApp", config.mergedProperties["app.name"])

        // Processing should complete without error even with no web resources
        val processor = ResourceProcessor(config)
        processor.processWebResources()
        assertEquals(0, processor.getFileCount())
        assertTrue(processor.getErrors().isEmpty())
    }
}
