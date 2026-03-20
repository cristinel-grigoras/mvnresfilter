package ro.gs1s.mvnresfilter

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class ResourceProcessorTest {

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

        private fun readText(path: Path): String =
            Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n")

        private fun compareTree(expectedDir: Path, actualDir: Path) {
            Files.walk(expectedDir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { expectedFile ->
                    val relative = expectedDir.relativize(expectedFile)
                    val actualFile = actualDir.resolve(relative)
                    assertTrue("Expected file missing in output: $relative", Files.exists(actualFile))
                    assertEquals(
                        "Content mismatch for: $relative",
                        readText(expectedFile),
                        readText(actualFile)
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Test 1: dev profile filtering for minimal-jar
    // -------------------------------------------------------------------------
    @Test
    fun testFilterProperties_DevProfile() {
        val projectDir = PROJECTS_BASE.resolve("minimal-jar")
        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val devProps = mapOf(
            "hibernate.show_sql" to "true",
            "hibernate.hbm2ddl_auto" to "update",
            "app.url" to "http://localhost:8080"
        )
        val config = OverlayConfig(
            artifactType = ArtifactType.JAR,
            activeProfiles = listOf("dev"),
            mergedProperties = devProps,
            webResources = emptyList(),
            resources = listOf(
                ResourceDef(
                    directory = "src/main/resources",
                    includes = emptyList(),
                    excludes = emptyList(),
                    filtering = true,
                    targetPath = null
                )
            ),
            nonFilteredExtensions = emptySet(),
            filterDeploymentDescriptors = false,
            projectBasedir = projectDir,
            artifactOutputPath = artifactOutput
        )

        ResourceProcessor(config).processResources()

        val expectedDir = EXPECTED_BASE.resolve("minimal-jar/dev")
        compareTree(expectedDir, artifactOutput)
    }

    // -------------------------------------------------------------------------
    // Test 2: prod profile filtering for minimal-jar
    // -------------------------------------------------------------------------
    @Test
    fun testFilterProperties_ProdProfile() {
        val projectDir = PROJECTS_BASE.resolve("minimal-jar")
        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val prodProps = mapOf(
            "hibernate.show_sql" to "false",
            "hibernate.hbm2ddl_auto" to "validate",
            "app.url" to "https://prod.example.com"
        )
        val config = OverlayConfig(
            artifactType = ArtifactType.JAR,
            activeProfiles = listOf("prod"),
            mergedProperties = prodProps,
            webResources = emptyList(),
            resources = listOf(
                ResourceDef(
                    directory = "src/main/resources",
                    includes = emptyList(),
                    excludes = emptyList(),
                    filtering = true,
                    targetPath = null
                )
            ),
            nonFilteredExtensions = emptySet(),
            filterDeploymentDescriptors = false,
            projectBasedir = projectDir,
            artifactOutputPath = artifactOutput
        )

        ResourceProcessor(config).processResources()

        val expectedDir = EXPECTED_BASE.resolve("minimal-jar/prod")
        compareTree(expectedDir, artifactOutput)
    }

    // -------------------------------------------------------------------------
    // Test 3: file without ${...} passes through verbatim
    // -------------------------------------------------------------------------
    @Test
    fun testUnfilteredPassthrough() {
        val sourceDir = tempFolder.newFolder("src").toPath()
        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val inputText = "no placeholders here\nstatic content\n"
        Files.writeString(sourceDir.resolve("static.txt"), inputText, StandardCharsets.UTF_8)

        val config = OverlayConfig(
            artifactType = ArtifactType.JAR,
            activeProfiles = emptyList(),
            mergedProperties = emptyMap(),
            webResources = emptyList(),
            resources = listOf(
                ResourceDef(
                    directory = sourceDir.toString(),
                    includes = emptyList(),
                    excludes = emptyList(),
                    filtering = true,
                    targetPath = null
                )
            ),
            nonFilteredExtensions = emptySet(),
            filterDeploymentDescriptors = false,
            projectBasedir = tempFolder.root.toPath(),
            artifactOutputPath = artifactOutput
        )

        ResourceProcessor(config).processResources()

        val outputText = readText(artifactOutput.resolve("static.txt"))
        assertEquals(inputText.replace("\r\n", "\n"), outputText)
    }

    // -------------------------------------------------------------------------
    // Test 4: nonFilteredExtensions — binary files copied byte-for-byte
    // -------------------------------------------------------------------------
    @Test
    fun testNonFilteredExtensions() {
        val sourceDir = tempFolder.newFolder("src").toPath()
        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        // Write some binary-ish bytes that would corrupt if treated as text
        val binaryContent = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        Files.write(sourceDir.resolve("logo.png"), binaryContent)

        val config = OverlayConfig(
            artifactType = ArtifactType.JAR,
            activeProfiles = emptyList(),
            mergedProperties = emptyMap(),
            webResources = emptyList(),
            resources = listOf(
                ResourceDef(
                    directory = sourceDir.toString(),
                    includes = emptyList(),
                    excludes = emptyList(),
                    filtering = true,
                    targetPath = null
                )
            ),
            nonFilteredExtensions = setOf("png"),
            filterDeploymentDescriptors = false,
            projectBasedir = tempFolder.root.toPath(),
            artifactOutputPath = artifactOutput
        )

        ResourceProcessor(config).processResources()

        val outputBytes = Files.readAllBytes(artifactOutput.resolve("logo.png"))
        assertArrayEquals(binaryContent, outputBytes)
    }

    // -------------------------------------------------------------------------
    // Test 5: UTF-8 encoding with Romanian characters survives filtering
    // -------------------------------------------------------------------------
    @Test
    fun testUtf8Encoding() {
        val metaInfDir = tempFolder.newFolder("src", "META-INF").toPath()
        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        // Copy the real persistence.xml which contains Romanian "Configurație specială"
        val sourcePersistence = PROJECTS_BASE.resolve("minimal-jar/src/main/resources/META-INF/persistence.xml")
        Files.copy(sourcePersistence, metaInfDir.resolve("persistence.xml"), StandardCopyOption.REPLACE_EXISTING)

        val srcDir = tempFolder.root.toPath().resolve("src")
        val config = OverlayConfig(
            artifactType = ArtifactType.JAR,
            activeProfiles = listOf("dev"),
            mergedProperties = mapOf(
                "hibernate.show_sql" to "true",
                "hibernate.hbm2ddl_auto" to "update"
            ),
            webResources = emptyList(),
            resources = listOf(
                ResourceDef(
                    directory = srcDir.toString(),
                    includes = emptyList(),
                    excludes = emptyList(),
                    filtering = true,
                    targetPath = null
                )
            ),
            nonFilteredExtensions = emptySet(),
            filterDeploymentDescriptors = false,
            projectBasedir = tempFolder.root.toPath(),
            artifactOutputPath = artifactOutput
        )

        ResourceProcessor(config).processResources()

        val outputContent = readText(artifactOutput.resolve("META-INF/persistence.xml"))
        assertTrue(
            "Romanian characters should be preserved in output",
            outputContent.contains("Configurație specială")
        )
    }

    // -------------------------------------------------------------------------
    // Test 6: missing property left as-is with ${...} unchanged
    // -------------------------------------------------------------------------
    @Test
    fun testMissingProperty_LeftAsIs() {
        val sourceDir = tempFolder.newFolder("src").toPath()
        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val inputText = "value=\${unknown.prop}\n"
        Files.writeString(sourceDir.resolve("test.properties"), inputText, StandardCharsets.UTF_8)

        val config = OverlayConfig(
            artifactType = ArtifactType.JAR,
            activeProfiles = emptyList(),
            mergedProperties = emptyMap(),
            webResources = emptyList(),
            resources = listOf(
                ResourceDef(
                    directory = sourceDir.toString(),
                    includes = emptyList(),
                    excludes = emptyList(),
                    filtering = true,
                    targetPath = null
                )
            ),
            nonFilteredExtensions = emptySet(),
            filterDeploymentDescriptors = false,
            projectBasedir = tempFolder.root.toPath(),
            artifactOutputPath = artifactOutput
        )

        ResourceProcessor(config).processResources()

        val outputText = readText(artifactOutput.resolve("test.properties"))
        assertTrue(
            "Missing property should remain as \${unknown.prop} in output",
            outputText.contains("\${unknown.prop}")
        )
    }

    // -------------------------------------------------------------------------
    // Test 7: empty includes list means all files are processed
    // -------------------------------------------------------------------------
    @Test
    fun testEmptyIncludes_AllFilesProcessed() {
        val sourceDir = tempFolder.newFolder("src").toPath()
        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        Files.writeString(sourceDir.resolve("a.xml"), "<root>one</root>", StandardCharsets.UTF_8)
        Files.writeString(sourceDir.resolve("b.properties"), "key=value", StandardCharsets.UTF_8)
        Files.writeString(sourceDir.resolve("c.txt"), "hello", StandardCharsets.UTF_8)

        val config = OverlayConfig(
            artifactType = ArtifactType.JAR,
            activeProfiles = emptyList(),
            mergedProperties = emptyMap(),
            webResources = emptyList(),
            resources = listOf(
                ResourceDef(
                    directory = sourceDir.toString(),
                    includes = emptyList(),
                    excludes = emptyList(),
                    filtering = false,
                    targetPath = null
                )
            ),
            nonFilteredExtensions = emptySet(),
            filterDeploymentDescriptors = false,
            projectBasedir = tempFolder.root.toPath(),
            artifactOutputPath = artifactOutput
        )

        ResourceProcessor(config).processResources()

        assertTrue("a.xml should be in output", Files.exists(artifactOutput.resolve("a.xml")))
        assertTrue("b.properties should be in output", Files.exists(artifactOutput.resolve("b.properties")))
        assertTrue("c.txt should be in output", Files.exists(artifactOutput.resolve("c.txt")))
    }

    // -------------------------------------------------------------------------
    // Test 8: excludes pattern — .properties files not copied, .xml files are
    // -------------------------------------------------------------------------
    @Test
    fun testExcludesPattern() {
        val sourceDir = tempFolder.newFolder("src").toPath()
        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        Files.writeString(sourceDir.resolve("config.properties"), "key=value", StandardCharsets.UTF_8)
        Files.writeString(sourceDir.resolve("beans.xml"), "<beans/>", StandardCharsets.UTF_8)

        val config = OverlayConfig(
            artifactType = ArtifactType.JAR,
            activeProfiles = emptyList(),
            mergedProperties = emptyMap(),
            webResources = emptyList(),
            resources = listOf(
                ResourceDef(
                    directory = sourceDir.toString(),
                    includes = emptyList(),
                    excludes = listOf("**/*.properties"),
                    filtering = false,
                    targetPath = null
                )
            ),
            nonFilteredExtensions = emptySet(),
            filterDeploymentDescriptors = false,
            projectBasedir = tempFolder.root.toPath(),
            artifactOutputPath = artifactOutput
        )

        ResourceProcessor(config).processResources()

        assertFalse(
            "config.properties should be excluded from output",
            Files.exists(artifactOutput.resolve("config.properties"))
        )
        assertTrue(
            "beans.xml should be present in output",
            Files.exists(artifactOutput.resolve("beans.xml"))
        )
    }

    // -------------------------------------------------------------------------
    // Test 9: filterDeploymentDescriptors enabled — web.xml and jboss-web.xml substituted
    // -------------------------------------------------------------------------
    @Test
    fun testFilterDeploymentDescriptors_Enabled() {
        val artifactOutput = tempFolder.newFolder("artifact").toPath()
        val webInfOutput = artifactOutput.resolve("WEB-INF")
        Files.createDirectories(webInfOutput)

        // Copy the minimal-war deployment descriptors to the output WEB-INF
        val srcWebInf = PROJECTS_BASE.resolve("minimal-war/src/main/webapp/WEB-INF")
        Files.copy(srcWebInf.resolve("web.xml"), webInfOutput.resolve("web.xml"), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(srcWebInf.resolve("jboss-web.xml"), webInfOutput.resolve("jboss-web.xml"), StandardCopyOption.REPLACE_EXISTING)

        val warProps = mapOf(
            "app.display.name" to "MyApp Dev",
            "session.timeout" to "60",
            "jbossweb.context" to "myapp-dev"
        )
        val config = OverlayConfig(
            artifactType = ArtifactType.WAR,
            activeProfiles = listOf("dev"),
            mergedProperties = warProps,
            webResources = emptyList(),
            resources = emptyList(),
            nonFilteredExtensions = emptySet(),
            filterDeploymentDescriptors = true,
            projectBasedir = PROJECTS_BASE.resolve("minimal-war"),
            artifactOutputPath = artifactOutput
        )

        ResourceProcessor(config).filterDeploymentDescriptors()

        val expectedWebInf = EXPECTED_BASE.resolve("minimal-war/dev/WEB-INF")
        val actualWebXml = readText(webInfOutput.resolve("web.xml"))
        val actualJbossXml = readText(webInfOutput.resolve("jboss-web.xml"))
        val expectedWebXml = readText(expectedWebInf.resolve("web.xml"))
        val expectedJbossXml = readText(expectedWebInf.resolve("jboss-web.xml"))

        assertEquals("web.xml should be filtered with dev properties", expectedWebXml, actualWebXml)
        assertEquals("jboss-web.xml should be filtered with dev properties", expectedJbossXml, actualJbossXml)
    }

    // -------------------------------------------------------------------------
    // Test 10: filterDeploymentDescriptors disabled — files remain unchanged
    // -------------------------------------------------------------------------
    @Test
    fun testFilterDeploymentDescriptors_Disabled() {
        val artifactOutput = tempFolder.newFolder("artifact").toPath()
        val webInfOutput = artifactOutput.resolve("WEB-INF")
        Files.createDirectories(webInfOutput)

        val srcWebInf = PROJECTS_BASE.resolve("minimal-war/src/main/webapp/WEB-INF")
        Files.copy(srcWebInf.resolve("web.xml"), webInfOutput.resolve("web.xml"), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(srcWebInf.resolve("jboss-web.xml"), webInfOutput.resolve("jboss-web.xml"), StandardCopyOption.REPLACE_EXISTING)

        val originalWebXml = readText(webInfOutput.resolve("web.xml"))
        val originalJbossXml = readText(webInfOutput.resolve("jboss-web.xml"))

        val config = OverlayConfig(
            artifactType = ArtifactType.WAR,
            activeProfiles = listOf("dev"),
            mergedProperties = mapOf(
                "app.display.name" to "MyApp Dev",
                "session.timeout" to "60",
                "jbossweb.context" to "myapp-dev"
            ),
            webResources = emptyList(),
            resources = emptyList(),
            nonFilteredExtensions = emptySet(),
            filterDeploymentDescriptors = false,
            projectBasedir = PROJECTS_BASE.resolve("minimal-war"),
            artifactOutputPath = artifactOutput
        )

        ResourceProcessor(config).filterDeploymentDescriptors()

        assertEquals("web.xml should be unchanged", originalWebXml, readText(webInfOutput.resolve("web.xml")))
        assertEquals("jboss-web.xml should be unchanged", originalJbossXml, readText(webInfOutput.resolve("jboss-web.xml")))
    }

    // -------------------------------------------------------------------------
    // Test 11: missing source directory — warn and skip, no exception
    // -------------------------------------------------------------------------
    @Test
    fun testMissingSourceDirectory_Skipped() {
        val artifactOutput = tempFolder.newFolder("artifact").toPath()

        val config = OverlayConfig(
            artifactType = ArtifactType.JAR,
            activeProfiles = emptyList(),
            mergedProperties = emptyMap(),
            webResources = emptyList(),
            resources = listOf(
                ResourceDef(
                    directory = "/nonexistent/path/that/does/not/exist",
                    includes = emptyList(),
                    excludes = emptyList(),
                    filtering = true,
                    targetPath = null
                )
            ),
            nonFilteredExtensions = emptySet(),
            filterDeploymentDescriptors = false,
            projectBasedir = tempFolder.root.toPath(),
            artifactOutputPath = artifactOutput
        )

        // Must not throw any exception
        ResourceProcessor(config).processResources()
        // If we get here without exception, test passes
    }
}
