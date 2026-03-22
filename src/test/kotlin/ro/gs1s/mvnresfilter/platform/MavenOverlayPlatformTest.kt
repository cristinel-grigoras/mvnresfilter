package ro.gs1s.mvnresfilter.platform

import com.intellij.maven.testFramework.MavenImportingTestCase
import kotlinx.coroutines.runBlocking
import ro.gs1s.mvnresfilter.ArtifactType
import ro.gs1s.mvnresfilter.MavenResourceOverlayTask
import java.nio.file.Files
import java.nio.file.Path

class MavenOverlayPlatformTest : MavenImportingTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testWarOverlay_DevProfile() = runBlocking {
        createProjectPom("""
            <groupId>com.test</groupId>
            <artifactId>war-test</artifactId>
            <version>1.0</version>
            <packaging>war</packaging>
            <profiles>
                <profile>
                    <id>dev</id>
                    <properties>
                        <app.context>myapp-dev</app.context>
                    </properties>
                    <build><plugins><plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-war-plugin</artifactId>
                        <configuration>
                            <filteringDeploymentDescriptors>true</filteringDeploymentDescriptors>
                            <webResources><resource>
                                <directory>profiles/dev</directory>
                            </resource></webResources>
                        </configuration>
                    </plugin></plugins></build>
                </profile>
            </profiles>
        """)
        createProjectSubFile("profiles/dev/WEB-INF/config.json", """{"env": "dev"}""")
        createProjectSubFile("src/main/webapp/WEB-INF/jboss-web.xml",
            "<jboss-web><context-root>\${app.context}</context-root></jboss-web>")

        importProjectWithProfilesAsync("dev")

        val mavenProject = projectsManager.projects.first()
        assertNotNull("Maven project should be imported", mavenProject)

