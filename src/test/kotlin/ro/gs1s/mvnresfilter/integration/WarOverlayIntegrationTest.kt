package ro.gs1s.mvnresfilter.integration

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.gs1s.mvnresfilter.ArtifactType
import ro.gs1s.mvnresfilter.MavenModelReader
import ro.gs1s.mvnresfilter.ResourceProcessor
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class WarOverlayIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // Test: dev profile — WAR artifact full pipeline
    // -------------------------------------------------------------------------
    @Test
    fun testDevProfile_WarArtifact() {
        val fixtureWebApp = TestUtils.PROJECTS_BASE.resolve("multi-module/web-app")
        val fixtureParent = TestUtils.PROJECTS_BASE.resolve("multi-module")

        // Copy the web-app fixture to a temp dir so paths are stable
        val tempWebApp = tempFolder.newFolder("web-app").toPath()
        TestUtils.copyTree(fixtureWebApp, tempWebApp)
        val tempParent = tempFolder.newFolder("multi-module").toPath()
        TestUtils.copyTree(fixtureParent, tempParent)

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
        val config = TestUtils.resolveRelativePaths(rawConfig)
        val processor = ResourceProcessor(config)

        // Step 1: copy profiles/dev/WEB-INF/* to artifact output
        processor.processWebResources()

        // Step 2: filter deployment descriptors (web.xml, jboss-web.xml) in place
        processor.filterDeploymentDescriptors()

        // Verify output against expected
        val expectedDir = TestUtils.EXPECTED_BASE.resolve("multi-module/dev/web-app/WEB-INF")
        val actualDir = artifactOutput.resolve("WEB-INF")

        assertEquals(
            "keycloak.json mismatch",
            TestUtils.readText(expectedDir.resolve("keycloak.json")),
            TestUtils.readText(actualDir.resolve("keycloak.json"))
        )
        assertEquals(
            "oidc.json mismatch",
            TestUtils.readText(expectedDir.resolve("oidc.json")),
            TestUtils.readText(actualDir.resolve("oidc.json"))
        )
        assertEquals(
            "jboss-web.xml mismatch (\${jbossweb.context} -> myapp-dev)",
            TestUtils.readText(expectedDir.resolve("jboss-web.xml")),
            TestUtils.readText(actualDir.resolve("jboss-web.xml"))
        )
        assertEquals(
            "web.xml mismatch (\${app.display.name} → MyApp Dev, \${session.timeout} → 60)",
            TestUtils.readText(expectedDir.resolve("web.xml")),
            TestUtils.readText(actualDir.resolve("web.xml"))
        )
    }

    // -------------------------------------------------------------------------
    // Test: prod profile — WAR artifact full pipeline
    // -------------------------------------------------------------------------
    @Test
    fun testProdProfile_WarArtifact() {
        val fixtureWebApp = TestUtils.PROJECTS_BASE.resolve("multi-module/web-app")
        val fixtureParent = TestUtils.PROJECTS_BASE.resolve("multi-module")

        val tempWebApp = tempFolder.newFolder("web-app").toPath()
        TestUtils.copyTree(fixtureWebApp, tempWebApp)
        val tempParent = tempFolder.newFolder("multi-module").toPath()
        TestUtils.copyTree(fixtureParent, tempParent)

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
        val config = TestUtils.resolveRelativePaths(rawConfig)
        val processor = ResourceProcessor(config)

        processor.processWebResources()
        processor.filterDeploymentDescriptors()

        val expectedDir = TestUtils.EXPECTED_BASE.resolve("multi-module/prod/web-app/WEB-INF")
        val actualDir = artifactOutput.resolve("WEB-INF")

        TestUtils.compareTree(expectedDir, actualDir)
    }
}
