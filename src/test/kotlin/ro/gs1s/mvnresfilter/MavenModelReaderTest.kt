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

    // -------------------------------------------------------------------------
    // Built-in Maven properties: groupId, packaging, build dirs, aliases
    // -------------------------------------------------------------------------

    @Test
    fun testBuiltInProperties_GroupIdAndPackaging() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        val config = reader.buildConfig(
            pomPath = MINIMAL_JAR_POM,
            activeProfileIds = emptyList(),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        assertEquals("com.example", config.mergedProperties["project.groupId"])
        assertEquals("jar", config.mergedProperties["project.packaging"])
        assertEquals("minimal-jar", config.mergedProperties["project.name"])
    }

    @Test
    fun testBuiltInProperties_BuildDirectoryDefaults() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        val config = reader.buildConfig(
            pomPath = MINIMAL_JAR_POM,
            activeProfileIds = emptyList(),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        val basedir = MINIMAL_JAR_POM.toAbsolutePath().parent.toString()
        assertEquals("$basedir/target", config.mergedProperties["project.build.directory"])
        assertEquals("$basedir/src/main/java", config.mergedProperties["project.build.sourceDirectory"])
        assertEquals("$basedir/target/classes", config.mergedProperties["project.build.outputDirectory"])
        assertEquals("minimal-jar-1.0.0", config.mergedProperties["project.build.finalName"])
    }

    @Test
    fun testBuiltInProperties_ShortAliases() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        val config = reader.buildConfig(
            pomPath = MINIMAL_JAR_POM,
            activeProfileIds = emptyList(),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        assertEquals(config.mergedProperties["project.groupId"], config.mergedProperties["groupId"])
        assertEquals(config.mergedProperties["project.artifactId"], config.mergedProperties["artifactId"])
        assertEquals(config.mergedProperties["project.version"], config.mergedProperties["version"])
    }

    @Test
    fun testBuiltInProperties_PomAliases() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        val config = reader.buildConfig(
            pomPath = MINIMAL_JAR_POM,
            activeProfileIds = emptyList(),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        assertEquals(config.mergedProperties["project.groupId"], config.mergedProperties["pom.groupId"])
        assertEquals(config.mergedProperties["project.artifactId"], config.mergedProperties["pom.artifactId"])
        assertEquals(config.mergedProperties["project.version"], config.mergedProperties["pom.version"])
        assertEquals(config.mergedProperties["project.basedir"], config.mergedProperties["pom.basedir"])
    }

    @Test
    fun testBuiltInProperties_InheritedFromParent() {
        val artifactOutput = tempFolder.newFolder("out").toPath()
        val config = reader.buildConfigWithParent(
            childPomPath = CORE_LIB_POM,
            parentPomPath = MULTI_MODULE_POM,
            activeProfileIds = emptyList(),
            artifactOutputPath = artifactOutput,
            artifactType = ArtifactType.JAR
        )
        // core-lib has no groupId/version of its own — inherits from parent
        assertEquals("com.example", config.mergedProperties["project.groupId"])
        assertEquals("1.0.0", config.mergedProperties["project.version"])
    }

    // -------------------------------------------------------------------------
    // Property substitution in directory paths
    // -------------------------------------------------------------------------

    @Test
    fun testDirectoryWithBasedirPlaceholder_Resolved() {
        val projDir = tempFolder.newFolder("basedir-test").toPath()
        Files.createDirectories(projDir.resolve("src/main/resources"))
        Files.writeString(projDir.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.test</groupId>
                <artifactId>basedir-test</artifactId>
                <version>1.0.0</version>
                <build>
                    <resources>
                        <resource>
                            <directory>${'$'}{basedir}/src/main/resources</directory>
                            <filtering>true</filtering>
                        </resource>
                    </resources>
                </build>
            </project>
        """.trimIndent())

        val config = reader.buildConfig(
            projDir.resolve("pom.xml"), emptyList(), tempFolder.root.toPath(), ArtifactType.JAR
        )

        val expectedDir = projDir.toAbsolutePath().toString() + "/src/main/resources"
        assertEquals(expectedDir, config.resources[0].directory)
    }

    @Test
    fun testWebResourceDirectoryWithBasedirPlaceholder_Resolved() {
        val projDir = tempFolder.newFolder("webres-test").toPath()
        Files.createDirectories(projDir.resolve("profiles/dev"))
        Files.writeString(projDir.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.test</groupId>
                <artifactId>webres-test</artifactId>
                <version>1.0.0</version>
                <packaging>war</packaging>
                <profiles>
                    <profile>
                        <id>dev</id>
                        <build>
                            <plugins>
                                <plugin>
                                    <groupId>org.apache.maven.plugins</groupId>
                                    <artifactId>maven-war-plugin</artifactId>
                                    <configuration>
                                        <webResources>
                                            <resource>
                                                <directory>${'$'}{basedir}/profiles/dev</directory>
                                            </resource>
                                        </webResources>
                                    </configuration>
                                </plugin>
                            </plugins>
                        </build>
                    </profile>
                </profiles>
            </project>
        """.trimIndent())

        val config = reader.buildConfig(
            projDir.resolve("pom.xml"), listOf("dev"), tempFolder.root.toPath(), ArtifactType.WAR
        )

        val expectedDir = projDir.toAbsolutePath().toString() + "/profiles/dev"
        assertEquals(expectedDir, config.webResources[0].directory)
    }

    @Test
    fun testPropertyValuesWithPlaceholders_Resolved() {
        val projDir = tempFolder.newFolder("prop-resolve").toPath()
        Files.writeString(projDir.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.test</groupId>
                <artifactId>prop-resolve</artifactId>
                <version>2.0.0</version>
                <properties>
                    <custom.path>${'$'}{basedir}/custom</custom.path>
                    <output.dir>${'$'}{project.build.directory}/generated</output.dir>
                </properties>
            </project>
        """.trimIndent())

        val config = reader.buildConfig(
            projDir.resolve("pom.xml"), emptyList(), tempFolder.root.toPath(), ArtifactType.JAR
        )

        val basedir = projDir.toAbsolutePath().toString()
        assertEquals("$basedir/custom", config.mergedProperties["custom.path"])
        assertEquals("$basedir/target/generated", config.mergedProperties["output.dir"])
    }

    // -------------------------------------------------------------------------
    // Property placeholders in webResource directory and includes
    // -------------------------------------------------------------------------

    @Test
    fun testWebResourceDirectoryAndIncludes_PropertyPlaceholders() {
        val projDir = tempFolder.newFolder("webres-props").toPath()
        Files.createDirectories(projDir.resolve("profiles/development/WEB-INF"))
        Files.writeString(projDir.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.test</groupId>
                <artifactId>webres-props</artifactId>
                <version>1.0.0</version>
                <packaging>war</packaging>
                <profiles>
                    <profile>
                        <id>dev</id>
                        <properties>
                            <base.dir>${'$'}{basedir}</base.dir>
                            <build.profile.id>development</build.profile.id>
                            <config.subdir>WEB-INF</config.subdir>
                        </properties>
                        <build>
                            <plugins>
                                <plugin>
                                    <groupId>org.apache.maven.plugins</groupId>
                                    <artifactId>maven-war-plugin</artifactId>
                                    <configuration>
                                        <webResources>
                                            <resource>
                                                <directory>${'$'}{base.dir}/profiles/${'$'}{build.profile.id}</directory>
                                                <filtering>true</filtering>
                                                <includes>
                                                    <include>${'$'}{config.subdir}/**</include>
                                                </includes>
                                                <excludes>
                                                    <exclude>${'$'}{config.subdir}/excluded-${'$'}{build.profile.id}.xml</exclude>
                                                </excludes>
                                            </resource>
                                        </webResources>
                                    </configuration>
                                </plugin>
                            </plugins>
                        </build>
                    </profile>
                </profiles>
            </project>
        """.trimIndent())

        val config = reader.buildConfig(
            projDir.resolve("pom.xml"), listOf("dev"), tempFolder.root.toPath(), ArtifactType.WAR
        )

        val basedir = projDir.toAbsolutePath().toString()
        val wr = config.webResources[0]
        assertEquals("$basedir/profiles/development", wr.directory)
        assertEquals(listOf("WEB-INF/**"), wr.includes)
        assertEquals(listOf("WEB-INF/excluded-development.xml"), wr.excludes)
        assertTrue(wr.filtering)
    }

    // -------------------------------------------------------------------------
    // resolvePropertyPlaceholders utility
    // -------------------------------------------------------------------------

    @Test
    fun testResolvePropertyPlaceholders_Basic() {
        val props = mapOf("basedir" to "/home/user/project", "env" to "dev")
        assertEquals("/home/user/project/src", MavenModelReader.resolvePropertyPlaceholders("\${basedir}/src", props))
        assertEquals("dev-config", MavenModelReader.resolvePropertyPlaceholders("\${env}-config", props))
    }

    @Test
    fun testResolvePropertyPlaceholders_UnresolvedLeftAsIs() {
        val props = mapOf("known" to "value")
        assertEquals("\${unknown}/path", MavenModelReader.resolvePropertyPlaceholders("\${unknown}/path", props))
    }

    @Test
    fun testResolvePropertyPlaceholders_NoPlaceholders() {
        val props = mapOf("basedir" to "/home")
        assertEquals("plain/path", MavenModelReader.resolvePropertyPlaceholders("plain/path", props))
    }
}
