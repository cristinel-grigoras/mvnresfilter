package ro.gs1s.mvnresfilter

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class MavenModelReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val reader = MavenModelReader()

    companion object {
        private val PROJECTS_BASE = Path.of("src/test/resources/projects")
        private val MINIMAL_JAR_POM = PROJECTS_BASE.resolve("minimal-jar/pom.xml")
        private val MINIMAL_WAR_POM = PROJECTS_BASE.resolve("minimal-war/pom.xml")
        private val MULTI_MODULE_POM = PROJECTS_BASE.resolve("multi-module/pom.xml")
        private val CORE_LIB_POM = PROJECTS_BASE.resolve("multi-module/core-lib/pom.xml")
    }

    // -------------------------------------------------------------------------
    // Test 1: parseProfileNames — returns declared profile IDs
    // -------------------------------------------------------------------------
    @Test
    fun testParseProfiles() {
        val profiles = reader.parseProfileNames(MINIMAL_JAR_POM)
        assertEquals(listOf("dev", "prod"), profiles)
    }

    // -------------------------------------------------------------------------
    // Test 2: dev profile overrides project-level properties
    // -------------------------------------------------------------------------
    @Test
    fun testMergeProperties_ProfileOverrides() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        val config = reader.buildConfig(
            pomPath = MINIMAL_JAR_POM,
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        assertEquals("true", config.mergedProperties["hibernate.show_sql"])
        assertEquals("update", config.mergedProperties["hibernate.hbm2ddl_auto"])
        assertEquals("http://localhost:8080", config.mergedProperties["app.url"])
    }

    // -------------------------------------------------------------------------
    // Test 3: multiple active profiles — last one (in pom order) wins
    // -------------------------------------------------------------------------
    @Test
    fun testMultipleActiveProfiles_LastWins() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        // dev sets hibernate.show_sql=true, prod (declared after dev) sets it to false
        val config = reader.buildConfig(
            pomPath = MINIMAL_JAR_POM,
            activeProfileIds = listOf("dev", "prod"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        assertEquals("false", config.mergedProperties["hibernate.show_sql"])
        assertEquals("validate", config.mergedProperties["hibernate.hbm2ddl_auto"])
        assertEquals("https://prod.example.com", config.mergedProperties["app.url"])
    }

    // -------------------------------------------------------------------------
    // Test 4: parse webResources from minimal-war + dev profile
    // -------------------------------------------------------------------------
    @Test
    fun testParseWebResources() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        val config = reader.buildConfig(
            pomPath = MINIMAL_WAR_POM,
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.WAR
        )
        assertEquals(1, config.webResources.size)
        assertEquals("profiles/dev", config.webResources[0].directory)
    }

    // -------------------------------------------------------------------------
    // Test 5: parse nonFilteredFileExtensions from minimal-war + dev profile
    // -------------------------------------------------------------------------
    @Test
    fun testParseNonFilteredExtensions() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        val config = reader.buildConfig(
            pomPath = MINIMAL_WAR_POM,
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.WAR
        )
        assertEquals(setOf("png", "jpg", "pdf"), config.nonFilteredExtensions)
    }

    // -------------------------------------------------------------------------
    // Test 6: parent pom property inheritance for multi-module child
    // -------------------------------------------------------------------------
    @Test
    fun testParentPropertyInheritance() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        val config = reader.buildConfigWithParent(
            childPomPath = CORE_LIB_POM,
            parentPomPath = MULTI_MODULE_POM,
            activeProfileIds = listOf("dev"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        // Parent pom has dev profile that sets hibernate.show_sql=true
        assertEquals("true", config.mergedProperties["hibernate.show_sql"])
        assertEquals("update", config.mergedProperties["hibernate.hbm2ddl_auto"])
    }

    // -------------------------------------------------------------------------
    // Test 7: no active profiles — only project-level properties
    // -------------------------------------------------------------------------
    @Test
    fun testNoActiveProfiles_ProjectLevelOnly() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        val config = reader.buildConfig(
            pomPath = MINIMAL_JAR_POM,
            activeProfileIds = emptyList(),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        // Project-level: hibernate.show_sql=false
        assertEquals("false", config.mergedProperties["hibernate.show_sql"])
        assertEquals("none", config.mergedProperties["hibernate.hbm2ddl_auto"])
    }

    // -------------------------------------------------------------------------
    // Test 8: activeByDefault profile is deactivated when an explicit profile
    //         is requested (Maven behaviour)
    // -------------------------------------------------------------------------
    @Test
    fun testActiveByDefault_DeactivatedWhenExplicitProfile() {
        // Create a temp pom that has an activeByDefault profile and a regular profile
        val tempPom = tempFolder.newFile("pom.xml")
        tempPom.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>test-project</artifactId>
    <version>1.0.0</version>
    <profiles>
        <profile>
            <id>default-active</id>
            <activation>
                <activeByDefault>true</activeByDefault>
            </activation>
            <properties>
                <env.name>default</env.name>
            </properties>
        </profile>
        <profile>
            <id>ci</id>
            <properties>
                <env.name>ci</env.name>
            </properties>
        </profile>
    </profiles>
</project>"""
        )

        val artifactOutput = tempFolder.newFolder("out").toPath()

        // When "ci" is explicitly activated, "default-active" should NOT be included
        val config = reader.buildConfig(
            pomPath = tempPom.toPath(),
            activeProfileIds = listOf("ci"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )

        // ci profile should be active, not default-active
        assertFalse("default-active should not be in active profiles", "default-active" in config.activeProfiles)
        assertTrue("ci should be active", "ci" in config.activeProfiles)
        // env.name should be "ci", not "default"
        assertEquals("ci", config.mergedProperties["env.name"])
    }

    // -------------------------------------------------------------------------
    // Test 9: multiple profiles with webResources — order follows pom declaration
    // -------------------------------------------------------------------------
    @Test
    fun testMultipleProfilesWithWebResources_PomDeclarationOrder() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        val config = reader.buildConfig(
            pomPath = MINIMAL_WAR_POM,
            activeProfileIds = listOf("dev", "prod"),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.WAR
        )
        // Both dev and prod profiles have webResources; dev is declared first in pom
        assertEquals(2, config.webResources.size)
        assertEquals("profiles/dev", config.webResources[0].directory)
        assertEquals("profiles/prod", config.webResources[1].directory)
    }

    // -------------------------------------------------------------------------
    // Test 10: built-in properties are populated (project.artifactId, project.version, basedir)
    // -------------------------------------------------------------------------
    @Test
    fun testBuiltInProperties_ProjectArtifactId() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        val config = reader.buildConfig(
            pomPath = MINIMAL_JAR_POM,
            activeProfileIds = emptyList(),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        assertEquals("minimal-jar", config.mergedProperties["project.artifactId"])
        assertEquals("1.0.0", config.mergedProperties["project.version"])

        val expectedBasedir = MINIMAL_JAR_POM.toAbsolutePath().parent.toString()
        assertEquals(expectedBasedir, config.mergedProperties["project.basedir"])
        assertEquals(expectedBasedir, config.mergedProperties["basedir"])
    }

    // -------------------------------------------------------------------------
    // Filter file tests
    // -------------------------------------------------------------------------

    private val FILTER_TEST_POM = Path.of("src/test/resources/projects/filter-test/pom.xml")

    @Test
    fun testFilterFile_CommonPropertiesLoaded() {
        val config = reader.buildConfig(FILTER_TEST_POM, listOf("dev"), tempFolder.root.toPath(), ArtifactType.JAR)
        // From common.properties
        assertEquals("common-value", config.mergedProperties["prop.from.common"])
        assertEquals("postgresql", config.mergedProperties["db.driver"])
    }

    @Test
    fun testFilterFile_ProfileFilterOverridesCommon() {
        val config = reader.buildConfig(FILTER_TEST_POM, listOf("dev"), tempFolder.root.toPath(), ArtifactType.JAR)
        // From dev.properties (overrides common)
        assertEquals("dev-filter-value", config.mergedProperties["prop.from.dev"])
        assertEquals("jdbc:postgresql://localhost/devdb", config.mergedProperties["db.url"])
    }

    @Test
    fun testFilterFile_ProjectPropertiesOverrideFilter() {
        val config = reader.buildConfig(FILTER_TEST_POM, listOf("dev"), tempFolder.root.toPath(), ArtifactType.JAR)
        // Project <properties> override filter file value
        assertEquals("project-value", config.mergedProperties["prop.from.project"])
    }

    @Test
    fun testFilterFile_ProfilePropertiesOverrideAll() {
        val config = reader.buildConfig(FILTER_TEST_POM, listOf("dev"), tempFolder.root.toPath(), ArtifactType.JAR)
        // Priority chain: common filter "from-common-filter" → dev filter "from-dev-filter" → project "from-project" → profile "from-profile"
        // Profile <properties> wins
        assertEquals("from-profile", config.mergedProperties["prop.overridden"])
    }

    @Test
    fun testFilterFile_NoProfile_OnlyCommonFilter() {
        val config = reader.buildConfig(FILTER_TEST_POM, emptyList(), tempFolder.root.toPath(), ArtifactType.JAR)
        // Without profile, only common.properties loaded
        assertEquals("common-value", config.mergedProperties["prop.from.common"])
        // dev.properties NOT loaded
        assertNull(config.mergedProperties["prop.from.dev"])
        // Project <properties> override common filter
        assertEquals("from-project", config.mergedProperties["prop.overridden"])
    }

    @Test
    fun testFilterFile_MissingFilterFileIgnored() {
        // The pom references filter files — if they don't exist (e.g., wrong path), should not crash
        val tempPom = tempFolder.newFolder("missing-filter").toPath()
        val pomContent = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.test</groupId>
                <artifactId>missing-filter</artifactId>
                <version>1.0</version>
                <build>
                    <filters>
                        <filter>nonexistent/path/filter.properties</filter>
                    </filters>
                </build>
            </project>
        """.trimIndent()
        Files.writeString(tempPom.resolve("pom.xml"), pomContent)
        // Should not throw
        val config = reader.buildConfig(tempPom.resolve("pom.xml"), emptyList(), tempFolder.root.toPath(), ArtifactType.JAR)
        assertNotNull(config)
    }
}