        val artifactOutput = Files.createTempDirectory("artifact-output")
        try {
            val webInf = artifactOutput.resolve("WEB-INF")
            Files.createDirectories(webInf)
            Files.writeString(webInf.resolve("jboss-web.xml"),
                "<jboss-web><context-root>\${app.context}</context-root></jboss-web>")

            MavenResourceOverlayTask(project, artifactOutput,
                mavenProject.directoryFile.toNioPath(), ArtifactType.WAR, "test-war").execute()

            assertTrue("config.json should be overlaid",
                Files.exists(artifactOutput.resolve("WEB-INF/config.json")))
            val jbossXml = Files.readString(webInf.resolve("jboss-web.xml"))
            assertTrue("jboss-web.xml should have app.context substituted",
                jbossXml.contains("myapp-dev"))
            assertFalse("No unresolved placeholders",
                jbossXml.contains("\${app.context}"))
        } finally {
            artifactOutput.toFile().deleteRecursively()
        }
    }

    fun testWarOverlay_ProdProfile() = runBlocking {
        createProjectPom("""
            <groupId>com.test</groupId>
            <artifactId>war-test</artifactId>
            <version>1.0</version>
            <packaging>war</packaging>
            <profiles>
                <profile>
                    <id>prod</id>
                    <properties>
                        <app.context>myapp</app.context>
                    </properties>
                    <build><plugins><plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-war-plugin</artifactId>
                        <configuration>
                            <filteringDeploymentDescriptors>true</filteringDeploymentDescriptors>
                            <webResources><resource>
                                <directory>profiles/prod</directory>
                            </resource></webResources>
                        </configuration>
                    </plugin></plugins></build>
                </profile>
            </profiles>
        """)
        createProjectSubFile("profiles/prod/WEB-INF/config.json", """{"env": "prod"}""")
        createProjectSubFile("src/main/webapp/WEB-INF/jboss-web.xml",
            "<jboss-web><context-root>\${app.context}</context-root></jboss-web>")

        importProjectWithProfilesAsync("prod")
        val mavenProject = projectsManager.projects.first()
        val artifactOutput = Files.createTempDirectory("artifact-output")
        try {
            val webInf = artifactOutput.resolve("WEB-INF")
            Files.createDirectories(webInf)
            Files.writeString(webInf.resolve("jboss-web.xml"),
                "<jboss-web><context-root>\${app.context}</context-root></jboss-web>")

            MavenResourceOverlayTask(project, artifactOutput,
                mavenProject.directoryFile.toNioPath(), ArtifactType.WAR, "test-war").execute()

            assertTrue(Files.exists(artifactOutput.resolve("WEB-INF/config.json")))
            val jbossXml = Files.readString(webInf.resolve("jboss-web.xml"))
            assertTrue("Should have prod context root",
                jbossXml.contains("<context-root>myapp</context-root>"))
        } finally {
            artifactOutput.toFile().deleteRecursively()
        }
    }

    fun testWarOverlay_ProfileSwitch() = runBlocking {
        createProjectPom("""
            <groupId>com.test</groupId>
            <artifactId>war-test</artifactId>
            <version>1.0</version>
            <packaging>war</packaging>
            <profiles>
                <profile>
                    <id>dev</id>
                    <properties><app.context>myapp-dev</app.context></properties>
                    <build><plugins><plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-war-plugin</artifactId>
                        <configuration>
                            <filteringDeploymentDescriptors>true</filteringDeploymentDescriptors>
                            <webResources><resource><directory>profiles/dev</directory></resource></webResources>
                        </configuration>
                    </plugin></plugins></build>
                </profile>
                <profile>
                    <id>prod</id>
                    <properties><app.context>myapp</app.context></properties>
                    <build><plugins><plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-war-plugin</artifactId>
                        <configuration>
                            <filteringDeploymentDescriptors>true</filteringDeploymentDescriptors>
                            <webResources><resource><directory>profiles/prod</directory></resource></webResources>
                        </configuration>
                    </plugin></plugins></build>
                </profile>
            </profiles>
        """)
        createProjectSubFile("profiles/dev/WEB-INF/config.json", """{"env": "dev"}""")
        createProjectSubFile("profiles/prod/WEB-INF/config.json", """{"env": "prod"}""")
        createProjectSubFile("src/main/webapp/WEB-INF/jboss-web.xml",
            "<jboss-web><context-root>\${app.context}</context-root></jboss-web>")

        importProjectWithProfilesAsync("dev")
        val mavenProject = projectsManager.projects.first()
        val artifactOutput = Files.createTempDirectory("artifact-output")
        try {
            val webInf = artifactOutput.resolve("WEB-INF")
            Files.createDirectories(webInf)
            Files.writeString(webInf.resolve("jboss-web.xml"),
                "<jboss-web><context-root>\${app.context}</context-root></jboss-web>")

            MavenResourceOverlayTask(project, artifactOutput,
                mavenProject.directoryFile.toNioPath(), ArtifactType.WAR, "test-war").execute()

            var jbossXml = Files.readString(webInf.resolve("jboss-web.xml"))
            assertTrue("First build should have dev context", jbossXml.contains("myapp-dev"))

            // Switch to prod
            Files.writeString(webInf.resolve("jboss-web.xml"),
                "<jboss-web><context-root>\${app.context}</context-root></jboss-web>")
            importProjectWithProfilesAsync("prod")
            MavenResourceOverlayTask(project, artifactOutput,
                mavenProject.directoryFile.toNioPath(), ArtifactType.WAR, "test-war").execute()

            jbossXml = Files.readString(webInf.resolve("jboss-web.xml"))
            assertTrue("Second build should have prod context",
                jbossXml.contains("<context-root>myapp</context-root>"))
            val configJson = Files.readString(artifactOutput.resolve("WEB-INF/config.json"))
            assertTrue("config.json should now be prod", configJson.contains("prod"))
        } finally {
            artifactOutput.toFile().deleteRecursively()
        }
    }

    fun testJarFiltering_DevProfile() = runBlocking {
        createProjectPom("""
            <groupId>com.test</groupId>
            <artifactId>jar-test</artifactId>
            <version>1.0</version>
            <properties>
                <db.url>default-url</db.url>
            </properties>
            <build>
                <resources>
                    <resource>
                        <directory>src/main/resources</directory>
                        <filtering>true</filtering>
                    </resource>
                </resources>
            </build>
            <profiles>
                <profile>
                    <id>dev</id>
                    <properties><db.url>jdbc:h2:mem:devdb</db.url></properties>
                </profile>
            </profiles>
        """)
        createProjectSubFile("src/main/resources/config.properties", "db.url=\${db.url}\n")

        importProjectWithProfilesAsync("dev")
        val mavenProject = projectsManager.projects.first()
        val artifactOutput = Files.createTempDirectory("artifact-output")
        try {
            MavenResourceOverlayTask(project, artifactOutput,
                mavenProject.directoryFile.toNioPath(), ArtifactType.JAR, "test-jar").execute()

            val props = Files.readString(artifactOutput.resolve("config.properties"))
            assertTrue("config.properties should have dev db.url",
                props.contains("jdbc:h2:mem:devdb"))
        } finally {
            artifactOutput.toFile().deleteRecursively()
        }
    }

    fun testNoMavenProject_Skips() = runBlocking {
        createProjectPom("""
            <groupId>com.test</groupId>
            <artifactId>test</artifactId>
            <version>1.0</version>
        """)
        importProjectAsync()

        val artifactOutput = Files.createTempDirectory("artifact-output")
        try {
            val bogusPath = Path.of("/tmp/nonexistent-project-${System.nanoTime()}")
            MavenResourceOverlayTask(project, artifactOutput, bogusPath,
                ArtifactType.WAR, "test").execute()
            // Should not throw — just log warning and return
        } finally {
            artifactOutput.toFile().deleteRecursively()
        }
    }

    fun testNoActiveProfiles_UsesDefaults() = runBlocking {
        createProjectPom("""
            <groupId>com.test</groupId>
            <artifactId>jar-test</artifactId>
            <version>1.0</version>
            <build>
                <resources>
                    <resource>
                        <directory>src/main/resources</directory>
                        <filtering>true</filtering>
                    </resource>
                </resources>
            </build>
            <profiles>
                <profile>
                    <id>default-profile</id>
                    <activation><activeByDefault>true</activeByDefault></activation>
                    <properties><env.name>default-env</env.name></properties>
                </profile>
            </profiles>
        """)
        createProjectSubFile("src/main/resources/env.properties", "env=\${env.name}\n")

        importProjectAsync()
        val mavenProject = projectsManager.projects.first()
        val artifactOutput = Files.createTempDirectory("artifact-output")
        try {
            MavenResourceOverlayTask(project, artifactOutput,
                mavenProject.directoryFile.toNioPath(), ArtifactType.JAR, "test-jar").execute()

            val envProps = Files.readString(artifactOutput.resolve("env.properties"))
            assertTrue("Should have activeByDefault profile value",
                envProps.contains("default-env"))
        } finally {
            artifactOutput.toFile().deleteRecursively()
        }
    }

    /** Async version of importProjectWithProfiles that doesn't deadlock */
    private suspend fun importProjectWithProfilesAsync(vararg profiles: String) {
        doImportProjectsAsync(listOf(projectPom), true, *profiles)
    }
}
