package ro.gs1s.mvnresfilter.integration

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.gs1s.mvnresfilter.ArtifactType
import ro.gs1s.mvnresfilter.MavenModelReader
import ro.gs1s.mvnresfilter.ResourceProcessor
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MultiModuleIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val reader = MavenModelReader()

    @Test
    fun testDeepInheritance_ParentProperties() {
        val tempWebApp = tempFolder.newFolder("web-app").toPath()
        TestUtils.copyTree(TestUtils.PROJECTS_BASE.resolve("multi-module/web-app"), tempWebApp)
        val tempParent = tempFolder.newFolder("multi-module").toPath()
        TestUtils.copyTree(TestUtils.PROJECTS_BASE.resolve("multi-module"), tempParent)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        // Simulate IntelliJ's default assembly
        val artifactWebInf = artifactOutput.resolve("WEB-INF")
        Files.createDirectories(artifactWebInf)
        val srcWebInf = tempWebApp.resolve("src/main/webapp/WEB-INF")
        Files.copy(srcWebInf.resolve("jboss-web.xml"), artifactWebInf.resolve("jboss-web.xml"), StandardCopyOption.REPLACE_EXISTING)

        val rawConfig = reader.buildConfigWithParent(
            childPomPath = tempWebApp.resolve("pom.xml"),
            parentPomPath = tempParent.resolve("pom.xml"),
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.WAR
        )
        val config = TestUtils.resolveRelativePaths(rawConfig)

        assertEquals("myapp-dev", config.mergedProperties["jbossweb.context"])

        val processor = ResourceProcessor(config)
        processor.processWebResources()
        processor.filterDeploymentDescriptors()

        val jbossXml = TestUtils.readText(artifactWebInf.resolve("jboss-web.xml"))
        assertTrue("jboss-web.xml should contain dev context root",
            jbossXml.contains("myapp-dev"))
    }

    @Test
    fun testChildOverridesParentProperty() {
        val tempCoreLib = tempFolder.newFolder("core-lib").toPath()
        TestUtils.copyTree(TestUtils.PROJECTS_BASE.resolve("multi-module/core-lib"), tempCoreLib)
        val tempParent = tempFolder.newFolder("multi-module").toPath()
        TestUtils.copyTree(TestUtils.PROJECTS_BASE.resolve("multi-module"), tempParent)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val rawConfig = reader.buildConfigWithParent(
            childPomPath = tempCoreLib.resolve("pom.xml"),
            parentPomPath = tempParent.resolve("pom.xml"),
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        val config = TestUtils.resolveRelativePaths(rawConfig)

        assertEquals("true", config.mergedProperties["hibernate.show_sql"])

        val processor = ResourceProcessor(config)
        processor.processResources()

        val persistence = TestUtils.readText(artifactOutput.resolve("META-INF/persistence.xml"))
        assertFalse("persistence.xml should not have unresolved placeholder",
            persistence.contains("\${hibernate.show_sql}"))
        assertTrue("persistence.xml should have show_sql=true from dev profile",
            persistence.contains("value=\"true\""))
    }

    @Test
    fun testParentProfile_ChildProfile_BothActive() {
        val tempWebApp = tempFolder.newFolder("web-app").toPath()
        TestUtils.copyTree(TestUtils.PROJECTS_BASE.resolve("multi-module/web-app"), tempWebApp)
        val tempParent = tempFolder.newFolder("multi-module").toPath()
        TestUtils.copyTree(TestUtils.PROJECTS_BASE.resolve("multi-module"), tempParent)

        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val rawConfig = reader.buildConfigWithParent(
            childPomPath = tempWebApp.resolve("pom.xml"),
            parentPomPath = tempParent.resolve("pom.xml"),
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.WAR
        )
        val config = TestUtils.resolveRelativePaths(rawConfig)

        assertNotNull("Should have jbossweb.context from parent dev profile",
            config.mergedProperties["jbossweb.context"])
        assertEquals("myapp-dev", config.mergedProperties["jbossweb.context"])

        val processor = ResourceProcessor(config)
        processor.processWebResources()

        assertTrue("keycloak.json should be overlaid",
            Files.exists(artifactOutput.resolve("WEB-INF/keycloak.json")))
    }
}
