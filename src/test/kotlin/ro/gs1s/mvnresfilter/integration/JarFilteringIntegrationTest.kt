package ro.gs1s.mvnresfilter.integration

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.gs1s.mvnresfilter.ArtifactType
import ro.gs1s.mvnresfilter.MavenModelReader
import ro.gs1s.mvnresfilter.ResourceProcessor

class JarFilteringIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // Test: dev profile — JAR artifact full pipeline
    // -------------------------------------------------------------------------
    @Test
    fun testDevProfile_JarArtifact() {
        val fixtureCoreLib = TestUtils.PROJECTS_BASE.resolve("multi-module/core-lib")
        val fixtureParent = TestUtils.PROJECTS_BASE.resolve("multi-module")

        val tempCoreLib = tempFolder.newFolder("core-lib").toPath()
        TestUtils.copyTree(fixtureCoreLib, tempCoreLib)
        val tempParent = tempFolder.newFolder("multi-module").toPath()
        TestUtils.copyTree(fixtureParent, tempParent)

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
        val config = TestUtils.resolveRelativePaths(rawConfig)
        ResourceProcessor(config).processResources()

        val expectedDir = TestUtils.EXPECTED_BASE.resolve("multi-module/dev/core-lib")
        TestUtils.compareTree(expectedDir, artifactOutput)

        // Verify UTF-8 content with Romanian characters is preserved
        val persistenceXml = artifactOutput.resolve("META-INF/persistence.xml")
        val content = TestUtils.readText(persistenceXml)
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
        val fixtureCoreLib = TestUtils.PROJECTS_BASE.resolve("multi-module/core-lib")
        val fixtureParent = TestUtils.PROJECTS_BASE.resolve("multi-module")

        val tempCoreLib = tempFolder.newFolder("core-lib").toPath()
        TestUtils.copyTree(fixtureCoreLib, tempCoreLib)
        val tempParent = tempFolder.newFolder("multi-module").toPath()
        TestUtils.copyTree(fixtureParent, tempParent)

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
        val config = TestUtils.resolveRelativePaths(rawConfig)
        ResourceProcessor(config).processResources()

        val expectedDir = TestUtils.EXPECTED_BASE.resolve("multi-module/prod/core-lib")
        TestUtils.compareTree(expectedDir, artifactOutput)
    }
}
